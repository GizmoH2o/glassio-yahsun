package com.glasslauncher.tv.live

import org.junit.Assert.assertEquals
import org.junit.Test

class TrickleTest {
    private fun run(perSecond: Int, streamPerSecond: Long): Int {
        val t = Trickle()
        var out = Trickle.UNKNOWN
        for (s in 0..30) out = t.add(1000L + s * 1000L, streamPerSecond * (s + 1), perSecond)
        return out
    }

    @Test fun aTrickleIsSlowAndALiveStreamIsnt() {
        assertEquals(Trickle.SLOW, run(8_000, 12))
        assertEquals(Trickle.FINE, run(900_000, 1000))
        assertEquals(Trickle.FINE, run(40_000, 1000))      // a small picture, but as fast as it plays
    }

    @Test fun aClockThatStandsStillInAFullStreamIsntATrickle() = assertEquals(Trickle.FINE, run(900_000, 1))

    @Test fun radioHasNoClock() {
        assertEquals(Trickle.FINE, run(8_000, 0))
        assertEquals(Trickle.SLOW, run(500, 0))
    }

    @Test fun saidOnceEachHalfMinute() {
        val t = Trickle()
        assertEquals(Trickle.UNKNOWN, t.add(0, 1, 10))
        assertEquals(Trickle.UNKNOWN, t.add(29_999, 1, 10))
        assertEquals(Trickle.SLOW, t.add(30_000, 2, 10))
        assertEquals(Trickle.UNKNOWN, t.add(31_000, 2, 10))
    }

    @Test fun theNextSourceIsTheBestNotTried() {
        val tried = mutableSetOf(1)
        assertEquals(0, RecordService.nextSource(listOf(3, 2, 1), 1, tried))        // not past the best
        tried.add(3)
        assertEquals(1, RecordService.nextSource(listOf(3, 2, 1), 3, tried))
        tried.add(2)
        assertEquals(2, RecordService.nextSource(listOf(3, 2, 1), 2, tried))        // all tried: round again
        assertEquals(setOf(2), tried)
        assertEquals(0, RecordService.nextSource(listOf(7), 7, mutableSetOf(7)))
    }

    @Test fun theOneThatWorkedFirstAndTheFailingOnesLast() {
        assertEquals(listOf(3, 1, 2), RecordService.order(listOf(1, 2, 3), 1, 3, setOf<Int>()))
        assertEquals(listOf(1, 3, 2), RecordService.order(listOf(2, 3, 1), 2, 1, setOf(1, 2)))
        assertEquals(listOf(2, 3, 1), RecordService.order(listOf(1, 2, 3), 1, 0, setOf(1)))
        assertEquals(listOf(1, 2), RecordService.order(listOf(2, 1), 1, 0, setOf(1, 2)))
    }
}
