package com.example.bandqq.sync

import com.example.bandqq.onebot.OneBotMessage
import com.example.bandqq.onebot.OneBotParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

class MessageBrokerTest {

    @Before
    fun setUpOnline() { SyncState.bandConnected = true }

    @After
    fun tearDownOnline() { SyncState.bandConnected = false }

    private val parser = OneBotParser()

    @Test
    fun `handshake 发送消息帧被转发到 onebot`() {
        val sent = mutableListOf<Triple<String, String, String>>()
        val oneBot = FakeOneBot { t, id, c -> sent.add(Triple(t, id, c)); true }
        val broker = MessageBroker(parser, oneBot, MessageStore())
        val handled = broker.onBandFrame("""{"type":"send_message","message_type":"group","target_id":"123","content":"收到"}""")
        assertTrue(handled)
        assertEquals("group", sent[0].first)
        assertEquals("123", sent[0].second)
        assertEquals("收到", sent[0].third)
    }

    @Test
    fun `send_message 发送成功时回推 send_result ok 帧`() {
        val broker = MessageBroker(parser, FakeOneBot { _, _, _ -> true }, MessageStore())
        val out = mutableListOf<String>()
        broker.bandSender = { out.add(it) }
        val handled = broker.onBandFrame(
            """{"type":"send_message","seq":7,"message_type":"group","target_id":"123","content":"收到"}"""
        )
        assertTrue(handled)
        val result = out.first { it.contains("\"type\":\"send_result\"") }
        assertTrue(result.contains("\"ok\":true"))
        assertTrue(result.contains("\"seq\":7"))
    }

    @Test
    fun `send_message 发送失败时回推 send_result 失败帧并携带原因`() {
        val broker = MessageBroker(parser, FakeOneBot { _, _, _ -> false }, MessageStore())
        val out = mutableListOf<String>()
        broker.bandSender = { out.add(it) }
        val handled = broker.onBandFrame(
            """{"type":"send_message","seq":8,"message_type":"private","target_id":"194636275","content":"测式"}"""
        )
        assertTrue(handled)
        val result = out.first { it.contains("\"type\":\"send_result\"") }
        assertTrue(result.contains("\"ok\":false"))
        assertTrue(result.contains("fake failure"))
    }

    @Test
    fun `onebot 事件转手环帧`() {
        val broker = MessageBroker(parser, FakeOneBot { _, _, _ -> true }, MessageStore())
        val frame = broker.handleOneBotEvent(
            OneBotMessage("group", "123", "456", "张三", "你好", 1700000000L)
        )
        assertTrue(frame!!.contains("\"target_id\":\"123\""))
        assertTrue(frame.contains("你好"))
    }

    @Test
    fun `get_history 返回存储的最近消息`() {
        val store = MessageStore()
        store.addMessage("123", StoredMessage("group", "456", "张三", "你好", 1700000000L))
        val broker = MessageBroker(parser, FakeOneBot { _, _, _ -> true }, store)
        val out = mutableListOf<String>()
        broker.bandSender = { out.add(it) }
        val handled = broker.onBandFrame("""{"type":"get_history","seq":9,"target_id":"123","limit":20}""")
        assertTrue(handled)
        assertTrue(out[0].contains("\"type\":\"history_list\""))
        assertTrue(out[0].contains("你好"))
    }

    @Test
    fun `get_conversations 返回会话列表帧`() {
        val store = MessageStore()
        store.addMessage("123", StoredMessage("group", "456", "张三", "你好", 1700000000L))
        val broker = MessageBroker(parser, FakeOneBot { _, _, _ -> true }, store)
        val out = mutableListOf<String>()
        broker.bandSender = { out.add(it) }
        val handled = broker.onBandFrame("""{"type":"get_conversations","seq":9}""")
        assertTrue(handled)
        assertTrue(out[0].contains("\"type\":\"conversation_list\""))
        assertTrue(out[0].contains("\"id\":\"123\""))
    }

    @Test
    fun `自己群消息帧带 is_self`() {
        val broker = MessageBroker(parser, FakeOneBot { _, _, _ -> true }, MessageStore())
        val frame = broker.handleOneBotEvent(
            OneBotMessage("group", "123", "456", "张三", "你好", 1700000000L, isSelf = true)
        )
        assertTrue(frame!!.contains("\"is_self\":true"))
    }

    @Test
    fun `群消息 push_message 帧带缓存群名 target_name`() {
        val store = MessageStore()
        store.setCachedContacts(listOf(VisibleContact("123", "group", "技术交流群")))
        val broker = MessageBroker(parser, FakeOneBot { _, _, _ -> true }, store)
        val frame = broker.handleOneBotEvent(
            OneBotMessage("group", "123", "456", "张三", "你好", 1700000000L)
        )
        assertTrue(frame!!.contains("\"target_name\":\"技术交流群\""))
    }

    @Test
    fun `群消息无缓存时 target_name 回退为发送者名`() {
        val broker = MessageBroker(parser, FakeOneBot { _, _, _ -> true }, MessageStore())
        val frame = broker.handleOneBotEvent(
            OneBotMessage("group", "123", "456", "张三", "你好", 1700000000L)
        )
        assertTrue(frame!!.contains("\"target_name\":\"张三\""))
    }

    @Test
    fun `get_connect_state 返回 connect_state 帧`() {
        SyncState.bandConnected = true
        SyncState.oneBotConnected = true
        val broker = MessageBroker(parser, FakeOneBot { _, _, _ -> true }, MessageStore())
        val out = mutableListOf<String>()
        broker.bandSender = { out.add(it) }
        val handled = broker.onBandFrame("""{"type":"get_connect_state","seq":1}""")
        assertTrue(handled)
        assertTrue(out[0].contains("\"type\":\"connect_state\""))
        assertTrue(out[0].contains("\"band\":true"))
        assertTrue(out[0].contains("\"protocol\":true"))
        SyncState.bandConnected = false
        SyncState.oneBotConnected = false
    }

    @Test
    fun `pong 帧触发 onBandPong 回调`() {
        var ponged = false
        val broker = MessageBroker(parser, FakeOneBot { _, _, _ -> true }, MessageStore())
        broker.onBandPong = { ponged = true }
        val handled = broker.onBandFrame("""{"type":"pong","seq":0}""")
        assertTrue(handled)
        assertTrue(ponged)
    }

    @Test
    fun `band_state 帧触发 onBandPong 回调`() {
        var ponged = false
        val broker = MessageBroker(parser, FakeOneBot { _, _, _ -> true }, MessageStore())
        broker.onBandPong = { ponged = true }
        val handled = broker.onBandFrame("""{"type":"band_state","state":"connected","seq":0}""")
        assertTrue(handled)
        assertTrue(ponged)
    }

    @Test
    fun `未知手环帧返回 false`() {
        val broker = MessageBroker(parser, FakeOneBot { _, _, _ -> true }, MessageStore())
        val frame = broker.handleOneBotEvent(OneBotMessage("group", "1", "2", "n", "x", 0))
        assertTrue(frame!!.contains("\"type\":\"push_message\""))
        assertTrue(!broker.onBandFrame("""{"type":"unknown"}"""))
    }

    @Test
    fun `get_visible_contacts 返回可见联系人帧`() {
        val store = MessageStore()
        store.setVisibleContacts(listOf(VisibleContact("111", "private", "小明")))
        val broker = MessageBroker(parser, FakeOneBot { _, _, _ -> true }, store)
        val out = mutableListOf<String>()
        broker.bandSender = { out.add(it) }
        val handled = broker.onBandFrame("""{"type":"get_visible_contacts","seq":9}""")
        assertTrue(handled)
        assertTrue(out[0].contains("\"type\":\"visible_contacts\""))
        assertTrue(out[0].contains("\"id\":\"111\""))
    }

    @Test
    fun `已添加联系人 push_message 帧带 visible true`() {
        val store = MessageStore()
        store.setVisibleContacts(listOf(VisibleContact("123", "group", "群A")))
        val broker = MessageBroker(parser, FakeOneBot { _, _, _ -> true }, store)
        val frame = broker.handleOneBotEvent(OneBotMessage("group", "123", "456", "张三", "你好", 1700000000L))
        assertTrue(frame!!.contains("\"visible\":true"))
    }

    @Test
    fun `未添加联系人 push_message 帧带 visible false`() {
        val store = MessageStore()
        val broker = MessageBroker(parser, FakeOneBot { _, _, _ -> true }, store)
        val frame = broker.handleOneBotEvent(OneBotMessage("group", "999", "456", "张三", "你好", 1700000000L))
        assertTrue(frame!!.contains("\"visible\":false"))
    }

    @Test
    fun `onState 推送的 connect_state 帧含 band 与 protocol 字段`() {
        SyncState.bandConnected = true
        SyncState.oneBotConnected = false
        val broker = MessageBroker(parser, FakeOneBot { _, _, _ -> true }, MessageStore())
        val out = mutableListOf<String>()
        broker.bandSender = { out.add(it) }
        broker.onState(true)
        assertTrue(out[0].contains("\"type\":\"connect_state\""))
        assertTrue(out[0].contains("\"band\":true"))
        assertTrue(out[0].contains("\"protocol\":true"))
        SyncState.oneBotConnected = true
        broker.onState(false)
        assertTrue(out[1].contains("\"protocol\":false"))
    }

    @Test
    fun `onState 连接成功时触发自动拉取`() {
        var fetched = false
        val broker = MessageBroker(parser, FakeOneBot { _, _, _ -> true }, MessageStore()) {
            fetched = true
        }
        broker.autoFetchDone = false
        broker.onState(true)
        assertTrue(fetched)
    }

    @Test
    fun `onState 断开时不触发自动拉取`() {
        var fetched = false
        val broker = MessageBroker(parser, FakeOneBot { _, _, _ -> true }, MessageStore()) {
            fetched = true
        }
        broker.autoFetchDone = false
        broker.onState(false)
        assertTrue(!fetched)
    }

    @Test
    fun `autoFetchDone 为真时不再触发`() {
        var count = 0
        val broker = MessageBroker(parser, FakeOneBot { _, _, _ -> true }, MessageStore()) {
            count++
        }
        broker.autoFetchDone = true
        broker.onState(true)
        broker.onState(true)
        assertEquals(0, count)
    }

    @Test
    fun `收到 onebot 事件时通过 MessageBus 广播 targetId`() {
        var notified: String? = null
        MessageBus.add { notified = it }
        try {
            val broker = MessageBroker(parser, FakeOneBot { _, _, _ -> true }, MessageStore())
            broker.handleOneBotEvent(
                OneBotMessage("group", "123", "456", "张三", "你好", 1700000000L)
            )
            assertEquals("123", notified)
        } finally {
            MessageBus.clear()
        }
    }
}

class FakeOneBot(private val onSend: (String, String, String) -> Boolean) : MessageSender {
    override fun sendMessage(
        messageType: String,
        targetId: String,
        content: String,
        httpUrlOverride: String?,
        callback: (Boolean, String?, String?) -> Unit
    ) {
        val ok = onSend(messageType, targetId, content)
        callback(ok, if (ok) null else "fake failure", null)
    }
}
