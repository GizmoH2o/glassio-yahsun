p = 'app/src/main/java/com/glasslauncher/tv/ui/HomeScreen.kt'
s = open(p).read()


def rep(o, n):
    global s
    assert s.count(o) == 1, o[:90]
    s = s.replace(o, n)


# ---- backdrop: use pre-baked pictures, and publish which picture is on screen
rep("""        val dm = ctx.resources.displayMetrics
        val baked = withContext(Dispatchers.Default) { bakeBackdrop(fullBmp!!, dm.widthPixels, dm.heightPixels) }
        shownBackdrop = baked
""", """        val dm = ctx.resources.displayMetrics
        val src = fullBmp!!
        val baked = BakeCache.get(u, src) ?: withContext(Dispatchers.Default) { bakeBackdrop(src, dm.widthPixels, dm.heightPixels) }.also { BakeCache.put(u, src, it) }
        shownBackdrop = baked
        shownUrl.value = u
""")
rep("""    var shownBackdrop by remember { mutableStateOf<ImageBitmap?>(null) }""",
    """    var shownBackdrop by remember { mutableStateOf<ImageBitmap?>(null) }
    // Which picture is on screen: the title text waits for it, so text and picture change together.
    val shownUrl = remember { mutableStateOf<String?>(null) }""")
rep("""                HeroSection(app, s, hidden = expanded || folderOpen, paused = screen != Screen.Home, metrics = metrics,""",
    """                HeroSection(app, s, hidden = expanded || folderOpen, paused = screen != Screen.Home, metrics = metrics, shownUrl = shownUrl,""")
rep("""    app: GlassApp, s: Float, hidden: Boolean, paused: Boolean, metrics: DockMetrics,
    heroFocus: FocusRequester, firstTile: FocusRequester, onBackdrop: (String?) -> Unit,
) {""", """    app: GlassApp, s: Float, hidden: Boolean, paused: Boolean, metrics: DockMetrics,
    heroFocus: FocusRequester, firstTile: FocusRequester, onBackdrop: (String?) -> Unit,
    shownUrl: androidx.compose.runtime.State<String?> = androidx.compose.runtime.mutableStateOf(null),
) {""")
# text follows the picture: the shown title switches when its picture is on screen (max 1.5 s)
rep("""        val shown = item
        if (shown != null) {
            HeroInfo(""", """        val shown = textItem
        if (shown != null) {
            HeroInfo(""")
rep("""    val shownBg = gallery.getOrNull(shot) ?: item?.background
    LaunchedEffect(shownBg, hidden) { onBackdrop(shownBg?.takeIf { it.isNotBlank() }) }""",
    """    val shownBg = gallery.getOrNull(shot) ?: item?.background
    LaunchedEffect(shownBg, hidden) { onBackdrop(shownBg?.takeIf { it.isNotBlank() }) }
    // The text of a slide appears together with its picture (browsing with the remote: at once).
    var textItem by remember { mutableStateOf<HeroItem?>(null) }
    LaunchedEffect(item?.key, item?.logo) {
        val it0 = item
        if (it0 == null || textItem == null || app.browsing() || it0.background.isBlank()) { textItem = it0; return@LaunchedEffect }
        if (textItem?.key == it0.key) { textItem = it0; return@LaunchedEffect }
        kotlinx.coroutines.withTimeoutOrNull(1500) {
            androidx.compose.runtime.snapshotFlow { shownUrl.value }.first { u -> u == it0.background || u in it0.gallery }
        }
        textItem = it0
    }""")
# prepare the picture of the next and previous slide completely (decoded and baked) ahead of time
rep("""            if (n.background.isNotBlank()) {
                loader.enqueue(imgReq(ctx, sized(n.background, "w1280")))
                if (kotlin.math.abs(k) <= 1 && settings.imageQuality != "high") loader.enqueue(imgReq(ctx, n.background))
                launch { GlassCache.get(ctx, n.background, settings.blurRadius) }
            }""", """            if (n.background.isNotBlank()) {
                loader.enqueue(imgReq(ctx, sized(n.background, "w1280")))
                if (kotlin.math.abs(k) <= 1) launch {
                    val u = n.background
                    val r = loader.execute(imgReq(ctx, if (settings.imageQuality == "high") sized(u, "w1280") else u))
                    val b = (r as? SuccessResult)?.image?.toBitmap()?.asImageBitmap() ?: return@launch
                    if (BakeCache.get(u, b) == null) {
                        val dm = ctx.resources.displayMetrics
                        val baked = withContext(Dispatchers.Default) { bakeBackdrop(b, dm.widthPixels, dm.heightPixels) }
                        BakeCache.put(u, b, baked)
                    }
                }
                launch { GlassCache.get(ctx, n.background, settings.blurRadius) }
            }""")
rep("""/**
 * The backdrop scaled to the screen (centre crop) with the two scrims painted in, as one bitmap.""",
    """/** The last few baked backdrops (~8 MB each), keyed by picture and source bitmap. */
private object BakeCache {
    private val map = object : LinkedHashMap<String, Pair<Int, ImageBitmap>>(8, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Pair<Int, ImageBitmap>>?) = size > 4
    }
    @Synchronized fun get(url: String, src: ImageBitmap): ImageBitmap? =
        map[url]?.takeIf { it.first == src.width * 10000 + src.height }?.second
    @Synchronized fun put(url: String, src: ImageBitmap, baked: ImageBitmap) { map[url] = (src.width * 10000 + src.height) to baked }
}

/**
 * The backdrop scaled to the screen (centre crop) with the two scrims painted in, as one bitmap.""")
if 'import kotlinx.coroutines.flow.first' not in s:
    s = s.replace('import kotlinx.coroutines.withContext\n', 'import kotlinx.coroutines.withContext\nimport kotlinx.coroutines.flow.first\n', 1)
open(p, 'w').write(s)
print('ok')
