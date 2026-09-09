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
    val isSelf: Boolean = false
)

/** 快捷回复：label 为手环按钮上的纯文本（已剥离 CQ 码），content 为实际发送内容（保留 CQ 码） */
data class QuickReply(val label: String, val content: String)

class OneBotParser {

    companion object {
        private val EMOJI = Regex("\\p{So}|\\p{Sk}|[\\x{2600}-\\x{27BF}\\x{2B00}-\\x{2BFF}\\x{1F000}-\\x{1FAFF}\\x{FE0F}\\x{200D}]")
        private val CQ_CODE = Regex("\\[CQ:[^\\]]*\\]")

        fun stripEmoji(s: String): String = s.replace(EMOJI, "")

        fun markEmoji(s: String): String = s.replace(EMOJI, "[表情]")

        /** 剥离 CQ 码得到纯文本（用于手环按钮标签，避免显示一堆格式代码） */
        fun stripCq(s: String): String = s.replace(CQ_CODE, "").trim()

        /**
         * 将用户配置的快捷回复原文解析为 QuickReply 列表。
         * 标签 = 剥离 CQ 码 + 降级 emoji + 截短到 6 字（手环按钮宽度有限）；
         * 内容 = 保留原文（CQ 码原样发给 OneBot，保证表情等能正确发送）。
         */
        fun parseQuickReplies(rawList: List<String>): List<QuickReply> {
            val out = mutableListOf<QuickReply>()
            for (raw in rawList) {
                val text = raw.trim()
                if (text.isEmpty()) continue
                var label = stripEmoji(stripCq(text))
                    .replace("\\s+", " ").trim()
                if (label.isEmpty()) label = "回复"
                if (label.length > 6) label = label.take(5) + "…"
                out.add(QuickReply(label = label, content = text))
                if (out.size >= 6) break
            }
            return out
        }

        /** 构建下发手环的快捷回复帧 */
        fun buildQuickRepliesFrame(list: List<QuickReply>, seq: Int): String {
            val obj = JsonObject()
            obj.addProperty("type", "quick_replies")
            obj.addProperty("seq", seq)
            val arr = JsonArray()
            for (q in list) {
                val o = JsonObject()
                o.addProperty("label", q.label)
                o.addProperty("content", q.content)
                arr.add(o)
            }
            obj.add("list", arr)
            return obj.toString()
        }
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
        val senderId = obj.get("user_id")?.asLong?.toString() ?: return null
        val targetId = when (messageType) {
            "group" -> obj.get("group_id")?.asLong?.toString()
            "private" -> senderId
            else -> return null
        } ?: return null
        val content = degradeContent(obj.get("message"))
        val selfId = obj.get("self_id")?.let { if (it.isJsonPrimitive) it.asString else it.toString() }
        // OneBot 标准 time 为 Unix 秒（10 位），而本地发送链路使用毫秒（Date.now() 13 位）。
        // 统一转为毫秒，避免同一会话内秒/毫秒混排导致消息顺序跳变。
        val rawTime = obj.get("time")?.asLong ?: 0L
        return OneBotMessage(
            messageType = messageType,
            targetId = targetId,
            senderId = senderId,
            senderName = stripEmoji(sender?.get("nickname")?.asString ?: senderId),
            content = content,
            time = if (rawTime > 0 && rawTime < 100_000_000_000L) rawTime * 1000L else rawTime,
            isSelf = selfId != null && senderId == selfId
        )
    }

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

    fun toHandBandFrame(msg: OneBotMessage, visible: Boolean = true, targetName: String = msg.senderName): String {
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
        return obj.toString()
    }

    fun buildSendRequest(messageType: String, targetId: String, content: String): String {
        val body = JsonObject()
        val params = JsonObject()
        val idAsLong = targetId.toLongOrNull()
        if (messageType == "group") {
            body.addProperty("action", "send_group_msg")
            if (idAsLong != null) params.addProperty("group_id", idAsLong) else params.addProperty("group_id", targetId)
        } else {
            body.addProperty("action", "send_private_msg")
            if (idAsLong != null) params.addProperty("user_id", idAsLong) else params.addProperty("user_id", targetId)
        }
        params.addProperty("message", content)
        body.add("params", params)
        return body.toString()
    }

    /** 与 buildSendRequest 对应的 OneBot 动作路径名。 */
    fun actionName(messageType: String): String =
        if (messageType == "group") "send_group_msg" else "send_private_msg"
}
