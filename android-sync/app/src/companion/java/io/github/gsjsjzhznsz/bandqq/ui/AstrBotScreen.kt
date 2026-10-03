package io.github.gsjsjzhznsz.bandqq.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp

/**
 * v2.14.0 瘦包（companion flavor）AstrBotScreen stub：
 * 瘦包无「AstrBot」标签页（见同包 AppTabSet.kt —— 4 页不含 AstrBot），
 * AstrBot 能力仍是设置页的伴侣模式卡片（AstrBotSection.kt，检测拉起独立 AstrBot Bubble App）。
 * 本 stub 仅满足主源码 BandQQApp 的 when 分支编译引用，运行时永不渲染。
 */
@Composable
fun AstrBotScreen(
    bottomInnerPadding: Dp,
    isActive: Boolean,
) {
    // 不可达：companion 的 VisibleTabs 不含 AppTab.AstrBot
}
