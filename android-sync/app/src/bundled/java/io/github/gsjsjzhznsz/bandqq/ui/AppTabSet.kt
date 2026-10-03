package io.github.gsjsjzhznsz.bandqq.ui

/**
 * v2.14.0 底部标签页集合（flavor 隔离，编译期二选一）：
 * - 胖包（bundled）：AstrBot 独立成第 4 个标签页（内嵌引擎管理全在标签页内完成）
 * - 瘦包（companion）：4 页不变，AstrBot 仍是设置页里的伴侣模式卡片
 *
 * AppTab 枚举在 main sourceSet（两 flavor 共有）；本集合决定实际显示与页序，
 * BandQQApp 的 HorizontalPager 与 BottomBar 都以本集合为准。
 */
val VisibleTabs: List<AppTab> = listOf(
    AppTab.Home,
    AppTab.Contacts,
    AppTab.History,
    AppTab.AstrBot,
    AppTab.Settings,
)
