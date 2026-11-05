package com.glasslauncher.tv.data

import org.json.JSONObject

/**
 * What a title page shows beyond the card: from Cinemeta's page for the title (no key needed). Pure parsing in [parse],
 * so it's tested on the computer.
 */
data class TitleDetails(
    val overview: String, val background: String, val logo: String, val rating: String, val year: String,
    val runtime: String, val genres: List<String>, val cast: List<String>,
    /** Season number to its episodes, in order; specials (season 0) last. */
    val seasons: Map<Int, List<Episode>>,
) {
    data class Episode(val season: Int, val number: Int, val name: String, val overview: String, val thumb: String, val released: Long)

    companion object {
        val NONE = TitleDetails("", "", "", "", "", "", emptyList(), emptyList(), emptyMap())

        private fun JSONObject.text(k: String) = optString(k).takeIf { it.isNotBlank() && it != "null" }.orEmpty()

        fun parse(root: JSONObject): TitleDetails {
            val m = root.optJSONObject("meta") ?: return NONE
            val list = ArrayList<Episode>()
            m.optJSONArray("videos")?.let { v ->
                for (i in 0 until v.length()) {
                    val e = v.optJSONObject(i) ?: continue
                    val season = e.optInt("season", -1)
                    val n = e.optInt("episode", e.optInt("number", -1))
                    if (season < 0 || n < 0) continue
                    val at = runCatching { java.time.Instant.parse(e.text("released")).toEpochMilli() }.getOrDefault(0L)
                    list += Episode(season, n, e.text("name").ifBlank { e.text("title") }, e.text("overview").ifBlank { e.text("description") }, e.text("thumbnail"), at)
                }
            }
            val seasons = list.distinctBy { it.season to it.number }.groupBy { it.season }.mapValues { (_, l) -> l.sortedBy { it.number } }
            val ordered = LinkedHashMap<Int, List<Episode>>()
            seasons.keys.filter { it > 0 }.sorted().forEach { ordered[it] = seasons.getValue(it) }
            seasons[0]?.let { ordered[0] = it }
            fun strings(k: String) = m.optJSONArray(k)?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() && s != "null" } } }.orEmpty()
            val year = Regex("(19|20)\\d{2}").find(m.text("releaseInfo").ifBlank { m.text("year") })?.value.orEmpty()
            return TitleDetails(
                overview = m.text("description"), background = m.text("background"), logo = m.text("logo"),
                rating = m.optString("imdbRating").toDoubleOrNull()?.let { String.format(java.util.Locale.US, "%.1f", it) }.orEmpty(),
                year = year, runtime = m.text("runtime"), genres = strings("genres").take(3), cast = strings("cast").take(5).map { android.text.Html.fromHtml(it, 0).toString() }, seasons = ordered,
            )
        }

        /** Blocking; kept a day. A title Cinemeta doesn't know: [NONE] (the page shows what the card knew). */
        fun load(http: Http, item: HeroItem): TitleDetails = runCatching {
            if (!item.id.matches(Regex("tt\\d{6,10}"))) return NONE
            val type = if (item.type.equals("movie", true)) "movie" else "series"
            parse(http.json("https://v3-cinemeta.strem.io/meta/$type/${item.id}.json", 24L * 3600 * 1000))
        }.getOrDefault(NONE)
    }
}
