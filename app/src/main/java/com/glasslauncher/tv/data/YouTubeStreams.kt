package com.glasslauncher.tv.data

import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * A YouTube trailer's own video and audio streams, for Glassio's player (ExoPlayer) to play straight: no web view, no
 * YouTube page. The web view and its renderer took about 200 MB beside Glassio, and a 1.8 GB TV closed Glassio with it.
 *
 * Adapted from NuvioTV's InAppYouTubeExtractor (github.com/NuvioMedia/NuvioTV, GPL-3.0, as Glassio): YouTube's own
 * player API asked as its visionOS, Android and iOS apps, one at a time until one gives streams this TV can decode.
 * A separate 1080p (or smaller) H.264 video and the original-language audio come first, then YouTube's HLS playlist,
 * then a single file with both. YouTube refusing (HTTP 429, or "confirm you're not a bot") pauses asking for a while.
 */
object YouTubeStreams {
    /** Where to play from: [videoUrl], and [audioUrl] for the separate audio (null when the video has its sound). */
    data class Source(val videoUrl: String, val audioUrl: String?, val hls: Boolean = false)

    internal data class Candidate(
        val client: String, val priority: Int, val url: String, val score: Double, val hasN: Boolean,
        val height: Int, val width: Int, val fps: Int, val ext: String, val codecs: String?, val hdr: Boolean,
        val defaultAudio: Boolean = true,
    )

    private class Client(val key: String, val id: String, val version: String, val userAgent: String, val context: JSONObject, val priority: Int)

    private const val UA = "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
    private const val FALLBACK_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
    private const val PREFERRED_CLIENT = "visionos"
    private val ID = Regex("^[a-zA-Z0-9_-]{11}$")
    private val API_KEY = Regex("\"INNERTUBE_API_KEY\":\"([^\"]+)\"")
    private val VISITOR = Regex("\"VISITOR_DATA\":\"([^\"]+)\"")
    private val BOT = Regex("\\bbot\\b", RegexOption.IGNORE_CASE)
    private val CODECS = Regex("codecs=\"([^\"]+)\"", RegexOption.IGNORE_CASE)

    private val CLIENTS = listOf(
        Client("visionos", "101", "1.02",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15",
            JSONObject().put("clientName", "VISIONOS").put("clientVersion", "1.02").put("deviceMake", "Apple")
                .put("deviceModel", "RealityDevice17,1").put("osName", "visionOS").put("osVersion", "26.5.23O471").put("hl", "en").put("gl", "US"), 0),
        Client("android", "3", "20.10.35", "com.google.android.youtube/20.10.35 (Linux; U; Android 14; en_US) gzip",
            JSONObject().put("clientName", "ANDROID").put("clientVersion", "20.10.35").put("osName", "Android").put("osVersion", "14")
                .put("platform", "MOBILE").put("androidSdkVersion", 34).put("hl", "en").put("gl", "US"), 1),
        Client("ios", "5", "20.10.1", "com.google.ios.youtube/20.10.1 (iPhone16,2; U; CPU iOS 17_4 like Mac OS X)",
            JSONObject().put("clientName", "IOS").put("clientVersion", "20.10.1").put("deviceModel", "iPhone16,2").put("osName", "iPhone")
                .put("osVersion", "17.4.0.21E219").put("platform", "MOBILE").put("hl", "en").put("gl", "US"), 2),
    )

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true).followSslRedirects(true).build()
    }
    private val probeHttp by lazy {
        OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS).build()
    }

    // The watch page's API key and visitor id, kept three hours (ten minutes for the fallback key).
    private class Config(val apiKey: String, val visitor: String?, val at: Long, val ttl: Long)
    @Volatile private var config: Config? = null
    private val configLock = Mutex()

    // YouTube refusing: no asking until then. Each refusal doubles the pause (1 to 15 minutes); a success resets it.
    @Volatile private var blockedUntil = 0L
    @Volatile private var strikes = 0
    fun refusing(): Boolean = System.currentTimeMillis() < blockedUntil

    // What a video resolved to, until its links expire (YouTube's own `expire`, less a margin).
    private val resolved = ConcurrentHashMap<String, Pair<Source, Long>>()

    private class Refused(msg: String) : Exception(msg)
    private class Stale : Exception()

    /**
     * The streams of YouTube video [id] (or a link to it), [maxHeight] tall at most, playable by this TV
     * ([canDecode]: a codecs string, width, height, fps); null when there are none or YouTube is refusing.
     */
    suspend fun resolve(id: String, maxHeight: Int, canDecode: (String, Int, Int, Int) -> Boolean): Source? = withContext(Dispatchers.IO) {
        val vid = videoId(id) ?: return@withContext null
        val now = System.currentTimeMillis()
        resolved["$vid@$maxHeight"]?.let { (s, until) -> if (now < until) return@withContext s }
        if (refusing()) { Glog.w("Trailer: YouTube is refusing, ${(blockedUntil - now) / 1000}s left"); return@withContext null }
        var out: Source? = null
        try {
            out = try { withTimeout(30_000) { extract(vid, false, maxHeight, canDecode) } }
            catch (e: Stale) { withTimeout(30_000) { extract(vid, true, maxHeight, canDecode) } }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Glog.w("Trailer: YouTube took too long for $vid")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Refused) {
            strikes++
            val pause = (60_000L shl (strikes - 1).coerceAtMost(4)).coerceAtMost(15 * 60_000L)
            blockedUntil = System.currentTimeMillis() + pause
            Glog.w("Trailer: YouTube is refusing (${e.message}), pausing ${pause / 1000}s")
        } catch (e: Stale) {
            Glog.w("Trailer: YouTube still asks to sign in for $vid")
        } catch (e: Exception) {
            Glog.w("Trailer: couldn't read $vid: ${e.message}")
        }
        if (out != null) {
            strikes = 0; blockedUntil = 0
            val expire = Uri.parse(out.videoUrl).getQueryParameter("expire")?.toLongOrNull()?.times(1000)
            resolved["$vid@$maxHeight"] = out to ((expire ?: (now + 3_600_000)) - 10 * 60_000)
        }
        out
    }

    private fun videoId(input: String): String? {
        val t = input.trim()
        if (ID.matches(t)) return t
        return runCatching {
            val u = Uri.parse(if (t.startsWith("http")) t else "https://$t")
            if (u.host.orEmpty().endsWith("youtu.be")) u.pathSegments.firstOrNull()?.takeIf { ID.matches(it) }?.let { return it }
            u.getQueryParameter("v")?.takeIf { ID.matches(it) }?.let { return it }
            val p = u.pathSegments
            if (p.size >= 2 && p[0] in setOf("embed", "shorts", "live") && ID.matches(p[1])) p[1] else null
        }.getOrNull()
    }

    private suspend fun ensureConfig(force: Boolean): Config {
        val now = System.currentTimeMillis()
        config?.takeIf { !force && now - it.at < it.ttl }?.let { return it }
        return configLock.withLock {
            config?.takeIf { !force && System.currentTimeMillis() - it.at < it.ttl }?.let { return@withLock it }
            val r = request("https://www.youtube.com/watch?v=dQw4w9WgXcQ&hl=en", null, mapOf("accept-language" to "en-US,en;q=0.9", "user-agent" to UA))
            if (r.first == 429) throw Refused("watch page 429")
            val c = if (r.first in 200..299) {
                Config(API_KEY.find(r.second)?.groupValues?.get(1) ?: FALLBACK_KEY, VISITOR.find(r.second)?.groupValues?.get(1),
                    System.currentTimeMillis(), 3 * 3_600_000L)
            } else config ?: Config(FALLBACK_KEY, null, System.currentTimeMillis(), 10 * 60_000L)
            config = c
            c
        }
    }

    private suspend fun extract(vid: String, freshConfig: Boolean, maxHeight: Int, canDecode: (String, Int, Int, Int) -> Boolean): Source? {
        val cfg = ensureConfig(freshConfig)
        val progressive = ArrayList<Candidate>(); val video = ArrayList<Candidate>(); val audio = ArrayList<Candidate>()
        val manifests = ArrayList<String>()
        var signIn = 0; var bot = 0
        fun playable(list: List<Candidate>) = list.filter { !it.hdr && (it.codecs == null || canDecode(it.codecs, it.width, it.height, it.fps)) }
        val pending = CLIENTS.iterator()
        fun ask() {
            val c = pending.next()
            val body = JSONObject().put("videoId", vid).put("contentCheckOk", true).put("racyCheckOk", true)
                .put("context", JSONObject().put("client", c.context))
                .put("playbackContext", JSONObject().put("contentPlaybackContext", JSONObject().put("html5Preference", "HTML5_PREF_WANTS")))
            val headers = mutableMapOf("accept-language" to "en-US,en;q=0.9", "content-type" to "application/json", "origin" to "https://www.youtube.com",
                "x-youtube-client-name" to c.id, "x-youtube-client-version" to c.version, "user-agent" to c.userAgent)
            cfg.visitor?.let { headers["x-goog-visitor-id"] = it }
            val r = runCatching { request("https://www.youtube.com/youtubei/v1/player?key=${Uri.encode(cfg.apiKey)}", body.toString(), headers) }.getOrNull() ?: return
            if (r.first == 429) throw Refused("player API ${c.key} 429")
            if (r.first !in 200..299) return
            val o = runCatching { JSONObject(r.second) }.getOrNull() ?: return
            val ps = o.optJSONObject("playabilityStatus")
            val status = ps?.optString("status")
            if (status == "LOGIN_REQUIRED") {
                val reason = ps.optString("reason")
                if (BOT.containsMatchIn(reason)) bot++ else if (!reason.contains("age", true)) signIn++
                return
            }
            if (!status.isNullOrEmpty() && status != "OK") return
            val sd = o.optJSONObject("streamingData") ?: return
            sd.optString("hlsManifestUrl").takeIf { it.isNotBlank() }?.let { manifests += it }
            sd.optJSONArray("formats").forEachObject { f ->
                val url = f.optString("url").ifBlank { null } ?: return@forEachObject
                val mime = f.optString("mimeType")
                if (mime.isNotBlank() && !mime.contains("video/")) return@forEachObject
                progressive += candidate(c, url, f, mime, video = true)
            }
            sd.optJSONArray("adaptiveFormats").forEachObject { f ->
                val url = f.optString("url").ifBlank { null } ?: return@forEachObject
                val mime = f.optString("mimeType")
                if (mime.contains("video/")) video += candidate(c, url, f, mime, video = true)
                else if (mime.contains("audio/")) audio += candidate(c, url, f, mime, video = false)
            }
        }
        // One client at a time, until there's a separate video this TV plays and an audio.
        while (pending.hasNext() && !(playable(video).isNotEmpty() && audio.isNotEmpty())) ask()
        if (progressive.isEmpty() && video.isEmpty() && audio.isEmpty() && manifests.isEmpty()) {
            if (bot > 0) throw Refused("$bot clients hit the bot check")
            if (signIn == CLIENTS.size) { config = null; throw Stale() }
            return null
        }
        // Separate video and audio, the best that fits.
        val v = bestFor(capHeight(playable(video), maxHeight))
        val a = bestFor(audio)
        if (v != null && a != null) {
            val vu = reachable(v.url)
            val au = vu?.let { reachable(a.url) }
            if (vu != null && au != null) return Source(vu, au)
        }
        // YouTube's HLS playlist (it has its sound), then a file with both.
        manifests.firstOrNull()?.let { return Source(it, null, hls = true) }
        sort(progressive).firstOrNull()?.let { p -> reachable(p.url)?.let { return Source(it, null) } }
        return null
    }

    private fun candidate(c: Client, url: String, f: JSONObject, mime: String, video: Boolean): Candidate {
        val bitrate = f.optDouble("bitrate", f.optDouble("averageBitrate", 0.0)).let { if (it.isNaN()) 0.0 else it }
        if (!video) {
            val asr = f.optString("audioSampleRate").toDoubleOrNull() ?: 0.0
            val def = f.optJSONObject("audioTrack")?.optBoolean("audioIsDefault", true) ?: true
            return Candidate(c.key, c.priority, url, bitrate * 1e6 + asr, hasN(url), 0, 0, 0, if (mime.contains("webm")) "webm" else "m4a", null, false, def)
        }
        val h = f.optInt("height", Regex("(\\d{2,4})p").find(f.optString("qualityLabel"))?.groupValues?.get(1)?.toIntOrNull() ?: 0)
        val fps = f.optInt("fps", 0)
        val transfer = f.optJSONObject("colorInfo")?.optString("transferCharacteristics").orEmpty()
        val hdr = transfer.contains("SMPTEST2084") || transfer.contains("ARIB_STD_B67") || f.optString("qualityLabel").contains("HDR", true)
        val codecs = CODECS.find(mime)?.groupValues?.get(1)?.trim()?.ifBlank { null }
        return Candidate(c.key, c.priority, url, h * 1e9 + fps * 1e6 + bitrate, hasN(url), h, f.optInt("width", 0), fps,
            if (mime.contains("webm")) "webm" else "mp4", codecs, hdr)
    }

    /** Within the 16:9 box of [maxHeight] (a wide trailer is shorter than its quality class), or all when none fits. */
    internal fun capHeight(list: List<Candidate>, maxHeight: Int): List<Candidate> {
        if (maxHeight == Int.MAX_VALUE) return list
        val maxWidth = maxHeight * 16 / 9
        return list.filter { it.height <= maxHeight && it.width <= maxWidth }.ifEmpty { list }
    }

    /**
     * Best first: the original audio, then H.264 (the TV's video plane takes it without trouble, as it does every
     * phone's), then quality, then links YouTube doesn't slow down (no `n`), MP4 before WebM, the preferred client.
     */
    internal fun sort(list: List<Candidate>): List<Candidate> = list.sortedWith(
        compareBy<Candidate> { if (it.defaultAudio) 0 else 1 }
            .thenBy { if (it.codecs == null || it.codecs.startsWith("avc1")) 0 else 1 }
            .thenByDescending { it.score }
            .thenBy { if (it.hasN) 1 else 0 }
            .thenBy { if (it.ext == "mp4" || it.ext == "m4a") 0 else 1 }
            .thenBy { it.priority })

    private fun bestFor(list: List<Candidate>): Candidate? =
        list.filter { it.client == PREFERRED_CLIENT }.ifEmpty { list }.let { sort(it).firstOrNull() }

    private fun hasN(url: String) = runCatching { !Uri.parse(url).getQueryParameter("n").isNullOrBlank() }.getOrDefault(false)

    /**
     * A googlevideo link that answers: its own host, else one of the other servers it names (`mn`), whichever answers
     * first within 2 s; null when none does (a 403 everywhere).
     */
    private suspend fun reachable(url: String): String? {
        if (!url.contains("googlevideo.com")) return url
        val uri = Uri.parse(url)
        val host = uri.host ?: return url
        val servers = uri.getQueryParameter("mn")?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
        val all = mutableListOf(url)
        servers.forEachIndexed { i, s ->
            val alt = host.replaceFirst(Regex("^rr\\d+---"), "rr${i + 1}---").replaceFirst(Regex("sn-[a-z0-9]+-[a-z0-9]+"), s)
            if (alt != host) all += url.replace(host, alt)
        }
        if (all.size == 1) return if (answers(url)) url else null
        val first = CompletableDeferred<String>()
        val scope = CoroutineScope(Dispatchers.IO)
        all.forEach { u -> scope.launch { if (answers(u)) first.complete(u) } }
        return try { withTimeoutOrNull(2_000) { first.await() } } finally { scope.cancel() }
    }

    private fun answers(url: String): Boolean = runCatching {
        probeHttp.newCall(Request.Builder().url(url).header("Range", "bytes=0-0").header("user-agent", UA).build()).execute().use { it.code == 200 || it.code == 206 }
    }.getOrDefault(false)

    /** GET (or POST [body] as JSON): the status and the text. */
    private fun request(url: String, body: String?, headers: Map<String, String>): Pair<Int, String> {
        val b = Request.Builder().url(url)
        headers.forEach { (k, v) -> b.header(k, v) }
        if (body != null) b.post(body.toRequestBody("application/json".toMediaType()))
        http.newCall(b.build()).execute().use { r -> return r.code to (r.body?.string().orEmpty()) }
    }

    private inline fun JSONArray?.forEachObject(f: (JSONObject) -> Unit) {
        if (this == null) return
        for (i in 0 until length()) optJSONObject(i)?.let(f)
    }
}
