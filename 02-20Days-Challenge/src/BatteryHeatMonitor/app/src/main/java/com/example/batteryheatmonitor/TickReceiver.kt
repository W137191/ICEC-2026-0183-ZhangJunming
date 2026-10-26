package com.example.batteryheatmonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * 精确采样闹钟接收器（v1.5 起作为主调度）。
 *
 * 每个网格采样点由 MonitorService 通过 AlarmManager.setExactAndAllowWhileIdle 在「该时刻」
 * 直接挂一个 RTC_WAKEUP 精确闹钟（取代 v1.4 晚 60 秒的粗粒度看门狗）。
 * 闹钟由系统持有：即使采样进程被回收 / 手机息屏进入 Doze，到点仍由系统唤起本 Receiver，
 * 重新拉起 MonitorService，由服务判断网格点是否到期并补采——保证每 5 分钟一格不漏。
 * （服务内还有「同一网格点 + 30 秒」去重窗口兜底，闹钟与重启双路不会重复采样。）
 */
class TickReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, MonitorService::class.java)
        )
    }
}
