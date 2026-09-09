# Miuix 手机端 UI 重构实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 `android-sync` 手机端从传统 View（XML+ViewBinding+多 Activity）重构为 Compose + Miuix 0.9.3 的单 Activity 底部导航界面，业务层零改动。

**Architecture:** 单一 `MainActivity` 承载 Compose，`ui/` 包内 `BandQQApp` 用 `NavigationBar` 切换四个 Tab（主页/联系人/聊天记录/设置）。UI 通过现有单例状总线（`SyncState`/`BandStateBus`/`MessageBus`）与单例数据仓（`StoreHolder.store`）取数，通过单例服务入口（`SyncService`/`InterconnectBridge`/`ConfigManager`/`OneBotClient`）触发动作。全部业务类不改写。

**Tech Stack:** Kotlin 2.0.21 (K2) + `org.jetbrains.kotlin.plugin.compose`、AndroidX Compose BOM `2024.09.03`、miuix `0.9.3`（`miuix-ui-android`）+ `miuix-icons-android`、Gradle 8.7、AGP 8.5.2、JDK17、minSdk 26 / targetSdk 34、compileSdk 34。

## Global Constraints

- **版本锁定（Task 1 验证后签名确认）：** Kotlin `2.0.21`（含 `compose` 编译器插件同版本）、`androidx.compose:compose-bom:2024.09.03`、`top.yukonga.miuix.kmp:miuix-ui-android:0.9.3`、`top.yukonga.miuix.kmp:miuix-icons-android:0.9.3`。若 Task 1 构建显示 Kotlin/Compose 编译器不兼容，只允许升级 Kotlin 前缀保持全项目一致（根 build.gradle.kts 中三处同版本号），其余不变。
- **业务层只读：** `SyncService.kt`、`OneBotClient.kt`、`MessageStore.kt`、`InterconnectBridge.kt`、`MessageBroker.kt`、`OneBotParser.kt`、`GameProtocolDetector.kt`、`ConfigManager.kt`、`SyncStatePush.kt`、`SyncPreferencesKv.kt`、`ContactCache.kt` 一律不改。新 UI 文件一律放 `app/src/main/java/com/example/bandqq/ui/` 包。
- **已知 API 签名（Task 2 起直接使用，勿改动拼写）：**
  - `top.yukonga.miuix.kmp.basic.NavigationBar(modifier, color, showDivider, defaultWindowInsetsPadding, mode, content: RowScope.() -> Unit)`
  - `top.yukonga.miuix.kmp.basic.RowScope.NavigationBarItem(selected, onClick, icon: ImageVector, label, modifier, enabled, badge)`
  - `top.yukonga.miuix.kmp.basic.Scaffold(bottomBar, topBar, modifier, ...)` — Miuix 版，参数有 `bottomBar`、`topBar`、`contentPadding`（外层 `content: @Composable PaddingValues.() -> Unit` 除以 PaddingValues 分发的调用方式，见 Task 3 代码）
  - `top.yukonga.miuix.kmp.basic.Button(onClick, modifier, enabled, cornerRadius, minWidth, minHeight, colors, insideMargin, interactionSource, indication, content: RowScope.() -> Unit)`；主按钮 `ButtonDefaults.buttonColorsPrimary()`；普通 `ButtonDefaults.buttonColors()`；`top.yukonga.miuix.kmp.basic.TextButton(text, onClick, modifier, colors = ButtonDefaults.textButtonColorsPrimary(), ...)`
  - `top.yukonga.miuix.kmp.basic.TextField(value: String, onValueChange: (String) -> Unit, label: String = "", useLabelAsPlaceholder: Boolean = false, ...)`
  - `top.yukonga.miuix.kmp.basic.Card(modifier, cornerRadius, insideMargin, colors, content: ColumnScope.() -> Unit)`（不可点击版本）；可点击版本多了 `onClick`/`onLongPress` 参数。`CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer, ...)`。
  - `top.yukonga.miuix.kmp.basic.Switch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier, colors, enabled)`
  - `top.yukonga.miuix.kmp.basic.Checkbox(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, ...)`
  - `top.yukonga.miuix.kmp.basic.SmallTitle(text, maxLines, ...)` — 分段标题
  - `top.yukonga.miuix.kmp.basic.Divider()` — 分隔线
  - `top.yukonga.miuix.kmp.basic.ListItem(titleText, summaryText, endAccessory, modifier, onClick, enabled, detailText)` — 设置/列表行（Task 4 起用）
  - `top.yukonga.miuix.kmp.theme.MiuixTheme(content = { ... })`；配色经 `MiuixTheme.colorScheme`（`surfaceContainer`、`primary`、`onPrimary`、`secondaryVariant`、`onSurfaceContainer`、`onSurfaceVariant` 等）
  - `top.yukonga.miuix.kmp.basic.TopAppBar(title: String, icon: ImageVector?, ...)` / `SmallTopAppBar(ActionIconAction)` — 页面标题栏
  - 图标：`top.yukonga.miuix.kmp.icon.extended.MiuixIcons`（扩展属性 `MiuixIcons.Home`、`Messages`、`Contacts`、`Settings`、`Delete`、`Refresh`、`Search`、`Close`、`Clear`、`Ok`、`Copy`、`Info`、`Link`、`Update` 等为 `ImageVector`）。**需在 `miuix-ui-android` 旁额外依赖 `miuix-icons-android:0.9.3`。**
  - Miuix 的 `Scaffold/Button/TextField/Card` 均为标准 Compose 组件，运行在 androidx compose runtime 上，可混用 `androidx.compose.foundation.layout`、`material3` BOM 组件。
- **删除清单固定（Task 7 执行，不得遗漏或额外增删）：** `app/src/main/java/com/example/bandqq/MainActivity.kt`（重写而非删除）、`ChatHistoryActivity.kt`、`ContactManagerActivity.kt`（删除）；`res/layout/activity_main.xml`、`activity_chat_history.xml`、`activity_contact_manager.xml`、`item_contact_checkable.xml`（删除）；`AndroidManifest.xml` 移除两个 Activity 声明。
- **构建门禁统一命令**（每次构建/验证均用，task 中写 `Run: 构建门禁命令`）：
  ```powershell
  $env:JAVA_HOME = "D:\android-build\jdk17\jdk-17.0.20+8"
  $env:GRADLE_USER_HOME = "D:\android-build\gradle-home"
  & "D:\android-build\gradle-8.7\bin\gradle.bat" -p "C:\Users\wang\Desktop\band-qq\android-sync" assembleRelease --no-daemon
  ```
- 本项目不是 git 仓库。每步的"提交"以「构建通过 + 更新 `docs/superpowers/plans/status.md` 进度清单」替代 git commit。

---

### Task 1: 工具链升级与最小 Miuix 探测

**Files:**
- Modify: `android-sync/build.gradle.kts`（根）
- Modify: `android-sync/app/build.gradle.kts`
- Create: `app/src/main/java/com/example/bandqq/ui/ProbeScreen.kt`
- Modify: `app/src/main/java/com/example/bandqq/MainActivity.kt`

**Interfaces:**
- Consumes: 无。
- Produces: 锁定三件套版本（Kotlin 2.0.21 / compose-bom 2024.09.03 / miuix 0.9.3）；`MainActivity` 可渲染一个 Miuix `Button`；证明 Compose+miuix 链路可编译打包。

- [ ] **Step 1: 更新根 build.gradle.kts**

用 Write 覆盖 `android-sync/build.gradle.kts` 为：

```kotlin
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
```

- [ ] **Step 2: 更新 app/build.gradle.kts**

覆盖 `android-sync/app/build.gradle.kts` 为：

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
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

    signingConfigs {
        create("release") {
            val ks = rootProject.file("../keystore.jks")
            storeFile = if (ks.exists()) ks else rootProject.file("keystore.jks")
            storePassword = "bandqq123"
            keyAlias = "bandqq"
            keyPassword = "bandqq123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
        compose = true
    }
}

dependencies {
    implementation(files("libs/xms-wearable-lib_1.4_release.aar"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.code.gson:gson:2.11.0")

    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.1")

    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
```

说明：移除了 `viewBinding = true`。`compose-bom` 提供 androidx 基础组件，miuix 用自己的主题层。

- [ ] **Step 3: 创建 ProbeScreen.kt 探针**

`app/src/main/java/com/example/bandqq/ui/ProbeScreen.kt`：

```kotlin
package com.example.bandqq.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.extended.MiuixIcons
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ProbeScreen() {
    MiuixTheme {
        Scaffold { contentPadding ->
            Button(
                onClick = {},
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text("Miuix ${MiuixIcons.Home.hashCode()}")
            }
        }
    }
}
```

注意：Miuix 的 `Scaffold` 的 `content` 是 `@Composable PaddingValues.() -> Unit`，用 `contentPadding` 命名即可。若 `fillMaxSize().padding(contentPadding)` 编译不过，改为 `Modifier.fillMaxSize()`（按钮不随 inset 也无碍，探测阶段只验证可编译）。

- [ ] **Step 4: 重置 MainActivity 为探针**

覆盖 `app/src/main/java/com/example/bandqq/MainActivity.kt` 为：

```kotlin
package com.example.bandqq

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.bandqq.ui.ProbeScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ProbeScreen() }
    }
}
```

注意类改成 `ComponentActivity`（原为 `AppCompatActivity`，旧绑定的 `binding` 已删除）。

- [ ] **Step 5: 构建验证**

Run: 构建门禁命令。
Expected: `BUILD SUCCESSFUL`。`asklsr/build/outputs/apk/release/...-release.apk` 生成。
若失败：
- Kotlin 插件与应用配置警告 `composeOptions.kotlinCompilerExtensionVersion` 旧格式 → 忽略（K2 用 `org.jetbrains.kotlin.plugin.compose`，无需该选项）。
- `Could not resolve ... miuix-ui-android:0.9.3` → 检查 `settings.gradle.kts` 的 `repositories { mavenCentral() }` 存在（已存在）。
- Compose 运行时与 miuix 编译报错 → 尝试将 compose-bom 升级到 `2025.06.01`，若仍失败向负责人反馈并暂停（不要自行降级 miuix）。
- 其它编译错误：按错误信息修复后再跑。

- [ ] **Step 6: 记录锁定版本与进度**

写入 `docs/superpowers/plans/status.md`（不存在则创建）：

```markdown
# 实施进度
- [x] Task 1 工具链升级与 Miuix 探测（Kotlin 2.0.21 / compose-bom 2024.09.03 / miuix 0.9.3）
- [ ] Task 2 主题 + 状态基座
- [ ] Task 3 底部导航壳 + 四页面骨架
- [ ] Task 4 主页 HomeScreen
- [ ] Task 5 联系人 ContactScreen
- [ ] Task 6 聊天记录 HistoryScreen + 会话详情
- [ ] Task 7 设置 SettingsScreen
- [ ] Task 8 删除旧 View 层 + 全局验收
```

---

### Task 2: 主题 + 状态与工具基座

**Files:**
- Create: `app/src/main/java/com/example/bandqq/ui/Theme.kt`
- Create: `app/src/main/java/com/example/bandqq/ui/UiState.kt`
- Create: `app/src/main/java/com/example/bandqq/ui/BandQQApp.kt`（占位：先只放 Tab 枚举与路由定义，Task 3 填 UI）
- Modify: `app/src/main/java/com/example/bandqq/ui/BandQQApp.kt`

**Interfaces:**
- Consumes: `MiuixTheme`、`SyncState`、`BandStateBus`。
- Produces（后续所有 Task 依赖，签名固化）:
  - `@Composable fun BandQQTheme(content: @Composable () -> Unit)` — 包一层 `MiuixTheme { content() }`。
  - `enum class AppTab(val label: String) { Home, Contacts, History, Settings }`。
  - `@Composable fun useBandConnected(): State<Boolean>` — 监听 `BandStateBus`，初始取 `SyncState.bandConnected`，事件到即更新。
  - `@Composable fun useOneBotConnected(): State<Boolean>` — 仅读 `SyncState.oneBotConnected` + 一个可手动触发重读的 key（见 Task 4 用法），本 Task 只要求读一次。
  - `fun toast(context: Context, msg: String)` — 复用 `Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()`。

- [ ] **Step 1: 写 Theme.kt**

`app/src/main/java/com/example/bandqq/ui/Theme.kt`：

```kotlin
package com.example.bandqq.ui

import androidx.compose.runtime.Composable
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun BandQQTheme(content: @Composable () -> Unit) {
    MiuixTheme(content = content)
}
```

- [ ] **Step 2: 写 UiState.kt**

`app/src/main/java/com/example/bandqq/ui/UiState.kt`：

```kotlin
package com.example.bandqq.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.example.bandqq.sync.BandStateBus
import com.example.bandqq.sync.SyncState

@Composable
fun useBandConnected(): State<Boolean> {
    val state = remember { mutableStateOf(SyncState.bandConnected) }
    DisposableEffect(Unit) {
        val listener: (Boolean) -> Unit = { connected -> state.value = connected }
        BandStateBus.add(listener)
        onDispose { BandStateBus.remove(listener) }
    }
    return state
}

fun toast(context: Context, msg: String) {
    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}
```

`useOneBotConnected` 放 Task 4（因为需要可刷新 key），本 Task 不写。

- [ ] **Step 3: 建 BandQQApp.kt 占位（Tab 枚举）**

`app/src/main/java/com/example/bandqq/ui/BandQQApp.kt`：

```kotlin
package com.example.bandqq.ui

enum class AppTab(val label: String) {
    Home("主页"),
    Contacts("联系人"),
    History("聊天记录"),
    Settings("设置"),
}
```

- [ ] **Step 4: 构建验证**

Run: 构建门禁命令。Expected: `BUILD SUCCESSFUL`。若 `MiuixTheme` 内容 lambda 参数名有出入按 IDE/错误提示调整。

- [ ] **Step 5: 更新进度**

`status.md` 勾选 Task 2 并记录产物接口名（`useBandConnected`、`toast`、`AppTab`）。

---

### Task 3: 底部导航壳 + 四页面骨架

**Files:**
- Modify: `app/src/main/java/com/example/bandqq/ui/BandQQApp.kt`
- Create: `app/src/main/java/com/example/bandqq/ui/HomeScreen.kt`（骨架：只标题+占位文案）
- Create: `app/src/main/java/com/example/bandqq/ui/ContactScreen.kt`（骨架）
- Create: `app/src/main/java/com/example/bandqq/ui/HistoryScreen.kt`（骨架）
- Create: `app/src/main/java/com/example/bandqq/ui/SettingsScreen.kt`（骨架）
- Modify: `app/src/main/java/com/example/bandqq/MainActivity.kt`

**Interfaces:**
- Consumes: `AppTab`、`BandQQTheme`。
- Produces:
  - `@Composable fun BandQQApp()` — 完整底部导航容器：`BandQQTheme { Scaffold(bottomBar = { NavigationBar { ...4 个 NavigationBarItem... } }) { contentPadding -> when(tab) {...四个页面各自 Composable...} } }`。
  - `@Composable fun HomeScreen()`（骨架，后续补参数）等四个页面函数，均空壳 `SmallTopAppBar(title) + Placeholder`。

- [ ] **Step 1: 写完整 BandQQApp()**

覆盖 `ui/BandQQApp.kt` 为（含 Tab 枚举与路由）：

```kotlin
package com.example.bandqq.ui

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.icon.extended.MiuixIcons

enum class AppTab(val label: String) {
    Home("主页"),
    Contacts("联系人"),
    History("聊天记录"),
    Settings("设置"),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BandQQApp() {
    var current by remember { mutableStateOf(AppTab.Home) }
    BandQQTheme {
        androidx.compose.material3.Scaffold(
            bottomBar = {
                NavigationBar {
                    AppTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = current == tab,
                            onClick = { current = tab },
                            icon = when (tab) {
                                AppTab.Home -> MiuixIcons.Home
                                AppTab.Contacts -> MiuixIcons.Contacts
                                AppTab.History -> MiuixIcons.Messages
                                AppTab.Settings -> MiuixIcons.Settings
                            },
                            label = tab.label,
                        )
                    }
                }
            },
        ) { contentPadding ->
            when (current) {
                AppTab.Home -> HomeScreen()
                AppTab.Contacts -> ContactScreen()
                AppTab.History -> HistoryScreen()
                AppTab.Settings -> SettingsScreen()
            }
        }
    }
}
```

说明：外层 `Scaffold` 用 androidx material3 的（Miuix 自有 Scaffold 也接受 bottomBar/topBar，二者皆可；为减少不确定采用 material3 BOM 的 Scaffold）。若 `AppTab.entries`（Kotlin 2.0 可用）不识别，改用 `AppTab.values().forEach`。`icon` 参数类型是 `ImageVector`，MiuixIcons 扩展属性正是该类型。

- [ ] **Step 2: 建四个页面骨架**

`HomeScreen.kt`：

```kotlin
package com.example.bandqq.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TopAppBar

@Composable
fun HomeScreen() {
    top.yukonga.miuix.kmp.basic.Scaffold(
        topBar = { TopAppBar(title = "主页") },
    ) { contentPadding ->
        SmallTitle(
            text = "主页（骨架）",
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        )
    }
}
```

`ContactScreen.kt`、`HistoryScreen.kt`、`SettingsScreen.kt` 同构（标题分别为「联系人」「聊天记录」「设置」）。若调用 Miuix Scaffold 时 `contentPadding` 类型不符，把 `Modifier.padding(contentPadding)` 换成 `Modifier.padding(16.dp)`（骨架无关紧要）。

- [ ] **Step 3: 接入 MainActivity**

覆盖 `MainActivity.kt`：

```kotlin
package com.example.bandqq

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.bandqq.ui.BandQQApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BandQQApp() }
    }
}
```

- [ ] **Step 4: 构建验证**

Run: 构建门禁命令。Expected: `BUILD SUCCESSFUL`。若 `MiuixIcons.Messages` 等不存在（需在 `miuix-icons` 里确认），改用已确认存在的 `Home/Contacts/Settings + Refresh`，并记录到 status.md。

- [ ] **Step 5: 更新进度**

`status.md` 勾选 Task 3，记录四个页面函数已建、导航可用。

---

### Task 4: 主页 HomeScreen（连接状态卡 + 操作)

**Files:**
- Modify: `app/src/main/java/com/example/bandqq/ui/HomeScreen.kt`
- Modify: `app/src/main/java/com/example/bandqq/ui/UiState.kt`

**Interfaces:**
- Consumes: `useBandConnected()`、`SyncState`、`SyncService.start/stop`、`InterconnectBridge.connect/init`、`StoreHolder.store`、`toast`、`ConfigManager.load`。
- Produces: `@Composable fun HomeScreen()` 定稿；`@Composable fun useOneBotConnected(refreshKey: Int): State<Boolean>`（Task 中一并补到 UiState.kt，供测试连接后刷新）。

- [ ] **Step 1: 向 UiState.kt 追加 useOneBotConnected**

追加到 `UiState.kt`（保留原内容）：

```kotlin
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

@Composable
fun useOneBotConnected(refreshKey: Int): State<Boolean> {
    val state = remember { mutableStateOf(SyncState.oneBotConnected) }
    val key = remember(refreshKey) { refreshKey }
    state.value = SyncState.oneBotConnected
    return state
}
```

（`refreshKey` 变化触发重组并读取最新值。）

- [ ] **Step 2: 写 HomeScreen() 完整实现**

用 Miuix 组件构建完整主页。覆盖 `HomeScreen.kt`：

```kotlin
package com.example.bandqq.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.bandqq.config.ConfigManager
import com.example.bandqq.onebot.GameProtocolDetector
import com.example.bandqq.sync.InterconnectBridge
import com.example.bandqq.sync.SyncService
import com.example.bandqq.sync.SyncState
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.ListItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bandConnected by useBandConnected()
    val oneBotConnected by useOneBotConnected(refreshKey = 0)

    top.yukonga.miuix.kmp.basic.Scaffold(
        topBar = { TopAppBar(title = "QQ 同步器") },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(),
            ) {
                ListItem(
                    titleText = if (bandConnected) "手环：已连接" else "手环：未连接",
                    summaryText = "小米运动健康互联通道",
                    detailText = if (bandConnected) "在线" else "离线",
                )
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(),
            ) {
                ListItem(
                    titleText = if (oneBotConnected) "SnowLuma：在线" else "SnowLuma：离线",
                    summaryText = "OneBot 协议端",
                    detailText = if (oneBotConnected) "在线" else "离线",
                )
            }

            SmallTitle(text = "操作")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        SyncService.start(context)
                        toast(context, "同步服务已启动")
                    },
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                ) { Text("启动服务") }
                Button(
                    onClick = {
                        SyncService.stop(context)
                        toast(context, "同步服务已停止")
                    },
                    colors = ButtonDefaults.buttonColors(),
                    modifier = Modifier.weight(1f),
                ) { Text("停止服务") }
            }
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

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "当前状态：${if (bandConnected && oneBotConnected) "互联已连接，SnowLuma 在线" else if (oneBotConnected) "SnowLuma 在线，等待手环" else if (bandConnected) "手环已连接，等待 SnowLuma" else "未连接（请先启动同步服务）"}",
                color = MiuixTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
```

修正已知疑点：`GameProtocolDetector.testConnection` 返回类型为 `ConnectionTestResult`（含 wsReachable/httpReachable），验证时确认 import 与字段名；`Row` 的 `weight` 需要 `RowScope`，此处 `Row` 块内直接可用（Button 的 modifier.weight 在 RowScope 下合法）。若某 `Miuix` 组件参数名对不上，以编译错误信息为唯一依据修正并记录到 status.md。

- [ ] **Step 3: 构建验证**

Run: 构建门禁命令。Expected: `BUILD SUCCESSFUL`。核对 `GameProtocolDetector.ConnectionTestResult` 字段名（读 `GameProtocolDetector.kt` 确认），不一致则改。

- [ ] **Step 4: 更新进度**

`status.md` 勾选 Task 4。

---

### Task 5: 联系人管理 ContactScreen

**Files:**
- Modify: `app/src/main/java/com/example/bandqq/ui/ContactScreen.kt`
- Create: `app/src/main/java/com/example/bandqq/ui/ContactViewModel.kt`（轻量状态封装，或内联 Composable 状态亦可）

**Interfaces:**
- Consumes: `StoreHolder.store.getCachedContacts()`、`StoreHolder.store.getVisibleContacts()`、`StoreHolder.store.setVisibleContacts(list)`、`OneBotClient`（重新拉取）、`ConfigHolder.config.endpoint.httpUrl`、`InterconnectBridge`、`toast`。
- Produces: `@Composable fun ContactScreen()` 定稿（含 私聊/群聊 分段 tab 切换、勾选保存、刷新）。

- [ ] **Step 1: 写 ContactScreen()**

覆盖 `ui/ContactScreen.kt`：

```kotlin
package com.example.bandqq.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.bandqq.config.ConfigHolder
import com.example.bandqq.onebot.OneBotClient
import com.example.bandqq.onebot.OneBotParser
import com.example.bandqq.sync.InterconnectBridge
import com.example.bandqq.sync.StoreHolder
import com.example.bandqq.sync.VisibleContact
import com.google.gson.JsonParser
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.ListItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar

@Composable
fun ContactScreen() {
    val context = LocalContext.current
    val store = StoreHolder.store
    var activeType by remember { mutableStateOf("private") }
    var refreshKey by remember { mutableStateOf(0) }

    fun contacts(): List<VisibleContact> {
        val all = store?.getCachedContacts() ?: emptyList()
        return all.filter { it.type == activeType }
    }
    val visibleIds = remember(refreshKey) { (store?.getVisibleContacts() ?: emptyList()).map { it.id }.toSet() }
    var selected by remember { mutableStateOf(visibleIds.toMutableSet()) }

    top.yukonga.miuix.kmp.basic.Scaffold(
        topBar = { TopAppBar(title = "联系人管理") },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    text = "私聊",
                    onClick = { activeType = "private" },
                    colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColorsPrimary(),
                )
                TextButton(
                    text = "群聊",
                    onClick = { activeType = "group" },
                )
                SpacerWeight(1f)
                TextButton(
                    text = "刷新",
                    onClick = {
                        val client = OneBotClient(OneBotParser())
                        val http = ConfigHolder.config.endpoint.httpUrl
                        client.requestApi("get_friend_list", http) { raw ->
                            applyContactList("private", raw, context)
                            refreshKey++
                        }
                        client.requestApi("get_group_list", http) { raw ->
                            applyContactList("group", raw, context)
                            refreshKey++
                        }
                    },
                )
            }
            SmallTitle(text = if (activeType == "private") "私聊联系人" else "群聊联系人")
            contacts().forEach { c ->
                ListItem(
                    titleText = c.name.ifBlank { c.id },
                    summaryText = if (activeType == "private") "私聊" else "群聊",
                    endAccessory = {
                        Checkbox(
                            checked = c.id in selected,
                            onCheckedChange = { checked ->
                                if (checked) selected.add(c.id) else selected.remove(c.id)
                            },
                        )
                    },
                )
            }
            TextButton(
                text = "保存选中联系人到手环",
                onClick = {
                    val store = StoreHolder.store
                    if (store == null) {
                        toast(context, "同步服务尚未启动，请先启动同步")
                        return@TextButton
                    }
                    val checkedList = contacts().filter { it.id in selected }
                    val merged = store.getVisibleContacts().filter { it.type != activeType } + checkedList
                    store.setVisibleContacts(merged)
                    if (InterconnectBridge.available && InterconnectBridge.isNodeReady()) {
                        InterconnectBridge.sendToBand(store.buildVisibleContactsFrame(0))
                        toast(context, "已保存并同步到手环")
                    } else {
                        toast(context, "已保存，但手环未连接，将在连接后自动同步")
                    }
                },
            )
        }
    }
}

@Composable
private fun SpacerWeight(weight: Float) {
    androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(weight))
}

private fun applyContactList(type: String, raw: String?, context: android.content.Context) {
    val store = StoreHolder.store ?: return
    if (raw == null) return
    val parsed = parseContactsRaw(type, raw)
    if (parsed.isNotEmpty()) {
        val others = store.getCachedContacts().filter { it.type != type }
        store.setCachedContacts(others + parsed)
    }
}

private fun parseContactsRaw(type: String, raw: String?): List<VisibleContact> {
    val out = mutableListOf<VisibleContact>()
    try {
        val data = JsonParser.parseString(raw ?: return out).asJsonObject.get("data") ?: return out
        if (!data.isJsonArray) return out
        for (e in data.asJsonArray) {
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
```

修正疑点：`SpacerWeight` 是为了在 RowScope 外使用 weight——其实 `Row` 内容本就是 `RowScope`，可直接 `Modifier.weight(1f)`，删除 `SpacerWeight` 直接在 `Spacer(modifier = Modifier.weight(1f))` 使用。`ListItem` 参数若为 `endAccessory` 且类型 `@Composable () -> Unit`，则 `Checkbox` 写法成立；否则按编译错误调整（可能为 `trailing` 参数）。`Checkbox(checked, onCheckedChange)` 签名沿用 miuix。

- [ ] **Step 2: 构建验证**

Run: 构建门禁命令。Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 3: 更新进度**

`status.md` 勾选 Task 5。

---

### Task 6: 聊天记录 HistoryScreen + 会话消息详情

**Files:**
- Modify: `app/src/main/java/com/example/bandqq/ui/HistoryScreen.kt`
- Create: `app/src/main/java/com/example/bandqq/ui/HistoryDetailSheet.kt`

**Interfaces:**
- Consumes: `StoreHolder.store.getConversations()`、`getAllMessages(id)`、`MessageBus`、`clearAllHistory()`、`InterconnectBridge.sendToBand`、`toast`、`MiuixTheme.colorScheme`。
- Produces: `@Composable fun HistoryScreen()`、`@Composable fun HistoryDetailDialog(conv: ConversationInfo, onDismiss: () -> Unit)` — 会话消息弹窗。

- [ ] **Step 1: 写 HistoryScreen()**

覆盖 `ui/HistoryScreen.kt`：

```kotlin
package com.example.bandqq.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.bandqq.sync.ConversationInfo
import com.example.bandqq.sync.InterconnectBridge
import com.example.bandqq.sync.MessageBus
import com.example.bandqq.sync.StoreHolder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.yukonga.miuix.kmp.basic.ListItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar

private val timeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

@Composable
fun HistoryScreen() {
    val context = LocalContext.current
    var refresh by remember { mutableStateOf(0) }
    val conversations = remember(refresh) { StoreHolder.store?.getConversations() ?: emptyList() }
    val listener = remember { { _: String -> refresh++ } }
    androidx.compose.runtime.DisposableEffect(Unit) {
        MessageBus.add(listener)
        onDispose { MessageBus.remove(listener) }
    }

    var detailConv by remember { mutableStateOf<ConversationInfo?>(null) }

    top.yukonga.miuix.kmp.basic.Scaffold(
        topBar = { TopAppBar(title = "聊天记录") },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(16.dp),
        ) {
            TextButton(
                text = "清空全部聊天记录",
                onClick = {
                    StoreHolder.store?.clearAllHistory()
                    InterconnectBridge.sendToBand("""{"type":"clear_all_history","seq":0}""")
                    toast(context, "聊天记录已清空")
                    refresh++
                },
            )
            SmallTitle(text = "会话 ${conversations.size} 个")
            conversations.forEach { conv ->
                ListItem(
                    titleText = conv.name,
                    summaryText = timeFmt.format(Date(conv.time)),
                    endAccessory = { /* 空位可放角标 */ },
                    onClick = { detailConv = conv },
                )
            }
        }
    }
    val c = detailConv
    if (c != null) {
        HistoryDetailDialog(conv = c, onDismiss = { detailConv = null })
    }
}
```

- [ ] **Step 2: 写 HistoryDetailDialog()**

`ui/HistoryDetailSheet.kt`：

```kotlin
package com.example.bandqq.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.bandqq.sync.ConversationInfo
import com.example.bandqq.sync.StoreHolder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun HistoryDetailDialog(conv: ConversationInfo, onDismiss: () -> Unit) {
    val msgs = StoreHolder.store?.getAllMessages(conv.id) ?: emptyList()
    val lines = msgs.map { m ->
        val who = if (m.isSelf) "我" else (m.senderName.ifBlank { m.senderId })
        "[${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(m.time))}] $who：${m.content}"
    }
    // 用 Material3 Dialog 撑起一个简单列表
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { androidx.compose.material3.Text("${conv.name}（${msgs.size} 条）") },
        text = {
            Column {
                lines.forEach { androidx.compose.material3.Text(it) }
            }
        },
        confirmButton = {
            TextButton(text = "关闭", onClick = onDismiss)
        },
    )
}
```

说明：为降低组件不匹配风险，详情用 material3 `AlertDialog`（compose-bom 自带），不依赖 miuix OverlayDialog。

- [ ] **Step 3: 构建验证**

Run: 构建门禁命令。Expected: `BUILD SUCCESSFUL`。若 `remove(listener)` 的 lambda 类型与 `MessageBus.remove((String)->Unit)` 签名冲突（`remember` 包装的类型推断），显式写 `val listener: (String) -> Unit = { _ -> refresh++ }`。

- [ ] **Step 4: 更新进度**

`status.md` 勾选 Task 6。

---

### Task 7: 设置 SettingsScreen（SnowLuma 配置）

**Files:**
- Modify: `app/src/main/java/com/example/bandqq/ui/SettingsScreen.kt`

**Interfaces:**
- Consumes: `ConfigManager.load()/save()`、`AppConfig`、`EndpointConfig`、`GameProtocolDetector.testConnection`、`toast`、`rememberCoroutineScope`。
- Produces: `@Composable fun SettingsScreen()` 定稿。

- [ ] **Step 1: 写 SettingsScreen()**

覆盖 `ui/SettingsScreen.kt`：

```kotlin
package com.example.bandqq.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.bandqq.config.AppConfig
import com.example.bandqq.config.ConfigManager
import com.example.bandqq.config.EndpointConfig
import com.example.bandqq.onebot.GameProtocolDetector
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val configManager = remember { ConfigManager(context) }
    var wsUrl by remember { mutableStateOf("") }
    var wsToken by remember { mutableStateOf("") }
    var httpUrl by remember { mutableStateOf("") }
    var httpToken by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val cfg = configManager.load()
        wsUrl = cfg.endpoint.wsUrl
        wsToken = cfg.endpoint.wsToken
        httpUrl = cfg.endpoint.httpUrl
        httpToken = cfg.endpoint.httpToken
        loaded = true
    }

    top.yukonga.miuix.kmp.basic.Scaffold(
        topBar = { TopAppBar(title = "设置") },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(16.dp),
        ) {
            TextField(value = wsUrl, onValueChange = { wsUrl = it }, label = "WS 地址")
            TextField(value = wsToken, onValueChange = { wsToken = it }, label = "WS Token")
            TextField(value = httpUrl, onValueChange = { httpUrl = it }, label = "HTTP 地址")
            TextField(value = httpToken, onValueChange = { httpToken = it }, label = "HTTP Token")

            Button(
                onClick = {
                    scope.launch {
                        val cfg = AppConfig(
                            EndpointConfig(
                                wsUrl = wsUrl.trim(),
                                wsToken = wsToken.trim(),
                                httpUrl = httpUrl.trim(),
                                httpToken = httpToken.trim(),
                            ),
                        )
                        configManager.save(cfg)
                        toast(context, "配置已保存")
                    }
                },
                colors = ButtonDefaults.buttonColorsPrimary(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("保存") }

            Button(
                onClick = {
                    scope.launch {
                        val result = GameProtocolDetector.testConnection(
                            wsUrl.trim(), wsToken.trim(), httpUrl.trim(), httpToken.trim(),
                        )
                        val msg = if (result.wsReachable && result.httpReachable) "SnowLuma 连接正常" else "连接失败，请检查地址与协议端"
                        toast(context, msg)
                    }
                },
                colors = ButtonDefaults.buttonColors(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("测试连接") }

            if (!loaded) {
                SmallTitle(text = "正在读取配置…")
            }
        }
    }
}
```

- [ ] **Step 2: 构建验证**

Run: 构建门禁命令。Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 3: 更新进度**

`status.md` 勾选 Task 7。

---

### Task 8: 删除旧 View 层 + 全局验收

**Files:**
- DELETE: `app/src/main/java/com/example/bandqq/ChatHistoryActivity.kt`
- DELETE: `app/src/main/java/com/example/bandqq/ContactManagerActivity.kt`
- DELETE: `app/src/main/res/layout/activity_main.xml`
- DELETE: `app/src/main/res/layout/activity_chat_history.xml`
- DELETE: `app/src/main/res/layout/activity_contact_manager.xml`
- DELETE: `app/src/main/res/layout/item_contact_checkable.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/example/bandqq/MainActivity.kt`（最终版确认无旧引用）

**Interfaces:**
- Produces: 干净的纯 Compose 工程 + 新 APK `bandqq-phone-v3.0.apk`。

- [ ] **Step 1: 删除四个 XML 与两个 Activity 文件**

Run:
```bash
Remove-Item -LiteralPath "C:\Users\wang\Desktop\band-qq\android-sync\app\src\main\res\layout\activity_main.xml","C:\Users\wang\Desktop\band-qq\android-sync\app\src\main\res\layout\activity_chat_history.xml","C:\Users\wang\Desktop\band-qq\android-sync\app\src\main\res\layout\activity_contact_manager.xml","C:\Users\wang\Desktop\band-qq\android-sync\app\src\main\res\layout\item_contact_checkable.xml","C:\Users\wang\Desktop\band-qq\android-sync\app\src\main\java\com\example\bandqq\ChatHistoryActivity.kt","C:\Users\wang\Desktop\band-qq\android-sync\app\src\main\java\com\example\bandqq\ContactManagerActivity.kt"
```
Expected: 无输出（删除成功）。

- [ ] **Step 2: 更新 AndroidManifest.xml**

将 `app/src/main/AndroidManifest.xml` 中两个 `<activity android:name=".ChatHistoryActivity" .../>` 与 `.ContactManagerActivity` 节点删除。最终 manifest 只剩 `.MainActivity`（launcher）与 `.sync.SyncService`。

- [ ] **Step 3: 全文扫描残留引用**

Run:
```bash
rg -n "viewBinding|ActivityMainBinding|ChatHistoryActivity|ContactManagerActivity|activity_main|activity_chat_history|activity_contact_manager|item_contact_checkable" "C:\Users\wang\Desktop\band-qq\android-sync\app\src"
```
Expected: 无匹配（`rg` 结果为空）。若匹配在 `MainActivity.kt`，说明引入了旧引用，必须清理（该文件已是纯 Compose）。

- [ ] **Step 4: 构建验收**

Run: 构建门禁命令。
Expected: `BUILD SUCCESSFUL`。随后运行单元测试：

```powershell
$env:JAVA_HOME = "D:\android-build\jdk17\jdk-17.0.20+8"
$env:GRADLE_USER_HOME = "D:\android-build\gradle-home"
& "D:\android-build\gradle-8.7\bin\gradle.bat" -p "C:\Users\wang\Desktop\band-qq\android-sync" :app:testReleaseUnitTest --no-daemon
```
Expected: 现有 JVM 测试全绿（业务层未动，应照旧通过）。

- [ ] **Step 5: 产出并替换 APK**

从 `app/build/outputs/apk/release/` 找到新 release APK，复制到桌面并改名 `bandqq-phone-v3.0.apk`；删除桌面旧 `bandqq-phone-v1.2.apk`。记录 `status.md` 完成 Task 8。

- [ ] **Step 6: 手工冒烟清单（面向用户）**

1. 打开 App → 底部 4 个 Tab 可点击、页面标题正确。
2. 主页：状态卡正确反映 SnowLuma/手环。
3. 设置：修改 WS/HTTP 地址 → 保存 → Toast「配置已保存」→ 主页测试连接。
4. 联系人：私聊/群聊可切换，勾选后「保存选中联系人到手环」触发同步（手环未连时提示）。
5. 聊天记录：显示会话，点开详情弹窗，清空按钮生效。
6. 回归：启动同步服务 → SnowLuma 收消息 → 聊天记录实时新增。

- [ ] **Step 7: 完成**

在 `status.md` 顶部补一句总结：*「Miuix 0.9.3 UI 重构完成，业务层未改动，APK v3.0 已生成。」* 并向负责人报告 Task 1~8 全部通过。