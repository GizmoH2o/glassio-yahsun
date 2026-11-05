package com.glasslauncher.tv.data

import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionTestTest {
    @Test fun `no internet comes first`() {
        assertTrue(ConnectionTest.verdict(-1, 0f, -1).startsWith("No internet"))
    }

    @Test fun `a provider not answering is told apart`() {
        assertTrue(ConnectionTest.verdict(30, 50f, -1).contains("provider isn't"))
    }

    @Test fun `slow internet is told`() {
        assertTrue(ConnectionTest.verdict(30, 4f, 100).contains("slow"))
    }

    @Test fun `all well, or a list with no account to ask`() {
        assertTrue(ConnectionTest.verdict(30, 50f, 100).contains("fine"))
        assertTrue(ConnectionTest.verdict(30, 50f, 0).contains("fine"))
    }
}
