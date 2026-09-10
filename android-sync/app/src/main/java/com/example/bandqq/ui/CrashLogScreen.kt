package com.example.bandqq.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bandqq.CrashGuard
import com.example.bandqq.ui.util.BlurredBar
import com.example.bandqq.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.blur.layerBackdrop

/**
 * 崩溃日志查看页（v2.4.5）：
 * CrashGuard 落盘的未捕获异常堆栈在这里展示，支持一键复制反馈、清空。
 * 远程无法复现的崩溃从此有据可查——用户直接把这里的内容发给开发者。
 */
@Composable
fun CrashLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val enableBlur = LocalEnableBlur.current
    val blurBackdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = blurBackdrop != null
    val colorScheme = MiuixTheme.colorScheme

    var log by remember { mutableStateOf(CrashGuard.readLog(context)) }
    var toastMsg by remember { mutableStateOf<String?>(null) }
    toastMsg?.let { msg ->
        toast(context, msg)
        toastMsg = null
    }

    Scaffold(
        topBar = {
            BlurredBar(blurBackdrop) {
                SmallTopAppBar(
                    title = "崩溃日志",
                    color = if (blurActive) Color.Transparent else colorScheme.surface,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = "返回",
                                tint = colorScheme.onBackground,
                            )
                        }
                    },
                )
            }
        },
        popupHost = { },
    ) { innerPadding ->
        Box(
            modifier = if (blurBackdrop != null) Modifier.layerBackdrop(blurBackdrop) else Modifier
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                Spacer(Modifier.height(innerPadding.calculateTopPadding() + 12.dp))

                SmallTitle(
                    text = if (log.isBlank()) "暂无崩溃记录" else "共 ${log.split("\n----------\n").size} 条记录",
                )
                Spacer(Modifier.height(12.dp))

                if (log.isNotBlank()) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        // 只读展示：可编辑 TextField 承载大文本会掉帧，复制走下方按钮
                        Text(
                            text = log,
                            fontSize = 12.sp,
                            color = colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("bandqq_crash", log))
                            toastMsg = "已复制，可粘贴反馈给开发者"
                        },
                        enabled = log.isNotBlank(),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                        modifier = Modifier.weight(1f),
                    ) { Text("复制日志") }
                    Button(
                        onClick = {
                            CrashGuard.clearLog(context)
                            log = ""
                            toastMsg = "已清空"
                        },
                        enabled = log.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) { Text("清空") }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    text = "应用发生未捕获崩溃时，完整堆栈会自动保存到这里。反馈问题时请点「复制日志」把内容发给开发者，可大幅加快定位速度。",
                    fontSize = 13.sp,
                    color = colorScheme.onSurfaceSecondary,
                )

                // 推入页打开时外层底栏已收起，只需导航栏安全高度 + 余量
                Spacer(
                    Modifier.height(
                        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp
                    )
                )
            }
        }
    }
}
