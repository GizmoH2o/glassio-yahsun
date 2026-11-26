package com.glasslauncher.tv.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiviewRulesTest {
    @Test fun frozenOnlyOnceItHasPlayed() {
        assertFalse(LiveMultiRules.frozen(true, false, 1_000, 60_000))      // still opening
        assertFalse(LiveMultiRules.frozen(true, true, 0, 60_000))
        assertFalse(LiveMultiRules.frozen(true, true, 50_000, 60_000))
        assertTrue(LiveMultiRules.frozen(true, true, 40_000, 60_000))
        assertFalse(LiveMultiRules.frozen(false, true, 40_000, 60_000))     // waiting for data: longer
        assertTrue(LiveMultiRules.frozen(false, true, 30_000, 60_000))
    }

    @Test fun secondsRoundUp() {
        assertEquals(3, LiveMultiRules.seconds(3_000, 0))
        assertEquals(1, LiveMultiRules.seconds(3_000, 2_999))
        assertEquals(0, LiveMultiRules.seconds(3_000, 3_000))
    }

    @Test fun buttonsThenTheNextBox() {
        assertEquals(1, LiveMultiRules.button(0, 3, true))
        assertEquals(-1, LiveMultiRules.button(2, 3, true))
        assertEquals(-1, LiveMultiRules.button(0, 3, false))
    }
}
