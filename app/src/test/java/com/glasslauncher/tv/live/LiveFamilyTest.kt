package com.glasslauncher.tv.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** Family Mode's rules for Kids profiles: a daily limit, bedtime, 30 minutes more with the PIN, and channels allowed or hidden. */
class LiveFamilyTest {
    private val tz = TimeZone.getTimeZone("Europe/London")
    private val min = 60_000L

    private fun at(day: Int, hour: Int, minute: Int = 0): Long = Calendar.getInstance(tz).apply {
        clear(); set(2026, Calendar.NOVEMBER, day, hour, minute)
    }.timeInMillis

    // ── the day's count

    @Test fun `time watched counts for the day it was watched`() {
        val today = LiveFamily.day(at(7, 18), tz)
        assertEquals(42 * min, LiveFamily.used(today, 42 * min, today))
    }

    @Test fun `a new day starts from nothing at midnight`() {
        val yesterday = LiveFamily.day(at(7, 23, 59), tz)
        val today = LiveFamily.day(at(8, 0, 1), tz)
        assertTrue(yesterday != today)
        assertEquals(0L, LiveFamily.used(yesterday, 90 * min, today))
    }

    @Test fun `a tick counts the time since the last, never a long gap`() {
        assertEquals(15_000L, LiveFamily.tick(1_000_000, 1_015_000))
        assertEquals(LiveFamily.TICK_MAX_MS, LiveFamily.tick(1_000_000, 1_000_000 + 10 * min))   // asleep, not watching
        assertEquals(0L, LiveFamily.tick(0, 1_015_000))                                         // the first tick
        assertEquals(0L, LiveFamily.tick(1_015_000, 1_000_000))                                 // the clock went back
    }

    // ── the limit

    @Test fun `under the limit nothing stops`() {
        assertEquals(LiveFamily.NONE, LiveFamily.stop(at(7, 16), tz, 60, 59 * min, 0, 0))
    }

    @Test fun `at the limit it stops`() {
        assertEquals(LiveFamily.LIMIT, LiveFamily.stop(at(7, 16), tz, 60, 60 * min, 0, 0))
    }

    @Test fun `no limit, no stop`() {
        assertEquals(LiveFamily.NONE, LiveFamily.stop(at(7, 16), tz, 0, 600 * min, 0, 0))
    }

    // ── bedtime

    @Test fun `bedtime from its hour until seven in the morning`() {
        assertEquals(LiveFamily.NONE, LiveFamily.stop(at(7, 19, 59), tz, 0, 0, 20, 0))
        assertEquals(LiveFamily.BEDTIME, LiveFamily.stop(at(7, 20, 0), tz, 0, 0, 20, 0))
        assertEquals(LiveFamily.BEDTIME, LiveFamily.stop(at(8, 2, 0), tz, 0, 0, 20, 0))
        assertEquals(LiveFamily.BEDTIME, LiveFamily.stop(at(8, 6, 59), tz, 0, 0, 20, 0))
        assertEquals(LiveFamily.NONE, LiveFamily.stop(at(8, 7, 0), tz, 0, 0, 20, 0))
    }

    @Test fun `the limit is said before bedtime when both apply`() {
        assertEquals(LiveFamily.LIMIT, LiveFamily.stop(at(7, 21), tz, 60, 60 * min, 20, 0))
    }

    // ── 30 minutes more

    @Test fun `extra time with the PIN holds off both until it ends`() {
        val now = at(7, 21)
        val grace = now + LiveFamily.MORE_MS
        assertEquals(LiveFamily.NONE, LiveFamily.stop(now, tz, 60, 70 * min, 20, grace))
        assertEquals(LiveFamily.BEDTIME, LiveFamily.stop(grace, tz, 0, 0, 20, grace))
    }

    // ── what's left, for the warning

    @Test fun `time left is the limit's when it comes first`() {
        assertEquals(10 * min, LiveFamily.left(at(7, 16), tz, 60, 50 * min, 20, 0))
    }

    @Test fun `time left is bedtime's when it comes first`() {
        assertEquals(15 * min, LiveFamily.left(at(7, 19, 45), tz, 60, 0, 20, 0))
    }

    @Test fun `nothing set, never anything left to count`() {
        assertEquals(Long.MAX_VALUE, LiveFamily.left(at(7, 16), tz, 0, 0, 0, 0))
    }

    @Test fun `during extra time, what's left is until it ends`() {
        val now = at(7, 21)
        assertEquals(LiveFamily.MORE_MS, LiveFamily.left(now, tz, 0, 0, 20, now + LiveFamily.MORE_MS))
    }

    @Test fun `the warning comes once, five minutes before`() {
        assertFalse(LiveFamily.warnNow(6 * min, false))
        assertTrue(LiveFamily.warnNow(5 * min, false))
        assertFalse(LiveFamily.warnNow(4 * min, true))
        assertFalse(LiveFamily.warnNow(0, false))       // already over: the stop says it
    }

    // ── channels for Kids profiles

    private fun lineup(): Xtream.Lineup {
        val l = Xtream.Lineup()
        fun ch(id: Int, name: String, cat: String) = Xtream.Channel().also { it.id = id; it.name = name; it.category = cat }
        val fav = Xtream.Category().also { it.id = "fav"; it.name = "Favorites" }
        val all = Xtream.Category().also { it.id = "all"; it.name = "All" }
        val ent = Xtream.Category().also { it.id = "1"; it.name = "UK | Entertainment" }
        val chans = listOf(ch(1, "Cartoon Network", "1"), ch(2, "BBC One", "1"), ch(3, "Tiny Pop", "1"), ch(4, "Pop", "1"))
        all.channels.addAll(chans); ent.channels.addAll(chans)
        l.categories.addAll(listOf(fav, all, ent))
        for (c in chans) l.byId[c.id] = c
        return l
    }

    private fun names(l: Xtream.Lineup) = l.all().map { it.name }

    @Test fun `the word list decides with no rules`() {
        assertEquals(listOf("Cartoon Network", "Tiny Pop"), names(LiveProfiles.kidsOnly(lineup(), emptySet(), emptySet())))
    }

    @Test fun `an allowed channel joins, in its group too`() {
        val k = LiveProfiles.kidsOnly(lineup(), setOf(4), emptySet())
        assertEquals(listOf("Cartoon Network", "Tiny Pop", "Pop"), names(k))
        assertTrue(k.categories.any { it.id == "1" && it.channels.any { c -> c.name == "Pop" } })
        assertTrue(k.byId.containsKey(4))
    }

    @Test fun `a hidden channel goes, even one the words would keep`() {
        val k = LiveProfiles.kidsOnly(lineup(), emptySet(), setOf(1))
        assertEquals(listOf("Tiny Pop"), names(k))
        assertFalse(k.byId.containsKey(1))
    }

    @Test fun `hidden wins over allowed`() {
        assertEquals(listOf("Cartoon Network", "Tiny Pop"), names(LiveProfiles.kidsOnly(lineup(), setOf(4), setOf(4))))
    }
}
