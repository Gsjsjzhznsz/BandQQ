package com.example.bandqq.sync

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class StoredMessage(
    val messageType: String,
    val senderId: String,
    val senderName: String,
    val content: String,
    val time: Long,
    val isSelf: Boolean = false,
    val messageId: String = "",
    val messageSeq: String = ""   // NapCat 历史分页锚点（message_seq），翻页拉取优先用它
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
    /** 全部已存 key（按会话分 key 持久化后，冷启动恢复需要枚举） */
    fun keys(): Set<String>
}

class InMemoryKv : KvStorage {
    private val map = LinkedHashMap<String, String>()
    override fun get(key: String, default: String) = map[key] ?: default
    override fun set(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
    override fun keys(): Set<String> = map.keys.toSet()
}

class MessageStore(private val storage: KvStorage = InMemoryKv()) {

    companion object {
        /** 统一时间戳为毫秒：小于 100 000 000 000（约 1973 年）视为 Unix 秒，转为毫秒。 */
        private fun normalizeTime(t: Long): Long = if (t in 1 until 100_000_000_000L) t * 1000L else t
    }

    private val MESSAGES_KEY = "chat_messages" // v1.1.0 旧版单 key，仅用于迁移读取
    private val MSG_KEY_PREFIX = "chat_messages_" // v1.1.1 按会话分 key，避免单 key 过大写入失败/丢失
    private val CONVERSATIONS_KEY = "chat_conversations"
    private val VISIBLE_KEY = "visible_contacts"
    private val CACHED_KEY = "contact_cache"

    private val messagesByTarget = LinkedHashMap<String, MutableList<StoredMessage>>()
    private val MAX_MESSAGES = 200
    private val MAX_CONVERSATIONS = 100

    /** 联系人可见性：以手机端为主存储，持久化 */
    private var visibleContacts: MutableList<VisibleContact> = loadVisibleContacts()
    private var cachedContacts: MutableList<VisibleContact> = loadCachedContacts()
    private val duplicates = HashSet<String>()

    init {
        visibleContacts = loadVisibleContacts()
        cachedContacts = loadCachedContacts()
        loadPersistedMessages()
    }

    /** 解析单个会话的消息数组 JSON */
    private fun parseMessageList(raw: String): MutableList<StoredMessage> {
        val list = mutableListOf<StoredMessage>()
        val arr = JsonParser.parseString(raw).asJsonArray
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
                    messageSeq = o.get("message_seq")?.asString ?: ""
                )
            )
        }
        return list
    }

    /** 从持久化存储恢复全部消息与会话索引（v1.1.1 分 key；首次运行时从旧单 key 迁移） */
    private fun loadPersistedMessages() {
        try {
            // 1) 新版分 key 读取
            for (k in storage.keys()) {
                if (!k.startsWith(MSG_KEY_PREFIX)) continue
                val targetId = k.removePrefix(MSG_KEY_PREFIX)
                if (targetId.isEmpty()) continue
                val raw = storage.get(k, "[]")
                if (raw == "[]") continue
                val list = try { parseMessageList(raw) } catch (e: Exception) { mutableListOf() }
                if (list.isNotEmpty()) {
                    list.sortBy { it.time }
                    messagesByTarget[targetId] = list
                }
            }
            // 2) 旧版单 key 迁移：读取后分拆写入并删除旧 key
            val legacy = storage.get(MESSAGES_KEY, "")
            if (legacy.isNotBlank()) {
                try {
                    val obj = JsonParser.parseString(legacy).asJsonObject
                    for ((targetId, el) in obj.entrySet()) {
                        if (messagesByTarget.containsKey(targetId)) continue
                        val list = try { parseMessageList(el.toString()) } catch (e: Exception) { mutableListOf() }
                        if (list.isNotEmpty()) {
                            list.sortBy { it.time }
                            messagesByTarget[targetId] = list
                        }
                    }
                    persistMessages()
                } catch (e: Exception) {
                }
                storage.remove(MESSAGES_KEY)
            }
        } catch (e: Exception) {
            // 存储损坏时忽略，从空开始
        }
    }

    /** 将全部消息写入持久化存储（按会话分 key，单 key 体积可控） */
    private fun persistMessages() {
        for ((targetId, list) in messagesByTarget) {
            val json = messagesJson(list)
            val key = MSG_KEY_PREFIX + targetId
            if (storage.get(key, "") != json) storage.set(key, json)
        }
        // 清理已不存在的会话 key
        val liveKeys = messagesByTarget.keys.map { MSG_KEY_PREFIX + it }.toSet()
        for (k in storage.keys()) {
            if (k.startsWith(MSG_KEY_PREFIX) && k !in liveKeys) storage.remove(k)
        }
    }

    private fun messagesJson(list: List<StoredMessage>): String {
        val arr = JsonArray()
        for (m in list) {
            val o = JsonObject()
            o.addProperty("message_type", m.messageType)
            o.addProperty("sender_id", m.senderId)
            o.addProperty("sender_name", m.senderName)
            o.addProperty("content", m.content)
            o.addProperty("time", m.time)
            o.addProperty("is_self", m.isSelf)
            if (m.messageId.isNotBlank()) o.addProperty("message_id", m.messageId)
            if (m.messageSeq.isNotBlank()) o.addProperty("message_seq", m.messageSeq)
            arr.add(o)
        }
        return arr.toString()
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
            messageSeq = msg.messageSeq
        )
        val dedup = "$targetId|${normalized.senderId}|${normalized.time}|${normalized.content}"
        // 去重集合上限保护：长期运行时防止无限增长
        if (duplicates.size > 5000) duplicates.clear()
        val existing = list.any { it.time == normalized.time && it.content == normalized.content }
        if (duplicates.add(dedup) || !existing) {
            list.add(normalized)
        } else if (normalized.messageId.isNotBlank() || normalized.messageSeq.isNotBlank()) {
            // 补锚点：老数据无 message_id/message_seq，翻页拉取需要锚点，回填到已有记录上
            val idx = list.indexOfFirst { it.time == normalized.time && it.content == normalized.content }
            if (idx >= 0) {
                val old = list[idx]
                if (old.messageId.isBlank() || old.messageSeq.isBlank()) {
                    list[idx] = old.copy(
                        messageId = old.messageId.ifBlank { normalized.messageId },
                        messageSeq = old.messageSeq.ifBlank { normalized.messageSeq }
                    )
                }
            }
        }
        // 保持按时间升序，历史帧与手环端 upsert 都依赖列表有序
        list.sortBy { it.time }
        while (list.size > MAX_MESSAGES) list.removeAt(0)
        persistMessages()
    }

    fun getHistory(targetId: String, limit: Int): List<StoredMessage> {
        val list = messagesByTarget[targetId] ?: return emptyList()
        val from = (list.size - limit).coerceAtLeast(0)
        return list.subList(from, list.size)
    }

    /** 本地更早一页（OneBot 翻页不可用时的回退）：返回时间严格早于 beforeTime 的最末 limit 条 */
    fun getOlderLocal(targetId: String, beforeTime: Long, limit: Int): List<StoredMessage> {
        val list = messagesByTarget[targetId] ?: return emptyList()
        val older = if (beforeTime > 0) list.filter { it.time < beforeTime } else list.toList()
        return older.takeLast(limit)
    }

    /** 锚点候选：优先取时间 ≤ beforeTime 且最接近 beforeTime 的带锚点消息；
     *  本地消息全部晚于 beforeTime 时回退取最旧一条（仍可向更早翻页）；均无锚点字段时返回 null。 */
    fun pickAnchor(targetId: String, beforeTime: Long): StoredMessage? {
        val list = messagesByTarget[targetId] ?: return null
        if (list.isEmpty()) return null
        val anchored = list.filter { it.messageSeq.isNotBlank() || it.messageId.isNotBlank() }
        if (anchored.isEmpty()) return null
        val candidates = if (beforeTime > 0) anchored.filter { it.time <= beforeTime } else anchored
        if (candidates.isNotEmpty()) {
            return if (beforeTime > 0) candidates.minByOrNull { kotlin.math.abs(it.time - beforeTime) }
            else candidates.minByOrNull { it.time }
        }
        // 本地没有早于 beforeTime 的锚点消息：回退最旧一条（OneBot 会返回它之前的更早历史）
        return anchored.minByOrNull { it.time }
    }

    /** 本地最旧一条（判断手机端历史是否比手环还旧用） */
    fun oldestMessage(targetId: String): StoredMessage? =
        messagesByTarget[targetId]?.minByOrNull { it.time }

    /** 会话类型：group / private（历史分页选 action 用） */
    fun conversationType(targetId: String): String {
        val last = messagesByTarget[targetId]?.lastOrNull()
        if (last != null) return last.messageType
        val cached = cachedContacts.firstOrNull { it.id == targetId }
        return cached?.type ?: "private"
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
        persistMessages()
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
            arr.add(o)
        }
        obj.add("contacts", arr)
        return obj.toString()
    }

    fun buildHistoryFrame(targetId: String, limit: Int, seq: Int): String {
        val obj = JsonObject()
        obj.addProperty("type", "history_list")
        obj.addProperty("seq", seq)
        obj.addProperty("target_id", targetId)
        val arr = JsonArray()
        // 历史帧过大时可能超出互联通道单帧上限导致下发失败，这里对总帧大小做保护
        val maxBytes = 15000
        var bytes = obj.toString().length
        for (m in getHistory(targetId, limit)) {
            val o = JsonObject()
            o.addProperty("message_type", m.messageType)
            o.addProperty("sender_id", m.senderId)
            o.addProperty("sender_name", m.senderName)
            o.addProperty("content", m.content)
            o.addProperty("time", m.time)
            o.addProperty("is_self", m.isSelf)
            val itemBytes = o.toString().length
            if (bytes + itemBytes > maxBytes && arr.size() > 0) break
            arr.add(o)
            bytes += itemBytes
        }
        obj.add("list", arr)
        return obj.toString()
    }

    /** 翻页历史帧：mode=older + has_more，手环据此控制「加载更早」按钮状态 */
    fun buildOlderHistoryFrame(targetId: String, msgs: List<StoredMessage>, seq: Int, hasMore: Boolean): String {
        val obj = JsonObject()
        obj.addProperty("type", "history_list")
        obj.addProperty("seq", seq)
        obj.addProperty("target_id", targetId)
        obj.addProperty("mode", "older")
        obj.addProperty("has_more", hasMore)
        val arr = JsonArray()
        val maxBytes = 15000
        var bytes = obj.toString().length
        for (m in msgs) {
            val o = JsonObject()
            o.addProperty("message_type", m.messageType)
            o.addProperty("sender_id", m.senderId)
            o.addProperty("sender_name", m.senderName)
            o.addProperty("content", m.content)
            o.addProperty("time", m.time)
            o.addProperty("is_self", m.isSelf)
            val itemBytes = o.toString().length
            if (bytes + itemBytes > maxBytes && arr.size() > 0) break
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
            arr.add(o)
        }
        obj.add("list", arr)
        return obj.toString()
    }
}
