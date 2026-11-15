package com.glasslauncher.tv.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.glasslauncher.tv.GlassApp
import com.glasslauncher.tv.data.HeroItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The poster style (Settings › Home › Poster style), as the streaming apps' own posters: the picture without its own
 * lettering, where it streams at the top left, its age rating at the top right, the title's logo (else its name) at
 * the bottom over a soft shade, genre · ★ rating · year under it, and a thin edge in the picture's colour.
 *
 * Each is painted once, off the main thread, into one picture, and kept (in memory, and on disk for next time): a card
 * then draws one picture as before. Drawn live, the shades and text over every poster cost a TV's graphics chip far
 * more than the poster itself.
 */
object PosterArt {
    /** The picture's size: a 2:3 poster, about twice what a card shows on a 1080p TV. */
    private const val W = 330
    private const val H = 495
    /** Bumped when the look changes: older pictures on disk are then made again. */
    private const val LOOK = 2

    private val mem = android.util.LruCache<String, ImageBitmap>(36)
    private val gate = Semaphore(2)
    private val failed = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun key(item: HeroItem, service: Boolean) = "${item.key}|$service|$LOOK"

    /** Already made, in memory (no waiting): for a card's first frame. */
    fun cached(item: HeroItem, service: Boolean): ImageBitmap? = mem.get(key(item, service))

    /** The poster for [item]: from memory, else from disk, else made now (background). Null if it can't be made. */
    suspend fun get(app: GlassApp, item: HeroItem, service: Boolean): ImageBitmap? {
        val k = key(item, service)
        mem.get(k)?.let { return it }
        // Not made a minute ago (no picture, no connection): not asked again straight away.
        failed[k]?.let { if (System.currentTimeMillis() - it < 10 * 60_000L) return null }
        return withContext(Dispatchers.IO) {
            gate.withPermit {
                mem.get(k)?.let { return@withPermit it }
                val file = File(File(app.cacheDir, "posters").apply { mkdirs() }, hash(k) + ".webp")
                val fromDisk = runCatching { android.graphics.BitmapFactory.decodeFile(file.path) }.getOrNull()
                val made = fromDisk ?: runCatching { make(app, app.shelf.filled(item), service) }
                    .onFailure { com.glasslauncher.tv.data.Glog.w("Poster style: ${item.name}", it) }.getOrNull()
                    ?.also { b -> runCatching { file.outputStream().use { b.compress(webp(), 88, it) } } }
                if (made == null) { failed[k] = System.currentTimeMillis(); return@withPermit null }
                made.prepareToDraw()
                made.asImageBitmap().also { mem.put(k, it) }
            }
        }
    }

    /** Forgets every made poster (a setting changed what's on them). */
    fun clear(app: GlassApp) {
        mem.evictAll(); failed.clear()
        runCatching { File(app.cacheDir, "posters").listFiles()?.forEach { it.delete() } }
    }

    @Suppress("DEPRECATION")
    private fun webp() = if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP

    private fun hash(s: String) = java.security.MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(24)

    private suspend fun load(app: GlassApp, url: String, w: Int, h: Int): Bitmap? {
        if (url.isBlank()) return null
        val r = coil3.SingletonImageLoader.get(app).execute(ImageRequest.Builder(app).data(url).size(w, h).allowHardware(false).build())
        return (r as? SuccessResult)?.image?.toBitmap()
    }

    /** Paints the poster. Background thread. */
    private suspend fun make(app: GlassApp, item: HeroItem, service: Boolean): Bitmap? {
        // The picture: without its own lettering when TMDB has one; else the catalog's poster (which has its name on it,
        // so the name isn't painted a second time).
        val textless = app.recs.textlessPoster(item)
        val pic = load(app, textless, W, H) ?: load(app, item.poster, W, H) ?: return null
        val ownName = textless.isNotBlank()
        val logo = if (ownName && item.logo.isNotBlank()) runCatching { load(app, item.logo, 600, 220) }.getOrNull() else null
        val cert = runCatching { app.recs.extras(item).cert }.getOrDefault("")

        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val w = W.toFloat(); val h = H.toFloat()
        // The picture, filling the poster.
        val sc = maxOf(w / pic.width, h / pic.height)
        c.drawBitmap(pic, android.graphics.Matrix().apply { setScale(sc, sc); postTranslate((w - pic.width * sc) / 2f, (h - pic.height * sc) / 2f) },
            Paint(Paint.FILTER_BITMAP_FLAG))
        // Shades: a little at the top (the badges), more at the bottom (the name and the line).
        val shade = Paint(Paint.DITHER_FLAG)
        shade.shader = LinearGradient(0f, 0f, 0f, h * 0.3f, AColor.argb(110, 0, 0, 0), 0, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h * 0.3f, shade)
        shade.shader = LinearGradient(0f, h * 0.45f, 0f, h, 0, AColor.argb(242, 8, 10, 14), Shader.TileMode.CLAMP)
        c.drawRect(0f, h * 0.45f, w, h, shade)

        val bold = Typeface.create("sans-serif", Typeface.BOLD)
        // Where it streams: its name as a word at the top left.
        val svc = if (service) item.platform.ifBlank { app.recs.service(item) } else ""
        if (svc.isNotBlank()) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-black", Typeface.BOLD); textSize = w * 0.068f; color = AColor.WHITE
                setShadowLayer(4f, 0f, 1f, AColor.argb(150, 0, 0, 0)) }
            c.drawText(fit(svc, p, w * 0.55f), w * 0.06f, w * 0.06f + p.textSize * 0.8f, p)
        }
        // Its age rating at the top right, in a gold outline.
        if (cert.isNotBlank()) {
            val gold = AColor.rgb(0xE7, 0xB5, 0x4A)
            val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = bold; textSize = w * 0.05f; color = gold }
            val tw = t.measureText(cert); val padX = w * 0.025f; val bh = t.textSize * 1.55f
            val r = RectF(w - w * 0.05f - tw - padX * 2, w * 0.05f, w - w * 0.05f, w * 0.05f + bh)
            c.drawRoundRect(r, bh * 0.28f, bh * 0.28f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AColor.argb(110, 0, 0, 0) })
            c.drawRoundRect(r, bh * 0.28f, bh * 0.28f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = w * 0.006f; color = gold })
            c.drawText(cert, r.left + padX, r.centerY() + t.textSize * 0.36f, t)
        }
        // The line at the bottom: genre · ★ rating · year.
        val lineY = h - w * 0.065f
        val parts = listOfNotNull(item.genres.firstOrNull()?.substringBefore(" & ")?.let { Lang.tr(it) }, item.rating.takeIf { it.isNotBlank() }, item.year.take(4).takeIf { it.isNotBlank() })
        if (parts.isNotEmpty()) {
            val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = bold; textSize = w * 0.056f; color = AColor.WHITE }
            val star = "★ "
            val gap = w * 0.035f
            val widths = parts.map { (if (it == item.rating) t.measureText(star) else 0f) + t.measureText(it) }
            val total = widths.sum() + gap * 2 * (parts.size - 1)
            var x = (w - total) / 2f
            val bar = Paint().apply { color = AColor.argb(110, 255, 255, 255); strokeWidth = w * 0.004f }
            parts.forEachIndexed { i, s ->
                if (s == item.rating) {
                    c.drawText(star, x, lineY, Paint(t).apply { color = AColor.rgb(0xF5, 0xC5, 0x18) }); x += t.measureText(star)
                }
                c.drawText(s, x, lineY, t); x += t.measureText(s)
                if (i < parts.lastIndex) { x += gap; c.drawLine(x, lineY - t.textSize * 0.78f, x, lineY + t.textSize * 0.1f, bar); x += gap }
            }
        }
        // Its own lettering: the logo, fitted into the band above the line; else its name in a bold face.
        if (ownName) {
            val boxW = w * 0.84f; val boxH = h * 0.2f; val boxBottom = lineY - w * 0.09f
            if (logo != null) {
                val k = minOf(boxW / logo.width, boxH / logo.height)
                val lw = logo.width * k; val lh = logo.height * k
                c.drawBitmap(logo, null, RectF((w - lw) / 2f, boxBottom - lh, (w + lw) / 2f, boxBottom), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            } else {
                val t = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD); textSize = w * 0.12f; color = AColor.WHITE
                    setShadowLayer(6f, 0f, 2f, AColor.argb(160, 0, 0, 0)) }
                val layout = android.text.StaticLayout.Builder.obtain(item.name.uppercase(), 0, item.name.length, t, boxW.toInt())
                    .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER).setMaxLines(2).setEllipsize(android.text.TextUtils.TruncateAt.END)
                    .setLineSpacing(0f, 0.92f).build()
                c.save(); c.translate((w - boxW) / 2f, boxBottom - layout.height); layout.draw(c); c.restore()
            }
        }
        // The edge, in the picture's colour (brightened so it reads against the page).
        val tint = com.glasslauncher.tv.ui.BackdropTint.of(pic)
        val edge = tint?.let { val hsv = FloatArray(3); AColor.colorToHSV(it, hsv); hsv[1] = hsv[1].coerceIn(0.25f, 0.6f); hsv[2] = 0.55f; AColor.HSVToColor(hsv) }
            ?: AColor.argb(90, 255, 255, 255)
        val sw = w * 0.012f; val rad = w * 0.055f
        c.drawRoundRect(RectF(sw / 2f, sw / 2f, w - sw / 2f, h - sw / 2f), rad, rad,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = sw; color = edge })
        return out
    }

    /** [s] cut to fit [max] px with an ellipsis. */
    private fun fit(s: String, p: Paint, max: Float): String {
        if (p.measureText(s) <= max) return s
        var t = s
        while (t.length > 1 && p.measureText("$t…") > max) t = t.dropLast(1)
        return "$t…"
    }
}
