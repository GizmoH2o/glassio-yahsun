package com.glasslauncher.tv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The Stremio account (api.strem.io), signed in on the TV with a code like the Stremio TV app does: its add-ons, its
 * Library and what's being watched in Stremio (the library's items in progress, Stremio's own Continue Watching).
 *
 * Sign-in: link.stremio.com hands out a short code and its page; the person opens it on the phone, signs in to Stremio
 * there and confirms; the code then reads back as the account's key (authKey). The key stays on the TV, in its own
 * preferences file (backed up with the rest, like the Nuvio sign-in).
 */
class StremioAccount(ctx: Context) {
    private val sp = ctx.getSharedPreferences("glass_stremio", Context.MODE_PRIVATE)
    private val api = "https://api.strem.io/api/"
    private val link = "https://link.stremio.com/api/v2/"

    val connected: Boolean get() = authKey.isNotBlank()
    val email: String get() = sp.getString("email", "").orEmpty()
    private val authKey: String get() = sp.getString("authKey", "").orEmpty()
    @Volatile var lastError: String = ""

    data class Code(val code: String, val url: String)

    private fun read(c: HttpURLConnection): Pair<Int, String> {
        val code = c.responseCode
        val text = runCatching { (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
        return code to text
    }

    private fun get(url: String): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 15000
        try { return read(c) } finally { c.disconnect() }
    }

    /**
     * One call to Stremio's API: its answer's "result", or null with [lastError] set. A key Stremio no longer knows
     * ("Session does not exist") signs out here, as the Nuvio account does on a refused token.
     */
    private fun call(method: String, body: JSONObject): Any? {
        val c = URL(api + method).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 20000
        c.requestMethod = "POST"
        c.setRequestProperty("Content-Type", "application/json")
        val (code, text) = try {
            c.doOutput = true; c.outputStream.use { it.write(body.toString().toByteArray()) }
            read(c)
        } finally { c.disconnect() }
        if (code !in 200..299) { lastError = "Stremio answered HTTP $code"; Glog.w("Stremio $method: HTTP $code"); return null }
        val o = runCatching { JSONObject(text) }.getOrNull() ?: run { lastError = "Stremio sent something unexpected"; return null }
        o.optJSONObject("error")?.let { e ->
            lastError = "Stremio: " + e.optString("message").ifBlank { "error ${e.optInt("code")}" }
            Glog.w("Stremio $method: ${e.optInt("code")} ${e.optString("message")}")
            if (e.optInt("code") == 1) signOut(remote = false)
            return null
        }
        return if (o.has("result")) o.get("result") else null
    }

    /** Step 1: a code and its page (shown as a QR), from Stremio's own TV sign-in. */
    fun startLogin(): Code {
        val (sc, st) = get(link + "create?type=Create")
        check(sc in 200..299) { "Stremio answered HTTP $sc" }
        val r = JSONObject(st).let { it.optJSONObject("result") ?: it }
        val code = r.optString("code")
        check(code.isNotBlank()) { "Stremio sent no code" }
        return Code(code, r.optString("link").ifBlank { "https://link.stremio.com/$code" })
    }

    /** Step 2: waits until the code is confirmed on the phone (ten minutes at most), then keeps the account's key. */
    fun waitForApproval(c: Code, cancelled: () -> Boolean): Boolean {
        lastError = "The code expired"
        val end = System.currentTimeMillis() + 10 * 60_000L
        while (System.currentTimeMillis() < end && !cancelled()) {
            Thread.sleep(3000)
            val (rc, rt) = runCatching { get(link + "read?type=Read&code=" + java.net.URLEncoder.encode(c.code, "UTF-8")) }.getOrNull() ?: continue
            if (rc !in 200..299) continue
            // Not confirmed yet: an error ("Invalid or expired token") until it is.
            val key = runCatching { JSONObject(rt).optJSONObject("result")?.optString("authKey") }.getOrNull().orEmpty()
            if (key.isBlank()) continue
            sp.edit().putString("authKey", key).apply()
            val user = call("getUser", JSONObject().put("type", "GetUser").put("authKey", key)) as? JSONObject
            user?.optString("email")?.takeIf { it.isNotBlank() }?.let { sp.edit().putString("email", it).apply() }
            return true
        }
        return false
    }

    /** Signs out here (and, [remote], ends the key's session on Stremio too). */
    fun signOut(remote: Boolean = true) {
        val key = authKey
        sp.edit().clear().apply()
        if (remote && key.isNotBlank()) Thread({ runCatching { call("logout", JSONObject().put("type", "Logout").put("authKey", key)) } }, "stremio-logout").start()
    }

    /**
     * The add-ons installed on the account, in its order: the same list the Stremio apps read. Null when it couldn't
     * be read (signed out, offline). Stremio's local add-on (on the device it runs on) isn't one Glassio can reach.
     */
    fun addons(): List<String>? {
        val key = authKey.ifBlank { return null }
        val r = call("addonCollectionGet", JSONObject().put("type", "AddonCollectionGet").put("authKey", key).put("update", true)) as? JSONObject
            ?: return null
        val a = r.optJSONArray("addons") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("transportUrl")?.trim() }
            .filter { it.startsWith("http") || it.startsWith("stremio") }
            .filterNot { u -> runCatching { java.net.URI(u.replace("stremio://", "https://")).host.orEmpty().let { it == "127.0.0.1" || it == "localhost" } }.getOrDefault(false) }
    }

    /** All the account's library items as Stremio keeps them (removed ones too), or null (signed out, offline). */
    private fun items(ids: List<String> = emptyList()): List<JSONObject>? {
        val key = authKey.ifBlank { return null }
        val a = call("datastoreGet", JSONObject().put("authKey", key).put("collection", "libraryItem")
            .put("ids", JSONArray(ids)).put("all", ids.isEmpty())) as? JSONArray ?: return null
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optString("_id").isNotBlank() }
    }

    private fun put(item: JSONObject): Boolean {
        val key = authKey.ifBlank { return false }
        item.put("_mtime", iso(System.currentTimeMillis()))
        return call("datastorePut", JSONObject().put("authKey", key).put("collection", "libraryItem").put("changes", JSONArray().put(item))) != null
    }

    data class Saved(val id: String, val type: String, val name: String, val poster: String, val at: Long)

    /** The account's Library (what's saved, not removed), newest first. Null when it couldn't be read. */
    fun library(): List<Saved>? = items()?.filter { !it.optBoolean("removed") && !it.optBoolean("temp") && it.optString("type") != "other" }
        ?.map { o ->
            Saved(o.optString("_id"), o.optString("type"), o.optString("name"), o.optString("poster").takeIf { it != "null" }.orEmpty(),
                ms(o.optString("_ctime")).takeIf { it > 0 } ?: ms(o.optString("_mtime")))
        }?.sortedByDescending { it.at }

    /** One title in progress in Stremio: its episode (0 for a film) and how far, as the library item's state says. */
    data class Progress(val id: String, val type: String, val season: Int, val episode: Int, val percent: Int, val at: Long)

    /**
     * What's being watched in Stremio, as its own Continue Watching shows it: library items (saved, or only watched)
     * with a position in them. Null when it couldn't be read.
     */
    fun watchProgress(): List<Progress>? = items()?.mapNotNull { o ->
        if (o.optString("type") == "other" || (o.optBoolean("removed") && !o.optBoolean("temp"))) return@mapNotNull null
        val s = o.optJSONObject("state") ?: return@mapNotNull null
        val offset = s.optLong("timeOffset"); val duration = s.optLong("duration")
        if (offset <= 0 || duration <= 0) return@mapNotNull null
        val id = o.optString("_id")
        val (season, episode) = episodeOf(s.optString("video_id").takeIf { it != "null" }.orEmpty(), id)
        val type = if (o.optString("type") == "movie") "movie" else "series"
        Progress(id, type, season, episode, (offset * 100 / duration).toInt().coerceIn(0, 100),
            ms(s.optString("lastWatched")).takeIf { it > 0 } ?: ms(o.optString("_mtime")))
    }

    /**
     * Adds a title to (or removes it from) the account's Library, as the Stremio apps do: the item is kept and marked
     * removed (or not), everything else in it as it was; a title not there yet is added with a fresh state.
     */
    fun setSaved(add: Boolean, id: String, type: String, name: String, poster: String): Boolean = runCatching {
        if (id.isBlank()) return false
        val had = items(listOf(id)) ?: return false
        val item = had.firstOrNull { it.optString("_id") == id }
        if (item == null) {
            if (!add) return true
            val now = iso(System.currentTimeMillis())
            return put(JSONObject().put("_id", id).put("name", name.ifBlank { "Untitled" }).put("type", if (type == "movie") "movie" else "series")
                .put("poster", poster.ifBlank { JSONObject.NULL }).put("posterShape", "poster").put("removed", false).put("temp", false)
                .put("_ctime", now).put("state", freshState())
                .put("behaviorHints", JSONObject().put("defaultVideoId", JSONObject.NULL).put("featuredVideoId", JSONObject.NULL).put("hasScheduledVideos", false)))
        }
        if (add == (!item.optBoolean("removed") && !item.optBoolean("temp"))) return true
        item.put("removed", !add)
        if (add) item.put("temp", false)
        put(item)
    }.getOrElse { Glog.w("Stremio library change", it); false }

    /** Takes a title out of what's being watched (Continue Watching here, so in Stremio too): back to its start. */
    fun rewind(id: String): Boolean = runCatching {
        val item = items(listOf(id))?.firstOrNull { it.optString("_id") == id } ?: return false
        val s = item.optJSONObject("state") ?: return true
        s.put("timeOffset", 0)
        put(item)
    }.getOrElse { Glog.w("Stremio rewind", it); false }

    private fun freshState() = JSONObject().put("lastWatched", JSONObject.NULL).put("timeWatched", 0).put("timeOffset", 0)
        .put("overallTimeWatched", 0).put("timesWatched", 0).put("flaggedWatched", 0).put("duration", 0)
        .put("video_id", JSONObject.NULL).put("watched", JSONObject.NULL).put("noNotif", false)

    companion object {
        /**
         * A series' episode from Stremio's video id, the title's id and then its numbers ("tt0944947:1:2": season 1,
         * episode 2; "kitsu:123:5": episode 5 of its one season); 0, 0 for a film or anything else.
         */
        fun episodeOf(videoId: String, id: String): Pair<Int, Int> {
            if (videoId.isBlank() || videoId == id || !videoId.startsWith("$id:")) return 0 to 0
            val parts = videoId.removePrefix("$id:").split(":")
            return when (parts.size) {
                2 -> (parts[0].toIntOrNull() ?: return 0 to 0) to (parts[1].toIntOrNull() ?: return 0 to 0)
                1 -> 1 to (parts[0].toIntOrNull() ?: return 0 to 0)
                else -> 0 to 0
            }
        }

        /** Stremio's dates ("2024-03-01T20:15:00.000Z") as ms; 0 when there's none. */
        fun ms(iso: String): Long = runCatching { java.time.Instant.parse(iso).toEpochMilli() }.getOrDefault(0L)

        fun iso(ms: Long): String = java.time.Instant.ofEpochMilli(ms).toString()
    }
}
