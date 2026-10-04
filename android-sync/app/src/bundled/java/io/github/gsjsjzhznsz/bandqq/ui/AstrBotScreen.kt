package io.github.gsjsjzhznsz.bandqq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import io.github.gsjsjzhznsz.bandqq.WebUiActivity
import io.github.gsjsjzhznsz.bandqq.astrbot.AstrBotBridge
import io.github.gsjsjzhznsz.bandqq.astrbot.engine.AstrBotEngineProbe
import io.github.gsjsjzhznsz.bandqq.astrbot.engine.AstrBotSecrets
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
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.CloudFill
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * v2.14.0 胖包（bundled flavor）AstrBot 独立标签页：
 * 引擎（proot + Ubuntu rootfs + astrbot-startup.sh）随 APK 分发，本页承载全部引擎管理：
 * 安装 → 启动（前台服务保活）→ 本机 NapCat 就绪 → 一键保存本机地址直连。
 *
 * v2.20.0 可视化改造（用户反馈：普通用户看不懂 log，不知道流程到哪了）：
 *  - 新增「启动进度」卡：8 个阶段大白话清单 + 进度条，由脚本 stage() 标记实时驱动；
 *  - 新增「打开控制台」按钮（对齐 AstrBot Bubble/泡泡版）：系统 WebView 打开
 *    AstrBot 控制台 http://127.0.0.1:6185（复用 WebUiActivity）；
 *  - 引擎中断时给出可行动的通俗提示（重试不从头装）。
 *
 * 瘦包（companion）的同签名 stub 在 app/src/companion/ 下（瘦包无此标签页，永不渲染）。
 */

/** 启动进度阶段定义（pct 与 astrbot-startup.sh 的 stage() 标记一一对应） */
private data class StageDef(val pct: Int, val title: String, val hint: String)

private val STAGES = listOf(
    StageDef(5, "准备容器", "解压 Ubuntu 运行时（已内置在安装包，无需下载）"),
    StageDef(10, "基础命令", "安装 git/curl 等工具（清华镜像，约 1 分钟）"),
    StageDef(20, "uv 工具", "下载 Python 包管理器 uv（约 20MB，多源竞速）"),
    StageDef(35, "下载 LinuxQQ", "约 200MB，约 1~3 分钟（中断自动换源重试）"),
    StageDef(45, "安装 NapCat", "QQ↔OneBot 协议桥，含依赖约 2~5 分钟"),
    StageDef(60, "下载 AstrBot", "拉取最新正式版源码（约 30MB）"),
    StageDef(75, "Python 依赖", "AstrBot 运行依赖，首次约 3~8 分钟"),
    StageDef(92, "启动服务", "拉起 AstrBot 与 OneBot（WS:3001 / HTTP:3000）"),
)

/** AstrBot 控制台地址（cmd_config.json 默认 dashboard 端口 6185） */
private const val ASTRBOT_DASHBOARD_URL = "http://127.0.0.1:6185"

/** NapCat WebUI 地址（astrbot-startup.sh 固定写入 webui.json；未登录 QQ 时扫码入口） */
private const val NAPCAT_WEBUI_URL = "http://127.0.0.1:5099"

/** v2.22.0：复制到剪贴板 + toast（「自动读取2个程序的密码给复制」） */
private fun copySecret(context: android.content.Context, label: String, value: String) {
    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
        as? android.content.ClipboardManager
    if (cm == null) {
        toast(context, "剪贴板不可用")
        return
    }
    cm.setPrimaryClip(android.content.ClipData.newPlainText(label, value))
    toast(context, "${label}已复制")
}

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
    // v2.22.0：本机两个程序的账号信息（AstrBot WebUI 密码 / NapCat WebUI Token）+ 手动刷新计数
    var secretsVersion by remember { mutableStateOf(0) }
    val secrets = remember(secretsVersion) { AstrBotSecrets.read(context) }
    var astrbotEnabled by remember { mutableStateOf(EngineManager.isAstrbotEnabled(context)) }

    val running = EngineManager.isRunning()
    val installing = state as? EngineManager.State.Installing

    val statusText = when (state) {
        is EngineManager.State.Idle ->
            if (EngineManager.isInstalled(context)) "引擎已安装（未启动）" else "引擎未安装"
        is EngineManager.State.Installing ->
            "安装中：${installing?.step}（${installing?.percent}%）"
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
                            "BandQQ 直连 127.0.0.1 同步手环，同时你的 QQ 号获得大模型自动回复能力。",
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

            // ===== 启动进度卡（v2.20.0：大白话分步进度，替代让用户读裸 log）=====
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    val inProgress = state is EngineManager.State.Installing ||
                        state is EngineManager.State.Starting
                    val finished = state is EngineManager.State.Running
                    Text(
                        text = when {
                            finished -> "启动完成"
                            inProgress -> "启动进度"
                            state is EngineManager.State.Error -> "启动中断"
                            else -> "启动进度（未开始）"
                        },
                        fontSize = 14.sp,
                    )
                    when {
                        // 未开始 / 已停止：说明性文案，不留空卡
                        !inProgress && !finished && state !is EngineManager.State.Error -> {
                            Text(
                                text = "点「启动引擎」后，这里会实时显示安装/启动到哪一步、" +
                                    "大概还要多久，不需要看懂日志。",
                                modifier = Modifier.padding(top = 6.dp),
                                fontSize = 12.sp,
                                color = colorScheme.onSurfaceSecondary,
                            )
                        }
                        else -> {
                            val percent = when {
                                finished -> 100
                                installing != null -> installing.percent
                                else -> 0
                            }
                            // 进度条
                            Spacer(Modifier.height(8.dp))
                            androidx.compose.foundation.layout.Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .background(
                                        colorScheme.surfaceContainer,
                                        RoundedCornerShape(3.dp),
                                    ),
                            ) {
                                androidx.compose.foundation.layout.Box(
                                    modifier = Modifier
                                        .fillMaxWidth(percent / 100f)
                                        .height(6.dp)
                                        .background(
                                            colorScheme.primary,
                                            RoundedCornerShape(3.dp),
                                        ),
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            // 阶段清单：已完成 ✓ / 进行中（主色加粗）/ 未开始（灰）
                            STAGES.forEachIndexed { idx, s ->
                                val nextPct = STAGES.getOrNull(idx + 1)?.pct ?: 101
                                val done = finished || percent >= nextPct ||
                                    (state is EngineManager.State.Error && percent >= nextPct)
                                val active = !done && percent >= s.pct
                                val failedHere = state is EngineManager.State.Error && active
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(vertical = 3.dp),
                                ) {
                                    androidx.compose.foundation.layout.Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(
                                                when {
                                                    done -> colorScheme.primary
                                                    active && !failedHere -> colorScheme.primary
                                                    failedHere -> colorScheme.onErrorContainer
                                                    else -> colorScheme.onSurfaceSecondary.copy(alpha = 0.35f)
                                                },
                                                CircleShape,
                                            ),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = "${idx + 1}. ${s.title}" + when {
                                                done -> "（完成）"
                                                active && !failedHere -> "（进行中…）"
                                                failedHere -> "（中断）"
                                                else -> ""
                                            },
                                            fontSize = 13.sp,
                                            color = when {
                                                done || active -> colorScheme.primary
                                                failedHere -> colorScheme.onErrorContainer
                                                else -> colorScheme.onSurfaceSecondary
                                            },
                                        )
                                        if (active || (done && idx == STAGES.lastIndex)) {
                                            Text(
                                                text = if (finished) "全部就绪，可点「打开控制台」管理机器人" else s.hint,
                                                fontSize = 11.sp,
                                                color = colorScheme.onSurfaceSecondary,
                                            )
                                        }
                                    }
                                }
                            }
                            if (inProgress) {
                                Text(
                                    text = "提示：中途断了没关系，再点「启动引擎」会接着已下载的部分继续，不会从头安装。",
                                    modifier = Modifier.padding(top = 8.dp),
                                    fontSize = 11.sp,
                                    color = colorScheme.onSurfaceSecondary,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // ===== 启动 / 停止 =====
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            if (!EngineManager.installIfNeeded(context)) {
                                toast(context, "引擎安装失败，看下方日志定位")
                                return@launch
                            }
                            EngineManager.start(context)
                            toast(context, "引擎启动中，进度见下方卡片")
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

            // ===== 探测 / 控制台 / 日志开关（v2.20.0：新增打开控制台，泡泡版同款）=====
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
                ) { Text("检测 NapCat") }
                Button(
                    onClick = {
                        // v2.20.0：泡泡版（AstrBot Bubble）同款入口——系统 WebView 打开
                        // AstrBot 控制台（扫码登录插件/配置模型/查看日志）
                        runCatching {
                            context.startActivity(
                                android.content.Intent(context, WebUiActivity::class.java)
                                    .putExtra("url", ASTRBOT_DASHBOARD_URL)
                            )
                        }.onFailure { toast(context, "打开控制台失败：${it.message}") }
                    },
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                    enabled = running,
                ) { Text("打开控制台") }
                Button(
                    onClick = { showLogs = !showLogs },
                    colors = ButtonDefaults.buttonColors(),
                ) { Text(if (showLogs) "收日志" else "看日志") }
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

            // ===== 密码与登录（v2.22.0：自动读取两个程序的密码，一键复制/打开 WebUI）=====
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("密码与登录", fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Button(
                            onClick = { secretsVersion++ },
                            colors = ButtonDefaults.buttonColors(),
                        ) { Text("刷新", fontSize = 12.sp) }
                    }
                    Text(
                        text = "AstrBot 控制台用户名默认 astrbot；若你在控制台改过密码，以改后的为准。" +
                            "首次使用需先启动引擎完成安装。",
                        modifier = Modifier.padding(top = 4.dp),
                        fontSize = 11.sp,
                        color = colorScheme.onSurfaceSecondary,
                    )
                    // AstrBot WebUI 初始密码（引擎日志自动读取）
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("AstrBot 控制台密码", fontSize = 13.sp)
                            Text(
                                text = secrets.webuiPassword ?: "尚未获取（引擎启动过一次后自动出现）",
                                fontSize = 12.sp,
                                color = colorScheme.primary,
                            )
                        }
                        Button(
                            onClick = {
                                val v = secrets.webuiPassword
                                if (v.isNullOrBlank()) toast(context, "暂无密码：先启动引擎完成一次安装")
                                else copySecret(context, "AstrBot 密码", v)
                            },
                            colors = ButtonDefaults.buttonColorsPrimary(),
                        ) { Text("复制", fontSize = 12.sp) }
                    }
                    // NapCat WebUI Token（扫码登录 QQ 用）
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(" NapCat WebUI Token", fontSize = 13.sp)
                            Text(
                                text = secrets.napcatToken ?: "尚未获取（引擎安装 NapCat 后自动出现）",
                                fontSize = 12.sp,
                                color = colorScheme.primary,
                            )
                        }
                        Button(
                            onClick = {
                                val v = secrets.napcatToken
                                if (v.isNullOrBlank()) toast(context, "暂无 Token：先启动引擎完成 NapCat 安装")
                                else copySecret(context, " NapCat Token", v)
                            },
                            colors = ButtonDefaults.buttonColorsPrimary(),
                        ) { Text("复制", fontSize = 12.sp) }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        android.content.Intent(context, WebUiActivity::class.java)
                                            .putExtra("url", ASTRBOT_DASHBOARD_URL)
                                    )
                                }.onFailure { toast(context, "打开失败：${it.message}") }
                            },
                            colors = ButtonDefaults.buttonColors(),
                            modifier = Modifier.weight(1f),
                        ) { Text("开控制台", fontSize = 12.sp) }
                        Button(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        android.content.Intent(context, WebUiActivity::class.java)
                                            .putExtra("url", NAPCAT_WEBUI_URL)
                                    )
                                    toast(context, "用 Token 登录后扫码登录 QQ")
                                }.onFailure { toast(context, "打开失败：${it.message}") }
                            },
                            colors = ButtonDefaults.buttonColors(),
                            modifier = Modifier.weight(1f),
                        ) { Text(" NapCat 扫码", fontSize = 12.sp) }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // ===== AstrBot 机器人开关（v2.22.0：是否启动/安装 AstrBot；关闭 = 仅 NapCat 模式）=====
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("启动 AstrBot 机器人", fontSize = 14.sp)
                        Text(
                            text = "关闭后引擎仅安装/运行 NapCat（手环 QQ 直连所需），" +
                                "不下载不启动 AstrBot，更省电省存储；下次启动引擎生效。",
                            modifier = Modifier.padding(top = 4.dp),
                            fontSize = 11.sp,
                            color = colorScheme.onSurfaceSecondary,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Switch(
                        checked = astrbotEnabled,
                        onCheckedChange = {
                            astrbotEnabled = it
                            EngineManager.setAstrbotEnabled(context, it)
                            toast(
                                context,
                                if (it) "已开启：下次启动引擎生效" else "已关闭：下次启动引擎仅运行 NapCat",
                            )
                        },
                    )
                }
            }

            // ===== 引擎日志（进阶；v2.18.1 起同时落盘 files/logs/engine-*.log）=====
            if (showLogs) {
                Spacer(Modifier.height(10.dp))
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("详细日志（进阶）", fontSize = 13.sp)
                        Text(
                            text = "完整日志已落盘：Android/data/" +
                                "${context.packageName}/files/logs/engine-*.log（可用文件管理器取出，导出日志 zip 同样收录）",
                            modifier = Modifier.padding(top = 4.dp),
                            fontSize = 10.sp,
                            color = colorScheme.onSurfaceSecondary,
                        )
                        // v2.18.1：80 行长文本改为定高滚动容器，页不再被整段撑高/依赖外部测量
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(240.dp)
                                .padding(top = 8.dp)
                                .background(
                                    color = colorScheme.surfaceContainer,
                                    shape = RoundedCornerShape(10.dp),
                                ),
                        ) {
                            Text(
                                text = logs.takeLast(80).joinToString("\n").ifBlank { "暂无日志" },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                                    .padding(10.dp),
                                fontSize = 10.sp,
                                color = colorScheme.onSurfaceSecondary,
                                lineHeight = 14.sp,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(bottomInnerPadding + 12.dp))
        }
    }
}
