package com.glasslauncher.tv.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Live TV for everyone: what TalkBack hears, text made larger, and high contrast over any design. */
class LiveAccessTest {
    // ── what's said

    @Test fun `a channel as it's said, with its country`() {
        assertEquals("622, Sky Sports News, UK", LiveSpeech.channel(622, "Sky Sports News", "UK"))
        assertEquals("Sky Sports News", LiveSpeech.channel(0, "Sky Sports News", ""))
        assertEquals("16227, ESPN, Arabic", LiveSpeech.channel(16227, "ESPN", LiveCountry.ARABIC))
    }

    @Test fun `a show as it's said, its state after it`() {
        assertEquals("8:00 PM, Gogglebox, 622, Channel 4, UK, on now, reminder set",
            LiveSpeech.show("8:00 PM", "Gogglebox", "622, Channel 4, UK", "on now", "reminder set"))
        assertEquals("8:00 PM, Gogglebox", LiveSpeech.show("8:00 PM", "Gogglebox", "", "", ""))
    }

    @Test fun `nothing empty is said`() {
        assertEquals("Gogglebox", LiveSpeech.join("", "Gogglebox", " ", null))
    }

    // ── larger text

    @Test fun `text grows by the size chosen`() {
        assertEquals(20f, Ui.scaled(20f, 100), 0.001f)
        assertEquals(23f, Ui.scaled(20f, 115), 0.001f)
        assertEquals(26f, Ui.scaled(20f, 130), 0.001f)
        assertEquals(20f, Ui.scaled(20f, 0), 0.001f)        // unset: as it was
    }

    // ── high contrast

    @Test fun `high contrast over a design - black, solid, white edges, yellow focus with black on it`() {
        LiveTheme.contrast(true)
        try {
            assertEquals(0xFF000000.toInt(), LiveTheme.bg)
            assertEquals(0xFF000000.toInt(), LiveTheme.bgTop)
            assertEquals(0xFF, LiveTheme.panel ushr 24)
            assertEquals(0xFF, LiveTheme.card ushr 24)
            assertEquals(0xFFFFD60A.toInt(), LiveTheme.tile)
            assertEquals(0xFF000000.toInt(), LiveTheme.ink)
            assertTrue(LiveTheme.rim ushr 24 >= 0xB0)
            assertEquals(0xFF, LiveTheme.chip() ushr 24)
            assertEquals(0xFF, LiveTheme.sheet() ushr 24)
        } finally {
            LiveTheme.contrast(false)
        }
    }
}
