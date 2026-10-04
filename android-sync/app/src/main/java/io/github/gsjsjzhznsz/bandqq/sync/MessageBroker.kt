package io.github.gsjsjzhznsz.bandqq.sync

import io.github.gsjsjzhznsz.bandqq.config.ConfigHolder
import io.github.gsjsjzhznsz.bandqq.onebot.OneBotMessage
import io.github.gsjsjzhznsz.bandqq.onebot.OneBotParser
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.gsjsjzhznsz.bandqq.sync.LogBus
import io.github.gsjsjzhznsz.bandqq.sync.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
        /** v2.13.0：回传发送结果与协议端返回的 message_id（撤回定位用，可能为空）。
         *  v2.24.0：增加 err=协议端拒绝原因（ok=false 时给手环 toast 展示；修假成功） */
        callback: (ok: Boolean, messageId: String, err: String) -> Unit = { _, _, _ -> }
    )

    /**
     * v2.9.4：带参数的 OneBot 通用 API 调用（get_group_info 等）。
     * OneBotClient 实现 = HTTP 优先、失败自动回退 WS（真实 NapCat 常只开 WS）。
     * 默认空实现保持旧测试桩/旧实现兼容（直接回调 null）。
     */
    fun requestApiAction(action: String, paramsJson: String, callback: (String?) -> Unit) {
        callback(null)
    }
}

class MessageBroker(
    private val parser: OneBotParser,
    private val oneBot: MessageSender,
    private val store: MessageStore,
    private val autoFetch: ((MessageStore) -> Unit)? = null
) : io.github.gsjsjzhznsz.bandqq.onebot.OneBotListener {

    var autoFetchDone = false

    var bandSender: (String) -> Unit = {}

    /**
     * v2.8.0 双端设置互通：手环 settings_update 回写。
     * SyncService onCreate 注入 ConfigManager::applyBandSettings；
     * 返回 true 表示有变化（需要回推确认帧对齐两端）。
     * v2.9.0：增加 muteList（逗号分隔免打扰会话 ID 集合，手环端长按菜单上报）。
     */
    var settingsWriter: (suspend (emojiNative: Boolean?, msgVibrate: Boolean?, muteList: String?) -> Boolean)? = null

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
                // v2.24.0：发送结果三态处理——成功才入库+回推手环；失败回 action_result(ok=false)
                // 让手环 toast 真实原因。旧实现「HTTP 200 即成功」+ 无条件回显 = NapCat 拒收时
                // 手环仍显示已发送（用户实测"为什么发不了信息"的直接体验来源）。
                oneBot.sendMessage(messageType, targetId, content) { ok, messageId, err ->
                    if (ok) {
                        // v2.13.0：发送结果与 message_id 回传手环（撤回/表情回应定位用）
                        if (messageId.isNotEmpty()) {
                            store.setMessageId(targetId, sendTime, content, messageId)
                            bandSender(
                                "{\"type\":\"action_result\",\"seq\":0,\"action\":\"send\",\"ok\":true," +
                                    "\"target_id\":\"$targetId\",\"time\":$sendTime,\"message_id\":\"$messageId\"}"
                            )
                        }
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
                    } else {
                        LogBus.log(
                            "MessageBroker", LogLevel.WARN,
                            "send_message 失败（$messageType → $targetId）：${err.ifBlank { "未知原因" }}"
                        )
                        val info = "发送失败：" + (err.ifBlank { "协议端拒绝" })
                            .take(60).replace("\\", "\\\\").replace("\"", "'")
                        bandSender(
                            "{\"type\":\"action_result\",\"seq\":0,\"action\":\"send\",\"ok\":false," +
                                "\"target_id\":\"$targetId\",\"time\":$sendTime,\"info\":\"$info\"}"
                        )
                    }
                }
                return true
            }
            "send_like" -> {
                // v2.13.0 OneBot v11 扩展：点赞（NapCat send_like，s=次数上限依协议端）
                val targetId = obj.get("target_id")?.asString ?: return false
                val times = obj.get("times")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: 10
                callOneBot(
                    "send_like", "{\"user_id\":$targetId,\"times\":$times}", seq,
                    "点赞 ×$times", targetId
                )
                return true
            }
            "send_poke" -> {
                // v2.13.0 OneBot v11 扩展：主动拍一拍（NapCat friend_poke / group_poke）
                val targetId = obj.get("target_id")?.asString ?: return false
                val chatType = obj.get("chat_type")?.asString ?: "private"
                val action = if (chatType == "group") "group_poke" else "friend_poke"
                val params = if (chatType == "group") {
                    "{\"group_id\":$targetId,\"user_id\":$targetId}"
                } else {
                    "{\"user_id\":$targetId}"
                }
                callOneBot(action, params, seq, "拍一拍", targetId)
                return true
            }
            "group_sign" -> {
                // v2.13.0 OneBot v11 扩展：群签到（send_group_sign，部分协议端支持）
                val targetId = obj.get("target_id")?.asString ?: return false
                callOneBot("send_group_sign", "{\"group_id\":$targetId}", seq, "群签到", targetId)
                return true
            }
            "message_action" -> {
                // v2.13.0 OneBot v11 扩展：消息级动作（表情回应 / 撤回自己消息）
                val messageId = obj.get("message_id")?.asString ?: return false
                val sub = obj.get("sub_action")?.asString ?: ""
                // v2.16.0 修复：OneBot v11 规范 message_id 为 int32，NapCat 内部按数值
                // 索引（MessageUnique Map 的 key 是 number），字符串 key 查不到 → 撤回/
                // 表情回应全部失败（"协议端未响应或动作不支持"）。数字形态去引号下发，
                // 非 safe-integer 字符串形态（LLOneBot 等长数字串 id）保留原样。
                val midParam = messageId.toLongOrNull()?.toString() ?: "\"$messageId\""
                when (sub) {
                    "delete" -> callOneBot(
                        "delete_msg", "{\"message_id\":$midParam}", seq, "撤回消息", ""
                    )
                    "emoji" -> {
                        val emojiId = obj.get("emoji_id")?.takeIf { it.isJsonPrimitive }?.asString ?: "128077"
                        callOneBot(
                            "set_msg_emoji_like",
                            "{\"message_id\":$midParam,\"emoji_id\":\"$emojiId\"}",
                            seq, "表情回应 $emojiId", ""
                        )
                    }
                    else -> log("message_action 未知子动作: $sub")
                }
                return true
            }
            "get_user_info" -> {
                // v2.13.0 OneBot v11 扩展：资料查询（私聊 get_stranger_info / 群 get_group_member_info）
                val targetId = obj.get("target_id")?.asString ?: return false
                val chatType = obj.get("chat_type")?.asString ?: "private"
                val groupId = obj.get("group_id")?.asString
                val action: String
                val params: String
                if (chatType == "group" && groupId != null) {
                    action = "get_group_member_info"
                    params = "{\"group_id\":$groupId,\"user_id\":$targetId}"
                } else {
                    action = "get_stranger_info"
                    params = "{\"user_id\":$targetId}"
                }
                oneBot.requestApiAction(action, params) { raw ->
                    var ok = false
                    if (raw != null) {
                        try {
                            val root = JsonParser.parseString(raw).asJsonObject
                            if (root.get("retcode")?.asInt == 0 || root.has("data")) {
                                val d = root.getAsJsonObject("data")
                                if (d != null) {
                                    val nick = d.get("nickname")?.takeIf { it.isJsonPrimitive }?.asString
                                        ?: d.get("card")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
                                    val uid = d.get("user_id")?.takeIf { it.isJsonPrimitive }?.asString ?: targetId
                                    val level = d.get("level")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
                                    ok = true
                                    val reply = JsonObject()
                                    reply.addProperty("type", "user_info")
                                    reply.addProperty("seq", seq)
                                    reply.addProperty("target_id", targetId)
                                    reply.addProperty("nickname", nick)
                                    reply.addProperty("user_id", uid)
                                    if (level.isNotEmpty()) reply.addProperty("level", level)
                                    bandSender(reply.toString())
                                }
                            }
                        } catch (t: Throwable) {
                            log("get_user_info 解析失败: $t")
                        }
                    }
                    if (!ok) bandSender(actionResultFrame(seq, "资料查询失败", targetId))
                }
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
                // v2.9.0：mute_list = 逗号分隔的免打扰会话 ID 集合（手环长按菜单开关）
                val emoji = obj.get("emoji_native")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
                val vibrate = obj.get("msg_vibrate")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
                val muteList = obj.get("mute_list")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                if (emoji == null && vibrate == null && muteList == null) return true
                val writer = settingsWriter
                settingsScope.launch {
                    val changed = try { writer?.invoke(emoji, vibrate, muteList) ?: false } catch (t: Throwable) { false }
                    if (changed) bandSender(buildSettingsStateFrame())
                }
                return true
            }
            else -> return false
        }
    }

    fun handleOneBotEvent(msg: OneBotMessage): String? {
        // v2.9.0：@段协议层只有 QQ 号，手环上显示一串数字不友好 ——
        // 在手机端用联系人缓存把「@10086」解析为「@昵称」（解析不到保持原样）。
        // 用户规则：@判定靠协议 QQ 号在手机端完成，显示名称也统一在手机端解析。
        val display = msg.copy(content = decorateAtNames(msg.content))
        // v2.9.4：群名缓存缺失时按需拉取群资料（HTTP 优先失败回退 WS），
        // 防止群会话退化为「发送者昵称」被误认成个人联系人
        if (display.messageType == "group") fetchGroupNameIfNeeded(display.targetId)
        store.addMessage(
            display.targetId,
            StoredMessage(
                messageType = display.messageType,
                senderId = display.senderId,
                senderName = display.senderName,
                content = display.content,
                time = display.time,
                isSelf = display.isSelf,
                // v2.17.0 修复：入库保留 message_id（v2.13~2.16 丢在这里 → 撤回事件
                // recallMessage 按 messageId 匹配必失败 → 对方撤回手环永不灰显；
                // 且历史帧无 id 可下发，手环端撤回/表情回应菜单整体残废）
                messageId = display.messageId,
                atMe = display.atMe
            )
        )
        MessageBus.notify(display.targetId)
        val visible = store.isVisibleContact(display.targetId)
        val targetName = store.conversationName(display.targetId, display.messageType, display.senderName)
        return parser.toHandBandFrame(display, visible, targetName)
    }

    override fun onEvent(message: OneBotMessage) {
        // v2.8.1 全链兑底：处理链上有入库/互联下发/通知/自动拉起等 Android 侧调用，
        // 任何异常若传播回 OkHttp WS 回调都会断连（DevTools 联调时消息发不出首因）。
        // 单条消息异常只记日志，不断连不影响后续消息。
        try {
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
            // v2.9.0：传入会话与重要级（@我）—— 拉起范围默认仅 @我/拍一拍我；免打扰会话不拉起
            try { AutoLauncher.scheduleIfEnabled(message.targetId, important = message.atMe) } catch (t: Throwable) {
                log("auto launch schedule exception: $t")
            }
        } catch (t: Throwable) {
            log("onEvent exception: $t")
        }
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
     * @param scenario text/at/image/face/reply/recall/voice/file/long/redpacket/forward/sign
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
            // v2.17.0 新场景：渲染器智能化新增段型可演示（红包识别/转发摘要/群签到卡片）
            "redpacket" -> arr(seg("json", "data" to "{\"prompt\":\"恭喜发财，大吉大利\",\"wcpay\":{\"title\":\"QQ红包\"}}"))
            "forward" -> arr(
                seg(
                    "forward", "content" to
                    "[{\"content\":{\"content\":[{\"type\":\"text\",\"data\":{\"text\":\"周末组织去爬山，报名接龙\"}}]}},{\"content\":\"群相册已更新 30 张新照片\"}]"
                )
            )
            "sign" -> arr(seg("xml", "data" to "<msg brief=\"群签到\"><title>BandQQ 体验群 签到成功</title></msg>"))
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
        // v2.9.4：固化测试会话名进联系人缓存 —— 会话名解析优先走缓存，
        // 不再受名单拉取状态影响（此前缓存缺名时群测试会话会被解析成「QQ群 20001」）
        store.rememberContactName(parsed.targetId, parsed.messageType, targetName)
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
            "voice" -> "语音"; "file" -> "文件"; "long" -> "长文本"
            "redpacket" -> "红包"; "forward" -> "转发卡片"; "sign" -> "群签到"
            else -> "文本"
        }
        bandSender(parser.toHandBandFrame(parsed, visible = true, targetName = targetName))
        bandSender(store.buildConversationFrame(0))
        return "已模拟：${if (group) "群聊" else "私聊"} · $label（发送者：$senderName）"
    }

    private var testPushCounter = 0

    override fun onRecall(recall: io.github.gsjsjzhznsz.bandqq.onebot.OneBotRecall) {
        // v2.8.1：同 onEvent，全链兑底防断连
        try {
            // 借鉴 Stapxs 撤回提示：手机端内容替换为标记 + 推送撤回同步帧。
            // 手环端按 time 原位替换（不新增消息、不动未读），聊天页实时灰显，
            // 会话帧同步推送 —— 列表预览按 convSignature 签名 diff 自动更新。
            val recalledTime = store.recallMessage(recall.targetId, recall.messageId)
            if (recalledTime > 0L) {
                MessageBus.notify(recall.targetId)
                bandSender(buildRecallFrame(recall.targetId, recalledTime))
                bandSender(store.buildConversationFrame(0))
            }
        } catch (t: Throwable) {
            log("onRecall exception: $t")
        }
    }

    /**
     * 拍一拍通知（v2.9.0）：只处理「拍一拍我」，拍别人不提醒。
     * 链路与普通消息一致：入库（拍一拍标记）→ 互联下发 poke 帧（手环特效+长震）→
     * 自动拉起（important=true，与 @我 合并为「重要消息拉起」；免打扰会话不拉起）。
     * 名称解析：notice 事件无 sender 对象，发送者名在手机端用联系人缓存把 QQ 号解析为昵称
     * （用户规则：@/拍一拍的协议解析与名称映射全部在手机端完成，手环零计算）。
     */
    override fun onPoke(poke: io.github.gsjsjzhznsz.bandqq.onebot.OneBotPoke) {
        try {
            if (!poke.pokeMe) return
            val senderName = store.contactName(poke.senderId).ifBlank { poke.senderId }
            val content = "${OneBotParser.stripEmoji(senderName)} 拍了拍你"
            store.addMessage(
                poke.targetId,
                StoredMessage(
                    messageType = poke.chatType,
                    senderId = poke.senderId,
                    senderName = senderName,
                    content = content,
                    time = System.currentTimeMillis(),
                    isSelf = false,
                    poke = true
                )
            )
            MessageBus.notify(poke.targetId)
            val visible = store.isVisibleContact(poke.targetId)
            val targetName = store.conversationName(poke.targetId, poke.chatType, senderName)
            bandSender(
                parser.buildPokeFrame(
                    chatType = poke.chatType,
                    targetId = poke.targetId,
                    senderId = poke.senderId,
                    senderName = senderName,
                    targetName = targetName,
                    content = content,
                    visible = visible
                )
            )
            try { AutoLauncher.scheduleIfEnabled(poke.targetId, important = true) } catch (t: Throwable) {
                log("auto launch schedule exception (poke): $t")
            }
        } catch (t: Throwable) {
            log("onPoke exception: $t")
        }
    }

    /** @QQ号 → 联系人昵称（联系人缓存命中才替换；5 位以上数字才尝试，避免误伤普通文本） */
    private fun decorateAtNames(content: String): String {
        if (!content.contains("@")) return content
        return AT_QQ_REF.replace(content) { m ->
            val qq = m.groupValues[1]
            val name = store.contactName(qq)
            if (name.isNotBlank() && name != qq) "@$name" else m.value
        }
    }

    /**
     * v2.9.4：群名按需补齐。名单拉取（get_group_list）只发生在连接建立时，
     * 新加入的群/拉取失败的群会缺名 —— 这里在收到该群消息时单独调 get_group_info，
     * 拉到群名即固化进联系人缓存并补推一帧权威会话列表（手环列表名同步纠正）。
     * in-flight 去重：同一群同时只发一个请求，失败后下一条群消息再试。
     */
    private val groupInfoInFlight =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    private fun fetchGroupNameIfNeeded(targetId: String) {
        if (store.hasContactName(targetId)) return
        if (!groupInfoInFlight.add(targetId)) return
        val gid = targetId.toLongOrNull()
        if (gid == null || gid <= 0L) {
            groupInfoInFlight.remove(targetId)
            return
        }
        oneBot.requestApiAction("get_group_info", "{\"group_id\":$gid}") { raw ->
            groupInfoInFlight.remove(targetId)
            if (raw == null) return@requestApiAction
            try {
                val data = JsonParser.parseString(raw).asJsonObject.get("data")
                    ?.takeIf { it.isJsonObject }?.asJsonObject ?: return@requestApiAction
                val name = data.get("group_name")?.takeIf { it.isJsonPrimitive }?.asString ?: return@requestApiAction
                if (name.isNotBlank()) {
                    store.rememberContactName(targetId, "group", OneBotParser.stripEmoji(name))
                    log("group name resolved: $targetId -> $name")
                    // 群名补齐后补推权威会话帧，手环列表立即从「发送者昵称/QQ群号」纠正为真名
                    bandSender(store.buildConversationFrame(0))
                }
            } catch (t: Throwable) {
                log("get_group_info parse fail: $t")
            }
        }
    }

    private val AT_QQ_REF = Regex("@(\\d{5,})")

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
        try {
            SyncState.oneBotConnected = connected
            // 推送手环端同步状态帧 + 通知本机界面实时刷新（不再只能靠手动测试/轮询感知）
            OneBotStateBus.notify(connected)
            bandSender(SyncStatePush.buildFrame())
            if (connected && !autoFetchDone) {
                // v2.9.4：连接建立即拉取联系人名单；若名单仍空（NapCat 登录中/接口未就绪），
                // 每 30s 重试直到拉到（此前只试一次，QQ 扫码登录完成前连接的会话永远拿不到联系人）
                tryAutoFetch()
                scheduleAutoFetchRetry()
            } else if (!connected) {
                autoFetchRetryJob?.cancel()
            }
        } catch (t: Throwable) {
            log("onState exception: $t")
        }
    }

    /** v2.9.4：名单拉取重试（30s 间隔，最多 10 次；拉到即停，断连即取消） */
    private var autoFetchRetryJob: kotlinx.coroutines.Job? = null

    private fun scheduleAutoFetchRetry() {
        autoFetchRetryJob?.cancel()
        autoFetchRetryJob = settingsScope.launch {
            var attempt = 0
            while (!autoFetchDone && attempt < 10) {
                delay(30_000)
                if (autoFetchDone) break
                attempt++
                log("autoFetch retry #$attempt (contact lists still incomplete)")
                tryAutoFetch()
            }
        }
    }

    /** v2.9.4：服务销毁时取消重试任务（防协程泄漏） */
    fun cancelAutoFetchRetry() {
        autoFetchRetryJob?.cancel()
        autoFetchRetryJob = null
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

    /**
     * v2.10.0 eSIM 独立线路：下发 NapCat HTTP 直连配置（url+token），手环持久化。
     * eSIM 机型（RW5E eSIM / Watch S4 eSIM / 15th 等）互联断开（手机不在旁）时
     * 自动切直连收发。仅下发有效外网/局域网地址：默认 127.0.0.1/localhost 对手环
     * 无意义（手表连不到手机 loopback），不下发避免手环误判"已配置待命"。
     */
    fun pushDirectConfig() {
        val frame = buildDirectConfigFrame()
        if (frame != null) {
            bandSender(frame)
            log("pushDirectConfig -> ${ConfigHolder.config.endpoint.httpUrl}")
        } else {
            log("pushDirectConfig skipped: httpUrl not reachable from band (default/blank)")
        }
    }

    private fun buildDirectConfigFrame(): String? {
        val ep = ConfigHolder.config.endpoint
        val url = ep.httpUrl.trim()
        if (url.isEmpty() || url.contains("127.0.0.1") || url.contains("localhost")) return null
        val obj = JsonObject()
        obj.addProperty("type", "direct_config")
        obj.addProperty("seq", 0)
        obj.addProperty("url", url)
        obj.addProperty("token", ep.httpToken)
        return obj.toString()
    }

    private fun buildSettingsStateFrame(): String {
        val cfg = ConfigHolder.config
        val obj = JsonObject()
        obj.addProperty("type", "settings_state")
        obj.addProperty("seq", 0)
        obj.addProperty("emoji_native", cfg.emojiNative)
        obj.addProperty("msg_vibrate", cfg.bandMsgVibrate)
        // v2.9.0：免打扰会话集合（逗号分隔 ID），手环据此渲染灰色红点
        obj.addProperty("mute_list", cfg.mutedChats.joinToString(","))
        return obj.toString()
    }

    /**
     * v2.13.0 OneBot v11 扩展动作统一通道：requestApiAction（HTTP 优先、WS 回退）→
     * retcode==0 判成功 → action_result 帧回手环（toast 展示）。
     * v2.18.0：带重试与结果明细（见 attemptCall）。
     */
    private fun callOneBot(action: String, paramsJson: String, seq: Int, label: String, targetId: String) {
        attemptCall(action, paramsJson, seq, label, targetId, 1)
    }

    /**
     * v2.18.0：v11 动作重试通道。实证（用户 10-03 日志）：撤回链路 BandQQ 侧已全通，
     * 失败点在协议端——NapCat 对 recallMsg 的 NT 事件等待超时（retcode=1200，
     * "Timeout: NTEvent ... recallMsg ... result: 5"）。该模式在 NapCat 社区为已知
     * 瞬态问题（sendMsg/recallMsg 均有同型 issue）：NT 反馈事件偶发丢失/迟滞，
     * 重试往往成功。策略：
     * ① 失败重试至多 3 次（间隔 1.2s / 2.5s）；
     * ② delete_msg 首次 1200 时先经 get_msg 验证——
     *   v2.18.1 修正：用户实测证伪了 v2.18 的「get_msg 查无 = 已撤回」推断：
     *   NapCat recallMsg NT 超时（撤回实际未生效）时 get_msg 同样可能 1200
     *   （"消息不存在或已被撤回"，NT 服务异常时查询与撤回一起失灵），
     *   按成功回帧 = 手环显示"撤回成功"而群里消息还在。现在 get_msg 失败
     *   一律按"无法确认"处理：照样走重试，终败如实回帧失败并给出可行动提示；
     *   仅 get_msg 成功（消息仍在，撤回确定未生效）也照走重试。
     * ③ 最终失败把协议端 retcode/wording 透传手环，不再笼统"未响应或动作不支持"。
     */
    private fun attemptCall(
        action: String, paramsJson: String, seq: Int, label: String,
        targetId: String, attempt: Int
    ) {
        oneBot.requestApiAction(action, paramsJson) { raw ->
            val (retcode, detail) = parseRet(raw)
            if (retcode == 0) {
                log("v11 action $action -> ok (attempt=$attempt)")
                // v2.24.0：手环发起的撤回成功后，本机入库同步灰显 + 回推撤回帧。
                // 旧链路只回 action_result toast——手环聊天页那条消息永远亮着
                // （撤回感知只覆盖对方撤回的 WS 事件 onRecall，本端主动撤回无帧）。
                if (action == "delete_msg") {
                    val mid = paramsJson
                        .substringAfter("\"message_id\":", "").trim()
                        .trimEnd('}', ' ').trim('"')
                    val hit = runCatching { store.recallByMessageId(mid) }.getOrNull()
                    if (hit != null) {
                        MessageBus.notify(hit.first)
                        bandSender(buildRecallFrame(hit.first, hit.second))
                        bandSender(store.buildConversationFrame(0))
                    }
                }
                bandSender(actionResultFrame(seq, label + "成功", targetId))
                return@requestApiAction
            }
            log("v11 action $action -> fail attempt=$attempt retcode=$retcode detail=${detail.take(140)}")
            // delete_msg + 1200：经 get_msg 区分「消息仍在」与「状态无法确认」，
            // 两种结论都只影响终败文案，不再产生任何假成功（v2.18.1）
            if (action == "delete_msg" && retcode == 1200) {
                val mid = paramsJson
                    .substringAfter("\"message_id\":", "").trim()
                    .trimEnd('}', ' ')
                if (mid.isNotEmpty()) {
                    oneBot.requestApiAction("get_msg", "{\"message_id\":$mid}") { graw ->
                        val (gcode, gmsg) = parseRet(graw)
                        log("delete_msg 1200 probe get_msg retcode=$gcode msg=${gmsg.take(60)}")
                        if (attempt < 3) {
                            retryBackoff(attempt)
                            attemptCall(action, paramsJson, seq, label, targetId, attempt + 1)
                        } else {
                            // 终败：按探测结果给出可行动的如实文案（绝不回"成功"）
                            val info = if (gcode == 0) {
                                label + "失败：协议端撤回超时（NapCat recallMsg NT 超时）且消息仍在，" +
                                    "建议升级/重启 NapCat 后重试"
                            } else {
                                label + "失败：协议端超时且撤回结果无法确认（retcode=1200），" +
                                    "请到 QQ 里确认消息状态；建议升级 NapCat"
                            }
                            bandSender(actionResultFrame(seq, info, targetId))
                        }
                    }
                    return@requestApiAction
                }
            }
            if (attempt < 3) {
                retryBackoff(attempt)
                attemptCall(action, paramsJson, seq, label, targetId, attempt + 1)
            } else {
                val info = when {
                    retcode != null && detail.isNotBlank() ->
                        label + "失败（协议端 retcode=$retcode）：${detail.take(80)}"
                    retcode != null -> label + "失败（协议端 retcode=$retcode）"
                    else -> label + "失败（协议端未响应或动作不支持）"
                }
                bandSender(actionResultFrame(seq, info, targetId))
            }
        }
    }

    /** v2.18.0：解析 retcode 与可读错误明细（wording 优先，message 兜底） */
    private fun parseRet(raw: String?): Pair<Int?, String> = try {
        val o = JsonParser.parseString(raw).asJsonObject
        val code = o.get("retcode")?.takeIf { it.isJsonPrimitive }?.asInt
        val msg = o.get("wording")?.takeIf { it.isJsonPrimitive }?.asString
            ?: o.get("message")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
        code to msg
    } catch (t: Throwable) {
        null to ""
    }

    /** v2.18.0：重试间隔（1.2s / 2.5s），回调线程短暂阻塞可接受 */
    private fun retryBackoff(attempt: Int) {
        try {
            Thread.sleep(if (attempt == 1) 1200L else 2500L)
        } catch (_: Throwable) {
        }
    }

    /** v2.13.0：动作结果回帧（手环端 toast 展示） */
    private fun actionResultFrame(seq: Int, info: String, targetId: String): String {
        val obj = JsonObject()
        obj.addProperty("type", "action_result")
        obj.addProperty("seq", seq)
        obj.addProperty("ok", info.endsWith("成功"))
        obj.addProperty("info", info)
        if (targetId.isNotEmpty()) obj.addProperty("target_id", targetId)
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
