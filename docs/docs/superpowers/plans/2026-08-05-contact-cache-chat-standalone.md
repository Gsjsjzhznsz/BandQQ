# 连接后自动缓存联系人 + 聊天记录不依赖同步服务 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** SnowLuma 连接成功后后台自动拉取一次好友/群列表并持久化缓存;联系人管理页增加手动刷新按钮且打开即显缓存;手环未连接/同步服务未运行时也能在 App 查看历史聊天记录。

**Architecture:** 在 `MessageStore` 新增全量联系人缓存 KV(`contact_cache`,持久化),`MessageBroker.onState` 在连接边沿触发一次自动拉取(通过注入的拉取器,HTTP get_friend_list/get_group_list),解析逻辑抽成可单测纯函数。`ContactManagerActivity` 打开先显缓存再异步刷新并加「刷新联系人」按钮。`ChatHistoryActivity` 在 `StoreHolder.store` 为空时用 `SyncPreferencesKv` 兜底创建持久化 store。

**Tech Stack:** Kotlin + Android(SharedPreferences KV、OkHttp、Gson)、JUnit4 单测、手环 `node --test`(不受影响)。

## Global Constraints

- 构建命令:`& "D:\android-build\gradle-8.7\bin\gradle.bat" test --tests "com.example.bandqq.sync.*"`(在 `C:\Users\wang\Desktop\band-qq\android-sync` 下);全量测试用 `gradle test`。
- 手环测试命令:`npm test`(在 `C:\Users\wang\Desktop\band-qq\band-qq` 下),本计划不修改手环代码,但完成后需跑一次确认 25/25 不受影响。
- Kotlin 文件禁止加注释,除非任务明确给出;中文 UI 文案用简体中文,保留英文专有名词(如 SnowLuma、token)。
- `MessageStore` 现有 key 命名风格:`chat_messages`、`chat_conversations`、`visible_contacts`;新 key 用 `contact_cache`。
- 测试框架:JUnit4,类名用反引号中文方法名,`assertEquals`/`assertTrue`。
- 所有修改基于当前未提交的调试修复(工作树含 MainActivity/GameProtocolDetector/OneBotClient/OneBotParser 改动,HEAD=114d3fa),计划中的任务按增量提交,不触碰这些未提交改动的内容。

---
## 文件结构

| 文件 | 职责 | 动作 |
|---|---|---|
| `android-sync/app/src/main/java/com/example/bandqq/sync/MessageStore.kt` | 全量联系人缓存读写/持久化 | 修改 |
| `android-sync/app/src/main/java/com/example/bandqq/sync/ContactCache.kt` | 好友/群响应解析纯函数 + 拉取并缓存逻辑 | 新建 |
| `android-sync/app/src/main/java/com/example/bandqq/sync/MessageBroker.kt` | onState 连接边沿触发自动拉取 | 修改 |
| `android-sync/app/src/main/java/com/example/bandqq/sync/SyncService.kt` | 创建 ContactCache 并注入 broker | 修改 |
| `android-sync/app/src/main/java/com/example/bandqq/ContactManagerActivity.kt` | 刷新按钮、打开先显缓存、store 兜底 | 修改 |
| `android-sync/app/src/main/res/layout/activity_contact_manager.xml` | 刷新按钮 | 修改 |
| `android-sync/app/src/main/java/com/example/bandqq/ChatHistoryActivity.kt` | store 兜底创建 | 修改 |
| `android-sync/app/src/test/java/com/example/bandqq/sync/MessageStoreTest.kt` | 缓存读写/持久化用例 | 修改 |
| `android-sync/app/src/test/java/com/example/bandqq/sync/ContactCacheTest.kt` | 解析纯函数用例 | 新建 |
| `android-sync/app/src/test/java/com/example/bandqq/sync/MessageBrokerTest.kt` | 连接触发自动拉取用例 | 修改 |

---

### Task 1: MessageStore 新增全量联系人缓存

**Files:**
- Modify: `android-sync/app/src/main/java/com/example/bandqq/sync/MessageStore.kt`
- Test: `android-sync/app/src/test/java/com/example/bandqq/sync/MessageStoreTest.kt`

**Interfaces:**
- Produces:
  - `fun setCachedContacts(list: List<VisibleContact>)`
  - `fun getCachedContacts(): List<VisibleContact>`

- [ ] **Step 1: 写失败测试**

在 `MessageStoreTest.kt` 追加:

```kotlin
@Test
fun `cachedContacts 持久化往返`() {
    val kv = InMemoryKv()
    val s1 = MessageStore(kv)
    s1.setCachedContacts(listOf(VisibleContact("111", "private", "小明"), VisibleContact("222", "group", "群A")))
    val reload = MessageStore(kv)
    assertEquals(
        listOf(VisibleContact("111", "private", "小明"), VisibleContact("222", "group", "群A")),
        reload.getCachedContacts()
    )
}

@Test
fun `cachedContacts 覆盖写入`() {
    val store = MessageStore()
    store.setCachedContacts(listOf(VisibleContact("111", "private", "小明")))
    store.setCachedContacts(listOf(VisibleContact("222", "group", "群B")))
    assertEquals(listOf(VisibleContact("222", "group", "群B")), store.getCachedContacts())
}

@Test
fun `cachedContacts 损坏数据回退为空`() {
    val kv = InMemoryKv()
    kv.set("contact_cache", "not-json")
    val store = MessageStore(kv)
    assertEquals(emptyList<VisibleContact>(), store.getCachedContacts())
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `& "D:\android-build\gradle-8.7\bin\gradle.bat" test --tests "com.example.bandqq.sync.MessageStoreTest" -q`
Expected: 编译失败或 `getCachedContacts`/`setCachedContacts` 未定义

- [ ] **Step 3: 实现缓存**

在 `MessageStore.kt` 中,在 `private var visibleContacts` 声明旁新增:

```kotlin
private var cachedContacts: MutableList<VisibleContact> = loadCachedContacts()
```

`init` 块内追加 `cachedContacts = loadCachedContacts()`。

新增私有加载方法(仿 `loadVisibleContacts`):

```kotlin
private fun loadCachedContacts(): MutableList<VisibleContact> {
    val raw = storage.get(CACHED_KEY, "[]")
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

private fun persistCachedContacts() {
    val arr = JsonArray()
    for (c in cachedContacts) {
        val o = JsonObject()
        o.addProperty("id", c.id)
        o.addProperty("type", c.type)
        o.addProperty("name", c.name)
        arr.add(o)
    }
    storage.set(CACHED_KEY, arr.toString())
}
```

在常量区新增 key(紧邻 `VISIBLE_KEY`):

```kotlin
private const val CACHED_KEY = "contact_cache"
```

新增公开方法(放在 `setVisibleContacts` 附近):

```kotlin
fun getCachedContacts(): List<VisibleContact> = cachedContacts.toList()

fun setCachedContacts(list: List<VisibleContact>) {
    cachedContacts = list.distinctBy { it.id }.toMutableList()
    persistCachedContacts()
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `& "D:\android-build\gradle-8.7\bin\gradle.bat" test --tests "com.example.bandqq.sync.MessageStoreTest" -q`
Expected: 全绿(含新增 3 条)

- [ ] **Step 5: 提交**

```bash
git add android-sync/app/src/main/java/com/example/bandqq/sync/MessageStore.kt android-sync/app/src/test/java/com/example/bandqq/sync/MessageStoreTest.kt
git commit -m "feat(sync): MessageStore 新增全量联系人缓存(contact_cache)持久化"
```

---

### Task 2: 新建 ContactCache 解析纯函数

**Files:**
- Create: `android-sync/app/src/main/java/com/example/bandqq/sync/ContactCache.kt`
- Test: `android-sync/app/src/test/java/com/example/bandqq/sync/ContactCacheTest.kt`

**Interfaces:**
- Produces:
  - `fun parseContactResponse(type: String, raw: String?): List<VisibleContact>`
- Consumes: `VisibleContact`(来自 Task 1 的 MessageStore.kt)

- [ ] **Step 1: 写失败测试**

新建 `ContactCacheTest.kt`:

```kotlin
package com.example.bandqq.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class ContactCacheTest {

    @Test
    fun `解析好友列表`() {
        val raw = """{"status":"ok","data":[{"user_id":111,"nickname":"小明"},{"user_id":222,"nickname":"小红"}]}"""
        val list = parseContactResponse("private", raw)
        assertEquals(2, list.size)
        assertEquals(VisibleContact("111", "private", "小明"), list[0])
        assertEquals(VisibleContact("222", "private", "小红"), list[1])
    }

    @Test
    fun `解析群列表`() {
        val raw = """{"status":"ok","data":[{"group_id":999,"group_name":"测试群"}]}"""
        val list = parseContactResponse("group", raw)
        assertEquals(listOf(VisibleContact("999", "group", "测试群")), list)
    }

    @Test
    fun `null 或空 data 返回空列表`() {
        assertEquals(emptyList<VisibleContact>(), parseContactResponse("private", null))
        assertEquals(emptyList<VisibleContact>(), parseContactResponse("private", """{"status":"ok"}"""))
        assertEquals(emptyList<VisibleContact>(), parseContactResponse("private", "not-json"))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `& "D:\android-build\gradle-8.7\bin\gradle.bat" test --tests "com.example.bandqq.sync.ContactCacheTest" -q`
Expected: 编译失败或 `parseContactResponse` 未定义

- [ ] **Step 3: 实现纯函数**

新建 `ContactCache.kt`:

```kotlin
package com.example.bandqq.sync

import com.google.gson.JsonParser

object ContactCache {

    /** 解析 OneBot get_friend_list/get_group_list 响应为联系人列表 */
    fun parseContactResponse(type: String, raw: String?): List<VisibleContact> {
        if (raw == null) return emptyList()
        val out = mutableListOf<VisibleContact>()
        try {
            val data = JsonParser.parseString(raw).asJsonObject.get("data") ?: return emptyList()
            if (!data.isJsonArray) return emptyList()
            val arr = data.asJsonArray
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
        } catch (e: Exception) {
            return emptyList()
        }
        return out
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `& "D:\android-build\gradle-8.7\bin\gradle.bat" test --tests "com.example.bandqq.sync.ContactCacheTest" -q`
Expected: 全绿

- [ ] **Step 5: 提交**

```bash
git add android-sync/app/src/main/java/com/example/bandqq/sync/ContactCache.kt android-sync/app/src/test/java/com/example/bandqq/sync/ContactCacheTest.kt
git commit -m "feat(sync): ContactCache 解析好友/群响应纯函数"
```

---

### Task 3: MessageBroker 连接边沿自动拉取联系人

**Files:**
- Modify: `android-sync/app/src/main/java/com/example/bandqq/sync/MessageBroker.kt`
- Test: `android-sync/app/src/test/java/com/example/bandqq/sync/MessageBrokerTest.kt`

**Interfaces:**
- Consumes:
  - `ContactCache.parseContactResponse(type, raw)`(Task 2)
  - `MessageStore.getCachedContacts()` / `setCachedContacts(list)`(Task 1)
  - `com.example.bandqq.onebot.OneBotClient.requestApi(action, baseUrl, callback)`(现有,返回 `(String?) -> Unit`,callback 收到原始响应体或 null)
- Produces:
  - `MessageBroker` 构造器新增参数 `autoFetch: ((MessageStore) -> Unit)? = null`
  - `var autoFetchDone: Boolean`(可在测试中重置)
  - 私有 `private fun tryAutoFetch()`

- [ ] **Step 1: 写失败测试**

在 `MessageBrokerTest.kt` 追加:

```kotlin
@Test
fun `onState 连接成功时触发自动拉取`() {
    var fetched = false
    val broker = MessageBroker(parser, FakeOneBot { _, _, _ -> true }, MessageStore()) {
        fetched = true
    }
    broker.autoFetchDone = false
    broker.onState(true)
    assertTrue(fetched)
}

@Test
fun `onState 断开时不触发自动拉取`() {
    var fetched = false
    val broker = MessageBroker(parser, FakeOneBot { _, _, _ -> true }, MessageStore()) {
        fetched = true
    }
    broker.autoFetchDone = false
    broker.onState(false)
    assertTrue(!fetched)
}

@Test
fun `autoFetchDone 为真时不再触发`() {
    var count = 0
    val broker = MessageBroker(parser, FakeOneBot { _, _, _ -> true }, MessageStore()) {
        count++
    }
    broker.autoFetchDone = true
    broker.onState(true)
    broker.onState(true)
    assertEquals(0, count)
}
```

注意:`MessageBroker` 现有构造器为 `(parser, oneBot, store)` 三个参数。新增第四个带默认值的 lambda 参数,现有测试(构造三个参数)无需改动。

- [ ] **Step 2: 运行测试确认失败**

Run: `& "D:\android-build\gradle-8.7\bin\gradle.bat" test --tests "com.example.bandqq.sync.MessageBrokerTest" -q`
Expected: 编译失败(构造器无第四参数)

- [ ] **Step 3: 实现自动拉取触发**

在 `MessageBroker.kt`:

类声明改为:

```kotlin
class MessageBroker(
    private val parser: OneBotParser,
    private val oneBot: MessageSender,
    private val store: MessageStore,
    private val autoFetch: ((MessageStore) -> Unit)? = null
) : com.example.bandqq.onebot.OneBotListener {

    var autoFetchDone = false
```

`onState` 改为:

```kotlin
override fun onState(connected: Boolean) {
    SyncState.oneBotConnected = connected
    bandSender(SyncStatePush.buildFrame())
    if (connected && !autoFetchDone) {
        autoFetchDone = true
        tryAutoFetch()
    }
}

private fun tryAutoFetch() {
    autoFetch?.invoke(store)
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `& "D:\android-build\gradle-8.7\bin\gradle.bat" test --tests "com.example.bandqq.sync.MessageBrokerTest" -q`
Expected: 全绿(含新增 3 条,原有 9 条不回归)

- [ ] **Step 5: 提交**

```bash
git add android-sync/app/src/main/java/com/example/bandqq/sync/MessageBroker.kt android-sync/app/src/test/java/com/example/bandqq/sync/MessageBrokerTest.kt
git commit -m "feat(sync): MessageBroker 连接成功边沿触发一次联系人自动拉取"
```

---

### Task 4: SyncService 注入自动拉取实现

**Files:**
- Modify: `android-sync/app/src/main/java/com/example/bandqq/sync/SyncService.kt`

**Interfaces:**
- Consumes:
  - `MessageBroker` 第四参数 `autoFetch`(Task 3)
  - `OneBotClient.requestApi(action, baseUrl, callback)`(现有)
  - `ContactCache.parseContactResponse(type, raw)`(Task 2)
  - `MessageStore.setCachedContacts(list)`(Task 1)
  - `ConfigHolder.config.endpoint.httpUrl`(现有)

- [ ] **Step 1: 修改 SyncService 注入拉取器**

在 `SyncService.kt` 的 `onCreate` 中,创建 broker 处(第 90 行)改为:

```kotlin
broker = MessageBroker(parser, oneBot, store) { s ->
    val http = ConfigHolder.config.endpoint.httpUrl
    oneBot.requestApi("get_friend_list", http) { raw ->
        val list = ContactCache.parseContactResponse("private", raw)
        if (list.isNotEmpty()) s.setCachedContacts(s.getCachedContacts().filter { it.type != "private" } + list)
    }
    oneBot.requestApi("get_group_list", http) { raw ->
        val list = ContactCache.parseContactResponse("group", raw)
        if (list.isNotEmpty()) s.setCachedContacts(s.getCachedContacts().filter { it.type != "group" } + list)
    }
}
```

注意:好友和群两个回调可能乱序返回,`setCachedContacts` 内 `distinctBy { it.id }` 会按 id 去重,但为了不互相覆盖不同类型,合并时按 type 过滤保留另一类型。此逻辑在 Task 4 不单测(涉及 OkHttp 异步),由 Task 2 纯函数与 Task 3 触发机制单测覆盖;手动验证在收尾阶段执行。

- [ ] **Step 2: 编译验证**

Run: `& "D:\android-build\gradle-8.7\bin\gradle.bat" compileDebugKotlin -q`
Expected: BUILD SUCCESSFUL(无编译错误)

- [ ] **Step 3: 提交**

```bash
git add android-sync/app/src/main/java/com/example/bandqq/sync/SyncService.kt
git commit -m "feat(sync): SyncService 连接成功后自动拉取好友/群并写入联系人缓存"
```

---

### Task 5: 联系人管理页刷新按钮 + 缓存优先展示

**Files:**
- Modify: `android-sync/app/src/main/res/layout/activity_contact_manager.xml`
- Modify: `android-sync/app/src/main/java/com/example/bandqq/ContactManagerActivity.kt`

**Interfaces:**
- Consumes:
  - `MessageStore.getCachedContacts()` / `setCachedContacts(list)`(Task 1)
  - `ContactCache.parseContactResponse(type, raw)`(Task 2)
  - `StoreHolder.store`(现有)

- [ ] **Step 1: 布局加刷新按钮**

在 `activity_contact_manager.xml` 顶部 Tab 行下方、`contactHint` 之前,插入:

```xml
    <Button
        android:id="@+id/refreshContactsBtn"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="刷新联系人" />
```

- [ ] **Step 2: 改造 ContactManagerActivity**

在 `ContactManagerActivity.kt`:

- `onCreate` 中绑定按钮:

```kotlin
findViewById<View>(R.id.refreshContactsBtn).setOnClickListener { loadContacts() }
```

- 新增 `loadCachedIntoCurrentTab()`(打开页面立即用缓存填充,保持当前 tab):

```kotlin
private fun loadCachedIntoCurrentTab() {
    val cached = StoreHolder.store?.getCachedContacts() ?: return
    if (currentType == "private") friends = cached.filter { it.type == "private" }.toMutableList()
    else groups = cached.filter { it.type == "group" }.toMutableList()
    refreshCurrentTab()
}
```

- `loadContacts()` 成功后写回缓存。修改 `applyList`:

```kotlin
private fun applyList(type: String, raw: String?) {
    val rows = parseContacts(type, raw)
    if (type == "private") friends = rows else groups = rows
    val store = StoreHolder.store
    if (store != null) {
        val cached = store.getCachedContacts().filter { it.type != type }
        store.setCachedContacts(cached + rows)
    }
    refreshCurrentTab()
}
```

- `onCreate` 中,`loadContacts()` 前先调用 `loadCachedIntoCurrentTab()`:

```kotlin
loadCachedIntoCurrentTab()
loadContacts()
```

`onResume` 保持 `loadContacts()`(回到页面刷新),但为避免闪空,在 `loadContacts()` 前也调用 `loadCachedIntoCurrentTab()`。

- [ ] **Step 3: 编译验证**

Run: `& "D:\android-build\gradle-8.7\bin\gradle.bat" compileDebugKotlin -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 提交**

```bash
git add android-sync/app/src/main/res/layout/activity_contact_manager.xml android-sync/app/src/main/java/com/example/bandqq/ContactManagerActivity.kt
git commit -m "feat(ui): 联系人页加刷新按钮,打开先显缓存再异步刷新"
```

---

### Task 6: 聊天记录查看兜底初始化

**Files:**
- Modify: `android-sync/app/src/main/java/com/example/bandqq/ChatHistoryActivity.kt`

**Interfaces:**
- Consumes: `SyncPreferencesKv(context)`(现有)、`MessageStore(storage)`(现有)、`StoreHolder.setStore(s)`(现有)

- [ ] **Step 1: 兜底创建 store**

在 `ChatHistoryActivity.onCreate` 中,`setupList()` 前加入:

```kotlin
if (StoreHolder.store == null) {
    StoreHolder.setStore(MessageStore(SyncPreferencesKv(this)))
}
```

`onResume` 中同理(在 `setupList()` 前)。因 `onResume` 每次都会执行,兜底条件为空才创建,不覆盖已运行服务的 store。

- [ ] **Step 2: 编译验证**

Run: `& "D:\android-build\gradle-8.7\bin\gradle.bat" compileDebugKotlin -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add android-sync/app/src/main/java/com/example/bandqq/ChatHistoryActivity.kt
git commit -m "feat(ui): 聊天记录页兜底创建持久化 store,不依赖同步服务运行"
```

---

### Task 7: 全量回归验证

**Files:** 无代码改动。

- [ ] **Step 1: Android 全量单测**

Run: `& "D:\android-build\gradle-8.7\bin\gradle.bat" test -q`(在 android-sync 下)
Expected: 全绿。测试总数应为 42 + 3(MessageStore 缓存) + 3(ContactCacheTest) + 3(MessageBroker 自动拉取)= **51**,全部通过。

- [ ] **Step 2: 手环测试回归**

Run: `npm test`(在 `C:\Users\wang\Desktop\band-qq\band-qq` 下)
Expected: 25/25 通过(本计划未改手环代码)。

- [ ] **Step 3: 构建 APK 验证**

Run: `& "D:\android-build\gradle-8.7\bin\gradle.bat" assembleDebug -q`
Expected: BUILD SUCCESSFUL,产出 `android-sync/app/build/outputs/apk/debug/app-debug.apk`

- [ ] **Step 4: 手动验证清单(交付前)**

1. 启动同步并连接 SnowLuma → 等 1-2 秒,打开「管理手环联系人」应**立即**看到缓存的好友/群,随后自动刷新为最新。
2. 联系人页点击「刷新联系人」→ 列表刷新。
3. **不启动同步服务**,直接打开「查看聊天记录」→ 能看到历史会话/消息(此前为空白)。
4. 在模拟器内填入 WS/HTTP 地址与 token,点「测试 SnowLuma 连接」→ WS/HTTP 均可连接。

- [ ] **Step 5: 汇报**

向用户报告:新增功能与修复已实现、测试全绿、APK 已构建,并附手动验证清单结果。
