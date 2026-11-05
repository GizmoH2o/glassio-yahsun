package com.glasslauncher.tv.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamInfoTest {
    @Test fun `half-time is told from a match's status`() {
        assertTrue(LiveScores.halfTime("STATUS_HALFTIME", "HT"))
        assertTrue(LiveScores.halfTime("STATUS_IN_PROGRESS", " ht "))
        assertFalse(LiveScores.halfTime("STATUS_IN_PROGRESS", "63'"))
    }
}
