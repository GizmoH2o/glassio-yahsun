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
