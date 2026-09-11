package com.example.bandqq.devtools

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * 极简 HTTP API 服务器（零第三方依赖，v2.8.0 DevTools）：
 * 模拟 OneBot HTTP 端口（默认 3000），接收 BandQQ 同步器的动作请求：
 * - POST /send_private_msg  /send_group_msg  → retcode 0；可选自动回推一条
 *   对方消息（经 WS 下发 message 事件），完整演示「手环回复 → 对方收到 →
 *   对方再回复」的闭环。
 * - POST /get_friend_list / get_group_list → 返回模拟好友/群列表，
 *   BandQQ 连接后自动拉取联系人即可看到示例联系人。
 * - 其余动作 → retcode 0 通用成功（保持客户端流程不中断）。
 */
class HttpApiServer(
    private val port: Int,
    private val autoEcho: () -> Boolean,          // 收到消息动作后是否自动回推对方回复
    private val pushEvent: (String) -> Unit,      // 经 WS 下发事件（MsgBuilder 构造）
    private val onLog: (String) -> Unit,
) {

    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "http-api").apply { isDaemon = true }
    }
    private val messageIdSeq = AtomicLong(1000)

    @Volatile
    var running = false
        private set

    fun start(): Boolean {
        if (running) return true
        return try {
            val ss = ServerSocket(port)
            ss.reuseAddress = true
            serverSocket = ss
            running = true
            pool.execute {
                onLog("HTTP API 监听 0.0.0.0:$port")
                while (running) {
                    try {
                        val socket = ss.accept()
                        pool.execute { handle(socket) }
                    } catch (t: Throwable) {
                        if (running) onLog("HTTP accept error: ${t.message}")
                    }
                }
            }
            true
        } catch (t: Throwable) {
            onLog("HTTP 启动失败(端口被占用?)：${t.message}")
            running = false
            false
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 8000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.lowercase().startsWith("content-length:")) {
                    contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                }
            }
            val body = if (contentLength > 0) {
                val buf = CharArray(contentLength)
                var off = 0
                while (off < contentLength) {
                    val n = reader.read(buf, off, contentLength - off)
                    if (n < 0) break
                    off += n
                }
                String(buf, 0, off)
            } else ""

            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0) ?: "GET"
            val path = (parts.getOrNull(1) ?: "/").substringBefore('?')
            val response = route(method, path, body)

            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
            writer.write("HTTP/1.1 200 OK\r\n")
            writer.write("Content-Type: application/json; charset=utf-8\r\n")
            writer.write("Content-Length: ${response.toByteArray(Charsets.UTF_8).size}\r\n")
            writer.write("Connection: close\r\n\r\n")
            writer.write(response)
            writer.flush()
        } catch (t: Throwable) {
            onLog("HTTP 处理异常: ${t.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun route(method: String, path: String, body: String): String {
        if (method != "POST") {
            // BandQQ 测试连接可能发 GET；统一回标准成功结构
            return ok(JSONObject())
        }
        val action = path.trim('/').substringAfterLast('/')
        val params = try { JSONObject(body.ifBlank { "{}" }) } catch (e: Exception) { JSONObject() }
        return when (action) {
            "send_private_msg", "send_group_msg" -> handleSend(action, params)
            "get_friend_list" -> handleFriendList()
            "get_group_list" -> handleGroupList()
            else -> {
                onLog("HTTP 动作 $action（通用成功）")
                ok(JSONObject())
            }
        }
    }

    /** 手环回复送达：日志 + 可选自动回推一条对方消息（闭环演示） */
    private fun handleSend(action: String, params: JSONObject): String {
        val message = params.optString("message", "")
        val isGroup = action == "send_group_msg"
        val targetId = params.optLong(if (isGroup) "group_id" else "user_id", 0L).toString()
        val status = JSONObject()
            .put("message_id", messageIdSeq.incrementAndGet())
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
        return ok(status)
    }

    /** 模拟好友列表：BandQQ 连接后自动拉取，出现在手机端联系人页 */
    private fun handleFriendList(): String {
        val data = JSONArray()
        data.put(JSONObject().put("user_id", 10086L).put("nickname", "测试好友"))
        data.put(JSONObject().put("user_id", 10010L).put("nickname", "BandQQ 机器人"))
        data.put(JSONObject().put("user_id", 10001L).put("nickname", "阿瑶"))
        onLog("返回模拟好友列表（${data.length()} 人）")
        return ok(data)
    }

    private fun handleGroupList(): String {
        val data = JSONArray()
        data.put(JSONObject().put("group_id", 20001L).put("group_name", "BandQQ 体验群"))
        data.put(JSONObject().put("group_id", 20002L).put("group_name", "手环玩家俱乐部"))
        onLog("返回模拟群列表（${data.length()} 个）")
        return ok(data)
    }

    private fun ok(data: Any): String =
        JSONObject()
            .put("status", "ok")
            .put("retcode", 0)
            .put("data", data)
            .put("echo", JSONObject.NULL)
            .toString()
}
