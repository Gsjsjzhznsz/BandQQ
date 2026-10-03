package io.github.gsjsjzhznsz.bandqq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.gsjsjzhznsz.bandqq.sync.FileLogger
import io.github.gsjsjzhznsz.bandqq.sync.LogBus
import io.github.gsjsjzhznsz.bandqq.sync.LogEntry
import io.github.gsjsjzhznsz.bandqq.sync.LogLevel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val logTimeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

/** 单行日志展示上限：超长协议帧截断（行高有界，列表不因单条巨帧撑爆） */
private const val MAX_LOG_LINE = 400

/**
 * v2.9.4 重写：修复「新日志刷新时日志区被压成底部一条线」。
 * 根因组合拳：
 * 1) 旧实现用 scroll Column + key(time,tag,message)，同毫秒同内容日志会撞 key；
 * 2) 自动滚动用 animateScrollTo，日志风暴时动画不断重启互相打断；
 * 3) 日志盒高度依赖 weight(1f) 单一路径，任何一次异常测量都会塌成 0 高。
 * 新实现：LazyColumn（懒组合 + seq 唯一 key）+ 即时滚动（无动画争抢）。
 *
 * v2.18.1 再加固：日志盒彻底去 weight 化，改为固定高 300dp。用户实测（10-03）
 * 开启服务后日志区高度仍会塌陷：weight(1f)+heightIn(min) 组合在「日志盒上方
 * 动态插入过滤标签行」时会改变测量路径，异常固件/嵌套滚动容器下 weight 分配
 * 可能归零。定高后日志视口物理恒定，标签行出现与否、父容器如何测量都不影响。
 *
 * v2.19.0 位置回移：用户实测（10-04）高度正常了，但「开服务后标签行在标题与
 * 日志盒之间插入，把日志盒整体向下推到原塌陷位」。修法：过滤标签并入标题行
 * （标题左侧 + 标签横向滚动 + 导出/清空右侧同行），任何时刻不再新增行，
 * 日志盒 y 坐标恒定不跳。
 */
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
    val listState = rememberLazyListState()
    var userScrolledAway by remember { mutableStateOf(false) }

    // 用户手动上滑翻旧日志时暂停自动跟随；拖回底部（倒数 2 条内）恢复
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                if (listState.layoutInfo.totalItemsCount > 0 &&
                    listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 < listState.layoutInfo.totalItemsCount - 2
                ) {
                    userScrolledAway = true
                }
            }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .distinctUntilChanged()
            .collect { last ->
                if (last != null) {
                    val total = listState.layoutInfo.totalItemsCount
                    if (total > 0 && last >= total - 2) userScrolledAway = false
                }
            }
    }

    // 新日志到达：即时滚到底（scrollToItem 无动画，风暴时不叠加、不打断、不漂移）
    LaunchedEffect(filtered.size, filter) {
        if (filtered.isNotEmpty() && !userScrolledAway) {
            runCatching { listState.scrollToItem(filtered.size - 1) }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                text = "实时日志",
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
            )
            // v2.19.0：过滤标签并入标题行（横向滚动），不再独立成行——
            // 开启服务后标签出现时日志盒不再被向下推移
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // v2.18.1：固定高 300dp（去 weight 化）——日志视口物理恒定，
                // 不随标签行增减/父容器测量路径变化，坍缩在物理上不再可能
                .height(300.dp)
                .background(MiuixTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
                .padding(8.dp),
        ) {
            if (filtered.isEmpty()) {
                Text(text = "暂无日志", color = MiuixTheme.colorScheme.onSurfaceSecondary)
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(filtered, key = { _, entry -> entry.seq }) { _, entry ->
                        LogLine(entry)
                    }
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
        Text(
            text = entry.message.take(MAX_LOG_LINE),
            modifier = Modifier.weight(1f),
        )
    }
}
