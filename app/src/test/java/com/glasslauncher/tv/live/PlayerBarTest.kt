package com.glasslauncher.tv.live

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerBarTest {
    @Test fun theChosenOnesInTheirOrderThenMore() {
        val row = LiveOverlay.liveBar(LiveOverlay.BAR_DEFAULT, false) { true }.toList()
        assertEquals(listOf(LiveOverlay.START_OVER, LiveOverlay.RECORD, LiveOverlay.AUDIO, LiveOverlay.CHANNELS, LiveOverlay.LAST, LiveOverlay.MATCH, LiveOverlay.MORE), row)
    }

    @Test fun notWhatTheChannelHasnt() {
        val row = LiveOverlay.liveBar(LiveOverlay.BAR_DEFAULT, true) { it == LiveOverlay.AUDIO || it == LiveOverlay.CHANNELS }.toList()
        assertEquals(listOf(LiveOverlay.PLAY, LiveOverlay.AUDIO, LiveOverlay.CHANNELS, LiveOverlay.MORE), row)
    }

    @Test fun noneChosenIsStillMore() {
        assertEquals(listOf(LiveOverlay.MORE), LiveOverlay.liveBar(0, false) { true }.toList())
    }

    @Test fun theLastOnesThatDontFitGoUnderMore() {
        val row = { intArrayOf(1, 2, 3, LiveOverlay.MORE) }
        assertEquals(listOf(1, 2, 3, LiveOverlay.MORE), LiveOverlay.fit(row(), { 100f }, 10f, 430f).toList())
        assertEquals(listOf(1, 2, LiveOverlay.MORE), LiveOverlay.fit(row(), { 100f }, 10f, 429f).toList())
        assertEquals(listOf(LiveOverlay.MORE), LiveOverlay.fit(row(), { 100f }, 10f, 50f).toList())
    }

    @Test fun threeStopsInFiveMinutes() {
        var t = LiveTroubleCard.stop(listOf(), 0)
        t = LiveTroubleCard.stop(t, 5_000)       // its tries: the same stop
        assertEquals(1, t.size)
        t = LiveTroubleCard.stop(t, 60_000)
        t = LiveTroubleCard.stop(t, 299_000)
        assertEquals(3, t.size)
        assertEquals(2, LiveTroubleCard.stop(t, 350_000).size - 1)
    }

    @Test fun theOrderSavedThenTheRest() {
        assertEquals(LiveOverlay.BAR.toList(), LiveOverlay.order("").toList())
        val o = LiveOverlay.order("13,15,13,99,x").toList()
        assertEquals(listOf(13, 15), o.take(2))
        assertEquals(LiveOverlay.BAR.toSet(), o.toSet())
        assertEquals(LiveOverlay.BAR.size, o.size)
    }

    @Test fun aPastShowsChannelsAndMoreWhereTheresRoom() {
        val row = { intArrayOf(LiveOverlay.START_OVER, LiveOverlay.AUDIO, LiveOverlay.LIVE) }
        assertEquals(listOf(LiveOverlay.START_OVER, LiveOverlay.AUDIO, LiveOverlay.CHANNELS, LiveOverlay.MORE, LiveOverlay.LIVE),
            LiveOverlay.withMore(row(), true, { 100f }, 10f, 540f).toList())
        assertEquals(listOf(LiveOverlay.START_OVER, LiveOverlay.AUDIO, LiveOverlay.MORE, LiveOverlay.LIVE),
            LiveOverlay.withMore(row(), true, { 100f }, 10f, 539f).toList())
        assertEquals(row().toList(), LiveOverlay.withMore(row(), true, { 100f }, 10f, 400f).toList())
    }

    @Test fun aMatchsCompetitionAboveIt() {
        assertEquals(listOf("UEFA Champions League Football", "Feyenoord v FC Porto"), LiveOverlay.titleParts("UEFA Champions League Football : Feyenoord v FC Porto").toList())
        assertEquals(listOf("Formula 1", "Las Vegas Grand Prix"), LiveOverlay.titleParts("Formula 1: Las Vegas Grand Prix").toList())
        assertEquals(listOf("", "Joe Pera Talks With You"), LiveOverlay.titleParts("Joe Pera Talks With You").toList())
        assertEquals(listOf("", "Scoreline: Soc"), LiveOverlay.titleParts("Scoreline: Soc").toList())
    }
}
