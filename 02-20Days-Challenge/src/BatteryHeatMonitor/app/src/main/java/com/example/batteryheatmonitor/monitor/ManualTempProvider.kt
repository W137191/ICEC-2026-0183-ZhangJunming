package com.example.batteryheatmonitor.monitor

import android.content.Context

/**
 * 手动温度兜底数据源（预留接口的兜底实现）。
 * 当系统读不到温度、或你想用第三方测温 App 的读数手动录入时使用。
 * valueC 为 null 表示「不使用手动值」。
 */
class ManualTempProvider(private val valueC: Float?) : TemperatureProvider {
    override fun getTemperatureC(context: Context): Float? = valueC
}
