# 手机端主页实时日志窗口 + 布局重组 + 动效 设计

日期：2026-08-11

## 背景与目标

安卓端主页（`HomeScreen.kt`）当前内容堆叠：2 张全宽状态卡 + 4 个操作按钮 + 一行状态文本，整体用 `Column + verticalScroll` 滚动。用户希望：

1. 主页底部新增**实时日志窗口**，能展示各模块运行日志以辅助排查。
2. 主页**重新布局**，消除杂乱感。
3. 添加**动效**提升观感。

当前日志需求确认结果：
- 建立**统一日志通道**，全量接入现有 `android.util.Log` 调用点。
- 日志窗口功能：清空按钮、按来源过滤、显示时间戳、限制最大条数、自动滚到底部。
- 主页布局：手环/雪鹿状态卡横排方形、操作按钮竖排、日志窗口置底。
- 动效：日志滚动/新条目、状态卡呼吸/切换、主页入场。

## 架构总览

```
各模块 (OneBotClient / InterconnectBridge / MessageBroker / OneBotParser)
        │  LogBus.log(tag, level, message)   (android.util.Log 双写保留)
        ▼
    sync/LogBus.kt  (object)
        │  MutableStateFlow<List<LogEntry>>   (裁剪最近 200 条)
        ▼
    ui/HomeScreen.kt → ui/LogPanel.kt (subscribe via collectAsState)
```

## 组件设计

### 1. 日志通道 `sync/LogBus.kt`（新建）

```kotlin
enum class LogLevel { DEBUG, INFO, WARN, ERROR }

data class LogEntry(
    val time: Long,        // System.currentTimeMillis()
    val tag: String,       // 模块名，如 "OneBotClient" / "InterconnectBridge" / "MessageBroker"
    val level: LogLevel,
    val message: String
)

object LogBus {
    private val MAX_LOGS = 200
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs

    fun log(tag: String, level: LogLevel, message: String) {
        // 追加 + 裁剪最近 200 条
        _logs.update { (it + LogEntry(System.currentTimeMillis(), tag, level, message)).takeLast(MAX_LOGS) }
        // android.util.Log 双写（DEBUG→Log.d，INFO→Log.i，WARN→Log.w，ERROR→Log.e）
    }

    fun clear() { _logs.value = emptyList() }
}
```

### 2. 接入改造（机械替换）

将以下文件的 `Log.d/w/e` 调用改为 `LogBus.log(tag, level, msg)`，同时保留 `android.util.Log` 双写：

- `onebot/OneBotClient.kt`：约 9 处（连接/收发/Http 错误）
- `sync/InterconnectBridge.kt`：约 15 处（onBandMessage/connect/registerListener/sendToBand 等）
- `sync/MessageBroker.kt`：`log()` helper（约 1 处入口，供内部调用）
- `onebot/OneBotParser.kt`：解析错误 1 处

改造原则：只改日志出口，不改业务逻辑；单测环境 `android.util.Log` 不可用，`LogBus.log` 内 double-write 需 try/catch 或沿用现有 MessageBroker 的防御写法。

### 3. 主页布局 `ui/HomeScreen.kt`（重写）

`BandQQApp` 与底部导航不变，仅重写 `HomeScreen`。主页不再整体 `verticalScroll`，改为固定布局：

```
Column(fillMaxSize, padding 16dp, spacedBy 12dp)
├── Row: 两个正方形状态卡（weight 各 1，aspectRatio 方形）
│     ├── 手环卡：标题/连接状态/呼吸指示点
│     └── SnowLuma 卡：标题/连接状态/呼吸指示点
├── 操作按钮竖排（全宽）
│     ├── 启动服务
│     ├── 停止服务
│     ├── 检查手环连接
│     └── 测试 SnowLuma 连接
└── LogPanel(modifier = Modifier.weight(1f).fillMaxWidth())
```

- 移除原 `SmallTitle("操作")` 与底部状态文本（状态已由卡片呈现）。
- 状态数据来源不变：`useBandConnected()`、`useOneBotConnected(refreshKey)`。

### 4. 日志窗口 `ui/LogPanel.kt`（新建）

`@Composable LogPanel(modifier)`，订阅 `LogBus.logs.collectAsState()`。

- 标题行 + 操作行：清空按钮、来源过滤（全部 / OneBotClient / InterconnectBridge / MessageBroker 等横向 chips）。
- 列表：`verticalScroll(rememberScrollState())`，每条 `[HH:mm:ss] <tag> message`。
- 自动滚动：`LaunchedEffect(logs.size)` → `scrollState.animateScrollTo(last index)` 平滑跟随。
- 过滤逻辑在 UI 层计算（`remember(logs, filter)`），不动 LogBus。

### 5. 动效（均在 HomeScreen/LogPanel 内实现）

- **日志新条目**：列表项 `AnimatedVisibility`（`fadeIn` + `slideInVertically`），仅新条目播放。
- **状态卡**：
  - 在线指示点：`infiniteRepeatable` 透明度呼吸（0.4→1.0，1.2s）。
  - 状态切换：`animateColorAsState` 平滑过渡卡片标题/指示点颜色（在线亮绿，离线灰/红）。
- **主页入场**：`Column` 三段内容用 `AnimatedVisibility` 分 0/100/200ms 错峰入场，`LaunchedEffect` 只播一次。

动画全部使用 androidx.compose.animation / animateFloatAsState / animateColorAsState，无新增依赖。

## 错误处理

- `LogBus.log` 内 double-write 对 `android.util.Log` 调用加 try/catch，避免 JVM 单测环境崩溃。
- 日志满 200 条自动裁剪，窗口常驻不导致 OOM。
- 自动滚动仅在用户未手动上翻时触发（简单实现：记录最后滚动位置或仅 `animateScrollTo` 到底部）。

## 测试

- 无法本机运行 gradle（无 gradle/wrapper），Kotlin 改动需在有 gradle 环境验证编译。
- `LogBus` 逻辑简单（追加/裁剪/清空），可补 JUnit 单测（纯内存，无 Android 依赖）。
- band-qq 侧无改动，不受影响。

## 涉及文件

| 文件 | 动作 |
| --- | --- |
| `android-sync/app/src/main/java/com/example/bandqq/sync/LogBus.kt` | 新建 |
| `android-sync/app/src/main/java/com/example/bandqq/ui/LogPanel.kt` | 新建 |
| `android-sync/app/src/main/java/com/example/bandqq/ui/HomeScreen.kt` | 重写 |
| `android-sync/app/src/main/java/com/example/bandqq/onebot/OneBotClient.kt` | 接入 LogBus |
| `android-sync/app/src/main/java/com/example/bandqq/sync/InterconnectBridge.kt` | 接入 LogBus |
| `android-sync/app/src/main/java/com/example/bandqq/sync/MessageBroker.kt` | 接入 LogBus |
| `android-sync/app/src/main/java/com/example/bandqq/onebot/OneBotParser.kt` | 接入 LogBus |
| 新增 LogBus 单测文件（可选） | 新建 |

## 明确的范围约束

- 不修改 `BandQQApp.kt` 底部导航与标签页切换。
- 不修改 band-qq 快应用侧任何逻辑。
- 不引入新的第三方依赖。