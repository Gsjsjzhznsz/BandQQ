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
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
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

@Composable
fun BandQQApp(navGlass: Boolean = true) {
    var selected by rememberSaveable { mutableStateOf(AppTab.Home) }
    val glassSupported = isRuntimeShaderSupported()
    val layerBaseColor = MiuixTheme.colorScheme.background

    // 液态玻璃模糊源：必须先垫不透明背景色再画内容。
    // 旧实现直接 layerBackdrop 登记内容层，层内背景透明（背景画在外层 Box），
    // 玻璃栏采样到大量透明像素 → 模糊合成后发黑/花屏，这是渲染问题根因之一。
    // （对齐 KernelSU rememberBlurBackdrop：drawRect(surface) + drawContent()）
    val backdrop = rememberLayerBackdrop {
        drawRect(layerBaseColor)
        drawContent()
    }

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

        // 底部悬浮栏：Android 13+ 液态玻璃（KernelSU 同款 textureBlur 配方），低版本回退 miuix 悬浮导航
        val navInsets = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val navModifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(start = 24.dp, end = 24.dp, bottom = 16.dp + navInsets)
        if (navGlass && glassSupported) {
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

/**
 * 液态玻璃悬浮导航：KernelSU manager 同款配方——
 * textureBlur(blurRadius=25f) + surface 87% 叠色，磨砂通透不偏色。
 * （旧配方 drawBackdrop+colorControls+BloomStroke 为自行试验参数，渲染异常）
 */
@Composable
private fun LiquidGlassNavBar(
    backdrop: LayerBackdrop,
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MiuixTheme.colorScheme.primary
    val onSurface = MiuixTheme.colorScheme.onSurface

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .textureBlur(
                backdrop = backdrop,
                shape = RoundedCornerShape(32.dp),
                blurRadius = 25f,
                colors = BlurColors(
                    blendColors = listOf(
                        BlendColorEntry(color = MiuixTheme.colorScheme.surface.copy(alpha = 0.87f))
                    )
                ),
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
