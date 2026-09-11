package com.example.bandqq.devtools

import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * HTTP API 服务器（零第三方依赖，v1.2.0 DevTools 重写）：
 * 模拟 OneBot HTTP 端口（默认 3000），动作经 ActionRouter 与 WS 共用同一套应答。
 *
 * v1.2.0 修复的关键 bug：请求体此前按「字符数」读 Content-Length（字节数），
 * 任何含中文的请求体（手环快捷回复几乎全是中文）都会少读/阻塞至 8s 超时且不回包，
 * 表现为手环回复后 APP 端「send failed」、DevTools 收不到任何回复日志。
 * 现改为按字节精确读取（先读头到 \r\n\r\n，再读满 Content-Length 字节）。
 */
class HttpApiServer(
    private val port: Int,
    private val router: ActionRouter,
    private val onLog: (String) -> Unit,
) {

    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "http-api").apply { isDaemon = true }
    }

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
            socket.tcpNoDelay = true
            val input = socket.getInputStream()
            // ---- 读取请求头（字节级，遇 \r\n\r\n 结束）----
            val headerBytes = StringBuilder()
            var b: Int
            while (headerBytes.length < 32 * 1024) {
                b = input.read()
                if (b == -1) break
                headerBytes.append(b.toChar())
                val n = headerBytes.length
                if (n >= 4 &&
                    headerBytes[n - 1] == '\n' && headerBytes[n - 2] == '\r' &&
                    headerBytes[n - 3] == '\n' && headerBytes[n - 4] == '\r'
                ) break
            }
            val headerText = headerBytes.toString()
            val lines = headerText.split("\r\n")
            val requestLine = lines.firstOrNull() ?: return
            var contentLength = 0
            for (l in lines.drop(1)) {
                if (l.lowercase().startsWith("content-length:")) {
                    contentLength = l.substringAfter(':').trim().toIntOrNull() ?: 0
                }
            }
            // ---- 按字节精确读取请求体（修复中文多字节错位）----
            val bodyBytes = ByteArray(contentLength.coerceAtLeast(0))
            var off = 0
            while (off < bodyBytes.size) {
                val n = input.read(bodyBytes, off, bodyBytes.size - off)
                if (n < 0) break
                off += n
            }
            val body = String(bodyBytes, 0, off, Charsets.UTF_8)

            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0) ?: "GET"
            val path = (parts.getOrNull(1) ?: "/").substringBefore('?')
            val response = route(method, path, body)

            val out = BufferedOutputStream(socket.getOutputStream())
            out.write("HTTP/1.1 200 OK\r\n".toByteArray(Charsets.ISO_8859_1))
            out.write("Content-Type: application/json; charset=utf-8\r\n".toByteArray(Charsets.ISO_8859_1))
            out.write("Content-Length: ${response.toByteArray(Charsets.UTF_8).size}\r\n".toByteArray(Charsets.ISO_8859_1))
            out.write("Connection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            out.write(response.toByteArray(Charsets.UTF_8))
            out.flush()
        } catch (t: Throwable) {
            onLog("HTTP 处理异常: ${t.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun route(method: String, path: String, body: String): String {
        val action = path.trim('/').substringAfterLast('/').ifBlank { "index" }
        val params = try { org.json.JSONObject(body.ifBlank { "{}" }) } catch (_: Exception) { org.json.JSONObject() }
        return if (method == "POST") {
            router.handle(action, params, null)
        } else {
            // BandQQ 测试连接可能发 GET；统一回标准成功结构
            org.json.JSONObject()
                .put("status", "ok")
                .put("retcode", 0)
                .put("data", org.json.JSONObject())
                .put("echo", org.json.JSONObject.NULL)
                .toString()
        }
    }
}
