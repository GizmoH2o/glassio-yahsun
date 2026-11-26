package com.glasslauncher.tv.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReserveTest {
    @Test fun waitsForTheReserveButNotForever() {
        assertFalse(LiveOptions.Unstuck.goOn(6_000, 8_000, 0))
        assertTrue(LiveOptions.Unstuck.goOn(8_000, 8_000, 0))
        assertTrue(LiveOptions.Unstuck.goOn(6_000, 8_000, 7_000))
        assertTrue(LiveOptions.Unstuck.goOn(2_500, 0, 0))
    }

    @Test fun aFreezeAndItsReconnectAreOneStop() {
        assertEquals(1, LiveHealth.stops(longArrayOf(1_000, 1_000, 9_000), 0))
        assertEquals(2, LiveHealth.stops(longArrayOf(1_000, 1_000, 47_000, 47_000), 0))
        assertEquals(1, LiveHealth.stops(longArrayOf(1_000, 47_000), 40_000))
        assertEquals(0, LiveHealth.stops(longArrayOf(), 0))
    }
}
