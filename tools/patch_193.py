B = 'app/src/main/java/com/glasslauncher/tv/ui/'


def edit(path, pairs):
    p = B + path
    s = open(p).read()
    for o, n in pairs:
        assert s.count(o) == 1, (path, o[:90])
        s = s.replace(o, n)
    open(p, 'w').write(s)


edit('HomeScreen.kt', [
    # bake the scrims into the backdrop once, off the main thread
    ("""        fadeMs = if (app.browsing()) 220 else settings.transitionMs
        shownBackdrop = fullBmp
""", """        fadeMs = if (app.browsing()) 220 else settings.transitionMs
        val dm = ctx.resources.displayMetrics
        val baked = withContext(Dispatchers.Default) { bakeBackdrop(fullBmp!!, dm.widthPixels, dm.heightPixels) }
        shownBackdrop = baked
"""),
    ("""        upgrade?.let { d -> bmpOf(d.await())?.let { hi -> fadeMs = 0; shownBackdrop = hi } }""",
     """        upgrade?.let { d -> bmpOf(d.await())?.let { hi ->
            val dm = ctx.resources.displayMetrics
            val b = withContext(Dispatchers.Default) { bakeBackdrop(hi, dm.widthPixels, dm.heightPixels) }
            fadeMs = 0; shownBackdrop = b
        } }"""),
    # the page backdrop: one pre-composited picture per slide, no offscreen layer, no gradient layers
    ("""            if (!folderOpen) Box(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }) {
                Backdrop(shownBackdrop, fadeMs)
                Box(Modifier.fillMaxHeight().fillMaxWidth(0.62f).background(Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = 0.88f), 1f to Color.Transparent)))
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().fillMaxHeight(0.42f).background(Brush.verticalGradient(
                    0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.80f))))
            }""", """            // The scrims are baked into the picture (bakeBackdrop): a slide change draws two flat pictures and
            // nothing else, which keeps the crossfade smooth on TV graphics chips.
            if (!folderOpen) Box(Modifier.fillMaxSize()) {
                Backdrop(shownBackdrop, fadeMs)
            }"""),
    ("""@Composable
private fun Backdrop(""", """/**
 * The backdrop scaled to the screen (centre crop) with the two scrims painted in, as one bitmap.
 * Done once per picture on a background thread; drawing it is then a single full-screen copy.
 */
private fun bakeBackdrop(src: ImageBitmap, w: Int, h: Int): ImageBitmap {
    var bmp = src.asAndroidBitmap()
    if (bmp.config == android.graphics.Bitmap.Config.HARDWARE) bmp = bmp.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
    val out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(out)
    val scale = maxOf(w / bmp.width.toFloat(), h / bmp.height.toFloat())
    val dw = bmp.width * scale; val dh = bmp.height * scale
    val dst = android.graphics.RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f)
    c.drawBitmap(bmp, null, dst, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.DITHER_FLAG))
    val p = android.graphics.Paint(android.graphics.Paint.DITHER_FLAG)
    p.shader = android.graphics.LinearGradient(0f, 0f, w * 0.62f, 0f, 0xE0000000.toInt(), 0x00000000, android.graphics.Shader.TileMode.CLAMP)
    c.drawRect(0f, 0f, w * 0.62f, h.toFloat(), p)
    p.shader = android.graphics.LinearGradient(0f, h * 0.58f, 0f, h.toFloat(), 0x00000000, 0xCC000000.toInt(), android.graphics.Shader.TileMode.CLAMP)
    c.drawRect(0f, h * 0.58f, w.toFloat(), h.toFloat(), p)
    out.prepareToDraw()
    return out.asImageBitmap()
}

@Composable
private fun Backdrop("""),
])

# glass: switch with the slide instead of blending old and new every frame; the glass then stays a
# cached layer during the fade (its refraction does not run again on every frame)
edit('Glass.kt', [
    ("""                val m = env.mix?.value ?: 1f
                val prev = if (sharp) env.prevSharp ?: env.prevBmp else env.prevBmp
                if (m < 0.995f && prev != null) {
                    drawBackdropRegion(prev, env.rootW, env.rootH, pos.value, scrims = false)
                    drawBackdropRegion(bmp, env.rootW, env.rootH, pos.value, alpha = m)
                } else drawBackdropRegion(bmp, env.rootW, env.rootH, pos.value)""",
     """                drawBackdropRegion(bmp, env.rootW, env.rootH, pos.value)"""),
])
print('ok')
