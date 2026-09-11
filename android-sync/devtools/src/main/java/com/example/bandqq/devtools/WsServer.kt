package com.example.bandqq.devtools

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/**
 * WebSocket 服务器（零第三方依赖，v1.2.0 DevTools 重写）：
 * 模拟 OneBot 正向 WS 事件端口（默认 3001）。v1.2.0 起为完整 OneBot 正向 WS 端：
 * - RFC6455 握手（Sec-WebSocket-Accept = b64(sha1(key + GUID))）
 * - 文本帧收发（含 126/127 扩展长度）、ping/pong、close 关闭握手
 * - 连接建立即下发 lifecycle connect 元事件，之后每 30s 下发心跳元事件
 *   （OneBot 标准保活语义；BandQQ/ Stapxs 等客户端按此判活）
 * - 客户端动作帧（{"action":...,"echo":...}）→ ActionRouter 应答并原样回带 echo
 * - 断连精细归因日志：客户端主动 close 帧带 code/reason、写入失败、60s 读空闲收割
 *   （此前"客户端断开"无法区分是 APP 主动断、异常断还是已被系统冻结，联调困难）
 */
class WsServer(
    private val port: Int,
    private val router: ActionRouter?,
    private val onEvent: (kind: String, detail: String) -> Unit, // kind: open/close/log
) {

    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "ws-server").apply { isDaemon = true }
    }
    private val heartbeat = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ws-heartbeat").apply { isDaemon = true }
    }
    /** 事件下发专用线程：Android 主线程禁止网络 IO（NetworkOnMainThreadException），
     *  v1.2.0 及以前 UI 点击同步写 socket，发送必失败且连接被误标死亡（发一条断一条的根因）。 */
    private val sender = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ws-sender").apply { isDaemon = true }
    }
    private val clients = CopyOnWriteArrayList<WsConn>()
    private val connSeq = AtomicLong(0)

    @Volatile
    var running = false
        private set

    fun clientCount(): Int = clients.size

    fun start(): Boolean {
        if (running) return true
        return try {
            val ss = ServerSocket(port)
            ss.reuseAddress = true
            serverSocket = ss
            running = true
            pool.execute {
                onEvent("log", "WS 服务器监听 0.0.0.0:$port")
                while (running) {
                    try {
                        val socket = ss.accept()
                        pool.execute { handleAccept(socket) }
                    } catch (t: Throwable) {
                        if (running) onEvent("log", "WS accept error: ${t.message}")
                    }
                }
            }
            // OneBot 11 心跳：30s 周期向所有在线客户端下发 meta_event.heartbeat
            heartbeat.scheduleAtFixedRate({
                if (!running || clients.isEmpty()) return@scheduleAtFixedRate
                val selfId = routerSelfId()
                val frame = ActionRouter.heartbeatEvent(selfId, HEARTBEAT_MS)
                for (c in clients) c.sendText(frame)
            }, HEARTBEAT_MS, HEARTBEAT_MS, TimeUnit.MILLISECONDS)
            true
        } catch (t: Throwable) {
            onEvent("log", "WS 启动失败(端口被占用?)：${t.message}")
            running = false
            false
        }
    }

    private fun routerSelfId(): Long = router?.selfId() ?: MsgBuilder.DEFAULT_SELF_ID

    fun stop() {
        running = false
        runCatching { heartbeat.shutdownNow() }
        runCatching { sender.shutdownNow() }
        runCatching { serverSocket?.close() }
        serverSocket = null
        clients.forEach { runCatching { it.close() } }
        clients.clear()
    }

    /**
     * 向全部已连接客户端广播一条文本帧（异步：写入统一投递到 ws-sender 线程）。
     * 任意线程可安全调用（UI 主线程点击发送也 OK）；写入失败逐条经 onEvent 上报
     * 并移除死连接，不再静默。
     */
    fun broadcast(text: String) {
        val snapshot = clients.toList()
        if (snapshot.isEmpty()) return
        sender.execute {
            for (c in snapshot) {
                if (c.sendText(text)) continue
                val reason = c.deadReason ?: "写入异常"
                onEvent("log", "⚠ 事件下发失败（#${c.id}）：$reason")
                removeClient(c, "下发失败：$reason")
            }
        }
    }

    private fun handleAccept(socket: Socket) {
        val conn = WsConn(socket, connSeq.incrementAndGet())
        if (!conn.handshake()) {
            runCatching { socket.close() }
            return
        }
        clients.add(conn)
        onEvent("open", "BandQQ 已连接 WS (#${conn.id}，当前 ${clients.size} 个)")
        try {
            conn.readLoop()
        } finally {
            removeClient(conn, conn.endReason ?: "连接关闭")
        }
    }

    private fun removeClient(conn: WsConn, reason: String) {
        if (clients.remove(conn)) {
            onEvent("close", "客户端断开 WS (#${conn.id}，剩余 ${clients.size} 个) · $reason")
        }
        runCatching { conn.close() }
    }

    companion object {
        private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val HEARTBEAT_MS = 30_000L
        private const val IDLE_TIMEOUT_MS = 60_000 // 读空闲收割：客户端 pings 每 20s 必达，超时=对端已死
        fun acceptKey(clientKey: String): String {
            val sha1 = MessageDigest.getInstance("SHA-1").digest((clientKey + WS_GUID).toByteArray())
            return Base64.getEncoder().encodeToString(sha1)
        }
    }

    /** 单条 WS 连接：握手 + 帧读写 */
    inner class WsConn(private val socket: Socket, val id: Long) {
        private val input: InputStream = BufferedInputStream(socket.getInputStream(), 16 * 1024)
        private val output: OutputStream = BufferedOutputStream(socket.getOutputStream(), 16 * 1024)
        private val writeLock = Any()
        @Volatile
        private var closed = false
        /** 连接结束原因（供断开日志归因） */
        @Volatile var endReason: String? = null
        /** 最近一次写入失败原因（供 broadcast 上报） */
        @Volatile var deadReason: String? = null

        fun close() {
            if (closed) return
            closed = true
            runCatching { socket.close() }
        }

        /** RFC6455 升级握手：解析 GET 请求头，回 101 + Accept，随后下发 lifecycle connect */
        fun handshake(): Boolean {
            return try {
                socket.soTimeout = 10_000
                var requestLine = ""
                var clientKey = ""
                val lines = mutableListOf<String>()
                var line = readLine()
                while (!line.isNullOrEmpty() && lines.size < 200) {
                    lines.add(line)
                    if (requestLine.isEmpty()) requestLine = line
                    val lower = line.lowercase()
                    if (lower.startsWith("sec-websocket-key:")) {
                        clientKey = line.substringAfter(':').trim()
                    }
                    line = readLine()
                }
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.soTimeout = IDLE_TIMEOUT_MS
                if (!requestLine.uppercase().startsWith("GET") || clientKey.isEmpty()) {
                    onEvent("log", "WS 非 WebSocket 握手请求: $requestLine")
                    return false
                }
                val resp = "HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: " + acceptKey(clientKey) + "\r\n\r\n"
                output.write(resp.toByteArray(Charsets.ISO_8859_1))
                output.flush()
                onEvent("log", "WS 握手完成: $requestLine")
                // OneBot 11 lifecycle connect 元事件
                sendText(ActionRouter.lifecycleEvent(routerSelfId()))
                true
            } catch (t: Throwable) {
                onEvent("log", "WS 握手异常: ${t.message}")
                false
            }
        }

        /** 逐字符读一行（到 \n，容忍 \r\n）；超时抛出 */
        private fun readLine(): String? {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b == -1) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(b.toChar())
                if (sb.length > 8192) return sb.toString()
            }
        }

        /** 读循环：解析帧直到对端关闭；动作帧应答、ping 回 pong、close 回 close */
        fun readLoop() {
            val fragmented = StringBuilder()
            while (!closed && running) {
                val frame = try {
                    readFrame() ?: run { endReason = "对端关闭连接(TCP EOF)"; null }
                } catch (e: SocketTimeoutException) {
                    endReason = "读空闲超时 ${IDLE_TIMEOUT_MS / 1000}s（客户端 20s ping 未达，疑似 APP 已被系统冻结/杀死）"
                    null
                } catch (t: Throwable) {
                    endReason = "读帧异常: ${t.javaClass.simpleName}: ${t.message}"
                    null
                }
                if (frame == null) break
                when (frame.opcode) {
                    0x1, 0x2 -> {
                        val text = String(frame.payload, Charsets.UTF_8)
                        if (frame.fin) {
                            fragmented.setLength(0)
                            handleClientText(text)
                        } else {
                            fragmented.append(text)
                        }
                    }
                    0x0 -> {
                        fragmented.append(String(frame.payload, Charsets.UTF_8))
                        if (frame.fin && fragmented.isNotEmpty()) {
                            val whole = fragmented.toString()
                            fragmented.setLength(0)
                            handleClientText(whole)
                        }
                    }
                    0x9 -> sendRaw(0xA, frame.payload) // ping -> pong
                    0xA -> {} // pong
                    0x8 -> {
                        // RFC6455 close 帧：2 字节大端 code + UTF-8 reason
                        val (code, reason) = parseClose(frame.payload)
                        endReason = "客户端主动关闭 close(code=$code${if (reason.isNotBlank()) ", reason=\"$reason\"" else ""})"
                        sendRaw(0x8, frame.payload.takeIf { it.size <= 125 } ?: ByteArray(0))
                        break
                    }
                    else -> {}
                }
            }
            close()
        }

        /** 客户端动作帧 → ActionRouter 应答（echo 原样回带）；非动作 JSON 仅记录 */
        private fun handleClientText(text: String) {
            val shown = text.take(200)
            val action = try {
                val obj = JSONObject(text)
                if (obj.has("action")) obj else null
            } catch (_: Throwable) { null }
            if (action == null) {
                onEvent("log", "WS 收到客户端帧: $shown")
                return
            }
            val name = action.optString("action", "")
            val params = action.optJSONObject("params") ?: JSONObject()
            val echo: Any? = action.opt("echo")
            val router = this@WsServer.router
            if (router == null) {
                onEvent("log", "WS 收到动作 $name（无路由，忽略）: $shown")
                return
            }
            val resp = try {
                router.handle(name, params, echo)
            } catch (t: Throwable) {
                onEvent("log", "WS 动作 $name 处理异常: ${t.message}")
                JSONObject()
                    .put("status", "failed")
                    .put("retcode", 1200)
                    .put("msg", "internal error: ${t.message}")
                    .also { if (echo != null) it.put("echo", echo.toString()) }
                    .toString()
            }
            // BandQQ 2.8.2+ 握手即上报身份（echo=bandqq-<版本>），旧版 APP 无此动作
            val echoStr = echo?.toString() ?: ""
            val identityHint = if (echoStr.startsWith("bandqq-")) "（BandQQ APP ${echoStr.removePrefix("bandqq-")}）" else ""
            val ok = sendText(resp)
            onEvent("log", "WS 动作 $name$identityHint → 已应答${if (ok) "" else "（写入失败）"}")
        }

        private fun parseClose(payload: ByteArray): Pair<Int, String> {
            return if (payload.size >= 2) {
                val code = ((payload[0].toInt() and 0xff) shl 8) or (payload[1].toInt() and 0xff)
                val reason = if (payload.size > 2) String(payload, 2, payload.size - 2, Charsets.UTF_8) else ""
                code to reason
            } else 1005 to ""
        }

        fun sendText(text: String): Boolean = sendRaw(0x1, text.toByteArray(Charsets.UTF_8))

        /** 发送一帧（服务器->客户端不掩码）；失败记录原因并标记连接死亡 */
        private fun sendRaw(opcode: Int, payload: ByteArray): Boolean {
            if (closed) {
                deadReason = "连接已关闭"
                return false
            }
            return try {
                synchronized(writeLock) {
                    val header = mutableListOf<Byte>()
                    header.add((0x80 or opcode).toByte())
                    when {
                        payload.size < 126 -> header.add(payload.size.toByte())
                        payload.size < 65536 -> {
                            header.add(126.toByte())
                            header.add((payload.size shr 8).toByte())
                            header.add((payload.size and 0xff).toByte())
                        }
                        else -> {
                            header.add(127.toByte())
                            val len = payload.size.toLong()
                            for (shift in 56 downTo 0 step 8) header.add((len shr shift).toByte())
                        }
                    }
                    output.write(header.toByteArray())
                    output.write(payload)
                    output.flush()
                }
                true
            } catch (t: Throwable) {
                deadReason = "${t.javaClass.simpleName}: ${t.message}"
                closed = true
                false
            }
        }

        /** 读一帧：支持掩码（客户端帧必掩码）与 126/127 扩展长度 */
        private fun readFrame(): Frame? {
            val b0 = readByte() ?: return null
            val b1 = readByte() ?: return null
            val fin = (b0 and 0x80) != 0
            val opcode = b0 and 0x0f
            val masked = (b1 and 0x80) != 0
            var len = (b1 and 0x7f).toLong()
            when (len.toInt()) {
                126 -> {
                    val ext = ByteArray(2)
                    if (input.read(ext) != 2) return null
                    len = ((ext[0].toLong() and 0xff) shl 8) or (ext[1].toLong() and 0xff)
                }
                127 -> {
                    val ext = ByteArray(8)
                    if (input.read(ext) != 8) return null
                    len = 0
                    for (b in ext) len = (len shl 8) or (b.toLong() and 0xff)
                }
            }
            if (len > 16 * 1024 * 1024) return null // 防御：16MB 上限
            val mask = if (masked) {
                val m = ByteArray(4)
                if (input.read(m) != 4) return null
                m
            } else null
            val payload = ByteArray(len.toInt())
            var off = 0
            while (off < payload.size) {
                val n = input.read(payload, off, payload.size - off)
                if (n < 0) return null
                off += n
            }
            if (mask != null) {
                for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
            }
            return Frame(fin, opcode, payload)
        }

        private fun readByte(): Int? {
            val b = input.read()
            return if (b == -1) null else b and 0xff
        }
    }

    private class Frame(val fin: Boolean, val opcode: Int, val payload: ByteArray)
}
