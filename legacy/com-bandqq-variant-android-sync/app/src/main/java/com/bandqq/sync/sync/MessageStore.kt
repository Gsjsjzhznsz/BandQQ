package com.bandqq.sync.sync

import android.content.Context
import android.content.SharedPreferences
import com.bandqq.sync.onebot.OneBotMessage
import org.json.JSONArray
import org.json.JSONObject

/** 手机端持久化的消息（SharedPreferences XML：chat_messages / contact_cache） */
data class StoredMessage(
    val messageId: Long,
    val historySeq: String?,
    val chatType: String,
    val targetId: String,
    val senderId: String,
    val senderName: String,
    val content: String,
    val timeMs: Long,     // 统一毫秒
    val isSelf: Boolean,
    val atMe: Boolean
)

class MessageStore(context: Context? = null) {
    // 持久化后端可注入：默认 SharedPreferences（Android），纯 JVM 测试传 null
    private val prefs: SharedPreferences? = context?.getSharedPreferences("bandqq_store", Context.MODE_PRIVATE)

    private val messages = LinkedHashMap<String, MutableList<StoredMessage>>()
    private val contacts = LinkedHashMap<String, JSONObject>() // id -> {id,type,name}
    val oldestSeqByTarget = HashMap<String, String?>()          // 翻页远端锚点

    init {
        load()
    }

    private fun load() {
        messages.clear()
        try {
            prefs?.getString("chat_messages", null)?.let { raw ->
                val root = JSONObject(raw)
                val it = root.keys()
                while (it.hasNext()) {
                    val tid = it.next()
                    val arr = root.optJSONArray(tid) ?: continue
                    val list = ArrayList<StoredMessage>()
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        list.add(
                            StoredMessage(
                                messageId = o.optLong("message_id", -1L),
                                historySeq = o.optString("history_seq").takeIf { it.isNotEmpty() },
                                chatType = o.optString("message_type", "group"),
                                targetId = tid,
                                senderId = o.optString("sender_id"),
                                senderName = o.optString("sender_name"),
                                content = o.optString("content"),
                                timeMs = normalizeTime(o.optLong("time", 0L)),
                                isSelf = o.optBoolean("is_self", false),
                                atMe = o.optBoolean("at_me", false)
                            )
                        )
                    }
                    list.sortBy { it.timeMs }
                    messages[tid] = list
                }
            }
            prefs?.getString("contact_cache", null)?.let { raw ->
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id")
                    if (id.isNotEmpty()) contacts[id] = o
                }
            }
        } catch (_: Exception) {}
    }

    @Synchronized
    private fun persist() {
        try {
            val root = JSONObject()
            for ((tid, list) in messages) {
                val arr = JSONArray()
                for (m in list.takeLast(MAX_PER_TARGET)) {
                    val o = JSONObject()
                    o.put("message_type", m.chatType)
                    o.put("sender_id", m.senderId)
                    o.put("sender_name", m.senderName)
                    o.put("content", m.content)
                    o.put("time", m.timeMs)
                    o.put("is_self", m.isSelf)
                    if (m.atMe) o.put("at_me", true)
                    if (m.messageId > 0) o.put("message_id", m.messageId)
                    m.historySeq?.let { o.put("history_seq", it) }
                    arr.put(o)
                }
                root.put(tid, arr)
            }
            prefs?.edit()?.putString("chat_messages", root.toString())?.apply()
            val cArr = JSONArray()
            for (c in contacts.values) cArr.put(c)
            prefs?.edit()?.putString("contact_cache", cArr.toString())?.apply()
        } catch (_: Exception) {}
    }

    companion object {
        const val MAX_PER_TARGET = 200
        const val MAX_TARGETS = 80

        fun normalizeTime(t: Long): Long = if (t < 100_000_000_000L) t * 1000 else t
    }

    @Synchronized
    fun addMessage(m: OneBotMessage): StoredMessage {
        val list = messages.getOrPut(m.targetId) { ArrayList() }
        // 按 messageId 去重（回放/重连场景）
        if (m.messageId > 0 && list.any { it.messageId == m.messageId }) {
            return list.first { it.messageId == m.messageId }
        }
        val sm = StoredMessage(
            messageId = m.messageId,
            historySeq = m.historySeq,
            chatType = m.chatType,
            targetId = m.targetId,
            senderId = m.senderId,
            senderName = m.senderName,
            content = m.content,
            timeMs = normalizeTime(m.time),
            isSelf = m.isSelf,
            atMe = m.atMe
        )
        list.add(sm)
        if (list.size > MAX_PER_TARGET) {
            list.subList(0, list.size - MAX_PER_TARGET).clear()
        }
        // 会话信息回填（空白锚点）
        if (!contacts.containsKey(m.targetId)) {
            contacts[m.targetId] = JSONObject()
                .put("id", m.targetId)
                .put("type", m.chatType)
                .put("name", m.senderName)
        }
        // 控制总量
        while (messages.size > MAX_TARGETS) {
            val oldest = messages.minByOrNull { it.value.lastOrNull()?.timeMs ?: 0L }?.key
            if (oldest == null || oldest == m.targetId) break
            messages.remove(oldest)
        }
        persist()
        return sm
    }

    @Synchronized
    fun upsertContact(id: String, type: String, name: String) {
        val existing = contacts[id]
        val finalName = if (existing != null && existing.optString("name").isNotEmpty()) existing.optString("name") else name
        contacts[id] = JSONObject().put("id", id).put("type", type).put("name", finalName)
        persist()
    }

    @Synchronized
    fun getMessages(targetId: String): List<StoredMessage> =
        messages[targetId]?.toList() ?: emptyList()

    /** 本地翻页：取 timeMs <= beforeMs 的更早 limit 条（含等号：before 常为本地最旧一条时间） */
    @Synchronized
    fun getOlderLocal(targetId: String, beforeMs: Long, limit: Int): List<StoredMessage> {
        val list = messages[targetId] ?: return emptyList()
        return list.filter { it.timeMs <= beforeMs }
            .sortedByDescending { it.timeMs }
            .take(limit)
            .sortedBy { it.timeMs }
    }

    @Synchronized
    fun latestTimeMs(targetId: String): Long =
        messages[targetId]?.lastOrNull()?.timeMs ?: 0L

    @Synchronized
    fun getContacts(): List<JSONObject> = contacts.values.toList()

    /** 冷启动回放：所有会话最近 24h 的消息（每会话最多 10 条，总量 50 条上限），按时间升序 */
    @Synchronized
    fun replayMessages(nowMs: Long = System.currentTimeMillis()): List<StoredMessage> {
        val cutoff = nowMs - 24 * 3600_000L
        val out = ArrayList<StoredMessage>()
        for (list in messages.values) {
            out.addAll(list.filter { it.timeMs >= cutoff }.takeLast(10))
        }
        return out.sortedBy { it.timeMs }.takeLast(50)
    }

    /** 手环历史帧（recent / older），15KB 帧保护 */
    @Synchronized
    fun buildHistoryFrame(
        targetId: String,
        mode: String,
        messages: List<StoredMessage>,
        hasMore: Boolean
    ): String {
        val frame = JSONObject()
        frame.put("type", "history")
        frame.put("mode", mode)
        frame.put("target_id", targetId)
        frame.put("has_more", hasMore)
        val arr = JSONArray()
        for (m in messages) {
            val o = JSONObject()
            o.put("message_type", m.chatType)
            o.put("sender_id", m.senderId)
            o.put("sender_name", m.senderName)
            o.put("content", m.content)
            o.put("time", m.timeMs)
            o.put("is_self", m.isSelf)
            if (m.messageId > 0) o.put("message_id", m.messageId)
            arr.put(o)
        }
        frame.put("messages", arr)
        var s = frame.toString()
        if (s.length > 15_000) {
            // 从尾部裁掉直到 <15KB
            var n = messages.size
            while (n > 1) {
                n--
                val f2 = JSONObject(frame.toString())
                val a2 = JSONArray()
                for (i in 0 until n) a2.put(arr.getJSONObject(i))
                f2.put("messages", a2)
                s = f2.toString()
                if (s.length <= 15_000) break
            }
        }
        return s
    }

    @Synchronized
    fun buildListFrame(): String {
        val arr = JSONArray()
        // 附加最近消息摘要
        for ((tid, list) in messages) {
            val c = contacts[tid] ?: JSONObject().put("id", tid).put("type", "group").put("name", tid)
            val o = JSONObject(c.toString())
            val last = list.lastOrNull()
            if (last != null) {
                o.put("last_content", last.content)
                o.put("last_time", last.timeMs)
                o.put("last_sender", last.senderName)
                if (last.atMe) o.put("at_me", true)
            }
            arr.put(o)
        }
        // 纯联系人（无消息）也带上
        for ((tid, c) in contacts) {
            if (!messages.containsKey(tid)) arr.put(JSONObject(c.toString()))
        }
        return JSONObject().put("type", "list").put("contacts", arr).toString()
    }
}
