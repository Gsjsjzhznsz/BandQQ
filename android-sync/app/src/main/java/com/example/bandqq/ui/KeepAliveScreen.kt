package com.example.bandqq.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.blur.layerBackdrop

/** 单个保活步骤：标题 + 具体路径说明 */
private data class KeepAliveStep(val title: String, val detail: String)

/** 品牌适配方案：matchers 用于按 Build.MANUFACTURER 自动选中 */
private data class KeepAliveBrand(
    val name: String,
    val matchers: List<String>,
    val steps: List<KeepAliveStep>,
)

/**
 * 主流品牌后台保活方案（锁屏清理 / 自启动 / 电池优化 / 无障碍辅助）。
 * 步骤路径以当前主流 ROM 版本为准，老版本路径写在括号内兜底。
 */
private val KEEP_ALIVE_BRANDS = listOf(
    KeepAliveBrand(
        name = "通用",
        matchers = listOf(),
        steps = listOf(
            KeepAliveStep(
                "电池优化白名单",
                "点击上方「一键申请」按钮，在系统弹窗中选择「允许」，将本应用加入电池优化白名单（几乎所有品牌通用，效果最直接）。",
            ),
            KeepAliveStep(
                "最近任务锁定",
                "打开最近任务（多任务）界面，下拉或长按本应用卡片，出现锁图标即已锁定，防止一键清理时被杀。",
            ),
            KeepAliveStep(
                "允许通知",
                "在系统设置中允许本应用的通知权限，前台服务常驻更稳（部分系统杀后台前会先撤掉通知）。",
            ),
            KeepAliveStep(
                "关闭无障碍冲突",
                "若开启了会「自动清理后台」的无障碍/管家类工具，请将本应用加入其白名单或关闭对应功能。",
            ),
        ),
    ),
    KeepAliveBrand(
        name = "小米 / Redmi",
        matchers = listOf("xiaomi", "redmi"),
        steps = listOf(
            KeepAliveStep(
                "自启动",
                "设置 → 应用设置 → 应用管理 → QQ同步器 → 自启动，打开开关。（老版本：安全中心 → 应用管理 → 权限 → 自启动管理）",
            ),
            KeepAliveStep(
                "省电策略无限制",
                "设置 → 省电与电池 → 右上角齿轮 → 应用智能省电 → QQ同步器 → 无限制。（老版本：安全中心 → 省电优化 → 省电策略 → 无限制）",
            ),
            KeepAliveStep(
                "锁屏清理内存",
                "手机管家（安全中心）→ 右上角齿轮设置 → 清理内存 / 锁屏清理 → 选择「从不」，避免锁屏后进程被清理。",
            ),
            KeepAliveStep(
                "神隐模式（老版 MIUI）",
                "安全中心 → 神隐模式 → 找到 QQ同步器 → 允许后台使用与联网。",
            ),
        ),
    ),
    KeepAliveBrand(
        name = "华为 / 荣耀",
        matchers = listOf("huawei", "honor"),
        steps = listOf(
            KeepAliveStep(
                "应用启动管理",
                "设置 → 应用和服务（应用）→ 应用启动管理 → QQ同步器 → 关闭「自动管理」，手动开启：允许自启动 / 关联启动 / 后台活动 三项。",
            ),
            KeepAliveStep(
                "电池优化",
                "设置 → 电池 → 更多电池设置（或 启动管理），确保本应用不在休眠名单；也可直接用上方「一键申请」按钮。",
            ),
            KeepAliveStep(
                "多任务锁定",
                "最近任务界面下拉本应用卡片锁定（部分版本为卡片右上角锁图标）。",
            ),
            KeepAliveStep(
                "荣耀专属路径",
                "荣耀手机管家 → 启动管理 → QQ同步器 → 关闭自动管理，手动放行后台活动。",
            ),
        ),
    ),
    KeepAliveBrand(
        name = "OPPO / 一加",
        matchers = listOf("oppo", "oneplus", "realme"),
        steps = listOf(
            KeepAliveStep(
                "自启动",
                "手机管家 → 权限隐私 → 自启动管理 → QQ同步器，打开开关。（或 设置 → 应用 → 自启动）",
            ),
            KeepAliveStep(
                "后台运行权限",
                "设置 → 电池 → 更多（高级设置）→ 应用耗电管理 → QQ同步器 → 允许完全后台行为；同时在「应用速冻」中关闭对本应用的限制。",
            ),
            KeepAliveStep(
                "睡眠待机优化",
                "设置 → 电池 → 睡眠待机优化，建议关闭（夜间深度休眠可能清理后台）。",
            ),
            KeepAliveStep(
                "锁定卡片",
                "最近任务下拉本应用卡片锁定，防止一键清理误杀。",
            ),
        ),
    ),
    KeepAliveBrand(
        name = "vivo / iQOO",
        matchers = listOf("vivo", "iqoo"),
        steps = listOf(
            KeepAliveStep(
                "自启动",
                "i管家 → 应用管理 → 权限管理 → 自启动 → QQ同步器，允许。（或 设置 → 应用与权限 → 权限管理 → 自启动）",
            ),
            KeepAliveStep(
                "后台高耗电",
                "设置 → 电池 → 后台功耗管理（后台高耗电）→ QQ同步器，允许。",
            ),
            KeepAliveStep(
                "加速白名单",
                "i管家 → 空间清理 → 右上角设置 → 加速白名单 → 添加 QQ同步器。",
            ),
            KeepAliveStep(
                "锁定卡片",
                "最近任务下拉卡片加锁，防止一键加速误杀。",
            ),
        ),
    ),
    KeepAliveBrand(
        name = "三星",
        matchers = listOf("samsung"),
        steps = listOf(
            KeepAliveStep(
                "应用电池不受限",
                "设置 → 应用程序 → QQ同步器 → 电池 → 选择「不受限制」。",
            ),
            KeepAliveStep(
                "后台使用限制",
                "设置 → 电池 → 后台使用限制 → 确认 QQ同步器 不在「深度睡眠 / 休眠」应用列表中。",
            ),
            KeepAliveStep(
                "常驻通知",
                "允许应用通知，保持前台服务持续活跃。",
            ),
        ),
    ),
    KeepAliveBrand(
        name = "其他 / 原生",
        matchers = listOf(),
        steps = listOf(
            KeepAliveStep(
                "电池不受限",
                "设置 → 应用 → QQ同步器 → 电池 → 「不受限制」。（Pixel 等原生系统可直接点上方「一键申请」）",
            ),
            KeepAliveStep(
                "厂商管家白名单",
                "若安装了厂商/第三方清理类应用，请将 QQ同步器 加入其清理白名单。",
            ),
            KeepAliveStep(
                "最近任务锁定",
                "部分原生系统支持最近任务卡片锁定，建议开启。",
            ),
        ),
    ),
)

/** 按 Build.MANUFACTURER 推断品牌方案下标；未匹配则回到「通用」 */
private fun detectBrandIndex(): Int = runCatching {
    val m = Build.MANUFACTURER.lowercase()
    KEEP_ALIVE_BRANDS.indices.firstOrNull { i ->
        KEEP_ALIVE_BRANDS[i].matchers.any { m.contains(it) }
    } ?: 0
}.getOrDefault(0)

private fun isIgnoringBatteryOptimizations(context: Context): Boolean = runCatching {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    pm.isIgnoringBatteryOptimizations(context.packageName)
}.getOrDefault(false)

/**
 * 后台保活向导（多数品牌适配方案）：
 * - 顶部直接动作：一键申请电池优化白名单（系统弹窗，全品牌通用）+ 打开应用信息；
 * - 品牌方案：按 Build.MANUFACTURER 自动选中，可手动切换（小米/华为/OPPO/vivo/三星/通用）；
 * - 分步教程卡片：自启动 / 省电策略 / 锁屏清理内存 / 最近任务锁定等。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeepAliveScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val enableBlur = LocalEnableBlur.current
    val blurBackdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = blurBackdrop != null
    val colorScheme = MiuixTheme.colorScheme

    var batteryWhitelisted by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryWhitelisted = isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var brandIndex by rememberSaveable { mutableStateOf(detectBrandIndex()) }
    val brand = KEEP_ALIVE_BRANDS[brandIndex.coerceIn(0, KEEP_ALIVE_BRANDS.lastIndex)]

    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    Scaffold(
        topBar = {
            BlurredBar(blurBackdrop) {
                SmallTopAppBar(
                    title = "后台保活向导",
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

                // ===== 直接动作卡：一键申请电池优化白名单 =====
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                MiuixIcons.Lock,
                                contentDescription = "电池优化",
                                tint = colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(text = "电池优化白名单", modifier = Modifier.weight(1f))
                            Text(
                                text = if (batteryWhitelisted) "已优化" else "未设置",
                                color = if (batteryWhitelisted) Color(0xFF4CAF50) else colorScheme.onSurfaceSecondary,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "将本应用加入电池优化白名单，防止系统在锁屏/省电时杀掉同步服务。全品牌通用，建议首先完成。",
                            fontSize = 13.sp,
                            color = colorScheme.onSurfaceSecondary,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { requestIgnoreBatteryOptimizations(context) },
                                colors = ButtonDefaults.buttonColorsPrimary(),
                                modifier = Modifier.weight(1f),
                            ) { Text(if (batteryWhitelisted) "重新申请" else "一键申请") }
                            Button(
                                onClick = { openAppDetails(context) },
                                colors = ButtonDefaults.buttonColors(),
                                modifier = Modifier.weight(1f),
                            ) { Text("打开应用信息") }
                        }
                    }
                }

                // ===== 品牌选择 =====
                SmallTitle(text = "选择品牌方案（已自动识别）")
                Card(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth(),
                ) {
                    FlowRow(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        KEEP_ALIVE_BRANDS.forEachIndexed { index, b ->
                            Button(
                                onClick = { brandIndex = index },
                                colors = if (index == brandIndex) {
                                    ButtonDefaults.buttonColorsPrimary()
                                } else {
                                    ButtonDefaults.buttonColors()
                                },
                                modifier = Modifier.padding(horizontal = 2.dp),
                            ) { Text(b.name) }
                        }
                    }
                }

                // ===== 分步教程 =====
                SmallTitle(text = "${brand.name} · 保活步骤")
                Column(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    brand.steps.forEachIndexed { index, step ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .listItemReveal(entered, index),
                        ) {
                            Row(modifier = Modifier.padding(14.dp)) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(colorScheme.primary.copy(alpha = 0.14f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "${index + 1}",
                                        color = colorScheme.primary,
                                        fontSize = 13.sp,
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = step.title)
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        text = step.detail,
                                        fontSize = 13.sp,
                                        color = colorScheme.onSurfaceSecondary,
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** 请求电池优化白名单（manifest 已声明 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 权限） */
private fun requestIgnoreBatteryOptimizations(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }.onFailure {
        // 个别 ROM 屏蔽了直达弹窗，退回电池优化设置列表
        runCatching {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}

/** 打开系统应用详情页（自启动/通知/电池等入口的集散地） */
private fun openAppDetails(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }
}
