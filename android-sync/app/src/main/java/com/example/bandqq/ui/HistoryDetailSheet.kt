package com.example.bandqq.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.bandqq.sync.ConversationInfo
import com.example.bandqq.sync.StoreHolder
import top.yukonga.miuix.kmp.basic.TextButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val detailTimeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

@Composable
fun HistoryDetailDialog(conv: ConversationInfo, onDismiss: () -> Unit) {
    val msgs = StoreHolder.store?.getAllMessages(conv.id) ?: emptyList()
    val lines = msgs.map { m ->
        val who = if (m.isSelf) "我" else (m.senderName.ifBlank { m.senderId })
        "[${detailTimeFmt.format(Date(m.time))}] $who：${m.content}"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { M3Text("${conv.name}（${msgs.size} 条）") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(end = 8.dp),
            ) {
                if (lines.isEmpty()) {
                    M3Text("暂无消息")
                }
                lines.forEach { M3Text(it) }
            }
        },
        confirmButton = {
            TextButton(text = "关闭", onClick = onDismiss)
        },
    )
}