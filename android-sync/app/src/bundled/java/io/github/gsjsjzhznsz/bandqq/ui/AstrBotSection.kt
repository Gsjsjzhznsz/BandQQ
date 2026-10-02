package io.github.gsjsjzhznsz.bandqq.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import io.github.gsjsjzhznsz.bandqq.astrbot.engine.AstrBotEngineProbe
import io.github.gsjsjzhznsz.bandqq.astrbot.engine.EngineManager
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
 * v2.13.0 胖包（bundled flavor）AstrBot 卡片：内嵌本地引擎版。
 * 引擎（proot + Ubuntu rootfs + astrbot-startup.sh）已随 APK 分发：
 * 安装 → 启动（前台服务保活）→ 本机 NapCat 就绪 → 一键填配置直连。
 * 瘦包（companion）的同签名实现在 app/src/companion/ 下（伴侣模式检测拉起）。
 */
@Composable
fun AstrBotSection(
    entered: Boolean,
    onFillLocalAddresses: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colorScheme = MiuixTheme.colorScheme
    val state by EngineManager.state.collectAsState()
    val logs by EngineManager.log.collectAsState()
    var probeMsg by remember { mutableStateOf("") }
    var showLogs by remember { mutableStateOf(false) }

    val (title, desc) = when (state) {
        is EngineManager.State.Idle ->
            if (EngineManager.isInstalled(context)) "引擎已安装（未启动）" to descIdle
            else "引擎未安装" to descIdle
        is EngineManager.State.Installing ->
            "安装中：${(state as EngineManager.State.Installing).step}" to descIdle
        is EngineManager.State.Starting ->
            "启动中（首次安装 AstrBot/NapCat 需联网数分钟，请保持前台）" to descIdle
        is EngineManager.State.Running -> "运行中 · 本机 NapCat 在线" to descIdle
        is EngineManager.State.Stopped -> "已停止" to descIdle
        is EngineManager.State.Error ->
            (state as EngineManager.State.Error).msg to descIdle
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
                Text(title, fontSize = 14.sp)
            }
            Text(
                text = "当前为胖包（内嵌引擎版）：AstrBot + NapCat + Ubuntu 容器已随 APK 内置" +
                    "（来源 MuFengDR/AstrBot-Bubble-Android-App 引擎层），无需另装应用。" +
                    "点「启动引擎」后本机即有 OneBot 服务（WS :3001 / HTTP :3000），" +
                    "BandQQ 直连 127.0.0.1 同步手环，同时你的 QQ 号获得大模型自动回复能力。" +
                    "首次启动会下载 AstrBot/NapCat（清华源 + GitHub 代理），需要网络。",
                modifier = Modifier.padding(top = 8.dp),
                fontSize = 12.sp,
                color = colorScheme.onSurfaceSecondary,
            )
            if (state is EngineManager.State.Error) {
                Text(
                    text = (state as EngineManager.State.Error).msg,
                    modifier = Modifier.padding(top = 8.dp),
                    fontSize = 12.sp,
                    color = colorScheme.onErrorContainer,
                )
            }
            if (probeMsg.isNotBlank()) {
                Text(
                    text = probeMsg,
                    modifier = Modifier.padding(top = 8.dp),
                    fontSize = 13.sp,
                    color = colorScheme.primary,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val running = EngineManager.isRunning()
                Button(
                    onClick = {
                        scope.launch {
                            if (!EngineManager.installIfNeeded(context)) {
                                toast(context, "引擎安装失败，看下方日志")
                                return@launch
                            }
                            EngineManager.start(context)
                            toast(context, "引擎启动中，就绪后状态会更新")
                        }
                    },
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                    enabled = state !is EngineManager.State.Installing && !running,
                ) { Text(if (state is EngineManager.State.Installing) "安装中…" else if (running) "运行中" else "启动引擎") }
                Button(
                    onClick = {
                        EngineManager.stop(context)
                        toast(context, "已停止引擎")
                    },
                    colors = ButtonDefaults.buttonColors(),
                    modifier = Modifier.weight(1f),
                    enabled = running || state is EngineManager.State.Starting,
                ) { Text("停止") }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = {
                        val ready = AstrBotEngineProbe.portsReady()
                        probeMsg = when {
                            ready && AstrBotEngineProbe.portOpen(3001) -> "本机 NapCat 在线（WS :3001 可连）"
                            ready -> "本机 NapCat 在线（仅 HTTP :3000 可连）"
                            else -> "本机 NapCat 未就绪（3001/3000 均未监听；容器可能仍在安装/启动）"
                        }
                        EngineManager.note("手动探测：$probeMsg")
                    },
                    colors = ButtonDefaults.buttonColors(),
                    modifier = Modifier.weight(1f),
                ) { Text("检测本机 NapCat") }
                Button(
                    onClick = { showLogs = !showLogs },
                    colors = ButtonDefaults.buttonColors(),
                    modifier = Modifier.weight(1f),
                ) { Text(if (showLogs) "收起日志" else "引擎日志") }
            }
            Button(
                onClick = onFillLocalAddresses,
                colors = ButtonDefaults.buttonColors(),
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            ) { Text("一键填入本机 NapCat 地址（127.0.0.1）") }
            if (showLogs) {
                Text(
                    text = logs.takeLast(14).joinToString("\n").ifBlank { "暂无日志" },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    fontSize = 10.sp,
                    color = colorScheme.onSurfaceSecondary,
                    lineHeight = 14.sp,
                )
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

private val descIdle = ""
