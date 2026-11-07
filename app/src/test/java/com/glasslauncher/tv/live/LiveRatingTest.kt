package com.glasslauncher.tv.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The age lock's decision when the guide has no ratings: TMDB's, asked for; and shows nobody rated. */
class LiveRatingTest {
    private val ASK = LiveRating.ASKING
    private val NONE = LiveRating.NONE

    @Test fun `rated at or over the age asks for the PIN`() {
        assertTrue(LiveRating.locked(12, 15, false))
        assertTrue(LiveRating.locked(12, 12, false))
        assertFalse(LiveRating.locked(12, 10, false))
    }

    @Test fun `no lock, nothing locked`() {
        assertFalse(LiveRating.locked(0, 18, true))
    }

    @Test fun `a show nobody rated plays unless the parents chose otherwise`() {
        assertFalse(LiveRating.locked(12, NONE, false))
        assertTrue(LiveRating.locked(12, NONE, true))
    }

    @Test fun `still asking counts as no rating once the wait is over`() {
        assertFalse(LiveRating.locked(12, ASK, false))
        assertTrue(LiveRating.locked(12, ASK, true))
    }

    @Test fun `the start waits only while TMDB is being asked`() {
        assertTrue(LiveRating.waitFor(12, ASK))
        assertFalse(LiveRating.waitFor(12, NONE))
        assertFalse(LiveRating.waitFor(12, 15))
        assertFalse(LiveRating.waitFor(0, ASK))
    }

    @Test fun `ratings looked for where you are, then the English-speaking countries`() {
        assertEquals(listOf("JM", "GB", "US", "IE", "CA", "AU"), LiveRating.regions("", "JM").toList())
        assertEquals(listOf("GB", "US", "IE", "CA", "AU"), LiveRating.regions("UK", "JM").toList())
        assertEquals(listOf("GB", "US", "IE", "CA", "AU"), LiveRating.regions("", "").toList())
        assertEquals(listOf("CA", "GB", "US", "IE", "AU"), LiveRating.regions("ca", "US").toList())
    }

    @Test fun `ratings as the countries write them`() {
        assertEquals(15, XmltvStore.ageOf("15"))
        assertEquals(12, XmltvStore.ageOf("12A"))
        assertEquals(0, XmltvStore.ageOf("U"))
        assertEquals(14, XmltvStore.ageOf("14A"))
        assertEquals(15, XmltvStore.ageOf("MA15+"))
        assertEquals(18, XmltvStore.ageOf("R18+"))
        assertEquals(15, XmltvStore.ageOf("M"))
        assertEquals(-1, XmltvStore.ageOf("NR"))
    }

    @Test fun `the info bar's rating`() {
        assertEquals("12+", LiveRating.label(12, true))
        assertTrue(LiveRating.label(0, true).isNotEmpty())          // "All ages", in the TV's language
        assertEquals("", LiveRating.label(ASK, true))
        assertTrue(LiveRating.label(NONE, true).isNotEmpty())       // "No rating"
        assertEquals("", LiveRating.label(NONE, false))      // no lock on: no need to say
        assertEquals("12+", LiveRating.label(12, false))
    }
}
