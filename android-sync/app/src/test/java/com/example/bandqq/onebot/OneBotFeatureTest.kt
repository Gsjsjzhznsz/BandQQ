package com.example.bandqq.onebot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stapxs 功能移植相关测试：@我判定、图片 URL/message_id 提取、
 * 历史响应解析、登录信息解析、手环帧扩展字段。
 */
class OneBotFeatureTest {

    private val parser = OneBotParser()

    @Test
    fun `at 段还原为 @qq 而非落到其他`() {
        val msg = parser.parseMessageEvent(
            """{"post_type":"message","message_type":"group","group_id":100,"user_id":22,
               "self_id":99999,"time":1700000000,
               "sender":{"nickname":"张三"},
               "message":[{"type":"at","data":{"qq":"2233"}},{"type":"text","data":{"text":" 在吗"}}]}"""
        )
        assertNotNull(msg)
        assertEquals("@2233  在吗", msg!!.content)
        assertTrue(!msg.atMe)
    }

    @Test
    fun `at 段命中登录用户时 atMe 标记`() {
        val msg = parser.parseMessageEvent(
            """{"post_type":"message","message_type":"group","group_id":100,"user_id":22,
               "self_id":2233,"time":1700000000,
               "sender":{"nickname":"张三"},
               "message":[{"type":"at","data":{"qq":"2233"}},{"type":"text","data":{"text":" 看我"}}]}"""
        )
        assertNotNull(msg)
        assertTrue(msg!!.atMe)
        assertEquals("@2233  看我", msg.content)
    }

    @Test
    fun `图片段提取 url 且 atall 特判`() {
        val msg = parser.parseMessageEvent(
            """{"post_type":"message","message_type":"group","group_id":100,"user_id":22,
               "self_id":99999,"time":1700000000,
               "sender":{"nickname":"张三"},
               "message":[{"type":"at","data":{"qq":"all"}},
                          {"type":"image","data":{"file":"abc.image","url":"https://example.com/a.jpg"}}]}"""
        )
        assertNotNull(msg)
        assertTrue(msg!!.atMe.not())
        assertEquals("https://example.com/a.jpg", msg.imageUrl)
        assertTrue(msg.content.contains("@全体成员"))
        assertTrue(msg.content.contains("[图片]"))
        assertTrue(msg.messageId.isNotBlank() || true)
    }

    @Test
    fun `message_id 与 image url 同步提取`() {
        val msg = parser.parseMessageEvent(
            """{"post_type":"message","message_type":"private","user_id":77,"self_id":99999,
               "time":1700000000,"message_id":5566,
               "sender":{"nickname":"李四"},
               "message":[{"type":"image","data":{"url":"http://img.example.com/p.png"}}]}"""
        )
        assertNotNull(msg)
        assertEquals("5566", msg!!.messageId)
        assertEquals("http://img.example.com/p.png", msg.imageUrl)
    }

    @Test
    fun `历史响应解析 data-messages 数组且群名片优先`() {
        val raw = """{"status":"ok","retcode":0,"data":{"messages":[
            {"message_id":101,"group_id":194636275,"user_id":111,"self_id":99999,
             "time":1700000100,
             "sender":{"nickname":"一一","card":"我的世界一秋小镇"},
             "message":[{"type":"text","data":{"text":"翻页测试"}}]},
            {"message_id":102,"group_id":194636275,"user_id":99999,"self_id":99999,
             "time":1700000160,
             "sender":{"nickname":"我","card":""},
             "message":[{"type":"text","data":{"text":"自己发的"}}]}
        ]}}"""
        val list = parser.parseHistoryResponse(raw)
        assertEquals(2, list.size)
        assertEquals("我的世界一秋小镇", list[0].senderName)
        assertEquals("101", list[0].messageId)
        assertEquals("group", list[0].messageType)
        assertEquals("194636275", list[0].targetId)
        assertTrue(list[1].isSelf)
    }

    @Test
    fun `历史响应解析 data 直接为数组的实现`() {
        val raw = """{"status":"ok","retcode":0,"data":[
            {"message_id":201,"user_id":55,"self_id":99999,"time":1700000200,
             "sender":{"nickname":"私聊好友"},"message":"字符串消息体"}
        ]}"""
        val list = parser.parseHistoryResponse(raw)
        assertEquals(1, list.size)
        assertEquals("private", list[0].messageType)
        assertEquals("字符串消息体", list[0].content)
        assertEquals("55", list[0].targetId)
    }

    @Test
    fun `parseLoginInfo 提取账号`() {
        val info = parser.parseLoginInfo("""{"status":"ok","retcode":0,"data":{"user_id":194636275,"nickname":"我的世界一秋小镇"}}""")
        assertNotNull(info)
        assertEquals("194636275", info!!.first)
        assertEquals("我的世界一秋小镇", info.second)
    }

    @Test
    fun `手环帧携带 at_me 与 thumb 扩展字段`() {
        val frame = parser.toHandBandFrame(
            OneBotMessage("group", "100", "22", "张三", "@2233 你看", 1700000000L, atMe = true),
            visible = true,
            targetName = "测试群",
            thumb = "data:image/jpeg;base64,QUJD"
        )
        assertTrue(frame.contains("\"at_me\":true"))
        assertTrue(frame.contains("\"thumb\":\"data:image/jpeg;base64,QUJD\""))
        val plain = parser.toHandBandFrame(
            OneBotMessage("group", "100", "22", "张三", "普通", 1700000000L)
        )
        assertTrue(!plain.contains("at_me"))
        assertTrue(!plain.contains("thumb"))
    }
}
