package io.github.gsjsjzhznsz.bandqq.astrbot.engine

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * v2.13.0 AstrBot 本地引擎管理器（胖包 bundled flavor 专用）。
 *
 * 与 v2.12.0 的伴侣模式（AstrBotBridge 检测拉起独立 App）不同，胖包把
 * MuFengDR/AstrBot-Bubble-Android-App 的引擎层完整嵌入本 APK：
 *   - jniLibs：proot 套件（busybox/bash/loader/proot/talloc，来自上游 arm64-v8a）
 *   - assets：Ubuntu noble rootfs（proot-distro 格式 tar.xz）+ AstrBot 一体化启动脚本
 *
 * 运行链路与上游一致（scripts.dart 的 proot 启动命令逐参数复刻）：
 *   busybox xz -d | tar -x 解压 rootfs → bin 组装（lib*.so 去 lib 前缀 + chmod）→
 *   bash 环境变量（PROOT_LOADER/LD_LIBRARY_PATH/PROOT_TMP_DIR）→
 *   proot -0 -r rootfs（绑定 /dev /proc /sys /dev/pts tmp）→ 容器内 bash -lc
 *   astrbot-startup.sh（装 AstrBot + NapCat 并拉起，幂等）。
 *
 * 启动完成后本机 NapCat 监听 127.0.0.1:3001(WS)/3000(HTTP)，BandQQ 直连即可。
 */
object EngineManager {

    /** 引擎状态机：Idle → Installing → Starting → Running（Error 可从任意态进入） */
    sealed class State {
        object Idle : State()
        data class Installing(val step: String, val percent: Int) : State()
        object Starting : State()
        object Running : State()
        object Stopped : State()
        data class Error(val msg: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val mutex = Mutex()
    private val processRef = AtomicReference<Process?>(null)

    /** v2.20.0：用户主动停止标记——停止时杀进程会打断输出读取线程，
     *  此前会以“启动异常: read interrupted by close() on another thread”
     *  报进日志误导用户（10-04 日志实证）；置位后按正常停止处理 */
    @Volatile
    private var stopping = false

    /** v2.20.0：解析脚本 [STAGE:百分比:描述] 标记（ astrbot-startup.sh stage() 输出），
     *  驱动 UI 启动进度卡，让用户不看裸 log 也知道流程到哪了 */
    private val stageRegex = Regex("^\\[STAGE:(\\d{1,3}):(.+)]\\s*$")

    private fun tryParseStage(line: String): Pair<Int, String>? {
        val m = stageRegex.find(line.trim()) ?: return null
        val pct = (m.groupValues[1].toIntOrNull() ?: return null).coerceIn(0, 100)
        val desc = m.groupValues[2].trim()
        return pct to desc
    }

    // ---------- 目录布局（与上游 RuntimeEnvir 对齐） ----------

    fun engineRoot(ctx: Context): File = File(ctx.filesDir, "engine")
    fun binDir(ctx: Context): File = File(engineRoot(ctx), "bin")
    fun tmpDir(ctx: Context): File = File(engineRoot(ctx), "tmp")
    fun homeDir(ctx: Context): File = File(engineRoot(ctx), "home")
    fun rootfsDir(ctx: Context): File =
        File(engineRoot(ctx), "var/lib/proot-distro/installed-rootfs/ubuntu")

    /** v2.19.0：宿主侧生成的 resolv.conf（proot -b 绑定进容器 /etc/resolv.conf） */
    fun containerEtcDir(ctx: Context): File = File(engineRoot(ctx), "etc")

    private fun installStamp(ctx: Context): File = File(engineRoot(ctx), ".installed-4.18.0")

    /** 引擎是否已安装（bin 完整 + rootfs 关键文件存在 + 版本标记匹配） */
    fun isInstalled(ctx: Context): Boolean {
        val stamp = installStamp(ctx)
        if (!stamp.exists()) return false
        val bin = binDir(ctx)
        val rootfs = rootfsDir(ctx)
        return File(bin, "proot").canExecute() &&
            File(bin, "bash").canExecute() &&
            File(bin, "busybox").canExecute() &&
            File(rootfs, "bin/bash").exists() &&
            File(rootfs, "usr/bin/env").exists()
    }

    /** 引擎进程是否存活 */
    fun isRunning(): Boolean {
        val p = processRef.get() ?: return false
        return runCatching { p.isAlive }.getOrDefault(false)
    }

    // ---------- 日志（环形缓冲 + v2.18.1 文件落盘，设置卡片展示最近 N 行） ----------

    private val logBuf = ArrayDeque<String>()
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log

    /** v2.18.1：引擎日志落盘上下文与单线程 IO（与 app FileLogger 同目录同名约定） */
    @Volatile
    private var logCtx: Context? = null
    private val logIo = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "engine-file-log").apply { isDaemon = true }
    }

    /** 引擎日志目录：与主日志同目录（getExternalFilesDir/files 下的 logs/），导出 zip 自动收录 */
    private fun engineLogDir(ctx: Context): File? = runCatching {
        val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        File(base, "logs").apply { if (!exists()) mkdirs() }
    }.getOrNull()

    private fun dayFmt() = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
    private fun tsFmt() = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)

    private fun logFileFor(ctx: Context): File? {
        val d = engineLogDir(ctx) ?: return null
        return File(d, "engine-" + dayFmt().format(java.util.Date()) + ".log")
    }

    private fun logLine(s: String) {
        synchronized(logBuf) {
            logBuf.addLast(s)
            while (logBuf.size > 160) logBuf.removeFirst()
        }
        _log.value = synchronized(logBuf) { logBuf.toList() }
        android.util.Log.i("AstrBotEngine", s)
        // v2.24.0：转发到 App 注入的 sink（主页日志面板 LogBus），引擎/NapCat 行主页可见
        runCatching { logSink?.invoke(s) }
        // v2.18.1：异步落盘（用户反馈：引擎日志本地无 log 文件）。单文件 8MB 滚动 .old.log，
        // 保留策略随 FileLogger.cleanup（同目录 .log/.old.log 7 天统一回收）
        val ctx = logCtx ?: return
        val target = logFileFor(ctx) ?: return
        val ts = tsFmt().format(java.util.Date())
        logIo.execute {
            runCatching {
                if (target.exists() && target.length() > 8L * 1024 * 1024) {
                    val rolled = File(target.parentFile, target.name.removeSuffix(".log") + ".old.log")
                    rolled.delete()
                    target.renameTo(rolled)
                }
                target.appendText("$ts $s\n")
            }
        }
    }

    /** v2.18.1：引擎会话头（与 FileLogger 会话头同风格，区分每次引擎启动） */
    private fun writeEngineSessionHeader(ctx: Context) {
        logCtx = ctx.applicationContext
        val target = logFileFor(ctx) ?: return
        val ts = tsFmt().format(java.util.Date())
        logIo.execute {
            runCatching {
                target.appendText("\n===== AstrBot 引擎会话开始 $ts =====\n")
            }
        }
    }

    private fun logLinesOf(s: String) = s.split('\n').filter { it.isNotBlank() }

    // ---------- 安装（解压 rootfs + 组装 bin） ----------

    /** jniLibs → bin 映射（上游命名去 lib 前缀；libsudo.so 上游为占位文件跳过） */
    private val BIN_MAP = mapOf(
        "libbusybox.so" to "busybox",
        "libbash.so" to "bash",
        "libloader.so" to "loader",
        "libproot.so" to "proot",
        "liblibtalloc.so.2.so" to "libtalloc.so.2",
    )

    const val ROOTFS_ASSET = "ubuntu-noble-aarch64-pd-v4.18.0.tar.xz"

    /** 诊断：busybox 支持的 applet 清单（失败时打进日志，便于定位 ROM 差异） */
    private fun busyboxApplets(bin: File): String = runCatching {
        val p = ProcessBuilder(File(bin, "busybox").absolutePath, "--list")
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out.split('\n').filter { it.isNotBlank() }
            .let { l -> if (l.size > 24) "${l.take(24).joinToString(",")}…共${l.size}" else l.joinToString(",") }
    }.getOrDefault("无法获取（busybox --list 执行失败）")

    /**
     * v2.14.0：从 APK 压缩包内直接读取 jniLibs 产物（回退通道）。
     * 优先精确匹配 lib/arm64-v8a/<so>，退化匹配任意 abi 目录下同名项。
     */
    private fun extractSoFromApk(ctx: Context, soName: String): ByteArray? = runCatching {
        val apkFile = File(ctx.applicationInfo.sourceDir)
        java.util.zip.ZipFile(apkFile).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            val target = names.firstOrNull { it == "lib/arm64-v8a/$soName" }
                ?: names.firstOrNull { it.endsWith("/$soName") && it.startsWith("lib/") }
                ?: return null
            zip.getInputStream(zip.getEntry(target)).use { it.readBytes() }
        }
    }.getOrNull()

    /**
     * 安装引擎（幂等）：已装直接返回 true。三步：
     *  ① jniLibs 复制到 filesDir/bin 并 chmod 755（Android 11+ nativeLibraryDir 只读；
     *     v2.14.0 双通道：nativeLibraryDir 缺文件时从 APK 内 lib/arm64-v8a/ 直取，
     *     覆盖部分 ROM 解压目录不全 / split APK 场景）
     *  ② busybox xz -d -c | tar -x 解压 rootfs（C 实现，62MB → 约 3~10s）
     *  ③ 启动脚本与 AstrBot 配置模板拷入容器 /root
     */
    suspend fun installIfNeeded(ctx: Context): Boolean = mutex.withLock {
        if (isInstalled(ctx)) return true
        logCtx = ctx.applicationContext
        withContext(Dispatchers.IO) {
            try {
                val root = engineRoot(ctx)
                val bin = binDir(ctx)
                val tmp = tmpDir(ctx)
                listOf(root, bin, tmp, homeDir(ctx)).forEach { it.mkdirs() }

                // ① 组装 bin
                _state.value = State.Installing("组装运行时（proot/bash/busybox）", 5)
                val nativeDir = File(ctx.applicationInfo.nativeLibraryDir)
                for ((so, name) in BIN_MAP) {
                    val src = File(nativeDir, so)
                    val dst = File(bin, name)
                    if (src.exists()) {
                        src.copyTo(dst, overwrite = true)
                    } else {
                        // v2.14.0 回退通道：部分真机 ROM 的 nativeLibraryDir 不含全部 jniLibs
                        // （或用户装的是历版缺包 APK），直接从 APK 压缩包内提取同名 .so
                        val bytes = extractSoFromApk(ctx, so)
                        if (bytes == null) {
                            val abis = Build.SUPPORTED_ABIS.joinToString("/")
                            logLine(
                                "缺失 $so：nativeLibraryDir=$nativeDir 无此文件，" +
                                    "APK(${ctx.applicationInfo.sourceDir}) 内也无 lib/arm64-v8a/$so"
                            )
                            logLine("设备 ABI 列表：$abis；本引擎仅支持 arm64-v8a 真机")
                            throw IllegalStateException("native 库缺失: $so（设备 ABI: $abis，仅支持 arm64 真机）")
                        }
                        logLine("$so 从 nativeLibraryDir 缺失，已从 APK 内直取（${bytes.size / 1024}KB）")
                        dst.writeBytes(bytes)
                    }
                    dst.setExecutable(true, false)
                    dst.setReadable(true, false)
                }
                logLine("bin 组装完成（${BIN_MAP.size} 个可执行）")

                // ② 解压 rootfs。
                // v2.16.0 根因修复：proot-distro 发行包（pd-v4.18.0）全部条目带
                // ubuntu-noble-aarch64/ 顶层前缀，v2.15 直接 tar -x -C rootfs 后实体落在
                // <rootfs>/ubuntu-noble-aarch64/ 之下，"rootfs/bin/bash" 校验必然失败
                // （报"缺 bin/bash，包可能损坏"）。改为：清残留 → xz 两段解压（退出码
                // 独立可判）→ 前缀拍平（rename 上移，兼容无前缀包）→ 双路径校验。
                val rootfs = rootfsDir(ctx)
                // 历史版本半成品不可信（无 stamp 即未完成安装），整体清空重装
                if (rootfs.exists()) rootfs.deleteRecursively()
                rootfs.mkdirs()
                _state.value = State.Installing("解压 Ubuntu rootfs（首次约 3~10 秒）", 20)
                val tar = File(ctx.cacheDir, ROOTFS_ASSET)
                // assets 无法直接给 busybox 文件路径，先落 cache（62MB）
                if (!tar.exists() || tar.length() == 0L) {
                    ctx.assets.open(ROOTFS_ASSET).use { input ->
                        tar.outputStream().use { input.copyTo(it) }
                    }
                }
                logLine("rootfs 包就绪（${tar.length() / 1024 / 1024}MB），xz 解压中…")
                val tarPlain = File(ctx.cacheDir, "rootfs-plain.tar")
                val xz = ProcessBuilder(
                    File(bin, "busybox").absolutePath, "xz", "-d", "-c", tar.absolutePath
                ).redirectErrorStream(true).redirectOutput(tarPlain).start()
                val xzCode = xz.waitFor()
                if (xzCode != 0 || !tarPlain.exists() || tarPlain.length() == 0L) {
                    tarPlain.delete()
                    logLine("busybox xz 失败 exit=$xzCode（applet 清单：${busyboxApplets(bin)}）")
                    throw IllegalStateException("rootfs xz 解压失败 exit=$xzCode")
                }
                logLine("xz 完成（${tarPlain.length() / 1024 / 1024}MB），tar 解包中…")
                val untar = ProcessBuilder(
                    File(bin, "busybox").absolutePath, "tar", "-x", "-f",
                    tarPlain.absolutePath, "-C", rootfs.absolutePath
                ).redirectErrorStream(true).start()
                val tarTail = StringBuilder()
                untar.inputStream.bufferedReader().forEachLine {
                    if (tarTail.length < 2000) tarTail.appendLine(it)
                }
                val tarCode = untar.waitFor()
                tarPlain.delete()
                if (tarCode != 0) {
                    logLine("tar 解包失败 exit=$tarCode：${tarTail.takeLast(500)}")
                    throw IllegalStateException("rootfs tar 解包失败 exit=$tarCode")
                }

                // 前缀拍平：条目带 <distro>/ 顶层目录时把实体逐项 rename 上移
                if (!File(rootfs, "usr/bin/env").exists()) {
                    val nested = rootfs.listFiles { f -> f.isDirectory && File(f, "usr/bin").isDirectory }
                        ?.firstOrNull()
                    if (nested == null) {
                        logLine("解压产物：${rootfs.listFiles()?.joinToString(limit = 12) { it.name }}")
                        throw IllegalStateException("rootfs 解压后缺 usr/bin/env，包可能损坏")
                    }
                    logLine("检测到顶层前缀 ${nested.name}/，拍平…")
                    nested.listFiles()?.forEach { child ->
                        val dst = File(rootfs, child.name)
                        if (dst.exists()) dst.deleteRecursively()
                        if (!child.renameTo(dst)) {
                            throw IllegalStateException("rootfs 前缀拍平失败: ${child.name}")
                        }
                    }
                    nested.delete()
                }
                if (!File(rootfs, "usr/bin/bash").exists() || !File(rootfs, "bin/bash").exists()) {
                    logLine("解压产物：${rootfs.listFiles()?.joinToString(limit = 12) { it.name }}")
                    throw IllegalStateException("rootfs 解压后缺 usr/bin/bash 或 bin 链接，包可能损坏")
                }
                tar.delete()
                logLine("rootfs 解压完成")

                // ③ 脚本/配置入容器（上游 copy_files 逻辑）
                _state.value = State.Installing("写入 AstrBot 启动脚本", 90)
                val rootHome = File(rootfs, "root")
                rootHome.mkdirs()
                ctx.assets.open("astrbot-startup.sh").use { input ->
                    File(rootHome, "astrbot-startup.sh").outputStream().use { input.copyTo(it) }
                }
                ctx.assets.open("astrbot-installer-bootstrap.sh").use { input ->
                    File(rootHome, "astrbot-installer-bootstrap.sh").outputStream().use { input.copyTo(it) }
                }
                ctx.assets.open("cmd_config.json").use { input ->
                    File(rootHome, "cmd_config.json").outputStream().use { input.copyTo(it) }
                }
                File(rootHome, "astrbot-startup.sh").setExecutable(true, false)
                File(rootHome, "astrbot-installer-bootstrap.sh").setWritable(true, false)

                installStamp(ctx).writeText(Build.FINGERPRINT)
                _state.value = State.Stopped
                logLine("引擎安装完成")
                true
            } catch (t: Throwable) {
                // v2.15.0 诊断增强：exec 被系统拒绝（error=13 / Permission denied）
                // 几乎必然是 targetSdk≥29 的旧安装包撞上 W^X，给出可行动的提示
                val raw = t.message ?: ""
                val msg = if (raw.contains("error=13") || raw.contains("Permission denied")) {
                    "$raw —— W^X 拒绝执行：本安装包 targetSdk≥29 或 ROM 收紧；请安装 v2.15.0+（targetSdk 28）胖包"
                } else raw
                _state.value = State.Error("安装失败: $msg")
                false
            }
        }
    }

    // ---------- 启动 / 停止 ----------

    /**
     * v2.19.0：生成容器 resolv.conf（宿主侧）。rootfs 发行包自带的 resolv.conf 烙着
     * 构建机 systemd-resolved 桩（nameserver 127.0.0.53，proot 内无监听 → 解析必死）；
     * 启动脚本内的无条件重写是第一道防线，这里是第二道：proot -b 直接把本文件绑定
     * 到 /etc/resolv.conf，绑定层优先于 rootfs 文件，不依赖脚本执行时机。
     * DNS 优先取系统当前网络的 DNS（ConnectivityManager，覆盖仅内网 DNS 可达的
     * 载波/路由器场景），公共解析器（阿里/腾讯/114/谷歌）兑底。
     */
    private fun writeContainerResolvConf(ctx: Context) {
        val servers = mutableListOf<String>()
        runCatching {
            val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
            val lp = cm?.activeNetwork?.let { cm.getLinkProperties(it) }
            lp?.dnsServers?.forEach { dns ->
                val host = dns?.hostAddress ?: return@forEach
                if (!host.contains(':') && host !in servers) servers.add(host)
            }
        }
        listOf("223.5.5.5", "119.29.29.29", "114.114.114.114", "8.8.8.8").forEach {
            if (it !in servers) servers.add(it)
        }
        val text = buildString {
            servers.take(5).forEach { append("nameserver ").append(it).append('\n') }
            append("options timeout:2 attempts:3 rotate\n")
        }
        runCatching {
            val dir = containerEtcDir(ctx)
            if (!dir.exists()) dir.mkdirs()
            File(dir, "resolv.conf").writeText(text)
        }.onFailure { logLine("resolv.conf 写入失败: ${it.message}") }
    }

    /**
     * v2.28.0：环境伪装文件（宿主侧生成，proot -b 绑定进容器）。
     *
     * 用户 10-05 封号反馈怀疑“虚拟设备被识别”：proot 容器的 /proc 是 Android 宿主的，
     * /proc/version 直接暴露 Android 内核字符串（uname 系统调用无法伪装，但 procfs
     * 读口可以）；/proc/sys/kernel/hostname 是 glibc gethostname() 的读口。把这两处
     * 绑定成常规 Ubuntu arm64 桌面值，消除最易采样的两个容器特征。生效文件在
     * buildLaunchScript 里条件绑定（文件不存在则跳过绑定，绝不阻断启动）。
     */
    private fun writeEnvMaskFiles(ctx: Context) {
        runCatching {
            val dir = containerEtcDir(ctx)
            if (!dir.exists()) dir.mkdirs()
            File(dir, "proc-version").writeText(
                "Linux version 6.8.0-45-generic (buildd@bos01-arm64-015) " +
                    "(aarch64-linux-gnu-gcc-13 (Ubuntu 13.2.0-23ubuntu4) 13.2.0, GNU ld (GNU Binutils) 2.42) " +
                    "#45-Ubuntu SMP PREEMPT_DYNAMIC Mon Jul 15 14:21:45 UTC 2024\n"
            )
            File(dir, "kernel-osrelease").writeText("6.8.0-45-generic\n")
            File(dir, "kernel-hostname").writeText("bandqq-desktop\n")
        }.onFailure { logLine("环境伪装文件写入失败: ${it.message}") }
    }

    /**
     * 组装 proot 启动命令并 exec。命令逐参数对齐上游 scripts.dart（仅去掉 sdcard
     * 绑定：引擎全部数据自包含于容器，不需要手机存储，减少权限面）。
     * v2.19.0：新增 -b resolv.conf 绑定（Termux proot-distro 同款做法，见上）。
     * v2.28.0：新增 /proc/version、/proc/sys/kernel/osrelease、/proc/sys/kernel/hostname
     * 伪装绑定（细路径绑定在宽绑定 /proc 之后声明，proot 最长路径优先遮蔽——
     * Termux proot-distro 同款模式），消除 Android 宿主内核/主机名直接暴露。
     */
    private fun buildLaunchScript(ctx: Context, bin: File, tmp: File, ubuntu: File, astrbotEnabled: Boolean): String {
        val tz = java.util.TimeZone.getDefault().id
        val resolv = File(containerEtcDir(ctx), "resolv.conf")
        val procVersion = File(containerEtcDir(ctx), "proc-version")
        val osrelease = File(containerEtcDir(ctx), "kernel-osrelease")
        val hostname = File(containerEtcDir(ctx), "kernel-hostname")
        return buildString {
            append("exec ").append(bin.absolutePath).append("/proot ")
            append("-0 ")
            append("-r '").append(ubuntu.absolutePath).append("' ")
            append("--link2symlink ")
            append("-b /dev -b /proc -b /sys -b /dev/pts ")
            append("-b '").append(tmp.absolutePath).append("' ")
            append("-b '").append(tmp.absolutePath).append("':/dev/shm ")
            if (resolv.exists()) {
                append("-b '").append(resolv.absolutePath).append("':/etc/resolv.conf ")
            }
            // v2.28.0：环境伪装绑定（文件不存在则跳过，绝不阻断启动）
            if (procVersion.exists()) append("-b '").append(procVersion.absolutePath).append("':/proc/version ")
            if (osrelease.exists()) append("-b '").append(osrelease.absolutePath).append("':/proc/sys/kernel/osrelease ")
            if (hostname.exists()) append("-b '").append(hostname.absolutePath).append("':/proc/sys/kernel/hostname ")
            append("-b /proc/self/fd:/dev/fd ")
            append("-w /root ")
            append("/usr/bin/env -i ")
            append("HOME=/root TERM=xterm-256color LANG=C.UTF-8 TZ=").append(tz).append(" ")
            // v2.22.0：「启动 AstrBot 机器人」开关透传进容器（0 = 仅装/启 NapCat，不装不启 AstrBot）
            append("ASTRBOT_ENABLE=").append(if (astrbotEnabled) "1" else "0").append(" ")
            append("TMPDIR=/tmp ")
            append("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin ")
            append("/bin/bash -lc 'bash /root/astrbot-startup.sh 2>&1'")
        }
    }

    /**
     * v2.18.0：APK 升级后容器内脚本可能仍是旧版——历史实现只在安装流程③写入一次，
     * 而 isInstalled=true 时安装流程整体跳过。用户从 v2.16.0 升级后容器保留，
     * v2.17.0 的「启动自愈」版脚本从未落到设备，表现为：启动即报 missing dependency
     * 清单 + 立即 MANUAL_ENV（旧脚本无自愈分支，且提示里还有已废弃的 Environment Manager）。
     * 修法：每次启动前用 assets 重写三个脚本/配置；内容一致则跳过写盘；
     * 保留旧脚本的 REINSTALL_PLUGINS_FLAG=1 标记（脚本自身用 sed 消费后清零）。
     */
    private fun refreshContainerScripts(ctx: Context) {
        val rootHome = File(rootfsDir(ctx), "root")
        if (!rootHome.isDirectory) return
        val keepReinstallFlag = runCatching {
            File(rootHome, "astrbot-startup.sh").takeIf { it.exists() }
                ?.bufferedReader()?.useLines { lines -> lines.any { it.trim() == "REINSTALL_PLUGINS_FLAG=1" } }
        }.getOrNull() == true
        val entries = listOf(
            "astrbot-startup.sh" to true,
            "astrbot-installer-bootstrap.sh" to false,
            "cmd_config.json" to false,
        )
        for ((asset, exec) in entries) {
            val dst = File(rootHome, asset)
            try {
                val fresh = ctx.assets.open(asset).use { it.readBytes() }
                if (!dst.exists() || !dst.readBytes().contentEquals(fresh)) {
                    dst.outputStream().use { it.write(fresh) }
                }
                if (exec) dst.setExecutable(true, false)
            } catch (t: Throwable) {
                logLine("脚本刷新失败 $asset: ${t.message}")
            }
        }
        if (keepReinstallFlag) {
            runCatching {
                val f = File(rootHome, "astrbot-startup.sh")
                val txt = f.readText()
                if (!txt.contains("REINSTALL_PLUGINS_FLAG=1")) {
                    f.writeText(txt.replaceFirst("REINSTALL_PLUGINS_FLAG=0", "REINSTALL_PLUGINS_FLAG=1"))
                }
            }
        }
        logLine("启动脚本已与 APK 资产对齐（v2.28.0 每次启动强制刷新）")
    }

    /**
     * 启动引擎：installIfNeeded → 前台服务保活 → bash（PROOT_LOADER 等环境变量）→
     * proot → 容器内 astrbot-startup.sh（首次装 AstrBot/NapCat 需联网数分钟，
     * 已装则直接拉起；脚本幂等；v2.17 环境不完整时脚本内自动补装自愈）。
     * stdout 逐行进引擎日志。
     */
    fun start(ctx: Context) {
        val appCtx = ctx.applicationContext
        if (isRunning()) {
            logLine("引擎已在运行")
            return
        }
        stopping = false
        _state.value = State.Starting
        writeEngineSessionHeader(appCtx)
        EngineService.start(appCtx)
        Thread {
            try {
                val bin = binDir(appCtx)
                val tmp = tmpDir(appCtx)
                val ubuntu = rootfsDir(appCtx)
                tmp.mkdirs()
                // v2.18.0：启动前保证引擎已安装（历史上 start 不装引擎，未安装直接拉起必败），
                // 且每次启动强制刷新容器内脚本（修 v2.17 自愈脚本在升级用户设备上从未部署的断链）
                if (!isInstalled(appCtx)) {
                    logLine("引擎未安装，先执行安装（解压 rootfs 约 62MB）…")
                    val ok = kotlinx.coroutines.runBlocking { installIfNeeded(appCtx) }
                    if (!ok) {
                        // installIfNeeded 内部已置 State.Error 与日志
                        return@Thread
                    }
                    _state.value = State.Starting
                }
                refreshContainerScripts(appCtx)
                writeContainerResolvConf(appCtx)
                writeEnvMaskFiles(appCtx)
                // v2.22.0：「启动 AstrBot 机器人」开关（关闭 = 仅 NapCat 模式）
                val astrbotEnabled = isAstrbotEnabled(appCtx)
                if (!astrbotEnabled) logLine("AstrBot 机器人开关已关闭：本次仅启动 NapCat（手环 QQ 直连），不安装/启动 AstrBot")
                val script = buildLaunchScript(appCtx, bin, tmp, ubuntu, astrbotEnabled)
                logLine("拉起容器（proot → astrbot-startup.sh）…")
                val pb = ProcessBuilder(File(bin, "bash").absolutePath, "-lc", script)
                pb.environment().apply {
                    put("PROOT_LOADER", File(bin, "loader").absolutePath)
                    put("LD_LIBRARY_PATH", bin.absolutePath)
                    put("PROOT_TMP_DIR", tmp.absolutePath)
                    put("TMPDIR", tmp.absolutePath)
                    put("HOME", homeDir(appCtx).absolutePath)
                    put("PATH", bin.absolutePath + ":" + System.getenv("PATH"))
                    put("TERMUX_PREFIX", engineRoot(appCtx).absolutePath)
                }
                pb.redirectErrorStream(true)
                val p = pb.start()
                processRef.set(p)
                // v2.20.0：逐行扫描 [STAGE:百分比:描述] 标记 → 更新 Installing 状态，
                // 驱动 AstrBot 标签页的启动进度卡；标记行照常进日志（人可读）
                // v2.24.0：stage 100 视为终态直接进 Running（仅 NapCat 模式的
                // wait_napcat_ports 探测到端口后发 stage 100；此前停在
                // "安装中：NapCat 已就绪 100%" 文案，用户误读为还在启动）
                p.inputStream.bufferedReader().forEachLine { line ->
                    logLinesOf(line).forEach { l ->
                        tryParseStage(l)?.let { (pct, desc) ->
                            val cur = _state.value
                            if (cur !is State.Error) {
                                if (pct >= 100) {
                                    _state.value = State.Running
                                } else if (cur !is State.Running) {
                                    _state.value = State.Installing(desc, pct)
                                }
                            }
                        }
                        logLine(l)
                    }
                }
                val code = p.waitFor()
                if (code == 0) {
                    logLine("引擎进程退出（正常）")
                } else {
                    logLine("引擎进程退出 exit=$code")
                    // v2.20.0：安装链中断时给出可行动的通俗错误（此前只报 exit=1，
                    // 用户不知道下一步该干嘛）
                    val cur = _state.value
                    if (!stopping && cur is State.Installing) {
                        _state.value = State.Error(
                            "自动安装/启动中断（exit=$code）。常见原因：网络不可达或存储不足；" +
                                "可点「启动引擎」重试（已下载的组件会复用，不会从头装）；" +
                                "若反复失败，请展开日志查看最近报错并反馈"
                        )
                    }
                }
            } catch (t: Throwable) {
                val raw = t.message ?: ""
                logLine(
                    when {
                        // v2.20.0：主动停止时杀进程会打断读取线程，属预期路径，不再以
                        // “启动异常”误导（read interrupted by close() on another thread）
                        stopping -> "引擎输出读取结束（主动停止）"
                        raw.contains("error=13") || raw.contains("Permission denied") ->
                            "启动异常: $raw —— W^X 拒绝执行（targetSdk≥29 安装包）；请安装 v2.15.0+（targetSdk 28）胖包"
                        else -> "启动异常: $raw"
                    }
                )
            } finally {
                processRef.set(null)
                if (_state.value !is State.Error) _state.value = State.Stopped
            }
        }.apply { name = "astrbot-engine-main" }.start()
        // 进程拉起即视为 Starting→Running 的过渡：真实"可连"由端口探测确认
        // v2.22.0：探测细化——AstrBot WebUI :6185 / NapCat WebUI :5099 也算就绪信号
        // （QQ 未扫码登录前 3001/3000 不监听，但引擎实际已就绪，需给用户扫码指引）
        Thread {
            repeat(360) { // 最多 30 分钟（首次自愈需下载 AstrBot 依赖+LinuxQQ，弱网放宽窗口）
                if (isRunning()) {
                    val napcatOk = AstrBotEngineProbe.portOpen(3001) || AstrBotEngineProbe.portOpen(3000)
                    val botOk = AstrBotEngineProbe.portOpen(6185)
                    val webui = AstrBotEngineProbe.portOpen(5099)
                    if (napcatOk || botOk || webui) {
                        _state.value = State.Running
                        when {
                            napcatOk -> logLine("本机 NapCat 就绪（127.0.0.1:3001/3000 可连）")
                            botOk -> logLine("AstrBot 已就绪（WebUI :6185）；NapCat 等待 QQ 扫码登录（WebUI :5099，Token 在「密码与登录」卡复制）")
                            else -> logLine("NapCat 已启动，等待 QQ 扫码登录（WebUI http://127.0.0.1:5099，Token 在「密码与登录」卡复制）")
                        }
                        return@Thread
                    }
                }
                if (!isRunning()) return@Thread
                Thread.sleep(5000)
            }
            if (isRunning()) _state.value = State.Running // 容器在跑，端口探测交给 UI 手动确认
        }.apply { name = "astrbot-engine-watch" }.start()
    }

    /**
     * 停止引擎：杀根进程 + 容器内残留（proot 不隔离 PID，host pkill 可命中）。
     * v2.25.0：停止链优雅化——SIGKILL 强杀 QQ 是「NapCat 启动一次后，二次启动必崩
     * （Worker SIGSEGV 退出码 11 ×3 → 主进程退出 → :3001/:3000 永不监听）」的根因
     * （用户 10-04 第六份日志定案）：强杀让 Chromium/NT 数据目录来不及落盘变脏，
     * 下次快速登录路径读到脏数据即段错误。修法：
     * ①第一轮 SIGTERM 只发容器内业务进程（QQ/Xvfb/napcat 辅助进程），QQ 收到
     *   SIGTERM 会优雅退出并落盘，等待 4 秒；
     * ②第二轮 SIGKILL 收尾（含 proot/启动脚本壳），残留兜底清零。
     * 脚本侧配套：start_napcat 启动前清理 Singleton/GPU 缓存等脏状态 + 看门狗崩溃自愈。
     */
    fun stop(ctx: Context) {
        val appCtx = ctx.applicationContext
        stopping = true
        EngineService.stop(appCtx)
        processRef.getAndSet(null)?.let { p -> runCatching { p.destroy() } }
        Thread {
            try {
                val busybox = File(binDir(appCtx), "busybox")
                if (busybox.canExecute()) {
                    fun pkill(args: List<String>) {
                        val p = ProcessBuilder(
                            listOf(busybox.absolutePath, "pkill") + args
                        ).redirectErrorStream(true).start()
                        p.waitFor()
                    }
                    // 第一轮：优雅终止业务进程（先业务后壳；proot 是 ptrace 跟踪者，
                    // 先杀壳会破坏容器内进程的优雅退出路径，故仅第二轮才动壳）。
                    // v2.26.0：补 "/opt/QQ/qq"——崩溃残留的子进程（--type=）旧模式杀不到
                    for (pat in listOf("/opt/QQ/qq", "qq --no-sandbox", "Xvfb", "napcat", "AstrBot/main.py")) {
                        pkill(listOf("-TERM", "-f", pat))
                    }
                    // 给 Chromium 优雅退出落盘的窗口（脏数据 = 二次启动 Worker SIGSEGV 的根因）；
                    // v2.26.0：4s→8s，QQ NT 数据库大时 4s 经常不够完整落盘
                    Thread.sleep(8000)
                    // 第二轮：KILL 收尾兜底（含容器壳 proot/启动脚本）
                    for (pat in listOf("/opt/QQ/qq", "qq --no-sandbox", "Xvfb", "napcat", "AstrBot/main.py", "proot", "astrbot-startup")) {
                        pkill(listOf("-9", "-f", pat))
                    }
                }
            } catch (_: Throwable) {
            }
            _state.value = State.Stopped
            logLine("引擎已停止")
        }.start()
    }

    /** 追加一段外部探测结果进日志（UI 检测本机 NapCat 按钮复用） */
    fun note(msg: String) = logLine(msg)

    // ---------- v2.24.0：真实连接驱动的状态联动 + 主页日志转发 ----------

    /**
     * App 侧可注入的日志 sink（引擎模块不依赖 app，反向由 app 在启动时挂入）。
     * SyncService 启动后把引擎/ NapCat 行同步进主页日志面板（LogBus），修
     * 「log 里没有 NapCat 的 log」——[NAPCAT] 前缀的控制台 tail 行会随之出现在主页。
     */
    @Volatile
    var logSink: ((line: String) -> Unit)? = null

    /**
     * OneBotClient WS 连接成功时由 App 侧调用（NapCat 真就绪的铁证——比端口探测更权威）。
     * 若引擎仍在 Starting/Installing，立即推进到 Running：修「NapCat 启动完成还在显示启动」
     * ——端口探测线程虽有 30 分钟窗口，但探测间隔/时序落后于真实连接，状态卡会滞留在
     * "92% 启动中"直到下一次探测；且手动探测的"未就绪"提示文案会一直挂屏不刷新。
     */
    fun onNapcatConnected() {
        val cur = _state.value
        if (cur is State.Starting || cur is State.Installing) {
            _state.value = State.Running
            logLine("OneBot WS 已连接：本机 NapCat 就绪（状态已自动更新）")
        }
    }

    // ---------- v2.22.0：「启动 AstrBot 机器人」开关（仅 NapCat 模式） ----------

    private const val PREFS = "bandqq_astrbot"
    private const val KEY_ASTRBOT_ENABLE = "engine_start_astrbot"

    /** AstrBot 机器人是否随引擎启动/安装（默认开；关闭后引擎仅运行 NapCat） */
    fun isAstrbotEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ASTRBOT_ENABLE, true)

    fun setAstrbotEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ASTRBOT_ENABLE, enabled).apply()
        logLine(if (enabled) "AstrBot 机器人开关已开启：下次启动引擎生效" else "AstrBot 机器人开关已关闭：下次启动引擎仅运行 NapCat")
    }
}

/**
 * v2.22.0：本机引擎账号信息自动读取（用户需求："搞个自动读取2个程序的密码给复制"）。
 *  - AstrBot WebUI：解析引擎日志落盘（files/logs/engine-*.log）里 AstrBot 首次启动打印的
 *    「Initial username/password」（最新一份优先；用户若在控制台改过密码，以改后为准）
 *  - NapCat WebUI Token：读容器内 /root/napcat/config/webui.json（astrbot-startup.sh 固定写入，
 *    未登录 QQ 时用该 Token 打开 WebUI :5099 扫码）
 * v2.23.0：新增 QQ 账号提取（用户反馈"怎么没有提取账号"）——
 *  - 首选：NapCat 登录后生成的按账号配置文件名 onebot11_<uin>.json 直接提取 uin；
 *  - 兕底：napcat-console.log 里登录成功行（账号/UIN/logged in 等常见格式）。
 */
object AstrBotSecrets {
    data class Secrets(
        val webuiUser: String?,
        val webuiPassword: String?,
        val napcatToken: String?,
        val qqAccounts: List<String> = emptyList(),
        val antiDetect: AntiDetectState = AntiDetectState.Unknown,
    )

    /**
     * v2.28.0 反检测真实状态（密码卡展示用）。判据优先级：
     * ① root/napcat/.bypass_disabled 在 → Off（崩溃自愈禁用）
     * ② root/napcat/.bypass_nohook 在 → Partial（hook 单项规避，五项开启）
     * ③ napcat-console.log 近期出现「Bypass已通过环境变量禁用」→ Off（运行时真相：
     *    配置层写了全开但实际未生效——用户 10-05 封号时正是这种状态）
     * ④ 读 napcat_<uin>.json / napcat.json 的 bypass 块 → 六项全 true=Full，
     *    部分开启=Partial；无文件/解析失败=Unknown（未安装/未写入）
     */
    enum class AntiDetectState(val label: String) {
        Full("反检测：6 项全开（配置层持久生效，重启不丢）"),
        Partial("反检测：5 项开启（hook 因崩溃规避，其余全开）"),
        Off("反检测：运行时已禁用（bypass 钩子在本环境段错误，崩溃自愈已关闭；环境/行为层防护生效中）"),
        Unknown("反检测：待引擎启动后自动写入（当前未检测到配置）"),
    }

    private val userRe = Regex("Initial username:\\s*(\\S+)")
    private val passRe = Regex("Initial password:\\s*(\\S+)")
    private val tokenRe = Regex("\"token\"\\s*:\\s*\"([^\"]+)\"")
    private val uinFileRe = Regex("onebot11_(\\d{5,12})\\.json")
    private val uinLogRes = listOf(
        Regex("(?:账号|帐号|QQ号|QQ 号|uin|UIN|self_id)[=:：\\s]+(\\d{5,12})"),
        Regex("logged in[\\s\\S]{0,40}?((?<!\\d)\\d{5,12}(?!\\d))", RegexOption.IGNORE_CASE),
    )

    fun read(ctx: Context): Secrets {
        var user: String? = null
        var pass: String? = null
        runCatching {
            val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            val dir = File(base, "logs")
            val files = dir.listFiles { f -> f.name.startsWith("engine-") }
                ?.sortedByDescending { it.name } ?: emptyList()
            for (f in files) {
                if (user != null && pass != null) break
                runCatching {
                    val lines = f.useLines { it.toList() }
                    for (i in lines.indices.reversed()) {
                        if (user == null) userRe.find(lines[i])?.let { user = it.groupValues[1] }
                        if (pass == null) passRe.find(lines[i])?.let { pass = it.groupValues[1] }
                    }
                }
            }
        }
        var token: String? = null
        runCatching {
            val f = File(EngineManager.rootfsDir(ctx), "root/napcat/config/webui.json")
            if (f.exists()) tokenRe.find(f.readText())?.let { token = it.groupValues[1] }
        }
        return Secrets(user, pass, token, readQqAccounts(ctx), readAntiDetect(ctx))
    }

    /** v2.28.0：反检测真实状态（标记文件 > 控制台运行时真相 > 配置内容，与壳脚本分级阶梯严格一致） */
    private fun readAntiDetect(ctx: Context): AntiDetectState {
        val napcatDir = File(EngineManager.rootfsDir(ctx), "root/napcat")
        if (File(napcatDir, ".bypass_disabled").exists()) return AntiDetectState.Off
        if (File(napcatDir, ".bypass_nohook").exists()) return AntiDetectState.Partial
        // 运行时真相：控制台近期出现过「Bypass已通过环境变量禁用」→ 配置层全开也是假象
        // （本环境全开必崩，崩溃自愈会禁用；用户 10-05 封号时即此状态）
        runCatching {
            val console = File(napcatDir, "napcat-console.log")
            if (console.exists()) {
                val tail = console.useLines { lines -> lines.toList().takeLast(200) }
                if (tail.any { it.contains("Bypass已通过环境变量禁用") }) return AntiDetectState.Off
            }
        }
        val cfgDir = File(napcatDir, "config")
        if (!cfgDir.isDirectory) return AntiDetectState.Unknown
        // 登录后 per-uin 优先（与壳脚本读取规则一致），无则全局兜底
        val f = cfgDir.listFiles { it.isFile }?.filter { it.name == "napcat.json" || (it.name.startsWith("napcat_") && it.name.endsWith(".json")) }
            ?.sortedByDescending { it.name.startsWith("napcat_") }
            ?.firstOrNull() ?: return AntiDetectState.Unknown
        val content = runCatching { f.readText() }.getOrNull() ?: return AntiDetectState.Unknown
        return runCatching {
            // engine 模块无 Gson 依赖，用 Android 框架自带 org.json（NapCat 配置即标准 JSON）
            val obj = org.json.JSONObject(content)
            val bp = obj.optJSONObject("bypass")
            val keys = listOf("hook", "window", "module", "process", "container", "js")
            val flags = keys.map { bp?.optBoolean(it, false) == true }
            when {
                flags.all { it } -> AntiDetectState.Full
                flags.any { it } -> AntiDetectState.Partial
                else -> AntiDetectState.Unknown
            }
        }.getOrDefault(AntiDetectState.Unknown)
    }

    /** QQ 账号列表（登录过的账号从配置文件名提取；兑底扫 NapCat 控制台日志） */
    private fun readQqAccounts(ctx: Context): List<String> {
        val accounts = mutableListOf<String>()
        runCatching {
            val cfgDir = File(EngineManager.rootfsDir(ctx), "root/napcat/config")
            cfgDir.listFiles { f -> f.name.startsWith("onebot11_") && f.name.endsWith(".json") }
                ?.sortedByDescending { it.lastModified() }
                ?.forEach { f ->
                    uinFileRe.find(f.name)?.groupValues?.get(1)?.let { uin ->
                        if (uin !in accounts) accounts.add(uin)
                    }
                }
        }
        if (accounts.isEmpty()) runCatching {
            val f = File(EngineManager.rootfsDir(ctx), "root/napcat/napcat-console.log")
            if (f.exists()) {
                val lines = f.useLines { it.toList() }
                outer@ for (i in lines.indices.reversed()) {
                    for (re in uinLogRes) {
                        re.find(lines[i])?.groupValues?.get(1)?.let { uin ->
                            if (uin !in accounts) accounts.add(uin)
                            if (accounts.size >= 3) return@runCatching
                            break@outer
                        }
                    }
                }
            }
        }
        return accounts
    }
}

/** 端口探测（独立小对象，避免 engine 模块依赖 app 的 AstrBotBridge） */
object AstrBotEngineProbe {
    /** 本机 NapCat 是否就绪（3001/3000 任一可连，2s 超时） */
    fun portsReady(): Boolean = portOpen(3001) || portOpen(3000)

    fun portOpen(port: Int): Boolean = runCatching {
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress("127.0.0.1", port), 2000)
        }
        true
    }.getOrDefault(false)
}
