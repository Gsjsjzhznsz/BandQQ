package com.example.bandqq.onebot

import com.example.bandqq.config.EndpointConfig
import com.example.bandqq.sync.LogBus
import com.example.bandqq.sync.LogLevel
import com.example.bandqq.sync.MessageSender
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

interface OneBotListener {
    fun onEvent(message: OneBotMessage)
    fun onState(connected: Boolean)
}

/**
 * OneBot v11 客户端。
 *
 * 发送链路设计（参考 Stapxs-QQ-Lite-X 的 WS 单链路方案）：
 * - 优先走 WS echo RPC：与收消息同一条 WS 连接，发送 {"action","params","echo"}，
 *   按 echo 匹配响应并校验 retcode/status。实测 WS 可用而 HTTP 不可用的部署下仍可发送。
 * - WS 不可用（未连接/发送失败）时回退 HTTP POST {httpUrl}/{action}，失败再试 /api/{action}。
 * - 严格校验 OneBot 业务层结果：HTTP 200 不代表成功，必须 status=ok/async 或 retcode=0/1，
 *   否则回调失败并携带 retcode，供上层把失败原因回推手环（不再静默丢消息）。
 */
class OneBotClient(private val parser: OneBotParser) : MessageSender {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var reconnectJob: Job? = null
    private var ws: WebSocket? = null
    private var config: EndpointConfig = EndpointConfig("ws://127.0.0.1:3001", "", "http://127.0.0.1:3000", "")
    private var listener: OneBotListener? = null
    @Volatile private var connected = false

    /** echo -> 待响应回调。收到带 echo 的 WS 响应时移除并触发。 */
    private val pendingAcks = ConcurrentHashMap<String, (Boolean, String?) -> Unit>()

    /** 本机 echo 计数器（与协议端可能存在的其他客户端隔离）。 */
    private val echoCounter = AtomicLong()

    companion object {
        /** WS echo RPC 响应超时：超时后按失败处理（迟到的响应会被丢弃，不会二次回调）。 */
        private const val WS_ACK_TIMEOUT_MS = 8000L

        /**
         * 校验 OneBot v11 业务层结果。
         * retcode：0=成功，1=已提交异步处理；status：ok=成功，async=异步受理。
         */
        fun checkOneBotResult(status: String?, retcode: Int): Boolean =
            status == "ok" || retcode == 0 || status == "async" || retcode == 1

        /** 解析 HTTP 响应体为 (ok, error)：HTTP 200 但业务失败时返回 false + 原因。 */
        fun parseResponseBody(body: String): Pair<Boolean, String?> {
            return try {
                val obj = JsonParser.parseString(body).asJsonObject
                val status = obj.get("status")?.takeIf { it.isJsonPrimitive }?.asString
                val retcode = obj.get("retcode")?.takeIf { it.isJsonPrimitive }
                    ?.let { runCatching { it.asInt }.getOrDefault(Int.MIN_VALUE) } ?: Int.MIN_VALUE
                if (checkOneBotResult(status, retcode)) {
                    true to null
                } else {
                    val wording = obj.get("wording")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
                    false to "OneBot 失败 retcode=$retcode status=$status" + wording.take(60)
                }
            } catch (e: Exception) {
                false to "响应非 OneBot JSON: ${body.take(80)}"
            }
        }
    }

    fun start(config: EndpointConfig, listener: OneBotListener) {
        this.config = config
        this.listener = listener
        reconnect()
    }

    fun startWithListener(listener: OneBotListener) {
        this.listener = listener
    }

    /** 仅更新 HTTP/WS 端点配置（含 token），不建立连接。用于界面侧手动拉取联系人。 */
    fun configure(endpoint: EndpointConfig) {
        this.config = endpoint
    }

    fun stop() {
        reconnectJob?.cancel()
        scope.cancel()
        ws?.close(1000, "stopped")
        ws = null
        connected = false
        pendingAcks.clear()
    }

    fun isConnected(): Boolean = connected

    private fun reconnect() {
        reconnectJob = scope.launch {
            while (isActive) {
                if (!connected) {
                    try {
                        connectOnce()
                    } catch (e: Exception) {
                        LogBus.log("OneBotClient", LogLevel.WARN, "connect failed: $e")
                    }
                    delay(5000)
                } else {
                    delay(1000)
                }
            }
        }
    }

    private fun connectOnce() {
        val builder = Request.Builder().url(config.wsUrl)
        if (config.wsToken.isNotBlank()) {
            builder.header("Authorization", "Bearer ${config.wsToken}")
        }
        val req = builder.build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true
                LogBus.log("OneBotClient", LogLevel.DEBUG, "WS onOpen, connected=$connected")
                listener?.onState(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                LogBus.log("OneBotClient", LogLevel.DEBUG, "WS recv: ${text.take(300)}")
                // echo 分流：带 echo 的帧是动作响应，按 echo 匹配挂起回调；
                // 不带 echo 的才是事件推送（OneBot v11 WS 通信规范，同 Stapxs-QQ-Lite-X）
                if (handleAckResponse(text)) return
                val msg = parser.parseMessageEvent(text)
                if (msg == null) {
                    LogBus.log("OneBotClient", LogLevel.WARN, "WS msg parse -> null (may be meta/heartbeat)")
                } else {
                    listener?.onEvent(msg)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                LogBus.log("OneBotClient", LogLevel.WARN, "WS recv binary bytes (ignored)")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                failAllPending("WS 断开")
                listener?.onState(false)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                failAllPending("WS 关闭")
                listener?.onState(false)
            }
        })
    }

    /** 处理 WS echo 响应；命中挂起回调返回 true（该帧不是事件推送）。 */
    private fun handleAckResponse(text: String): Boolean {
        val obj = try {
            JsonParser.parseString(text)
        } catch (e: Exception) {
            return false
        }
        if (!obj.isJsonObject) return false
        val jo = obj.asJsonObject
        val echo = jo.get("echo")?.takeIf { it.isJsonPrimitive }?.asString ?: return false
        val cb = pendingAcks.remove(echo) ?: return false // 超时后迟到的响应，丢弃
        val status = jo.get("status")?.takeIf { it.isJsonPrimitive }?.asString
        val retcode = jo.get("retcode")?.takeIf { it.isJsonPrimitive }
            ?.let { runCatching { it.asInt }.getOrDefault(Int.MIN_VALUE) } ?: Int.MIN_VALUE
        if (checkOneBotResult(status, retcode)) {
            cb(true, jo.toString())
        } else {
            cb(false, "OneBot 失败 retcode=$retcode status=$status")
        }
        return true
    }

    private fun failAllPending(reason: String) {
        val entries = pendingAcks.keys.toList()
        for (k in entries) {
            pendingAcks.remove(k)?.invoke(false, reason)
        }
    }

    /**
     * 经 WS 发送 OneBot 动作并等待 echo 响应。
     * @return true 表示请求已从 WS 发出（结果异步回调）；false 表示 WS 不可用，调用方应回退 HTTP。
     */
    private fun sendViaWs(action: String, params: JsonObject, callback: (Boolean, String?) -> Unit): Boolean {
        val socket = ws ?: return false
        if (!connected) return false
        val echo = "bandqq-${echoCounter.incrementAndGet()}"
        val payload = JsonObject()
        payload.addProperty("action", action)
        payload.add("params", params)
        payload.addProperty("echo", echo)
        val sent = try {
            socket.send(payload.toString())
        } catch (e: Exception) {
            LogBus.log("OneBotClient", LogLevel.WARN, "WS send throw: $e")
            false
        }
        if (!sent) return false
        pendingAcks[echo] = callback
        // 超时兜底：echo 长时间无响应按失败处理
        scope.launch {
            delay(WS_ACK_TIMEOUT_MS)
            pendingAcks.remove(echo)?.invoke(false, "WS echo 响应超时(${WS_ACK_TIMEOUT_MS}ms)")
        }
        return true
    }

    override fun sendMessage(
        messageType: String,
        targetId: String,
        content: String,
        httpUrlOverride: String?,
        callback: (Boolean, String?, String?) -> Unit
    ) {
        val action = parser.actionName(messageType)
        // WS 优先：与收消息同链路，HTTP 端点失效时发送仍可用；失败回退 HTTP
        if (sendViaWs(action, parser.buildParams(messageType, targetId, content)) { ok, resp ->
                // ok 时 resp 为响应体（供回填 message_id）；失败时 resp 为错误信息
                if (ok) callback(true, null, resp) else callback(false, resp, null)
            }
        ) {
            return
        }
        sendMessageHttp(messageType, targetId, content, httpUrlOverride, callback)
    }

    private fun sendMessageHttp(
        messageType: String,
        targetId: String,
        content: String,
        httpUrlOverride: String?,
        callback: (Boolean, String?, String?) -> Unit
    ) {
        val baseUrl = httpUrlOverride ?: config.httpUrl
        val body = parser.buildSendRequest(messageType, targetId, content)
        // SnowLuma 从路径解析 action：发到与 body action 一致的路径（如 /send_group_msg）
        val action = parser.actionName(messageType)
        var lastError: String? = null
        fun doSend(url: String, onFail: () -> Unit) {
            val request = Request.Builder()
                .url(url)
                .post(body.toRequestBody("application/json".toMediaType()))
                .apply { if (config.httpToken.isNotBlank()) header("Authorization", "Bearer ${config.httpToken}") }
                .build()
            client.newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    try { LogBus.log("OneBotClient", LogLevel.ERROR, "send failed: $url: $e") } catch (t: Throwable) {}
                    lastError = "HTTP 请求失败: ${e.message ?: "IOException"}"
                    onFail()
                }

                override fun onResponse(call: okhttp3.Call, response: Response) {
                    response.use {
                        val resp = it.body?.string() ?: ""
                        if (it.isSuccessful) {
                            // HTTP 200 不代表成功：必须校验 OneBot 业务层 retcode/status
                            val (ok, err) = parseResponseBody(resp)
                            if (ok) {
                                try { LogBus.log("OneBotClient", LogLevel.DEBUG, "send ok(${it.code}) $url -> $resp") } catch (t: Throwable) {}
                                callback(true, null, resp)
                            } else {
                                try { LogBus.log("OneBotClient", LogLevel.ERROR, "send biz fail $url -> $resp") } catch (t: Throwable) {}
                                lastError = err
                                onFail()
                            }
                        } else {
                            try { LogBus.log("OneBotClient", LogLevel.ERROR, "send http ${it.code} $url -> $resp") } catch (t: Throwable) {}
                            lastError = "HTTP ${it.code}: ${resp.take(80)}"
                            onFail()
                        }
                    }
                }
            })
        }
        val root = baseUrl.trimEnd('/')
        doSend("$root/$action") {
            doSend("$root/api/$action") {
                callback(false, lastError ?: "发送失败", null)
            }
        }
    }

    override fun requestApiParams(action: String, params: JsonObject, callback: (String?) -> Unit) {
        requestApiWithParams(action, params, callback)
    }

    /**
     * 调用 OneBot 通用接口（如 get_friend_list/get_group_list）。
     * WS 已连接时优先走 echo RPC（单链路，HTTP 端点失效时联系人仍可拉取），
     * 否则/失败时回退 HTTP：优先 {httpUrl}/{action}，再回退 {httpUrl}/api/{action}
     * （兼容 NapCat 等实现）。成功回传原始响应体，失败回传 null。
     */
    fun requestApi(action: String, baseUrl: String = config.httpUrl, callback: (String?) -> Unit) {
        val wsSent = sendViaWs(action, JsonObject()) { ok, resp ->
            if (ok) {
                callback(resp)
            } else {
                try { LogBus.log("OneBotClient", LogLevel.DEBUG, "requestApi WS 失败回退 HTTP: $action ($resp)") } catch (t: Throwable) {}
                requestApiHttp(action, baseUrl, callback)
            }
        }
        if (!wsSent) requestApiHttp(action, baseUrl, callback)
    }

    /**
     * 调用带参数的 OneBot 通用接口（如 get_group_msg_history 分页拉取）。
     * 与 requestApi 同样的 WS 优先 + HTTP 双路径回退策略；
     * 成功回传原始响应体，失败回传 null。
     */
    fun requestApiWithParams(action: String, params: JsonObject, callback: (String?) -> Unit) {
        val wsSent = sendViaWs(action, params) { ok, resp ->
            if (ok) {
                callback(resp)
            } else {
                requestApiParamsHttp(action, params, callback)
            }
        }
        if (!wsSent) requestApiParamsHttp(action, params, callback)
    }

    private fun requestApiParamsHttp(action: String, params: JsonObject, callback: (String?) -> Unit) {
        fun doRequest(url: String, onFail: () -> Unit) {
            val request = Request.Builder()
                .url(url)
                .post(params.toString().toRequestBody("application/json".toMediaType()))
                .apply { if (config.httpToken.isNotBlank()) header("Authorization", "Bearer ${config.httpToken}") }
                .build()
            client.newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    onFail()
                }

                override fun onResponse(call: okhttp3.Call, response: Response) {
                    response.use {
                        val body = it.body?.string() ?: ""
                        if (it.isSuccessful) {
                            val (ok, _) = parseResponseBody(body)
                            if (ok) callback(body) else onFail()
                        } else {
                            onFail()
                        }
                    }
                }
            })
        }
        val root = config.httpUrl.trimEnd('/')
        doRequest("$root/$action") {
            doRequest("$root/api/$action") {
                callback(null)
            }
        }
    }

    private fun requestApiHttp(action: String, baseUrl: String, callback: (String?) -> Unit) {
        fun doRequest(url: String, onFail: () -> Unit) {
            val request = Request.Builder()
                .url(url)
                .post("{}".toRequestBody("application/json".toMediaType()))
                .apply { if (config.httpToken.isNotBlank()) header("Authorization", "Bearer ${config.httpToken}") }
                .build()
            client.newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    onFail()
                }

                override fun onResponse(call: okhttp3.Call, response: Response) {
                    response.use {
                        val body = it.body?.string() ?: ""
                        if (it.isSuccessful) {
                            val (ok, _) = parseResponseBody(body)
                            if (ok) callback(body) else onFail()
                        } else {
                            onFail()
                        }
                    }
                }
            })
        }
        val root = baseUrl.trimEnd('/')
        doRequest("$root/$action") {
            doRequest("$root/api/$action") {
                callback(null)
            }
        }
    }
}
