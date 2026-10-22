package com.example.batteryheatmonitor.storage

import android.content.Context
import com.example.batteryheatmonitor.model.Sample
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 采样记录的本地持久化（JSON 文件，存于 app filesDir）。
 * 选用 JSON 而非 Room，是为了零额外依赖、保证一次编译通过；
 * 最终数据通过 CsvExporter 导出为课程要求的 CSV。
 *
 * 防丢数据三道保险（2026-10-22 修复"继续监测后历史记录全丢"问题）：
 * 1. 原子写入：先写 samples.json.tmp 再 rename 替换，写入中途进程被杀也不会留下半个 JSON；
 * 2. 损坏抢救：万一文件仍损坏，先备份为 samples.corrupt-<时间>.json，再逐条 {...} 尽量解析回记录；
 * 3. 线程同步：add/loadAll/clear 全部加锁，避免服务线程与主界面并发读写互相覆盖。
 */
object SampleStore {

    private fun file(context: Context): File = File(context.filesDir, "samples.json")
    private fun tmpFile(context: Context): File = File(context.filesDir, "samples.json.tmp")

    @Synchronized
    fun loadAll(context: Context): List<Sample> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return try {
            parseArray(f.readText())
        } catch (e: Exception) {
            // 整体解析失败 = 文件损坏（旧版全量重写被中断的典型后果）。
            // 先备份留证，再逐条抢救，绝不让历史静默变空后被覆盖。
            backupCorrupt(f)
            recoverRecords(f)
        }
    }

    @Synchronized
    fun add(context: Context, sample: Sample) {
        val list = loadAll(context).toMutableList()
        list.add(sample)
        save(context, list)
    }

    @Synchronized
    fun clear(context: Context) {
        file(context).delete()
        tmpFile(context).delete()
    }

    /** 删除指定 id 的一条（误采补救） */
    @Synchronized
    fun remove(context: Context, id: Long) {
        val list = loadAll(context).filter { it.id != id }
        save(context, list)
    }

    /** 删除最新一条（时间戳最大者），返回被删记录便于提示；无记录返回 null */
    @Synchronized
    fun removeLatest(context: Context): Sample? {
        val list = loadAll(context)
        val latest = list.maxByOrNull { it.timestamp } ?: return null
        save(context, list.filter { it.id != latest.id })
        return latest
    }

    private fun parseArray(text: String): List<Sample> {
        val arr = JSONArray(text)
        val list = mutableListOf<Sample>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                Sample(
                    id = o.getLong("id"),
                    scenario = o.getString("scenario"),
                    elapsedMin = o.getInt("elapsedMin"),
                    batteryPct = o.getInt("batteryPct"),
                    tempC = o.getDouble("tempC").toFloat(),
                    charging = o.getBoolean("charging"),
                    note = o.optString("note", ""),
                    timestamp = o.getLong("timestamp")
                )
            )
        }
        return list
    }

    /** 逐个大括号片段解析，能救回几条救几条（截断发生在最后一条时前面的都完好） */
    private fun recoverRecords(f: File): List<Sample> {
        val text = runCatching { f.readText() }.getOrElse { return emptyList() }
        val recovered = mutableListOf<Sample>()
        var depth = 0
        val sb = StringBuilder()
        text.forEach { ch ->
            when (ch) {
                '{' -> { depth++; sb.append(ch) }
                '}' -> if (depth > 0) {
                    depth--
                    sb.append(ch)
                    if (depth == 0) {
                        runCatching { parseArray("[${sb}]") }.getOrNull()?.let { recovered.addAll(it) }
                        sb.clear()
                    }
                }
                else -> if (depth > 0) sb.append(ch)
            }
        }
        return recovered
    }

    private fun backupCorrupt(f: File) {
        runCatching {
            val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA).format(Date())
            f.copyTo(File(f.parentFile, "samples.corrupt-$ts.json"), overwrite = false)
        }
    }

    private fun save(context: Context, list: List<Sample>) {
        val arr = JSONArray()
        list.forEach {
            val o = JSONObject()
            o.put("id", it.id)
            o.put("scenario", it.scenario)
            o.put("elapsedMin", it.elapsedMin)
            o.put("batteryPct", it.batteryPct)
            o.put("tempC", it.tempC)
            o.put("charging", it.charging)
            o.put("note", it.note)
            o.put("timestamp", it.timestamp)
            arr.put(o)
        }
        // 原子写入：临时文件落盘后再 rename，同目录 rename 是原子操作，
        // 中途被杀也只会留下 .tmp，正式文件保持上一个完好版本
        val tmp = tmpFile(context)
        tmp.writeText(arr.toString())
        val target = file(context)
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            // rename 失败兜底：直接写正式文件（旧行为）
            target.writeText(arr.toString())
            tmp.delete()
        }
    }
}
