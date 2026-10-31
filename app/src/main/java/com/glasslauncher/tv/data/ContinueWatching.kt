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
 * Continue Watching for Nuvio Legacy, the official Nuvio app (through the Nuvio account), Stremio (through the Stremio
 * account) and Trakt.
 *
 * Google TV only lets privileged apps read other apps' Watch Next rows, so instead this reads what Nuvio
 * Legacy writes to its own log while you watch ("[atividade] inicio tt… T1E3 42%", "[visto] ponto gravado
 * … (63%)", "[player] saida"). The log is read through the TV's local adb shell (LocalShell), so Android's
 * "Allow access to all device logs?" prompt never appears; only the one-time "Allow USB debugging" does.
 * The list is kept on the TV, newest first; titles past 92% count as finished and leave the list.
 * The official Nuvio app writes no such log: it saves what you watch to the Nuvio account, which this reads; Stremio
 * keeps it in its account's library (each title's position), which this reads too.
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
    val trakt = Trakt(ctx)
    @Volatile private var traktAt = 0L
    /** The Nuvio account (the Library's, so one sign-in and one token refresh at a time); set once the Library is made. */
    @Volatile var nuvio: NuvioAccount? = null
    @Volatile private var nuvioAt = 0L
    /** Last Nuvio account sync result for Settings ("4 titles in progress", or the error). */
    val nuvioStatus = MutableStateFlow("")
    /** The Stremio account (the Library's); set once the Library is made. */
    @Volatile var stremio: StremioAccount? = null
    @Volatile private var stremioAt = 0L
    /** Last Stremio account sync result for Settings. */
    val stremioStatus = MutableStateFlow("")
    @Volatile private var woke = false
    /** Last Trakt sync result for Settings ("12 titles", or the error). */
    val traktStatus = MutableStateFlow("")
    /** Released to wake the poll loop early (Glass came back to the front). */
    private val wake = java.util.concurrent.Semaphore(0)

    /** True once the local adb shell has answered (USB debugging allowed for Glassio). */
    fun hasLogAccess(): Boolean = access

    fun start() {
        if (started) return
        started = true
        scope.launch(Dispatchers.IO) {
            // The cards as last shown, at once; then made again from the entries (each title's details).
            if (items.value.isEmpty()) items.value = keepRecent(HeroSnapshot.load(ctx, "cw"))
            rebuildItems()
        }
        thread(name = "glass-cw-log", isDaemon = true) {
            while (true) {
                // Can wait a long time: the TV's adb waits for "Allow USB debugging" to be answered. The accounts
                // below have their own loop, so they never wait for it.
                if (settings.cwLegacy && settings.continueMode != "off") poll()
                // Every 20 s, or right away when Glass comes back to the front (pollNow).
                runCatching { wake.tryAcquire(20, java.util.concurrent.TimeUnit.SECONDS) }
                wake.drainPermits()
            }
        }
        thread(name = "glass-cw-accounts", isDaemon = true) {
            while (true) {
                // Trakt: every 3 minutes on screen (15 away), or right away (at most every 45 s) when Glass comes back.
                val now = System.currentTimeMillis()
                if (settings.cwTrakt && settings.continueMode != "off" && trakt.connected &&
                    now - traktAt > every()) { traktAt = now; syncTrakt() }
                // The official Nuvio app, through the account: as often as Trakt.
                val nu = nuvio
                if (settings.cwNuvio && settings.continueMode != "off" && nu != null && nu.connected &&
                    now - nuvioAt > every()) { nuvioAt = now; syncNuvio(nu) }
                // Stremio, through the account: as often too.
                val sa = stremio
                if (settings.cwStremio && settings.continueMode != "off" && sa != null && sa.connected &&
                    now - stremioAt > every()) { stremioAt = now; syncStremio(sa) }
                woke = false
                runCatching { accountsWake.tryAcquire(20, java.util.concurrent.TimeUnit.SECONDS) }
                accountsWake.drainPermits()
            }
        }
    }

    /** Wakes the accounts' loop (Trakt, Nuvio, Stremio) early. */
    private val accountsWake = java.util.concurrent.Semaphore(0)

    /** Called when the home screen resumes, so leaving Nuvio shows up immediately. */
    fun pollNow() { inFront = true; woke = true; wake.release(); accountsWake.release() }

    /** Whether the home screen is on screen: away (watching in another app), the accounts are read far less often. */
    @Volatile var inFront = true

    /** How long between account syncs: soon after coming back, every 3 minutes on screen, every 15 away. */
    private fun every(): Long = if (woke) 45_000L else if (inFront) 180_000L else 900_000L

    /** Trakt sign-in in progress: the line to show ("Go to … and enter CODE"), null when idle. */
    val traktCode = MutableStateFlow<String?>(null)
    @Volatile var traktUrl: String = "https://trakt.tv/activate"
    @Volatile private var signIn: kotlinx.coroutines.Job? = null

    /** Starts the device sign-in; it keeps going when you leave Settings. [say] shows a short message. */
    fun connectTrakt(say: (String) -> Unit) {
        if (signIn?.isActive == true) return
        signIn = scope.launch(Dispatchers.IO) {
            val dc = runCatching { trakt.deviceCode() }.getOrElse { say("Trakt: ${it.message}"); return@launch }
            traktUrl = dc.url
            traktCode.value = dc.userCode
            val ok = trakt.waitForApproval(dc) { signIn?.isCancelled == true }
            traktCode.value = null
            say(if (ok) "Trakt connected" + (trakt.user.takeIf { it.isNotBlank() }?.let { " as $it" } ?: "") else "Trakt: ${trakt.lastError}. Try again")
            if (ok) { settings.cwTrakt = true; syncTraktNow() }
        }
    }

    /** Forces a Trakt sync on the next round (after signing in). */
    fun syncTraktNow() { traktAt = 0L; pollNow() }

    private fun traktIds(): JSONObject = runCatching { JSONObject(sp.getString("traktIds", null) ?: "{}") }.getOrDefault(JSONObject())

    /**
     * What you paused on Trakt joins the list. A title Nuvio Legacy saw more recently keeps Legacy's
     * progress; Trakt titles that are no longer paused there (finished or removed) leave the list.
     */
    private fun syncTrakt() {
        val paused = runCatching { trakt.playback() }.getOrElse { Glog.w("Trakt sync", it); traktStatus.value = "Sync failed: ${it.message}"; return }
        var changed: Boolean
        synchronized(this) {
            val ids = JSONObject()
            val list = entries.value.filterNot { it.app == "Trakt" }.toMutableList()
            for (p in paused) {
                if (p.percent >= 92) continue
                val e = CwEntry(p.imdb, p.season, p.episode, p.percent, p.at, "Trakt")
                val other = list.firstOrNull { it.imdb == p.imdb }
                if (other != null && other.at >= p.at) continue
                list.removeAll { it.imdb == p.imdb }
                list.add(e)
                ids.put(e.key, p.id)
            }
            val trimmed = list.sortedByDescending { it.at }.take(30)
            changed = trimmed != entries.value
            if (changed) {
                entries.value = trimmed
                save(trimmed)
            }
            sp.edit().putString("traktIds", ids.toString()).apply()
        }
        traktStatus.value = "${paused.size} paused on Trakt"
        if (changed) rebuildItems()     // the same list as before: the cards stay (no details read again)
    }

    private fun nuvioKeys(): JSONObject = runCatching { JSONObject(sp.getString("nuvioKeys", null) ?: "{}") }.getOrDefault(JSONObject())

    /** Forces a Nuvio account sync on the next round (after signing in, or turning it on). */
    fun syncNuvioNow() { nuvioAt = 0L; pollNow() }

    /**
     * What you're watching in the official Nuvio app joins the list, as Trakt's does: a title another app saw more
     * recently keeps that app's progress; titles no longer in progress on the account leave the list.
     */
    private fun syncNuvio(nu: NuvioAccount) {
        val all = nu.watchProgress() ?: run { nuvioStatus.value = "Sync failed: ${nu.lastError.ifBlank { "offline" }}"; return }
        // Cinemeta names the title: only IMDb ids (tt…).
        val latest = onePerTitle(all.filter { it.id.startsWith("tt") })
        var changed: Boolean
        synchronized(this) {
            val keys = JSONObject()
            val list = entries.value.filterNot { it.app == "Nuvio" }.toMutableList()
            for (p in latest) {
                if (p.percent >= 92) continue
                val e = CwEntry(p.id, p.season, p.episode, p.percent, p.at, "Nuvio")
                val other = list.firstOrNull { it.imdb == p.id }
                if (other != null && other.at >= p.at) continue
                list.removeAll { it.imdb == p.id }
                list.add(e)
                keys.put(e.key, JSONArray(all.filter { it.id == p.id }.map { it.key }.filter { it.isNotBlank() }))
            }
            val trimmed = list.sortedByDescending { it.at }.take(30)
            changed = trimmed != entries.value
            if (changed) {
                entries.value = trimmed
                save(trimmed)
            }
            sp.edit().putString("nuvioKeys", keys.toString()).apply()
        }
        val n = latest.count { it.percent < 92 }
        Glog.i("Nuvio watch progress: ${all.size} entries on the account, ${latest.size} titles with IMDb ids, $n in progress")
        nuvioStatus.value = if (n == 1) "1 title in progress" else "$n titles in progress"
        if (changed) rebuildItems()     // the same list as before: the cards stay (no details read again)
    }

    /** Forces a Stremio account sync on the next round (after signing in, or turning it on). */
    fun syncStremioNow() { stremioAt = 0L; pollNow() }

    /**
     * What you're watching in Stremio joins the list, as the Nuvio account's does: a title another app saw more recently
     * keeps that app's progress; titles no longer in progress on the account leave the list.
     */
    private fun syncStremio(sa: StremioAccount) {
        val all = sa.watchProgress() ?: run { stremioStatus.value = "Sync failed: ${sa.lastError.ifBlank { "offline" }}"; return }
        // Cinemeta names the title: only IMDb ids (tt…); a series only with its episode known.
        val latest = all.filter { it.id.startsWith("tt") && (it.type == "movie" || it.season > 0 || it.episode > 0) }
        var changed: Boolean
        synchronized(this) {
            val list = entries.value.filterNot { it.app == "Stremio" }.toMutableList()
            for (p in latest) {
                if (p.percent >= 92) continue
                val e = CwEntry(p.id, if (p.type == "movie") 0 else p.season, if (p.type == "movie") 0 else p.episode, p.percent, p.at, "Stremio")
                val other = list.firstOrNull { it.imdb == p.id }
                if (other != null && other.at >= p.at) continue
                list.removeAll { it.imdb == p.id }
                list.add(e)
            }
            val trimmed = list.sortedByDescending { it.at }.take(30)
            changed = trimmed != entries.value
            if (changed) {
                entries.value = trimmed
                save(trimmed)
            }
        }
        val n = latest.count { it.percent < 92 }
        Glog.i("Stremio watch progress: ${all.size} titles with a position, ${latest.size} with IMDb ids, $n in progress")
        stremioStatus.value = if (n == 1) "1 title in progress" else "$n titles in progress"
        if (changed) rebuildItems()     // the same list as before: the cards stay (no details read again)
    }

    companion object {
        /**
         * One entry per title from the Nuvio account's rows: a film's latest, a series' furthest episode (specials only
         * when there's nothing else), as Nuvio resumes it ("Up Next From Furthest Episode", on unless turned off there),
         * so an old episode started again doesn't stand for the series. Each is dated by its title's latest watch.
         */
        fun onePerTitle(rows: List<NuvioAccount.Progress>): List<NuvioAccount.Progress> = rows.groupBy { it.id }.mapNotNull { (_, l) ->
            val pool = l.filter { it.season > 0 }.ifEmpty { l }
            val pick = if (l.first().type == "movie") l.maxByOrNull { it.at }
            else pool.maxWithOrNull(compareBy<NuvioAccount.Progress> { it.season }.thenBy { it.episode })
            pick?.copy(at = l.maxOf { it.at })
        }
    }

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

    /**
     * Whether [markWatched] can mark this title watched in the app it came from: the Nuvio account, Trakt, or Stremio
     * for a film (an episode there needs Stremio's own list of the series' episodes, which this doesn't keep).
     */
    fun canMarkWatched(key: String): Boolean {
        val e = entries.value.firstOrNull { it.key == key } ?: return false
        return when (e.app) {
            "Nuvio" -> nuvio?.connected == true
            "Trakt" -> trakt.connected
            "Stremio" -> e.type == "movie" && stremio?.connected == true
            else -> false
        }
    }

    /** Marks the title (its episode) watched where it came from, then takes it out of the list as [remove] does. */
    fun markWatched(key: String, title: String, done: (Boolean) -> Unit) {
        val e = entries.value.firstOrNull { it.key == key } ?: return done(false)
        scope.launch(Dispatchers.IO) {
            val ok = when (e.app) {
                "Nuvio" -> nuvio?.markWatched(e.imdb, e.type, title, e.season, e.episode) == true
                "Trakt" -> trakt.markWatched(e.imdb, e.type, e.season, e.episode)
                "Stremio" -> stremio?.markWatched(e.imdb) == true
                else -> false
            }
            if (ok) remove(key)
            done(ok)
        }
    }

    fun remove(key: String) {
        if (entries.value.any { it.key == key && it.app == "Trakt" }) {
            val id = traktIds().optLong(key, 0L)
            if (id > 0) scope.launch(Dispatchers.IO) { trakt.removePlayback(id) }
        }
        if (entries.value.any { it.key == key && it.app == "Nuvio" }) {
            val a = nuvioKeys().optJSONArray(key)
            val keys = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
            val nu = nuvio
            if (keys.isNotEmpty() && nu != null) scope.launch(Dispatchers.IO) { nu.removeProgress(keys) }
        }
        entries.value.firstOrNull { it.key == key && it.app == "Stremio" }?.let { e ->
            val sa = stremio
            if (sa != null) scope.launch(Dispatchers.IO) { sa.rewind(e.imdb) }
        }
        val l = entries.value.filterNot { it.key == key }
        entries.value = l; save(l)
        scope.launch(Dispatchers.IO) { rebuildItems() }
    }

    /** Turns entries into carousel items using Cinemeta (name, art, logo, synopsis, rating). */
    private fun rebuildItems() {
        // Settings › Continue Watching › Keep: only what was watched within that many days (0: everything).
        val days = settings.cwDays
        val since = if (days > 0) System.currentTimeMillis() - days * 24L * 3600 * 1000 else 0L
        val out = entries.value.filter { it.percent >= 2 && it.at >= since }.mapNotNull { e ->
            runCatching {
                val meta = http.json("https://v3-cinemeta.strem.io/meta/${e.type}/${e.imdb}.json", 7L * 24 * 3600 * 1000).optJSONObject("meta")
                    ?: return@runCatching null
                fun str(k: String) = meta.optString(k).takeIf { it.isNotBlank() && it != "null" }.orEmpty()
                val rating = meta.optString("imdbRating").toDoubleOrNull()?.let { String.format(Locale.US, "%.1f", it) }.orEmpty()
                val year = Regex("(19|20)\\d{2}").find(str("releaseInfo").ifBlank { str("year") })?.value.orEmpty()
                val genres = meta.optJSONArray("genres")?.let { a -> (0 until minOf(3, a.length())).map { a.optString(it) } }.orEmpty()
                val sub = if (e.type == "series") "S${e.season} · E${e.episode}" else ""
                // The episode's own title, from the series' list of episodes.
                val episodeTitle = if (e.type != "series") "" else meta.optJSONArray("videos")?.let { v ->
                    (0 until v.length()).mapNotNull { v.optJSONObject(it) }
                        .firstOrNull { it.optInt("season") == e.season && it.optInt("episode", it.optInt("number")) == e.episode }
                        ?.let { it.optString("name").ifBlank { it.optString("title") } }
                }?.takeIf { it.isNotBlank() && it != "null" }.orEmpty()
                HeroItem(
                    key = e.key, id = e.imdb, type = e.type, name = str("name"),
                    poster = str("poster"), background = str("background").ifBlank { str("poster") }, logo = str("logo"),
                    overview = str("description"), rating = rating, year = year, genres = genres, platform = "",
                    progress = e.percent / 100f, sub = sub, source = if (e.app == "Trakt") "" else e.app,
                    episodeTitle = episodeTitle,
                )
            }.getOrNull()
        }
        items.value = out
        HeroSnapshot.save(ctx, "cw", out)
    }

    /** The saved cards still in the entries and within the days kept (the entries load before the cards). */
    private fun keepRecent(list: List<HeroItem>): List<HeroItem> {
        val days = settings.cwDays
        val since = if (days > 0) System.currentTimeMillis() - days * 24L * 3600 * 1000 else 0L
        val keep = entries.value.filter { it.at >= since }.map { it.key }.toHashSet()
        return list.filter { it.key in keep }
    }

    /** Settings changed (how many days are kept): the cards made again. */
    fun rebuild() { scope.launch(Dispatchers.IO) { rebuildItems() } }

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
