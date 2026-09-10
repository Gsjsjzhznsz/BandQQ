package com.example.bandqq.sync

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍保活锚点服务（v2.4.6）：
 * - 唯一职责是「被系统绑定」：无障碍服务处于开启状态时，ROM 的省电策略与一键清理
 *   通常会跳过该应用（系统对持有活跃无障碍服务的进程极其宽容），是各品牌通用、
 *   无需 ROOT 的保活手段之一；
 * - 不读取任何屏幕内容、不监听任何事件（见 res/xml/keep_alive_accessibility_config.xml
 *   的 canRetrieveWindowContent=false），只在系统设置的无障碍列表中占一个可开关的位置；
 * - 状态检测与引导入口在保活向导「无障碍保活」行（KeepAliveScreen）。
 */
class KeepAliveAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    companion object {
        private fun componentName(context: Context) =
            ComponentName(context, KeepAliveAccessibilityService::class.java)

        /**
         * 本服务是否已在系统无障碍设置中启用。
         * 直接读 Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES（读取该字段不需要任何权限），
         * 与系统设置页判定口径一致；ON_RESUME 实时刷新即可感知开关变化。
         */
        fun isEnabled(context: Context): Boolean = runCatching {
            val expected = componentName(context)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            enabled.split(':').any { raw ->
                val cn = ComponentName.unflattenFromString(raw)
                (cn != null && cn.packageName == expected.packageName && cn.className == expected.className) ||
                    raw.equals(expected.flattenToString(), ignoreCase = true)
            }
        }.getOrDefault(false)

        /** 干扰检查用：从已启用列表中排除本应用的保活服务后的数量 */
        fun enabledForeignServiceCount(context: Context): Int = runCatching {
            val expected = componentName(context)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return 0
            enabled.split(':').count { raw ->
                val cn = ComponentName.unflattenFromString(raw)
                val isOurs = cn != null && cn.packageName == expected.packageName
                !isOurs
            }
        }.getOrDefault(0)
    }
}
