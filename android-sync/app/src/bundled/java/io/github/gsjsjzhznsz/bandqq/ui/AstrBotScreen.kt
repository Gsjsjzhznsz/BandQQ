package io.github.gsjsjzhznsz.bandqq.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import io.github.gsjsjzhznsz.bandqq.astrbot.AstrBotBridge
import io.github.gsjsjzhznsz.bandqq.astrbot.engine.AstrBotEngineProbe
import io.github.gsjsjzhznsz.bandqq.astrbot.engine.EngineManager
import io.github.gsjsjzhznsz.bandqq.config.ConfigHolder
import io.github.gsjsjzhznsz.bandqq.config.ConfigManager
import io.github.gsjsjzhznsz.bandqq.sync.SyncService
import io.github.gsjsjzhznsz.bandqq.ui.component.PageScaffold
import io.github.gsjsjzhznsz.bandqq.ui.toast
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
 * v2.14.0 胖包（bundled flavor）AstrBot 独立标签页：
 * 引擎（proot + Ubuntu rootfs + astrbot-startup.sh）随 APK 分发，本页承载全部引擎管理：
 * 安装 → 启动（前台服务保活）→ 本机 NapCat 就绪 → 一键保存本机地址直连。
 * v2.13.0 时这些能力挤在设置页一张卡片里；独立标签页后日志区更大、操作不被设置页
 * 长列表淹没（用户指令：胖包的 astrbot 单独开一个标签页）。
 *
 * 瘦包（companion）的同签名 stub 在 app/src/companion/ 下（瘦包无此标签页，永不渲染）。
 */
@Composable
fun AstrBotScreen(
    bottomInnerPadding: androidx.compose.ui.unit.Dp,
    isActive: Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colorScheme = MiuixTheme.colorScheme
    val configManager = remember { ConfigManager(context) }
    val state by EngineManager.state.collectAsState()
    val logs by EngineManager.log.collectAsState()
    var probeMsg by remember { mutableStateOf("") }
    var showLogs by remember { mutableStateOf(true) }

    val statusText = when (state) {
        is EngineManager.State.Idle ->
            if (EngineManager.isInstalled(context)) "引擎已安装（未启动）" else "引擎未安装"
        is EngineManager.State.Installing ->
            "安装中：${(state as EngineManager.State.Installing).step}" +
                "（${(state as EngineManager.State.Installing).percent}%）"
        is EngineManager.State.Starting ->
            "启动中（首次安装 AstrBot/NapCat 需联网数分钟，请保持前台）"
        is EngineManager.State.Running -> "运行中 · 本机 NapCat 在线"
        is EngineManager.State.Stopped -> "已停止"
        is EngineManager.State.Error -> (state as EngineManager.State.Error).msg
    }

    PageScaffold(title = "AstrBot", bottomInnerPadding = bottomInnerPadding) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(innerPadding.calculateTopPadding() + 12.dp))

            // ===== 引擎状态卡 =====
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            MiuixIcons.CloudFill,
                            contentDescription = "AstrBot",
                            tint = colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("本地引擎 · ${statusText}", fontSize = 15.sp)
                    }
                    Text(
                        text = "当前为胖包（内嵌引擎版）：AstrBot + NapCat + Ubuntu 容器已随 APK 内置" +
                            "（引擎层来自 MuFengDR/AstrBot-Bubble-Android-App），无需另装应用。" +
                            "点「启动引擎」后本机即有 OneBot 服务（WS :3001 / HTTP :3000），" +
                            "BandQQ 直连 127.0.0.1 同步手环，同时你的 QQ 号获得大模型自动回复能力。" +
                            "首次启动会下载 AstrBot/NapCat（清华源 + GitHub 代理），需要网络与电量。",
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
                }
            }

            Spacer(Modifier.height(10.dp))

            // ===== 启动 / 停止 =====
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
            ) {
                val running = EngineManager.isRunning()
                Button(
                    onClick = {
                        scope.launch {
                            if (!EngineManager.installIfNeeded(context)) {
                                toast(context, "引擎安装失败，看下方日志定位")
                                return@launch
                            }
                            EngineManager.start(context)
                            toast(context, "引擎启动中，就绪后状态会更新")
                        }
                    },
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                    enabled = state !is EngineManager.State.Installing && !running,
                ) {
                    Text(
                        if (state is EngineManager.State.Installing) "安装中…"
                        else if (running) "运行中" else "启动引擎"
                    )
                }
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

            Spacer(Modifier.height(10.dp))

            // ===== 探测 / 日志 =====
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
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
                ) { Text(if (showLogs) "收起日志" else "展开日志") }
            }

            Spacer(Modifier.height(10.dp))

            // ===== 一键保存本机地址（独立标签页版：直接落盘 + 同步，不再回设置页手填）=====
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("直连配置", fontSize = 14.sp)
                    Text(
                        text = "把 BandQQ 的 OneBot 服务地址直接写为引擎本机地址" +
                            "（WS ${AstrBotBridge.LOCAL_WS_URL} / HTTP ${AstrBotBridge.LOCAL_HTTP_URL}，" +
                            "Token 保持原值不变），保存后立即同步快捷回复到手环。",
                        modifier = Modifier.padding(top = 6.dp),
                        fontSize = 12.sp,
                        color = colorScheme.onSurfaceSecondary,
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                val cfg = ConfigHolder.config
                                configManager.save(
                                    cfg.copy(
                                        endpoint = cfg.endpoint.copy(
                                            wsUrl = AstrBotBridge.LOCAL_WS_URL,
                                            httpUrl = AstrBotBridge.LOCAL_HTTP_URL,
                                        )
                                    )
                                )
                                SyncService.pushQuickRepliesNow?.invoke()
                                toast(context, "已写入并保存本机地址（Token 未变），手环同步已触发")
                            }
                        },
                        colors = ButtonDefaults.buttonColorsPrimary(),
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    ) { Text("一键填入本机 NapCat 地址并保存") }
                }
            }

            // ===== 引擎日志（独立标签页放宽到 80 行）=====
            if (showLogs) {
                Spacer(Modifier.height(10.dp))
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("引擎日志（最近 80 行）", fontSize = 13.sp)
                        Text(
                            text = logs.takeLast(80).joinToString("\n").ifBlank { "暂无日志" },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            fontSize = 10.sp,
                            color = colorScheme.onSurfaceSecondary,
                            lineHeight = 14.sp,
                        )
                    }
                }
            }

            Spacer(Modifier.height(bottomInnerPadding + 12.dp))
        }
    }
}
