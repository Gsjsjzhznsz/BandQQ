package com.example.bandqq.onebot

import com.example.bandqq.config.EndpointConfig
import com.example.bandqq.sync.LogBus
import com.example.bandqq.sync.LogLevel
import com.example.bandqq.sync.MessageSender
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
import java.util.concurrent.TimeUnit

interface OneBotListener {
    fun onEvent(message: OneBotMessage)
    fun onState(connected: Boolean)
    /** 消息撤回通知（friend_recall/group_recall，借鉴 Stapxs）；默认空实现保持旧监听器兼容 */
    fun onRecall(recall: OneBotRecall) {}
}

class OneBotClient(private val parser: OneBotParser) : MessageSender {

    companion object {
        /**
         * 全局共享 OkHttp 客户端：连接池/线程池复用。
         * 联系人页「刷新」每次点击都会临时 new 一个 OneBotClient，
         * 若各自 new OkHttpClient 会不断新建连接池与线程池（官方推荐全局共享）。
         * WS pingInterval 对纯 HTTP 请求无副作用。
         */
        private val sharedOk = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    private val client = sharedOk

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var reconnectJob: Job? = null
    private var ws: WebSocket? = null
    private var config: EndpointConfig = EndpointConfig("ws://127.0.0.1:3001", "", "http://127.0.0.1:3000", "")
    private var listener: OneBotListener? = null
    @Volatile private var connected = false

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
                // 撤回通知优先（post_type=notice 事件，普通消息 parse 为 null 不再报 warn）
                val recall = parser.parseRecallEvent(text)
                if (recall != null) {
                    listener?.onRecall(recall)
                    return
                }
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
                listener?.onState(false)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                listener?.onState(false)
            }
        })
    }

    override fun sendMessage(
        messageType: String,
        targetId: String,
        content: String,
        httpUrlOverride: String?,
        callback: (Boolean) -> Unit
    ) {
        val baseUrl = httpUrlOverride ?: config.httpUrl
        val body = parser.buildSendRequest(messageType, targetId, content)
        // SnowLuma 从路径解析 action：发到与 body action 一致的路径（如 /send_group_msg）
        val action = parser.actionName(messageType)
        fun doSend(url: String, onFail: () -> Unit) {
            val request = Request.Builder()
                .url(url)
                .post(body.toRequestBody("application/json".toMediaType()))
                .apply { if (config.httpToken.isNotBlank()) header("Authorization", "Bearer ${config.httpToken}") }
                .build()
            client.newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    try { LogBus.log("OneBotClient", LogLevel.ERROR, "send failed: $url: $e") } catch (t: Throwable) {}
                    onFail()
                }

                override fun onResponse(call: okhttp3.Call, response: Response) {
                    response.use {
                        val resp = it.body?.string() ?: ""
                        if (it.isSuccessful) {
                            // OneBot 返回 HTTP 200，但业务可能失败（retcode != 0），记录下来便于定位
                            try { LogBus.log("OneBotClient", LogLevel.DEBUG, "send ok(${it.code}) $url -> $resp") } catch (t: Throwable) {}
                            callback(true)
                        } else {
                            try { LogBus.log("OneBotClient", LogLevel.ERROR, "send http ${it.code} $url -> $resp") } catch (t: Throwable) {}
                            onFail()
                        }
                    }
                }
            })
        }
        val root = baseUrl.trimEnd('/')
        doSend("$root/$action") {
            doSend("$root/api/$action") {
                callback(false)
            }
        }
    }

    /**
     * 调用 OneBot HTTP 通用接口（如 get_friend_list/get_group_list）。
     * SnowLuma 默认 path='/' 且 action 从路径解析，优先 {httpUrl}/{action}，
     * 失败时回退 {httpUrl}/api/{action}（兼容 NapCat 等实现）。
     * 成功回传原始响应体，失败回传 null。
     */
    fun requestApi(action: String, baseUrl: String = config.httpUrl, callback: (String?) -> Unit) {
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
                        if (it.isSuccessful) callback(body) else onFail()
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
