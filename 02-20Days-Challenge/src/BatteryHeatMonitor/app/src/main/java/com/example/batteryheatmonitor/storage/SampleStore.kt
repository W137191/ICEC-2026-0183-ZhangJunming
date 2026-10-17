package com.example.batteryheatmonitor.storage

import android.content.Context
import com.example.batteryheatmonitor.model.Sample
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 采样记录的本地持久化（JSON 文件，存于 app filesDir）。
 * 选用 JSON 而非 Room，是为了零额外依赖、保证一次编译通过；
 * 最终数据通过 CsvExporter 导出为课程要求的 CSV。
 */
object SampleStore {

    private fun file(context: Context): File = File(context.filesDir, "samples.json")

    fun loadAll(context: Context): List<Sample> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
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
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun add(context: Context, sample: Sample) {
        val list = loadAll(context).toMutableList()
        list.add(sample)
        save(context, list)
    }

    fun clear(context: Context) {
        file(context).delete()
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
        file(context).writeText(arr.toString(2))
    }
}
