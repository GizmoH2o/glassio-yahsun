package com.glasslauncher.tv.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shows Home's search lists from the guide. */
class LiveHomeSearchTest {
    private val now = 1_000_000L

    private fun found(title: String, epg: String, start: Long, end: Long) = XmltvStore.Found().apply {
        this.title = title
        this.epg = epg
        this.start = start
        this.end = end
    }

    private fun titles(list: List<XmltvStore.Found>) = list.map { it.title + "@" + it.epg }

    @Test fun `what's on now comes before what's later`() {
        val later = found("Later", "a", now + 100, now + 200)
        val on = found("Now", "b", now - 100, now + 100)
        assertEquals(listOf("Now@b", "Later@a"), titles(LiveSearchRules.shows(listOf(later, on), emptySet(), now, 10)))
    }

    @Test fun `later shows come soonest first`() {
        val b = found("B", "a", now + 200, now + 300)
        val a = found("A", "a", now + 100, now + 200)
        assertEquals(listOf("A@a", "B@a"), titles(LiveSearchRules.shows(listOf(b, a), emptySet(), now, 10)))
    }

    @Test fun `one show on many channels at once is one card`() {
        val one = found("The Match", "a", now - 10, now + 100)
        val two = found("The Match", "b", now - 10, now + 100)
        assertEquals(1, LiveSearchRules.shows(listOf(one, two), emptySet(), now, 10).size)
    }

    @Test fun `the same show at another time is another card`() {
        val one = found("News", "a", now - 10, now + 100)
        val two = found("News", "a", now + 500, now + 600)
        assertEquals(2, LiveSearchRules.shows(listOf(one, two), emptySet(), now, 10).size)
    }

    @Test fun `the marks in a title don't make it another show`() {
        val one = found("The Match [New]", "a", now - 10, now + 100)
        val two = found("The Match", "b", now - 10, now + 100)
        assertEquals(1, LiveSearchRules.shows(listOf(one, two), emptySet(), now, 10).size)
    }

    @Test fun `what's over isn't listed`() {
        assertTrue(LiveSearchRules.shows(listOf(found("Old", "a", now - 100, now - 10)), emptySet(), now, 10).isEmpty())
    }

    @Test fun `no more than asked for`() {
        val many = (1..30).map { found("S$it", "a", now + it * 100L, now + it * 100L + 50) }
        assertEquals(5, LiveSearchRules.shows(many, emptySet(), now, 5).size)
    }

    @Test fun `your own channels come first among what's on`() {
        val other = found("News Abroad", "far", now - 100, now + 100)
        val mine = found("News Here", "mine", now - 10, now + 100)
        assertEquals(listOf("News Here@mine", "News Abroad@far"), titles(LiveSearchRules.shows(listOf(other, mine), setOf("mine"), now, 10)))
    }

    @Test fun `your own channels come first among what's later too`() {
        val other = found("News Abroad", "far", now + 100, now + 200)
        val mine = found("News Here", "mine", now + 500, now + 600)
        assertEquals(listOf("News Here@mine", "News Abroad@far"), titles(LiveSearchRules.shows(listOf(other, mine), setOf("mine"), now, 10)))
    }

    @Test fun `what's on now still beats a later show on your channel`() {
        val other = found("News Abroad", "far", now - 100, now + 100)
        val mine = found("News Here", "mine", now + 500, now + 600)
        assertEquals(listOf("News Abroad@far", "News Here@mine"), titles(LiveSearchRules.shows(listOf(other, mine), setOf("mine"), now, 10)))
    }

    @Test fun `the same show, marks and numbers aside`() {
        assertTrue(LiveSearchRules.sameShow("Suits", "Suits"))
        assertTrue(LiveSearchRules.sameShow("SUITS [New]", "Suits"))
        assertTrue(LiveSearchRules.sameShow("Suits S06E04", "Suits"))
        assertTrue(LiveSearchRules.sameShow("Suits - S6 E4", "Suits"))
        assertTrue(LiveSearchRules.sameShow("Heat (1995)", "Heat"))
    }

    @Test fun `another show with the same words isn't`() {
        assertFalse(LiveSearchRules.sameShow("Dexter: Resurrection", "Dexter"))
        assertFalse(LiveSearchRules.sameShow("Buster Poindexter", "Dexter"))
        assertFalse(LiveSearchRules.sameShow("Blade Runner", "Blade Runner 2049"))
        assertFalse(LiveSearchRules.sameShow("Anything", "   "))
    }

    @Test fun `a title matches all the words, in any order, ignoring case and accents`() {
        assertTrue(LiveSearchRules.matches("Arsenal v Chelsea", "chelsea arsenal"))
        assertTrue(LiveSearchRules.matches("Pokémon", "pokemon"))
        assertFalse(LiveSearchRules.matches("Arsenal v Spurs", "chelsea arsenal"))
        assertFalse(LiveSearchRules.matches("Anything", "   "))
    }
}
