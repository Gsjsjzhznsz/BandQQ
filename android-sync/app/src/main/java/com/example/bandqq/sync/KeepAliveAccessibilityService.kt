package com.example.bandqq.sync

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍保活服务（用户明确要求的保活方案）。
 *
 * 原理：系统对无障碍服务的存活优先级远高于普通前台服务，
 * 用户在系统设置中启用后，SyncService 被杀后可由本服务上下文重新拉起，
 * 且本服务自身几乎不占资源（不监听任何事件、不读取窗口内容）。
 *
 * 隐私：canRetrieveWindowContent=false，不拦截任何用户操作。
 */
class KeepAliveAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var running: Boolean = false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        // 服务连通时尝试拉起同步服务（系统重启/被杀后恢复）
        try {
            val app = applicationContext
            SyncService.start(app)
        } catch (_: Throwable) {
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 刻意不处理任何事件：仅利用系统的高优先级保活
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        running = false
        return super.onUnbind(intent)
    }
}
