# 设计：页面切换动效 + 其他页面动效 + 日志窗口增大

日期：2026-08-11

## 背景与目标

手机端（Android）当前四个 Tab 页（主页/联系人/聊天记录/设置）切换时无任何动画，切换是硬切的 `when(selected)`。除主页外，其余页面元素也无入场动效。同时日志窗口过小，被上方状态卡和按钮挤压，无法舒服查看实时日志。

本次目标：
1. 页面切换加入"水平滑动 + 淡入"动效。
2. 其他页面列表项依次入场、关键交互有过渡动画。
3. 主页整页可上下滑动，日志窗口固定较高区域并保留独立滚动（自动跟随 + 手动拖动）。

## 现状分析

- `BandQQApp.kt`：`Scaffold` + `NavigationBar`，内容区用 `when(selected)` 直接切换，无动画。
- 各页面 Screen（Contact/History/Settings）为普通 `Column + verticalScroll` 静态列表，无入场动画。
- `HomeScreen.kt`：`Column` 固定 `fillMaxSize` 不滚动；`StatusCard` 高度 120dp；4 个按钮占高；`LogPanel` 用 `weight(1f)` 占剩余空间，实际可用高度小。
- `LogPanel.kt`：内部已有 `ScrollState` 自动跟随（`animateScrollTo(Int.MAX_VALUE)`）与 `verticalScroll`，可手动拖动。

## 设计决策

### 1. 页面切换动效（方案 A：AnimatedContent）

`BandQQApp.kt` 内容区改为：

```kotlin
AnimatedContent(
    targetState = selected,
    transitionSpec = {
        (slideInHorizontally { it / 3 } + fadeIn(tween(220)))
            .togetherWith(slideOutHorizontally { -it / 3 } + fadeOut(tween(180)))
    },
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

- 特点：新页从右向左滑入（1/3 屏宽加淡入，220ms），旧页向左滑出并淡出（180ms），形成"水平滑动 + 淡入"。
- 依赖：Compose `androidx.compose.animation`（BOM 2024.09.03 已含），无需新增依赖。
- 不引入 HorizontalPager（无手势滑页需求，避免状态同步复杂度）。
- 保留 `rememberSaveable` 记录选中 Tab。

### 2. 其他页面动效

新增通用入场 modifier，仿首页 `HomeScreen.enterReveal`：

```kotlin
fun Modifier.listItemReveal(entered: Boolean, index: Int, delayMs: Long = 60L): Modifier = composed {
    val alpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(300, delayMillis = (index * delayMs).toInt()),
    )
    val offset by animateFloatAsState(
        targetValue = if (entered) 0f else 20f,
        animationSpec = tween(300, delayMillis = (index * delayMs).toInt()),
    )
    graphicsLayer { this.alpha = alpha; translationY = offset }
}
```

应用位置：
- `ContactScreen`：联系人 `Card` 列表项按 index 依次入场（私聊/群聊切换时重新触发）。
- `HistoryScreen`：会话 `Card` 列表项按 index 依次入场。
- `SettingsScreen`：表单字段/按钮按 index 依次入场。
- `HomeScreen` 保留现有 `enterReveal`，不改。

关键交互过渡：
- `ContactScreen` 私聊/群聊切换：内容区域用 `AnimatedContent` 交叉淡化（`crossfade` 风格），tab 切换 + 列表重新入场。

每个页面用一个 `entered` 状态（`LaunchedEffect(Unit) { entered = true }`），列表 `forEachIndexed` 应用 modifier。

### 3. 日志窗口增大 + 整页滚动

`HomeScreen.kt`：
- 外层 `Column` 加 `verticalScroll(rememberScrollState())`，整页可上下滑动。
- 移除 `fillMaxSize` 导致的固定高度约束（保留 `fillMaxWidth`）。
- `LogPanel` 不再用 `weight(1f)`，改用固定较高高度：`Modifier.heightIn(min = 320.dp)`（约屏高 40%）或 `BoxWithConstraints` 中取 `maxHeight * 0.4f`。

`LogPanel.kt`：
- 保留内部 `verticalScroll` + 自动跟随 `animateScrollTo(Int.MAX_VALUE)`。
- 手动拖动浏览：现有 `ScrollState` 已支持；确保自动跟随只在新日志追加且位于底部时触发，避免打断用户在向上回看。

## 涉及文件

- `android-sync/app/src/main/java/com/example/bandqq/ui/BandQQApp.kt`：切换动效。
- `android-sync/app/src/main/java/com/example/bandqq/ui/HomeScreen.kt`：整页滚动 + LogPanel 高度。
- `android-sync/app/src/main/java/com/example/bandqq/ui/LogPanel.kt`：高度适配 + 自动跟随逻辑微调。
- `android-sync/app/src/main/java/com/example/bandqq/ui/ContactScreen.kt`：列表入场 + 私聊/群聊交叉淡入。
- `android-sync/app/src/main/java/com/example/bandqq/ui/HistoryScreen.kt`：列表入场。
- `android-sync/app/src/main/java/com/example/bandqq/ui/SettingsScreen.kt`：元素入场。

新增公共动画工具：`UiMotion.kt`（`listItemReveal` 等），供各页面复用。

## 测试与验证

- `gradle testDebugUnitTest`（现有 LogBusTest 等需继续通过）。
- `gradle assembleRelease` 需构建成功。
- 手动验证：切换 Tab 有水平滑动+淡入；联系人/历史/设置列表项依次入场；主页整页可滚，日志窗口更高且可自动跟随 + 手动拖动。

## 非目标

- 不改手环端（QuickApp）逻辑。
- 不引入 HorizontalPager 手势滑页。
- 不改动现有业务/同步逻辑，纯 UI 表现层。