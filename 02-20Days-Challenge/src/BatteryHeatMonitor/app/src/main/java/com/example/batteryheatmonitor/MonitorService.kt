package com.example.batteryheatmonitor

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
 * 默认每 5 分钟采一条：电量% + 温度℃ + 充电状态，写入 SampleStore 并广播刷新界面。
 *
 * 关键修复（相较旧版）：
 * 旧版唤醒锁带 10 分钟超时，超时释放后设备休眠，Handler 的周期回调被冻结，
 * 导致只采到 0min 一条、之后 30 分钟都没有记录。
 * 新版改为「监测期间全程持有不超时唤醒锁」，保证周期内 CPU 不休眠、回调按时触发；
 * 会话起点与参数持久化到 SharedPreferences，服务被系统重建后 elapsedMin 不归零。
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
    }

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var scenario = "待机"
    private var intervalMs = 5 * 60 * 1000L
    private var provider: TemperatureProvider = SystemBatteryTempProvider()
    private var startTimestamp = 0L
    private val seq = AtomicLong(0)

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
        intervalMs = if (freshStart) {
            intent!!.getLongExtra(EXTRA_INTERVAL_MS, 5 * 60 * 1000L)
        } else {
            prefs.getLong(KEY_INTERVAL, 5 * 60 * 1000L)
        }
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
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "BatteryHeatMonitor::monitor"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }

        thread = HandlerThread("MonitorThread").also { it.start() }
        handler = Handler(thread!!.looper)
        handler?.post { sampleOnce() }
        scheduleNext()

        return START_STICKY
    }

    private fun scheduleNext() {
        handler?.postDelayed({
            sampleOnce()
            scheduleNext()
        }, intervalMs)
    }

    private fun sampleOnce() {
        val now = System.currentTimeMillis()
        val elapsedMin = ((now - startTimestamp) / 60000).toInt()
        val (pct, charging) = readBattery()
        val temp = provider.getTemperatureC(this) ?: 0f
        val sample = Sample(
            id = seq.incrementAndGet(),
            scenario = scenario,
            elapsedMin = elapsedMin,
            batteryPct = pct,
            tempC = temp,
            charging = charging,
            note = "",
            timestamp = now
        )
        SampleStore.add(this, sample)

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(1, buildNotification("电量 $pct% · ${String.format("%.1f", temp)}℃ · ${elapsedMin}min"))
        sendBroadcast(Intent(ACTION_NEW_SAMPLE))
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
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        stopForeground(true)
        super.onDestroy()
    }
}
