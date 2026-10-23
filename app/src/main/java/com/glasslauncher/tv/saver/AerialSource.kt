package com.glasslauncher.tv.saver

import com.glasslauncher.tv.GlassApp

/** Builds the aerial-video playlist: the user's own links plus (optionally) Apple's public aerial feed. */
object AerialSource {
    private const val APPLE_FEED = "https://sylvan.apple.com/Aerials/2x/entries.json"

    /** Blocking; call off the main thread. */
    fun playlist(app: GlassApp): List<String> {
        val list = linkedSetOf<String>()
        list += app.settings.aerialUrls.filter { it.startsWith("http") }
        if (app.settings.useAppleAerials) list += apple(app)
        return list.toList().shuffled()
    }

    private fun apple(app: GlassApp): List<String> = runCatching {
        val assets = app.http.json(APPLE_FEED, 7L * 24 * 3600 * 1000).optJSONArray("assets") ?: return emptyList()
        (0 until assets.length()).mapNotNull { i ->
            val a = assets.optJSONObject(i) ?: return@mapNotNull null
            listOf("url-1080-SDR", "url-1080-H264", "url-4K-SDR").map { a.optString(it) }.firstOrNull { it.startsWith("http") }
        }
    }.getOrDefault(emptyList())
}
