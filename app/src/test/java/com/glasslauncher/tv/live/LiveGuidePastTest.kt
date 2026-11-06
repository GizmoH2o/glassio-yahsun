package com.glasslauncher.tv.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** A catch-up channel's older shows, from the provider's table, behind the full guide. */
class LiveGuidePastTest {
    private val hour = 3_600_000L
    private val day = 24 * hour
    private val now = 100 * day

    private fun show(title: String, start: Long, end: Long) = Xtream.Program().apply {
        this.title = title
        this.start = start
        this.end = end
    }

    @Test fun `a guide that stops short of the catch-up asks for more`() {
        assertTrue(LiveCatchup.wantsOlder(5, now - day, now))
    }

    @Test fun `a guide that reaches the catch-up has all it needs`() {
        assertFalse(LiveCatchup.wantsOlder(5, now - 5 * day, now))
    }

    @Test fun `within an hour of the catch-up's edge is close enough`() {
        assertFalse(LiveCatchup.wantsOlder(5, now - 5 * day + hour / 2, now))
    }

    @Test fun `a channel without catch-up has no table to ask`() {
        assertFalse(LiveCatchup.wantsOlder(0, now - hour, now))
    }

    @Test fun `older shows go in front of the guide`() {
        val guide = listOf(show("Tue", 200, 300))
        val older = listOf(show("Mon", 100, 200))
        assertEquals(listOf("Mon", "Tue"), LiveCatchup.joinPast(older, guide).map { it.title })
    }

    @Test fun `what the guide covers stays the guide's`() {
        val guide = listOf(show("Guide", 200, 300))
        val older = listOf(show("Mon", 100, 200), show("Overlap", 150, 250), show("Same", 200, 300), show("Later", 300, 400))
        assertEquals(listOf("Mon", "Guide"), LiveCatchup.joinPast(older, guide).map { it.title })
    }

    @Test fun `the table's order doesn't matter`() {
        val guide = listOf(show("Guide", 300, 400))
        val older = listOf(show("Second", 200, 300), show("First", 100, 200))
        assertEquals(listOf("First", "Second", "Guide"), LiveCatchup.joinPast(older, guide).map { it.title })
    }

    @Test fun `shows that overlap each other in the table don't both stay`() {
        val guide = listOf(show("Guide", 300, 400))
        val older = listOf(show("A", 100, 200), show("B", 150, 250))
        assertEquals(listOf("A", "Guide"), LiveCatchup.joinPast(older, guide).map { it.title })
    }

    @Test fun `nothing older leaves the guide as it was`() {
        val guide = listOf(show("Guide", 300, 400))
        assertSame(guide, LiveCatchup.joinPast(emptyList(), guide))
    }
}
