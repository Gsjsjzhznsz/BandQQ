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

    // ---------- 目录布局（与上游 RuntimeEnvir 对齐） ----------

    fun engineRoot(ctx: Context): File = File(ctx.filesDir, "engine")
    fun binDir(ctx: Context): File = File(engineRoot(ctx), "bin")
    fun tmpDir(ctx: Context): File = File(engineRoot(ctx), "tmp")
    fun homeDir(ctx: Context): File = File(engineRoot(ctx), "home")
    fun rootfsDir(ctx: Context): File =
        File(engineRoot(ctx), "var/lib/proot-distro/installed-rootfs/ubuntu")

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

    // ---------- 日志（环形缓冲，设置卡片展示最近 N 行） ----------

    private val logBuf = ArrayDeque<String>()
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log

    private fun logLine(s: String) {
        synchronized(logBuf) {
            logBuf.addLast(s)
            while (logBuf.size > 160) logBuf.removeFirst()
        }
        _log.value = synchronized(logBuf) { logBuf.toList() }
        android.util.Log.i("AstrBotEngine", s)
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

                // ② 解压 rootfs（busybox xz | tar 管道，单进程 sh -c）
                val rootfs = rootfsDir(ctx)
                rootfs.mkdirs()
                _state.value = State.Installing("解压 Ubuntu rootfs（首次约 3~10 秒）", 20)
                val tar = File(ctx.cacheDir, ROOTFS_ASSET)
                // assets 无法直接给 busybox 文件路径，先落 cache（62MB）
                if (!tar.exists() || tar.length() == 0L) {
                    ctx.assets.open(ROOTFS_ASSET).use { input ->
                        tar.outputStream().use { input.copyTo(it) }
                    }
                }
                logLine("rootfs 包就绪（${tar.length() / 1024 / 1024}MB），开始解压…")
                val extractor = ProcessBuilder(
                    File(bin, "busybox").absolutePath, "sh", "-c",
                    "exec ${File(bin, "busybox").absolutePath} xz -d -c '${tar.absolutePath}' | " +
                        "exec ${File(bin, "busybox").absolutePath} tar -x -C '${rootfs.absolutePath}'"
                ).redirectErrorStream(true).start()
                val tail = StringBuilder()
                extractor.inputStream.bufferedReader().forEachLine {
                    if (tail.length < 2000) tail.appendLine(it)
                }
                val code = extractor.waitFor()
                if (code != 0) {
                    logLine("解压失败 exit=$code：${tail.takeLast(500)}")
                    throw IllegalStateException("rootfs 解压失败 exit=$code")
                }
                if (!File(rootfs, "bin/bash").exists()) {
                    throw IllegalStateException("rootfs 解压后缺 bin/bash，包可能损坏")
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
                _state.value = State.Error("安装失败: ${t.message}")
                false
            }
        }
    }

    // ---------- 启动 / 停止 ----------

    /**
     * 组装 proot 启动命令并 exec。命令逐参数对齐上游 scripts.dart（仅去掉 sdcard
     * 绑定：引擎全部数据自包含于容器，不需要手机存储，减少权限面）。
     */
    private fun buildLaunchScript(ctx: Context, bin: File, tmp: File, ubuntu: File): String {
        val tz = java.util.TimeZone.getDefault().id
        return buildString {
            append("exec ").append(bin.absolutePath).append("/proot ")
            append("-0 ")
            append("-r '").append(ubuntu.absolutePath).append("' ")
            append("--link2symlink ")
            append("-b /dev -b /proc -b /sys -b /dev/pts ")
            append("-b '").append(tmp.absolutePath).append("' ")
            append("-b '").append(tmp.absolutePath).append("':/dev/shm ")
            append("-b /proc/self/fd:/dev/fd ")
            append("-b /proc/self/fd/0:/dev/stdin ")
            append("-b /proc/self/fd/1:/dev/stdout ")
            append("-b /proc/self/fd/2:/dev/stderr ")
            append("-w /root ")
            append("/usr/bin/env -i ")
            append("HOME=/root TERM=xterm-256color LANG=C.UTF-8 TZ=").append(tz).append(" ")
            append("TMPDIR=/tmp ")
            append("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin ")
            append("/bin/bash -lc 'bash /root/astrbot-startup.sh 2>&1'")
        }
    }

    /**
     * 启动引擎：installIfNeeded → 前台服务保活 → bash（PROOT_LOADER 等环境变量）→
     * proot → 容器内 astrbot-startup.sh（首次装 AstrBot/NapCat 需联网数分钟，
     * 已装则直接拉起；脚本幂等）。stdout 逐行进引擎日志。
     */
    fun start(ctx: Context) {
        val appCtx = ctx.applicationContext
        if (isRunning()) {
            logLine("引擎已在运行")
            return
        }
        _state.value = State.Starting
        EngineService.start(appCtx)
        Thread {
            try {
                val bin = binDir(appCtx)
                val tmp = tmpDir(appCtx)
                val ubuntu = rootfsDir(appCtx)
                tmp.mkdirs()
                val script = buildLaunchScript(appCtx, bin, tmp, ubuntu)
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
                p.inputStream.bufferedReader().forEachLine { logLinesOf(it).forEach(::logLine) }
                val code = p.waitFor()
                if (code == 0) {
                    logLine("引擎进程退出（正常）")
                } else {
                    logLine("引擎进程退出 exit=$code")
                }
            } catch (t: Throwable) {
                logLine("启动异常: ${t.message}")
            } finally {
                processRef.set(null)
                if (_state.value !is State.Error) _state.value = State.Stopped
            }
        }.apply { name = "astrbot-engine-main" }.start()
        // 进程拉起即视为 Starting→Running 的过渡：真实"可连"由端口探测确认
        Thread {
            repeat(120) { // 最多 10 分钟（首次安装 AstrBot 需要下载）
                if (isRunning() && AstrBotEngineProbe.portsReady()) {
                    _state.value = State.Running
                    logLine("本机 NapCat 就绪（127.0.0.1:3001/3000 可连）")
                    return@Thread
                }
                if (!isRunning()) return@Thread
                Thread.sleep(5000)
            }
            if (isRunning()) _state.value = State.Running // 容器在跑，端口探测交给 UI 手动确认
        }.apply { name = "astrbot-engine-watch" }.start()
    }

    /** 停止引擎：杀根进程 + 容器内残留（proot 不隔离 PID，host pkill 可命中） */
    fun stop(ctx: Context) {
        val appCtx = ctx.applicationContext
        EngineService.stop(appCtx)
        processRef.getAndSet(null)?.let { p -> runCatching { p.destroy() } }
        Thread {
            try {
                val busybox = File(binDir(appCtx), "busybox")
                if (busybox.canExecute()) {
                    // proot/启动脚本/容器内 python(node 进程 cmdline 保持容器路径) 三类兜底
                    for (pat in listOf("proot", "astrbot-startup", "napcat", "AstrBot/main.py")) {
                        val p = ProcessBuilder(
                            busybox.absolutePath, "pkill", "-9", "-f", pat
                        ).redirectErrorStream(true).start()
                        p.waitFor()
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
