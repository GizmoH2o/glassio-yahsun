package com.glasslauncher.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Home's row for the time of year. */
class SeasonRowTest {
    @Test fun months() {
        assertEquals("Christmas films", Recs.seasonQuery(12)?.first)
        assertEquals("Halloween", Recs.seasonQuery(10)?.first)
        assertEquals("Summer blockbusters", Recs.seasonQuery(7)?.first)
        assertNull(Recs.seasonQuery(11))
    }
}
