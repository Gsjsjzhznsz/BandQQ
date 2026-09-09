package com.example.bandqq.sync

import com.google.gson.JsonObject

/** 组装统一 connect_state 帧（蓝牙通道 band + 协议端 protocol）,供手机端下发给手环。 */
object SyncStatePush {
    fun buildFrame(): String {
        val band = SyncState.bandConnected
        val protocol = SyncState.oneBotConnected
        val obj = JsonObject()
        obj.addProperty("type", "connect_state")
        obj.addProperty("seq", 0)
        obj.addProperty("state", if (band && protocol) "connected" else "disconnected")
        obj.addProperty("band", band)
        obj.addProperty("protocol", protocol)
        return obj.toString()
    }
}
