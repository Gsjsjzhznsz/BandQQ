package com.bandqq.sync.onebot

import org.json.JSONArray
import org.json.JSONObject

/** OneBot v11 消息的统一中间表示 */
data class OneBotMessage(
    val messageId: Long,          // OneBot message_id
    val historySeq: String?,      // 历史接口返回的 message_seq（翻页锚点优先用它）
    val chatType: String,         // group / private
    val targetId: String,         // 群号 / 好友QQ
    val senderId: String,
    val senderName: String,
    val content: String,          // 已还原的可读文本（@xx [图片] 等）
    val time: Long,               // Unix 秒
    val isSelf: Boolean,
    val atMe: Boolean = false,
    val imageUrl: String? = null,
    val raw: String = ""
)

object OneBotParser {

    private val SELF_AT = Regex("@(\\d{5,11})(?:\\s|$)")

    /**
     * 解析 OneBot 消息事件（message / message_sent）。
     * extractSegs：还原 at 段为 @QQ 并判断是否 @我；image 提取 url；face/record 等转占位文本。
     */
    fun parseEvent(json: JSONObject, selfId: String): OneBotMessage? {
        val type = json.optString("post_type")
        if (type != "message" && type != "message_sent") return null
        val detail = json.optJSONObject("sender")
        val senderId = detail?.optString("user_id")?.takeIf { it.isNotEmpty() && it != "null" }
            ?: json.optLong("user_id").takeIf { it != 0L }?.toString()
            ?: return null
        val messageType = json.optString("message_type", "group")
        val targetId = if (messageType == "group") {
            json.optLong("group_id").takeIf { it != 0L }?.toString() ?: return null
        } else senderId
        val senderName = detail?.optString("card")?.takeIf { it.isNotEmpty() && it != "null" }
            ?: detail?.optString("nickname")?.takeIf { it.isNotEmpty() && it != "null" }
            ?: senderId
        val time = json.optLong("time", System.currentTimeMillis() / 1000)
        val messageId = json.optLong("message_id", -1L)
        val segs = extractSegs(json, selfId)
        val isSelf = senderId == selfId || type == "message_sent"
        return OneBotMessage(
            messageId = messageId,
            historySeq = json.optString("message_seq").takeIf { it.isNotEmpty() && it != "null" }
                ?: json.optString("real_seq").takeIf { it.isNotEmpty() && it != "null" },
            chatType = messageType,
            targetId = targetId,
            senderId = senderId,
            senderName = senderName,
            content = segs.second,
            time = time,
            isSelf = isSelf,
            atMe = segs.first,
            imageUrl = json.optJSONObject("__thumb")?.optString("url"),
            raw = json.toString()
        )
    }

    /**
     * 提取 message 段。返回 (atMe, 文本)。
     * message 可为字符串（CQ 码）或段数组。
     */
    fun extractSegs(json: JSONObject, selfId: String): Pair<Boolean, String> {
        var atMe = false
        val sb = StringBuilder()
        val message = json.opt("message")
        when (message) {
            is JSONArray -> {
                for (i in 0 until message.length()) {
                    val seg = message.optJSONObject(i) ?: continue
                    when (seg.optString("type")) {
                        "text" -> sb.append(seg.optJSONObject("data")?.optString("text") ?: "")
                        "at" -> {
                            val qq = seg.optJSONObject("data")?.optString("qq") ?: ""
                            when {
                                qq == "all" -> {
                                    sb.append("@全体成员 ")
                                    atMe = true
                                }
                                qq == selfId -> {
                                    sb.append("@$selfId ")
                                    atMe = true
                                }
                                qq.isNotEmpty() && qq != "null" -> sb.append("@$qq ")
                            }
                        }
                        "image" -> {
                            val url = seg.optJSONObject("data")?.optString("url")
                            if (url != null && url.isNotEmpty()) {
                                try { json.put("__thumb", JSONObject().put("url", url)) } catch (_: Exception) {}
                            }
                            sb.append("[图片]")
                        }
                        "face" -> sb.append("[表情]")
                        "record" -> sb.append("[语音]")
                        "video" -> sb.append("[视频]")
                        "forward" -> sb.append("[合并转发]")
                        "json" -> sb.append("[卡片]")
                        "reply" -> {}
                        else -> {}
                    }
                }
            }
            is String -> {
                var text = message
                if (text.contains("[CQ:at,qq=$selfId]") || text.contains("[CQ:at,qq=all]")) atMe = true
                text = text.replace(Regex("\\[CQ:at,qq=(all|$selfId)(,[^\\]]*)?\\]"), "@${if (atMe) selfId else "全体成员"} ")
                text = text.replace(Regex("\\[CQ:image[,:][^\\]]*url=([^,\\]&]+)[^\\]]*\\]")) { m ->
                    try { json.put("__thumb", JSONObject().put("url", m.groupValues[1])) } catch (_: Exception) {}
                    "[图片]"
                }
                text = text.replace(Regex("\\[CQ:image[,:][^\\]]*\\]"), "[图片]")
                text = text.replace(Regex("\\[CQ:face[,:][^\\]]*\\]"), "[表情]")
                text = text.replace(Regex("\\[CQ:record[,:][^\\]]*\\]"), "[语音]")
                text = text.replace(Regex("\\[CQ:video[,:][^\\]]*\\]"), "[视频]")
                text = text.replace(Regex("\\[CQ:forward[,:][^\\]]*\\]"), "[合并转发]")
                text = text.replace(Regex("\\[CQ:\\w+[,:][^\\]]*\\]"), "")
                sb.append(text)
            }
        }
        // 兜底：文本里出现 @自己QQ号
        if (!atMe && selfId.isNotEmpty() && sb.toString().contains("@$selfId")) atMe = true
        return Pair(atMe, sb.toString().trim())
    }

    /** get_login_info 响应 */
    fun parseLoginInfo(resp: JSONObject): Pair<String, String>? {
        val data = resp.optJSONObject("data") ?: return null
        val uid = data.optLong("user_id", 0L).takeIf { it != 0L }?.toString() ?: return null
        val nick = data.optString("nickname").takeIf { it.isNotEmpty() } ?: uid
        return Pair(uid, nick)
    }

    /**
     * get_group_msg_history / get_friend_msg_history 响应。
     * 兼容 data.messages 数组与 data 直接数组两种实现（NapCat/go-cqhttp/LLOneBot）。
     */
    fun parseHistoryResponse(resp: JSONObject): List<OneBotMessage> {
        val data = resp.opt("data") ?: return emptyList()
        val arr: JSONArray = when (data) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("messages") ?: return emptyList()
            else -> return emptyList()
        }
        val out = ArrayList<OneBotMessage>()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val messageId = m.optLong("message_id", -1L)
            val sender = m.optJSONObject("sender")
            val senderId = sender?.optString("user_id")?.takeIf { it.isNotEmpty() && it != "null" }
                ?: m.optLong("user_id").takeIf { it != 0L }?.toString() ?: ""
            val messageType = m.optString("message_type", "group")
            val targetId = if (messageType == "group")
                m.optLong("group_id").takeIf { it != 0L }?.toString() ?: "" else senderId
            val senderName = sender?.optString("card")?.takeIf { it.isNotEmpty() && it != "null" }
                ?: sender?.optString("nickname")?.takeIf { it.isNotEmpty() && it != "null" }
                ?: senderId
            val segs = extractSegs(m, "")
            out.add(
                OneBotMessage(
                    messageId = messageId,
                    historySeq = m.optString("message_seq").takeIf { it.isNotEmpty() && it != "null" }
                        ?: m.optLong("message_seq").takeIf { it != 0L }?.toString(),
                    chatType = messageType,
                    targetId = targetId,
                    senderId = senderId,
                    senderName = senderName,
                    content = segs.second,
                    time = m.optLong("time", 0L),
                    isSelf = false,
                    atMe = segs.first,
                    imageUrl = m.optJSONObject("__thumb")?.optString("url"),
                    raw = m.toString()
                )
            )
        }
        return out
    }

    /** 消息 → 手环推送帧 JSON 字符串 */
    fun toHandBandFrame(
        m: OneBotMessage,
        atMe: Boolean = m.atMe,
        thumb: String? = null
    ): String {
        val o = JSONObject()
        o.put("type", "message")
        o.put("target_id", m.targetId)
        o.put("chat_type", m.chatType)
        o.put("sender_id", m.senderId)
        o.put("sender_name", m.senderName)
        o.put("content", m.content)
        o.put("time", m.time) // OneBot 秒，手环端 normalize 为毫秒
        o.put("is_self", m.isSelf)
        o.put("message_id", m.messageId)
        if (atMe) o.put("at_me", true)
        if (thumb != null) o.put("thumb", thumb)
        return o.toString()
    }
}
