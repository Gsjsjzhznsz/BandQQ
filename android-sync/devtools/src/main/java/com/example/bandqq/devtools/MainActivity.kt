package com.example.bandqq.devtools

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * BandQQ DevTools（v1.2.0）—— 模拟 OneBot 协议端的开发者测试工具。
 *
 * 使用方法：
 * 1. 打开本工具，点「启动服务器」（默认 WS :3001 / HTTP :3000）；
 * 2. BandQQ 同步器 APP 设置里，OneBot 地址保持默认（ws://127.0.0.1:3001 与
 *    http://127.0.0.1:3000）或改指本机，点「测试连接」应显示可连；
 * 3. 启动同步服务后 APP 自动连上 WS 并拉取模拟联系人；
 *    v1.2.0：BandQQ 2.8.2+ 连上后会经 WS 上报身份，日志显示「BandQQ APP x.y.z」——
 *    若连接日志无此行，说明对端不是最新 APP（旧版收到事件会异常断连）；
 * 4. 点下方按钮发送模拟消息 → APP 入库 → 蓝牙推到手环展示；
 * 5. 手环回复 → 本工具日志显示收到的动作 → （开关开启时）自动回推对方消息，
 *   形成「手环 ⇄ 协议端」完整闭环，全程无需真实 QQ 服务器。
 *
 * v1.2.0 协议补全：WS 动作 echo 应答 / lifecycle connect / 30s 心跳元事件 /
 * 断连归因日志（close code、写入失败、读空闲收割）/ HTTP 中文 body 字节读修复。
 */
class MainActivity : AppCompatActivity() {

    private var wsServer: WsServer? = null
    private var httpServer: HttpApiServer? = null
    private var actionRouter: ActionRouter? = null

    /** 最近一次事件下发时间：用于识别「下发后客户端秒断」并给出升级提示 */
    private val lastBroadcastAt = java.util.concurrent.atomic.AtomicLong(0L)

    private lateinit var statusText: TextView
    private lateinit var logView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var startBtn: Button
    private lateinit var chatTypeGroup: LinearLayout
    private lateinit var selfQqInput: EditText
    private lateinit var selfNickInput: EditText
    private lateinit var nicknameInput: EditText
    private lateinit var contentInput: EditText
    private lateinit var portInput: EditText
    private lateinit var autoEchoSwitch: Switch
    private lateinit var scenarioRowButtons: List<List<Button>>
    private lateinit var chatTypeButtons: List<Button>

    private var chatType = "private"
    private var scenario = "text"

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val logLines = StringBuilder()

    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        restorePrefs()
    }

    // ================= UI 构建（纯代码，避免多套资源文件） =================

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun buildUi() {
        val root = ScrollView(this).apply {
            setBackgroundColor(0xFF10141A.toInt())
            scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(24))
        }
        root.addView(column)

        // ===== 标题 =====
        column.addView(text("BandQQ DevTools", 20f, 0xFFFFFFFF.toInt(), bold = true))
        column.addView(
            text(
                "模拟 OneBot 协议端（WS :3001 / HTTP :3000）· 无需真实 QQ 服务器即可调试 BandQQ 全链路",
                12f, 0xFF8A93A3.toInt()
            ).apply { setPadding(0, dp(2), 0, dp(4)) }
        )
        column.addView(
            text(
                "作者一秋 · QQ 2308534727 · github.com/Gsjsjzhznsz/BandQQ",
                12f, 0xFF5B9BFF.toInt()
            ).apply { setPadding(0, 0, 0, dp(8)) }
        )

        // ===== 服务器控制 =====
        column.addView(card {
            orientation = LinearLayout.VERTICAL
            statusText = text("服务器未启动", 14f, 0xFFFFC864.toInt())
            addView(statusText)
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(8), 0, 0)
                    portInput = EditText(this@MainActivity).apply {
                        hint = "端口基号(3000)"
                        inputType = InputType.TYPE_CLASS_NUMBER
                        setText("3000")
                        textSize = 13f
                        setSingleLine(true)
                        setTextColor(0xFFFFFFFF.toInt())
                        setHintTextColor(0xFF5A6372.toInt())
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    addView(portInput)
                    startBtn = Button(this@MainActivity).apply {
                        text = "启动服务器"
                        textSize = 13f
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { marginStart = dp(8) }
                        setOnClickListener { toggleServer() }
                    }
                    addView(startBtn)
                }
            )
        })

        // ===== 我的身份（v2.8.1）：@我 判定与 get_login_info 的依据 =====
        column.addView(card {
            orientation = LinearLayout.VERTICAL
            addView(
                text(
                    "我的身份（BandQQ 的 @我 判定依据：@消息的 at 号需与此一致）",
                    12f, 0xFF8A93A3.toInt()
                ).apply { setPadding(0, 0, 0, dp(6)) }
            )
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    selfQqInput = EditText(this@MainActivity).apply {
                        hint = "我的QQ号（默认10000）"
                        inputType = InputType.TYPE_CLASS_NUMBER
                        setText("10000")
                        textSize = 13f
                        setSingleLine(true)
                        setTextColor(0xFFFFFFFF.toInt())
                        setHintTextColor(0xFF5A6372.toInt())
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    addView(selfQqInput)
                    selfNickInput = EditText(this@MainActivity).apply {
                        hint = "我的昵称（默认：我）"
                        textSize = 13f
                        setSingleLine(true)
                        setTextColor(0xFFFFFFFF.toInt())
                        setHintTextColor(0xFF5A6372.toInt())
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                            .apply { marginStart = dp(8) }
                    }
                    addView(selfNickInput)
                }
            )
        })

        // ===== 模拟消息 =====
        column.addView(sectionTitle("模拟消息（点击即发送到手环）"))
        chatTypeGroup = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chatTypeButtons = listOf("私聊" to "private", "群聊" to "group").map { (label, id) ->
            toggleButton(label) {
                chatType = id
                refreshToggleStyles()
            }.also {
                chatTypeGroup.addView(it, rowParams())
            }
        }
        column.addView(chatTypeGroup)

        val rows = listOf(
            listOf("文本" to "text", "@我" to "at", "图片" to "image"),
            listOf("表情" to "face", "引用回复" to "reply", "语音" to "voice"),
            listOf("文件" to "file", "长文本" to "long", "撤回" to "recall"),
        )
        scenarioRowButtons = rows.map { row ->
            val btns = row.map { (label, id) ->
                toggleButton(label) {
                    if (scenario == id) sendScenario() else { scenario = id; refreshToggleStyles(); sendScenario() }
                }
            }
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                btns.forEach { addView(it, rowParams()) }
            }.also { column.addView(it) }
            btns
        }

        // ===== 自定义发送 =====
        column.addView(sectionTitle("自定义消息"))
        column.addView(card {
            orientation = LinearLayout.VERTICAL
            nicknameInput = EditText(this@MainActivity).apply {
                hint = "发送者昵称（默认：测试好友/群友小王）"
                textSize = 13f
                setSingleLine(true)
                setTextColor(0xFFFFFFFF.toInt())
                setHintTextColor(0xFF5A6372.toInt())
            }
            addView(nicknameInput)
            contentInput = EditText(this@MainActivity).apply {
                hint = "消息内容（支持换行）"
                textSize = 13f
                minLines = 2
                setTextColor(0xFFFFFFFF.toInt())
                setHintTextColor(0xFF5A6372.toInt())
            }
            addView(contentInput)
            addView(
                Button(this@MainActivity).apply {
                    text = "发送自定义消息"
                    textSize = 13f
                    setOnClickListener { sendCustom() }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(6) }
                }
            )
        })

        // ===== 自动回推开关 =====
        column.addView(card {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            autoEchoSwitch = Switch(this@MainActivity).apply {
                isChecked = true
                setTextSize(13f)
                setTextColor(0xFFE8ECF2.toInt())
                text = "收到手环回复后自动回推对方消息（闭环演示）"
            }
            addView(autoEchoSwitch)
        })

        // ===== 日志 =====
        column.addView(sectionTitle("事件日志"))
        column.addView(
            card {
                orientation = LinearLayout.VERTICAL
                scrollView = ScrollView(this@MainActivity).apply {
                    isVerticalScrollBarEnabled = true
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(240)
                    )
                }
                logView = TextView(this@MainActivity).apply {
                    textSize = 11f
                    typeface = Typeface.MONOSPACE
                    setTextColor(0xFF9FE8A8.toInt())
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    text = "等待启动服务器…\n"
                }
                scrollView.addView(logView)
                addView(scrollView)
            }
        )

        setContentView(root)
        refreshToggleStyles()
    }

    private fun sectionTitle(s: String): TextView =
        text(s, 13f, 0xFF8A93A3.toInt()).apply { setPadding(0, dp(10), 0, dp(4)) }

    private fun card(content: LinearLayout.() -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xFF1A2029.toInt())
                cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
            content()
        }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun toggleButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 12f
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    private fun rowParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginEnd = dp(6); topMargin = dp(4) }

    private fun refreshToggleStyles() {
        chatTypeButtons.forEachIndexed { i, b ->
            val active = (i == 0 && chatType == "private") || (i == 1 && chatType == "group")
            b.background = pill(active)
            b.setTextColor(if (active) 0xFFFFFFFF.toInt() else 0xFFB6BECA.toInt())
        }
        scenarioRowButtons.flatten().forEach { b ->
            val label = b.text.toString()
            val id = when (label) {
                "文本" -> "text"; "@我" -> "at"; "图片" -> "image"
                "表情" -> "face"; "引用回复" -> "reply"; "语音" -> "voice"
                "文件" -> "file"; "长文本" -> "long"; "撤回" -> "recall"
                else -> ""
            }
            val active = id == scenario
            b.background = pill(active)
            b.setTextColor(if (active) 0xFFFFFFFF.toInt() else 0xFFB6BECA.toInt())
        }
    }

    private fun pill(active: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setColor(if (active) 0xFF2D6BE4.toInt() else 0xFF232A36.toInt())
    }

    // ================= 业务逻辑 =================

    private fun basePort(): Int = portInput.text.toString().toIntOrNull()?.coerceIn(1024, 65535) ?: 3000

    /** 我的QQ号（v2.8.1）：@我 场景 at 段与事件 self_id、get_login_info 三处一致 */
    private fun selfQq(): Long = selfQqInput.text.toString().trim().toLongOrNull()
        ?.takeIf { it > 0 } ?: MsgBuilder.DEFAULT_SELF_ID

    private fun selfNickname(): String = selfNickInput.text.toString().trim().ifBlank { "我" }

    private fun toggleServer() {
        if (wsServer?.running == true || httpServer?.running == true) {
            stopServers()
        } else {
            startServers()
        }
    }

    private fun startServers() {
        val base = basePort()
        val wsPort = base + 1
        val log: (String) -> Unit = { msg -> appendLog(msg) }
        val router = ActionRouter(
            autoEcho = { autoEchoSwitch.isChecked },
            selfInfo = { selfQq() to selfNickname() },
            pushEvent = { event ->
                lastBroadcastAt.set(System.currentTimeMillis())
                wsServer?.broadcast(event)
                uiHandler.post {
                    appendLog("WS → 已下发事件: ${event.take(90)}")
                }
            },
            onLog = { msg -> uiHandler.post { appendLog(msg) } },
        )
        actionRouter = router
        wsServer = WsServer(wsPort, router) { kind, detail ->
            uiHandler.post {
                appendLog(detail)
                if (kind == "close") maybeReportSuspectClose()
            }
        }.also { it.start() }
        httpServer = HttpApiServer(port = base, router = router, onLog = log).also { it.start() }
        startBtn.text = "停止服务器"
        statusText.text = "运行中：WS :$wsPort · HTTP :$base"
        statusText.setTextColor(0xFF63E07C.toInt())
        getPreferences(MODE_PRIVATE).edit().putInt("base_port", base).apply()
        appendLog("DevTools 就绪：请打开 BandQQ APP → 设置 → 启动同步服务（地址保持 ws://127.0.0.1:${wsPort}）")
        appendLog("提示：BandQQ 2.8.2+ 连上后日志会出现「BandQQ APP x.y.z」身份行；没有即旧版 APP")
    }

    /** 下发事件后 4s 内客户端断开：大概率旧版 APP 解析异常断连或被系统冻结，给出可操作提示 */
    private fun maybeReportSuspectClose() {
        val last = lastBroadcastAt.get()
        if (last == 0L) return
        val delta = System.currentTimeMillis() - last
        if (delta in 0..4000) {
            appendLog("⚠ 事件下发后 ${delta}ms 客户端即断开 —— 同步器 APP 低于 2.8.2 时收到事件解析异常会断连")
            appendLog("  请安装最新 bandqq-sync-release APK，并在系统省电策略中允许 BandQQ 后台运行")
        }
    }

    private fun stopServers() {
        wsServer?.stop()
        httpServer?.stop()
        wsServer = null
        httpServer = null
        actionRouter = null
        lastBroadcastAt.set(0L)
        startBtn.text = "启动服务器"
        statusText.text = "服务器未启动"
        statusText.setTextColor(0xFFFFC864.toInt())
        appendLog("服务器已停止")
    }

    private fun sendScenario() {
        val ws = wsServer?.takeIf { it.running } ?: run {
            toastUi("请先启动服务器"); return
        }
        lastBroadcastAt.set(System.currentTimeMillis())
        val nickname = nicknameInput.text.toString().trim().ifBlank {
            if (chatType == "group") "群友小王" else "测试好友"
        }
        when (scenario) {
            "recall" -> {
                val (first, second) = MsgBuilder.recallFlow(chatType, nickname)
                ws.broadcast(first)
                ws.broadcast(second)
                appendLog("已发送：${if (chatType == "group") "群聊" else "私聊"} · 撤回（先文本后撤回帧）")
            }
            else -> {
                val event = MsgBuilder.messageEvent(
                    type = chatType,
                    nickname = nickname,
                    groupId = if (chatType == "group") MsgBuilder.DEFAULT_GROUP_ID else null,
                    message = MsgBuilder.scenarioSegments(scenario, contentInput.text.toString(), selfQq()),
                    selfId = selfQq(),
                )
                ws.broadcast(event)
                appendLog("已发送：${if (chatType == "group") "群聊" else "私聊"} · ${MsgBuilder.scenarioLabel(scenario)}（$nickname）")
            }
        }
    }

    private fun sendCustom() {
        val ws = wsServer?.takeIf { it.running } ?: run {
            toastUi("请先启动服务器"); return
        }
        lastBroadcastAt.set(System.currentTimeMillis())
        val content = contentInput.text.toString().trim()
        if (content.isEmpty()) {
            toastUi("请输入消息内容"); return
        }
        val nickname = nicknameInput.text.toString().trim().ifBlank {
            if (chatType == "group") "群友小王" else "测试好友"
        }
        val event = MsgBuilder.messageEvent(
            type = chatType,
            nickname = nickname,
            groupId = if (chatType == "group") MsgBuilder.DEFAULT_GROUP_ID else null,
            message = MsgBuilder.textArray(content),
            selfId = selfQq(),
        )
        ws.broadcast(event)
        appendLog("已发送：${if (chatType == "group") "群聊" else "私聊"} · 自定义（$nickname）：${content.take(40)}")
    }

    private fun toastUi(msg: String) {
        uiHandler.post {
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun appendLog(msg: String) {
        logLines.append(timeFmt.format(Date())).append("  ").append(msg).append('\n')
        if (logLines.length > 32_000) logLines.delete(0, logLines.length - 24_000)
        logView.text = logLines.toString()
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun restorePrefs() {
        val saved = getPreferences(MODE_PRIVATE)
        portInput.setText(saved.getInt("base_port", 3000).toString())
        // v2.8.1：恢复我的身份配置（QQ号/昵称），保证 @我 判定跨启动一致
        selfQqInput.setText(saved.getString("self_qq", "10000") ?: "10000")
        selfNickInput.setText(saved.getString("self_nick", "") ?: "")
    }

    override fun onPause() {
        super.onPause()
        // v2.8.1：身份配置随改随存
        getPreferences(MODE_PRIVATE).edit()
            .putString("self_qq", selfQqInput.text.toString().trim())
            .putString("self_nick", selfNickInput.text.toString().trim())
            .apply()
    }

    override fun onDestroy() {
        wsServer?.stop()
        httpServer?.stop()
        super.onDestroy()
    }
}
