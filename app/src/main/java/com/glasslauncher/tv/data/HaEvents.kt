package com.glasslauncher.tv.data

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * What Glassio tells Home Assistant (Settings › Nuvio, Stremio & Home Assistant › webhook), as JSON events an
 * automation can act on, e.g. lights down when something starts, up again back on Home:
 *   {"event":"playing","what":"title"|"app"|"live","title":…,"app":…,"source":"glassio"}
 *   {"event":"home","source":"glassio"}          back on Glassio's Home after watching
 *   {"event":"screensaver","source":"glassio"}   the screensaver came on
 * With Events to Home Assistant: All (Home Assistant 2.0), also:
 *   {"event":"paused"|"resumed","what":"live"|"catchup"|"recording","title":…,"channel":…}
 *   {"event":"goal","team":…,"match":"Arsenal 1–0 Hull City · 63'","home":…,"away":…,"home_score":…,"away_score":…}
 *   {"event":"kickoff"|"full_time","match":…,"home":…,"away":…,"home_score":…,"away_score":…}   a followed match
 *   {"event":"recording_started"|"recording_finished","title":…,"channel":…}
 *   {"event":"bedtime"|"time_up","profile":…}     a Kids profile's (Family Mode)
 * Read straight from Glassio's settings file, so Live TV's own process sends its events too.
 */
object HaEvents {
    /** Something is playing (a file, so Live TV's process and Home's both know): "home" is sent once, when it stops. */
    private fun mark(ctx: Context) = java.io.File(ctx.filesDir, "ha-watching")

    @JvmStatic
    @JvmOverloads
    fun playing(ctx: Context, what: String, title: String, app: String = "", extra: JSONObject? = null) {
        runCatching { mark(ctx).createNewFile() }
        val body = extra ?: JSONObject()
        send(ctx, body.put("event", "playing").put("what", what).put("title", title).put("app", app))
    }

    /** Back on Home: once after something played. */
    @JvmStatic
    fun home(ctx: Context) {
        if (!mark(ctx).delete()) return
        send(ctx, JSONObject().put("event", "home"))
    }

    @JvmStatic
    fun screensaver(ctx: Context) = send(ctx, JSONObject().put("event", "screensaver"))

    // MARK: - Home Assistant 2.0 (Events to Home Assistant: All)

    /** A followed match's event is told once in this long, however many of Glassio's processes read the scores. */
    const val ONCE_MS = 6 * 3600_000L
    private val BASIC = setOf("playing", "home", "screensaver")

    /** Whether `event` goes with the setting (Basic: the first three only). */
    fun wanted(event: String, all: Boolean) = event in BASIC || all

    /** Whether `key` wasn't told lately (`sent`: what was, and when). */
    fun fresh(sent: Map<String, Long>, key: String, now: Long) = sent[key]?.let { now - it > ONCE_MS } ?: true

    fun pausedBody(paused: Boolean, what: String, title: String, channel: String): JSONObject =
        JSONObject().put("event", if (paused) "paused" else "resumed").put("what", what).put("title", title).put("channel", channel)

    fun goalBody(home: String, away: String, homeScore: Int, awayScore: Int, homeScored: Boolean, clock: String): JSONObject =
        JSONObject().put("event", "goal").put("team", if (homeScored) home else away)
            .put("match", "$home $homeScore–$awayScore $away" + (if (clock.isBlank()) "" else " · $clock"))
            .put("home", home).put("away", away).put("home_score", homeScore).put("away_score", awayScore)

    fun matchBody(kickoff: Boolean, home: String, away: String, homeScore: Int, awayScore: Int): JSONObject =
        JSONObject().put("event", if (kickoff) "kickoff" else "full_time").put("match", "$home $homeScore–$awayScore $away")
            .put("home", home).put("away", away).put("home_score", homeScore).put("away_score", awayScore)

    fun recordingBody(started: Boolean, title: String, channel: String): JSONObject =
        JSONObject().put("event", if (started) "recording_started" else "recording_finished").put("title", title).put("channel", channel)

    fun kidsBody(bedtime: Boolean, profile: String): JSONObject =
        JSONObject().put("event", if (bedtime) "bedtime" else "time_up").put("profile", profile)

    @JvmStatic
    fun paused(ctx: Context, paused: Boolean, what: String, title: String, channel: String) = more(ctx, pausedBody(paused, what, title, channel))

    @JvmStatic
    fun goal(ctx: Context, key: String, home: String, away: String, homeScore: Int, awayScore: Int, homeScored: Boolean, clock: String) {
        if (once(ctx, key)) more(ctx, goalBody(home, away, homeScore, awayScore, homeScored, clock))
    }

    @JvmStatic
    fun match(ctx: Context, key: String, kickoff: Boolean, home: String, away: String, homeScore: Int, awayScore: Int) {
        if (once(ctx, key)) more(ctx, matchBody(kickoff, home, away, homeScore, awayScore))
    }

    @JvmStatic
    fun recording(ctx: Context, started: Boolean, title: String, channel: String) = more(ctx, recordingBody(started, title, channel))

    @JvmStatic
    fun kids(ctx: Context, key: String, bedtime: Boolean, profile: String) {
        if (once(ctx, key)) more(ctx, kidsBody(bedtime, profile))
    }

    /** One of the newer events: sent with Events to Home Assistant: All. */
    private fun more(ctx: Context, body: JSONObject) {
        val all = ctx.getSharedPreferences("glass", Context.MODE_PRIVATE).getBoolean("haEventsAll", false)
        if (!wanted(body.optString("event"), all)) return
        Glog.i("Home Assistant event: " + body.optString("event"))
        send(ctx, body)
    }

    /** Whether `key` is told now (the first time lately), kept in a file the processes share. */
    @Synchronized
    private fun once(ctx: Context, key: String): Boolean {
        val f = java.io.File(ctx.filesDir, "ha-sent.json")
        val now = System.currentTimeMillis()
        val sent = HashMap<String, Long>()
        runCatching { val o = JSONObject(f.readText()); o.keys().forEach { k -> sent[k] = o.optLong(k) } }
        if (!fresh(sent, key, now)) return false
        sent[key] = now
        val keep = JSONObject()
        sent.filter { now - it.value <= ONCE_MS }.forEach { (k, v) -> keep.put(k, v) }
        runCatching { f.writeText(keep.toString()) }
        return true
    }

    private fun send(ctx: Context, body: JSONObject) {
        val sp = ctx.getSharedPreferences("glass", Context.MODE_PRIVATE)
        val url = sp.getString("haWebhook", "").orEmpty().trim()
        if (!sp.getBoolean("haOnLaunch", false) || !url.startsWith("http")) return
        Thread({
            runCatching {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 4000; c.readTimeout = 6000
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                try {
                    c.outputStream.use { it.write(body.put("source", "glassio").toString().toByteArray()) }
                    c.responseCode
                } finally { c.disconnect() }
            }.onFailure { Glog.w("Home Assistant event", it) }
        }, "ha-event").apply { isDaemon = true; start() }
    }
}
