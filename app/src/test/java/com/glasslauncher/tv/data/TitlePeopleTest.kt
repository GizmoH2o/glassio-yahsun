package com.glasslauncher.tv.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Who a title page shows, and which of a person's titles. */
class TitlePeopleTest {
    @Test fun `the cast comes in billing order with photo and role`() {
        val p = TitlePeople.parse(JSONObject("""{"credits":{"cast":[
            {"id":2,"name":"Second","character":"B","order":1},
            {"id":1,"name":"First","character":"A","order":0,"profile_path":"/a.jpg"}]}}"""), true)
        assertEquals(listOf("First", "Second"), p.cast.map { it.name })
        assertEquals("A", p.cast[0].role)
        assertEquals("https://image.tmdb.org/t/p/w185/a.jpg", p.cast[0].photo)
        assertEquals("", p.cast[1].photo)
    }

    @Test fun `no more of the cast than the row holds`() {
        val cast = (0 until 40).joinToString(",") { """{"id":$it,"name":"P$it","order":$it}""" }
        assertEquals(TitlePeople.CAST, TitlePeople.parse(JSONObject("""{"credits":{"cast":[$cast]}}"""), true).cast.size)
    }

    @Test fun `someone without a name is left out`() {
        assertTrue(TitlePeople.parse(JSONObject("""{"credits":{"cast":[{"id":1,"name":""}]}}"""), true).cast.isEmpty())
    }

    @Test fun `a film's makers are its directors`() {
        val p = TitlePeople.parse(JSONObject("""{"credits":{"crew":[
            {"name":"Writer","job":"Writer"},{"name":"Dir One","job":"Director"},{"name":"Dir One","job":"Director"},{"name":"Dir Two","job":"Director"}]}}"""), true)
        assertEquals(listOf("Dir One", "Dir Two"), p.makers)
    }

    @Test fun `a series' makers are its creators`() {
        val p = TitlePeople.parse(JSONObject("""{"created_by":[{"name":"Maker"}],"credits":{"crew":[{"name":"Dir","job":"Director"}]}}"""), false)
        assertEquals(listOf("Maker"), p.makers)
    }

    @Test fun `no credits, nothing to show`() {
        assertEquals(TitlePeople.NONE, TitlePeople.parse(JSONObject("{}"), true))
    }

    private fun credit(id: Int, votes: Int, extra: String = "") =
        """{"id":$id,"media_type":"movie","vote_count":$votes,"character":"Hero"$extra}"""

    private fun ids(json: String) = TitlePeople.known(JSONObject(json)).map { it.optInt("id") }

    @Test fun `a person's best known titles first`() {
        assertEquals(listOf(2, 1), ids("""{"cast":[${credit(1, 5)},${credit(2, 900)}]}"""))
    }

    @Test fun `a title played twice is one card`() {
        assertEquals(listOf(1), ids("""{"cast":[${credit(1, 5)},${credit(1, 5)}]}"""))
    }

    @Test fun `playing yourself doesn't count`() {
        assertTrue(ids("""{"cast":[${credit(1, 5, ""","character":"Self - Guest"""")}]}""".replace(""""character":"Hero",""", "")).isEmpty())
    }

    @Test fun `chat shows, news and reality don't count`() {
        assertTrue(ids("""{"cast":[${credit(1, 5, ""","genre_ids":[10767]""")},${credit(2, 5, ""","genre_ids":[10763]""")},${credit(3, 5, ""","genre_ids":[10764]""")}]}""").isEmpty())
    }

    @Test fun `only films and series count`() {
        assertTrue(ids("""{"cast":[{"id":1,"media_type":"person","vote_count":5}]}""").isEmpty())
    }

    @Test fun `no more than a row holds`() {
        val many = (1..40).joinToString(",") { credit(it, it) }
        assertEquals(TitlePeople.TITLES, ids("""{"cast":[$many]}""").size)
    }
}
