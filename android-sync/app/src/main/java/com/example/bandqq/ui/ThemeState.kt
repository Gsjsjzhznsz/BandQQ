package com.example.bandqq.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/** 当前应用是否处于深色主题（由 BandQQTheme 提供，供液态玻璃组件在 draw 阶段读取） */
val LocalBandQQDarkTheme = staticCompositionLocalOf { false }

/** 对齐 KernelSU ui/theme.isInDarkTheme 的读取方式 */
@Composable
fun isInDarkTheme(): Boolean = LocalBandQQDarkTheme.current
