package com.example.bandqq.onebot

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.WebSocket
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OneBotClientTest {

    private lateinit var server: MockWebServer
    private val parser = OneBotParser()

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun teardown() {
        try {
            server.shutdown()
        } catch (_: java.io.IOException) {
            // WS 连接未完全释放时 MockWebServer 可能关闭超时，不影响断言结果
        }
    }

    @Test
    fun `sendMessage 发送 HTTP 请求`() = runBlocking {
        val url = server.url("/").toString()
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
        val client = OneBotClient(parser)
        val latch = CountDownLatch(1)
        var ok = false
        client.sendMessage("group", "123", "收到", url) { o, _, _ -> ok = o; latch.countDown() }
        latch.await(3, TimeUnit.SECONDS)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.path!!.contains("send_group_msg"))
        assertTrue(request.body.readUtf8().contains("send_group_msg"))
        assertTrue(ok)
    }

    @Test
    fun `sendMessage 发私聊走 send_private_msg 路径`() = runBlocking {
        val url = server.url("/").toString()
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
        val client = OneBotClient(parser)
        val latch = CountDownLatch(1)
        var ok = false
        client.sendMessage("private", "456", "hi", url) { o, _, _ -> ok = o; latch.countDown() }
        latch.await(3, TimeUnit.SECONDS)
        val request = server.takeRequest()
        assertTrue(request.path!!.contains("send_private_msg"))
        assertTrue(request.body.readUtf8().contains("send_private_msg"))
        assertTrue(ok)
    }

    @Test
    fun `requestApi 请求 get_friend_list 并回传响应`() = runBlocking {
        val url = server.url("/").toString()
        server.enqueue(MockResponse().setBody("""{"status":"ok","data":[{"user_id":10001,"nickname":"小明"}]}"""))
        val client = OneBotClient(parser)
        val latch = CountDownLatch(1)
        var resp: String? = null
        client.requestApi("get_friend_list", url) { resp = it; latch.countDown() }
        latch.await(3, TimeUnit.SECONDS)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.path!!.contains("get_friend_list"))
        assertTrue(resp != null)
        assertTrue(resp!!.contains("小明"))
    }

    @Test
    fun `HTTP 200 但业务 retcode failed 判定失败并携带原因`() = runBlocking {
        val url = server.url("/").toString()
        // 第一条：/{action} 返回业务失败；第二条：/api/{action} 回退同样失败
        server.enqueue(MockResponse().setBody("""{"status":"failed","retcode":1200,"wording":"无效参数"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"failed","retcode":1200}"""))
        val client = OneBotClient(parser)
        val latch = CountDownLatch(1)
        var ok = true
        var err: String? = null
        // 未 start() => 无 WS 连接，走 HTTP 路径
        client.sendMessage("private", "194636275", "测式", url) { o, e, _ -> ok = o; err = e; latch.countDown() }
        latch.await(3, TimeUnit.SECONDS)
        assertTrue(!ok)
        assertTrue(err != null && err!!.contains("1200"))
        // 两次请求：路径回退各一次（末尾断言返回 Unit，避免方法非 void 被 JUnit4 拒绝）
        assertTrue("应向 /{action} 与 /api/{action} 各请求一次", server.requestCount == 2)
    }

    @Test
    fun `HTTP async 受理 retcode=1 判定成功`() = runBlocking {
        val url = server.url("/").toString()
        server.enqueue(MockResponse().setBody("""{"status":"async","retcode":1,"data":null}"""))
        val client = OneBotClient(parser)
        val latch = CountDownLatch(1)
        var ok = false
        client.sendMessage("group", "123", "hi", url) { o, _, _ -> ok = o; latch.countDown() }
        latch.await(3, TimeUnit.SECONDS)
        assertTrue(ok)
    }

    @Test
    fun `WS 已连接时走 echo RPC 并按 echo 匹配响应`() {
        val serverListener = object : okhttp3.WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                // 模拟 OneBot 服务端：解析 action/echo，回推标准响应
                val obj = JsonParser.parseString(text).asJsonObject
                val resp = JsonObject()
                resp.addProperty("status", "ok")
                resp.addProperty("retcode", 0)
                resp.add("data", JsonObject())
                resp.addProperty("echo", obj.get("echo").asString)
                webSocket.send(resp.toString())
            }
        }
        server.enqueue(MockResponse().withWebSocketUpgrade(serverListener))
        val client = OneBotClient(parser)
        val wsUrl = server.url("/").toString().replaceFirst("http://", "ws://")
        val connectedLatch = CountDownLatch(1)
        client.start(
            com.example.bandqq.config.EndpointConfig(wsUrl = wsUrl, wsToken = "", httpUrl = "http://127.0.0.1:1", httpToken = ""),
            object : OneBotListener {
                override fun onEvent(message: OneBotMessage) {}
                override fun onState(connected: Boolean) {
                    if (connected) connectedLatch.countDown()
                }
            }
        )
        try {
            assertTrue("WS 应能建立连接", connectedLatch.await(5, TimeUnit.SECONDS))
            val ackLatch = CountDownLatch(1)
            var ok = false
            client.sendMessage("group", "123", "hi") { o, _, _ -> ok = o; ackLatch.countDown() }
            assertTrue("WS echo 应有响应", ackLatch.await(5, TimeUnit.SECONDS))
            assertTrue(ok)
        } finally {
            client.stop()
        }
    }

    @Test
    fun `WS 响应 retcode failed 时判定失败`() {
        val serverListener = object : okhttp3.WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val obj = JsonParser.parseString(text).asJsonObject
                val resp = JsonObject()
                resp.addProperty("status", "failed")
                resp.addProperty("retcode", 1200)
                resp.addProperty("echo", obj.get("echo").asString)
                webSocket.send(resp.toString())
            }
        }
        server.enqueue(MockResponse().withWebSocketUpgrade(serverListener))
        val client = OneBotClient(parser)
        val wsUrl = server.url("/").toString().replaceFirst("http://", "ws://")
        val connectedLatch = CountDownLatch(1)
        client.start(
            com.example.bandqq.config.EndpointConfig(wsUrl = wsUrl, wsToken = "", httpUrl = "http://127.0.0.1:1", httpToken = ""),
            object : OneBotListener {
                override fun onEvent(message: OneBotMessage) {}
                override fun onState(connected: Boolean) {
                    if (connected) connectedLatch.countDown()
                }
            }
        )
        try {
            assertTrue(connectedLatch.await(5, TimeUnit.SECONDS))
            val ackLatch = CountDownLatch(1)
            var ok = true
            var err: String? = null
            client.sendMessage("private", "10000", "hi") { o, e, _ -> ok = o; err = e; ackLatch.countDown() }
            assertTrue(ackLatch.await(5, TimeUnit.SECONDS))
            assertTrue(!ok)
            assertTrue(err!!.contains("1200"))
        } finally {
            client.stop()
        }
    }
}
