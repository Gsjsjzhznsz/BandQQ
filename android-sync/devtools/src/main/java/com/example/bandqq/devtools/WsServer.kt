package com.example.bandqq.devtools

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * 极简 WebSocket 服务器（零第三方依赖，v2.8.0 DevTools）：
 * 模拟 OneBot 正向 WS 事件端口（默认 3001），BandQQ 同步器 APP 以
 * ws://127.0.0.1:3001 连接后，本工具即可向其下发模拟 OneBot 事件。
 *
 * 实现范围（面向本工具的实际需要）：
 * - RFC6455 握手（Sec-WebSocket-Accept = b64(sha1(key + GUID))）
 * - 文本帧收发（含 126/127 扩展长度与 continuation 分片拼接）
 * - ping/pong 心跳、close 关闭握手
 * - 只读不解析客户端文本（OneBot 客户端仅下行事件，上行走 HTTP API）
 */
class WsServer(
    private val port: Int,
    private val onEvent: (kind: String, detail: String) -> Unit, // kind: open/close/log
) {

    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "ws-server").apply { isDaemon = true }
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
            true
        } catch (t: Throwable) {
            onEvent("log", "WS 启动失败(端口被占用?)：${t.message}")
            running = false
            false
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        clients.forEach { runCatching { it.close() } }
        clients.clear()
    }

    /** 向全部已连接的 BandQQ 客户端广播一条文本帧（模拟 OneBot 事件） */
    fun broadcast(text: String) {
        val dead = mutableListOf<WsConn>()
        for (c in clients) {
            if (!c.sendText(text)) dead.add(c)
        }
        dead.forEach { removeClient(it) }
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
            removeClient(conn)
        }
    }

    private fun removeClient(conn: WsConn) {
        if (clients.remove(conn)) {
            onEvent("close", "客户端断开 WS (#${conn.id}，剩余 ${clients.size} 个)")
        }
        runCatching { conn.close() }
    }

    companion object {
        private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
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

        fun close() {
            if (closed) return
            closed = true
            runCatching { socket.close() }
        }

        /** RFC6455 升级握手：解析 GET 请求头，回 101 + Accept */
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
                socket.soTimeout = 0
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

        /** 读循环：解析帧直到对端关闭；ping 回 pong，close 回 close */
        fun readLoop() {
            val fragmented = StringBuilder()
            while (!closed && running) {
                val frame = try {
                    readFrame() ?: break
                } catch (t: Throwable) {
                    break
                }
                when (frame.opcode) {
                    0x1, 0x2 -> {
                        val text = String(frame.payload, Charsets.UTF_8)
                        if (frame.fin) {
                            onEvent("log", "WS 收到客户端帧: ${text.take(120)}")
                        } else {
                            fragmented.append(text)
                        }
                    }
                    0x0 -> {
                        fragmented.append(String(frame.payload, Charsets.UTF_8))
                        if (frame.fin && fragmented.isNotEmpty()) {
                            onEvent("log", "WS 收到分片帧: ${fragmented.take(120)}")
                            fragmented.setLength(0)
                        }
                    }
                    0x9 -> sendRaw(0xA, frame.payload) // ping -> pong
                    0xA -> {} // pong
                    0x8 -> {
                        sendRaw(0x8, frame.payload.takeIf { it.size <= 125 } ?: ByteArray(0))
                        break
                    }
                    else -> {}
                }
            }
            close()
        }

        fun sendText(text: String): Boolean = sendRaw(0x1, text.toByteArray(Charsets.UTF_8))

        /** 发送一帧（服务器->客户端不掩码） */
        private fun sendRaw(opcode: Int, payload: ByteArray): Boolean {
            if (closed) return false
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
