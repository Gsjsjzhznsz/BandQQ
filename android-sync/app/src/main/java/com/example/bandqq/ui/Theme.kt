package com.example.bandqq.ui

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowInsetsControllerCompat
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 主题模式（参考 KernelSU manager 的 ColorMode，取 miuix ThemeController 支持的六种）。
 *
 * 渲染问题根因说明：旧实现 `MiuixTheme(content)` 走的是 colors 参数默认值的重载，
 * 永远停留在浅色 LightColors，不跟随系统深色模式，也不调整状态栏图标颜色——
 * 这正是与 KernelSU（正确使用 ThemeController + ColorSchemeMode）渲染不一致的原因。
 */
enum class ThemeMode(val value: Int, val label: String) {
    SYSTEM(0, "跟随系统"),
    LIGHT(1, "浅色"),
    DARK(2, "深色"),
    MONET_SYSTEM(3, "动态取色 · 跟随系统"),
    MONET_LIGHT(4, "动态取色 · 浅色"),
    MONET_DARK(5, "动态取色 · 深色");

    val isDark: Boolean get() = value == 2 || value == 5
    val isSystem: Boolean get() = value == 0 || value == 3
    val isMonet: Boolean get() = value >= 3

    companion object {
        fun fromValue(value: Int): ThemeMode = entries.find { it.value == value } ?: SYSTEM
    }
}

/** Android 12 以下无系统取色板，回退 miuix 品牌蓝作为 Monet 种子色（避免紫罗兰默认色）。 */
private val FALLBACK_KEY_COLOR = Color(0xFF3482FF)

@Composable
fun BandQQTheme(themeMode: Int, content: @Composable () -> Unit) {
    val mode = ThemeMode.fromValue(themeMode)
    val darkTheme = mode.isDark || (mode.isSystem && isSystemInDarkTheme())

    val schemeMode = when (mode) {
        ThemeMode.SYSTEM -> ColorSchemeMode.System
        ThemeMode.LIGHT -> ColorSchemeMode.Light
        ThemeMode.DARK -> ColorSchemeMode.Dark
        ThemeMode.MONET_SYSTEM -> ColorSchemeMode.MonetSystem
        ThemeMode.MONET_LIGHT -> ColorSchemeMode.MonetLight
        ThemeMode.MONET_DARK -> ColorSchemeMode.MonetDark
    }

    // Monet 模式下 keyColor 为空时，miuix 会读取系统动态色板（Android 13+ 系统调色板 / 12+ 系统 Md3 角色）
    val keyColor: Color? =
        if (mode.isMonet && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) FALLBACK_KEY_COLOR else null

    val controller = remember(schemeMode, darkTheme, keyColor) {
        ThemeController(
            colorSchemeMode = schemeMode,
            keyColor = keyColor,
            isDark = darkTheme,
        )
    }

    MiuixTheme(controller = controller) {
        val activity = LocalContext.current as? Activity
        LaunchedEffect(darkTheme) {
            val window = activity?.window ?: return@LaunchedEffect
            WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
        androidx.compose.runtime.CompositionLocalProvider(LocalBandQQDarkTheme provides darkTheme) {
            content()
        }
    }
}
