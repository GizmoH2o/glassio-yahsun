package com.glasslauncher.tv.live

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The player's buffer. Since media3 1.11 a local file (a recording) gets its own rules: loading starts again only under
 * 1 s buffered. This TV's video decoder wants a few seconds ahead, so a recording stopped at 49 s with 2.7 s buffered and
 * never loaded again. Recordings now buffer as streams do.
 */
class LiveBufferTest {
    @Test fun `every buffer setting starts loading again long before the decoder runs dry`() {
        for (b in listOf(LiveOptions.NORMAL, LiveOptions.LARGE, LiveOptions.EXTRA)) {
            val ms = LiveOptions.bufferMs(b)
            assertTrue("setting $b loads again under ${ms[0]} ms", ms[0] >= 15_000)
            assertTrue(ms[1] >= ms[0])
        }
    }

    @Test fun `the usual buffer is media3's for streams, so live TV is unchanged`() {
        assertArrayEquals(intArrayOf(50_000, 50_000, 1_000, 2_000), LiveOptions.bufferMs(LiveOptions.NORMAL))
    }

    @Test fun `the larger buffers are as they were`() {
        assertArrayEquals(intArrayOf(30_000, 60_000, 5_000, 10_000), LiveOptions.bufferMs(LiveOptions.LARGE))
        assertArrayEquals(intArrayOf(50_000, 120_000, 10_000, 20_000), LiveOptions.bufferMs(LiveOptions.EXTRA))
    }
}
