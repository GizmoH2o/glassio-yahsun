package com.glasslauncher.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the mood and time filters let through. */
class MoodsTest {
    private fun title(key: String, genres: List<String> = emptyList(), runtime: String = "", rating: String = "", id: String = key) =
        HeroItem(key, id, "movie", key, "", "", "", "", rating, "", genres, "", runtime = runtime)

    private fun mood(label: String) = Moods.ALL.first { it.label == label }

    @Test fun `runtimes in every form come to minutes`() {
        assertEquals(126, Moods.minutes("126 min"))
        assertEquals(126, Moods.minutes("2h 6m"))
        assertEquals(120, Moods.minutes("2h"))
        assertEquals(52, Moods.minutes("52 min"))
        assertEquals(45, Moods.minutes("45m"))
    }

    @Test fun `a runtime that says nothing is unknown`() {
        assertNull(Moods.minutes(""))
        assertNull(Moods.minutes("soon"))
        assertNull(Moods.minutes("0 min"))
    }

    @Test fun `a mood goes by the genres`() {
        assertTrue(Moods.fits(title("a", listOf("Comedy")), mood("Funny"), 0))
        assertFalse(Moods.fits(title("a", listOf("Drama")), mood("Funny"), 0))
    }

    @Test fun `case doesn't matter, and joined genres count as each`() {
        assertTrue(Moods.fits(title("a", listOf("COMEDY")), mood("Funny"), 0))
        assertTrue(Moods.fits(title("a", listOf("Action & Adventure")), mood("Exciting"), 0))
    }

    @Test fun `a horror comedy isn't feel-good`() {
        assertFalse(Moods.fits(title("a", listOf("Comedy", "Horror")), mood("Feel-good"), 0))
    }

    @Test fun `with the kids goes by the family genres`() {
        assertTrue(Moods.fits(title("a", listOf("Family")), mood("With the kids"), 0))
        assertFalse(Moods.fits(title("a", listOf("Animation")), mood("With the kids"), 0))
    }

    @Test fun `no mood lets any genre through`() {
        assertTrue(Moods.fits(title("a", listOf("Drama"), "20 min"), null, 30))
    }

    @Test fun `a time keeps what's shorter`() {
        assertTrue(Moods.fits(title("a", runtime = "24 min"), null, 30))
        assertTrue(Moods.fits(title("a", runtime = "30 min"), null, 30))
        assertFalse(Moods.fits(title("a", runtime = "31 min"), null, 30))
    }

    @Test fun `with a time asked for, an unknown length doesn't pass`() {
        assertFalse(Moods.fits(title("a"), null, 30))
    }

    @Test fun `mood and time combine`() {
        assertTrue(Moods.fits(title("a", listOf("Comedy"), "22 min"), mood("Funny"), 30))
        assertFalse(Moods.fits(title("a", listOf("Comedy"), "2h"), mood("Funny"), 30))
        assertFalse(Moods.fits(title("a", listOf("Drama"), "22 min"), mood("Funny"), 30))
    }

    @Test fun `yours first, then the best rated`() {
        val low = title("low", listOf("Comedy"), rating = "5.0")
        val high = title("high", listOf("Comedy"), rating = "9.0")
        val mine = title("mine", listOf("Comedy"), rating = "6.0")
        assertEquals(listOf("mine", "high", "low"), Moods.pick(listOf(low, high, mine), mood("Funny"), 0, setOf("mine"), false).map { it.key })
    }

    @Test fun `a title from two sources is one card`() {
        val a = title("movie:tt1", listOf("Comedy"), id = "tt1")
        val b = title("other:tt1", listOf("Comedy"), id = "tt1")
        assertEquals(1, Moods.pick(listOf(a, b), mood("Funny"), 0, emptySet(), false).size)
    }

    @Test fun `in Kids mode only family titles`() {
        val grown = title("grown", listOf("Comedy"))
        val family = title("family", listOf("Comedy", "Family"))
        assertEquals(listOf("family"), Moods.pick(listOf(grown, family), mood("Funny"), 0, emptySet(), true).map { it.key })
    }

    @Test fun `no more than the row holds`() {
        val many = (1..200).map { title("t$it", listOf("Comedy")) }
        assertEquals(Moods.MAX, Moods.pick(many, mood("Funny"), 0, emptySet(), false).size)
    }

    @Test fun `what's worth looking up has an id and something missing`() {
        val full = title("a", listOf("Comedy"), "20 min", id = "tt1234567")
        val bare = title("b", id = "tt7654321")
        val odd = title("c", id = "nope")
        assertEquals(listOf("b"), Moods.lacking(listOf(full, bare, odd)).map { it.key })
    }
}
