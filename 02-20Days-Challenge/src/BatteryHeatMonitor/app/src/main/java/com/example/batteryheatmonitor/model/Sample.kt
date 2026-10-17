package com.example.batteryheatmonitor.model

/**
 * 一条采样记录。
 * CSV 列映射（与课题02 raw_data 模板一致）：
 *   id        -> 序号
 *   scenario  -> 工况
 *   elapsedMin-> 时间min
 *   batteryPct-> 电量%
 *   tempC     -> 机身温度℃
 *   note      -> 备注
 */
data class Sample(
    val id: Long,
    val scenario: String,
    val elapsedMin: Int,
    val batteryPct: Int,
    val tempC: Float,
    val charging: Boolean,
    val note: String,
    val timestamp: Long
)
