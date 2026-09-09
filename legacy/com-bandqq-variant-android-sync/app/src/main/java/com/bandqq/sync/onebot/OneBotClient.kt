package com.bandqq.sync.onebot

import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong

/**
 * OneBot v11 客户端：WebSocket(:3001) 优先，HTTP(:3000) 兜底。
 * 请求带 echo 回调分发；未注册 echo 的响应用于 WS 状态判定。
 */
class OneBotClient(
    private var wsUrl: String,
    private var httpUrl: String,
    private var accessToken: String = ""
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val echoSeq = AtomicLong(1)
    private val pending = ConcurrentHashMap<String, (JSONObject) -> Unit>()
    private var ws: WebSocket? = null
    @Volatile var connected = false
        private set
    private var onState: ((Boolean) -> Unit)? = null
    private var onEvent: ((JSONObject) -> Unit)? = null
    private val apiExecutor = Executors.newSingleThreadExecutor()

    fun setOnState(cb: (Boolean) -> Unit) { onState = cb }
    fun setOnEvent(cb: (JSONObject) -> Unit) { onEvent = cb }

    fun start() {
        connectWs()
    }

    private fun connectWs() {
        val url = wsUrl.toHttpUrlOrNull() ?: return
        val reqBuilder = Request.Builder().url(url)
        if (accessToken.isNotEmpty()) {
            reqBuilder.header("Authorization", "Bearer $accessToken")
            reqBuilder.header("access_token", accessToken)
        }
        ws = client.newWebSocket(reqBuilder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true
                onState?.invoke(true)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (connected) {
                    connected = false
                    onState?.invoke(false)
                }
                scheduleReconnect()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                onState?.invoke(false)
                scheduleReconnect()
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val echo = json.optString("echo")
                    if (echo.isNotEmpty()) {
                        pending.remove(echo)?.invoke(json)
                    } else {
                        onEvent?.invoke(json)
                    }
                } catch (_: Exception) {}
            }
        })
    }

    private fun scheduleReconnect() {
        apiExecutor.submit {
            try { Thread.sleep(5000) } catch (_: InterruptedException) {}
            if (!connected) connectWs()
        }
    }

    /** 通用 API 请求：WS 带参数；若 WS 未连上走 HTTP */
    fun requestApiParams(action: String, params: JSONObject, callback: (JSONObject?) -> Unit) {
        val echo = "e${echoSeq.getAndIncrement()}"
        if (connected && ws != null) {
            val payload = JSONObject()
                .put("action", action)
                .put("params", params)
                .put("echo", echo)
            pending[echo] = { resp -> callback(resp) }
            val ok = try { ws?.send(payload.toString()) ?: false } catch (_: Exception) { false }
            if (ok == true) {
                // 超时兜底：8s 未响应转 HTTP
                apiExecutor.submit {
                    try { Thread.sleep(8000) } catch (_: InterruptedException) {}
                    if (pending.remove(echo) != null) httpFallback(action, params, callback)
                }
                return
            }
        }
        httpFallback(action, params, callback)
    }

    fun requestApi(action: String, callback: (JSONObject?) -> Unit) =
        requestApiParams(action, JSONObject(), callback)

    private fun httpFallback(action: String, params: JSONObject, callback: (JSONObject?) -> Unit) {
        apiExecutor.submit {
            try {
                // 优先 POST /{action}，404 回退 /api/{action}
                var url = httpUrl.trimEnd('/') + "/" + action
                var resp = postJson(url, params)
                if (resp == null || resp.optInt("status", -1) == 404) {
                    resp = postJson(httpUrl.trimEnd('/') + "/api/" + action, params)
                }
                callback(resp)
            } catch (_: Exception) {
                callback(null)
            }
        }
    }

    private fun postJson(url: String, params: JSONObject): JSONObject? {
        return try {
            val body = params.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val rb = Request.Builder().url(url).post(body)
            if (accessToken.isNotEmpty()) rb.header("Authorization", "Bearer $accessToken")
            client.newCall(rb.build()).execute().use { r ->
                val text = r.body?.string() ?: return null
                try { JSONObject(text) } catch (_: Exception) { null }
            }
        } catch (_: Exception) { null }
    }

    fun updateConfig(ws: String, http: String, token: String) {
        this.wsUrl = ws; this.httpUrl = http; this.accessToken = token
        this.ws?.close(1000, "reconfig")
        this.ws = null
        connectWs()
    }

    fun destroy() {
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
        client.dispatcher.executorService.shutdown()
        apiExecutor.shutdown()
    }
}
