package com.glasslauncher.tv.live

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideJobTest {
    private val hour = 3600_000L

    @Test fun `a guide past its hours is fetched`() {
        assertTrue(GuideJob.overdue(7 * hour, 6))
    }

    @Test fun `a wake-up just early still counts`() {
        assertTrue(GuideJob.overdue(5 * hour + 45 * 60_000L, 6))
    }

    @Test fun `a fresh guide is left alone`() {
        assertFalse(GuideJob.overdue(2 * hour, 6))
    }

    @Test fun `a clock that went back fetches anew`() {
        assertTrue(GuideJob.overdue(-1, 6))
    }
}
