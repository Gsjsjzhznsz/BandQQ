# 去除 NapCat,专一 SnowLuma(单端点) + 分离 WS/HTTP token 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 完全移除 NapCat,将 SnowLuma 降为唯一协议端并配置为单组(WS/HTTP 各独立地址与 token,HTTP 端口独立),精简 App 与手环端。

**Architecture:** 配置层 `AppConfig` 从「双端点 + activeType」降为单端点四字段 `EndpointConfig(wsUrl, wsToken, httpUrl, httpToken)`;探测层 `GameProtocolDetector` 去掉 `type` 参数只服务 SnowLuma,HTTP 按常见端口扫描;`OneBotClient` WS/HTTP 各用各 token;UI 只剩一组「SnowLuma 配置」并去掉侧切;手环端「NapCat」行标签改「协议端」。

**Tech Stack:** Kotlin Android + DataStore Preferences、OkHttp、QuickApp(Vela) JS。

## Global Constraints

- 仓库根 `C:\Users\wang\Desktop\band-qq`,git 命令在此执行。
- **Android 单测:** `powershell scripts\build-android.ps1 -Task testDebugUnitTest`(工作目录仓库根);期望全绿。
- **手环测试:** 工作目录 `C:\Users\wang\Desktop\band-qq\band-qq`,运行 `npm test`;期望全绿。
- **禁止触碰** `band-qq/src/common/icon.png`(有未提交改动,图标遗留,勿 git add)。
- **删除项必须全局清除引用**,编译期不得残留 `ProtocolType` / `getActiveEndpoint` / `.napcat` / `.snowluma` / `activeType` 的任何使用。
- `EndpointConfig` 新四字段签名(全计划统一):
  ```kotlin
  data class EndpointConfig(val wsUrl: String, val wsToken: String, val httpUrl: String, val httpToken: String)
  ```
- `AppConfig` 新签名(全计划统一):
  ```kotlin
  data class AppConfig(val endpoint: EndpointConfig = EndpointConfig("ws://127.0.0.1:3001", "", "http://127.0.0.1:3000", ""))
  ```
- 默认 HTTP 端口与 WS 端口独立(HTTP 默认 3000、WS 默认 3001)。

---

### Task 1: 重写配置数据层(AppConfig 单端点四字段 + 新键)

**Files:**
- Modify: `android-sync/app/src/main/java/com/example/bandqq/config/ConfigManager.kt`
- Rewrite: `android-sync/app/src/test/java/com/example/bandqq/config/AppConfigTest.kt`

**Interfaces:**
- Consumes: 无(Task 1 为数据层,独立可测)。
- Produces: `com.example.bandqq.config.EndpointConfig(wsUrl, wsToken, httpUrl, httpToken)`、`AppConfig(endpoint)`、`ConfigHolder.config: AppConfig`、`ConfigManager.load()/save(config)`。Task 3、Task 4 依赖这些。

- [ ] **Step 1: 重写 AppConfigTest 为单端点四字段预期**

用以下内容整体替换 `AppConfigTest.kt`:
```kotlin
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
```

- [ ] **Step 2: 运行测试确认失败**

Run(仓库根): `powershell scripts\build-android.ps1 -Task testDebugUnitTest`
Expected: 编译错误(新字段 `wsToken`/`httpToken`/`endpoint` 未定义)。

- [ ] **Step 3: 重写 ConfigManager.kt**

用以下内容整体替换 `ConfigManager.kt`:
```kotlin
package com.example.bandqq.config

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore(name = "sync_config")

data class EndpointConfig(
    val wsUrl: String,
    val wsToken: String,
    val httpUrl: String,
    val httpToken: String
)

data class AppConfig(
    val endpoint: EndpointConfig = EndpointConfig("ws://127.0.0.1:3001", "", "http://127.0.0.1:3000", "")
)

object ConfigHolder {
    var config: AppConfig = AppConfig()
}

class ConfigManager(private val context: Context) {

    private object Keys {
        val WS = stringPreferencesKey("ws_url")
        val WS_TOKEN = stringPreferencesKey("ws_token")
        val HTTP = stringPreferencesKey("http_url")
        val HTTP_TOKEN = stringPreferencesKey("http_token")
    }

    suspend fun load(): AppConfig {
        val prefs = context.dataStore.data.first()
        val default = AppConfig()
        val cfg = AppConfig(
            endpoint = EndpointConfig(
                wsUrl = prefs[Keys.WS] ?: default.endpoint.wsUrl,
                wsToken = prefs[Keys.WS_TOKEN] ?: "",
                httpUrl = prefs[Keys.HTTP] ?: default.endpoint.httpUrl,
                httpToken = prefs[Keys.HTTP_TOKEN] ?: ""
            )
        )
        ConfigHolder.config = cfg
        return cfg
    }

    suspend fun save(config: AppConfig) {
        context.dataStore.edit { prefs ->
            prefs[Keys.WS] = config.endpoint.wsUrl
            prefs[Keys.WS_TOKEN] = config.endpoint.wsToken
            prefs[Keys.HTTP] = config.endpoint.httpUrl
            prefs[Keys.HTTP_TOKEN] = config.endpoint.httpToken
        }
        ConfigHolder.config = config
    }
}
```
(旧键 `token`、`snowluma_*`、`active_type` 已废弃,不再读写;缺失用默认值兜底,不做数据迁移。)

- [ ] **Step 4: 运行测试确认通过**

Run: `powershell scripts\build-android.ps1 -Task testDebugUnitTest`
Expected: `AppConfigTest` 2 用例 PASS。此刻 `SyncService`/`MainActivity`/`ContactManagerActivity`/`GameProtocolDetector` 仍引用旧字段,整仓编译可能仍 FAIL——本任务仅要求 `AppConfigTest` 自身通过;整仓编译由后续任务逐层恢复。若需单独验证本任务:工作目录 `android-sync`,运行 `gradlew.bat :app:testDebugUnitTest --tests "com.example.bandqq.config.AppConfigTest"`。

- [ ] **Step 5: 提交**

```bash
git add android-sync/app/src/main/java/com/example/bandqq/config/ConfigManager.kt android-sync/app/src/test/java/com/example/bandqq/config/AppConfigTest.kt
git commit -m "refactor(config): AppConfig 降为单端点四字段(ws/http 各独立 token),删除 NapCat 枚举与旧键"
```

---

### Task 2: 探测层去 NapCat,适配无 type 四字段

**Files:**
- Modify: `android-sync/app/src/main/java/com/example/bandqq/onebot/GameProtocolDetector.kt`
- Rewrite: `android-sync/app/src/test/java/com/example/bandqq/onebot/GameProtocolDetectorTest.kt`

**Interfaces:**
- Consumes: `EndpointConfig(wsUrl, wsToken, httpUrl, httpToken)`、`AppConfig` 单端点(Task 1)。
- Produces:
  ```kotlin
  suspend fun detect(preferred: String? = null, hosts: List<String> = detectHosts(), ports: IntArray = defaultPorts()): EndpointConfig?
  fun defaultPorts(): IntArray
  fun detectHosts(): List<String>
  ```
  Task 4(MainActivity)调用 `detect(preferred = ..., hosts = ..., ports = ...)`;返回的端点 wsToken/httpToken 均为空,由 UI 回填。

- [ ] **Step 1: 重写 GameProtocolDetectorTest**

用以下内容整体替换 `GameProtocolDetectorTest.kt`:
```kotlin
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
```

- [ ] **Step 2: 运行测试确认失败**

Run(android-sync 工作目录): `gradlew.bat :app:testDebugUnitTest --tests "com.example.bandqq.onebot.GameProtocolDetectorTest"`
Expected: 编译错误或用例 FAIL。

- [ ] **Step 3: 重写 GameProtocolDetector.kt**

用以下内容整体替换 `GameProtocolDetector.kt`:
```kotlin
package com.example.bandqq.onebot

import com.example.bandqq.config.EndpointConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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

    fun defaultPorts(): IntArray = intArrayOf(3001, 3000, 8080, 3005, 5000)

    suspend fun detect(
        preferred: String? = null,
        hosts: List<String> = detectHosts(),
        ports: IntArray = defaultPorts()
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
                    sem.withPermit { if (isProtocol(url)) url else null }
                }
            }.awaitAll().filterNotNull()

            if (preferredUrl != null && results.contains(preferredUrl)) {
                toConfig(preferredUrl)
            } else {
                results.firstOrNull()?.let { toConfig(it) }
            }
        }
    }

    private fun isProtocol(base: String): Boolean {
        if (base.startsWith("ws://") || base.startsWith("wss://")) {
            return probeWs(base)
        }
        if (httpProbe(base)) return true
        // 无 HTTP 端点时,尝试把 http 换成 ws 握手
        val wsUrl = base.replaceFirst("http://", "ws://").replaceFirst("https://", "wss://")
        return wsUrl != base && probeWs(wsUrl)
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

    /** WebSocket 握手探测：onOpen 触发即判成功（连接建立后关闭）。 */
    private fun probeWs(wsUrl: String): Boolean {
        val latch = java.util.concurrent.CountDownLatch(1)
        val result = BooleanArray(1) { false }
        var wsRef: WebSocket? = null
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
            wsRef = ws
        } catch (e: Exception) {
            return false
        }
        latch.await(1, TimeUnit.SECONDS)
        wsRef?.cancel()
        return result[0]
    }

    private fun toConfig(base: String): EndpointConfig {
        if (base.startsWith("ws://") || base.startsWith("wss://")) {
            return EndpointConfig(wsUrl = base, wsToken = "", httpUrl = base, httpToken = "")
        }
        val trimmed = base.trim()
        return try {
            val isHttps = trimmed.lowercase().startsWith("https://")
            val scheme = if (isHttps) "wss" else "ws"
            val m = Regex("""^https?://([^:/]+)(?::(\d+))?""").find(trimmed)
            if (m == null) return EndpointConfig(wsUrl = trimmed, wsToken = "", httpUrl = base, httpToken = "")
            val host = m.groupValues[1]
            val httpPort = m.groupValues[2].ifBlank { if (isHttps) "443" else "80" }.toInt()
            EndpointConfig(wsUrl = "$scheme://$host:$httpPort", wsToken = "", httpUrl = base, httpToken = "")
        } catch (e: Exception) {
            EndpointConfig(wsUrl = trimmed, wsToken = "", httpUrl = base, httpToken = "")
        }
    }

    /** 探测目标主机：loopback + 本机所在子网。取不到网络信息时仅 loopback。 */
    fun detectHosts(): List<String> {
        val hosts = mutableListOf("127.0.0.1")
        return try {
            val nics = java.util.Collections.list(NetworkInterface.getNetworkInterfaces())
            for (nic in nics) {
                val addrs = java.util.Collections.list(nic.inetAddresses)
                for (addr in addrs) {
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
(说明:HTTP 与 WS 默认端口独立,`toConfig` 不再把 3000 推导成 3001;HTTP 端口按探测到为准填写 httpUrl,wsUrl 同探测 base 端口;实际 WS 端口由用户在 wsUrl 字段手填纠正,或经局域网探测的 WS 候选命中。)

- [ ] **Step 4: 运行测试确认通过**

Run(android-sync): `gradlew.bat :app:testDebugUnitTest --tests "com.example.bandqq.onebot.GameProtocolDetectorTest"`
Expected: 5 用例全 PASS。

- [ ] **Step 5: 提交**

```bash
git add android-sync/app/src/main/java/com/example/bandqq/onebot/GameProtocolDetector.kt android-sync/app/src/test/java/com/example/bandqq/onebot/GameProtocolDetectorTest.kt
git commit -m "refactor(onebot): GameProtocolDetector 去 NapCat,detect 去掉 type 参数,适配四字段端点"
```

---

### Task 3: 连接与同步服务接入单端点四字段

**Files:**
- Modify: `android-sync/app/src/main/java/com/example/bandqq/onebot/OneBotClient.kt`
- Modify: `android-sync/app/src/main/java/com/example/bandqq/sync/SyncService.kt`
- Modify: `android-sync/app/src/main/java/com/example/bandqq/ContactManagerActivity.kt`

**Interfaces:**
- Consumes: `EndpointConfig(wsUrl, wsToken, httpUrl, httpToken)`、`AppConfig(endpoint)`(Task 1)。
- Produces:
  - `OneBotClient.start(config: EndpointConfig, listener)`(签名不变,改用四字段)。
  - 行为约定:WS 握手用 `config.wsToken`;`sendMessage()` 与 `requestApi()` 用 `config.httpToken`。
  - `SyncService`: 用 `configManager.load()` 返回的 `AppConfig` 的 `.endpoint` 调 `oneBot.start(...)`。
  - `ContactManagerActivity`: 用 `ConfigHolder.config.endpoint.httpUrl` 调 `requestApi`。

- [ ] **Step 1: 修改 OneBotClient.kt 使 WS/HTTP 各用各 token**

在 `OneBotClient.kt` 做 3 处修改(其余不动):
1. `connectOnce()` 的 WS 请求(约 :81-84):
   ```kotlin
   val builder = Request.Builder().url(config.wsUrl)
   if (config.wsToken.isNotBlank()) {
       builder.header("Authorization", "Bearer ${config.wsToken}")
   }
   val req = builder.build()
   ```
2. `sendMessage()`(约 :123)Authorization 头:改为 `if (config.httpToken.isNotBlank()) header("Authorization", "Bearer ${config.httpToken}")`。
3. `requestApi()` 的 `doRequest`(约 :149)Authorization 头:改为 `if (config.httpToken.isNotBlank()) header("Authorization", "Bearer ${config.httpToken}")`。

- [ ] **Step 2: 修改 SyncService.kt 使用单端点**

- 删除 import `com.example.bandqq.config.getActiveEndpoint`(约 :12)。
- 修改 `onStartCommand` 的 `scope.launch` 块(约 :107-108):
  ```kotlin
  val config = configManager.load()
  oneBot.start(config.endpoint, broker)
  ```

- [ ] **Step 3: 修改 ContactManagerActivity.kt 使用单端点**

- 删除 import `com.example.bandqq.config.getActiveEndpoint`(约 :14)。
- 修改 `loadContacts()`(约 :56):
  ```kotlin
  val http = ConfigHolder.config.endpoint.httpUrl
  ```

- [ ] **Step 4: 编译 + 相关测试验证**

Task 4 之前 `MainActivity` 仍引用旧字段,整仓 `assembleDebug` 仍 FAIL。本步骤只验证 OneBotClient/SyncService/ContactManager 相关测试。

Run(android-sync): `gradlew.bat :app:testDebugUnitTest --tests "com.example.bandqq.onebot.*" --tests "com.example.bandqq.sync.*"`
Expected: 相关测试编译通过并 PASS。若 `OneBotClientTest` 或任何被本任务触及的测试用旧 `EndpointConfig(a,b,c)` 3 参构造,必须改为 4 参 `EndpointConfig(a, "", b, "")`(第2参 wsToken、第4参 httpToken)。逐个修正后重新运行直到 PASS。

- [ ] **Step 5: 提交**

```bash
git add android-sync/app/src/main/java/com/example/bandqq/onebot/OneBotClient.kt android-sync/app/src/main/java/com/example/bandqq/sync/SyncService.kt android-sync/app/src/main/java/com/example/bandqq/ContactManagerActivity.kt
git commit -m "refactor(onebot,sync): WS/HTTP 分用 token,同步服务与联系人管理用单端点"
```

---

### Task 4: MainActivity 与 UI 改为单组 SnowLuma 配置

**Files:**
- Modify: `android-sync/app/src/main/res/layout/activity_main.xml`
- Rewrite: `android-sync/app/src/main/java/com/example/bandqq/MainActivity.kt`

**Interfaces:**
- Consumes: `AppConfig(endpoint)`/`ConfigHolder.config`、`EndpointConfig` 四字段、`ConfigManager.load()/save()`(Task 1);`GameProtocolDetector.detect(preferred, hosts, ports)`(Task 2)。
- Produces: 完整可用的 MainActivity(整仓编译恢复)。Task 5 `testDebugUnitTest` 依赖此恢复整仓编译。

- [ ] **Step 1: 重写 activity_main.xml 配置区**

保留 `<ScrollView><LinearLayout>` 外壳、`statusText`、以及 `startBtn` 后的所有控件(`checkBandBtn`/`stopBtn`/`chatHistoryBtn`/`contactManagerBtn`/`clearHistoryBtn`/`logView`)。将配置区(现「当前协议端」起到 `testSnowBtn`)整体替换为:
```xml
        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="SnowLuma 配置"
            android:paddingTop="12dp" />

        <EditText
            android:id="@+id/wsInput"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:hint="WS 地址 ws://127.0.0.1:3001" />

        <EditText
            android:id="@+id/wsTokenInput"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:hint="WS Access Token (可选)" />

        <EditText
            android:id="@+id/httpInput"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:hint="HTTP 地址 http://127.0.0.1:3000" />

        <EditText
            android:id="@+id/httpTokenInput"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:hint="HTTP Access Token (可选)" />

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
            android:id="@+id/testBtn"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="测试 SnowLuma 连接"
            android:paddingTop="8dp" />
```
(删除:`protocolGroup`/`napcatRadio`/`snowlumaRadio`、NapCat 组、`snow*` 组、`testNapBtn`/`testSnowBtn`;`wsInput`/`httpInput` id 复用。)

- [ ] **Step 2: 重写 MainActivity.kt**

用以下内容整体替换 `MainActivity.kt`:
```kotlin
package com.example.bandqq

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.bandqq.config.AppConfig
import com.example.bandqq.config.ConfigManager
import com.example.bandqq.config.ConfigHolder
import com.example.bandqq.config.EndpointConfig
import com.example.bandqq.databinding.ActivityMainBinding
import com.example.bandqq.onebot.GameProtocolDetector
import com.example.bandqq.sync.BandStateBus
import com.example.bandqq.sync.InterconnectBridge
import com.example.bandqq.sync.SyncService
import com.example.bandqq.sync.SyncState
import com.example.bandqq.sync.StoreHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var configManager: ConfigManager
    private val scope = CoroutineScope(Dispatchers.Main)

    private val stateListener: (Boolean) -> Unit = { _ -> refreshStatus() }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configManager = ConfigManager(this)

        BandStateBus.add(stateListener)
        requestPermissions()
        loadConfig()
        bindButtons()
    }

    override fun onDestroy() {
        BandStateBus.remove(stateListener)
        super.onDestroy()
    }

    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val perms = arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(perms)
            }
        }
    }

    private fun loadConfig() {
        scope.launch {
            val config = configManager.load()
            binding.wsInput.setText(config.endpoint.wsUrl)
            binding.wsTokenInput.setText(config.endpoint.wsToken)
            binding.httpInput.setText(config.endpoint.httpUrl)
            binding.httpTokenInput.setText(config.endpoint.httpToken)
            refreshStatus()
        }
    }

    private fun bindButtons() {
        binding.saveBtn.setOnClickListener {
            scope.launch {
                val cfg = AppConfig(
                    EndpointConfig(
                        wsUrl = binding.wsInput.text.toString().trim(),
                        wsToken = binding.wsTokenInput.text.toString().trim(),
                        httpUrl = binding.httpInput.text.toString().trim(),
                        httpToken = binding.httpTokenInput.text.toString().trim()
                    )
                )
                configManager.save(cfg)
                Toast.makeText(this@MainActivity, "配置已保存", Toast.LENGTH_SHORT).show()
                refreshStatus()
            }
        }

        binding.probeBtn.setOnClickListener { probe() }

        binding.testBtn.setOnClickListener { testConnection() }

        binding.startBtn.setOnClickListener {
            SyncService.start(this)
            toast("同步服务已启动")
            refreshStatus()
        }

        binding.stopBtn.setOnClickListener {
            SyncService.stop(this)
            toast("同步服务已停止")
            refreshStatus()
        }

        binding.checkBandBtn.setOnClickListener {
            checkBand()
        }

        binding.chatHistoryBtn.setOnClickListener {
            startActivity(Intent(this, ChatHistoryActivity::class.java))
        }

        binding.contactManagerBtn.setOnClickListener {
            startActivity(Intent(this, ContactManagerActivity::class.java))
        }

        binding.clearHistoryBtn.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("清空全部聊天记录")
                .setMessage("确定要清空手机端保存的全部聊天记录吗？\n（将同步清空手环端缓存）")
                .setPositiveButton("清空") { _, _ ->
                    StoreHolder.store?.clearAllHistory()
                    InterconnectBridge.sendToBand("""{"type":"clear_all_history","seq":0}""")
                    toast("聊天记录已清空")
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun probe() {
        scope.launch {
            binding.statusText.text = "状态：正在局域网探测 SnowLuma..."
            val detected = GameProtocolDetector.detect()
            if (detected != null) {
                binding.wsInput.setText(detected.wsUrl)
                binding.httpInput.setText(detected.httpUrl)
                // 保留用户已填的 token,不覆盖
                val cfg = ConfigHolder.config.copy(
                    endpoint = detected.copy(
                        wsToken = binding.wsTokenInput.text.toString().trim(),
                        httpToken = binding.httpTokenInput.text.toString().trim()
                    )
                )
                configManager.save(cfg)
                binding.statusText.text = "状态：SnowLuma 在线（${detected.httpUrl}）"
                toast("已探测到 SnowLuma,配置已保存")
            } else {
                binding.statusText.text = "状态：未检测到 SnowLuma,请检查协议端是否已启动"
            }
        }
    }

    private fun testConnection() {
        scope.launch {
            binding.statusText.text = "状态：正在测试 SnowLuma..."
            val wsText = binding.wsInput.text.toString().trim()
            val httpText = binding.httpInput.text.toString().trim()
            val cfg = GameProtocolDetector.detect(
                preferred = httpText.ifBlank { wsText },
                hosts = listOf("127.0.0.1"),
                ports = intArrayOf()
            )
            if (cfg != null) {
                binding.wsInput.setText(cfg.wsUrl)
                binding.httpInput.setText(cfg.httpUrl)
                binding.statusText.text = "状态：SnowLuma 在线"
                toast("SnowLuma 连接正常")
            } else {
                binding.statusText.text = "状态：SnowLuma 连接失败"
                toast("连接失败,请检查协议端是否已启动")
            }
        }
    }

    private fun refreshStatus() {
        binding.statusText.text = when {
            SyncState.oneBotConnected && SyncState.bandConnected -> "状态：互联已连接,SnowLuma 在线"
            SyncState.oneBotConnected -> "状态：SnowLuma 在线,等待手环连接"
            SyncState.bandConnected -> "状态：手环已连接,等待 SnowLuma"
            else -> "状态：未连接（请启动同步服务）"
        }
    }

    /** 检查手环互联：主动触发 SDK 连接（若已连接会触发授权/拉起手环应用） */
    private fun checkBand() {
        binding.statusText.text = "状态：正在检查手环连接..."
        if (SyncState.bandConnected) {
            toast("手环已连接")
            refreshStatus()
            return
        }
        InterconnectBridge.connect()
        toast("已发起手环连接检查，请留意运动健康的授权提示")
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }
}
```
(移除 `ProtocolType` import 及 NapCat/SnowLuma 分支;`AppConfig` import 现真实使用;若编译报未使用 import 一并删除。)

- [ ] **Step 3: 整仓编译 + 全量单测验证**

Run(仓库根): `powershell scripts\build-android.ps1 -Task testDebugUnitTest`
Expected: **整仓 BUILD SUCCESSFUL**,全部单测绿(含 Task 1/2 改动)。

- [ ] **Step 4: 确认无 NapCat/protocol 残留引用**

Run(仓库根): `& "C:\Program Files\Git\bin\bash.exe" -c "grep -rn 'NapCat\|ProtocolType\|getActiveEndpoint\|\.napcat\|\.snowluma\|activeType' android-sync/app/src/"`
Expected: 无匹配(android-sync 内 NapCat/旧结构彻底清除)。注:手环端 settings.ux 的「NapCat」字样由 Task 5 处理。

- [ ] **Step 5: 提交**

```bash
git add android-sync/app/src/main/res/layout/activity_main.xml android-sync/app/src/main/java/com/example/bandqq/MainActivity.kt
git commit -m "feat(ui): MainActivity 改为单组 SnowLuma 配置(ws/http 独立 token),去除 NapCat 与侧切"
```

---

### Task 5: 手环端协议行改名 + 全量回归

**Files:**
- Modify: `band-qq/src/pages/settings/settings.ux`
- Test: `band-qq/test`(不改测试文件,仅运行 `npm test` 稳绿)

**Interfaces:**
- Consumes: 无新依赖;沿用已建立的手环端 `store.getConnectState()` 与 `protocol` 状态帧。

- [ ] **Step 1: 修改 settings.ux 协议行标签与变量名**

在 `band-qq/src/pages/settings/settings.ux` 做 3 处修改:
1. 模板(约 :15):`<text class="row-label">NapCat</text>` → `<text class="row-label">协议端</text>`。
2. 模板(约 :16):`{{napcatText}}` → `{{protocolText}}`。
3. 脚本:
   - private(约 :65): `napcatText: '未知',` → `protocolText: '未知',`
   - refreshStatus(约 :84): `this.napcatText = s ? (s.protocol ? '在线' : '离线') : '未知'` → `this.protocolText = s ? (s.protocol ? '在线' : '离线') : '未知'`

- [ ] **Step 2: 运行手环测试**

Run(工作目录 `C:\Users\wang\Desktop\band-qq\band-qq`): `npm test`
Expected: 全绿。

- [ ] **Step 3: 确认手环端无 NapCat 残留**

Run(仓库根): `& "C:\Program Files\Git\bin\bash.exe" -c "grep -rn 'NapCat\|napcat' band-qq/src/"`
Expected: 无匹配。

- [ ] **Step 4: 全量回归**

Run(仓库根): `powershell scripts\build-android.ps1 -Task testDebugUnitTest` → 全绿。
Run(工作目录 band-qq): `npm test` → 全绿。

- [ ] **Step 5: 提交**

```bash
git add band-qq/src/pages/settings/settings.ux
git commit -m "feat(band): settings 协议端行改名为「协议端」,去除 NapCat 字样"
```

---

### Task 6: 构建产物 + 更新交付文档与 zip

**Files:**
- Build: `dist/app-debug.apk`、`dist/app-release.apk`、`dist/bandqq.release.rpk`
- Docs: 桌面交付 zip(含重写 INSTALL.txt)

**Interfaces:**
- Consumes: Task 1-5 全部改动(源码已定)。
- Produces: 可安装交付物;INSTALL.txt 反映「仅 SnowLuma、WS/HTTP 独立 token、HTTP 独立端口」。

- [ ] **Step 1: 回归全绿确认**

Run: Android `powershell scripts\build-android.ps1 -Task testDebugUnitTest`;band(工作目录 band-qq) `npm test`。均须全绿。

- [ ] **Step 2: 构建 release 与 debug APK**

Run(仓库根): `powershell scripts\build-android.ps1 -Task assembleRelease` 与 `-Task assembleDebug`
Expected: `dist/app-release.apk`、`dist/app-debug.apk` 更新(时间戳为本次)。

- [ ] **Step 3: 打包 rpk**

Run(仓库根): `powershell scripts\rpk-pack.ps1`
Expected: `dist/bandqq.release.rpk` 更新。

- [ ] **Step 4: 重写交付 INSTALL.txt 并归档新 zip**

参照既有 v7 zip 结构(`INSTALL.txt` + `band/bandqq.release.rpk` + `docs/{napcat-usage.txt, signing.txt}` + `phone/{app-debug.apk, app-release.apk}`),重写根级 `INSTALL.txt`,如实反映新版:
- **仅支持 SnowLuma**(已移除 NapCat),协议端配置为**单组**。
- WS 地址与 HTTP 地址为**独立端口**,且各自有**独立的 Access Token**(WS token 用于收消息、HTTP token 用于发消息/拉联系人)。
- 保留「自动探测(局域网)」「测试」按钮与包名 `com.example.bandqq`、同证书签名说明。
- 版本号递增(保留 v7 或标注本次为 SnowLuma-only 修订)。

生成新桌面 zip(用 PowerShell `Compress-Archive`),命名递增(如 `QQ手环消息接收器_v8_<HHmmss>.zip`),用临时目录 `C:\Users\wang\AppData\Local\Temp\opencode\deliver-snowluma-only\` 按结构放好再压缩;产出到桌面 `C:\Users\wang\Desktop\` 并删除上一个 v7 zip(避免混淆)。

- [ ] **Step 5: 确认 git 状态并收尾**

Run(仓库根): `git status --short`
Expected: 仅 `band-qq/src/common/icon.png` 遗留(未提交,勿 add);dist/ 未被跟踪无需提交;本次无源码改动不需新提交。

---

## Self-Review 记录

- **Spec 覆盖**:
  - 移除 NapCat(代码/UI/枚举/键/测试/文档)→ Task 1(枚举+嵌套字段删)、Task 2(探测 NapCat 分支删)、Task 4(grep 验证 android-sync 无残留)、Task 5(手环端 NapCat 字样删)、Task 6(INSTALL 更新)✓
  - SnowLuma 唯一端点四字段 → Task 1 `EndpointConfig(ws,wsToken,http,httpToken)` + `AppConfig(endpoint)` ✓
  - HTTP 独立端口 → Task 1 默认(`ws://...:3001` vs `http://...:3000`)、Task 2 `toConfig` 不再 3000→3001 推导 ✓
  - 探测扫常见 HTTP 端口 → Task 2 `defaultPorts()` = {3001,3000,8080,3005,5000} ✓
  - WS/HTTP 各用各 token → Task 3 `wsToken`(WS)、`httpToken`(HTTP)✓
  - UI 单组 + 去侧切 → Task 4 `activity_main.xml` + `MainActivity` ✓
  - 手环端「协议端」→ Task 5 ✓
  - 交付 zip → Task 6 ✓
- **占位符**:无 TBD/TODO;所有代码步骤含完整实现。
- **类型一致性**:`EndpointConfig(wsUrl, wsToken, httpUrl, httpToken)` 在 Task 1 定义、Task 2/3/4 引用一致;`GameProtocolDetector.detect(preferred, hosts, ports)` 在 Task 2 定义、Task 4 调用一致;`AppConfig(endpoint)` 在 Task 1 定义、Task 3/4 引用一致。