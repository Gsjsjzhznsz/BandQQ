package com.example.bandqq.devtools

import org.json.JSONArray
import org.json.JSONObject

/**
 * OneBot v11 模拟事件构造器（v2.8.0 DevTools）。
 * 与 BandQQ 同步器内置「测试推送模拟器」同源的载荷形态（数组段格式，
 * NapCat/SnowLuma 默认上报格式），覆盖文本/@我/图片/表情/引用回复/
 * 语音/文件/长文本/撤回九类场景 + 自定义文本。
 *
 * 关键字段（与 OneBotParser.parseMessageEvent 对齐）：
 * - post_type=message, message_type=private|group
 * - self_id=10000（BandQQ 的"我"）：at 段 qq=10000 才会被判定为 @我
 * - user_id=发送者；group 时 targetId=group_id，private 时 targetId=user_id
 * - time=Unix 秒（10 位，OneBot 标准；BandQQ 会统一转毫秒）
 */
object MsgBuilder {

    const val SELF_ID = 10000L
    const val DEFAULT_USER_ID = 10086L
    const val DEFAULT_GROUP_ID = 20001L

    fun seg(type: String, vararg kv: Pair<String, String>): JSONObject {
        val o = JSONObject()
        o.put("type", type)
        val d = JSONObject()
        for ((k, v) in kv) d.put(k, v)
        o.put("data", d)
        return o
    }

    fun text(t: String): JSONObject = seg("text", "text" to t)

    /** 文本数组段消息 */
    fun textArray(t: String): JSONArray = JSONArray().put(text(t))

    /** 场景消息段：text/at/image/face/reply/voice/file/long/custom */
    fun scenarioSegments(scenario: String, customText: String): JSONArray {
        return when (scenario) {
            "at" -> JSONArray()
                .put(seg("at", "qq" to SELF_ID.toString()))
                .put(text("刚刚的方案你觉得怎么样？这条是@我模拟消息"))
            "image" -> JSONArray()
                .put(text("看看这张图"))
                .put(seg("image", "file" to "test.jpg", "url" to "https://example.com/test.jpg"))
            "face" -> JSONArray()
                .put(seg("face", "id" to "13"))
                .put(text("哈哈，这是表情消息测试"))
            "reply" -> JSONArray()
                .put(seg("reply", "id" to "-1"))
                .put(text("收到，马上处理！这条是引用回复测试"))
            "voice" -> JSONArray()
                .put(text("发来一条语音"))
                .put(seg("record", "file" to "test.amr"))
            "file" -> JSONArray()
                .put(seg("file", "name" to "需求文档.pdf", "size" to "102400"))
            "long" -> JSONArray()
                .put(
                    text(
                        "这是一条长文本测试：\n第一行模拟群通知内容，检查多行折行\n" +
                            "第二行 BandQQ 手环端应完整展示不截断\n第三行 滑动查看后续\n—— 完"
                    )
                )
            "custom" -> textArray(customText.ifBlank { "你好，这是 DevTools 自定义消息" })
            else -> textArray("你好呀，这是私聊模拟消息")
        }
    }

    fun scenarioLabel(scenario: String): String = when (scenario) {
        "at" -> "@我"; "image" -> "图片"; "face" -> "表情"; "reply" -> "引用回复"
        "voice" -> "语音"; "file" -> "文件"; "long" -> "长文本"
        "custom" -> "自定义"; else -> "文本"
    }

    /**
     * 构造 message 事件 JSON。
     * @param type private|group
     * @param userId 发送者 QQ
     * @param nickname 发送者昵称
     * @param groupId 群号（type=group 时必填，否则忽略）
     * @param message 消息段（JSONArray 数组段 或 String CQ 字符串）
     */
    fun messageEvent(
        type: String,
        userId: Long = DEFAULT_USER_ID,
        nickname: String = "测试好友",
        groupId: Long? = null,
        message: Any,
    ): String {
        val event = JSONObject()
        event.put("post_type", "message")
        event.put("message_type", type)
        event.put("time", System.currentTimeMillis() / 1000)
        event.put("self_id", SELF_ID)
        event.put("user_id", userId)
        if (type == "group") event.put("group_id", groupId ?: DEFAULT_GROUP_ID)
        event.put("message_id", "dev_${System.currentTimeMillis()}_${(0..999).random()}")
        val sender = JSONObject().put("nickname", nickname).put("user_id", userId)
        event.put("sender", sender)
        event.put("message", message)
        return event.toString()
    }

    /**
     * 构造撤回事件 JSON（notice.friend_recall / group_recall）。
     * BandQQ 端按 message_id 原位替换为撤回文案。
     */
    fun recallEvent(type: String, messageId: String, userId: Long = DEFAULT_USER_ID, groupId: Long? = null): String {
        val event = JSONObject()
        event.put("post_type", "notice")
        event.put("notice_type", if (type == "group") "group_recall" else "friend_recall")
        event.put("time", System.currentTimeMillis() / 1000)
        event.put("self_id", SELF_ID)
        event.put("user_id", userId)
        if (type == "group") event.put("group_id", groupId ?: DEFAULT_GROUP_ID)
        event.put("message_id", messageId)
        return event.toString()
    }

    /** 「撤回」完整演示：先发文本（返回 message_id），再撤回该条 */
    fun recallFlow(type: String, nickname: String): Pair<String, String> {
        val mid = "dev_recall_${System.currentTimeMillis()}"
        val first = messageEvent(
            type = type,
            userId = DEFAULT_USER_ID,
            nickname = nickname,
            message = textArray("过一会儿我会撤回这条消息"),
        )
        return first to recallEvent(type, mid)
    }
}
