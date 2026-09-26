package io.github.gsjsjzhznsz.bandqq.onebot

import kotlinx.coroutines.runBlocking
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
    private var activeClient: OneBotClient? = null

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun teardown() {
        // 先关客户端（含 WS graceful close），再关 server；
        // WS 连接未完全握手 close 时 shutdown 会撙 IOException，吞掉以免掩盖真实断言结果
        try { activeClient?.stop() } catch (t: Throwable) {}
        try { server.shutdown() } catch (t: Throwable) {}
    }

    @Test
    fun `sendMessage 发送 HTTP 请求`() = runBlocking {
        val url = server.url("/").toString()
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
        val client = OneBotClient(parser)
        val latch = CountDownLatch(1)
        var ok = false
        client.sendMessage("group", "123", "收到", url) { ok = it; latch.countDown() }
        latch.await(3, TimeUnit.SECONDS)
        val request = server.takeRequest(3, TimeUnit.SECONDS)!!
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
        client.sendMessage("private", "456", "hi", url) { ok = it; latch.countDown() }
        latch.await(3, TimeUnit.SECONDS)
        val request = server.takeRequest(3, TimeUnit.SECONDS)!!
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
        val request = server.takeRequest(3, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertTrue(request.path!!.contains("get_friend_list"))
        assertTrue(resp != null)
        assertTrue(resp!!.contains("小明"))
    }

    // ===== v2.9.5：sendMessage 的 WS 回退（真实 NapCat 常只开 WS，HTTP 两路均不可达） =====

    /** 启动客户端并把它的 WS 连到 MockWebServer（返回 wsUrl/httpUrl，await 已连接） */
    private fun startClientWithWs(serverEchoReply: (String) -> String): Pair<OneBotClient, String> {
        val httpUrl = server.url("/").toString()
        val wsUrl = "ws://" + server.hostName + ":" + server.port + "/"
        // 队列顺序：#1 客户端 WS 连接（upgrade），#2/#3 留给 sendMessage 的 HTTP 两路
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : okhttp3.WebSocketListener() {
                override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                    // 对 identify 与 WS API 请求统一回带同 echo 的应答
                    val echo = Regex("\"echo\":\"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: ""
                    webSocket.send(serverEchoReply(echo))
                }
            })
        )
        val client = OneBotClient(parser)
        activeClient = client
        val connected = CountDownLatch(1)
        client.start(
            io.github.gsjsjzhznsz.bandqq.config.EndpointConfig(wsUrl, "", httpUrl, ""),
            object : io.github.gsjsjzhznsz.bandqq.onebot.OneBotListener {
                override fun onEvent(message: OneBotMessage) {}
                override fun onState(state: Boolean) {
                    if (state) connected.countDown()
                }
            }
        )
        assertTrue("client ws should connect", connected.await(5, TimeUnit.SECONDS))
        return client to httpUrl
    }

    @Test
    fun `sendMessage HTTP两路失败回退WS retcode=0 判成功`() = runBlocking {
        val (client, httpUrl) = startClientWithWs { echo ->
            """{"status":"ok","retcode":0,"data":null,"echo":"$echo"}"""
        }
        try {
            server.enqueue(MockResponse().setResponseCode(500))
            server.enqueue(MockResponse().setResponseCode(500))
            val latch = CountDownLatch(1)
            var ok = false
            client.sendMessage("group", "1124665323", "测适", httpUrl) { ok = it; latch.countDown() }
            latch.await(8, TimeUnit.SECONDS)
            assertTrue("WS 回退应判定发送成功", ok)
        } finally {
            client.stop()
        }
    }

    @Test
    fun `sendMessage HTTP两路失败回退WS retcode非0 判失败`() = runBlocking {
        val (client, httpUrl) = startClientWithWs { echo ->
            """{"status":"failed","retcode":1204,"wording":"群不存在","echo":"$echo"}"""
        }
        try {
            server.enqueue(MockResponse().setResponseCode(500))
            server.enqueue(MockResponse().setResponseCode(500))
            val latch = CountDownLatch(1)
            var ok = true
            client.sendMessage("group", "999999999", "hi", httpUrl) { ok = it; latch.countDown() }
            latch.await(8, TimeUnit.SECONDS)
            assertTrue("retcode!=0 应判定发送失败", !ok)
        } finally {
            client.stop()
        }
    }
}
