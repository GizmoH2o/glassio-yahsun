package com.glasslauncher.tv.live

import org.junit.Assert.assertEquals
import org.junit.Test

/** A guide title as words for Glassio's Search (Live TV's More info with no title page). */
class LiveSearchNameTest {
    @Test fun cleans() {
        assertEquals("The Lost World", LiveView.searchName("The Lost World"))
        assertEquals("Doctor Who", LiveView.searchName("New: Doctor Who"))
        assertEquals("Heat", LiveView.searchName("Heat (1995)"))
        assertEquals("Grey's Anatomy", LiveView.searchName("Grey's Anatomy - S12E4"))
        assertEquals("Teleshopping", LiveView.searchName("  Teleshopping "))
        assertEquals("S13 · E225", LiveView.searchName("S13 · E225"))
    }
}
