package com.glasslauncher.tv.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Designs kept by name, shared by code (and QR), and the community list from the repo's designs.json. */
class LiveDesignsTest {
    @Test fun `a code is a design only when it can be one`() {
        assertTrue(LiveTheme.validCode("00201123"))
        assertTrue(LiveTheme.validCode("1234"))           // from before designs
        assertFalse(LiveTheme.validCode("99999999"))       // beyond the parts there are
        assertFalse(LiveTheme.validCode("0020112"))
        assertFalse(LiveTheme.validCode("abc12345"))
        assertFalse(LiveTheme.validCode(null))
    }

    @Test fun `a code's parts and focus color`() {
        val p = LiveTheme.partsOf("01439179")!!          // Cinema Red
        assertEquals(listOf(LiveTheme.YOUTUBE, LiveTheme.NETFLIX, LiveTheme.NETFLIX, LiveTheme.YOUTUBE, LiveTheme.NETFLIX, LiveTheme.ZERO, 0), p.toList())
        assertNull(LiveTheme.partsOf("99999999"))
    }

    // ── kept by name

    @Test fun `saved, renamed and deleted`() {
        var j = LiveDesigns.add("[]", "Match day", "00201123")!!
        j = LiveDesigns.add(j, "Late film", "01439179")!!
        assertEquals(listOf("Match day", "Late film"), LiveDesigns.parse(j).map { it.name })
        j = LiveDesigns.rename(j, 1, "Film night")
        assertEquals("Film night", LiveDesigns.parse(j)[1].name)
        j = LiveDesigns.remove(j, 0)
        assertEquals(listOf("01439179"), LiveDesigns.parse(j).map { it.code })
    }

    @Test fun `twenty at most, a name kept short, no empty name`() {
        var j = "[]"
        repeat(LiveDesigns.MAX) { j = LiveDesigns.add(j, "D$it", "00201123")!! }
        assertNull(LiveDesigns.add(j, "One more", "00201123"))
        assertEquals(40, LiveDesigns.parse(LiveDesigns.add("[]", "x".repeat(80), "00201123")!!)[0].name.length)
        assertEquals("My design", LiveDesigns.parse(LiveDesigns.add("[]", "  ", "00201123")!!)[0].name)
    }

    @Test fun `a bad code isn't kept`() {
        assertNull(LiveDesigns.add("[]", "Bad", "99999999"))
    }

    // ── the community's

    @Test fun `the community file, its broken entries left out`() {
        val json = """{"version":1,"designs":[
            {"name":"Good","code":"00201123","author":"A"},
            {"name":"","code":"00201123"},
            {"name":"Bad code","code":"99999999"},
            {"name":"Good","code":"06033678"},
            {"code":"01439179"},
            "nonsense",
            {"name":"Also good","code":"01439179"}]}"""
        val list = LiveDesigns.community(json)
        assertEquals(listOf("Good", "Also good"), list.map { it.name })
        assertEquals("A", list[0].author)
        assertTrue(LiveDesigns.community("not json").isEmpty())
    }

    @Test fun `the repo's own designs file is all good designs`() {
        val f = listOf(File("../designs.json"), File("designs.json")).first { it.exists() }
        val text = f.readText()
        val list = LiveDesigns.community(text)
        assertEquals(org.json.JSONObject(text).getJSONArray("designs").length(), list.size)
        assertEquals(list.size, list.map { it.name }.toSet().size)
        assertTrue(list.size >= 5)
    }
}
