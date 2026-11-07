package com.glasslauncher.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What takes a finished episode's place, and the order of the one row. */
class UpNextTest {
    private val day = 24 * 3600_000L
    private val now = 1000 * day

    private fun done(s: Int, e: Int, at: Long = now, id: String = "tt1", hidden: Boolean = false) = Finished(id, s, e, at, "Nuvio", hidden)
    private fun ep(s: Int, e: Int, released: Long = 1L) = Episode(s, e, "S${s}E$e", released)
    private fun entry(s: Int, e: Int, at: Long, id: String = "tt1") = CwEntry(id, s, e, 40, at, "Nuvio")
    private fun card(key: String, at: Long) = HeroItem(key, key, "series", key, "", "", "", "", "", "", emptyList(), "", watchedAt = at)

    // ── what's remembered

    @Test fun `a further episode replaces the one kept`() {
        val l = UpNext.note(listOf(done(1, 3, 5)), done(1, 4, 9))
        assertEquals(1, l.size)
        assertEquals(1 to 4, l[0].season to l[0].episode)
    }

    @Test fun `an old episode watched again doesn't stand for the series, but dates it`() {
        val l = UpNext.note(listOf(done(2, 3, 5)), done(1, 1, 9))
        assertEquals(2 to 3, l[0].season to l[0].episode)
        assertEquals(9L, l[0].at)
    }

    @Test fun `the same news again changes nothing`() {
        val l = listOf(done(1, 3, 5))
        assertEquals(l, UpNext.note(l, done(1, 3, 5)))
    }

    @Test fun `a card taken out stays out until a further episode is finished`() {
        val hidden = UpNext.hide(listOf(done(1, 3, 5)), "tt1")
        assertTrue(UpNext.note(hidden, done(1, 3, 9))[0].hidden)
        assertFalse(UpNext.note(hidden, done(1, 4, 9))[0].hidden)
    }

    @Test fun `titles stay apart and only so many are kept`() {
        val l = (1..60).fold(emptyList<Finished>()) { acc, i -> UpNext.note(acc, done(1, 1, i.toLong(), "tt$i")) }
        assertEquals(UpNext.KEEP, l.size)
        assertEquals(60L, l.first().at)
    }

    // ── the next episode

    @Test fun `the next episode is the next in the season`() {
        assertEquals(ep(1, 4), UpNext.next(listOf(ep(1, 3), ep(1, 4), ep(1, 5)), 1, 3, now))
    }

    @Test fun `after a season's last comes the next season's first`() {
        assertEquals(ep(2, 1), UpNext.next(listOf(ep(2, 2), ep(2, 1), ep(1, 9)), 1, 9, now))
    }

    @Test fun `an episode not out yet isn't next`() {
        assertNull(UpNext.next(listOf(ep(1, 4, now + day)), 1, 3, now))
    }

    @Test fun `specials are never next`() {
        assertNull(UpNext.next(listOf(ep(0, 9)), 1, 3, now))
    }

    @Test fun `there's nothing after the last episode`() {
        assertNull(UpNext.next(listOf(ep(1, 3)), 1, 3, now))
    }

    // ── old news

    @Test fun `something finished lately is worth a card`() {
        assertTrue(UpNext.fresh(done(1, 3, now - 30 * day), ep(1, 4), now))
    }

    @Test fun `a series left long ago isn't brought back`() {
        assertFalse(UpNext.fresh(done(1, 3, now - 90 * day), ep(1, 4), now))
    }

    @Test fun `a new episode brings back a series left months ago`() {
        assertTrue(UpNext.fresh(done(1, 3, now - 200 * day), ep(1, 4, now - 2 * day), now))
    }

    @Test fun `not after years, though`() {
        assertFalse(UpNext.fresh(done(1, 3, now - 500 * day), ep(1, 4, now - 2 * day), now))
    }

    // ── against what's in progress

    @Test fun `nothing in progress on the title, the next episode waits`() {
        assertEquals(listOf(done(1, 3)), UpNext.waiting(listOf(done(1, 3)), emptyList()))
    }

    @Test fun `a card taken out doesn't wait`() {
        assertTrue(UpNext.waiting(listOf(done(1, 3, hidden = true)), emptyList()).isEmpty())
    }

    @Test fun `an episode started since wins`() {
        assertTrue(UpNext.waiting(listOf(done(1, 3, 5)), listOf(entry(1, 4, 9))).isEmpty())
    }

    @Test fun `a later episode in progress wins whenever it was`() {
        assertTrue(UpNext.waiting(listOf(done(1, 3, 9)), listOf(entry(1, 5, 5))).isEmpty())
    }

    @Test fun `what another app still has half watched gives way to finishing it`() {
        val e = entry(1, 3, 5)
        assertEquals(listOf(done(1, 3, 9)), UpNext.waiting(listOf(done(1, 3, 9)), listOf(e)))
        assertTrue(UpNext.inProgress(listOf(e), listOf(done(1, 3, 9))).isEmpty())
    }

    @Test fun `other titles in progress stay`() {
        val e = entry(1, 3, 5, "tt2")
        assertEquals(listOf(e), UpNext.inProgress(listOf(e), listOf(done(1, 3, 9))))
    }

    @Test fun `a card taken out doesn't take the entry with it`() {
        val e = entry(1, 3, 5)
        assertEquals(listOf(e), UpNext.inProgress(listOf(e), listOf(done(1, 3, 9, hidden = true))))
    }

    // ── the row

    @Test fun `live now stays in front, the rest go by when you watched`() {
        val match = card("match", 0)
        val row = UpNext.order(listOf(match, card("rec", 5)), listOf(card("old", 2), card("new", 9)), emptySet(), true)
        assertEquals(listOf("match", "new", "rec", "old"), row.map { it.key })
    }

    @Test fun `new episodes go first when that's asked for`() {
        val row = UpNext.order(listOf(card("rec", 5)), listOf(card("show", 2), card("film", 9)), setOf("show"), true)
        assertEquals(listOf("show", "film", "rec"), row.map { it.key })
    }

    @Test fun `and not when it isn't`() {
        val row = UpNext.order(listOf(card("rec", 5)), listOf(card("show", 2), card("film", 9)), setOf("show"), false)
        assertEquals(listOf("film", "rec", "show"), row.map { it.key })
    }
}
