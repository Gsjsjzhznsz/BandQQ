package com.example.bandqq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.bandqq.sync.FileLogger
import com.example.bandqq.sync.LogBus
import com.example.bandqq.sync.LogEntry
import com.example.bandqq.sync.LogLevel
import kotlinx.coroutines.flow.collect
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val logTimeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

@Composable
fun LogPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val logs by LogBus.logs.collectAsState()
    val tags = remember(logs) {
        logs.map { it.tag }.distinct().sorted()
    }
    var filter by remember { mutableStateOf<String?>(null) }
    val filtered = remember(logs, filter) {
        if (filter == null) logs else logs.filter { it.tag == filter }
    }
    val scrollState = rememberScrollState()
    var userScrolledAway by remember { mutableStateOf(false) }
    var autoScrolling by remember { mutableStateOf(false) }

    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.value }
            .collect { value ->
                val maxValue = scrollState.maxValue
                if (maxValue > 0) {
                    val nearBottom = value >= maxValue - 200
                    if (nearBottom) {
                        userScrolledAway = false
                    } else if (scrollState.isScrollInProgress && !autoScrolling) {
                        userScrolledAway = true
                    }
                }
            }
    }

    LaunchedEffect(filtered.size) {
        if (filtered.isNotEmpty() && !userScrolledAway) {
            autoScrolling = true
            try {
                scrollState.animateScrollTo(scrollState.maxValue)
            } finally {
                autoScrolling = false
                userScrolledAway = false
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = "实时日志", color = MiuixTheme.colorScheme.onSurfaceSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = filter ?: "全部",
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MiuixTheme.colorScheme.surfaceContainer)
                        .clickable { filter = null }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    color = MiuixTheme.colorScheme.primary,
                )
                Text(
                    text = "导出",
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            FileLogger.shareZip(context)
                        }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    color = MiuixTheme.colorScheme.primary,
                )
                Text(
                    text = "清空",
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { LogBus.clear() }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    color = MiuixTheme.colorScheme.primary,
                )
            }
        }
        if (tags.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                tags.forEach { tag ->
                    val selected = filter == tag
                    Text(
                        text = tag,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (selected) MiuixTheme.colorScheme.primary else Color.Transparent)
                            .clickable { filter = if (selected) null else tag }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        color = if (selected) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurfaceSecondary,
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(MiuixTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
                .padding(8.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState),
            ) {
                // 性能：日志上限 200 条且增长快（WS 调试日志），
                // 不再逐条包 AnimatedVisibility（纯组合开销，可见时动画价值低）
                filtered.forEach { entry ->
                    key(entry.time, entry.tag, entry.message) { LogLine(entry) }
                }
                if (filtered.isEmpty()) {
                    Text(text = "暂无日志", color = MiuixTheme.colorScheme.onSurfaceSecondary)
                }
            }
        }
    }
}

@Composable
private fun LogLine(entry: LogEntry) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = logTimeFmt.format(Date(entry.time)),
            color = MiuixTheme.colorScheme.onSurfaceSecondary,
        )
        Text(
            text = " [${entry.tag}] ",
            color = when (entry.level) {
                LogLevel.ERROR -> MiuixTheme.colorScheme.error
                LogLevel.WARN -> MiuixTheme.colorScheme.primaryVariant
                else -> MiuixTheme.colorScheme.primary
            },
        )
        Text(text = entry.message, modifier = Modifier.weight(1f))
    }
}
