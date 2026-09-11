package com.example.bandqq.sync

import android.content.Context
import com.xiaomi.xms.wearable.Wearable
import com.xiaomi.xms.wearable.auth.AuthApi
import com.xiaomi.xms.wearable.auth.Permission
import com.xiaomi.xms.wearable.message.MessageApi
import com.xiaomi.xms.wearable.message.OnMessageReceivedListener
import com.xiaomi.xms.wearable.node.Node
import com.xiaomi.xms.wearable.node.NodeApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets

/**
 * 手环互联桥：基于小米 xms-wearable-lib 实现手环 <-> 手机互联通道。
 * 连接流程（参考弦电子书）：
 *   1. getNodeApi.connectedNodes 发现已通过运动健康连接的手环
 *   2. authApi 申请 DEVICE_MANAGER 权限（触发运动健康授权）
 *   3. launchWearApp 拉起手环端应用页面
 *   4. messageApi.addListener 注册接收手环帧
 *   5. sendMessage 向手环下发帧
 */
object InterconnectBridge {

    private const val TAG = "InterconnectBridge"

    /** 手环端 manifest 路由，用于 launchWearApp 拉起入口页 */
    const val WEAR_ENTRY_ROUTE = "/pages/index"

    private const val PING_INTERVAL_MS = 3000L
    private const val TIMEOUT_MS = 10000L
    private const val RECONNECT_INTERVAL_MS = 5000L

    private var broker: MessageBroker? = null
    private var context: Context? = null

    private var nodeApi: NodeApi? = null
    private var authApi: AuthApi? = null
    private var messageApi: MessageApi? = null

    @Volatile
    private var currentNode: Node? = null

    /** 是否存在尚未完成的连接链路（防止重连循环并发触发多条鉴权/注册链路）。 */
    @Volatile
    private var connecting: Boolean = false

    @Volatile
    private var lastPongMs: Long = 0L

    @Volatile
    var available: Boolean = false
        private set

    /** 是否存在可用连接节点（sendToBand 可实际下发的前提）。 */
    fun isNodeReady(): Boolean = currentNode != null

    private val heartbeatScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null

    /**
     * 注册手环消息监听。
     * 使用 nodeId 级幂等锁：重复注册前先移除旧 listener，避免多条帧被多次 import
     * 回调（已观察到 addListener 多次注册会将一条 send_message 重复执行 N 次）。
     */
    private val messageListener = OnMessageReceivedListener { _, data ->
        try {
            val json = String(data, StandardCharsets.UTF_8)
            LogBus.log(TAG, LogLevel.DEBUG, "onBandMessage: $json")
            broker?.onBandFrame(json)
        } catch (e: Exception) {
            LogBus.log(TAG, LogLevel.ERROR, "onBandMessage parse error: $e")
        }
    }

    /** 已注册监听器的节点集合，作为幂等注册判断。 */
    private val registeredNodes = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun registerListener(node: Node) {
        val messageApi = messageApi ?: run {
            connecting = false
            return
        }
        // 幂等：若该节点已注册过则先移除旧 listener，确保不会叠加多个回调
        if (registeredNodes.remove(node.id)) {
            LogBus.log(TAG, LogLevel.DEBUG, "registerListener idempotent remove old listener for ${node.id}")
            messageApi.removeListener(node.id)
        }
        messageApi.addListener(node.id, messageListener)
            .addOnSuccessListener {
                registeredNodes.add(node.id)
                LogBus.log(TAG, LogLevel.DEBUG, "registerListener ok")
                connecting = false
                startHeartbeat()
            }
            .addOnFailureListener { error ->
                val msg = error.message.orEmpty()
                if (msg.contains("You have registered", ignoreCase = true)) {
                    LogBus.log(TAG, LogLevel.WARN, "listener already registered, continue")
                    connecting = false
                    startHeartbeat()
                } else {
                    LogBus.log(TAG, LogLevel.ERROR, "registerListener failed: $error")
                    SyncState.bandConnected = false
                    BandStateBus.notify(false)
                    connecting = false
                }
            }
    }

    fun register(broker: MessageBroker) {
        this.broker = broker
        broker.bandSender = { frame -> sendToBand(frame) }
        broker.onBandPong = { onPong() }
        SyncState.bandConnected = false
        startReconnectLoop()
    }

    fun unregister(broker: MessageBroker) {
        if (this.broker === broker) this.broker = null
        stopReconnectLoop()
        stopHeartbeat()
        release()
    }

    /**
     * 初始化互联 SDK 通道并建立连接。
     * 需在 Service/Activity 持有 Context 时调用（连接状态通过 SyncState 暴露）。
     */
    fun init(context: Context) {
        this.context = context.applicationContext
        if (available) return
        available = true
        try {
            nodeApi = Wearable.getNodeApi(context)
            authApi = Wearable.getAuthApi(context)
            messageApi = Wearable.getMessageApi(context)
        } catch (e: Throwable) {
            LogBus.log(TAG, LogLevel.ERROR, "init xms-wearable-lib failed: $e")
            available = false
        }
    }

    /**
     * 异步建立与手环的互联连接：发现设备 -> 鉴权 -> 拉起应用 -> 注册监听。
     * @param launchApp 是否拉起手环端应用。用户主动连接（开启服务/点击连接）为 true；
     *                  自动重连为 false，避免用户退出手环应用后又被反复拉起。
     */
    fun connect(launchApp: Boolean = true) {
        val nodeApi = nodeApi ?: run {
            LogBus.log(TAG, LogLevel.ERROR, "connect: not initialized")
            SyncState.bandConnected = false
            BandStateBus.notify(false)
            connecting = false
            return
        }
        nodeApi.connectedNodes
            .addOnSuccessListener { nodes ->
                if (nodes.isEmpty()) {
                    LogBus.log(TAG, LogLevel.WARN, "connect: 未发现已连接的手环，请确认小米运动健康已连接手环")
                    SyncState.bandConnected = false
                    // 清除可能过期的陈旧节点，避免重连循环反复走 re-auth 快路径
                    currentNode = null
                    BandStateBus.notify(false)
                    connecting = false
                    return@addOnSuccessListener
                }
                currentNode = nodes[0]
                LogBus.log(TAG, LogLevel.DEBUG, "connect: found device ${nodes[0].name}")
                auth(nodes[0], launchApp)
            }
            .addOnFailureListener { e ->
                LogBus.log(TAG, LogLevel.ERROR, "connect: getConnectedNodes failed: $e")
                SyncState.bandConnected = false
                BandStateBus.notify(false)
                connecting = false
            }
    }

    private fun auth(node: Node, launchApp: Boolean = true) {
        val authApi = authApi ?: run {
            connecting = false
            return
        }
        authApi.checkPermissions(node.id, arrayOf(Permission.DEVICE_MANAGER))
            .addOnSuccessListener { results ->
                var needRequest = false
                for ((_, value) in results.withIndex()) {
                    if (!value) {
                        authApi.requestPermission(node.id, Permission.DEVICE_MANAGER)
                            .addOnFailureListener { e -> LogBus.log(TAG, LogLevel.ERROR, "auth request failed: $e") }
                        needRequest = true
                    }
                }
                if (needRequest) {
                    LogBus.log(TAG, LogLevel.DEBUG, "auth: 已请求 DEVICE_MANAGER 权限，等待用户在运动健康中授权")
                }
                // 无论本次结果如何，继续尝试后续流程；连接真正可用取决于授权完成
                openApp(node, launchApp)
            }
            .addOnFailureListener { e ->
                LogBus.log(TAG, LogLevel.ERROR, "auth check failed: $e")
                openApp(node, launchApp)
            }
    }

    private fun openApp(node: Node, launchApp: Boolean = true) {
        val nodeApi = nodeApi ?: run {
            connecting = false
            return
        }
        // 自动重连场景不拉起手环应用：用户退出手环应用后保持退出，避免反复被拉起
        if (!launchApp) {
            LogBus.log(TAG, LogLevel.DEBUG, "openApp: skip launchWearApp (auto reconnect), register listener anyway")
            registerListener(node)
            return
        }
        nodeApi.launchWearApp(node.id, WEAR_ENTRY_ROUTE)
            .addOnSuccessListener {
                LogBus.log(TAG, LogLevel.DEBUG, "openApp: 已在手环上拉起应用")
                registerListener(node)
            }
            .addOnFailureListener { e ->
                LogBus.log(TAG, LogLevel.ERROR, "openApp failed: $e")
                // 手环端可能未安装应用或未在前台；仍注册监听以便手环侧主动连接
                registerListener(node)
            }
    }

/**
     * 将同步器产生的 JSON 帧发送到已连接的手环。
     * 不依赖 bandConnected（心跳 ping 需在未确认前也能发送），仅要求已发现节点。
     */
    fun sendToBand(frame: String) {
        val node = currentNode ?: run {
            LogBus.log(TAG, LogLevel.ERROR, "sendToBand skipped: no connected node. frame=${frame.take(80)}")
            return
        }
        val messageApi = messageApi ?: run {
            LogBus.log(TAG, LogLevel.ERROR, "sendToBand skipped: messageApi null")
            return
        }
        val bytes = frame.toByteArray(StandardCharsets.UTF_8)
        LogBus.log(TAG, LogLevel.DEBUG, "sendToBand frame bytes=${bytes.size}: ${frame.take(80)}")
        // v2.8.1：互联 SDK 在节点失效/通道异常时可能同步抛异常；该调用发生在 OkHttp WS
        // 回调线程（OneBot 消息链）内，异常上抛会被 OkHttp 当作连接失败断开。全部兜住。
        try {
            messageApi.sendMessage(node.id, bytes)
                .addOnSuccessListener { LogBus.log(TAG, LogLevel.DEBUG, "sendToBand ok (${bytes.size}B)") }
                .addOnFailureListener { e -> LogBus.log(TAG, LogLevel.ERROR, "sendToBand failed (${bytes.size}B): ${e.message}") }
        } catch (t: Throwable) {
            LogBus.log(TAG, LogLevel.ERROR, "sendToBand exception (${bytes.size}B): $t")
        }
    }

    /** 收到手环 pong：确认真实在线并重置超时计时。 */
    private fun onPong() {
        lastPongMs = System.currentTimeMillis()
        if (!SyncState.bandConnected) {
            onConnect()
        }
    }

    /** 启动心跳：立即发一次 ping，随后周期性 ping 并检查超时。 */
    private fun startHeartbeat() {
        stopHeartbeat()
        lastPongMs = System.currentTimeMillis()
        heartbeatJob = heartbeatScope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                if (now - lastPongMs > TIMEOUT_MS) {
                    onDisconnect()
                } else {
                    sendPing()
                }
                delay(PING_INTERVAL_MS)
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    /** 启动自动重连循环：离线时周期性重建与手环的互联通道。 */
    private fun startReconnectLoop() {
        stopReconnectLoop()
        reconnectJob = heartbeatScope.launch {
            while (isActive) {
                try {
                    // connecting 防止上一条链路未终结时又发起新的鉴权/注册，避免 addListener 重复注册
                    if (!SyncState.bandConnected && !connecting) {
                        connecting = true
                        val node = currentNode
                        if (node != null) {
                            LogBus.log(TAG, LogLevel.WARN, "reconnect: re-auth node ${node.id} (no launch)")
                            auth(node, launchApp = false)
                        } else {
                            LogBus.log(TAG, LogLevel.WARN, "reconnect: no node, retry connect (no launch)")
                            connect(launchApp = false)
                        }
                    }
                } catch (t: Throwable) {
                    LogBus.log(TAG, LogLevel.ERROR, "reconnect loop error: ${t.message}")
                    connecting = false
                }
                delay(RECONNECT_INTERVAL_MS)
            }
        }
    }

    /** 停止自动重连循环。 */
    private fun stopReconnectLoop() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    private fun sendPing() {
        val b = broker
        if (b != null) {
            sendToBand("""{"type":"ping","seq":0}""")
        }
    }

    /** 手环消息回调：由 SDK 在收到手环帧时调用 */
    fun onBandMessage(json: String) {
        val b = broker ?: return
        b.onBandFrame(json)
    }

    fun onConnect() {
        SyncState.bandConnected = true
        BandStateBus.notify(true)
        // v2.7.0：快应用已连上，撤销待执行的自动拉起与预告通知
        try { AutoLauncher.onBandConnected() } catch (_: Throwable) {}
        broker?.bandSender?.invoke(SyncStatePush.buildFrame())
        broker?.pushVisibleContacts()
        broker?.pushQuickReplies()
        // v2.8.0 双端互通：连接建立即下发设置快照（手环本地持久化，变更经 settings_update 回传）
        broker?.pushSettingsState()
    }

    fun onDisconnect() {
        val wasConnected = SyncState.bandConnected
        SyncState.bandConnected = false
        BandStateBus.notify(false)
        if (wasConnected) {
            broker?.bandSender?.invoke(SyncStatePush.buildFrame())
        }
    }

    /**
     * 仅拉起手环端快应用，不动鉴权/监听链（v2.7.0 AutoLauncher 专用）。
     * 与 connect(launchApp=true) 的区别：不重新走 connectedNodes/auth/addListener，
     * 避免自动拉起场景下与心跳/重连循环并发触发多条连接链路。
     */
    fun launchWearAppNow() {
        val node = currentNode
        val nodeApi = nodeApi
        if (node == null || nodeApi == null) {
            LogBus.log(TAG, LogLevel.WARN, "launchWearAppNow skipped: node not ready")
            return
        }
        nodeApi.launchWearApp(node.id, WEAR_ENTRY_ROUTE)
            .addOnSuccessListener { LogBus.log(TAG, LogLevel.INFO, "launchWearAppNow ok: quick app opening on band") }
            .addOnFailureListener { e -> LogBus.log(TAG, LogLevel.ERROR, "launchWearAppNow failed: $e") }
    }

    fun release() {
        stopHeartbeat()
        val node = currentNode
        val messageApi = messageApi
        if (node != null && messageApi != null) {
            messageApi.removeListener(node.id)
                .addOnSuccessListener { SyncState.bandConnected = false }
                .addOnFailureListener { SyncState.bandConnected = false }
        }
        currentNode = null
    }
}
