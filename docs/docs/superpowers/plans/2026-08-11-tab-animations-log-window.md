# 页面切换动效 + 其他页面动效 + 日志窗口增大 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 手机端 Tab 切换加入水平滑动+淡入动效，联系人/历史/设置页面元素依次入场并带关键交互过渡，主页整页可滚动且日志窗口更大、可独立滚动。

**Architecture:** 纯 UI 表现层改动。`BandQQApp.kt` 内容区用 Compose `AnimatedContent` 做切换过渡；新增 `UiMotion.kt` 导出通用 `listItemReveal` 入场 modifier 供各页面复用；`HomeScreen.kt` 外层改可滚动并把 `LogPanel` 从 `weight(1f)` 改为固定较高高度；`LogPanel.kt` 微调自动跟随逻辑。不新增依赖（Compose BOM 2024.09.03 已含 animation）。

**Tech Stack:** Kotlin, Jetpack Compose (BOM 2024.09.03), Miuix 0.9.3, Gradle 8.13 + JDK17。构建用 `D:\android-build\gradle-8.13\bin\gradle.bat`，环境变量见 Global Constraints。

## Global Constraints

- 不新增任何依赖；仅用 Compose animation/foundation 自带 API。
- 构建命令：设置 `JAVA_HOME=D:\android-build\jdk17\jdk-17.0.20+8`、`ANDROID_HOME=D:\android-sdk`、`ANDROID_SDK_ROOT=D:\android-sdk`、`GRADLE_USER_HOME=D:\android-build\gradle-home`，在 `C:\Users\wang\Desktop\band-qq\android-sync` 下运行 `& "D:\android-build\gradle-8.13\bin\gradle.bat" <task> --no-daemon`。
- 验证命令：`testDebugUnitTest` 与 `assembleRelease` 均须通过；失败时修复。
- 每个 Task 完成后按提供的 commit 命令提交（repo 为 `C:\Users\wang\Desktop\band-qq`）。
- 不修改手环端（band-qq/）任何文件。
- 沿用中文注释风格（现有代码为中文注释）。
- 参考 spec：`docs/superpowers/specs/2026-08-11-tab-animations-log-window-design.md`。

---

### Task 1: 新增公共入场动效工具 UiMotion

**Files:**
- Create: `android-sync/app/src/main/java/com/example/bandqq/ui/UiMotion.kt`

**Interfaces:**
- Produces: `fun Modifier.listItemReveal(entered: Boolean, index: Int, delayMs: Long = 60L): Modifier` — 供 Task 3/4/5 使用。

- [ ] **Step 1: 创建 UiMotion.kt**

```kotlin
package com.example.bandqq.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

/**
 * 列表项依次入场动效：按 index 延迟后淡入并上移（单次播放）。
 * 用于联系人/历史会话/设置项列表，形成级联入场效果。
 */
fun Modifier.listItemReveal(entered: Boolean, index: Int, delayMs: Long = 60L): Modifier = composed {
    val delay = (index * delayMs).toInt()
    val alpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(300, delayMillis = delay),
        label = "listRevealAlpha",
    )
    val offsetY by animateFloatAsState(
        targetValue = if (entered) 0f else 20f,
        animationSpec = tween(300, delayMillis = delay),
        label = "listRevealOffset",
    )
    graphicsLayer {
        this.alpha = alpha
        translationY = offsetY
    }
}
```

- [ ] **Step 2: 编译验证**

Run: 在 `android-sync` 目录按 Global Constraints 执行 `assembleRelease`。
Expected: BUILD SUCCESSFUL，无未使用 import 报错。

- [ ] **Step 3: 提交**

```bash
git add android-sync/app/src/main/java/com/example/bandqq/ui/UiMotion.kt
git commit -m "feat(phone): 新增列表项依次入场动效工具 UiMotion"
```

---

### Task 2: 页面切换动效（AnimatedContent）

**Files:**
- Modify: `android-sync/app/src/main/java/com/example/bandqq/ui/BandQQApp.kt:51-64`

**Interfaces:**
- Consumes: 现有 `AppTab` 枚举与各 Screen 函数（均不改签名）。
- Produces: 内容区切换动画；后续任务不依赖。

- [ ] **Step 1: 替换内容区 Box 为 AnimatedContent**

将 `BandQQApp.kt` 第 51-64 行的 `Box(...) { when (selected) {...} }` 替换为：

```kotlin
AnimatedContent(
    targetState = selected,
    transitionSpec = {
        (slideInHorizontally { it / 3 } + fadeIn(tween(220)))
            .togetherWith(slideOutHorizontally { -it / 3 } + fadeOut(tween(180)))
    },
    contentAlignment = Alignment.Center,
    modifier = Modifier
        .fillMaxSize()
        .padding(contentPadding),
    label = "tabSwitch",
) { tab ->
    when (tab) {
        AppTab.Home -> HomeScreen()
        AppTab.Contacts -> ContactScreen()
        AppTab.History -> HistoryScreen()
        AppTab.Settings -> SettingsScreen()
    }
}
```

新增 import：
```kotlin
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.ui.Alignment
```

移除不再使用的 `androidx.compose.foundation.layout.Box` import（如无其他引用）。

- [ ] **Step 2: 编译验证**

Run: `assembleRelease`。
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: 提交**

```bash
git add android-sync/app/src/main/java/com/example/bandqq/ui/BandQQApp.kt
git commit -m "feat(phone): 底部 Tab 切换加水平滑动+淡入动效"
```

---

### Task 3: 联系人页列表依次入场 + 私聊/群聊交叉淡入

**Files:**
- Modify: `android-sync/app/src/main/java/com/example/bandqq/ui/ContactScreen.kt:41-108`

**Interfaces:**
- Consumes: `Modifier.listItemReveal`（Task 1）。
- Produces: 无新接口。

- [ ] **Step 1: 加 entered 状态**

在 `ContactScreen` 函数体顶部（`var selected` 之后）加：
```kotlin
var entered by remember { mutableStateOf(false) }
LaunchedEffect(Unit) { entered = true }
```

新增 import：
```kotlin
import androidx.compose.runtime.LaunchedEffect
```
（`mutableStateOf` 已 import。）

- [ ] **Step 2: 联系人列表项应用 listItemReveal**

将第 110 行 `contacts().forEach { c ->` 改为 `contacts().forEachIndexed { index, c ->`，并将 Card 的 modifier 从：
```kotlin
modifier = Modifier.fillMaxWidth(),
```
改为：
```kotlin
modifier = Modifier.fillMaxWidth().listItemReveal(entered, index),
```

- [ ] **Step 3: 私聊/群聊切换交叉淡化**

将第 109-134 行的 `SmallTitle(...)` + 列表 `forEachIndexed {...}` 整段包在 `AnimatedContent` 里，`targetState = activeType`：

```kotlin
AnimatedContent(
    targetState = activeType,
    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(180)) },
    label = "contactType",
) { type ->
    Column {
        SmallTitle(text = if (type == "private") "私聊联系人" else "群聊联系人")
        contacts().forEachIndexed { index, c ->
            Card(
                modifier = Modifier.fillMaxWidth().listItemReveal(entered, index),
                colors = CardDefaults.defaultColors(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
                        Text(text = c.name.ifBlank { c.id })
                        Text(
                            text = if (type == "private") "私聊" else "群聊",
                            color = MiuixTheme.colorScheme.onSurfaceSecondary,
                        )
                    }
                    Checkbox(
                        state = if (c.id in selected) ToggleableState.On else ToggleableState.Off,
                        onClick = {
                            selected = if (c.id in selected) selected - c.id else selected + c.id
                        },
                    )
                }
            }
        }
        Spacer(modifier = Modifier.padding(top = 4.dp))
    }
}
```

注意：此改动后 `entered` 应在每次 `activeType` 变化时重置触发新入场。将 `entered` 声明改为 `var entered by remember(activeType) { mutableStateOf(false) }`，保持 `LaunchedEffect(activeType) { entered = true }`。

新增 import：
```kotlin
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
```

- [ ] **Step 4: 编译验证**

Run: `assembleRelease`。
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: 提交**

```bash
git add android-sync/app/src/main/java/com/example/bandqq/ui/ContactScreen.kt
git commit -m "feat(phone): 联系人列表依次入场 + 私聊/群聊切换交叉淡入"
```

---

### Task 4: 历史会话列表依次入场

**Files:**
- Modify: `android-sync/app/src/main/java/com/example/bandqq/ui/HistoryScreen.kt:40-102`

**Interfaces:**
- Consumes: `Modifier.listItemReveal`（Task 1）。
- Produces: 无新接口。

- [ ] **Step 1: 加 entered 状态**

在 `HistoryScreen` 函数体顶部加：
```kotlin
var entered by remember { mutableStateOf(false) }
LaunchedEffect(Unit) { entered = true }
```

新增 import：
```kotlin
import androidx.compose.runtime.LaunchedEffect
```
（`mutableStateOf` 已 import。）

- [ ] **Step 2: 会话列表项应用 listItemReveal**

将第 76 行 `conversations.forEach { conv ->` 改为 `conversations.forEachIndexed { index, conv ->`，并将 Card 的 modifier 从：
```kotlin
modifier = Modifier
    .fillMaxWidth()
    .clickable { detailConv = conv },
```
改为：
```kotlin
modifier = Modifier
    .fillMaxWidth()
    .listItemReveal(entered, index)
    .clickable { detailConv = conv },
```

- [ ] **Step 3: 编译验证**

Run: `assembleRelease`。
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: 提交**

```bash
git add android-sync/app/src/main/java/com/example/bandqq/ui/HistoryScreen.kt
git commit -m "feat(phone): 聊天记录列表项依次入场"
```

---

### Task 5: 设置页元素依次入场

**Files:**
- Modify: `android-sync/app/src/main/java/com/example/bandqq/ui/SettingsScreen.kt:31-105`

**Interfaces:**
- Consumes: `Modifier.listItemReveal`（Task 1）。
- Produces: 无新接口。

- [ ] **Step 1: 加 entered 状态**

在 `SettingsScreen` 函数体内 `loaded` 声明后加：
```kotlin
var entered by remember { mutableStateOf(false) }
LaunchedEffect(loaded) { if (loaded) entered = true }
```
（`LaunchedEffect` 已 import；`mutableStateOf` 已 import。）

- [ ] **Step 2: 各表单元素应用 listItemReveal**

给第 63-66 行四个 `TextField` 各加对应 index 的 modifier：

```kotlin
TextField(
    value = wsUrl, onValueChange = { wsUrl = it }, label = "WS 地址",
    modifier = Modifier.fillMaxWidth().listItemReveal(entered, 0),
)
TextField(
    value = wsToken, onValueChange = { wsToken = it }, label = "WS Token",
    modifier = Modifier.fillMaxWidth().listItemReveal(entered, 1),
)
TextField(
    value = httpUrl, onValueChange = { httpUrl = it }, label = "HTTP 地址",
    modifier = Modifier.fillMaxWidth().listItemReveal(entered, 2),
)
TextField(
    value = httpToken, onValueChange = { httpToken = it }, label = "HTTP Token",
    modifier = Modifier.fillMaxWidth().listItemReveal(entered, 3),
)
```

两个 Button 分别加 `.listItemReveal(entered, 4)` 与 `.listItemReveal(entered, 5)`（modifier 中已有 `fillMaxWidth()`）。

- [ ] **Step 3: 编译验证**

Run: `assembleRelease`。
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: 提交**

```bash
git add android-sync/app/src/main/java/com/example/bandqq/ui/SettingsScreen.kt
git commit -m "feat(phone): 设置页元素依次入场"
```

---

### Task 6: 主页整页可滚动 + 日志窗口固定较高

**Files:**
- Modify: `android-sync/app/src/main/java/com/example/bandqq/ui/HomeScreen.kt:47-132`

**Interfaces:**
- Consumes: 现有 Screen/组件。无新接口。
- Produces: `LogPanel` 高度改为独立固定；`HomeScreen` 外层可滚动。

- [ ] **Step 1: 外层 Column 改可滚动**

将 `HomeScreen` 的外层 `Column` modifier 从：
```kotlin
modifier = Modifier
    .fillMaxSize()
    .padding(16.dp),
```
改为：
```kotlin
modifier = Modifier
    .fillMaxSize()
    .verticalScroll(rememberScrollState())
    .padding(16.dp),
```

新增 import：
```kotlin
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
```

- [ ] **Step 2: LogPanel 改用固定较高高度**

将第 130 行：
```kotlin
LogPanel(modifier = Modifier.weight(1f).fillMaxWidth().enterReveal(entered, delayMs = 300))
```
改为：
```kotlin
LogPanel(
    modifier = Modifier
        .fillMaxWidth()
        .height(360.dp)
        .enterReveal(entered, delayMs = 300),
)
```

新增 import：
```kotlin
import androidx.compose.foundation.layout.height
```
（`height` 已 import 用于 StatusCard，若已在则跳过。）

- [ ] **Step 3: 去除非必要的 weight import（如无残留）**

检查 `HomeScreen.kt` 若不再使用 `Modifier.weight`，无需删除（Row 内 StatusCard 仍用 weight）。保留现有 import。

- [ ] **Step 4: 编译验证**

Run: `assembleRelease`。
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: 运行单元测试**

Run: `testDebugUnitTest`。
Expected: BUILD SUCCESSFUL（现有 LogBusTest 等通过）。

- [ ] **Step 6: 提交**

```bash
git add android-sync/app/src/main/java/com/example/bandqq/ui/HomeScreen.kt
git commit -m "feat(phone): 主页整页可滚动，日志窗口固定较高高度"
```

---

### Task 7: 日志窗口内部自动跟随逻辑微调

**Files:**
- Modify: `android-sync/app/src/main/java/com/example/bandqq/ui/LogPanel.kt:52-56`

**Interfaces:**
- Consumes: 现有 `LogEntry`/`LogBus`/`filter` 等。无新接口。

- [ ] **Step 1: 自动跟随条件化**

将 LogPanel 内第 52-56 行的自动跟随改为：仅当用户位于底部附近（手动上拉回看时不打断）才跟随。

```kotlin
LaunchedEffect(filtered.size) {
    if (filtered.isNotEmpty()) {
        val atBottom = scrollState.maxValue == 0 ||
            scrollState.value >= scrollState.maxValue - 200
        if (atBottom) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }
}
```

替换原：
```kotlin
LaunchedEffect(filtered.size) {
    if (filtered.isNotEmpty()) {
        scrollState.animateScrollTo(Int.MAX_VALUE)
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `assembleRelease`。
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: 运行单元测试**

Run: `testDebugUnitTest`。
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: 提交**

```bash
git add android-sync/app/src/main/java/com/example/bandqq/ui/LogPanel.kt
git commit -m "fix(phone): 日志自动跟随仅当位于底部时触发，不打断手动回看"
```

---

### Task 8: 全量验证 + 产出安装包

**Files:** 无代码改动。

**Interfaces:** 无。

- [ ] **Step 1: 全量构建 + 测试**

Run: `assembleRelease` 与 `testDebugUnitTest` 依次执行。
Expected: 两者均 BUILD SUCCESSFUL。

- [ ] **Step 2: 复制新 APK 到桌面**

将 `android-sync/app/build/outputs/apk/release/app-release.apk` 复制覆盖 `C:\Users\wang\Desktop\bandqq-phone-v1.0.0.apk`。

- [ ] **Step 3: 验证签名指纹**

Run: `& "D:\android-sdk\build-tools\35.0.0\apksigner.bat" verify --print-certs <桌面apk>`
Expected: SHA-256 = `62c7818b39e79038664360c83c2a4075ecfe654ef83755944253aadfafb4a190`，DN 含 `CN=BandQQ`。

- [ ] **Step 4: 确认 git 干净**

Run: `git -C C:\Users\wang\Desktop\band-qq status --short`
Expected: 无未提交改动。

---

## Self-Review

**Spec 覆盖：**
- 页面切换水平滑动+淡入 → Task 2 ✓
- 联系人/历史/设置列表依次入场 → Task 3/4/5 ✓
- 私聊/群聊关键交互过渡 → Task 3 Step 3 ✓
- 主页整页滚动 → Task 6 ✓
- 日志窗口更高、独立滚动、自动跟随+手动拖动 → Task 6 + Task 7 ✓
- 新增加的 UiMotion 工具 → Task 1 ✓

**Placeholder 扫描：** 无 TBD/TODO；所有代码步骤含完整代码。

**类型一致性：** `listItemReveal(entered: Boolean, index: Int, delayMs: Long = 60L)` 在各 Task 中调用一致；`AnimatedContent`/`slideInHorizontally` import 路径统一；Task 3 的 `for...forEachIndexed`/`contacts().forEachIndexed` 一致。

**潜在注意点：** Task 3 中 `Spacer` 移入 AnimatedContent 内后，`Column` 每侧需保留 spacing；原 `Column` 外层 verticalArrangement spacedBy(8.dp) 依然生效。Task 6 的 `height(360.dp)` 为经验值，可在验证时按需调整。