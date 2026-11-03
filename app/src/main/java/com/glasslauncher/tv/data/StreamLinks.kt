package com.glasslauncher.tv.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URLEncoder

/**
 * "Open in Netflix" and the like: where a title is on the streaming apps this TV has, as links straight to its page
 * in that app. Wikidata keeps each service's id for a title by its IMDb id (Netflix's, Prime Video's, Disney+'s,
 * Apple TV's); a link is offered only when an app on this TV opens it (some TVs ship an app that others can't open).
 */
object StreamLinks {
    /** A service's page for the title: its name, its app, the link. */
    data class Link(val service: String, val pkg: String, val uri: String) {
        fun intent(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private class Service(val prop: String, val name: String, val pkg: String, val link: (String) -> String)

    private val SERVICES = listOf(
        Service("P1874", "Netflix", "com.netflix.ninja") { "https://www.netflix.com/title/$it" },
        Service("P8055", "Prime Video", "com.amazon.amazonvideo.livingroom") { "https://watch.amazon.com/detail?asin=$it" },
        Service("P7595", "Disney+", "com.disney.disneyplus") { "https://www.disneyplus.com/movies/x/$it" },
        Service("P7596", "Disney+", "com.disney.disneyplus") { "https://www.disneyplus.com/series/x/$it" },
        Service("P9586", "Apple TV", "com.apple.atve.androidtv.appletv") { "https://tv.apple.com/movie/x/$it" },
        Service("P9751", "Apple TV", "com.apple.atve.androidtv.appletv") { "https://tv.apple.com/show/x/$it" },
    )

    /** A service's search in its app, for a title whose page there isn't known: by TMDB's name for the service. */
    private val SEARCH = mapOf(
        "Netflix" to ("com.netflix.ninja" to { q: String -> "https://www.netflix.com/search?q=$q" }),
        "Prime Video" to ("com.amazon.amazonvideo.livingroom" to { q: String -> "https://watch.amazon.com/search?phrase=$q" }),
        "Disney" to ("com.disney.disneyplus" to { q: String -> "https://www.disneyplus.com/search?q=$q" }),
        "Apple TV" to ("com.apple.atve.androidtv.appletv" to { q: String -> "https://tv.apple.com/search?term=$q" }),
    )

    /**
     * The title's pages in the apps this TV can open them in, the first service first; none known, a search for its
     * name in the app of [platform] (TMDB's streaming service for it, data.Tmdb). Blocking; kept 30 days.
     */
    fun forTitle(ctx: Context, http: Http, imdb: String, name: String = "", platform: String = ""): List<Link> {
        val pages = pages(ctx, http, imdb)
        if (pages.isNotEmpty() || name.isBlank()) return pages
        val (pkg, link) = SEARCH[platform] ?: return emptyList()
        val l = Link(if (platform == "Disney") "Disney+" else platform, pkg, link(URLEncoder.encode(name, "UTF-8").replace("+", "%20")))
        return if (ctx.packageManager.resolveActivity(l.intent(), 0) != null) listOf(l) else emptyList()
    }

    private fun pages(ctx: Context, http: Http, imdb: String): List<Link> {
        if (!imdb.matches(Regex("tt\\d{6,10}"))) return emptyList()
        val pm = ctx.packageManager
        val here = SERVICES.filter { s -> runCatching { pm.getPackageInfo(s.pkg, 0) }.isSuccess }
        if (here.isEmpty()) return emptyList()
        val vars = here.indices.joinToString(" ") { "?v$it" }
        val opts = here.mapIndexed { i, s -> "OPTIONAL{?i wdt:${s.prop} ?v$i}" }.joinToString(" ")
        val q = "SELECT $vars WHERE { ?i wdt:P345 \"$imdb\" . $opts } LIMIT 1"
        val row = runCatching {
            http.json("https://query.wikidata.org/sparql?format=json&query=" + URLEncoder.encode(q, "UTF-8"), 30L * 24 * 3600 * 1000)
                .optJSONObject("results")?.optJSONArray("bindings")?.optJSONObject(0)
        }.getOrNull() ?: return emptyList()
        val out = ArrayList<Link>()
        here.forEachIndexed { i, s ->
            val id = row.optJSONObject("v$i")?.optString("value").orEmpty()
            if (id.isBlank() || out.any { it.service == s.name }) return@forEachIndexed
            val link = Link(s.name, s.pkg, s.link(id))
            if (pm.resolveActivity(link.intent(), 0) != null) out += link
        }
        return out
    }
}
