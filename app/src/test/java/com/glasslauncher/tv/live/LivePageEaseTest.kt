package com.glasslauncher.tv.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Live TV's channel change on Glassio's page-change curve (cubic-bezier 0.2, 0.8, 0.2, 1). */
class LivePageEaseTest {
    @Test fun curve() {
        assertEquals(0f, LiveView.pageEase(0f), 0.001f)
        assertEquals(1f, LiveView.pageEase(1f), 0.001f)
        assertTrue(LiveView.pageEase(0.25f) > 0.6f)       // eases out: most of the way early
        assertTrue(LiveView.pageEase(0.5f) < LiveView.pageEase(0.6f))
    }
}
