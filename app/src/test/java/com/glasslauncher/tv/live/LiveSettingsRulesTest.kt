package com.glasslauncher.tv.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The redesigned settings' rules, apart from their drawing. */
class LiveSettingsRulesTest {
    // The home screen: search, three status cards, then nine sections three to a row.

    @Test fun `up from the status cards reaches search, and down comes back`() {
        assertEquals(LiveSettingsHome.SEARCH, LiveSettingsHome.move(1, 0, -1))
        assertEquals(LiveSettingsHome.FIRST_SECTION - 1, LiveSettingsHome.move(LiveSettingsHome.SEARCH, 0, 1))
    }

    @Test fun `up and down go straight through the grid`() {
        assertEquals(4, LiveSettingsHome.move(1, 0, 1))
        assertEquals(7, LiveSettingsHome.move(4, 0, 1))
        assertEquals(4, LiveSettingsHome.move(7, 0, -1))
    }

    @Test fun `the grid's edges stop the focus`() {
        assertEquals(3, LiveSettingsHome.move(3, -1, 0))
        assertEquals(5, LiveSettingsHome.move(5, 1, 0))
        assertEquals(11, LiveSettingsHome.move(11, 0, 1))
    }

    @Test fun `left and right do nothing on search`() {
        assertEquals(LiveSettingsHome.SEARCH, LiveSettingsHome.move(LiveSettingsHome.SEARCH, 1, 0))
    }

    // Search: a row's group counts, so "subtitle" finds the rows under Sound and subtitles.

    @Test fun `search finds a row by its group`() {
        assertTrue(LiveSettingsHome.found("subtitle", "Captions", "Watching", "Sound and subtitles"))
    }

    @Test fun `search ignores case and accents`() {
        assertTrue(LiveSettingsHome.found("cafe", "Café"))
        assertTrue(LiveSettingsHome.found("BUFFER", "Buffer"))
    }

    @Test fun `search misses what isn't there`() {
        assertFalse(LiveSettingsHome.found("subtitle", "Buffer", "Watching", "Stream"))
    }

    // The Guide page never lists adult channels by name, locked or not.

    @Test fun `adult categories are found by name`() {
        val news = Xtream.Category().apply { id = "1"; name = "UK | News" }
        val adult = Xtream.Category().apply { id = "2"; name = "24/7 Adult" }
        assertEquals(setOf("2"), LiveOptions.adultIds(listOf(news, adult)))
    }

    // A match's teams: providers also write "A / B".

    @Test fun `a slash between two teams is a match`() {
        assertEquals(listOf("Slavia Prague", "Arsenal"), LiveFollow.choices("Slavia Prague / Arsenal"))
    }

    // The design gallery: the time-of-day rows above the row of designs.

    @Test fun `up from the designs reaches the last row, down comes back`() {
        assertEquals(LiveSettingsGallery.TILES - 1, LiveSettingsGallery.moveRow(LiveSettingsGallery.TILES, -1))
        assertEquals(LiveSettingsGallery.TILES, LiveSettingsGallery.moveRow(LiveSettingsGallery.TILES - 1, 1))
    }

    @Test fun `the gallery's rows stop at either end`() {
        assertEquals(0, LiveSettingsGallery.moveRow(0, -1))
        assertEquals(LiveSettingsGallery.TILES, LiveSettingsGallery.moveRow(LiveSettingsGallery.TILES, 1))
    }

    // The connection check.

    @Test fun `a steady stream needs no change`() {
        val a = LiveCheck.advise(1, 0, 6_000, LiveOptions.NORMAL, LiveOptions.AUTO)
        assertTrue(a.good)
        assertEquals(LiveOptions.NORMAL, a.buffer)
        assertEquals(LiveOptions.AUTO, a.format)
    }

    @Test fun `many stops this week ask for more buffer`() {
        val a = LiveCheck.advise(9, 0, 6_000, LiveOptions.NORMAL, LiveOptions.AUTO)
        assertFalse(a.good)
        assertEquals(LiveOptions.LARGE, a.buffer)
        assertEquals(LiveOptions.AUTO, a.format)
    }

    @Test fun `a stop during the check counts`() {
        assertFalse(LiveCheck.advise(0, 1, 6_000, LiveOptions.NORMAL, LiveOptions.AUTO).good)
    }

    @Test fun `a nearly empty buffer tries the other format too`() {
        val a = LiveCheck.advise(2, 0, 800, LiveOptions.LARGE, LiveOptions.TS)
        assertEquals(LiveOptions.EXTRA, a.buffer)
        assertEquals(LiveOptions.HLS, a.format)
    }

    @Test fun `the largest buffer stays the largest`() {
        assertEquals(LiveOptions.EXTRA, LiveCheck.advise(20, 0, 6_000, LiveOptions.EXTRA, LiveOptions.HLS).buffer)
    }

    @Test fun `already on HLS it stays there`() {
        assertEquals(LiveOptions.HLS, LiveCheck.advise(20, 0, 800, LiveOptions.NORMAL, LiveOptions.HLS).format)
    }

    @Test fun `trouble now is told apart from a bad week`() {
        assertFalse(LiveCheck.troubleNow(0, 17_000))
        assertTrue(LiveCheck.troubleNow(1, 17_000))
        assertTrue(LiveCheck.troubleNow(0, 800))
    }

    // The guide's coverage.

    @Test fun `coverage rounds, but never says all while some are missing`() {
        assertEquals(96, LiveCheck.percent(2300, 2400))
        assertEquals(99, LiveCheck.percent(2399, 2400))
        assertEquals(100, LiveCheck.percent(2400, 2400))
        assertEquals(0, LiveCheck.percent(0, 0))
    }
}
