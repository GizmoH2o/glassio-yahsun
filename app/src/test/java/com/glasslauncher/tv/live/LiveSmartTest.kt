package com.glasslauncher.tv.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a Smart channel's row is laid out from the shows its rule finds. */
class LiveSmartTest {
    private val hour = 3600_000L
    private val now = 100 * hour

    private fun show(title: String, start: Long, end: Long, live: Boolean = false) = Xtream.Program().apply {
        this.title = title
        this.start = start
        this.end = end
        this.live = live
    }

    private fun titles(l: List<Xtream.Program>) = l.map { it.title }

    @Test fun `shows go in time order`() {
        val b = show("B", now + 2 * hour, now + 3 * hour)
        val a = show("A", now, now + hour)
        assertEquals(listOf("A", "B"), titles(LiveSmart.lineUp(listOf(b, a), now)))
    }

    @Test fun `a show over isn't listed`() {
        assertTrue(LiveSmart.lineUp(listOf(show("Old", now - 2 * hour, now - hour)), now).isEmpty())
    }

    @Test fun `of two at once, the one on first stays`() {
        val first = show("First", now, now + 2 * hour)
        val clash = show("Clash", now + hour, now + 3 * hour)
        assertEquals(listOf("First"), titles(LiveSmart.lineUp(listOf(clash, first), now)))
    }

    @Test fun `what's on now counts as starting together, a live one ahead`() {
        val replay = show("Replay", now - 2 * hour, now + hour)
        val match = show("Match", now - hour, now + hour, live = true)
        assertEquals(listOf("Match"), titles(LiveSmart.lineUp(listOf(replay, match), now)))
    }

    @Test fun `the next show comes when the last ends`() {
        val a = show("A", now, now + hour)
        val b = show("B", now + hour, now + 2 * hour)
        assertEquals(listOf("A", "B"), titles(LiveSmart.lineUp(listOf(a, b), now)))
    }

    @Test fun `later clashes go the same way, a live one first`() {
        val other = show("Other", now + hour, now + 2 * hour)
        val match = show("Match", now + hour, now + 2 * hour, live = true)
        assertEquals(listOf("Match"), titles(LiveSmart.lineUp(listOf(other, match), now)))
    }

    @Test fun `a day-long block doesn't hide the shows behind it`() {
        val block = show("League Channel", now - 6 * hour, now + 3 * hour)
        val match = show("Match", now + hour, now + 2 * hour)
        assertEquals(listOf("Match"), titles(LiveSmart.lineUp(listOf(block, match), now)))
    }

    @Test fun `a long block stays when it's all there is`() {
        val block = show("League Channel", now - 6 * hour, now + 3 * hour)
        assertEquals(listOf("League Channel"), titles(LiveSmart.lineUp(listOf(block), now)))
    }

    @Test fun `what's on now is the one covering the time`() {
        val a = show("A", now - hour, now + hour)
        assertEquals("A", LiveSmart.onNow(listOf(a), now)?.title)
        assertNull(LiveSmart.onNow(listOf(show("B", now + hour, now + 2 * hour)), now))
    }

    @Test fun `what's next is the first still to start`() {
        val a = show("A", now - hour, now + hour)
        val b = show("B", now + hour, now + 2 * hour)
        assertEquals("B", LiveSmart.next(listOf(a, b), now)?.title)
    }

    @Test fun `the words of a rule, folded`() {
        assertEquals(listOf("premier", "league"), LiveSmart.words(" Premier  LEAGUE ").toList())
    }

    @Test fun `a rule needs words`() {
        assertTrue(LiveSmart.words("   ").isEmpty())
    }

    @Test fun `smart ids never meet the provider's`() {
        assertTrue(LiveSmart.idOf(1) < 0)
        assertEquals(7, LiveSmart.ruleOf(LiveSmart.idOf(7)))
        assertFalse(LiveSmart.idOf(1) == LiveSmart.idOf(2))
    }

    @Test fun `rules survive being saved`() {
        val rules = LiveSmart.addRule(LiveSmart.addRule(emptyList(), "Premier League"), "Formula 1")
        val back = LiveSmart.parse(LiveSmart.write(rules))
        assertEquals(listOf("Premier League", "Formula 1"), back.map { it.name })
        assertEquals(2, back.map { it.id }.toSet().size)
    }

    @Test fun `a new rule never takes a preset's number`() {
        assertTrue(LiveSmart.addRule(emptyList(), "Tennis")[0].id > LiveSmart.NEW)
    }

    @Test fun `a removed rule's number isn't handed to the next while others stand`() {
        val two = LiveSmart.addRule(LiveSmart.addRule(emptyList(), "A"), "B")
        val left = LiveSmart.removeRule(two, two[0].id)
        assertEquals(1, left.size)
        assertFalse(LiveSmart.addRule(left, "C").last().id == left[0].id)
    }

    @Test fun `no two rules alike`() {
        val one = LiveSmart.addRule(emptyList(), "Premier League")
        assertEquals(1, LiveSmart.addRule(one, "  premier league ").size)
    }

    @Test fun `nothing readable is no rules`() {
        assertTrue(LiveSmart.parse("not json").isEmpty())
    }
}
