package com.example.bandqq.sync

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

data class LogEntry(
    val time: Long,
    val tag: String,
    val level: LogLevel,
    val message: String
)

object LogBus {
    private const val MAX_LOGS = 200

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs

    fun log(tag: String, level: LogLevel, message: String) {
        _logs.update { (it + LogEntry(System.currentTimeMillis(), tag, level, message)).takeLast(MAX_LOGS) }
        try {
            when (level) {
                LogLevel.DEBUG -> Log.d(tag, message)
                LogLevel.INFO -> Log.i(tag, message)
                LogLevel.WARN -> Log.w(tag, message)
                LogLevel.ERROR -> Log.e(tag, message)
            }
        } catch (t: Throwable) {
            // JVM 单测环境 android.util.Log 不可用，静默忽略
        }
    }

    fun clear() {
        _logs.value = emptyList()
    }
}
