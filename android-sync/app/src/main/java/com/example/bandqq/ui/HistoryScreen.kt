package com.example.bandqq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bandqq.sync.ConversationInfo
import com.example.bandqq.sync.InterconnectBridge
import com.example.bandqq.sync.MessageBus
import com.example.bandqq.sync.StoreHolder
import com.example.bandqq.ui.component.AvatarCircle
import com.example.bandqq.ui.component.PageScaffold
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val timeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

@Composable
fun HistoryScreen(bottomInnerPadding: Dp, isActive: Boolean = true) {
    val context = LocalContext.current
    var refresh by remember { mutableStateOf(0) }
    val conversations = remember(refresh) { StoreHolder.store?.getConversations() ?: emptyList() }
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val listener: (String) -> Unit = remember { { _ -> mainHandler.post { refresh++ } } }
    DisposableEffect(Unit) {
        MessageBus.add(listener)
        onDispose { MessageBus.remove(listener) }
    }

    var detailConv by remember { mutableStateOf<ConversationInfo?>(null) }
    // v2.7.0 二次清除确认：破坏性操作 + 会同步请求手机端清除，必须显式确认
    var confirmClear by remember { mutableStateOf(false) }

    var entered by remember { mutableStateOf(false) }
    // 仅当本页为当前页才播入场动画（HorizontalPager 预组合不触发）
    LaunchedEffect(isActive) { if (isActive) entered = true }

    PageScaffold(title = "聊天记录", bottomInnerPadding = bottomInnerPadding) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Spacer(Modifier.height(innerPadding.calculateTopPadding() + 16.dp))
        SmallTitle(text = "会话 ${conversations.size} 个")
        if (conversations.isEmpty()) {
            Text(
                text = "暂无聊天记录",
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
            )
        }
        conversations.forEachIndexed { index, conv ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .listItemReveal(entered, index)
                    .clickable { detailConv = conv },
                colors = CardDefaults.defaultColors(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AvatarCircle(name = conv.name, id = conv.id, size = 40.dp, fontSize = 17.sp)
                    Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(text = conv.name)
                        Text(
                            text = conv.lastMsg.ifBlank { "暂无消息" },
                            color = MiuixTheme.colorScheme.onSurfaceSecondary,
                        )
                    }
                    Spacer(modifier = Modifier.padding(start = 8.dp))
                    Text(
                        text = if (conv.time > 0L) timeFmt.format(Date(conv.time)) else "",
                        color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    )
                }
            }
        }
        // 破坏性操作移到列表末尾：避免误触，也让主内容成为视觉焦点
        TextButton(
            text = "清空全部聊天记录",
            modifier = Modifier.padding(top = 8.dp),
            onClick = { confirmClear = true },
        )
        Spacer(modifier = Modifier.height(bottomInnerPadding + 12.dp))
    }
    }

    // v2.7.0：清空二次确认 —— 同时说明会请求手环端同步清空
    if (confirmClear) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { confirmClear = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MiuixTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                    .padding(20.dp),
            ) {
                Text(text = "清空全部聊天记录", fontSize = 18.sp)
                Text(
                    text = "将清除手机端已保存的记录，并请求手环端同步清空，此操作不可恢复。确定继续？",
                    modifier = Modifier.padding(top = 10.dp),
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TextButton(
                        text = "取消",
                        modifier = Modifier.weight(1f),
                        onClick = { confirmClear = false },
                    )
                    TextButton(
                        text = "清空",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            confirmClear = false
                            StoreHolder.store?.clearAllHistory()
                            InterconnectBridge.sendToBand("""{"type":"clear_all_history","seq":0}""")
                            // 回推权威空会话帧：手环本地若残留旧预览立即被覆盖
                            InterconnectBridge.sendToBand(StoreHolder.store?.buildConversationFrame(0) ?: "")
                            toast(context, "聊天记录已清空")
                            refresh++
                        },
                    )
                }
            }
        }
    }

    val c = detailConv
    if (c != null) {
        HistoryDetailDialog(conv = c, onDismiss = { detailConv = null })
    }
}