package com.glasslauncher.tv.live

import org.junit.Assert.assertEquals
import org.junit.Test

/** Glassio's text size carried to Live TV, which only grows. */
class LiveTextSizeTest {
    @Test fun homeSizes() {
        assertEquals(100, LiveOptions.fromHomeSize("small"))
        assertEquals(100, LiveOptions.fromHomeSize("normal"))
        assertEquals(115, LiveOptions.fromHomeSize("large"))
        assertEquals(130, LiveOptions.fromHomeSize("larger"))
        assertEquals(100, LiveOptions.fromHomeSize(null))
    }
}
