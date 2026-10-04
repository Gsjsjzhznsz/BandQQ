package com.bandqq.sync.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.bandqq.sync.R
import com.bandqq.sync.onebot.OneBotClient

/**
 * 前台同步服务：组装 OneBot 客户端 + 消息桥 + 手环 WS 服务。
 * 配置存于 prefs：onebot_ws / onebot_http / onebot_token
 */
class SyncService : Service() {

    companion object {
        const val CHANNEL_ID = "sync"
        const val NOTIF_ID = 1001
        const val PREFS = "bandqq_config"
        const val DEF_WS = "ws://192.168.3.33:3001"
        const val DEF_HTTP = "http://192.168.3.33:3000"
        @Volatile var instance: SyncService? = null
            private set
        @Volatile var lastState: SyncState = SyncState()
    }

    data class SyncState(
        val onebotConnected: Boolean = false,
        val bandConnected: Boolean = false,
        val loginUserId: String = "",
        val loginNickname: String = ""
    )

    private var client: OneBotClient? = null
    private var broker: MessageBroker? = null
    private var bandServer: BandServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForeground(NOTIF_ID, buildNotification("正在连接 OneBot…"))
        startSync()
    }

    private fun prefs(): SharedPreferences = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun startSync() {
        val ws = prefs().getString("onebot_ws", DEF_WS) ?: DEF_WS
        val http = prefs().getString("onebot_http", DEF_HTTP) ?: DEF_HTTP
        val token = prefs().getString("onebot_token", "") ?: ""

        val store = MessageStore(this)
        client = OneBotClient(ws, http, token)
        broker = MessageBroker(client!!, store)
        broker!!.thumbFetcher = ThumbFetcher()

        client!!.setOnState { connected ->
            lastState = lastState.copy(onebotConnected = connected)
            updateNotification()
        }

        broker!!.start()

        bandServer = BandServer(broker!!, BandServer.PORT)
        try {
            bandServer!!.isReuseAddr = true
            bandServer!!.start()
        } catch (_: Exception) {}
        lastState = lastState.copy(bandConnected = true)
        updateNotification()
    }

    fun updateConfig(ws: String, http: String, token: String) {
        prefs().edit().putString("onebot_ws", ws).putString("onebot_http", http)
            .putString("onebot_token", token).apply()
        client?.updateConfig(ws, http, token)
    }

    fun currentConfig(): Triple<String, String, String> = Triple(
        prefs().getString("onebot_ws", DEF_WS) ?: DEF_WS,
        prefs().getString("onebot_http", DEF_HTTP) ?: DEF_HTTP,
        prefs().getString("onebot_token", "") ?: ""
    )

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(ch)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val st = lastState
        val text = when {
            st.onebotConnected -> "已连接（QQ: ${st.loginNickname.ifEmpty { st.loginUserId }}）"
            else -> "等待 OneBot 连接…"
        }
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        try { bandServer?.stop() } catch (_: Exception) {}
        client?.destroy()
        instance = null
        super.onDestroy()
    }
}
