package com.example.bandqq.sync

import com.example.bandqq.onebot.OneBotParser
import com.google.gson.JsonParser

object ContactCache {

    fun parseContactResponse(type: String, raw: String?): List<VisibleContact> {
        if (raw == null) return emptyList()
        val out = mutableListOf<VisibleContact>()
        try {
            val data = JsonParser.parseString(raw).asJsonObject.get("data") ?: return emptyList()
            if (!data.isJsonArray) return emptyList()
            val arr = data.asJsonArray
            for (e in arr) {
                val o = e.asJsonObject
                val id: String
                val name: String
                if (type == "private") {
                    id = o.get("user_id")?.asLong?.toString() ?: continue
                    name = o.get("nickname")?.asString ?: id
                } else {
                    id = o.get("group_id")?.asLong?.toString() ?: continue
                    name = o.get("group_name")?.asString ?: id
                }
                out.add(VisibleContact(id, type, OneBotParser.stripEmoji(name)))
            }
        } catch (e: Exception) {
            return emptyList()
        }
        return out
    }
}
