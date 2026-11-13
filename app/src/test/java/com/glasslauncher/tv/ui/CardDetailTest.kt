package com.glasslauncher.tv.ui

import com.glasslauncher.tv.data.HeroItem
import org.junit.Assert.assertEquals
import org.junit.Test

/** The line under every card. */
class CardDetailTest {
    private fun item(rating: String, year: String, genres: List<String>) =
        HeroItem("k", "id", "series", "Name", "", "", "", "", rating, year, genres, "")

    @Test fun line() {
        assertEquals("8.4 · 2026 · Sci-Fi", cardDetail(item("8.4", "2026", listOf("Sci-Fi & Fantasy", "Drama"))))
        assertEquals("2026 · Drama", cardDetail(item("", "2026–", listOf("Drama"))))
        assertEquals("", cardDetail(item("0", "", emptyList())))
    }
}
