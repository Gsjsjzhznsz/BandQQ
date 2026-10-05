package io.github.gsjsjzhznsz.bandqq.onebot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.28.0 SendGuard（发送风控保护）单元测试。
 * 时钟注入为递增变量，随机抖动固定 0/上限两态，全部断言可离线复现。
 */
class SendGuardTest {

    private class Clock {
        var t: Long = 1_000_000L
        fun now(): Long = t
        fun advance(ms: Long) { t += ms }
    }

    private fun guard(clock: Clock, jitter: Long = 0L): SendGuard =
        SendGuard(now = clock::now, jitter = { jitter })

    // ===== gate：空闲放行 =====
    @Test
    fun `gate passes when idle`() {
        val c = Clock(); val g = guard(c)
        assertNull(g.gate())
    }

    // ===== 预热：首次连接不触发 =====
    @Test
    fun `first connect does not warm up`() {
        val c = Clock(); val g = guard(c)
        g.onConnected()
        assertNull(g.gate())
    }

    // ===== 预热：短抖动重连不触发 =====
    @Test
    fun `short reconnect does not warm up`() {
        val c = Clock(); val g = guard(c)
        g.onConnected()
        c.advance(3_000)
        g.onDisconnected()
        c.advance(5_000)
        g.onConnected()
        assertNull(g.gate())
    }

    // ===== 预热：断开≥2 分钟重连触发，45 秒后自动解除 =====
    @Test
    fun `long disconnect triggers warmup and expires`() {
        val c = Clock(); val g = guard(c)
        g.onConnected()
        c.advance(10_000)
        g.onDisconnected()
        c.advance(121_000) // 断开超过 2 分钟（NapCat 冷启动场景）
        g.onConnected()
        assertNotNull("重连后应进入预热", g.gate())
        assertTrue(g.gate()!!.contains("预热"))
        // 拒绝外发由 OneBotClient 层执行；这里验证预热期内 gate 一直拦
        c.advance(10_000)
        assertNotNull(g.gate())
        c.advance(SendGuard.WARMUP_MS - 10_000)
        assertNull("预热期满应放行", g.gate())
    }

    // ===== 限速：相邻发送最小间隔 + 抖动 =====
    @Test
    fun `pacing enforces min interval plus jitter`() {
        val c = Clock(); val g = guard(c, jitter = 0L)
        g.commit()
        assertEquals("紧随其后的第二条必须等满最小间隔", SendGuard.MIN_INTERVAL_MS.toLong(), g.pacingDelay())
        c.advance(2_000)
        assertEquals("间隔不足部分补齐", 500L, g.pacingDelay())
        c.advance(3_000)
        assertEquals("间隔已足时仅剩抖动", 0L, g.pacingDelay())
    }

    @Test
    fun `pacing includes jitter when configured`() {
        val c = Clock(); val g = guard(c, jitter = 1_000L)
        assertEquals(1_000L, g.pacingDelay()) // 首条 = 纯抖动
    }

    // ===== 突发冷却：60s 窗口 12 条后进入冷却，期满解除 =====
    @Test
    fun `burst beyond limit triggers cooldown`() {
        val c = Clock(); val g = guard(c)
        repeat(SendGuard.BURST_MAX) {
            assertNull("第 ${it + 1} 条不应被冷却拦截", g.gate())
            c.advance(3_000) // 满足限速间隔
            g.commit()
        }
        c.advance(1_000)
        val r = g.gate()
        assertNotNull("突发超限应进入冷却", r)
        assertTrue(r!!.contains("冷却"))
        c.advance(SendGuard.BURST_COOLDOWN_MS)
        assertNull("冷却期满应放行", g.gate())
    }

    @Test
    fun `burst window slides - old sends expire`() {
        val c = Clock(); val g = guard(c)
        repeat(SendGuard.BURST_MAX) {
            c.advance(3_000)
            g.commit()
        }
        c.advance(SendGuard.BURST_WINDOW_MS + 1_000) // 全部滑出窗口
        assertNull("窗口滑动后不应持续冷却", g.gate())
    }

    // ===== 断开时序：从未连接成功时不触发预热 =====
    @Test
    fun `disconnect before first connect never warms up`() {
        val c = Clock(); val g = guard(c)
        g.onDisconnected() // 连接失败重试场景
        g.onConnected()
        assertNull(g.gate())
    }

    // ===== reset 全清 =====
    @Test
    fun `reset clears everything`() {
        val c = Clock(); val g = guard(c)
        g.onConnected()
        c.advance(10_000)
        g.onDisconnected()
        c.advance(200_000) // 断开 200 秒（期间流逝，与连接时长无关）
        g.onConnected()
        assertNotNull(g.gate())
        g.reset()
        assertNull(g.gate())
        assertEquals(0L, g.pacingDelay())
    }

    // ===== 文案可见性：拦截原因可读（直达手环 toast）=====
    @Test
    fun `gate reason is user readable`() {
        val c = Clock(); val g = guard(c)
        g.onConnected(); c.advance(300_000); g.onDisconnected(); c.advance(150_000); g.onConnected()
        val r = g.gate()!!
        assertFalse(r.isBlank())
        assertTrue(r.length < 120) // 手环 toast 长度预算
    }
}
