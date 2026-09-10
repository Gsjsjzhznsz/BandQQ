package com.example.bandqq.ui

import android.content.ComponentName
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.bandqq.sync.SyncService
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
import top.yukonga.miuix.kmp.icon.extended.CloudFill
import top.yukonga.miuix.kmp.icon.extended.Email
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.blur.layerBackdrop

/** 单个保活步骤：标题 + 具体路径说明 */
private data class KeepAliveStep(val title: String, val detail: String)

/** 品牌适配方案：matchers 用于按 Build.MANUFACTURER 自动选中；autoStartIntents 按顺序尝试直达自启动管理页 */
private data class KeepAliveBrand(
    val name: String,
    val matchers: List<String>,
    val autoStartIntents: List<Pair<String, String>> = emptyList(), // (包名, 类名) 依优先级
    val steps: List<KeepAliveStep>,
)

/**
 * 主流品牌后台保活方案（锁屏清理 / 自启动 / 电池优化 / 无障碍辅助）。
 * 自启动管理页组件名矩阵源自 DontKillMyApp / KeepAlive 库社区实践，按优先级逐个尝试，
 * 全部失败时降级系统应用详情页。步骤路径以当前主流 ROM 版本为准，老版本路径写在括号内兜底。
 */
private val KEEP_ALIVE_BRANDS = listOf(
    KeepAliveBrand(
        name = "通用",
        matchers = listOf(),
        steps = listOf(
            KeepAliveStep(
                "电池优化白名单",
                "点击上方「权限检测」中的申请按钮，在系统弹窗中选择「允许」（几乎所有品牌通用，效果最直接）。",
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
                "关闭清理类工具冲突",
                "若开启了会「自动清理后台」的管家/清理类工具，请将本应用加入其白名单或关闭对应功能。",
            ),
        ),
    ),
    KeepAliveBrand(
        name = "小米 / Redmi",
        matchers = listOf("xiaomi", "redmi"),
        autoStartIntents = listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        ),
        steps = listOf(
            KeepAliveStep(
                "自启动",
                "设置 → 应用设置 → 应用管理 → QQ同步器 → 自启动，打开开关。（可点上方「打开自启动管理」直达）",
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
        autoStartIntents = listOf(
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
            "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        ),
        steps = listOf(
            KeepAliveStep(
                "应用启动管理",
                "设置 → 应用和服务（应用）→ 应用启动管理 → QQ同步器 → 关闭「自动管理」，手动开启：允许自启动 / 关联启动 / 后台活动 三项。（可点上方「打开自启动管理」直达）",
            ),
            KeepAliveStep(
                "电池优化",
                "设置 → 电池 → 更多电池设置（或 启动管理），确保本应用不在休眠名单；也可直接用上方「一键申请」。",
            ),
            KeepAliveStep(
                "多任务锁定",
                "最近任务界面下拉本应用卡片锁定（部分版本为卡片右上角锁图标）。",
            ),
        ),
    ),
    KeepAliveBrand(
        name = "OPPO / 一加",
        matchers = listOf("oppo", "oneplus", "realme"),
        autoStartIntents = listOf(
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
            "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
        ),
        steps = listOf(
            KeepAliveStep(
                "自启动",
                "手机管家 → 权限隐私 → 自启动管理 → QQ同步器，打开开关。（可点上方「打开自启动管理」直达）",
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
        autoStartIntents = listOf(
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManagerActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
        ),
        steps = listOf(
            KeepAliveStep(
                "自启动",
                "i管家 → 应用管理 → 权限管理 → 自启动 → QQ同步器，允许。（可点上方「打开自启动管理」直达）",
            ),
            KeepAliveStep(
                "后台高耗电",
                "设置 → 电池 → 后台功耗管理（后台高耗电）→ QQ同步器，允许。",
            ),
            KeepAliveStep(
                "加速白名单",
                "i管家 → 空间清理 → 右上角设置 → 加速白名单 → 添加 QQ同步器。",
            ),
        ),
    ),
    KeepAliveBrand(
        name = "三星",
        matchers = listOf("samsung"),
        autoStartIntents = listOf(
            "com.samsung.android.lool" to "com.samsung.android.sm.battery.ui.BatteryActivity",
            "com.samsung.android.sm" to "com.samsung.android.sm.battery.ui.BatteryActivity",
        ),
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

private fun notificationsEnabled(context: Context): Boolean = runCatching {
    NotificationManagerCompat.from(context).areNotificationsEnabled()
}.getOrDefault(true)

/** 逐个尝试品牌自启动管理页直达 Intent，全部失败返回 false（降级应用详情页） */
private fun tryOpenAutoStart(context: Context, brand: KeepAliveBrand): Boolean {
    brand.autoStartIntents.forEach { (pkg, cls) ->
        runCatching {
            context.startActivity(
                Intent().setComponent(ComponentName(pkg, cls))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onSuccess { return true }
    }
    return false
}

/** 打开系统应用详情页（自启动/通知/电池等入口的集散地，兜底方案） */
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
        }.onFailure { openAppDetails(context) }
    }
}

/** 跳转本应用系统通知设置 */
private fun openNotificationSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }.onFailure { openAppDetails(context) }
}

/** 权限检测项状态 */
private enum class PermState(val label: String, val color: Color) {
    Ok("已允许", Color(0xFF4CAF50)),
    Denied("未允许", Color(0xFFE53935)),
    Manual("需手动", Color(0xFFFF9800)),
}

/** 权限检测行：图标 + 标题 + 状态 chip + 动作按钮 */
@Composable
private fun PermCheckRow(
    title: String,
    icon: @Composable () -> Unit,
    state: PermState,
    hint: String,
    actionText: String,
    onAction: () -> Unit,
    entered: Boolean,
    index: Int,
) {
    val colorScheme = MiuixTheme.colorScheme
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .listItemReveal(entered, index),
    ) {
        Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) { icon() }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = title, modifier = Modifier.weight(1f))
                    Text(
                        text = state.label,
                        color = state.color,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(state.color.copy(alpha = 0.12f))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(text = hint, fontSize = 13.sp, color = colorScheme.onSurfaceSecondary)
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onAction,
                    colors = if (state == PermState.Ok) ButtonDefaults.buttonColors() else ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(actionText, fontSize = 14.sp) }
            }
        }
    }
}

/**
 * 后台保活向导（多数品牌适配方案）：
 * - 权限检测：电池优化白名单 / 通知权限 / 自启动 / 前台服务，状态实时刷新 + 直达跳转；
 * - 品牌直达：按 Build.MANUFACTURER 自动匹配自启动管理页组件矩阵，失败降级应用详情页；
 * - 品牌方案可手动切换（小米/华为/OPPO/vivo/三星/通用）+ 分步教程卡片。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeepAliveScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val enableBlur = LocalEnableBlur.current
    val blurBackdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = blurBackdrop != null
    val colorScheme = MiuixTheme.colorScheme

    // ===== 权限检测状态（ON_RESUME 实时刷新，从系统页返回后立即更新）=====
    var batteryWhitelisted by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    var notificationsOn by remember { mutableStateOf(notificationsEnabled(context)) }
    var serviceRunning by remember { mutableStateOf(SyncService.isRunning) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryWhitelisted = isIgnoringBatteryOptimizations(context)
                notificationsOn = notificationsEnabled(context)
                serviceRunning = SyncService.isRunning
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // 服务状态没有系统回调，轻量轮询兜底（2s，页面在前台才跑）
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(2000)
            serviceRunning = SyncService.isRunning
        }
    }

    var brandIndex by rememberSaveable { mutableIntStateOf(detectBrandIndex()) }
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

                // ===== 权限检测 =====
                SmallTitle(text = "权限检测（从系统页返回后自动刷新）")
                Spacer(Modifier.height(12.dp))
                PermCheckRow(
                    title = "电池优化白名单",
                    icon = {
                        Icon(
                            MiuixIcons.Lock, contentDescription = "电池优化",
                            tint = colorScheme.primary, modifier = Modifier.size(18.dp),
                        )
                    },
                    state = if (batteryWhitelisted) PermState.Ok else PermState.Denied,
                    hint = "防止系统在锁屏/省电时杀掉同步服务，全品牌通用，建议首先完成。",
                    actionText = if (batteryWhitelisted) "重新申请" else "一键申请",
                    onAction = { requestIgnoreBatteryOptimizations(context) },
                    entered = entered,
                    index = 0,
                )
                Spacer(Modifier.height(8.dp))
                PermCheckRow(
                    title = "通知权限",
                    icon = {
                        Icon(
                            MiuixIcons.Email, contentDescription = "通知",
                            tint = colorScheme.primary, modifier = Modifier.size(18.dp),
                        )
                    },
                    state = if (notificationsOn) PermState.Ok else PermState.Denied,
                    hint = "前台服务常驻通知更稳，部分系统杀后台前会先撤掉通知。",
                    actionText = "打开通知设置",
                    onAction = { openNotificationSettings(context) },
                    entered = entered,
                    index = 1,
                )
                Spacer(Modifier.height(8.dp))
                PermCheckRow(
                    title = "自启动（系统受限，无法读取状态）",
                    icon = {
                        Icon(
                            MiuixIcons.Play, contentDescription = "自启动",
                            tint = colorScheme.primary, modifier = Modifier.size(18.dp),
                        )
                    },
                    state = PermState.Manual,
                    hint = "各品牌不提供查询接口，请点下方按钮直达「自启动管理」，手动允许本应用。",
                    actionText = "打开自启动管理（${brand.name}）",
                    onAction = {
                        if (!tryOpenAutoStart(context, brand)) openAppDetails(context)
                    },
                    entered = entered,
                    index = 2,
                )
                Spacer(Modifier.height(8.dp))
                PermCheckRow(
                    title = "同步前台服务",
                    icon = {
                        Icon(
                            MiuixIcons.CloudFill, contentDescription = "前台服务",
                            tint = colorScheme.primary, modifier = Modifier.size(18.dp),
                        )
                    },
                    state = if (serviceRunning) PermState.Ok else PermState.Manual,
                    hint = "同步器前台服务当前状态；未运行时全部保活设置均无意义，请先启动。",
                    actionText = if (serviceRunning) "服务运行中" else "启动同步服务",
                    onAction = {
                        if (!serviceRunning) {
                            SyncService.start(context)
                            toast(context, "同步服务已启动")
                        }
                    },
                    entered = entered,
                    index = 3,
                )

                // ===== 品牌选择 =====
                SmallTitle(text = "选择品牌方案（已自动识别）", modifier = Modifier.padding(top = 20.dp))
                Card(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth()
                        .listItemReveal(entered, 4),
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
                SmallTitle(text = "${brand.name} · 补充保活步骤", modifier = Modifier.padding(top = 20.dp))
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
                                .listItemReveal(entered, index + 5),
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
