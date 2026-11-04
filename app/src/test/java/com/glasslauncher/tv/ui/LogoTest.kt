package com.glasslauncher.tv.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogoTest {
    private val clear = 0x00000000
    private val black = 0xFF101010.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val red = 0xFFE50914.toInt()
    private fun logo(ink: Int, inkShare: Int) = IntArray(100) { if (it < inkShare) ink else clear }

    @Test fun `a black logo on nothing is dark`() = assertTrue(logoIsDark(logo(black, 30)))
    @Test fun `white and coloured logos aren't`() { assertFalse(logoIsDark(logo(white, 30))); assertFalse(logoIsDark(logo(red, 30))) }
    @Test fun `a solid black rectangle isn't, its black is its background`() = assertFalse(logoIsDark(logo(black, 100)))
    @Test fun `an empty picture isn't`() = assertFalse(logoIsDark(logo(black, 0)))
}
