package com.example.bandqq.sync

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class StoredMessage(
    val messageType: String,
    val senderId: String,
    val senderName: String,
    val content: String,
    val time: Long,
    val isSelf: Boolean = false,
    val messageId: String = "",  // OneBot message_id（旧持久化数据无此字段，默认空，撤回定位用）
    val atMe: Boolean = false    // CQ:at 指向我/全体（手环端高亮；旧数据默认 false）
)

data class ConversationInfo(
    val id: String,
    val type: String,
    val name: String,
    val lastMsg: String,
    val time: Long
)

data class VisibleContact(
    val id: String,
    val type: String,
    val name: String
)

interface KvStorage {
    fun get(key: String, default: String): String
    fun set(key: String, value: String)
    fun remove(key: String)
}

class InMemoryKv : KvStorage {
    private val map = LinkedHashMap<String, String>()
    override fun get(key: String, default: String) = map[key] ?: default
    override fun set(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
}

class MessageStore(private val storage: KvStorage = InMemoryKv()) {

    companion object {
        /** 统一时间戳为毫秒：小于 100 000 000 000（约 1973 年）视为 Unix 秒，转为毫秒。 */
        private fun normalizeTime(t: Long): Long = if (t in 1 until 100_000_000_000L) t * 1000L else t

        /** 撤回消息的展示文案（借鉴 Stapxs 撤回提示；会话预览同步显示） */
        const val RECALL_MARK = "[消息已撤回]"
    }

    private val MESSAGES_KEY = "chat_messages"
    private val CONVERSATIONS_KEY = "chat_conversations"
    private val VISIBLE_KEY = "visible_contacts"
    private val CACHED_KEY = "contact_cache"
    private val UNREAD_KEY = "chat_unread"

    private val messagesByTarget = LinkedHashMap<String, MutableList<StoredMessage>>()
    private val MAX_MESSAGES = 200
    private val MAX_CONVERSATIONS = 100

    /** 未读计数（手机端为唯一事实源，手环只展示）；按会话持久化 */
    private val unreadByTarget = LinkedHashMap<String, Int>()

    /** 会话是否存在未读 @我 消息（手环列表显示「@我」提示，读后清除） */
    private val atUnreadByTarget = HashSet<String>()
    private val AT_UNREAD_KEY = "chat_at_unread"

    /** 联系人可见性：以手机端为主存储，持久化 */
    private var visibleContacts: MutableList<VisibleContact> = loadVisibleContacts()
    private var cachedContacts: MutableList<VisibleContact> = loadCachedContacts()
    private val duplicates = HashSet<String>()

    init {
        visibleContacts = loadVisibleContacts()
        cachedContacts = loadCachedContacts()
        loadUnread()
        loadAtUnread()
        loadPersistedMessages()
    }

    private fun loadAtUnread() {
        runCatching {
            val raw = storage.get(AT_UNREAD_KEY, "[]")
            val arr = JsonParser.parseString(raw).asJsonArray
            for (e in arr) atUnreadByTarget.add(e.asString)
        }
    }

    private fun persistAtUnread() {
        val arr = JsonArray()
        for (k in atUnreadByTarget) arr.add(k)
        storage.set(AT_UNREAD_KEY, arr.toString())
    }

    private fun loadUnread() {
        try {
            val raw = storage.get(UNREAD_KEY, "{}")
            val obj = JsonParser.parseString(raw).asJsonObject
            for ((k, v) in obj.entrySet()) {
                val n = v.asInt
                if (n > 0) unreadByTarget[k] = n.coerceAtMost(99)
            }
        } catch (_: Exception) {
        }
    }

    private fun persistUnread() {
        val obj = JsonObject()
        for ((k, v) in unreadByTarget) {
            if (v > 0) obj.addProperty(k, v)
        }
        storage.set(UNREAD_KEY, obj.toString())
    }

    /** 从持久化存储恢复全部消息与会话索引 */
    private fun loadPersistedMessages() {
        try {
            val raw = storage.get(MESSAGES_KEY, "{}")
            val obj = JsonParser.parseString(raw).asJsonObject
            for ((targetId, el) in obj.entrySet()) {
                val arr = el.asJsonArray
                val list = mutableListOf<StoredMessage>()
                for (e in arr) {
                    val o = e.asJsonObject
                    list.add(
                        StoredMessage(
                            messageType = o.get("message_type")?.asString ?: "private",
                            senderId = o.get("sender_id")?.asString ?: "",
                            senderName = o.get("sender_name")?.asString ?: "",
                            content = o.get("content")?.asString ?: "",
                            time = normalizeTime(o.get("time")?.asLong ?: 0L),
                            isSelf = o.get("is_self")?.asBoolean ?: false,
                            messageId = o.get("message_id")?.asString ?: "",
                            atMe = o.get("at_me")?.asBoolean ?: false
                        )
                    )
                }
                if (list.isNotEmpty()) messagesByTarget[targetId] = list
            }
        } catch (e: Exception) {
            // 存储损坏时忽略，从空开始
        }
    }

    /** 将全部消息写入持久化存储 */
    private fun persistMessages() {
        val root = JsonObject()
        for ((targetId, list) in messagesByTarget) {
            val arr = JsonArray()
            for (m in list) {
                val o = JsonObject()
                o.addProperty("message_type", m.messageType)
                o.addProperty("sender_id", m.senderId)
                o.addProperty("sender_name", m.senderName)
                o.addProperty("content", m.content)
                o.addProperty("time", m.time)
                o.addProperty("is_self", m.isSelf)
                if (m.messageId.isNotEmpty()) o.addProperty("message_id", m.messageId)
                if (m.atMe) o.addProperty("at_me", true)
                arr.add(o)
            }
            root.add(targetId, arr)
        }
        storage.set(MESSAGES_KEY, root.toString())
    }

    fun addMessage(targetId: String, msg: StoredMessage) {
        val list = messagesByTarget.getOrPut(targetId) { mutableListOf() }
        // 统一为毫秒，避免传入秒/毫秒混用时同会话排序错乱
        val normalized = StoredMessage(
            messageType = msg.messageType,
            senderId = msg.senderId,
            senderName = msg.senderName,
            content = msg.content,
            time = normalizeTime(msg.time),
            isSelf = msg.isSelf,
            messageId = msg.messageId,
            atMe = msg.atMe
        )
        val dedup = "$targetId|${normalized.senderId}|${normalized.time}|${normalized.content}"
        val existing = list.any { it.time == normalized.time && it.content == normalized.content }
        if (duplicates.add(dedup) || !existing) {
            list.add(normalized)
            // 未读计数：仅非自发消息且目标在可见联系人中才累计，封顶 99
            if (!normalized.isSelf && isVisibleContact(targetId)) {
                val next = (unreadByTarget[targetId] ?: 0) + 1
                unreadByTarget[targetId] = next.coerceAtMost(99)
                if (normalized.atMe) atUnreadByTarget.add(targetId)
            }
        }
        // 保持按时间升序，历史帧与手环端 upsert 都依赖列表有序
        list.sortBy { it.time }
        while (list.size > MAX_MESSAGES) list.removeAt(0)
        // 去重集合上限：防长期运行时缓慢增长（清空后最坏情况重复入库一条，可接受）
        if (duplicates.size > 1000) duplicates.clear()
        persistMessages()
        persistUnread()
        persistAtUnread()
    }

    /** 手环打开聊天时上报已读：清零未读计数 + 清除 @我 未读提示 */
    fun markRead(targetId: String) {
        val changed = unreadByTarget.remove(targetId) != null
        val atChanged = atUnreadByTarget.remove(targetId)
        if (changed || atChanged != null) {
            persistUnread()
            persistAtUnread()
        }
    }

    /**
     * 标记一条消息被撤回：将内容替换为撤回标记（保留原占位，会话预览同步变化）。
     * 返回被撤回消息的原始时间戳（手环端凭此原位替换，聊天页实时变灰）；找不到返回 0。
     */
    fun recallMessage(targetId: String, messageId: String): Long {
        if (messageId.isBlank()) return 0L
        val list = messagesByTarget[targetId] ?: return 0L
        val idx = list.indexOfFirst { it.messageId == messageId }
        if (idx < 0) return 0L
        val old = list[idx]
        if (old.content == RECALL_MARK) return 0L
        list[idx] = old.copy(content = RECALL_MARK)
        persistMessages()
        return old.time
    }

    fun unreadOf(targetId: String): Int = unreadByTarget[targetId] ?: 0

    /** 该会话是否有未读 @我 消息（会话帧 cat 字段） */
    fun hasUnreadAt(targetId: String): Boolean = atUnreadByTarget.contains(targetId)

    /** 所有会话未读消息总数（导航栏角标数据源） */
    fun unreadTotal(): Int = unreadByTarget.values.sum()

    fun getHistory(targetId: String, limit: Int): List<StoredMessage> {
        val list = messagesByTarget[targetId] ?: return emptyList()
        val from = (list.size - limit).coerceAtLeast(0)
        return list.subList(from, list.size)
    }

    /** 按会话列出全部历史消息（供手机端查看页） */
    fun getAllMessages(targetId: String): List<StoredMessage> {
        return messagesByTarget[targetId]?.toList() ?: emptyList()
    }

    fun getAllTargetIds(): List<String> {
        return messagesByTarget.keys.toList()
    }

    fun getConversations(): List<ConversationInfo> {
        val out = mutableListOf<ConversationInfo>()
        for ((id, list) in messagesByTarget) {
            if (list.isEmpty()) continue
            val last = list.last()
            out.add(
                ConversationInfo(
                    id = id,
                    type = last.messageType,
                    name = conversationName(id, last.messageType, last.senderName),
                    lastMsg = last.content,
                    time = last.time
                )
            )
        }
        out.sortByDescending { it.time }
        val hasMessages = out.map { it.id }.toSet()
        for (c in visibleContacts) {
            if (c.id.isNotEmpty() && c.id !in hasMessages) {
                out.add(ConversationInfo(id = c.id, type = c.type, name = c.name, lastMsg = "", time = 0L))
            }
        }
        return out.subList(0, out.size.coerceAtMost(MAX_CONVERSATIONS))
    }

    /**
     * 解析会话显示名：群会话优先用缓存的群名，找不到才用发送者名；私聊用发送者名。
     */
    fun conversationName(targetId: String, messageType: String, fallback: String): String {
        if (messageType == "group") {
            val cached = cachedContacts.firstOrNull { it.type == "group" && it.id == targetId }
            if (cached != null && cached.name.isNotBlank()) return cached.name
        }
        // 历史数据中 self 消息 senderName 可能为"我"，此时会话名不应显示"我"，
        // 优先用联系人缓存名，其次是回退 targetId
        if (fallback == "我" || fallback == "self") {
            val contact = contactName(targetId)
            if (contact.isNotBlank()) return contact
            val cached = cachedContacts.firstOrNull { it.id == targetId }
            if (cached != null && cached.name.isNotBlank()) return cached.name
            return targetId
        }
        return fallback.ifBlank { targetId }
    }

    fun clearHistory(targetId: String) {
        messagesByTarget.remove(targetId)
        persistMessages()
    }

    fun clearAllHistory() {
        messagesByTarget.clear()
        duplicates.clear()
        unreadByTarget.clear()
        // v2.7.0：@我 未读提示一并清除（此前遗漏，清空后手环列表仍残留「@我」角标）
        atUnreadByTarget.clear()
        persistMessages()
        persistUnread()
        persistAtUnread()
    }

    private fun loadCachedContacts(): MutableList<VisibleContact> {
        val raw = storage.get(CACHED_KEY, "[]")
        return try {
            val arr = JsonParser.parseString(raw).asJsonArray
            val out = mutableListOf<VisibleContact>()
            for (e in arr) {
                val o = e.asJsonObject
                out.add(
                    VisibleContact(
                        id = o.get("id")?.asString ?: "",
                        type = o.get("type")?.asString ?: "private",
                        name = o.get("name")?.asString ?: ""
                    )
                )
            }
            out
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun persistCachedContacts() {
        val arr = JsonArray()
        for (c in cachedContacts) {
            val o = JsonObject()
            o.addProperty("id", c.id)
            o.addProperty("type", c.type)
            o.addProperty("name", c.name)
            arr.add(o)
        }
        storage.set(CACHED_KEY, arr.toString())
    }

    private fun loadVisibleContacts(): MutableList<VisibleContact> {
        val raw = storage.get(VISIBLE_KEY, "[]")
        return try {
            val arr = JsonParser.parseString(raw).asJsonArray
            val out = mutableListOf<VisibleContact>()
            for (e in arr) {
                val o = e.asJsonObject
                out.add(
                    VisibleContact(
                        id = o.get("id")?.asString ?: "",
                        type = o.get("type")?.asString ?: "private",
                        name = o.get("name")?.asString ?: ""
                    )
                )
            }
            out
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun persistVisibleContacts() {
        val arr = JsonArray()
        for (c in visibleContacts) {
            val o = JsonObject()
            o.addProperty("id", c.id)
            o.addProperty("type", c.type)
            o.addProperty("name", c.name)
            arr.add(o)
        }
        storage.set(VISIBLE_KEY, arr.toString())
    }

    fun getVisibleContacts(): List<VisibleContact> = visibleContacts.toList()

    @Synchronized
    fun getCachedContacts(): List<VisibleContact> = cachedContacts.toList()

    @Synchronized
    fun setCachedContacts(list: List<VisibleContact>) {
        cachedContacts = list.distinctBy { it.id }.toMutableList()
        persistCachedContacts()
    }

    fun setVisibleContacts(list: List<VisibleContact>) {
        visibleContacts = list.distinctBy { it.id }.toMutableList()
        persistVisibleContacts()
    }

    fun isVisibleContact(id: String): Boolean = visibleContacts.any { it.id == id }

    /** 按 id 查找联系人/群显示名（优先可见联系人，再找缓存联系人），找不到返回空串 */
    fun contactName(id: String): String {
        val hit = (visibleContacts + cachedContacts).firstOrNull { it.id == id }
        return hit?.name ?: ""
    }

    fun buildVisibleContactsFrame(seq: Int): String {
        val obj = JsonObject()
        obj.addProperty("type", "visible_contacts")
        obj.addProperty("seq", seq)
        val arr = JsonArray()
        for (c in visibleContacts) {
            val o = JsonObject()
            o.addProperty("id", c.id)
            o.addProperty("type", c.type)
            o.addProperty("name", c.name)
            // 预计算显示字段：手环端零计算直接渲染（性能架构：重活全在手机端）
            o.addProperty("n9", Display.shortName(c.name))
            o.addProperty("achar", Display.avatarChar(c.name, c.id))
            o.addProperty("hue", Display.hueOf(c.id))
            arr.add(o)
        }
        obj.add("contacts", arr)
        return obj.toString()
    }

    /**
     * 构建历史帧。
     * @param before 翻页锚点：只返回 time < before 的更早消息（0 表示拉最新一页）
     * 帧内带 has_more 告知手环是否还有更早消息，避免无效续拉。
     */
    fun buildHistoryFrame(targetId: String, limit: Int, seq: Int, before: Long = 0L): String {
        val obj = JsonObject()
        obj.addProperty("type", "history_list")
        obj.addProperty("seq", seq)
        obj.addProperty("target_id", targetId)
        obj.addProperty("before", before)
        val all = messagesByTarget[targetId] ?: emptyList()
        // 取候选：锚点之前的消息（时间升序），取倒数 limit 条（靠近锚点的）
        val candidates = if (before > 0L) all.filter { it.time < before } else all
        val candidatesCount = candidates.size
        val from = (candidatesCount - limit).coerceAtLeast(0)
        val window = candidates.subList(from, candidatesCount)
        obj.addProperty("has_more", from > 0)
        val arr = JsonArray()
        // 历史帧过大时可能超出互联通道单帧上限导致下发失败，这里对总帧大小做保护
        val maxBytes = 15000
        var bytes = obj.toString().length
        for (m in window) {
            val o = JsonObject()
            o.addProperty("message_type", m.messageType)
            o.addProperty("sender_id", m.senderId)
            o.addProperty("sender_name", m.senderName)
            o.addProperty("content", m.content)
            o.addProperty("time", m.time)
            o.addProperty("is_self", m.isSelf)
            // 样式标志只在为真时下发（省字节）：rc=已撤回（手环灰显），at=@我（手环高亮）
            if (m.content == RECALL_MARK) o.addProperty("rc", 1)
            if (m.atMe) o.addProperty("at", 1)
            val itemBytes = o.toString().length
            if (bytes + itemBytes > maxBytes && arr.size() > 0) {
                obj.addProperty("has_more", true)
                break
            }
            arr.add(o)
            bytes += itemBytes
        }
        obj.add("list", arr)
        return obj.toString()
    }

    fun buildConversationFrame(seq: Int): String {
        val obj = JsonObject()
        obj.addProperty("type", "conversation_list")
        obj.addProperty("seq", seq)
        val arr = JsonArray()
        for (c in getConversations()) {
            val o = JsonObject()
            o.addProperty("id", c.id)
            o.addProperty("type", c.type)
            o.addProperty("name", c.name)
            o.addProperty("last_msg", c.lastMsg)
            o.addProperty("time", c.time)
            // 预计算显示字段：手环零计算直接渲染
            o.addProperty("n9", Display.shortName(c.name))
            o.addProperty("achar", Display.avatarChar(c.name, c.id))
            o.addProperty("hue", Display.hueOf(c.id))
            o.addProperty("unread", unreadOf(c.id))
            o.addProperty("prev", Display.previewOf(c.lastMsg))
            o.addProperty("tstr", Display.timeStr(c.time))
            o.addProperty("is_temporary", !isVisibleContact(c.id))
            // cat=该会话有未读 @我 消息（手环列表显示「@我」角标）
            if (hasUnreadAt(c.id)) o.addProperty("cat", 1)
            arr.add(o)
        }
        obj.add("list", arr)
        return obj.toString()
    }

    object Display {
        /** 会话名截短（手环列表宽度有限） */
        fun shortName(name: String, max: Int = 10): String {
            val n = name.trim()
            return if (n.length <= max) n else n.take(max - 1) + "…"
        }

        /** 头像字符：名称首字符，空则用问号 */
        fun avatarChar(name: String, id: String): String {
            val n = name.trim()
            return (n.firstOrNull() ?: id.firstOrNull() ?: '?').toString()
        }

        /** 由 targetId 稳定散列出色相（0-359），手环据此生成头像底色 */
        fun hueOf(id: String): Int {
            var h = 0
            for (ch in id) h = (h * 31 + ch.code) and 0x7FFFFFFF
            return h % 360
        }

        /** 预览文本截短（单行展示） */
        fun previewOf(lastMsg: String, max: Int = 18): String {
            val s = lastMsg.replace("\n", " ").trim()
            return if (s.length <= max) s else s.take(max - 1) + "…"
        }

        /** 时间展示串：今天 HH:mm，今年 M/d，往年 yy/M/d（手机端预算好，手环零日期运算）；秒级时间自动归一化 */
        fun timeStr(timeMs: Long, nowMs: Long = System.currentTimeMillis()): String {
            if (timeMs <= 0L) return ""
            val normalized = if (timeMs < 100_000_000_000L) timeMs * 1000L else timeMs
            val d = Date(normalized)
            val calNow = java.util.Calendar.getInstance().apply { timeInMillis = nowMs }
            val calMsg = java.util.Calendar.getInstance().apply { timeInMillis = normalized }
            val sameDay = calNow.get(java.util.Calendar.YEAR) == calMsg.get(java.util.Calendar.YEAR) &&
                calNow.get(java.util.Calendar.DAY_OF_YEAR) == calMsg.get(java.util.Calendar.DAY_OF_YEAR)
            return if (sameDay) {
                SimpleDateFormat("HH:mm", Locale.US).format(d)
            } else if (calNow.get(java.util.Calendar.YEAR) == calMsg.get(java.util.Calendar.YEAR)) {
                SimpleDateFormat("M/d", Locale.US).format(d)
            } else {
                SimpleDateFormat("yy/M/d", Locale.US).format(d)
            }
        }
    }
}
