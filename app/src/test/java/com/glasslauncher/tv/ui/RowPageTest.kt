package com.glasslauncher.tv.ui

import com.glasslauncher.tv.data.Browse
import com.glasslauncher.tv.data.HeroItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A row opened as a page (its filters and sorts), and which titles a genre's page takes. */
class RowPageTest {
    private fun t(key: String, genres: List<String> = emptyList(), rating: String = "", year: String = "", runtime: String = "") =
        HeroItem(key, key, "movie", key, "", "", "", "", rating, year, genres, "", runtime = runtime)

    private val row = listOf(
        t("Zed", listOf("Drama"), "6.1", "2001", "2h 10m"),
        t("alpha", listOf("Comedy"), "8.2", "2024", "95 min"),
        t("Mid", listOf("Drama", "Comedy"), "7.0", "2015"),
    )

    private fun keys(l: List<HeroItem>) = l.map { it.key }

    @Test fun `all keeps the row's own order`() = assertEquals(listOf("Zed", "alpha", "Mid"), keys(rowShown(row, "all", RowSort.Row, emptySet())))

    @Test fun `not started leaves out what's begun`() = assertEquals(listOf("alpha", "Mid"), keys(rowShown(row, "new", RowSort.Row, setOf("Zed"))))

    @Test fun `a genre keeps only its titles, any case`() = assertEquals(listOf("alpha", "Mid"), keys(rowShown(row, "comedy", RowSort.Row, emptySet())))

    @Test fun `under 2 hours leaves out the long and the unknown`() = assertEquals(listOf("alpha"), keys(rowShown(row, "short", RowSort.Row, emptySet())))

    @Test fun `sorts by name, rating and year`() {
        assertEquals(listOf("alpha", "Mid", "Zed"), keys(rowShown(row, "all", RowSort.Name, emptySet())))
        assertEquals(listOf("alpha", "Mid", "Zed"), keys(rowShown(row, "all", RowSort.Rating, emptySet())))
        assertEquals(listOf("alpha", "Mid", "Zed"), keys(rowShown(row, "all", RowSort.Newest, emptySet())))
    }

    @Test fun `a genre page matches the names catalogs give it`() {
        val scifi = Browse.genreNamed("Sci-Fi")!!
        assertTrue(Browse.inGenre(t("a", listOf("Sci-Fi & Fantasy")), scifi))
        assertTrue(Browse.inGenre(t("b", listOf("Science Fiction")), scifi))
        assertFalse(Browse.inGenre(t("c", listOf("Drama")), scifi))
    }

    @Test fun `kids get the gentle genres only`() {
        val kids = Browse.genres(true).map { it.name }
        assertTrue("Animation" in kids && "Family" in kids)
        assertFalse("Horror" in kids || "Crime" in kids)
    }

    @Test fun `genre and person pages are told apart from titles`() {
        assertTrue(Browse.isPage(Browse.genrePage("Comedy")))
        assertTrue(Browse.isPage(Browse.personPage(com.glasslauncher.tv.data.TitlePeople.Person(31, "Tom Hanks", "Acting", ""))))
        assertFalse(Browse.isPage(t("x")))
    }
}
