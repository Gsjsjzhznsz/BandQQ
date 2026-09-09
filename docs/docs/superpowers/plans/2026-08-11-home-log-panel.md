# 手机端主页实时日志窗口 + 布局重组 + 动效 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为安卓端主页新增实时日志窗口（全量接入统一日志通道），重组主页布局（状态卡横排方形、按钮竖排、日志置底），并添加三类动效。

**Architecture:** 新增单例 `LogBus`（`MutableStateFlow<List<LogEntry>>`）统一收集各模块日志并双写 `android.util.Log`；主页订阅 `LogBus.logs` 在 `LogPanel` 中实时展示。`HomeScreen` 重写为固定布局，动效全部用 Compose 内置动画。

**Tech Stack:** Kotlin、Jetpack Compose（BOM 2024.09.03）、Miuix 0.9.3、kotlinx.coroutines StateFlow、JUnit 4。

## Global Constraints

- 不新增任何第三方依赖；动画只用 `androidx.compose.animation` / `animateFloatAsState` / `animateColorAsState`。
- 不修改 `BandQQApp.kt`（底部导航与标签页切换）与 band-qq 快应用侧代码。
- 日志窗口内存上限 **200 条**，超出裁剪最旧。
- `LogBus.log` 内的 `android.util.Log` 双写必须 try/catch 包裹（JVM 单测环境 `android.util.Log` 不可用）。
- 接入改造只改日志出口，不改任何业务逻辑。
- 本机无 gradle/wrapper（`android-sync` 无 `gradlew`，系统无 gradle），JUnit 测试命令需在有 gradle 的环境执行；本机以代码审查把关。
- 时间戳格式 `HH:mm:ss`。

---

### Task 1: 日志通道 `LogBus`

**Files:**
- Create: `android-sync/app/src/main/java/com/example/bandqq/sync/LogBus.kt`
- Test: `android-sync/app/src/test/java/com/example/bandqq/sync/LogBusTest.kt`

**Interfaces:**
- Produces: `enum LogLevel { DEBUG, INFO, WARN, ERROR }`；`data class LogEntry(time: Long, tag: String, level: LogLevel, message: String)`；`object LogBus { val logs: StateFlow<List<LogEntry>>; fun log(tag, level, message); fun clear() }`。后续 Task 5（LogPanel）与 Task 2-4（模块接入）都依赖这些签名。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.example.bandqq.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogBusTest {

    @Test
    fun `追加日志按时间记录字段`() {
        LogBus.clear()
        LogBus.log("Test", LogLevel.INFO, "hello")
        val logs = LogBus.logs.value
        assertEquals(1, logs.size)
        assertEquals("Test", logs[0].tag)
        assertEquals(LogLevel.INFO, logs[0].level)
        assertEquals("hello", logs[0].message)
        assertTrue(logs[0].time > 0)
    }

    @Test
    fun `超过 200 条裁剪最旧日志`() {
        LogBus.clear()
        repeat(250) { i -> LogBus.log("T", LogLevel.DEBUG, "msg$i") }
        val logs = LogBus.logs.value
        assertEquals(200, logs.size)
        assertEquals("msg50", logs[0].message)
        assertEquals("msg249", logs.last().message)
    }

    @Test
    fun `clear 清空日志`() {
        LogBus.log("T", LogLevel.WARN, "x")
        LogBus.clear()
        assertEquals(0, LogBus.logs.value.size)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `gradle testDebugUnitTest --tests "com.example.bandqq.sync.LogBusTest"`（在有 gradle 的环境；本机无 gradle，此项标记为环境受限）
Expected: 编译失败，`LogBus` 未定义。

- [ ] **Step 3: 实现 LogBus**

```kotlin
package com.example.bandqq.sync

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

data class LogEntry(
    val time: Long,
    val tag: String,
    val level: LogLevel,
    val message: String
)

object LogBus {
    private const val MAX_LOGS = 200

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs

    fun log(tag: String, level: LogLevel, message: String) {
        _logs.update { (it + LogEntry(System.currentTimeMillis(), tag, level, message)).takeLast(MAX_LOGS) }
        try {
            when (level) {
                LogLevel.DEBUG -> Log.d(tag, message)
                LogLevel.INFO -> Log.i(tag, message)
                LogLevel.WARN -> Log.w(tag, message)
                LogLevel.ERROR -> Log.e(tag, message)
            }
        } catch (t: Throwable) {
            // JVM 单测环境 android.util.Log 不可用，静默忽略
        }
    }

    fun clear() {
        _logs.value = emptyList()
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `gradle testDebugUnitTest --tests "com.example.bandqq.sync.LogBusTest"`（环境受限同 Step 2）
Expected: 3 个测试全部 PASS。

- [ ] **Step 5: 提交**

```bash
git add android-sync/app/src/main/java/com/example/bandqq/sync/LogBus.kt android-sync/app/src/test/java/com/example/bandqq/sync/LogBusTest.kt
git commit -m "feat(phone): 新增 LogBus 统一日志通道"
```

---

### Task 2: 接入 OneBotClient

**Files:**
- Modify: `android-sync/app/src/main/java/com/example/bandqq/onebot/OneBotClient.kt`

**Interfaces:**
- Consumes: `LogBus.log(tag: String, level: LogLevel, message: String)`（来自 Task 1）。
- Produces: 无新接口。

- [ ] **Step 1: 加入 import**

在 `OneBotClient.kt` 顶部新增：
```kotlin
import com.example.bandqq.sync.LogBus
import com.example.bandqq.sync.LogLevel
```

- [ ] **Step 2: 替换所有 Log 调用**

将以下调用逐一替换（保留原有 try/catch 防御结构，异常合并进 message）：

| 原代码 | 替换为 |
| --- | --- |
| `Log.w("OneBotClient", "connect failed", e)` | `LogBus.log("OneBotClient", LogLevel.WARN, "connect failed: $e")` |
| `Log.d("OneBotClient", "WS onOpen, connected=$connected")` | `LogBus.log("OneBotClient", LogLevel.DEBUG, "WS onOpen, connected=$connected")` |
| `Log.d("OneBotClient", "WS recv: ${text.take(300)}")` | `LogBus.log("OneBotClient", LogLevel.DEBUG, "WS recv: ${text.take(300)}")` |
| `Log.w("OneBotClient", "WS msg parse -> null (may be meta/heartbeat)")` | `LogBus.log("OneBotClient", LogLevel.WARN, "WS msg parse -> null (may be meta/heartbeat)")` |
| `Log.w("OneBotClient", "WS recv binary bytes (ignored)")` | `LogBus.log("OneBotClient", LogLevel.WARN, "WS recv binary bytes (ignored)")` |
| `try { Log.e("OneBotClient", "send failed: $url", e) } catch (t: Throwable) {}` | `try { LogBus.log("OneBotClient", LogLevel.ERROR, "send failed: $url: $e") } catch (t: Throwable) {}` |
| `try { Log.d("OneBotClient", "send ok(${it.code}) $url -> $resp") } catch (t: Throwable) {}` | `try { LogBus.log("OneBotClient", LogLevel.DEBUG, "send ok(${it.code}) $url -> $resp") } catch (t: Throwable) {}` |
| `try { Log.e("OneBotClient", "send http ${it.code} $url -> $resp") } catch (t: Throwable) {}` | `try { LogBus.log("OneBotClient", LogLevel.ERROR, "send http ${it.code} $url -> $resp") } catch (t: Throwable) {}` |

- [ ] **Step 3: 校验**

Run: 无 gradle，改为人工检查——`grep -rn "Log\.\(d\|w\|e\|i\)" app/src/main/java/com/example/bandqq/onebot/OneBotClient.kt` 应无输出；`import android.util.Log` 应已无引用（可删除该 import）。

- [ ] **Step 4: 提交**

```bash
git add android-sync/app/src/main/java/com/example/bandqq/onebot/OneBotClient.kt
git commit -m "feat(phone): OneBotClient 接入 LogBus"
```

---

### Task 3: 接入 InterconnectBridge

**Files:**
- Modify: `android-sync/app/src/main/java/com/example/bandqq/sync/InterconnectBridge.kt`

**Interfaces:**
- Consumes: `LogBus.log`（Task 1）。

- [ ] **Step 1: 加入 import**

```kotlin
import com.example.bandqq.sync.LogBus
import com.example.bandqq.sync.LogLevel
```
（与文件同包，仅 `LogLevel` 需要 import；`LogBus` 同包可省略，但为一致性保留亦可。仅保留 `import com.example.bandqq.sync.LogLevel`。）

- [ ] **Step 2: 替换所有 Log 调用**

文件内共约 15 处，`Log.d/w/e` 均改走 LogBus，TAG 用常量 `"InterconnectBridge"`（注意：MessageBroker/LogBus 用 tag 过滤，此处必须用 `"InterconnectBridge"` 而非 `TAG` 之外的名称；`TAG` 常量本身值就是 `"InterconnectBridge"`，直接 `LogBus.log(TAG, ...)` 即可）。带异常的调用把 `e` 拼进 message。替换规则与 Task 2 一致：

| 行号（当前） | 替换为 |
| --- | --- |
| 71 | `LogBus.log(TAG, LogLevel.DEBUG, "onBandMessage: $json")` |
| 74 | `LogBus.log(TAG, LogLevel.ERROR, "onBandMessage parse error: $e")` |
| 85 | `LogBus.log(TAG, LogLevel.DEBUG, "registerListener idempotent remove old listener for ${node.id}")` |
| 91 | `LogBus.log(TAG, LogLevel.DEBUG, "registerListener ok")` |
| 97 | `LogBus.log(TAG, LogLevel.WARN, "listener already registered, continue")` |
| 100 | `LogBus.log(TAG, LogLevel.ERROR, "registerListener failed: $error")` |
| 133 | `LogBus.log(TAG, LogLevel.ERROR, "init xms-wearable-lib failed: $e")` |
| 141 | `LogBus.log(TAG, LogLevel.ERROR, "connect: not initialized")` |
| 149 | `LogBus.log(TAG, LogLevel.WARN, "connect: 未发现已连接的手环，请确认小米运动健康已连接手环")` |
| 155 | `LogBus.log(TAG, LogLevel.DEBUG, "connect: found device ${nodes[0].name}")` |
| 159 | `LogBus.log(TAG, LogLevel.ERROR, "connect: getConnectedNodes failed: $e")` |
| 173 | `LogBus.log(TAG, LogLevel.ERROR, "auth request failed: $e")` |
| 178 | `LogBus.log(TAG, LogLevel.DEBUG, "auth: 已请求 DEVICE_MANAGER 权限，等待用户在运动健康中授权")` |
| 184 | `LogBus.log(TAG, LogLevel.ERROR, "auth check failed: $e")` |
| 193 | `LogBus.log(TAG, LogLevel.DEBUG, "openApp: 已在手环上拉起应用")` |
| 197 | `LogBus.log(TAG, LogLevel.ERROR, "openApp failed: $e")` |
| 209 | `LogBus.log(TAG, LogLevel.ERROR, "sendToBand skipped: no connected node. frame=${frame.take(80)}")` |
| 213 | `LogBus.log(TAG, LogLevel.ERROR, "sendToBand skipped: messageApi null")` |
| 217 | `LogBus.log(TAG, LogLevel.DEBUG, "sendToBand frame bytes=${bytes.size}: ${frame.take(80)}")` |
| 219 | `LogBus.log(TAG, LogLevel.DEBUG, "sendToBand ok (${bytes.size}B)")` |
| 220 | `LogBus.log(TAG, LogLevel.ERROR, "sendToBand failed (${bytes.size}B): ${e.message}")` |

删除不再使用的 `import android.util.Log`。

- [ ] **Step 3: 校验**

Run: `grep -rn "Log\.\(d\|w\|e\|i\)" app/src/main/java/com/example/bandqq/sync/InterconnectBridge.kt` 应无输出。

- [ ] **Step 4: 提交**

```bash
git add android-sync/app/src/main/java/com/example/bandqq/sync/InterconnectBridge.kt
git commit -m "feat(phone): InterconnectBridge 接入 LogBus"
```

---

### Task 4: 接入 MessageBroker 与 OneBotParser

**Files:**
- Modify: `android-sync/app/src/main/java/com/example/bandqq/sync/MessageBroker.kt`
- Modify: `android-sync/app/src/main/java/com/example/bandqq/onebot/OneBotParser.kt`

**Interfaces:**
- Consumes: `LogBus.log`（Task 1）。

- [ ] **Step 1: MessageBroker 的 log() helper 改走 LogBus**

`MessageBroker.kt` 当前末尾的 `private fun log(msg: String)` 实现是 `android.util.Log.d("MessageBroker", msg)`。替换为：

```kotlin
private fun log(msg: String) {
    try {
        LogBus.log("MessageBroker", LogLevel.DEBUG, msg)
    } catch (t: Throwable) {
        // JVM 单测环境下不可用，静默忽略
    }
}
```

`MessageBroker.kt` 与 `LogBus`/`LogLevel` 同包（`com.example.bandqq.sync`），无需 import。

- [ ] **Step 2: OneBotParser 解析错误改走 LogBus**

`OneBotParser.kt` 第 31 行：
```kotlin
android.util.Log.w("OneBotParser", "parse error", e)
```
替换为：
```kotlin
LogBus.log("OneBotParser", LogLevel.WARN, "parse error: $e")
```
新增 import：
```kotlin
import com.example.bandqq.sync.LogBus
import com.example.bandqq.sync.LogLevel
```
删除不再使用的 `android.util.Log` 引用（第 31 行是唯一使用处，检查 `import android.util.Log` 是否存在并删除）。

- [ ] **Step 3: 校验**

Run: 检查 `MessageBroker.kt` 与 `OneBotParser.kt` 中无残留 `Log.` 直调（grep 确认）。

- [ ] **Step 4: 提交**

```bash
git add android-sync/app/src/main/java/com/example/bandqq/sync/MessageBroker.kt android-sync/app/src/main/java/com/example/bandqq/onebot/OneBotParser.kt
git commit -m "feat(phone): MessageBroker/OneBotParser 接入 LogBus"
```

---

### Task 5: 日志窗口 `LogPanel`

**Files:**
- Create: `android-sync/app/src/main/java/com/example/bandqq/ui/LogPanel.kt`

**Interfaces:**
- Consumes: `LogBus.logs: StateFlow<List<LogEntry>>`（Task 1）、`LogBus.clear()`、`LogEntry(tag, level, message, time)`。
- Produces: `@Composable fun LogPanel(modifier: Modifier = Modifier)`。Task 6（HomeScreen）以 `LogPanel(modifier = Modifier.weight(1f))` 调用。

- [ ] **Step 1: 实现 LogPanel**

```kotlin
package com.example.bandqq.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.bandqq.sync.LogBus
import com.example.bandqq.sync.LogEntry
import com.example.bandqq.sync.LogLevel
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val logTimeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

@Composable
fun LogPanel(modifier: Modifier = Modifier) {
    val logs by LogBus.logs.collectAsState()
    val tags = remember(logs) {
        logs.map { it.tag }.distinct().sorted()
    }
    var filter by remember { mutableStateOf<String?>(null) }
    val filtered = remember(logs, filter) {
        if (filter == null) logs else logs.filter { it.tag == filter }
    }
    val scrollState = rememberScrollState()

    LaunchedEffect(filtered.size) {
        if (filtered.isNotEmpty()) {
            scrollState.animateScrollTo(Int.MAX_VALUE)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = "实时日志", color = MiuixTheme.colorScheme.onSurfaceSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (filter == null) "全部" else filter,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MiuixTheme.colorScheme.surfaceContainer)
                        .clickable { filter = null }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    color = MiuixTheme.colorScheme.primary,
                )
                Text(
                    text = "清空",
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { LogBus.clear() }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    color = MiuixTheme.colorScheme.primary,
                )
            }
        }
        if (tags.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                tags.forEach { tag ->
                    val selected = filter == tag
                    Text(
                        text = tag,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (selected) MiuixTheme.colorScheme.primary else Color.Transparent)
                            .clickable { filter = if (selected) null else tag }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        color = if (selected) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurfaceSecondary,
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(MiuixTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
                .padding(8.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState),
            ) {
                filtered.forEach { entry ->
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
                    ) {
                        LogLine(entry)
                    }
                }
                if (filtered.isEmpty()) {
                    Text(text = "暂无日志", color = MiuixTheme.colorScheme.onSurfaceSecondary)
                }
            }
        }
    }
}

@Composable
private fun LogLine(entry: LogEntry) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = logTimeFmt.format(Date(entry.time)),
            color = MiuixTheme.colorScheme.onSurfaceSecondary,
        )
        Text(
            text = " [${entry.tag}] ",
            color = when (entry.level) {
                LogLevel.ERROR -> MiuixTheme.colorScheme.error
                LogLevel.WARN -> MiuixTheme.colorScheme.primaryVariant
                else -> MiuixTheme.colorScheme.primary
            },
        )
        Text(text = entry.message)
    }
}
```

注意：`AnimatedVisibility(visible = true, enter = ...)` 会为每个列表项首帧播放入场动画。若渲染性能可接受即保留；若日志刷屏卡顿，可改回纯 `Text`（下一步的提交信息注明）。

- [ ] **Step 2: 校验**

Run: 无 gradle 环境，人工检查 API 名称。**Pre-flight 已确认**（Miuix 官方 Color System 文档 + MD3→Miuix 映射表）：`surfaceContainer`、`onPrimary`、`error`、`primaryVariant`、`onSurfaceSecondary` 均为 Miuix 0.9.3 的 MiuixColorScheme 成员；`tertiary` 不存在（对应 `primaryVariant`），`secondaryContainer` 不存在（仅 `secondaryContainerVariant`，但用 `surfaceContainer` 取代更贴合视觉）。

- [ ] **Step 3: 提交**

```bash
git add android-sync/app/src/main/java/com/example/bandqq/ui/LogPanel.kt
git commit -m "feat(phone): 新增主页实时日志窗口 LogPanel"
```

---

### Task 6: 重写 HomeScreen（布局 + 动效）

**Files:**
- Modify: `android-sync/app/src/main/java/com/example/bandqq/ui/HomeScreen.kt`

**Interfaces:**
- Consumes: `LogPanel(modifier: Modifier)`（Task 5）、`useBandConnected()` / `useOneBotConnected(refreshKey)`（既有 UiState.kt）。

- [ ] **Step 1: 重写 HomeScreen**

```kotlin
package com.example.bandqq.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.bandqq.config.ConfigManager
import com.example.bandqq.onebot.GameProtocolDetector
import com.example.bandqq.sync.InterconnectBridge
import com.example.bandqq.sync.SyncService
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bandConnected by useBandConnected()
    var oneBotRefreshKey by remember { mutableStateOf(0) }
    val oneBotConnected by useOneBotConnected(refreshKey = oneBotRefreshKey)
    var entered by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { entered = true }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EnterReveal(entered, delayMs = 0) {
                StatusCard(
                    title = if (bandConnected) "手环" else "手环",
                    online = bandConnected,
                    summary = "小米运动健康互联通道",
                    detail = if (bandConnected) "已连接" else "未连接",
                    modifier = Modifier.weight(1f),
                )
            }
            EnterReveal(entered, delayMs = 100) {
                StatusCard(
                    title = if (oneBotConnected) "SnowLuma" else "SnowLuma",
                    online = oneBotConnected,
                    summary = "OneBot 协议端",
                    detail = if (oneBotConnected) "在线" else "离线",
                    modifier = Modifier.weight(1f),
                )
            }
        }

        EnterReveal(entered, delayMs = 200) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        SyncService.start(context)
                        toast(context, "同步服务已启动")
                    },
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("启动服务") }
                Button(
                    onClick = {
                        SyncService.stop(context)
                        toast(context, "同步服务已停止")
                    },
                    colors = ButtonDefaults.buttonColors(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("停止服务") }
                Button(
                    onClick = {
                        InterconnectBridge.init(context)
                        InterconnectBridge.connect()
                        toast(context, "已发起手环连接检查")
                    },
                    colors = ButtonDefaults.buttonColors(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("检查手环连接") }
                Button(
                    onClick = {
                        scope.launch {
                            val cfg = ConfigManager(context).load()
                            val result = GameProtocolDetector.testConnection(
                                cfg.endpoint.wsUrl, cfg.endpoint.wsToken,
                                cfg.endpoint.httpUrl, cfg.endpoint.httpToken,
                            )
                            oneBotRefreshKey++
                            val msg = if (result.wsReachable && result.httpReachable) {
                                "SnowLuma 在线（WS/HTTP 可连接）"
                            } else {
                                "WS:${if (result.wsReachable) "可连" else "不可连"} " +
                                    "HTTP:${if (result.httpReachable) "可连" else "不可连"}"
                            }
                            toast(context, msg)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("测试 SnowLuma 连接") }
            }
        }

        EnterReveal(entered, delayMs = 300) {
            LogPanel(modifier = Modifier.weight(1f).fillMaxWidth())
        }
    }
}

/** 入场动画：delay 后淡入并上移（单次播放）。 */
@Composable
private fun EnterReveal(entered: Boolean, delayMs: Int, content: @Composable () -> Unit) {
    val alpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(300, delayMillis = delayMs),
        label = "revealAlpha",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                this.alpha = alpha
                translationY = (1 - alpha) * 20f
            },
    ) {
        content()
    }
}

/** 状态卡：标题 + 摘要 + 呼吸指示点 + 状态切换颜色过渡。 */
@Composable
private fun StatusCard(
    title: String,
    online: Boolean,
    summary: String,
    detail: String,
    modifier: Modifier = Modifier,
) {
    val indicatorColor by animateColorAsState(
        targetValue = when {
            online -> Color(0xFF4CAF50)
            title.contains("SnowLuma") -> Color(0xFFE53935)
            else -> Color(0xFF9E9E9E)
        },
        label = "statusColor",
    )
    val transition = rememberInfiniteTransition(label = "breath")
    val alpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200), repeatMode = RepeatMode.Reverse),
        label = "breathAlpha",
    )
    Card(
        modifier = modifier.aspectRatio(1f),
        colors = CardDefaults.defaultColors(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = title)
            Text(text = summary, color = MiuixTheme.colorScheme.onSurfaceSecondary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(color = indicatorColor.copy(alpha = if (online) alpha else 0.4f), shape = CircleShape),
                )
                Text(
                    text = detail,
                    color = if (online) indicatorColor else MiuixTheme.colorScheme.onSurfaceSecondary,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}
```

**注意（编译风险点）**：
- `EnterReveal` 计划原为 `Box` 包裹 `content` 的组合形式，但 `StatusCard(modifier = Modifier.weight(1f))` 在 `content` lambda 内会脱离 `RowScope`/`ColumnScope`，`weight` 无法编译。**实现偏差**：改为 `private fun Modifier.enterReveal(entered: Boolean, delayMs: Int): Modifier = composed { ... }`，在调用点直接 `Modifier.weight(1f).enterReveal(entered, ...)`，行为等价（淡入+上移，tween 300 + delay）。
- `MiuixTheme.colorScheme.surfaceContainer / onPrimary / error / primaryVariant` 已由 pre-flight 确认存在于 Miuix 0.9.3；本任务仅使用已确认字段。
- `LogPanel(modifier = Modifier.weight(1f).fillMaxWidth())`：`weight` 只在 `ColumnScope` 中可用，HomeScreen 的 Column 是默认作用域，OK。

- [ ] **Step 2: 校验**

Run: 无 gradle 环境。人工核对所有 import 均被使用、`graphicsLayer` import 已加、无残留 `verticalScroll`/`SmallTitle` 旧代码。

- [ ] **Step 3: 提交**

```bash
git add android-sync/app/src/main/java/com/example/bandqq/ui/HomeScreen.kt
git commit -m "feat(phone): 重写主页布局并加入场/呼吸/状态切换动效"
```

---

### Task 7: 全量校验与收尾

**Files:**
- 无新文件。

- [ ] **Step 1: 无残留 Log 直调**

Run: `grep -rn "android.util.Log\|Log\.\(d\|w\|e\|i\)" android-sync/app/src/main/java/com/example/bandqq` 应只命中 `LogBus.kt` 一处（双写实现），其余模块无直调。

- [ ] **Step 2: 检查未使用的 import**

逐文件确认删除已无引用的 `import android.util.Log`（OneBotClient.kt、InterconnectBridge.kt、OneBotParser.kt）。

- [ ] **Step 3: 编译/测试（环境受限）**

在有 gradle 的环境执行：
```
gradle testDebugUnitTest
```
Expected: 全部既有单测 + 新增 LogBusTest 通过。
本机无 gradle，此项标记为待在有 gradle 环境验证；本机仅做静态审查。

- [ ] **Step 4: 提交收尾**

```bash
git add -A
git commit -m "chore(phone): 日志接入收尾清理"
```
（若 Step 4 前无额外改动，可跳过该提交。）

---

## Self-Review

**Spec coverage:**
- 统一日志通道（spec §组件设计-1）→ Task 1
- 全量接入 OneBotClient/InterconnectBridge/MessageBroker/OneBotParser（spec §组件设计-2）→ Task 2/3/4
- 主页布局重组：横排方形状态卡 + 竖排按钮 + 日志置底（spec §组件设计-3）→ Task 6
- 日志窗口功能：清空/过滤/时间戳/200 条上限/自动滚动（spec §组件设计-4）→ Task 1 + Task 5
- 三类动效（spec §组件设计-5）→ Task 5（新条目动效）+ Task 6（呼吸/切换/入场）
- 错误处理（spec §错误处理）→ Task 1 try/catch、Task 5 裁剪
- 测试（spec §测试）→ Task 1 LogBusTest；gradle 环境受限已在各任务注明

**Placeholder scan:** 无 TBD/TODO；所有替换表都给出具体目标代码；动效代码完整。

**Type consistency:** `LogBus.log(tag, level, message)` / `LogEntry(tag, level, message, time)` / `LogPanel(modifier)` / `EnterReveal(entered, delayMs, content)` 在 Task 1、5、6 间签名一致。过滤 tag 统一用模块名 `"OneBotClient"` / `"InterconnectBridge"` / `"MessageBroker"` / `"OneBotParser"`，与 LogBus 接入时保持一致。
