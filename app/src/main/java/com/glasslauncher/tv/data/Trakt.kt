package com.glasslauncher.tv.data

import android.content.Context
import com.glasslauncher.tv.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Trakt account (device sign-in, no password on the TV): what you paused in any app that scrobbles to Trakt
 * (the official Nuvio app, Nuvio Arc, Re-Shaped, Stremio, Kodi…) shows up in Continue Watching.
 *
 * The app is a PKCE app, so it has no client secret: the device code itself proves the sign-in. Tokens stay
 * on the TV, in their own preferences file (not in the settings backup or the settings dump).
 */
class Trakt(ctx: Context) {
    private val sp = ctx.getSharedPreferences("glass_trakt", Context.MODE_PRIVATE)
    private val clientId = BuildConfig.TRAKT_CLIENT_ID

    val available: Boolean get() = clientId.isNotBlank()
    val connected: Boolean get() = sp.getString("refresh", "").orEmpty().isNotBlank()
    val user: String get() = sp.getString("user", "").orEmpty()

    data class DeviceCode(val deviceCode: String, val userCode: String, val url: String, val expiresIn: Int, val interval: Int)

    private fun call(method: String, path: String, body: JSONObject? = null, auth: Boolean = false): Pair<Int, String> {
        val c = URL("https://api.trakt.tv$path").openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 12000
        c.requestMethod = method
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("trakt-api-version", "2")
        c.setRequestProperty("trakt-api-key", clientId)
        c.setRequestProperty("User-Agent", "Glassio/1.0")
        if (auth) c.setRequestProperty("Authorization", "Bearer ${accessToken() ?: ""}")
        try {
            if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toString().toByteArray()) } }
            val code = c.responseCode
            val text = runCatching { (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
            return code to text
        } finally { c.disconnect() }
    }

    /** Step 1: a short code for the person to enter at trakt.tv/activate. */
    fun deviceCode(): DeviceCode {
        val (code, text) = call("POST", "/oauth/device/code", JSONObject().put("client_id", clientId))
        check(code in 200..299) { "Trakt answered HTTP $code" }
        val o = JSONObject(text)
        return DeviceCode(o.getString("device_code"), o.getString("user_code"),
            o.optString("verification_url", "https://trakt.tv/activate"), o.optInt("expires_in", 600), o.optInt("interval", 5))
    }

    /** Step 2: poll until the code is approved. Returns true when signed in, false when expired / denied. */
    fun waitForApproval(dc: DeviceCode, cancelled: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + dc.expiresIn * 1000L
        var wait = dc.interval.coerceAtLeast(1) * 1000L
        while (System.currentTimeMillis() < end && !cancelled()) {
            Thread.sleep(wait)
            val (code, text) = runCatching {
                call("POST", "/oauth/device/token", JSONObject().put("code", dc.deviceCode).put("client_id", clientId))
            }.getOrElse { 0 to "" }
            when (code) {
                200 -> { saveTokens(JSONObject(text)); fetchUser(); return true }
                429 -> wait += 1000 // slow down
                404, 409, 410, 418 -> return false // invalid, already used, expired, denied
            }
        }
        return false
    }

    private fun saveTokens(o: JSONObject) {
        val expires = (o.optLong("created_at", System.currentTimeMillis() / 1000) + o.optLong("expires_in", 7 * 86400)) * 1000
        sp.edit().putString("access", o.getString("access_token")).putString("refresh", o.getString("refresh_token"))
            .putLong("expires", expires).apply()
    }

    private fun fetchUser() {
        runCatching {
            val (code, text) = call("GET", "/users/settings", auth = true)
            if (code == 200) sp.edit().putString("user", JSONObject(text).optJSONObject("user")?.optString("username").orEmpty()).apply()
        }
    }

    @Synchronized
    private fun accessToken(): String? {
        val access = sp.getString("access", null) ?: return null
        if (System.currentTimeMillis() < sp.getLong("expires", 0) - 3600_000) return access
        val refresh = sp.getString("refresh", null) ?: return null
        // Refresh tokens are single use: every refresh returns a new pair.
        val body = JSONObject().put("refresh_token", refresh).put("client_id", clientId).put("grant_type", "refresh_token")
        var (code, text) = runCatching { call("POST", "/oauth/token", body) }.getOrElse { return access }
        if (code == 400 || code == 401) {
            val r = runCatching { call("POST", "/oauth/token", body.put("redirect_uri", "http://localhost")) }.getOrNull()
            if (r != null) { code = r.first; text = r.second }
        }
        if (code == 200) { saveTokens(JSONObject(text)); return sp.getString("access", null) }
        if (code == 400 || code == 401) signOut()
        return null
    }

    fun signOut() { sp.edit().clear().apply() }

    data class Paused(val id: Long, val imdb: String, val season: Int, val episode: Int, val percent: Int, val at: Long)

    /** Movies and episodes you paused, newest first (GET /sync/playback). */
    fun playback(): List<Paused> {
        if (!connected) return emptyList()
        val (code, text) = call("GET", "/sync/playback?limit=30", auth = true)
        check(code == 200) { "Trakt answered HTTP $code" }
        val a = JSONArray(text)
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val pct = o.optDouble("progress", 0.0).toInt()
            val at = runCatching { java.time.Instant.parse(o.optString("paused_at")).toEpochMilli() }.getOrDefault(0L)
            when (o.optString("type")) {
                "movie" -> o.optJSONObject("movie")?.optJSONObject("ids")?.optString("imdb")?.takeIf { it.startsWith("tt") }
                    ?.let { Paused(o.optLong("id"), it, 0, 0, pct, at) }
                "episode" -> {
                    val show = o.optJSONObject("show")?.optJSONObject("ids")?.optString("imdb")?.takeIf { it.startsWith("tt") }
                    val ep = o.optJSONObject("episode")
                    if (show == null || ep == null) null
                    else Paused(o.optLong("id"), show, ep.optInt("season"), ep.optInt("number"), pct, at)
                }
                else -> null
            }
        }
    }

    /** Removes a paused item on Trakt too, so it doesn't come back with the next sync. */
    fun removePlayback(id: Long) { runCatching { call("DELETE", "/sync/playback/$id", auth = true) } }
}
