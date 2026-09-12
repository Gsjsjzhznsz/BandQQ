package com.example.bandqq

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import com.example.bandqq.config.ConfigManager
import com.example.bandqq.sync.FileLogger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * 预测性返回手势开关（KernelSU 同款实现）：
 * - manifest 不写 android:enableOnBackInvokedCallback 静态开关（写了会覆盖运行时设置，
 *   导致主题设置里的「预测性返回手势」开关无效果）；
 * - Android 14+ 用隐藏 API ApplicationInfo.setEnableOnBackInvokedCallback 按用户设置动态开关，
 *   HiddenApiBypass 负责解除 hidden API 访问限制；
 * - 切换设置后需重启应用（或重进 Activity）生效，与 KernelSU 行为一致。
 */
class BandQQApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // 全局崩溃日志落盘：设置页可查看，用户反馈崩溃时有据可查（v2.4.5）
        CrashGuard.install(this)
        // 文件日志（v2.9.1）：全量 LogBus 落盘到 Android/data/<pkg>/files/logs（免 root），
        // 并在每次进程启动时 dump 环境自检（机型/运动健康版本/权限），供远程排查互联授权问题
        FileLogger.install(this)
        FileLogger.logEnvironment(this)
        applyPredictiveBackFlag()
    }

    /** 幂等：Application.onCreate 与 MainActivity.onCreate 都可调用（温启动恢复设置） */
    fun applyPredictiveBackFlag() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val enable = runBlocking {
            runCatching { ConfigManager(this@BandQQApplication).observePredictiveBack().first() }
                .getOrDefault(true)
        }
        HiddenApiBypass.addHiddenApiExemptions(
            "Landroid/content/pm/ApplicationInfo;->setEnableOnBackInvokedCallback"
        )
        setEnableOnBackInvokedCallback(applicationInfo, enable)
    }

    companion object {
        /** 隐藏 API 不在公开 SDK 中，编译期只能反射调用（KernelSU 同款） */
        fun setEnableOnBackInvokedCallback(appInfo: ApplicationInfo, enable: Boolean) {
            runCatching {
                val method = ApplicationInfo::class.java.getDeclaredMethod(
                    "setEnableOnBackInvokedCallback", Boolean::class.javaPrimitiveType,
                )
                method.isAccessible = true
                method.invoke(appInfo, enable)
            }
        }
    }
}
