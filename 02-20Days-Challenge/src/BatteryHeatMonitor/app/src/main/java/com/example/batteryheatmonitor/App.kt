package com.example.batteryheatmonitor

import android.app.Application
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 全局异常兜底：任何未捕获崩溃都先把完整堆栈写进 filesDir/last_crash.txt，
 * 再交给系统默认处理器（仍会弹"已停止运行"）。
 * 下次冷启动 MainActivity 会读到该文件并弹窗显示错误文本，
 * 用户把文字发回即可精准定位（HarmonyOS/平板等无法现场调试的设备尤其有用）。
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val def = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val f = File(filesDir, "last_crash.txt")
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
                f.writeText(
                    buildString {
                        append("时间：$ts\n")
                        append("系统：Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）\n")
                        append("设备：${Build.MANUFACTURER} ${Build.MODEL}\n")
                        append("线程：${thread.name}\n")
                        append("异常：${throwable.javaClass.name}: ${throwable.message}\n\n")
                        append("堆栈：\n")
                        append(throwable.stackTraceToString())
                    }
                )
            } catch (_: Exception) { /* 写入失败忽略，不影响系统兜底 */ }
            def?.uncaughtException(thread, throwable)
        }
    }
}
