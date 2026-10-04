package com.bandqq.sync.sync

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.IBinder
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍保活：不监听内容、不读取屏幕（canRetrieveWindowContent=false）。
 * 系统拉起本服务时重启 SyncService（前台服务）。
 */
class KeepAliveAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i("KeepAlive", "accessibility keepalive connected -> start SyncService")
        try {
            startForegroundService(Intent(this, SyncService::class.java))
        } catch (_: Exception) {}
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}
}
