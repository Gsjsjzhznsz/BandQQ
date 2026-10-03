package io.github.gsjsjzhznsz.bandqq.ui

/**
 * v2.14.0 底部标签页集合（flavor 隔离，编译期二选一）：
 * - 瘦包（companion）：4 页不变（AstrBot 保持设置页伴侣模式卡片，不占标签页）
 * - 胖包（bundled）：AstrBot 独立成第 4 个标签页（见 bundled sourceSet 同名文件）
 */
val VisibleTabs: List<AppTab> = listOf(
    AppTab.Home,
    AppTab.Contacts,
    AppTab.History,
    AppTab.Settings,
)
