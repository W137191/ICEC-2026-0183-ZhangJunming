package com.example.batteryheatmonitor.sensing

// 生成的资源类 R 在应用命名空间下，子包必须显式导入，否则 R.layout / R.id 全部 Unresolved
import com.example.batteryheatmonitor.R
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max

/**
 * 项目三声振采集：一次性录 10 秒
 *  - 麦克风 → 16kHz / 16bit / 单声道 PCM → 写标准 WAV
 *  - 加速度计 → 约 100Hz → 写 CSV（前 3 列 ax,ay,az，便于 fft_features.py 直接读取）
 * 文件命名：<设备>_<条件>_G<组号>_<yyyyMMdd-HHmm>.wav / .csv
 * 同时追加一行到 采集记录索引_本机.csv（字段对齐仓库 raw_data/采集记录索引.csv）
 *
 * v3 好用度升级（2026-10-24）：
 *  - 实时音量电平条：确认麦克风真的在收音、手机贴紧设备
 *  - 本组备注 + 异常勾选：写入索引备注列（采样时的环境异常是抗噪报告与面试素材）
 *  - 组号防覆盖：目标组号已存在时自动顺延，不静默覆盖旧数据
 *  - 中断丢弃：离开页面中止的采集直接丢弃，不把半成品混进数据集
 *  - 一键 ZIP 导出：整个 Sensing 目录打包成一个文件，75 组数据一次发到电脑
 */
class SensingActivity : AppCompatActivity() {

    private lateinit var spinner: Spinner
    private lateinit var etGroup: EditText
    private lateinit var btnCapture: Button
    private lateinit var btnShare: Button
    private lateinit var btnExportZip: Button
    private lateinit var btnBack: Button
    private lateinit var tvCountdown: TextView
    private lateinit var tvResult: TextView
    private lateinit var tvProgress: TextView
    private lateinit var pbLevel: ProgressBar
    private lateinit var etNote: EditText
    private lateinit var cbAnomaly: CheckBox

    private data class Cond(val 工况: String, val 设备: String, val 条件: String)

    private val conds = listOf(
        Cond("工况1-电风扇正常低速", "风扇", "正常"),
        Cond("工况2-电风扇扇叶失衡", "风扇", "失衡"),
        Cond("工况3-电风扇高速", "风扇", "高速"),
        Cond("工况4-吹风机冷风", "吹风", "冷风"),
        Cond("工况5-吹风机热风", "吹风", "热风")
    )

    private val SR = 16000
    private val CHANNEL = AudioFormat.CHANNEL_IN_MONO
    private val FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val DURATION_MS = 10_000L
    private val REQ_AUDIO = 200

    private var recording = false
    private var lastWav: File? = null
    private var lastCsv: File? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sensing)

        spinner = findViewById(R.id.spinnerCond)
        etGroup = findViewById(R.id.etGroup)
        btnCapture = findViewById(R.id.btnCapture)
        btnShare = findViewById(R.id.btnShare)
        btnExportZip = findViewById(R.id.btnExportZip)
        btnBack = findViewById(R.id.btnBack)
        tvCountdown = findViewById(R.id.tvCountdown)
        tvResult = findViewById(R.id.tvResult)
        tvProgress = findViewById(R.id.tvProgress)
        pbLevel = findViewById(R.id.pbLevel)
        etNote = findViewById(R.id.etNote)
        cbAnomaly = findViewById(R.id.cbAnomaly)

        spinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, conds.map { it.工况 }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                etGroup.setText(nextGroupFor(conds[pos]).toString())
                refreshProgress()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        btnCapture.setOnClickListener { startCapture() }
        btnShare.setOnClickListener { shareLatest() }
        btnExportZip.setOnClickListener { exportZip() }
        btnBack.setOnClickListener { finish() }
        btnShare.isEnabled = false
        etGroup.setText(nextGroupFor(conds[0]).toString())
        refreshProgress()
    }

    /** 采集进度：当前工况 N/15 组、总计 M/75 组（对齐仓库 raw_data/采集记录索引.csv 目标） */
    private fun refreshProgress() {
        val idx = indexFile(outDir())
        val lines = if (idx.exists()) idx.readLines().drop(1).filter { it.isNotBlank() } else emptyList()
        val cur = lines.count { it.split(",").getOrNull(1) == conds[spinner.selectedItemPosition].工况 }
        tvProgress.text = "当前工况已采 ${cur}/15 组 · 总计 ${lines.size}/75 组"
    }

    private fun outDir(): File {
        // getExternalFilesDir 在外部存储不可用时返回 null，需用 filesDir 兜底，否则返回类型为 File? 会编译失败
        val base = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: filesDir
        val d = File(base, "Sensing")
        d.mkdirs()
        return d
    }

    private fun indexFile(dir: File) = File(dir, "采集记录索引_本机.csv")

    /** 该工况已有几组，组号自动递增 */
    private fun nextGroupFor(c: Cond): Int {
        val idx = indexFile(outDir())
        if (!idx.exists()) return 1
        val cnt = idx.readLines().drop(1)
            .filter { it.isNotBlank() }
            .count { it.split(",").getOrNull(1) == c.工况 }
        return cnt + 1
    }

    private fun hasAudioPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startCapture() {
        if (!hasAudioPermission()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
            Toast.makeText(this, "需要麦克风权限才能录音", Toast.LENGTH_SHORT).show()
            return
        }
        val c = conds[spinner.selectedItemPosition]
        val dir = outDir()

        // 组号防覆盖：已存在的组号自动顺延，绝不静默覆盖旧数据
        var group = etGroup.text.toString().toIntOrNull() ?: 1
        var bumped = false
        while (dir.listFiles()?.any {
                it.name.startsWith("${c.设备}_${c.条件}_G${String.format("%02d", group)}_")
            } == true) {
            group++
            bumped = true
        }
        if (bumped) {
            etGroup.setText(group.toString())
            Toast.makeText(this, "该组号已存在，已自动顺延到 G${String.format("%02d", group)}", Toast.LENGTH_LONG).show()
        }

        val ts = SimpleDateFormat("yyyyMMdd-HHmm", Locale.CHINA).format(Date())
        val base = "${c.设备}_${c.条件}_G${String.format("%02d", group)}_$ts"
        val wavFile = File(dir, "$base.wav")
        val csvFile = File(dir, "$base.csv")

        val (pct, temp) = readBattery()

        // 备注与异常标记在 UI 线程预先取值：后台线程不读 UI 控件
        val noteText = etNote.text.toString().trim().replace(",", "，")
        val anomaly = cbAnomaly.isChecked

        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val accelTS = mutableListOf<Long>()
        val ax = mutableListOf<Float>(); val ay = mutableListOf<Float>(); val az = mutableListOf<Float>()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                accelTS.add(e.timestamp)
                ax.add(e.values[0]); ay.add(e.values[1]); az.add(e.values[2])
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (accel != null) sm.registerListener(listener, accel, SensorManager.SENSOR_DELAY_GAME)

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BHM::sensing").apply {
            setReferenceCounted(false); acquire()
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        recording = true
        btnCapture.isEnabled = false
        pbLevel.progress = 0
        tvResult.text = "采集中… 保持手机贴紧设备、屏幕常亮"

        Thread {
            val pcm = ByteArrayOutputStream()
            var rec: AudioRecord? = null
            var audioMissing = false
            try {
                val minBuf = AudioRecord.getMinBufferSize(SR, CHANNEL, FORMAT)
                val bufSize = max(minBuf * 2, SR * 2) // ≥2 秒缓冲
                rec = AudioRecord(MediaRecorder.AudioSource.MIC, SR, CHANNEL, FORMAT, bufSize)
                if (rec.state != AudioRecord.STATE_INITIALIZED) throw RuntimeException("AudioRecord 初始化失败（麦克风被占用或权限不足）")
                rec.startRecording()
                val buf = ShortArray(minBuf)
                val start = System.currentTimeMillis()
                var peak = 0
                while (recording && System.currentTimeMillis() - start < DURATION_MS) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) {
                        for (i in 0 until n) {
                            val s = buf[i].toInt()
                            pcm.write(s and 0xff)        // 小端写入 16bit PCM
                            pcm.write((s ushr 8) and 0xff)
                            val a = if (s < 0) -s else s
                            if (a > peak) peak = a
                        }
                    }
                    val left = DURATION_MS - (System.currentTimeMillis() - start)
                    val level = peak * 100 / 32768 // 先取快照再投递：闭包延迟执行时 peak 已被重置
                    peak = 0
                    runOnUiThread {
                        tvCountdown.text = "剩余 ${String.format("%.1f", left / 1000.0)} 秒"
                        pbLevel.progress = level
                    }
                }
                rec.stop()
            } catch (e: Exception) {
                audioMissing = true
                runOnUiThread { tvResult.text = "音频采集异常：${e.message}（仍会保存加速度数据）" }
            } finally {
                try { rec?.release() } catch (_: Exception) {}
            }

            if (!recording) {
                // 离开页面中止：丢弃半成品，避免不完整的采集混入数据集（与噪声测量的"部分保留"策略不同：
                // 3 秒的风扇音频对 FFT 无意义，必须凑满 10 秒）
                wavFile.delete()
                csvFile.delete()
                if (accel != null) sm.unregisterListener(listener)
                wakeLock?.let { if (it.isHeld) it.release() }
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                runOnUiThread {
                    btnCapture.isEnabled = true
                    tvCountdown.text = "已中断"
                    tvResult.text = "本次采集未完成，已丢弃（未写入索引、未占组号）。重新采集请再点开始。"
                }
                return@Thread
            }

            val pcmBytes = pcm.toByteArray()
            if (pcmBytes.isNotEmpty()) {
                try { writeWav(wavFile, pcmBytes) } catch (e: Exception) {
                    audioMissing = true
                    runOnUiThread { tvResult.text = "写 WAV 失败：${e.message}" }
                }
            } else {
                audioMissing = true
                wavFile.delete()
            }
            try { writeCsv(csvFile, accelTS, ax, ay, az, pct, temp) } catch (e: Exception) {
                runOnUiThread { tvResult.text = "写 CSV 失败：${e.message}" }
            }
            appendIndex(dir, c, group, wavFile, csvFile, pct, temp, audioMissing, noteText, anomaly)

            if (accel != null) sm.unregisterListener(listener)
            wakeLock?.let { if (it.isHeld) it.release() }
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            recording = false
            lastWav = wavFile; lastCsv = csvFile
            runOnUiThread {
                btnCapture.isEnabled = true
                btnShare.isEnabled = true
                tvCountdown.text = "完成"
                tvResult.text = "已保存：\n${wavFile.absolutePath}\n${csvFile.absolutePath}\n建议每采几组就点「导出全部ZIP」发到电脑备份（卸载 App 会清空数据）"
                etNote.setText("")
                cbAnomaly.isChecked = false
                refreshProgress()
            }
        }.start()
    }

    private fun readBattery(): Pair<Int, Float> {
        val intent = ContextCompat.registerReceiver(
            this, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else 0
        val temp = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0f
        return pct to temp
    }

    private fun writeWav(file: File, pcm: ByteArray) {
        FileOutputStream(file).use { fos ->
            val channels = 1
            val bits = 16
            val byteRate = SR * channels * bits / 8
            val dataLen = pcm.size
            fun wStr(s: String) = fos.write(s.toByteArray(Charsets.US_ASCII))
            fun wInt(v: Int) {
                fos.write(v and 0xff); fos.write((v ushr 8) and 0xff)
                fos.write((v ushr 16) and 0xff); fos.write((v ushr 24) and 0xff)
            }
            fun wShort(v: Int) { fos.write(v and 0xff); fos.write((v ushr 8) and 0xff) }
            wStr("RIFF"); wInt(36 + dataLen); wStr("WAVE")
            wStr("fmt "); wInt(16); wShort(1); wShort(channels); wInt(SR); wInt(byteRate); wShort(channels * bits / 8); wShort(bits)
            wStr("data"); wInt(dataLen)
            fos.write(pcm)
        }
    }

    private fun writeCsv(
        file: File, ts: List<Long>, ax: List<Float>, ay: List<Float>, az: List<Float>, pct: Int, temp: Float
    ) {
        file.bufferedWriter().use { w ->
            w.write("ax,ay,az,t_ns,battery_pct,temp_c\n")
            for (i in ax.indices) {
                val t = if (i < ts.size) ts[i] else 0L
                w.write("${ax[i]},${ay[i]},${az[i]},$t,$pct,${String.format("%.1f", temp)}\n")
            }
        }
    }

    private fun appendIndex(
        dir: File, c: Cond, group: Int, wav: File, csv: File, pct: Int, temp: Float,
        audioMissing: Boolean, noteText: String, anomaly: Boolean
    ) {
        val idx = indexFile(dir)
        val exists = idx.exists()
        val existing = if (exists) idx.readLines().drop(1).filter { it.isNotBlank() }.size else 0
        // 备注列拼装：异常勾选 / 音频缺失 / 用户手填；逗号一律替换为中文逗号，防止撑破 CSV 列结构
        val parts = mutableListOf<String>()
        if (anomaly) parts.add("异常")
        if (audioMissing) parts.add("音频缺失")
        if (noteText.isNotEmpty()) parts.add(noteText)
        val note = parts.joinToString(";")
        // 用 Java FileWriter(file, append) 保证跨 Kotlin 版本可用；Kotlin 的 bufferedWriter/appendWriter 在不同 stdlib 版本行为不一
        val writer = java.io.BufferedWriter(java.io.FileWriter(idx, exists))
        writer.use { w ->
            if (!exists) w.write("序号,工况,组号,日期,设备,条件,音频文件,加速度文件,电量%,温度℃,备注\n")
            val seq = existing + 1
            val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date())
            w.write("${seq},${c.工况},${group},${date},${c.设备},${c.条件},${wav.name},${csv.name},${pct},${String.format("%.1f", temp)},$note\n")
        }
    }

    private fun shareLatest() {
        val uris = ArrayList<android.net.Uri>()
        lastWav?.let { if (it.exists()) uris.add(FileProvider.getUriForFile(this, packageName + ".fileprovider", it)) }
        lastCsv?.let { if (it.exists()) uris.add(FileProvider.getUriForFile(this, packageName + ".fileprovider", it)) }
        if (uris.isEmpty()) { Toast.makeText(this, "还没有可分享的文件", Toast.LENGTH_SHORT).show(); return }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "分享采集文件"))
    }

    /**
     * 一键 ZIP 导出：整个 Sensing 目录（含索引）打包成一个文件放在 Download/ 根下，
     * 通过 FileProvider 分享。75 组数据从此不用一组一组发。
     * 压缩包放在 Sensing/ 目录外，不会被下一次导出重复打入。
     */
    private fun exportZip() {
        val dir = outDir()
        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()
        if (files.isEmpty()) { Toast.makeText(this, "还没有采集文件可导出", Toast.LENGTH_SHORT).show(); return }
        val ts = SimpleDateFormat("yyyyMMdd-HHmm", Locale.CHINA).format(Date())
        val parent = dir.parentFile ?: dir
        val zip = File(parent, "Sensing_Export_$ts.zip")
        try {
            ZipOutputStream(FileOutputStream(zip)).use { zos ->
                files.forEach { f ->
                    zos.putNextEntry(ZipEntry(f.name))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "打包失败：${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "已打包 ${files.size} 个文件，选择发送方式传到电脑", Toast.LENGTH_LONG).show()
        val uri = FileProvider.getUriForFile(this, packageName + ".fileprovider", zip)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "发送采集数据包"))
    }

    override fun onPause() {
        if (recording) recording = false // 离开页面则中止本次采集（半成品将被丢弃）
        super.onPause()
    }
}
