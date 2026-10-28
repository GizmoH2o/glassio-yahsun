package com.glasslauncher.tv.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.util.Locale

/** One episode of a series, as Cinemeta lists it: its numbers, title and when it came (or comes) out. */
data class Episode(val season: Int, val episode: Int, val title: String, val released: Long)

/** A title's details from Cinemeta: what the home screen's posters, their wide cards and the big picture show. */
data class TitleInfo(
    val background: String, val logo: String, val overview: String, val rating: String, val year: String,
    val runtime: String, val genres: List<String>, val episodes: List<Episode>,
)

/** A series in your library with an episode out (or coming) this week: "Airing this week". */
data class Airing(val item: HeroItem, val episode: Episode, val more: Int) {
    val out: Boolean get() = episode.released <= System.currentTimeMillis()
}

/**
 * What the home screen shows from your library: the Nuvio and/or Stremio library (Settings → Home), with what's saved
 * here, as Movies and Series; the series' new episodes this week (Airing this week); and how many episodes came out
 * after the one you're on in Continue Watching. Each title's details come from Cinemeta, kept on disk (a series' a few
 * hours, for its new episodes; a film's a week).
 */
class HomeShelf(
    private val settings: Settings, private val http: Http, private val library: Library,
    private val cw: ContinueWatching, private val scope: CoroutineScope,
) {
    val info = MutableStateFlow<Map<String, TitleInfo>>(emptyMap())
    val movies = MutableStateFlow<List<HeroItem>>(emptyList())
    val series = MutableStateFlow<List<HeroItem>>(emptyList())
    val airing = MutableStateFlow<List<Airing>>(emptyList())
    /** Continue Watching's series (by key): how many episodes are out after the one you're on. */
    val newEpisodes = MutableStateFlow<Map<String, Int>>(emptyMap())
    private var job: Job? = null
    @Volatile private var listsAt = 0L

    fun start() {
        scope.launch {
            launch { library.saved.collect { rebuild() } }
            launch { library.nuvioList.collect { rebuild() } }
            launch { library.stremioList.collect { rebuild() } }
            launch { cw.items.collect { rebuild() } }
            // The library lists themselves: read now, then every half hour (the Library screen reads them too).
            launch(Dispatchers.IO) {
                while (true) {
                    refreshLists()
                    delay(30 * 60_000L)
                }
            }
        }
    }

    /** Reads the Nuvio and Stremio libraries again (at most once a minute), which rebuilds the rows. */
    fun refreshLists() {
        val now = System.currentTimeMillis()
        if (now - listsAt < 60_000L) return
        listsAt = now
        runCatching { library.refreshNuvio() }
        runCatching { library.refreshStremio() }
    }

    /** The rows again (the source changed in Settings, or a list did). */
    fun rebuild() {
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            val all = libraryItems()
            val m = all.filter { it.type == "movie" }
            val s = all.filter { it.type == "series" }
            movies.value = m.map(::filled)
            series.value = s.map(::filled)
            // Series first (Airing this week, the new episodes), then films; Continue Watching's series too.
            val cwSeries = cw.items.value.filter { it.type == "series" && it.id.startsWith("tt") }
            val todo = (s.take(MAX) + cwSeries + m.take(MAX)).distinctBy { it.key }.filter { it.id.startsWith("tt") }
            val gate = Semaphore(4)
            todo.chunked(12).forEach { chunk ->
                val got = chunk.map { item -> async { gate.withPermit { item.key to fetch(item) } } }.awaitAll()
                val add = got.mapNotNull { (k, v) -> v?.let { k to it } }.toMap()
                if (add.isNotEmpty()) info.value = info.value + add
                movies.value = m.map(::filled)
                series.value = s.map(::filled)
                airing.value = airingOf(s)
                newEpisodes.value = newAfter(cwSeries)
            }
        }
    }

    /** Your library, as the home screen uses it: what's saved here, then Nuvio's and/or Stremio's, each title once. */
    private fun libraryItems(): List<HeroItem> {
        val src = settings.homeSource
        val list = buildList {
            addAll(library.saved.value)
            if (src != "stremio") addAll(library.nuvioList.value)
            if (src != "nuvio") addAll(library.stremioList.value)
        }
        return list.filter { it.id.isNotBlank() }.distinctBy { it.key }
    }

    /** An item with its details from Cinemeta (picture, logo, rating, year, runtime, genres) where it had none. */
    fun filled(item: HeroItem): HeroItem {
        val i = info.value[item.key] ?: return item
        return item.copy(
            background = i.background.ifBlank { item.background }, logo = item.logo.ifBlank { i.logo },
            overview = item.overview.ifBlank { i.overview }, rating = item.rating.ifBlank { i.rating },
            year = item.year.ifBlank { i.year }, genres = item.genres.ifEmpty { i.genres }, runtime = item.runtime.ifBlank { i.runtime },
        )
    }

    private fun fetch(item: HeroItem): TitleInfo? = runCatching {
        val type = if (item.type == "movie") "movie" else "series"
        val ttl = if (type == "series") 6 * 3600_000L else 7 * 24 * 3600_000L
        val meta = http.json("https://v3-cinemeta.strem.io/meta/$type/${item.id}.json", ttl).optJSONObject("meta") ?: return null
        parse(meta)
    }.onFailure { Glog.w("Home: details for ${item.key}", it) }.getOrNull()

    private fun airingOf(series: List<HeroItem>): List<Airing> {
        val now = System.currentTimeMillis()
        val out = ArrayList<Airing>()
        for (s in series) {
            val (shown, more) = thisWeek(info.value[s.key]?.episodes ?: continue, now) ?: continue
            out += Airing(filled(s), shown, more)
        }
        // Out now first (newest first), then what's coming (soonest first).
        return out.sortedWith(compareBy<Airing> { if (it.out) 0 else 1 }.thenBy { if (it.out) -it.episode.released else it.episode.released })
    }

    private fun newAfter(items: List<HeroItem>): Map<String, Int> {
        val now = System.currentTimeMillis()
        val watched = cw.entries.value.associate { it.key to it.at }
        val map = HashMap<String, Int>()
        for (c in items) {
            val (season, episode) = episodeOf(c.sub) ?: continue
            val since = watched[c.key] ?: continue
            val n = newSince(info.value[c.key]?.episodes ?: continue, season, episode, since, now)
            if (n > 0) map[c.key] = n
        }
        return map
    }

    companion object {
        private const val DAY = 24 * 3600_000L
        /** How many films and series a refresh reads at most (each a small, cached download). */
        private const val MAX = 150

        /**
         * A series' episode for "Airing this week": of those out in the last 3 days or coming in the next 7 (specials
         * left out), the newest one out, else the next one; with how many others there are. Null for none.
         */
        fun thisWeek(eps: List<Episode>, now: Long): Pair<Episode, Int>? {
            val week = eps.filter { it.season > 0 && it.released in now - 3 * DAY..now + 7 * DAY }.sortedBy { it.released }
            if (week.isEmpty()) return null
            return (week.lastOrNull { it.released <= now } ?: week.first()) to week.size - 1
        }

        /** Episodes after S[season] E[episode] that came out since [since] (you last watched), up to [now]. */
        fun newSince(eps: List<Episode>, season: Int, episode: Int, since: Long, now: Long): Int =
            eps.count { it.season > 0 && it.released in (since + 1)..now && (it.season > season || it.season == season && it.episode > episode) }

        /** "S10 · E12" → (10, 12). */
        fun episodeOf(sub: String): Pair<Int, Int>? {
            val m = Regex("""S(\d+)\s*·\s*E(\d+)""").find(sub) ?: return null
            return m.groupValues[1].toInt() to m.groupValues[2].toInt()
        }

        /** Cinemeta's meta → TitleInfo (its episodes only for a series). */
        fun parse(meta: JSONObject): TitleInfo {
            fun str(k: String) = meta.optString(k).takeIf { it.isNotBlank() && it != "null" }.orEmpty()
            val rating = str("imdbRating").toDoubleOrNull()?.let { String.format(Locale.US, "%.1f", it) }.orEmpty()
            val year = Regex("(19|20)\\d{2}").find(str("releaseInfo").ifBlank { str("year") })?.value.orEmpty()
            val genres = meta.optJSONArray("genres")?.let { a -> (0 until minOf(3, a.length())).map { a.optString(it) } }.orEmpty()
            val videos = meta.optJSONArray("videos")
            val eps = if (videos == null) emptyList() else (0 until videos.length()).mapNotNull { i ->
                val v = videos.optJSONObject(i) ?: return@mapNotNull null
                val season = v.optInt("season", -1)
                val number = if (v.has("episode")) v.optInt("episode", -1) else v.optInt("number", -1)
                val released = released(v.optString("released").ifBlank { v.optString("firstAired") })
                if (season < 0 || number < 0 || released <= 0) null
                else Episode(season, number, v.optString("name").ifBlank { v.optString("title") }.takeIf { it != "null" }.orEmpty(), released)
            }
            return TitleInfo(str("background"), str("logo"), str("description"), rating, year, runtime(str("runtime")), genres, eps)
        }

        /** Cinemeta's dates ("2026-10-27T05:00:00.000Z") as ms; 0 when there's none. */
        fun released(iso: String): Long = runCatching { java.time.Instant.parse(iso).toEpochMilli() }.getOrDefault(0L)

        /** "126 min" → "2h 6m"; "52 min" stays; anything else as it came. */
        fun runtime(raw: String): String {
            val min = Regex("""^(\d+)\s*min""").find(raw.trim())?.groupValues?.get(1)?.toIntOrNull() ?: return raw
            return if (min >= 60) "${min / 60}h" + (if (min % 60 > 0) " ${min % 60}m" else "") else "$min min"
        }
    }
}
