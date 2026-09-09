package com.example.bandqq.sync

import android.content.Context

/** SharedPreferences 实现的 KV 存储，用于聊天数据的手机端持久化 */
class SyncPreferencesKv(context: Context) : KvStorage {
    private val prefs = context.getSharedPreferences("bandqq_sync_data", Context.MODE_PRIVATE)
    override fun get(key: String, default: String) = prefs.getString(key, default) ?: default
    override fun set(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}