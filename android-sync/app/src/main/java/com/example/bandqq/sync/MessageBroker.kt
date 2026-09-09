package com.example.bandqq.sync

import com.example.bandqq.config.ConfigHolder
import com.example.bandqq.onebot.OneBotMessage
import com.example.bandqq.onebot.OneBotParser
import com.google.gson.JsonParser

object HistoryDedup {
    private val map = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** 同一 targetId 的 get_history 在 WINDOW_MS 内只响应一次，避免手环频繁拉取叠加重复下发历史 */
    fun tryRun(targetId: String): Boolean {
        val now = System.currentTimeMillis()
        val prev = map.put(targetId, now)
        if (prev != null && now - prev < WINDOW_MS) return false
        return true
    }
    private const val WINDOW_MS = 1500L
}

interface MessageSender {
    fun sendMessage(
        messageType: String,
        targetId: String,
        content: String,
        httpUrlOverride: String? = null,
        callback: (Boolean) -> Unit = {}
    )
}

class MessageBroker(
    private val parser: OneBotParser,
    private val oneBot: MessageSender,
    private val store: MessageStore,
    private val autoFetch: ((MessageStore) -> Unit)? = null
) : com.example.bandqq.onebot.OneBotListener {

    var autoFetchDone = false

    var bandSender: (String) -> Unit = {}

    /** 手环 pong 心跳应答回调，由互联层在收到 pong 时调用以确认手环在线。 */
    var onBandPong: () -> Unit = {}

    fun onBandFrame(json: String): Boolean {
        val obj = try {
            JsonParser.parseString(json).asJsonObject
        } catch (e: Exception) {
            return false
        }
        val type = obj.get("type")?.asString ?: return false
        val seq = obj.get("seq")?.asInt ?: 0
        when (type) {
            "pong" -> {
                onBandPong()
                return true
            }
            "band_state" -> {
                onBandPong()
                return true
            }
            "send_message" -> {
                val messageType = obj.get("message_type")?.asString ?: "private"
                val targetId = obj.get("target_id")?.asString ?: return false
                val content = obj.get("content")?.asString ?: ""
                val frameTime = obj.get("time")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
                val sendTime = if (frameTime != null && frameTime > 0) frameTime else System.currentTimeMillis()
                oneBot.sendMessage(messageType, targetId, content)
                // 记录自己发送的消息，保证手机端历史与会话完整性。
                // 会话名以目标联系人的真实名称为准，避免落成"我"导致会话列表出现"我"
                val selfSenderName = store.contactName(targetId).ifBlank { targetId }
                store.addMessage(
                    targetId,
                    StoredMessage(
                        messageType = messageType,
                        senderId = targetId,
                        senderName = selfSenderName,
                        content = content,
                        time = sendTime,
                        isSelf = true
                    )
                )
                MessageBus.notify(targetId)
                // 回推手环：与手环本地回显相同 time，upsertMessage 按 time|content 去重不会重复显示
                val visible = store.isVisibleContact(targetId)
                val targetName = store.conversationName(targetId, messageType, selfSenderName)
                bandSender(
                    parser.toHandBandFrame(
                        OneBotMessage(
                            messageType = messageType,
                            targetId = targetId,
                            senderId = targetId,
                            senderName = selfSenderName,
                            content = content,
                            time = sendTime,
                            isSelf = true
                        ),
                        visible = visible,
                        targetName = targetName
                    )
                )
                return true
            }
            "get_history" -> {
                val targetId = obj.get("target_id")?.asString ?: return false
                val limit = obj.get("limit")?.asInt ?: 20
                // 翻页锚点：手环传 before（早于该时间的更早消息），0 表示拉最新一页
                val before = obj.get("before")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong ?: 0L
                // 去重：手环 onInit+onShow 会连续发多次 get_history，只响应第一次，
                // 避免 history_list 多次到达覆盖 push_message 新消息
                if (HistoryDedup.tryRun(targetId + "@" + before)) {
                    bandSender(store.buildHistoryFrame(targetId, limit, seq, before))
                } else {
                    log("get_history dedup skip $targetId before=$before")
                }
                return true
            }
            "read_chat" -> {
                val targetId = obj.get("target_id")?.asString ?: return false
                store.markRead(targetId)
                // 回推最新会话列表（含归零后的未读数），手环按签名 diff 决定是否重渲染
                bandSender(store.buildConversationFrame(seq))
                return true
            }
            "get_conversations" -> {
                bandSender(store.buildConversationFrame(seq))
                return true
            }
            "clear_all_history" -> {
                store.clearAllHistory()
                return true
            }
            "get_visible_contacts" -> {
                val frame = store.buildVisibleContactsFrame(seq)
                log("get_visible_contacts -> ${store.getVisibleContacts().size} contacts")
                bandSender(frame)
                return true
            }
            "get_connect_state" -> {
                bandSender(SyncStatePush.buildFrame())
                return true
            }
            else -> return false
        }
    }

    fun handleOneBotEvent(msg: OneBotMessage): String? {
        store.addMessage(
            msg.targetId,
            StoredMessage(
                messageType = msg.messageType,
                senderId = msg.senderId,
                senderName = msg.senderName,
                content = msg.content,
                time = msg.time,
                isSelf = msg.isSelf
            )
        )
        MessageBus.notify(msg.targetId)
        val visible = store.isVisibleContact(msg.targetId)
        val targetName = store.conversationName(msg.targetId, msg.messageType, msg.senderName)
        return parser.toHandBandFrame(msg, visible, targetName)
    }

    override fun onEvent(message: OneBotMessage) {
        // 开启"上报自身信息"时 OneBot 会回推自己发的消息：
        // 私聊场景 targetId=senderId=selfId 会落进机器人自己的会话，且该消息已由 send_message 分支记录并回推，故跳过
        if (message.isSelf) return
        val frame = handleOneBotEvent(message) ?: return
        bandSender(frame)
    }

    override fun onState(connected: Boolean) {
        SyncState.oneBotConnected = connected
        bandSender(SyncStatePush.buildFrame())
        if (connected && !autoFetchDone) {
            tryAutoFetch()
        }
    }

    private fun tryAutoFetch() {
        autoFetch?.invoke(store)
    }

    /** 手环连接建立后补推可见联系人，确保保存时未连接的联系人在连接后自动同步到手环。 */
    fun pushVisibleContacts() {
        bandSender(store.buildVisibleContactsFrame(0))
        log("pushVisibleContacts -> ${store.getVisibleContacts().size} contacts")
    }

    /** 手环连接建立后补推快捷回复（手机端配置 + CQ 码剥离后的干净标签） */
    fun pushQuickReplies() {
        val raw = ConfigHolder.config.quickReplies
        val list = OneBotParser.parseQuickReplies(raw)
        bandSender(OneBotParser.buildQuickRepliesFrame(list, 0))
        log("pushQuickReplies -> ${list.size} items")
    }

    private fun log(msg: String) {
        try {
            LogBus.log("MessageBroker", LogLevel.DEBUG, msg)
        } catch (t: Throwable) {
            // JVM 单测环境下不可用，静默忽略
        }
    }
}
