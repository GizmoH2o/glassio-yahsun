package com.glasslauncher.tv.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

/** Catch-up's arithmetic, and the addresses M3U playlists describe. */
class LiveCatchupTest {
    private val show = 1_793_832_000_000L     // a whole minute
    private val utc = TimeZone.getTimeZone("UTC")

    // Where an address starts: providers only start on the minute.

    @Test fun `an address starts on the minute before the spot`() {
        assertEquals(1_140_000L, LiveCatchup.minute(show, 1_194_000L))
        assertEquals(0L, LiveCatchup.minute(show, 59_000L))
        assertEquals(60_000L, LiveCatchup.minute(show, 60_000L))
    }

    @Test fun `a show that starts mid-minute keeps real minutes`() {
        // 20 seconds into the clock's minute: the clock's next minute is 40 seconds in.
        assertEquals(40_000L, LiveCatchup.minute(show + 20_000L, 50_000L))
        assertEquals(-20_000L, LiveCatchup.minute(show + 20_000L, 10_000L))
    }

    // Skipping ahead: a press has to land further on, though the address only starts on the minute.

    @Test fun `a skip ahead within the minute goes to the next minute`() {
        assertEquals(120_000L, LiveCatchup.skip(show, 100_000L, 90_000L))
    }

    @Test fun `a skip ahead into a later minute goes there`() {
        assertEquals(210_000L, LiveCatchup.skip(show, 210_000L, 90_000L))
    }

    @Test fun `a skip back stays where it was asked`() {
        assertEquals(70_000L, LiveCatchup.skip(show, 70_000L, 90_000L))
    }

    // What the player shows: never the old stream's position against the new address.

    @Test fun `on its way it shows where it's going`() {
        assertEquals(1_194_000L, LiveCatchup.shown(1_194_000L, 1_140_000L, 474_000L))
    }

    @Test fun `playing, it shows the address's start plus how far in`() {
        assertEquals(1_149_000L, LiveCatchup.shown(-1L, 1_140_000L, 9_000L))
    }

    // Recovering: the same spot again would fail the same way.

    @Test fun `the first try goes back to the spot itself`() {
        assertEquals(500_000L, LiveCatchup.retryFrom(500_000L, 0, 4_800_000L))
    }

    @Test fun `tries that got nowhere step further past the bad spot`() {
        assertEquals(560_000L, LiveCatchup.retryFrom(500_000L, 1, 4_800_000L))
        assertEquals(620_000L, LiveCatchup.retryFrom(500_000L, 2, 4_800_000L))
    }

    @Test fun `stepping on stops short of the end`() {
        assertEquals(4_740_000L, LiveCatchup.retryFrom(4_730_000L, 2, 4_800_000L))
    }

    @Test fun `progress since the last try means it isn't stuck`() {
        assertEquals(false, LiveCatchup.stuck(530_000L, 500_000L))
        assertEquals(true, LiveCatchup.stuck(505_000L, 500_000L))
        assertEquals(false, LiveCatchup.stuck(500_000L, -1L))
    }

    // M3U playlists: 2026-11-04 17:40:00 UTC for 80 minutes, asked at 2026-11-05 09:00:00 UTC.

    private val start = 1_793_814_000_000L
    private val end = start + 80 * 60_000L
    private val now = 1_793_869_200_000L

    private fun m3u(url: String, mode: String, source: String = "") = LiveCatchup.m3u(url, mode, source, start, end, now, utc)

    @Test fun `default replaces the address with its source`() {
        assertEquals("http://a.example/1?b=1793814000&e=1793818800",
            m3u("http://a.example/live/1.ts", "default", "http://a.example/1?b={utc}&e={utcend}"))
    }

    @Test fun `append adds its source to the address`() {
        assertEquals("http://a.example/1.m3u8?start=1793814000&d=4800",
            m3u("http://a.example/1.m3u8", "append", "?start=\${start}&d={duration}"))
    }

    @Test fun `shift adds the usual pair`() {
        assertEquals("http://a.example/1.ts?utc=1793814000&lutc=1793869200", m3u("http://a.example/1.ts", "shift"))
        assertEquals("http://a.example/1.ts?k=2&utc=1793814000&lutc=1793869200", m3u("http://a.example/1.ts?k=2", "shift"))
    }

    @Test fun `a source can ask for the date's parts and scaled durations`() {
        assertEquals("http://a.example/2026/11/04/17-40-00/80",
            m3u("http://a.example/x", "default", "http://a.example/{Y}/{m}/{d}/{H}-{M}-{S}/{duration:60}"))
        assertEquals("http://a.example/?o=55200", m3u("http://a.example/x", "default", "http://a.example/?o={offset:1}"))
    }

    @Test fun `flussonic rewrites the file name`() {
        assertEquals("http://a.example/bbc/index-1793814000-4800.m3u8", m3u("http://a.example/bbc/index.m3u8", "flussonic"))
        assertEquals("http://a.example/bbc/timeshift_abs-1793814000.ts", m3u("http://a.example/bbc/mpegts", "flussonic"))
        assertEquals("http://a.example/bbc/mono-1793814000-4800.m3u8", m3u("http://a.example/bbc/mono.m3u8", "fs"))
    }

    @Test fun `xc turns a live address into its timeshift address`() {
        assertEquals("http://a.example:8080/timeshift/u/p/80/2026-11-04:17-40/99.ts", m3u("http://a.example:8080/live/u/p/99.ts", "xc"))
        assertEquals("http://a.example/timeshift/u/p/80/2026-11-04:17-40/99.ts", m3u("http://a.example/u/p/99.ts", "xc"))
    }

    @Test fun `what can't be worked out has no address`() {
        assertNull(m3u("http://a.example/1.ts", "something-new"))
        assertNull(m3u("http://a.example/1.ts", "default"))
        assertNull(m3u("http://a.example/not-xtream.ts", "xc"))
        assertNull(m3u("http://a.example/plain.ts", "flussonic"))
    }

    @Test fun `days come from the first attribute that says`() {
        assertEquals(7, LiveCatchup.days("default", "7", ""))
        assertEquals(3, LiveCatchup.days("", "", "3"))
        assertEquals(LiveCatchup.DEFAULT_DAYS, LiveCatchup.days("shift", "", ""))
        assertEquals(0, LiveCatchup.days("", "", ""))
        assertEquals(LiveCatchup.DEFAULT_DAYS, LiveCatchup.days("xc", "soon", ""))
    }
}
