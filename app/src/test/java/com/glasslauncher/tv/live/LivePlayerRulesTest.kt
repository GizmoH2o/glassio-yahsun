package com.glasslauncher.tv.live

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The redesigned player's rules, apart from its drawing. */
class LivePlayerRulesTest {
    // Skips: a provider's catch-up by the minute (its addresses start on one), the rest as they were.

    @Test fun `provider catch-up skips a minute each way`() {
        assertEquals(-60_000L, LiveCatchup.skipStep(true, true))
        assertEquals(60_000L, LiveCatchup.skipStep(true, false))
    }

    @Test fun `recordings and live rewind keep ten and thirty seconds`() {
        assertEquals(-10_000L, LiveCatchup.skipStep(false, true))
        assertEquals(30_000L, LiveCatchup.skipStep(false, false))
    }

    @Test fun `stacked skips read as minutes from where it is`() {
        assertEquals("+3 min", LiveCatchup.skipLabel(300_000L, 120_000L))
        assertEquals("−2 min", LiveCatchup.skipLabel(0L, 120_000L))
    }

    @Test fun `short skips read as seconds`() {
        assertEquals("+30 s", LiveCatchup.skipLabel(150_000L, 120_000L))
        assertEquals("−10 s", LiveCatchup.skipLabel(110_000L, 120_000L))
    }

    // The match's clock.

    @Test fun `a clock reads as its minute, stoppage time added`() {
        assertEquals(12, LiveMoments.minuteOf("12'"))
        assertEquals(47, LiveMoments.minuteOf("45'+2'"))
        assertEquals(93, LiveMoments.minuteOf("90'+3'"))
        assertEquals(-1, LiveMoments.minuteOf(""))
        assertEquals(-1, LiveMoments.minuteOf("HT"))
    }

    private val show = 1_793_814_000_000L
    private val kickOff = show + 5 * 60_000L     // five minutes of build-up first

    @Test fun `a first-half moment is that far after kick-off`() {
        assertEquals(17 * 60_000L, LiveMoments.position(kickOff, show, 12))
    }

    @Test fun `a second-half moment counts half-time too`() {
        assertEquals((5 + 60 + 15) * 60_000L, LiveMoments.position(kickOff, show, 60))
    }

    // A match off ESPN's board.

    private val iso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", java.util.Locale.ROOT).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }.format(java.util.Date(kickOff))

    private val event = JSONObject("""
        {"id":"1","date":"$iso","status":{"type":{"state":"post"}},"competitions":[{
          "competitors":[
            {"homeAway":"home","score":"2","team":{"id":"10","abbreviation":"AEK","displayName":"AEK Athens"}},
            {"homeAway":"away","score":"3","team":{"id":"20","abbreviation":"RMA","displayName":"Real Madrid"}}],
          "details":[
            {"type":{"text":"Goal"},"clock":{"displayValue":"12'"},"team":{"id":"10"},"scoringPlay":true,
             "athletesInvolved":[{"shortName":"A. Koita"}]},
            {"type":{"text":"Yellow Card"},"clock":{"displayValue":"20'"},"team":{"id":"20"},"yellowCard":true},
            {"type":{"text":"Red Card"},"clock":{"displayValue":"44'"},"team":{"id":"20"},"redCard":true},
            {"type":{"text":"Goal"},"clock":{"displayValue":"58'"},"team":{"id":"20"},"scoringPlay":true}
          ]}]}
    """.trimIndent())

    @Test fun `a match keeps its goals and red cards in order, the score running`() {
        val m = LiveMoments.parse(event, show)
        assertNotNull(m)
        assertEquals("AEK 2–3 RMA", m!!.result())
        assertEquals(3, m.moments.size)
        assertEquals(12, m.moments[0].minute)
        assertEquals(LiveMoments.GOAL, m.moments[0].kind)
        assertEquals("AEK 1–0 · A. Koita", m.moments[0].who)
        assertEquals(LiveMoments.RED, m.moments[1].kind)
        assertEquals("RMA", m.moments[1].who)
        assertEquals("RMA 1–1", m.moments[2].who)
    }

    @Test fun `a match without events has no moments`() {
        assertNull(LiveMoments.parse(JSONObject("""{"date":"$iso","competitions":[{"competitors":[]}]}"""), show))
    }

    @Test fun `the match is found by a team in the title`() {
        val events = org.json.JSONArray().put(event)
        val found = LiveMoments.find(events, listOf(LiveNames.of("real madrid", emptyList())))
        assertEquals("1", found?.optString("id"))
        assertNull(LiveMoments.find(events, listOf(LiveNames.of("arsenal", emptyList()))))
    }

    // Spoilers.

    @Test fun `catch-up hides the match until it's asked for`() {
        assertTrue(LiveMoments.hidden(true, false, false, true))
        assertFalse(LiveMoments.hidden(true, false, true, true))
    }

    @Test fun `the guard turned off hides nothing, nor does it hide live`() {
        assertFalse(LiveMoments.hidden(false, false, false, true))
        assertFalse(LiveMoments.hidden(true, false, false, false))
    }

    @Test fun `a team kept spoiler-free stays hidden live too`() {
        assertTrue(LiveMoments.hidden(false, true, false, false))
    }

    // Channel names: a country word at the end needs a name left before it.

    @Test fun `a number with a country word is the channel's name`() {
        assertEquals("", LiveCountry.split("5 USA").code)
        assertEquals("5 USA", LiveCountry.split("5 USA").name)
    }

    @Test fun `a real name keeps losing its country word`() {
        assertEquals("US", LiveCountry.split("TLC USA").code)
        assertEquals("TLC", LiveCountry.split("TLC USA").name)
    }

    // Up next.

    @Test fun `the card comes in the show's last two minutes`() {
        val end = 1_000_000L
        assertFalse(LiveRail.upNextDue(end - 121_000L, end))
        assertTrue(LiveRail.upNextDue(end - 90_000L, end))
        assertFalse(LiveRail.upNextDue(end, end))
    }

    // The rail: the focus stays in view, the row starts as late as it fits.

    @Test fun `the rail's window follows the focus`() {
        assertEquals(0, LiveRail.window(1, 20, 5, 0))
        assertEquals(3, LiveRail.window(7, 20, 5, 0))
        assertEquals(2, LiveRail.window(2, 20, 5, 4))
        assertEquals(15, LiveRail.window(19, 20, 5, 0))
        assertEquals(0, LiveRail.window(2, 3, 5, 0))
    }

    // Moments: a row of tiles, the focus stops at either end.

    @Test fun `the moments' focus moves along the tiles and stops at the ends`() {
        assertEquals(1, LivePlayerMoments.step(0, 1, 5))
        assertEquals(0, LivePlayerMoments.step(0, -1, 5))
        assertEquals(4, LivePlayerMoments.step(4, 1, 5))
    }

    @Test fun `more moments than tiles slide along with the focus`() {
        assertEquals(0, LiveRail.window(4, 8, LivePlayerMoments.TILES, 0))
        assertEquals(1, LiveRail.window(5, 8, LivePlayerMoments.TILES, 0))
    }

    // The row of tabs: live, and its own for a past show.

    @Test fun `live has the tabs, Start over beside them and no Options`() {
        val tabs = LiveOverlay.tabs(false, true, true, true, true).toList()
        assertEquals(listOf(LiveOverlay.INFO, LiveOverlay.CHANNELS, LiveOverlay.AUDIO, LiveOverlay.MOMENTS, LiveOverlay.START_OVER), tabs)
    }

    @Test fun `a past show has the skips, then Moments, Audio and Back to live`() {
        val tabs = LiveOverlay.tabs(true, true, true, true, true).toList()
        assertEquals(listOf(LiveOverlay.SKIP_BACK, LiveOverlay.SKIP_AHEAD, LiveOverlay.MOMENTS, LiveOverlay.AUDIO, LiveOverlay.LIVE), tabs)
    }

    @Test fun `no match means no Moments tab`() {
        assertFalse(LiveOverlay.tabs(true, true, true, false, true).contains(LiveOverlay.MOMENTS))
        assertFalse(LiveOverlay.tabs(false, true, true, false, true).contains(LiveOverlay.MOMENTS))
    }

    @Test fun `a channel that cannot seek has Play first and no Start over`() {
        val tabs = LiveOverlay.tabs(false, false, true, false, false).toList()
        assertEquals(LiveOverlay.PLAY, tabs[0])
        assertFalse(tabs.contains(LiveOverlay.START_OVER))
    }

    // Catch-up says how long ago the show was.

    @Test fun `days ago counts calendar days, not hours`() {
        val c = java.util.Calendar.getInstance()
        c.set(2026, 10, 5, 0, 30, 0)
        val now = c.timeInMillis
        c.set(2026, 10, 4, 23, 30, 0)
        assertEquals(1, LiveCatchup.daysAgo(c.timeInMillis, now))
        c.set(2026, 10, 5, 0, 5, 0)
        assertEquals(0, LiveCatchup.daysAgo(c.timeInMillis, now))
        c.set(2026, 10, 2, 12, 0, 0)
        assertEquals(3, LiveCatchup.daysAgo(c.timeInMillis, now))
    }

    // Info: the buttons wrap, and the focus moves to the nearest one on the next line.

    @Test fun `the buttons wrap when a line is full`() {
        assertEquals(listOf(0, 0, 1, 1), LivePlayerInfo.lines(floatArrayOf(40f, 40f, 40f, 40f), 90f, 10f).toList())
    }

    @Test fun `a button wider than the line still gets a line`() {
        assertEquals(listOf(0, 1), LivePlayerInfo.lines(floatArrayOf(200f, 40f), 90f, 10f).toList())
    }

    @Test fun `down lands on the nearest button of that line`() {
        val mids = floatArrayOf(50f, 150f, 250f, 40f, 240f)
        val line = intArrayOf(0, 0, 0, 1, 1)
        assertEquals(4, LivePlayerInfo.nearest(mids, line, 1, 250f))
        assertEquals(3, LivePlayerInfo.nearest(mids, line, 1, 50f))
        assertEquals(-1, LivePlayerInfo.nearest(mids, line, 2, 50f))
    }
}
