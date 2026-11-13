package com.glasslauncher.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** The quality a trailer asks YouTube for: a step above the screen, but 720p at most on a TV with little memory. */
class TrailerQualityTest {
    @Test fun `full screen on a 1080p TV asks for 1080p`() {
        assertEquals("hd1080", youTubeQuality(1080))
    }

    @Test fun `a TV with little memory gets 720p at most`() {
        assertEquals("hd720", youTubeQuality(1080, smallTv = true))
    }

    @Test fun `a small player stays small either way`() {
        assertEquals("medium", youTubeQuality(200, smallTv = true))
        assertEquals("medium", youTubeQuality(200))
    }
}
