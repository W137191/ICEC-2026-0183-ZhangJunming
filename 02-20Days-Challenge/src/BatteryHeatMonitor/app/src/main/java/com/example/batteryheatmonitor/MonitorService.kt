package com.example.batteryheatmonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
 */
class MonitorService : Service() {

    companion object {
        const val EXTRA_SCENARIO = "scenario"
        const val EXTRA_INTERVAL_MS = "interval_ms"
        const val EXTRA_MANUAL_TEMP = "manual_temp" // 用 -1f 表示未设置
        const val ACTION_NEW_SAMPLE = "com.example.batteryheatmonitor.NEW_SAMPLE"
        const val CHANNEL_ID = "battery_monitor"
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
        scenario = intent?.getStringExtra(EXTRA_SCENARIO) ?: "待机"
        intervalMs = intent?.getLongExtra(EXTRA_INTERVAL_MS, 5 * 60 * 1000L) ?: 5 * 60 * 1000L
        val manual = intent?.getFloatExtra(EXTRA_MANUAL_TEMP, -1f) ?: -1f
        provider = if (manual >= 0f) ManualTempProvider(manual) else SystemBatteryTempProvider()
        startTimestamp = System.currentTimeMillis()

        startForeground(1, buildNotification("监测中：$scenario"))

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BatteryHeatMonitor::monitor")
        wakeLock?.acquire(intervalMs * 2)

        thread = HandlerThread("MonitorThread").also { it.start() }
        handler = Handler(thread!!.looper)
        handler?.post { sampleOnce() }
        scheduleNext()

        return START_STICKY
    }

    private fun scheduleNext() {
        // 每个周期续期唤醒锁，保证长时段（如 30 分钟待机）熄屏时也能按时采样
        wakeLock?.acquire(intervalMs * 2)
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
        wakeLock?.release()
        stopForeground(true)
        super.onDestroy()
    }
}
