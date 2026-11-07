package com.glasslauncher.tv.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Remembering which sources keep failing. */
class LiveReliabilityTest {
    private val hour = 3_600_000L
    private val now = 1_000 * hour

    @Test fun `one bad moment is forgiven`() {
        assertFalse(LiveReliability.avoid(longArrayOf(now - hour), now))
        assertFalse(LiveReliability.avoid(longArrayOf(now - hour, now - 2 * hour), now))
    }

    @Test fun `three failures in a few hours is a pattern`() {
        assertTrue(LiveReliability.avoid(longArrayOf(now - hour, now - 2 * hour, now - 3 * hour), now))
    }

    @Test fun `old trouble doesn't count`() {
        assertFalse(LiveReliability.avoid(longArrayOf(now - 10 * hour, now - 11 * hour, now - 12 * hour), now))
    }

    @Test fun `only what's still recent is kept`() {
        val kept = LiveReliability.recent(longArrayOf(now - hour, now - 10 * hour, now - 2 * hour), now)
        assertEquals(listOf(now - hour, now - 2 * hour), kept.toList())
    }

    @Test fun `no more than a handful of times are kept for one source`() {
        val many = LongArray(30) { now - it * 60_000L }
        assertEquals(8, LiveReliability.recent(many, now).size)
    }

    private fun ch(id: Int, quality: Int) = Xtream.Channel().apply {
        this.id = id
        this.quality = quality
    }

    @Test fun `a bad source is passed over for the next one`() {
        val best = ch(1, LiveMerge.FHD)
        val next = ch(2, LiveMerge.HD)
        assertSame(next, LiveReliability.choose(setOf(1)) { avoid -> if (1 in avoid) next else best })
    }

    @Test fun `when every source is bad, the channel still plays`() {
        val best = ch(1, LiveMerge.FHD)
        assertSame(best, LiveReliability.choose(setOf(1)) { avoid -> if (1 in avoid) null else best })
    }

    @Test fun `nothing to avoid is the plain pick`() {
        val best = ch(1, LiveMerge.FHD)
        assertSame(best, LiveReliability.choose(emptySet()) { best })
    }
}
