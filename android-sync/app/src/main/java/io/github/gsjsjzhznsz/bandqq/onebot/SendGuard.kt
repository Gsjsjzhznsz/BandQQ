package io.github.gsjsjzhznsz.bandqq.onebot

/**
 * v2.28.0 发送风控保护（SendGuard）。
 *
 * 背景（用户 10-05 封号反馈）：APK 内置 NapCat（proot 容器）一发消息账号即被冻结，
 * 而 docker NapCat / AstrBot 泡泡版同账号从未被封。除反检测层（bypass 钩子被崩溃
 * 自愈长期禁用→已修）外，行为层差异同样致命：手环/自动化场景的"新登录后立即
 * 发送 + 机器人式匀速连发"是腾讯风控的高危特征。本类在 App 发送链上加三道闸：
 *
 * ① 登录预热：NapCat 重启/重新登录（WS 断开 ≥2 分钟后重新连上）后 45 秒预热期
 *   内拒绝外发——新登录窗口立即发消息是风控最敏感特征。短抖动重连不触发。
 * ② 限速+抖动：相邻两条发送最小间隔 2.5s + 0~1.5s 随机抖动（拟人节奏）。
 * ③ 突发冷却：60 秒滑动窗口内 ≥12 条 → 冷却 60 秒（群发/轰炸特征拦截）。
 *
 * 仅约束 send_* 消息下发（sendMessage）；get_* 读取与 WS 握手不受影响。
 * 纯逻辑无 Android 依赖，时钟/随机可注入，可单测。
 */
class SendGuard(
    private val now: () -> Long = System::currentTimeMillis,
    private val jitter: () -> Long = { (0..JITTER_MS).random() },
) {

    companion object {
        /** 断开≥该时长后重新连上视为「NapCat 重启/重新登录」→ 触发预热（NapCat 冷启动 2~3 分钟，短抖动 1~5 秒，阈值取中） */
        const val RECONNECT_WARMUP_GAP_MS = 120_000L

        /** 预热时长：新登录窗口内不外发 */
        const val WARMUP_MS = 45_000L

        /** 相邻两条消息最小间隔 */
        const val MIN_INTERVAL_MS = 2_500L

        /** 随机抖动上限 */
        const val JITTER_MS = 1_500L

        /** 突发判定滑动窗口 */
        const val BURST_WINDOW_MS = 60_000L

        /** 窗口内允许的最大发送条数（超出触发冷却） */
        const val BURST_MAX = 12

        /** 突发冷却时长 */
        const val BURST_COOLDOWN_MS = 60_000L
    }

    @Volatile private var warmupUntil = 0L
    @Volatile private var cooldownUntil = 0L
    @Volatile private var disconnectedAt = 0L
    @Volatile private var lastSendAt = 0L
    @Volatile private var wasConnected = false
    private val sendTimestamps = ArrayDeque<Long>()

    /** WS 连接建立（onOpen）时调用。曾连接成功且断开足够久 → 进入预热。 */
    fun onConnected() {
        val n = now()
        if (wasConnected && disconnectedAt > 0 && n - disconnectedAt >= RECONNECT_WARMUP_GAP_MS) {
            warmupUntil = n + WARMUP_MS
        }
        wasConnected = true
    }

    /** WS 断开（onFailure/onClosed）时调用；仅曾连接成功才记录（连接失败重试不触发预热）。 */
    fun onDisconnected() {
        if (wasConnected) disconnectedAt = now()
    }

    /**
     * 发送前风控检查。返回 null=放行（随后按 [pacingDelay] 等待并 [commit]）；
     * 非 null=拦截原因（直接回给手环 toast）。
     */
    fun gate(): String? {
        val n = now()
        if (n < warmupUntil) {
            val left = (warmupUntil - n + 999) / 1000
            return "风控预热中（剩 ${left} 秒）：NapCat 刚重启/重新登录，新登录立即发消息易触发腾讯风控"
        }
        if (n < cooldownUntil) {
            val left = (cooldownUntil - n + 999) / 1000
            return "发送冷却中（剩 ${left} 秒）：短时间内发送过多，已自动放慢（风控保护）"
        }
        return null
    }

    /** 本次发送需先等待的毫秒数（最小间隔 + 随机抖动；0=立即可发）。 */
    fun pacingDelay(): Long {
        val n = now()
        val sinceLast = if (lastSendAt > 0) n - lastSendAt else Long.MAX_VALUE
        val base = if (sinceLast < MIN_INTERVAL_MS) MIN_INTERVAL_MS - sinceLast else 0L
        return base + jitter()
    }

    /** 记录一次已下发的发送（滑动窗口计数；超突发上限 → 冷却）。 */
    fun commit() {
        val n = now()
        lastSendAt = n
        synchronized(sendTimestamps) {
            sendTimestamps.addLast(n)
            while (sendTimestamps.isNotEmpty() && n - sendTimestamps.first() > BURST_WINDOW_MS) {
                sendTimestamps.removeFirst()
            }
            if (sendTimestamps.size >= BURST_MAX) {
                cooldownUntil = n + BURST_COOLDOWN_MS
                sendTimestamps.clear()
            }
        }
    }

    /** 当前预热/冷却剩余说明（UI 展示用；无任何活动返回 null）。 */
    fun activeRestriction(): String? = gate()

    /** 测试辅助：完全重置状态。 */
    fun reset() {
        warmupUntil = 0; cooldownUntil = 0; disconnectedAt = 0; lastSendAt = 0; wasConnected = false
        synchronized(sendTimestamps) { sendTimestamps.clear() }
    }
}
