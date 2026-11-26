package com.glasslauncher.tv.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordOffTest {
    @Test fun wakesUnlessToldNotTo() {
        assertEquals(RecordOff.WAKE, RecordOff.screenOff(LiveOptions.OFF_ASKS, true, false, false))
        assertEquals(RecordOff.WAKE, RecordOff.screenOff(LiveOptions.OFF_BLACK, true, false, false))
        assertEquals(RecordOff.NOTHING, RecordOff.screenOff(LiveOptions.OFF_TURNS_OFF, true, false, false))
        assertEquals(RecordOff.NOTHING, RecordOff.screenOff(LiveOptions.OFF_ASKS, false, false, false))
    }

    @Test fun offAgainStaysOff() {
        assertEquals(RecordOff.LET_GO, RecordOff.screenOff(LiveOptions.OFF_ASKS, true, true, false))
        assertEquals(RecordOff.NOTHING, RecordOff.screenOff(LiveOptions.OFF_ASKS, true, false, true))
    }

    @Test fun staysOnForOneWithinTenMinutes() {
        assertTrue(RecordOff.waits(1_600_000, 1_000_000))
        assertFalse(RecordOff.waits(1_600_001, 1_000_000))
        assertFalse(RecordOff.waits(0, 1_000_000))
    }

    @Test fun waitsForTheClosedStreamOnlyWhenARecordingWouldBeCutOff() {
        assertEquals(4000L, Live.streamGap(1, 2, 0, 4000))
        assertEquals(1000L, Live.streamGap(1, 2, 3000, 4000))
        assertEquals(0L, Live.streamGap(1, 2, 9000, 4000))
        assertEquals(0L, Live.streamGap(0, 2, 0, 4000))     // nothing recording
        assertEquals(0L, Live.streamGap(1, 3, 0, 4000))     // room for one more
        assertEquals(0L, Live.streamGap(1, 0, 0, 4000))     // the account's limit not known
    }

    @Test fun oneStreamTooMany() {
        assertTrue(LiveStreams.over(2, 1, 2))
        assertFalse(LiveStreams.over(1, 1, 2))
        assertFalse(LiveStreams.over(5, 5, 0))      // the limit not known
    }

    @Test fun offsetsAreWholePackets() {
        assertEquals(0L, LiveStreams.offset(100_000_000, 0, 60_000))
        val half = LiveStreams.offset(100_000_000, 30_000, 60_000)
        assertEquals(0L, half % 188)
        assertTrue(half in 49_999_000..50_000_000)
        assertTrue(LiveStreams.offset(100_000_000, 90_000, 60_000) <= 100_000_000 - 188 * 4096)     // never at its very end
        assertEquals(0L, LiveStreams.offset(1000, 30_000, 60_000))
    }
}
