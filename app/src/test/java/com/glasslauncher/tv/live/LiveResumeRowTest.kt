package com.glasslauncher.tv.live

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which past shows left partway can still be carried on from Home. */
class LiveResumeRowTest {
    private val hour = 3600_000L
    private val day = 24 * hour
    private val now = 100 * day

    private fun left(ago: Long) = LiveResume.Entry().apply { program.start = now - ago }

    @Test fun `a show still in the channel's catch-up can`() {
        assertTrue(LiveResume.canCarryOn(left(2 * day), 3, now))
    }

    @Test fun `one past it can't`() {
        assertFalse(LiveResume.canCarryOn(left(4 * day), 3, now))
    }

    @Test fun `one about to fall out of it can't`() {
        assertFalse(LiveResume.canCarryOn(left(3 * day - hour / 2), 3, now))
    }

    @Test fun `a channel without catch-up can't`() {
        assertFalse(LiveResume.canCarryOn(left(hour), 0, now))
    }
}
