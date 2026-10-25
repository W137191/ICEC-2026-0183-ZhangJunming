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
 * 关键设计（v1.2，修 10-25 实测暴露的定时断档问题）：
 * 旧版用 postDelayed 递归排下一次采样，进程一旦被系统回收，Handler 链随之死亡，
 * START_STICKY 重启时机不定，实测出现 7~45 分钟采样空档与"重启 0min 残片"。
 * 新版三级保障：
 *   1. 绝对网格调度：下一次采样时刻 = 会话起点 + N×间隔（墙上时钟），
 *      单次回调早晚不影响后续网格点，杜绝漂移累积；
 *   2. 看门狗闹钟：每个网格点在 AlarmManager 挂 RTC_WAKEUP 闹钟，进程被杀后
 *      由系统唤起 TickReceiver 重新拉起服务补采，采样空档不超过看门狗余量；
 *   3. 重启不盲采：服务被重建时只在网格点真正到期才补采一条，elapsedMin 按
 *      墙上时钟计算，迟于网格点超过 90s 会在备注如实标注"定时延迟Xs"。
 * 采样前去重（30 秒内已有采样则跳过），防止 Handler 与看门狗双路触发重复记数。
 */
class MonitorService : Service() {

    companion object {
        const val EXTRA_SCENARIO = "scenario"
        const val EXTRA_INTERVAL_MS = "interval_ms"
        const val EXTRA_MANUAL_TEMP = "manual_temp" // 用 -1f 表示未设置
        const val ACTION_NEW_SAMPLE = "com.example.batteryheatmonitor.NEW_SAMPLE"
        const val CHANNEL_ID = "battery_monitor"

        private const val PREFS = "monitor_session"
        private const val KEY_START = "start_ts"
        private const val KEY_SCENARIO = "scenario"
        private const val KEY_INTERVAL = "interval_ms"
        private const val KEY_MANUAL = "manual_temp"

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

        // 读取参数：优先用本次启动传入的，否则（服务被系统重建、intent 为空）从持久化恢复
        val freshStart = intent?.hasExtra(EXTRA_SCENARIO) == true
        scenario = if (freshStart) {
            intent!!.getStringExtra(EXTRA_SCENARIO) ?: "待机"
        } else {
            prefs.getString(KEY_SCENARIO, "待机") ?: "待机"
        }
        intervalMs = (
            if (freshStart) {
                intent!!.getLongExtra(EXTRA_INTERVAL_MS, 5 * 60 * 1000L)
            } else {
                prefs.getLong(KEY_INTERVAL, 5 * 60 * 1000L)
            }
            ).coerceAtLeast(MIN_INTERVAL_MS)
        val manual = if (freshStart) {
            intent!!.getFloatExtra(EXTRA_MANUAL_TEMP, -1f)
        } else {
            prefs.getFloat(KEY_MANUAL, -1f)
        }
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
     * 排定采样节奏：
     * - 全新会话：先采 0min 起点，再从第 1 个网格点开始；
     * - 被系统重建：不盲采起点（消除"重启 0min 残片"），仅当网格点已到期时补采一条。
     */
    private fun kickOff(freshStart: Boolean) {
        if (freshStart) {
            sampleOnce(0)
        } else {
            catchUpIfDue()
        }
        scheduleNext()
    }

    /** 重建后补采：已到期的网格点补一条（elapsedMin 反映真实间隔，延迟写进备注） */
    private fun catchUpIfDue() {
        val now = System.currentTimeMillis()
        val due = ((now - startTimestamp) / intervalMs).toInt()
        if (due >= 1) {
            sampleOnce(due)
            tickIndex = due
        }
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
        // 去重：Handler 与看门狗可能双路触发，30 秒内已有采样则跳过
        val last = SampleStore.loadAll(this).maxByOrNull { it.timestamp }
        if (last != null && now - last.timestamp < DEDUPE_WINDOW_MS) return

        val elapsedMin = ((now - startTimestamp) / 60000).toInt()
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
