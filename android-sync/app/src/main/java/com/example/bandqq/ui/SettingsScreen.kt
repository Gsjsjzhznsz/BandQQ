package com.example.bandqq.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.CloudFill
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Scan
import top.yukonga.miuix.kmp.icon.extended.Theme
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SettingsScreen(
    bottomInnerPadding: Dp,
    isActive: Boolean = true,
    onOpenThemeSettings: () -> Unit = {},
    onOpenKeepAlive: () -> Unit = {},
    onOpenCrashLog: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val configManager = remember { ConfigManager(context) }
    val colorScheme = MiuixTheme.colorScheme
    var wsUrl by remember { mutableStateOf("") }
    var wsToken by remember { mutableStateOf("") }
    var httpUrl by remember { mutableStateOf("") }
    var httpToken by remember { mutableStateOf("") }
    var quickReplies by remember { mutableStateOf("") }
    var webuiUrl by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var entered by remember { mutableStateOf(false) }

    // 仅当本页为当前页才播入场动画（HorizontalPager 预组合不触发）
    LaunchedEffect(isActive) { if (isActive) entered = true }

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

            // ===== 外观与保活入口 =====
            SmallTitle(text = "通用")
            Card(modifier = Modifier.fillMaxWidth().listItemReveal(entered, 0)) {
                ArrowPreference(
                    title = "主题与外观",
                    summary = "主题模式、动态取色、液态玻璃、动画调节",
                    startAction = {
                        Icon(
                            MiuixIcons.Theme,
                            contentDescription = "主题与外观",
                            tint = colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                    },
                    onClick = onOpenThemeSettings,
                )
                ArrowPreference(
                    title = "后台保活向导",
                    summary = "权限检测、自启动、电池优化、锁屏清理",
                    startAction = {
                        Icon(
                            MiuixIcons.Lock,
                            contentDescription = "后台保活向导",
                            tint = colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                    },
                    onClick = onOpenKeepAlive,
                    // 单卡内最后一项去掉分隔线的观感差异交给 miuix 自身处理
                )
            }
            Card(modifier = Modifier.fillMaxWidth().listItemReveal(entered, 1)) {
                ArrowPreference(
                    title = "崩溃日志",
                    summary = "应用异常退出时自动记录堆栈，一键复制反馈",
                    startAction = {
                        Icon(
                            MiuixIcons.Scan,
                            contentDescription = "崩溃日志",
                            tint = colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                    },
                    onClick = onOpenCrashLog,
                )
            }

            // ===== SnowLuma 连接（字段收进卡片，避免表单散落在页面上）=====
            SmallTitle(text = "SnowLuma 连接")
            Card(modifier = Modifier.fillMaxWidth().listItemReveal(entered, 2)) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            MiuixIcons.CloudFill,
                            contentDescription = "连接配置",
                            tint = colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("OneBot 接入（WS :3001 / HTTP :3000）", fontSize = 14.sp)
                    }
                    TextField(
                        value = wsUrl, onValueChange = { wsUrl = it }, label = "WS 地址",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextField(
                        value = wsToken, onValueChange = { wsToken = it }, label = "WS Token",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextField(
                        value = httpUrl, onValueChange = { httpUrl = it }, label = "HTTP 地址",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextField(
                        value = httpToken, onValueChange = { httpToken = it }, label = "HTTP Token",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
                                            quickReplies = quickReplies.split("\n").map { it.trim() }
                                                .filter { it.isNotEmpty() },
                                            webuiUrl = webuiUrl.trim(),
                                        )
                                    )
                                    toast(context, "配置已保存")
                                }
                            },
                            colors = ButtonDefaults.buttonColorsPrimary(),
                            modifier = Modifier.weight(1f),
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
                            modifier = Modifier.weight(1f),
                        ) { Text("测试连接") }
                    }
                }
            }

            // ===== 快捷回复 =====
            SmallTitle(text = "快捷回复（手环聊天页按钮，每行一条）")
            Card(modifier = Modifier.fillMaxWidth().listItemReveal(entered, 3)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    TextField(
                        value = quickReplies,
                        onValueChange = { quickReplies = it },
                        label = "支持 CQ 码，按钮自动显示剥离后的纯文本",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "保存后生效；发送内容保留 CQ 码原文，按钮标签 = 剥离 CQ/表情后的前 6 个字",
                        modifier = Modifier.padding(top = 8.dp),
                        fontSize = 12.sp,
                        color = colorScheme.onSurfaceSecondary,
                    )
                }
            }

            // ===== SnowLuma WebUI =====
            SmallTitle(text = "SnowLuma WebUI")
            Card(modifier = Modifier.fillMaxWidth().listItemReveal(entered, 4)) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TextField(
                        value = webuiUrl, onValueChange = { webuiUrl = it },
                        label = "WebUI 地址（默认 http://主机:5099）",
                        modifier = Modifier.fillMaxWidth(),
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
                        colors = ButtonDefaults.buttonColorsPrimary(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("打开 WebUI（扫码登录 / 配置协议端）") }
                }
            }

            // ===== 关于内嵌 SnowLuma =====
            SmallTitle(text = "关于协议端")
            Card(modifier = Modifier.fillMaxWidth().listItemReveal(entered, 5)) {
                Text(
                    text = "SnowLuma 为 hook 型协议端，需要向桌面版 QQ 进程注入（ptrace），" +
                        "无法直接内嵌进 APK。推荐用 Termux 一键脚本把协议端跑在本机：" +
                        "手机装 Termux 后执行仓库 scripts/snowluma-termux.sh，随后地址全填 " +
                        "127.0.0.1（WS :3001 / HTTP :3000 / WebUI :5099），整条链路零电脑、" +
                        "零局域网依赖。也可部署在电脑/NAS 上改填对应 IP；WebUI 入口已内置（上方按钮）。",
                    modifier = Modifier.padding(12.dp),
                    fontSize = 13.sp,
                    color = colorScheme.onSurfaceSecondary,
                )
            }

            // 底部留白：外层底栏总高（含导航栏 inset），末项可完全滚出底栏
            Spacer(modifier = Modifier.height(bottomInnerPadding + 12.dp))
        }
    }
}
