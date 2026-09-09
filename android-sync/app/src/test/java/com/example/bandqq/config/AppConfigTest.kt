package com.example.bandqq.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppConfigTest {

    @Test
    fun `默认端点指向 loopback,WS与HTTP端口独立`() {
        val cfg = AppConfig()
        val ep = cfg.endpoint
        assertEquals("ws://127.0.0.1:3001", ep.wsUrl)
        assertEquals("http://127.0.0.1:3000", ep.httpUrl)
        assertEquals("", ep.wsToken)
        assertEquals("", ep.httpToken)
    }

    @Test
    fun `可构造含四字段 token 的端点`() {
        val cfg = AppConfig(
            EndpointConfig(
                wsUrl = "ws://192.168.1.5:3001",
                wsToken = "wst",
                httpUrl = "http://192.168.1.5:3005",
                httpToken = "httpt"
            )
        )
        assertEquals("ws://192.168.1.5:3001", cfg.endpoint.wsUrl)
        assertEquals("wst", cfg.endpoint.wsToken)
        assertEquals("http://192.168.1.5:3005", cfg.endpoint.httpUrl)
        assertEquals("httpt", cfg.endpoint.httpToken)
        assertTrue(cfg.endpoint.httpUrl != cfg.endpoint.wsUrl)
    }
}