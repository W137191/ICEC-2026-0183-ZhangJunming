package com.example.batteryheatmonitor.monitor

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * 系统电池温度数据源（默认）。
 * BatteryManager.EXTRA_TEMPERATURE 单位为 0.1℃，故除以 10。
 */
class SystemBatteryTempProvider : TemperatureProvider {
    override fun getTemperatureC(context: Context): Float? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null
        val raw = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        if (raw == Int.MIN_VALUE) return null
        return raw / 10.0f
    }
}
