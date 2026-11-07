package com.glasslauncher.tv.data

import org.json.JSONObject

/** Who made a title and who's in it, from TMDB, for its page. Pure parsing, so it's tested on the computer. */
data class TitlePeople(val makers: List<String>, val cast: List<Person>) {
    data class Person(val id: Int, val name: String, val role: String, val photo: String)

    companion object {
        val NONE = TitlePeople(emptyList(), emptyList())
        /** How many of the cast the row holds, and how many of a person's titles. */
        const val CAST = 14
        const val TITLES = 12
        private const val PHOTO = "https://image.tmdb.org/t/p/w185"
        /** TMDB's genres for talk shows, news and reality: appearances, not roles. */
        private val NOT_ROLES = setOf(10767, 10763, 10764)

        private fun JSONObject.text(k: String) = optString(k).takeIf { it.isNotBlank() && it != "null" }.orEmpty()

        private fun objects(o: JSONObject?, k: String): List<JSONObject> =
            o?.optJSONArray(k)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()

        /** A title's details with its credits appended. A film's makers are its directors; a series', its creators. */
        fun parse(root: JSONObject, movie: Boolean): TitlePeople {
            val credits = root.optJSONObject("credits")
            val makers = if (movie) objects(credits, "crew").filter { it.optString("job") == "Director" }.map { it.text("name") }
            else objects(root, "created_by").map { it.text("name") }
            val cast = objects(credits, "cast").filter { it.text("name").isNotEmpty() }.sortedBy { it.optInt("order", Int.MAX_VALUE) }.take(CAST).map {
                Person(it.optInt("id"), it.text("name"), it.text("character"), it.text("profile_path").takeIf { p -> p.startsWith("/") }?.let { p -> PHOTO + p }.orEmpty())
            }
            return TitlePeople(makers.filter { it.isNotEmpty() }.distinct().take(2), cast)
        }

        /** A person's real roles from their combined credits, the best known first (each still a list result of TMDB's). */
        fun known(root: JSONObject): List<JSONObject> = objects(root, "cast").filter { o ->
            val kind = o.optString("media_type")
            val genres = o.optJSONArray("genre_ids")?.let { a -> (0 until a.length()).map { a.optInt(it) } }.orEmpty()
            (kind == "movie" || kind == "tv") && !o.optString("character").contains("Self") && genres.none { it in NOT_ROLES }
        }.distinctBy { it.optString("media_type") + it.optInt("id") }.sortedByDescending { it.optInt("vote_count") }.take(TITLES)
    }
}
