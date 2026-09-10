package com.example.bandqq.ui.component

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bandqq.ui.AppTab
import com.example.bandqq.ui.util.BlurredBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Messages
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.basic.Badge
import top.yukonga.miuix.kmp.basic.BadgedBox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 主界面底栏（对齐 KernelSU BottomBarMiuix 的双形态）：
 * - 非悬浮：BlurredBar(textureBlur) 包 miuix NavigationBar，模糊开启时本体透明；
 * - 悬浮：KernelSU 同款 FloatingBottomBar（液态玻璃 + 阻尼拖拽指示 pill + 交互高光）。
 *
 * @param blurBackdrop 顶栏/普通底栏共用的模糊采集层（null = 模糊关闭或设备不支持）
 * @param backdrop     悬浮底栏液态玻璃的采集层
 * @param unread       未读消息总数（角标；0 或 <=0 不显示）
 */
@Composable
fun BottomBar(
    blurBackdrop: LayerBackdrop?,
    backdrop: Backdrop,
    selected: Int,
    onSelect: (Int) -> Unit,
    unread: Int,
    modifier: Modifier = Modifier,
    enableFloatingBottomBar: Boolean,
    enableFloatingBottomBarGlass: Boolean,
) {
    if (!enableFloatingBottomBar) {
        BlurredBar(blurBackdrop) {
            NavigationBar(
                modifier = modifier,
                color = if (blurBackdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
                content = {
                    AppTab.entries.forEachIndexed { index, tab ->
                        NavigationBarItem(
                            modifier = Modifier.weight(1f),
                            icon = tab.icon(),
                            label = tab.label,
                            selected = selected == index,
                            onClick = { onSelect(index) },
                            badge = navigationBadgeFor(index, unread, tab),
                        )
                    }
                }
            )
        }
    } else {
        // KernelSU 同款：悬浮栏底距 = 导航栏 inset + 8dp（无手势导航设备回退 28dp），
        // 否则底栏贴到屏幕底边（偏下）；容器 pointerInput 吞掉栏外空白区点击防穿透
        val bottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            .let { inset -> if (inset != 0.dp) 8.dp + inset else 28.dp }
        FloatingBottomBar(
            modifier = modifier
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(start = 28.dp, end = 28.dp, bottom = bottomPadding),
            selectedIndex = selected,
            onSelected = onSelect,
            backdrop = backdrop,
            tabsCount = AppTab.entries.size,
            isBlurEnabled = enableFloatingBottomBarGlass,
        ) { activateTab ->
            AppTab.entries.forEachIndexed { index, tab ->
                FloatingBottomBarItem(
                    selected = selected == index,
                    onClick = { activateTab(index) },
                    // 关键：weight 子项在 IntrinsicSize.Min 的 intrinsic 测量中宽度为 0，
                    // 必须 minWidth 兜底，否则整个底栏塌缩成一个颗粒（KSU 同款写法）
                    modifier = Modifier.defaultMinSize(minWidth = 76.dp),
                ) {
                    val badge = navigationBadgeFor(index, unread, tab)
                    val icon: @Composable () -> Unit = {
                        Icon(
                            imageVector = tab.icon(),
                            contentDescription = tab.label,
                        )
                    }
                    if (badge != null) {
                        BadgedBox(badge = { badge() }) { icon() }
                    } else {
                        icon()
                    }
                    Text(
                        text = tab.label,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Visible
                    )
                }
            }
        }
    }
}

/** 未读角标挂在「聊天记录」页签上（BandQQ 的未读即消息数） */
private fun navigationBadgeFor(
    index: Int,
    unread: Int,
    tab: AppTab,
): (@Composable () -> Unit)? {
    if (tab != AppTab.History || unread <= 0) return null
    return {
        Badge(
            containerColor = MiuixTheme.colorScheme.primary,
            contentColor = MiuixTheme.colorScheme.onPrimary,
        ) {
            Text(if (unread > 99) "99+" else unread.toString())
        }
    }
}

private fun AppTab.icon(): ImageVector = when (this) {
    AppTab.Home -> MiuixIcons.Home
    AppTab.Contacts -> MiuixIcons.Contacts
    AppTab.History -> MiuixIcons.Messages
    AppTab.Settings -> MiuixIcons.Settings
}
