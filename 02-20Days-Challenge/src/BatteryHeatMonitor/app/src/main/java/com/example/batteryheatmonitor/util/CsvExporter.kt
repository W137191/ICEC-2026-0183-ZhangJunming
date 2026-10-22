package com.example.batteryheatmonitor.util

import android.content.Context
import android.content.Intent
import android.os.Environment
import com.example.batteryheatmonitor.model.Sample
import com.example.batteryheatmonitor.storage.SampleStore
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 把采样记录导出为与课题02 raw_data 模板完全一致的 CSV：
 *   序号,工况,时间min,电量%,机身温度℃,备注
 * 文件保存在 app 外部下载目录（Android/data/<pkg>/files/Download），
 * 无需存储权限，可通过 USB / 文件管理器取出，或直接用系统分享。
 */
object CsvExporter {

    private const val HEADER = "序号,工况,时间min,电量%,机身温度℃,备注"

    fun export(context: Context): File {
        val samples = SampleStore.loadAll(context)
        val sb = StringBuilder()
        sb.appendLine(HEADER)
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
        samples.forEach {
            // 列结构严格对齐课题02模板；测量时刻拼进备注列前缀，方便跨天数据回溯
            val note = "${sdf.format(Date(it.timestamp))} ${it.note}".trim()
            sb.appendLine(
                "${it.id},${csvCell(it.scenario)},${it.elapsedMin},${it.batteryPct}," +
                        "${String.format("%.1f", it.tempC)},${csvCell(note)}"
            )
        }
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        val file = File(dir, "battery_heat_samples.csv")
        file.writeText(sb.toString())
        return file
    }

    /** 通过 FileProvider 安全地分享 CSV（避免直接暴露文件路径） */
    fun shareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun csvCell(s: String): String {
        return if (s.contains(",") || s.contains("\n") || s.contains("\"")) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else s
    }
}
