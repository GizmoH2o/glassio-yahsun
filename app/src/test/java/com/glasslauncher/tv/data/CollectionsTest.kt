package com.glasslauncher.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Your own Home rows. */
class CollectionsTest {
    private fun title(n: Int) = HeroItem("movie:tt$n", "tt$n", "movie", "Film $n", "p$n", "b$n", "", "", "7.0", "2001", listOf("Comedy"), "")

    private fun friday() = Collections.create(emptyList(), "Friday films")!!

    @Test fun `a new collection takes the first free slot`() {
        val one = friday()
        assertEquals(1, one[0].slot)
        val two = Collections.create(one, "Rainy days")!!
        assertEquals(listOf(1, 2), two.map { it.slot })
    }

    @Test fun `a deleted collection's slot is used again`() {
        val two = Collections.create(friday(), "Rainy days")!!
        val left = Collections.delete(two, 1)
        assertEquals(1, Collections.create(left, "Sundays")!!.first { it.name == "Sundays" }.slot)
    }

    @Test fun `no more than there are rows`() {
        var l = emptyList<MyCollection>()
        repeat(Collections.MAX) { l = Collections.create(l, "C$it")!! }
        assertNull(Collections.create(l, "One too many"))
    }

    @Test fun `a collection needs a name`() {
        assertNull(Collections.create(emptyList(), "   "))
    }

    @Test fun `the name is trimmed`() {
        assertEquals("Friday", Collections.create(emptyList(), "  Friday ")!![0].name)
    }

    @Test fun `a title goes in, newest first, and comes out again`() {
        val a = Collections.toggle(friday(), 1, title(1))
        val b = Collections.toggle(a, 1, title(2))
        assertEquals(listOf("tt2", "tt1"), b[0].titles.map { it.id })
        assertEquals(listOf("tt1"), Collections.toggle(b, 1, title(2))[0].titles.map { it.id })
    }

    @Test fun `only that collection changes`() {
        val two = Collections.create(friday(), "Rainy days")!!
        val after = Collections.toggle(two, 2, title(1))
        assertTrue(after[0].titles.isEmpty())
        assertEquals(1, after[1].titles.size)
    }

    @Test fun `a collection keeps its newest titles when it's full`() {
        var l = friday()
        for (i in 1..(Collections.TITLES + 5)) l = Collections.toggle(l, 1, title(i))
        assertEquals(Collections.TITLES, l[0].titles.size)
        assertEquals("tt${Collections.TITLES + 5}", l[0].titles[0].id)
    }

    @Test fun `which collections hold a title`() {
        val two = Collections.toggle(Collections.create(friday(), "Rainy days")!!, 2, title(1))
        assertTrue(Collections.holds(two[1], title(1)))
        assertFalse(Collections.holds(two[0], title(1)))
    }

    @Test fun `a rename keeps the titles`() {
        val l = Collections.rename(Collections.toggle(friday(), 1, title(1)), 1, " Weekend ")
        assertEquals("Weekend", l[0].name)
        assertEquals(1, l[0].titles.size)
    }

    @Test fun `a blank rename changes nothing`() {
        assertEquals("Friday films", Collections.rename(friday(), 1, "  ")[0].name)
    }

    @Test fun `collections survive being saved`() {
        val l = Collections.toggle(friday(), 1, title(1))
        val back = Collections.parse(Collections.write(l))
        assertEquals("Friday films", back[0].name)
        assertEquals(1, back[0].slot)
        val t = back[0].titles[0]
        assertEquals(listOf("movie:tt1", "tt1", "movie", "Film 1", "p1", "b1", "7.0", "2001"), listOf(t.key, t.id, t.type, t.name, t.poster, t.background, t.rating, t.year))
        assertEquals(listOf("Comedy"), t.genres)
    }

    @Test fun `nothing readable is no collections`() {
        assertTrue(Collections.parse("").isEmpty())
        assertTrue(Collections.parse("not json").isEmpty())
    }

    @Test fun `a slot's row name`() {
        assertEquals("Mine3", Collections.row(3))
        assertEquals(3, Collections.slotOf("Mine3"))
        assertNull(Collections.slotOf("Movies"))
    }
}
