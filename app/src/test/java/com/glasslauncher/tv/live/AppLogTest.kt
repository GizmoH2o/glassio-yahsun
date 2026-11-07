package com.glasslauncher.tv.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Live TV's own short log: the system's is gone within a minute on a busy TV. */
class AppLogTest {
    @Test fun `what fits stays as it is`() {
        assertEquals("one\ntwo\n", AppLog.tail("one\ntwo\n", 100))
    }

    @Test fun `the oldest lines go, whole`() {
        assertEquals("three\n", AppLog.tail("one\ntwo\nthree\n", 8))
    }

    @Test fun `a very long line never leaves half a line`() {
        val out = AppLog.tail("aaaaaaaaaaaaaaaaaaaa\nbb\n", 5)
        assertTrue(out, out == "bb\n")
    }
}
