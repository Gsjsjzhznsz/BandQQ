package io.github.gsjsjzhznsz.bandqq.onebot

import io.github.gsjsjzhznsz.bandqq.BuildConfig
import io.github.gsjsjzhznsz.bandqq.config.EndpointConfig
import io.github.gsjsjzhznsz.bandqq.sync.LogBus
import io.github.gsjsjzhznsz.bandqq.sync.LogLevel
import io.github.gsjsjzhznsz.bandqq.sync.MessageSender
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
    /** 拍一拍通知（v2.9.0 notice.notify.poke）；默认空实现保持旧监听器兼容 */
    fun onPoke(poke: OneBotPoke) {}
}

class OneBotClient(private val parser: OneBotParser) : MessageSender {

    companion object {
        /** v2.9.4：WS API 调用的 echo 前缀（与握手 identify 的 bandqq-<ver> 区分开） */
        private const val WS_API_ECHO_PREFIX = "bandqq-api-"

        /** v2.9.4：WS API 调用超时（协议端繁忙时兜底，超时后回调 null 走失败路径） */
        private const val WS_API_TIMEOUT_MS = 8000L

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

    /**
     * v2.28.0 发送风控保护（用户 10-05 封号反馈）：登录预热 + 限速抖动 + 突发冷却。
     * 仅约束 send_* 外发；读取/握手不受影响。详见 SendGuard 类注释。
     */
    private val sendGuard = SendGuard()

    /**
     * v2.9.4：经 WS 下发的 API 请求（echo → 回调）。
     * 真实 NapCat 部署常只开 WS 服务（HTTP API 是独立开关），
     * 联系人拉取（get_friend_list/get_group_list）必须有 WS 通道兜底。
     */
    private val pendingWsApi = java.util.concurrent.ConcurrentHashMap<String, (String?) -> Unit>()
    private val wsApiSeq = java.util.concurrent.atomic.AtomicLong(0)

    // v2.18.0：HTTP 自适应降级——真实 NapCat 部署常只开 WS，而 HTTP 地址留默认
    // （127.0.0.1:3000）。历史上每个动作都先空转两路 HTTP（ConnectException 拒连）
    // 再回退 WS：日志刷屏 + 每动作多几百毫秒延迟。连续拒连 3 次后本会话内
    // 10 分钟直接走 WS（有 HTTP 应答即重置计数；configure 换配置时重置）。
    @Volatile private var httpFailStreak = 0
    @Volatile private var httpDisabledUntil = 0L

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
        // v2.18.0：换配置后重新信任 HTTP 通道
        httpFailStreak = 0
        httpDisabledUntil = 0L
    }

    /**
     * v2.24.0：换配置并立即按新端点重连（「一键填入本机地址」/ 自动对接用）。
     * 旧 configure() 只换引用不重连，运行中的 WS 仍挂在旧地址上——按钮"按了没反应"的主因。
     * 实现：更新配置 → 主动关闭现有 WS → onClosed 置 connected=false → 既有重连循环
     * （reconnectJob，1s 巡检）自动用新 config 重建连接。
     */
    fun reconnectWith(endpoint: EndpointConfig) {
        configure(endpoint)
        runCatching { ws?.close(1000, "reconfigure") }
        // 若重连循环尚未在跑（stop 后未 start 的场景），兜底拉起
        if (reconnectJob?.isActive != true) reconnect()
    }

    /** v2.18.0：HTTP 通道是否可用（空地址或降级窗口内不可用） */
    private fun httpUsable(): Boolean {
        val root = config.httpUrl.trim().trimEnd('/')
        if (root.isBlank()) return false
        return System.currentTimeMillis() >= httpDisabledUntil
    }

    /** v2.18.0：HTTP 拒连计数（仅连接被拒/网络不可达才累计；有应答即重置） */
    private fun noteHttpFailure(e: java.io.IOException?) {
        val refused = e is java.net.ConnectException ||
            (e?.message ?: "").contains("Failed to connect", ignoreCase = true) ||
            (e?.message ?: "").contains("ECONNREFUSED", ignoreCase = true)
        if (!refused) return
        val streak = ++httpFailStreak
        if (streak >= 3) {
            httpDisabledUntil = System.currentTimeMillis() + 10 * 60 * 1000L
            httpFailStreak = 0
            try {
                LogBus.log(
                    "OneBotClient", LogLevel.WARN,
                    "HTTP 拒连 ${streak} 次连续失败，降级 WS-only 10 分钟（换配置自动恢复）"
                )
            } catch (_: Throwable) {}
        }
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
        // v2.8.1 修复：start() 每次被调（用户在设置页反复点启动/服务重建）都会新建循环，
        // 旧循环未取消 → 多个循环并发 connectOnce → 双连接（DevTools 日志“当前 2 个”）且旧 ws 引用被覆盖泄漏
        reconnectJob?.cancel()
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
        // v2.8.1：重连前先主动关闭旧 ws（若有），防止旧连接残留形成双连接
        runCatching { ws?.close(1000, "reconnect") }
        ws = null
        // v2.9.4：旧连接上未应答的 WS API 请求全部失败回调，防调用方永久挂起
        pendingWsApi.forEach { (_, cb) -> runCatching { cb(null) } }
        pendingWsApi.clear()
        val builder = Request.Builder().url(config.wsUrl)
        if (config.wsToken.isNotBlank()) {
            builder.header("Authorization", "Bearer ${config.wsToken}")
        }
        val req = builder.build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // v2.8.1：onState 链上有互联 SDK/通知等 Android API，抛异常会被 OkHttp
                // 视为连接失败立即断开（DevTools 联调时“连接后立即断开”的根因之一），全部兜住
                try {
                    connected = true
                    LogBus.log("OneBotClient", LogLevel.DEBUG, "WS onOpen, connected=$connected")
                    // v2.8.2：握手即经 WS 上报身份（echo=bandqq-<版本>）。
                    // 真实协议端会按 OneBot 标准应答此动作（Stapxs 同款行为）；
                    // DevTools 1.2.0 会把它打进日志（「BandQQ APP x.y.z」），
                    // 联调时一眼确认对端连接的是哪个版本的 APP。
                    val identify = "{\"action\":\"get_version_info\",\"params\":{},\"echo\":\"bandqq-${BuildConfig.VERSION_NAME}\"}"
                    webSocket.send(identify)
                    LogBus.log("OneBotClient", LogLevel.DEBUG, "WS identify sent (echo=bandqq-${BuildConfig.VERSION_NAME})")
                    listener?.onState(true)
                    // v2.28.0：风控预热判定（断开≥2 分钟重连 = NapCat 重启/重登，进入 45s 预热）
                    sendGuard.onConnected()
                    if (sendGuard.activeRestriction() != null) {
                        LogBus.log(
                            "OneBotClient", LogLevel.WARN,
                            "风控保护已激活：${sendGuard.activeRestriction()}"
                        )
                    }
                } catch (t: Throwable) {
                    LogBus.log("OneBotClient", LogLevel.ERROR, "onOpen/onState exception: $t")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // v2.8.1 核心加固：OkHttp 的 loopReader 用 catch-all 把 onMessage 回调里
                // 抛出的任何异常当成 WebSocket failure → 连接立即断开（表现：DevTools 每发
                // 一条消息 APP 就断连重连、消息丢失）。整体兜底后单条消息处理异常只记日志不断连。
                try {
                    LogBus.log("OneBotClient", LogLevel.DEBUG, "WS recv: ${text.take(300)}")
                    // v2.9.4：本端经 WS 下发的 API 应答（echo=bandqq-api-*）优先路由，
                    // 不进入事件解析管线
                    if (routeWsApiResponse(text)) return
                    // 撤回通知优先（post_type=notice 事件，普通消息 parse 为 null 不再报 warn）
                    val recall = parser.parseRecallEvent(text)
                    if (recall != null) {
                        listener?.onRecall(recall)
                        return
                    }
                    // 拍一拍通知（v2.9.0：notice.notify.poke，私聊/群聊）
                    val poke = parser.parsePokeEvent(text)
                    if (poke != null) {
                        listener?.onPoke(poke)
                        return
                    }
                    val msg = parser.parseMessageEvent(text)
                    if (msg == null) {
                        // v2.8.2 降噪：meta_event（lifecycle/heartbeat）也会走到这里，属正常协议帧
                        LogBus.log("OneBotClient", LogLevel.DEBUG, "WS non-message frame (meta/action response)")
                    } else {
                        listener?.onEvent(msg)
                    }
                } catch (t: Throwable) {
                    LogBus.log("OneBotClient", LogLevel.ERROR, "WS onMessage exception (connection kept): $t")
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                LogBus.log("OneBotClient", LogLevel.WARN, "WS recv binary bytes (ignored)")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                sendGuard.onDisconnected()
                try {
                    LogBus.log("OneBotClient", LogLevel.WARN, "WS failure: ${t.javaClass.simpleName}: ${t.message}")
                } catch (_: Throwable) {}
                listener?.onState(false)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                sendGuard.onDisconnected()
                try {
                    listener?.onState(false)
                } catch (t: Throwable) {
                    LogBus.log("OneBotClient", LogLevel.ERROR, "onClosed/onState exception: $t")
                }
            }
        })
    }

    override fun sendMessage(
        messageType: String,
        targetId: String,
        content: String,
        httpUrlOverride: String?,
        callback: (ok: Boolean, messageId: String, err: String) -> Unit
    ) {
        // v2.28.0 发送风控：预热/突发冷却硬拦截（失败原因直达手环 toast）
        sendGuard.gate()?.let { reason ->
            try { LogBus.log("OneBotClient", LogLevel.WARN, "发送被风控保护拦截：$reason") } catch (_: Throwable) {}
            callback(false, "", reason)
            return
        }
        // v2.28.0 限速+抖动：非阻塞延迟后真实下发（下发时计入突发窗口）；
        // 取消安全：stop() 后延迟中的发送以失败回调收尾，调用方不悬挂
        val wait = sendGuard.pacingDelay()
        scope.launch {
            try {
                if (wait > 0) delay(wait)
                sendGuard.commit()
                doSendMessage(messageType, targetId, content, httpUrlOverride, callback)
            } catch (e: kotlinx.coroutines.CancellationException) {
                try { callback(false, "", "发送已取消（引擎停止中）") } catch (_: Throwable) {}
            }
        }
    }

    /** v2.24.0 发送链实现（原 sendMessage 主体；v2.28.0 风控调度拆到上层入口） */
    private fun doSendMessage(
        messageType: String,
        targetId: String,
        content: String,
        httpUrlOverride: String?,
        callback: (ok: Boolean, messageId: String, err: String) -> Unit
    ) {
        val baseUrl = httpUrlOverride ?: config.httpUrl
        // v2.24.0：HTTP 路径式请求体改为 params-only（OneBot v11 标准）。
        // NapCat 4.18.28 httpApiRequest 把整个 body 直接当 params（action 从路径取），
        // 旧信封 {action,params} → body.message=undefined → "$aye" 校验崩 → retcode=200
        // "Cannot read properties of undefined (reading 'type')" → 手环永远发不出消息。
        val paramsJson = parser.buildSendParams(messageType, targetId, content)
        val action = parser.actionName(messageType)
        /** v2.13.0：从 OneBot 应答 data.message_id 提取消息 ID（撤回定位用） */
        fun extractMessageId(resp: String): String = try {
            com.google.gson.JsonParser.parseString(resp).asJsonObject
                .get("data")?.takeIf { it.isJsonObject }
                ?.getAsJsonObject()?.get("message_id")?.let { mid ->
                    when {
                        mid.isJsonPrimitive && mid.asJsonPrimitive.isNumber -> mid.asString
                        mid.isJsonPrimitive -> mid.asString
                        else -> ""
                    }
                } ?: ""
        } catch (_: Throwable) {
            ""
        }
        /**
         * v2.24.0：应答判定。返回 null=传输层失败（继续换通道）；
         * Pair(first=业务成功, second=错误摘要)。HTTP 200 但 retcode!=0 一律业务失败，
         * 修 v2.9.5 起 "HTTP 200 即成功" 的假成功（NapCat 失败也回 HTTP 200）。
         */
        fun judge(resp: String, httpCode: Int): Pair<Boolean, String>? {
            val obj = runCatching { com.google.gson.JsonParser.parseString(resp).asJsonObject }.getOrNull()
                ?: return true to "" // 非标准应答体（纯 data/文本）：维持 HTTP 200 即成功的历史契约
            val retcode = obj.get("retcode")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }
            val status = obj.get("status")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
            val errMsg = (obj.get("message")?.takeIf { it.isJsonPrimitive }?.asString
                ?: obj.get("wording")?.takeIf { it.isJsonPrimitive }?.asString ?: "").replace('\n', ' ')
            if (retcode == null) return (status.equals("ok", true) || status.equals("async", true)) to errMsg
            if (retcode == 0) return true to ""
            // 协议端不认识该动作（如 NapCat /api/xxx 前缀 → "不支持的Api"）→ 视为传输失败换通道
            val unsupported = httpCode == 404 || errMsg.contains("不支持的") ||
                errMsg.contains("unsupported", ignoreCase = true) || errMsg.contains("not found", ignoreCase = true)
            return if (unsupported) null else false to errMsg.take(160).ifBlank { "retcode=$retcode" }
        }
        fun doSend(url: String, onFail: () -> Unit) {
            val request = Request.Builder()
                .url(url)
                .post(paramsJson.toRequestBody("application/json".toMediaType()))
                .apply { if (config.httpToken.isNotBlank()) header("Authorization", "Bearer ${config.httpToken}") }
                .build()
            client.newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    try { LogBus.log("OneBotClient", LogLevel.DEBUG, "send transport fail: $url: ${e.message}") } catch (t: Throwable) {}
                    noteHttpFailure(e)
                    onFail()
                }

                override fun onResponse(call: okhttp3.Call, response: Response) {
                    httpFailStreak = 0
                    response.use {
                        val resp = it.body?.string() ?: ""
                        if (!it.isSuccessful) {
                            try { LogBus.log("OneBotClient", LogLevel.WARN, "send http ${it.code} $url -> ${resp.take(160)}") } catch (t: Throwable) {}
                            onFail()
                            return
                        }
                        when (val v = judge(resp, it.code)) {
                            null -> {
                                try { LogBus.log("OneBotClient", LogLevel.DEBUG, "send unsupported at $url, 换通道") } catch (t: Throwable) {}
                                onFail()
                            }
                            else -> {
                                val (ok, err) = v
                                try {
                                    LogBus.log(
                                        "OneBotClient", if (ok) LogLevel.DEBUG else LogLevel.WARN,
                                        "send $url -> " + if (ok) "ok(retcode=0)" else "fail: $err"
                                    )
                                } catch (t: Throwable) {}
                                if (ok) callback(true, extractMessageId(resp), "") else callback(false, "", err)
                            }
                        }
                    }
                }
            })
        }
        val root = baseUrl.trimEnd('/')
        // v2.18.0：HTTP 降级窗口内/空地址直接走 WS，不再空转拒连
        if (!httpUsable()) {
            sendViaWs(action, paramsJson, callback)
            return
        }
        doSend("$root/$action") {
            doSend("$root/api/$action") {
                // v2.9.5：HTTP 两路均不可达（真实 NapCat 常只开 WS，HTTP API 是独立开关）
                // → 经 WS 通道下发同样的 action，根治「WS-only 部署发不出消息」
                sendViaWs(action, paramsJson, callback)
            }
        }
    }

    /**
     * v2.9.5：sendMessage 的 WS 回退通道（WS 协议用 {action,params} 信封——与 HTTP 的
     * params-only 不同，requestViaWs 内部按 WS 标准自行组信封）。
     * 应答 retcode==0 判定成功。
     */
    private fun sendViaWs(action: String, paramsJson: String, callback: (ok: Boolean, messageId: String, err: String) -> Unit) {
        requestViaWs(action, paramsJson) { resp ->
            if (resp == null) {
                callback(false, "", "OneBot 未连接（WS/HTTP 均不可达）")
                return@requestViaWs
            }
            val messageId = try {
                com.google.gson.JsonParser.parseString(resp).asJsonObject
                    .get("data")?.takeIf { it.isJsonObject }?.asJsonObject
                    ?.get("message_id")?.let { m -> if (m.isJsonPrimitive) m.asString else "" } ?: ""
            } catch (_: Throwable) { "" }
            var ok = false
            var err = ""
            try {
                val obj = com.google.gson.JsonParser.parseString(resp).asJsonObject
                val retcode = obj.get("retcode")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }
                val status = obj.get("status")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
                ok = retcode == 0 || status.equals("ok", true) || status.equals("async", true)
                if (!ok) err = (obj.get("message")?.takeIf { it.isJsonPrimitive }?.asString ?: "retcode=$retcode")
                    .replace('\n', ' ').take(160)
            } catch (_: Throwable) {
                ok = false; err = "应答解析失败"
            }
            try {
                LogBus.log(
                    "OneBotClient", if (ok) LogLevel.DEBUG else LogLevel.WARN,
                    "send via ws $action -> ${if (ok) "ok(retcode=0)" else "fail: ${err.ifBlank { resp.take(200) }}"}"
                )
            } catch (t: Throwable) {}
            callback(ok, messageId, err)
        }
    }

    /**
     * 调用 OneBot 通用接口（如 get_friend_list/get_group_list）。
     * SnowLuma 默认 path='/' 且 action 从路径解析，优先 {httpUrl}/{action}，
     * 失败时回退 {httpUrl}/api/{action}（兼容 NapCat 等实现）。
     * v2.9.4：HTTP 两路均失败后自动回退 WS API（真实 NapCat 常只开 WS 服务）。
     * v2.9.5 修复：baseUrl 参数此前被静默丢弃（v2.9.4 重构引入，requestApiAction 只用 config.httpUrl），
     * 联系人页手动刷新/定时拉取传入的自定义地址全部失效；现已透传。
     * 成功回传原始响应体，失败回传 null。
     */
    fun requestApi(action: String, baseUrl: String = config.httpUrl, callback: (String?) -> Unit) {
        requestApiAction(action, "{}", baseUrl, callback)
    }

    /** v2.9.4：带参数的通用 API（MessageSender 接口实现；HTTP 优先，失败回退 WS） */
    override fun requestApiAction(action: String, paramsJson: String, callback: (String?) -> Unit) {
        requestApiAction(action, paramsJson, config.httpUrl, callback)
    }

    /** v2.9.5：支持显式 baseUrl 的通用 API 调用（HTTP 优先，失败回退 WS）。
     *  v2.24.0：HTTP 路径式请求体改 params-only（NapCat 4.18.28 把整个 body 当 params，
     *  信封 shape 会让 body.group_id/user_id 全部 undefined——get_group_info 等带参动作
     *  在 NapCat 上"看起来 ok 实际 retcode!=0"）。WS 通道仍走信封（requestViaWs 内部组）。 */
    fun requestApiAction(action: String, paramsJson: String, baseUrl: String, callback: (String?) -> Unit) {
        fun doRequest(url: String, onFail: () -> Unit) {
            val request = Request.Builder()
                .url(url)
                .post(paramsJson.toRequestBody("application/json".toMediaType()))
                .apply { if (config.httpToken.isNotBlank()) header("Authorization", "Bearer ${config.httpToken}") }
                .build()
            client.newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    try { LogBus.log("OneBotClient", LogLevel.DEBUG, "api $action http fail: $url") } catch (t: Throwable) {}
                    noteHttpFailure(e)
                    onFail()
                }

                override fun onResponse(call: okhttp3.Call, response: Response) {
                    httpFailStreak = 0
                    response.use {
                        val resp = it.body?.string() ?: ""
                        if (!it.isSuccessful) {
                            try { LogBus.log("OneBotClient", LogLevel.DEBUG, "api $action http ${it.code}") } catch (t: Throwable) {}
                            onFail()
                            return
                        }
                        // 协议端明确"不认识该动作"→ 换通道（/api 前缀 → WS）；其余原样透传给调用方解析
                        val unsupported = runCatching {
                            val o = com.google.gson.JsonParser.parseString(resp).asJsonObject
                            val msg = o.get("message")?.takeIf { m -> m.isJsonPrimitive }?.asString ?: ""
                            o.get("retcode")?.takeIf { m -> m.isJsonPrimitive }?.asInt != 0 &&
                                (msg.contains("不支持的") || msg.contains("unsupported", ignoreCase = true))
                        }.getOrDefault(false)
                        if (unsupported) {
                            try { LogBus.log("OneBotClient", LogLevel.DEBUG, "api $action unsupported at $url, 换通道") } catch (t: Throwable) {}
                            onFail()
                            return
                        }
                        try { LogBus.log("OneBotClient", LogLevel.DEBUG, "api $action http ok") } catch (t: Throwable) {}
                        callback(resp)
                    }
                }
            })
        }
        val root = baseUrl.trimEnd('/')
        // v2.18.0：HTTP 降级窗口内/空地址直接走 WS，不再空转拒连
        if (!httpUsable()) {
            requestViaWs(action, paramsJson, callback)
            return
        }
        doRequest("$root/$action") {
            doRequest("$root/api/$action") {
                // v2.9.4：HTTP 不可达（NapCat 只开 WS 是最常见部署）→ 经 WS 通道调用
                requestViaWs(action, paramsJson, callback)
            }
        }
    }

    /** v2.9.4：经 WS 下发 OneBot action，按 echo 路由应答；超时/未连接回传 null */
    private fun requestViaWs(action: String, paramsJson: String, callback: (String?) -> Unit) {
        val socket = ws
        if (socket == null || !connected) {
            try { LogBus.log("OneBotClient", LogLevel.WARN, "api $action ws fallback: ws not connected") } catch (t: Throwable) {}
            callback(null)
            return
        }
        val echo = "$WS_API_ECHO_PREFIX${wsApiSeq.incrementAndGet()}-$action"
        pendingWsApi[echo] = callback
        val sent = runCatching {
            socket.send("{\"action\":\"$action\",\"params\":$paramsJson,\"echo\":\"$echo\"}")
        }.getOrDefault(false)
        if (!sent) {
            pendingWsApi.remove(echo)
            try { LogBus.log("OneBotClient", LogLevel.WARN, "api $action ws send fail") } catch (t: Throwable) {}
            callback(null)
            return
        }
        try { LogBus.log("OneBotClient", LogLevel.DEBUG, "api $action via ws (echo=$echo)") } catch (t: Throwable) {}
        // 超时兜底：NapCat 繁忙/掉线时防调用方永久等待
        scope.launch {
            delay(WS_API_TIMEOUT_MS)
            val cb = pendingWsApi.remove(echo)
            if (cb != null) {
                try { LogBus.log("OneBotClient", LogLevel.WARN, "api $action ws timeout") } catch (t: Throwable) {}
                runCatching { cb(null) }
            }
        }
    }

    /** v2.9.4：识别并路由本端 WS API 应答（echo=bandqq-api-* 前缀）；命中返回 true */
    private fun routeWsApiResponse(text: String): Boolean {
        if (!text.contains("\"echo\":\"$WS_API_ECHO_PREFIX")) return false
        return try {
            val obj = com.google.gson.JsonParser.parseString(text).asJsonObject
            val echo = obj.get("echo")?.takeIf { it.isJsonPrimitive }?.asString ?: return false
            if (!echo.startsWith(WS_API_ECHO_PREFIX)) return false
            val cb = pendingWsApi.remove(echo)
            if (cb != null) {
                try { LogBus.log("OneBotClient", LogLevel.DEBUG, "ws api response ($echo)") } catch (t: Throwable) {}
                runCatching { cb(text) }
            }
            true
        } catch (t: Throwable) {
            false
        }
    }
}
