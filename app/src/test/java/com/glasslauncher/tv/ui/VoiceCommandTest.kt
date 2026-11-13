package com.glasslauncher.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Voice commands and "Did you mean". */
class VoiceCommandTest {
    @Test fun commands() {
        assertEquals(101, (voiceCommand("Open My List") as VoiceCommand.Page).code)
        assertEquals(102, (voiceCommand("go to Live TV") as VoiceCommand.Page).code)
        assertTrue(voiceCommand("channel 264") is VoiceCommand.Channel)
        assertEquals("Breaking Bad", (voiceCommand("play Breaking Bad") as VoiceCommand.Play).title)
        assertNull(voiceCommand("breaking bad"))
    }

    @Test fun didYouMean() {
        val names = listOf("Breaking Bad", "The Office", "Dexter")
        assertEquals("Breaking Bad", closestName("brekaing bad", names))
        assertNull(closestName("severance", names))
        assertNull(closestName("dexter", names))
    }
}
