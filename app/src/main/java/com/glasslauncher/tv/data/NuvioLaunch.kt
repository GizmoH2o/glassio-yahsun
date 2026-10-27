package com.glasslauncher.tv.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Opens a title inside a Nuvio app (Nuvio Legacy, the official Nuvio TV app, Nuvio Arc (fork), Nuvio Re-Shaped) or
 * Stremio. "auto" (default) uses the first one installed, in that order.
 */
object NuvioLaunch {
    const val LEGACY_PKG = "space.nuvio.nativelegacy"
    const val OFFICIAL_PKG = "com.nuvio.tv"
    const val ARC_PKG = "com.nuvio.tv.test"
    const val RESHAPED_PKG = "com.nuvioreshaped.tv"
    /** Stremio for Android and Android TV. */
    const val STREMIO_PKG = "com.stremio.one"

    /** Setting value → package, in the "auto" priority order. */
    val TARGETS = linkedMapOf(
        "legacy" to LEGACY_PKG, "official" to OFFICIAL_PKG, "arc" to ARC_PKG, "reshaped" to RESHAPED_PKG, "stremio" to STREMIO_PKG,
    )
    val NAMES = mapOf(
        "auto" to "Auto", "legacy" to "Nuvio Legacy", "official" to "Nuvio (official)",
        "arc" to "Nuvio Arc", "reshaped" to "Nuvio Re-Shaped", "stremio" to "Stremio",
    )

    fun installed(ctx: Context, pkg: String) = runCatching { ctx.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    /** The target actually used: the chosen one, or for "auto" the first installed (Legacy if none). */
    fun resolve(ctx: Context, s: Settings): String {
        val t = s.nuvioTarget
        if (t in TARGETS) return t
        return TARGETS.entries.firstOrNull { installed(ctx, it.value) }?.key ?: "legacy"
    }

    fun packageName(ctx: Context, s: Settings) = TARGETS.getValue(resolve(ctx, s))

    /**
     * The target for a title from Continue Watching, which opens in the app it came from whatever "Open titles in" says:
     * Stremio's in Stremio; Nuvio Legacy's in Legacy if it's there; a Nuvio account's in the chosen Nuvio app, else the
     * first Nuvio app installed. Anything else ("" or Trakt's) goes where [resolve] says.
     */
    fun resolve(ctx: Context, s: Settings, item: HeroItem): String {
        val nuvios = TARGETS.keys - "stremio"
        return when (item.source) {
            "Stremio" -> "stremio"
            "Nuvio Legacy" -> if (installed(ctx, LEGACY_PKG)) "legacy" else nuvio(ctx, s, nuvios)
            "Nuvio" -> nuvio(ctx, s, nuvios)
            else -> resolve(ctx, s)
        }
    }

    private fun nuvio(ctx: Context, s: Settings, nuvios: Set<String> = TARGETS.keys - "stremio"): String =
        s.nuvioTarget.takeIf { it in nuvios } ?: nuvios.firstOrNull { installed(ctx, TARGETS.getValue(it)) } ?: "legacy"

    /** The other installed apps [item] can open in (your Nuvio app, Stremio), besides the one OK opens it in. */
    fun others(ctx: Context, s: Settings, item: HeroItem): List<String> {
        val main = resolve(ctx, s, item)
        return listOf(nuvio(ctx, s), "stremio").filter { it != main && installed(ctx, TARGETS.getValue(it)) }
    }

    /** "Nuvio", "Nuvio Legacy", "Stremio"…: a target's short name for "Open in …". */
    fun shortName(target: String) = if (target == "official") "Nuvio" else NAMES[target] ?: target

    fun intent(ctx: Context, s: Settings, type: String, id: String): Intent = intent(resolve(ctx, s), type, id)

    private fun intent(target: String, type: String, id: String): Intent {
        // No CLEAR_TASK: a running Nuvio is brought forward and opens the title at once, without
        // restarting (no splash). If Nuvio is not running it starts normally.
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        val pkg = TARGETS.getValue(target)
        val t = if (type.equals("movie", true)) "movie" else "series"
        val uri = if (target == "legacy") {
            Uri.Builder().scheme("nuvio-legacy").authority("meta").appendPath(t).appendPath(id.trim()).build()
        } else if (target == "stremio") {
            // Stremio's own links: stremio:///detail/series/tt0944947 opens the title's page.
            Uri.parse("stremio:///detail/$t/" + Uri.encode(id.trim(), ":"))
        } else {
            // Official Nuvio TV, Nuvio Arc and Re-Shaped all read nuvio://meta?type=…&id=…
            Uri.Builder().scheme("nuvio").authority("meta")
                .appendQueryParameter("type", t).appendQueryParameter("id", id.trim()).build()
        }
        return Intent(Intent.ACTION_VIEW, uri).setPackage(pkg).addFlags(flags)
    }

    /** Opens [item] in the app it came from, else where "Open titles in" says; or in [target] when given. */
    fun open(ctx: Context, s: Settings, http: Http, item: HeroItem, target: String = resolve(ctx, s, item)) {
        val pkg = TARGETS.getValue(target)
        try {
            ctx.startActivity(intent(target, item.type, item.id))
        } catch (e: Exception) {
            Glog.w("Open ${item.type} ${item.id} in $pkg", e)
            Toast.makeText(ctx, "${NAMES[target]} is not installed or can't open this title", Toast.LENGTH_LONG).show()
            return
        }
        if (s.haOnLaunch && s.haWebhook.startsWith("http")) {
            thread(name = "ha-hook") {
                runCatching {
                    http.post(s.haWebhook, JSONObject()
                        .put("event", "launch").put("title", item.name).put("type", item.type)
                        .put("id", item.id).put("app", pkg))
                }
            }
        }
    }

    fun testWebhook(s: Settings, http: Http): String = try {
        if (!s.haWebhook.startsWith("http")) "Set the webhook URL first"
        else "Home Assistant answered HTTP " + http.post(s.haWebhook, JSONObject().put("event", "test").put("source", "glassio"))
    } catch (e: Exception) { "Failed: ${e.message}" }
}
