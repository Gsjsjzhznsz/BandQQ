package com.bandqq.sync.sync

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress

/**
 * 手环 WebSocket 服务端：Vela 快应用经蓝牙隧道以 websocketfactory 接入。
 * 帧均为单条 JSON 文本。
 * 入帧：{type: hello|send|get_history|refresh, ...}
 * 出帧：{type: message|list|history|state|login_info|send_failed, ...}
 */
class BandServer(
    private val broker: MessageBroker,
    port: Int = PORT
) : WebSocketServer(InetSocketAddress(port)) {

    private val senders = HashMap<WebSocket, MessageBroker.MessageSender>()

    override fun onStart() {
        connectionLostTimeout = 30
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        val sender = object : MessageBroker.MessageSender {
            override fun sendToBand(frame: String): Boolean {
                return try { conn.send(frame); true } catch (_: Exception) { false }
            }
            override fun requestApiParams(action: String, params: JSONObject, callback: (JSONObject?) -> Unit): Boolean {
                broker.requestApiParams(action, params, callback)
                return true
            }
        }
        synchronized(senders) { senders[conn] = sender }
        // 连接即登记，等 hello 后 attach（回放）；保守起见直接 attach 也安全（去重在手环端）
        broker.attachBand(conn.remoteSocketAddress.toString(), sender)
    }

    override fun onMessage(conn: WebSocket, text: String) {
        val sender = synchronized(senders) { senders[conn] } ?: return
        try {
            val json = JSONObject(text)
            when (json.optString("type")) {
                "hello" -> {
                    conn.send(JSONObject().put("type", "state").put("connected", true).toString())
                }
                "send" -> {
                    val targetId = json.optString("target_id")
                    val chatType = json.optString("chat_type", "group")
                    val content = json.optString("content")
                    if (targetId.isNotEmpty() && content.isNotEmpty()) {
                        broker.sendFromBand(sender, targetId, chatType, content)
                    }
                }
                "get_history" -> {
                    val targetId = json.optString("target_id")
                    val chatType = json.optString("chat_type", "group")
                    val limit = json.optInt("limit", 20)
                    val older = json.optBoolean("older", false)
                    val beforeTime = json.optLong("before_time", 0L).takeIf { it > 0 }
                    if (targetId.isNotEmpty()) {
                        broker.fetchHistory(sender, targetId, chatType, limit, older, beforeTime)
                    }
                }
                "refresh" -> broker.sendList(sender)
            }
        } catch (_: Exception) {}
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        val id = conn.remoteSocketAddress?.toString() ?: return
        broker.detachBand(id)
        synchronized(senders) { senders.remove(conn) }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {}

    companion object {
        const val PORT = 9810
    }
}
