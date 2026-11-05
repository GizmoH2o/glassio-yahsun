package com.glasslauncher.tv.data

import com.glasslauncher.tv.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/**
 * Recommendations for whoever has no Nuvio or Stremio account signed in (Settings › Home › Recommendations): what's
 * trending and popular, from TMDB (the token in Settings, else the one built into the app); with no token at all, from
 * Cinemeta's charts, so Home is never empty. Also what's like a title (its page's More like this), and search.
 */
private val COLLECTIONS = listOf("Pixar" to 3, "Studio Ghibli" to 10342, "Marvel Studios" to 420)     // TMDB's companies

class Recs(private val settings: Settings, private val http: Http) {
    data class Rows(val trending: List<HeroItem> = emptyList(), val films: List<HeroItem> = emptyList(), val series: List<HeroItem> = emptyList())

    val rows = MutableStateFlow(Rows())

    private val token: String get() = settings.tmdbToken.ifBlank { BuildConfig.TMDB_READ_TOKEN }
    private val day = 24L * 3600 * 1000
    private val month = 30 * day

    /** Are the rows wanted? [signedIn]: a Nuvio or Stremio account is. */
    fun on(signedIn: Boolean) = when (settings.recsMode) { "always" -> true; "off" -> false; else -> !signedIn }

    fun start(scope: CoroutineScope, signedIn: () -> Boolean) {
        scope.launch(Dispatchers.IO) {
            while (true) {
                if (on(signedIn())) runCatching { refresh() }.onFailure { Glog.w("Recommendations", it) }
                delay(6 * 3600_000L)
            }
        }
    }

    suspend fun refresh() {
        val r = if (token.isNotBlank()) runCatching { fromTmdb() }.onFailure { Glog.w("TMDB recommendations", it) }.getOrNull() else null
        rows.value = r?.takeIf { it.trending.isNotEmpty() } ?: fromCinemeta()
    }

    /** Home's Collections rows: a studio's best-known films, in the same order every time. Empty without a token. */
    suspend fun collections(): List<Pair<String, List<HeroItem>>> = coroutineScope {
        if (token.isBlank()) return@coroutineScope emptyList()
        val genres = genreNames()
        val lang = "&language=" + (TMDB_LANGS[settings.descLang] ?: "en-US")
        COLLECTIONS.map { (name, company) ->
            async {
                name to runCatching { tmdbList("https://api.themoviedb.org/3/discover/movie?with_companies=$company&sort_by=vote_count.desc$lang", "movie", genres) }.getOrDefault(emptyList())
            }
        }.awaitAll().filter { it.second.isNotEmpty() }
    }

    // ── TMDB

    private suspend fun fromTmdb(): Rows = coroutineScope {
        val genres = async { genreNames() }
        val lang = "&language=" + (TMDB_LANGS[settings.descLang] ?: "en-US")
        val trending = async { tmdbList("https://api.themoviedb.org/3/trending/all/week?page=1$lang", null, genres.await()) }
        val films = async { tmdbList("https://api.themoviedb.org/3/movie/popular?page=1$lang", "movie", genres.await()) }
        val series = async { tmdbList("https://api.themoviedb.org/3/tv/popular?page=1$lang", "tv", genres.await()) }
        Rows(trending.await(), films.await(), series.await())
    }

    private fun genreNames(): Map<Int, String> {
        val out = HashMap<Int, String>()
        for (k in listOf("movie", "tv")) runCatching {
            val a = http.json("https://api.themoviedb.org/3/genre/$k/list", month, token).optJSONArray("genres") ?: JSONArray()
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { out[it.optInt("id")] = it.optString("name") }
        }
        return out
    }

    /** A TMDB list's titles, with their IMDb ids (what the rest of Glassio and the players go by): one without is left out. */
    private suspend fun tmdbList(url: String, kind: String?, genres: Map<Int, String>): List<HeroItem> = coroutineScope {
        val results = http.json(url, 6 * 3600_000L, token).optJSONArray("results") ?: JSONArray()
        val gate = Semaphore(5)
        (0 until results.length()).mapNotNull { results.optJSONObject(it) }.map { o ->
            async(Dispatchers.IO) { gate.withPermit { runCatching { tmdbItem(o, kind, genres) }.getOrNull() } }
        }.awaitAll().filterNotNull()
    }

    private fun tmdbItem(o: JSONObject, kind: String?, genres: Map<Int, String>): HeroItem? {
        val k = kind ?: o.optString("media_type")
        if (k != "movie" && k != "tv") return null
        val imdb = http.json("https://api.themoviedb.org/3/$k/${o.optInt("id")}/external_ids", month, token).optString("imdb_id")
        if (!imdb.startsWith("tt")) return null
        return parseTmdb(o, k, imdb, genres)
    }

    /** Blocking: what's like [item] on TMDB; empty without a token or when TMDB doesn't know it. */
    fun similar(item: HeroItem): List<HeroItem> {
        if (token.isBlank() || !item.id.matches(Regex("tt\\d{6,10}"))) return emptyList()
        return runCatching {
            val isMovie = item.type.equals("movie", true)
            val k = if (isMovie) "movie" else "tv"
            val found = http.json("https://api.themoviedb.org/3/find/${item.id}?external_source=imdb_id", month, token)
                .optJSONArray(if (isMovie) "movie_results" else "tv_results")?.optJSONObject(0) ?: return emptyList()
            val genres = genreNames()
            val a = http.json("https://api.themoviedb.org/3/$k/${found.optInt("id")}/recommendations", 7 * day, token).optJSONArray("results") ?: JSONArray()
            (0 until minOf(a.length(), 12)).mapNotNull { a.optJSONObject(it) }.mapNotNull { runCatching { tmdbItem(it, k, genres) }.getOrNull() }
        }.getOrDefault(emptyList())
    }

    // ── Cinemeta (no key)

    private fun fromCinemeta(): Rows {
        val films = cinemeta("https://v3-cinemeta.strem.io/catalog/movie/top.json", "movie", 6 * 3600_000L)
        val series = cinemeta("https://v3-cinemeta.strem.io/catalog/series/top.json", "series", 6 * 3600_000L)
        return Rows(interleave(films, series).take(20), films, series)
    }

    private fun cinemeta(url: String, type: String, ttl: Long): List<HeroItem> = runCatching {
        parseCinemeta(http.json(url, ttl), type)
    }.onFailure { Glog.w("Cinemeta chart", it) }.getOrDefault(emptyList())

    /** Blocking: titles matching [query] (Cinemeta's search, films and series in turn). */
    fun search(query: String): List<HeroItem> {
        val q = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        val films = cinemeta("https://v3-cinemeta.strem.io/catalog/movie/top/search=$q.json", "movie", 3600_000L)
        val series = cinemeta("https://v3-cinemeta.strem.io/catalog/series/top/search=$q.json", "series", 3600_000L)
        return interleave(films, series)
    }

    companion object {
        private const val IMG = "https://image.tmdb.org/t/p/"

        internal fun interleave(a: List<HeroItem>, b: List<HeroItem>): List<HeroItem> {
            val out = ArrayList<HeroItem>()
            for (i in 0 until maxOf(a.size, b.size)) { a.getOrNull(i)?.let { out += it }; b.getOrNull(i)?.let { out += it } }
            return out.distinctBy { it.key }
        }

        /** One of TMDB's list results as a title; null without a name or a picture. */
        internal fun parseTmdb(o: JSONObject, kind: String, imdb: String, genres: Map<Int, String>): HeroItem? {
            val movie = kind == "movie"
            val name = o.optString(if (movie) "title" else "name").takeIf { it.isNotBlank() } ?: return null
            val poster = o.optString("poster_path").takeIf { it.startsWith("/") }
            val back = o.optString("backdrop_path").takeIf { it.startsWith("/") }
            if (poster == null && back == null) return null
            val type = if (movie) "movie" else "series"
            val ids = o.optJSONArray("genre_ids")
            val g = ids?.let { a -> (0 until a.length()).mapNotNull { genres[a.optInt(it)] } }.orEmpty().take(3)
            val rating = o.optDouble("vote_average", 0.0).takeIf { it > 0 }?.let { String.format(Locale.US, "%.1f", it) }.orEmpty()
            return HeroItem(
                key = "$type:$imdb", id = imdb, type = type, name = name,
                poster = poster?.let { IMG + "w500" + it }.orEmpty(), background = back?.let { IMG + "w1280" + it }.orEmpty(), logo = "",
                overview = o.optString("overview"), rating = rating, year = o.optString(if (movie) "release_date" else "first_air_date").take(4),
                genres = g, platform = "",
            )
        }

        /** A Cinemeta catalog's titles; those without an IMDb id or a poster are left out. */
        internal fun parseCinemeta(root: JSONObject, type: String): List<HeroItem> {
            val a = root.optJSONArray("metas") ?: return emptyList()
            return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { m ->
                val id = m.optString("imdb_id").ifBlank { m.optString("id") }
                val poster = m.optString("poster")
                if (!id.startsWith("tt") || !poster.startsWith("http")) return@mapNotNull null
                val g = m.optJSONArray("genres") ?: m.optJSONArray("genre")
                HeroItem(
                    key = "$type:$id", id = id, type = type, name = m.optString("name"),
                    poster = poster, background = m.optString("background").takeIf { it.startsWith("http") } ?: poster, logo = m.optString("logo").takeIf { it.startsWith("http") }.orEmpty(),
                    overview = m.optString("description"), rating = m.optString("imdbRating"),
                    year = Regex("(19|20)\\d{2}").find(m.optString("releaseInfo").ifBlank { m.optString("year") })?.value.orEmpty(),
                    genres = g?.let { x -> (0 until minOf(3, x.length())).map { x.optString(it) } }.orEmpty(), platform = "",
                )
            }
        }
    }
}
