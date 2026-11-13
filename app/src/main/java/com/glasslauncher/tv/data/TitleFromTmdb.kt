package com.glasslauncher.tv.data

import com.glasslauncher.tv.GlassApp

/**
 * A title from its TMDB id (Live TV's programme pages): its IMDb id (what the title page, Cinemeta and the add-ons go
 * by) and TMDB's pictures, logo and story, as Home's catalog titles have them. Blocking; null without TMDB.
 */
object TitleFromTmdb {
    fun of(app: GlassApp, id: Int, tv: Boolean, name: String, year: String): HeroItem? = runCatching {
        val token = app.settings.tmdbToken
        if (token.isBlank()) return null
        val kind = if (tv) "tv" else "movie"
        val ext = app.http.json("https://api.themoviedb.org/3/$kind/$id/external_ids", 30L * 24 * 3600 * 1000, token)
        val imdb = ext.optString("imdb_id").takeIf { it.startsWith("tt") }.orEmpty()
        val base = HeroItem(
            key = if (imdb.isNotEmpty()) imdb else "tmdb:$kind:$id", id = imdb, type = if (tv) "series" else "movie", name = name,
            poster = "", background = "", logo = "", overview = "", rating = "", year = year, genres = emptyList(), platform = "",
        )
        Tmdb(app.http).enrich(base, token, app.settings.descLang) ?: base
    }.getOrNull()
}
