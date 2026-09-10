package com.example.bandqq.ui

import android.app.Activity
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bandqq.BandQQApplication
import com.example.bandqq.config.ConfigManager
import com.example.bandqq.ui.component.ScaleDialog
import com.example.bandqq.ui.util.BlurredBar
import com.example.bandqq.ui.util.rememberBlurBackdrop
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.CloudFill
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.HorizontalSplit
import top.yukonga.miuix.kmp.icon.extended.Pin
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.icon.extended.Scan
import top.yukonga.miuix.kmp.icon.extended.Sidebar
import top.yukonga.miuix.kmp.icon.extended.Theme
import top.yukonga.miuix.kmp.icon.extended.Timer
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/** Monet 关键色候选（0 = 默认品牌蓝/系统色板），对齐 KSU keyColorOptions 的用法 */
private val KEY_COLOR_OPTIONS = listOf(
    0xFFF44336L to "红色", 0xFFE91E63L to "粉色", 0xFF9C27B0L to "紫色",
    0xFF673AB7L to "深紫", 0xFF3F51B5L to "靛蓝", 0xFF2196F3L to "蓝色",
    0xFF00BCD4L to "青色", 0xFF009688L to "蓝绿", 0xFF4CAF50L to "绿色",
    0xFFFFEB3BL to "黄色", 0xFFFFC107L to "琥珀", 0xFFFF9800L to "橙色",
    0xFF795548L to "棕色", 0xFF607D8BL to "蓝灰", 0xFFFF8FABL to "樱粉",
)

/**
 * 主题与外观全屏设置页（对齐 KernelSU ColorPaletteScreenMiuix 的完整结构与交互）：
 * 主题预览 → TabRow 三档模式 → Monet + 关键色 → 模糊/悬浮底栏/液态玻璃(二级)/导航角标
 * → 预测性返回/界面缩放（内嵌 Slider + 数值对话框），返回箭头/系统返回键退出。
 */
@Composable
fun ThemeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val configManager = remember { ConfigManager(context) }

    val themeMode by configManager.observeThemeMode().collectAsState(initial = 0)
    val keyColor by configManager.observeKeyColor().collectAsState(initial = 0)
    val enableBlur by configManager.observeEnableBlur().collectAsState(initial = true)
    val floatingBar by configManager.observeFloatingBottomBar().collectAsState(initial = true)
    val navGlass by configManager.observeNavGlass().collectAsState(initial = true)
    val navBadge by configManager.observeNavigationBadge().collectAsState(initial = true)
    val pageScale by configManager.observePageScale().collectAsState(initial = 1.0f)
    val predictiveBack by configManager.observePredictiveBack().collectAsState(initial = false)
    val motionSpeed by configManager.observeMotionSpeed().collectAsState(initial = 1.0f)
    val motionStagger by configManager.observeMotionStagger().collectAsState(initial = 100)

    val mode = ThemeMode.fromValue(themeMode)
    val isDark = mode.isDark || (mode.isSystem && isSystemInDarkTheme())
    val supportBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    // 顶栏模糊：本页自身也走 KSU 的 BlurredBar 方案
    val blurBackdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = blurBackdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface

    Scaffold(
        topBar = {
            BlurredBar(blurBackdrop) {
                SmallTopAppBar(
                    title = "主题与外观",
                    color = barColor,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = "返回",
                                tint = colorScheme.onBackground,
                            )
                        }
                    },
                )
            }
        },
        popupHost = { },
    ) { innerPadding ->
        val showScaleDialog = rememberSaveable { mutableStateOf(false) }
        var sliderValue by remember(pageScale) { mutableFloatStateOf(pageScale) }
        var speedValue by remember(motionSpeed) { mutableFloatStateOf(motionSpeed) }
        var staggerValue by remember(motionStagger) { mutableFloatStateOf(motionStagger.toFloat()) }

        Box(
            modifier = if (blurBackdrop != null) Modifier.layerBackdrop(blurBackdrop) else Modifier
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                // 顶部留白 = 顶栏高度：初始从顶栏下缘开始，滚动时内容可穿透顶栏模糊
                Spacer(Modifier.height(innerPadding.calculateTopPadding()))
                Spacer(Modifier.height(12.dp))

                // ===== 主题实时预览卡片 =====
                ThemePreviewCard(
                    isDark = isDark,
                    monet = mode.isMonet,
                    floatingBar = floatingBar,
                    glassBar = navGlass && supportBlur,
                )
                Spacer(modifier = Modifier.height(24.dp))

                // ===== 主题模式 TabRow（跟随系统 / 浅色 / 深色）=====
                TabRow(
                    tabs = listOf("跟随系统", "浅色", "深色"),
                    selectedTabIndex = mode.value % 3,
                    onTabSelected = { index ->
                        scope.launch { configManager.setThemeMode(index + if (mode.isMonet) 3 else 0) }
                    },
                )

                // ===== Monet 颜色卡片 =====
                Card(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth(),
                ) {
                    SwitchPreference(
                        title = "启用 Monet 颜色",
                        summary = "跟随系统壁纸取色；关闭则使用 miuix 经典配色",
                        startAction = {
                            Icon(
                                MiuixIcons.Theme,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = "启用 Monet 颜色",
                                tint = colorScheme.onBackground,
                            )
                        },
                        checked = mode.isMonet,
                        onCheckedChange = { on ->
                            scope.launch {
                                configManager.setThemeMode(mode.value % 3 + if (on) 3 else 0)
                            }
                        },
                    )
                    // KSU 同款默认动画：自定义 expand/shrink spec 会与 0.9.3 浮层首帧测量冲突，
                    // 曾导致点开 Monet 开关即崩溃，勿再加回（v2.4.5）
                    AnimatedVisibility(visible = mode.isMonet) {
                        Column {
                            val colorNames = listOf("默认（品牌蓝）") + KEY_COLOR_OPTIONS.map { it.second }
                            val colorValues = listOf(0) + KEY_COLOR_OPTIONS.map { it.first.toInt() }
                            OverlayDropdownPreference(
                                title = "关键色",
                                startAction = {
                                    Icon(
                                        MiuixIcons.Tune,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = "关键色",
                                        tint = colorScheme.onBackground,
                                    )
                                },
                                items = colorNames,
                                selectedIndex = colorValues.indexOf(keyColor).takeIf { it >= 0 } ?: 0,
                                onSelectedIndexChange = { index ->
                                    scope.launch { configManager.setKeyColor(colorValues[index]) }
                                },
                            )
                        }
                    }
                }

                // ===== 模糊 / 悬浮底栏 / 液态玻璃（二级）/ 导航角标 =====
                SmallTitle(text = "界面与效果")
                Card(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth(),
                ) {
                    if (supportBlur) {
                        SwitchPreference(
                            title = "模糊",
                            summary = "启用顶栏和底栏的模糊效果",
                            startAction = {
                                Icon(
                                    MiuixIcons.CloudFill,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = "模糊",
                                    tint = colorScheme.onBackground,
                                )
                            },
                            checked = enableBlur,
                            onCheckedChange = { on -> scope.launch { configManager.setEnableBlur(on) } },
                        )
                    }
                    SwitchPreference(
                        title = "悬浮底栏",
                        summary = "使用 Apple 风格的悬浮底栏",
                        startAction = {
                            Icon(
                                MiuixIcons.HorizontalSplit,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = "悬浮底栏",
                                tint = colorScheme.onBackground,
                            )
                        },
                        checked = floatingBar,
                        onCheckedChange = { on -> scope.launch { configManager.setFloatingBottomBar(on) } },
                    )
                    // 悬浮底栏开启后的二级选项：液态玻璃（Android 13+）
                    AnimatedVisibility(visible = floatingBar && supportBlur) {
                        SwitchPreference(
                            title = "液态玻璃",
                            summary = "启用悬浮底栏的液态玻璃效果（实时折射 + 高光）",
                            startAction = {
                                Icon(
                                    MiuixIcons.Scan,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = "液态玻璃",
                                    tint = colorScheme.onBackground,
                                )
                            },
                            checked = navGlass,
                            onCheckedChange = { on -> scope.launch { configManager.setNavGlass(on) } },
                        )
                    }
                    SwitchPreference(
                        title = "导航栏角标",
                        summary = "在导航栏「聊天记录」上显示未读消息数量",
                        startAction = {
                            Icon(
                                MiuixIcons.Pin,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = "导航栏角标",
                                tint = colorScheme.onBackground,
                            )
                        },
                        checked = navBadge,
                        onCheckedChange = { on -> scope.launch { configManager.setNavigationBadge(on) } },
                    )
                }

                // ===== 预测性返回 / 界面缩放 =====
                SmallTitle(text = "手势与显示")
                Card(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth(),
                ) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        SwitchPreference(
                            title = "预测性返回手势",
                            summary = "启用对预测性返回手势的支持（切换后界面刷新）",
                            startAction = {
                                Icon(
                                    MiuixIcons.Sidebar,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = "预测性返回手势",
                                    tint = colorScheme.onBackground,
                                )
                            },
                            checked = predictiveBack,
                            onCheckedChange = { on ->
                                scope.launch { configManager.setPredictiveBack(on) }
                                // KSU 同款：立即反射设置 + recreate，否则要重进应用才能生效。
                                // recreate 前记录当前推入页，重建后 BandQQApp 自动重新推入（v2.4.7：
                                // 否则用户视角是「点开关被踢回主页」，以为开关坏了）
                                val app = context.applicationContext as? BandQQApplication
                                if (app != null) {
                                    BandQQApplication.setEnableOnBackInvokedCallback(
                                        app.applicationInfo, on,
                                    )
                                    RecreateCoordinator.reopenScreen = "theme"
                                    RecreateCoordinator.armed = true
                                    (context as? Activity)?.recreate()
                                }
                            },
                        )
                    }

                    ArrowPreference(
                        title = "界面缩放",
                        summary = "调整全局显示比例",
                        startAction = {
                            Icon(
                                MiuixIcons.GridView,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = "界面缩放",
                                tint = colorScheme.onBackground,
                            )
                        },
                        endActions = {
                            Text(
                                text = "${(sliderValue * 100).toInt()}%",
                                color = colorScheme.onSurfaceVariantActions,
                            )
                        },
                        onClick = { showScaleDialog.value = !showScaleDialog.value },
                        holdDownState = showScaleDialog.value,
                        bottomAction = {
                            Slider(
                                value = sliderValue,
                                onValueChange = { sliderValue = it },
                                onValueChangeFinished = {
                                    scope.launch { configManager.setPageScale(sliderValue) }
                                },
                                valueRange = 0.8f..1.1f,
                                showKeyPoints = true,
                                keyPoints = listOf(0.8f, 0.9f, 1f, 1.1f),
                                magnetThreshold = 0.01f,
                                hapticEffect = SliderDefaults.SliderHapticEffect.Step,
                            )
                        },
                    )
                    ScaleDialog(
                        show = showScaleDialog.value,
                        onDismissRequest = { showScaleDialog.value = false },
                        volumeState = { pageScale },
                        onVolumeChange = { scale ->
                            scope.launch { configManager.setPageScale(scale) }
                        },
                    )
                }

                // ===== 动画：速度 / 级联延迟（实时生效） =====
                SmallTitle(text = "动画")
                Card(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth(),
                ) {
                    ArrowPreference(
                        title = "动画速度",
                        summary = "页面推入与列表入场的播放速度（0.5x 慢速 ~ 2.0x 极速）",
                        startAction = {
                            Icon(
                                MiuixIcons.Play,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = "动画速度",
                                tint = colorScheme.onBackground,
                            )
                        },
                        endActions = {
                            Text(
                                text = "${(speedValue * 10).roundToInt() / 10.0}x",
                                color = colorScheme.onSurfaceVariantActions,
                            )
                        },
                        bottomAction = {
                            Slider(
                                value = speedValue,
                                onValueChange = { speedValue = it },
                                onValueChangeFinished = {
                                    scope.launch {
                                        configManager.setMotionSpeed((speedValue * 100).roundToInt() / 100f)
                                    }
                                },
                                valueRange = 0.5f..2f,
                                showKeyPoints = true,
                                keyPoints = listOf(0.5f, 1f, 1.5f, 2f),
                                magnetThreshold = 0.01f,
                                hapticEffect = SliderDefaults.SliderHapticEffect.Step,
                            )
                        },
                    )
                    ArrowPreference(
                        title = "动画延迟",
                        summary = "列表卡片级联入场的逐项间隔",
                        startAction = {
                            Icon(
                                MiuixIcons.Timer,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = "动画延迟",
                                tint = colorScheme.onBackground,
                            )
                        },
                        endActions = {
                            Text(
                                text = "${staggerValue.roundToInt()}ms",
                                color = colorScheme.onSurfaceVariantActions,
                            )
                        },
                        bottomAction = {
                            Slider(
                                value = staggerValue,
                                onValueChange = { staggerValue = it },
                                onValueChangeFinished = {
                                    scope.launch { configManager.setMotionStagger(staggerValue.roundToInt()) }
                                },
                                valueRange = 0f..200f,
                                showKeyPoints = true,
                                keyPoints = listOf(0f, 50f, 100f, 150f, 200f),
                                magnetThreshold = 1f,
                                hapticEffect = SliderDefaults.SliderHapticEffect.Step,
                            )
                        },
                    )
                }

                // 底部留白：导航栏高度 + 余量，内容可从底栏下穿过
                Spacer(
                    Modifier.height(
                        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                                WindowInsets.captionBar.asPaddingValues().calculateBottomPadding() +
                                12.dp
                    )
                )
            }
        }
    }
}

/**
 * 主题实时预览卡片（KSU ThemePreviewCardMiuix 精简版）：
 * 按当前模式/Monet/悬浮底栏/玻璃状态实时渲染一个迷你手机界面示意。
 */
@Composable
private fun ThemePreviewCard(
    isDark: Boolean,
    monet: Boolean,
    floatingBar: Boolean,
    glassBar: Boolean,
) {
    val cs = colorScheme
    val bgColor = cs.background
    val cardColor = cs.surfaceVariant
    val accentColor = cs.primary
    val navBarColor = cs.surface
    val textColor = cs.onBackground

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.42f)
                .height(150.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(bgColor)
                .border(1.dp, cs.outline, RoundedCornerShape(20.dp)),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "BandQQ",
                    fontSize = 11.sp,
                    color = textColor,
                    modifier = Modifier.padding(start = 4.dp),
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(accentColor.copy(alpha = 0.18f)),
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(30.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(cardColor),
                )
            }

            if (floatingBar) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp)
                        .height(22.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(if (glassBar) navBarColor.copy(alpha = 0.5f) else navBarColor)
                        .border(0.5.dp, textColor.copy(alpha = 0.12f), RoundedCornerShape(11.dp))
                        .padding(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(4) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(if (it == 0) accentColor else textColor.copy(alpha = 0.6f)),
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(textColor.copy(alpha = 0.1f)),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(22.dp)
                            .background(navBarColor)
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        repeat(4) {
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(if (it == 0) accentColor else textColor.copy(alpha = 0.6f)),
                            )
                        }
                    }
                }
            }
        }
    }
}
