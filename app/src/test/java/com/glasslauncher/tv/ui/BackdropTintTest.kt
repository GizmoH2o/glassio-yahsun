package com.glasslauncher.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The page's colour from a backdrop's pixels: dark always, and none for a picture with no real colour in it. */
class BackdropTintTest {
    private fun red(c: Int) = (c shr 16) and 0xFF
    private fun green(c: Int) = (c shr 8) and 0xFF
    private fun blue(c: Int) = c and 0xFF
    private fun picture(rgb: Int, n: Int = 100) = IntArray(n) { 0xFF000000.toInt() or rgb }

    @Test fun `an orange picture gives a dark orange`() {
        val c = BackdropTint.of(picture(0xF08030))!!
        assertTrue(red(c) > green(c))
        assertTrue(green(c) > blue(c))
    }

    @Test fun `a blue picture gives a dark blue`() {
        val c = BackdropTint.of(picture(0x2050E0))!!
        assertTrue(blue(c) > green(c))
        assertTrue(green(c) > red(c))
    }

    @Test fun `the tint is opaque`() {
        assertEquals(0xFF, (BackdropTint.of(picture(0xF08030))!! ushr 24) and 0xFF)
    }

    @Test fun `even the brightest colour stays dark enough for white text`() {
        for (rgb in listOf(0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0x00FFFF, 0xFF00FF)) {
            val c = BackdropTint.of(picture(rgb))!!
            assertTrue("%06X".format(rgb), maxOf(red(c), green(c), blue(c)) <= 48)
        }
    }

    @Test fun `a dim colourful picture is tinted as strongly as a bright one`() {
        assertEquals(BackdropTint.of(picture(0xF08030)), BackdropTint.of(picture(0x784018)))
    }

    @Test fun `a black and white picture keeps the neutral page`() {
        assertNull(BackdropTint.of(IntArray(100) { i -> 0xFF000000.toInt() or (if (i % 2 == 0) 0xFFFFFF else 0x808080) }))
    }

    @Test fun `a near-black picture keeps the neutral page`() {
        assertNull(BackdropTint.of(picture(0x100804)))
    }

    @Test fun `a speck of colour keeps the neutral page`() {
        val px = picture(0x808080)
        for (i in 0 until 3) px[i] = 0xFFF00000.toInt()
        assertNull(BackdropTint.of(px))
    }

    @Test fun `the colourful part decides, not the grey around it`() {
        val px = picture(0x808080)
        for (i in 0 until 30) px[i] = 0xFF2050E0.toInt()
        val c = assertNotNullAnd(BackdropTint.of(px))
        assertTrue(blue(c) > red(c))
    }

    @Test fun `nothing to read keeps the neutral page`() {
        assertNull(BackdropTint.of(IntArray(0)))
    }

    private fun assertNotNullAnd(c: Int?): Int { assertNotNull(c); return c!! }
}
