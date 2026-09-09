package com.example.bandqq.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.bandqq.config.AppConfig
import com.example.bandqq.config.ConfigManager
import com.example.bandqq.config.EndpointConfig
import com.example.bandqq.onebot.GameProtocolDetector
import com.example.bandqq.sync.SyncState
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField

/** 检查本应用的无障碍保活服务是否已在系统中启用 */
fun isKeepAliveEnabled(ctx: Context): Boolean {
    val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
    val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
    return enabled.any {
        it.resolveInfo?.serviceInfo?.packageName == ctx.packageName
    }
}

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val configManager = remember { ConfigManager(context) }
    var wsUrl by remember { mutableStateOf("") }
    var wsToken by remember { mutableStateOf("") }
    var httpUrl by remember { mutableStateOf("") }
    var httpToken by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(loaded) { if (loaded) entered = true }

    LaunchedEffect(Unit) {
        val cfg = configManager.load()
        wsUrl = cfg.endpoint.wsUrl
        wsToken = cfg.endpoint.wsToken
        httpUrl = cfg.endpoint.httpUrl
        httpToken = cfg.endpoint.httpToken
        loaded = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!loaded) {
            SmallTitle(text = "正在读取配置…")
            return@Column
        }

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
                    val cfg = AppConfig(
                        EndpointConfig(
                            wsUrl = wsUrl.trim(),
                            wsToken = wsToken.trim(),
                            httpUrl = httpUrl.trim(),
                            httpToken = httpToken.trim(),
                        ),
                    )
                    configManager.save(cfg)
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

        SmallTitle(text = "保活")
        val keepAliveEnabled = remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { keepAliveEnabled.value = isKeepAliveEnabled(context) }
        Row(
            modifier = Modifier.fillMaxWidth().listItemReveal(entered, 6),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "无障碍保活")
                Text(
                    text = if (keepAliveEnabled.value)
                        "已开启（登录账号：${SyncState.loginNickname.ifBlank { "未获取" }}）"
                    else
                        "未开启，开启后后台更稳定",
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Button(
                onClick = {
                    try {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    } catch (_: Exception) {
                        toast(context, "无法打开无障碍设置")
                    }
                },
                colors = if (keepAliveEnabled.value) ButtonDefaults.buttonColors()
                         else ButtonDefaults.buttonColorsPrimary(),
            ) { Text(if (keepAliveEnabled.value) "管理" else "去开启") }
        }
    }
}
