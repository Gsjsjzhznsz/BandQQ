package io.github.gsjsjzhznsz.bandqq.devtools

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * BandQQ DevTools（v1.4.0）—— 模拟 OneBot 协议端的开发者测试工具。
 *
 * v1.4.0：UI 全面重写为 Compose + miuix（与 BandQQ 主 APK 同一套 HyperOS 设计语言），
 * 主题跟随系统深浅色；协议层（WsServer/HttpApiServer/ActionRouter）不变；
 * ActionRouter 同步补全 v2.13.0 APP 新动作的模拟应答（点赞/拍一拍/群签到/撤回/
 * 表情回应/资料查询）。
 *
 * 使用方法：
 * 1. 打开本工具，点「启动服务器」（默认 HTTP :3000 / WS :3001）；
 * 2. BandQQ 同步器 APP 设置里，OneBot 地址保持默认或改指本机，点「测试连接」应可连；
 * 3. 启动同步服务后 APP 自动连上 WS 并拉取模拟联系人；
 *    BandQQ 2.8.2+ 连上后会经 WS 上报身份，日志显示「BandQQ APP x.y.z」；
 * 4. 点下方按钮发送模拟消息 → APP 入库 → 蓝牙推到手环展示；
 * 5. 手环回复 → 本工具日志显示收到的动作 → （开关开启时）自动回推对方消息。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            val controller = remember(dark) {
                ThemeController(
                    colorSchemeMode = if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light,
                    isDark = dark,
                )
            }
            MiuixTheme(controller = controller) {
                DevToolsApp()
            }
        }
    }
}

private class DevToolsState {
    var basePort by mutableStateOf("3000")
    var running by mutableStateOf(false)
    var autoEcho by mutableStateOf(true)
    var selfQq by mutableStateOf("10000")
    var selfNick by mutableStateOf("")
    var nickname by mutableStateOf("")
    var content by mutableStateOf("")
    var chatType by mutableStateOf("private")
    var scenario by mutableStateOf("text")
    val logs = mutableStateListOf<String>()

    var wsServer: WsServer? = null
    var httpServer: HttpApiServer? = null
    var actionRouter: ActionRouter? = null
    val lastBroadcastAt = java.util.concurrent.atomic.AtomicLong(0L)
    val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun basePortValue(): Int = basePort.toIntOrNull()?.coerceIn(1024, 65535) ?: 3000

    fun selfQqValue(): Long = selfQq.trim().toLongOrNull()?.takeIf { it > 0 } ?: MsgBuilder.DEFAULT_SELF_ID

    fun selfNickValue(): String = selfNick.trim().ifBlank { "我" }

    fun append(msg: String) {
        val line = timeFmt.format(Date()) + "  " + msg
        synchronized(logs) {
            logs.add(line)
            while (logs.size > 240) logs.removeAt(0)
        }
    }
}

@Composable
private fun DevToolsApp() {
    val context = LocalContext.current
    val state = remember { DevToolsState() }
    val colorScheme = MiuixTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Text(
            "BandQQ DevTools",
            fontSize = 24.sp,
            color = colorScheme.onSurface,
        )
        Text(
            "模拟 OneBot 协议端（WS :3001 / HTTP :3000）· 无需真实 QQ 服务器即可调试 BandQQ 全链路 · v1.4.0",
            fontSize = 12.sp,
            color = colorScheme.onSurfaceSecondary,
        )

        // ===== 服务器控制 =====
        SmallTitle(text = "服务器")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    if (state.running) "运行中：WS :${state.basePortValue() + 1} · HTTP :${state.basePortValue()}"
                    else "服务器未启动",
                    fontSize = 14.sp,
                    color = if (state.running) Color(0xFF63E07C) else colorScheme.primary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextField(
                        value = state.basePort,
                        onValueChange = { state.basePort = it.filter { c -> c.isDigit() }.take(5) },
                        label = "端口基号（默认 3000）",
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                    Button(
                        onClick = { toggleServer(context, state) },
                        colors = if (state.running) ButtonDefaults.buttonColors() else ButtonDefaults.buttonColorsPrimary(),
                    ) { Text(if (state.running) "停止" else "启动") }
                }
            }
        }

        // ===== 我的身份 =====
        SmallTitle(text = "我的身份")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "@我 判定与 get_login_info 的依据：@消息的 at 号需与此一致",
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceSecondary,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextField(
                        value = state.selfQq,
                        onValueChange = { state.selfQq = it.filter { c -> c.isDigit() }.take(12) },
                        label = "我的QQ号（默认 10000）",
                        modifier = Modifier.weight(1f),
                    )
                    TextField(
                        value = state.selfNick,
                        onValueChange = { state.selfNick = it },
                        label = "我的昵称（默认：我）",
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // ===== 模拟消息 =====
        SmallTitle(text = "模拟消息（点击即发送到手环）")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ToggleChip(
                        "私聊", state.chatType == "private",
                        modifier = Modifier.weight(1f),
                    ) { state.chatType = "private" }
                    ToggleChip(
                        "群聊", state.chatType == "group",
                        modifier = Modifier.weight(1f),
                    ) { state.chatType = "group" }
                }
                Spacer(Modifier.height(10.dp))
                val rows = listOf(
                    listOf("文本" to "text", "拍一拍" to "poke", "图片" to "image", "表情" to "face"),
                    listOf("引用回复" to "reply", "语音" to "voice", "@我·仅群聊" to "at", "文件" to "file"),
                    listOf("长文本" to "long", "撤回" to "recall"),
                )
                for (row in rows) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for ((label, id) in row) {
                            ToggleChip(
                                label, state.scenario == id,
                                modifier = Modifier.weight(1f),
                            ) {
                                if (state.scenario == id) sendScenario(context, state)
                                else {
                                    state.scenario = id
                                    sendScenario(context, state)
                                }
                            }
                        }
                    }
                }
            }
        }

        // ===== 自定义消息 =====
        SmallTitle(text = "自定义消息")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TextField(
                    value = state.nickname,
                    onValueChange = { state.nickname = it },
                    label = "发送者昵称（默认：测试好友/群友小王）",
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = state.content,
                    onValueChange = { state.content = it },
                    label = "消息内容（支持换行）",
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { sendCustom(context, state) },
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("发送自定义消息") }
            }
        }

        // ===== 自动回推 =====
        SmallTitle(text = "闭环演示")
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("收到手环回复后自动回推对方消息", fontSize = 14.sp, color = colorScheme.onSurface)
                    Text("关掉后手环回复不会触发自动回应，便于观察原始动作", fontSize = 11.sp, color = colorScheme.onSurfaceSecondary)
                }
                Switch(checked = state.autoEcho, onCheckedChange = { state.autoEcho = it })
            }
        }

        // ===== 日志 =====
        SmallTitle(text = "事件日志")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                val snapshot = state.logs.toList()
                if (snapshot.isEmpty()) {
                    Text("等待启动服务器…", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = colorScheme.onSurfaceSecondary)
                } else {
                    snapshot.forEach { line ->
                        Text(
                            line,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (line.contains("→") || line.contains("已")) colorScheme.primary else colorScheme.onSurface,
                        )
                    }
                }
            }
        }

        Text(
            "作者一秋 · QQ群 885186458 · github.com/Gsjsjzhznsz/BandQQ",
            fontSize = 12.sp,
            color = colorScheme.primary,
        )
    }
}

@Composable
private fun ToggleChip(label: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colorScheme = MiuixTheme.colorScheme
    Button(
        onClick = onClick,
        colors = if (active) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
        modifier = modifier,
    ) {
        Text(
            label,
            fontSize = 12.sp,
            color = if (active) colorScheme.onPrimary else colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

// ================= 业务逻辑（与 v1.3.0 一致） =================

private fun basePortOf(state: DevToolsState): Int = state.basePortValue()

private fun selfQqOf(state: DevToolsState): Long = state.selfQqValue()

private fun toggleServer(context: android.content.Context, state: DevToolsState) {
    if (state.wsServer?.running == true || state.httpServer?.running == true) {
        state.wsServer?.stop()
        state.httpServer?.stop()
        state.wsServer = null
        state.httpServer = null
        state.actionRouter = null
        state.lastBroadcastAt.set(0L)
        state.running = false
        state.append("服务器已停止")
    } else {
        val base = basePortOf(state)
        val wsPort = base + 1
        val router = ActionRouter(
            autoEcho = { state.autoEcho },
            selfInfo = { selfQqOf(state) to state.selfNickValue() },
            pushEvent = { event ->
                state.lastBroadcastAt.set(System.currentTimeMillis())
                state.wsServer?.broadcast(event)
                state.append("WS → 已下发事件: ${event.take(90)}")
            },
            onLog = { msg -> state.append(msg) },
        )
        state.actionRouter = router
        state.wsServer = WsServer(wsPort, router) { kind, detail ->
            state.append(detail)
        }.also { it.start() }
        state.httpServer = HttpApiServer(port = base, router = router, onLog = { state.append(it) }).also { it.start() }
        state.running = true
        state.append("DevTools 就绪：请打开 BandQQ APP → 设置 → 启动同步服务（地址保持 ws://127.0.0.1:$wsPort）")
        state.append("提示：BandQQ 2.8.2+ 连上后日志会出现「BandQQ APP x.y.z」身份行；没有即旧版 APP")
        context.getSharedPreferences("devtools", android.content.Context.MODE_PRIVATE)
            .edit().putInt("base_port", base).apply()
    }
}

private fun sendScenario(context: android.content.Context, state: DevToolsState) {
    val ws = state.wsServer?.takeIf { it.running } ?: run {
        state.append("⚠ 请先启动服务器"); return
    }
    state.lastBroadcastAt.set(System.currentTimeMillis())
    val nickname = state.nickname.trim().ifBlank {
        if (state.chatType == "group") "群友小王" else "测试好友"
    }
    when (state.scenario) {
        "recall" -> {
            val (first, second) = MsgBuilder.recallFlow(state.chatType, nickname)
            ws.broadcast(first)
            ws.broadcast(second)
            state.append("已发送：${if (state.chatType == "group") "群聊" else "私聊"} · 撤回（先文本后撤回帧）")
        }
        "poke" -> {
            ws.broadcast(MsgBuilder.pokeEvent(state.chatType, selfId = selfQqOf(state)))
            state.append("已发送：${if (state.chatType == "group") "群聊" else "私聊"} · 拍一拍（$nickname）")
        }
        else -> {
            if (state.scenario == "at" && state.chatType == "private") {
                state.append("⚠ QQ 私聊没有 @（只有拍一拍）：已自动改发「私聊 · 拍一拍」；要测 @我 请切到「群聊」")
                ws.broadcast(MsgBuilder.pokeEvent("private", selfId = selfQqOf(state)))
                state.append("已发送：私聊 · 拍一拍（自动转换自 @我）")
                return
            }
            val event = MsgBuilder.messageEvent(
                type = state.chatType,
                nickname = nickname,
                groupId = if (state.chatType == "group") MsgBuilder.DEFAULT_GROUP_ID else null,
                message = MsgBuilder.scenarioSegments(state.scenario, state.content, selfQqOf(state)),
                selfId = selfQqOf(state),
            )
            ws.broadcast(event)
            state.append("已发送：${if (state.chatType == "group") "群聊" else "私聊"} · ${MsgBuilder.scenarioLabel(state.scenario)}（$nickname）")
        }
    }
}

private fun sendCustom(context: android.content.Context, state: DevToolsState) {
    val ws = state.wsServer?.takeIf { it.running } ?: run {
        state.append("⚠ 请先启动服务器"); return
    }
    state.lastBroadcastAt.set(System.currentTimeMillis())
    val content = state.content.trim()
    if (content.isEmpty()) {
        state.append("⚠ 请输入消息内容"); return
    }
    val nickname = state.nickname.trim().ifBlank {
        if (state.chatType == "group") "群友小王" else "测试好友"
    }
    val event = MsgBuilder.messageEvent(
        type = state.chatType,
        nickname = nickname,
        groupId = if (state.chatType == "group") MsgBuilder.DEFAULT_GROUP_ID else null,
        message = MsgBuilder.textArray(content),
        selfId = selfQqOf(state),
    )
    ws.broadcast(event)
    state.append("已发送：${if (state.chatType == "group") "群聊" else "私聊"} · 自定义（$nickname）：${content.take(40)}")
}
