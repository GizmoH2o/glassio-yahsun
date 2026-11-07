package com.glasslauncher.tv.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone page's Live TV tab: what it's sent (never a stream's address or the login) and how channels are found. */
class LivePhoneTest {
    private fun prog(title: String, start: Long, end: Long) = Xtream.Program().also { it.title = title; it.start = start; it.end = end }
    private fun chan(id: Int, num: Int, name: String) = Xtream.Channel().also {
        it.id = id; it.num = num; it.name = name; it.category = "1"; it.epg = "bbc1.uk"
        it.logo = "http://provider.example:8080/images/user123/pass456/logo.png"
    }

    @Test fun `what's on now and next`() {
        val list = listOf(prog("A", 0, 100), prog("B", 100, 200), prog("C", 200, 300))
        val nn = LivePhone.nowNext(list, 150)
        assertEquals("B", nn[0]!!.title)
        assertEquals("C", nn[1]!!.title)
    }

    @Test fun `nothing on, the next still`() {
        val nn = LivePhone.nowNext(listOf(prog("A", 200, 300)), 150)
        assertNull(nn[0])
        assertEquals("A", nn[1]!!.title)
    }

    @Test fun `no guide, nothing`() {
        val nn = LivePhone.nowNext(null, 150)
        assertNull(nn[0]); assertNull(nn[1])
    }

    @Test fun `a channel found by its number or its words`() {
        assertTrue(LivePhone.matches("Sky Sports News", 622, "622"))
        assertFalse(LivePhone.matches("Sky Sports News", 6220, "622"))
        assertTrue(LivePhone.matches("Sky Sports News", 622, "sky news"))
        assertTrue(LivePhone.matches("BBC One", 101, "bbc"))
        assertFalse(LivePhone.matches("BBC One", 101, "itv"))
        assertTrue(LivePhone.matches("Télé-Québec", 5, "tele quebec"))
    }

    @Test fun `a channel as sent, its name, number and country, the shows, and no address`() {
        val o = LivePhone.channel(chan(7, 101, "UK: BBC One"), "BBC One", "UK", prog("News", 0, 100), prog("Weather", 100, 200))
        assertEquals(7, o.getInt("id"))
        assertEquals(101, o.getInt("num"))
        assertEquals("BBC One", o.getString("name"))
        assertEquals("UK", o.getString("country"))
        assertEquals("News", o.getJSONObject("now").getString("title"))
        assertEquals("Weather", o.getJSONObject("next").getString("title"))
        val all = o.toString()
        assertFalse(all, all.contains("http"))
        assertFalse(all, all.contains("user123") || all.contains("pass456"))
        assertFalse(all, all.contains("bbc1.uk"))
    }

    @Test fun `an airing as sent, with what's set`() {
        val o = LivePhone.airing(chan(7, 101, "BBC One"), "BBC One", "UK", prog("Match of the Day", 1000, 2000), true, false)
        assertEquals("Match of the Day", o.getString("title"))
        assertEquals(1000L, o.getLong("start"))
        assertTrue(o.getBoolean("remind"))
        assertFalse(o.getBoolean("record"))
        assertFalse(o.toString().contains("http"))
    }
}
