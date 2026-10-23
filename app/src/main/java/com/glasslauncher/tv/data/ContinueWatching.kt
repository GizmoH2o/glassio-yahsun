package com.glasslauncher.tv.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.concurrent.thread

/** One title being watched. season/episode are 0 for movies. */
data class CwEntry(val imdb: String, val season: Int, val episode: Int, val percent: Int, val at: Long, val app: String) {
    val type: String get() = if (season > 0 || episode > 0) "series" else "movie"
    val key: String get() = "$type:$imdb"
}

/**
 * Continue Watching for Nuvio Legacy.
 *
 * Google TV only lets privileged apps read other apps' Watch Next rows, so instead this reads what Nuvio
 * Legacy writes to its own log while you watch ("[atividade] inicio tt… T1E3 42%", "[visto] ponto gravado
 * … (63%)", "[player] saida"). The log is read through the TV's local adb shell (LocalShell), so Android's
 * "Allow access to all device logs?" prompt never appears; only the one-time "Allow USB debugging" does.
 * The list is kept on the TV, newest first; titles past 92% count as finished and leave the list.
 */
class ContinueWatching(
    private val ctx: Context,
    private val settings: Settings,
    private val http: Http,
    private val scope: CoroutineScope,
) {
    private val sp = ctx.getSharedPreferences("glass_cw", Context.MODE_PRIVATE)
    val entries = MutableStateFlow(load())
    val items = MutableStateFlow<List<HeroItem>>(emptyList())
    /** Key of the title you were watching last; bumped when you leave the player (carousel jumps to it). */
    val lastWatched = MutableStateFlow<String?>(null)
    val jumpRev = MutableStateFlow(0)

    private var current: Triple<String, Int, Int>? = null
    @Volatile private var started = false

    @Volatile private var access = sp.getBoolean("access", false)
    private val wake = Object()

    /** True once the local adb shell has answered (USB debugging allowed for Glass Launcher). */
    fun hasLogAccess(): Boolean = access

    fun start() {
        if (started) return
        started = true
        scope.launch(Dispatchers.IO) { rebuildItems() }
        thread(name = "glass-cw-log", isDaemon = true) {
            while (true) {
                if (settings.cwLegacy && settings.continueMode != "off") poll()
                // Every 20 s, or right away when Glass comes back to the front (pollNow).
                synchronized(wake) { runCatching { (wake as Object).wait(20_000) } }
            }
        }
    }

    /** Called when the home screen resumes, so leaving Nuvio shows up immediately. */
    fun pollNow() { synchronized(wake) { (wake as Object).notifyAll() } }

    private val epochLine = Regex("""^(\d+)\.(\d+)\s+\d+\s+\d+\s+[A-Z]\s+nuvio\s*:\s(.*)$""")
    private val startRe = Regex("""\[atividade] inicio (tt\d+) T(\d+)E(\d+) (\d+)%""")
    private val pointRe = Regex("""\[visto] ponto gravado .*?\((\d+)%\)""")
    private val exitRe = Regex("""\[player] saida""")

    /** Reads Nuvio's log lines from the buffer; lines already handled (by time) are skipped. */
    private fun poll() {
        val out = LocalShell.run(ctx, "logcat -d -v epoch -s nuvio:I").getOrNull()
        if ((out != null) != access) { access = out != null; sp.edit().putBoolean("access", access).apply() }
        out ?: return
        for (line in out.lineSequence()) {
            val m = epochLine.find(line.trim()) ?: continue
            val at = m.groupValues[1].toLong() * 1000L + m.groupValues[2].padEnd(3, '0').take(3).toLong()
            if (at <= sp.getLong("lastAt", 0L)) continue
            sp.edit().putLong("lastAt", at).apply()
            handle(at, m.groupValues[3])
        }
    }

    private fun handle(at: Long, msg: String) {
        startRe.find(msg)?.let {
            val id = it.groupValues[1]; val s = it.groupValues[2].toInt(); val e = it.groupValues[3].toInt()
            current = Triple(id, s, e)
            upsert(CwEntry(id, s, e, it.groupValues[4].toInt(), at, "Nuvio Legacy"))
            return
        }
        pointRe.find(msg)?.let {
            val c = current ?: return
            upsert(CwEntry(c.first, c.second, c.third, it.groupValues[1].toInt(), at, "Nuvio Legacy"))
            return
        }
        if (exitRe.containsMatchIn(msg)) {
            current?.let { c ->
                val key = CwEntry(c.first, c.second, c.third, 0, at, "").key
                if (entries.value.any { it.key == key }) { lastWatched.value = key; jumpRev.value = jumpRev.value + 1 }
            }
        }
    }

    @Synchronized
    private fun upsert(e: CwEntry) {
        // One entry per title: a newer episode of the same series replaces the older one.
        val list = entries.value.filterNot { it.imdb == e.imdb }.toMutableList()
        if (e.percent < 92) list.add(0, e)
        val trimmed = list.sortedByDescending { it.at }.take(30)
        entries.value = trimmed
        save(trimmed)
        lastWatched.value = e.key
        scope.launch(Dispatchers.IO) { rebuildItems() }
    }

    fun clear() { entries.value = emptyList(); save(emptyList()); items.value = emptyList() }

    fun remove(key: String) {
        val l = entries.value.filterNot { it.key == key }
        entries.value = l; save(l)
        scope.launch(Dispatchers.IO) { rebuildItems() }
    }

    /** Turns entries into carousel items using Cinemeta (name, art, logo, synopsis, rating). */
    private fun rebuildItems() {
        val out = entries.value.filter { it.percent >= 2 }.mapNotNull { e ->
            runCatching {
                val meta = http.json("https://v3-cinemeta.strem.io/meta/${e.type}/${e.imdb}.json", 7L * 24 * 3600 * 1000).optJSONObject("meta")
                    ?: return@runCatching null
                fun str(k: String) = meta.optString(k).takeIf { it.isNotBlank() && it != "null" }.orEmpty()
                val rating = meta.optString("imdbRating").toDoubleOrNull()?.let { String.format(Locale.US, "%.1f", it) }.orEmpty()
                val year = Regex("(19|20)\\d{2}").find(str("releaseInfo").ifBlank { str("year") })?.value.orEmpty()
                val genres = meta.optJSONArray("genres")?.let { a -> (0 until minOf(3, a.length())).map { a.optString(it) } }.orEmpty()
                val sub = if (e.type == "series") "S${e.season} · E${e.episode}" else ""
                HeroItem(
                    key = e.key, id = e.imdb, type = e.type, name = str("name"),
                    poster = str("poster"), background = str("background").ifBlank { str("poster") }, logo = str("logo"),
                    overview = str("description"), rating = rating, year = year, genres = genres, platform = "",
                    progress = e.percent / 100f, sub = sub,
                )
            }.getOrNull()
        }
        items.value = out
    }

    private fun load(): List<CwEntry> = runCatching {
        val a = JSONArray(sp.getString("entries", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            CwEntry(o.getString("imdb"), o.optInt("s"), o.optInt("e"), o.optInt("p"), o.optLong("at"), o.optString("app"))
        }
    }.getOrDefault(emptyList())

    private fun save(l: List<CwEntry>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("imdb", it.imdb).put("s", it.season).put("e", it.episode).put("p", it.percent).put("at", it.at).put("app", it.app)) }
        sp.edit().putString("entries", a.toString()).apply()
    }
}
