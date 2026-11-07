package com.glasslauncher.tv.live

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a match is worth an alert, and when an alert would spoil it. */
class LiveAlertsTest {
    @Test fun `level late on is a close game`() {
        assertTrue(LiveAlerts.closeGame("in", "82'", 1, 1))
    }

    @Test fun `one goal in it late on is a close game`() {
        assertTrue(LiveAlerts.closeGame("in", "85'", 2, 1))
        assertTrue(LiveAlerts.closeGame("in", "85'", 0, 1))
    }

    @Test fun `two goals in it isn't`() {
        assertFalse(LiveAlerts.closeGame("in", "85'", 3, 1))
    }

    @Test fun `too early isn't`() {
        assertFalse(LiveAlerts.closeGame("in", "79'", 1, 1))
    }

    @Test fun `added time counts`() {
        assertTrue(LiveAlerts.closeGame("in", "90'+3'", 1, 1))
    }

    @Test fun `a match not on isn't`() {
        assertFalse(LiveAlerts.closeGame("post", "85'", 1, 1))
        assertFalse(LiveAlerts.closeGame("pre", "85'", 1, 1))
    }

    @Test fun `a clock that isn't a minute isn't`() {
        assertFalse(LiveAlerts.closeGame("in", "HT", 1, 1))
        assertFalse(LiveAlerts.closeGame("in", "", 1, 1))
    }

    @Test fun `a match being recorded stays quiet`() {
        assertTrue(LiveAlerts.recording(listOf("premier league : man united v bournemouth"), { it.contains("man united") }))
    }

    @Test fun `another recording doesn't quieten it`() {
        assertFalse(LiveAlerts.recording(listOf("the great british bake off"), { it.contains("man united") }))
    }

    @Test fun `no recordings, nothing to spoil`() {
        assertFalse(LiveAlerts.recording(emptyList(), { true }))
    }

    @Test fun `a show naming both sides is the match`() {
        assertTrue(LiveAlerts.sameMatch("nwsl quarter final san diego wave fc v utah royals", "san diego wave fc", "utah royals fc"))
    }

    @Test fun `a show with a similar name isn't`() {
        assertFalse(LiveAlerts.sameMatch("water polo uc san diego vs ca baptist", "san diego wave fc", "utah royals fc"))
    }

    @Test fun `a word both sides share doesn't count`() {
        assertFalse(LiveAlerts.sameMatch("manchester united v liverpool", "manchester united", "manchester city"))
        assertTrue(LiveAlerts.sameMatch("manchester city v manchester united", "manchester united", "manchester city"))
    }

    @Test fun `part of a word doesn't count`() {
        assertFalse(LiveAlerts.sameMatch("utahraptors special", "san diego wave fc", "utah royals fc"))
    }

    @Test fun `offered the match when you're on another channel`() {
        assertTrue(LiveAlerts.offer(622, 104))
    }

    @Test fun `not offered what you're already watching`() {
        assertFalse(LiveAlerts.offer(622, 622))
    }

    @Test fun `offered it with nothing playing`() {
        assertTrue(LiveAlerts.offer(622, -1))
    }

    @Test fun `nowhere to go, nothing offered`() {
        assertFalse(LiveAlerts.offer(-1, 104))
    }
}
