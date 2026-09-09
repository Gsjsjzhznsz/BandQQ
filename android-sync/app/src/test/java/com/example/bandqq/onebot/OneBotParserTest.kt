package com.example.bandqq.onebot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OneBotParserTest {

    private val parser = OneBotParser()

    @Test
    fun `解析群消息事件`() {
        val json = """
            {"post_type":"message","message_type":"group","group_id":"123","user_id":"456",
             "sender":{"nickname":"张三"},"message":[{"type":"text","data":{"text":"你好"}}],
             "time":1700000000,"self_id":1,"message_id":2}
        """.trimIndent()
        val msg = parser.parseMessageEvent(json)
        assertEquals("group", msg?.messageType)
        assertEquals("123", msg?.targetId)
        assertEquals("456", msg?.senderId)
        assertEquals("张三", msg?.senderName)
        assertEquals("你好", msg?.content)
        assertEquals(1700000000000L, msg?.time)
    }

    @Test
    fun `解析私聊消息事件`() {
        val json = """
            {"post_type":"message","message_type":"private","user_id":"789",
             "sender":{"nickname":"李四"},"message":[{"type":"text","data":{"text":"在吗"}}],
             "time":1700000001,"self_id":1,"message_id":3}
        """.trimIndent()
        val msg = parser.parseMessageEvent(json)
        assertEquals("private", msg?.messageType)
        assertEquals("789", msg?.targetId)
    }

    @Test
    fun `非文本段降级`() {
        val json = """
            {"post_type":"message","message_type":"group","group_id":"123","user_id":"456",
             "sender":{"nickname":"张三"},
             "message":[{"type":"text","data":{"text":"图:"}},
                        {"type":"image","data":{"file":"a.png"}},
                        {"type":"text","data":{"text":"。"}}],
             "time":1700000000,"self_id":1,"message_id":2}
        """.trimIndent()
        val msg = parser.parseMessageEvent(json)
        assertEquals("图:[图片]。", msg?.content)
    }

    @Test
    fun `非消息事件返回 null`() {
        val json = """{"post_type":"meta_event","meta_event_type":"heartbeat"}"""
        assertNull(parser.parseMessageEvent(json))
    }

    @Test
    fun `自己发的群消息标记 isSelf`() {
        val json = """
            {"post_type":"message","message_type":"group","group_id":"123","user_id":"456",
             "sender":{"nickname":"我自己"},"message":[{"type":"text","data":{"text":"测试"}}],
             "time":1700000000,"self_id":456,"message_id":2}
        """.trimIndent()
        val msg = parser.parseMessageEvent(json)
        assertEquals(true, msg?.isSelf)
    }

    @Test
    fun `他人群消息 isSelf 为 false`() {
        val json = """
            {"post_type":"message","message_type":"group","group_id":"123","user_id":"456",
             "sender":{"nickname":"张三"},"message":[{"type":"text","data":{"text":"你好"}}],
             "time":1700000000,"self_id":999,"message_id":2}
        """.trimIndent()
        val msg = parser.parseMessageEvent(json)
        assertEquals(false, msg?.isSelf)
    }

    @Test
    fun `构建发送请求体`() {
        val body = parser.buildSendRequest("group", "123", "收到")
        assertEquals("""{"action":"send_group_msg","params":{"group_id":123,"message":"收到"}}""", body)
    }

    @Test
    fun `OneBot 秒级 time 统一转为毫秒`() {
        val json = """
            {"post_type":"message","message_type":"private","user_id":"789",
             "sender":{"nickname":"李四"},"message":[{"type":"text","data":{"text":"在吗"}}],
             "time":1700000000,"self_id":1,"message_id":3}
        """.trimIndent()
        val msg = parser.parseMessageEvent(json)
        assertEquals(1700000000000L, msg?.time)
    }

    @Test
    fun `毫秒级 time 保持原值`() {
        val json = """
            {"post_type":"message","message_type":"private","user_id":"789",
             "sender":{"nickname":"李四"},"message":[{"type":"text","data":{"text":"在吗"}}],
             "time":1700000000123,"self_id":1,"message_id":3}
        """.trimIndent()
        val msg = parser.parseMessageEvent(json)
        assertEquals(1700000000123L, msg?.time)
    }

    @Test
    fun `文本内的 emoji 降级为表情`() {
        val json = """
            {"post_type":"message","message_type":"group","group_id":"123","user_id":"456",
             "sender":{"nickname":"张三"},"message":[{"type":"text","data":{"text":"早😊好"}}],
             "time":1700000000,"self_id":1,"message_id":2}
        """.trimIndent()
        val msg = parser.parseMessageEvent(json)
        assertEquals("早[表情]好", msg?.content)
    }

    @Test
    fun `表情包段降级为表情而不落到其他`() {
        val json = """
            {"post_type":"message","message_type":"group","group_id":"123","user_id":"456",
             "sender":{"nickname":"张三"},"message":[{"type":"face","data":{"id":"178"}},
                        {"type":"text","data":{"text":"了"}}],
             "time":1700000000,"self_id":1,"message_id":2}
        """.trimIndent()
        val msg = parser.parseMessageEvent(json)
        assertEquals("[表情]了", msg?.content)
    }

    @Test
    fun `昵称中的 emoji 被剔除`() {
        val json = """
            {"post_type":"message","message_type":"group","group_id":"123","user_id":"456",
             "sender":{"nickname":"🌟阿杰"},"message":[{"type":"text","data":{"text":"hi"}}],
             "time":1700000000,"self_id":1,"message_id":2}
        """.trimIndent()
        val msg = parser.parseMessageEvent(json)
        assertEquals("阿杰", msg?.senderName)
    }
}
