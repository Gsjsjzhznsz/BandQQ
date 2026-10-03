package io.github.gsjsjzhznsz.bandqq.onebot

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
    fun `文本内的 emoji 透传给手环渲染`() {
        val json = """
            {"post_type":"message","message_type":"group","group_id":"123","user_id":"456",
             "sender":{"nickname":"张三"},"message":[{"type":"text","data":{"text":"早😊好"}}],
             "time":1700000000,"self_id":1,"message_id":2}
        """.trimIndent()
        val msg = parser.parseMessageEvent(json)
        assertEquals("早😊好", msg?.content)
    }

    @Test
    fun `face 段映射为对应 emoji（178 斜眼笑）`() {
        val json = """
            {"post_type":"message","message_type":"group","group_id":"123","user_id":"456",
             "sender":{"nickname":"张三"},"message":[{"type":"face","data":{"id":"178"}},
                        {"type":"text","data":{"text":"了"}}],
             "time":1700000000,"self_id":1,"message_id":2}
        """.trimIndent()
        val msg = parser.parseMessageEvent(json)
        assertEquals("🤣了", msg?.content)
    }

    @Test
    fun `未收录 face 段回退表情占位`() {
        val json = """
            {"post_type":"message","message_type":"group","group_id":"123","user_id":"456",
             "sender":{"nickname":"张三"},"message":[{"type":"face","data":{"id":"99999"}},
                        {"type":"text","data":{"text":"好"}}],
             "time":1700000000,"self_id":1,"message_id":2}
        """.trimIndent()
        val msg = parser.parseMessageEvent(json)
        assertEquals("[表情]好", msg?.content)
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

    @Test
    fun `DevTools 同构 at 段事件判定 atMe（self_id 数字 + at qq 字符串）`() {
        // DevTools MsgBuilder.messageEvent 实际输出形态：self_id 为 JSON 数字，
        // at 段 data.qq 为字符串（selfId.toString()）；二者数值一致必须判为 @我
        val json = """
            {"post_type":"message","message_type":"private","time":1757635200,
             "self_id":10000,"user_id":10086,"message_id":"dev_1757635200_1",
             "sender":{"nickname":"999","user_id":10086},
             "message":[{"type":"at","data":{"qq":"10000"}},
                        {"type":"text","data":{"text":"刚刚的方案你觉得怎么样？"}}]}
        """.trimIndent()
        val msg = parser.parseMessageEvent(json)
        assertEquals(true, msg?.atMe)
    }

    @Test
    fun `DevTools 同构 at 段事件判定 atMe（at qq 为 JSON 数字形态）`() {
        // 防御：某些协议端把 at 段 qq 上报为数字，字符串比较必须不漏判
        val json = """
            {"post_type":"message","message_type":"group","group_id":20001,"time":1757635200,
             "self_id":10000,"user_id":10086,"message_id":"dev_1757635200_2",
             "sender":{"nickname":"群友小王","user_id":10086},
             "message":[{"type":"at","data":{"qq":10000}},
                        {"type":"text","data":{"text":"看这里"}}]}
        """.trimIndent()
        val msg = parser.parseMessageEvent(json)
        assertEquals(true, msg?.atMe)
    }

    @Test
    fun `at 段 qq 与 self_id 不一致时不算 atMe`() {
        val json = """
            {"post_type":"message","message_type":"private","time":1757635200,
             "self_id":10000,"user_id":10086,"message_id":"dev_1757635200_3",
             "sender":{"nickname":"999","user_id":10086},
             "message":[{"type":"at","data":{"qq":"88888"}},
                        {"type":"text","data":{"text":"@别人"}}]}
        """.trimIndent()
        val msg = parser.parseMessageEvent(json)
        assertEquals(false, msg?.atMe)
    }

    // ===== v2.9.0 拍一拍（notice.notify.poke）=====

    @Test
    fun `群聊拍一拍 target_id 指向我时判定 pokeMe`() {
        val json = """
            {"post_type":"notice","notice_type":"notify","sub_type":"poke",
             "time":1757635200,"self_id":10000,"user_id":10086,"group_id":20001,"target_id":10000}
        """.trimIndent()
        val poke = parser.parsePokeEvent(json)
        assertEquals(true, poke?.pokeMe)
        assertEquals("group", poke?.chatType)
        assertEquals("20001", poke?.targetId)
        assertEquals("10086", poke?.senderId)
    }

    @Test
    fun `私聊拍一拍无 group_id 落在拍人者会话且判定 pokeMe`() {
        val json = """
            {"post_type":"notice","notice_type":"notify","sub_type":"poke",
             "time":1757635200,"self_id":10000,"user_id":10001,"target_id":10000}
        """.trimIndent()
        val poke = parser.parsePokeEvent(json)
        assertEquals(true, poke?.pokeMe)
        assertEquals("private", poke?.chatType)
        assertEquals("10001", poke?.targetId)
    }

    @Test
    fun `拍别人不判定 pokeMe 且兼容 group_poke 形态`() {
        // NapCat 旧形态 notice_type=group_poke + 拍别人（target_id != self_id）
        val json = """
            {"post_type":"notice","notice_type":"group_poke",
             "time":1757635200,"self_id":10000,"user_id":10086,"group_id":20001,"target_id":88888}
        """.trimIndent()
        val poke = parser.parsePokeEvent(json)
        assertEquals(false, poke?.pokeMe)
    }

    @Test
    fun `无 target_id 的私聊拍一拍默认拍的是我`() {
        val json = """
            {"post_type":"notice","notice_type":"notify","sub_type":"poke",
             "time":1757635200,"self_id":10000,"user_id":10001}
        """.trimIndent()
        val poke = parser.parsePokeEvent(json)
        assertEquals(true, poke?.pokeMe)
        assertEquals("private", poke?.chatType)
    }

    @Test
    fun `拍一拍手环帧带 poke 标志且普通帧不带`() {
        val frame = parser.buildPokeFrame(
            chatType = "private", targetId = "10001", senderId = "10001",
            senderName = "马化腾", targetName = "马化腾", content = "马化腾 拍了拍你", visible = true
        )
        val obj = com.google.gson.JsonParser.parseString(frame).asJsonObject
        assertEquals(1, obj.get("poke")?.asInt)
        assertEquals("push_message", obj.get("type")?.asString)
        assertEquals(true, obj.get("content")?.asString?.contains("拍了拍你"))
    }

    // ===== v2.14.0 智能自动渲染器 =====

    @Test
    fun `渲染器 json 卡片 meta prompt 优先`() {
        val card = """{"meta":{"prompt":"[分享]我看到一个很棒的视频，快来看!"}}"""
        assertEquals(
            "[分享]我看到一个很棒的视频，快来看!",
            OneBotParser.jsonCardSummary(card)
        )
    }

    @Test
    fun `渲染器 json 音乐卡片 title 加 singer`() {
        val card = """{"meta":{"music":{"title":"晴天","singer":"周杰伦"}}}"""
        assertEquals("[卡片] 晴天 · 周杰伦", OneBotParser.jsonCardSummary(card))
    }

    @Test
    fun `渲染器 json 非法回退卡片消息`() {
        assertEquals("[卡片消息]", OneBotParser.jsonCardSummary("not-json{{"))
    }

    @Test
    fun `渲染器 xml 取 title 与 brief`() {
        assertEquals("[卡片] 红包来袭", OneBotParser.xmlCardSummary("<msg><title>红包来袭</title></msg>"))
        assertEquals("[卡片] 签到成功", OneBotParser.xmlCardSummary("<msg brief=\"签到成功\"></msg>"))
    }

    @Test
    fun `渲染器 markdown 降纯文本`() {
        val out = OneBotParser.markdownToPlain("## 标题\n**加粗** 与 `code`\n- 列表项\n[锚](https://x.y)")
        assertEquals(false, out.contains('#'))
        assertEquals(false, out.contains("**"))
        assertEquals(true, out.contains("加粗"))
        assertEquals(true, out.contains("· 列表项"))
        assertEquals(true, out.contains("锚"))
    }

    @Test
    fun `渲染器 数组段新类型覆盖`() {
        fun seg(type: String, data: String) = com.google.gson.JsonParser.parseString(
            """[{"type":"$type","data":$data}]"""
        ).asJsonArray
        assertEquals("[合并转发]", parser.degradeContent(seg("forward", "{}")))
        assertEquals("[表情包]", parser.degradeContent(seg("mface", "{}")))
        assertEquals("[骰子]", parser.degradeContent(seg("dice", "{}")))
        assertEquals("[GIF]", parser.degradeContent(seg("image", """{"url":"https://x/a.gif"}""")))
        assertEquals("[文件] 报告.pdf", parser.degradeContent(seg("file", """{"name":"报告.pdf"}""")))
        assertEquals("正文", parser.degradeContent(seg("markdown", """{"content":"正文"}""")))
    }

    @Test
    fun `渲染器 json 段整链路`() {
        val card = """{"meta":{"music":{"title":"晴天","singer":"周杰伦"}}}"""
        val arr = com.google.gson.JsonParser.parseString(
            """[{"type":"text","data":{"text":"点歌:"}},{"type":"json","data":{"data":""}}]"""
        ).asJsonArray
        // OneBot 规范：json 段 data.data 为字符串化 JSON
        arr[1].asJsonObject.getAsJsonObject("data").addProperty("data", card)
        assertEquals("点歌:[卡片] 晴天 · 周杰伦", parser.degradeContent(arr))
    }

    @Test
    fun `渲染器 CQ 字符串 json 卡片与文件名`() {
        val cq = "[CQ:json,data={&#34;meta&#34;:{&#34;prompt&#34;:&#34;[分享]测试&#34;}}]看看"
        // data 值里的引号按 OneBot 规范转义为 &#44;/&#91; 等；这里用含引号的简化场景验证不炸
        assertEquals(true, parser.degradeCqString(cq).contains("看看"))
        val file = "[CQ:file,file=abc,name=报告.pdf]后续"
        assertEquals(true, parser.degradeCqString(file).contains("报告.pdf"))
        assertEquals(true, parser.degradeCqString("[CQ:forward,id=1]").contains("合并转发"))
    }

    @Test
    fun `渲染器 长度护栏 800`() {
        val long = "x".repeat(2000)
        val arr = com.google.gson.JsonParser.parseString(
            """[{"type":"text","data":{"text":"$long"}}]"""
        )
        val out = parser.degradeContent(arr)
        assertEquals(801, out.length)
        assertEquals(true, out.endsWith("…"))
    }

    @Test
    fun `渲染器 cqUnescape 四实体`() {
        assertEquals("[a,b]c&", OneBotParser.cqUnescape("&#91;a&#44;b&#93;c&amp;"))
    }
}
