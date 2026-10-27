package com.example.batteryheatmonitor

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.batteryheatmonitor.monitor.ManualTempProvider
import com.example.batteryheatmonitor.monitor.SystemBatteryTempProvider
import com.example.batteryheatmonitor.monitor.TemperatureProvider
import com.example.batteryheatmonitor.model.Sample
import com.example.batteryheatmonitor.storage.SampleStore
import java.util.concurrent.atomic.AtomicLong

/**
 * 前台采样服务：让监测在熄屏 / 切到其它 App（刷视频、打游戏）时持续进行。
 * 每 5 分钟采一条：电量% + 温度℃ + 充电状态，写入 SampleStore 并广播刷新界面。
 *
 * v1.5 关键修正（「每 5 分钟一格不漏」真正落地）：
 * v1.4 的主调度是 Handler.postDelayed，仅用「晚 60 秒的 setAndAllowWhileIdle 看门狗」兜底。
 * 但手机熄屏进入 Doze 后，Android 会把这种「非精确」闹钟推迟到维护窗口，
 * 于是出现数十分钟的采样空档（遗留数据里 39/11/13/29 分钟的跳跃即由此而来）。
 *
 * 现改为【精确闹钟作主调度】：每个网格点用 AlarmManager.setExactAndAllowWhileIdle 直接挂在该时刻，
 * 这是「即便息屏/Doze 也由系统准时唤起」的官方推荐做法——闹钟由系统持有，进程被杀也不丢，
 * 到点即拉起服务补采，每 5 分钟一格规整、不漏、不重复。原 v1.4 的「重启续接补满缺格」逻辑保留作安全网。
 *
 * v1.3 引入的 KEY_FORCE_FRESH：区分「用户主动点开始」与「系统因 START_STICKY 重投递 / 闹钟唤起而重启」，
 * 杜绝重启被误判为「新会话」→ 反复采 0min 残片。
 *
 * v1.6 关键修正（「用户点开始却整场不采样」实机回归，2026-10-27 00:15 复现）：
 * 用户点击开始时若本地残留超过 6 小时的旧起点（KEY_START），v1.5 的 MAX_SESSION_MS 过期判定
 * 会把用户的「全新会话」误判成「续接旧会话」→ kickOff 立刻一次性补满全部 6 个网格点
 * （同一瞬间读数，六条数据完全相同）且网格随即判满、不再排程 → 整场监测温度/时间纹丝不动。
 * 现改为：① 用户主动点开始**永远**开新会话（过期判定只约束系统重启续接）；
 * ② 续接时若会话已过期则不补采、不排程，直接停服；
 * ③ maxElapsed 只统计**本会话**（timestamp ≥ 起点）的样本，杜绝同工况上一场旧数据
 * 把网格顶满 30 导致本场从头到尾不再采样。
 */
class MonitorService : Service() {

    companion object {
        const val EXTRA_SCENARIO = "scenario"
        const val EXTRA_INTERVAL_MS = "interval_ms"
        const val EXTRA_MANUAL_TEMP = "manual_temp" // 用 -1f 表示未设置
        const val ACTION_NEW_SAMPLE = "com.example.batteryheatmonitor.NEW_SAMPLE"
        const val CHANNEL_ID = "battery_monitor"

        internal const val PREFS = "monitor_session"
        private const val KEY_START = "start_ts"
        private const val KEY_SCENARIO = "scenario"
        private const val KEY_INTERVAL = "interval_ms"
        private const val KEY_MANUAL = "manual_temp"
        /** 仅用户主动点"开始监测"时由 MainActivity 置真；系统重启 / 闹钟唤起时已被消费清除，用于区分"全新会话"与"续接旧会话" */
        internal const val KEY_FORCE_FRESH = "force_fresh"
        /** 会话超过此时长（毫秒）视为过期：重启时即便 force_fresh 残留也开新会话，而非续接陈旧的起点 */
        private const val MAX_SESSION_MS = 6L * 60 * 60 * 1000

        /** 30 分钟目标时长内的网格点数：0,5,10,15,20,25,30min → 索引 0..6 共 7 个点 */
        private const val GRID_POINTS = 7
        /** 单次采样迟于网格点超过该值，就在备注里如实标注延迟（用于暴露系统仍推迟闹钟的情况） */
        private const val LATE_NOTE_THRESHOLD_MS = 90_000L
        /** 去重窗口：30 秒内已有一条采样则跳过（闹钟 / 重启双路触发同一点保护） */
        private const val DEDUPE_WINDOW_MS = 30_000L
        /** 间隔下限保护，避免非法参数导致除零 / 密集采样 */
        private const val MIN_INTERVAL_MS = 60_000L
    }

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var scenario = "待机"
    private var intervalMs = 5 * 60 * 1000L
    private var provider: TemperatureProvider = SystemBatteryTempProvider()
    private var startTimestamp = 0L
    private val seq = AtomicLong(0)

    /** 已排到的网格点序号（0 = 起点采样，1 = 第 1 个 5min 点……） */
    private var tickIndex = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // 是否"全新会话"看 MainActivity 在用户点击开始时写入的持久化标记 KEY_FORCE_FRESH，
        // 且消费后立即清除，使任何后续系统重启 / 闹钟唤起都不会再触发 0min 起点；会话过期也强制开新会话。
        val forceFreshFlag = prefs.getBoolean(KEY_FORCE_FRESH, false)
        val priorStart = prefs.getLong(KEY_START, 0L)
        val sessionExpired = priorStart > 0 &&
            (System.currentTimeMillis() - priorStart) > MAX_SESSION_MS
        // v1.6：用户主动点开始永远开新会话——过期判定只约束"系统重启续接"，
        // 绝不能反过来吞掉用户的全新会话（v1.5 实机回归：残留旧起点 >6h 时，
        // 点开始被当续接 → 一瞬间补满 6 格且不再排程，整场温度/时间不动）
        val freshStart = forceFreshFlag
        val resumeExpired = !forceFreshFlag && sessionExpired
        if (forceFreshFlag) {
            prefs.edit().putBoolean(KEY_FORCE_FRESH, false).apply()
        }

        // 参数读取：优先用本次启动传入的 intent extra（用户点击 / 系统重投递均可能带），否则从持久化恢复
        scenario = intent?.getStringExtra(EXTRA_SCENARIO)
            ?: prefs.getString(KEY_SCENARIO, "待机") ?: "待机"
        intervalMs = (intent?.getLongExtra(EXTRA_INTERVAL_MS, -1L)?.takeIf { it > 0 }
            ?: prefs.getLong(KEY_INTERVAL, 5 * 60 * 1000L)).coerceAtLeast(MIN_INTERVAL_MS)
        val manual = intent?.getFloatExtra(EXTRA_MANUAL_TEMP, -2f)?.takeIf { it != -2f }
            ?: prefs.getFloat(KEY_MANUAL, -1f)
        provider = if (manual >= 0f) ManualTempProvider(manual) else SystemBatteryTempProvider()

        // 全新开始时记录真实起点；重建时沿用旧起点，保证 elapsedMin 连续
        startTimestamp = if (freshStart) {
            System.currentTimeMillis().also { prefs.edit().putLong(KEY_START, it).apply() }
        } else {
            prefs.getLong(KEY_START, System.currentTimeMillis())
        }
        prefs.edit()
            .putString(KEY_SCENARIO, scenario)
            .putLong(KEY_INTERVAL, intervalMs)
            .putFloat(KEY_MANUAL, manual)
            .apply()

        // 序号从已存记录的最大值续接，避免服务重建后 id 冲突
        seq.set(SampleStore.loadAll(this).maxOfOrNull { it.id } ?: 0)

        startForeground(1, buildNotification("监测中：$scenario"))

        // 持有不超时唤醒锁：监测期间 CPU 不休眠，周期回调才能按时触发（配合下方精确闹钟双保险）
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "BatteryHeatMonitor::monitor"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }

        if (thread == null) {
            thread = HandlerThread("MonitorThread").also { it.start() }
            handler = Handler(thread!!.looper)
        }
        // 同一时间只保留一条调度链：清掉旧链（可能是被闹钟唤起前的残留）再重排
        handler?.removeCallbacksAndMessages(null)
        tickIndex = 0
        // 采样与文件 IO 全部放到 handler 线程，主线程只做状态装配
        handler?.post {
            try {
                kickOff(freshStart, resumeExpired)
            } catch (e: Exception) {
                // v1.7：任何未在采样链内部捕获的异常都在此兜底，绝不抛到线程顶层导致进程被杀（闪退）
                android.util.Log.e("MonitorService", "kickOff 异常已捕获，避免闪退：${e.message}", e)
            }
        }

        return START_STICKY
    }

    /**
     * 排定采样节奏（v1.3 force_fresh 防 0min 残片；v1.4 重启不漏格；v1.5 精确闹钟主调度；
     * v1.6 用户点开始必开新会话、续接只认本会话样本）：
     * - 全新会话：先采 0min 起点，再由 scheduleNext 从该时刻精确挂第 1 个网格点闹钟；
     * - 续接且会话已过期(>6h)：不补采陈旧网格、不排程，直接停服（v1.6）；
     * - 被系统重建（START_STICKY 重投递 / 闹钟唤起）：从「本会话已采样本最大 elapsedMin」之后
     *   续接，**一次性把已到期却未采的网格点补采满**（每个点 elapsedMin 由 index 决定、互不相同，
     *   不会被去重误删），再正常排程后续精确闹钟；已采满 30min 目标网格则停止排程。
     */
    private fun kickOff(freshStart: Boolean, resumeExpired: Boolean) {
        if (resumeExpired) {
            // v1.6：系统在会话过期(>6h)后才唤醒续接 → 本次测量早已失效，
            // 不补采陈旧网格、不再排程，直接停服等用户重新开始
            stopSelf()
            return
        }
        if (freshStart) {
            sampleOnce(0)
            scheduleNext()
            return
        }
        val intervalMin = intervalMs / 60000  // 5（分钟）
        val lastIdx = GRID_POINTS - 1  // 30min 目标对应索引 6
        // v1.6：只统计「本会话」（timestamp ≥ 起点）的样本——同工况上一场的旧样本
        // 不能把 maxElapsed 顶满 30，否则本场从头到尾都不会再采样
        val maxElapsed = SampleStore.loadAll(this)
            .filter { it.scenario == scenario && it.timestamp >= startTimestamp }
            .maxOfOrNull { it.elapsedMin } ?: -1
        if (maxElapsed >= lastIdx * intervalMin) {
            // 本会话已采满 30min 目标网格，停止后续排程（防止重启后越界重采已采点）
            return
        }
        val startIdx = if (maxElapsed < 0) 0 else (maxElapsed / intervalMin).toInt() + 1
        val nowIdx = ((System.currentTimeMillis() - startTimestamp) / intervalMs).toInt()
        val endIdx = if (nowIdx < lastIdx) nowIdx else lastIdx
        // 把「已到期却还没采」的网格点一次性补采满（index 决定 elapsedMin，互不干扰、不漏）
        for (i in startIdx..endIdx) {
            sampleOnce(i)
        }
        tickIndex = endIdx
        if (endIdx >= lastIdx) return  // 已到 30min 目标，不再排程后续点
        scheduleNext()
    }

    /**
     * 精确网格调度（v1.5 核心修复）：下一个采样时刻 = 起点 + tickIndex×间隔，
     * 用 setExactAndAllowWhileIdle 直接在该时刻挂闹钟——息屏 / Doze 下也由系统准时唤起，
     * 取代 v1.4 易被打断的 Handler.postDelayed 链与粗粒度看门狗，确保每 5 分钟一格不漏。
     * 闹钟由系统持有，进程被杀也不丢；到点唤起服务后由 kickOff 续接补采。
     *
     * v1.7 修复「点开始就闪退」：setExactAndAllowWhileIdle 需要 SCHEDULE_EXACT_ALARM 权限，
     * 而 Android 13+ / 部分 OEM 默认不授予、或用户曾在设置里关闭该权限 → 调用即抛
     * SecurityException；该异常发生在采样线程且未被捕获，默认未捕获异常会直接杀进程（闪退）。
     * 现改由 safeScheduleExact 统一处理：先查 canScheduleExactAlarms()，未授予则退化为
     * setAndAllowWhileIdle（仍 Doze 感知、不崩溃），并对任何 SecurityException 兜底同样退化，
     * 保证「开始监测」一定不崩、采样照常进行（仅极端情况下 5 分钟刻度略被 Doze 推迟）。
     */
    private fun scheduleNext() {
        tickIndex++
        // 已排到 30min 目标网格的最后一个点（索引 GRID_POINTS-1），不再挂后续闹钟
        if (tickIndex >= GRID_POINTS) return
        val nextTick = startTimestamp + tickIndex * intervalMs
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = tickPendingIntent()
        safeScheduleExact(am, nextTick, pi)
    }

    /**
     * 安全挂精确闹钟（v1.7）：优先 setExactAndAllowWhileIdle（Doze 下仍准时，每 5 分钟一格不漏）；
     * 若系统未授予 SCHEDULE_EXACT_ALARM（Android 13+ / OEM 默认不授予 / 用户曾关闭）则捕获
     * SecurityException 并退化为 setAndAllowWhileIdle（Doze 感知但不强制精确，绝不崩溃）。
     */
    private fun safeScheduleExact(am: AlarmManager, triggerAt: Long, pi: PendingIntent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    am.canScheduleExactAlarms()
                if (canExact) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                } else {
                    // 未授予精确闹钟权限：退化为 Doze 感知的非精确闹钟（仍会在维护窗口后补采）
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                }
            } catch (e: SecurityException) {
                // 权限在判断后被回收等极端情况兜底，避免闪退
                @Suppress("MissingPermission")
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } else {
            // API 21/22 无 while-idle 变体，退化为 setExact（仍远优于 postDelayed）
            @Suppress("MissingPermission")
            am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    private fun sampleOnce(index: Int) {
        val now = System.currentTimeMillis()
        // 网格点位置由 index 决定，elapsedMin 始终对齐 5 分钟网格（0/5/10…），
        // 不受"进程被杀后补采"的墙钟时间影响——保证每 5 分钟一格规整、不漏、不重
        val elapsedMin = (index * intervalMs / 60000).toInt()
        // 去重：仅当"同一网格点(elapsedMin 相同) 且 30 秒内"才跳过（闹钟 / 重启双路触发同一点）；
        // 不同网格点的补采互不干扰，确保进程被杀后一次性 / 级联补采不漏格
        val last = SampleStore.loadAll(this).maxByOrNull { it.timestamp }
        if (last != null && last.elapsedMin == elapsedMin && now - last.timestamp < DEDUPE_WINDOW_MS) return

        val lateMs = now - (startTimestamp + index.toLong() * intervalMs)
        val note = if (lateMs > LATE_NOTE_THRESHOLD_MS) {
            "定时延迟${lateMs / 1000}s(系统回收进程或定时被推迟)"
        } else ""
        val (pct, charging) = readBattery()
        val temp = provider.getTemperatureC(this) ?: 0f
        val sample = Sample(
            id = seq.incrementAndGet(),
            scenario = scenario,
            elapsedMin = elapsedMin,
            batteryPct = pct,
            tempC = temp,
            charging = charging,
            note = note,
            timestamp = now
        )
        SampleStore.add(this, sample)

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(1, buildNotification("电量 $pct% · ${String.format("%.1f", temp)}℃ · ${elapsedMin}min"))
        sendBroadcast(Intent(ACTION_NEW_SAMPLE))
    }

    // ---------- 精确闹钟：进程被杀 / 息屏后由系统唤起补采 ----------

    private fun tickPendingIntent(): PendingIntent {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(
            this, 0, Intent(this, TickReceiver::class.java), flags
        )
    }

    /** 取消尚未触发的精确闹钟（用户主动停止或采满 30min 时调用，避免闹钟日后误唤起） */
    private fun cancelTickAlarm() {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(tickPendingIntent())
    }

    private fun readBattery(): Pair<Int, Boolean> {
        val intent = ContextCompat.registerReceiver(
            this, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        ) ?: return 0 to false
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else 0
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        return pct to charging
    }

    private fun buildNotification(text: String): Notification {
        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            piFlags
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("发热掉电监测")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "电池监测", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "后台采样保活通知" }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(ch)
        }
    }

    override fun onDestroy() {
        handler?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        handler = null
        cancelTickAlarm()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
        stopForeground(true)
        super.onDestroy()
    }
}
