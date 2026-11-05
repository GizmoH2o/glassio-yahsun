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
}
