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

    /** Blocking: who made [item] and who's in it; [TitlePeople.NONE] without a token or when TMDB doesn't know it. */
    fun people(item: HeroItem): TitlePeople {
        if (token.isBlank() || !item.id.matches(Regex("tt\\d{6,10}"))) return TitlePeople.NONE
        return runCatching {
            val isMovie = item.type.equals("movie", true)
            val k = if (isMovie) "movie" else "tv"
            val found = http.json("https://api.themoviedb.org/3/find/${item.id}?external_source=imdb_id", month, token)
                .optJSONArray(if (isMovie) "movie_results" else "tv_results")?.optJSONObject(0) ?: return TitlePeople.NONE
            TitlePeople.parse(http.json("https://api.themoviedb.org/3/$k/${found.optInt("id")}?append_to_response=credits", 7 * day, token), isMovie)
        }.onFailure { Glog.w("TMDB credits", it) }.getOrDefault(TitlePeople.NONE)
    }

    /** The row for the time of year (its title, its films), or null for a month that has none. Needs a TMDB token. */
    suspend fun seasonal(m: Int): Pair<String, List<HeroItem>>? {
        if (token.isBlank()) return null
        val (title, part) = seasonQuery(m) ?: return null
        val lang = "&language=" + (TMDB_LANGS[settings.descLang] ?: "en-US")
        val with = if (part.startsWith("keyword:")) {
            val word = part.removePrefix("keyword:")
            val k = runCatching {
                val a = http.json("https://api.themoviedb.org/3/search/keyword?query=" + java.net.URLEncoder.encode(word, "UTF-8"), month, token)
                    .optJSONArray("results") ?: JSONArray()
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }.firstOrNull { it.optString("name").equals(word, true) }?.optInt("id")
            }.getOrNull() ?: return null
            "&with_keywords=$k&without_genres=27"     // not the horror films with Christmas in them
        } else "&with_genres=$part"
        val list = runCatching {
            tmdbList("https://api.themoviedb.org/3/discover/movie?sort_by=popularity.desc&vote_count.gte=250$with$lang", "movie", genreNames())
        }.getOrDefault(emptyList())
        return if (list.size >= 4) title to list.take(20) else null
    }

    /** What TMDB adds to a title's page: its users' score (0-100, -1 none), its age rating, its videos, its film series. */
    class Extras(val score: Int = -1, val cert: String = "", val videos: List<Pair<String, String>> = emptyList(), val collection: Int = 0, val collectionName: String = "")

    /** Blocking: [item]'s extras; none without a token or an IMDb id. */
    fun extras(item: HeroItem): Extras {
        if (token.isBlank() || !item.id.matches(Regex("tt\\d{6,10}"))) return Extras()
        return runCatching {
            val movie = item.type.equals("movie", true)
            val k = if (movie) "movie" else "tv"
            val found = http.json("https://api.themoviedb.org/3/find/${item.id}?external_source=imdb_id", month, token)
                .optJSONArray(if (movie) "movie_results" else "tv_results")?.optJSONObject(0) ?: return Extras()
            val d = http.json("https://api.themoviedb.org/3/$k/${found.optInt("id")}?append_to_response=videos," + (if (movie) "release_dates" else "content_ratings"), 7 * day, token)
            val votes = d.optInt("vote_count")
            val score = if (votes >= 50) Math.round(d.optDouble("vote_average", 0.0) * 10).toInt() else -1
            val cert = if (movie) {
                val a = d.optJSONObject("release_dates")?.optJSONArray("results") ?: JSONArray()
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }.firstOrNull { it.optString("iso_3166_1") == "US" }
                    ?.optJSONArray("release_dates")?.let { r -> (0 until r.length()).mapNotNull { r.optJSONObject(it)?.optString("certification") }.firstOrNull { it.isNotBlank() } }.orEmpty()
            } else {
                val a = d.optJSONObject("content_ratings")?.optJSONArray("results") ?: JSONArray()
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }.firstOrNull { it.optString("iso_3166_1") == "US" }?.optString("rating").orEmpty()
            }
            val v = d.optJSONObject("videos")?.optJSONArray("results") ?: JSONArray()
            val order = listOf("Trailer", "Teaser", "Clip", "Featurette", "Behind the Scenes")
            val videos = (0 until v.length()).mapNotNull { v.optJSONObject(it) }
                .filter { it.optString("site") == "YouTube" && it.optString("type") in order && it.optString("key").isNotBlank() }
                .sortedBy { order.indexOf(it.optString("type")) }.take(12)
                .map { it.optString("name").ifBlank { it.optString("type") } to it.optString("key") }
            val col = d.optJSONObject("belongs_to_collection")
            Extras(score, cert, videos, col?.optInt("id") ?: 0, col?.optString("name").orEmpty())
        }.onFailure { Glog.w("TMDB extras", it) }.getOrDefault(Extras())
    }

    /** Blocking: a film series' films in the order they came out (TMDB's collection). */
    suspend fun collection(id: Int): List<HeroItem> {
        if (token.isBlank() || id <= 0) return emptyList()
        return runCatching {
            val parts = http.json("https://api.themoviedb.org/3/collection/$id", 7 * day, token).optJSONArray("parts") ?: JSONArray()
            val genres = genreNames()
            (0 until parts.length()).mapNotNull { parts.optJSONObject(it) }.sortedBy { it.optString("release_date").ifBlank { "9999" } }
                .mapNotNull { runCatching { tmdbItem(it, "movie", genres) }.getOrNull() }
        }.getOrDefault(emptyList())
    }

    private val stills = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Blocking: the episode's own picture (TMDB's still) for a series at [season] · [episode]; "" when there's none. */
    fun episodeStill(item: HeroItem, season: Int, episode: Int): String {
        if (token.isBlank() || !item.id.matches(Regex("tt\\d{6,10}"))) return ""
        val key = "${item.id}:$season:$episode"
        stills[key]?.let { return it }
        val url = runCatching {
            val tv = http.json("https://api.themoviedb.org/3/find/${item.id}?external_source=imdb_id", month, token)
                .optJSONArray("tv_results")?.optJSONObject(0)?.optInt("id") ?: return@runCatching ""
            val path = http.json("https://api.themoviedb.org/3/tv/$tv/season/$season/episode/$episode", month, token).optString("still_path")
            if (path.startsWith("/")) "https://image.tmdb.org/t/p/w780$path" else ""
        }.getOrDefault("")
        stills[key] = url
        return url
    }

    /** Blocking: the people TMDB knows by [query] (actors, directors), the better known first; none without a token. */
    fun searchPeople(query: String): List<TitlePeople.Person> {
        if (token.isBlank() || query.trim().length < 3) return emptyList()
        return runCatching {
            val a = http.json("https://api.themoviedb.org/3/search/person?query=" + java.net.URLEncoder.encode(query.trim(), "UTF-8"), day, token)
                .optJSONArray("results") ?: JSONArray()
            (0 until minOf(a.length(), 8)).mapNotNull { a.optJSONObject(it) }
                .filter { it.optString("profile_path").startsWith("/") && it.optDouble("popularity", 0.0) >= 2.0 }
                .map { TitlePeople.Person(it.optInt("id"), it.optString("name"), it.optString("known_for_department"), "https://image.tmdb.org/t/p/w185" + it.optString("profile_path")) }
                .take(5)
        }.onFailure { Glog.w("TMDB people", it) }.getOrDefault(emptyList())
    }

    /** Blocking: a person's best known films and series. */
    fun titlesOf(person: Int): List<HeroItem> {
        if (token.isBlank()) return emptyList()
        return runCatching {
            val genres = genreNames()
            TitlePeople.known(http.json("https://api.themoviedb.org/3/person/$person/combined_credits", 7 * day, token))
                .mapNotNull { o -> runCatching { tmdbItem(o, null, genres) }.getOrNull() }
        }.onFailure { Glog.w("TMDB person", it) }.getOrDefault(emptyList())
    }

    /**
     * Blocking: [item]'s poster without its own lettering (TMDB's language-free one, the best liked), for the poster
     * style (its name and rating are drawn on it); "" when there's none or no token.
     */
    fun textlessPoster(item: HeroItem): String {
        if (token.isBlank() || !item.id.matches(Regex("tt\\d{6,10}"))) return ""
        return runCatching {
            val movie = item.type.equals("movie", true)
            val k = if (movie) "movie" else "tv"
            val found = http.json("https://api.themoviedb.org/3/find/${item.id}?external_source=imdb_id", month, token)
                .optJSONArray(if (movie) "movie_results" else "tv_results")?.optJSONObject(0) ?: return ""
            val a = http.json("https://api.themoviedb.org/3/$k/${found.optInt("id")}/images?include_image_language=null", month, token)
                .optJSONArray("posters") ?: return ""
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }
                .filter { it.optString("file_path").startsWith("/") && (it.isNull("iso_639_1") || it.optString("iso_639_1").let { l -> l.isBlank() || l == "null" }) }
                .maxByOrNull { it.optDouble("vote_average", 0.0) * 10 + minOf(it.optInt("vote_count"), 20) }
                ?.let { "https://image.tmdb.org/t/p/w500" + it.optString("file_path") }.orEmpty()
        }.onFailure { Glog.w("TMDB textless poster", it) }.getOrDefault("")
    }

    /** Blocking: where [item] streams (its first service), as the title page says it; "" for none or no token. */
    fun service(item: HeroItem): String {
        if (token.isBlank() || !item.id.matches(Regex("tt\\d{6,10}"))) return ""
        return runCatching {
            val movie = item.type.equals("movie", true)
            val found = http.json("https://api.themoviedb.org/3/find/${item.id}?external_source=imdb_id", month, token)
                .optJSONArray(if (movie) "movie_results" else "tv_results")?.optJSONObject(0) ?: return ""
            Tmdb(http).providerOf(if (movie) "movie" else "tv", found.optInt("id"), token)
        }.getOrDefault("")
    }

    /** A person's page: what TMDB says of them (a line or two of their life, born when and where) and how many titles. */
    class PersonInfo(val bio: String, val born: String, val place: String, val dept: String, val count: Int)

    /** Blocking: who [person] is and their titles (their roles, or what they made when that's what they're known for). */
    fun person(person: Int): Pair<PersonInfo, List<HeroItem>>? {
        if (token.isBlank()) return null
        return runCatching {
            val root = http.json("https://api.themoviedb.org/3/person/$person?append_to_response=combined_credits", 7 * day, token)
            val credits = root.optJSONObject("combined_credits") ?: org.json.JSONObject()
            val dept = root.optString("known_for_department")
            val acted = TitlePeople.known(credits)
            val made = TitlePeople.made(credits)
            val picked = if (dept == "Acting") acted.ifEmpty { made } else made.ifEmpty { acted }
            val genres = genreNames()
            val titles = picked.mapNotNull { o -> runCatching { tmdbItem(o, null, genres) }.getOrNull() }
            // Each title once (a film they acted in and produced is one title, not two).
            val count = listOf("cast", "crew").flatMap { k -> credits.optJSONArray(k)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty() }
                .filter { it.optString("media_type") in setOf("movie", "tv") }.map { it.optString("media_type") + it.optInt("id") }.toSet().size
            fun t(k: String) = root.optString(k).takeIf { it.isNotBlank() && it != "null" }.orEmpty()
            PersonInfo(t("biography"), t("birthday").take(4), t("place_of_birth"), dept, count) to titles
        }.onFailure { Glog.w("TMDB person page", it) }.getOrNull()
    }

    /** Blocking: a genre's most popular films and series on TMDB ([movie], [tv]: its ids; null for none). */
    fun genre(movie: Int?, tv: Int?, kids: Boolean): Pair<List<HeroItem>, List<HeroItem>> {
        if (token.isBlank()) return emptyList<HeroItem>() to emptyList()
        val lang = "&language=" + (TMDB_LANGS[settings.descLang] ?: "en-US")
        val gentle = if (kids) "&certification_country=US&certification.lte=PG" else ""
        val genres = genreNames()
        fun list(kind: String, id: Int?) = if (id == null) emptyList() else runCatching {
            kotlinx.coroutines.runBlocking { tmdbList("https://api.themoviedb.org/3/discover/$kind?sort_by=popularity.desc&vote_count.gte=150&with_genres=$id" + (if (kind == "movie") gentle else "") + lang, kind, genres) }
        }.onFailure { Glog.w("TMDB genre", it) }.getOrDefault(emptyList())
        return list("movie", movie).take(20) to list("tv", tv).take(20)
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

        /** The season's row for [month] (1..12): its title and what to find (a TMDB keyword, or genre ids); null if none. Pure. */
        internal fun seasonQuery(month: Int): Pair<String, String>? = when (month) {
            12 -> "Christmas films" to "keyword:christmas"
            10 -> "Halloween" to "27"
            2 -> "Valentine's" to "10749"
            6, 7, 8 -> "Summer blockbusters" to "28,12"
            else -> null
        }

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
