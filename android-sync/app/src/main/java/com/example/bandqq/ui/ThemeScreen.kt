package com.example.bandqq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bandqq.config.ConfigManager
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 主题与外观全屏设置页（对齐 KernelSU manager ColorPaletteScreen 的交互：
 * TabRow 三档主题模式 + 动态取色开关 + 液态玻璃开关，返回箭头/系统返回键退出）。
 */
@Composable
fun ThemeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val configManager = remember { ConfigManager(context) }
    val themeMode by configManager.observeThemeMode().collectAsState(initial = 0)
    val navGlass by configManager.observeNavGlass().collectAsState(initial = true)
    val mode = ThemeMode.fromValue(themeMode)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MiuixTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()),
    ) {
        SmallTopAppBar(
            title = "主题与外观",
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = MiuixIcons.Back,
                        contentDescription = "返回",
                        tint = MiuixTheme.colorScheme.onBackground,
                    )
                }
            },
        )

        SmallTitle(text = "主题模式")
        TabRow(
            tabs = listOf("跟随系统", "浅色", "深色"),
            selectedTabIndex = mode.value % 3,
            onTabSelected = { index ->
                scope.launch { configManager.setThemeMode(index + if (mode.isMonet) 3 else 0) }
            },
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            SwitchPreference(
                checked = mode.isMonet,
                onCheckedChange = { on ->
                    scope.launch {
                        configManager.setThemeMode(mode.value % 3 + if (on) 3 else 0)
                    }
                },
                title = "动态取色（Material You）",
                summary = "跟随系统壁纸取色；Android 12 以下回退 miuix 蓝",
            )
        }
        Text(
            text = "当前：${mode.label}",
            modifier = Modifier.padding(horizontal = 20.dp),
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )

        SmallTitle(text = "液态玻璃")
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            SwitchPreference(
                checked = navGlass,
                onCheckedChange = { on -> scope.launch { configManager.setNavGlass(on) } },
                title = "悬浮栏液态玻璃",
                summary = "内容实时穿透模糊 + 镜头折射（Android 13+）；关闭后为实色悬浮栏",
            )
        }

        Spacer(modifier = Modifier.height(120.dp))
    }
}
