package com.example.bandqq

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局崩溃捕获（v2.4.5）：
 * 把未捕获异常的完整堆栈落盘到 files/crash_log.txt，设置页可查看/复制/清空。
 * 背景：Monet / 预测性返回开关在部分真机上崩溃但无法远程复现，
 * 有了落盘日志，用户反馈时可直接提供堆栈，修复不再靠猜。
 */
object CrashGuard {

    private const val MAX_LOG_BYTES = 64 * 1024
    private const val MAX_ENTRIES = 5

    fun logFile(context: Context): File = File(context.filesDir, "crash_log.txt")

    /** 超大日志只保留最后 MAX_ENTRIES 段（每段以分隔行开头） */
    fun trimIfNeeded(file: File) {
        if (!file.exists() || file.length() <= MAX_LOG_BYTES) return
        val content = runCatching { file.readText() }.getOrNull() ?: return
        val parts = content.split("\n----------\n").filter { it.isNotBlank() }
        val keep = parts.takeLast(MAX_ENTRIES).joinToString("\n----------\n")
        runCatching { file.writeText(keep) }
    }

    fun install(context: Context) {
        val file = logFile(context)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val entry = buildString {
                    append("\n----------\n")
                    append("[").append(stamp).append("] thread=").append(thread.name).append('\n')
                    append(throwable.javaClass.name).append(": ").append(throwable.message).append('\n')
                    append(throwable.stackTraceToString()).append('\n')
                    throwable.cause?.let { cause ->
                        append("CAUSED BY ").append(cause.javaClass.name).append(": ")
                        append(cause.message).append('\n')
                        append(cause.stackTraceToString()).append('\n')
                    }
                }
                file.appendText(entry)
                trimIfNeeded(file)
            }
            // 交回系统默认处理（杀进程 + 崩溃弹窗），保证行为与用户预期一致
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun readLog(context: Context): String =
        runCatching { logFile(context).takeIf { it.exists() }?.readText().orEmpty() }.getOrDefault("")

    fun clearLog(context: Context) {
        runCatching { logFile(context).delete() }
    }
}
