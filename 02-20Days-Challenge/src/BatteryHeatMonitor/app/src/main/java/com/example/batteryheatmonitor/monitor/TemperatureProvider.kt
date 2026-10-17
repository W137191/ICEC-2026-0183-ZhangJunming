package com.example.batteryheatmonitor.monitor

import android.content.Context

/**
 * 温度采集接口（预留接口 / 可插拔数据源）。
 *
 * 课程要求：机身温度无法由统一的系统 API 跨平台读取。本 App 用原生安卓的
 * BatteryManager 可直接读到电池温度，因此默认实现 SystemBatteryTempProvider。
 * 若某些机型读不到、或你希望接入第三方测温 App / Root 读数 / 红外传感器，
 * 只需新增一个实现本接口的类，并在 MonitorService 里替换 provider 即可，
 * 无需改动采样与导出逻辑。
 */
interface TemperatureProvider {
    /** 返回机身温度（摄氏度），无法获取时返回 null */
    fun getTemperatureC(context: Context): Float?
}
