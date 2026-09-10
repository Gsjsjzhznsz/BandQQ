package com.example.bandqq.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.bandqq.config.ConfigManager
import com.example.bandqq.onebot.GameProtocolDetector
import com.example.bandqq.sync.InterconnectBridge
import com.example.bandqq.sync.SyncService
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bandConnected by useBandConnected()
    var oneBotRefreshKey by remember { mutableStateOf(0) }
    val oneBotConnected by useOneBotConnected(refreshKey = oneBotRefreshKey)
    var entered by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { entered = true }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusCard(
                title = "手环",
                online = bandConnected,
                summary = "小米运动健康互联通道",
                detail = if (bandConnected) "已连接" else "未连接",
                modifier = Modifier.weight(1f).enterReveal(entered, delayMs = 0),
            )
            StatusCard(
                title = "SnowLuma",
                online = oneBotConnected,
                summary = "OneBot 协议端",
                detail = if (oneBotConnected) "在线" else "离线",
                modifier = Modifier.weight(1f).enterReveal(entered, delayMs = 100),
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    SyncService.start(context)
                    toast(context, "同步服务已启动")
                },
                colors = ButtonDefaults.buttonColorsPrimary(),
                modifier = Modifier.fillMaxWidth().enterReveal(entered, delayMs = 200),
            ) { Text("启动服务") }
            Button(
                onClick = {
                    SyncService.stop(context)
                    toast(context, "同步服务已停止")
                },
                colors = ButtonDefaults.buttonColors(),
                modifier = Modifier.fillMaxWidth().enterReveal(entered, delayMs = 200),
            ) { Text("停止服务") }
            Button(
                onClick = {
                    InterconnectBridge.init(context)
                    InterconnectBridge.connect()
                    toast(context, "已发起手环连接检查")
                },
                colors = ButtonDefaults.buttonColors(),
                modifier = Modifier.fillMaxWidth().enterReveal(entered, delayMs = 200),
            ) { Text("检查手环连接") }
            Button(
                onClick = {
                    scope.launch {
                        val cfg = ConfigManager(context).load()
                        val result = GameProtocolDetector.testConnection(
                            cfg.endpoint.wsUrl, cfg.endpoint.wsToken,
                            cfg.endpoint.httpUrl, cfg.endpoint.httpToken,
                        )
                        oneBotRefreshKey++
                        val msg = if (result.wsReachable && result.httpReachable) {
                            "SnowLuma 在线（WS/HTTP 可连接）"
                        } else {
                            "WS:${if (result.wsReachable) "可连" else "不可连"} " +
                                "HTTP:${if (result.httpReachable) "可连" else "不可连"}"
                        }
                        toast(context, msg)
                    }
                },
                colors = ButtonDefaults.buttonColors(),
                modifier = Modifier.fillMaxWidth().enterReveal(entered, delayMs = 200),
            ) { Text("测试 SnowLuma 连接") }
        }

        LogPanel(
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp)
                .enterReveal(entered, delayMs = 300),
        )
    }
}

/** 入场动画 modifier：delay 后淡入并上移（单次播放）。 */
private fun Modifier.enterReveal(entered: Boolean, delayMs: Int): Modifier = composed {
    val alpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(300, delayMillis = delayMs),
        label = "revealAlpha",
    )
    graphicsLayer {
        this.alpha = alpha
        translationY = (1 - alpha) * 20f
    }
}

/** 状态卡：标题 + 摘要 + 呼吸指示点 + 状态切换颜色过渡。 */
@Composable
private fun StatusCard(
    title: String,
    online: Boolean,
    summary: String,
    detail: String,
    modifier: Modifier = Modifier,
) {
    val indicatorColor by animateColorAsState(
        targetValue = when {
            online -> Color(0xFF4CAF50)
            title.contains("SnowLuma") -> Color(0xFFE53935)
            else -> Color(0xFF9E9E9E)
        },
        label = "statusColor",
    )
    val transition = rememberInfiniteTransition(label = "breath")
    val alpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200), repeatMode = RepeatMode.Reverse),
        label = "breathAlpha",
    )
    Card(
        modifier = modifier.height(120.dp),
        colors = CardDefaults.defaultColors(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = title)
            Text(text = summary, color = MiuixTheme.colorScheme.onSurfaceSecondary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(color = indicatorColor.copy(alpha = if (online) alpha else 0.4f), shape = CircleShape),
                )
                Text(
                    text = detail,
                    color = if (online) indicatorColor else MiuixTheme.colorScheme.onSurfaceSecondary,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(112.dp))

    }
}
