# 手机端添加好友/群聊并同步到手环 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 手机端从 NapCat 拉取已有好友/群列表，用户勾选要显示到手环的联系人；手环会话列表只常驻显示已添加联系人；未添加联系人消息临时显示并标记，重新同步时清除。

**Architecture:** 手机端新增 `OneBotClient.requestApi` 通用调用 NapCat `get_friend_list`/`get_group_list`，`ContactManagerActivity` 提供勾选 UI 并写 `MessageStore.visibleContacts`（SharedPreferences 持久化），经帧协议 `visible_contacts` 同步到手环；手环 `store.js` 维护 `visibleContacts` + 会话 `is_temporary` 标记，`index.ux` 区分渲染并加"临时"角标。

**Tech Stack:** Kotlin/Android（OkHttp、Gson、MockWebServer、JUnit4）、Vela 快应用（JS、`node:test`）。

## Global Constraints

- 帧协议为 JSON 文本帧，经 `InterconnectBridge` 互联通道传递；所有新帧必须含 `type` 与 `seq` 字段。
- `push_message` 帧新增 `visible` 布尔字段（默认 true）；`conversation_list` 帧每项补齐 `type` 字段（修复现存 bug）。
- 手机端为联系人主存储：`MessageStore.visibleContacts` 持久化 key 为 `visible_contacts`（与现有 `quick_replies`/`chat_messages` 一致的 SharedPreferences JSON）。
- 手环端 `store.js` 新增 `visible_contacts` 存储 key，与现有 `conv_cache`/`quick_replies` 相同的 storage 适配器。
- 手环会话列表逻辑：`conversations` 仍为列表数据源；`upsertMessage` 计算 `is_temporary`（未添加联系人 = 临时）；`setVisibleContacts` 时清除全部临时会话。
- 不改动手环 `InputMethod.ux` 组件（用户禁止）；`input` 组件不支持 text 类型。
- 测试命令：Android `powershell scripts\build-android.ps1 -Task testDebugUnitTest`；手环 `cd band-qq; npm test`。

---

## 文件结构

### 新增
- `android-sync/app/src/main/java/com/example/bandqq/ContactManagerActivity.kt` — 好友/群勾选 UI
- `android-sync/app/src/main/res/layout/activity_contact_manager.xml` — 勾选页布局
- `android-sync/app/src/main/res/layout/item_contact_checkable.xml` — 单联系人复选框行（可选，见 Task 6）

### 修改
- `android-sync/app/src/main/java/com/example/bandqq/sync/MessageStore.kt` — `VisibleContact` 数据类、持久化、`buildVisibleContactsFrame`、`buildConversationFrame` 补 `type`
- `android-sync/app/src/main/java/com/example/bandqq/onebot/OneBotClient.kt` — 新增 `requestApi(action, callback)`
- `android-sync/app/src/main/java/com/example/bandqq/sync/MessageBroker.kt` — 处理 `get_visible_contacts`、`handleOneBotEvent` 附加 `visible`
- `android-sync/app/src/main/java/com/example/bandqq/MainActivity.kt` — 新增"管理手环联系人"按钮
- `android-sync/app/src/main/res/layout/activity_main.xml` — 新增按钮
- `android-sync/app/src/main/AndroidManifest.xml` — 注册 `ContactManagerActivity`
- `band-qq/src/common/store.js` — `visibleContacts` 状态、`is_temporary` 标记、`setVisibleContacts` 清除临时
- `band-qq/src/common/protocol.js` — 新增 `getVisibleContacts()`、`decodePush` 透传 `visible`
- `band-qq/src/app.ux` — 处理 `visible_contacts` 帧
- `band-qq/src/pages/index/index.ux` — 临时角标 + 私聊/群聊图标区分 + 空态文案

### 测试
- `android-sync/app/src/test/java/com/example/bandqq/sync/MessageStoreTest.kt` — 追加 visibleContacts 测试
- `android-sync/app/src/test/java/com/example/bandqq/sync/MessageBrokerTest.kt` — 追加 `get_visible_contacts` 与 `visible` 字段测试
- `android-sync/app/src/test/java/com/example/bandqq/onebot/OneBotClientTest.kt` — 追加 `requestApi` 测试
- `band-qq/test/store.test.js` — 追加 visibleContacts / is_temporary 测试
- `band-qq/test/protocol.test.js` — 追加 `getVisibleContacts` 测试

---

### Task 1: MessageStore — VisibleContact 数据与帧构建

**Files:**
- Modify: `android-sync/app/src/main/java/com/example/bandqq/sync/MessageStore.kt`
- Test: `android-sync/app/src/test/java/com/example/bandqq/sync/MessageStoreTest.kt`

**Interfaces:**
- Produces:
  - `data class VisibleContact(val id: String, val type: String, val name: String)`（顶层，与 `ConversationInfo` 同级）
  - `fun getVisibleContacts(): List<VisibleContact>`
  - `fun setVisibleContacts(list: List<VisibleContact>)`
  - `fun isVisibleContact(id: String): Boolean`
  - `fun buildVisibleContactsFrame(seq: Int): String` → `{"type":"visible_contacts","seq":N,"contacts":[...]}`
  - `fun buildConversationFrame(seq: Int): String` → 每项新增 `type` 字段（修复现有 bug）

- [ ] **Step 1: 写失败测试**

在 `MessageStoreTest.kt` 末尾追加：

```kotlin
@Test
fun `visibleContacts 持久化往返`() {
    val kv = InMemoryKv()
    val s1 = MessageStore(kv)
    s1.setVisibleContacts(listOf(VisibleContact("111", "private", "小明")))
    val reload = MessageStore(kv)
    assertEquals(listOf(VisibleContact("111", "private", "小明")), reload.getVisibleContacts())
}

@Test
fun `isVisibleContact 判定`() {
    val store = MessageStore()
    store.setVisibleContacts(listOf(VisibleContact("111", "private", "小明")))
    assertTrue(store.isVisibleContact("111"))
    assertTrue(!store.isVisibleContact("222"))
}

@Test
fun `visible_contacts 帧包含联系人列表`() {
    val store = MessageStore()
    store.setVisibleContacts(listOf(VisibleContact("111", "private", "小明"), VisibleContact("222", "group", "群A")))
    val frame = store.buildVisibleContactsFrame(7)
    assertTrue(frame.contains("\"type\":\"visible_contacts\""))
    assertTrue(frame.contains("\"id\":\"111\""))
    assertTrue(frame.contains("\"type\":\"group\""))
    assertTrue(frame.contains("\"name\":\"群A\""))
}

@Test
fun `conversation_list 帧包含 type 字段`() {
    val store = MessageStore()
    store.addMessage("123", StoredMessage("group", "456", "张三", "你好", 1700000000L))
    val frame = store.buildConversationFrame(3)
    assertTrue(frame.contains("\"type\":\"group\""))
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `powershell scripts\build-android.ps1 -Task testDebugUnitTest`
Expected: FAIL — `VisibleContact` 未定义、`setVisibleContacts` 未定义

- [ ] **Step 3: 实现**

在 `MessageStore.kt` 顶层追加数据类（放在 `ConversationInfo` 之后）：

```kotlin
data class VisibleContact(
    val id: String,
    val type: String,
    val name: String
)
```

在类内新增字段（`CONVERSATIONS_KEY` 之后）：

```kotlin
private val VISIBLE_KEY = "visible_contacts"
private var visibleContacts: MutableList<VisibleContact> = loadVisibleContacts()
```

在 `init` 块中追加 `visibleContacts = loadVisibleContacts()`（放在 `quickReplies` 之后）。

新增方法（放在 `clearAllHistory()` 之后、`buildHistoryFrame` 之前）：

```kotlin
private fun loadVisibleContacts(): MutableList<VisibleContact> {
    val raw = storage.get(VISIBLE_KEY, "[]")
    return try {
        val arr = JsonParser.parseString(raw).asJsonArray
        val out = mutableListOf<VisibleContact>()
        for (e in arr) {
            val o = e.asJsonObject
            out.add(
                VisibleContact(
                    id = o.get("id")?.asString ?: "",
                    type = o.get("type")?.asString ?: "private",
                    name = o.get("name")?.asString ?: ""
                )
            )
        }
        out
    } catch (e: Exception) {
        mutableListOf()
    }
}

private fun persistVisibleContacts() {
    val arr = JsonArray()
    for (c in visibleContacts) {
        val o = JsonObject()
        o.addProperty("id", c.id)
        o.addProperty("type", c.type)
        o.addProperty("name", c.name)
        arr.add(o)
    }
    storage.set(VISIBLE_KEY, arr.toString())
}

fun getVisibleContacts(): List<VisibleContact> = visibleContacts.toList()

fun setVisibleContacts(list: List<VisibleContact>) {
    visibleContacts = list.distinctBy { it.id }.toMutableList()
    persistVisibleContacts()
}

fun isVisibleContact(id: String): Boolean = visibleContacts.any { it.id == id }

fun buildVisibleContactsFrame(seq: Int): String {
    val obj = JsonObject()
    obj.addProperty("type", "visible_contacts")
    obj.addProperty("seq", seq)
    val arr = JsonArray()
    for (c in visibleContacts) {
        val o = JsonObject()
        o.addProperty("id", c.id)
        o.addProperty("type", c.type)
        o.addProperty("name", c.name)
        arr.add(o)
    }
    obj.add("contacts", arr)
    return obj.toString()
}
```

修改 `buildConversationFrame`（当前 L232-239 循环体内），在 `o.addProperty("id", c.id)` 之后插入：

```kotlin
o.addProperty("type", c.type)
```

- [ ] **Step 4: 运行测试确认通过**

Run: `powershell scripts\build-android.ps1 -Task testDebugUnitTest`
Expected: PASS（原有 8 项 + 新增 4 项）

- [ ] **Step 5: 提交**

```bash
git add android-sync/app/src/main/java/com/example/bandqq/sync/MessageStore.kt android-sync/app/src/test/java/com/example/bandqq/sync/MessageStoreTest.kt
git commit -m "feat(sync): MessageStore 新增可见联系人(visibleContacts)存储与帧构建，conversation_list 补 type"
```

---

### Task 2: OneBotClient — 通用 API 调用 requestApi

**Files:**
- Modify: `android-sync/app/src/main/java/com/example/bandqq/onebot/OneBotClient.kt`
- Test: `android-sync/app/src/test/java/com/example/bandqq/onebot/OneBotClientTest.kt`

**Interfaces:**
- Consumes: `ConfigHolder.config`（`AppConfig.httpUrl`、`AppConfig.token`）
- Produces: `fun requestApi(action: String, callback: (String?) -> Unit)` — 请求 OneBot HTTP API，成功回传响应体字符串，失败回传 null。请求路径优先 `{httpUrl}/api/{action}`，异常时 fallback `{httpUrl}/{action}`。响应成功判定：HTTP 200 且 body 可解析（`callback(body)`）。

- [ ] **Step 1: 写失败测试**

在 `OneBotClientTest.kt` 末尾追加：

```kotlin
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
```

- [ ] **Step 2: 运行测试确认失败**

Run: `powershell scripts\build-android.ps1 -Task testDebugUnitTest`
Expected: FAIL — `requestApi` 未定义

- [ ] **Step 3: 实现**

在 `OneBotClient.kt` 的 `sendMessage` 之后追加：

```kotlin
/**
 * 调用 OneBot HTTP 通用接口（如 get_friend_list/get_group_list）。
 * 路径优先 {httpUrl}/api/{action}，失败时回退 {httpUrl}/{action}。
 * 成功回传原始响应体，失败回传 null。
 */
fun requestApi(action: String, baseUrl: String = config.httpUrl, callback: (String?) -> Unit) {
    fun doRequest(url: String, onFail: () -> Unit) {
        val request = Request.Builder()
            .url(url)
            .post("{}".toRequestBody("application/json".toMediaType()))
            .apply { if (config.token.isNotBlank()) header("Authorization", "Bearer ${config.token}") }
            .build()
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                onFail()
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    val body = it.body?.string() ?: ""
                    if (it.isSuccessful) callback(body) else onFail()
                }
            }
        })
    }
    val root = baseUrl.trimEnd('/')
    doRequest("$root/api/$action") {
        doRequest("$root/$action") {
            callback(null)
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `powershell scripts\build-android.ps1 -Task testDebugUnitTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add android-sync/app/src/main/java/com/example/bandqq/onebot/OneBotClient.kt android-sync/app/src/test/java/com/example/bandqq/onebot/OneBotClientTest.kt
git commit -m "feat(onebot): OneBotClient 新增通用 requestApi 调用"
```

---

### Task 3: MessageBroker — 处理 get_visible_contacts 与 push_message 附 visible

**Files:**
- Modify: `android-sync/app/src/main/java/com/example/bandqq/sync/MessageBroker.kt`
- Test: `android-sync/app/src/test/java/com/example/bandqq/sync/MessageBrokerTest.kt`

**Interfaces:**
- Consumes: `MessageStore.buildVisibleContactsFrame(seq)`、`MessageStore.isVisibleContact(id)`（Task 1）
- Produces: 手环请求 `get_visible_contacts` 时经 `bandSender` 回推 `visible_contacts` 帧；`handleOneBotEvent` 返回的 `push_message` 帧含 `visible` 字段（`store.isVisibleContact(msg.targetId)`）。

- [ ] **Step 1: 写失败测试**

在 `MessageBrokerTest.kt` 末尾追加：

```kotlin
@Test
fun `get_visible_contacts 返回可见联系人帧`() {
    val store = MessageStore()
    store.setVisibleContacts(listOf(VisibleContact("111", "private", "小明")))
    val broker = MessageBroker(parser, FakeOneBot { _, _, _ -> true }, store)
    val out = mutableListOf<String>()
    broker.bandSender = { out.add(it) }
    val handled = broker.onBandFrame("""{"type":"get_visible_contacts","seq":9}""")
    assertTrue(handled)
    assertTrue(out[0].contains("\"type\":\"visible_contacts\""))
    assertTrue(out[0].contains("\"id\":\"111\""))
}

@Test
fun `已添加联系人 push_message 帧带 visible true`() {
    val store = MessageStore()
    store.setVisibleContacts(listOf(VisibleContact("123", "group", "群A")))
    val broker = MessageBroker(parser, FakeOneBot { _, _, _ -> true }, store)
    val frame = broker.handleOneBotEvent(OneBotMessage("group", "123", "456", "张三", "你好", 1700000000L))
    assertTrue(frame!!.contains("\"visible\":true"))
}

@Test
fun `未添加联系人 push_message 帧带 visible false`() {
    val store = MessageStore()
    val broker = MessageBroker(parser, FakeOneBot { _, _, _ -> true }, store)
    val frame = broker.handleOneBotEvent(OneBotMessage("group", "999", "456", "张三", "你好", 1700000000L))
    assertTrue(frame!!.contains("\"visible\":false"))
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `powershell scripts\build-android.ps1 -Task testDebugUnitTest`
Expected: FAIL — `VisibleContact`/`buildVisibleContactsFrame` 若 Task 1 未先合入则一起失败；`get_visible_contacts` 分支不存在

- [ ] **Step 3: 实现**

在 `MessageBroker.kt` 的 `onBandFrame` 的 `when` 中追加分支（放在 `clear_all_history` 之后、`else` 之前）：

```kotlin
"get_visible_contacts" -> {
    bandSender(store.buildVisibleContactsFrame(seq))
    return true
}
```

修改 `handleOneBotEvent`（当前 L76-89），在 `return parser.toHandBandFrame(msg)` 前计算 visible：

```kotlin
fun handleOneBotEvent(msg: OneBotMessage): String? {
    store.addMessage(
        msg.targetId,
        StoredMessage(
            messageType = msg.messageType,
            senderId = msg.senderId,
            senderName = msg.senderName,
            content = msg.content,
            time = msg.time,
            isSelf = msg.isSelf
        )
    )
    val visible = store.isVisibleContact(msg.targetId)
    return parser.toHandBandFrame(msg, visible)
}
```

**注意：** 此改动引入 `toHandBandFrame(msg, visible)` 新签名，依赖 Task 5（`OneBotParser.toHandBandFrame` 增加 `visible` 参数）。为保证 Task 4 可独立编译测试，请先完成 Task 5 再实现本步骤（或同步修改 `OneBotParser`）。

- [ ] **Step 4: 运行测试确认通过**

Run: `powershell scripts\build-android.ps1 -Task testDebugUnitTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add android-sync/app/src/main/java/com/example/bandqq/sync/MessageBroker.kt android-sync/app/src/test/java/com/example/bandqq/sync/MessageBrokerTest.kt
git commit -m "feat(sync): MessageBroker 处理 get_visible_contacts，push_message 附加 visible 标记"
```

---

### Task 4: OneBotParser — toHandBandFrame 增加 visible 参数

**Files:**
- Modify: `android-sync/app/src/main/java/com/example/bandqq/onebot/OneBotParser.kt`

**Interfaces:**
- Produces: `fun toHandBandFrame(msg: OneBotMessage, visible: Boolean = true): String` — 输出帧新增 `"visible": visible` 属性。
- Consumes by: Task 3 的 `MessageBroker.handleOneBotEvent`。

- [ ] **Step 1: 修改实现**

修改 `OneBotParser.kt` 的 `toHandBandFrame`（当前 L70-82），签名加 `visible: Boolean = true` 参数，并在 `obj.addProperty("is_self", msg.isSelf)` 之后追加：

```kotlin
obj.addProperty("visible", visible)
```

完成后方法体：

```kotlin
fun toHandBandFrame(msg: OneBotMessage, visible: Boolean = true): String {
    val obj = JsonObject()
    obj.addProperty("type", "push_message")
    obj.addProperty("seq", 0)
    obj.addProperty("message_type", msg.messageType)
    obj.addProperty("target_id", msg.targetId)
    obj.addProperty("sender_id", msg.senderId)
    obj.addProperty("sender_name", msg.senderName)
    obj.addProperty("content", msg.content)
    obj.addProperty("time", msg.time)
    obj.addProperty("is_self", msg.isSelf)
    obj.addProperty("visible", visible)
    return obj.toString()
}
```

- [ ] **Step 2: 验证编译与现有测试**

Run: `powershell scripts\build-android.ps1 -Task testDebugUnitTest`
Expected: PASS（默认参数不影响既有调用）

- [ ] **Step 3: 提交**

```bash
git add android-sync/app/src/main/java/com/example/bandqq/onebot/OneBotParser.kt
git commit -m "feat(onebot): toHandBandFrame 增加 visible 参数"
```

---

### Task 5: ContactManagerActivity — 好友/群勾选界面

**Files:**
- Create: `android-sync/app/src/main/java/com/example/bandqq/ContactManagerActivity.kt`
- Create: `android-sync/app/src/main/res/layout/activity_contact_manager.xml`
- Create: `android-sync/app/src/main/res/layout/item_contact_checkable.xml`
- Modify: `android-sync/app/src/main/AndroidManifest.xml`
- Modify: `android-sync/app/src/main/res/layout/activity_main.xml`
- Modify: `android-sync/app/src/main/java/com/example/bandqq/MainActivity.kt`

**Interfaces:**
- Consumes: `ConfigHolder.config.httpUrl`、`OneBotClient.requestApi`、`MessageStore.getVisibleContacts/setVisibleContacts`、`MessageStore.buildVisibleContactsFrame`、`StoreHolder.store`、`InterconnectBridge.sendToBand`
- Produces: 用户保存勾选后调用 `store.setVisibleContacts(checked)` 并经 `InterconnectBridge.sendToBand(store.buildVisibleContactsFrame(0))` 推送手环。

**UI 布局设计**（activity_contact_manager.xml）：顶部两个 Tab 按钮（好友/群聊）+ ListView + 底部"保存"按钮。每行用 `item_contact_checkable.xml`（横向 LinearLayout：CheckBox + 名称 TextView）。

**数据归一化**：`requestApi("get_friend_list")` 响应体 JSON 形如 `{"status":"ok","data":[{"user_id":10001,"nickname":"小明"}]}`；`get_group_list` 形如 `{"status":"ok","data":[{"group_id":20001,"group_name":"群A"}]}`。解析出 `{id, type, name}` 列表，加载时回填当前 `visibleContacts` 的勾选态。

- [ ] **Step 1: 写 item_contact_checkable.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:gravity="center_vertical"
    android:padding="8dp">

    <CheckBox
        android:id="@+id/contactCheck"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content" />

    <TextView
        android:id="@+id/contactName"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:textSize="16sp"
        android:textColor="#FFFFFF"
        android:paddingStart="8dp" />
</LinearLayout>
```

- [ ] **Step 2: 写 activity_contact_manager.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="12dp">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal">

        <Button
            android:id="@+id/friendTab"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:text="好友" />

        <Button
            android:id="@+id/groupTab"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:text="群聊" />
    </LinearLayout>

    <TextView
        android:id="@+id/contactHint"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="勾选要显示到手环的联系人"
        android:textSize="12sp"
        android:paddingTop="6dp" />

    <ListView
        android:id="@+id/contactList"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:choiceMode="multipleChoice" />

    <Button
        android:id="@+id/saveContactsBtn"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="保存并同步到手环" />
</LinearLayout>
```

- [ ] **Step 3: 写 ContactManagerActivity.kt**

```kotlin
package com.example.bandqq

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.bandqq.config.ConfigHolder
import com.example.bandqq.onebot.OneBotClient
import com.example.bandqq.onebot.OneBotParser
import com.example.bandqq.sync.InterconnectBridge
import com.example.bandqq.sync.StoreHolder
import com.example.bandqq.sync.VisibleContact
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

class ContactManagerActivity : AppCompatActivity() {

    private data class Row(val contact: VisibleContact)

    private val client = OneBotClient(OneBotParser())
    private var friends = mutableListOf<VisibleContact>()
    private var groups = mutableListOf<VisibleContact>()
    private var currentRows = mutableListOf<Row>()
    private var currentType = "private"
    private lateinit var listView: ListView
    private val adapter = ContactAdapter()

    private val contactListener: (Boolean) -> Unit = { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contact_manager)
        listView = findViewById(R.id.contactList)
        listView.adapter = adapter
        listView.choiceMode = ListView.CHOICE_MODE_MULTIPLE

        findViewById<View>(R.id.friendTab).setOnClickListener { loadTab("private") }
        findViewById<View>(R.id.groupTab).setOnClickListener { loadTab("group") }
        findViewById<View>(R.id.saveContactsBtn).setOnClickListener { saveSelection() }

        loadContacts()
    }

    override fun onResume() {
        super.onResume()
        loadContacts()
    }

    private fun loadContacts() {
        val http = ConfigHolder.config.httpUrl
        client.requestApi("get_friend_list", http) { raw -> runOnUiThread { applyList("private", raw) } }
        client.requestApi("get_group_list", http) { raw -> runOnUiThread { applyList("group", raw) } }
    }

    private fun applyList(type: String, raw: String?) {
        val rows = parseContacts(type, raw)
        if (type == "private") friends = rows else groups = rows
        refreshCurrentTab()
    }

    private fun parseContacts(type: String, raw: String?): MutableList<VisibleContact> {
        val out = mutableListOf<VisibleContact>()
        if (raw == null) return out
        try {
            val data = JsonParser.parseString(raw).asJsonObject.get("data") ?: return out
            if (!data.isJsonArray) return out
            val arr: JsonArray = data.asJsonArray
            val stored = StoreHolder.store?.getVisibleContacts() ?: emptyList()
            for (e in arr) {
                val o = e.asJsonObject
                val id: String
                val name: String
                if (type == "private") {
                    id = o.get("user_id")?.asLong?.toString() ?: continue
                    name = o.get("nickname")?.asString ?: id
                } else {
                    id = o.get("group_id")?.asLong?.toString() ?: continue
                    name = o.get("group_name")?.asString ?: id
                }
                out.add(VisibleContact(id, type, name))
            }
            out.addAll(stored.filter { s -> s.type == type && out.none { it.id == s.id } })
        } catch (e: Exception) {
            // 解析失败，返回已存储联系人
            out.addAll(StoreHolder.store?.getVisibleContacts()?.filter { it.type == type } ?: emptyList())
        }
        return out
    }

    private fun loadTab(type: String) {
        currentType = type
        refreshCurrentTab()
    }

    private fun refreshCurrentTab() {
        val source = if (currentType == "private") friends else groups
        currentRows = source.map { Row(it) }.toMutableList()
        adapter.notifyDataSetChanged()
        val stored = StoreHolder.store?.getVisibleContacts() ?: emptyList()
        for (i in currentRows.indices) {
            listView.setItemChecked(i, stored.any { it.id == currentRows[i].contact.id })
        }
    }

    private fun saveSelection() {
        val store = StoreHolder.store
        if (store == null) {
            toast("同步服务尚未启动，请先启动同步")
            return
        }
        val checked = mutableListOf<VisibleContact>()
        for (i in currentRows.indices) {
            if (listView.isItemChecked(i)) checked.add(currentRows[i].contact)
        }
        val merged = (store.getVisibleContacts().filter { it.type != currentType } + checked)
        store.setVisibleContacts(merged)
        InterconnectBridge.sendToBand(store.buildVisibleContactsFrame(0))
        toast("已保存并同步到手环")
        finish()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private inner class ContactAdapter : BaseAdapter() {
        override fun getCount(): Int = currentRows.size
        override fun getItem(position: Int): Any = currentRows[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(this@ContactManagerActivity)
                .inflate(R.layout.item_contact_checkable, parent, false)
            val name = view.findViewById<TextView>(R.id.contactName)
            name.text = currentRows[position].contact.name
            return view
        }
    }
}
```

**说明：** `item_contact_checkable.xml` 的 CheckBox 仅作装饰（ListView 的 `CHOICE_MODE_MULTIPLE` 负责实际勾选态），故 `getView` 不手动 setChecked。若真机发现 CheckBox 不联动，可在 `getView` 中 `view.findViewById<CheckBox>(R.id.contactCheck).isChecked = listView.isItemChecked(position)`（此情况在 Task 5 提交后由执行者根据真机反馈补一行，测试不受影响）。

- [ ] **Step 4: 注册 Activity**

在 `AndroidManifest.xml` 的 `</activity>`（ChatHistoryActivity 之后）追加：

```xml
<activity
    android:name=".ContactManagerActivity"
    android:exported="false" />
```

- [ ] **Step 5: 添加 MainActivity 按钮**

`activity_main.xml` 中，在 `chatHistoryBtn`（L134-139）之后追加：

```xml
<Button
    android:id="@+id/contactManagerBtn"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:text="管理手环联系人"
    android:paddingTop="8dp" />
```

`MainActivity.bindButtons()` 中，在 `binding.chatHistoryBtn.setOnClickListener { ... }` 之后追加：

```kotlin
binding.contactManagerBtn.setOnClickListener {
    startActivity(Intent(this, ContactManagerActivity::class.java))
}
```

- [ ] **Step 6: 编译验证**

Run: `powershell scripts\build-android.ps1 -Task assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 提交**

```bash
git add android-sync/app/src/main/AndroidManifest.xml android-sync/app/src/main/java/com/example/bandqq/ContactManagerActivity.kt android-sync/app/src/main/java/com/example/bandqq/MainActivity.kt android-sync/app/src/main/res/layout/activity_main.xml android-sync/app/src/main/res/layout/activity_contact_manager.xml android-sync/app/src/main/res/layout/item_contact_checkable.xml
git commit -m "feat(android): 新增管理手环联系人界面(好友/群勾选同步)"
```

---

### Task 6: 手环 store.js — visibleContacts 与 is_temporary 标记

**Files:**
- Modify: `band-qq/src/common/store.js`
- Test: `band-qq/test/store.test.js`

**Interfaces:**
- Consumes: `protocol.decodePush`（保留 `visible` 字段，见 Task 7）
- Produces:
  - `async setVisibleContacts(list)` — 保存 visibleContacts（持久化），并清除所有 `is_temporary` 会话及其消息缓存
  - `async getVisibleContacts(): Promise<Array>` — 返回 `[{id,type,name}]`
  - `isVisible(id): Boolean` — id 是否在 visibleContacts
  - `upsertMessage(msg)` 内部为会话对象增加 `is_temporary` 属性：`!(msg.visible !== false && this.isVisible(key))`；为 `{type: 'visible_contacts'}` 数据帧提供 `setVisibleContacts` 调用入口（见 Task 8 app.ux）

- [ ] **Step 1: 写失败测试**

在 `store.test.js` 末尾追加：

```js
it('visibleContacts 持久化', async () => {
  await store.setVisibleContacts([{ id: '100', type: 'private', name: '小明' }])
  assert.deepEqual(await store.getVisibleContacts(), [{ id: '100', type: 'private', name: '小明' }])
})

it('isVisible 判定', async () => {
  await store.setVisibleContacts([{ id: '100', type: 'private', name: '小明' }])
  assert.equal(store.isVisible('100'), true)
  assert.equal(store.isVisible('200'), false)
})

it('未添加联系人消息标记临时', async () => {
  await store.setVisibleContacts([{ id: '100', type: 'private', name: '小明' }])
  const msg = { type: 'push_message', message_type: 'private', target_id: '200', sender_id: '200', sender_name: '张三', content: '你好', visible: false, time: 1700000000 }
  await store.upsertMessage(msg)
  const convs = await store.getConversations()
  assert.equal(convs[0].is_temporary, true)
})

it('已添加联系人消息非临时', async () => {
  await store.setVisibleContacts([{ id: '100', type: 'private', name: '小明' }])
  const msg = { type: 'push_message', message_type: 'private', target_id: '100', sender_id: '100', sender_name: '小明', content: '你好', visible: true, time: 1700000000 }
  await store.upsertMessage(msg)
  const convs = await store.getConversations()
  assert.equal(convs[0].is_temporary, false)
})

it('setVisibleContacts 清除临时会话', async () => {
  const tmp = { type: 'push_message', message_type: 'private', target_id: '200', sender_id: '200', sender_name: '张三', content: '你好', visible: false, time: 1700000000 }
  await store.upsertMessage(tmp)
  assert.equal((await store.getConversations()).length, 1)
  await store.setVisibleContacts([{ id: '100', type: 'private', name: '小明' }])
  assert.deepEqual(await store.getConversations(), [])
  assert.deepEqual(await store.getMessages('200'), [])
})
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd band-qq; npm test`
Expected: FAIL — `setVisibleContacts`/`getVisibleContacts`/`isVisible` 未定义

- [ ] **Step 3: 实现**

在 `store.js` 顶部常量区追加：

```js
const VISIBLE_KEY = 'visible_contacts'
```

在 `createStore` 内状态声明区（`let quickReplies = []` 之后）追加：

```js
let visibleContacts = []
```

在 `init()` 方法中追加（`quickRaw` 解析之后）：

```js
const visibleRaw = await cache.get(VISIBLE_KEY, '[]')
try { visibleContacts = JSON.parse(visibleRaw) } catch (e) { visibleContacts = [] }
```

在 `upsertMessage` 方法中，在计算 `const conv = {...}` 之前追加 is_temporary 计算：

```js
const isTemp = !(msg.visible !== false && this.isVisible(msg.target_id))
```

并给 conv 对象增加属性（放在 `time:` 之后）：

```js
is_temporary: isTemp,
```

在 `getDefaultQuickReplies()` 之前追加新方法：

```js
async setVisibleContacts(list) {
  visibleContacts = Array.isArray(list) ? list : []
  const visibleIds = new Set(visibleContacts.map((c) => c.id))
  const remaining = conversations.filter((c) => !c.is_temporary)
  conversations = remaining.filter((c) => visibleIds.has(c.id))
  for (const c of visibleContacts) {
    if (!conversations.some((x) => x.id === c.id)) {
      conversations.push({ id: c.id, type: c.type, name: c.name, last_msg: '', time: 0, is_temporary: false })
    }
  }
  const msgKeys = Object.keys(messagesByTarget)
  msgKeys.forEach((k) => {
    if (!visibleIds.has(k)) {
      delete messagesByTarget[k]
      cache.set(MSG_PREFIX + k, JSON.stringify([]))
    }
  })
  await cache.set(VISIBLE_KEY, JSON.stringify(visibleContacts))
  await cache.set(CONV_KEY, JSON.stringify(conversations.slice(0, CACHE_CONVERSATIONS)))
},
async getVisibleContacts() {
  return visibleContacts
},
isVisible(id) {
  return visibleContacts.some((c) => c.id === id)
},
```

**关于 setVisibleContacts 清除逻辑：** visibleContacts 更新后：
1. 移除所有 `is_temporary` 会话；
2. 移除不在新列表中的会话（联系人被取消勾选）；
3. 为新列表中尚无会话的联系人补空会话条目；
4. 清掉不在可见列表中的目标的消息缓存。

- [ ] **Step 4: 运行测试确认通过**

Run: `cd band-qq; npm test`
Expected: PASS（原有 8 项 + 新增 5 项）

- [ ] **Step 5: 提交**

```bash
git add band-qq/src/common/store.js band-qq/test/store.test.js
git commit -m "feat(band): store 支持 visibleContacts 与会话 is_temporary 标记"
```

---

### Task 7: 手环 protocol.js — getVisibleContacts 帧与 decodePush 透传 visible

**Files:**
- Modify: `band-qq/src/common/protocol.js`
- Test: `band-qq/test/protocol.test.js`

**Interfaces:**
- Produces:
  - `getVisibleContacts()` → `{type:'get_visible_contacts', seq:nextSeq()}`
  - `decodePush(raw)` 返回值新增 `visible: raw.visible !== false`
- Consumes by: `app.ux`、`index.ux`

- [ ] **Step 1: 写失败测试**

在 `protocol.test.js` 末尾追加：

```js
it('构造 get_visible_contacts 帧', () => {
  const msg = getVisibleContacts()
  assert.equal(msg.type, 'get_visible_contacts')
})

it('decodePush 透传 visible', () => {
  const raw = { type: 'push_message', message_type: 'group', target_id: '9', sender_id: '8', sender_name: '张三', content: '你好', time: 1700000000, visible: false }
  const msg = decodePush(raw)
  assert.equal(msg.visible, false)
})

it('decodePush 默认 visible 为 true', () => {
  const raw = { type: 'push_message', message_type: 'group', target_id: '9', sender_id: '8', sender_name: '张三', content: '你好', time: 1700000000 }
  const msg = decodePush(raw)
  assert.equal(msg.visible, true)
})
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd band-qq; npm test`
Expected: FAIL — `getVisibleContacts` 未定义、`visible` 未透传

- [ ] **Step 3: 实现**

在 `protocol.js` 的 `getConversations()` 之后追加：

```js
export function getVisibleContacts() {
  return { type: 'get_visible_contacts', seq: nextSeq() }
}
```

在 `decodePush` 返回对象中（`time` 之后）追加：

```js
visible: raw.visible !== false,
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd band-qq; npm test`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add band-qq/src/common/protocol.js band-qq/test/protocol.test.js
git commit -m "feat(band): protocol 新增 get_visible_contacts 帧，decodePush 透传 visible"
```

---

### Task 8: 手环 app.ux — 处理 visible_contacts 帧

**Files:**
- Modify: `band-qq/src/app.ux`

**Interfaces:**
- Consumes: `store.setVisibleContacts`
- Produces: 收到 `visible_contacts` 帧 → `store.setVisibleContacts(msg.contacts)` → `emit('visible_contacts', msg.contacts)`

- [ ] **Step 1: 实现**

在 `app.ux` 的 `handleMessage` 的 `switch` 中，`case 'clear_all_history'` 之前追加：

```js
case 'visible_contacts':
  if (Array.isArray(msg.contacts)) store.setVisibleContacts(msg.contacts)
  emit('visible_contacts', msg.contacts)
  break
```

- [ ] **Step 2: 验证构建**

Run: `cd band-qq; npm run build`
Expected: build success

- [ ] **Step 3: 提交**

```bash
git add band-qq/src/app.ux
git commit -m "feat(band): app.ux 处理 visible_contacts 帧"
```

---

### Task 9: 手环 index.ux — 临时角标、私聊/群聊区分、空态文案

**Files:**
- Modify: `band-qq/src/pages/index/index.ux`

**Interfaces:**
- Consumes: `store.getVisibleContacts`、`protocol.getVisibleContacts`、事件 `visible_contacts`
- Produces: 列表渲染区分临时项（角标）与常驻项；私聊/群聊图标区分；空态文案"请在手机端添加联系人"

- [ ] **Step 1: 实现模板**

修改 `index.ux` 的 `<list-item>` 块（当前 L7-17），增加群聊图标区分与临时角标：

```html
<list-item type="conv" for="{{(idx, item) in conversations}}" onclick="openChat(idx)">
  <div class="card item">
    <div class="avatar {{$item.type === 'group' ? 'avatar-group' : 'avatar-private'}}">
      <text class="avatar-text">{{$item.name.charAt(0)}}</text>
    </div>
    <div class="item-body">
      <div class="item-title-row">
        <text class="item-name">{{$item.name}}</text>
        <text class="item-tag" if="{{$item.is_temporary}}">临时</text>
      </div>
      <text class="item-sub">{{$item.last_msg || ({{$item.is_temporary}} ? '' : '')}}</text>
    </div>
  </div>
</list-item>
```

**注意：** 若 `{{$item.is_temporary}}` 在属性中使用不被支持，改为在 `<text class="item-sub">{{$item.last_msg}}</text>` 保持原样，仅靠角标区分。空会话条目（last_msg 为空）显示占位文本。

（保留原 `item-sub` 单行文本即可，模板简化为：）

```html
<list-item type="conv" for="{{(idx, item) in conversations}}" onclick="openChat(idx)">
  <div class="card item">
    <div class="avatar {{$item.type === 'group' ? 'avatar-group' : 'avatar-private'}}">
      <text class="avatar-text">{{$item.name.charAt(0)}}</text>
    </div>
    <div class="item-body">
      <div class="item-title-row">
        <text class="item-name">{{$item.name}}</text>
        <text class="item-tag" if="{{$item.is_temporary}}">临时</text>
      </div>
      <text class="item-sub">{{$item.last_msg}}</text>
    </div>
  </div>
</list-item>
```

空态文案（当前 L21 `暂无会话`）改为：

```html
<text class="empty-text">{{conversations.length === 0 ? '请在手机端添加联系人' : '暂无会话'}}</text>
```

- [ ] **Step 2: 实现样式**

在 `<style>` 中新增/修改：

```css
.avatar-private { background-color: #1f1f1f; }
.avatar-group { background-color: #2a3b4c; border-radius: 10px; }
.item-title-row { flex-direction: row; align-items: center; }
.item-tag { font-size: 12px; color: #ffb800; border-width: 1px; border-color: #ffb800; border-radius: 6px; padding: 0 4px; margin-left: 6px; }
```

- [ ] **Step 3: 实现脚本**

修改 `index.ux` 的 `onShow`（当前 L56-59）追加拉取可见联系人：

```js
onShow() {
  this.refreshStatus()
  this.loadConversations()
  this.loadVisibleContacts()
},
```

新增方法（放在 `goSettings` 之前）：

```js
onInit() {
  this.$app.$def.on('conversations', () => this.loadConversations())
  this.$app.$def.on('visible_contacts', () => this.loadConversations())
},
async loadVisibleContacts() {
  const api = this.$app.$def.api
  const protocol = this.$app.$def.protocol
  try {
    await api.send(protocol.getVisibleContacts())
  } catch (e) {
    console.error('pull visible contacts failed', e)
  }
},
```

**注意：** 现有 `onInit` 已存在（L60-62），需合并，将 `visible_contacts` 订阅追加到其中。

- [ ] **Step 4: 验证构建**

Run: `cd band-qq; npm run build`
Expected: build success

- [ ] **Step 5: 提交**

```bash
git add band-qq/src/pages/index/index.ux
git commit -m "feat(band): 会话列表区分私聊/群聊图标与临时角标，空态提示添加联系人"
```

---

### Task 10: 端到端验证与交付

**Files:**
- Modify: `docs/superpowers/specs/2026-08-04-visible-contacts-design.md`（如需同步更新）
- 交付物：`dist/bandqq.release.rpk`、`app-release.apk`

- [ ] **Step 1: 手环完整测试与打包**

Run: `cd band-qq; npm test`（全部通过）
Run: `powershell scripts\rpk-pack.ps1`（生成 release rpk）

- [ ] **Step 2: Android 完整测试与打包**

Run: `powershell scripts\build-android.ps1 -Task testDebugUnitTest`
Run: `powershell scripts\build-android.ps1 -Task assembleRelease`

- [ ] **Step 3: 更新交付 zip**

将 `band-qq/dist/bandqq.release.rpk` 与 `android-sync/app/build/outputs/apk/release/app-release.apk` 重新压缩到桌面 `QQ手环消息交付包_vN.zip`（沿用既有目录结构 band/ + phone/ + INSTALL.txt + docs/）。

- [ ] **Step 4: 提交**

```bash
git add -A
git commit -m "feat: 手机端添加好友/群聊并同步到手环 全量交付"
```

---

## Self-Review 记录

**Spec 覆盖检查：**
- ✅ 手机端 NapCat 拉好友/群：Task 2（requestApi）+ Task 5（ContactManagerActivity）
- ✅ visibleContacts 存储：Task 1（MessageStore）
- ✅ visible_contacts 帧 + get_visible_contacts 请求：Task 1/3/7/8
- ✅ push_message 附 visible：Task 3/4/7
- ✅ 手环常驻/临时标记：Task 6（store.js）+ Task 9（index.ux）
- ✅ setVisibleContacts 清除临时：Task 6
- ✅ conversation_list 补 type：Task 1
- ✅ 私聊/群聊图标区分：Task 9
- ✅ 空态文案：Task 9
- ✅ 错误处理（NapCat 拉取失败回退已存储）：Task 5 `parseContacts` catch

**占位符扫描：** 无 TBD/TODO；所有代码块含完整实现。

**类型一致性：**
- `VisibleContact(id, type, name)` 在 Task 1 定义，Task 3/5 使用一致。
- `buildVisibleContactsFrame(seq)` Task 1 定义，Task 3/5 调用一致。
- `toHandBandFrame(msg, visible=true)` Task 4 定义，Task 3 调用一致。
- `store.setVisibleContacts(list)` / `getVisibleContacts()` / `isVisible(id)` Task 6 定义，Task 7/8/9 调用一致。
- `protocol.getVisibleContacts()` Task 7 定义，Task 9 调用一致。
- 手环会话 `is_temporary` 字段 Task 6 写入，Task 9 渲染读取一致。

**依赖顺序：** Task 3 依赖 Task 4（toHandBandFrame 签名），计划中已注明先做 Task 4。执行时可按 1→2→4→3→5→6→7→8→9→10 顺序。
