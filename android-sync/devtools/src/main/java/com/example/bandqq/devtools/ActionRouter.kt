package com.example.bandqq.devtools

import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * OneBot v11 动作路由（v1.2.0 DevTools）：HTTP API 与正向 WS 共用同一套应答逻辑。
 *
 * v1.2.0 修复的关键缺口：此前 WsServer 收到客户端动作帧一律不回应（只打日志），
 * 而标准 OneBot 客户端（Stapxs / BandQQ 等）连接后会经 WS 发 get_version_info 等
 * 动作并等待 echo 应答——无应答时客户端判定连接不可用。现在：
 * - WS 动作帧 → 解析 action/params/echo → 本路由应答 → 原样回带 echo
 * - HTTP POST /{action} → 同一路由
 *
 * 支持动作：get_version_info（真实版本数据）、get_login_info（我的身份）、
 * get_friend_list / get_group_list（模拟联系人）、get_status、
 * send_private_msg / send_group_msg（日志 + 可选自动回推闭环）、其余通用成功。
 */
class ActionRouter(
    private val autoEcho: () -> Boolean,
    private val selfInfo: () -> Pair<Long, String>,
    private val pushEvent: (String) -> Unit,   // 经 WS 下发事件（自动回推闭环）
    private val onLog: (String) -> Unit,
) {

    private val messageIdSeq = AtomicLong(1000)

    /** 直取「我的QQ号」（心跳/lifecycle 元事件用，避免走 handle 触发动作日志） */
    fun selfId(): Long = try { selfInfo().first } catch (_: Throwable) { MsgBuilder.DEFAULT_SELF_ID }

    /** 统一入口：返回完整应答 JSON（echo 原样回带，可为 null/字符串/数字） */
    fun handle(action: String, params: JSONObject, echo: Any?): String {
        val data: Any = when (action) {
            "get_version_info" -> {
                onLog("动作 get_version_info（v1.2.0 应答）")
                JSONObject()
                    .put("app_name", "BandQQ DevTools")
                    .put("version", "1.2.0")
                    .put("protocol_version", "v11")
            }
            "get_login_info" -> {
                val (qq, nick) = selfInfo()
                onLog("动作 get_login_info → $nick（$qq）")
                JSONObject().put("user_id", qq).put("nickname", nick)
            }
            "get_status" -> {
                onLog("动作 get_status → online")
                JSONObject().put("online", true).put("good", true)
            }
            "get_friend_list" -> friendList()
            "get_group_list" -> groupList()
            "send_private_msg", "send_group_msg" -> handleSend(action, params)
            else -> {
                onLog("动作 $action（通用成功）")
                JSONObject()
            }
        }
        val resp = JSONObject()
            .put("status", "ok")
            .put("retcode", 0)
            .put("data", data)
        when (echo) {
            null -> resp.put("echo", JSONObject.NULL)
            is Int, is Long, is Boolean, is Double -> resp.put("echo", echo)
            else -> resp.put("echo", echo.toString())
        }
        return resp.toString()
    }

    private fun friendList(): Any {
        val data = org.json.JSONArray()
            .put(JSONObject().put("user_id", 10086L).put("nickname", "测试好友"))
            .put(JSONObject().put("user_id", 10010L).put("nickname", "BandQQ 机器人"))
            .put(JSONObject().put("user_id", 10001L).put("nickname", "阿瑶"))
        onLog("返回模拟好友列表（${data.length()} 人）")
        return data
    }

    private fun groupList(): Any {
        val data = org.json.JSONArray()
            .put(JSONObject().put("group_id", 20001L).put("group_name", "BandQQ 体验群"))
            .put(JSONObject().put("group_id", 20002L).put("group_name", "手环玩家俱乐部"))
        onLog("返回模拟群列表（${data.length()} 个）")
        return data
    }

    /** 手环回复送达：日志 + 可选自动回推一条对方消息（闭环演示） */
    private fun handleSend(action: String, params: JSONObject): Any {
        val message = params.optString("message", "")
        val isGroup = action == "send_group_msg"
        val targetId = params.optLong(if (isGroup) "group_id" else "user_id", 0L).toString()
        val status = JSONObject().put("message_id", messageIdSeq.incrementAndGet())
        onLog("收到 ${if (isGroup) "群聊" else "私聊"}回复 → ${if (isGroup) "群 $targetId" else "好友 $targetId"}：${message.take(60)}")
        if (autoEcho()) {
            // 对方"收到后回复"，user_id 与消息来源一致，落入同一会话
            val userId = if (isGroup) 10086L else targetId.toLongOrNull() ?: 10086L
            val nickname = if (isGroup) "群友小王" else "测试好友"
            val groupId = if (isGroup) targetId.toLongOrNull() ?: 20001L else null
            val event = MsgBuilder.messageEvent(
                type = if (isGroup) "group" else "private",
                userId = userId,
                nickname = nickname,
                groupId = groupId,
                message = MsgBuilder.textArray("收到！这是一条自动回推的模拟回复：你刚才发的「${message.take(12)}」我看到了"),
            )
            pushEvent(event)
            onLog("已自动回推一条对方消息（可在设置关闭）")
        }
        return status
    }

    companion object {
        /** OneBot 11 元事件：连接生命周期（客户端连上后立即下发） */
        fun lifecycleEvent(selfId: Long): String = JSONObject()
            .put("time", System.currentTimeMillis() / 1000)
            .put("self_id", selfId)
            .put("post_type", "meta_event")
            .put("meta_event_type", "lifecycle")
            .put("sub_type", "connect")
            .toString()

        /** OneBot 11 元事件：心跳（默认 30s，客户端可据此判活） */
        fun heartbeatEvent(selfId: Long, intervalMs: Long): String = JSONObject()
            .put("time", System.currentTimeMillis() / 1000)
            .put("self_id", selfId)
            .put("post_type", "meta_event")
            .put("meta_event_type", "heartbeat")
            .put("interval", intervalMs)
            .put("status", JSONObject().put("online", true).put("good", true))
            .toString()
    }
}
