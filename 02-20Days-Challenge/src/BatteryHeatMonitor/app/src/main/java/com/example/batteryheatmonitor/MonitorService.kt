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
 * v1.3 关键修正：区分「用户主动点开始监测」与「系统因 START_STICKY 重投递 / 看门狗唤起而重启服务」。
 * 旧版用 intent.hasExtra(EXTRA_SCENARIO) 判定全新会话，但 START_STICKY 会把最后一次带该 extra 的
 * intent 重新投递，导致进程被杀后重启被误判为「新会话」→ 反复采 0min 残片、网格永远积不满。
 * 现改用持久化标记 [KEY_FORCE_FRESH]（仅用户点击开始时由 MainActivity 置真）判定，且消费后立即清除，
 * 任何后续重启红投递都不会再触发 0min 起点；另用 [MAX_SESSION_MS] 防止续接过度陈旧的起点。
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
        /** 仅用户主动点"开始监测"时由 MainActivity 置真；系统重启 / 看门狗唤起时已被消费清除，用于区分"全新会话"与"续接旧会话" */
        internal const val KEY_FORCE_FRESH = "force_fresh"
        /** 会话超过此时长（毫秒）视为过期：重启时即便 force_fresh 残留也开新会话，而非续接陈旧的起点 */
        private const val MAX_SESSION_MS = 6L * 60 * 60 * 1000

        /** 看门狗闹钟在网格点之后多少毫秒触发（给 Handler 留出先触发的机会） */
        private const val WATCHDOG_DELAY_MS = 60_000L
        /** 单次采样迟于网格点超过该值，就在备注里如实标注延迟 */
        private const val LATE_NOTE_THRESHOLD_MS = 90_000L
        /** 去重窗口：30 秒内已有一条采样则跳过（Handler 与看门狗双路触发保护） */
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

        // v1.3：是否"全新会话"不再看 intent 是否带 EXTRA_SCENARIO（START_STICKY 会重投递该 intent 造成误判），
        // 而是看 MainActivity 在用户点击开始时写入的持久化标记 KEY_FORCE_FRESH，且消费后立即清除，
        // 使任何后续系统重启 / 看门狗唤起都不会再触发 0min 起点；会话过期(>MAX_SESSION_MS)也强制开新会话。
        val forceFreshFlag = prefs.getBoolean(KEY_FORCE_FRESH, false)
        val priorStart = prefs.getLong(KEY_START, 0L)
        // 仅当确实存在"上一会话"起点时才谈得上过期；首启 KEY_START 为 0，不能误判过期
        val sessionExpired = priorStart > 0 &&
            (System.currentTimeMillis() - priorStart) > MAX_SESSION_MS
        val freshStart = forceFreshFlag && !sessionExpired
        // 消费标记：无论是否真的开新会话，都清除它，杜绝重启红投递二次触发
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

        // 全程持有不超时唤醒锁：监测期间 CPU 不休眠，周期回调才能按时触发
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
        // 同一时间只保留一条调度链：清掉旧链（可能是被看门狗唤起前的残留）再重排
        handler?.removeCallbacksAndMessages(null)
        tickIndex = 0
        // 采样与文件 IO 全部放到 handler 线程，主线程只做状态装配
        handler?.post { kickOff(freshStart) }

        return START_STICKY
    }

    /**
     * 排定采样节奏（v1.3 引入 force_fresh 防 0min 残片；v1.4 保证重启后不漏格）：
     * - 全新会话：先采 0min 起点，再由 scheduleNext 从第 1 个网格点开始；
     * - 被系统重建（START_STICKY 重投递 / 看门狗唤起）：从「已采样本最大 elapsedMin」之后续接，
     *   **一次性把已到期却未采的网格点补采满**（每个点 elapsedMin 由 index 决定、互不相同，
     *   不会被去重误删），再正常排程后续点；已采满 30min 目标网格则停止排程。
     */
    private fun kickOff(freshStart: Boolean) {
        if (freshStart) {
            sampleOnce(0)
            scheduleNext()
            return
        }
        val intervalMin = intervalMs / 60000  // 5（分钟）
        val lastIdx = (intervalMin * 6).toInt()  // 30min 目标对应 index 6
        val maxElapsed = SampleStore.loadAll(this)
            .filter { it.scenario == scenario }
            .maxOfOrNull { it.elapsedMin } ?: -1
        if (maxElapsed >= lastIdx * intervalMin) {
            // 已采满 30min 目标网格，停止后续排程（防止重启后越界重采已采点）
            return
        }
        val startIdx = (maxElapsed / intervalMin).toInt() + 1
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

    /** 绝对网格调度：下一个采样时刻 = 起点 + (tickIndex+1)×间隔，单次回调早晚不累积漂移 */
    private fun scheduleNext() {
        tickIndex++
        val nextTick = startTimestamp + tickIndex * intervalMs
        val delay = (nextTick - System.currentTimeMillis()).coerceAtLeast(0L)
        handler?.postDelayed({ onTick() }, delay)
        armWatchdog(nextTick)
    }

    private fun onTick() {
        cancelWatchdog()
        sampleOnce(tickIndex)
        scheduleNext()
    }

    private fun sampleOnce(index: Int) {
        val now = System.currentTimeMillis()
        // 网格点位置由 index 决定，elapsedMin 始终对齐 5 分钟网格（0/5/10…），
        // 不受"进程被杀后补采"的墙钟时间影响——保证每 5 分钟一格规整、不漏、不重
        val elapsedMin = (index * intervalMs / 60000).toInt()
        // 去重：仅当"同一网格点(elapsedMin 相同) 且 30 秒内"才跳过（Handler/看门狗双路触发同一点）；
        // 不同网格点的补采互不干扰，确保进程被杀后一次性/级联补采不漏格
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

    // ---------- 看门狗：进程被杀后由系统闹钟唤起补采 ----------

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

    private fun armWatchdog(nextTick: Long) {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // setAndAllowWhileIdle 无需精确闹钟权限，息屏 / Doze 下仍会被系统唤起
        am.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            nextTick + WATCHDOG_DELAY_MS,
            tickPendingIntent()
        )
    }

    private fun cancelWatchdog() {
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
        cancelWatchdog()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
        stopForeground(true)
        super.onDestroy()
    }
}
