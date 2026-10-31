package com.glasslauncher.tv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A list of titles as last shown (the catalogs, Continue Watching), kept on the TV: Home shows it the moment it opens,
 * pictures and all (they're in the picture cache), while the add-ons and accounts are asked again.
 */
object HeroSnapshot {
    private fun file(ctx: Context, name: String) = File(ctx.filesDir, "snapshot-$name.json")

    /** Blocking (small: a few hundred titles at most). */
    fun save(ctx: Context, name: String, list: List<HeroItem>) {
        runCatching {
            val a = JSONArray()
            for (h in list) a.put(JSONObject()
                .put("key", h.key).put("id", h.id).put("type", h.type).put("name", h.name)
                .put("poster", h.poster).put("background", h.background).put("logo", h.logo)
                .put("overview", h.overview).put("rating", h.rating).put("year", h.year)
                .put("genres", JSONArray(h.genres)).put("platform", h.platform).put("progress", h.progress.toDouble())
                .put("sub", h.sub).put("addonArt", h.addonArt).put("bestArt", h.bestArt).put("country", h.country)
                .put("runtime", h.runtime).put("source", h.source).put("episodeTitle", h.episodeTitle))
            val f = file(ctx, name)
            val tmp = File(f.path + ".tmp")
            tmp.writeText(a.toString())
            tmp.renameTo(f)
        }.onFailure { Glog.w("snapshot $name", it) }
    }

    /** What was saved, or empty. Blocking. */
    fun load(ctx: Context, name: String): List<HeroItem> = runCatching {
        val f = file(ctx, name)
        if (!f.exists()) return emptyList()
        val a = JSONArray(f.readText())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val g = o.optJSONArray("genres")
            HeroItem(
                key = o.getString("key"), id = o.optString("id"), type = o.optString("type"), name = o.optString("name"),
                poster = o.optString("poster"), background = o.optString("background"), logo = o.optString("logo"),
                overview = o.optString("overview"), rating = o.optString("rating"), year = o.optString("year"),
                genres = g?.let { (0 until it.length()).map { j -> it.optString(j) } }.orEmpty(), platform = o.optString("platform"),
                progress = o.optDouble("progress", -1.0).toFloat(), sub = o.optString("sub"), addonArt = o.optString("addonArt"),
                bestArt = o.optString("bestArt"), country = o.optString("country"), runtime = o.optString("runtime"),
                source = o.optString("source"), episodeTitle = o.optString("episodeTitle"),
            )
        }
    }.getOrElse { Glog.w("snapshot $name", it); emptyList() }
}
