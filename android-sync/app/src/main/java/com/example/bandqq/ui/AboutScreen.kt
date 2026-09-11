package com.example.bandqq.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bandqq.BuildConfig
import com.example.bandqq.R
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
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 关于页（v2.8.0）：应用信息、GitHub 仓库、作者与联系方式、项目简介。
 * 仓库 Gsjsjzhznsz/BandQQ（点击跳浏览器）、作者一秋、QQ 2308534727（点击复制）。
 */
@Composable
fun AboutScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val enableBlur = LocalEnableBlur.current
    val blurBackdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = blurBackdrop != null
    val colorScheme = MiuixTheme.colorScheme
    val repoUrl = "https://github.com/Gsjsjzhznsz/BandQQ"

    var toastMsg by remember { mutableStateOf<String?>(null) }
    toastMsg?.let { msg ->
        toast(context, msg)
        toastMsg = null
    }

    // v2.8.1 修复关于页打开即闪退：R.mipmap.ic_launcher 在 API 26+ 是 adaptive-icon XML，
    // Compose painterResource 只支持 Vector/Bitmap drawable，遇到 AdaptiveIconDrawable 直接抛
    // IllegalStateException（其他推入页都没用 mipmap 图标，所以只有关于页崩）。
    // 改为 ContextCompat.getDrawable + core-ktx toBitmap（可绘制 AdaptiveIconDrawable），再转 ImageBitmap。
    val launcherBitmap = remember {
        runCatching {
            ContextCompat.getDrawable(context, R.mipmap.ic_launcher)?.toBitmap()
        }.getOrNull()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            BlurredBar(blurBackdrop) {
                SmallTopAppBar(
                    title = "关于",
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
                Spacer(Modifier.height(innerPadding.calculateTopPadding() + 16.dp))

                // ===== 应用标识区 =====
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (launcherBitmap != null) {
                        Image(
                            bitmap = launcherBitmap.asImageBitmap(),
                            contentDescription = "BandQQ 图标",
                            modifier = Modifier.size(84.dp),
                        )
                    }
                    Text(
                        text = "BandQQ 同步器",
                        fontSize = 22.sp,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    Text(
                        text = "v${BuildConfig.VERSION_NAME}",
                        fontSize = 14.sp,
                        color = colorScheme.onSurfaceSecondary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                // ===== 项目信息 =====
                SmallTitle(text = "项目信息")
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        InfoRow("应用", "小米手环 QQ 客户端 + Android 同步器")
                        InfoRow("作者", "一秋")
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "联系方式",
                                fontSize = 14.sp,
                                color = colorScheme.onSurfaceSecondary,
                                modifier = Modifier.width(76.dp),
                            )
                            Text(
                                text = "QQ 2308534727（点击复制）",
                                fontSize = 14.sp,
                                color = colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                // v2.8.1：仓库行显示完整 GitHub 链接，点击直接打开浏览器
                                .clickable {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(repoUrl))
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }.onFailure { toastMsg = "打开浏览器失败" }
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "仓库",
                                fontSize = 14.sp,
                                color = colorScheme.onSurfaceSecondary,
                                modifier = Modifier.width(76.dp),
                            )
                            Text(
                                text = repoUrl,
                                fontSize = 14.sp,
                                color = colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                // ===== 项目简介 =====
                SmallTitle(text = "项目简介", modifier = Modifier.padding(top = 16.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "BandQQ 是一套小米手环上的 QQ 方案：手环端为 Vela 快应用客户端，" +
                            "手机端同步器通过小米互联（xms-wearable）与手环双向通信，" +
                            "并对接 OneBot 协议端（SnowLuma / NapCat 等）。\n\n" +
                            "支持：消息实时推送与回复、@我 高亮提醒、表情渲染、快捷回复、" +
                            "聊天记录双端同步、撤回实时提示、勿扰与推送策略、" +
                            "新消息自动拉起快应用（延迟与震动可调）、后台保活向导等。" +
                            "消息筛选与渲染预算全部在手机端完成，手环端零额外计算。\n\n" +
                            "配套开发者工具 BandQQ DevTools 可在本机模拟 OneBot 协议端，" +
                            "无需真实 QQ 服务器即可体验与调试完整链路。",
                        fontSize = 14.sp,
                        lineHeight = 22.sp,
                        color = colorScheme.onSurface,
                        modifier = Modifier.padding(14.dp),
                    )
                }

                // ===== 操作按钮 =====
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("bandqq_qq", "2308534727"))
                            toastMsg = "QQ 号已复制：2308534727"
                        },
                        colors = ButtonDefaults.buttonColors(),
                        modifier = Modifier.weight(1f),
                    ) { Text("复制联系方式") }
                    Button(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(repoUrl))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }.onFailure { toastMsg = "打开浏览器失败" }
                        },
                        colors = ButtonDefaults.buttonColorsPrimary(),
                        modifier = Modifier.weight(1f),
                    ) { Text("打开 GitHub 仓库") }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val colorScheme = MiuixTheme.colorScheme
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = colorScheme.onSurfaceSecondary,
            modifier = Modifier.width(76.dp),
        )
        Text(text = value, fontSize = 14.sp, modifier = Modifier.weight(1f))
    }
}
