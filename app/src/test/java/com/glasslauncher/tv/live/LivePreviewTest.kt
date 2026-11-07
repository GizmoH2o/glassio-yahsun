package com.glasslauncher.tv.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the guide previews a channel, and when it leaves the provider alone. */
class LivePreviewTest {
    private fun wanted(on: Boolean = true, smart: Boolean = false, same: Boolean = false, locked: Boolean = false, recording: Boolean = false, resting: Boolean = false) =
        LivePreview.wanted(on, smart, same, locked, recording, resting)

    @Test fun `another channel is previewed`() {
        assertTrue(wanted())
    }

    @Test fun `not with the setting off`() {
        assertFalse(wanted(on = false))
    }

    @Test fun `not a smart row, which isn't a channel`() {
        assertFalse(wanted(smart = true))
    }

    @Test fun `not what's already playing`() {
        assertFalse(wanted(same = true))
    }

    @Test fun `not a locked channel`() {
        assertFalse(wanted(locked = true))
    }

    @Test fun `not while a recording needs the stream`() {
        assertFalse(wanted(recording = true))
    }

    @Test fun `not during a pause after the provider said no`() {
        assertFalse(wanted(resting = true))
    }

    @Test fun `the provider's no-more-streams answers`() {
        for (code in listOf(509, 458, 403, 429)) assertTrue(LivePreview.limit(code))
    }

    @Test fun `other failures aren't the limit`() {
        for (code in listOf(0, 404, 500, 502)) assertFalse(LivePreview.limit(code))
    }

    @Test fun `after a refusal it rests for a while`() {
        assertTrue(LivePreview.resting(1000, 1000 + LivePreview.PAUSE - 1))
        assertFalse(LivePreview.resting(1000, 1000 + LivePreview.PAUSE))
    }

    @Test fun `never refused, never resting`() {
        assertFalse(LivePreview.resting(0, 5000))
    }

    @Test fun `the rest before a preview is long enough to scroll past channels`() {
        assertEquals(1500L, LivePreview.REST)
    }
}
