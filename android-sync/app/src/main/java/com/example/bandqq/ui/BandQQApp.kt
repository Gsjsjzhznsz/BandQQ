package com.example.bandqq.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Messages
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.example.bandqq.ui.component.FloatingBottomBar
import com.example.bandqq.ui.component.FloatingBottomBarItem

enum class AppTab(val label: String) {
    Home("主页"),
    Contacts("联系人"),
    History("聊天记录"),
    Settings("设置"),
}

@Composable
fun BandQQApp(navGlass: Boolean = true) {
    var selected by rememberSaveable { mutableStateOf(AppTab.Home) }
    var showThemeScreen by rememberSaveable { mutableStateOf(false) }
    val glassActive = isRuntimeShaderSupported()
    val layerBaseColor = MiuixTheme.colorScheme.background

    // 液态玻璃模糊源：必须先垫不透明背景色再画内容（KernelSU 同款 rememberBlurBackdrop 写法）
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
                        AppTab.Settings -> SettingsScreen(onOpenThemeSettings = { showThemeScreen = true })
                    }
                }
            }
        }

        // 底部悬浮栏：Android 13+ 用 KernelSU 同款 FloatingBottomBar（液态玻璃 + 可拖拽指示 pill）
        if (glassActive) {
            val navInsets = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            FloatingBottomBar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        start = 24.dp,
                        end = 24.dp,
                        bottom = if (navInsets != 0.dp) 8.dp + navInsets else 28.dp,
                    ),
                selectedIndex = selected.ordinal,
                onSelected = { index -> selected = AppTab.entries[index] },
                backdrop = backdrop,
                tabsCount = AppTab.entries.size,
                isBlurEnabled = navGlass,
            ) { activateTab ->
                AppTab.entries.forEachIndexed { index, tab ->
                    FloatingBottomBarItem(
                        selected = selected == tab,
                        onClick = { activateTab(index) },
                    ) {
                        Icon(
                            imageVector = tab.icon(),
                            contentDescription = tab.label,
                            modifier = Modifier.size(22.dp),
                        )
                        Text(text = tab.label, fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
        } else {
            // 低版本回退：miuix 普通悬浮导航
            val navInsets = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            FloatingNavigationBar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 24.dp, end = 24.dp, bottom = 16.dp + navInsets),
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

        // 主题与外观：全屏推入页（对齐 KernelSU 导航交互），置于最上层
        AnimatedVisibility(
            visible = showThemeScreen,
            enter = slideInVertically { it } + fadeIn(tween(200)),
            exit = slideOutVertically { it } + fadeOut(tween(180)),
            modifier = Modifier.fillMaxSize(),
        ) {
            ThemeScreen(onBack = { showThemeScreen = false })
        }
    }

    BackHandler(enabled = showThemeScreen) { showThemeScreen = false }
}

@Composable
private fun AppTab.icon() = when (this) {
    AppTab.Home -> MiuixIcons.Home
    AppTab.Contacts -> MiuixIcons.Contacts
    AppTab.History -> MiuixIcons.Messages
    AppTab.Settings -> MiuixIcons.Settings
}
