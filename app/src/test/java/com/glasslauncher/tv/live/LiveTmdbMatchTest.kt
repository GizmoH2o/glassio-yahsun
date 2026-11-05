package com.glasslauncher.tv.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TMDB's entry of the same name isn't always the programme on the guide. */
class LiveTmdbMatchTest {
    private val bbc = "The BBC's Breakfast programme features the latest news, sport, business, weather and other items on a daily basis."

    @Test fun aLittleKnownEntryMustSayWhatTheGuideSays() {
        val horror = "A family's breakfast turns to terror when something stirs beneath the old farmhouse."
        assertFalse(LiveTmdbMatch.plausible("Breakfast", 3, horror, bbc))
        assertTrue(LiveTmdbMatch.plausible("Breakfast", 3, "The latest news and sport from around the country each morning.", bbc))
    }

    @Test fun theTitlesOwnWordsDontCount() {
        assertFalse(LiveTmdbMatch.plausible("Breakfast Programme", 3, "A breakfast programme.", bbc))
    }

    @Test fun nothingToCompareIsNotAMatch() {
        assertFalse(LiveTmdbMatch.plausible("Breakfast", 3, "Anything at all here.", ""))
        assertFalse(LiveTmdbMatch.plausible("Breakfast", 3, "", bbc))
        assertFalse(LiveTmdbMatch.plausible("Breakfast", 3, null, null))
    }

    @Test fun aWellKnownOneNeedsNoMore() {
        assertTrue(LiveTmdbMatch.plausible("Family Guy", 5000, "A dysfunctional family.", ""))
        assertTrue(LiveTmdbMatch.plausible("Family Guy", 50, "", null))
    }

    @Test fun aFilmMustFitItsSlot() {
        assertTrue(LiveTmdbMatch.fitsSlot(100, 120))
        assertTrue("a little over, with the credits cut", LiveTmdbMatch.fitsSlot(100, 95))
        assertFalse(LiveTmdbMatch.fitsSlot(117, 30))
        assertTrue("length unknown", LiveTmdbMatch.fitsSlot(0, 30))
        assertTrue("slot unknown", LiveTmdbMatch.fitsSlot(117, 0))
    }

    @Test fun seasons() {
        assertTrue(LiveTmdbMatch.fitsSeason(true, 5, 5))
        assertTrue("one just begun", LiveTmdbMatch.fitsSeason(true, 6, 5))
        assertFalse(LiveTmdbMatch.fitsSeason(true, 8, 1))
        assertTrue("seasons unknown", LiveTmdbMatch.fitsSeason(true, 8, 0))
        assertFalse("a film has no seasons", LiveTmdbMatch.fitsSeason(false, 2, 0))
        assertTrue(LiveTmdbMatch.fitsSeason(false, 0, 0))
    }

    @Test fun readsTheSeason() {
        assertEquals(7, LiveTmdbMatch.seasonIn("S07 E75 Valerie Bertinelli"))
        assertEquals(2, LiveTmdbMatch.seasonIn("s2·e7 The Signal Box"))
        assertEquals(0, LiveTmdbMatch.seasonIn("Class of 2026 ends"))
        assertEquals(0, LiveTmdbMatch.seasonIn(null))
    }
}
