package com.glasslauncher.tv.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A Custom design's code: typed on another TV, the same parts and focus color; the four-digit codes from before. */
class LiveDesignCodeTest {
    private fun parts() = (0 until LiveTheme.PARTS).map { LiveTheme.customPart(it) }

    @Test fun eightDigitsComeBackTheSame() {
        for (code in listOf("00000000", "00000006", "04127355", "12345678", "33787662")) {
            assertTrue(code, LiveTheme.readCode(code))
            assertEquals(LiveTheme.CUSTOM, LiveTheme.style())
            assertEquals(code, LiveTheme.code())
        }
    }

    @Test fun eachPartFromAnyDesign() {
        // Every part from every design but Custom, the focus color last.
        assertTrue(LiveTheme.readCode("33787662"))
        assertEquals(List(LiveTheme.PARTS) { LiveTheme.PLUTO }, parts())
        assertEquals(LiveTheme.COUNT - 1, LiveTheme.current())
        assertTrue(LiveTheme.readCode("00000000"))
        assertEquals(List(LiveTheme.PARTS) { LiveTheme.ZERO }, parts())
        assertFalse(parts().contains(LiveTheme.CUSTOM))
    }

    @Test fun notCodes() {
        for (code in listOf("", "123", "12345", "1234567", "123456789", "33787663", "99999999", "abcd1234", "12a4")) {
            assertFalse(code, LiveTheme.readCode(code))
        }
        assertFalse(LiveTheme.readCode(null))
    }

    @Test fun fourDigitsFromBeforeDesigns() {
        // Midnight blue, very round, Sky's layout, white: the closest designs, the focus color as it was.
        assertTrue(LiveTheme.readCode("1246"))
        assertEquals(listOf(LiveTheme.MAX, LiveTheme.ZERO, LiveTheme.APPLE, LiveTheme.SKY, LiveTheme.SKY, LiveTheme.SKY), parts())
        assertEquals(LiveTheme.WHITE, LiveTheme.current())
        assertFalse(LiveTheme.readCode("6000"))     // no seventh background
        assertFalse(LiveTheme.readCode("0300"))     // three roundnesses
        assertFalse(LiveTheme.readCode("0070"))     // Custom wasn't a layout
        assertFalse(LiveTheme.readCode("0007"))     // seven focus colors
    }
}
