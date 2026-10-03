package io.github.gsjsjzhznsz.bandqq.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * v2.14.0 胖包（bundled flavor）设置页 AstrBot 卡片：
 * 引擎管理已整体迁至底部「AstrBot」独立标签页（AstrBotScreen.kt），此处仅保留指引卡，
 * 避免设置页与标签页两处入口状态不同步。同签名实现（companion flavor）仍是伴侣模式卡片。
 */
@Composable
fun AstrBotSection(
    entered: Boolean,
    onFillLocalAddresses: () -> Unit,
) {
    val colorScheme = MiuixTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth().listItemReveal(entered, 6)) {
        Text(
            text = "胖包内嵌引擎版：AstrBot 引擎已独立为底部「AstrBot」标签页，" +
                "安装/启动/停止/日志/本机 NapCat 探测与一键直连都在该页完成。",
            modifier = Modifier.padding(12.dp),
            fontSize = 12.sp,
            color = colorScheme.onSurfaceSecondary,
        )
    }
}
