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
import android.os.Build
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
import kotlin.math.max

/**
 * 项目三声振采集：一次性录 10 秒
 *  - 麦克风 → 16kHz / 16bit / 单声道 PCM → 写标准 WAV
 *  - 加速度计 → 约 100Hz → 写 CSV（前 3 列 ax,ay,az，便于 fft_features.py 直接读取）
 * 文件命名：<设备>_<条件>_G<组号>_<yyyyMMdd-HHmm>.wav / .csv
 * 同时追加一行到 采集记录索引_本机.csv（字段对齐仓库 raw_data/采集记录索引.csv）
 */
class SensingActivity : AppCompatActivity() {

    private lateinit var spinner: Spinner
    private lateinit var etGroup: EditText
    private lateinit var btnCapture: Button
    private lateinit var btnShare: Button
    private lateinit var btnBack: Button
    private lateinit var tvCountdown: TextView
    private lateinit var tvResult: TextView

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
        btnBack = findViewById(R.id.btnBack)
        tvCountdown = findViewById(R.id.tvCountdown)
        tvResult = findViewById(R.id.tvResult)

        spinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, conds.map { it.工况 }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                etGroup.setText(nextGroupFor(conds[pos]).toString())
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        btnCapture.setOnClickListener { startCapture() }
        btnShare.setOnClickListener { shareLatest() }
        btnBack.setOnClickListener { finish() }
        btnShare.isEnabled = false
        etGroup.setText(nextGroupFor(conds[0]).toString())
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
        val group = etGroup.text.toString().toIntOrNull() ?: 1
        val ts = SimpleDateFormat("yyyyMMdd-HHmm", Locale.CHINA).format(Date())
        val base = "${c.设备}_${c.条件}_G${String.format("%02d", group)}_$ts"
        val dir = outDir()
        val wavFile = File(dir, "$base.wav")
        val csvFile = File(dir, "$base.csv")

        val (pct, temp) = readBattery()

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
        tvResult.text = "采集中… 保持手机贴紧设备、屏幕常亮"

        Thread {
            val pcm = ByteArrayOutputStream()
            var rec: AudioRecord? = null
            try {
                val minBuf = AudioRecord.getMinBufferSize(SR, CHANNEL, FORMAT)
                val bufSize = max(minBuf * 2, SR * 2) // ≥2 秒缓冲
                rec = AudioRecord(MediaRecorder.AudioSource.MIC, SR, CHANNEL, FORMAT, bufSize)
                if (rec.state != AudioRecord.STATE_INITIALIZED) throw RuntimeException("AudioRecord 初始化失败（麦克风被占用或权限不足）")
                rec.startRecording()
                val buf = ShortArray(minBuf)
                val start = System.currentTimeMillis()
                while (recording && System.currentTimeMillis() - start < DURATION_MS) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) {
                        for (i in 0 until n) {
                            val s = buf[i].toInt()
                            pcm.write(s and 0xff)        // 小端写入 16bit PCM
                            pcm.write((s ushr 8) and 0xff)
                        }
                    }
                    val left = DURATION_MS - (System.currentTimeMillis() - start)
                    runOnUiThread { tvCountdown.text = "剩余 ${String.format("%.1f", left / 1000.0)} 秒" }
                }
                rec.stop()
            } catch (e: Exception) {
                runOnUiThread { tvResult.text = "音频采集异常：${e.message}（仍会保存加速度数据）" }
            } finally {
                try { rec?.release() } catch (_: Exception) {}
            }

            try { writeWav(wavFile, pcm.toByteArray()) } catch (e: Exception) {
                runOnUiThread { tvResult.text = "写 WAV 失败：${e.message}" }
            }
            try { writeCsv(csvFile, accelTS, ax, ay, az, pct, temp) } catch (e: Exception) {
                runOnUiThread { tvResult.text = "写 CSV 失败：${e.message}" }
            }
            appendIndex(dir, c, group, wavFile, csvFile, pct, temp)

            if (accel != null) sm.unregisterListener(listener)
            wakeLock?.let { if (it.isHeld) it.release() }
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            recording = false
            lastWav = wavFile; lastCsv = csvFile
            runOnUiThread {
                btnCapture.isEnabled = true
                btnShare.isEnabled = true
                tvCountdown.text = "完成"
                tvResult.text = "已保存：\n${wavFile.absolutePath}\n${csvFile.absolutePath}"
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

    private fun appendIndex(dir: File, c: Cond, group: Int, wav: File, csv: File, pct: Int, temp: Float) {
        val idx = indexFile(dir)
        val exists = idx.exists()
        val existing = if (exists) idx.readLines().drop(1).filter { it.isNotBlank() }.size else 0
        // Kotlin 的 bufferedWriter() 没有 append 参数；已有文件时用 appendWriter() 追加，否则覆盖写
        val writer = if (exists) idx.appendWriter() else idx.bufferedWriter()
        writer.use { w ->
            if (!exists) w.write("序号,工况,组号,日期,设备,条件,音频文件,加速度文件,电量%,温度℃,备注\n")
            val seq = existing + 1
            val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date())
            w.write("${seq},${c.工况},${group},${date},${c.设备},${c.条件},${wav.name},${csv.name},${pct},${String.format("%.1f", temp)},\n")
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

    override fun onPause() {
        if (recording) recording = false // 离开页面则中止本次采集
        super.onPause()
    }
}
