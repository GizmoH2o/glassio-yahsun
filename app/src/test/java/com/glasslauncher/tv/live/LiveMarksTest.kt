package com.glasslauncher.tv.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** NEW and LIVE from what the rest of a guide says: only on firm evidence. */
class LiveMarksTest {
    private val jamaica = TimeZone.getTimeZone("America/Jamaica")

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0) = Calendar.getInstance(jamaica).run {
        clear()
        set(y, m - 1, d, h, min)
        timeInMillis
    }

    @Test fun liveCarriesOnlyToTheSameTime() {
        val nine = at(2026, 11, 4, 21)
        assertTrue(LiveMarks.sameLiveShow(nine, nine))
        assertTrue(LiveMarks.sameLiveShow(nine, at(2026, 11, 4, 21, 15)))
        assertFalse("hours later it's a replay", LiveMarks.sameLiveShow(nine, at(2026, 11, 5, 0)))
    }

    @Test fun newCarriesToTheSameEpisodeThatNight() {
        val nine = at(2026, 11, 4, 21)
        val episode = LiveMarks.episodeId("", "S07 E75 Valerie Bertinelli\nThe host talks.")
        val other = LiveMarks.episodeId("S07 E76 Someone Else", "")
        assertTrue("the other coast", LiveMarks.sameNewShow(nine, episode, at(2026, 11, 5, 0), episode))
        assertFalse("the next day", LiveMarks.sameNewShow(nine, episode, at(2026, 11, 5, 21), episode))
        assertFalse("another episode", LiveMarks.sameNewShow(nine, episode, nine, other))
    }

    @Test fun withoutAnEpisodeOnlyTheSameTimeCounts() {
        val nine = at(2026, 11, 4, 21)
        val episode = LiveMarks.episodeId("S07 E75", "")
        assertTrue(LiveMarks.sameNewShow(nine, 0, at(2026, 11, 4, 21, 15), 0))
        assertTrue(LiveMarks.sameNewShow(nine, episode, nine, 0))
        assertFalse(LiveMarks.sameNewShow(nine, 0, at(2026, 11, 5, 0), 0))
        assertFalse(LiveMarks.sameNewShow(nine, episode, at(2026, 11, 5, 0), 0))
    }

    @Test fun episodeIdsCompareAcrossWriting() {
        assertEquals(LiveMarks.episodeId("S07 E75", ""), LiveMarks.episodeId("", "s07 e75\nMore text."))
        assertEquals(0, LiveMarks.episodeId("", "One long description with no first line of its own."))
        assertEquals(0, LiveMarks.episodeId(null, null))
    }

    @Test fun readsWholeDatesOnly() {
        assertEquals("20261104", LiveMarks.dayOf("20261104"))
        assertEquals("20261104", LiveMarks.dayOf("2026-11-04 20:00:00"))
        assertEquals("20261104", LiveMarks.dayOf("20261104200000 +0000"))
        assertEquals("", LiveMarks.dayOf("2026"))
        assertEquals("", LiveMarks.dayOf("2026-13-01"))
        assertEquals("", LiveMarks.dayOf(""))
        assertEquals("", LiveMarks.dayOf(null))
    }

    @Test fun firstShownTodayIsNew() {
        assertTrue(LiveMarks.firstShownNow("20261104", at(2026, 11, 4, 20), jamaica))
        assertTrue("after midnight, the evening's show", LiveMarks.firstShownNow("20261104", at(2026, 11, 5, 1), jamaica))
        assertFalse("the next morning it's a repeat", LiveMarks.firstShownNow("20261104", at(2026, 11, 5, 9), jamaica))
        assertFalse(LiveMarks.firstShownNow("20200101", at(2026, 11, 4, 20), jamaica))
        assertFalse(LiveMarks.firstShownNow("", at(2026, 11, 4, 20), jamaica))
    }

    @Test fun firstShownEarlierIsARepeat() {
        assertTrue(LiveMarks.firstShownBefore("20200101", at(2026, 11, 4, 20), jamaica))
        assertTrue(LiveMarks.firstShownBefore("20261104", at(2026, 11, 5, 9), jamaica))
        assertFalse(LiveMarks.firstShownBefore("20261104", at(2026, 11, 4, 20), jamaica))
        assertFalse("a date ahead of it says nothing", LiveMarks.firstShownBefore("20261201", at(2026, 11, 4, 20), jamaica))
        assertFalse(LiveMarks.firstShownBefore("", at(2026, 11, 4, 20), jamaica))
    }
}
