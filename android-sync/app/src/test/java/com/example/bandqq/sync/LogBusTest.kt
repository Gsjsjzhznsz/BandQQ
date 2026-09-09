package com.example.bandqq.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogBusTest {

    @Test
    fun `追加日志按时间记录字段`() {
        LogBus.clear()
        LogBus.log("Test", LogLevel.INFO, "hello")
        val logs = LogBus.logs.value
        assertEquals(1, logs.size)
        assertEquals("Test", logs[0].tag)
        assertEquals(LogLevel.INFO, logs[0].level)
        assertEquals("hello", logs[0].message)
        assertTrue(logs[0].time > 0)
    }

    @Test
    fun `超过 200 条裁剪最旧日志`() {
        LogBus.clear()
        repeat(250) { i -> LogBus.log("T", LogLevel.DEBUG, "msg$i") }
        val logs = LogBus.logs.value
        assertEquals(200, logs.size)
        assertEquals("msg50", logs[0].message)
        assertEquals("msg249", logs.last().message)
    }

    @Test
    fun `clear 清空日志`() {
        LogBus.log("T", LogLevel.WARN, "x")
        LogBus.clear()
        assertEquals(0, LogBus.logs.value.size)
    }
}
