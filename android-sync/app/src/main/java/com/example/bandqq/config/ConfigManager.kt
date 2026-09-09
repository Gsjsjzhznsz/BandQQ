package com.example.bandqq.config

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore(name = "sync_config")

data class EndpointConfig(
    /** 账号显示名（多账号管理用，如「小号 SnowLuma」）；单账号模式下为空 */
    val name: String = "",
    val wsUrl: String,
    val wsToken: String,
    val httpUrl: String,
    val httpToken: String
)

/**
 * v1.2.0 T7 多账号（借鉴 Stapxs-QQ-Lite-X 的多 profile 思路）：
 * accounts 保存全部已配置的 OneBot 端点，activeIndex 指向当前生效账号；
 * endpoint 始终等于 accounts[activeIndex]（加载时已解析），下游代码零改动。
 */
data class AppConfig(
    val endpoint: EndpointConfig = EndpointConfig(
        name = "", wsUrl = "ws://127.0.0.1:3001", wsToken = "",
        httpUrl = "http://127.0.0.1:3000", httpToken = ""
    ),
    val accounts: List<EndpointConfig> = emptyList(),
    val activeIndex: Int = 0
)

object ConfigHolder {
    var config: AppConfig = AppConfig()
}

class ConfigManager(private val context: Context) {

    private object Keys {
        // 兼容字段：旧版单账号配置仍保留一份，降级安装旧版也能用
        val WS = stringPreferencesKey("ws_url")
        val WS_TOKEN = stringPreferencesKey("ws_token")
        val HTTP = stringPreferencesKey("http_url")
        val HTTP_TOKEN = stringPreferencesKey("http_token")
        // v1.2.0 多账号
        val ACCOUNTS_JSON = stringPreferencesKey("accounts_json")
        val ACTIVE_INDEX = intPreferencesKey("active_index")
    }

    private val gson = Gson()

    suspend fun load(): AppConfig {
        val prefs = context.dataStore.data.first()
        val default = AppConfig()
        var accounts: MutableList<EndpointConfig> = mutableListOf()
        val raw = prefs[Keys.ACCOUNTS_JSON]
        if (!raw.isNullOrBlank()) {
            try {
                val type = object : TypeToken<MutableList<EndpointConfig>>() {}.type
                val parsed: MutableList<EndpointConfig> = gson.fromJson(raw, type)
                if (parsed != null) accounts = parsed
            } catch (_: Exception) {
                accounts = mutableListOf()
            }
        }
        // 旧版单账号配置迁移：accounts 为空且有 WS 配置时转为第一个账号
        if (accounts.isEmpty()) {
            val legacy = EndpointConfig(
                name = if (accounts.isEmpty()) "默认" else "",
                wsUrl = prefs[Keys.WS] ?: default.endpoint.wsUrl,
                wsToken = prefs[Keys.WS_TOKEN] ?: "",
                httpUrl = prefs[Keys.HTTP] ?: default.endpoint.httpUrl,
                httpToken = prefs[Keys.HTTP_TOKEN] ?: ""
            )
            accounts.add(legacy)
        }
        val active = (prefs[Keys.ACTIVE_INDEX] ?: 0).coerceIn(0, accounts.size - 1)
        val cfg = AppConfig(
            endpoint = accounts[active],
            accounts = accounts.toList(),
            activeIndex = active
        )
        ConfigHolder.config = cfg
        return cfg
    }

    suspend fun save(config: AppConfig) {
        val accounts = if (config.accounts.isEmpty()) listOf(config.endpoint) else config.accounts
        val active = config.activeIndex.coerceIn(0, accounts.size - 1)
        val endpoint = accounts[active]
        context.dataStore.edit { prefs ->
            // 多账号主存储
            prefs[Keys.ACCOUNTS_JSON] = gson.toJson(accounts)
            prefs[Keys.ACTIVE_INDEX] = active
            // 兼容字段同步写第一份（旧版读取不至空）
            prefs[Keys.WS] = endpoint.wsUrl
            prefs[Keys.WS_TOKEN] = endpoint.wsToken
            prefs[Keys.HTTP] = endpoint.httpUrl
            prefs[Keys.HTTP_TOKEN] = endpoint.httpToken
        }
        ConfigHolder.config = AppConfig(endpoint = endpoint, accounts = accounts.toList(), activeIndex = active)
    }

    /** 切换生效账号（保存后需重启同步服务生效） */
    suspend fun switchTo(index: Int) {
        val current = load()
        save(current.copy(activeIndex = index, endpoint = current.accounts[index.coerceIn(0, current.accounts.size - 1)]))
    }
}
