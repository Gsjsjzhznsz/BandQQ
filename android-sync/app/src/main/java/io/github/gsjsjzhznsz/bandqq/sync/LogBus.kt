package io.github.gsjsjzhznsz.bandqq.sync

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

data class LogEntry(
    val time: Long,
    val tag: String,
    val level: LogLevel,
    val message: String,
    val seq: Long = 0  // v2.9.4：唯一自增序号，作列表 key（同毫秒同内容日志不再撞 key 崩渲染）
)

object LogBus {
    private const val MAX_LOGS = 200

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs

    /** v2.9.4：日志唯一序号发生器 */
    private val seqCounter = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * 落盘 sink（v2.9.1）：FileLogger.install 时注入，每条日志同步转发到文件层
     * （文件层内部单线程异步写）。JVM 单测不注入则为 null。
     */
    var sink: ((LogEntry) -> Unit)? = null

    fun log(tag: String, level: LogLevel, message: String) {
        val entry = LogEntry(System.currentTimeMillis(), tag, level, message, seqCounter.incrementAndGet())
        _logs.update { (it + entry).takeLast(MAX_LOGS) }
        try {
            sink?.invoke(entry)
        } catch (_: Throwable) {
        }
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
