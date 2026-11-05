package com.glasslauncher.tv.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** A past show's file that the provider is still adding to. */
class LiveGrowingFileTest {
    /** `sizes`: how big the file is at each open; `refused`: the opens that fail. */
    private class Server(private vararg val sizes: Int, private val refused: Set<Int> = emptySet()) : LiveGrowingFile.Bytes {
        var opens = 0
        val from = ArrayList<Long>()
        private var left = 0

        override fun open(position: Long): Long {
            val size = sizes[minOf(opens, sizes.size - 1)]
            opens++
            from.add(position)
            if (opens - 1 in refused) throw IOException("416")
            left = (size - position).toInt().coerceAtLeast(0)
            return left.toLong()
        }

        override fun read(into: ByteArray, offset: Int, length: Int): Int {
            if (left == 0) return -1
            val n = minOf(left, length)
            left -= n
            return n
        }

        override fun close() {}
    }

    private fun readAll(file: LiveGrowingFile): Int {
        val buffer = ByteArray(4)
        var all = 0
        while (true) {
            val n = file.read(buffer, 0, buffer.size)
            if (n < 0) return all
            all += n
        }
    }

    @Test fun `a file that isn't growing says its length and ends at its end`() {
        val server = Server(10)
        val file = LiveGrowingFile(server, {}, 5)
        assertEquals(10, file.open(0, false))
        assertEquals(10, readAll(file))
        assertEquals(1, server.opens)
    }

    @Test fun `a growing file hides its length, so the reader doesn't go looking for its end`() {
        val file = LiveGrowingFile(Server(10), {}, 5)
        assertEquals(LiveGrowingFile.UNKNOWN, file.open(0, true))
    }

    @Test fun `the end of what was there goes on from the same byte`() {
        val server = Server(10, 25, 25)
        val file = LiveGrowingFile(server, {}, 3)
        file.open(0, true)
        assertEquals(25, readAll(file))
        assertEquals(10L, server.from[1])
    }

    @Test fun `it waits for more, then gives up`() {
        var waits = 0
        val server = Server(10)
        val file = LiveGrowingFile(server, { waits++ }, 3)
        file.open(0, true)
        assertEquals(10, readAll(file))
        assertEquals(3, waits)
    }

    @Test fun `a refused try counts as nothing new yet`() {
        val server = Server(10, 10, 20, refused = setOf(1))
        val file = LiveGrowingFile(server, {}, 5)
        file.open(0, true)
        assertEquals(20, readAll(file))
    }

    @Test fun `new data starts the patience again`() {
        val server = Server(4, 4, 8, 8, 12, 12)
        val file = LiveGrowingFile(server, {}, 2)
        file.open(0, true)
        assertEquals(12, readAll(file))
    }

    @Test fun `a stream with no length of its own is left alone`() {
        val server = object : LiveGrowingFile.Bytes {
            var opens = 0
            override fun open(position: Long): Long {
                opens++
                return LiveGrowingFile.UNKNOWN
            }
            override fun read(into: ByteArray, offset: Int, length: Int) = -1
            override fun close() {}
        }
        val file = LiveGrowingFile(server, {}, 5)
        file.open(0, true)
        assertEquals(-1, file.read(ByteArray(4), 0, 4))
        assertEquals(1, server.opens)
    }

    @Test fun `a show is still growing while it airs and for a while after`() {
        val end = 1_000_000L
        assertTrue(LiveCatchup.stillGrowing(end, end - 60_000))
        assertTrue(LiveCatchup.stillGrowing(end, end + 60_000))
        assertFalse(LiveCatchup.stillGrowing(end, end + 3_600_000))
    }
}
