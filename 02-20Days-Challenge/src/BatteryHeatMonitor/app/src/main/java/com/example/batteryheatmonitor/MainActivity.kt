package com.example.batteryheatmonitor

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.*
import com.example.batteryheatmonitor.model.Sample
import com.example.batteryheatmonitor.storage.SampleStore
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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

    // v1.9：前台保底采样——iQOO/vivo 会把后台 App 冻结（通知还亮着但进程停摆、闹钟不触发）。
    // 当 App 本身处于前台（屏幕亮、用户在看）时，用主线程 Handler 每 60 秒自查并补齐当前网格点，
    // 不依赖被冻结的后台闹钟，保证「手机亮屏 + App 开着」就能稳定每 5 分钟采样。
    // 后台（已加白名单）仍由 MonitorService 的精确/备用闹钟负责；两者通过 session-grid 去重互不重复。
    private val fgHandler = Handler(Looper.getMainLooper())
    private val fgRunnable = object : Runnable {
        override fun run() {
            if (monitoring) ensureCurrentGridSampled()
            fgHandler.postDelayed(this, 60_000L)
        }
    }

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
        // v1.7：精确闹钟权限（SCHEDULE_EXACT_ALARM）在 Android 13+ / 部分 OEM 默认不授予，
        // 缺失时虽已退化为不崩溃的 setAndAllowWhileIdle，但 5 分钟刻度可能被 Doze 推迟。
        // 这里主动引导用户去设置开启，保证每 5 分钟一格规整（开测前提醒，不阻塞开始）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            if (!am.canScheduleExactAlarms()) {
                Toast.makeText(
                    this,
                    "建议到「设置→应用→发热掉电监测→闹钟与提醒」开启精确闹钟权限，否则每5分钟采样可能被推迟",
                    Toast.LENGTH_LONG
                ).show()
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                } catch (_: Exception) { /* 个别 ROM 无此入口，忽略 */ }
            }
        }
        // v1.8：申请电池优化白名单（Doze 豁免）——未豁免时熄屏后采样闹钟可能被推迟几十分钟
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Exception) { /* 个别 ROM 无此入口，忽略 */ }
        }
        // v1.8：国产 ROM 冻结后台 App 引导——iQOO/vivo/OPPO 等会把后台进程"冻结"（通知还在、
        // 进程停摆，任何闹钟都不触发），表现为通知停在 0min、几十分钟不采样。代码无法绕过，
        // 必须引导用户加白名单（只在点名开始时提示，不阻塞开始）。
        val mfr = Build.MANUFACTURER ?: ""
        val guide = when {
            mfr.contains("vivo", true) || mfr.contains("iqoo", true) ->
                "iQOO/vivo 手机请到：设置→电池→后台高耗电→允许本App；设置→应用与权限→自启动→允许本App；并在多任务界面下拉锁定本App卡片。否则熄屏后采样会被系统冻结"
            mfr.contains("oppo", true) || mfr.contains("realme", true) || mfr.contains("oneplus", true) ->
                "OPPO/一加 手机请到：设置→电池→更多设置→关闭本App的智能后台管控；设置→应用管理→自启动→允许本App；并在多任务界面锁定本App卡片"
            mfr.contains("xiaomi", true) || mfr.contains("redmi", true) ->
                "小米/红米 手机请到：设置→应用设置→应用管理→本App→省电策略→无限制，并开启自启动"
            mfr.contains("huawei", true) || mfr.contains("honor", true) ->
                "华为/荣耀 手机请到：设置→电池→更多电池设置，应用启动管理改为手动并允许自启动+后台运行"
            else -> null
        }
        guide?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
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
        // v1.9：启动前台保底采样循环（屏幕亮、App 在前台时由 Handler 补采，抗系统冻结）
        fgHandler.removeCallbacks(fgRunnable)
        fgHandler.postDelayed(fgRunnable, 60_000L)
    }

    private fun stopMonitoring() {
        // v1.3：清除"全新会话"标记，确保停止后即便服务被系统重启也不会误采 0min 残片
        getSharedPreferences(MonitorService.PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(MonitorService.KEY_FORCE_FRESH, false).apply()
        stopService(Intent(this, MonitorService::class.java))
        monitoring = false
        fgHandler.removeCallbacks(fgRunnable)
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
        val lastTs = all.maxOfOrNull { it.timestamp } ?: 0
        val ageText = if (monitoring && lastTs > 0) {
            val age = (System.currentTimeMillis() - lastTs) / 1000
            " · 上次采样 ${age}s 前"
        } else ""
        tvStatus.text = if (monitoring) "监测中 · 工况：$selectedScenario · 本工况 ${list.size}/6 条$ageText"
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
        // v1.9：回到前台且正在监测时，恢复前台保底采样循环
        if (monitoring) {
            fgHandler.removeCallbacks(fgRunnable)
            fgHandler.postDelayed(fgRunnable, 60_000L)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(batteryReceiver)
        unregisterReceiver(sampleReceiver)
        // v1.9：离开前台即停掉保底循环（后台交由 MonitorService 的闹钟负责；冻结时谁都救不了）
        fgHandler.removeCallbacks(fgRunnable)
    }

    /**
     * v1.9 前台保底：每 60 秒自查「当前 5 分钟网格点」是否已在本次会话采样，缺则补齐。
     * 仅在 App 处于前台（屏幕亮、用户在看）时运行 —— 此时进程未被系统冻结，必定能采样；
     * 这样即使后台闹钟被 iQOO/vivo 冻结，只要手机亮屏 + App 开着，数据照样每 5 分钟一格不漏。
     * 与 MonitorService 的采样通过 session-grid 去重（同一会话同一网格点不重复写）。
     */
    private fun ensureCurrentGridSampled() {
        val prefs = getSharedPreferences(MonitorService.PREFS, Context.MODE_PRIVATE)
        val startTs = prefs.getLong(MonitorService.KEY_START, 0L)
        if (startTs == 0L || !monitoring) return
        val elapsedSec = (System.currentTimeMillis() - startTs) / 1000
        val gridMin = ((elapsedSec / 300).toLong() * 5).toInt() // 0/5/10/.../30
        if (gridMin > 30) return
        val already = SampleStore.loadAll(this).any {
            it.scenario == selectedScenario && it.elapsedMin == gridMin && it.timestamp >= startTs
        }
        if (already) return
        val (pct, charging) = readPct()
        val temp = readTemp()
        val late = elapsedSec - gridMin * 60
        // 若明显晚于网格点，备注如实标注（暴露系统闹钟被推迟/冻结），绝不伪装准时
        val note = if (late > 90) "前台补采样(主闹钟可能被系统冻结或推迟)" else ""
        val seq = SampleStore.loadAll(this).maxOfOrNull { it.id } ?: 0
        SampleStore.add(
            this,
            Sample(
                id = seq + 1,
                scenario = selectedScenario,
                elapsedMin = gridMin,
                batteryPct = pct,
                tempC = temp,
                charging = charging,
                note = note,
                timestamp = System.currentTimeMillis()
            )
        )
        sendBroadcast(Intent(MonitorService.ACTION_NEW_SAMPLE))
        refreshList()
    }

    private fun readPct(): Pair<Int, Boolean> {
        val intent = ContextCompat.registerReceiver(
            this, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        ) ?: return 0 to false
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else 0
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        return pct to charging
    }

    private fun readTemp(): Float {
        val intent = ContextCompat.registerReceiver(
            this, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        ) ?: return 0f
        val raw = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
        return raw / 10.0f
    }
}
