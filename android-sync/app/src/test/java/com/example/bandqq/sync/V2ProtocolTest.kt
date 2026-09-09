package com.example.bandqq.sync

import com.example.bandqq.onebot.OneBotParser
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2 协议回归测试：
 * ① 未读计数（手机端为唯一事实源）
 * ② 历史翻页 before 锚点 + has_more
 * ③ 快捷回复 CQ 码剥离
 * ④ 会话帧预计算显示字段（手环零计算直渲染）
 * ⑤ read_chat 已读回执链路
 */
class V2ProtocolTest {

    private val parser = OneBotParser()

    // ---------- 未读计数 ----------

    @Test
    fun `可见联系人的他人消息累计未读`() {
        val store = MessageStore()
        store.setVisibleContacts(listOf(VisibleContact("u1", "private", "小明")))
        store.addMessage("u1", StoredMessage("private", "u1", "小明", "hi", 1000L, false))
        store.addMessage("u1", StoredMessage("private", "u1", "小明", "yo", 2000L, false))
        assertEquals(2, store.unreadOf("u1"))
    }

    @Test
    fun `自发消息不累计未读`() {
        val store = MessageStore()
        store.setVisibleContacts(listOf(VisibleContact("u1", "private", "小明")))
        store.addMessage("u1", StoredMessage("private", "u1", "我", "自己说的", 1000L, true))
        assertEquals(0, store.unreadOf("u1"))
    }

    @Test
    fun `非可见联系人不累计未读`() {
        val store = MessageStore()
        store.addMessage("ghost", StoredMessage("private", "ghost", "路人", "hi", 1000L, false))
        assertEquals(0, store.unreadOf("ghost"))
    }

    @Test
    fun `markRead 清零未读`() {
        val store = MessageStore()
        store.setVisibleContacts(listOf(VisibleContact("u1", "private", "小明")))
        store.addMessage("u1", StoredMessage("private", "u1", "小明", "hi", 1000L, false))
        assertEquals(1, store.unreadOf("u1"))
        store.markRead("u1")
        assertEquals(0, store.unreadOf("u1"))
    }

    @Test
    fun `未读持久化重启后恢复`() {
        val kv = InMemoryKv()
        val s1 = MessageStore(kv)
        s1.setVisibleContacts(listOf(VisibleContact("u1", "private", "小明")))
        s1.addMessage("u1", StoredMessage("private", "u1", "小明", "hi", 1000L, false))
        s1.addMessage("u1", StoredMessage("private", "u1", "小明", "hi2", 2000L, false))
        val s2 = MessageStore(kv)
        assertEquals(2, s2.unreadOf("u1"))
    }

    @Test
    fun `未读封顶99`() {
        val store = MessageStore()
        store.setVisibleContacts(listOf(VisibleContact("u1", "private", "小明")))
        repeat(150) { i ->
            store.addMessage("u1", StoredMessage("private", "u1", "小明", "m$i", (1000L + i), false))
        }
        assertEquals(99, store.unreadOf("u1"))
    }

    // ---------- 会话帧预计算字段 ----------

    @Test
    fun `conversation_list 帧含预计算字段`() {
        val store = MessageStore()
        store.setVisibleContacts(listOf(VisibleContact("10001", "private", "测试好友")))
        store.addMessage("10001", StoredMessage("private", "10001", "测试好友", "这是一条很长很长很长很长的预览消息内容", 1700000000000L, false))
        val frame = JsonParser.parseString(store.buildConversationFrame(1)).asJsonObject
        val item = frame.getAsJsonArray("list")[0].asJsonObject
        assertEquals("测试好友", item.get("name").asString)
        assertTrue(item.has("n9") && item.get("n9").asString.isNotEmpty())
        assertEquals("测", item.get("achar").asString)
        assertTrue(item.get("hue").asInt in 0..359)
        assertEquals(1, item.get("unread").asInt)
        assertTrue(item.get("prev").asString.length <= 18)
        assertTrue(item.has("tstr"))
        assertFalse(item.get("is_temporary").asBoolean)
    }

    @Test
    fun `非可见联系人会话标记为临时`() {
        val store = MessageStore()
        store.addMessage("ghost", StoredMessage("private", "ghost", "路人", "hi", 1700000000000L, false))
        val frame = JsonParser.parseString(store.buildConversationFrame(1)).asJsonObject
        val item = frame.getAsJsonArray("list")[0].asJsonObject
        assertTrue(item.get("is_temporary").asBoolean)
    }

    @Test
    fun `shortName 截短超过10字的名字`() {
        assertEquals("abcdefghi…", MessageStore.Display.shortName("abcdefghijk"))
        assertEquals("短名", MessageStore.Display.shortName("短名"))
    }

    @Test
    fun `hueOf 稳定且在范围内`() {
        val a = MessageStore.Display.hueOf("12345")
        assertEquals(a, MessageStore.Display.hueOf("12345"))
        assertTrue(a in 0..359)
    }

    @Test
    fun `timeStr 秒级时间也能正确展示`() {
        val now = System.currentTimeMillis()
        val todaySec = (now - 60_000L) / 1000L
        val s = MessageStore.Display.timeStr(todaySec)
        assertTrue("今天的时间串应为 HH:mm 格式: $s", s.contains(":"))
        assertEquals("", MessageStore.Display.timeStr(0L))
    }

    // ---------- 历史翻页 before 锚点 ----------

    /** 合理的历元毫秒基底（避免被 normalizeTime 误判为秒） */
    private val BASE = 1_700_000_000_000L

    private fun seedMessages(store: MessageStore, targetId: String, n: Int, startMs: Long): List<Long> {
        val times = mutableListOf<Long>()
        for (i in 0 until n) {
            val t = startMs + i * 1000L
            times.add(t)
            store.addMessage(targetId, StoredMessage("private", "u1", "小明", "msg$i", t, false))
        }
        return times
    }


    @Test
    fun `before 锚点返回更早的一页`() {
        val store = MessageStore()
        val times = seedMessages(store, "c1", 10, BASE)
        // 最新一页
        val first = JsonParser.parseString(store.buildHistoryFrame("c1", 5, 1)).asJsonObject
        assertEquals(5, first.getAsJsonArray("list").size())
        assertTrue(first.get("has_more").asBoolean)
        // 翻页：锚点为当前窗口最早一条
        val anchor = first.getAsJsonArray("list")[0].asJsonObject.get("time").asLong
        val older = JsonParser.parseString(store.buildHistoryFrame("c1", 5, 2, anchor)).asJsonObject
        val olderList = older.getAsJsonArray("list")
        assertEquals(5, olderList.size())
        assertTrue(older.get("before").asLong == anchor)
        for (e in olderList) {
            assertTrue(e.asJsonObject.get("time").asLong < anchor)
        }
        assertFalse(older.get("has_more").asBoolean)
        assertEquals(times[0], olderList[0].asJsonObject.get("time").asLong)
    }

    @Test
    fun `无更早消息时翻页返回空列表`() {
        val store = MessageStore()
        val times = seedMessages(store, "c1", 3, BASE)
        val older = JsonParser.parseString(store.buildHistoryFrame("c1", 5, 1, times[0])).asJsonObject
        assertEquals(0, older.getAsJsonArray("list").size())
        assertFalse(older.get("has_more").asBoolean)
    }

    @Test
    fun `大帧保护时强制 has_more`() {
        val store = MessageStore()
        // 构造长内容消息，超过 15000 字节帧保护
        val big = "x".repeat(3000)
        repeat(10) { i ->
            store.addMessage("big", StoredMessage("private", "u1", "小明", big + i, 1000L + i, false))
        }
        val frame = JsonParser.parseString(store.buildHistoryFrame("big", 10, 1)).asJsonObject
        val list = frame.getAsJsonArray("list")
        assertTrue(list.size() < 10)
        assertTrue(frame.get("has_more").asBoolean)
    }

    // ---------- 快捷回复 CQ 码 ----------

    @Test
    fun `stripCq 剥离 CQ 码`() {
        assertEquals("在的", OneBotParser.stripCq("[CQ:face,id=74]在的"))
        assertEquals("收到", OneBotParser.stripCq("收到[CQ:image,file=abc.jpg]"))
        assertEquals("纯文本", OneBotParser.stripCq("纯文本"))
    }

    @Test
    fun `parseQuickReplies 标签净化且内容保留CQ码`() {
        val list = OneBotParser.parseQuickReplies(
            listOf("[CQ:face,id=74]在的", "稍后回复你", "[CQ:face,id=1]", "😊好的", "很长很长很长很长很长的回复内容")
        )
        assertEquals(5, list.size)
        assertEquals("在的", list[0].label)
        assertEquals("[CQ:face,id=74]在的", list[0].content)
        assertEquals("稍后回复你", list[1].label)
        // 纯 CQ 码消息：标签回退为「回复」，内容保留 CQ 码原样发送
        assertEquals("回复", list[2].label)
        assertEquals("[CQ:face,id=1]", list[2].content)
        // emoji 被剥离后剩「好的」
        assertEquals("好的", list[3].label)
        // 超长标签截短到 6 字
        assertTrue(list[4].label.length <= 6)
    }

    @Test
    fun `parseQuickReplies 最多6条`() {
        val list = OneBotParser.parseQuickReplies((1..10).map { "回复$it" })
        assertEquals(6, list.size)
    }

    @Test
    fun `quick_replies 帧结构正确`() {
        val frame = JsonParser.parseString(
            OneBotParser.buildQuickRepliesFrame(OneBotParser.parseQuickReplies(listOf("[CQ:face,id=1]好", "OK")), 3)
        ).asJsonObject
        assertEquals("quick_replies", frame.get("type").asString)
        assertEquals(3, frame.get("seq").asInt)
        val arr = frame.getAsJsonArray("list")
        assertEquals(2, arr.size())
        assertEquals("好", arr[0].asJsonObject.get("label").asString)
        assertEquals("[CQ:face,id=1]好", arr[0].asJsonObject.get("content").asString)
    }

    // ---------- read_chat 链路 ----------

    @Test
    fun `read_chat 帧清零未读并回推会话列表`() {
        val store = MessageStore()
        store.setVisibleContacts(listOf(VisibleContact("u1", "private", "小明")))
        store.addMessage("u1", StoredMessage("private", "u1", "小明", "hi", 1000L, false))
        assertEquals(1, store.unreadOf("u1"))

        val oneBot = FakeOneBot { _, _, _ -> true }
        val broker = MessageBroker(parser, oneBot, store)
        val out = mutableListOf<String>()
        broker.bandSender = { out.add(it) }

        val handled = broker.onBandFrame("""{"type":"read_chat","seq":9,"target_id":"u1"}""")
        assertTrue(handled)
        assertEquals(0, store.unreadOf("u1"))
        // 回推了会话列表帧
        assertEquals(1, out.size)
        val frame = JsonParser.parseString(out[0]).asJsonObject
        assertEquals("conversation_list", frame.get("type").asString)
        assertEquals(0, frame.getAsJsonArray("list")[0].asJsonObject.get("unread").asInt)
    }

    @Test
    fun `get_history 带 before 参数下发翻页帧`() {
        val store = MessageStore()
        val times = seedMessages(store, "c1", 8, BASE)
        val oneBot = FakeOneBot { _, _, _ -> true }
        val broker = MessageBroker(parser, oneBot, store)
        val out = mutableListOf<String>()
        broker.bandSender = { out.add(it) }
        val anchor = times[6]
        val ok = broker.onBandFrame("""{"type":"get_history","seq":2,"target_id":"c1","limit":5,"before":$anchor}""")
        assertTrue(ok)
        assertEquals(1, out.size)
        val frame = JsonParser.parseString(out[0]).asJsonObject
        assertEquals("history_list", frame.get("type").asString)
        // 错点 times[6]=7000 之前共 6 条(1000..6000)，取近镐5条(2000..6000)，仍有更早 → has_more=true
        val list = frame.getAsJsonArray("list")
        assertEquals(5, list.size())
        assertEquals(times[1], list[0].asJsonObject.get("time").asLong)
        assertTrue(frame.get("has_more").asBoolean)
    }
}
