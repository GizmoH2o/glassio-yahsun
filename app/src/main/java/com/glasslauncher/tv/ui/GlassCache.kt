package com.glasslauncher.tv.ui

import android.content.Context
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher

/** The three small copies of a backdrop that the glass refracts: blurred (setting), sharp, heavy (folder / veil). */
data class GlassImages(val blurred: ImageBitmap, val sharp: ImageBitmap, val heavy: ImageBitmap)

/** A TMDB image at another size ("w780", "w1280"); other URLs are returned unchanged. */
fun sized(url: String, size: String) = url.replace("/t/p/original/", "/t/p/$size/")

/**
 * Glass copies of recent and upcoming backdrops, prepared ahead of time so a slide, its backdrop and the
 * glass in front of it all change in the same frame. Each entry is ~1.5 MB (three 480×270 bitmaps).
 *
 * The work runs in its own scope: a caller that goes away (you pressed Right again) only stops waiting,
 * it never cancels a job other callers are waiting for.
 */
object GlassCache {
    private val cache = LruCache<String, GlassImages>(24)
    private val pending = HashMap<String, Deferred<GlassImages?>>()
    // One low-priority worker: blurring never competes with drawing the screen.
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "glass-blur").apply { isDaemon = true }
    }
    private val scope = CoroutineScope(SupervisorJob() + worker.asCoroutineDispatcher())

    fun peek(url: String, blur: Int): GlassImages? = pinned["$blur|$url"] ?: cache.get("$blur|$url")

    /** Continue Watching titles: kept for as long as they are in the list, whatever else is browsed. */
    private val pinned = java.util.concurrent.ConcurrentHashMap<String, GlassImages>()
    val pinnedFull = java.util.concurrent.ConcurrentHashMap<String, ImageBitmap>()
    val pinnedLogo = java.util.concurrent.ConcurrentHashMap<String, ImageBitmap>()

    suspend fun pin(ctx: Context, url: String, blur: Int) {
        val key = "$blur|$url"
        if (pinned.containsKey(key)) return
        get(ctx, url, blur)?.let { pinned[key] = it }
    }

    /** Drops pins of titles that left Continue Watching. */
    fun keepOnly(urls: Set<String>) {
        pinned.keys.removeAll { k -> k.substringAfter('|') !in urls }
        pinnedFull.keys.retainAll(urls)
        pinnedLogo.keys.retainAll(urls)
    }

    suspend fun get(ctx: Context, url: String, blur: Int): GlassImages? {
        val key = "$blur|$url"
        pinned[key]?.let { return it }
        cache.get(key)?.let { return it }
        val job = synchronized(pending) {
            pending.getOrPut(key) {
                scope.async {
                    try {
                        val req = ImageRequest.Builder(ctx.applicationContext).data(sized(url, "w780"))
                            .size(480, 270).allowHardware(false).build()
                        val src = (SingletonImageLoader.get(ctx.applicationContext).execute(req) as? SuccessResult)?.image?.toBitmap()
                        src?.let { GlassImages(blurredBackdrop(it, blur), it.asImageBitmap(), blurredBackdrop(it, 80)) }
                            ?.also { cache.put(key, it) }
                    } catch (e: Exception) {
                        null
                    } finally {
                        synchronized(pending) { pending.remove(key) }
                    }
                }
            }
        }
        return job.await()
    }
}
