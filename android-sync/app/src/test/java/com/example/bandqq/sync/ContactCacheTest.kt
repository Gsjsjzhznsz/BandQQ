package com.example.bandqq.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class ContactCacheTest {

    @Test
    fun `解析好友列表`() {
        val raw = """{"status":"ok","data":[{"user_id":111,"nickname":"小明"},{"user_id":222,"nickname":"小红"}]}"""
        val list = ContactCache.parseContactResponse("private", raw)
        assertEquals(2, list.size)
        assertEquals(VisibleContact("111", "private", "小明"), list[0])
        assertEquals(VisibleContact("222", "private", "小红"), list[1])
    }

    @Test
    fun `解析群列表`() {
        val raw = """{"status":"ok","data":[{"group_id":999,"group_name":"测试群"}]}"""
        val list = ContactCache.parseContactResponse("group", raw)
        assertEquals(listOf(VisibleContact("999", "group", "测试群")), list)
    }

    @Test
    fun `null 或空 data 返回空列表`() {
        assertEquals(emptyList<VisibleContact>(), ContactCache.parseContactResponse("private", null))
        assertEquals(emptyList<VisibleContact>(), ContactCache.parseContactResponse("private", """{"status":"ok"}"""))
        assertEquals(emptyList<VisibleContact>(), ContactCache.parseContactResponse("private", "not-json"))
    }
}
