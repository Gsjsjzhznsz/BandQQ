package io.github.gsjsjzhznsz.bandqq.astrbot

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * v2.12.0 AstrBot Bubble 本地伴侣桥（MuFengDR/AstrBot-Bubble-Android-App）
 *
 * AstrBot Bubble 是跑在手机 proot 容器里的 AstrBot + NapCat 一体化部署：
 * 它在本机拉起 NapCat（WS :3001 / HTTP :3000），BandQQ 无需电脑/局域网即可直连，
 * 同时 AstrBot 给这个 QQ 号接入大模型自动回复能力。
 *
 * 本桥不做二进制合并（对方是 Flutter + 64MB Ubuntu rootfs 的独立应用，
 * 强行嵌入会让 BandQQ APK 膨胀数百 MB 且双引擎互相拖累），而是做三件务实事：
 *   ① 检测 AstrBot Bubble 是否已安装（含 profile/debug 变体）并一键拉起
 *   ② 探测本机 NapCat 端口（127.0.0.1:3001/3000）是否就绪
 *   ③ 一键把连接配置填充为本机地址（用户点"保存"后与手环同步）
 */
object AstrBotBridge {

    /** AstrBot Bubble 各变体包名（release 无后缀；profile/debug 带后缀） */
    val KNOWN_PACKAGES = listOf(
        "com.astrbot.astrbot_bubble",
        "com.astrbot.astrbot_bubble.profile",
        "com.astrbot.astrbot_bubble.debug"
    )

    const val REPO_URL = "https://github.com/MuFengDR/AstrBot-Bubble-Android-App"

    /** 本机 NapCat 预设地址（AstrBot Bubble 默认端口） */
    const val LOCAL_WS_URL = "ws://127.0.0.1:3001"
    const val LOCAL_HTTP_URL = "http://127.0.0.1:3000"

    /** 返回已安装的 AstrBot Bubble 包名（按 release→profile→debug 优先级），未装返回 null */
    fun installedPackage(context: Context): String? {
        val pm = context.packageManager
        for (pkg in KNOWN_PACKAGES) {
            // Android 11+ 包可见性需 manifest <queries> 声明；查不到时静默视为未安装
            if (runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull() != null) {
                return pkg
            }
        }
        return null
    }

    /** 拉起 AstrBot Bubble；返回 false 表示未安装或无法拉起 */
    fun launch(context: Context): Boolean {
        val pkg = installedPackage(context) ?: return false
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /** 跳转浏览器打开 AstrBot Bubble 仓库（未安装时的引导） */
    fun openRepo(context: Context) {
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(REPO_URL))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    data class LocalProbe(
        val wsReachable: Boolean,
        val httpReachable: Boolean
    ) {
        val ready: Boolean get() = wsReachable || httpReachable
        fun describe(): String = when {
            wsReachable && httpReachable -> "本机 NapCat 在线（WS+HTTP 均可连）"
            wsReachable -> "本机 NapCat 在线（仅 WS :3001 可连）"
            httpReachable -> "本机 NapCat 在线（仅 HTTP :3000 可连）"
            else -> "本机 NapCat 未就绪（3001/3000 均未监听）"
        }
    }

    /** 探测本机 NapCat 端口是否就绪（TCP 连通即算，2 秒超时） */
    suspend fun probeLocalNapcat(): LocalProbe = withContext(Dispatchers.IO) {
        LocalProbe(
            wsReachable = portOpen(3001),
            httpReachable = portOpen(3000)
        )
    }

    private fun portOpen(port: Int): Boolean = runCatching {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), 2000)
        }
        true
    }.getOrDefault(false)
}
