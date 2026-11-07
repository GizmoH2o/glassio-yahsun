package com.glasslauncher.tv.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** Recordings that won't all fit, and where they go. */
class LiveRecordingRulesTest {
    private fun clash(limit: Int, vararg times: LongArray) = LiveSchedule.clashes(times.toList(), limit).toList()

    @Test fun `recordings within the limit don't clash`() {
        assertEquals(emptyList<Int>(), clash(2, longArrayOf(100, 200), longArrayOf(150, 250)))
    }

    @Test fun `the one past the limit clashes, not the ones before it`() {
        assertEquals(listOf(2), clash(2, longArrayOf(100, 200), longArrayOf(110, 200), longArrayOf(120, 200)))
    }

    @Test fun `one after another never clash`() {
        assertEquals(emptyList<Int>(), clash(1, longArrayOf(100, 200), longArrayOf(200, 300)))
    }

    @Test fun `a limit of one means any overlap`() {
        assertEquals(listOf(1), clash(1, longArrayOf(100, 200), longArrayOf(150, 250)))
    }

    @Test fun `the earlier start wins, whatever the order they were set`() {
        assertEquals(listOf(0), clash(1, longArrayOf(150, 250), longArrayOf(100, 200)))
    }

    @Test fun `overlaps at different moments don't add up`() {
        assertEquals(emptyList<Int>(), clash(2, longArrayOf(100, 200), longArrayOf(150, 350), longArrayOf(300, 400)))
    }

    private val tv = File("/tv/Recordings")
    private val usb = File("/usb/Recordings")

    @Test fun `recordings stay on the TV unless asked`() {
        assertEquals(tv, LiveRecordings.pick(listOf(tv, usb), false))
    }

    @Test fun `asked, they go to the drive`() {
        assertEquals(usb, LiveRecordings.pick(listOf(tv, usb), true))
    }

    @Test fun `with no drive plugged in they stay on the TV`() {
        assertEquals(tv, LiveRecordings.pick(listOf(tv), true))
        assertEquals(tv, LiveRecordings.pick(listOf(tv, null), true))
    }

    @Test fun `with nowhere to go there's no folder`() {
        assertNull(LiveRecordings.pick(listOf<File?>(null), false))
    }
}
