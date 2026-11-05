package com.glasslauncher.tv.live

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightModeTest {
    @Test fun `on from its hour until the morning`() {
        assertTrue(NightMode.on(22, 22))
        assertTrue(NightMode.on(22, 23))
        assertTrue(NightMode.on(22, 3))
        assertFalse(NightMode.on(22, 7))
        assertFalse(NightMode.on(22, 21))
    }

    @Test fun `off is never`() {
        assertFalse(NightMode.on(0, 23))
        assertFalse(NightMode.on(0, 3))
    }
}
