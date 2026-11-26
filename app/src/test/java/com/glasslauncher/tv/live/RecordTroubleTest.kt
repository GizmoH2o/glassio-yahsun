package com.glasslauncher.tv.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordTroubleTest {
    private fun rec() = LiveRecordings.Rec().apply { state = LiveRecordings.DONE; file = "x" }

    @Test fun onlyRealHolesCount() {
        val r = rec()
        LiveRecordings.gap(r, 0, 50_000)            // nothing before: not a hole
        LiveRecordings.gap(r, 100_000, 109_000)     // too short
        assertEquals(0L, r.missing())
        LiveRecordings.gap(r, 100_000, 125_000)
        assertEquals(25_000L, r.missing())
        assertFalse(LiveRecordings.trouble(r))
        LiveRecordings.gap(r, 200_000, 220_000)
        assertTrue(LiveRecordings.trouble(r))
        r.dismissed = true
        assertFalse(LiveRecordings.trouble(r))
    }

    @Test fun failedAndCutShortNeedAttention() {
        assertFalse(LiveRecordings.trouble(rec()))
        assertTrue(LiveRecordings.trouble(rec().apply { state = LiveRecordings.FAILED }))
        assertTrue(LiveRecordings.trouble(rec().apply { why = "the TV was off" }))
        assertFalse(LiveRecordings.trouble(rec().apply { state = LiveRecordings.RECORDING; why = "x" }))
    }

    @Test fun catchupOnlyWhenOverAndStillThere() {
        val day = 24 * 3600_000L
        val now = 100 * day
        assertTrue(LiveRecordings.inCatchup(now - day, now - day / 2, 2, now))
        assertFalse(LiveRecordings.inCatchup(now - day, now + 1, 2, now))
        assertFalse(LiveRecordings.inCatchup(now - 3 * day, now - 2 * day, 2, now))
        assertFalse(LiveRecordings.inCatchup(now - day, now - day / 2, 0, now))
    }

    private fun packet(ms: Long): ByteArray {
        val v = ms * 90
        val b = ByteArray(188)
        b[0] = 0x47; b[2] = 0x21; b[3] = 0x20; b[4] = 20; b[5] = 0x10
        b[6] = (v shr 25).toByte(); b[7] = (v shr 17).toByte(); b[8] = (v shr 9).toByte(); b[9] = (v shr 1).toByte(); b[10] = ((v and 1) shl 7).toByte()
        return b
    }

    @Test fun clockAddsTheStreamsOwnTimeAndSkipsJumps() {
        val c = TsClock()
        val all = listOf(1000L, 3000L, 5000L, 900_000L, 902_000L, 10L, 1010L).map { packet(it) }.reduce { a, b -> a + b }
        c.feed(all, 0, 100)             // a packet split across reads
        c.feed(all, 100, all.size - 100)
        assertEquals(4000L + 2000L + 1000L, c.ms())
        c.at(60_000)
        c.feed(packet(0) + packet(500), 0, 376)
        assertEquals(60_500L, c.ms())
    }
}
