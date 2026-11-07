package com.glasslauncher.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What For You picks, worked out on the TV from what you watch. */
class ForYouTest {
    private val day = 24 * 3600_000L
    private val now = 1000 * day

    private fun title(key: String, genres: List<String>, rating: String = "7.0", at: Long = 0) =
        HeroItem(key, key, "movie", key, "", "", "", "", rating, "", genres, "", watchedAt = at)

    private fun taste(watching: List<HeroItem>, saved: List<HeroItem> = emptyList(), disliked: List<HeroItem> = emptyList()) =
        ForYou.taste(watching, saved, disliked, now)

    private fun keys(l: List<HeroItem>) = l.map { it.key }

    @Test fun `what you watch makes up your taste`() {
        val t = taste(listOf(title("a", listOf("Comedy"), at = now)))
        assertTrue((t["comedy"] ?: 0.0) > 0)
    }

    @Test fun `what you watched lately counts for more`() {
        val fresh = taste(listOf(title("a", listOf("Comedy"), at = now)))
        val old = taste(listOf(title("a", listOf("Comedy"), at = now - 90 * day)))
        assertTrue(fresh.getValue("comedy") > old.getValue("comedy"))
    }

    @Test fun `what you saved counts, less than what you watch`() {
        val t = taste(listOf(title("a", listOf("Comedy"), at = now)), listOf(title("b", listOf("Drama"))))
        assertTrue(t.getValue("drama") > 0)
        assertTrue(t.getValue("comedy") > t.getValue("drama"))
    }

    @Test fun `not interested counts against a genre`() {
        val t = taste(listOf(title("a", listOf("Horror"), at = now)), disliked = listOf(title("b", listOf("Horror"))))
        assertTrue(t.getValue("horror") < taste(listOf(title("a", listOf("Horror"), at = now))).getValue("horror"))
    }

    @Test fun `joined genres count as each`() {
        val t = taste(listOf(title("a", listOf("Action & Adventure"), at = now)))
        assertTrue("action" in t && "adventure" in t)
    }

    @Test fun `titles like your taste come first`() {
        val t = taste(listOf(title("a", listOf("Comedy"), at = now)))
        val funny = title("funny", listOf("Comedy"))
        val other = title("other", listOf("Comedy", "Drama"), "9.9")
        assertEquals("funny", ForYou.pick(listOf(other, funny), t, emptySet()).first().key)
    }

    @Test fun `among equals the better rated goes first`() {
        val t = taste(listOf(title("a", listOf("Comedy"), at = now)))
        val low = title("low", listOf("Comedy"), "5.0")
        val high = title("high", listOf("Comedy"), "9.0")
        assertEquals(listOf("high", "low"), keys(ForYou.pick(listOf(low, high), t, emptySet())))
    }

    @Test fun `nothing you already have or turned down`() {
        val t = taste(listOf(title("a", listOf("Comedy"), at = now)))
        val seen = title("seen", listOf("Comedy"))
        val fresh = title("fresh", listOf("Comedy"))
        assertEquals(listOf("fresh"), keys(ForYou.pick(listOf(seen, fresh), t, setOf("seen"))))
    }

    @Test fun `nothing outside your taste`() {
        val t = taste(listOf(title("a", listOf("Comedy"), at = now)))
        assertTrue(ForYou.pick(listOf(title("b", listOf("Western"))), t, emptySet()).isEmpty())
    }

    @Test fun `a genre you turned down more than you watch stays out`() {
        val t = taste(emptyList(), disliked = listOf(title("b", listOf("Horror"))))
        assertTrue(ForYou.pick(listOf(title("c", listOf("Horror"))), t, emptySet()).isEmpty())
    }

    @Test fun `no taste, no picks`() {
        assertTrue(ForYou.pick(listOf(title("b", listOf("Comedy"))), emptyMap(), emptySet()).isEmpty())
    }

    @Test fun `a title from two catalogs is one card`() {
        val t = taste(listOf(title("a", listOf("Comedy"), at = now)))
        assertEquals(1, ForYou.pick(listOf(title("x", listOf("Comedy")), title("x", listOf("Comedy"))), t, emptySet()).size)
    }

    @Test fun `one genre doesn't fill the row while others are liked`() {
        val t = taste((1..6).map { title("c$it", listOf("Comedy"), at = now) } + title("d", listOf("Drama"), at = now))
        val funny = (1..30).map { title("f$it", listOf("Comedy"), "9.0") }
        val drama = (1..5).map { title("g$it", listOf("Drama"), "6.0") }
        val row = ForYou.pick(funny + drama, t, emptySet())
        assertTrue(row.take(ForYou.ROW).any { "Drama" in it.genres })
    }

    @Test fun `no more than the row holds`() {
        val t = taste(listOf(title("a", listOf("Comedy"), at = now)))
        assertEquals(ForYou.ROW, ForYou.pick((1..60).map { title("t$it", listOf("Comedy")) }, t, emptySet()).size)
    }

    @Test fun `the same taste picks the same row`() {
        val t = taste(listOf(title("a", listOf("Comedy"), at = now)))
        val pool = (1..30).map { title("t$it", listOf("Comedy")) }
        assertFalse(ForYou.pick(pool, t, emptySet()) != ForYou.pick(pool, t, emptySet()))
    }
}
