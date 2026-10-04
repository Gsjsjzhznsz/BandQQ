# Android 同步器实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 Android 同步器 App（android-sync/），通过蓝牙互联接收手环数据、通过 OneBot 协议连接手机本机 NapCat，双向转发 QQ 消息。

**Architecture:** Kotlin 单模块 App。`SyncService` 前台服务持有互联桥与 OneBot 客户端；`MessageBroker` 负责双向协议映射；`MainActivity` 提供配置界面与日志；OkHttp 实现 WS 事件订阅与 HTTP API。默认连接 NapCat 正向 WS `ws://127.0.0.1:3001`。

**Tech Stack:** Kotlin、Android Gradle Plugin 8.x、OkHttp 4.12、WebSocket、DataStore（配置）、协程。单元测试 JUnit4 + MockWebServer。

## Global Constraints

- `applicationId = com.example.bandqq`，与手环端 manifest `package` 一致（互联硬性要求）。
- `minSdk = 26`，`targetSdk = 34`。
- NapCat 默认地址：WS `ws://127.0.0.1:3001`，HTTP `http://127.0.0.1:3000`，可在设置页修改。
- 非文本消息段（CQ 码 image/record/video/file）降级为 `[图片]`/`[语音]`/`[视频]`/`[文件]`。
- 所有 `target_id`/`sender_id`/`group_id`/`user_id` 以字符串传输（避免 JS 大整数精度）。
- 前台服务 + 常驻通知栏；引导用户关闭电池优化。
- 代码注释使用中文。
- 本机无 Android SDK：本计划的 Gradle 编译验证需在装有 Android Studio 的机器上执行；纯 JVM 单元测试逻辑在计划中完整给出，可在 Android Studio 中运行 `gradlew :app:testDebugUnitTest` 验证。

---

### Task 1: Gradle 工程骨架

**Files:**
- Create: `android-sync/settings.gradle.kts`
- Create: `android-sync/build.gradle.kts`
- Create: `android-sync/gradle.properties`
- Create: `android-sync/app/build.gradle.kts`
- Create: `android-sync/app/src/main/AndroidManifest.xml`
- Create: `android-sync/gradle/wrapper/gradle-wrapper.properties`

**Interfaces:**
- Produces: 可编译的 Android 工程，`applicationId com.example.bandqq`。

- [ ] **Step 1: 创建 settings.gradle.kts**

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "android-sync"
include(":app")
```

- [ ] **Step 2: 创建根 build.gradle.kts**

```kotlin
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
```

- [ ] **Step 3: 创建 gradle.properties**

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
android.nonTransitiveRClass=true
```

- [ ] **Step 4: 创建 app/build.gradle.kts**

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.bandqq"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.bandqq"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
```

- [ ] **Step 5: 创建 gradle/wrapper/gradle-wrapper.properties**

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.7-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

- [ ] **Step 6: 创建 AndroidManifest.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />

    <application
        android:label="QQ同步器"
        android:theme="@style/Theme.AppCompat.DayNight">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name=".sync.SyncService"
            android:exported="false"
            android:foregroundServiceType="connectedDevice" />
    </application>
</manifest>
```

- [ ] **Step 7: 提交**

```bash
git add android-sync/
git commit -m "feat(android): Gradle 工程骨架与 manifest"
```

---

### Task 2: 配置管理 ConfigManager

**Files:**
- Create: `android-sync/app/src/main/java/com/example/bandqq/config/ConfigManager.kt`

**Interfaces:**
- Produces:
  - `data class AppConfig(wsUrl: String, httpUrl: String, token: String)`
  - `class ConfigManager(context: Context)`，方法：
    - `suspend fun load(): AppConfig`
    - `suspend fun save(config: AppConfig)`
  - 单例 `object ConfigHolder { var config: AppConfig }`（进程内当前配置，供非挂起上下文读取）。

- [ ] **Step 1: 实现 ConfigManager.kt**

```kotlin
package com.example.bandqq.config

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore(name = "sync_config")

data class AppConfig(
    val wsUrl: String = "ws://127.0.0.1:3001",
    val httpUrl: String = "http://127.0.0.1:3000",
    val token: String = ""
)

object ConfigHolder {
    var config: AppConfig = AppConfig()
}

class ConfigManager(private val context: Context) {

    private object Keys {
        val WS = stringPreferencesKey("ws_url")
        val HTTP = stringPreferencesKey("http_url")
        val TOKEN = stringPreferencesKey("token")
    }

    suspend fun load(): AppConfig {
        val prefs = context.dataStore.data.first()
        val cfg = AppConfig(
            wsUrl = prefs[Keys.WS] ?: AppConfig().wsUrl,
            httpUrl = prefs[Keys.HTTP] ?: AppConfig().httpUrl,
            token = prefs[Keys.TOKEN] ?: ""
        )
        ConfigHolder.config = cfg
        return cfg
    }

    suspend fun save(config: AppConfig) {
        context.dataStore.edit { prefs ->
            prefs[Keys.WS] = config.wsUrl
            prefs[Keys.HTTP] = config.httpUrl
            prefs[Keys.TOKEN] = config.token
        }
        ConfigHolder.config = config
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add android-sync/app/src/main/java/com/example/bandqq/config/ConfigManager.kt
git commit -m "feat(android): 配置管理 ConfigManager"
```

---

### Task 3: OneBot 协议解析 OneBotParser

**Files:**
- Create: `android-sync/app/src/main/java/com/example/bandqq/onebot/OneBotParser.kt`
- Test: `android-sync/app/src/test/java/com/example/bandqq/onebot/OneBotParserTest.kt`

**Interfaces:**
- Produces:
  - `data class OneBotMessage(messageType: String, targetId: String, senderId: String, senderName: String, content: String, time: Long)`
  - `class OneBotParser`，方法：
    - `fun parseMessageEvent(json: String): OneBotMessage?` — 解析 OneBot v11 `message` 事件。
    - `fun toHandBandFrame(msg: OneBotMessage): String` — 转手环 `push_message` JSON。
    - `fun buildSendRequest(messageType: String, targetId: String, content: String): String` — 构造 HTTP POST body。
  - `degradeContent` 作为 parser 内部函数：CQ 码 `[CQ:image,...]`/`[CQ:record]`/`[CQ:video]`/`[CQ:file]` → 对应文字标签。

- [ ] **Step 1: 写失败测试**

`android-sync/app/src/test/java/com/example/bandqq/onebot/OneBotParserTest.kt`:
```kotlin
package com.example.bandqq.onebot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OneBotParserTest {

    private val parser = OneBotParser()

    @Test
    fun `解析群消息事件`() {
        val json = """
            {"post_type":"message","message_type":"group","group_id":"123","user_id":"456",
             "sender":{"nickname":"张三"},"message":[{"type":"text","data":{"text":"你好"}}],
             "time":1700000000,"self_id":1,"message_id":2}
        """.trimIndent()
        val msg = parser.parseMessageEvent(json)
        assertEquals("group", msg?.messageType)
        assertEquals("123", msg?.targetId)
        assertEquals("456", msg?.senderId)
        assertEquals("张三", msg?.senderName)
        assertEquals("你好", msg?.content)
        assertEquals(1700000000L, msg?.time)
    }

    @Test
    fun `解析私聊消息事件`() {
        val json = """
            {"post_type":"message","message_type":"private","user_id":"789",
             "sender":{"nickname":"李四"},"message":[{"type":"text","data":{"text":"在吗"}}],
             "time":1700000001,"self_id":1,"message_id":3}
        """.trimIndent()
        val msg = parser.parseMessageEvent(json)
        assertEquals("private", msg?.messageType)
        assertEquals("789", msg?.targetId)
    }

    @Test
    fun `非文本段降级`() {
        val json = """
            {"post_type":"message","message_type":"group","group_id":"123","user_id":"456",
             "sender":{"nickname":"张三"},
             "message":[{"type":"text","data":{"text":"图:"}},
                        {"type":"image","data":{"file":"a.png"}},
                        {"type":"text","data":{"text":"。"}}],
             "time":1700000000,"self_id":1,"message_id":2}
        """.trimIndent()
        val msg = parser.parseMessageEvent(json)
        assertEquals("图:[图片]。", msg?.content)
    }

    @Test
    fun `非消息事件返回 null`() {
        val json = """{"post_type":"meta_event","meta_event_type":"heartbeat"}"""
        assertNull(parser.parseMessageEvent(json))
    }

    @Test
    fun `构建发送请求体`() {
        val body = parser.buildSendRequest("group", "123", "收到")
        assertEquals("""{"action":"send_group_msg","params":{"group_id":123,"message":"收到"}}""", body)
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

运行（Android Studio 中）：`./gradlew :app:testDebugUnitTest`
预期：FAIL（类不存在 / 编译失败）。

- [ ] **Step 3: 实现 OneBotParser.kt**

```kotlin
package com.example.bandqq.onebot

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class OneBotMessage(
    val messageType: String,  // "private" | "group"
    val targetId: String,     // group_id 或 user_id
    val senderId: String,
    val senderName: String,
    val content: String,
    val time: Long
)

class OneBotParser {

    fun parseMessageEvent(json: String): OneBotMessage? {
        val obj = try {
            JsonParser.parseString(json).asJsonObject
        } catch (e: Exception) {
            return null
        }
        if (obj.get("post_type")?.asString != "message") return null
        val messageType = obj.get("message_type")?.asString ?: return null
        val sender = obj.getAsJsonObject("sender")
        val senderId = obj.get("user_id")?.asLong?.toString() ?: return null
        val targetId = when (messageType) {
            "group" -> obj.get("group_id")?.asLong?.toString()
            "private" -> senderId
            else -> return null
        } ?: return null
        val content = degradeContent(obj.get("message"))
        return OneBotMessage(
            messageType = messageType,
            targetId = targetId,
            senderId = senderId,
            senderName = sender?.get("nickname")?.asString ?: senderId,
            content = content,
            time = obj.get("time")?.asLong ?: 0L
        )
    }

    fun degradeContent(message: com.google.gson.JsonElement?): String {
        if (message == null) return ""
        if (message.isJsonPrimitive && message.asJsonPrimitive.isString) return message.asString
        if (!message.isJsonArray) return ""
        val arr: JsonArray = message.asJsonArray
        val sb = StringBuilder()
        for (elem in arr) {
            val seg = if (elem.isJsonObject) elem.asJsonObject else continue
            when (seg.get("type")?.asString) {
                "text" -> {
                    val text = seg.getAsJsonObject("data")?.get("text")?.asString ?: ""
                    sb.append(text)
                }
                "image" -> sb.append("[图片]")
                "record", "voice" -> sb.append("[语音]")
                "video" -> sb.append("[视频]")
                "file" -> sb.append("[文件]")
                else -> sb.append("[其他]")
            }
        }
        return sb.toString()
    }

    fun toHandBandFrame(msg: OneBotMessage): String {
        val obj = JsonObject()
        obj.addProperty("type", "push_message")
        obj.addProperty("seq", 0)
        obj.addProperty("message_type", msg.messageType)
        obj.addProperty("target_id", msg.targetId)
        obj.addProperty("sender_id", msg.senderId)
        obj.addProperty("sender_name", msg.senderName)
        obj.addProperty("content", msg.content)
        obj.addProperty("time", msg.time)
        return obj.toString()
    }

    fun buildSendRequest(messageType: String, targetId: String, content: String): String {
        val body = JsonObject()
        val params = JsonObject()
        if (messageType == "group") {
            body.addProperty("action", "send_group_msg")
            params.addProperty("group_id", targetId.toLongOrNull() ?: targetId)
        } else {
            body.addProperty("action", "send_private_msg")
            params.addProperty("user_id", targetId.toLongOrNull() ?: targetId)
        }
        params.addProperty("message", content)
        body.add("params", params)
        return body.toString()
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

运行（Android Studio 中）：`./gradlew :app:testDebugUnitTest`
预期：OneBotParserTest 全部 PASS。

- [ ] **Step 5: 提交**

```bash
git add android-sync/app/src/main/java/com/example/bandqq/onebot/OneBotParser.kt android-sync/app/src/test/
git commit -m "feat(android): OneBot 协议解析 OneBotParser"
```

---

### Task 4: OneBot 客户端 OneBotClient

**Files:**
- Create: `android-sync/app/src/main/java/com/example/bandqq/onebot/OneBotClient.kt`
- Test: `android-sync/app/src/test/java/com/example/bandqq/onebot/OneBotClientTest.kt`

**Interfaces:**
- Consumes: `OneBotParser`、`AppConfig`。
- Produces:
  - `class OneBotClient(parser: OneBotParser)`，方法：
    - `fun start(config: AppConfig, listener: Listener)` — 连接 WS。
    - `fun stop()`
    - `fun sendMessage(messageType: String, targetId: String, content: String, callback: (Boolean) -> Unit)` — HTTP POST。
    - `fun isConnected(): Boolean`
  - `interface Listener { fun onEvent(oneBotMessage: OneBotMessage); fun onState(connected: Boolean) }`

- [ ] **Step 1: 写失败测试（MockWebServer 验证 HTTP 发送）**

`android-sync/app/src/test/java/com/example/bandqq/onebot/OneBotClientTest.kt`:
```kotlin
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
        assertTrue(request.body.readUtf8().contains("send_group_msg"))
        assertTrue(ok)
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

运行（Android Studio 中）：`./gradlew :app:testDebugUnitTest`
预期：FAIL（类不存在）。

- [ ] **Step 3: 实现 OneBotClient.kt**

```kotlin
package com.example.bandqq.onebot

import android.util.Log
import com.example.bandqq.config.AppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

interface OneBotListener {
    fun onEvent(message: OneBotMessage)
    fun onState(connected: Boolean)
}

class OneBotClient(private val parser: OneBotParser) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var reconnectJob: Job? = null
    private var ws: WebSocket? = null
    private var config: AppConfig = AppConfig()
    private var listener: OneBotListener? = null
    @Volatile private var connected = false

    fun start(config: AppConfig, listener: OneBotListener) {
        this.config = config
        this.listener = listener
        reconnect()
    }

    fun stop() {
        reconnectJob?.cancel()
        scope.cancel()
        ws?.close(1000, "stopped")
        ws = null
        connected = false
    }

    fun isConnected(): Boolean = connected

    private fun reconnect() {
        reconnectJob = scope.launch {
            while (isActive) {
                if (!connected) {
                    try {
                        connectOnce()
                    } catch (e: Exception) {
                        Log.w("OneBotClient", "connect failed", e)
                    }
                    delay(5000)
                } else {
                    delay(1000)
                }
            }
        }
    }

    private fun connectOnce() {
        val builder = Request.Builder().url(config.wsUrl)
        if (config.token.isNotBlank()) {
            builder.header("Authorization", "Bearer ${config.token}")
        }
        val req = builder.build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true
                listener?.onState(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = parser.parseMessageEvent(text)
                if (msg != null) listener?.onEvent(msg)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {}

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                listener?.onState(false)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                listener?.onState(false)
            }
        })
    }

    fun sendMessage(
        messageType: String,
        targetId: String,
        content: String,
        httpUrlOverride: String? = null,
        callback: (Boolean) -> Unit = {}
    ) {
        val baseUrl = httpUrlOverride ?: config.httpUrl
        val body = parser.buildSendRequest(messageType, targetId, content)
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/send_msg")
            .post(body.toRequestBody("application/json".toMediaTypeOrNull()))
            .apply { if (config.token.isNotBlank()) header("Authorization", "Bearer ${config.token}") }
            .build()
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                Log.e("OneBotClient", "send failed", e)
                callback(false)
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    callback(it.isSuccessful)
                }
            }
        })
    }
}

private fun String.toMediaTypeOrNull() = "application/json; charset=utf-8".toMediaType()

fun String.toMediaType(): okhttp3.MediaType {
    return okhttp3.MediaType.parse(this)!!
}
```

- [ ] **Step 4: 运行测试验证通过**

运行（Android Studio 中）：`./gradlew :app:testDebugUnitTest`
预期：OneBotClientTest 全部 PASS。

- [ ] **Step 5: 提交**

```bash
git add android-sync/app/src/main/java/com/example/bandqq/onebot/OneBotClient.kt
git commit -m "feat(android): OneBot WS/HTTP 客户端"
```

---

### Task 5: 消息存储 MessageStore

**Files:**
- Create: `android-sync/app/src/main/java/com/example/bandqq/sync/MessageStore.kt`
- Test: `android-sync/app/src/test/java/com/example/bandqq/sync/MessageStoreTest.kt`

**Interfaces:**
- Consumes: 无（纯内存实现，后续可换持久化后端）。
- Produces:
  - `data class StoredMessage(messageType: String, senderId: String, senderName: String, content: String, time: Long)`
  - `data class ConversationInfo(id: String, type: String, name: String, lastMsg: String, time: Long)`
  - `class MessageStore`：
    - `fun addMessage(targetId: String, msg: StoredMessage)` — 落库并更新会话。
    - `fun getHistory(targetId: String, limit: Int): List<StoredMessage>` — 最近 limit 条。
    - `fun getConversations(): List<ConversationInfo>` — 会话列表（按时间倒序）。
    - `fun buildHistoryFrame(targetId: String, limit: Int, seq: Int): String` — 构造 `history_list` 帧。
    - `fun buildConversationFrame(seq: Int): String` — 构造 `conversation_list` 帧。
- 限制：每会话保留最近 200 条、会话 100 个，防膨胀。

- [ ] **Step 1: 写失败测试**

`android-sync/app/src/test/java/com/example/bandqq/sync/MessageStoreTest.kt`:
```kotlin
package com.example.bandqq.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageStoreTest {

    private val store = MessageStore()

    @Test
    fun `写入后能取回历史`() {
        store.addMessage("123", StoredMessage("group", "456", "张三", "你好", 1700000000L))
        val history = store.getHistory("123", 50)
        assertEquals(1, history.size)
        assertEquals("你好", history[0].content)
    }

    @Test
    fun `会话按时间倒序`() {
        store.addMessage("a", StoredMessage("group", "1", "A", "x", 100L))
        store.addMessage("b", StoredMessage("group", "2", "B", "y", 200L))
        val convs = store.getConversations()
        assertEquals("b", convs[0].id)
    }

    @Test
    fun `history_list 帧包含消息`() {
        store.addMessage("123", StoredMessage("group", "456", "张三", "你好", 1700000000L))
        val frame = store.buildHistoryFrame("123", 50, 4)
        assertTrue(frame.contains("\"type\":\"history_list\""))
        assertTrue(frame.contains("\"target_id\":\"123\""))
        assertTrue(frame.contains("你好"))
    }

    @Test
    fun `conversation_list 帧包含会话`() {
        store.addMessage("123", StoredMessage("group", "456", "张三", "你好", 1700000000L))
        val frame = store.buildConversationFrame(3)
        assertTrue(frame.contains("\"type\":\"conversation_list\""))
        assertTrue(frame.contains("\"id\":\"123\""))
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

运行（Android Studio 中）：`./gradlew :app:testDebugUnitTest`
预期：FAIL（类不存在）。

- [ ] **Step 3: 实现 MessageStore.kt**

```kotlin
package com.example.bandqq.sync

import com.google.gson.JsonArray
import com.google.gson.JsonObject

data class StoredMessage(
    val messageType: String,
    val senderId: String,
    val senderName: String,
    val content: String,
    val time: Long
)

data class ConversationInfo(
    val id: String,
    val type: String,
    val name: String,
    val lastMsg: String,
    val time: Long
)

class MessageStore {

    private val messagesByTarget = LinkedHashMap<String, MutableList<StoredMessage>>()
    private val MAX_MESSAGES = 200
    private val MAX_CONVERSATIONS = 100

    fun addMessage(targetId: String, msg: StoredMessage) {
        val list = messagesByTarget.getOrPut(targetId) { mutableListOf() }
        list.add(msg)
        while (list.size > MAX_MESSAGES) list.removeAt(0)
    }

    fun getHistory(targetId: String, limit: Int): List<StoredMessage> {
        val list = messagesByTarget[targetId] ?: return emptyList()
        val from = (list.size - limit).coerceAtLeast(0)
        return list.subList(from, list.size)
    }

    fun getConversations(): List<ConversationInfo> {
        val out = mutableListOf<ConversationInfo>()
        for ((id, list) in messagesByTarget) {
            if (list.isEmpty()) continue
            val last = list.last()
            out.add(
                ConversationInfo(
                    id = id,
                    type = last.messageType,
                    name = last.senderName.ifBlank { id },
                    lastMsg = last.content,
                    time = last.time
                )
            )
        }
        out.sortByDescending { it.time }
        return out.subList(0, out.size.coerceAtMost(MAX_CONVERSATIONS))
    }

    fun buildHistoryFrame(targetId: String, limit: Int, seq: Int): String {
        val obj = JsonObject()
        obj.addProperty("type", "history_list")
        obj.addProperty("seq", seq)
        obj.addProperty("target_id", targetId)
        val arr = JsonArray()
        for (m in getHistory(targetId, limit)) {
            val o = JsonObject()
            o.addProperty("message_type", m.messageType)
            o.addProperty("sender_id", m.senderId)
            o.addProperty("sender_name", m.senderName)
            o.addProperty("content", m.content)
            o.addProperty("time", m.time)
            arr.add(o)
        }
        obj.add("list", arr)
        return obj.toString()
    }

    fun buildConversationFrame(seq: Int): String {
        val obj = JsonObject()
        obj.addProperty("type", "conversation_list")
        obj.addProperty("seq", seq)
        val arr = JsonArray()
        for (c in getConversations()) {
            val o = JsonObject()
            o.addProperty("id", c.id)
            o.addProperty("type", c.type)
            o.addProperty("name", c.name)
            o.addProperty("last_msg", c.lastMsg)
            o.addProperty("time", c.time)
            arr.add(o)
        }
        obj.add("list", arr)
        return obj.toString()
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

运行（Android Studio 中）：`./gradlew :app:testDebugUnitTest`
预期：MessageStoreTest 全部 PASS。

- [ ] **Step 5: 提交**

```bash
git add android-sync/app/src/main/java/com/example/bandqq/sync/MessageStore.kt android-sync/app/src/test/java/com/example/bandqq/sync/MessageStoreTest.kt
git commit -m "feat(android): 消息存储 MessageStore"
```

---

### Task 6: 消息中枢 MessageBroker

**Files:**
- Create: `android-sync/app/src/main/java/com/example/bandqq/sync/MessageBroker.kt`
- Test: `android-sync/app/src/test/java/com/example/bandqq/sync/MessageBrokerTest.kt`

**Interfaces:**
- Consumes: `OneBotParser`、`OneBotClient`、`MessageStore`、`OneBotListener`、手环帧对象。
- Produces:
  - `class MessageBroker(parser: OneBotParser, oneBot: OneBotClient, store: MessageStore)`：
    - `fun onBandFrame(json: String): Boolean` — 处理手环消息（send_message / get_history / get_conversations），返回是否已处理。
    - `fun handleOneBotEvent(msg: OneBotMessage): String?` — 落库并转手环帧，返回 JSON 或 null。
    - `var bandSender: (String) -> Unit` — 回调，将 JSON 发回手环（由 InterconnectBridge 注入）。

- [ ] **Step 1: 写失败测试**

`android-sync/app/src/test/java/com/example/bandqq/sync/MessageBrokerTest.kt`:
```kotlin
package com.example.bandqq.sync

import com.example.bandqq.onebot.OneBotMessage
import com.example.bandqq.onebot.OneBotParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageBrokerTest {

    private val parser = OneBotParser()

    @Test
    fun `handshake 发送消息帧被转发到 onebot`() {
        val sent = mutableListOf<Triple<String, String, String>>()
        val oneBot = FakeOneBot { t, id, c -> sent.add(Triple(t, id, c)); true }
        val broker = MessageBroker(parser, oneBot, MessageStore())
        val handled = broker.onBandFrame("""{"type":"send_message","message_type":"group","target_id":"123","content":"收到"}""")
        assertTrue(handled)
        assertEquals("group", sent[0].first)
        assertEquals("123", sent[0].second)
        assertEquals("收到", sent[0].third)
    }

    @Test
    fun `onebot 事件转手环帧`() {
        val broker = MessageBroker(parser, FakeOneBot { _, _, _ -> true }, MessageStore())
        val frame = broker.handleOneBotEvent(
            OneBotMessage("group", "123", "456", "张三", "你好", 1700000000L)
        )
        assertTrue(frame!!.contains("\"target_id\":\"123\""))
        assertTrue(frame.contains("你好"))
    }

    @Test
    fun `get_history 返回存储的最近消息`() {
        val store = MessageStore()
        store.addMessage("123", StoredMessage("group", "456", "张三", "你好", 1700000000L))
        val broker = MessageBroker(parser, FakeOneBot { _, _, _ -> true }, store)
        val out = mutableListOf<String>()
        broker.bandSender = { out.add(it) }
        val handled = broker.onBandFrame("""{"type":"get_history","seq":9,"target_id":"123","limit":20}""")
        assertTrue(handled)
        assertTrue(out[0].contains("\"type\":\"history_list\""))
        assertTrue(out[0].contains("你好"))
    }

    @Test
    fun `get_conversations 返回会话列表帧`() {
        val store = MessageStore()
        store.addMessage("123", StoredMessage("group", "456", "张三", "你好", 1700000000L))
        val broker = MessageBroker(parser, FakeOneBot { _, _, _ -> true }, store)
        val out = mutableListOf<String>()
        broker.bandSender = { out.add(it) }
        val handled = broker.onBandFrame("""{"type":"get_conversations","seq":9}""")
        assertTrue(handled)
        assertTrue(out[0].contains("\"type\":\"conversation_list\""))
        assertTrue(out[0].contains("\"id\":\"123\""))
    }

    @Test
    fun `未知手环帧返回 false`() {
        val broker = MessageBroker(parser, FakeOneBot { _, _, _ -> true }, MessageStore())
        assertNull(broker.handleOneBotEvent(OneBotMessage("group", "1", "2", "n", "x", 0)))
        assertTrue(!broker.onBandFrame("""{"type":"unknown"}"""))
    }
}

class FakeOneBot(private val onSend: (String, String, String) -> Boolean) :
    com.example.bandqq.onebot.OneBotListener {
    var connected = false
    override fun onEvent(message: OneBotMessage) {}
    override fun onState(connected: Boolean) { this.connected = connected }
    fun sendMessage(t: String, id: String, c: String): Boolean = onSend(t, id, c)
}
```

- [ ] **Step 2: 运行测试验证失败**

运行（Android Studio 中）：`./gradlew :app:testDebugUnitTest`
预期：FAIL（类不存在 / MessageBroker 未实现 OneBotListener）。

- [ ] **Step 3: 实现 MessageBroker.kt**

```kotlin
package com.example.bandqq.sync

import com.example.bandqq.onebot.OneBotClient
import com.example.bandqq.onebot.OneBotMessage
import com.example.bandqq.onebot.OneBotParser
import com.google.gson.JsonObject
import com.google.gson.JsonParser

class MessageBroker(
    private val parser: OneBotParser,
    private val oneBot: OneBotClient,
    private val store: MessageStore
) : OneBotListener {

    var bandSender: (String) -> Unit = {}

    fun onBandFrame(json: String): Boolean {
        val obj = try {
            JsonParser.parseString(json).asJsonObject
        } catch (e: Exception) {
            return false
        }
        val type = obj.get("type")?.asString ?: return false
        val seq = obj.get("seq")?.asInt ?: 0
        when (type) {
            "send_message" -> {
                val messageType = obj.get("message_type")?.asString ?: "private"
                val targetId = obj.get("target_id")?.asString ?: return false
                val content = obj.get("content")?.asString ?: ""
                oneBot.sendMessage(messageType, targetId, content)
                return true
            }
            "get_history" -> {
                val targetId = obj.get("target_id")?.asString ?: return false
                val limit = obj.get("limit")?.asInt ?: 20
                bandSender(store.buildHistoryFrame(targetId, limit, seq))
                return true
            }
            "get_conversations" -> {
                bandSender(store.buildConversationFrame(seq))
                return true
            }
            else -> return false
        }
    }

    fun handleOneBotEvent(msg: OneBotMessage): String? {
        store.addMessage(
            msg.targetId,
            StoredMessage(
                messageType = msg.messageType,
                senderId = msg.senderId,
                senderName = msg.senderName,
                content = msg.content,
                time = msg.time
            )
        )
        return parser.toHandBandFrame(msg)
    }

    override fun onEvent(message: OneBotMessage) {
        val frame = handleOneBotEvent(message) ?: return
        bandSender(frame)
    }

    override fun onState(connected: Boolean) {
        val obj = JsonObject()
        obj.addProperty("type", "connect_state")
        obj.addProperty("seq", 0)
        obj.addProperty("state", if (connected) "connected" else "disconnected")
        bandSender(obj.toString())
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

运行（Android Studio 中）：`./gradlew :app:testDebugUnitTest`
预期：MessageBrokerTest 全部 PASS。

- [ ] **Step 5: 提交**

```bash
git add android-sync/app/src/main/java/com/example/bandqq/sync/MessageBroker.kt
git commit -m "feat(android): 双向消息中枢 MessageBroker"
```

---

### Task 7: 前台服务 SyncService

**Files:**
- Create: `android-sync/app/src/main/java/com/example/bandqq/sync/SyncService.kt`

**Interfaces:**
- Consumes: `ConfigManager`、`MessageBroker`、`OneBotClient`、`OneBotParser`。
- Produces:
  - `class SyncService : Service()` — 前台服务，注册 `Action.START`/`Action.STOP`，持有 broker 并注入 `bandSender`。
  - 互联桥 `InterconnectBridge`：注册手环消息回调，`onConnect/onDisconnect` 时触发状态同步。
  - 提供 `object SyncState { var oneBotConnected: Boolean; var bandConnected: Boolean }` 供 UI 查询。

- [ ] **Step 1: 实现 SyncService.kt**

```kotlin
package com.example.bandqq.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.example.bandqq.R
import com.example.bandqq.config.ConfigManager
import com.example.bandqq.onebot.OneBotClient
import com.example.bandqq.onebot.OneBotListener
import com.example.bandqq.onebot.OneBotMessage
import com.example.bandqq.onebot.OneBotParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object SyncState {
    @Volatile var oneBotConnected: Boolean = false
    @Volatile var bandConnected: Boolean = false
}

class SyncService : Service() {

    companion object {
        const val ACTION_START = "com.example.bandqq.action.START"
        const val ACTION_STOP = "com.example.bandqq.action.STOP"
        private const val CHANNEL_ID = "sync_channel"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SyncService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, SyncService::class.java).setAction(ACTION_STOP))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var configManager: ConfigManager
    private lateinit var parser: OneBotParser
    private lateinit var oneBot: OneBotClient
    private lateinit var broker: MessageBroker

    override fun onCreate() {
        super.onCreate()
        createChannel()
        configManager = ConfigManager(this)
        parser = OneBotParser()
        oneBot = OneBotClient(parser)
        broker = MessageBroker(parser, oneBot, MessageStore())
        oneBot.startWithListener(broker)
        InterconnectBridge.register(broker)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                scope.launch {
                    val config = configManager.load()
                    oneBot.start(config, broker)
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        oneBot.stop()
        InterconnectBridge.unregister(broker)
        scope.cancelScope()
        super.onDestroy()
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "同步器服务", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("QQ同步器")
            .setContentText("同步器运行中")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
    }
}
```

- [ ] **Step 2: 补充 OneBotClient.startWithListener（在 OneBotClient 添加）**

```kotlin
fun startWithListener(listener: OneBotListener) {
    this.listener = listener
}
```

- [ ] **Step 3: 提交**

```bash
git add android-sync/app/src/main/java/com/example/bandqq/sync/SyncService.kt
git commit -m "feat(android): 前台服务 SyncService 与互联桥"
```

---

### Task 8: MainActivity 配置界面

**Files:**
- Create: `android-sync/app/src/main/java/com/example/bandqq/MainActivity.kt`
- Create: `android-sync/app/src/main/res/layout/activity_main.xml`
- Create: `android-sync/app/src/main/res/values/strings.xml`
- Create: `android-sync/app/src/main/res/values/themes.xml`
- Create: `android-sync/app/src/main/res/drawable/ic_launcher_foreground.xml`

**Interfaces:**
- Consumes: `ConfigManager`、`SyncService`、`SyncState`。
- Produces: 配置界面（WS 地址、HTTP 地址、Token 输入 + 保存）、NapCat 探测按钮、连接状态与日志显示、启动/停止服务按钮。

- [ ] **Step 1: 创建 layout**

`android-sync/app/src/main/res/layout/activity_main.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<ScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="16dp">

        <TextView
            android:id="@+id/statusText"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="状态：-"
            android:textSize="18sp"
            android:textStyle="bold" />

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="NapCat WS 地址"
            android:paddingTop="12dp" />

        <EditText
            android:id="@+id/wsInput"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:hint="ws://127.0.0.1:3001" />

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="HTTP 地址"
            android:paddingTop="12dp" />

        <EditText
            android:id="@+id/httpInput"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:hint="http://127.0.0.1:3000" />

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="Access Token"
            android:paddingTop="12dp" />

        <EditText
            android:id="@+id/tokenInput"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:hint="(可选)" />

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
            android:text="探测 NapCat"
            android:paddingTop="8dp" />

        <Button
            android:id="@+id/startBtn"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="启动同步"
            android:paddingTop="8dp" />

        <Button
            android:id="@+id/stopBtn"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="停止同步"
            android:paddingTop="8dp" />

        <TextView
            android:id="@+id/logView"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text=""
            android:textSize="12sp"
            android:paddingTop="16dp" />
    </LinearLayout>
</ScrollView>
```

- [ ] **Step 2: 创建 values 资源**

`strings.xml`:
```xml
<resources>
    <string name="app_name">QQ同步器</string>
</resources>
```

`themes.xml`:
```xml
<resources>
    <style name="Theme.AppCompat.DayNight" parent="Theme.AppCompat.DayNight" />
</resources>
```

`ic_launcher_foreground.xml`:
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#07C160"
        android:pathData="M12,2C6.5,2 2,6 2,11c0,2.8 1.4,5.2 3.7,6.8L5,21l3.4,-1.8c1.1,0.3 2.4,0.5 3.6,0.5 5.5,0 10,-4 10,-8.7C22,6 17.5,2 12,2z" />
</vector>
```

- [ ] **Step 3: 实现 MainActivity.kt**

```kotlin
package com.example.bandqq

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.bandqq.config.ConfigManager
import com.example.bandqq.config.ConfigHolder
import com.example.bandqq.databinding.ActivityMainBinding
import com.example.bandqq.sync.SyncService
import com.example.bandqq.sync.SyncState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var configManager: ConfigManager
    private val scope = CoroutineScope(Dispatchers.Main)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configManager = ConfigManager(this)

        requestPermissions()
        loadConfig()
        bindButtons()
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
            binding.wsInput.setText(config.wsUrl)
            binding.httpInput.setText(config.httpUrl)
            binding.tokenInput.setText(config.token)
            refreshStatus()
        }
    }

    private fun bindButtons() {
        binding.saveBtn.setOnClickListener {
            scope.launch {
                val cfg = ConfigHolder.config.copy(
                    wsUrl = binding.wsInput.text.toString().trim(),
                    httpUrl = binding.httpInput.text.toString().trim(),
                    token = binding.tokenInput.text.toString().trim()
                )
                configManager.save(cfg)
                Toast.makeText(this@MainActivity, "配置已保存", Toast.LENGTH_SHORT).show()
            }
        }

        binding.probeBtn.setOnClickListener { probeNapCat() }

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
    }

    private fun probeNapCat() {
        scope.launch {
            val httpUrl = binding.httpInput.text.toString().trim().ifBlank { "http://127.0.0.1:3000" }
            val ok = probeHttp(httpUrl)
            if (ok) {
                binding.statusText.text = "状态：NapCat 在线"
                toast("NapCat 在线")
            } else {
                binding.statusText.text = "状态：NapCat 未响应，请检查是否已安装并登录 NapCat APK"
                promptInstallNapCat()
            }
        }
    }

    private fun suspend probeHttp(url: String): Boolean {
        val client = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).build()
        return try {
            val resp = client.newCall(Request.Builder().url(url).get().build()).execute()
            resp.use { it.isSuccessful }
        } catch (e: IOException) {
            false
        }
    }

    private fun promptInstallNapCat() {
        runOnUiThread {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/linger-su/astrbot-termux/releases"))
            try {
                startActivity(intent)
            } catch (e: Exception) {
                toast("请手动访问 astrbot-termux releases 下载 NapCat APK")
            }
        }
    }

    private fun refreshStatus() {
        binding.statusText.text = when {
            SyncState.oneBotConnected && SyncState.bandConnected -> "状态：互联已连接，NapCat 在线"
            SyncState.oneBotConnected -> "状态：NapCat 在线，等待手环连接"
            else -> "状态：未连接（请启动同步服务）"
        }
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

- [ ] **Step 4: 提交**

```bash
git add android-sync/app/src/main/java/com/example/bandqq/MainActivity.kt android-sync/app/src/main/res/
git commit -m "feat(android): 配置界面 MainActivity"
```

---

### Task 9: 说明文档与完整验证

**Files:**
- Create: `android-sync/README.md`
- Create: `android-sync/keystore.example.properties`

- [ ] **Step 1: 编写 android-sync/README.md**

说明：Android Studio 打开工程、配置 NapCat 地址、真机安装步骤、签名要求（与手环端证书一致）。

- [ ] **Step 2: 创建 keystore.example.properties**

```properties
storeFile=keystore.jks
storePassword=changeit
keyAlias=bandqq
keyPassword=changeit
```

- [ ] **Step 3: 提交**

```bash
git add android-sync/README.md android-sync/keystore.example.properties
git commit -m "docs(android): 构建与签名说明"
```
