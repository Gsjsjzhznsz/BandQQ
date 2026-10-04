package com.bandqq.sync

import com.bandqq.sync.onebot.OneBotMessage
import com.bandqq.sync.sync.MessageBroker
import com.bandqq.sync.sync.MessageStore
import com.bandqq.sync.sync.ThumbFetcher
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BrokerPaginationTest {

    private fun msg(id: Long, seq: String?, timeMs: Long, target: String = "100", self: Boolean = false) =
        OneBotMessage(
            messageId = id, historySeq = seq, chatType = "group", targetId = target,
            senderId = "555", senderName = "tester", content = "m$id",
            time = timeMs / 1000, isSelf = self
        )

    private class CapturingSender : MessageBroker.MessageSender {
        val frames = ArrayList<String>()
        val apiCalls = ArrayList<Pair<String, JSONObject>>()
        var apiResponse: JSONObject? = null
        override fun sendToBand(frame: String): Boolean {
            frames.add(frame); return true
        }
        override fun requestApiParams(action: String, params: JSONObject, callback: (JSONObject?) -> Unit): Boolean {
            apiCalls.add(Pair(action, params))
            callback(apiResponse)
            return true
        }
    }

    @Test
    fun testOlderLocalPagination() {
        val store = MessageStore(null)
        for (i in 1..30) {
            store.addMessage(msg(i.toLong(), "s$i", 1_700_000_000_000L + i * 1000))
        }
        // before = 第 21 条时间，取更早 20 条
        val before = 1_700_000_000_000L + 10 * 1000
        val older = store.getOlderLocal("100", before, 20)
        assertEquals(10, older.size)
        assertTrue(older.all { it.timeMs <= before })
        assertEquals("m1", older.first().content)
    }

    @Test
    fun testOlderHistoryFrameHasMore() {
        val store = MessageStore(null)
        for (i in 1..25) {
            store.addMessage(msg(i.toLong(), "s$i", 1_700_000_000_000L + i * 1000))
        }
        val older = store.getOlderLocal("100", 1_700_000_000_000L + 10_000, 20)
        val frame = JSONObject(store.buildHistoryFrame("100", "older", older, hasMore = true))
        assertEquals("older", frame.optString("mode"))
        assertEquals(true, frame.optBoolean("has_more"))
        val arr = frame.optJSONArray("messages")
        assertNotNull(arr)
        assertTrue(arr!!.length() <= 20)
        // 消息时间统一毫秒
        assertTrue(arr.getJSONObject(0).optLong("time") > 1_000_000_000_000L)
    }

    @Test
    fun testOlderRemoteFetchUsesSeqAnchor() {
        val store = MessageStore(null)
        // 本地只有一条（锚点），history_seq 优先作为 message_seq
        store.addMessage(msg(10, "9999", 1_700_000_000_000L))
        val sender = CapturingSender()
        // 远端返回 3 条更早的消息（time 秒）
        val remoteMsgs = JSONArray()
        for (i in 1..3) {
            remoteMsgs.put(
                JSONObject()
                    .put("message_id", i.toLong())
                    .put("message_seq", (i * 100).toLong())
                    .put("message_type", "group")
                    .put("group_id", 100L)
                    .put("time", (1_700_000_000_000L - i * 10_000) / 1000)
                    .put("sender", JSONObject().put("user_id", 555L).put("card", "c"))
                    .put("message", JSONArray().put(JSONObject().put("type", "text").put("data", JSONObject().put("text", "old$i"))))
            )
        }
        sender.apiResponse = JSONObject().put("status", "ok").put("data", JSONObject().put("messages", remoteMsgs))
        val broker = MessageBroker(com.bandqq.sync.onebot.OneBotClient("ws://127.0.0.1:1", "http://127.0.0.1:2"), store)
        broker.fetchHistory(sender, "100", "group", 20, older = true, beforeMs = 1_700_000_000_000L)
        assertEquals(1, sender.apiCalls.size)
        assertEquals("get_group_msg_history", sender.apiCalls[0].first)
        // 锚点应是 history_seq 而不是 message_id
        assertEquals(9999L, sender.apiCalls[0].second.optLong("message_seq"))
        // 应推 older 帧
        val olderFrame = sender.frames.firstOrNull { JSONObject(it).optString("mode") == "older" }
        assertNotNull(olderFrame)
        val arr = JSONObject(olderFrame!!).optJSONArray("messages")
        assertEquals(3, arr!!.length())
    }

    @Test
    fun testReplayOnConnectSendsCached() {
        val store = MessageStore(null)
        for (i in 1..5) {
            store.addMessage(msg(i.toLong(), "s$i", System.currentTimeMillis() - i * 1000))
        }
        val sender = CapturingSender()
        val b = MessageBroker(client = com.bandqq.sync.onebot.OneBotClient("ws://127.0.0.1:1", "http://127.0.0.1:2"), store = store)
        b.attachBand("conn1", sender)
        val messageFrames = sender.frames.map { JSONObject(it) }.filter { it.optString("type") == "message" }
        assertEquals(5, messageFrames.size)
        assertTrue(sender.frames.any { JSONObject(it).optString("type") == "list" })
    }

    @Test
    fun testThumbHookAssigned() {
        val store = MessageStore(null)
        val broker = MessageBroker(com.bandqq.sync.onebot.OneBotClient("ws://127.0.0.1:1", "http://127.0.0.1:2"), store)
        broker.thumbFetcher = ThumbFetcher()
        assertNotNull(broker.thumbFetcher)
    }
}
