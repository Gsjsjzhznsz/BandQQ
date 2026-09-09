package com.example.bandqq.onebot

import com.example.bandqq.sync.LogBus
import com.example.bandqq.sync.LogLevel
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class OneBotMessage(
    val messageType: String,  // "private" | "group"
    val targetId: String,     // group_id 或 user_id
    val senderId: String,
    val senderName: String,
    val content: String,
    val time: Long,
    val isSelf: Boolean = false,
    val atMe: Boolean = false,       // 消息 @ 了登录用户（Stapxs 同款判定：at 段 user_id == self_id）
    val messageId: String = "",      // OneBot message_id，翻页拉取历史用
    val messageSeq: String = "",     // 历史响应中的 message_seq（NapCat 翻页锚点，优先于 messageId）
    val imageUrl: String = ""        // 首个图片段的 URL（缩略图抓取用）
)

class OneBotParser {

    companion object {
        private val EMOJI = Regex("\\p{So}|\\p{Sk}|[\\x{2600}-\\x{27BF}\\x{2B00}-\\x{2BFF}\\x{1F000}-\\x{1FAFF}\\x{FE0F}\\x{200D}]")

        fun stripEmoji(s: String): String = s.replace(EMOJI, "")

        fun markEmoji(s: String): String = s.replace(EMOJI, "[表情]")
    }

    /** 消息段提取结果：降级文本 + @我标记 + 首图 URL */
    data class SegInfo(val content: String, val atMe: Boolean, val imageUrl: String)

    /** 逐段解析消息数组：文本保留、face/image/... 降级为中文标记、at 段还原为 @xxx。 */
    fun extractSegs(message: com.google.gson.JsonElement?, selfId: String?): SegInfo {
        if (message == null) return SegInfo("", false, "")
        if (message.isJsonPrimitive && message.asJsonPrimitive.isString) return SegInfo(message.asString, false, "")
        if (!message.isJsonArray) return SegInfo("", false, "")
        val arr: JsonArray = message.asJsonArray
        val sb = StringBuilder()
        var atMe = false
        var imageUrl = ""
        for (elem in arr) {
            val seg = if (elem.isJsonObject) elem.asJsonObject else continue
            when (seg.get("type")?.asString) {
                "text" -> {
                    val text = seg.getAsJsonObject("data")?.get("text")?.asString ?: ""
                    sb.append(markEmoji(text))
                }
                "at" -> {
                    // at 段：@登录用户 → atMe 标记；文本还原为 @QQ号 保留语义（不再落到 [其他]）
                    val data = seg.getAsJsonObject("data")
                    val qq = data?.get("qq")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
                    if (selfId != null && qq.isNotBlank() && qq == selfId) atMe = true
                    if (qq == "all") sb.append("@全体成员 ") else sb.append("@$qq ")
                }
                "face" -> sb.append("[表情]")
                "image" -> {
                    sb.append("[图片]")
                    if (imageUrl.isEmpty()) {
                        val d = seg.getAsJsonObject("data")
                        val url = d?.get("url")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
                        val file = d?.get("file")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
                        imageUrl = when {
                            url.startsWith("http") -> url
                            file.startsWith("http") -> file
                            else -> ""
                        }
                    }
                }
                "record", "voice" -> sb.append("[语音]")
                "video" -> sb.append("[视频]")
                "file" -> sb.append("[文件]")
                else -> sb.append("[其他]")
            }
        }
        return SegInfo(sb.toString(), atMe, imageUrl)
    }

    /** 条目锚点提取：优先 message_seq（NapCat 历史分页语义），缺失时回退 message_id。 */
    fun extractAnchor(obj: JsonObject): String {
        val seq = obj.get("message_seq")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
        if (seq.isNotBlank() && seq != "null") return seq
        return obj.get("message_id")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
    }

    fun parseMessageEvent(json: String): OneBotMessage? {
        val obj = try {
            JsonParser.parseString(json).asJsonObject
        } catch (e: Exception) {
            LogBus.log("OneBotParser", LogLevel.WARN, "parse error: $e")
            return null
        }
        if (obj.get("post_type")?.asString != "message") return null
        val messageType = obj.get("message_type")?.asString ?: return null
        val sender = obj.getAsJsonObject("sender")
        val senderId = obj.get("user_id")?.let { primitiveAsString(it) } ?: return null
        val targetId = when (messageType) {
            "group" -> obj.get("group_id")?.let { primitiveAsString(it) }
            "private" -> senderId
            else -> return null
        } ?: return null
        val selfId = obj.get("self_id")?.let { primitiveAsString(it) }
        val segs = extractSegs(obj.get("message"), selfId)
        // OneBot 标准 time 为 Unix 秒（10 位），而本地发送链路使用毫秒（Date.now() 13 位）。
        // 统一转为毫秒，避免同一会话内秒/毫秒混排导致消息顺序跳变。
        val rawTime = obj.get("time")?.asLong ?: 0L
        return OneBotMessage(
            messageType = messageType,
            targetId = targetId,
            senderId = senderId,
            senderName = stripEmoji(sender?.get("nickname")?.asString ?: senderId),
            content = segs.content,
            time = if (rawTime > 0 && rawTime < 100_000_000_000L) rawTime * 1000L else rawTime,
            isSelf = selfId != null && senderId == selfId,
            atMe = segs.atMe,
            messageId = obj.get("message_id")?.let { primitiveAsString(it) } ?: "",
            imageUrl = segs.imageUrl
        )
    }

    private fun primitiveAsString(el: com.google.gson.JsonElement): String =
        if (el.isJsonPrimitive) el.asJsonPrimitive.asString else el.toString()

    fun degradeContent(message: com.google.gson.JsonElement?): String {
        if (message == null) return ""
        if (message.isJsonPrimitive && message.asJsonPrimitive.isString) return message.asString
        if (!message.isJsonArray) return ""
        val arr: JsonArray = message.asJsonArray
        val sb = StringBuilder()
        for (elem in arr) {
            val seg = if (elem.isJsonObject) elem.asJsonObject else continue
            when (seg.get("type")?.asString) {
                "text" -> {
                    val text = seg.getAsJsonObject("data")?.get("text")?.asString ?: ""
                    sb.append(markEmoji(text))
                }
                "face" -> sb.append("[表情]")
                "image" -> sb.append("[图片]")
                "record", "voice" -> sb.append("[语音]")
                "video" -> sb.append("[视频]")
                "file" -> sb.append("[文件]")
                else -> sb.append("[其他]")
            }
        }
        return sb.toString()
    }

    fun toHandBandFrame(
        msg: OneBotMessage,
        visible: Boolean = true,
        targetName: String = msg.senderName,
        thumb: String? = null
    ): String {
        val obj = JsonObject()
        obj.addProperty("type", "push_message")
        obj.addProperty("seq", 0)
        obj.addProperty("message_type", msg.messageType)
        obj.addProperty("target_id", msg.targetId)
        obj.addProperty("sender_id", msg.senderId)
        obj.addProperty("sender_name", msg.senderName)
        obj.addProperty("target_name", targetName)
        obj.addProperty("content", msg.content)
        obj.addProperty("time", msg.time)
        obj.addProperty("is_self", msg.isSelf)
        obj.addProperty("visible", visible)
        if (msg.atMe) obj.addProperty("at_me", true)
        if (!thumb.isNullOrBlank()) obj.addProperty("thumb", thumb)
        return obj.toString()
    }

    /** 解析登录信息响应（get_login_info）：data.user_id + data.nickname。 */
    fun parseLoginInfo(raw: String?): Pair<String, String>? {
        if (raw.isNullOrBlank()) return null
        return try {
            val obj = JsonParser.parseString(raw).asJsonObject
            val data = obj.getAsJsonObject("data") ?: return null
            val uid = data.get("user_id")?.let { primitiveAsString(it) } ?: return null
            val nick = data.get("nickname")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
            uid to nick
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 解析 OneBot 历史消息响应（get_group_msg_history / get_friend_msg_history）。
     * 兼容 data.messages 数组（NapCat/Lagrange）与 data 直接为数组两种实现；
     * 历史条目无 post_type，按 group_id 判定会话类型，群名片优先 card。
     */
    fun parseHistoryResponse(raw: String?): List<OneBotMessage> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = mutableListOf<OneBotMessage>()
        try {
            val root = JsonParser.parseString(raw)
            if (!root.isJsonObject) return emptyList()
            val data = root.asJsonObject.get("data") ?: return emptyList()
            val arr: JsonArray = when {
                data.isJsonArray -> data.asJsonArray
                data.isJsonObject && data.asJsonObject.get("messages")?.isJsonArray == true -> data.asJsonObject.getAsJsonArray("messages")
                else -> return emptyList()
            }
            for (elem in arr) {
                val obj = if (elem.isJsonObject) elem.asJsonObject else continue
                val messageType = if (obj.has("group_id")) "group" else "private"
                val sender = obj.getAsJsonObject("sender")
                val senderId = obj.get("user_id")?.let { primitiveAsString(it) } ?: continue
                val targetId = if (messageType == "group") {
                    obj.get("group_id")?.let { primitiveAsString(it) } ?: continue
                } else senderId
                val selfId = obj.get("self_id")?.let { primitiveAsString(it) }
                val segs = extractSegs(obj.get("message"), selfId)
                val nick = sender?.get("card")?.takeIf { it.isJsonPrimitive && it.asString.isNotBlank() }?.asString
                    ?: sender?.get("nickname")?.takeIf { it.isJsonPrimitive }?.asString
                    ?: senderId
                val rawTime = obj.get("time")?.asLong ?: 0L
                out.add(
                    OneBotMessage(
                        messageType = messageType,
                        targetId = targetId,
                        senderId = senderId,
                        senderName = stripEmoji(nick),
                        content = segs.content,
                        time = if (rawTime > 0 && rawTime < 100_000_000_000L) rawTime * 1000L else rawTime,
                        isSelf = selfId != null && senderId == selfId,
                        atMe = segs.atMe,
                        messageId = obj.get("message_id")?.let { primitiveAsString(it) } ?: "",
                        messageSeq = extractAnchor(obj)
                    )
                )
            }
        } catch (e: Exception) {
            try { LogBus.log("OneBotParser", LogLevel.WARN, "history parse error: $e") } catch (t: Throwable) {}
        }
        return out
    }

    /**
     * 从发送动作的 OneBot 响应体中提取 message_id（data.message_id）。
     * 用于自发消息回填翻页锚点；解析失败/缺失返回空串。
     */
    fun parseSentMessageId(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return try {
            val obj = JsonParser.parseString(raw).asJsonObject
            val data = obj.getAsJsonObject("data") ?: return ""
            data.get("message_id")?.let { primitiveAsString(it) } ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    /** 构造发送参数（不含 action），供 HTTP 请求体与 WS echo RPC 共用。 */
    fun buildParams(messageType: String, targetId: String, content: String): JsonObject {
        val params = JsonObject()
        val idAsLong = targetId.toLongOrNull()
        if (messageType == "group") {
            if (idAsLong != null) params.addProperty("group_id", idAsLong) else params.addProperty("group_id", targetId)
        } else {
            if (idAsLong != null) params.addProperty("user_id", idAsLong) else params.addProperty("user_id", targetId)
        }
        params.addProperty("message", content)
        return params
    }

    fun buildSendRequest(messageType: String, targetId: String, content: String): String {
        val body = JsonObject()
        body.addProperty("action", if (messageType == "group") "send_group_msg" else "send_private_msg")
        body.add("params", buildParams(messageType, targetId, content))
        return body.toString()
    }

    /** 与 buildSendRequest 对应的 OneBot 动作路径名。 */
    fun actionName(messageType: String): String =
        if (messageType == "group") "send_group_msg" else "send_private_msg"

    /**
     * 构造发送结果回推帧（手机端 -> 手环）。
     * 手环据此把「发送中…」更新为「已送达 / 发送失败:原因」，让发送失败不再静默。
     */
    fun buildSendResultFrame(seq: Int, ok: Boolean, error: String?): String {
        val obj = JsonObject()
        obj.addProperty("type", "send_result")
        obj.addProperty("seq", seq)
        obj.addProperty("ok", ok)
        if (!error.isNullOrBlank()) obj.addProperty("error", error)
        return obj.toString()
    }
}
