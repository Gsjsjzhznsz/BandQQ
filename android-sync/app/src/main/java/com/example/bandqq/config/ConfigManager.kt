package com.example.bandqq.config

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "sync_config")

data class EndpointConfig(
    val wsUrl: String,
    val wsToken: String,
    val httpUrl: String,
    val httpToken: String
)

/** 默认快捷回复（用户未自定义时使用） */
val DEFAULT_QUICK_REPLIES = listOf("收到", "好的", "稍后回复", "在忙哦", "谢谢", "OK")

data class AppConfig(
    val endpoint: EndpointConfig = EndpointConfig("ws://127.0.0.1:3001", "", "http://127.0.0.1:3000", ""),
    val quickReplies: List<String> = DEFAULT_QUICK_REPLIES,
    val webuiUrl: String = "",
    // ===== 外观设置（对齐 KernelSU manager Appearance/ColorPalette）=====
    val themeMode: Int = 0,            // 0跟随系统 1浅色 2深色 3-5 同名 Monet 档
    val keyColor: Int = 0,             // 0=默认品牌蓝，非 0 为 ARGB 种子色
    val enableBlur: Boolean = true,    // 顶栏和底栏的模糊效果（Android 13+）
    val enableFloatingBottomBar: Boolean = true, // Apple 风格悬浮底栏
    val navGlass: Boolean = true,      // 悬浮底栏的液态玻璃效果（二级选项）
    val enableNavigationBadge: Boolean = true,   // 导航栏未读角标
    val enablePredictiveBack: Boolean = true,    // 预测性返回手势（Android 14+，运行时动态开关）
    val pageScale: Float = 1.0f,       // 界面缩放 0.8 ~ 1.1
    val motionSpeed: Float = 1.0f,     // 动画速度 0.5 ~ 2.0（越大越快）
    val motionStagger: Int = 100,      // 列表级联入场的逐项间隔 0 ~ 200ms
    // ===== 消息推送策略（全部在手机端判断，手环零感知零计算，v2.4.7）=====
    val dndEnabled: Boolean = false,   // 勿扰时段开关：时段内新消息只入历史不推手环
    val dndStart: String = "23:00",    // 勿扰开始（HH:mm，支持跨零点）
    val dndEnd: String = "07:00",      // 勿扰结束（HH:mm）
    val groupPushMode: Int = 0,        // 群聊推送：0=全部 1=仅@我(含@全体) 2=不推送
    val emojiNative: Boolean = true,   // v2.6.0 表情：true=QQ表情/emoji 映射为原生 emoji 透传；false=降级 [表情] 占位（手环字形缺失时）
    // ===== 快应用自动拉起（v2.7.0）：快应用未打开时收到新消息延迟拉起同步展示 =====
    val autoLaunchEnabled: Boolean = false, // 默认关：拉起属主动行为，由用户在设置专区显式开启
    val autoLaunchDelaySec: Int = 10,       // 收到消息到拉起的延迟（3~120s，设置页档位可选）
    val autoLaunchVibrate: Int = 1,         // v2.8.0 拉起后手环震动提示：0=不震 1=短震×2 2=长震
    // ===== 双端互通设置（v2.8.0）：RPK 设置页可改并回传，手机端可改并下发 =====
    val bandMsgVibrate: Boolean = true      // 新消息手环振动（经 settings_state 帧同步到手环）
)

object ConfigHolder {
    var config: AppConfig = AppConfig()
}

class ConfigManager(private val context: Context) {

    private object Keys {
        val WS = stringPreferencesKey("ws_url")
        val WS_TOKEN = stringPreferencesKey("ws_token")
        val HTTP = stringPreferencesKey("http_url")
        val HTTP_TOKEN = stringPreferencesKey("http_token")
        val QUICK_REPLIES = stringPreferencesKey("quick_replies")
        val WEBUI_URL = stringPreferencesKey("webui_url")
        val THEME_MODE = intPreferencesKey("theme_mode")
        val NAV_GLASS = booleanPreferencesKey("nav_glass")
        val KEY_COLOR = intPreferencesKey("key_color")
        val ENABLE_BLUR = booleanPreferencesKey("enable_blur")
        val ENABLE_FLOATING_BOTTOM_BAR = booleanPreferencesKey("enable_floating_bottom_bar")
        val ENABLE_NAV_BADGE = booleanPreferencesKey("enable_nav_badge")
        val ENABLE_PREDICTIVE_BACK = booleanPreferencesKey("enable_predictive_back")
        val PAGE_SCALE = floatPreferencesKey("page_scale")
        val MOTION_SPEED = floatPreferencesKey("motion_speed")
        val MOTION_STAGGER = intPreferencesKey("motion_stagger")
        val DND_ENABLED = booleanPreferencesKey("dnd_enabled")
        val DND_START = stringPreferencesKey("dnd_start")
        val DND_END = stringPreferencesKey("dnd_end")
        val GROUP_PUSH_MODE = intPreferencesKey("group_push_mode")
        val EMOJI_NATIVE = booleanPreferencesKey("emoji_native")
        val AUTO_LAUNCH_ENABLED = booleanPreferencesKey("auto_launch_enabled")
        val AUTO_LAUNCH_DELAY = intPreferencesKey("auto_launch_delay_sec")
        val AUTO_LAUNCH_VIBRATE = intPreferencesKey("auto_launch_vibrate")
        val BAND_MSG_VIBRATE = booleanPreferencesKey("band_msg_vibrate")
    }

    suspend fun load(): AppConfig {
        val prefs = context.dataStore.data.first()
        val default = AppConfig()
        val qrRaw = prefs[Keys.QUICK_REPLIES]
        val quickReplies = if (qrRaw.isNullOrBlank()) default.quickReplies else qrRaw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val cfg = AppConfig(
            endpoint = EndpointConfig(
                wsUrl = prefs[Keys.WS] ?: default.endpoint.wsUrl,
                wsToken = prefs[Keys.WS_TOKEN] ?: "",
                httpUrl = prefs[Keys.HTTP] ?: default.endpoint.httpUrl,
                httpToken = prefs[Keys.HTTP_TOKEN] ?: ""
            ),
            quickReplies = quickReplies,
            webuiUrl = prefs[Keys.WEBUI_URL] ?: "",
            themeMode = prefs[Keys.THEME_MODE] ?: default.themeMode,
            keyColor = prefs[Keys.KEY_COLOR] ?: default.keyColor,
            enableBlur = prefs[Keys.ENABLE_BLUR] ?: default.enableBlur,
            enableFloatingBottomBar = prefs[Keys.ENABLE_FLOATING_BOTTOM_BAR] ?: default.enableFloatingBottomBar,
            navGlass = prefs[Keys.NAV_GLASS] ?: default.navGlass,
            enableNavigationBadge = prefs[Keys.ENABLE_NAV_BADGE] ?: default.enableNavigationBadge,
            enablePredictiveBack = prefs[Keys.ENABLE_PREDICTIVE_BACK] ?: default.enablePredictiveBack,
            pageScale = prefs[Keys.PAGE_SCALE] ?: default.pageScale,
            motionSpeed = prefs[Keys.MOTION_SPEED] ?: default.motionSpeed,
            motionStagger = prefs[Keys.MOTION_STAGGER] ?: default.motionStagger,
            emojiNative = prefs[Keys.EMOJI_NATIVE] ?: default.emojiNative,
            dndEnabled = prefs[Keys.DND_ENABLED] ?: default.dndEnabled,
            dndStart = prefs[Keys.DND_START] ?: default.dndStart,
            dndEnd = prefs[Keys.DND_END] ?: default.dndEnd,
            groupPushMode = prefs[Keys.GROUP_PUSH_MODE] ?: default.groupPushMode,
            autoLaunchEnabled = prefs[Keys.AUTO_LAUNCH_ENABLED] ?: default.autoLaunchEnabled,
            autoLaunchDelaySec = prefs[Keys.AUTO_LAUNCH_DELAY] ?: default.autoLaunchDelaySec,
            autoLaunchVibrate = prefs[Keys.AUTO_LAUNCH_VIBRATE] ?: default.autoLaunchVibrate,
            bandMsgVibrate = prefs[Keys.BAND_MSG_VIBRATE] ?: default.bandMsgVibrate
        )
        ConfigHolder.config = cfg
        return cfg
    }

    suspend fun save(config: AppConfig) {
        context.dataStore.edit { prefs ->
            prefs[Keys.WS] = config.endpoint.wsUrl
            prefs[Keys.WS_TOKEN] = config.endpoint.wsToken
            prefs[Keys.HTTP] = config.endpoint.httpUrl
            prefs[Keys.HTTP_TOKEN] = config.endpoint.httpToken
            prefs[Keys.QUICK_REPLIES] = config.quickReplies.joinToString("\n")
            prefs[Keys.WEBUI_URL] = config.webuiUrl
            prefs[Keys.THEME_MODE] = config.themeMode
            prefs[Keys.KEY_COLOR] = config.keyColor
            prefs[Keys.ENABLE_BLUR] = config.enableBlur
            prefs[Keys.ENABLE_FLOATING_BOTTOM_BAR] = config.enableFloatingBottomBar
            prefs[Keys.NAV_GLASS] = config.navGlass
            prefs[Keys.ENABLE_NAV_BADGE] = config.enableNavigationBadge
            prefs[Keys.ENABLE_PREDICTIVE_BACK] = config.enablePredictiveBack
            prefs[Keys.PAGE_SCALE] = config.pageScale
            prefs[Keys.MOTION_SPEED] = config.motionSpeed
            prefs[Keys.MOTION_STAGGER] = config.motionStagger
            prefs[Keys.EMOJI_NATIVE] = config.emojiNative
            prefs[Keys.DND_ENABLED] = config.dndEnabled
            prefs[Keys.DND_START] = config.dndStart
            prefs[Keys.DND_END] = config.dndEnd
            prefs[Keys.GROUP_PUSH_MODE] = config.groupPushMode
            prefs[Keys.AUTO_LAUNCH_ENABLED] = config.autoLaunchEnabled
            prefs[Keys.AUTO_LAUNCH_DELAY] = config.autoLaunchDelaySec
            prefs[Keys.AUTO_LAUNCH_VIBRATE] = config.autoLaunchVibrate
            prefs[Keys.BAND_MSG_VIBRATE] = config.bandMsgVibrate
        }
        ConfigHolder.config = config
    }

    // ===== 外观设置：每个开关独立 Flow，写入即全局重组（无需重启） =====

    /** 主题模式 Flow（设置页与 MainActivity 共享） */
    fun observeThemeMode(): Flow<Int> = context.dataStore.data.map { it[Keys.THEME_MODE] ?: 0 }

    suspend fun setThemeMode(mode: Int) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode }
        ConfigHolder.config = ConfigHolder.config.copy(themeMode = mode)
    }

    /** Monet 关键色（0=默认） */
    fun observeKeyColor(): Flow<Int> = context.dataStore.data.map { it[Keys.KEY_COLOR] ?: 0 }

    suspend fun setKeyColor(color: Int) {
        context.dataStore.edit { it[Keys.KEY_COLOR] = color }
        ConfigHolder.config = ConfigHolder.config.copy(keyColor = color)
    }

    /** 顶栏和底栏的模糊效果 */
    fun observeEnableBlur(): Flow<Boolean> = context.dataStore.data.map { it[Keys.ENABLE_BLUR] ?: true }

    suspend fun setEnableBlur(enabled: Boolean) {
        context.dataStore.edit { it[Keys.ENABLE_BLUR] = enabled }
        ConfigHolder.config = ConfigHolder.config.copy(enableBlur = enabled)
    }

    /** 悬浮底栏总开关 */
    fun observeFloatingBottomBar(): Flow<Boolean> =
        context.dataStore.data.map { it[Keys.ENABLE_FLOATING_BOTTOM_BAR] ?: true }

    suspend fun setFloatingBottomBar(enabled: Boolean) {
        context.dataStore.edit { it[Keys.ENABLE_FLOATING_BOTTOM_BAR] = enabled }
        ConfigHolder.config = ConfigHolder.config.copy(enableFloatingBottomBar = enabled)
    }

    /** 悬浮底栏的液态玻璃效果（二级选项） */
    fun observeNavGlass(): Flow<Boolean> = context.dataStore.data.map { it[Keys.NAV_GLASS] ?: true }

    suspend fun setNavGlass(enabled: Boolean) {
        context.dataStore.edit { it[Keys.NAV_GLASS] = enabled }
        ConfigHolder.config = ConfigHolder.config.copy(navGlass = enabled)
    }

    /** 导航栏未读角标 */
    fun observeNavigationBadge(): Flow<Boolean> =
        context.dataStore.data.map { it[Keys.ENABLE_NAV_BADGE] ?: true }

    suspend fun setNavigationBadge(enabled: Boolean) {
        context.dataStore.edit { it[Keys.ENABLE_NAV_BADGE] = enabled }
        ConfigHolder.config = ConfigHolder.config.copy(enableNavigationBadge = enabled)
    }

    /**
     * 预测性返回手势。
     * ⚠️ 默认值必须与 AppConfig.enablePredictiveBack 一致（true）：
     * Application 启动时经此 Flow 读取并反射设置系统开关，两处不一致会导致默认关闭。
     */
    fun observePredictiveBack(): Flow<Boolean> =
        context.dataStore.data.map { it[Keys.ENABLE_PREDICTIVE_BACK] ?: true }

    suspend fun setPredictiveBack(enabled: Boolean) {
        context.dataStore.edit { it[Keys.ENABLE_PREDICTIVE_BACK] = enabled }
        ConfigHolder.config = ConfigHolder.config.copy(enablePredictiveBack = enabled)
    }

    /** 界面缩放（0.8 ~ 1.1） */
    fun observePageScale(): Flow<Float> = context.dataStore.data.map { it[Keys.PAGE_SCALE] ?: 1.0f }

    suspend fun setPageScale(scale: Float) {
        context.dataStore.edit { it[Keys.PAGE_SCALE] = scale }
        ConfigHolder.config = ConfigHolder.config.copy(pageScale = scale)
    }

    /** 动画速度（0.5 ~ 2.0，越大越快） */
    fun observeMotionSpeed(): Flow<Float> = context.dataStore.data.map { it[Keys.MOTION_SPEED] ?: 1.0f }

    suspend fun setMotionSpeed(speed: Float) {
        context.dataStore.edit { it[Keys.MOTION_SPEED] = speed }
        ConfigHolder.config = ConfigHolder.config.copy(motionSpeed = speed)
    }

    /** 列表级联入场的逐项间隔（0 ~ 200ms） */
    fun observeMotionStagger(): Flow<Int> = context.dataStore.data.map { it[Keys.MOTION_STAGGER] ?: 100 }

    suspend fun setMotionStagger(staggerMs: Int) {
        context.dataStore.edit { it[Keys.MOTION_STAGGER] = staggerMs }
        ConfigHolder.config = ConfigHolder.config.copy(motionStagger = staggerMs)
    }

    // ===== 消息推送策略（手机端预算，手环零感知）=====

    fun observeDndEnabled(): Flow<Boolean> = context.dataStore.data.map { it[Keys.DND_ENABLED] ?: false }

    suspend fun setDndEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DND_ENABLED] = enabled }
        ConfigHolder.config = ConfigHolder.config.copy(dndEnabled = enabled)
    }

    fun observeDndStart(): Flow<String> = context.dataStore.data.map { it[Keys.DND_START] ?: "23:00" }

    suspend fun setDndStart(time: String) {
        context.dataStore.edit { it[Keys.DND_START] = time }
        ConfigHolder.config = ConfigHolder.config.copy(dndStart = time)
    }

    fun observeDndEnd(): Flow<String> = context.dataStore.data.map { it[Keys.DND_END] ?: "07:00" }

    suspend fun setDndEnd(time: String) {
        context.dataStore.edit { it[Keys.DND_END] = time }
        ConfigHolder.config = ConfigHolder.config.copy(dndEnd = time)
    }

    /** 群聊推送模式：0=全部 1=仅@我 2=不推送 */
    fun observeGroupPushMode(): Flow<Int> = context.dataStore.data.map { it[Keys.GROUP_PUSH_MODE] ?: 0 }

    suspend fun setGroupPushMode(mode: Int) {
        context.dataStore.edit { it[Keys.GROUP_PUSH_MODE] = mode }
        ConfigHolder.config = ConfigHolder.config.copy(groupPushMode = mode)
    }

    /** 表情原生渲染开关（v2.6.0）：手环字形缺失导致 tofu 时关闭即降级占位 */
    fun observeEmojiNative(): Flow<Boolean> = context.dataStore.data.map { it[Keys.EMOJI_NATIVE] ?: true }

    suspend fun setEmojiNative(enabled: Boolean) {
        context.dataStore.edit { it[Keys.EMOJI_NATIVE] = enabled }
        ConfigHolder.config = ConfigHolder.config.copy(emojiNative = enabled)
    }

    // ===== 快应用自动拉起（v2.7.0）=====

    fun observeAutoLaunchEnabled(): Flow<Boolean> =
        context.dataStore.data.map { it[Keys.AUTO_LAUNCH_ENABLED] ?: false }

    suspend fun setAutoLaunchEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_LAUNCH_ENABLED] = enabled }
        ConfigHolder.config = ConfigHolder.config.copy(autoLaunchEnabled = enabled)
    }

    /** 拉起延迟档位（秒） */
    fun observeAutoLaunchDelay(): Flow<Int> =
        context.dataStore.data.map { it[Keys.AUTO_LAUNCH_DELAY] ?: 10 }

    suspend fun setAutoLaunchDelay(sec: Int) {
        context.dataStore.edit { it[Keys.AUTO_LAUNCH_DELAY] = sec }
        ConfigHolder.config = ConfigHolder.config.copy(autoLaunchDelaySec = sec)
    }

    /** 拉起后手环震动档位（0=不震 1=短震×2 2=长震，v2.8.0） */
    fun observeAutoLaunchVibrate(): Flow<Int> =
        context.dataStore.data.map { it[Keys.AUTO_LAUNCH_VIBRATE] ?: 1 }

    suspend fun setAutoLaunchVibrate(mode: Int) {
        context.dataStore.edit { it[Keys.AUTO_LAUNCH_VIBRATE] = mode }
        ConfigHolder.config = ConfigHolder.config.copy(autoLaunchVibrate = mode)
    }

    // ===== 双端互通设置（v2.8.0）：手环端 settings_update 可回写，手机端改动即下发 =====

    fun observeBandMsgVibrate(): Flow<Boolean> =
        context.dataStore.data.map { it[Keys.BAND_MSG_VIBRATE] ?: true }

    suspend fun setBandMsgVibrate(enabled: Boolean) {
        context.dataStore.edit { it[Keys.BAND_MSG_VIBRATE] = enabled }
        ConfigHolder.config = ConfigHolder.config.copy(bandMsgVibrate = enabled)
    }

    /**
     * 手环端 settings_update 帧回写（v2.8.0 双端互通）。
     * 在互联 IO 线程调用，直接落盘并同步 ConfigHolder；仅在值真正变化时返回 true，
     * 供调用方决定是否回推确认帧（避免手环↔手机设置同步风暴）。
     */
    suspend fun applyBandSettings(emojiNative: Boolean?, msgVibrate: Boolean?): Boolean {
        val cur = ConfigHolder.config
        var changed = false
        if (emojiNative != null && emojiNative != cur.emojiNative) {
            context.dataStore.edit { it[Keys.EMOJI_NATIVE] = emojiNative }
            changed = true
        }
        if (msgVibrate != null && msgVibrate != cur.bandMsgVibrate) {
            context.dataStore.edit { it[Keys.BAND_MSG_VIBRATE] = msgVibrate }
            changed = true
        }
        if (changed) {
            ConfigHolder.config = ConfigHolder.config.copy(
                emojiNative = emojiNative ?: cur.emojiNative,
                bandMsgVibrate = msgVibrate ?: cur.bandMsgVibrate
            )
        }
        return changed
    }
}
