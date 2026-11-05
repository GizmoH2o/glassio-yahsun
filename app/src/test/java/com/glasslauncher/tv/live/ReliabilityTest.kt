package com.glasslauncher.tv.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReliabilityTest {
    private fun channel(id: Int, epg: String?) = Xtream.Channel().also { it.id = id; it.epg = epg }

    @Test fun `a copy has the same guide channel`() {
        val a = channel(1, "tlc.us"); val b = channel(2, "other"); val c = channel(3, "TLC.US")
        assertEquals(c, LiveMerge.copyOf(listOf(a, b, c), a, setOf(1), { false }))
    }

    @Test fun `tried or locked copies are skipped`() {
        val a = channel(1, "x"); val b = channel(2, "x"); val c = channel(3, "x")
        assertNull(LiveMerge.copyOf(listOf(a, b, c), a, setOf(1, 2), { it.id == 3 }))
    }

    @Test fun `a channel with no guide has no copy`() {
        assertNull(LiveMerge.copyOf(listOf(channel(1, ""), channel(2, "")), channel(1, ""), emptySet(), { false }))
    }

    @Test fun `a live score the board stopped telling is not shown`() {
        val min = 60_000L
        assertFalse(LiveScores.stale("in", 10 * min, 12 * min))
        assertTrue(LiveScores.stale("in", 10 * min, 16 * min))
        assertFalse(LiveScores.stale("post", 10 * min, 90 * min))
        assertFalse(LiveScores.stale("in", 0, 90 * min))
    }
}
