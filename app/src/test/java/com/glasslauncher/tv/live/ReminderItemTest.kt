package com.glasslauncher.tv.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderItemTest {
    @Test fun itsOwnTimesAreKept() {
        val it = LiveSchedule.Item().apply {
            kind = LiveSchedule.REMIND
            ch = Xtream.Channel().apply { id = 7; name = "A" }
            start = 1000; end = 2000; title = "T"
            lead = 5; auto = true; at = 900; was = 500
        }
        val back = LiveSchedule.Item.of(it.json())
        assertEquals(5, back.lead)
        assertTrue(back.auto)
        assertEquals(900L, back.at)
        assertEquals(500L, back.was)
        val older = LiveSchedule.Item.of(it.json().apply { remove("lead"); remove("auto"); remove("at"); remove("was") })
        assertEquals(-1, older.lead)      // set before these were
        assertEquals(0L, older.at)
        assertEquals(0L, it.`as`(LiveSchedule.RECORD).at)      // put off: the reminder's alone
    }

    private fun item(start: Long, channel: Int = 7) = LiveSchedule.Item().apply { kind = LiveSchedule.REMIND; ch = Xtream.Channel().apply { id = channel }; this.start = start }

    @Test fun whatMovedKeepsItsSettingsAndAnotherEpisodeDoesnt() {
        val before = item(1_000_000).apply { lead = 5; auto = true; mine = true }
        item(1_000_000 + 1800_000).also { LiveSchedule.asBefore(it, before); assertEquals(5, it.lead); assertTrue(it.auto); assertTrue(it.mine); assertEquals(1_000_000L, it.was) }
        item(1_000_000 + 24 * 3600_000L).also { LiveSchedule.asBefore(it, before); assertEquals(-1, it.lead); assertEquals(0L, it.was) }
        item(1_000_000 + 1800_000, 8).also { LiveSchedule.asBefore(it, before); assertEquals(0L, it.was) }
    }

    @Test fun aStashDoneAgainKeepsWhatItStashed() {
        val dir = java.nio.file.Files.createTempDirectory("stash").toFile()
        val from = java.io.File(dir, "now").apply { writeText("new") }
        val to = java.io.File(dir, "aside").apply { writeText("old") }
        LivePlaylists.putAside(from, to, true)
        assertEquals("old", to.readText()); assertTrue(!from.exists())
        from.writeText("newer")
        LivePlaylists.putAside(from, to, false)
        assertEquals("newer", to.readText())
        dir.deleteRecursively()
    }

    @Test fun favoritesThenWatchedThenRecentAndTroubleCounts() {
        assertTrue(LiveFollow.rank(true, 0, false, 10) > LiveFollow.rank(false, 360_000, true, 0))
        assertTrue(LiveFollow.rank(false, 7200, false, 0) > LiveFollow.rank(false, 60, true, 0))
        assertTrue(LiveFollow.rank(false, 0, true, 0) > LiveFollow.rank(false, 0, true, 3))
    }
}
