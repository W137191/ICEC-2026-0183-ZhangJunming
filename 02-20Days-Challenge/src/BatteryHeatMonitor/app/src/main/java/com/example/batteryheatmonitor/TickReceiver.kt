package com.example.batteryheatmonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * 采样看门狗接收器。
 *
 * 每个采样网格点都在 AlarmManager 挂了一个 RTC_WAKEUP 闹钟（比网格点晚 60 秒）；
 * 若此时服务进程已被系统回收（Handler 链已死），闹钟仍会由系统唤起本 Receiver，
 * 重新拉起 MonitorService，由服务判断网格点是否到期并补采——
 * 避免再出现数十分钟的采样空档。Handler 正常触发时会先撤掉闹钟，二者不会重复采样
 * （服务内还有 30 秒去重窗口兜底）。
 */
class TickReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, MonitorService::class.java)
        )
    }
}
