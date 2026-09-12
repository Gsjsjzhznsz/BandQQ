package com.example.bandqq.sync

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.bandqq.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 文件日志（v2.9.1）：把 LogBus 全量日志异步落盘到应用外部数据目录，
 * 用户用文件管理器 / USB(MTP) 直接可取，无需 root：
 *   /sdcard/Android/data/com.example.bandqq/files/logs/bandqq-YYYY-MM-DD.log
 *
 * 设计要点：
 * - 挂在 LogBus.sink 上，业务代码无感知（业务照旧 LogBus.log）；
 * - 单线程异步写，绝不阻塞业务线程；IO 异常静默（日志失败不影响主流程）；
 * - 按天分文件，保留 RETENTION_DAYS 天，单文件超 8MB 滚动为 .old.log；
 * - JVM 单测环境未 install() 时 dir 为空，write 直接 no-op。
 */
object FileLogger {

    private const val TAG = "FileLogger"
    private const val RETENTION_DAYS = 7
    private const val MAX_FILE_BYTES = 8L * 1024 * 1024

    private var dir: File? = null
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "file-logger").apply { isDaemon = true }
    }
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val lineFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** 在 Application.onCreate 调用；幂等。 */
    fun install(context: Context) {
        if (dir != null) return
        val base = runCatching { context.getExternalFilesDir(null) }.getOrNull() ?: context.filesDir
        val d = File(base, "logs")
        runCatching { if (!d.exists()) d.mkdirs() }
        dir = d
        LogBus.sink = { entry -> write(entry) }
        val appCtx = context.applicationContext
        io.execute {
            cleanup()
            writeSessionHeader(appCtx)
        }
    }

    /** 当前日志目录（可能为 null：未初始化或存储不可用）。 */
    fun logDir(): File? = dir?.takeIf { it.exists() }

    private fun write(entry: LogEntry) {
        val d = dir ?: return
        io.execute {
            runCatching {
                val day = dayFmt.format(Date(entry.time))
                val f = fileFor(d, day)
                f.appendText(
                    "${lineFmt.format(Date(entry.time))} ${levelChar(entry.level)}/${entry.tag}: ${entry.message}\n"
                )
            }
        }
    }

    private fun fileFor(d: File, day: String): File {
        var f = File(d, "bandqq-$day.log")
        if (f.exists() && f.length() > MAX_FILE_BYTES) {
            val rolled = File(d, "bandqq-$day.old.log")
            rolled.delete()
            f.renameTo(rolled)
            f = File(d, "bandqq-$day.log")
        }
        return f
    }

    /** 进程启动头：分隔每个运行会话，并记录版本与设备环境。 */
    private fun writeSessionHeader(context: Context) {
        runCatching {
            val day = dayFmt.format(Date())
            val f = fileFor(dir ?: return, day)
            f.appendText("\n===== BandQQ ${BuildConfig.VERSION_NAME}(vc${BuildConfig.VERSION_CODE}) "
                + "会话开始 ${lineFmt.format(Date())} =====\n")
        }
    }

    /**
     * 环境自检 dump（走 LogBus → 自动进文件日志与实时面板）。
     * 内容面向「设备授权管理找不到应用」一类远程排查：
     * 机型 / 系统版本 / 运动健康版本 / 关键权限 / 互联授权状态提示。
     */
    fun logEnvironment(context: Context) {
        val appVersion = "${BuildConfig.VERSION_NAME}(vc${BuildConfig.VERSION_CODE})"
        LogBus.log(TAG, LogLevel.INFO, "设备: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        LogBus.log(TAG, LogLevel.INFO, "系统: Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) ${miuiVersion()}".trimEnd())
        LogBus.log(TAG, LogLevel.INFO, "应用: BandQQ $appVersion  日志目录: ${logDir()?.absolutePath ?: "不可用"}")
        val pm = context.packageManager
        try {
            val info = pm.getPackageInfo("com.xiaomi.wearable", 0)
            LogBus.log(TAG, LogLevel.INFO, "小米运动健康: ${info.versionName} 已安装")
        } catch (_: Exception) {
            LogBus.log(TAG, LogLevel.WARN, "小米运动健康: 未安装（互联通道必需，未安装则发现不了手环节点）")
        }
        if (Build.VERSION.SDK_INT >= 31) {
            LogBus.log(TAG, LogLevel.INFO, "权限-蓝牙连接: "
                + if (ContextCompat.checkSelfPermission(context, "android.permission.BLUETOOTH_CONNECT")
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) "已授权" else "未授权")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            LogBus.log(TAG, LogLevel.INFO, "权限-通知: "
                + if (ContextCompat.checkSelfPermission(context, "android.permission.POST_NOTIFICATIONS")
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) "已授权" else "未授权")
        }
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        LogBus.log(TAG, LogLevel.INFO, "电池优化白名单: "
            + if (power.isIgnoringBatteryOptimizations(context.packageName)) "已加入" else "未加入")
        val a11y = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        LogBus.log(TAG, LogLevel.INFO, "无障碍保活: "
            + if (a11y.contains(context.packageName)) "已开启" else "未开启")
        LogBus.log(TAG, LogLevel.INFO, "提示: 运动健康「设备授权管理」条目在 APP 发起 DEVICE_MANAGER 授权请求后才生成，"
            + "开启同步服务并连接一次即会触发")
    }

    /** 打包全部日志 + 崩溃记录并拉起系统分享。 */
    fun shareZip(context: Context) {
        val d = logDir()
        if (d == null) {
            toast(context, "日志目录不可用")
            return
        }
        val appCtx = context.applicationContext
        io.execute {
            runCatching {
                val out = File(appCtx.cacheDir, "bandqq-logs.zip")
                if (out.exists()) out.delete()
                ZipOutputStream(out.outputStream()).use { zos ->
                    d.listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.forEach { f ->
                        zos.putNextEntry(ZipEntry(f.name))
                        f.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                    val crash = File(appCtx.filesDir, "crash_log.txt")
                    if (crash.exists()) {
                        zos.putNextEntry(ZipEntry("crash_log.txt"))
                        crash.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
                val uri = FileProvider.getUriForFile(appCtx, "${appCtx.packageName}.fileprovider", out)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(send, "分享 BandQQ 日志")
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                appCtx.startActivity(chooser, null)
                toast(appCtx, "日志已打包: ${out.absolutePath}")
            }.onFailure { t ->
                LogBus.log(TAG, LogLevel.ERROR, "导出日志失败: $t")
                toast(appCtx, "导出失败: ${t.message}")
            }
        }
    }

    private fun cleanup() {
        val d = dir ?: return
        val files = d.listFiles() ?: return
        val cutoff = System.currentTimeMillis() - RETENTION_DAYS * 86_400_000L
        for (f in files) {
            val name = f.name
            if (f.isFile && (name.endsWith(".log") || name.endsWith(".old.log"))
                && f.lastModified() < cutoff) {
                runCatching { f.delete() }
            }
        }
    }

    private fun miuiVersion(): String {
        val hyper = sysProp("ro.mi.os.version.name")
        if (hyper.isNotBlank()) return "HyperOS $hyper"
        val miui = sysProp("ro.miui.ui.version.name")
        if (miui.isNotBlank()) return "MIUI $miui"
        return ""
    }

    private fun sysProp(key: String): String = runCatching {
        val c = Class.forName("android.os.SystemProperties")
        c.getMethod("get", String::class.java).invoke(null, key) as? String
    }.getOrNull().orEmpty()

    private fun levelChar(l: LogLevel) = when (l) {
        LogLevel.DEBUG -> 'D'
        LogLevel.INFO -> 'I'
        LogLevel.WARN -> 'W'
        LogLevel.ERROR -> 'E'
    }

    private fun toast(context: Context, msg: String) {
        val appCtx = context.applicationContext
        Handler(Looper.getMainLooper()).post {
            runCatching {
                android.widget.Toast.makeText(appCtx, msg, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
}
