package com.example.bandqq.sync

import com.example.bandqq.onebot.OneBotMessage
import com.example.bandqq.onebot.OneBotParser
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 翻页历史与缩略图钩子测试 */
class BrokerPaginationTest {

    private val parser = OneBotParser()

    /** 带参数 API 的 Fake：返回预置响应并记录调用 */
    private class FakeApiOneBot(
        private val historyResp: String?
    ) : MessageSender {
        val apiCalls = mutableListOf<Pair<String, JsonObject>>()
        override fun sendMessage(
            messageType: String, targetId: String, content: String,
            httpUrlOverride: String?, callback: (Boolean, String?, String?) -> Unit
        ) { callback(true, null, null) }

        override fun requestApiParams(action: String, params: JsonObject, callback: (String?) -> Unit) {
            apiCalls.add(action to params)
            callback(historyResp)
        }
    }

    @Test
    fun `older 翻页走 OneBot 历史接口并回传 mode-older 帧`() {
        val store = MessageStore()
        // 本地已有带锚点的两条消息
        store.addMessage("100", StoredMessage("group", "1", "甲", "旧消息", 1700000000L, false, messageId = "101"))
        store.addMessage("100", StoredMessage("group", "2", "乙", "新消息", 1700000600L, false, messageId = "102"))
        val resp = """{"status":"ok","retcode":0,"data":{"messages":[
            {"message_id":100,"group_id":100,"user_id":1,"self_id":9,"time":1699999900,
             "sender":{"nickname":"更早"},"message":[{"type":"text","data":{"text":"更早的一页"}}]}
        ]}}"""
        val oneBot = FakeApiOneBot(resp)
        val broker = MessageBroker(parser, oneBot, store)
        val out = mutableListOf<String>()
        broker.bandSender = { out.add(it) }

        val handled = broker.onBandFrame(
            """{"type":"get_history","seq":9,"target_id":"100","limit":20,"older":true,"before_time":1700000000000}"""
        )
        assertTrue(handled)
        // action 选择：群会话 → get_group_msg_history，锚点 message_seq=101
        assertEquals("get_group_msg_history", oneBot.apiCalls[0].first)
        assertEquals("101", oneBot.apiCalls[0].second.get("message_seq").asString)
        // 回传帧：mode=older + 新页内容
        val frame = out.first { it.contains("\"mode\":\"older\"") }
        assertTrue(frame.contains("更早的一页"))
        assertTrue(frame.contains("\"has_more\":false"))
        // 翻到的旧消息回填本地存储
        assertTrue(store.getAllMessages("100").any { it.messageId == "100" })
    }

    @Test
    fun `无锚点时先拉最新页建立锚点，OneBot 不可用回退本地更早一页`() {
        val store = MessageStore()
        // 旧数据：无 message_id
        store.addMessage("200", StoredMessage("private", "5", "老王", "本地旧消息", 1700000000L))
        // FakeApiOneBot(null) = OneBot 不可用：先尝试拉最新页失败 → 回退本地更早一页
        val oneBot = FakeApiOneBot(null)
        val broker = MessageBroker(parser, oneBot, store)
        val out = mutableListOf<String>()
        broker.bandSender = { out.add(it) }
        broker.onBandFrame(
            """{"type":"get_history","seq":3,"target_id":"200","limit":20,"older":true,"before_time":1700000100000}"""
        )
        // v1.1.1：先发一次最新页请求建立锚点（失败后再走本地回退）
        assertEquals(1, oneBot.apiCalls.size)
        val frame = out.first { it.contains("\"mode\":\"older\"") }
        assertTrue(frame.contains("本地旧消息"))
        assertTrue(frame.contains("\"has_more\":false"))
    }

    @Test
    fun `OneBot 正常时无锚点走两段式翻页`() {
        val store = MessageStore()
        // 本地数据完全无锚点
        store.addMessage("300", StoredMessage("group", "6", "小李", "本地新消息", 1700000100L))
        val resp = """{"status":"ok","retcode":0,"data":{"messages":[
            {"message_seq":55,"message_id":55,"group_id":300,"user_id":6,"self_id":9,"time":1699999800,
             "sender":{"nickname":"更早"},"message":[{"type":"text","data":{"text":"锚点前的消息"}}]}
        ]}}"""
        val oneBot = FakeApiOneBot(resp)
        val broker = MessageBroker(parser, oneBot, store)
        val out = mutableListOf<String>()
        broker.bandSender = { out.add(it) }
        broker.onBandFrame(
            """{"type":"get_history","seq":4,"target_id":"300","limit":20,"older":true,"before_time":1700000100000}"""
        )
        // 两段式：第一次拉最新页（无 message_seq），第二次以最旧条锚点续翻
        assertEquals(2, oneBot.apiCalls.size)
        assertTrue(oneBot.apiCalls[0].second.get("message_seq") == null)
        assertEquals("55", oneBot.apiCalls[1].second.get("message_seq").asString)
        val frame = out.first { it.contains("\"mode\":\"older\"") }
        assertTrue(frame.contains("锚点前的消息"))
        // 响应条目的 message_seq 入库（后续翻页锚点链）
        assertTrue(store.getAllMessages("300").any { it.messageSeq == "55" })
    }

    @Test
    fun `图片消息经缩略图钩子一次性下发`() {
        val store = MessageStore()
        val oneBot = FakeApiOneBot(null)
        val broker = MessageBroker(parser, oneBot, store)
        val out = mutableListOf<String>()
        broker.bandSender = { out.add(it) }
        broker.thumbFetcher = { _, cb -> cb("data:image/jpeg;base64,QUJD") }
        val frame = broker.handleOneBotEvent(
            OneBotMessage(
                "group", "100", "7", "发图人", "[图片]", 1700000300L,
                atMe = true, messageId = "77", imageUrl = "http://img.example.com/x.jpg"
            )
        )
        // 异步投递：同步返回 null，帧由钩子回调发出
        assertEquals(null, frame)
        assertEquals(1, out.size)
        assertTrue(out[0].contains("\"thumb\":\"data:image/jpeg;base64,QUJD\""))
        assertTrue(out[0].contains("\"at_me\":true"))
    }

    @Test
    fun `连接成功后推送 login_info 帧`() {
        val store = MessageStore()
        val oneBot = FakeApiOneBot(
            """{"status":"ok","retcode":0,"data":{"user_id":194636275,"nickname":"我的世界一秋小镇"}}"""
        )
        val broker = MessageBroker(parser, oneBot, store)
        val out = mutableListOf<String>()
        broker.bandSender = { out.add(it) }
        broker.onState(true)
        val frame = out.first { it.contains("\"type\":\"login_info\"") }
        assertTrue(frame.contains("194636275"))
        assertTrue(frame.contains("我的世界一秋小镇"))
    }
}
