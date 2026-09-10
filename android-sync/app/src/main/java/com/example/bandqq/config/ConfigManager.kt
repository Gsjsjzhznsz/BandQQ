package com.example.bandqq.config

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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
    val themeMode: Int = 0,
    val navGlass: Boolean = true
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
            navGlass = prefs[Keys.NAV_GLASS] ?: default.navGlass
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
            prefs[Keys.NAV_GLASS] = config.navGlass
        }
        ConfigHolder.config = config
    }

    /** 主题模式 Flow（设置页与 MainActivity 共享，写入即全局重组） */
    fun observeThemeMode(): Flow<Int> = context.dataStore.data.map { it[Keys.THEME_MODE] ?: 0 }

    suspend fun setThemeMode(mode: Int) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode }
        ConfigHolder.config = ConfigHolder.config.copy(themeMode = mode)
    }

    /** 液态玻璃悬浮栏开关 Flow */
    fun observeNavGlass(): Flow<Boolean> = context.dataStore.data.map { it[Keys.NAV_GLASS] ?: true }

    suspend fun setNavGlass(enabled: Boolean) {
        context.dataStore.edit { it[Keys.NAV_GLASS] = enabled }
        ConfigHolder.config = ConfigHolder.config.copy(navGlass = enabled)
    }
}