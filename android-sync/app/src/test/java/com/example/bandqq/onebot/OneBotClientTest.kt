package com.example.bandqq.onebot

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

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun teardown() {
        server.shutdown()
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
        client.sendMessage("private", "456", "hi", url) { ok = it; latch.countDown() }
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
}
