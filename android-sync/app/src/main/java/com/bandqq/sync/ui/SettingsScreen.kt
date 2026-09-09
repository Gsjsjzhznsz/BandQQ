package com.bandqq.sync.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bandqq.sync.sync.SyncService

/**
 * 手机端设置界面：连接配置、服务状态、无障碍保活入口。
 */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    var ws by remember { mutableStateOf("") }
    var http by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var stateTick by remember { mutableStateOf(0) }

    LaunchedEffect(loaded) {
        if (!loaded) {
            SyncService.instance?.let {
                val (w, h, t) = it.currentConfig()
                ws = w; http = h; token = t
                loaded = true
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            stateTick++
            kotlinx.coroutines.delay(2000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("BandQQ 同步器 v1.1.1", fontSize = 22.sp)
        val st = SyncService.lastState
        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("服务状态", fontSize = 16.sp)
                Text("OneBot: ${if (st.onebotConnected) "已连接" else "未连接"}", fontSize = 13.sp)
                Text(
                    "登录账号: ${if (st.loginNickname.isNotEmpty()) st.loginNickname else "未知"}",
                    fontSize = 13.sp
                )
                Text("手环端口: ${com.bandqq.sync.sync.BandServer.PORT}", fontSize = 13.sp)
            }
        }
        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("OneBot 连接", fontSize = 16.sp)
                OutlinedTextField(
                    value = ws, onValueChange = { ws = it },
                    label = { Text("WebSocket 地址 (ws://IP:3001)") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                OutlinedTextField(
                    value = http, onValueChange = { http = it },
                    label = { Text("HTTP 地址 (http://IP:3000)") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                OutlinedTextField(
                    value = token, onValueChange = { token = it },
                    label = { Text("Access Token（可空）") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                Button(
                    onClick = {
                        SyncService.instance?.updateConfig(ws, http, token)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("保存并重连") }
            }
        }
        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("保活", fontSize = 16.sp)
                Text("开启无障碍保活可在系统杀死后台后自动拉起同步服务。该服务不读取屏幕内容。", fontSize = 13.sp)
                Button(onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }) { Text("打开无障碍设置") }
            }
        }
        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("手环连接说明", fontSize = 16.sp)
                Text(
                    "手环快应用通过蓝牙隧道连接本机端口 ${com.bandqq.sync.sync.BandServer.PORT}。" +
                        "首次使用请保持手环与手机蓝牙已配对连接。", fontSize = 13.sp
                )
            }
        }
    }
}
