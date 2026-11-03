package com.glasslauncher.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeroPicksTest {
    private fun h(key: String, vararg genres: String, name: String = key, overview: String = "") = HeroItem(
        key = key, id = key, type = "movie", name = name, poster = "", background = "", logo = "", overview = overview,
        rating = "", year = "", genres = genres.toList(), platform = "",
    )

    private val cat = listOf(h("a", "Drama"), h("b", "Horror"), h("c", "Family", "Animation"), h("d", "Animation"),
        h("e", "Comedy", name = "A Christmas Story"), h("f", "Romance"), h("g", "Action"))

    private fun keys(r: HeroPicks.Result) = r.slides.map { it.key }

    @Test fun hiddenLeaveIt() {
        assertEquals(listOf("a", "c", "d", "e", "f", "g"), keys(HeroPicks.pick(cat, emptyMap(), setOf("b"), false, emptyList(), true, false, 1)))
    }

    @Test fun kidsOnlyFamily() {
        // Animation alone isn't a family title (plenty of anime isn't for children).
        assertEquals(listOf("c"), keys(HeroPicks.pick(cat, emptyMap(), emptySet(), true, emptyList(), true, true, 10)))
    }

    @Test fun seasonFirst() {
        assertEquals("b", keys(HeroPicks.pick(cat, emptyMap(), emptySet(), false, emptyList(), false, true, 10)).first())
        assertEquals("e", keys(HeroPicks.pick(cat, emptyMap(), emptySet(), false, emptyList(), false, true, 12)).first())
        assertEquals("f", keys(HeroPicks.pick(cat, emptyMap(), emptySet(), false, emptyList(), false, true, 2)).first())
        assertEquals(cat.map { it.key }, keys(HeroPicks.pick(cat, emptyMap(), emptySet(), false, emptyList(), false, true, 11)))
        assertEquals(cat.map { it.key }, keys(HeroPicks.pick(cat, emptyMap(), emptySet(), false, emptyList(), false, false, 10)))
    }

    @Test fun becauseEveryThird() {
        val watching = listOf(h("w1", "Action", name = "John Wick"))
        val r = HeroPicks.pick(cat, emptyMap(), emptySet(), false, watching, true, false, 1)
        assertEquals("g", r.slides[2].key)
        assertEquals("Because you watched John Wick", r.reasons["g"])
        assertEquals(cat.size, r.slides.size)       // nothing lost, nothing twice
        assertEquals(cat.map { it.key }.toSet(), r.slides.map { it.key }.toSet())
    }

    @Test fun becauseNotTheWatchedOnesAndUsesDetails() {
        val watching = listOf(h("a", name = "Drama show"))
        val details = mapOf("a" to h("a", "Drama"), "g" to h("g", "Drama"))
        val r = HeroPicks.pick(cat, details, emptySet(), false, watching, true, false, 1)
        assertTrue("a" !in r.reasons)       // never "because you watched" the title itself
        assertEquals("Because you watched Drama show", r.reasons["g"])
    }

    @Test fun becauseOff() {
        val watching = listOf(h("w1", "Action"))
        assertTrue(HeroPicks.pick(cat, emptyMap(), emptySet(), false, watching, false, false, 1).reasons.isEmpty())
    }
}
