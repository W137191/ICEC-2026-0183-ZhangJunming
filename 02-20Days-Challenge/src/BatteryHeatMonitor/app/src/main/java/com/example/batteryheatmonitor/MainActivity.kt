package com.example.batteryheatmonitor

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.batteryheatmonitor.model.Sample
import com.example.batteryheatmonitor.storage.SampleStore
import com.example.batteryheatmonitor.sensing.SensingActivity
import com.example.batteryheatmonitor.ui.LineChartView
import com.example.batteryheatmonitor.util.CsvExporter
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var tvBattery: TextView
    private lateinit var tvTemp: TextView
    private lateinit var tvCharging: TextView
    private lateinit var tvStatus: TextView
    private lateinit var chart: LineChartView
    private lateinit var listSamples: TextView
    private lateinit var etManualTemp: EditText
    private lateinit var btnStartStop: Button

    private var selectedScenario = "待机"
    private var monitoring = false
    private var manualTemp: Float? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val pct = if (level >= 0 && scale > 0) level * 100 / scale else 0
            val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0f
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            tvBattery.text = "$pct%"
            tvTemp.text = String.format("%.1f℃", temp)
            tvCharging.text = if (charging) "充电中" else "放电"
        }
    }

    private val sampleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refreshList()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvBattery = findViewById(R.id.tvBattery)
        tvTemp = findViewById(R.id.tvTemp)
        tvCharging = findViewById(R.id.tvCharging)
        tvStatus = findViewById(R.id.tvStatus)
        chart = findViewById(R.id.chart)
        listSamples = findViewById(R.id.listSamples)
        etManualTemp = findViewById(R.id.etManualTemp)
        btnStartStop = findViewById(R.id.btnStartStop)

        setupScenarioButtons()
        setupManualTemp()
        setupStartStop()
        setupExportClear()
        setupSensingEntry()
        refreshList()
    }

    private fun setupScenarioButtons() {
        val map = mapOf(
            R.id.btnStandby to "待机",
            R.id.btnVideo to "短视频",
            R.id.btnNav to "导航拍摄",
            R.id.btnGame to "高帧率游戏"
        )
        val buttons = map.keys.map { findViewById<Button>(it) }
        fun paint() = buttons.forEach { b ->
            val sel = map[b.id] == selectedScenario
            b.setBackgroundColor(if (sel) 0xFF03DAC5.toInt() else 0xFF1A2029.toInt())
            b.setTextColor(if (sel) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        }
        buttons.forEach { b ->
            b.setOnClickListener {
                if (!monitoring) {
                    selectedScenario = map[b.id]!!
                    paint()
                    refreshList() // 切换工况后同步刷新图表与状态栏
                } else {
                    Toast.makeText(this, "监测中不可切换工况，先停止", Toast.LENGTH_SHORT).show()
                }
            }
        }
        paint()
    }

    private fun setupManualTemp() {
        findViewById<Button>(R.id.btnApplyManual).setOnClickListener {
            val t = etManualTemp.text.toString().trim()
            manualTemp = if (t.isEmpty()) null else t.toFloatOrNull()
            val msg = if (manualTemp == null) "已恢复使用传感器读数"
            else "温度兜底值：${manualTemp}℃（下次开始时生效）"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupStartStop() {
        btnStartStop.setOnClickListener {
            if (!monitoring) startMonitoring() else stopMonitoring()
        }
    }

    private fun startMonitoring() {
        // 充电状态下测"掉电"会失真（电量可能不降反升），开测前检查并提醒
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val sticky = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            registerReceiver(null, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(null, filter)
        }
        val charging = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)?.let {
            it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL
        } ?: false
        if (charging) {
            Toast.makeText(this, "⚠ 手机正在充电，掉电数据会失真，建议拔掉充电器再测", Toast.LENGTH_LONG).show()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100
            )
        }
        val intent = Intent(this, MonitorService::class.java).apply {
            putExtra(MonitorService.EXTRA_SCENARIO, selectedScenario)
            putExtra(MonitorService.EXTRA_INTERVAL_MS, 5 * 60 * 1000L)
            putExtra(MonitorService.EXTRA_MANUAL_TEMP, manualTemp ?: -1f)
        }
        // v1.3：在真正拉起服务前，把"全新会话"标记写入持久化，供 MonitorService 区分
        // 用户主动开始 vs 系统因 START_STICKY 重投递而重启（后者不会带此标记，不会误采 0min 残片）。
        getSharedPreferences(MonitorService.PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(MonitorService.KEY_FORCE_FRESH, true).apply()
        ContextCompat.startForegroundService(this, intent)
        monitoring = true
        btnStartStop.text = "停止监测"
        btnStartStop.setBackgroundColor(0xFFFF5252.toInt())
        tvStatus.text = "监测中 · 工况：$selectedScenario · 每5分钟采样"
    }

    private fun stopMonitoring() {
        // v1.3：清除"全新会话"标记，确保停止后即便服务被系统重启也不会误采 0min 残片
        getSharedPreferences(MonitorService.PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(MonitorService.KEY_FORCE_FRESH, false).apply()
        stopService(Intent(this, MonitorService::class.java))
        monitoring = false
        btnStartStop.text = "开始监测（每5分钟采样）"
        btnStartStop.setBackgroundColor(0xFF03DAC5.toInt())
        tvStatus.text = "已停止"
    }

    private fun setupSensingEntry() {
        findViewById<Button>(R.id.btnSensing).setOnClickListener {
            startActivity(Intent(this, SensingActivity::class.java))
        }
        // 课题06 噪声测量（替代市面分贝仪：可导出 dB 时间序列 CSV）
        findViewById<Button>(R.id.btnNoise).setOnClickListener {
            startActivity(Intent(this, com.example.batteryheatmonitor.sensing.NoiseActivity::class.java))
        }
    }

    private fun setupExportClear() {
        findViewById<Button>(R.id.btnExport).setOnClickListener {
            val file = CsvExporter.export(this)
            startActivity(Intent.createChooser(CsvExporter.shareIntent(this, file), "导出CSV"))
            Toast.makeText(this, "已保存到：${file.absolutePath}", Toast.LENGTH_LONG).show()
        }
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            SampleStore.clear(this)
            refreshList()
            Toast.makeText(this, "记录已清空", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnRemoveLatest).setOnClickListener {
            val removed = SampleStore.removeLatest(this)
            if (removed == null) {
                Toast.makeText(this, "没有可删除的记录", Toast.LENGTH_SHORT).show()
            } else {
                refreshList()
                Toast.makeText(
                    this,
                    "已删除最新一条：${removed.scenario} ${removed.elapsedMin}min ${removed.batteryPct}%",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun refreshList() {
        val all = SampleStore.loadAll(this)
        // 图表只画当前选中工况，避免 4 种工况的曲线混在一张图里看不清
        val list = all.filter { it.scenario == selectedScenario }
        chart.setSamples(list)
        val sdf = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)
        listSamples.text = all.joinToString("\n") {
            "${it.scenario} ${it.elapsedMin}min | ${it.batteryPct}% | ${String.format("%.1f", it.tempC)}℃ | ${sdf.format(Date(it.timestamp))}"
        }
        // 课题02 每工况目标 6 条（0/5/10/15/20/25/30min），进度可见才知何时采满
        tvStatus.text = if (monitoring) "监测中 · 工况：$selectedScenario · 本工况 ${list.size}/6 条"
        else "未开始 · 共 ${all.size} 条（当前工况 ${list.size}/6 条）"
    }

    override fun onResume() {
        super.onResume()
        // Android 14+ 必须显式声明接收器是否导出，否则 registerReceiver 抛 IllegalArgumentException 直接崩
        ContextCompat.registerReceiver(
            this, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this, sampleReceiver, IntentFilter(MonitorService.ACTION_NEW_SAMPLE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(batteryReceiver)
        unregisterReceiver(sampleReceiver)
    }
}
