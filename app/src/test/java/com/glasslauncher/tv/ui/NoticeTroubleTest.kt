package com.glasslauncher.tv.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which notices get the "not that" sound. */
class NoticeTroubleTest {
    @Test fun trouble() {
        assertTrue(Notice.looksLikeTrouble("Nuvio: couldn't remove Heat"))
        assertTrue(Notice.looksLikeTrouble("Live TV isn't set up yet"))
        assertTrue(Notice.looksLikeTrouble("This screen is not available on this device"))
        assertFalse(Notice.looksLikeTrouble("Added to My List · Heat"))
        assertFalse(Notice.looksLikeTrouble("Back online"))
    }
}
