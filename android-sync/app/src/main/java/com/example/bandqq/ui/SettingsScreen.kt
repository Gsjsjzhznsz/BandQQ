package com.example.bandqq.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bandqq.WebUiActivity
import com.example.bandqq.config.AppConfig
import com.example.bandqq.config.ConfigHolder
import com.example.bandqq.config.ConfigManager
import com.example.bandqq.config.EndpointConfig
import com.example.bandqq.onebot.GameProtocolDetector
import com.example.bandqq.ui.component.PageScaffold
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference

@Composable
fun SettingsScreen(
    bottomInnerPadding: Dp,
    onOpenThemeSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val configManager = remember { ConfigManager(context) }
    var wsUrl by remember { mutableStateOf("") }
    var wsToken by remember { mutableStateOf("") }
    var httpUrl by remember { mutableStateOf("") }
    var httpToken by remember { mutableStateOf("") }
    var quickReplies by remember { mutableStateOf("") }
    var webuiUrl by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(loaded) { if (loaded) entered = true }

    LaunchedEffect(Unit) {
        val cfg = configManager.load()
        wsUrl = cfg.endpoint.wsUrl
        wsToken = cfg.endpoint.wsToken
        httpUrl = cfg.endpoint.httpUrl
        httpToken = cfg.endpoint.httpToken
        quickReplies = cfg.quickReplies.joinToString("\n")
        // 默认 WebUI 地址：由 HTTP 地址推导同主机 :5099（SnowLuma WebUI 默认端口）
        webuiUrl = cfg.webuiUrl.ifBlank {
            runCatching {
                val http = java.net.URI(cfg.endpoint.httpUrl)
                "http://${http.host}:5099"
            }.getOrDefault("")
        }
        loaded = true
    }

    PageScaffold(title = "设置", bottomInnerPadding = bottomInnerPadding) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(innerPadding.calculateTopPadding() + 16.dp))
            if (!loaded) {
                SmallTitle(text = "正在读取配置…")
                Spacer(modifier = Modifier.height(bottomInnerPadding + 12.dp))
                return@Column
            }

        SmallTitle(text = "主题与外观")
        Card(modifier = Modifier.fillMaxWidth()) {
            ArrowPreference(
                title = "主题与外观",
                summary = "主题模式、动态取色、液态玻璃",
                onClick = onOpenThemeSettings,
            )
        }

        SmallTitle(text = "SnowLuma 连接")
        TextField(
            value = wsUrl, onValueChange = { wsUrl = it }, label = "WS 地址",
            modifier = Modifier.fillMaxWidth().listItemReveal(entered, 0),
        )
        TextField(
            value = wsToken, onValueChange = { wsToken = it }, label = "WS Token",
            modifier = Modifier.fillMaxWidth().listItemReveal(entered, 1),
        )
        TextField(
            value = httpUrl, onValueChange = { httpUrl = it }, label = "HTTP 地址",
            modifier = Modifier.fillMaxWidth().listItemReveal(entered, 2),
        )
        TextField(
            value = httpToken, onValueChange = { httpToken = it }, label = "HTTP Token",
            modifier = Modifier.fillMaxWidth().listItemReveal(entered, 3),
        )

        Button(
            onClick = {
                scope.launch {
                    // 从 ConfigHolder.copy 保留主题/玻璃等未在此页编辑的字段
                    configManager.save(
                        ConfigHolder.config.copy(
                            endpoint = EndpointConfig(
                                wsUrl = wsUrl.trim(),
                                wsToken = wsToken.trim(),
                                httpUrl = httpUrl.trim(),
                                httpToken = httpToken.trim(),
                            ),
                            quickReplies = quickReplies.split("\n").map { it.trim() }.filter { it.isNotEmpty() },
                            webuiUrl = webuiUrl.trim(),
                        )
                    )
                    toast(context, "配置已保存")
                }
            },
            colors = ButtonDefaults.buttonColorsPrimary(),
            modifier = Modifier.fillMaxWidth().listItemReveal(entered, 4),
        ) { Text("保存") }

        Button(
            onClick = {
                scope.launch {
                    val result = GameProtocolDetector.testConnection(
                        wsUrl.trim(), wsToken.trim(), httpUrl.trim(), httpToken.trim(),
                    )
                    val msg = if (result.wsReachable && result.httpReachable) {
                        "SnowLuma 连接正常"
                    } else {
                        "WS:${if (result.wsReachable) "可连" else "不可连"} " +
                            "HTTP:${if (result.httpReachable) "可连" else "不可连"}"
                    }
                    toast(context, msg)
                }
            },
            colors = ButtonDefaults.buttonColors(),
            modifier = Modifier.fillMaxWidth().listItemReveal(entered, 5),
        ) { Text("测试连接") }

        SmallTitle(text = "快捷回复（手环聊天页按钮，每行一条）")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                TextField(
                    value = quickReplies,
                    onValueChange = { quickReplies = it },
                    label = "支持 CQ 码，按钮自动显示剥离后的纯文本",
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "发送内容保留 CQ 码原文；按钮标签 = 剥离 CQ/表情后的前 6 个字",
                    modifier = Modifier.padding(top = 8.dp),
                    fontSize = 12.sp,
                )
            }
        }

        SmallTitle(text = "SnowLuma WebUI")
        TextField(
            value = webuiUrl, onValueChange = { webuiUrl = it },
            label = "WebUI 地址（默认 http://主机:5099）",
            modifier = Modifier.fillMaxWidth().listItemReveal(entered, 6),
        )
        Button(
            onClick = {
                val url = webuiUrl.trim()
                if (url.isBlank()) {
                    toast(context, "请先填写 WebUI 地址")
                } else {
                    context.startActivity(
                        Intent(context, WebUiActivity::class.java).putExtra("url", url)
                    )
                }
            },
            colors = ButtonDefaults.buttonColors(),
            modifier = Modifier.fillMaxWidth().listItemReveal(entered, 7),
        ) { Text("打开 WebUI（扫码登录 / 配置协议端）") }

        SmallTitle(text = "关于内嵌 SnowLuma")
        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "SnowLuma 为 hook 型协议端，需要 ptrace 注入真实 QQ 进程，" +
                    "无法直接内嵌进 APK。可在手机 Termux(proot) 或电脑/NAS 上部署，" +
                    "本 App 通过局域网 OneBot 直连；WebUI 入口已内置（上方按钮），" +
                    "部署教程见项目 README。",
                modifier = Modifier.padding(12.dp),
                fontSize = 13.sp,
            )
        }

        // 底部留白：外层底栏总高（含导航栏 inset），末项可完全滚出底栏
        Spacer(modifier = Modifier.height(bottomInnerPadding + 12.dp))
    }
    }
}
