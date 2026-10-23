package com.glasslauncher.tv.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import org.json.JSONObject
import kotlin.concurrent.thread

/** Opens a title inside Nuvio Legacy (default) or Nuvio Re-Shaped, same deep links as Nuvio TV Relay. */
object NuvioLaunch {
    const val LEGACY_PKG = "space.nuvio.nativelegacy"
    const val RESHAPED_PKG = "com.nuvioreshaped.tv"

    fun packageName(s: Settings) = if (s.nuvioTarget == "reshaped") RESHAPED_PKG else LEGACY_PKG

    fun intent(s: Settings, type: String, id: String): Intent {
        // No CLEAR_TASK: a running Nuvio is brought forward and opens the title at once, without
        // restarting (no splash). If Nuvio is not running it starts normally.
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        return if (s.nuvioTarget == "reshaped") {
            val uri = Uri.Builder().scheme("nuvio").authority("meta")
                .appendQueryParameter("type", type.lowercase()).appendQueryParameter("id", id).build()
            Intent(Intent.ACTION_VIEW, uri).setPackage(RESHAPED_PKG).addFlags(flags)
        } else {
            val t = if (type.equals("movie", true)) "movie" else "series"
            val uri = Uri.Builder().scheme("nuvio-legacy").authority("meta").appendPath(t).appendPath(id.trim()).build()
            Intent(Intent.ACTION_VIEW, uri).setPackage(LEGACY_PKG).addFlags(flags)
        }
    }

    fun open(ctx: Context, s: Settings, http: Http, item: HeroItem) {
        try {
            ctx.startActivity(intent(s, item.type, item.id))
        } catch (e: Exception) {
            Toast.makeText(ctx, "Nuvio is not installed or can't open this title", Toast.LENGTH_LONG).show()
            return
        }
        if (s.haOnLaunch && s.haWebhook.startsWith("http")) {
            thread(name = "ha-hook") {
                runCatching {
                    http.post(s.haWebhook, JSONObject()
                        .put("event", "launch").put("title", item.name).put("type", item.type)
                        .put("id", item.id).put("app", packageName(s)))
                }
            }
        }
    }

    fun testWebhook(s: Settings, http: Http): String = try {
        if (!s.haWebhook.startsWith("http")) "Set the webhook URL first"
        else "Home Assistant answered HTTP " + http.post(s.haWebhook, JSONObject().put("event", "test").put("source", "glassio"))
    } catch (e: Exception) { "Failed: ${e.message}" }
}
