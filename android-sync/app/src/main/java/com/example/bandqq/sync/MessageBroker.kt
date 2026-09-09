package com.example.bandqq.sync

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
        /** (是否成功, 失败原因, 原始响应体)。失败原因由 OneBot retcode/HTTP 状态解析得出；
         *  原始响应体供发送方回填 message_id（自发消息翻页锚点用）。 */
        callback: (Boolean, String?, String?) -> Unit = { _, _, _ -> }
    )

    /**
     * 带参调用 OneBot 通用接口（历史分页等）。JVM 测试 Fake 默认空实现返回 null。
     * 成功回传原始响应体，失败/不支持回传 null。
     */
    fun requestApiParams(action: String, params: com.google.gson.JsonObject, callback: (String?) -> Unit) {
        callback(null)
    }
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
     * v1.2.0 T2：手环业务帧统一出口，带在线门控。
     * 快应用关闭/挂后台被杀后，互联通道仍在投递帧（系统蓝牙栈缓存或唤醒快应用），
     * 会白白耗电甚至拖垮手环 —— 离线时业务帧直接丢弃：消息已在 store + pendingTargets，
     * 重连后由 onBandConnected() 统一回放。心跳 ping 不走此门控（需持续探测手环回归）。
     */
    private fun sendBand(frame: String) {
        if (!SyncState.bandConnected) {
            log("band offline, drop frame (" + frame.length + "B, type=" + frame.take(40) + ")")
            return
        }
        bandSender(frame)
    }

    /** 手环 pong 心跳应答回调，由互联层在收到 pong 时调用以确认手环在线。 */
    var onBandPong: () -> Unit = {}

    /**
     * 图片缩略图抓取钩子（仅 Android 运行时注入，JVM 测试为 null）：
     * 输入图片 URL，回调 base64 data URI（失败回传 null）。带 4s 超时。
     */
    var thumbFetcher: ((url: String, cb: (String?) -> Unit) -> Unit)? = null

    /** 翻页拉取中的保护锁：同会话同时只发一个 older 请求 */
    private val olderLoading = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * 断连期间有新消息的会话集合（冷启动补推用）：
     * 手环应用被杀/挂后台期间互联下发即丢，重连成功后按会话回推最近一页。
     */
    private val pendingTargets = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

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
                // 发送并回推真实投递结果：OneBot retcode 校验后把成功/失败（含原因）
                // 通过 send_result 帧回推手环，发送失败不再静默（此前 HTTP 200 即算成功，
                // 群号走 send_private_msg 之类的业务错误完全无感知）
                oneBot.sendMessage(messageType, targetId, content) { ok, err, respBody ->
                    if (!ok) {
                        log("send_message -> $targetId 失败: $err")
                    }
                    // 发送结果回推：手环离线时没有意义（聊天页都不在了），门控丢弃
                    sendBand(parser.buildSendResultFrame(seq, ok, err))
                    // 回填自发消息锚点：OneBot 发送响应携带 message_id，缺失锚点会导致该消息
                    // 成为翻页断点（v1.1.0 翻页失效诱因之一）
                    if (ok) {
                        val msgId = parser.parseSentMessageId(respBody)
                        if (msgId.isNotBlank()) {
                            store.addMessage(
                                targetId,
                                StoredMessage(
                                    messageType = messageType,
                                    senderId = targetId,
                                    senderName = store.contactName(targetId).ifBlank { targetId },
                                    content = content,
                                    time = sendTime,
                                    isSelf = true,
                                    messageId = msgId
                                )
                            )
                        }
                    }
                }
                if (!SyncState.bandConnected) pendingTargets.add(targetId)
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
                // 回推手环：与手环本地回显相同 time，upsertMessage 按 time|content 去重不会重复显示。
                // v1.2.0：手环离线时门控丢弃（不向已死通道推帧）
                val visible = store.isVisibleContact(targetId)
                val targetName = store.conversationName(targetId, messageType, selfSenderName)
                sendBand(
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
                // 翻页：older=true 转发 OneBot 分页历史（get_group_msg_history / get_friend_msg_history），
                // 以 message_seq 锚点向更早翻页（v1.1.1 重写：锚点健壮 + 多跳补齐）
                if (obj.get("older")?.asBoolean == true) {
                    val beforeTime = obj.get("before_time")?.asLong ?: 0L
                    fetchOlderHistory(targetId, beforeTime, limit, seq)
                    return true
                }
                // 去重：手环 onInit+onShow 会连续发多次 get_history，只响应第一次，
                // 避免 history_list 多次到达覆盖 push_message 新消息
                if (HistoryDedup.tryRun(targetId)) {
                    // 冷启动/首次打开且本地历史不足时先从 OneBot 拉最新一页补齐再回推，
                    // 避免手机端刚装机/清空数据后手环只看到实时消息
                    val have = store.getAllMessages(targetId).size
                    if (have < limit) {
                        fetchLatestThenReply(targetId, limit, seq)
                    } else {
                        sendBand(store.buildHistoryFrame(targetId, limit, seq))
                    }
                } else {
                    log("get_history dedup skip $targetId")
                }
                return true
            }
            "get_conversations" -> {
                sendBand(store.buildConversationFrame(seq))
                return true
            }
            "clear_all_history" -> {
                store.clearAllHistory()
                return true
            }
            "get_visible_contacts" -> {
                val frame = store.buildVisibleContactsFrame(seq)
                log("get_visible_contacts -> ${store.getVisibleContacts().size} contacts")
                sendBand(frame)
                return true
            }
            "get_connect_state" -> {
                sendBand(SyncStatePush.buildFrame())
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
                messageId = msg.messageId,
                messageSeq = msg.messageSeq
            )
        )
        MessageBus.notify(msg.targetId)
        // v1.2.0 T2：手环离线（快应用关闭/被杀）期间不再向已死通道推帧（停止蓝牙传输路径），
        // 消息已入 store + pendingTargets，重连后由 onBandConnected() 回放。
        // 缩略图也不抓（省流量省电），回放时只回文本占位。
        if (!SyncState.bandConnected) {
            pendingTargets.add(msg.targetId)
            return null
        }
        val visible = store.isVisibleContact(msg.targetId)
        val targetName = store.conversationName(msg.targetId, msg.messageType, msg.senderName)
        // 图片消息：经缩略图钩子抓取后再下发（一次投递，避免手环去重丢图）；
        // 抓取失败/无钩子时直接下发占位帧
        val url = msg.imageUrl
        val fetcher = thumbFetcher
        if (url.isNotBlank() && fetcher != null) {
            // 异步抓取缩略图后一次性下发（抓取失败回传 null，仅 [图片] 占位）
            fetcher(url) { b64 ->
                // 抓取是异步的：回调时手环可能已离线，再次门控
                if (SyncState.bandConnected) {
                    bandSender(parser.toHandBandFrame(msg, visible, targetName, b64))
                }
            }
            return null
        }
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
        sendBand(SyncStatePush.buildFrame())
        if (connected) {
            // 连接建立即拉登录账号并推手环：设置页展示 + @我 判定依据
            oneBot.requestApiParams("get_login_info", com.google.gson.JsonObject()) { raw ->
                val info = parser.parseLoginInfo(raw)
                if (info != null) {
                    SyncState.loginUserId = info.first
                    SyncState.loginNickname = info.second
                    val frame = com.google.gson.JsonObject()
                    frame.addProperty("type", "login_info")
                    frame.addProperty("user_id", info.first)
                    frame.addProperty("nickname", info.second)
                    sendBand(frame.toString())
                }
            }
        }
        if (connected && !autoFetchDone) {
            tryAutoFetch()
        }
    }

    /**
     * 手环连接建立（pong 确认在线）：
     * 1) 补推可见联系人骨架；2) 回推断连期间错过的消息（每会话最近 30 条，
     * 手环 upsertMessage 按 time|content 幂等去重，重复投递无副作用）。
     */
    fun onBandConnected() {
        pushVisibleContacts()
        // v1.2.0 T2：重连即推会话列表（冷启动后手环列表立刻有数据，不等下一条消息）
        sendBand(store.buildConversationFrame(0))
        val visibleIds = store.getVisibleContacts().map { it.id }.toSet()
        val targets = pendingTargets.filter { it in visibleIds }
        pendingTargets.clear()
        for (t in targets) {
            // 回放走 bandSender 直连（此刻刚确认在线，门控已是 true；保持一致用 sendBand 亦可）
            bandSender(store.buildHistoryFrame(t, 30, 0))
            log("band reconnect catch-up -> $t")
        }
    }

    /** 手环本地历史不足时：先从 OneBot 拉最新一页入本地库，再回推 history_list（失败也回推本地已有） */
    private fun fetchLatestThenReply(targetId: String, limit: Int, seq: Int) {
        val isGroup = store.conversationType(targetId) != "private"
        val action = if (isGroup) "get_group_msg_history" else "get_friend_msg_history"
        val params = historyBaseParams(targetId, isGroup, 0L)
        params.addProperty("count", limit)
        oneBot.requestApiParams(action, params) { raw ->
            if (raw != null) {
                val msgs = parseAndRemapHistory(raw, targetId, isGroup)
                for (m in msgs) {
                    store.addMessage(
                        m.targetId,
                        StoredMessage(m.messageType, m.senderId, m.senderName, m.content, m.time, m.isSelf, m.messageId, m.messageSeq)
                    )
                }
                if (msgs.isNotEmpty()) MessageBus.notify(targetId)
            }
            bandSender(store.buildHistoryFrame(targetId, limit, seq))
        }
    }

    /** 历史请求基础参数：群/私聊 id + 私聊兼容 Lagrange 的 time（秒）参数 */
    private fun historyBaseParams(targetId: String, isGroup: Boolean, beforeTime: Long): com.google.gson.JsonObject {
        val params = com.google.gson.JsonObject()
        val idAsLong = targetId.toLongOrNull()
        if (isGroup) {
            if (idAsLong != null) params.addProperty("group_id", idAsLong) else params.addProperty("group_id", targetId)
        } else {
            if (idAsLong != null) params.addProperty("user_id", idAsLong) else params.addProperty("user_id", targetId)
            // Lagrange 的 get_friend_msg_history 以 time（Unix 秒）为锚点；NapCat 忽略该字段
            if (beforeTime > 0) params.addProperty("time", beforeTime / 1000 - 1)
        }
        return params
    }

    /** 解析历史响应并重映射私聊自发条目的 targetId（响应里自发条目 targetId=self，需归位到会话对方） */
    private fun parseAndRemapHistory(raw: String, targetId: String, isGroup: Boolean): List<OneBotMessage> {
        if (raw.isBlank()) return emptyList()
        return parser.parseHistoryResponse(raw).map { m ->
            if (!isGroup && m.isSelf && m.targetId != targetId) m.copy(targetId = targetId) else m
        }
    }

    /**
     * 向更早翻页（v1.1.1 重写）：
     * - 锚点取本地存的 message_seq（响应条目优先 message_seq 字段，兼容 NapCat；
     *   本地全部晚于 beforeTime 时回退最旧一条，不再直接判死）；
     * - 本地完全无锚点时先拉最新一页建立锚点（两段式）；
     * - 返回消息全部晚于 beforeTime 时自动向更早续翻（最多 3 跳）；
     * - has_more 以 OneBot 原始返回条数判定（filter 前空页不再误判"没有更早消息"）；
     * - OneBot 不可用时回退本地更早一页，不阻塞手环。
     */
    private fun fetchOlderHistory(targetId: String, beforeTime: Long, limit: Int, seq: Int) {
        if (olderLoading.putIfAbsent(targetId, true) == true) {
            log("fetchOlder skip(loading) $targetId")
            return
        }
        fun done() { olderLoading.remove(targetId) }
        val isGroup = store.conversationType(targetId) != "private"
        val action = if (isGroup) "get_group_msg_history" else "get_friend_msg_history"
        val collected = java.util.Collections.synchronizedList(mutableListOf<OneBotMessage>())
        // 多跳收集去重（同跳内/跨跳重复响应不再叠加）
        val collectedKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        var hops = 0

        fun remember(msgs: List<OneBotMessage>) {
            for (m in msgs) {
                if (beforeTime > 0 && m.time >= beforeTime) continue
                val k = m.time.toString() + "|" + m.content
                if (collectedKeys.add(k)) collected.add(m)
            }
        }

        fun finish(hasMore: Boolean) {
            val snapshot = collected.toList()
            val toSend = snapshot
                .filter { beforeTime <= 0 || it.time < beforeTime }
                .sortedBy { it.time }
                .takeLast(limit)
            bandSender(
                store.buildOlderHistoryFrame(
                    targetId,
                    toSend.map {
                        StoredMessage(it.messageType, it.senderId, it.senderName, it.content, it.time, it.isSelf, it.messageId, it.messageSeq)
                    },
                    seq,
                    hasMore
                )
            )
            done()
        }

        fun persist(msgs: List<OneBotMessage>) {
            for (m in msgs) {
                store.addMessage(
                    m.targetId,
                    StoredMessage(m.messageType, m.senderId, m.senderName, m.content, m.time, m.isSelf, m.messageId, m.messageSeq)
                )
            }
            if (msgs.isNotEmpty()) MessageBus.notify(targetId)
        }

        fun requestOnce(anchorSeq: String?) {
            hops++
            val params = historyBaseParams(targetId, isGroup, beforeTime)
            params.addProperty("count", limit + 1)
            if (!anchorSeq.isNullOrBlank()) {
                // NapCat 要求 message_seq 为数值；非数字锚点（个别实现的字符串 id）按原样传
                val asLong = anchorSeq.toLongOrNull()
                if (asLong != null) params.addProperty("message_seq", asLong) else params.addProperty("message_seq", anchorSeq)
                params.addProperty("reverseOrder", true)
            }
            oneBot.requestApiParams(action, params) { raw ->
                val rawMsgs = if (raw != null) parseAndRemapHistory(raw, targetId, isGroup) else emptyList()
                persist(rawMsgs)
                remember(rawMsgs)
                if (raw == null || rawMsgs.isEmpty()) {
                    // OneBot 不可用/无数据：本地收集为空时回退本地更早一页，保证手环不空转
                    if (collected.isEmpty()) {
                        val local = store.getOlderLocal(targetId, beforeTime, limit)
                        bandSender(store.buildOlderHistoryFrame(targetId, local, seq, hasMore = false))
                        done()
                    } else {
                        finish(hasMore = false)
                    }
                    return@requestApiParams
                }
                val rawFull = rawMsgs.size >= limit + 1  // OneBot 满页返回 → 大概率仍有更早消息
                if (collected.size >= limit) {
                    finish(hasMore = rawFull)
                    return@requestApiParams
                }
                if (hops >= 3) {
                    finish(hasMore = rawFull)
                    return@requestApiParams
                }
                // 本跳消息全部晚于 beforeTime（锚点太新）：用响应最旧一条的锚点继续向更早翻
                val oldest = rawMsgs.minByOrNull { it.time }
                val nextSeq = oldest?.let { it.messageSeq.ifBlank { it.messageId } } ?: ""
                if (nextSeq.isBlank() || nextSeq == anchorSeq) {
                    finish(hasMore = rawFull)
                } else {
                    requestOnce(nextSeq)
                }
            }
        }

        val anchor = store.pickAnchor(targetId, beforeTime)
        if (anchor != null) {
            requestOnce(anchor.messageSeq.ifBlank { anchor.messageId })
        } else {
            // 本地无任何锚点（新装机/被清空/纯自发文本消息）：先拉最新一页建立锚点
            val params = historyBaseParams(targetId, isGroup, beforeTime)
            params.addProperty("count", limit + 1)
            oneBot.requestApiParams(action, params) { raw ->
                val rawMsgs = if (raw != null) parseAndRemapHistory(raw, targetId, isGroup) else emptyList()
                persist(rawMsgs)
                if (rawMsgs.isEmpty()) {
                    val local = store.getOlderLocal(targetId, beforeTime, limit)
                    bandSender(store.buildOlderHistoryFrame(targetId, local, seq, hasMore = false))
                    done()
                    return@requestApiParams
                }
                val oldest = rawMsgs.minByOrNull { it.time }
                val nextSeq = oldest?.let { it.messageSeq.ifBlank { it.messageId } } ?: ""
                if (nextSeq.isBlank()) {
                    remember(rawMsgs)
                    finish(hasMore = rawMsgs.size >= limit + 1)
                } else {
                    requestOnce(nextSeq)
                }
            }
        }
    }

    private fun tryAutoFetch() {
        autoFetch?.invoke(store)
    }

    /** 手环连接建立后补推可见联系人，确保保存时未连接的联系人在连接后自动同步到手环。 */
    fun pushVisibleContacts() {
        // 由 onConnect 主动调用（此刻必然在线），直连 bandSender
        bandSender(store.buildVisibleContactsFrame(0))
        log("pushVisibleContacts -> ${store.getVisibleContacts().size} contacts")
    }

    private fun log(msg: String) {
        try {
            LogBus.log("MessageBroker", LogLevel.DEBUG, msg)
        } catch (t: Throwable) {
            // JVM 单测环境下不可用，静默忽略
        }
    }
}
