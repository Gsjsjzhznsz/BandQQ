package com.example.bandqq.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import com.example.bandqq.sync.ConversationInfo
import com.example.bandqq.sync.InterconnectBridge
import com.example.bandqq.sync.MessageBus
import com.example.bandqq.sync.StoreHolder
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
fun HistoryScreen() {
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

    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(
            text = "清空全部聊天记录",
            onClick = {
                StoreHolder.store?.clearAllHistory()
                InterconnectBridge.sendToBand("""{"type":"clear_all_history","seq":0}""")
                toast(context, "聊天记录已清空")
                refresh++
            },
        )
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
                    Column(modifier = Modifier.weight(1f)) {
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
    }

    val c = detailConv
    if (c != null) {
        HistoryDetailDialog(conv = c, onDismiss = { detailConv = null })
    }
}