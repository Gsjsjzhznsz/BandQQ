package com.bandqq.sync.sync

import android.util.Log
import com.bandqq.sync.onebot.OneBotClient
import com.bandqq.sync.onebot.OneBotMessage
import com.bandqq.sync.onebot.OneBotParser
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 消息桥接核心：
 * OneBot(WS/HTTP) -> MessageStore(持久化) -> 手环通道(全部实时+连接回放)
 * 手环请求 -> send / get_history(recent|older) / refresh
 *
 * v1.1.1 修复：
 *  - 冷启动丢消息：手环通道接入后自动回放缓存消息（replayOnConnect）
 *  - 翻页失败：远端翻页锚点优先 history_seq（message_seq），回退 message_id；time 过滤统一毫秒
 */
class MessageBroker(
    private val client: OneBotClient,
    val store: MessageStore
) {
    /** 手环通道接口（由 BandServer 的每个连接实现） */
    interface MessageSender {
        fun sendToBand(frame: String): Boolean
        fun requestApiParams(action: String, params: JSONObject, callback: (JSONObject?) -> Unit): Boolean {
            return false // 必须由实现方接入 broker.requestApiParams
        }
    }

    /** 供手环通道转发 OneBot API 请求 */
    fun requestApiParams(action: String, params: JSONObject, callback: (JSONObject?) -> Unit) {
        client.requestApiParams(action, params, callback)
    }

    var thumbFetcher: ThumbFetcher? = null
    private val bandSenders = ConcurrentHashMap<String, MessageSender>()
    private val olderLoading = ConcurrentHashMap<String, Boolean>() // targetId -> loading
    @Volatile private var selfId: String = ""

    fun attachBand(id: String, sender: MessageSender) {
        bandSenders[id] = sender
        // 冷启动回放：手环重连/冷启后，把缓存里最近 24h 消息补推（手环端按 message_id 去重）
        replayOnConnect(sender)
        sender.sendToBand(store.buildListFrame())
    }

    fun detachBand(id: String) {
        bandSenders.remove(id)
    }

    /** v1.1.1：连接回放 */
    private fun replayOnConnect(sender: MessageSender) {
        val msgs = store.replayMessages()
        for (m in msgs) {
            val frame = OneBotParser.toHandBandFrame(
                OneBotMessage(
                    messageId = m.messageId, historySeq = m.historySeq,
                    chatType = m.chatType, targetId = m.targetId,
                    senderId = m.senderId, senderName = m.senderName,
                    content = m.content, time = m.timeMs / 1000,
                    isSelf = m.isSelf, atMe = m.atMe
                ),
                atMe = m.atMe
            )
            sender.sendToBand(frame)
        }
        if (selfId.isNotEmpty()) {
            sender.sendToBand(
                JSONObject()
                    .put("type", "login_info")
                    .put("user_id", selfId)
                    .toString()
            )
        }
    }

    fun broadcastToBand(frame: String) {
        for (s in bandSenders.values) s.sendToBand(frame)
    }

    fun start() {
        client.setOnEvent { json -> handleOneBotEvent(json) }
        client.setOnState { connected ->
            broadcastToBand(
                JSONObject().put("type", "state").put("connected", connected).toString()
            )
            if (connected) {
                // 拉登录信息推给手环
                client.requestApi("get_login_info") { resp ->
                    val info = resp?.let { OneBotParser.parseLoginInfo(it) } ?: return@requestApi
                    selfId = info.first
                    store.upsertContact(info.first, "self", info.second)
                    val frame = JSONObject()
                        .put("type", "login_info")
                        .put("user_id", info.first)
                        .put("nickname", info.second)
                        .toString()
                    broadcastToBand(frame)
                }
            }
        }
        client.start()
    }

    private fun handleOneBotEvent(json: JSONObject) {
        val msg = OneBotParser.parseEvent(json, selfId) ?: return
        val stored = store.addMessage(msg)
        // 推送手环（含缩略图钩子：图片消息异步抓取一次）
        val imageUrl = msg.imageUrl
        if (imageUrl != null && thumbFetcher != null) {
            thumbFetcher?.fetch(imageUrl) { b64 ->
                if (b64 != null) {
                    val frame = OneBotParser.toHandBandFrame(
                        msg, atMe = msg.atMe, thumb = b64
                    )
                    broadcastToBand(frame)
                }
            }
        }
        broadcastToBand(OneBotParser.toHandBandFrame(msg, atMe = msg.atMe))
        // 联系人缓存更新（群名片优先已由 parser 处理）
        if (stored.senderName.isNotEmpty()) {
            store.upsertContact(stored.targetId, stored.chatType, stored.senderName)
        }
    }

    /** 手环发送消息 */
    fun sendFromBand(sender: MessageSender, targetId: String, chatType: String, content: String) {
        val action = if (chatType == "group") "send_group_msg" else "send_private_msg"
        val params = JSONObject()
        if (chatType == "group") params.put("group_id", targetId.toLongOrNull() ?: 0L)
        else params.put("user_id", targetId.toLongOrNull() ?: 0L)
        val message = JSONObject().put("type", "text").put("data", JSONObject().put("text", content))
        params.put("message", JSONArray().put(message))
        sender.requestApiParams(action, params) { resp ->
            val ok = resp != null && resp.optString("status") in listOf("ok", "async")
            if (!ok) {
                // 失败通知手环
                sender.sendToBand(
                    JSONObject().put("type", "send_failed").put("target_id", targetId).toString()
                )
            }
        }
    }

    /**
     * 手环拉历史：
     *  - recent：本地最新 limit 条；不足时远端拉取一次
     *  - older：锚点=本地最旧一条；远端翻页 message_seq 优先 history_seq，回退 message_id；
     *           time 过滤用毫秒（存储层已统一）
     */
    fun fetchHistory(sender: MessageSender, targetId: String, chatType: String, limit: Int, older: Boolean, beforeMs: Long?) {
        if (older) {
            if (olderLoading.putIfAbsent(targetId, true) != null) return
            try {
                val local = store.getMessages(targetId)
                val anchor = local.firstOrNull()
                val before = beforeMs ?: anchor?.timeMs ?: 0L
                // 1) 本地先行
                val localOlder = store.getOlderLocal(targetId, before, limit)
                val anchorSeq = anchor?.historySeq ?: anchor?.messageId?.takeIf { it > 0 }?.toString()
                if (localOlder.size >= limit && localOlder.isNotEmpty() && localOlder.first().timeMs < before) {
                    val hasMore = localOlder.size >= limit
                    sender.sendToBand(store.buildHistoryFrame(targetId, "older", localOlder, hasMore))
                    return
                }
                // 2) 远端翻页
                if (anchorSeq == null || anchorSeq.isEmpty() || anchorSeq == "0" || anchorSeq == "-1") {
                    // 无锚点：直接本地（可能空）
                    sender.sendToBand(store.buildHistoryFrame(targetId, "older", localOlder, false))
                    return
                }
                val action = if (chatType == "group") "get_group_msg_history" else "get_friend_msg_history"
                val params = JSONObject()
                if (chatType == "group") params.put("group_id", targetId.toLongOrNull() ?: 0L)
                else params.put("user_id", targetId.toLongOrNull() ?: 0L)
                params.put("count", limit)
                try { params.put("message_seq", anchorSeq.toLong()) } catch (_: Exception) { params.put("message_seq", anchorSeq) }
                sender.requestApiParams(action, params) { resp ->
                    try {
                        olderLoading.remove(targetId)
                        val remote = resp?.let { OneBotParser.parseHistoryResponse(it) } ?: emptyList()
                        if (remote.isEmpty()) {
                            // 远端无数据：本地兜底（锚点前更早的）
                            val fallback = store.getOlderLocal(targetId, before - 1, limit)
                            sender.sendToBand(store.buildHistoryFrame(targetId, "older", fallback, false))
                            return@requestApiParams
                        }
                        // 入库（去重），并按 time <= before 过滤
                        for (m in remote) store.addMessage(m)
                        val filtered = remote
                            .map { it.copy(time = it.time) }
                            .filter { MessageStore.normalizeTime(it.time) <= before }
                            .sortedBy { MessageStore.normalizeTime(it.time) }
                            .takeLast(limit)
                        val hasMore = remote.isNotEmpty() && remote.size >= limit
                        sender.sendToBand(store.buildHistoryFrame(targetId, "older", filtered.map {
                            StoredMessage(
                                it.messageId, it.historySeq, it.chatType, it.targetId,
                                it.senderId, it.senderName, it.content,
                                MessageStore.normalizeTime(it.time), it.isSelf, it.atMe
                            )
                        }, hasMore))
                    } catch (e: Exception) {
                        Log.w("MessageBroker", "older parse fail", e)
                    }
                }
            } finally {
                // 异步回调里再 remove；此处仅防泄漏
            }
        } else {
            // recent：本地优先
            val local = store.getMessages(targetId).takeLast(limit)
            if (local.isNotEmpty()) {
                val hasMore = store.getMessages(targetId).size >= limit
                sender.sendToBand(store.buildHistoryFrame(targetId, "recent", local, hasMore))
            } else {
                // 本地空，远端拉一次
                val action = if (chatType == "group") "get_group_msg_history" else "get_friend_msg_history"
                val params = JSONObject()
                if (chatType == "group") params.put("group_id", targetId.toLongOrNull() ?: 0L)
                else params.put("user_id", targetId.toLongOrNull() ?: 0L)
                params.put("count", limit)
                sender.requestApiParams(action, params) { resp ->
                    val remote = resp?.let { OneBotParser.parseHistoryResponse(it) } ?: emptyList()
                    for (m in remote) store.addMessage(m)
                    val mapped = remote.takeLast(limit).map {
                        StoredMessage(
                            it.messageId, it.historySeq, it.chatType, it.targetId,
                            it.senderId, it.senderName, it.content,
                            MessageStore.normalizeTime(it.time), it.isSelf, it.atMe
                        )
                    }
                    sender.sendToBand(store.buildHistoryFrame(targetId, "recent", mapped, remote.size >= limit))
                }
            }
        }
    }

    /** 手环请求会话列表 */
    fun sendList(sender: MessageSender) {
        sender.sendToBand(store.buildListFrame())
    }
}
