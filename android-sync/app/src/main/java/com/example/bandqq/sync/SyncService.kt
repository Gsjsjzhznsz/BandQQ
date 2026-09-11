package com.example.bandqq.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.example.bandqq.R
import com.example.bandqq.config.ConfigHolder
import com.example.bandqq.config.ConfigManager
import com.example.bandqq.onebot.OneBotClient
import com.example.bandqq.onebot.OneBotListener
import com.example.bandqq.onebot.OneBotMessage
import com.example.bandqq.onebot.OneBotParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

object SyncState {
    @Volatile var oneBotConnected: Boolean = false
    @Volatile var bandConnected: Boolean = false
}

/** 全局可访问的数据仓（手机端为主存储），供服务与界面共享 */
object StoreHolder {
    @Volatile var store: MessageStore? = null
        private set

    fun setStore(s: MessageStore) {
        store = s
    }
}

/** 手环连接状态变化回调，供界面刷新 */
object BandStateBus {
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()

    fun add(listener: (Boolean) -> Unit) {
        listeners.add(listener)
    }

    fun remove(listener: (Boolean) -> Unit) {
        listeners.remove(listener)
    }

    fun notify(connected: Boolean) {
        for (l in listeners) {
            try {
                l(connected)
            } catch (_: Exception) {
            }
        }
    }
}

/** OneBot（协议端）连接状态变化回调，供界面实时刷新（原先只能手动测试/轮询感知） */
object OneBotStateBus {
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()

    fun add(listener: (Boolean) -> Unit) {
        listeners.add(listener)
    }

    fun remove(listener: (Boolean) -> Unit) {
        listeners.remove(listener)
    }

    fun notify(connected: Boolean) {
        for (l in listeners) {
            try {
                l(connected)
            } catch (_: Exception) {
            }
        }
    }
}

/** 新消息到达回调，供聊天记录页实时刷新（参数为收到消息的会话 targetId） */
object MessageBus {
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(String) -> Unit>()

    fun add(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    fun remove(listener: (String) -> Unit) {
        listeners.remove(listener)
    }

    fun notify(targetId: String) {
        for (l in listeners) {
            try {
                l(targetId)
            } catch (_: Exception) {
            }
        }
    }

    internal fun clear() {
        listeners.clear()
    }
}

class SyncService : Service() {

    companion object {
        const val ACTION_START = "com.example.bandqq.action.START"
        const val ACTION_STOP = "com.example.bandqq.action.STOP"
        private const val CHANNEL_ID = "sync_channel"
        const val NOTIFICATION_ID = 1001

        /** 前台服务是否在运行（供保活向导权限检测展示；非精确生命周期，仅指示性） */
        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SyncService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, SyncService::class.java).setAction(ACTION_STOP))
        }

        /** 测试推送钩子（v2.5.0 多场景模拟器）：服务运行时由 onCreate 注入，设置页调用。
         *  参数 (chatType: "private"|"group", scenario)，返回结果描述供 toast */
        @Volatile
        var testPush: ((String, String) -> String)? = null
            private set

        /** 快捷回复即时同步钩子（v2.7.0）：设置页保存后立即推给手环，免重启快应用 */
        @Volatile
        var pushQuickRepliesNow: (() -> Unit)? = null
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var configManager: ConfigManager
    private lateinit var parser: OneBotParser
    private lateinit var oneBot: OneBotClient
    private lateinit var broker: MessageBroker

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createChannel()
        configManager = ConfigManager(this)
        parser = OneBotParser()
        oneBot = OneBotClient(parser)
        val store = MessageStore(SyncPreferencesKv(this))
        StoreHolder.setStore(store)
        broker = MessageBroker(parser, oneBot, store) { s ->
            val http = ConfigHolder.config.endpoint.httpUrl
            var okFriend = false
            var okGroup = false
            fun settled() {
                if (okFriend && okGroup) broker.autoFetchDone = true
            }
            oneBot.requestApi("get_friend_list", http) { raw ->
                val list = ContactCache.parseContactResponse("private", raw)
                if (list.isNotEmpty()) {
                    s.setCachedContacts(s.getCachedContacts().filter { it.type != "private" } + list)
                    okFriend = true
                }
                settled()
            }
            oneBot.requestApi("get_group_list", http) { raw ->
                val list = ContactCache.parseContactResponse("group", raw)
                if (list.isNotEmpty()) {
                    s.setCachedContacts(s.getCachedContacts().filter { it.type != "group" } + list)
                    okGroup = true
                }
                settled()
            }
        }
        oneBot.startWithListener(broker)
        InterconnectBridge.register(broker)
        testPush = { chatType, scenario -> broker.pushTestMessage(chatType, scenario) }
        pushQuickRepliesNow = { broker.pushQuickReplies() }
        AutoLauncher.init(this)
        InterconnectBridge.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                scope.launch {
                    val config = configManager.load()
                    oneBot.start(config.endpoint, broker)
                    InterconnectBridge.connect()
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        testPush = null
        pushQuickRepliesNow = null
        AutoLauncher.cancelPending("service destroyed")
        oneBot.stop()
        InterconnectBridge.unregister(broker)
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "同步器服务", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("QQ同步器")
            .setContentText("同步器运行中")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
    }
}
