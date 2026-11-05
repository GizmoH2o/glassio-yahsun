package com.glasslauncher.tv.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveExtrasTest {
    private val day = 24 * 3600_000L

    private fun rec(id: String, title: String, ageDays: Long, state: String = LiveRecordings.DONE) = LiveRecordings.Rec().also {
        it.id = id; it.title = title; it.state = state
        it.start = 100 * day - ageDays * day; it.end = it.start + 1000
    }

    private fun gone(list: List<LiveRecordings.Rec>, days: Int, perShow: Int) =
        LiveRecordings.toDelete(list, days, perShow, 100 * day).map { it.id }.toSet()

    @Test fun `nothing goes with both off`() {
        assertEquals(emptySet<String>(), gone(listOf(rec("a", "News", 300)), 0, 0))
    }

    @Test fun `old recordings go`() {
        assertEquals(setOf("old"), gone(listOf(rec("old", "News", 40), rec("new", "News", 2)), 30, 0))
    }

    @Test fun `only the newest of a show stay`() {
        val list = listOf(rec("1", "News", 1), rec("2", "News", 2), rec("3", "News", 3), rec("x", "Film", 9))
        assertEquals(setOf("3"), gone(list, 0, 2))
    }

    @Test fun `one still recording is never deleted`() {
        assertEquals(emptySet<String>(), gone(listOf(rec("a", "News", 90, "recording")), 7, 1))
    }

    @Test fun `adult names are told apart`() {
        assertTrue(LiveOptions.looksAdult("XXX | Adults"))
        assertTrue(LiveOptions.looksAdult("For Adults 18+"))
        assertFalse(LiveOptions.looksAdult("Premier League U18"))
        assertFalse(LiveOptions.looksAdult("Kids"))
    }

    private fun lineup(vararg cats: Pair<String, List<String?>>) = Xtream.Lineup().also { l ->
        cats.forEach { (name, epgs) ->
            val cg = Xtream.Category(); cg.id = name; cg.name = name
            epgs.forEach { e -> cg.channels.add(Xtream.Channel().also { it.epg = e }) }
            l.categories.add(cg)
        }
    }

    @Test fun `channels with no guide leave the categories, favorites stay`() {
        val l = lineup("Fav" to listOf<String?>(null), "All" to listOf("a", null), "News" to listOf("a"), "Odd" to listOf<String?>("z"))
        LiveGroups.onlyGuided(l, setOf("a"))
        assertEquals(listOf("Fav", "All", "News"), l.categories.map { it.name })
        assertEquals(1, l.categories[0].channels.size)
        assertEquals(1, l.categories[1].channels.size)
    }

    @Test fun `no guide yet hides nothing`() {
        val l = lineup("Fav" to emptyList(), "All" to listOf<String?>(null))
        LiveGroups.onlyGuided(l, emptySet())
        assertEquals(1, l.categories[1].channels.size)
    }
}
