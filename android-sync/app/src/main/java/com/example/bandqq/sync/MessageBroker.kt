package com.example.bandqq.sync

import com.example.bandqq.config.ConfigHolder
import com.example.bandqq.onebot.OneBotMessage
import com.example.bandqq.onebot.OneBotParser
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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

    /**
     * v2.8.0 双端设置互通：手环 settings_update 回写。
     * SyncService onCreate 注入 ConfigManager::applyBandSettings；
     * 返回 true 表示有变化（需要回推确认帧对齐两端）。
     */
    var settingsWriter: (suspend (emojiNative: Boolean?, msgVibrate: Boolean?) -> Boolean)? = null

    private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
                log("clear_all_history from band -> cleared, reply empty conversation frame as ack")
                // v2.7.0 ack：回推权威会话帧（已空）。若本帧丢失/失败，手环端重试机制会再发；
                // 手环收到空列表后立即覆盖本地残留预览，两端状态严格一致
                bandSender(store.buildConversationFrame(seq))
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
            "get_settings" -> {
                // v2.8.0 双端互通：手环启动时主动拉取设置快照
                bandSender(buildSettingsStateFrame())
                return true
            }
            "settings_update" -> {
                // v2.8.0 双端互通：手环设置页改动回传（仅变化字段非空）。
                // 回写手机端配置后回推 settings_state 确认帧；值未变化不回推，防同步风暴。
                val emoji = obj.get("emoji_native")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
                val vibrate = obj.get("msg_vibrate")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
                if (emoji == null && vibrate == null) return true
                val writer = settingsWriter
                settingsScope.launch {
                    val changed = try { writer?.invoke(emoji, vibrate) ?: false } catch (t: Throwable) { false }
                    if (changed) bandSender(buildSettingsStateFrame())
                }
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
                isSelf = msg.isSelf,
                atMe = msg.atMe
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
        // v2.4.7 推送策略：消息照常入库（历史/未读完整），仅拦截「推给手环」这一步，
        // 手环不亮屏不震动；用户主动打开会话时 get_history 仍能补看（勿扰语义）
        if (shouldSuppressPush(message)) {
            log("push suppressed: type=${message.messageType} atMe=${message.atMe} dnd=${inDndWindow()} group=${ConfigHolder.config.groupPushMode}")
            return
        }
        bandSender(frame)
        // v2.7.0 快应用自动拉起：仅在消息实际推送且快应用未打开时触发；
        // 去重/延迟/通知/拉起细节由 AutoLauncher 管理（设置页「快应用自动拉起」专区开关）
        AutoLauncher.scheduleIfEnabled()
    }

    /**
     * 推送拦截判定（全部手机端内存计算，O(1)，手环零感知）：
     * 1) 群聊按 groupPushMode：1=仅@我(含@全体) 2=全部不推；
     * 2) 勿扰时段（dndEnabled && inDndWindow）：时段内新消息不推（支持跨零点，如 23:00~07:00）。
     */
    private fun shouldSuppressPush(message: OneBotMessage): Boolean {
        if (message.messageType == "group") {
            when (ConfigHolder.config.groupPushMode) {
                2 -> return true
                1 -> if (!message.atMe) return true
            }
        }
        return ConfigHolder.config.dndEnabled && inDndWindow()
    }

    /** 当前时刻是否处于勿扰时段（支持跨零点：start>end 表示 23:00→07:00 这种区间） */
    private fun inDndWindow(): Boolean {
        val startMin = parseHm(ConfigHolder.config.dndStart) ?: return false
        val endMin = parseHm(ConfigHolder.config.dndEnd) ?: return false
        val cal = java.util.Calendar.getInstance()
        val nowMin = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        return if (startMin == endMin) {
            false
        } else if (startMin < endMin) {
            nowMin in startMin until endMin
        } else {
            nowMin >= startMin || nowMin < endMin
        }
    }

    private fun parseHm(text: String): Int? = runCatching {
        val parts = text.trim().split(":")
        val h = parts[0].toInt()
        val m = if (parts.size > 1) parts[1].toInt() else 0
        if (h in 0..23 && m in 0..59) h * 60 + m else null
    }.getOrNull()

    /**
     * 一键测试推送（v2.5.0 多场景模拟器；v2.6.0 消息入库）：
     * 构造真实 OneBot v11 事件 JSON（数组段格式），走与真实消息完全一致的解析管线
     * （parseMessageEvent → degradeContent/atMe 检测 → toHandBandFrame），
     * 私聊/群聊 × 文本/@我/图片/表情/引用回复/撤回/语音/文件/长文本。
     * v2.6.0 起同时写入手机端历史库：手环侧会话/历史均以手机端为事实源，
     * 不入库的消息会在手环下次会话同步/拉历史时被清掉（表现为「手环一会就删除」）。
     *
     * @param chatType "private" | "group"
     * @param scenario text/at/image/face/reply/recall/voice/file/long
     * @return 结果描述（供界面 toast），空串表示构造失败
     */
    fun pushTestMessage(chatType: String = "private", scenario: String = "text"): String {
        val group = chatType == "group"
        val targetName = if (group) "BandQQ 体验群" else "BandQQ 测试"
        val senders = listOf("小明", "测试喵", "群友小王", "BandQQ 机器人", "阿瑶", "测试员")
        testPushCounter++
        val senderName = senders[testPushCounter % senders.size]
        val nowSec = System.currentTimeMillis() / 1000
        // 发送者 id 固定（10086/群 20001），昵称轮换 —— 同一会话内演示不同群友，不产生测试会话堆积
        fun seg(type: String, vararg kv: Pair<String, String>): JsonObject {
            val o = JsonObject()
            o.addProperty("type", type)
            val d = JsonObject()
            for ((k, v) in kv) d.addProperty(k, v)
            o.add("data", d)
            return o
        }
        fun arr(vararg e: JsonObject): JsonArray {
            val a = JsonArray()
            for (x in e) a.add(x)
            return a
        }
        fun text(t: String) = seg("text", "text" to t)

        val message: JsonArray = when (scenario) {
            // @我 场景：at 段 qq 指向 self_id=10000，走真实 atMe 检测（手环金色高亮）
            "at" -> arr(
                seg("at", "qq" to "10000"),
                text("刚刚的方案你觉得怎么样？这条是@我模拟消息"),
            )
            "image" -> arr(text("看看这张图"), seg("image", "file" to "test.jpg", "url" to "https://example.com/test.jpg"))
            "face" -> arr(seg("face", "id" to "1"), text("哈哈，这是表情消息测试"))
            "reply" -> arr(seg("reply", "id" to "-1"), text("收到，马上处理！这条是引用回复测试"))
            "voice" -> arr(text("发来一条语音"), seg("record", "file" to "test.amr"))
            "file" -> arr(seg("file", "name" to "需求文档.pdf", "size" to "102400"))
            "long" -> arr(
                text(
                    "这是一条长文本测试：\n第一行模拟群通知内容，检查多行折行\n" +
                        "第二行 BandQQ 手环端应完整展示不截断\n第三行 滑动查看后续\n—— 完"
                )
            )
            else -> arr(text(if (group) "大家好，这是群聊模拟消息" else "你好呀，这是私聊模拟消息"))
        }
        val event = JsonObject()
        event.addProperty("post_type", "message")
        event.addProperty("message_type", if (group) "group" else "private")
        event.addProperty("time", nowSec)
        event.addProperty("self_id", 10000L)
        event.addProperty("user_id", 10086L)
        if (group) event.addProperty("group_id", 20001L)
        event.addProperty("message_id", "test_${System.currentTimeMillis()}")
        val sender = JsonObject()
        sender.addProperty("nickname", senderName)
        sender.addProperty("user_id", 10086L)
        event.add("sender", sender)
        event.add("message", message)

        val parsed = parser.parseMessageEvent(event.toString()) ?: return ""
        val isRecall = scenario == "recall"
        // 撤回场景：两端先同步展示同一句文本，随后同 time 撤回（手环原位灰显、手机库内替换标记）
        val storedContent = if (isRecall) "过一会儿我会撤回这条消息" else parsed.content
        // 入库（v2.6.0）：与真实消息同路径写入手机端聊天记录，会话名固定用测试会话名，
        // 不随发送者轮换跳动；未读计数不累计（测试会话不在可见联系人列表）。
        store.addMessage(
            parsed.targetId,
            StoredMessage(
                messageType = parsed.messageType,
                senderId = parsed.senderId,
                senderName = targetName,
                content = storedContent,
                time = parsed.time,
                isSelf = parsed.isSelf,
                messageId = parsed.messageId,
                atMe = parsed.atMe,
            )
        )
        MessageBus.notify(parsed.targetId)
        if (isRecall) {
            val first = parsed.copy(content = storedContent)
            bandSender(parser.toHandBandFrame(first, visible = true, targetName = targetName))
            bandSender(buildRecallFrame(first.targetId, first.time))
            if (parsed.messageId.isNotBlank()) store.recallMessage(parsed.targetId, parsed.messageId)
            bandSender(store.buildConversationFrame(0))
            return "已模拟：${if (group) "群聊" else "私聊"} · 撤回（发送者：$senderName）"
        }
        val label = when (scenario) {
            "at" -> "@我"; "image" -> "图片"; "face" -> "表情"; "reply" -> "引用回复"
            "voice" -> "语音"; "file" -> "文件"; "long" -> "长文本"; else -> "文本"
        }
        bandSender(parser.toHandBandFrame(parsed, visible = true, targetName = targetName))
        bandSender(store.buildConversationFrame(0))
        return "已模拟：${if (group) "群聊" else "私聊"} · $label（发送者：$senderName）"
    }

    private var testPushCounter = 0

    override fun onRecall(recall: com.example.bandqq.onebot.OneBotRecall) {
        // 借鉴 Stapxs 撤回提示：手机端内容替换为标记 + 推送撤回同步帧。
        // 手环端按 time 原位替换（不新增消息、不动未读），聊天页实时灰显，
        // 会话帧同步推送 —— 列表预览按 convSignature 签名 diff 自动更新。
        val recalledTime = store.recallMessage(recall.targetId, recall.messageId)
        if (recalledTime > 0L) {
            MessageBus.notify(recall.targetId)
            bandSender(buildRecallFrame(recall.targetId, recalledTime))
            bandSender(store.buildConversationFrame(0))
        }
    }

    /** 撤回同步帧（type=push_message + recall=1）：手环按 time 原位替换为撤回文案 */
    private fun buildRecallFrame(targetId: String, time: Long): String {
        val obj = com.google.gson.JsonObject()
        obj.addProperty("type", "push_message")
        obj.addProperty("seq", 0)
        obj.addProperty("message_type", "private")
        obj.addProperty("target_id", targetId)
        obj.addProperty("sender_id", "")
        obj.addProperty("sender_name", "")
        obj.addProperty("target_name", "")
        obj.addProperty("content", MessageStore.RECALL_MARK)
        obj.addProperty("time", time)
        obj.addProperty("is_self", false)
        obj.addProperty("visible", true)
        obj.addProperty("recall", 1)
        return obj.toString()
    }

    override fun onState(connected: Boolean) {
        SyncState.oneBotConnected = connected
        // 推送手环端同步状态帧 + 通知本机界面实时刷新（不再只能靠手动测试/轮询感知）
        OneBotStateBus.notify(connected)
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

    /**
     * v2.8.0 双端互通设置快照帧（手机端为权威源）。
     * 下发时机：手环连接建立（onConnect）、手机端改动（pushSettingsNow 钩子）、
     * 手环回传有变化后的确认。
     */
    fun pushSettingsState() {
        bandSender(buildSettingsStateFrame())
        log("pushSettingsState -> emoji=${ConfigHolder.config.emojiNative} vibrate=${ConfigHolder.config.bandMsgVibrate}")
    }

    private fun buildSettingsStateFrame(): String {
        val cfg = ConfigHolder.config
        val obj = JsonObject()
        obj.addProperty("type", "settings_state")
        obj.addProperty("seq", 0)
        obj.addProperty("emoji_native", cfg.emojiNative)
        obj.addProperty("msg_vibrate", cfg.bandMsgVibrate)
        return obj.toString()
    }

    private fun log(msg: String) {
        try {
            LogBus.log("MessageBroker", LogLevel.DEBUG, msg)
        } catch (t: Throwable) {
            // JVM 单测环境下不可用，静默忽略
        }
    }
}
