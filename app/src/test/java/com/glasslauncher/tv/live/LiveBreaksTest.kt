package com.glasslauncher.tv.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ad breaks found from a recording's silences. */
class LiveBreaksTest {
    /**
     * The silences (seconds) of a real recording: 46 minutes of The UnXplained on Blaze. Its frames show four breaks:
     * 2:20–6:02, 15:37–19:25, 31:19–35:32 and 44:15–45:53 (after the credits); the rest is the show.
     */
    private val blaze = """140.15 140.69, 143.38 146.68, 152.86 153.12, 176.58 177.92, 187.27 188.08, 227.24 227.93, 287.38 287.96,
        297.18 297.78, 302.06 302.57, 305.24 305.55, 309.26 309.91, 312.43 312.98, 314.90 315.42, 316.94 317.92, 347.43 347.93,
        357.42 357.69, 360.38 362.71, 491.60 492.06, 642.38 642.84, 937.25 938.93, 968.40 968.97, 987.92 989.13, 989.18 989.51,
        991.72 992.16, 995.29 995.72, 1018.28 1018.99, 1028.19 1028.97, 1058.23 1058.96, 1118.34 1119.71, 1165.34 1165.65,
        1518.41 1518.69, 1805.66 1806.31, 1810.20 1810.56, 1879.22 1879.69, 1881.92 1882.37, 1882.37 1885.68, 1891.81 1892.12,
        1915.58 1916.97, 1946.40 1947.00, 1975.94 1976.94, 1986.38 1986.92, 2015.92 2016.92, 2026.42 2026.94, 2056.39 2056.92,
        2086.40 2086.92, 2096.16 2097.81, 2129.92 2130.25, 2130.38 2132.68, 2655.02 2655.78, 2698.43 2698.68, 2738.63 2738.92,
        2753.38 2753.71"""
        .split(",").map { it.trim().split(Regex("\\s+")).let { (a, b) -> longArrayOf((a.toDouble() * 1000).toLong(), (b.toDouble() * 1000).toLong()) } }

    private fun mmss(ms: Long) = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)

    @Test fun `the four breaks of the real recording, and nothing else`() {
        val found = LiveBreaks.group(blaze).map { mmss(it[0]) + "-" + mmss(it[1]) }
        assertEquals(listOf("2:20-6:02", "15:37-19:25", "31:19-35:32", "44:15-45:53"), found)
    }

    @Test fun `a few silences in the show aren't a break`() {
        val show = listOf(longArrayOf(10_000, 10_400), longArrayOf(40_000, 40_500), longArrayOf(70_000, 70_600))
        assertTrue(LiveBreaks.group(show).isEmpty())
    }

    @Test fun `silences too close together for a minute aren't a break`() {
        val quick = (0 until 6).map { longArrayOf(it * 5_000L, it * 5_000L + 300) }
        assertTrue(LiveBreaks.group(quick).isEmpty())
    }

    @Test fun `nothing heard, nothing found`() {
        assertTrue(LiveBreaks.group(emptyList()).isEmpty())
    }

    // ── where playback is

    private val breaks = listOf(longArrayOf(140_000, 362_000), longArrayOf(937_000, 1_165_000))

    @Test fun `inside a break, that break`() {
        assertEquals(937_000L, LiveBreaks.at(breaks, 1_000_000)!![0])
    }

    @Test fun `in the show, none`() {
        assertNull(LiveBreaks.at(breaks, 500_000))
    }

    @Test fun `in its last seconds a break isn't offered`() {
        assertNull(LiveBreaks.at(breaks, 360_000))
    }

    // ── hearing silence

    private fun pcm(vararg parts: Pair<Double, Boolean>, rate: Int = 1000): ShortArray {
        val out = ArrayList<Short>()
        for ((seconds, loud) in parts) repeat((seconds * rate).toInt()) { out += if (loud) 9000.toShort() else 20.toShort() }
        return out.toShortArray()
    }

    @Test fun `a quiet stretch long enough is a silence, timed`() {
        val m = LiveBreaks.Meter(1000, 1)
        val p = pcm(1.0 to true, 0.5 to false, 1.0 to true)
        m.feed(p, p.size)
        m.end()
        assertEquals(1, m.silences.size)
        assertEquals(1000L, m.silences[0][0])
        assertEquals(1500L, m.silences[0][1])
    }

    @Test fun `a short dip isn't a silence`() {
        val m = LiveBreaks.Meter(1000, 1)
        val p = pcm(1.0 to true, 0.1 to false, 1.0 to true)
        m.feed(p, p.size)
        m.end()
        assertTrue(m.silences.isEmpty())
    }

    @Test fun `one loud channel isn't silence`() {
        val m = LiveBreaks.Meter(1000, 2)
        val p = ShortArray(2000) { if (it % 2 == 0) 9000 else 20 }
        m.feed(p, p.size)
        m.end()
        assertTrue(m.silences.isEmpty())
    }

    @Test fun `audio in pieces is timed as one`() {
        val m = LiveBreaks.Meter(1000, 1)
        val p = pcm(1.0 to true, 0.5 to false, 1.0 to true)
        m.feed(p.copyOfRange(0, 1200), 1200)
        m.feed(p.copyOfRange(1200, p.size), p.size - 1200)
        m.end()
        assertEquals(1000L, m.silences.single()[0])
    }

    @Test fun `a recording that ends quiet still counts it`() {
        val m = LiveBreaks.Meter(1000, 1)
        val p = pcm(1.0 to true, 0.5 to false)
        m.feed(p, p.size)
        m.end()
        assertEquals(1, m.silences.size)
    }

    @Test fun `breaks survive being saved`() {
        val back = LiveBreaks.parse(LiveBreaks.write(breaks))
        assertEquals(2, back.size)
        assertEquals(1_165_000L, back[1][1])
    }

    @Test fun `nothing readable is no breaks`() {
        assertTrue(LiveBreaks.parse("x").isEmpty())
    }
}
