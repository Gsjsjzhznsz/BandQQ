package com.example.bandqq.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/** 当前应用是否处于深色主题（由 BandQQTheme 提供，供液态玻璃组件在 draw 阶段读取） */
val LocalBandQQDarkTheme = staticCompositionLocalOf { false }

/** 对齐 KernelSU ui/theme.isInDarkTheme 的读取方式 */
@Composable
fun isInDarkTheme(): Boolean = LocalBandQQDarkTheme.current

// ===== 以下 CompositionLocal 与 KernelSU ui/theme/Theme.kt 一一对应 =====

/** 启用顶栏和底栏的模糊效果（Android 13+ 生效） */
val LocalEnableBlur = staticCompositionLocalOf { false }

/** 使用 Apple 风格的悬浮底栏 */
val LocalEnableFloatingBottomBar = staticCompositionLocalOf { false }

/** 启用悬浮底栏的液态玻璃效果（悬浮底栏开启的二级选项，Android 13+ 生效） */
val LocalEnableFloatingBottomBarGlass = staticCompositionLocalOf { false }

/** 在导航栏显示未读消息角标 */
val LocalEnableNavigationBadge = staticCompositionLocalOf { true }
