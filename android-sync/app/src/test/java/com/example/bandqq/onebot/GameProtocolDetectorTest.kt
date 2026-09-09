package com.example.bandqq.onebot

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GameProtocolDetectorTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `detect 命中标准 HTTP 端点并回填配置,token 为空`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":"ok","data":{"msg":"SnowLuma"}}"""))
        val cfg = GameProtocolDetector.detect(
            preferred = server.url("/").toString().trimEnd('/'),
            hosts = listOf("127.0.0.1"),
            ports = intArrayOf()
        )
        assertNotNull(cfg)
        assertTrue(cfg!!.httpUrl.startsWith("http://127.0.0.1:"))
        assertTrue(cfg.wsUrl.startsWith("ws://127.0.0.1:"))
        assertEquals("", cfg.wsToken)
        assertEquals("", cfg.httpToken)
    }

    @Test
    fun `probeHttp 对 get_version_info 返回 JSON 判定为可连接`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":"ok","data":{"app_name":"SnowLuma"}}"""))
        val ok = GameProtocolDetector.probeHttp(server.url("/").toString().trimEnd('/'), null)
        assertTrue(ok)
        val request = server.takeRequest()
        assertTrue(request.path!!.contains("get_version_info"))
    }

    @Test
    fun `probeHttp 使用 Bearer token`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
        val ok = GameProtocolDetector.probeHttp(server.url("/").toString().trimEnd('/'), "secret")
        assertTrue(ok)
        val request = server.takeRequest()
        assertEquals("Bearer secret", request.getHeader("Authorization"))
    }

    @Test
    fun `testConnection 直接测试用户输入的 ws 与 http 地址`() = runBlocking {
        val http = server.url("/").toString().trimEnd('/')
        server.enqueue(MockResponse().setBody("""{"status":"ok","data":{"online":true}}"""))
        val result = GameProtocolDetector.testConnection("", "", http, "")
        assertTrue("HTTP 应可达", result.httpReachable)
        assertTrue("空 WS 地址判为不可达", !result.wsReachable)
    }

    @Test
    fun `testConnection 对不可达地址判为失败不抛异常`() = runBlocking {
        val dead = "http://127.0.0.1:${server.port}"
        val result = GameProtocolDetector.testConnection("ws://127.0.0.1:${server.port}", "", dead, "")
        // 无真实服务的端口:两端都应失败
        assertTrue(!result.wsReachable)
        assertTrue(!result.httpReachable)
    }

    @Test
    fun `detect 对非 JSON 响应返回 null`() = runBlocking {
        server.enqueue(MockResponse().setBody("<html>404</html>"))
        val cfg = GameProtocolDetector.detect(
            preferred = server.url("/").toString().trimEnd('/'),
            hosts = listOf("127.0.0.1"),
            ports = intArrayOf()
        )
        assertNull(cfg)
    }

    @Test
    fun `HTTP 非 3000 端口时 ws 保持同端口`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":"ok","data":{"msg":"ok"}}"""))
        val httpPort = server.port
        val cfg = GameProtocolDetector.detect(
            preferred = "http://127.0.0.1:$httpPort",
            hosts = listOf("127.0.0.1"),
            ports = intArrayOf()
        )
        assertNotNull(cfg)
        assertEquals("http://127.0.0.1:$httpPort", cfg!!.httpUrl)
        assertEquals("ws://127.0.0.1:$httpPort", cfg.wsUrl)
    }

    @Test
    fun `WS 端点无真实服务时预握手失败返回 null 而非抛异常`() = runBlocking {
        val cfg = GameProtocolDetector.detect(
            preferred = "ws://127.0.0.1:${server.port}",
            hosts = listOf("127.0.0.1"),
            ports = intArrayOf()
        )
        assertNull("无真实 WS 服务的端点不应误判", cfg)
    }

    @Test
    fun `defaultPorts 覆盖 WS 3001 与常见 HTTP 端口`() {
        val ports = GameProtocolDetector.defaultPorts().toList()
        assertTrue(ports.contains(3001))
        assertTrue(ports.contains(3000))
        assertTrue(ports.contains(8080))
    }
}
