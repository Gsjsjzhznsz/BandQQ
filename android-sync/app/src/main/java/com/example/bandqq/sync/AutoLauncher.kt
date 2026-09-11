package com.example.bandqq.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.bandqq.MainActivity
import com.example.bandqq.R
import com.example.bandqq.config.ConfigHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 快应用自动拉起（v2.7.0，用户置顶需求）：
 *
 * 场景：手环端快应用没打开时收到新消息 —— 消息不会显示（互联消息通道需要
 * 对端应用在线）。此功能在收到新消息后延迟 N 秒自动通过互联 launchWearApp
 * 拉起手环端快应用完成同步展示，用户无需手动打开。
 *
 * 流程（收到消息 → scheduleIfEnabled）：
 *   1. 判定：开关开启 && 快应用未连接(无心跳 pong) && 手环节点就绪；
 *   2. 先发一条系统通知「将于 N 秒后自动打开手环QQ」—— 小米运动健康的
 *      应用通知同步会把这条通知镜像到手环上，起到预告作用；
 *   3. 延迟到期且快应用仍未连接 → launchWearApp 拉起；期间快应用连上（用户
 *      手动打开了）→ 自动取消并撤掉通知；
 *   4. 用户点通知 = 不想拉起 → 取消本次任务并撤掉通知。
 *
 * 去重：消息风暴只保留第一个待执行任务；拉起完成/取消后归零，下次消息重新排队。
 */
object AutoLauncher {

    private const val TAG = "AutoLauncher"
    private const val CHANNEL_ID = "auto_launch_channel"
    private const val NOTIFICATION_ID = 1002

    /** 通知点击 extra：打开主界面同时取消本次自动拉起（用户不想要这次拉起） */
    const val EXTRA_CANCEL_AUTO_LAUNCH = "cancel_auto_launch"

    @Volatile
    private var appContext: Context? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var pendingJob: Job? = null

    /** SyncService.onCreate 注入应用上下文 */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * 新消息推送后的判定入口（MessageBroker.onEvent 调用）。
     * 全部为内存标志位判断，O(1)，未启用/已连接时直接返回，零开销。
     */
    fun scheduleIfEnabled() {
        val cfg = ConfigHolder.config
        if (!cfg.autoLaunchEnabled) return
        if (pendingJob?.isActive == true) return // 已有待执行任务：消息风暴去重，不叠加
        if (SyncState.bandConnected) return      // 快应用已打开，无需拉起
        val ctx = appContext ?: return
        if (!InterconnectBridge.isNodeReady()) return // 未发现手环节点（运动健康未连接）

        val delaySec = cfg.autoLaunchDelaySec.coerceIn(3, 120)
        LogBus.log(TAG, LogLevel.INFO, "schedule auto launch in ${delaySec}s (band quick app closed)")
        notifyCountdown(ctx, delaySec)
        pendingJob = scope.launch {
            delay(delaySec * 1000L)
            pendingJob = null
            try {
                if (SyncState.bandConnected) {
                    LogBus.log(TAG, LogLevel.INFO, "band connected during countdown, cancel launch")
                    return@launch
                }
                LogBus.log(TAG, LogLevel.INFO, "auto launch wear app now")
                InterconnectBridge.launchWearAppNow()
            } finally {
                cancelNotification(ctx)
            }
        }
    }

    /** 手环快应用连上（心跳 pong 确认）时调用：撤销待执行任务与预告通知 */
    fun onBandConnected() {
        if (pendingJob?.isActive == true) {
            pendingJob?.cancel()
            pendingJob = null
            LogBus.log(TAG, LogLevel.INFO, "band connected, cancel pending auto launch")
        }
        appContext?.let { cancelNotification(it) }
    }

    /** 用户显式取消（点通知 / 关开关）：撤销任务与通知 */
    fun cancelPending(reason: String) {
        if (pendingJob?.isActive == true) {
            pendingJob?.cancel()
            LogBus.log(TAG, LogLevel.INFO, "pending auto launch cancelled: $reason")
        }
        pendingJob = null
        appContext?.let { cancelNotification(it) }
    }

    /** 开关被关闭时调用（设置页）：连同通知一起清掉 */
    fun onSettingDisabled() = cancelPending("setting disabled")

    // ===== 系统通知（经运动健康镜像到手环，作为「即将打开」的预告） =====

    private fun notifyCountdown(ctx: Context, delaySec: Int) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (!nm.areNotificationsEnabled()) {
            LogBus.log(TAG, LogLevel.WARN, "notifications disabled by user, skip countdown notice")
            return
        }
        val channel = NotificationChannel(
            CHANNEL_ID, "快应用自动拉起", NotificationManager.IMPORTANCE_DEFAULT
        )
        channel.description = "收到新消息时预告并自动打开手环QQ"
        nm.createNotificationChannel(channel)
        val tap = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                .putExtra(EXTRA_CANCEL_AUTO_LAUNCH, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(ctx, CHANNEL_ID)
                    .setContentTitle("手环QQ 将于 ${delaySec} 秒后自动打开")
                    .setContentText("以同步展示新消息；点击通知取消本次自动打开")
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentIntent(tap)
                    .setAutoCancel(true)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(ctx)
                    .setContentTitle("手环QQ 将于 ${delaySec} 秒后自动打开")
                    .setContentText("以同步展示新消息；点击通知取消本次自动打开")
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentIntent(tap)
                    .setAutoCancel(true)
                    .build()
            }
        runCatching { nm.notify(NOTIFICATION_ID, notification) }
            .onFailure { LogBus.log(TAG, LogLevel.WARN, "notify failed: $it") }
    }

    private fun cancelNotification(ctx: Context) {
        runCatching {
            ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }
    }
}
