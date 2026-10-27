package com.glasslauncher.tv.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which of the Nuvio account's rows stands for each title in Continue Watching. */
class ContinueWatchingTest {
    private fun row(id: String, s: Int, e: Int, at: Long, type: String = "series") =
        NuvioAccount.Progress(id, type, s, e, 40, at, "${id}_s${s}e$e")

    @Test fun aSeriesShowsItsFurthestEpisodeDatedByItsLatestWatch() {
        // S5 E11 from years ago, S1 E1 started again this month: S5 E11, as Nuvio resumes it.
        val got = ContinueWatching.onePerTitle(listOf(row("tt4644488", 1, 1, 1792446847934), row("tt4644488", 5, 11, 1663923690000)))
        assertEquals(1, got.size)
        assertEquals(5 to 11, got[0].season to got[0].episode)
        assertEquals(1792446847934, got[0].at)
    }

    @Test fun theEpisodeCountsWithinTheSeason() {
        val got = ContinueWatching.onePerTitle(listOf(row("tt1", 2, 9, 3), row("tt1", 3, 1, 1), row("tt1", 3, 2, 2)))
        assertEquals(3 to 2, got.single().season to got.single().episode)
    }

    @Test fun specialsOnlyWhenThereIsNothingElse() {
        assertEquals(1 to 4, ContinueWatching.onePerTitle(listOf(row("tt1", 0, 9, 5), row("tt1", 1, 4, 1))).single().let { it.season to it.episode })
        assertEquals(0 to 9, ContinueWatching.onePerTitle(listOf(row("tt1", 0, 9, 5))).single().let { it.season to it.episode })
    }

    @Test fun aFilmIsItsLatestRowAndTitlesStayApart() {
        val got = ContinueWatching.onePerTitle(listOf(row("tt9", 0, 0, 1, "movie"), row("tt9", 0, 0, 7, "movie"), row("tt1", 1, 1, 2)))
        assertEquals(2, got.size)
        assertEquals(7L, got.first { it.id == "tt9" }.at)
    }
}
