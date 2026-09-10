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
import androidx.compose.runtime.DisposableEffect
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
import com.example.bandqq.sync.MessageBus
import com.example.bandqq.sync.StoreHolder
import com.example.bandqq.ui.component.BottomBar
import com.example.bandqq.ui.component.PageScaffold
import com.example.bandqq.ui.util.rememberBlurBackdrop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
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
    var showKeepAlive by rememberSaveable { mutableStateOf(false) }

    // 推入页动画时长跟随「动画速度」设置（速度越快时长越短）
    val motionSpeed = LocalMotionSpeed.current.coerceIn(0.5f, 2f)
    val pushIn = (260 / motionSpeed).roundToInt()
    val pushOut = (200 / motionSpeed).roundToInt()

    val surfaceColor = MiuixTheme.colorScheme.surface

    // 顶栏/普通底栏模糊源（enableBlur 关闭或设备不支持时为 null，回退实色）
    val blurBackdrop = rememberBlurBackdrop(enableBlur)
    // 悬浮底栏液态玻璃源：先垫 surface 底色再画内容（KernelSU 同款，防采样透明发黑）
    val backdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }

    // 导航栏角标数据：未读消息总数。
    // 事件驱动：消息到达/撤回（MessageBus）即刻刷新；3s 轮询作兜底（含手环 read_chat 后的归零）
    val unread = remember { mutableIntStateOf(0) }
    fun refreshUnread() { unread.intValue = StoreHolder.store?.unreadTotal() ?: 0 }
    DisposableEffect(Unit) {
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        val listener: (String) -> Unit = { _ ->
            mainHandler.post { refreshUnread() }
        }
        MessageBus.add(listener)
        onDispose { MessageBus.remove(listener) }
    }
    LaunchedEffect(pagerState.settledPage) {
        refreshUnread()
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(3000)
            refreshUnread()
        }
    }

    // 推入页打开时隐藏底栏（对齐 KSU：推入页后底栏消失，返回后恢复）
    val overlayOpen = showThemeScreen || showKeepAlive

    Scaffold(
        bottomBar = {
            // ⚠️ 推入页必须放在外层 Scaffold 的 content slot 内（见下方注释）。
            // bottomBar 也随推入收起，innerPadding 平滑过渡，列表尾部不跳动。
            AnimatedVisibility(
                visible = !overlayOpen,
                enter = slideInVertically { it } + fadeIn(tween(pushIn)),
                exit = slideOutVertically { it } + fadeOut(tween(pushOut)),
            ) {
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
                    // 预组合页（beyondViewport）不激活入场动画：只有成为当前页才播放
                    val isCurrentPage = page == pagerState.settledPage
                    when (AppTab.entries[page]) {
                        AppTab.Home -> HomeScreen(
                            bottomInnerPadding = bottomInnerPadding,
                            isActive = isCurrentPage,
                        )
                        AppTab.Contacts -> ContactScreen(
                            bottomInnerPadding = bottomInnerPadding,
                            isActive = isCurrentPage,
                        )
                        AppTab.History -> HistoryScreen(
                            bottomInnerPadding = bottomInnerPadding,
                            isActive = isCurrentPage,
                        )
                        AppTab.Settings -> SettingsScreen(
                            bottomInnerPadding = bottomInnerPadding,
                            isActive = isCurrentPage,
                            onOpenThemeSettings = { showThemeScreen = true },
                            onOpenKeepAlive = { showKeepAlive = true },
                        )
                    }
                },
            )

            // 主题与外观：全屏推入页。
            // ⚠️ 必须留在外层 Scaffold 的 content slot 内（KSU 同构）：miuix 的下拉/对话框
            // 浮层渲染在外层 Scaffold 的 popup slot（content 之上的最高层）；若推入页作为
            // Scaffold 的兄弟节点声明，会整体盖住浮层 —— 表现为「关键色下拉/对话框打不开」。
            AnimatedVisibility(
                visible = showThemeScreen,
                enter = slideInVertically { it } + fadeIn(tween(pushIn)),
                exit = slideOutVertically { it } + fadeOut(tween(pushOut)),
                modifier = Modifier.fillMaxSize(),
            ) {
                ThemeScreen(onBack = { showThemeScreen = false })
            }

            // 后台保活向导：同款全屏推入
            AnimatedVisibility(
                visible = showKeepAlive,
                enter = slideInVertically { it } + fadeIn(tween(pushIn)),
                exit = slideOutVertically { it } + fadeOut(tween(pushOut)),
                modifier = Modifier.fillMaxSize(),
            ) {
                KeepAliveScreen(onBack = { showKeepAlive = false })
            }
        }
    }

    BackHandler(enabled = showKeepAlive) { showKeepAlive = false }
    BackHandler(enabled = showThemeScreen && !showKeepAlive) { showThemeScreen = false }
}
