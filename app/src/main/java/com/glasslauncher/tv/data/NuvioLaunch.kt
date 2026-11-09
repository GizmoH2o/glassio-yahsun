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

    /** [intentFor], as Yahsun's code names it. */
    private fun intent(target: String, type: String, id: String): Intent = intentFor(target, type, id)

    fun intent(ctx: Context, s: Settings, type: String, id: String): Intent = intentFor(resolve(ctx, s), type, id)

    /** The installed apps a title can be opened in (Nuvio's versions and Stremio), as target → name. */
    fun installedTargets(ctx: Context): List<Pair<String, String>> =
        TARGETS.entries.filter { installed(ctx, it.value) }.map { it.key to NAMES.getValue(it.key) }

    /** Opens the title in one particular app ([target] as in [TARGETS]). */
    fun openIn(ctx: Context, target: String, item: HeroItem): Boolean = runCatching { ctx.startActivity(intentFor(target, item.type, item.id)) }
        .onFailure { Glog.w("Open in $target", it); com.glasslauncher.tv.ui.toast(ctx, "${NAMES[target]} can't open this title") }.isSuccess

    /**
     * Streaming apps by the names TMDB gives the platform (network) a title is on: name keyword → package.
     * They are opened at their home page (they don't take a TMDB or IMDb id).
     */
    private val PLATFORMS = listOf(
        "netflix" to "com.netflix.ninja", "apple tv" to "com.apple.atve.androidtv.appletv", "prime" to "com.amazon.amazonvideo.livingroom",
        "amazon" to "com.amazon.amazonvideo.livingroom", "disney" to "com.disney.disneyplus", "hbo" to "com.wbd.stream", "max" to "com.wbd.stream",
        "hulu" to "com.hulu.livingroomplus", "paramount" to "com.cbs.ott", "peacock" to "com.peacocktv.peacockandroid",
        "shahid" to "net.mbc.shahidTV", "youtube" to "com.google.android.youtube.tv", "crunchyroll" to "com.crunchyroll.crunchyroid",
        "osn" to "com.osn.go", "starz" to "com.bydeluxe.d3.android.program.starz", "mubi" to "com.mubi",
    )

    /** The installed streaming app for [platform] (e.g. "Apple TV+"), or null. */
    fun platformApp(ctx: Context, platform: String): String? {
        val p = platform.lowercase()
        if (p.isBlank()) return null
        return PLATFORMS.firstOrNull { p.contains(it.first) }?.second?.takeIf { installed(ctx, it) }
    }

    fun openApp(ctx: Context, pkg: String): Boolean = runCatching {
        val pm = ctx.packageManager
        val i = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: error("no launch intent")
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

    private fun intentFor(target: String, type: String, id: String): Intent {
        if (target == "legacy") NuvioWatch.opened(type, id.trim())
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

    /** Is there an app on this TV that plays titles (a Nuvio app or Stremio)? */
    fun playable(ctx: Context, s: Settings) = TARGETS.values.any { installed(ctx, it) }

    /**
     * An episode. Stremio goes straight to it; Nuvio's links only know a title, so its page opens, and a note says which
     * episode you picked.
     */
    fun openEpisode(ctx: Context, s: Settings, http: Http, item: HeroItem, season: Int, episode: Int) {
        val target = resolve(ctx, s, item)
        if (target != "stremio") {
            open(ctx, s, http, item, target)
            com.glasslauncher.tv.ui.toast(ctx, "${shortName(target)} opens on the series: choose S$season · E$episode")
            return
        }
        val id = item.id.trim()
        val uri = Uri.parse("stremio:///detail/series/" + Uri.encode(id, ":") + "/" + Uri.encode("$id:$season:$episode", ":"))
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(STREMIO_PKG).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (e: Exception) {
            Glog.w("Open episode in Stremio", e)
            open(ctx, s, http, item, target)
        }
    }

    /** A card's OK: the next episode's own page where that can be, else as [open]. */
    fun play(ctx: Context, s: Settings, http: Http, item: HeroItem) {
        val ep = if (item.upNext) HomeShelf.episodeOf(item.sub) else null
        if (ep != null) openEpisode(ctx, s, http, item, ep.first, ep.second) else open(ctx, s, http, item)
    }

    /**
     * Continue Watching's OK (Settings › Home › OK on Continue watching: Resume in its app): Stremio's resumes at once,
     * its link asking it to play the movie or episode with the stream used last (autoPlay; Stremio's TV app). Nuvio's
     * links only open a title's page (no link of theirs starts a stream), so a Nuvio one opens as [play] does.
     */
    fun resume(ctx: Context, s: Settings, http: Http, item: HeroItem) {
        val target = resolve(ctx, s, item)
        if (target != "stremio") { play(ctx, s, http, item); return }
        val uri = Uri.parse(resumeLink(item.type, item.id, HomeShelf.episodeOf(item.sub)) ?: run { play(ctx, s, http, item); return })
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(STREMIO_PKG).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (e: Exception) {
            Glog.w("Resume in Stremio", e)
            play(ctx, s, http, item)
            return
        }
        HaEvents.playing(ctx, "title", item.name, STREMIO_PKG, JSONObject().put("type", item.type).put("id", item.id))
    }

    /**
     * Stremio's link that plays a movie, or a series' episode (`episode`: season to episode), with the stream used last:
     * stremio:///detail/series/tt…/tt…:1:3?autoPlay=true. Null for a series with no episode known. Pure.
     */
    fun resumeLink(type: String, id: String, episode: Pair<Int, Int>?): String? {
        val i = id.trim()
        if (i.isEmpty()) return null
        val movie = type.equals("movie", true)
        if (!movie && episode == null) return null
        // Anime add-ons' ids (kitsu:42) number episodes alone, with no season: kitsu:42:7.
        val oneSeason = ANIME_IDS.any { i.startsWith(it) }
        val video = if (movie) i else if (oneSeason) "$i:${episode!!.second}" else "$i:${episode!!.first}:${episode.second}"
        return "stremio:///detail/${if (movie) "movie" else "series"}/" + enc(i) + "/" + enc(video) + "?autoPlay=true"
    }

    private val ANIME_IDS = listOf("kitsu:", "mal:", "anilist:", "anidb:")

    /** A path part, its `:` kept (Stremio's ids are built with it). Pure. */
    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("%3A", ":")

    /** Opens [item] in the app it came from, else where "Open titles in" says; or in [target] when given. */
    fun open(ctx: Context, s: Settings, http: Http, item: HeroItem, target: String = resolve(ctx, s, item)) {
        val pkg = TARGETS.getValue(target)
        try {
            ctx.startActivity(intent(target, item.type, item.id))
        } catch (e: Exception) {
            Glog.w("Open ${item.type} ${item.id} in $pkg", e)
            com.glasslauncher.tv.ui.toast(ctx, "${NAMES[target]} is not installed or can't open this title")
            return
        }
        HaEvents.playing(ctx, "title", item.name, pkg, JSONObject().put("type", item.type).put("id", item.id))
    }

    fun testWebhook(s: Settings, http: Http): String = try {
        if (!s.haWebhook.startsWith("http")) "Set the webhook URL first"
        else "Home Assistant answered HTTP " + http.post(s.haWebhook, JSONObject().put("event", "test").put("source", "glassio"))
    } catch (e: Exception) { "Failed: ${e.message}" }
}
