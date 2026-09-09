package com.bandqq.sync

import com.bandqq.sync.onebot.OneBotParser
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OneBotFeatureTest {

    private fun segMessage(vararg segs: String): JSONArray {
        val arr = JSONArray()
        for (s in segs) arr.put(JSONObject(s))
        return arr
    }

    @Test
    fun testAtMeDetection() {
        val json = JSONObject().put(
            "message",
            segMessage(
                "{\"type\":\"text\",\"data\":{\"text\":\"你好 \"}}",
                "{\"type\":\"at\",\"data\":{\"qq\":\"12345\"}}"
            )
        )
        val (atMe, text) = OneBotParser.extractSegs(json, "12345")
        assertTrue(atMe)
        assertEquals("你好 @12345", text)
    }

    @Test
    fun testAtAllDetection() {
        val json = JSONObject().put(
            "message",
            segMessage("{\"type\":\"at\",\"data\":{\"qq\":\"all\"}}")
        )
        val (atMe, text) = OneBotParser.extractSegs(json, "999")
        assertTrue(atMe)
        assertEquals("@全体成员", text.trim())
    }

    @Test
    fun testImageExtraction() {
        val json = JSONObject().put(
            "message",
            segMessage(
                "{\"type\":\"image\",\"data\":{\"url\":\"http://x/y.jpg\"}}",
                "{\"type\":\"text\",\"data\":{\"text\":\"看\"}}"
            )
        )
        val (atMe, text) = OneBotParser.extractSegs(json, "1")
        assertFalse(atMe)
        assertTrue(text.contains("[图片]"))
        assertEquals("http://x/y.jpg", json.optJSONObject("__thumb")?.optString("url"))
    }

    @Test
    fun testFaceRecordPlaceholders() {
        val json = JSONObject().put(
            "message",
            segMessage(
                "{\"type\":\"face\",\"data\":{\"id\":\"1\"}}",
                "{\"type\":\"record\",\"data\":{}}"
            )
        )
        val (_, text) = OneBotParser.extractSegs(json, "1")
        assertEquals("[表情][语音]", text)
    }

    @Test
    fun testParseLoginInfo() {
        val resp = JSONObject()
            .put("status", "ok")
            .put("data", JSONObject().put("user_id", 12345L).put("nickname", " tester "))
        val info = OneBotParser.parseLoginInfo(resp)
        assertNotNull(info)
        assertEquals("12345", info!!.first)
        assertEquals("tester", info.second.trim())
    }

    @Test
    fun testHistoryResponseDataMessages() {
        val msg = JSONObject()
            .put("message_id", 42L)
            .put("message_seq", 999L)
            .put("message_type", "group")
            .put("group_id", 100L)
            .put("time", 1700000000L)
            .put("sender", JSONObject().put("user_id", 555L).put("card", "群名片"))
            .put("message", segMessage("{\"type\":\"text\",\"data\":{\"text\":\"hi\"}}"))
        val resp = JSONObject().put("data", JSONObject().put("messages", JSONArray().put(msg)))
        val list = OneBotParser.parseHistoryResponse(resp)
        assertEquals(1, list.size)
        assertEquals("群名片", list[0].senderName)
        assertEquals("999", list[0].historySeq)
        assertEquals("100", list[0].targetId)
    }

    @Test
    fun testHistoryResponseDirectArray() {
        val msg = JSONObject()
            .put("message_id", 7L)
            .put("message_type", "private")
            .put("time", 1700000000L)
            .put("sender", JSONObject().put("user_id", 88L).put("nickname", "n"))
            .put("message", "[CQ:face,id=1]")
        val resp = JSONObject().put("data", JSONArray().put(msg))
        val list = OneBotParser.parseHistoryResponse(resp)
        assertEquals(1, list.size)
        assertEquals("[表情]", list[0].content)
    }

    @Test
    fun testToHandBandFrame() {
        val m = OneBotParser.parseHistoryResponse(
            JSONObject().put("data", JSONArray().put(
                JSONObject()
                    .put("message_id", 1L)
                    .put("message_type", "group")
                    .put("group_id", 100L)
                    .put("time", 1700000000L)
                    .put("sender", JSONObject().put("user_id", 555L).put("card", "c"))
                    .put("message", segMessage("{\"type\":\"text\",\"data\":{\"text\":\"x\"}}"))
            ))
        )[0]
        val frame = JSONObject(OneBotParser.toHandBandFrame(m, atMe = true, thumb = "AAAA"))
        assertEquals("message", frame.optString("type"))
        assertEquals(true, frame.optBoolean("at_me"))
        assertEquals("AAAA", frame.optString("thumb"))
        assertEquals(1700000000L, frame.optLong("time"))
    }
}
