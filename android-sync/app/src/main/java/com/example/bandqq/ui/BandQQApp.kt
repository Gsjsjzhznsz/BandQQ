package com.example.bandqq.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.colorControls
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.highlight.BloomStroke
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.highlight.LightPosition
import top.yukonga.miuix.kmp.blur.highlight.LightSource
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Messages
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme

enum class AppTab(val label: String) {
    Home("主页"),
    Contacts("联系人"),
    History("聊天记录"),
    Settings("设置"),
}

/** 液态玻璃边缘高光（引自 miuix 官方 LiquidGlass 示例参数）。 */
private val glassEdgeHighlight = Highlight(
    width = 1.dp,
    alpha = 0.9f,
    style = BloomStroke(
        color = Color.White.copy(alpha = 0.12f),
        innerBlurRadius = 2.0.dp,
        primaryLight = LightSource(
            position = LightPosition(0.5f, -0.3f, -0.05f),
            color = Color.White,
            intensity = 1f,
        ),
        secondaryLight = LightSource(
            position = LightPosition(0.5f, 0.8f, -0.5f),
            color = Color.White,
            intensity = 0.4f,
        ),
        dualPeak = true,
    ),
)

@Composable
fun BandQQApp() {
    var selected by rememberSaveable { mutableStateOf(AppTab.Home) }
    val backdrop = rememberLayerBackdrop()
    val glassSupported = isRuntimeShaderSupported()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background),
    ) {
        // 内容层：登记为液态玻璃的模糊源，内容可穿透底栏
        Column(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop),
        ) {
            SmallTopAppBar(title = selected.label)
            Box(modifier = Modifier.fillMaxSize()) {
                AnimatedContent(
                    targetState = selected,
                    transitionSpec = {
                        (slideInHorizontally { it / 3 } + fadeIn(tween(220)))
                            .togetherWith(slideOutHorizontally { -it / 3 } + fadeOut(tween(180)))
                    },
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize(),
                    label = "tabSwitch",
                ) { tab ->
                    when (tab) {
                        AppTab.Home -> HomeScreen()
                        AppTab.Contacts -> ContactScreen()
                        AppTab.History -> HistoryScreen()
                        AppTab.Settings -> SettingsScreen()
                    }
                }
            }
        }

        // 底部悬浮栏：Android 13+ 液态玻璃（实时模糊取景），低版本回退 miuix 悬浮导航
        val navInsets = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val navModifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(start = 24.dp, end = 24.dp, bottom = 16.dp + navInsets)
        if (glassSupported) {
            LiquidGlassNavBar(
                backdrop = backdrop,
                selected = selected,
                onSelect = { selected = it },
                modifier = navModifier,
            )
        } else {
            FloatingNavigationBar(
                modifier = navModifier,
            ) {
                AppTab.entries.forEach { tab ->
                    FloatingNavigationBarItem(
                        selected = selected == tab,
                        onClick = { selected = tab },
                        icon = tab.icon(),
                        label = tab.label,
                    )
                }
            }
        }
    }
}

/** 液态玻璃悬浮导航：内容实时穿透模糊 + 边缘高光 + 选中指示 pill。 */
@Composable
private fun LiquidGlassNavBar(
    backdrop: LayerBackdrop,
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.4f)
    val accent = MiuixTheme.colorScheme.primary
    val onSurface = MiuixTheme.colorScheme.onSurface
    val pillShape = CircleShape

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { pillShape },
                effects = {
                    blur(4.dp.toPx(), 4.dp.toPx())
                    // 参数顺序：brightness, contrast, saturation（基准 0/1/1）
                    colorControls(0.02f, 1.03f, 1.4f)
                },
                highlight = { glassEdgeHighlight },
                onDrawSurface = { drawRect(containerColor) },
            )
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppTab.entries.forEach { tab ->
            val isSelected = selected == tab
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(
                        color = if (isSelected) accent.copy(alpha = 0.16f) else Color.Transparent,
                        shape = RoundedCornerShape(50),
                    )
                    .clickable(
                        interactionSource = null,
                        indication = null,
                        onClick = { onSelect(tab) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) {
                    Icon(
                        imageVector = tab.icon(),
                        contentDescription = tab.label,
                        tint = if (isSelected) accent else onSurface.copy(alpha = 0.65f),
                        modifier = Modifier.size(21.dp),
                    )
                    Text(
                        text = tab.label,
                        fontSize = 10.sp,
                        color = if (isSelected) accent else onSurface.copy(alpha = 0.65f),
                        modifier = Modifier.offset(y = (-1).dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AppTab.icon() = when (this) {
    AppTab.Home -> MiuixIcons.Home
    AppTab.Contacts -> MiuixIcons.Contacts
    AppTab.History -> MiuixIcons.Messages
    AppTab.Settings -> MiuixIcons.Settings
}
