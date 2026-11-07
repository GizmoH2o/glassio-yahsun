package com.glasslauncher.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What Home Assistant 2.0 sends: each new event's JSON, and every event once even with two processes reading scores. */
class HaEventsTest {
    @Test fun `paused and resumed say what and where`() {
        val p = HaEvents.pausedBody(true, "live", "Premier League: Leeds v Spurs", "637 TNT Sports 1 UK")
        assertEquals("paused", p.getString("event"))
        assertEquals("live", p.getString("what"))
        assertEquals("637 TNT Sports 1 UK", p.getString("channel"))
        assertEquals("resumed", HaEvents.pausedBody(false, "recording", "X", "").getString("event"))
    }

    @Test fun `a goal with the score and who scored`() {
        val g = HaEvents.goalBody("Arsenal", "Hull City", 1, 0, true, "63'")
        assertEquals("goal", g.getString("event"))
        assertEquals("Arsenal", g.getString("team"))
        assertEquals("Arsenal 1–0 Hull City · 63'", g.getString("match"))
        assertEquals(1, g.getInt("home_score"))
        assertEquals(0, g.getInt("away_score"))
        assertEquals("Hull City", HaEvents.goalBody("Arsenal", "Hull City", 1, 1, false, "70'").getString("team"))
    }

    @Test fun `kick-off and full time`() {
        val k = HaEvents.matchBody(true, "Arsenal", "Hull City", 0, 0)
        assertEquals("kickoff", k.getString("event"))
        assertEquals("Arsenal 0–0 Hull City", k.getString("match"))
        val f = HaEvents.matchBody(false, "Arsenal", "Hull City", 2, 1)
        assertEquals("full_time", f.getString("event"))
        assertEquals("Arsenal 2–1 Hull City", f.getString("match"))
    }

    @Test fun `recordings and the Kids rules`() {
        assertEquals("recording_started", HaEvents.recordingBody(true, "Gogglebox", "Channel 4 UK").getString("event"))
        assertEquals("recording_finished", HaEvents.recordingBody(false, "Gogglebox", "Channel 4 UK").getString("event"))
        assertEquals("Gogglebox", HaEvents.recordingBody(false, "Gogglebox", "").getString("title"))
        assertEquals("bedtime", HaEvents.kidsBody(true, "Mia").getString("event"))
        assertEquals("time_up", HaEvents.kidsBody(false, "Mia").getString("event"))
        assertEquals("Mia", HaEvents.kidsBody(false, "Mia").getString("profile"))
    }

    @Test fun `the basic events go with Basic, the new ones only with All`() {
        assertTrue(HaEvents.wanted("playing", false))
        assertTrue(HaEvents.wanted("home", false))
        assertTrue(HaEvents.wanted("screensaver", false))
        assertFalse(HaEvents.wanted("goal", false))
        assertFalse(HaEvents.wanted("paused", false))
        assertTrue(HaEvents.wanted("goal", true))
    }

    @Test fun `each event once, however many read it`() {
        val now = 10_000_000L
        val sent = mutableMapOf<String, Long>()
        assertTrue(HaEvents.fresh(sent, "goal:401:1-0", now))
        sent["goal:401:1-0"] = now
        assertFalse(HaEvents.fresh(sent, "goal:401:1-0", now + 30_000))
        assertTrue(HaEvents.fresh(sent, "goal:401:2-0", now + 30_000))
        assertTrue(HaEvents.fresh(sent, "goal:401:1-0", now + HaEvents.ONCE_MS + 1))       // long after: another match day
    }
}
