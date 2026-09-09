package com.example.bandqq.sync

import com.example.bandqq.onebot.OneBotMessage
import com.example.bandqq.onebot.OneBotParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * v1.2.0 新能力测试：
 * 1. CQ 码清洗（快捷回复/气泡「一堆格式代码」根因）
 * 2. 下发正文截断（蓝牙帧瘦身）
 * 3. 手环离线门控（快应用关闭 → 停止蓝牙业务帧，缓存待回放）
 * 4. 重连回放（conversation_list + 离线期消息）
 */
class V120FeatureTest {

    private val parser = OneBotParser()

    @Before fun setUp() { SyncState.bandConnected = true }
    @After fun tearDown() { SyncState.bandConnected = false }

    // ---------- T4: CQ 码清洗 ----------

    @Test
    fun `cleanCqCodes 清洗 CQ 码保留正文`() {
        assertEquals("你好", OneBotParser.cleanCqCodes("[CQ:image,file=abc.image]你好"))
        assertEquals("前面 后面", OneBotParser.cleanCqCodes("前面[CQ:at,qq=123] 后面"))
        // 非 CQ 格式的占位文本不误伤
        assertEquals("看看 [图片] 这张", OneBotParser.cleanCqCodes("看看 [图片] 这张"))
    }

    @Test
    fun `extractSegs 字符串消息清洗 CQ 码`() {
        val segs = parser.extractSegs(com.google.gson.JsonParser.parseString("\"[CQ:face,id=1]哈哈\""), null)
        assertEquals("哈哈", segs.content)
    }

    @Test
    fun `toHandBandFrame 下发内容含 CQ 清洗与截断`() {
        val long = "[CQ:reply,id=-1]".repeat(30) + "这".repeat(300)
        val msg = OneBotMessage("group", "10086", "u1", "甲", long, 1788875415000L)
        val frame = parser.toHandBandFrame(msg)
        assertTrue(frame.contains("\"content\":\"这"))
        // 截断到 180 + 省略号
        val content = com.google.gson.JsonParser.parseString(frame).asJsonObject.get("content").asString
        assertEquals(181, content.length)
        assertTrue(content.endsWith("…"))
    }

    // ---------- T2: 离线门控与重连回放 ----------

    @Test
    fun `手环离线时 handleOneBotEvent 不再产出下发帧`() {
        SyncState.bandConnected = false
        val store = MessageStore()
        val broker = MessageBroker(parser, FakeOneBot { _, _, _ -> true }, store)
        val frame = broker.handleOneBotEvent(
            OneBotMessage("group", "10010", "u9", "乙", "离线期间的消息", 1788875420000L)
        )
        assertNull(frame)
        // 但消息已入缓存，等待回放
        assertEquals(1, store.getAllMessages("10010").size)
    }

    @Test
    fun `手环离线时 onEvent 不触发 bandSender`() {
        SyncState.bandConnected = false
        val broker = MessageBroker(parser, FakeOneBot { _, _, _ -> true }, MessageStore())
        val out = mutableListOf<String>()
        broker.bandSender = { out.add(it) }
        broker.onEvent(OneBotMessage("group", "10011", "u8", "丙", "离线消息", 1788875421000L))
        assertEquals(0, out.size)
    }

    @Test
    fun `手环在线时 onEvent 正常下发且内容被清洗`() {
        val broker = MessageBroker(parser, FakeOneBot { _, _, _ -> true }, MessageStore())
        val out = mutableListOf<String>()
        broker.bandSender = { out.add(it) }
        broker.onEvent(
            OneBotMessage("group", "10012", "u7", "丁", "看[CQ:image,file=x.jpg]图", 1788875422000L)
        )
        assertEquals(1, out.size)
        assertTrue(out[0].contains("看图"))
    }

    @Test
    fun `重连回放推会话列表与离线期消息`() {
        SyncState.bandConnected = false
        val store = MessageStore()
        store.setCachedContacts(listOf(VisibleContact("10013", "group", "回放群")))
        store.setVisibleContacts(listOf(VisibleContact("10013", "group", "回放群")))
        val broker = MessageBroker(parser, FakeOneBot { _, _, _ -> true }, store)
        // 离线期间到达两条消息
        broker.handleOneBotEvent(OneBotMessage("group", "10013", "u1", "甲", "离线一", 1788875423000L))
        broker.handleOneBotEvent(OneBotMessage("group", "10013", "u2", "乙", "离线二", 1788875424000L))
        // 手环上线
        SyncState.bandConnected = true
        val out = mutableListOf<String>()
        broker.bandSender = { out.add(it) }
        broker.onBandConnected()
        // 回放包含：visible_contacts + conversation_list + 离线期 history
        assertTrue(out.any { it.contains("\"type\":\"visible_contacts\"") })
        assertTrue(out.any { it.contains("\"type\":\"conversation_list\"") })
        val history = out.first { it.contains("\"type\":\"history_list\"") }
        assertTrue(history.contains("离线一"))
        assertTrue(history.contains("离线二"))
    }

    @Test
    fun `发送结果帧在离线时被门控丢弃`() {
        SyncState.bandConnected = false
        val store = MessageStore()
        val broker = MessageBroker(parser, FakeOneBot { _, _, _ -> true }, store)
        val out = mutableListOf<String>()
        broker.bandSender = { out.add(it) }
        broker.onBandFrame(
            """{"type":"send_message","seq":9,"message_type":"group","target_id":"10014","content":"离线发送"}"""
        )
        // send_message 处理本身成功，但 send_result / 回显帧不发给已死通道
        assertEquals(0, out.size)
        // 自发消息仍然入库（回放有据）
        assertEquals(1, store.getAllMessages("10014").size)
    }

    /** 与 MessageBrokerTest 同款 Fake */
    private class FakeOneBot(
        private val ok: (String, String, String) -> Boolean
    ) : MessageSender {
        val sent = mutableListOf<Triple<String, String, String>>()
        override fun sendMessage(
            messageType: String, targetId: String, content: String,
            httpUrlOverride: String?, callback: (Boolean, String?, String?) -> Unit
        ) {
            sent.add(Triple(messageType, targetId, content))
            callback(ok(messageType, targetId, content), if (ok(messageType, targetId, content)) null else "fake failure", null)
        }
    }
}
