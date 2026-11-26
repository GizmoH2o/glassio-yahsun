package com.glasslauncher.tv.live

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortSettingsTest {
    private val all = intArrayOf(1, 2, 3, 4, 5)

    @Test fun shortListThenChangedThenMore() {
        assertEquals(listOf(3, 1, 250), LiveSettings.shortRows(all, intArrayOf(3, 1), emptySet()).toList())
        assertEquals(listOf(3, 1, 5, 250), LiveSettings.shortRows(all, intArrayOf(3, 1), setOf(5)).toList())
        assertEquals(listOf(2, 4, 5), LiveSettings.moreRows(all, intArrayOf(3, 1)).toList())     // the changed one there too
        assertEquals(listOf(1, 2, 3, 4, 5), LiveSettings.shortRows(all, all, emptySet()).toList())
    }

    @Test fun zappingDoesntCount() {
        assertEquals(0, LiveWatchTime.counts(30, 30))
        assertEquals(0, LiveWatchTime.counts(90, 30))
        assertEquals(120, LiveWatchTime.counts(120, 30))     // then all of it
        assertEquals(30, LiveWatchTime.counts(150, 30))
    }

    @Test fun aMonthIsKept() {
        val o = JSONObject()
        LiveWatchTime.add(o, 7, 100, 60)
        LiveWatchTime.add(o, 8, 120, 30)
        LiveWatchTime.add(o, 8, 120, 30)
        assertEquals(60, LiveWatchTime.totals(o)[7])
        assertEquals(60, LiveWatchTime.totals(o)[8])
        LiveWatchTime.add(o, 8, 130, 10)
        assertFalse(LiveWatchTime.totals(o).containsKey(7))      // 30 days old
        assertEquals(70, LiveWatchTime.totals(o)[8])
    }

    @Test fun mostWatchedThenHistoryThenTeams() {
        val got = LiveSuggest.pick(mapOf(1 to 5000, 2 to 9000, 3 to 60), listOf(3, 4, 1), listOf(5, 2), setOf(4))
        assertEquals(listOf(2, 1, 3, 5), got.keys.toList())
        assertEquals("time", got[2])
        assertEquals("history", got[3])
        assertEquals("team", got[5])
        assertTrue(LiveSuggest.pick(emptyMap(), (1..40).toList(), listOf(90, 91, 92, 93), emptySet()).size == 12)
    }

    @Test fun copiesHaveOneName() {
        assertEquals(LiveSuggest.copyKey("Sky Sports News HD"), LiveSuggest.copyKey("Sky Sports News  FHD"))
        assertFalse(LiveSuggest.copyKey("Sky Sports News") == LiveSuggest.copyKey("Sky Sports Golf"))
    }
}
