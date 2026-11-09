package com.glasslauncher.tv.ui

import android.content.Context
import android.media.MediaPlayer
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.viewinterop.AndroidView
import com.glasslauncher.tv.data.Glog
import com.glasslauncher.tv.data.Settings
import java.io.File

/**
 * Your own picture or video behind the home screen instead of the titles' backdrops. A link (http…) or a file
 * in Glassio's backgrounds folder. The glass uses the picture (for a video: one frame of it).
 */
object CustomBackground {
    val exts = setOf("jpg", "jpeg", "png", "webp")
    /** Screensaver as background: the aerial video playing now (one per start of Glassio). */
    val aerial = androidx.compose.runtime.mutableStateOf<String?>(null)
    @Volatile var aerials: List<String> = emptyList()
    private var tries = 0
    /** First video of [list] that answers (Apple's feed still lists videos it has taken down). Blocking. */
    fun firstLive(list: List<String>): String? = list.take(12).firstOrNull { u ->
        runCatching {
            val c = java.net.URL(u).openConnection() as java.net.HttpURLConnection
            c.requestMethod = "HEAD"; c.connectTimeout = 4000; c.readTimeout = 4000
            try { c.responseCode in 200..399 } finally { c.disconnect() }
        }.getOrDefault(false)
    }

    /** That video didn't play (network, codec): the next one, a few times. */
    fun nextAerial() {
        if (aerials.size < 2 || ++tries > 4) { Glog.w("Screensaver background: no aerial video plays here"); return }
        val rest = aerials.drop(aerials.indexOf(aerial.value) + 1) + aerials.take(aerials.indexOf(aerial.value).coerceAtLeast(0))
        Thread { firstLive(rest)?.let { aerial.value = it } }.apply { isDaemon = true }.start()
    }
    val videoExts = setOf("mp4", "mkv", "webm", "mov", "m4v")

    fun folder(ctx: Context): File = (ctx.getExternalFilesDir("backgrounds") ?: File(ctx.filesDir, "backgrounds")).apply { mkdirs() }

    fun files(ctx: Context, video: Boolean): List<File> =
        folder(ctx).listFiles().orEmpty().filter { it.isFile && it.extension.lowercase() in (if (video) videoExts else exts) }.sortedBy { it.name.lowercase() }

    /** What the player / image loader gets: links unchanged, files as file:// URIs. */
    fun uri(src: String): String = if (src.startsWith("http") || src.startsWith("file://")) src else "file://" + src

    /** Bumped when a video frame becomes ready (taking it from a link can take a while). */
    val frameRev = androidx.compose.runtime.mutableIntStateOf(0)
    private val working = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * The picture to use as the backdrop (and for the glass), or null for the carousel's own. A video's frame
     * that isn't ready yet is made in the background (the titles' backdrops show meanwhile).
     */
    fun still(ctx: Context, s: Settings): String? = when (s.bgMode) {
        "image" -> s.bgImage.takeIf { it.isNotBlank() }?.let { uri(it) }
        "video" -> s.bgVideo.takeIf { it.isNotBlank() }?.let { cachedFrame(ctx, it) }
        "aerial" -> aerial.value?.let { cachedFrame(ctx, it) }
        else -> null
    }

    private fun frameFile(ctx: Context, src: String) = File(ctx.cacheDir, "bgframe-" + Integer.toHexString(src.hashCode()) + ".jpg")

    private fun cachedFrame(ctx: Context, src: String): String? {
        val f = frameFile(ctx, src)
        if (f.length() > 0) return "file://" + f.absolutePath
        if (working.add(src)) Thread {
            try { if (frameOf(ctx, src) != null) frameRev.intValue++ } finally { working.remove(src) }
        }.apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
        return null
    }

    /** One frame of the video (cached), so the glass has something to show. */
    private fun frameOf(ctx: Context, src: String): String? {
        val out = frameFile(ctx, src)
        if (out.length() > 0) return "file://" + out.absolutePath
        return runCatching {
            val r = android.media.MediaMetadataRetriever()
            try {
                if (src.startsWith("http")) r.setDataSource(src, HashMap()) else r.setDataSource(src.removePrefix("file://"))
                val b = r.getFrameAtTime(1_500_000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: r.frameAtTime ?: return null
                out.outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
                b.recycle()
            } finally { r.release() }
            "file://" + out.absolutePath
        }.getOrElse { Glog.w("Background video frame", it); null }
    }
}

/**
 * Live copies of the background video for the glass: a small sharp one (clear glass) and a tiny one that,
 * scaled up, reads as frosted. Updated about 12 times a second while the video plays; read only while drawing.
 */
object VideoFrames {
    val sharp = mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    val soft = mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    /** A video is on screen (the page's own picture under it isn't drawn then). */
    val showing = mutableStateOf(false)
    fun clear() { sharp.value = null; soft.value = null; showing.value = false }
}

/** Makes a smooth frosted copy: 960 → 480 → 240 → 160 px with filtering, then two box-blur passes (CPU, ~1 ms). */
private class Softener(val w: Int, val h: Int) {
    private val m1 = android.graphics.Bitmap.createBitmap(480, 270, android.graphics.Bitmap.Config.ARGB_8888)
    private val m2 = android.graphics.Bitmap.createBitmap(240, 135, android.graphics.Bitmap.Config.ARGB_8888)
    private val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
    private val px = IntArray(w * h)
    private val tmp = IntArray(w * h)
    private fun scale(src: android.graphics.Bitmap, dst: android.graphics.Bitmap) =
        android.graphics.Canvas(dst).drawBitmap(src, null, android.graphics.Rect(0, 0, dst.width, dst.height), paint)

    fun make(src: android.graphics.Bitmap, out: android.graphics.Bitmap) {
        scale(src, m1); scale(m1, m2); scale(m2, out)
        out.getPixels(px, 0, w, 0, 0, w, h)
        repeat(2) { blur(px, tmp, false); blur(tmp, px, true) }
        out.setPixels(px, 0, w, 0, 0, w, h)
    }

    private fun blur(src: IntArray, dst: IntArray, vertical: Boolean) {
        val r = 3; val div = 2 * r + 1
        val len = if (vertical) h else w; val lines = if (vertical) w else h
        for (line in 0 until lines) {
            fun idx(i: Int) = if (vertical) i.coerceIn(0, len - 1) * w + line else line * w + i.coerceIn(0, len - 1)
            var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) { val c = src[idx(i)]; sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255 }
            for (i in 0 until len) {
                dst[idx(i)] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val o = src[idx(i - r)]; val n = src[idx(i + r + 1)]
                sr += ((n shr 16) and 255) - ((o shr 16) and 255); sg += ((n shr 8) and 255) - ((o shr 8) and 255); sb += (n and 255) - (o and 255)
            }
        }
    }
}

/**
 * The video, muted and looping, with the page's shades on top. Paused while [playing] is false.
 *
 * A SurfaceView: the video goes straight to the screen as its own layer (the display hardware puts it
 * together), so the GPU doesn't redraw the home screen for every video frame. The glass gets live copies
 * (PixelCopy, scaled on the GPU) about 20 times a second.
 */
@Composable
fun VideoBackground(src: String, playing: Boolean, onError: () -> Unit = {}, busy: () -> Boolean = { false }) {
    var ready by remember(src) { mutableStateOf(false) }
    var aspect by remember(src) { mutableStateOf(16f / 9f) }
    val player = remember(src) { MediaPlayer() }
    var view by remember { mutableStateOf<android.view.SurfaceView?>(null) }
    DisposableEffect(src) {
        Glog.i("Background video on: $src")
        onDispose { Glog.i("Background video off"); runCatching { player.release() }; VideoFrames.clear() }
    }
    androidx.compose.runtime.LaunchedEffect(playing, ready) {
        runCatching { if (ready) { if (playing && !player.isPlaying) player.start() else if (!playing && player.isPlaying) player.pause() } }
    }
    androidx.compose.runtime.LaunchedEffect(ready, playing, view) {
        val v = view ?: return@LaunchedEffect
        if (!ready || !playing || android.os.Build.VERSION.SDK_INT < 24) return@LaunchedEffect
        val sharpBufs = Array(3) { android.graphics.Bitmap.createBitmap(960, 540, android.graphics.Bitmap.Config.ARGB_8888) }
        val softBufs = Array(3) { android.graphics.Bitmap.createBitmap(160, 90, android.graphics.Bitmap.Config.ARGB_8888) }
        val soften = Softener(160, 90)
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        val shadeH = android.graphics.Paint(android.graphics.Paint.DITHER_FLAG).apply {
            shader = android.graphics.LinearGradient(0f, 0f, 960 * 0.62f, 0f, 0xB0000000.toInt(), 0, android.graphics.Shader.TileMode.CLAMP) }
        val shadeV = android.graphics.Paint(android.graphics.Paint.DITHER_FLAG).apply {
            shader = android.graphics.LinearGradient(0f, 540 * 0.58f, 0f, 540f, 0, 0xCC000000.toInt(), android.graphics.Shader.TileMode.CLAMP) }
        var k = 0
        try {
            while (true) {
                kotlinx.coroutines.delay(33)
                if (!v.holder.surface.isValid) continue
                // While you move around, the GPU goes to the menu / dock: the glass catches up right after.
                if (busy()) continue
                val i = k++ % 3
                val sb = sharpBufs[i]
                runCatching {
                    android.view.PixelCopy.request(v, sb, { r ->
                        if (r != android.view.PixelCopy.SUCCESS) return@request
                        // The page's shades, painted into the small copy here on the CPU (the glass then draws one
                        // picture; shading every glass surface on the TV GPU cost more than the whole page).
                        android.graphics.Canvas(sb).apply { drawRect(0f, 0f, sb.width * 0.62f, sb.height.toFloat(), shadeH); drawRect(0f, sb.height * 0.58f, sb.width.toFloat(), sb.height.toFloat(), shadeV) }
                        // Reused buffers (three, in turn): copying into new bitmaps each time piled up native memory faster
                        // than the garbage collector freed it.
                        VideoFrames.sharp.value = sb.asImageBitmap()
                        // Frosted copy: halved step by step (no sparkle), then blurred, so scaled up it reads as frost.
                        val soft = softBufs[i]
                        soften.make(sb, soft)
                        VideoFrames.soft.value = soft.asImageBitmap()
                    }, h)
                }
            }
        } finally { VideoFrames.clear() }
    }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        // Centre crop: the surface is made as big as needed to fill the screen with the video's shape.
        val sw = maxWidth; val sh = maxHeight
        val screenAspect = sw.value / sh.value
        val vw = if (aspect > screenAspect) sh * aspect else sw
        val vh = if (aspect > screenAspect) sh else sw / aspect
        AndroidView(
            factory = { ctx ->
                android.view.SurfaceView(ctx).also { view = it }.apply {
                    holder.addCallback(object : android.view.SurfaceHolder.Callback {
                        override fun surfaceCreated(hd: android.view.SurfaceHolder) {
                            runCatching {
                                player.setDisplay(hd)
                                if (ready) return
                                player.setDataSource(ctx, android.net.Uri.parse(CustomBackground.uri(src)))
                                player.isLooping = true
                                player.setVolume(0f, 0f)
                                player.setOnPreparedListener { p ->
                                    if (p.videoWidth > 0 && p.videoHeight > 0) aspect = p.videoWidth.toFloat() / p.videoHeight
                                    ready = true
                                    VideoFrames.showing.value = true
                                    if (playing) p.start()
                                }
                                player.setOnErrorListener { _, what, extra -> Glog.w("Background video error $what/$extra"); onError(); true }
                                player.prepareAsync()
                            }.onFailure { Glog.w("Background video", it) }
                        }
                        override fun surfaceChanged(hd: android.view.SurfaceHolder, f: Int, w: Int, h: Int) {}
                        override fun surfaceDestroyed(hd: android.view.SurfaceHolder) { runCatching { player.setDisplay(null) } }
                    })
                }
            },
            modifier = Modifier.align(androidx.compose.ui.Alignment.Center).wrapContentSize(unbounded = true).requiredSize(vw, vh),
        )
        // The same shades the backdrops have baked in, so the text stays readable.
        // The page's shades as one small picture painted once (dithered) and scaled up: drawn live as two
        // full-screen gradients over the video they cost the TV GPU 30 ms a frame, this costs about 2.
        if (DebugHide.intValue and 8192 == 0) androidx.compose.foundation.Image(Shades.get(), null, Modifier.fillMaxSize(),
            contentScale = androidx.compose.ui.layout.ContentScale.FillBounds, filterQuality = androidx.compose.ui.graphics.FilterQuality.Low)
    }
}

private object Shades {
    private var img: androidx.compose.ui.graphics.ImageBitmap? = null
    fun get(): androidx.compose.ui.graphics.ImageBitmap = img ?: run {
        val w = 480; val h = 270
        val b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        val p = android.graphics.Paint(android.graphics.Paint.DITHER_FLAG)
        p.shader = android.graphics.LinearGradient(0f, 0f, w * 0.62f, 0f, 0xB0000000.toInt(), 0, android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w * 0.62f, h.toFloat(), p)
        p.shader = android.graphics.LinearGradient(0f, h * 0.58f, 0f, h.toFloat(), 0, 0xCC000000.toInt(), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, h * 0.58f, w.toFloat(), h.toFloat(), p)
        b.copy(android.graphics.Bitmap.Config.ARGB_8888, false).also { b.recycle(); it.prepareToDraw() }.asImageBitmap().also { img = it }
    }
}
