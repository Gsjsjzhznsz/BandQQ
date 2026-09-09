package com.example.bandqq.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageStoreTest {

    private val store = MessageStore()

    @Test
    fun `写入后能取回历史`() {
        store.addMessage("123", StoredMessage("group", "456", "张三", "你好", 1700000000L))
        val history = store.getHistory("123", 50)
        assertEquals(1, history.size)
        assertEquals("你好", history[0].content)
    }

    @Test
    fun `会话按时间倒序`() {
        store.addMessage("a", StoredMessage("group", "1", "A", "x", 100L))
        store.addMessage("b", StoredMessage("group", "2", "B", "y", 200L))
        val convs = store.getConversations()
        assertEquals("b", convs[0].id)
    }

    @Test
    fun `秒与毫秒混入时历史依旧按时间升序`() {
        val store = MessageStore()
        store.addMessage("123", StoredMessage("group", "2", "B", "第二条", 1700000001L))   // 秒 = 1700000001000ms
        store.addMessage("123", StoredMessage("group", "1", "A", "第一条", 1700000000000L)) // 毫秒 = 1700000000s
        store.addMessage("123", StoredMessage("group", "3", "C", "插中间", 1700000000001L)) // 毫秒，介于两者之间
        val history = store.getHistory("123", 50)
        assertEquals("第一条", history[0].content)
        assertEquals("插中间", history[1].content)
        assertEquals("第二条", history[2].content)
    }

    @Test
    fun `同一消息重复 add 不去重也不乱序`() {
        val store = MessageStore()
        store.addMessage("123", StoredMessage("group", "1", "A", "x", 1700000000L))
        store.addMessage("123", StoredMessage("group", "1", "A", "x", 1700000000L))
        assertEquals(1, store.getHistory("123", 50).size)
    }

    @Test
    fun `history_list 帧包含消息`() {
        store.addMessage("123", StoredMessage("group", "456", "张三", "你好", 1700000000L))
        val frame = store.buildHistoryFrame("123", 50, 4)
        assertTrue(frame.contains("\"type\":\"history_list\""))
        assertTrue(frame.contains("\"target_id\":\"123\""))
        assertTrue(frame.contains("你好"))
    }

    @Test
    fun `conversation_list 帧包含会话`() {
        store.addMessage("123", StoredMessage("group", "456", "张三", "你好", 1700000000L))
        val frame = store.buildConversationFrame(3)
        assertTrue(frame.contains("\"type\":\"conversation_list\""))
        assertTrue(frame.contains("\"id\":\"123\""))
    }

    @Test
    fun `clearAllHistory 清空全部会话`() {
        store.addMessage("a", StoredMessage("group", "1", "A", "x", 100L))
        store.addMessage("b", StoredMessage("group", "2", "B", "y", 200L))
        store.clearAllHistory()
        assertTrue(store.getConversations().isEmpty())
    }

    @Test
    fun `消息持久化重启后可恢复`() {
        val kv = InMemoryKv()
        val s1 = MessageStore(kv)
        s1.addMessage("123", StoredMessage("group", "456", "张三", "你好", 1700000000L, isSelf = false))
        s1.addMessage("123", StoredMessage("private", "456", "李四", "收到", 1700000100L, isSelf = true))

        // 模拟重启：用同一 KV 新建 Store
        val s2 = MessageStore(kv)
        val history = s2.getAllMessages("123")
        assertEquals(2, history.size)
        assertEquals("你好", history[0].content)
        assertEquals("收到", history[1].content)
        assertEquals(true, history[1].isSelf)

        val convs = s2.getConversations()
        assertEquals(1, convs.size)
        assertEquals("123", convs[0].id)
        assertEquals("李四", convs[0].name)
    }

    @Test
    fun `清除单个会话持久化后不恢复`() {
        val kv = InMemoryKv()
        val s1 = MessageStore(kv)
        s1.addMessage("a", StoredMessage("group", "1", "A", "x", 100L))
        s1.addMessage("b", StoredMessage("group", "2", "B", "y", 200L))
        s1.clearHistory("a")
        val s2 = MessageStore(kv)
        assertEquals(1, s2.getConversations().size)
        assertTrue(s2.getAllMessages("a").isEmpty())
    }

    @Test
    fun `visibleContacts 持久化往返`() {
        val kv = InMemoryKv()
        val s1 = MessageStore(kv)
        s1.setVisibleContacts(listOf(VisibleContact("111", "private", "小明")))
        val reload = MessageStore(kv)
        assertEquals(listOf(VisibleContact("111", "private", "小明")), reload.getVisibleContacts())
    }

    @Test
    fun `isVisibleContact 判定`() {
        val store = MessageStore()
        store.setVisibleContacts(listOf(VisibleContact("111", "private", "小明")))
        assertTrue(store.isVisibleContact("111"))
        assertTrue(!store.isVisibleContact("222"))
    }

    @Test
    fun `visible_contacts 帧包含联系人列表`() {
        val store = MessageStore()
        store.setVisibleContacts(listOf(VisibleContact("111", "private", "小明"), VisibleContact("222", "group", "群A")))
        val frame = store.buildVisibleContactsFrame(7)
        assertTrue(frame.contains("\"type\":\"visible_contacts\""))
        assertTrue(frame.contains("\"id\":\"111\""))
        assertTrue(frame.contains("\"type\":\"group\""))
        assertTrue(frame.contains("\"name\":\"群A\""))
    }

    @Test
    fun `conversation_list 帧包含 type 字段`() {
        val store = MessageStore()
        store.addMessage("123", StoredMessage("group", "456", "张三", "你好", 1700000000L))
        val frame = store.buildConversationFrame(3)
        assertTrue(frame.contains("\"type\":\"group\""))
    }

    @Test
    fun `cachedContacts 持久化往返`() {
        val kv = InMemoryKv()
        val s1 = MessageStore(kv)
        s1.setCachedContacts(listOf(VisibleContact("111", "private", "小明"), VisibleContact("222", "group", "群A")))
        val reload = MessageStore(kv)
        assertEquals(
            listOf(VisibleContact("111", "private", "小明"), VisibleContact("222", "group", "群A")),
            reload.getCachedContacts()
        )
    }

    @Test
    fun `cachedContacts 覆盖写入`() {
        val store = MessageStore()
        store.setCachedContacts(listOf(VisibleContact("111", "private", "小明")))
        store.setCachedContacts(listOf(VisibleContact("222", "group", "群B")))
        assertEquals(listOf(VisibleContact("222", "group", "群B")), store.getCachedContacts())
    }

    @Test
    fun `cachedContacts 损坏数据回退为空`() {
        val kv = InMemoryKv()
        kv.set("contact_cache", "not-json")
        val store = MessageStore(kv)
        assertEquals(emptyList<VisibleContact>(), store.getCachedContacts())
    }

    @Test
    fun `群会话名使用缓存群名而非发送者名`() {
        val store = MessageStore()
        store.setCachedContacts(listOf(VisibleContact("123", "group", "技术交流群")))
        store.addMessage("123", StoredMessage("group", "456", "张三", "你好", 1700000000L))
        val convs = store.getConversations()
        assertEquals("技术交流群", convs[0].name)
    }

    @Test
    fun `无缓存时群会话名回退为发送者名`() {
        val store = MessageStore()
        store.addMessage("123", StoredMessage("group", "456", "张三", "你好", 1700000000L))
        val convs = store.getConversations()
        assertEquals("张三", convs[0].name)
    }

    @Test
    fun `私聊会话名回退为发送者名`() {
        val store = MessageStore()
        store.addMessage("555", StoredMessage("private", "456", "李四", "你好", 1720000000L))
        val convs = store.getConversations()
        assertEquals("李四", convs[0].name)
    }

    @Test
    fun `可见联系人无消息也出现在会话列表`() {
        val store = MessageStore()
        store.setVisibleContacts(listOf(VisibleContact("111", "private", "小明"), VisibleContact("222", "group", "群A")))
        val convs = store.getConversations()
        assertEquals(2, convs.size)
        assertTrue(convs.any { it.id == "111" && it.name == "小明" })
        assertTrue(convs.any { it.id == "222" && it.name == "群A" })
    }

    @Test
    fun `可见联系人已收消息不重复出现`() {
        val store = MessageStore()
        store.setVisibleContacts(listOf(VisibleContact("123", "group", "技术交流群")))
        store.addMessage("123", StoredMessage("group", "456", "张三", "你好", 1700000000L))
        val convs = store.getConversations()
        assertEquals(1, convs.size)
        assertEquals("123", convs[0].id)
        assertEquals("你好", convs[0].lastMsg)
    }

    @Test
    fun `conversation_list 帧包含无消息可见联系人`() {
        val store = MessageStore()
        store.setVisibleContacts(listOf(VisibleContact("111", "private", "小明")))
        val frame = store.buildConversationFrame(3)
        assertTrue(frame.contains("\"id\":\"111\""))
        assertTrue(frame.contains("\"name\":\"小明\""))
    }
}
