package com.glasslauncher.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The home screen's library rows: runtimes, episode numbers, dates and which episode a series shows this week. */
class HomeShelfTest {
    private val day = 24 * 3600_000L
    private val now = 1_800_000_000_000L
    private fun ep(s: Int, e: Int, at: Long) = Episode(s, e, "", at)

    @Test fun runtimes() {
        assertEquals("2h 6m", HomeShelf.runtime("126 min"))
        assertEquals("2h", HomeShelf.runtime("120 min"))
        assertEquals("52 min", HomeShelf.runtime("52 min"))
        assertEquals("1h 30m", HomeShelf.runtime("1h 30m"))
        assertEquals("", HomeShelf.runtime(""))
    }

    @Test fun episodeNumbersFromTheLine() {
        assertEquals(10 to 12, HomeShelf.episodeOf("S10 · E12"))
        assertEquals(1 to 1, HomeShelf.episodeOf("S1 · E1"))
        assertNull(HomeShelf.episodeOf(""))
        assertNull(HomeShelf.episodeOf("Movie"))
    }

    @Test fun dates() {
        assertEquals(1709324100000L, HomeShelf.released("2024-03-01T20:15:00.000Z"))
        assertEquals(0L, HomeShelf.released(""))
        assertEquals(0L, HomeShelf.released("null"))
    }

    @Test fun theNewestOutWithTheOthersCounted() {
        val got = HomeShelf.thisWeek(listOf(ep(1, 1, now - 2 * day), ep(1, 2, now - day), ep(1, 3, now + 3 * day)), now)
        assertEquals(1 to 2, got!!.first.let { it.season to it.episode })
        assertEquals(2, got.second)
    }

    @Test fun nothingOutYetGivesTheNextOne() {
        val got = HomeShelf.thisWeek(listOf(ep(2, 5, now + 2 * day), ep(2, 4, now + day)), now)
        assertEquals(2 to 4, got!!.first.let { it.season to it.episode })
        assertEquals(1, got.second)
    }

    @Test fun newEpisodesAreOnlyThoseOutSinceYouWatched() {
        val eps = listOf(ep(7, 1, now - 900 * day), ep(7, 2, now - 899 * day), ep(7, 3, now - day), ep(7, 4, now + day))
        // On S7 E1, last watched 10 days ago: only S7 E3 is new (S7 E2 was out long before; S7 E4 isn't out yet).
        assertEquals(1, HomeShelf.newSince(eps, 7, 1, now - 10 * day, now))
        assertEquals(0, HomeShelf.newSince(eps, 7, 3, now - 10 * day, now))
        assertEquals(0, HomeShelf.newSince(eps, 7, 1, now, now))
    }

    @Test fun outsideTheWeekAndSpecialsDontCount() {
        assertNull(HomeShelf.thisWeek(listOf(ep(1, 1, now - 10 * day), ep(1, 9, now + 9 * day), ep(0, 1, now)), now))
    }
}
