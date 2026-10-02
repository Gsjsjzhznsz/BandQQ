package io.github.gsjsjzhznsz.bandqq.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gsjsjzhznsz.bandqq.astrbot.AstrBotBridge
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.CloudFill
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * v2.13.0 瘦包（companion flavor）AstrBot 卡片：伴侣模式。
 * 本包不含引擎，检测拉起独立 AstrBot Bubble App（MuFengDR/AstrBot-Bubble-Android-App），
 * 与 v2.12.0 行为一致。胖包（bundled）的同签名实现在 app/src/bundled/ 下。
 */
@Composable
fun AstrBotSection(
    entered: Boolean,
    onFillLocalAddresses: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colorScheme = MiuixTheme.colorScheme
    var astrbotInstalled by remember { mutableStateOf(false) }
    var astrbotProbeMsg by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        astrbotInstalled = AstrBotBridge.installedPackage(context) != null
    }

    Card(modifier = Modifier.fillMaxWidth().listItemReveal(entered, 6)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    MiuixIcons.CloudFill,
                    contentDescription = "AstrBot",
                    tint = colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (astrbotInstalled) "AstrBot Bubble 已安装" else "未检测到 AstrBot Bubble",
                    fontSize = 14.sp,
                )
            }
            Text(
                text = "当前为瘦包（伴侣版）：AstrBot Bubble 在手机 proot 容器里一体化部署 " +
                    "NapCat + AstrBot，拉起它并启动后，本机即有 OneBot 服务（WS :3001 / HTTP :3000），" +
                    "BandQQ 直连 127.0.0.1 就能同步手环，同时你的 QQ 号获得大模型自动回复能力。" +
                    "零电脑、零局域网，与 eSIM 手表的独立直连线路天然兼容。" +
                    "想要开箱即用、无需另装应用？请改用胖包（bundled 版，内嵌本地引擎）。",
                modifier = Modifier.padding(top = 8.dp),
                fontSize = 12.sp,
                color = colorScheme.onSurfaceSecondary,
            )
            if (astrbotProbeMsg.isNotBlank()) {
                Text(
                    text = astrbotProbeMsg,
                    modifier = Modifier.padding(top = 8.dp),
                    fontSize = 13.sp,
                    color = colorScheme.primary,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = {
                        if (astrbotInstalled) {
                            if (!AstrBotBridge.launch(context)) toast(context, "拉起失败，请手动打开 AstrBot Bubble")
                        } else {
                            AstrBotBridge.openRepo(context)
                        }
                    },
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                ) { Text(if (astrbotInstalled) "打开 AstrBot Bubble" else "打开项目主页") }
                Button(
                    onClick = {
                        scope.launch {
                            val probe = AstrBotBridge.probeLocalNapcat()
                            astrbotProbeMsg = probe.describe()
                            toast(context, probe.describe())
                        }
                    },
                    colors = ButtonDefaults.buttonColors(),
                    modifier = Modifier.weight(1f),
                ) { Text("检测本机 NapCat") }
            }
            Button(
                onClick = onFillLocalAddresses,
                colors = ButtonDefaults.buttonColors(),
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            ) { Text("一键填入本机 NapCat 地址（127.0.0.1）") }
        }
    }
}
