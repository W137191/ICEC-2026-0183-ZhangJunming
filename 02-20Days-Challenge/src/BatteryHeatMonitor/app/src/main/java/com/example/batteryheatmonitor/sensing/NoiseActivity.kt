package com.example.batteryheatmonitor.sensing

// 生成的资源类 R 在应用命名空间下，子包必须显式导入，否则 R.layout / R.id 全部 Unresolved
import com.example.batteryheatmonitor.R
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 课题06 噪声测量：替代市面分贝仪 App（只能截图、数据靠手抄，无法导出）
 *  - 麦克风 16kHz 采样，每 100ms 计算 RMS → dB，写时间序列 CSV（t_ms,rms,dB）
 *  - 结束后把 均值/峰值/最小 追加到 噪声记录索引_本机.csv，
 *    列结构与仓库 raw_data/课题06-噪声地图/噪声原始记录.csv 完全一致，可直接归档合并
 *  - dB = 20*log10(rms/32767) + 90：手机麦克风未校准，仅同一设备横向对比有效。
 *    市面消费级分贝仪同样未校准；本方案的优势是全量原始数据可追溯、可复算、可写入备注。
 *
 * 与声振采集的"中断即弃"不同：噪声的均值是有意义的统计量，中途中止的测量**保留**并在
 * 索引备注里如实标注"中止@Ns"——部分数据如实记录 > 凑一个假的完整值。
 */
class NoiseActivity : AppCompatActivity() {

    private lateinit var spinnerPoint: Spinner
    private lateinit var spinnerSlot: Spinner
    private lateinit var etDuration: EditText
    private lateinit var btnStart: Button
    private lateinit var btnShare: Button
    private lateinit var btnBack: Button
    private lateinit var tvLevel: TextView
    private lateinit var tvStats: TextView
    private lateinit var tvProgress: TextView
    private lateinit var tvResult: TextView
    private lateinit var pbLevel: ProgressBar

    // 点位/时段与课题06 CSV 模板对齐（5 点位 × 早午晚 = 15 格）
    private val points = listOf("图书馆", "食堂", "主干道", "运动场", "实训室走廊")
    private val slots = listOf("早", "午", "晚")

    private val SR = 16000
    private val CHANNEL = AudioFormat.CHANNEL_IN_MONO
    private val FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val DB_OFFSET = 90.0
    private val WINDOW = 1600 // 100ms 一个统计点
    private val REQ_AUDIO = 201

    private var measuring = false
    private var lastCsv: File? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_noise)

        spinnerPoint = findViewById(R.id.spinnerPoint)
        spinnerSlot = findViewById(R.id.spinnerSlot)
        etDuration = findViewById(R.id.etDuration)
        btnStart = findViewById(R.id.btnStart)
        btnShare = findViewById(R.id.btnShare)
        btnBack = findViewById(R.id.btnBack)
        tvLevel = findViewById(R.id.tvLevel)
        tvStats = findViewById(R.id.tvStats)
        tvProgress = findViewById(R.id.tvProgress)
        tvResult = findViewById(R.id.tvResult)
        pbLevel = findViewById(R.id.pbLevel)

        spinnerPoint.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, points)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerSlot.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, slots)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        etDuration.setText("120")

        val reload = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) = refreshProgress()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        spinnerPoint.onItemSelectedListener = reload
        spinnerSlot.onItemSelectedListener = reload

        btnStart.setOnClickListener { startMeasure() }
        btnShare.setOnClickListener { shareLatest() }
        btnBack.setOnClickListener { finish() }
        btnShare.isEnabled = false
        refreshProgress()
    }

    /** 进度：当前 点位+时段 组合已测几次 · 全部 N/15 格（对齐课题06 目标） */
    private fun refreshProgress() {
        val idx = indexFile(outDir())
        val lines = if (idx.exists()) idx.readLines().drop(1).filter { it.isNotBlank() } else emptyList()
        val p = points[spinnerPoint.selectedItemPosition]
        val s = slots[spinnerSlot.selectedItemPosition]
        val cur = lines.count { val f = it.split(","); f.getOrNull(1) == p && f.getOrNull(2) == s }
        tvProgress.text = "「$p·$s」已测 $cur 次 · 全部 ${lines.size}/15 格"
    }

    private fun outDir(): File {
        // getExternalFilesDir 在外部存储不可用时返回 null，需用 filesDir 兜底，否则返回类型为 File? 会编译失败
        val base = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: filesDir
        val d = File(base, "Noise")
        d.mkdirs()
        return d
    }

    private fun indexFile(dir: File) = File(dir, "噪声记录索引_本机.csv")

    private fun hasAudioPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startMeasure() {
        if (!hasAudioPermission()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
            Toast.makeText(this, "需要麦克风权限才能测量", Toast.LENGTH_SHORT).show()
            return
        }
        if (measuring) return
        val point = points[spinnerPoint.selectedItemPosition]
        val slot = slots[spinnerSlot.selectedItemPosition]
        val durS = etDuration.text.toString().toIntOrNull() ?: 120
        if (durS < 10) {
            Toast.makeText(this, "时长至少 10 秒", Toast.LENGTH_SHORT).show()
            return
        }
        val durMs = durS * 1000L
        val ts = SimpleDateFormat("yyyyMMdd-HHmm", Locale.CHINA).format(Date())
        val dir = outDir()
        val csvFile = File(dir, "噪声_${point}_${slot}_$ts.csv")

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BHM::noise").apply {
            setReferenceCounted(false); acquire()
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        measuring = true
        btnStart.isEnabled = false
        pbLevel.progress = 0
        tvResult.text = "测量中… 手机静置、屏幕常亮，勿挡住底部麦克风"

        Thread {
            val minBuf = AudioRecord.getMinBufferSize(SR, CHANNEL, FORMAT)
            val bufSize = max(minBuf * 2, SR * 2)
            var rec: AudioRecord? = null
            var sum = 0.0
            var cnt = 0
            var maxDb = -999.0
            var minDb = 999.0
            val startMs = System.currentTimeMillis()
            var failed = false
            try {
                rec = AudioRecord(MediaRecorder.AudioSource.MIC, SR, CHANNEL, FORMAT, bufSize)
                if (rec.state != AudioRecord.STATE_INITIALIZED) throw RuntimeException("AudioRecord 初始化失败（麦克风被占用或权限不足）")
                rec.startRecording()
                val buf = ShortArray(WINDOW)
                csvFile.bufferedWriter().use { w ->
                    w.write("t_ms,rms,dB\n")
                    while (measuring && System.currentTimeMillis() - startMs < durMs) {
                        val n = rec.read(buf, 0, buf.size)
                        if (n > 0) {
                            var sq = 0.0
                            for (i in 0 until n) {
                                val s = buf[i].toDouble()
                                sq += s * s
                            }
                            val rms = sqrt(sq / n)
                            val db = if (rms < 1.0) 0.0 else 20.0 * log10(rms / 32767.0) + DB_OFFSET
                            val t = System.currentTimeMillis() - startMs
                            w.write("$t,${String.format("%.1f", rms)},${String.format("%.1f", db)}\n")
                            sum += db
                            cnt++
                            if (db > maxDb) maxDb = db
                            if (db < minDb) minDb = db
                            val left = (durMs - (System.currentTimeMillis() - startMs)) / 1000.0
                            // 先取快照再投递：闭包延迟执行时统计变量已推进，避免显示与 dB 值不同步
                            val dbNow = String.format("%.1f", db)
                            val meanNow = meanOf(sum, cnt)
                            val maxNow = String.format("%.1f", maxDb)
                            val minNow = String.format("%.1f", minDb)
                            val leftNow = String.format("%.0f", left)
                            runOnUiThread {
                                tvLevel.text = "$dbNow dB"
                                pbLevel.progress = ((db - 30.0) / 70.0 * 100).toInt().coerceIn(0, 100)
                                tvStats.text = "均值 $meanNow · 峰值 $maxNow · 最小 $minNow · 剩余 $leftNow 秒"
                            }
                        }
                    }
                }
                rec.stop()
            } catch (e: Exception) {
                failed = true
                runOnUiThread { tvResult.text = "测量异常：${e.message}" }
            } finally {
                try { rec?.release() } catch (_: Exception) {}
            }

            val aborted = !measuring
            val elapsedS = (System.currentTimeMillis() - startMs) / 1000
            measuring = false
            wakeLock?.let { if (it.isHeld) it.release() }
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            if (!failed && cnt > 0) {
                // 部分测量也保留：均值/峰值对更短窗口依然成立，中止事实写进索引备注
                val mean = sum / cnt
                appendIndex(dir, point, slot, mean, maxDb, minDb, elapsedS, startMs, aborted, csvFile)
                lastCsv = csvFile
                runOnUiThread {
                    btnStart.isEnabled = true
                    btnShare.isEnabled = true
                    tvLevel.text = "${String.format("%.1f", mean)} dB"
                    tvStats.text = "均值 ${String.format("%.1f", mean)} · 峰值 ${String.format("%.1f", maxDb)} · " +
                        "最小 ${String.format("%.1f", minDb)} · 共 ${elapsedS}s${if (aborted) "（中止）" else ""}"
                    tvResult.text = "已保存：\n${csvFile.absolutePath}\n索引已追加一行（列结构与课题06模板一致，可直接归档合并）。\n点「分享 CSV+索引」发到电脑。"
                    refreshProgress()
                }
            } else {
                csvFile.delete()
                runOnUiThread {
                    btnStart.isEnabled = true
                    tvResult.text = if (failed) "测量失败，未保存任何数据" else "测量已中止且无有效数据，未保存"
                }
            }
        }.start()
    }

    private fun meanOf(sum: Double, cnt: Int) = if (cnt > 0) String.format("%.1f", sum / cnt) else "--"

    private fun appendIndex(
        dir: File, point: String, slot: String, mean: Double, maxDb: Double, minDb: Double,
        durS: Long, startMs: Long, aborted: Boolean, csv: File
    ) {
        val idx = indexFile(dir)
        val exists = idx.exists()
        val existing = if (exists) idx.readLines().drop(1).filter { it.isNotBlank() }.size else 0
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
        val startTime = sdf.format(Date(startMs))
        val endTime = sdf.format(Date())
        val partial = if (aborted) "；中止@${durS}s" else ""
        // 备注只使用中文标点，不含 ASCII 逗号，保证 CSV 列结构不被撑破
        val note = "最小${String.format("%.1f", minDb)}；${startTime}-${endTime}(${durS}s)$partial；" +
            "文件 ${csv.name}；自研App相对声压级(未校准；偏移90dB；同机横向对比有效)"
        // 用 Java FileWriter(file, append) 保证跨 Kotlin 版本可用；Kotlin 的 bufferedWriter/appendWriter 在不同 stdlib 版本行为不一
        val writer = java.io.BufferedWriter(java.io.FileWriter(idx, exists))
        writer.use { w ->
            if (!exists) w.write("序号,点位,时段,均值dB,峰值dB,备注\n")
            w.write("${existing + 1},$point,$slot,${String.format("%.1f", mean)},${String.format("%.1f", maxDb)},$note\n")
        }
    }

    private fun shareLatest() {
        val uris = ArrayList<android.net.Uri>()
        lastCsv?.let { if (it.exists()) uris.add(FileProvider.getUriForFile(this, packageName + ".fileprovider", it)) }
        val idx = indexFile(outDir())
        if (idx.exists()) uris.add(FileProvider.getUriForFile(this, packageName + ".fileprovider", idx))
        if (uris.isEmpty()) { Toast.makeText(this, "还没有可分享的文件", Toast.LENGTH_SHORT).show(); return }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "分享噪声数据"))
    }

    override fun onPause() {
        if (measuring) measuring = false // 离开页面则中止本次测量（部分数据保留并如实标注）
        super.onPause()
    }
}
