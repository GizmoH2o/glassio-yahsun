package com.glasslauncher.tv.saver

import com.glasslauncher.tv.GlassApp

/** Builds the aerial-video playlist: the user's own links plus (optionally) Apple's public aerial feed. */
object AerialSource {
    private const val APPLE_FEED = "https://sylvan.apple.com/Aerials/2x/entries.json"
    /** Apple's current (tvOS 26) aerial list, kept up to date by the Aerial Views project; the old feed above
     *  still answers but most of its videos are gone (404). */
    private const val TVOS_FEED = "https://raw.githubusercontent.com/theothernt/AerialViews/master/app/src/main/res/raw/tvos26.json"

    /** Blocking; call off the main thread. */
    fun playlist(app: GlassApp): List<String> {
        val list = linkedSetOf<String>()
        list += app.settings.aerialUrls.filter { it.startsWith("http") }
        if (app.settings.useAppleAerials) list += apple(app)
        return list.toList().shuffled()
    }

    private fun apple(app: GlassApp): List<String> = feed(app, TVOS_FEED).ifEmpty { feed(app, APPLE_FEED) }

    private fun feed(app: GlassApp, url: String): List<String> = runCatching {
        val assets = app.http.json(url, 7L * 24 * 3600 * 1000).optJSONArray("assets") ?: return emptyList()
        (0 until assets.length()).mapNotNull { i ->
            val a = assets.optJSONObject(i) ?: return@mapNotNull null
            listOf("url-1080-H264", "url-1080-SDR", "url-4K-SDR").map { a.optString(it) }.firstOrNull { it.startsWith("http") }
        }
    }.getOrElse { com.glasslauncher.tv.data.Glog.w("Apple aerial feed", it); emptyList() }
}
