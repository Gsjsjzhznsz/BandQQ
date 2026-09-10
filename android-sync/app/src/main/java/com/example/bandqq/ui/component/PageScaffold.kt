package com.example.bandqq.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.example.bandqq.ui.LocalEnableBlur
import com.example.bandqq.ui.util.BlurredBar
import com.example.bandqq.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 主界面分页容器（对齐 KernelSU「每页自带 Scaffold」的结构，修复顶栏遮挡内容）：
 * - 每页独立 topBar（BlurredBar + textureBlur）：内容从顶栏下穿过，顶栏玻璃才有东西可模糊；
 * - 每页独立 blurBackdrop（外层另有全局 backdrop 供底栏使用，KSU 同构）；
 * - contentWindowInsets 只取水平方向：顶部 inset 已由 SmallTopAppBar 自行消化，
 *   innerPadding.top = 顶栏总高（含状态栏），innerPadding.bottom = 0
 *   （底部安全余量统一由外层 bottomInnerPadding 提供，调用方在滚动内容末尾补 Spacer）。
 *
 * 页面用法：
 * ```
 * PageScaffold(title = "主页", bottomInnerPadding = bottomInnerPadding) { innerPadding ->
 *     Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
 *         Spacer(Modifier.height(innerPadding.calculateTopPadding() + 16.dp))
 *         // ... 内容 ...
 *         Spacer(Modifier.height(bottomInnerPadding + 12.dp))
 *     }
 * }
 * ```
 */
@Composable
fun PageScaffold(
    title: String,
    bottomInnerPadding: Dp,
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    Scaffold(
        modifier = modifier,
        topBar = {
            BlurredBar(backdrop) {
                SmallTopAppBar(
                    title = title,
                    color = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface,
                )
            }
        },
        // 弹出层宿主统一由外层 Scaffold 持有，页内不重复挂载
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars
            .add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box(
            modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier
        ) {
            content(innerPadding)
        }
    }
}
