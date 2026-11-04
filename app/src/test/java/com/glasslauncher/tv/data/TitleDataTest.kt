package com.glasslauncher.tv.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleDataTest {
    @Test fun `a series' episodes by season, specials last, repeats once`() {
        val d = TitleDetails.parse(JSONObject("""{"meta":{"description":"About it","imdbRating":"8.44","releaseInfo":"2019-2022","genres":["Drama","Mystery"],"cast":["A","B"],
            "videos":[{"season":2,"episode":1,"name":"Two one","released":"2021-01-01T00:00:00.000Z"},{"season":1,"episode":2,"name":"One two"},
            {"season":1,"number":1,"title":"One one"},{"season":0,"episode":1,"name":"Special"},{"season":1,"episode":2,"name":"Again"},{"name":"no numbers"}]}}"""))
        assertEquals(listOf(1, 2, 0), d.seasons.keys.toList())
        assertEquals(listOf("One one", "One two"), d.seasons[1]!!.map { it.name })
        assertEquals("8.4", d.rating)
        assertEquals("2019", d.year)
        assertEquals(1609459200000L, d.seasons[2]!![0].released)
        assertEquals(listOf("A", "B"), d.cast)
    }

    @Test fun `a page without a meta is empty`() {
        assertEquals(TitleDetails.NONE, TitleDetails.parse(JSONObject("{}")))
    }

    @Test fun `TMDB results become titles`() {
        val o = JSONObject("""{"id":5,"name":"Show","poster_path":"/p.jpg","backdrop_path":"/b.jpg","overview":"O","vote_average":7.56,"first_air_date":"2024-05-01","genre_ids":[18,99]}""")
        val h = Recs.parseTmdb(o, "tv", "tt123", mapOf(18 to "Drama"))!!
        assertEquals("series:tt123", h.key)
        assertEquals("series", h.type)
        assertEquals(listOf("Drama"), h.genres)
        assertEquals("7.6", h.rating)
        assertEquals("2024", h.year)
        assertTrue(h.poster.endsWith("w500/p.jpg"))
        assertTrue(h.background.endsWith("w1280/b.jpg"))
        assertNull(Recs.parseTmdb(JSONObject("""{"title":"No pictures"}"""), "movie", "tt1", emptyMap()))
    }

    @Test fun `Cinemeta charts become titles, without those lacking an id or poster`() {
        val r = JSONObject("""{"metas":[{"id":"tt1","name":"A","poster":"https://x/a.jpg","imdbRating":"7.0","releaseInfo":"2020"},{"id":"kitsu:1","name":"B","poster":"https://x/b.jpg"},{"id":"tt3","name":"C"}]}""")
        val l = Recs.parseCinemeta(r, "movie")
        assertEquals(listOf("movie:tt1"), l.map { it.key })
        assertEquals("https://x/a.jpg", l[0].background)
    }

    @Test fun `interleaving keeps turns and drops repeats`() {
        fun h(k: String) = HeroItem(k, k, "movie", k, "", "", "", "", "", "", emptyList(), "")
        assertEquals(listOf("a", "x", "b"), Recs.interleave(listOf(h("a"), h("b")), listOf(h("x"), h("a"))).map { it.key })
    }
}
