package com.example.bandqq.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.bandqq.sync.StoreHolder
import com.example.bandqq.ui.component.BottomBar
import com.example.bandqq.ui.component.PageScaffold
import com.example.bandqq.ui.util.rememberBlurBackdrop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

enum class AppTab(val label: String) {
    Home("主页"),
    Contacts("联系人"),
    History("聊天记录"),
    Settings("设置"),
}

/**
 * 主界面（对齐 KernelSU MainActivity 结构）：
 * - 外层 Scaffold 只持有 bottomBar，顶栏由每页 PageScaffold 自带（内容从顶栏下穿过，玻璃可模糊）；
 * - HorizontalPager：页面横向跟手滑动，底栏点击 animateScrollToPage 联动；
 * - 双 backdrop：外层 blurBackdrop 供普通底栏 textureBlur；backdrop 供悬浮底栏液态玻璃；
 * - 底栏双形态（悬浮玻璃 / 悬浮实色 / 普通模糊 / 普通实色）由设置项组合驱动。
 */
@Composable
fun BandQQApp() {
    val enableBlur = LocalEnableBlur.current
    val floatingBar = LocalEnableFloatingBottomBar.current
    val glassBar = LocalEnableFloatingBottomBarGlass.current
    val badgeEnabled = LocalEnableNavigationBadge.current

    val pagerState = rememberPagerState(pageCount = { AppTab.entries.size })
    val scope = rememberCoroutineScope()
    var showThemeScreen by rememberSaveable { mutableStateOf(false) }

    val surfaceColor = MiuixTheme.colorScheme.surface

    // 顶栏/普通底栏模糊源（enableBlur 关闭或设备不支持时为 null，回退实色）
    val blurBackdrop = rememberBlurBackdrop(enableBlur)
    // 悬浮底栏液态玻璃源：先垫 surface 底色再画内容（KernelSU 同款，防采样透明发黑）
    val backdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }

    // 导航栏角标数据：未读消息总数（3 秒轮询 + 切页即刷）
    val unread = remember { mutableIntStateOf(0) }
    LaunchedEffect(pagerState.settledPage) {
        unread.intValue = StoreHolder.store?.unreadTotal() ?: 0
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(3000)
            unread.intValue = StoreHolder.store?.unreadTotal() ?: 0
        }
    }

    Scaffold(
        bottomBar = {
            Box(modifier = Modifier.fillMaxWidth()) {
                BottomBar(
                    blurBackdrop = blurBackdrop,
                    backdrop = backdrop,
                    selected = pagerState.settledPage,
                    onSelect = { index ->
                        scope.launch { pagerState.animateScrollToPage(index) }
                    },
                    unread = if (badgeEnabled) unread.intValue else 0,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enableFloatingBottomBar = floatingBar,
                    enableFloatingBottomBarGlass = glassBar,
                )
            }
        },
    ) { innerPadding ->
        // 外层只传底部安全余量（顶栏已由每页 PageScaffold 自行处理，KSU 同款）
        val bottomInnerPadding = innerPadding.calculateBottomPadding()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (blurBackdrop != null) Modifier.layerBackdrop(blurBackdrop) else Modifier)
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (floatingBar && glassBar) Modifier.layerBackdrop(backdrop)
                        else Modifier
                    ),
                beyondViewportPageCount = 1,
                pageContent = { page ->
                    when (AppTab.entries[page]) {
                        AppTab.Home -> HomeScreen(bottomInnerPadding = bottomInnerPadding)
                        AppTab.Contacts -> ContactScreen(bottomInnerPadding = bottomInnerPadding)
                        AppTab.History -> HistoryScreen(bottomInnerPadding = bottomInnerPadding)
                        AppTab.Settings -> SettingsScreen(
                            bottomInnerPadding = bottomInnerPadding,
                            onOpenThemeSettings = { showThemeScreen = true },
                        )
                    }
                },
            )
        }
    }

    // 主题与外观：全屏推入页（对齐 KernelSU 导航交互），置于最上层
    AnimatedVisibility(
        visible = showThemeScreen,
        enter = slideInVertically { it } + fadeIn(tween(220)),
        exit = slideOutVertically { it } + fadeOut(tween(180)),
        modifier = Modifier.fillMaxSize(),
    ) {
        ThemeScreen(onBack = { showThemeScreen = false })
    }

    BackHandler(enabled = showThemeScreen) { showThemeScreen = false }
}
