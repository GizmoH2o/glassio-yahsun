package com.glasslauncher.tv.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TagsTest {
    private val hour = 3_600_000L
    private val now = 1_000_000 * hour
    private var id = 0L

    private fun LiveTags.Reader.show(epg: String, at: Int, title: String, about: String, fresh: Boolean = false, live: Boolean = false): Long {
        id++
        add(id, epg, now + at * hour, now + (at + 1) * hour, title, Xtream.fold(title), about, fresh, live)
        return id
    }

    /** Enough else on a channel for it not to look like a loop. */
    private fun LiveTags.Reader.fill(epg: String) {
        for (i in 0 until 12) show(epg, 200 + i, "Show $epg ${i % 5}", "Something", false)
    }

    @Test fun numbers() {
        assertEquals(280004, LiveTags.numbersAt("S28 E04 Storming", 0))
        assertEquals(10002, LiveTags.numbersAt("S1E2", 0))
        assertEquals(0, LiveTags.numbersAt("Season 2", 0))
        assertEquals(0, LiveTags.numbersAt("S2 Extra", 0))
        assertTrue(LiveTags.numbered("Angel Face - S07E17"))
        assertFalse(LiveTags.numbered("Law - Order"))
        assertEquals("s07 e09 the thanksgiving decoupling", LiveTags.episode("S07 E09 The Thanksgiving Decoupling\nWhen"))
        assertNull(LiveTags.episode("S01 E02 Highlighting people who make a difference."))     // a description, no title of its own
        assertNull(LiveTags.episode("S01 E02\nWhen"))
        assertEquals("uk", LiveTags.country("dave.uk"))
        assertEquals("", LiveTags.country("dave.uk.hd1"))
    }

    @Test fun seenBeforeIsARepeatAndTheFirstIsnt() {
        val r = LiveTags.Reader()
        listOf("a.us", "b.us", "c.uk").forEach { r.fill(it) }
        val first = r.show("a.us", 0, "Night Shift", "S02 E05 The Door\nX", fresh = true)
        val coast = r.show("b.us", 3, "Night Shift", "S02 E05 The Door\nX")
        val later = r.show("b.us", 60, "Night Shift", "S02 E05 The Door\nX", fresh = true)
        val abroad = r.show("c.uk", 70, "Night Shift", "S02 E05 The Door\nX", fresh = true)
        val b = r.bits(null, now)
        assertNull(b[first]); assertNull(b[coast])
        assertEquals(LiveTags.SEEN, b[later])
        assertNull(b[abroad])
    }

    @Test fun seenInAnOlderGuide() {
        val r = LiveTags.Reader()
        r.fill("a.us")
        val x = r.show("a.us", 0, "Night Shift", "S02 E05 The Door\nX")
        val k = LiveTags.seenKey("night shift", "us", "s02 e05 the door")
        assertEquals(LiveTags.SEEN, r.bits(LiveTags.Seen.of(longArrayOf(k), longArrayOf(now - 20 * 24 * hour)), now)[x])
    }

    @Test fun loopsAndDailyShowsWithOneNumberGetNone() {
        val r = LiveTags.Reader()
        val loop = (0 until 24).map { r.show("loop.us", it, "Cold Trail", "S01 E0${it % 3 + 1} Part\nX") }
        val titled = (0 until 8).map { r.show("titled.us", it * 20, "Thing - S01E0$it", "S01 E0$it Thing\nX") }
        r.fill("d.us")
        val daily = (0 until 5).map { r.show("d.us", it * 24, "News Magazine", "S04 E02 Weekday\nX") }
        val b = r.bits(null, now)
        (loop + titled + daily).forEach { assertNull(b[it]) }
    }

    @Test fun firstsAndWhatsSaid() {
        val r = LiveTags.Reader()
        r.fill("a.us")
        val e1 = r.show("a.us", 0, "Harbor", "S04 E01 Tide\nX", fresh = true)
        assertEquals(LiveTags.FIRST, r.bits(null, now)[e1])
        assertEquals(LiveTags.FINALE, LiveTags.said("S03 E10 Ends\nSeason finale. All is told."))
        assertEquals(LiveTags.PREMIERE, LiveTags.said("Season Premiere. Dr Ellingham visits"))
        assertEquals(0, LiveTags.said("On the season finale of Lizard Lick Towing"))
        assertEquals(0, LiveTags.said("At the film premiere"))
    }

    @Test fun rerunsOfALiveMatchButNotItsSessions() {
        val r = LiveTags.Reader()
        fun m(at: Int, len: Int, title: String) = r.add(++id, "s.uk", now + at * hour, now + (at + len) * hour, title, Xtream.fold(title), "", false, true)
        m(0, 3, "Champions League : Sporting v Man United")
        m(0, 3, "Champions League : Sporting v Man United")
        m(7, 2, "Champions League : Sporting v Man United")
        m(0, 4, "Cricket : Day 2 India v England"); m(4, 4, "Cricket : Day 2 India v England")       // goes on
        m(0, 3, "Davis Cup Tennis : Italy v Spain"); m(6, 3, "Davis Cup Tennis : Italy v Spain")
        val out = r.reruns()
        assertEquals(1, out.size)
        assertEquals(now + 7 * hour, out[0][1])
        val s = LiveTags.Reader()
        fun n(epg: String, at: Int, title: String) = s.add(++id, epg, now + at * hour, now + (at + 1) * hour, title, Xtream.fold(title), "", false, true)
        n("hsn.us", 0, "Bath & Body - All On Free Shipping"); n("hsn.us", 9, "Bath & Body - All On Free Shipping")       // no match
        n("a.it", 0, "ARSENAL - BORUSSIA DORTMUND"); n("b.cy", 7, "ARSENAL - BORUSSIA DORTMUND")       // another country's
        assertEquals(0, s.reruns().size)
    }

    @Test fun aDaysNewEpisodeAtEachStationsOwnHour() {
        val r = LiveTags.Reader()
        listOf("a.us", "b.us").forEach { r.fill(it) }
        r.show("a.us", 0, "Hot Bench", "S13 E52 Disorder\nX", fresh = true)
        val evening = r.show("b.us", 14, "Hot Bench", "S13 E52 Disorder\nX", fresh = true)
        val carried = r.show("b.us", 15, "Hot Bench", "S13 E52 Disorder\nX"); r.gained(carried, false)
        val again = r.show("a.us", 20, "Hot Bench", "S13 E52 Disorder\nX", fresh = true)
        val days = r.show("b.us", 60, "Hot Bench", "S13 E52 Disorder\nX")
        val b = r.bits(null, now)
        assertNull(b[evening]); assertNull(b[carried])
        assertEquals(LiveTags.SEEN, b[again]); assertEquals(LiveTags.SEEN, b[days])
    }

    @Test fun aSongsLiveIsntLive() {
        val r = LiveTags.Reader()
        val song = r.show("music.x", 0, "Bob Marley \u2013 Lively Up Yourself", "", live = true)
        for (i in 1..9) r.show("music.x", i, "Artist $i \u2013 Song", "")
        assertEquals(LiveTags.RERUN, r.bits(null, now)[song])
    }

    private fun p(title: String = "X", live: Boolean = false, fresh: Boolean = false) = Xtream.Program().apply {
        this.title = title; this.live = live; this.fresh = fresh
    }

    @Test fun applied() {
        assertTrue(p().also { LiveTags.apply(it, LiveTags.SEEN, true, true, true) }.repeat)
        assertFalse(p().also { LiveTags.apply(it, LiveTags.SEEN, false, true, true) }.repeat)
        p(fresh = true).also { LiveTags.apply(it, LiveTags.SEEN, true, true, true); assertFalse(it.fresh); assertTrue(it.repeat) }
        p(fresh = true).also { LiveTags.apply(it, LiveTags.SEEN, true, false, true); assertTrue(it.fresh); assertFalse(it.repeat) }
        p(live = true).also { LiveTags.apply(it, LiveTags.RERUN or LiveTags.SEEN, true, true, true); assertFalse(it.live); assertTrue(it.repeat) }
        p("Premier League Highlights", live = true).also { LiveTags.apply(it, 0, true, true, true); assertFalse(it.live); assertFalse(it.repeat) }
        p("Good Morning America", live = true).also { LiveTags.apply(it, 0, true, true, true); assertTrue(it.live) }
        p(fresh = true).also { LiveTags.apply(it, LiveTags.FIRST, true, true, true); assertTrue(it.premiere) }
        p().also { LiveTags.apply(it, LiveTags.FIRST, true, true, true); assertFalse(it.premiere) }
    }

    @Test fun whichShows() {
        assertEquals(LiveTags.LIVE_TAG, LiveTags.tag(p(live = true, fresh = true), 0))
        assertEquals(LiveTags.PREMIERE_TAG, LiveTags.tag(p(fresh = true).apply { premiere = true }, 0))
        assertEquals(LiveTags.NEW_TAG, LiveTags.tag(p(fresh = true).apply { premiere = true }, 1))
        assertEquals(0, LiveTags.tag(p().apply { repeat = true }, 1))
        assertEquals(0, LiveTags.tag(p(live = true), 2))
    }

    @Test fun marksInTheTitle() {
        val a = p("Serie A : Roma - Lazio (Diretta)").also { Xtream.mark(it) }
        assertTrue(a.live); assertEquals("Serie A : Roma - Lazio", a.title)
        val b = p("NBC5 News at 10PM (Repeat)").also { Xtream.mark(it) }
        assertTrue(b.repeat); assertEquals("NBC5 News at 10PM", b.title)
        val film = p("The Equalizer (R)").also { Xtream.mark(it) }      // its rating
        assertFalse(film.repeat); assertEquals("The Equalizer (R)", film.title)
        assertTrue(p("WDAY News Encore").also { Xtream.mark(it) }.repeat)
        assertFalse(p("Maude Audet – Tu trembleras encore").also { Xtream.mark(it) }.repeat)
    }
}
