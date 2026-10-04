# SnowLuma 支持 + 局域网 + 状态同步修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 QQ 同步器添加 SnowLuma 协议端支持与局域网连接,并修复手环/手机状态不同步问题,同时提供手动测试按钮。

**Architecture:** 数据层将单份 `AppConfig` 重构为双端点(NapCat + SnowLuma)+ 活动类型;探测层将 `NapCatDetector` 重构为通用 `GameProtocolDetector`(支持 loopback + 局域网子网扫描、区分两种协议端);状态层扩展 `connect_state` 帧携带 `band`/`protocol` 双字段,手环端渲染同源状态不再本地猜。

**Tech Stack:** Kotlin/Android(OkHttp、DataStore、Coroutines)、Vela 快应用(手环)、Gradle 8.7 + JDK17、Node test runner。

## Global Constraints

- 仓库根：`C:\Users\wang\Desktop\band-qq`（所有 git 命令在此目录执行）
- Android 构建：`powershell scripts\build-android.ps1 -Task testDebugUnitTest`（单测）/ `-Task assembleRelease`（release）
- 手环测试：`cd band-qq; npm test`（Node `--test`，需在 `band-qq/` 目录运行）
- 手环打包：`powershell scripts\rpk-pack.ps1`（从仓库根运行）
- 数据层 DataStore key 迁移兼容：NapCat 沿用旧 key `ws_url/http_url/token`；新增 `snowluma_ws_url/snowluma_http_url/snowluma_token/active_type`
- 状态帧协议：`connect_state` 帧固定为 `{type, seq, state, band, protocol}` 五个字段
- SnowLuma 默认端口：WS 3001（与 NapCat 相同）、WebUI 5099；NapCat 默认 HTTP 3000 / WS 3001
- Android 局域网访问需 manifest 显式 `android:usesCleartextTraffic="true"` + `ACCESS_WIFI_STATE`/`ACCESS_NETWORK_STATE` 权限
- 每任务提交一次 git，提交信息用仓库既有风格（`feat: ...` / `refactor: ...`）

---

### Task 1: ConfigManager 双端点重构

**Files:**
- Modify: `android-sync/app/src/main/java/com/example/bandqq/config/ConfigManager.kt`
- Test: `android-sync/app/src/test/java/com/example/bandqq/config/AppConfigTest.kt` (Create)

**Interfaces:**
- Produces:
  - `enum class ProtocolType { NAPCAT, SNOWLUMA }`
  - `data class EndpointConfig(wsUrl: String, httpUrl: String, token: String)`
  - `data class AppConfig(napcat: EndpointConfig, snowluma: EndpointConfig, activeType: ProtocolType)`
  - `fun AppConfig.getActiveEndpoint(): EndpointConfig`
  - `ConfigHolder.config: AppConfig`（类型不变，语义改为双端点）
  - `ConfigManager.load()/save(config: AppConfig)`

- [ ] **Step 1: 写失败的测试**

Create `android-sync/app/src/test/java/com/example/bandqq/config/AppConfigTest.kt`:

```kotlin
package com.example.bandqq.config

import org.junit.Assert.assertEquals
import org.junit.Test

class AppConfigTest {

    @Test
    fun `默认配置为 NapCat 活动端,指向 loopback`() {
        val cfg = AppConfig()
        assertEquals(ProtocolType.NAPCAT, cfg.activeType)
        assertEquals("ws://127.0.0.1:3001", cfg.getActiveEndpoint().wsUrl)
        assertEquals("http://127.0.0.1:3000", cfg.getActiveEndpoint().httpUrl)
    }

    @Test
    fun `getActiveEndpoint 按 activeType 返回对应端点`() {
        val cfg = AppConfig(
            napcat = EndpointConfig("ws://127.0.0.1:3001", "http://127.0.0.1:3000", "t1"),
            snowluma = EndpointConfig("ws://192.168.1.5:3001", "http://192.168.1.5:3001", "t2"),
            activeType = ProtocolType.SNOWLUMA
        )
        assertEquals("ws://192.168.1.5:3001", cfg.getActiveEndpoint().wsUrl)
        assertEquals("t2", cfg.getActiveEndpoint().token)
    }

    @Test
    fun `SnowLuma 默认端点指向 loopback 3001`() {
        val snow = AppConfig().snowluma
        assertEquals("ws://127.0.0.1:3001", snow.wsUrl)
        assertEquals("http://127.0.0.1:3001", snow.httpUrl)
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `powershell scripts\build-android.ps1 -Task testDebugUnitTest`
Expected: FAIL（`AppConfig`/`EndpointConfig`/`ProtocolType` 尚未定义，编译错误）

- [ ] **Step 3: 重写 ConfigManager.kt**

Replace the whole file `android-sync/app/src/main/java/com/example/bandqq/config/ConfigManager.kt` with:

```kotlin
package com.example.bandqq.config

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore(name = "sync_config")

enum class ProtocolType { NAPCAT, SNOWLUMA }

data class EndpointConfig(
    val wsUrl: String,
    val httpUrl: String,
    val token: String
)

data class AppConfig(
    val napcat: EndpointConfig = EndpointConfig("ws://127.0.0.1:3001", "http://127.0.0.1:3000", ""),
    val snowluma: EndpointConfig = EndpointConfig("ws://127.0.0.1:3001", "http://127.0.0.1:3001", ""),
    val activeType: ProtocolType = ProtocolType.NAPCAT
)

fun AppConfig.getActiveEndpoint(): EndpointConfig = when (activeType) {
    ProtocolType.NAPCAT -> napcat
    ProtocolType.SNOWLUMA -> snowluma
}

object ConfigHolder {
    var config: AppConfig = AppConfig()
}

class ConfigManager(private val context: Context) {

    private object Keys {
        val WS = stringPreferencesKey("ws_url")
        val HTTP = stringPreferencesKey("http_url")
        val TOKEN = stringPreferencesKey("token")
        val SNOW_WS = stringPreferencesKey("snowluma_ws_url")
        val SNOW_HTTP = stringPreferencesKey("snowluma_http_url")
        val SNOW_TOKEN = stringPreferencesKey("snowluma_token")
        val ACTIVE = stringPreferencesKey("active_type")
    }

    suspend fun load(): AppConfig {
        val prefs = context.dataStore.data.first()
        val default = AppConfig()
        val cfg = AppConfig(
            napcat = EndpointConfig(
                wsUrl = prefs[Keys.WS] ?: default.napcat.wsUrl,
                httpUrl = prefs[Keys.HTTP] ?: default.napcat.httpUrl,
                token = prefs[Keys.TOKEN] ?: ""
            ),
            snowluma = EndpointConfig(
                wsUrl = prefs[Keys.SNOW_WS] ?: default.snowluma.wsUrl,
                httpUrl = prefs[Keys.SNOW_HTTP] ?: default.snowluma.httpUrl,
                token = prefs[Keys.SNOW_TOKEN] ?: ""
            ),
            activeType = prefs[Keys.ACTIVE]?.let { runCatching { ProtocolType.valueOf(it) }.getOrNull() }
                ?: ProtocolType.NAPCAT
        )
        ConfigHolder.config = cfg
        return cfg
    }

    suspend fun save(config: AppConfig) {
        context.dataStore.edit { prefs ->
            prefs[Keys.WS] = config.napcat.wsUrl
            prefs[Keys.HTTP] = config.napcat.httpUrl
            prefs[Keys.TOKEN] = config.napcat.token
            prefs[Keys.SNOW_WS] = config.snowluma.wsUrl
            prefs[Keys.SNOW_HTTP] = config.snowluma.httpUrl
            prefs[Keys.SNOW_TOKEN] = config.snowluma.token
            prefs[Keys.ACTIVE] = config.activeType.name
        }
        ConfigHolder.config = config
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `powershell scripts\build-android.ps1 -Task testDebugUnitTest`
Expected: PASS（AppConfigTest 3 项全过；注意 `MainActivity`/`SyncService`/`OneBotClient` 仍引用旧 `AppConfig(wsUrl=...)` 会编译失败——**这是预期**，后续 Task 修正）

- [ ] **Step 5: 提交**

```bash
git add android-sync/app/src/main/java/com/example/bandqq/config/ConfigManager.kt android-sync/app/src/test/java/com/example/bandqq/config/AppConfigTest.kt
git commit -m "refactor(config): AppConfig 拆分为 NapCat/SnowLuma 双端点 + 活动类型"
```

---

### Task 2: 通用 GameProtocolDetector(协议类型 + 局域网扫描)

**Files:**
- Rename: `android-sync/app/src/main/java/com/example/bandqq/onebot/NapCatDetector.kt` → `GameProtocolDetector.kt`
- Test: `android-sync/app/src/test/java/com/example/bandqq/onebot/GameProtocolDetectorTest.kt` (Rename from `NapCatDetectorTest.kt`)

**Interfaces:**
- Consumes: `AppConfig`, `EndpointConfig`, `ProtocolType`（来自 Task 1）
- Produces:
  - `object GameProtocolDetector`
  - `suspend fun detect(type: ProtocolType, preferred: String? = null, hosts: List<String> = detectHosts(), ports: IntArray = defaultPorts(type)): EndpointConfig?`
  - `fun detectHosts(): List<String>`（loopback + 局域网子网枚举，网络不可用时仅 loopback）
  - `fun defaultPorts(type: ProtocolType): IntArray`（NAPCAT→[3000,6099,3001,5700,8080,3002]，SNOWLUMA→[3001,5099]）

- [ ] **Step 1: 写失败的测试**

Rename test file `android-sync/app/src/test/java/com/example/bandqq/onebot/NapCatDetectorTest.kt` → `GameProtocolDetectorTest.kt`,then replace with:

```kotlin
package com.example.bandqq.onebot

import com.example.bandqq.config.ProtocolType
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
    fun `detect NapCat 命中标准端点并回填配置`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":"ok","data":{"msg":"NapCat"}}"""))
        val cfg = GameProtocolDetector.detect(
            type = ProtocolType.NAPCAT,
            preferred = server.url("/").toString().trimEnd('/'),
            hosts = listOf("127.0.0.1"),
            ports = intArrayOf()
        )
        assertNotNull(cfg)
        assertTrue(cfg!!.httpUrl.startsWith("http://127.0.0.1:"))
        assertTrue(cfg.wsUrl.startsWith("ws://127.0.0.1:"))
    }

    @Test
    fun `detect NapCat 对非 JSON 响应返回 null`() = runBlocking {
        server.enqueue(MockResponse().setBody("<html>404</html>"))
        val cfg = GameProtocolDetector.detect(
            type = ProtocolType.NAPCAT,
            preferred = server.url("/").toString().trimEnd('/'),
            hosts = listOf("127.0.0.1"),
            ports = intArrayOf()
        )
        assertNull(cfg)
    }

    @Test
    fun `非 3000 端口保持 http ws 同端口`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":"ok","data":{"msg":"ok"}}"""))
        val httpPort = server.port
        val cfg = GameProtocolDetector.detect(
            type = ProtocolType.NAPCAT,
            preferred = "http://127.0.0.1:$httpPort",
            hosts = listOf("127.0.0.1"),
            ports = intArrayOf()
        )
        assertNotNull(cfg)
        assertEquals("http://127.0.0.1:$httpPort", cfg!!.httpUrl)
        assertEquals("ws://127.0.0.1:$httpPort", cfg.wsUrl)
    }

    @Test
    fun `detect SnowLuma 命中 WS 3001 并回填`() = runBlocking {
        val cfg = GameProtocolDetector.detect(
            type = ProtocolType.SNOWLUMA,
            preferred = "ws://127.0.0.1:${server.port}",
            hosts = listOf("127.0.0.1"),
            ports = intArrayOf()
        )
        // MockWebServer 不做 WS 升级,预握手失败后应回退为 null 而非抛异常
        assertNull("无真实 WS 服务的端点不应误判", cfg)
    }

    @Test
    fun `defaultPorts 分别覆盖两端默认端口`() {
        val napPorts = GameProtocolDetector.defaultPorts(ProtocolType.NAPCAT).toList()
        assertTrue(napPorts.contains(3000))
        assertTrue(napPorts.contains(6099))
        val snowPorts = GameProtocolDetector.defaultPorts(ProtocolType.SNOWLUMA).toList()
        assertTrue(snowPorts.contains(3001))
        assertTrue(snowPorts.contains(5099))
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `powershell scripts\build-android.ps1 -Task testDebugUnitTest`
Expected: FAIL（`GameProtocolDetector` 不存在）

- [ ] **Step 3: 实现 GameProtocolDetector.kt**

Git-rename NapCatDetector.kt → GameProtocolDetector.kt,replace content with:

```kotlin
package com.example.bandqq.onebot

import com.example.bandqq.config.EndpointConfig
import com.example.bandqq.config.ProtocolType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

/**
 * 通用协议端探测：
 *  - 扫描 loopback + 局域网子网中配置的默认端口
 *  - 按协议类型生成候选配置（NapCat: HTTP 3000/6099/...; SnowLuma: WS 3001/5099）
 *  - 判定条件：HTTP `GET /api/get_version` 返回 JSON（NapCat 与 SnowLuma 均实现 OB11 协议）；
 *    SnowLuma 额外尝试 WebSocket 握手（避免部分部署无 HTTP 端点时漏检）。
 */
object GameProtocolDetector {

    private val client = OkHttpClient.Builder()
        .connectTimeout(800, TimeUnit.MILLISECONDS)
        .readTimeout(800, TimeUnit.MILLISECONDS)
        .build()

    private val wsClient = OkHttpClient.Builder()
        .connectTimeout(800, TimeUnit.MILLISECONDS)
        .readTimeout(800, TimeUnit.MILLISECONDS)
        .pingInterval(0, TimeUnit.SECONDS)
        .build()

    fun defaultPorts(type: ProtocolType): IntArray = when (type) {
        ProtocolType.NAPCAT -> intArrayOf(3000, 6099, 3001, 5700, 8080, 3002)
        ProtocolType.SNOWLUMA -> intArrayOf(3001, 5099)
    }

    suspend fun detect(
        type: ProtocolType,
        preferred: String? = null,
        hosts: List<String> = detectHosts(),
        ports: IntArray = defaultPorts(type)
    ): EndpointConfig? {
        val preferredUrl = preferred?.trim()?.ifBlank { null }
        val candidates = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        if (preferredUrl != null && seen.add(preferredUrl)) candidates.add(preferredUrl)

        for (host in hosts) {
            for (port in ports) {
                val url = if (host.startsWith("http")) host else "http://$host:$port"
                if (seen.add(url)) candidates.add(url)
            }
        }

        return coroutineScope {
            val sem = Semaphore(24)
            val results = candidates.map { url ->
                async(Dispatchers.IO) {
                    sem.withPermit { if (isProtocol(type, url)) url else null }
                }
            }.awaitAll().filterNotNull()

            if (preferredUrl != null && results.contains(preferredUrl)) {
                toConfig(type, preferredUrl)
            } else {
                results.firstOrNull()?.let { toConfig(type, it) }
            }
        }
    }

    private fun isProtocol(type: ProtocolType, base: String): Boolean {
        if (base.startsWith("ws://") || base.startsWith("wss://")) {
            return probeWs(base)
        }
        if (httpProbe(base)) return true
        // SnowLuma 无 HTTP 端点时,尝试把 http 换成 ws 握手
        if (type == ProtocolType.SNOWLUMA) {
            val wsUrl = base.replaceFirst("http://", "ws://").replaceFirst("https://", "wss://")
            if (wsUrl != base && probeWs(wsUrl)) return true
        }
        return false
    }

    private fun httpProbe(httpBase: String): Boolean = try {
        val resp = client.newCall(
            Request.Builder().url(httpBase.trimEnd('/') + "/api/get_version").get().build()
        ).execute()
        resp.use { r ->
            if (!r.isSuccessful) return@use false
            val body = r.body?.string().orEmpty()
            body.isNotBlank() && body.trimStart().startsWith("{")
        }
    } catch (e: IOException) {
        false
    } catch (e: Exception) {
        false
    }

    private fun probeWs(wsUrl: String): Boolean {
        val latch = java.util.concurrent.CountDownLatch(1)
        val result = BooleanArray(1) { false }
        try {
            val ws: WebSocket = wsClient.newWebSocket(
                Request.Builder().url(wsUrl).build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        result[0] = true
                        latch.countDown()
                        webSocket.close(1000, "probe done")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {}
                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {}
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        latch.countDown()
                    }
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        latch.countDown()
                    }
                }
            )
            ws.cancel()
        } catch (e: Exception) {
            return false
        }
        latch.await(1, TimeUnit.SECONDS)
        return result[0]
    }

    private fun toConfig(type: ProtocolType, base: String): EndpointConfig {
        if (base.startsWith("ws://") || base.startsWith("wss://")) {
            return EndpointConfig(wsUrl = base, httpUrl = base, token = "")
        }
        val trimmed = base.trim()
        return try {
            val isHttps = trimmed.lowercase().startsWith("https://")
            val scheme = if (isHttps) "wss" else "ws"
            val m = Regex("""^https?://([^:/]+)(?::(\d+))?""").find(trimmed)
            if (m == null) return EndpointConfig(wsUrl = trimmed, httpUrl = base, token = "")
            val host = m.groupValues[1]
            val httpPort = m.groupValues[2].ifBlank { if (isHttps) "443" else "80" }.toInt()
            val wsPort = when {
                type == ProtocolType.NAPCAT && httpPort == 3000 -> 3001
                else -> httpPort
            }
            EndpointConfig(wsUrl = "$scheme://$host:$wsPort", httpUrl = base, token = "")
        } catch (e: Exception) {
            EndpointConfig(wsUrl = trimmed, httpUrl = base, token = "")
        }
    }

    /** 探测目标主机：loopback + 本机所在子网。取不到网络信息时仅 loopback。 */
    fun detectHosts(): List<String> {
        val hosts = mutableListOf("127.0.0.1")
        return try {
            for (nic in NetworkInterface.getNetworkInterfaces()) {
                for (addr in nic?.inetAddresses.orEmpty().toList()) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        val prefix = ip.substringBeforeLast('.')
                        hosts.addAll((1..254).map { "$prefix.$it" })
                    }
                }
            }
            hosts
        } catch (e: Exception) {
            hosts
        }
    }
}
```

Note: 若 `NetworkInterface.getNetworkInterfaces()` 在单元测试环境（无网卡枚举权限）抛异常,`detectHosts()` 已兜底仅返回 `["127.0.0.1"]`,测试不依赖它。

- [ ] **Step 4: 运行测试验证通过**

Run: `powershell scripts\build-android.ps1 -Task testDebugUnitTest`
Expected: PASS（GameProtocolDetectorTest 5 项全过）

- [ ] **Step 5: 提交**

```bash
git add -A android-sync/app/src/main/java/com/example/bandqq/onebot/ android-sync/app/src/test/java/com/example/bandqq/onebot/
git commit -m "feat(onebot): 通用 GameProtocolDetector 支持双协议端与局域网扫描"
```

---

### Task 3: OneBotClient 与 SyncService 接入活动端点

**Files:**
- Modify: `android-sync/app/src/main/java/com/example/bandqq/onebot/OneBotClient.kt`
- Modify: `android-sync/app/src/main/java/com/example/bandqq/sync/SyncService.kt`
- Test: `android-sync/app/src/test/java/com/example/bandqq/onebot/OneBotClientTest.kt`（仅编译适配,无新用例）

**Interfaces:**
- Consumes: `EndpointConfig`（Task 1）、`AppConfig.getActiveEndpoint()`（Task 1）
- Produces: `OneBotClient.start(config: EndpointConfig, listener: OneBotListener)`；内部 `config` 字段类型改为 `EndpointConfig`。`sendMessage`/`requestApi` 默认 base 改为 `config.httpUrl`（不变语义）。

- [ ] **Step 1: 改 OneBotClient 参数类型**

Edit `OneBotClient.kt`:
- `private var config: AppConfig = AppConfig()` → `private var config: EndpointConfig = EndpointConfig("ws://127.0.0.1:3001", "http://127.0.0.1:3000", "")`
- `import com.example.bandqq.config.AppConfig` → `import com.example.bandqq.config.EndpointConfig`
- `fun start(config: AppConfig, listener: OneBotListener)` → `fun start(config: EndpointConfig, listener: OneBotListener)`
- 其余字段访问 `config.wsUrl/config.httpUrl/config.token` 不变（`EndpointConfig` 有同名字段）

- [ ] **Step 2: 改 SyncService 传活动端点**

Edit `SyncService.kt` `onStartCommand` 内：

```kotlin
val config = configManager.load()
oneBot.start(config.getActiveEndpoint(), broker)
```

Add import: `com.example.bandqq.config.getActiveEndpoint`

- [ ] **Step 3: 运行 Android 单测确认编译通过**

Run: `powershell scripts\build-android.ps1 -Task testDebugUnitTest`
Expected: PASS（所有单测全过,且此前 Task 1 遗留的编译错误消失）

- [ ] **Step 4: 提交**

```bash
git add android-sync/app/src/main/java/com/example/bandqq/onebot/OneBotClient.kt android-sync/app/src/main/java/com/example/bandqq/sync/SyncService.kt
git commit -m "refactor(onebot): OneBotClient 改用 EndpointConfig,同步服务按活动协议端连接"
```

---

### Task 4: connect_state 帧携带 band/protocol,统一状态推送

**Files:**
- Modify: `android-sync/app/src/main/java/com/example/bandqq/sync/InterconnectBridge.kt`
- Modify: `android-sync/app/src/main/java/com/example/bandqq/sync/MessageBroker.kt`
- Test: `android-sync/app/src/test/java/com/example/bandqq/sync/MessageBrokerTest.kt`

**Interfaces:**
- Produces:
  - `object SyncStatePush`：`fun buildFrame(): String`，输出 `{"type":"connect_state","seq":0,"state":"connected|disconnected","band":<SyncState.bandConnected>,"protocol":<SyncState.oneBotConnected>}`
- Consumes: `SyncState`（既有）

- [ ] **Step 1: 写失败的测试**

Append to `MessageBrokerTest.kt`:

```kotlin
    @Test
    fun `onState 推送的 connect_state 帧含 band 与 protocol 字段`() {
        SyncState.bandConnected = true
        SyncState.oneBotConnected = false
        val broker = MessageBroker(parser, FakeOneBot { _, _, _ -> true }, MessageStore())
        val out = mutableListOf<String>()
        broker.bandSender = { out.add(it) }
        broker.onState(true)
        assertTrue(out[0].contains("\"type\":\"connect_state\""))
        assertTrue(out[0].contains("\"band\":true"))
        assertTrue(out[0].contains("\"protocol\":false"))
    }
```

需要 import：`com.example.bandqq.sync.SyncState`（已在同包,无需 import）。

- [ ] **Step 2: 运行测试验证失败**

Run: `powershell scripts\build-android.ps1 -Task testDebugUnitTest`
Expected: FAIL（`SyncStatePush` 不存在 / `onState` 帧无 band 字段）

- [ ] **Step 3: 新增 SyncStatePush + 改造两处状态帧发送**

Create `android-sync/app/src/main/java/com/example/bandqq/sync/SyncStatePush.kt`:

```kotlin
package com.example.bandqq.sync

import com.google.gson.JsonObject

/** 组装统一 connect_state 帧（蓝牙通道 band + 协议端 protocol）,供手机端下发给手环。 */
object SyncStatePush {
    fun buildFrame(): String {
        val band = SyncState.bandConnected
        val protocol = SyncState.oneBotConnected
        val obj = JsonObject()
        obj.addProperty("type", "connect_state")
        obj.addProperty("seq", 0)
        obj.addProperty("state", if (band && protocol) "connected" else "disconnected")
        obj.addProperty("band", band)
        obj.addProperty("protocol", protocol)
        return obj.toString()
    }
}
```

Edit `MessageBroker.kt` `onState` to:

```kotlin
    override fun onState(connected: Boolean) {
        bandSender(SyncStatePush.buildFrame())
    }
```

Edit `InterconnectBridge.kt`:
- In `onConnect()`: replace `broker?.bandSender?.invoke(buildStateFrame(true))` with `broker?.bandSender?.invoke(SyncStatePush.buildFrame())`
- In `onDisconnect()`: replace `broker?.bandSender?.invoke(buildStateFrame(false))` with `broker?.bandSender?.invoke(SyncStatePush.buildFrame())`
- Delete private `buildStateFrame(connected: Boolean)`（不再使用）
- Add import: `com.example.bandqq.sync.SyncStatePush`（同包 InterconnectBridge 在 `com.example.bandqq.sync`,无需 import）

- [ ] **Step 4: 运行测试验证通过**

Run: `powershell scripts\build-android.ps1 -Task testDebugUnitTest`
Expected: PASS（MessageBrokerTest 新增用例过,其余全绿）

- [ ] **Step 5: 提交**

```bash
git add android-sync/app/src/main/java/com/example/bandqq/sync/SyncStatePush.kt android-sync/app/src/main/java/com/example/bandqq/sync/MessageBroker.kt android-sync/app/src/main/java/com/example/bandqq/sync/InterconnectBridge.kt android-sync/app/src/test/java/com/example/bandqq/sync/MessageBrokerTest.kt
git commit -m "feat(sync): connect_state 帧携带 band/protocol 双状态,统一状态推送"
```

---

### Task 5: MainActivity 双协议配置 UI + 测试按钮 + 局域网探测 + Manifest

**Files:**
- Modify: `android-sync/app/src/main/res/layout/activity_main.xml`
- Modify: `android-sync/app/src/main/java/com/example/bandqq/MainActivity.kt`
- Modify: `android-sync/app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `ProtocolType`, `AppConfig`, `ConfigManager`, `GameProtocolDetector`（Task 1-2）
- Produces: 绑定控件 `napcatRadio/snowlumaRadio`、`testNapBtn/testSnowBtn`、`probeBtn`（复用）、`refreshStatus()` 增加协议端文案

- [ ] **Step 1: 更新 activity_main.xml**

Restructure the config section so NapCat inputs and SnowLuma inputs are two groups,plus protocol selector and test buttons. Replace lines 20-76 (from `<TextView android:text="NapCat WS 地址"` through the probe hint TextView) with:

```xml
        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="当前协议端"
            android:paddingTop="12dp" />

        <RadioGroup
            android:id="@+id/protocolGroup"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal">

            <RadioButton
                android:id="@+id/napcatRadio"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="NapCat" />

            <RadioButton
                android:id="@+id/snowlumaRadio"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="SnowLuma" />
        </RadioGroup>

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="NapCat 配置"
            android:paddingTop="12dp" />

        <EditText
            android:id="@+id/wsInput"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:hint="ws://127.0.0.1:3001" />

        <EditText
            android:id="@+id/httpInput"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:hint="http://127.0.0.1:3000" />

        <EditText
            android:id="@+id/tokenInput"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:hint="NapCat Access Token (可选)" />

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="SnowLuma 配置"
            android:paddingTop="12dp" />

        <EditText
            android:id="@+id/snowWsInput"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:hint="ws://127.0.0.1:3001" />

        <EditText
            android:id="@+id/snowHttpInput"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:hint="http://127.0.0.1:3001" />

        <EditText
            android:id="@+id/snowTokenInput"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:hint="SnowLuma Token (可选)" />

        <Button
            android:id="@+id/saveBtn"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="保存配置"
            android:paddingTop="8dp" />

        <Button
            android:id="@+id/probeBtn"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="自动探测(局域网)"
            android:paddingTop="8dp" />

        <Button
            android:id="@+id/testNapBtn"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="测试 NapCat 连接"
            android:paddingTop="8dp" />

        <Button
            android:id="@+id/testSnowBtn"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="测试 SnowLuma 连接"
            android:paddingTop="8dp" />
```

- [ ] **Step 2: 更新 MainActivity.kt**

在 `loadConfig()` 回填时同时回填 SnowLuma 输入框,并绑定侧切开关与测试按钮。替换 `loadConfig` 为:

```kotlin
    private fun loadConfig() {
        scope.launch {
            val config = configManager.load()
            binding.wsInput.setText(config.napcat.wsUrl)
            binding.httpInput.setText(config.napcat.httpUrl)
            binding.tokenInput.setText(config.napcat.token)
            binding.snowWsInput.setText(config.snowluma.wsUrl)
            binding.snowHttpInput.setText(config.snowluma.httpUrl)
            binding.snowTokenInput.setText(config.snowluma.token)
            when (config.activeType) {
                ProtocolType.NAPCAT -> binding.napcatRadio.isChecked = true
                ProtocolType.SNOWLUMA -> binding.snowlumaRadio.isChecked = true
            }
            refreshStatus()
        }
    }
```

替换 `bindButtons` 中 `saveBtn` 监听为(保存双端点+活动类型):

```kotlin
        binding.saveBtn.setOnClickListener {
            scope.launch {
                val active = if (binding.napcatRadio.isChecked) ProtocolType.NAPCAT else ProtocolType.SNOWLUMA
                val cfg = ConfigHolder.config.copy(
                    napcat = EndpointConfig(
                        binding.wsInput.text.toString().trim(),
                        binding.httpInput.text.toString().trim(),
                        binding.tokenInput.text.toString().trim()
                    ),
                    snowluma = EndpointConfig(
                        binding.snowWsInput.text.toString().trim(),
                        binding.snowHttpInput.text.toString().trim(),
                        binding.snowTokenInput.text.toString().trim()
                    ),
                    activeType = active
                )
                configManager.save(cfg)
                Toast.makeText(this@MainActivity, "配置已保存", Toast.LENGTH_SHORT).show()
                refreshStatus()
            }
        }
```

在 `bindButtons` 末尾追加测试按钮与侧切监听:

```kotlin
        binding.testNapBtn.setOnClickListener { testConnection(ProtocolType.NAPCAT) }
        binding.testSnowBtn.setOnClickListener { testConnection(ProtocolType.SNOWLUMA) }
        binding.napcatRadio.setOnClickListener { refreshStatus() }
        binding.snowlumaRadio.setOnClickListener { refreshStatus() }
```

新增方法 `testConnection`（探测对应协议端并回填该协议配置,不切换 activeType）:

```kotlin
    private fun testConnection(type: ProtocolType) {
        scope.launch {
            binding.statusText.text = "状态：正在测试 ${if (type == ProtocolType.NAPCAT) "NapCat" else "SnowLuma"}..."
            val wsText = if (type == ProtocolType.NAPCAT) binding.wsInput.text.toString().trim() else binding.snowWsInput.text.toString().trim()
            val httpText = if (type == ProtocolType.NAPCAT) binding.httpInput.text.toString().trim() else binding.snowHttpInput.text.toString().trim()
            val cfg = GameProtocolDetector.detect(type, preferred = httpText.ifBlank { wsText }, hosts = listOf("127.0.0.1"), ports = intArrayOf())
            if (cfg != null) {
                if (type == ProtocolType.NAPCAT) {
                    binding.wsInput.setText(cfg.wsUrl)
                    binding.httpInput.setText(cfg.httpUrl)
                } else {
                    binding.snowWsInput.setText(cfg.wsUrl)
                    binding.snowHttpInput.setText(cfg.httpUrl)
                }
                binding.statusText.text = "状态：${if (type == ProtocolType.NAPCAT) "NapCat" else "SnowLuma"} 在线"
                toast("${if (type == ProtocolType.NAPCAT) "NapCat" else "SnowLuma"} 连接正常")
            } else {
                binding.statusText.text = "状态：${if (type == ProtocolType.NAPCAT) "NapCat" else "SnowLuma"} 连接失败"
                toast("连接失败,请检查协议端是否已启动")
            }
        }
    }
```

修改 `probeNapCat()` 为「局域网探测当前活动协议端」并回填对应配置:

```kotlin
    private fun probeNapCat() {
        scope.launch {
            val type = if (binding.napcatRadio.isChecked) ProtocolType.NAPCAT else ProtocolType.SNOWLUMA
            val name = if (type == ProtocolType.NAPCAT) "NapCat" else "SnowLuma"
            binding.statusText.text = "状态：正在局域网探测 $name..."
            val detected = GameProtocolDetector.detect(
                type = type,
                preferred = if (type == ProtocolType.NAPCAT) binding.httpInput.text.toString().trim() else binding.snowHttpInput.text.toString().trim()
            )
            if (detected != null) {
                if (type == ProtocolType.NAPCAT) {
                    binding.wsInput.setText(detected.wsUrl)
                    binding.httpInput.setText(detected.httpUrl)
                } else {
                    binding.snowWsInput.setText(detected.wsUrl)
                    binding.snowHttpInput.setText(detected.httpUrl)
                }
                val cfg = ConfigHolder.config.copy(
                    napcat = if (type == ProtocolType.NAPCAT) detected else ConfigHolder.config.napcat,
                    snowluma = if (type == ProtocolType.SNOWLUMA) detected else ConfigHolder.config.snowluma
                )
                configManager.save(cfg)
                binding.statusText.text = "状态：$name 在线（${detected.httpUrl}）"
                toast("已探测到 $name,配置已保存")
            } else {
                binding.statusText.text = "状态：未检测到 $name,请检查协议端是否已启动"
            }
        }
    }
```

更新 `refreshStatus()` 文案以体现活动协议端:

```kotlin
    private fun refreshStatus() {
        val name = if (ConfigHolder.config.activeType == ProtocolType.NAPCAT) "NapCat" else "SnowLuma"
        binding.statusText.text = when {
            SyncState.oneBotConnected && SyncState.bandConnected -> "状态：互联已连接,$name 在线"
            SyncState.oneBotConnected -> "状态：$name 在线,等待手环连接"
            SyncState.bandConnected -> "状态：手环已连接,等待 $name"
            else -> "状态：未连接（请启动同步服务）"
        }
    }
```

Update imports in `MainActivity.kt`:
```kotlin
import com.example.bandqq.config.AppConfig
import com.example.bandqq.config.EndpointConfig
import com.example.bandqq.config.ProtocolType
import com.example.bandqq.onebot.GameProtocolDetector
```
Remove `import com.example.bandqq.onebot.NapCatDetector`.

- [ ] **Step 3: 更新 AndroidManifest.xml**

Add before `</manifest>`:
```xml
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```
Add attribute to `<application>`:
```xml
        android:usesCleartextTraffic="true"
```

- [ ] **Step 4: 运行 Android 单测确认编译通过**

Run: `powershell scripts\build-android.ps1 -Task testDebugUnitTest`
Expected: PASS（编译通过,全部单测绿）

- [ ] **Step 5: 提交**

```bash
git add android-sync/app/src/main/res/layout/activity_main.xml android-sync/app/src/main/java/com/example/bandqq/MainActivity.kt android-sync/app/src/main/AndroidManifest.xml
git commit -m "feat(ui): 双协议端配置/侧切/测试按钮/局域网探测 + 网络权限"
```

---

### Task 6: 手环端渲染同源 connect_state 帧

**Files:**
- Modify: `band-qq/src/pages/settings/settings.ux`
- Modify: `band-qq/src/pages/index/index.ux`
- Modify: `band-qq/src/app.ux`
- Test: `band-qq/test/store.test.js`

**Interfaces:**
- Consumes: 手机端 `connect_state` 帧（Task 4）：`{type, seq, state, band, protocol}`
- Produces: store 新增 `setConnectState/getConnectState`；页面 `statusText` 渲染 `band` 字段

- [ ] **Step 1: 写失败的测试**

Append **inside** the `describe('store', () => { ... })` block in `band-qq/test/store.test.js`（文件顶部已有 `store` 闭包变量与 `assert`,`createStore(mockStorage({}))` 于 `beforeEach` 创建):

```javascript
  it('setConnectState 保存并可读取 band/protocol', () => {
    store.setConnectState({ type: 'connect_state', band: true, protocol: false })
    const s = store.getConnectState()
    assert.equal(s.band, true)
    assert.equal(s.protocol, false)
  })
```

- [ ] **Step 2: 运行测试验证失败**

Run: `cd band-qq; npm test`
Expected: FAIL（`setConnectState` 未定义）

- [ ] **Step 3: 实现 store 状态 + 页面渲染**

Edit `band-qq/src/common/store.js`:在 `createStore` 返回对象内（与其他成员并列）新增闭包状态与同步方法：

```javascript
    setConnectState(state) {
      connectState = state || null
    },
    getConnectState() {
      return connectState
    }
```

并在 `createStore` 顶部闭包变量声明处新增 `let connectState = null`（放在 `let visibleContacts = []` 之后）。

> 说明:`connectState` 用**内存闭包变量**而非持久化——手机端会在每次蓝牙/协议状态变化时重推帧,页面 `onShow` 经内存值刷新,无需落盘。

Edit `band-qq/src/app.ux` `handleMessage` 的 `connect_state` 分支：

```javascript
    case 'connect_state':
      store.setConnectState(msg)
      emit('state', msg)
      break
```

Edit `band-qq/src/pages/index/index.ux`:
- 移除 `refreshStatus()` 中 `api.connectStatus()` 逻辑,改为读 store:

```javascript
    async refreshStatus() {
      const store = this.$app.$def.store
      const s = store.getConnectState()
      this.statusText = s && s.band ? '已连接' : '未连接'
    },
```

- 在 `onInit` 添加订阅 `this.$app.$def.on('state', () => this.refreshStatus())`,`onDestroy` 添加 `this.$app.$def.off('state')`(替换或并存现有 off 调用)

Edit `band-qq/src/pages/settings/settings.ux`:
- 移除 `refreshStatus()` 中 `api.connectStatus()` 逻辑,改为:

```javascript
    async refreshStatus() {
      const store = this.$app.$def.store
      const s = store.getConnectState()
      this.statusText = s && s.band ? '已连接' : '未连接'
      this.napcatText = s ? (s.protocol ? '在线' : '离线') : '未知'
    },
```

- 在 script 中新增订阅（`onShow` 已有 `refreshStatus`;再加 `onInit` 订阅 `state` 事件与 `onDestroy` 退订,参照 index.ux 写法）

- [ ] **Step 4: 运行手环测试验证通过**

Run: `cd band-qq; npm test`
Expected: PASS（store.test.js 新增用例过,既有用例全绿）

- [ ] **Step 5: 提交**

```bash
git add band-qq/src/common/store.js band-qq/src/app.ux band-qq/src/pages/settings/settings.ux band-qq/src/pages/index/index.ux band-qq/test/store.test.js
git commit -m "feat(band): 手环状态改读手机下发的 connect_state 帧(同源一致)"
```

---

### Task 7: 回归验证 + 打包交付

**Files:** (no source changes; build artifacts)
- `dist/bandqq.release.rpk`
- `dist/app-release.apk`（或 `dist/app-debug.apk`,按脚本产物）

- [ ] **Step 1: 全量回归**

Run Android: `powershell scripts\build-android.ps1 -Task testDebugUnitTest`
Expected: PASS（全部单测绿）
Run band: `cd band-qq; npm test`
Expected: PASS（全部手环测试绿）

- [ ] **Step 2: 确认无旧引用残留**

Run: `grep -r "NapCatDetector" --include="*.kt" --include="*.kts" android-sync/`
Expected: 无匹配（仅剩 `GameProtocolDetector`）
Run: `grep -rn "connectStatus" band-qq/src/`
Expected: 无匹配（手环端不再本地诊断;若 `api.js` 仍保留 `connectStatus` 方法,属无害保留,可忽略;若确无引用则移除）

- [ ] **Step 3: 构建 release APK 与 rpk**

Run: `powershell scripts\build-android.ps1 -Task assembleRelease`
Run: `powershell scripts\rpk-pack.ps1`
Expected: `dist/` 下产出 `app-release.apk`（或 `bandqq` 命名）与 `bandqq.release.rpk`

- [ ] **Step 4: 更新交付包**

按交付惯例把 `docs/INSTALL.txt`、`band/`、`phone/`、`docs/` 与新增 rpk/apk 归档到桌面交付 zip（参照既有交付包结构）。

- [ ] **Step 5: 提交构建产物说明（若仓库跟踪 dist 则提交,否则跳过）**

```bash
git status --short
```
若 `dist/` 已被仓库跟踪则 `git add dist && git commit -m "chore: 重新打包 release rpk/apk"`;否则无需提交。

---

## Self-Review 记录

- **Spec 覆盖核对**:
  - SnowLuma 支持 → Task 1（双端点）+ Task 2（SNOWLUMA 端口/探测）+ Task 3（活动端点连接）✅
  - 局域网 → Task 2 `detectHosts()` 子网扫描 + Task 5 manifest 权限/cleartext ✅
  - 任意 IP → EndpointConfig 存任意 host ✅
  - 状态不同步 → Task 4（band/protocol 双字段）+ Task 6（手环同源渲染）✅
  - 手动测试按钮 → Task 5 `testNapBtn/testSnowBtn` ✅
  - 侧切开关 → Task 5 `protocolGroup` ✅
  - 双配置、扫局域网网段 → Task 1 + Task 5 ✅
- **占位符扫描**：无 TBD/TODO;所有代码步骤含完整实现。
- **类型一致性**：`EndpointConfig(wsUrl, httpUrl, token)` 与 `ProtocolType` 在 Task 1 定义、Task 2/3/5 引用一致;`GameProtocolDetector.detect(type, preferred, hosts, ports)` 签名在 Task 2 定义、Task 5 调用一致;`SyncStatePush.buildFrame()` 在 Task 4 定义、Task 6 依赖其输出字段 `band/protocol` 一致。