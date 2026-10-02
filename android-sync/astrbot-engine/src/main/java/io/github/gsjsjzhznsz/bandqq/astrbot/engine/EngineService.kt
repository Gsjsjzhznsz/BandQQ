package io.github.gsjsjzhznsz.bandqq.astrbot.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * v2.13.0 AstrBot 引擎前台服务：容器运行期间 dataSync 前台通知 + 部分 wake lock。
 * Android 14 的 dataSync 6 小时限额场景（机器人长跑）由设置页提示用户；
 * 通知点击回 BandQQ 主界面由宿主侧包名解析（engine 模块不依赖 app）。
 */
class EngineService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        acquireWakeLock()
        return START_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "AstrBot 本地引擎", NotificationManager.IMPORTANCE_LOW
                ).apply { description = "容器运行保活通知" }
            )
        }
        val open = packageManager.getLaunchIntentForPackage(packageName)
        val pi = if (open != null) {
            android.app.PendingIntent.getActivity(
                this, 0, open,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
        } else null
        val n: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("AstrBot 本地引擎运行中")
            .setContentText("本机 NapCat :3001/:3000 · 手环 QQ 直连就绪")
            .setOngoing(true)
            .apply { pi?.let { setContentIntent(it) } }
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BandQQ:astrbot-engine").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L) // 12h 上限，重进页面会重置
        }
    }

    override fun onDestroy() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "astrbot_engine"
        private const val NOTIF_ID = 0x5151

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, EngineService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, EngineService::class.java))
        }
    }
}
