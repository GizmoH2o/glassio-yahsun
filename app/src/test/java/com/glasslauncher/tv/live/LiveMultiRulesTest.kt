package com.glasslauncher.tv.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Multiview's rules: which boxes use the video chip, saved views, and the wall of live matches. */
class LiveMultiRulesTest {
    private fun view(name: String, pip: Boolean = false, vararg ch: Int) =
        LiveMultiRules.View().apply { this.name = name; this.pip = pip; channels.addAll(ch.toList()) }

    // ── the video chip

    @Test fun `with the setting on, every box uses the video chip`() {
        assertTrue(LiveMultiRules.hardware(true, false, 0, 1))
    }

    @Test fun `a box that failed on it stays in software`() {
        assertFalse(LiveMultiRules.hardware(true, true, 0, 1))
    }

    @Test fun `with the setting off, only the focused box does`() {
        assertTrue(LiveMultiRules.hardware(false, false, 1, 1))
        assertFalse(LiveMultiRules.hardware(false, false, 0, 1))
    }

    // ── the wall

    @Test fun `the wall takes each channel once`() {
        assertEquals(listOf(5, 6), LiveMultiRules.wall(listOf(5, 5, 6), 4))
    }

    @Test fun `the wall stops at what the account allows`() {
        assertEquals(listOf(1, 2), LiveMultiRules.wall(listOf(1, 2, 3), 2))
    }

    @Test fun `no matches, no wall`() {
        assertTrue(LiveMultiRules.wall(emptyList(), 2).isEmpty())
    }

    // ── saved views

    @Test fun `a view keeps its channels, order and picture-in-picture`() {
        val back = LiveMultiRules.parse(LiveMultiRules.write(listOf(view("Saturday", true, 7, 9))))
        assertEquals("Saturday", back[0].name)
        assertTrue(back[0].pip)
        assertEquals(listOf(7, 9), back[0].channels)
    }

    @Test fun `saving the same channels again replaces, not repeats`() {
        val list = LiveMultiRules.add(listOf(view("A", false, 1, 2)), view("A", true, 1, 2))
        assertEquals(1, list.size)
        assertTrue(list[0].pip)
    }

    @Test fun `the newest view comes first, and only so many are kept`() {
        var list = emptyList<LiveMultiRules.View>()
        for (i in 1..(LiveMultiRules.SAVED + 2)) list = LiveMultiRules.add(list, view("V$i", false, i))
        assertEquals(LiveMultiRules.SAVED, list.size)
        assertEquals("V${LiveMultiRules.SAVED + 2}", list[0].name)
    }

    @Test fun `a view goes by its place in the list`() {
        val list = listOf(view("A", false, 1), view("B", false, 2))
        assertEquals(listOf("B"), LiveMultiRules.remove(list, 0).map { it.name })
    }

    @Test fun `a view with no channels isn't saved`() {
        assertTrue(LiveMultiRules.add(emptyList(), view("Empty")).isEmpty())
    }

    @Test fun `nothing readable is no views`() {
        assertTrue(LiveMultiRules.parse("not json").isEmpty())
    }

    @Test fun `a view's name is its channels`() {
        assertEquals("Sky News + BBC One", LiveMultiRules.name(listOf("Sky News", "BBC One")))
    }

    // ── picture in picture

    @Test fun `in picture-in-picture the small box sits inside the large one`() {
        val big = LiveMultiRules.placePip(0)
        val small = LiveMultiRules.placePip(1)
        assertEquals(1920f, big[2] - big[0], 0f)
        assertTrue(small[0] > big[0] && small[2] < big[2] && small[3] < big[3])
        assertTrue(small[2] - small[0] < (big[2] - big[0]) / 2)
    }
}
