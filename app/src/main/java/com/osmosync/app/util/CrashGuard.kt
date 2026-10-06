package com.osmosync.app.util

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局崩溃捕获：把未捕获异常的堆栈写入应用私有目录，
 * 下次启动时 UI 会弹窗展示，方便在没有电脑的情况下定位问题。
 */
object CrashGuard {

    private const val MAX_FILES = 5

    fun install(context: Context) {
        val appDir = File(context.filesDir, "crashes").apply { mkdirs() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val text = buildString {
                    append("时间: ")
                    append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                    append("\n线程: ").append(thread.name).append("\n\n")
                    append(sw.toString())
                }
                File(appDir, "crash_${System.currentTimeMillis()}.txt").writeText(text)
                // 只保留最近几份
                appDir.listFiles()?.sortedByDescending { it.name }?.drop(MAX_FILES)?.forEach { it.delete() }
            } catch (_: Exception) {
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun latestCrash(context: Context): File? =
        File(context.filesDir, "crashes").listFiles()?.maxByOrNull { it.name }

    fun clear(context: Context) {
        File(context.filesDir, "crashes").listFiles()?.forEach { it.delete() }
    }
}
