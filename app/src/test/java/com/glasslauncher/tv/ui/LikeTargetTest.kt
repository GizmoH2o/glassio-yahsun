package com.glasslauncher.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** "Something like X" in Search. */
class LikeTargetTest {
    @Test fun phrases() {
        assertEquals("Severance", likeTarget("something like Severance"))
        assertEquals("Heat", likeTarget("Similar to Heat?"))
        assertEquals("Dark", likeTarget("shows like Dark"))
        assertNull(likeTarget("Severance"))
        assertNull(likeTarget("like"))
        assertNull(likeTarget("Unlike us"))
    }
}
