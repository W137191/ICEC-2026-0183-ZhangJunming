package com.example.batteryheatmonitor.monitor

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build

/**
 * 系统电池温度数据源（默认）。
 * BatteryManager.EXTRA_TEMPERATURE 单位为 0.1℃，故除以 10。
 */
class SystemBatteryTempProvider : TemperatureProvider {
    override fun getTemperatureC(context: Context): Float? {
        // Android 14+ 动态注册必须显式声明导出标志，否则抛 IllegalArgumentException 崩
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            context.registerReceiver(null, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(null, filter)
        } ?: return null
        val raw = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        if (raw == Int.MIN_VALUE) return null
        return raw / 10.0f
    }
}
