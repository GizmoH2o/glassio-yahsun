package com.glasslauncher.tv.ui

import com.glasslauncher.tv.live.LiveHome
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the kick-off alert may come up: from kick-off to ten minutes after, for a match on now. */
class KickOffTest {
    private val start = 1_800_000_000_000L
    private fun match(live: Boolean = true, key: String = "603:$start") = LiveHome.MatchDay().apply {
        this.live = live; this.start = this@KickOffTest.start; this.key = key; channel = 603
    }

    @Test fun atKickOff() = assertTrue(KickOff.justStarted(match(), start))
    @Test fun nineMinutesIn() = assertTrue(KickOff.justStarted(match(), start + 9 * 60_000L))
    @Test fun tenMinutesIn() = assertTrue(KickOff.justStarted(match(), start + 10 * 60_000L))
    @Test fun elevenMinutesInIsTooLate() = assertFalse(KickOff.justStarted(match(), start + 11 * 60_000L))
    @Test fun beforeKickOff() = assertFalse(KickOff.justStarted(match(), start - 60_000L))
    @Test fun notLive() = assertFalse(KickOff.justStarted(match(live = false), start + 60_000L))
    @Test fun noKey() = assertFalse(KickOff.justStarted(match(key = ""), start + 60_000L))
    @Test fun noMatch() = assertFalse(KickOff.justStarted(null, start))
}
