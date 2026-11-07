package com.glasslauncher.tv.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** The Today page's day: what's left of today, what counts in it, recordings since yesterday, the morning. */
class LiveTodayTest {
    private val tz = TimeZone.getTimeZone("America/Jamaica")
    private fun at(day: Int, hour: Int, minute: Int = 0): Long = Calendar.getInstance(tz).apply {
        clear(); set(2026, Calendar.NOVEMBER, day, hour, minute)
    }.timeInMillis
    private val hour = 3600_000L

    @Test fun `today is from now to midnight`() {
        assertEquals(listOf(at(7, 9), at(8, 0)), LiveToday.day(at(7, 9), tz).toList())
    }

    @Test fun `just after midnight it's the new day`() {
        assertEquals(listOf(at(8, 0, 30), at(9, 0)), LiveToday.day(at(8, 0, 30), tz).toList())
    }

    @Test fun `what counts today, on now or starting before midnight`() {
        val day = LiveToday.day(at(7, 9), tz)
        assertTrue(LiveToday.within(at(7, 8), at(7, 10), day))         // on now
        assertTrue(LiveToday.within(at(7, 23, 30), at(8, 1), day))     // starts tonight
        assertFalse(LiveToday.within(at(7, 6), at(7, 8), day))         // over
        assertFalse(LiveToday.within(at(8, 0), at(8, 2), day))         // tomorrow
    }

    @Test fun `recordings finished since yesterday`() {
        val now = at(7, 9)
        assertTrue(LiveToday.recent(now - 2 * hour, now))
        assertTrue(LiveToday.recent(now - 23 * hour, now))
        assertFalse(LiveToday.recent(now - 25 * hour, now))
        assertFalse(LiveToday.recent(now + hour, now))      // not finished yet
    }

    @Test fun `the morning is five to noon`() {
        assertFalse(LiveToday.morning(at(7, 4, 59), tz))
        assertTrue(LiveToday.morning(at(7, 5), tz))
        assertTrue(LiveToday.morning(at(7, 11, 59), tz))
        assertFalse(LiveToday.morning(at(7, 12), tz))
    }

    @Test fun `it opens by itself once a morning`() {
        val today = LiveToday.dayKey(at(7, 7), tz)
        assertTrue(LiveToday.opensItself(at(7, 7), tz, 0))
        assertFalse(LiveToday.opensItself(at(7, 8), tz, today))        // opened already today
        assertTrue(LiveToday.opensItself(at(8, 7), tz, today))         // the next morning
        assertFalse(LiveToday.opensItself(at(7, 13), tz, 0))           // the afternoon
    }

    @Test fun `the card's line counts what there is, nothing for none`() {
        assertEquals(listOf("2 matches", "1 recording", "3 set for today"), LiveToday.counts(2, 0, 1, 3))
        assertEquals(listOf("1 match", "2 new episodes"), LiveToday.counts(1, 2, 0, 0))
        assertTrue(LiveToday.counts(0, 0, 0, 0).isEmpty())
    }
}
