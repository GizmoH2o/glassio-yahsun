package com.glasslauncher.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.glasslauncher.tv.GlassApp
import com.glasslauncher.tv.data.AppEntry
import com.glasslauncher.tv.data.Airing
import com.glasslauncher.tv.data.HeroItem
import com.glasslauncher.tv.data.HeroPicks
import com.glasslauncher.tv.data.StreamLinks
import com.glasslauncher.tv.data.LiveCards
import com.glasslauncher.tv.data.NuvioLaunch
import com.glasslauncher.tv.data.Collections
import com.glasslauncher.tv.data.ForYou
import com.glasslauncher.tv.data.Moods
import com.glasslauncher.tv.data.UpNext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/** The page's left edge, as the big picture's text (dp at 960 wide). */
internal const val EDGE = 48f
private const val ROW_GAP = 14f
internal const val AIRING_W = 104f
private const val WATCH_W = 168f
private const val POSTER_W = 100f
/** A poster rested on opens up to this much wider (its picture on top, its details under it). */
private const val OPEN_W = 1.6f
/** The accent: Settings › Home › Accent colour (Netflix's red unless another is picked). */
internal val RED: Color @Composable @androidx.compose.runtime.ReadOnlyComposable get() = LocalAccent.current
private val GREEN = Color(0xFF46D369)
private val IMDB = Color(0xFFF5C518)
internal val CARD = Color(0xFF1C1C1F)
/** The open poster card's lighter grey (Settings › Home › Card style), as on the streaming apps. */
private val CARD_LIGHT = Color(0xFF2A2A2D)

/** Which part of the page holds the focus: the big picture, or a row (in page order). */
private enum class Part { Hero, Airing, Watch, ForYou, Movies, Series, Trending, RecFilms, RecSeries, Col1, Col2, Col3, Mine1, Mine2, Mine3, Mine4, Mine5, Mine6, Because, Quick, Apps, Widgets }

/**
 * The home screen: the big picture (a title's backdrop, logo, details, Play and + Library) over the whole page, then
 * rows — Airing this week, Continue watching, your library's Movies and Series — and your apps last. The page moves
 * up just enough to show the row you're in; the big picture follows the title selected in a row (Settings → Home).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HomePage(
    app: GlassApp, s: Float, screenH: Float, visible: Boolean, paused: Boolean,
    heroFocus: FocusRequester, appsFocus: FocusRequester, folderFocus: FocusRequester,
    tileFocus: HashMap<String, FocusRequester>, movingPkg: String?, onMoveKey: (KeyEvent) -> Boolean,
    shownUrl: androidx.compose.runtime.State<String?>, tint: androidx.compose.runtime.State<Int?>, onBackdrop: (String?) -> Unit,
    onHeroFocus: (Boolean) -> Unit, onDialog: (DialogState) -> Unit,
    onAppMenu: (AppEntry) -> Unit, onFolder: () -> Unit, onFolderMenu: () -> Unit, onLibrary: () -> Unit,
    /** The trailer to play behind the big picture (null: none), and whether the last one has ended. */
    onTrailer: (String?) -> Unit, trailerOver: Boolean,
    /** How far down the page is, 0 (the top) to 1: the top bar fades as it goes. */
    down: androidx.compose.runtime.MutableFloatState,
    /** How far up the page has moved, px (the page's picture goes up with it when it scrolls away). */
    pageOffset: androidx.compose.runtime.MutableFloatState,
    /** The tab bar's Home tab, where Up from the big picture goes (none in Kids mode). */
    tabFocus: FocusRequester,
    /** A title's page. */
    onTitle: (HeroItem) -> Unit,
    /** Goes up by one when Home's in front again after a title's page: the focus back where it was. */
    restoreFocus: Int,
    /** The Today page (Your day's card). */
    onToday: () -> Unit = {},
) {
    val settings = app.settings
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val catalogTitles by app.catalogs.items.collectAsState()
    // Recommendations (Settings › Home): for whoever has no Nuvio or Stremio account signed in, or always.
    val recRows by app.recs.rows.collectAsState()
    val recsOn = app.recs.on(app.library.nuvio.connected || app.library.stremio.connected)
    // Settings › Home › Big picture shows: Recommendations only reads them even when their rows are off.
    val recsOnly = settings.heroSource == "recs"
    LaunchedEffect(recsOn, recsOnly) { if ((recsOn || recsOnly) && recRows.trending.isEmpty()) withContext(Dispatchers.IO) { runCatching { app.recs.refresh() } } }
    // The big picture: the catalogs' titles; none, what's trending; both when Always is chosen; or the recommendations only.
    val catalogItems = remember(catalogTitles, recRows, recsOn, settings.recsMode, recsOnly) {
        HeroPicks.sources(catalogTitles, recRows.trending, recRows.films, recRows.series, recsOn, settings.recsMode == "always", recsOnly)
    }
    val enriched by app.catalogs.enrichedItems.collectAsState()
    val status by app.catalogs.status.collectAsState()
    val cwItems by app.cw.items.collectAsState()
    val liveCards by LiveCards.items.collectAsState()
    val airing by app.shelf.airing.collectAsState()
    val movies by app.shelf.movies.collectAsState()
    val series by app.shelf.series.collectAsState()
    val newEps by app.shelf.newEpisodes.collectAsState()
    @Suppress("UNUSED_VARIABLE") val infoRev = app.shelf.info.collectAsState().value.size
    val savedList by app.library.saved.collectAsState()
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val lifeState by lifecycle.currentStateFlow.collectAsState()
    val resumed = lifeState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)

    // ── what the rows hold (Kids mode: no Continue watching or Airing, the library's family titles, the chosen apps)
    val kids = settings.kidsMode
    val watchItems = remember(liveCards, cwItems, enriched, newEps, settings.continueMode, settings.artChoice, settings.cwNewFirst, kids) {
        if (kids) emptyList()
        else if (settings.continueMode == "off") liveCards
        else {
            val cw = cwItems.map { c -> (enriched[c.key] ?: c).let { withArtwork(it, settings.artChoice, settings.artPick(it.key)) }.copy(progress = c.progress, sub = c.sub, source = c.source, episodeTitle = c.episodeTitle, watchedAt = c.watchedAt, upNext = c.upNext) }
            // One row: what's on now, then new episodes, then everything as you watched it.
            UpNext.order(liveCards, cw, newEps.filterValues { it > 0 }.keys, settings.cwNewFirst)
        }
    }
    val kidsMovies = remember(movies, kids, infoRev) { if (kids) movies.filter { HeroPicks.isKids(app.shelf.filled(it)) } else movies }
    val kidsSeries = remember(series, kids, infoRev) { if (kids) series.filter { HeroPicks.isKids(app.shelf.filled(it)) } else series }
    val allAppsNow by app.apps.apps.collectAsState()
    val anyApps = remember(allAppsNow, kids, settings.kidsApps) {
        !kids || settings.kidsApps.toSet().let { ok -> app.apps.ordered(includeHidden = true).any { it.pkg in ok } }
    }
    val showAiring = !kids && settings.homeAiring && airing.isNotEmpty()
    val showMovies = settings.homeMovies && kidsMovies.isNotEmpty()
    val showSeries = settings.homeSeries && kidsSeries.isNotEmpty()
    // ── the widgets row (Settings › Home › Widgets): the weather's next days, the calendars' next events and Home
    // Assistant readings, read again while Home is on screen (the calendars every 10 minutes, the readings every half
    // minute); OK on a card reads them now. Kids mode: the weather only.
    var widgetBump by remember { mutableIntStateOf(0) }
    val weatherNow by app.weather.now.collectAsState()
    val weatherDays by app.weather.days.collectAsState()
    val calLinks = if (kids) emptyList() else settings.calLinks
    val agenda by produceState<com.glasslauncher.tv.data.HomeWidgets.Agenda?>(null, calLinks, visible, widgetBump) {
        if (calLinks.isEmpty()) { value = null; return@produceState }
        while (visible) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.glasslauncher.tv.data.HomeWidgets.agenda(app.http, calLinks) }
            delay(10 * 60_000L)
        }
    }
    val haIds = if (kids) emptyList() else settings.haEntities
    val readings by produceState(emptyList<com.glasslauncher.tv.data.HomeWidgets.Reading>(), haIds, settings.haBase, settings.haToken, visible, widgetBump) {
        if (haIds.isEmpty()) { value = emptyList(); return@produceState }
        while (visible) {
            val got = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.glasslauncher.tv.data.HomeWidgets.readings(settings.haBase, settings.haToken, haIds) }
            // None read (Home Assistant restarting, the Wi-Fi gone a moment): the last ones stay, the cards don't vanish.
            if (got.isNotEmpty() || value.none { it.id in haIds }) value = got
            delay(30_000L)
        }
    }
    val widgetNow by produceState(java.time.Instant.now()) { while (true) { delay(30_000L); value = java.time.Instant.now() } }
    // Your day (Settings › Home › Today page): at the glance row's front in the morning, what Today has.
    val todayCard = settings.todayPage && !settings.kidsMode && visible &&
        com.glasslauncher.tv.live.LiveToday.morning(widgetNow.toEpochMilli(), java.util.TimeZone.getDefault())
    val todayCounts by produceState<List<String>?>(null, todayCard, widgetBump) {
        while (todayCard) {
            val t = withContext(Dispatchers.IO) { runCatching { com.glasslauncher.tv.live.LiveHome.today(app) }.getOrNull() }
            val eps = app.cw.items.value.count { it.upNext }
            value = com.glasslauncher.tv.live.LiveToday.counts(t?.matches?.size ?: 0, (t?.shows?.size ?: 0) + eps, t?.recordings?.size ?: 0, t?.set?.size ?: 0)
            delay(5 * 60_000L)
        }
    }
    val widgetKeys = buildList {
        if (todayCard) add("today")
        if (settings.widgetWeather && weatherNow != null && weatherDays.isNotEmpty()) add("weather")
        if (calLinks.isNotEmpty()) add("calendar")
        readings.forEach { add("ha:" + it.id) }
    }
    val showWidgets = settings.widgetsRow && widgetKeys.isNotEmpty()
    // The rows of recommendations (family titles only in Kids mode).
    fun forKids(l: List<HeroItem>) = if (kids) l.filter { HeroPicks.isKids(it) } else l
    val recTrending = if (recsOn) forKids(recRows.trending) else emptyList()
    val recFilms = if (recsOn) forKids(recRows.films) else emptyList()
    val recSeries = if (recsOn) forKids(recRows.series) else emptyList()
    var collections by remember { mutableStateOf<List<Pair<String, List<HeroItem>>>>(emptyList()) }
    LaunchedEffect(settings.homeCollections) {
        collections = if (settings.homeCollections) withContext(Dispatchers.IO) { runCatching { app.recs.collections() }.getOrDefault(emptyList()) } else emptyList()
    }
    val colParts = listOf(Part.Col1, Part.Col2, Part.Col3)
    // Your own rows, and the automatic ones: like the title you watched last, and what's short in your library.
    val mineList = if (settings.homeMine) Collections.parse(settings.myCollections) else emptyList()
    val auto = settings.homeAuto && !kids
    val lastSeen = cwItems.firstOrNull { it.id.startsWith("tt") }
    val becauseList by androidx.compose.runtime.produceState(emptyList<HeroItem>(), auto, lastSeen?.key) {
        val seen = lastSeen
        value = if (!auto || seen == null) emptyList() else withContext(Dispatchers.IO) { runCatching { app.recs.similar(seen) }.getOrDefault(emptyList()) }
    }
    // For You: from the titles already here, worked out on the TV.
    val forYouOn = settings.homeForYou && !kids
    val forYouList = remember(forYouOn, cwItems, catalogItems, recRows, movies, series, settings.heroHidden, infoRev) {
        if (!forYouOn) emptyList() else {
            val mine = (movies + series).map { app.shelf.filled(it) }
            val pool = catalogItems + recRows.trending + recRows.films + recRows.series
            val hidden = settings.heroHidden.toSet()
            val taste = ForYou.taste(cwItems, mine, pool.filter { it.key in hidden }.distinctBy { it.key }, System.currentTimeMillis())
            val skip = hidden + cwItems.map { it.key } + mine.map { it.key } + app.cw.finishedKeys()
            ForYou.pick(pool, taste, skip).takeIf { it.size >= 4 }.orEmpty()
        }
    }
    val quickList = remember(movies, series, auto, infoRev) {
        if (!auto) emptyList() else Moods.pick((movies + series).map { app.shelf.filled(it) }, null, 30, emptySet(), false).takeIf { it.size >= 4 }.orEmpty()
    }
    val parts = buildList {
        if (showAiring) add(Part.Airing)
        if (watchItems.isNotEmpty()) add(Part.Watch)
        if (forYouList.isNotEmpty()) add(Part.ForYou)
        if (showMovies) add(Part.Movies)
        if (showSeries) add(Part.Series)
        if (recTrending.isNotEmpty()) add(Part.Trending)
        if (recFilms.isNotEmpty()) add(Part.RecFilms)
        if (recSeries.isNotEmpty()) add(Part.RecSeries)
        collections.indices.forEach { if (it < colParts.size) add(colParts[it]) }
        for (c in mineList) if (forKids(c.titles).isNotEmpty()) add(Part.valueOf(Collections.row(c.slot)))
        if (becauseList.isNotEmpty()) add(Part.Because)
        if (quickList.isNotEmpty()) add(Part.Quick)
        if (anyApps) add(Part.Apps)
        if (showWidgets) add(Part.Widgets)
    }.let { present ->
        // As Settings › Home › Home rows arranges them: the big picture first, the rows in your order, without those off.
        val off = settings.homeHidden.split(',').toSet()
        listOf(Part.Hero) + com.glasslauncher.tv.data.HomeRows.order(settings.homeOrder)
            .mapNotNull { n -> present.firstOrNull { it.name == n } }.filter { it.name !in off }
    }
    val entry = remember { mutableStateMapOf<Part, FocusRequester>() }
    fun req(p: Part) = if (p == Part.Hero) heroFocus else if (p == Part.Apps) appsFocus else entry.getOrPut(p) { FocusRequester() }
    fun above(p: Part) = req(parts.getOrNull(parts.indexOf(p) - 1) ?: Part.Hero)
    fun below(p: Part) = parts.getOrNull(parts.indexOf(p) + 1)?.let { req(it) } ?: FocusRequester.Cancel
    var inPart by remember { mutableStateOf(Part.Hero) }
    // Back from a title's page: where it was opened from.
    var titleFromPart by remember { mutableStateOf(Part.Hero) }
    val heroMore = remember { FocusRequester() }
    var fromMore by remember { mutableStateOf(false) }
    val toTitle: (HeroItem) -> Unit = { item -> titleFromPart = inPart; onTitle(item) }
    /** OK on a card: its page, or straight to playing (Settings › Home › OK on a card); with no player app, always its page. */
    val openCard: (HeroItem) -> Unit = { item ->
        if (settings.cardOk == "play" && NuvioLaunch.playable(ctx, settings)) NuvioLaunch.play(ctx, settings, app.http, item) else toTitle(item)
    }
    LaunchedEffect(restoreFocus) { if (restoreFocus > 0) { delay(60); inPart = titleFromPart; runCatching { (if (fromMore) heroMore else req(titleFromPart)).requestFocus() }; fromMore = false } }
    // The row with the focus went (its last card taken out, a row turned off): the focus to the row below, else the
    // big picture, not lost.
    val partsNow = parts
    LaunchedEffect(partsNow) {
        if (inPart !in partsNow) {
            delay(50)
            val next = Part.entries.drop(inPart.ordinal + 1).firstOrNull { it in partsNow } ?: Part.Hero
            inPart = next
            runCatching { req(next).requestFocus() }
        }
    }

    // ── the big picture: the catalogs' titles in turn (HeroPicks: not the hidden ones, the season's first, Because you
    // watched among them, family ones only in Kids mode), or the title selected in a row. Picked from the catalogs' own
    // genres, not the fuller ones found later: those would reorder the slides while you look at them.
    var index by remember { mutableIntStateOf(0) }
    val month = settings.seasonMonth.takeIf { it in 1..12 } ?: (java.util.Calendar.getInstance().get(java.util.Calendar.MONTH) + 1)
    val picks = remember(catalogItems, settings.heroHidden, kids, cwItems, settings.heroBecause, settings.heroSeasonal, month) {
        HeroPicks.pick(catalogItems, emptyMap(), settings.heroHidden.toSet(), kids, cwItems, settings.heroBecause, settings.heroSeasonal, month)
    }
    val slides = picks.slides
    val slide = slides.getOrNull(index.mod(slides.size.coerceAtLeast(1)))
        ?.let { r -> (enriched[r.key] ?: r) }?.let { withArtwork(it, settings.artChoice, settings.artPick(it.key)) }
    var selected by remember { mutableStateOf<HeroItem?>(null) }
    // The selected title takes over the picture once you stop on it (not on every step along a row).
    var picked by remember { mutableStateOf<HeroItem?>(null) }
    LaunchedEffect(selected?.key) {
        val sel = selected
        if (sel == null) { picked = null; return@LaunchedEffect }
        delay(400)
        app.catalogs.enrich(sel)
        picked = sel
    }
    val pickedFull = picked?.let { p ->
        val e = enriched[p.key]
        val base = app.shelf.filled(p)
        if (e == null) base else base.copy(background = e.background.ifBlank { base.background }, logo = e.logo.ifBlank { base.logo },
            overview = e.overview.ifBlank { base.overview }, rating = base.rating.ifBlank { e.rating }, genres = base.genres.ifEmpty { e.genres })
    }?.let { withArtwork(it, settings.artChoice, settings.artPick(it.key)) }
    val hero = if (settings.homeFollow && inPart != Part.Hero && inPart != Part.Apps && pickedFull != null) pickedFull else slide
    LaunchedEffect(hero?.background) { onBackdrop(hero?.background?.takeIf { it.isNotBlank() }) }
    // Its text changes with its picture (or after a moment, if the picture is slow). In steps (Settings › Home ›
    // Changing title): the words fade out while the next picture comes, then the new ones fade in over it.
    var textItem by remember { mutableStateOf<HeroItem?>(null) }
    val textA = remember { Animatable(1f) }
    val steps = settings.heroChange != "fade" && !LocalReduceMotion.current
    val quick = steps && settings.heroChange == "quick"
    LaunchedEffect(hero?.key, hero?.logo, hero?.overview) {
        val h = hero
        if (h == null || textItem == null || h.background.isBlank() || textItem?.key == h.key) { textItem = h; textA.snapTo(1f); return@LaunchedEffect }
        if (quick) {
            // As on the streaming apps (measured from a recording): as the picture starts its crossfade (850 ms,
            // HomeScreen), the words fade out (200 ms, FastOutSlowIn); 420 ms into it the new words fade in (370 ms,
            // ease-out). Neither moves. The old words stay while the new picture is still on its way (never an empty
            // picture).
            kotlinx.coroutines.withTimeoutOrNull(1500) { androidx.compose.runtime.snapshotFlow { shownUrl.value }.first { it == h.background } }
            // At their own speed (HomeMotion), whatever the TV's animation speed, unless Follow the TV's is on.
            val shownAt = android.os.SystemClock.uptimeMillis()
            atOwnSpeed(settings.homeTvSpeed) { textA.animateTo(0f, tween(200, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
            textItem = h
            delay((420 - (android.os.SystemClock.uptimeMillis() - shownAt)).coerceAtLeast(0))
            atOwnSpeed(settings.homeTvSpeed) { textA.animateTo(1f, tween(370, easing = CubicBezierEasing(0f, 0f, 0.58f, 1f))) }
            return@LaunchedEffect
        }
        if (!steps) {
            textA.snapTo(1f)
            kotlinx.coroutines.withTimeoutOrNull(1200) { androidx.compose.runtime.snapshotFlow { shownUrl.value }.first { it == h.background } }
            textItem = h
            return@LaunchedEffect
        }
        kotlinx.coroutines.coroutineScope {
            launch { textA.animateTo(0f, tween(300, easing = CubicBezierEasing(0.4f, 0f, 1f, 1f))) }
            kotlinx.coroutines.withTimeoutOrNull(1200) { androidx.compose.runtime.snapshotFlow { shownUrl.value }.first { it == h.background } }
        }
        textItem = h
        delay((settings.transitionMs * 0.45f).toLong().coerceIn(120, 450))
        textA.animateTo(1f, tween(420, easing = CubicBezierEasing(0f, 0f, 0.2f, 1f)))
    }
    // The slides around this one: details and pictures ready before you get there.
    LaunchedEffect(index, slides.size, settings.tmdbToken) {
        if (slides.isEmpty()) return@LaunchedEffect
        for (k in listOf(0, 1, -1, 2)) app.catalogs.enrich(slides[(index + k).mod(slides.size)])
        delay(300)
        val loader = coil3.SingletonImageLoader.get(ctx)
        for (k in listOf(1, -1)) {
            val n = slides[(index + k).mod(slides.size)].let { enriched[it.key] ?: it }
            if (n.background.isNotBlank()) loader.enqueue(ImageRequest.Builder(ctx).data(n.background).size(1920, 1080).build())
        }
    }
    // Its trailer (Settings › Home › Trailers): after a few seconds on the big picture with a title, behind it; any
    // move (another title, a row, another screen) stops it.
    // (The words' title is followed off the page's composition: read here, every change of title recomposed the page.)
    val trailerReady = TrailerGuard.allowed(ctx, settings.trailerMode) && inPart == Part.Hero && visible && !paused && resumed
    var trailerOn by remember { mutableStateOf(false) }
    // An opened poster card's trailer (Settings › Home › Trailer in the open card), while Home is in front: its own
    // choice, not the big picture's Trailers; still never on a TV where trailers keep failing (unless Trailers is On).
    val cardLook = CardLook(settings.cardOpenMs, settings.cardOpen != "staggered", settings.slowZoom in setOf("cards", "both"), settings.cardStyle != "dark", settings.homeTvSpeed)
    val cardTrailer = if (settings.cardTrailerSec > 0 && TrailerGuard.allowed(ctx, if (settings.trailerMode == "on") "on" else "auto") && visible && !paused && resumed) settings.cardTrailerSec else 0
    LaunchedEffect(trailerReady) {
        androidx.compose.runtime.snapshotFlow { textItem?.takeIf { trailerReady } }.distinctUntilChangedBy { it?.key }.collectLatest { restingOn ->
            onTrailer(null); trailerOn = false
            val t = restingOn ?: return@collectLatest
            delay(4_000)
            val id = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { trailerOf(app, t) }
            if (id == null) return@collectLatest
            trailerOn = true
            onTrailer(id)
        }
    }
    // On its own: the next title every few seconds while the big picture is selected (or nothing is); not while its
    // trailer plays (after it, on).
    LaunchedEffect(index, slides.size, settings.heroAutoplay, settings.heroIntervalSec, visible, paused, resumed, inPart, trailerOn, trailerOver) {
        if (settings.heroAutoplay && visible && !paused && resumed && slides.size > 1 && inPart == Part.Hero && (!trailerOn || trailerOver)) {
            delay(settings.heroIntervalSec * 1000L)
            SlideDir.dir = 1
            index++
        }
    }
    // Home pressed on the home screen: the first title, the big picture selected.
    val homeRev = app.homeRev.intValue
    var seenHome by remember { mutableIntStateOf(homeRev) }
    LaunchedEffect(homeRev) {
        if (homeRev != seenHome) {
            seenHome = homeRev
            index = 0
            delay(120); runCatching { heroFocus.requestFocus() }
        }
    }

    // ── where the page sits: up just enough for the row with the focus, the big picture's text fading as it goes
    val tops = remember { mutableStateMapOf<Part, Pair<Float, Float>>() }
    /** The apps grid's rows, top and bottom on the page; and the one with the focus. */
    val appRows = remember { mutableStateMapOf<Int, Pair<Float, Float>>() }
    var appRow by remember { mutableIntStateOf(0) }
    val offset = remember { Animatable(0f) }
    val reduceMotion = LocalReduceMotion.current
    val scrollAway = settings.heroScroll == "away"
    val screenPx = screenH * density
    val spot = if (inPart == Part.Apps && appRow > 0) appRows[appRow] else tops[inPart]?.let { t ->
        // The apps' first row: from the "Apps" title to that row's bottom.
        if (inPart == Part.Apps) appRows[0]?.let { r -> t.first to r.second } ?: t else t
    }
    LaunchedEffect(inPart, spot, screenPx, scrollAway) {
        val (top, bottom) = spot ?: return@LaunchedEffect
        // The row as low on the screen as it fits, so what's above it shows (the big picture, back at the first row);
        // a row taller than the screen from its top.
        val margin = 26 * s * density
        // Scrolls away (Settings › Home › Going down the page): past the first row, the row comes to the top and the
        // big picture goes up and off with the page, as on the streaming apps.
        val toTop = scrollAway && inPart != Part.Hero && inPart != parts.getOrNull(1)
        val target = if (inPart == Part.Hero) 0f else if (toTop) (top - 70 * s * density).coerceAtLeast(0f)
            else minOf(bottom - screenPx + margin, top - 70 * s * density).coerceAtLeast(0f)
        if (reduceMotion) offset.snapTo(target) else offset.animateTo(target, tween(420, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)))
    }
    val heroH = screenH * 0.66f
    LaunchedEffect(Unit) { androidx.compose.runtime.snapshotFlow { offset.value }.collect { down.floatValue = (it / (40 * s * density)).coerceIn(0f, 1f); pageOffset.floatValue = it } }

    // Back: from a row to the first row, from there to the big picture (then, as before, the screensaver if set).
    val firstRow = parts.getOrNull(1)
    BackHandler(enabled = visible && movingPkg == null && inPart != Part.Hero && inPart != firstRow) { runCatching { firstRow?.let { req(it).requestFocus() } } }
    BackHandler(enabled = visible && movingPkg == null && inPart != Part.Hero && inPart == firstRow) { runCatching { heroFocus.requestFocus() } }

    val defaultSpec = LocalBringIntoViewSpec.current
    Box(Modifier.fillMaxSize().clipToBounds()) {
        // Further down the page the picture darkens, so the rows read over it.
        // In the title's colour, when it has one. Followed off the page's composition and read only while drawing: the
        // colour changes as each new picture comes in, and read here it recomposed the whole page at that moment
        // (a TV stalled up to a quarter of a second, mid-crossfade).
        val pageColor = remember { androidx.compose.animation.Animatable(tint.value?.let { Color(it) } ?: Color(0xFF0B0B0D)) }
        LaunchedEffect(reduceMotion) {
            androidx.compose.runtime.snapshotFlow { tint.value }.collectLatest { t ->
                val c = t?.let { Color(it) } ?: Color(0xFF0B0B0D)
                if (reduceMotion) pageColor.snapTo(c) else pageColor.animateTo(c, tween(600))
            }
        }
        if (scrollAway) {
            // The picture goes up with the page (HomeScreen moves it): under it, the page's own dark colour.
            // Going down, the picture's lower part (never quite dark) gives way to the page's colour, so the rows
            // read on a plain dark page, as on the streaming apps.
            Box(Modifier.fillMaxSize().drawBehind {
                val f = (offset.value / (0.3f * size.height)).coerceIn(0f, 1f)
                if (f <= 0f) return@drawBehind
                val edge = size.height * (1f - 0.25f * f) - offset.value
                val fadeH = size.height * 0.3f * f
                val c = pageColor.value
                if (edge > 0f) drawRect(Brush.verticalGradient(0f to c.copy(alpha = 0f), 1f to c, startY = edge - fadeH, endY = edge),
                    topLeft = Offset(0f, (edge - fadeH).coerceAtLeast(0f)), size = Size(size.width, edge - (edge - fadeH).coerceAtLeast(0f)))
                val y = edge.coerceAtLeast(0f)
                if (y < size.height) drawRect(c, topLeft = Offset(0f, y), size = Size(size.width, size.height - y))
            })
        } else Box(Modifier.fillMaxSize().graphicsLayer { alpha = (offset.value / (heroH * density * 0.6f)).coerceIn(0f, 0.72f) }.drawBehind { drawRect(pageColor.value) })
        // Nothing scrolls by itself: the page moves (above) and each row scrolls along (its own default).
        CompositionLocalProvider(LocalBringIntoViewSpec provides NoPageScroll) {
            Column(
                Modifier.fillMaxWidth().layout { m, c ->
                    val p = m.measure(c.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                    layout(p.width, c.maxHeight) { p.placeWithLayer(0, 0) { translationY = -offset.value } }
                },
            ) {
                // ── the big picture
                Box(Modifier.fillMaxWidth().height(heroH.dp).onGloballyPositioned { tops[Part.Hero] = it.positionInParent().y to it.positionInParent().y + it.size.height }) { Isolated {
                    // Its own scope: a new title's words recompose this, not the whole page under it.
                    val shown = textItem
                    // Scrolling away, the words go up with the picture as they are; staying, they fade over it.
                    val fade = { if (scrollAway) 1f else (1f - offset.value / (heroH * density * 0.55f)).coerceIn(0f, 1f) }
                    if (shown != null && !app.showSetup.value) {
                        HeroText(app, shown, s, savedList.any { it.key == shown.key }, heroFocus,
                            down = parts.getOrNull(1)?.let { req(it) } ?: FocusRequester.Cancel,
                            up = if (kids) FocusRequester.Default else tabFocus,
                            onFocus = { f -> if (f) { inPart = Part.Hero; selected = null }; onHeroFocus(f) },
                            onPrev = { app.navAt = System.currentTimeMillis(); SlideDir.dir = -1; index-- },
                            onNext = { app.navAt = System.currentTimeMillis(); SlideDir.dir = 1; index++ },
                            moreFocus = heroMore,
                            onMore = { fromMore = true; toTitle(shown) },
                            // No app that plays titles on this TV: its page (its trailer, any streaming app it's on).
                            onPlay = { if (NuvioLaunch.playable(ctx, settings)) NuvioLaunch.open(ctx, settings, app.http, shown) else toTitle(shown) },
                            onSave = {
                                val now = app.library.toggle(shown)
                                toast(ctx, if (now) "Saved to Library · ${shown.name}" else "Removed from Library · ${shown.name}")
                            },
                            // Hold OK: Not interested (it leaves the big picture), or another picture for it.
                            onArt = hold@{
                                if (kids) return@hold
                                val nextPicture = {
                                    val base = enriched[shown.key] ?: shown
                                    val options = artOptions(base)
                                    if (options.size < 2) toast(ctx, "No other pictures for ${shown.name} (add a TMDB token for more)")
                                    else {
                                        val cur = options.indexOf(shown.background).coerceAtLeast(0)
                                        settings.setArtPick(shown.key, options[(cur + 1) % options.size])
                                        toast(ctx, "Picture ${(cur + 1) % options.size + 1} of ${options.size} · ${shown.name}")
                                    }
                                }
                                onDialog(MenuDialog(shown.name, listOf(
                                    "Not interested" to {
                                        settings.heroHidden = (settings.heroHidden + shown.key).distinct()
                                        toast(ctx, "${shown.name} won't show here again (Settings › Home to bring it back)")
                                    },
                                    "Another picture" to nextPicture,
                                )))
                            },
                            reason = if (shown.key == slide?.key) picks.reasons[shown.key] else null,
                            steps = steps,
                            modifier = Modifier.align(Alignment.BottomStart).graphicsLayer { alpha = fade() * textA.value })
                        if (slides.size > 1 && hero == slide) Dashes(slides.size, index.mod(slides.size), s,
                            Modifier.align(Alignment.BottomEnd).padding(end = (EDGE * s).dp, bottom = (12 * s).dp).graphicsLayer { alpha = fade() })
                    } else if (kids) {
                        // Kids mode with no family title in the catalogs: a line, never the setup steps (they lead to Settings).
                        Text("Nothing to watch here yet", color = Color.White.copy(alpha = 0.85f), fontSize = (16 * s).sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.align(Alignment.BottomStart).padding(start = (EDGE * s).dp, bottom = (20 * s).dp)
                                .focusRequester(heroFocus).focusProperties { this.down = parts.getOrNull(1)?.let { req(it) } ?: FocusRequester.Cancel }
                                .onFocusChanged { if (it.isFocused) inPart = Part.Hero; onHeroFocus(it.isFocused) }.tvPress(onKey = backKey(), onClick = {}))
                    } else {
                        EmptyHint(app, status, s, heroFocus, parts.getOrNull(1)?.let { req(it) } ?: FocusRequester.Cancel,
                            Modifier.align(Alignment.TopStart).graphicsLayer { alpha = fade() },
                            { f -> if (f) inPart = Part.Hero; onHeroFocus(f) }) { cat ->
                            app.requestSettings.intValue = cat; app.requestSettingsRev.intValue++
                        }
                    }
                } }
                @Composable fun section(part: Part, title: String, note: String = "", content: @Composable () -> Unit) {
                    Column(
                        Modifier.fillMaxWidth().padding(top = (ROW_GAP * s).dp)
                            .onGloballyPositioned { tops[part] = it.positionInParent().y to it.positionInParent().y + it.size.height }
                            .onFocusChanged { if (it.hasFocus) inPart = part },
                    ) {
                        Row(Modifier.padding(start = (EDGE * s).dp, bottom = (6 * s).dp), verticalAlignment = Alignment.Bottom) {
                            val own = com.glasslauncher.tv.data.HomeRows.name(settings.homeNames, part.name)
                            if (own.isNotEmpty()) RawText(own, color = Color.White, fontSize = (12.5f * s).sp, fontWeight = FontWeight.Bold)
                            else Text(title, color = Color.White, fontSize = (12.5f * s).sp, fontWeight = FontWeight.Bold)
                            if (note.isNotBlank()) {
                                Spacer(Modifier.width((8 * s).dp))
                                Text(note, color = Color.White.copy(alpha = 0.5f), fontSize = (7.5f * s).sp, modifier = Modifier.padding(bottom = (1.5f * s).dp))
                            }
                        }
                        CompositionLocalProvider(LocalBringIntoViewSpec provides defaultSpec) { content() }
                    }
                }
                val airingRow: @Composable () -> Unit = {
                if (showAiring) section(Part.Airing, "Airing this week", "New episodes of shows in your library") {
                    CardRow(s, req(Part.Airing), above(Part.Airing), below(Part.Airing), airing.map { it.item.key + ":" + it.episode.season + ":" + it.episode.episode }) { i, mod ->
                        val a = airing[i]
                        AiringCard(a, s, mod, onFocused = { selected = a.item }, onOpen = { openCard(a.item) },
                            onHold = { onDialog(titleMenu(app, ctx, a.item, scope, onDialog)) })
                    }
                }
                }
                val watchRow: @Composable () -> Unit = {
                if (watchItems.isNotEmpty()) section(Part.Watch, "Continue watching") {
                    CardRow(s, req(Part.Watch), above(Part.Watch), below(Part.Watch), watchItems.map { it.key }) { i, mod ->
                        val item = watchItems[i]
                        WatchCard(item, newEps[item.key] ?: 0, s, mod, onFocused = { selected = if (LiveCards.isLive(item)) null else item },
                            onOpen = { if (LiveCards.isLive(item)) LiveCards.open(ctx, item) else openCard(item) },
                            onHold = { watchMenu(app, ctx, scope, item, onDialog) })
                    }
                }
                }
                // ── your library
                @Composable fun posters(part: Part, title: String, list: List<HeroItem>) = section(part, title) {
                    val row = remember { RowOpen() }.takeIf { settings.cardKeepOpen }
                    CardRow(s, req(part), above(part), below(part), list.map { it.key } + "__all__") { i, mod ->
                        if (i == list.size) SeeAllCard(s, mod, onLibrary)
                        else {
                            val item = list[i]
                            PosterCard(app.shelf.filled(item), s, settings.homeExpand, mod, trailerSec = cardTrailer, app = app, look = cardLook, row = row,
                                beside = { listOfNotNull(list.getOrNull(i - 1), list.getOrNull(i + 1)).map { app.shelf.filled(it) } }, onFocused = { selected = item },
                                onOpen = { openCard(item) },
                                onHold = { if (!kids) onDialog(titleMenu(app, ctx, item, scope, onDialog)) })
                        }
                    }
                }
                // ── your own rows
                @Composable fun mine(part: Part, c: com.glasslauncher.tv.data.MyCollection) = section(part, c.name) {
                    val list = forKids(c.titles)
                    val row = remember { RowOpen() }.takeIf { settings.cardKeepOpen }
                    CardRow(s, req(part), above(part), below(part), list.map { it.key }) { i, mod ->
                        val item = list[i]
                        PosterCard(app.shelf.filled(item), s, settings.homeExpand, mod, trailerSec = cardTrailer, app = app, look = cardLook, row = row,
                                beside = { listOfNotNull(list.getOrNull(i - 1), list.getOrNull(i + 1)).map { app.shelf.filled(it) } }, onFocused = { selected = item }, onOpen = { openCard(item) },
                            onHold = { if (!kids) onDialog(titleMenu(app, ctx, item, scope, onDialog, c)) })
                    }
                }
                // ── recommendations
                @Composable fun recs(part: Part, title: String, list: List<HeroItem>, note: String = "") = section(part, title, note) {
                    val row = remember { RowOpen() }.takeIf { settings.cardKeepOpen }
                    CardRow(s, req(part), above(part), below(part), list.map { it.key }) { i, mod ->
                        val item = list[i]
                        PosterCard(app.shelf.filled(item), s, settings.homeExpand, mod, trailerSec = cardTrailer, app = app, look = cardLook, row = row,
                                beside = { listOfNotNull(list.getOrNull(i - 1), list.getOrNull(i + 1)).map { app.shelf.filled(it) } }, onFocused = { selected = item }, onOpen = { openCard(item) },
                            onHold = { if (!kids) onDialog(titleMenu(app, ctx, item, scope, onDialog)) })
                    }
                }
                val appsRow: @Composable () -> Unit = {
                if (anyApps) section(Part.Apps, "Apps") {
                    AppsGrid(app, s, settings.appsPerRow, appsFocus, above(Part.Apps), below(Part.Apps), folderFocus, tileFocus, movingPkg, onMoveKey, onAppMenu, onFolder, onFolderMenu,
                        onRow = { r, top, bottom -> appRows[r] = (top + offset.value) to (bottom + offset.value) }, onFocusRow = { appRow = it })
                }
                }
                val widgetsRow: @Composable () -> Unit = {
                if (showWidgets) section(Part.Widgets, "At a glance") {
                    CardRow(s, req(Part.Widgets), above(Part.Widgets), below(Part.Widgets), widgetKeys) { i, mod ->
                        val k = widgetKeys[i]
                        val w = weatherNow
                        // A quarter larger than the other rows' cards: read from the sofa, beside the apps' big tiles.
                        val s = s * 1.25f
                        when {
                            k == "today" -> TodayWidget(todayCounts, w?.temp(settings.weatherF).orEmpty(), s, mod) { onToday() }
                            k == "weather" && w != null -> WeatherWidget(w.city, w.temp(settings.weatherF), weatherDays, settings.weatherF, s, mod) { app.weather.reload() }
                            k == "calendar" -> CalendarWidget(agenda, widgetNow, s, mod) { widgetBump++ }
                            else -> readings.firstOrNull { "ha:" + it.id == k }?.let { r ->
                                HaWidget(r, s, mod, onClick = { widgetBump++ }, onHold = {
                                    onDialog(MenuDialog(r.name, listOf("Remove from Home" to { settings.haEntities = settings.haEntities - r.id })))
                                })
                            }
                        }
                    }
                }
                }
                parts.forEach { p ->
                    androidx.compose.runtime.key(p) {
                        when (p) {
                            Part.Hero -> {}
                            Part.Airing -> airingRow()
                            Part.Watch -> watchRow()
                            Part.Movies -> posters(Part.Movies, "Movies in your library", kidsMovies)
                            Part.Series -> posters(Part.Series, "Series in your library", kidsSeries)
                            Part.Trending -> recs(Part.Trending, "Trending now", recTrending)
                            Part.RecFilms -> recs(Part.RecFilms, "Popular films", recFilms)
                            Part.RecSeries -> recs(Part.RecSeries, "Popular series", recSeries)
                            Part.Col1, Part.Col2, Part.Col3 -> collections.getOrNull(colParts.indexOf(p))?.let { (name, list) -> recs(p, name, forKids(list)) }
                            Part.Mine1, Part.Mine2, Part.Mine3, Part.Mine4, Part.Mine5, Part.Mine6 -> mineList.firstOrNull { Collections.row(it.slot) == p.name }?.let { mine(p, it) }
                            Part.Because -> recs(p, Lang.tr("Because you watched") + " " + lastSeen?.name.orEmpty(), becauseList)
                            Part.Quick -> recs(p, "Quick watches", quickList)
                            Part.ForYou -> recs(p, "For You", forYouList, "Picked on this TV from what you watch")
                            Part.Apps -> appsRow()
                            Part.Widgets -> widgetsRow()
                        }
                    }
                }
                Spacer(Modifier.height((40 * s).dp))
            }
        }
    }
}

/** Nothing scrolls the page to the focus by itself: [HomePage] moves it. */
@OptIn(ExperimentalFoundationApi::class)
private object NoPageScroll : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

/**
 * A row of cards. [entry] is on the card you left it on (the first one before that), so coming back in lands there;
 * Up and Down go straight to the rows around it ([up], [down]), to the card you left each of those on.
 */
@Composable
private fun CardRow(s: Float, entry: FocusRequester, up: FocusRequester, down: FocusRequester, titles: List<String>, card: @Composable (Int, Modifier) -> Unit) {
    // Each card its own key (a title from Nuvio and Stremio both would otherwise be there twice, and the row stops).
    val keys = remember(titles) { val seen = HashSet<String>(); titles.mapIndexed { i, k -> if (seen.add(k)) k else "$k#$i" } }
    var last by remember { mutableStateOf<String?>(null) }
    var inside by remember { mutableStateOf(false) }
    var leftAt by remember { mutableLongStateOf(0L) }
    val entryKey = keys.firstOrNull { it == last } ?: keys.firstOrNull()
    // The card with the focus went (taken out of the row): the focus to the one now in its place, not lost. (The row
    // loses the focus as the card goes, so "just now" counts too.)
    LaunchedEffect(keys) {
        val justNow = android.os.SystemClock.uptimeMillis() - leftAt < 400
        if ((inside || justNow) && last != null && last !in keys) { delay(50); runCatching { entry.requestFocus() } }
    }
    // A card new in front of the ones showing (Live TV's channel just watched, a new episode moved up): the row stays on
    // the card that was first by itself, half of another cut off at the edge. Not in the row, it shows from the start
    // (coming back in still lands on the card you left).
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(keys) {
        // After the row has been laid out with the new order (it moves to keep the old first card then).
        androidx.compose.runtime.withFrameNanos { }
        androidx.compose.runtime.withFrameNanos { }
        if (!inside && (state.firstVisibleItemIndex != 0 || state.firstVisibleItemScrollOffset != 0)) runCatching { state.scrollToItem(0) }
    }
    LazyRow(
        state = state,
        modifier = Modifier.fillMaxWidth().onFocusChanged {
            if (inside && !it.hasFocus) leftAt = android.os.SystemClock.uptimeMillis()
            inside = it.hasFocus
        }.focusGroup(),
        horizontalArrangement = Arrangement.spacedBy((9 * s).dp),
        contentPadding = PaddingValues(start = (EDGE * s).dp, end = (EDGE * s).dp, top = (6 * s).dp, bottom = (6 * s).dp),
    ) {
        itemsIndexed(keys, key = { _, k -> k }) { i, k ->
            card(i, Modifier.then(if (k == entryKey) Modifier.focusRequester(entry) else Modifier)
                .focusProperties { this.up = up; this.down = down }
                .onFocusChanged { if (it.isFocused) last = k })
        }
    }
}

// ───────────────────────────── the big picture ─────────────────────────────

@Composable
private fun HeroText(
    app: GlassApp, item: HeroItem, s: Float, saved: Boolean, heroFocus: FocusRequester, down: FocusRequester, up: FocusRequester,
    onFocus: (Boolean) -> Unit, onPrev: () -> Unit, onNext: () -> Unit, moreFocus: FocusRequester, onMore: () -> Unit, onPlay: () -> Unit, onSave: () -> Unit, onArt: () -> Unit,
    /** Why it's here ("Because you watched …"), after the kind line; null for none. */
    reason: String?,
    /** The words change in steps (the page fades them): no fade of their own here. */
    steps: Boolean = false,
    modifier: Modifier,
) {
    val settings = app.settings
    val saveFocus = remember { FocusRequester() }
    val back = backKey()
    var onPlayBtn by remember { mutableStateOf(false) }
    var onSaveBtn by remember { mutableStateOf(false) }
    var onSvc by remember { mutableStateOf(false) }
    var onMoreHeld by remember { mutableStateOf(false) }
    LaunchedEffect(onPlayBtn || onSaveBtn || onSvc || onMoreHeld) { onFocus(onPlayBtn || onSaveBtn || onSvc || onMoreHeld) }
    // High contrast: a solid panel behind the words, whatever the picture is.
    val backing = if (LocalHighContrast.current) Modifier.clip(RoundedCornerShape((Ds.R_PANEL * s).dp)).background(Color.Black.copy(alpha = 0.78f)).padding((8 * s).dp) else Modifier
    Column(modifier.padding(start = (EDGE * s).dp, bottom = (6 * s).dp).width(((if (LocalHighContrast.current) 380 else 330) * s).dp).then(backing)) {
        // In steps or Quick the page fades the words itself: only the current title's are drawn (an AnimatedContent with
        // no transition kept the old ones a frame longer, and a busy start-up left both on screen). All at once: they fade.
        val words: @Composable (HeroItem) -> Unit = { it ->
            Column {
                // A small kind line over the logo ("SERIES", "FILM"), why it's here beside it, then the logo.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (it.type.equals("movie", true)) "FILM" else "SERIES", color = RED, fontSize = (7.5f * s).sp,
                        fontWeight = FontWeight.Bold, letterSpacing = (1.5f * s).sp)
                    if (reason != null) Text("  ·  $reason", color = Color.White.copy(alpha = 0.8f), fontSize = (7.5f * s).sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height((4 * s).dp))
                Title(it, settings.titleMode, settings.titleScale * 0.9f, s)
                Spacer(Modifier.height((10 * s).dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (it.rating.isNotBlank()) { ImdbBadge(it.rating, s); Spacer(Modifier.width((7 * s).dp)) }
                    val facts = listOfNotNull(it.year.takeIf { y -> y.isNotBlank() }, it.runtime.takeIf { r -> r.isNotBlank() })
                    if (facts.isNotEmpty()) Text(facts.joinToString("  ·  "), color = Color.White.copy(alpha = 0.85f), fontSize = (9 * s).sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width((7 * s).dp))
                    Text(if (it.type.equals("movie", true)) "Movie" else "Series", color = Color.White.copy(alpha = 0.85f), fontSize = (7.5f * s).sp,
                        modifier = Modifier.border(0.8.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape((2.5f * s).dp)).padding(horizontal = (4 * s).dp, vertical = (0.5f * s).dp))
                    if (it.genres.isNotEmpty()) {
                        Spacer(Modifier.width((7 * s).dp))
                        Text(it.genres.joinToString(" · "), color = Color.White.copy(alpha = 0.85f), fontSize = (9 * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (it.progress >= 0f && it.sub.isNotBlank()) {
                    Spacer(Modifier.height((6 * s).dp))
                    Text("Continue  ·  ${it.sub}", color = Color.White, fontSize = (9 * s).sp, fontWeight = FontWeight.Medium)
                }
                if (it.overview.isNotBlank()) {
                    Spacer(Modifier.height((8 * s).dp))
                    Text(it.overview, color = Color.White.copy(alpha = 0.82f), fontSize = (10 * s).sp, lineHeight = (14.5f * s).sp,
                        maxLines = settings.synopsisLines.coerceIn(1, 5), overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (steps) androidx.compose.runtime.key(item.key) { words(item) }
        else AnimatedContent(
            targetState = item, contentKey = { it.key },
            transitionSpec = { fadeIn(tween(settings.transitionMs)) togetherWith fadeOut(tween((settings.transitionMs * 0.4f).toInt())) },
            label = "heroText",
        ) { words(it) }
        Spacer(Modifier.height((12 * s).dp))
        // Where else it's on: its page in Netflix, Prime Video, Disney+ or Apple TV, when this TV has the app.
        val ctx = LocalContext.current
        val link by produceState<StreamLinks.Link?>(null, item.id, item.platform) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { StreamLinks.forTitle(ctx, app.http, item.id, item.name, item.platform).firstOrNull() }.getOrNull() }
        }
        val svcFocus = remember { FocusRequester() }
        var onMoreBtn by remember { mutableStateOf(false) }
        LaunchedEffect(onMoreBtn) { onMoreHeld = onMoreBtn }
        var onSvcBtn by remember { mutableStateOf(false) }
        LaunchedEffect(onSvcBtn) { onSvc = onSvcBtn }
        // Play, then the title in its streaming app, and + to save it to your Library (✓ once it's there). ◀ on Play
        // and ▶ on + change the title.
        Row(Modifier.wrapContentWidth(unbounded = true, align = Alignment.Start), verticalAlignment = Alignment.CenterVertically) {
            val svc = link
            HeroButton("Play", true, s, onPlayBtn,
                Modifier.focusRequester(heroFocus).focusProperties { this.down = down; this.up = up; right = moreFocus }
                    .onFocusChanged { onPlayBtn = it.isFocused }
                    .tvPress(onLong = onArt, onKey = { ev -> if (ev.key == Key.DirectionLeft && ev.type == KeyEventType.KeyDown) { onPrev(); true } else back(ev) }, onClick = onPlay))
            Spacer(Modifier.width((8 * s).dp))
            HeroButton("More info", false, s, onMoreBtn,
                Modifier.focusRequester(moreFocus).focusProperties { this.down = down; this.up = up; left = heroFocus; right = if (svc != null) svcFocus else saveFocus }
                    .onFocusChanged { onMoreBtn = it.isFocused }
                    .tvPress(onKey = back, onClick = onMore), info = true)
            if (svc != null) {
                Spacer(Modifier.width((8 * s).dp))
                HeroButton(svc.service, false, s, onSvcBtn,
                    Modifier.focusRequester(svcFocus).focusProperties { this.down = down; this.up = up; left = moreFocus; right = saveFocus }
                        .onFocusChanged { onSvcBtn = it.isFocused }
                        .tvPress(onKey = back, onClick = {
                            runCatching { ctx.startActivity(svc.intent()) }.onFailure { toast(ctx, "${svc.service} couldn't open it") }
                        }), info = true, iconPkg = svc.pkg)
            }
            Spacer(Modifier.width((8 * s).dp))
            HeroButton(if (saved) "In Library" else "My Library", false, s, onSaveBtn,
                Modifier.focusRequester(saveFocus).focusProperties { this.down = down; this.up = up; left = if (svc != null) svcFocus else moreFocus }
                    .onFocusChanged { onSaveBtn = it.isFocused }
                    .tvPress(onKey = { ev -> if (ev.key == Key.DirectionRight && ev.type == KeyEventType.KeyDown) { onNext(); true } else back(ev) }, onClick = onSave),
                saved = saved)
        }
    }
}

/** Play (white, with its triangle) or + My Library (grey, ✓ when saved); the selected one grows and lights up. */
@Composable
private fun HeroButton(label: String, play: Boolean, s: Float, focused: Boolean, modifier: Modifier, saved: Boolean = false, info: Boolean = false, iconPkg: String? = null) {
    val sc = liftOf(focused, 1.06f)
    val bg = when {
        focused -> Color.White
        play -> Color.White.copy(alpha = 0.88f)
        else -> Ds.Glass
    }
    val fg = if (focused || play) Ds.Ink else Color.White
    Row(
        modifier.graphicsLayer { scaleX = sc; scaleY = sc }.clip(RoundedCornerShape((Ds.R_CONTROL * s).dp)).background(bg)
            .padding(horizontal = (13 * s).dp, vertical = (7f * s).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (play) PlayGlyph(Modifier.size((8.5f * s).dp), fg)
        else if (info) Spacer(Modifier.width(0.dp))
        else Canvas(Modifier.size((9 * s).dp)) {
            val w = size.width; val h = size.height
            val st = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.14f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
            val p = androidx.compose.ui.graphics.Path()
            if (saved) { p.moveTo(w * 0.14f, h * 0.52f); p.lineTo(w * 0.40f, h * 0.78f); p.lineTo(w * 0.88f, h * 0.24f) }
            else { p.moveTo(w * 0.5f, h * 0.1f); p.lineTo(w * 0.5f, h * 0.9f); p.moveTo(w * 0.1f, h * 0.5f); p.lineTo(w * 0.9f, h * 0.5f) }
            drawPath(p, fg, style = st)
        }
        if (iconPkg != null) { ServiceIcon(iconPkg, s); Spacer(Modifier.width((6 * s).dp)) }
        if (!info) Spacer(Modifier.width((6 * s).dp))
        Text(label, color = fg, fontSize = (11 * s).sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
    }
}

/** Which title of the big picture is on: a dash each, the current one long and bright. */
@Composable
private fun Dashes(total: Int, current: Int, s: Float, modifier: Modifier) {
    val max = 10
    val start = (current / max) * max
    Row(modifier, horizontalArrangement = Arrangement.spacedBy((3 * s).dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in start until minOf(total, start + max)) {
            val on = i == current
            val w by animateDpAsState(if (on) (16 * s).dp else (8 * s).dp, spring(0.8f, 500f), label = "dash")
            Box(Modifier.width(w).height((2 * s).dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = if (on) 0.95f else 0.3f)))
        }
    }
}

/** IMDb's yellow tag, then the rating. */
@Composable
internal fun ImdbBadge(rating: String, s: Float, small: Boolean = false) {
    val f = if (small) 0.8f else 1f
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("IMDb", color = Color.Black, fontSize = (6.5f * s * f).sp, fontWeight = FontWeight.Black,
            modifier = Modifier.clip(RoundedCornerShape((2 * s).dp)).background(IMDB).padding(horizontal = (2.5f * s * f).dp))
        Spacer(Modifier.width((3 * s * f).dp))
        Text(rating, color = Color.White, fontSize = (8.5f * s * f).sp, fontWeight = FontWeight.Bold)
    }
}

// ───────────────────────────── cards ─────────────────────────────

/**
 * Back on a card or button: straight to the page's Back (rows → first row → big picture). Left to Compose, the first
 * Back only took the focus off the card, and nothing seemed to happen.
 */
@Composable
internal fun backKey(): (KeyEvent) -> Boolean {
    val owner = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current
    return { ev ->
        if (ev.key == Key.Back) {
            if (ev.type == KeyEventType.KeyUp) owner?.onBackPressedDispatcher?.onBackPressed()
            true
        } else false
    }
}

/** A card's selected look: it grows a little and gets a white outline. */
@Composable
internal fun Modifier.cardFocus(s: Float, focused: Boolean, radius: Float = Ds.R_CARD, grow: Float = 1.07f): Modifier {
    val sc = liftOf(focused, grow)
    val shape = RoundedCornerShape((radius * s).dp)
    val strong = LocalHighContrast.current
    return this.zIndex(if (focused) 1f else 0f).graphicsLayer { scaleX = sc; scaleY = sc }
        // The soft shadow under what's selected (one picture, drawn only for it): lifted off the page, like tvOS.
        .then(if (focused) Modifier.drawBehind { drawCardShadow() } else Modifier)
        .border(if (focused) ((if (strong) 3f else 1.8f) * s).dp else 0.dp, if (focused) Color.White else Color.Transparent, shape).clip(shape)
}

private fun req(ctx: android.content.Context, url: String, w: Int, h: Int) = ImageRequest.Builder(ctx).data(url).size(w, h).build()

/** Airing this week: the show's picture, NEW, its name, the episode, and when (Out now, or in how long). */
@Composable
private fun AiringCard(a: Airing, s: Float, modifier: Modifier, onFocused: () -> Unit, onOpen: () -> Unit, onHold: () -> Unit) {
    val ctx = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(30_000); value = System.currentTimeMillis() } }
    val e = a.episode
    Box(
        modifier.width((AIRING_W * s).dp).height((AIRING_W * 0.58f * s).dp)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .tvPress(onLong = onHold, onKey = backKey(), onClick = onOpen)
            .cardFocus(s, focused, 5f)
            .background(CARD),
    ) {
        val pic = a.item.background.ifBlank { a.item.poster }
        if (pic.isNotBlank()) AsyncImage(remember(pic) { req(ctx, pic, 420, 240) }, a.item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.2f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.9f))))
        if (a.out && now - e.released < 3 * 24 * 3600_000L) Text("NEW", color = Color.White, fontSize = (5.5f * s).sp, fontWeight = FontWeight.Black,
            modifier = Modifier.align(Alignment.TopStart).padding((4 * s).dp).clip(RoundedCornerShape((1.5f * s).dp)).background(RED).padding(horizontal = (3 * s).dp))
        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = (5 * s).dp, vertical = (4 * s).dp)) {
            RawText(a.item.name, color = Color.White, fontSize = (7.5f * s).sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val line = listOfNotNull("S${e.season} · E${e.episode}", e.title.takeIf { it.isNotBlank() }, if (a.more > 0) "+${a.more} more" else null).joinToString(" · ")
            Text(line, color = Color.White.copy(alpha = 0.75f), fontSize = (6 * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                // One line however large the text (Settings › Home › Text size): the day gives way first.
                Text(airDay(e.released, now), color = Color.White.copy(alpha = 0.75f), fontSize = (6 * s).sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width((3 * s).dp))
                Text(if (e.released <= now) "Out now" else "in " + airIn(e.released, now), color = if (e.released <= now) GREEN else RED,
                    fontSize = (6.5f * s).sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
            }
        }
    }
}

/** How many days from [now]'s day to [at]'s (0 today, 1 tomorrow, -1 yesterday), by the TV's calendar. */
private fun daysBetween(now: Long, at: Long): Int {
    val a = java.util.Calendar.getInstance().apply { timeInMillis = now }
    val b = java.util.Calendar.getInstance().apply { timeInMillis = at }
    fun dayIndex(c: java.util.Calendar) = c.get(java.util.Calendar.YEAR) * 400 + c.get(java.util.Calendar.DAY_OF_YEAR)
    return dayIndex(b) - dayIndex(a)
}

/** "Today 01:40", "Tomorrow 23:30", "Friday 11:15", "Mon 3 Nov 20:00". */
private fun airDay(at: Long, now: Long): String {
    val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(at))
    val d = daysBetween(now, at)
    val day = when {
        d == 0 -> "Today"
        d == 1 -> "Tomorrow"
        d == -1 -> "Yesterday"
        d in 2..6 -> java.text.SimpleDateFormat("EEEE", java.util.Locale.getDefault()).format(java.util.Date(at))
        else -> java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()).format(java.util.Date(at))
    }
    return "$day $time"
}

/** How long until it's out: "35m", "21h 47m" within a day, else by the calendar ("1 day" tomorrow, "3 days"). */
private fun airIn(at: Long, now: Long): String {
    val min = ((at - now) / 60_000L).coerceAtLeast(1)
    val days = daysBetween(now, at)
    return when {
        min < 60 -> "${min}m"
        min < 24 * 60 -> "${min / 60}h ${min % 60}m"
        days == 1 -> "1 day"
        else -> "$days days"
    }
}

/** A Continue watching card's app tag: its name and colour (Nuvio's blue, Stremio's purple), or null for none. */
private fun sourceTag(source: String): Pair<String, Color>? = when (source) {
    "Nuvio", "Nuvio Legacy" -> "Nuvio" to Color(0xFF4FA3FF)
    "Stremio" -> "Stremio" to Color(0xFF8A5AF0)
    else -> null
}

/**
 * Continue watching: the picture with its logo and progress (NEW EPISODE and +N when newer episodes are out), and
 * under it what it is: the title and how much is left, then the episode (S2 · E5 and its title), the channel with its
 * country, or the film's year and length.
 */
@Composable
private fun WatchCard(item: HeroItem, newer: Int, s: Float, modifier: Modifier, onFocused: () -> Unit, onOpen: () -> Unit, onHold: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.width((WATCH_W * s).dp)) {
        WatchPicture(item, newer, s, focused, modifier.onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .tvPress(onLong = onHold, onKey = backKey(), onClick = onOpen))
        Spacer(Modifier.height((6 * s).dp))
        WatchInfo(item, s, focused)
    }
}

/** What a Continue watching card is, under its picture. */
@Composable
private fun WatchInfo(item: HeroItem, s: Float, focused: Boolean) {
    val live = LiveCards.isLive(item)
    val dim = Color.White.copy(alpha = 0.6f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        RawText(item.name, color = Color.White.copy(alpha = if (focused) 1f else 0.85f), fontSize = (9 * s).sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (!live && item.progress >= 0f) {
            val left = (100 - Math.round(item.progress.coerceIn(0f, 1f) * 100)).coerceIn(1, 100)
            Spacer(Modifier.width((6 * s).dp))
            Text("$left% left", color = dim, fontSize = (7 * s).sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
    Spacer(Modifier.height((2.5f * s).dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        val series = !live && item.sub.isNotBlank()
        if (series) {
            // The episode as a chip (S2 · E5), then its title.
            RawText(item.sub, color = Color.White, fontSize = (6.5f * s).sp, fontWeight = FontWeight.Bold, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape((2.5f * s).dp)).background(Color.White.copy(alpha = 0.16f))
                    .padding(horizontal = (3.5f * s).dp, vertical = (0.5f * s).dp))
            if (item.episodeTitle.isNotBlank()) {
                Spacer(Modifier.width((5 * s).dp))
                Text(item.episodeTitle, color = dim, fontSize = (7.5f * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
            }
        } else {
            // Live TV: the channel (its number and name), then its country; a film: its year and length.
            val line = if (live) item.sub else listOf(if (item.type.equals("movie", true)) "Movie" else "Series", item.year, item.runtime)
                .filter { it.isNotBlank() }.joinToString("  ·  ")
            Text(line, color = dim, fontSize = (7.5f * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false))
            if (item.country.isNotEmpty()) { Spacer(Modifier.width((4 * s).dp)); CountryTag(item.country, s * 0.85f) }
        }
    }
}

/** A Continue watching card's picture. */
@Composable
private fun WatchPicture(item: HeroItem, newer: Int, s: Float, focused: Boolean, modifier: Modifier) {
    val ctx = LocalContext.current
    val live = LiveCards.isLive(item)
    Box(
        modifier.width((WATCH_W * s).dp).height((WATCH_W * 9 / 16 * s).dp)
            .cardFocus(s, focused, 6f, 1.05f)
            .background(CARD),
    ) {
        val logoOnly = live && item.background.isBlank() && item.logo.isNotBlank()
        if (logoOnly) AsyncImage(remember(item.logo) { req(ctx, item.logo, 300, 170) }, null, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(horizontal = (34 * s).dp, vertical = (20 * s).dp))
        else {
            val pic = item.background.ifBlank { item.poster }
            if (pic.isNotBlank()) AsyncImage(remember(pic) { req(ctx, pic, 640, 360) }, item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f))))
        // Top left: LIVE, or the app it came from; top right: how many newer episodes are out.
        if (live && item.type != LiveCards.TYPE_RECORDING && item.type != LiveCards.TYPE_RESUME) Text("LIVE", color = Color.White, fontSize = (6.5f * s).sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopStart).padding((6 * s).dp).clip(RoundedCornerShape((3 * s).dp)).background(RED).padding(horizontal = (4 * s).dp, vertical = (1 * s).dp))
        sourceTag(item.source)?.let { (name, dot) ->
            Row(Modifier.align(Alignment.TopStart).padding((6 * s).dp).clip(RoundedCornerShape((3 * s).dp)).background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = (4 * s).dp, vertical = (1 * s).dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size((3.5f * s).dp).background(dot, CircleShape))
                Spacer(Modifier.width((3 * s).dp))
                Text(name, color = Color.White.copy(alpha = 0.92f), fontSize = (6.5f * s).sp, fontWeight = FontWeight.SemiBold)
            }
        }
        if (newer > 0) Text("+$newer", color = Color.White, fontSize = (6.5f * s).sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopEnd).padding((6 * s).dp).clip(RoundedCornerShape((3 * s).dp)).background(RED).padding(horizontal = (4 * s).dp, vertical = (0.5f * s).dp))
        // A Live TV channel's resolution (Live TV › Settings › Channels › Resolution badges), as on Live TV's own cards.
        else if (live && item.badge.isNotEmpty()) ResBadge(item.badge, s, Modifier.align(Alignment.TopEnd).padding((6 * s).dp))
        // The title: its logo, else its name.
        if (!logoOnly) {
            if (item.logo.isNotBlank()) AsyncImage(remember(item.logo) { req(ctx, item.logo, 300, 100) }, item.name, contentScale = ContentScale.Fit, alignment = Alignment.BottomStart,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = (8 * s).dp, bottom = (12 * s).dp).size(width = (82 * s).dp, height = (26 * s).dp))
            else RawText(item.name, color = Color.White, fontSize = (8.5f * s).sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = (8 * s).dp, bottom = (12 * s).dp, end = (8 * s).dp))
        }
        if (newer > 0) Text("NEW EPISODE", color = Color.White, fontSize = (5.5f * s).sp, fontWeight = FontWeight.Black,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = (5 * s).dp).clip(RoundedCornerShape((1.5f * s).dp)).background(RED).padding(horizontal = (4 * s).dp))
        else if (item.upNext) Text("UP NEXT", color = Color.Black, fontSize = (5.5f * s).sp, fontWeight = FontWeight.Black,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = (5 * s).dp).clip(RoundedCornerShape((1.5f * s).dp)).background(Color.White).padding(horizontal = (4 * s).dp))
        if (item.progress >= 0f) {
            val p = item.progress.coerceIn(0f, 1f)
            val accent = RED
            Canvas(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height((2.5f * s).dp)) {
                drawRect(Color.White.copy(alpha = 0.25f))
                drawRect(accent, size = Size(size.width * p, size.height))
            }
        }
    }
}

/** A recomposition scope of its own: state read inside [content] recomposes only it, not the composable around it. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.Isolated(content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) = content()

/** How the poster cards open (Settings › Home): after how long, all at once or staggered, slow zoom, lighter card. */
private data class CardLook(val openMs: Int = 650, val together: Boolean = false, val zoom: Boolean = false, val light: Boolean = false,
    /** Follow the TV's animation speed (HomeMotion), not the card's own timings. */
    val followTv: Boolean = false)

/**
 * A row's open card (Settings › Home › Keep the row open): which one is open, and when the last one closed, so the
 * next card along opens straight away instead of waiting.
 */
private class RowOpen {
    var openKey: String? = null
    var closedAt = 0L
    /** A card in this row is open, or closed just now (the focus moving on from it). */
    fun warm() = openKey != null || android.os.SystemClock.uptimeMillis() - closedAt < 400
}

/**
 * A poster with its IMDb rating. Rested on, it opens up into a wide card: the picture with the logo on top, then
 * Play / + / more, the rating, year and runtime, and the genres (the posters beside it make room).
 */
@Composable
private fun PosterCard(item: HeroItem, s: Float, expand: Boolean, modifier: Modifier, onFocused: () -> Unit, onOpen: () -> Unit, onHold: () -> Unit,
    /** Seconds open before its trailer plays in its picture (Settings › Home › Trailer in the open card); 0: none. */
    trailerSec: Int = 0, app: GlassApp? = null, look: CardLook = CardLook(), row: RowOpen? = null,
    /** The cards either side of it: their wide pictures load while it's open, so moving along the row opens the next at once. */
    beside: () -> List<HeroItem> = { emptyList() }) {
    val ctx = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(focused, expand) {
        if (!focused || !expand) {
            if (open && row != null) { if (row.openKey == item.key) row.openKey = null; row.closedAt = android.os.SystemClock.uptimeMillis() }
            open = false; return@LaunchedEffect
        }
        // Along a row with a card open, this one opens straight away (the last one closing as it does).
        val focusAt = android.os.SystemClock.uptimeMillis()
        val warm = row != null && row.warm()
        if (!warm) delay(look.openMs.toLong())
        if (com.glasslauncher.tv.data.Glog.timing) com.glasslauncher.tv.data.Glog.i("Card ${item.name}: open after ${android.os.SystemClock.uptimeMillis() - focusAt} ms (row ${if (warm) "open" else if (row == null) "off" else "closed"})")
        open = true
        row?.openKey = item.key
        // The wide pictures either side, as this card asks for its own (the same request, so from the same cache):
        // moving along the row, the next card opens with the last closing instead of after its picture loads.
        val loader = coil3.SingletonImageLoader.get(ctx)
        for (n in beside()) n.background.ifBlank { n.poster }.takeIf { it.isNotBlank() }?.let { loader.enqueue(req(ctx, it, 480, 270)) }
    }
    // Opening, as on the big streaming apps. Together (measured from a recording): in 200 ms it widens (the posters
    // beside it make room), the poster crossfades into the wide picture and the details rise in, all at once.
    // Staggered: the picture first, then the buttons and details under it. Closing, the same backwards.
    val still = LocalReduceMotion.current
    val together = look.together
    val ease = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    // Together, it widens only once its wide picture is here (never a wide empty card), in the same frames as the rest.
    var picReady by remember(item.key) { mutableStateOf(false) }
    val wide = if (together) open && picReady else open
    // At the card's own timings (HomeMotion), whatever the TV's animation speed, unless Follow the TV's is on.
    val follow = look.followTv
    val wDp by animateAtOwnSpeed((if (wide) POSTER_W * OPEN_W else POSTER_W) * s, tween(if (still) 0 else if (together) 200 else 380, easing = ease), follow)
    val w = wDp.dp
    val h = (POSTER_W * 1.5f * s).dp
    // Its trailer, after a while open: found, then played in the picture; any move away stops it.
    var trailerIds by remember { mutableStateOf<String?>(null) }
    var trailerPlaying by remember { mutableStateOf(false) }
    LaunchedEffect(open, trailerSec, item.key) {
        trailerIds = null; trailerPlaying = false
        if (!open || trailerSec <= 0 || app == null) return@LaunchedEffect
        delay(trailerSec * 1000L)
        trailerIds = withContext(Dispatchers.IO) { trailerOf(app, item) }
    }
    // Card style (Settings › Home): the lighter grey of the streaming apps, or as dark as the page.
    val cardBg = if (look.light) CARD_LIGHT else CARD
    Box(
        modifier.width(w).height(h)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .tvPress(onLong = onHold, onKey = backKey(), onClick = onOpen)
            .cardFocus(s, focused, 5f, if (open) 1.03f else 1.06f)
            .background(cardBg),
    ) {
        // Its wide picture loads (unseen) as soon as it's selected: the poster fades into it once it's there, never into an empty card.
        val shownOpen = open && picReady
        val picA by animateAtOwnSpeed(if (shownOpen) 1f else 0f,
            if (together) tween(if (still) 0 else 180, easing = ease) else tween(if (still) 0 else 320, delayMillis = if (shownOpen && !still) 60 else 0), follow)
        val infoA by animateAtOwnSpeed(if (shownOpen) 1f else 0f,
            if (together) tween(if (still) 0 else if (shownOpen) 200 else 120, easing = ease)
            else tween(if (still) 0 else if (shownOpen) 260 else 140, delayMillis = if (shownOpen && !still) 220 else 0), follow)
        val posterA by animateAtOwnSpeed(if (shownOpen) 0f else 1f,
            if (together) tween(if (still) 0 else 180, easing = ease)
            else tween(if (still) 0 else if (shownOpen) 340 else 220, delayMillis = if (shownOpen && !still) 80 else 0), follow)
        // Slow zoom (Settings › Home), as on the streaming apps (measured from a recording): from the moment it opens,
        // its picture grows at a steady 1.8% a second towards a point right of its middle. Closing, it stays as it is
        // while it fades (no jump back), and starts again from its own size the next time it opens.
        // Worked out from the clock, not an animation: a TV's animation speed (0.5x on some) leaves it as measured.
        val zoomFrom = remember { androidx.compose.runtime.mutableLongStateOf(0L) }
        val zoomNow = remember { androidx.compose.runtime.mutableLongStateOf(0L) }
        val zoomOn = look.zoom && !still
        LaunchedEffect(shownOpen, zoomOn) {
            if (!zoomOn) { zoomFrom.longValue = 0L; return@LaunchedEffect }
            if (!shownOpen) return@LaunchedEffect
            zoomFrom.longValue = android.os.SystemClock.uptimeMillis()
            while (slowZoomOf(zoomFrom.longValue, zoomNow.longValue, CARD_ZOOM_PER_S) < SLOW_ZOOM_MAX) {
                androidx.compose.runtime.withFrameMillis { zoomNow.longValue = android.os.SystemClock.uptimeMillis() }
            }
        }
        val zoom = { if (zoomFrom.longValue == 0L) 1f else slowZoomOf(zoomFrom.longValue, zoomNow.longValue, CARD_ZOOM_PER_S) }
        if (focused || picA > 0.01f) Box(Modifier.fillMaxWidth().height((POSTER_W * OPEN_W * 9 / 16 * s).dp).clipToBounds().graphicsLayer { alpha = picA }) {
            val pic = item.background.ifBlank { item.poster }
            trailerIds?.let { ids ->
                CardTrailer(ids, Modifier.fillMaxSize(), followTv = look.followTv, insetPx = kotlin.math.ceil(1.8f * s * LocalDensity.current.density).toInt(), fadeColor = cardBg.toArgb(),
                    onPlaying = { trailerPlaying = true }, onEnded = { trailerIds = null; trailerPlaying = false })
            }
            val trailerShown by animateFloatAsState(if (trailerPlaying) 1f else 0f, tween(500), label = "cardTrailer")
            AsyncImage(remember(pic) { req(ctx, pic, 480, 270) }, null, contentScale = ContentScale.Crop, onSuccess = { if (com.glasslauncher.tv.data.Glog.timing && !picReady) com.glasslauncher.tv.data.Glog.i("Card ${item.name}: picture ready"); picReady = true }, onError = { picReady = true },
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 1f - trailerShown; val z = zoom(); scaleX = z; scaleY = z
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(CARD_ZOOM_X, CARD_ZOOM_Y) })
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to cardBg)))
            if (item.logo.isNotBlank()) AsyncImage(remember(item.logo) { req(ctx, item.logo, 300, 100) }, item.name, contentScale = ContentScale.Fit, alignment = Alignment.BottomStart,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = (6 * s).dp, bottom = (3 * s).dp).size(width = (80 * s).dp, height = (22 * s).dp).graphicsLayer { alpha = 1f - trailerShown * 0.6f })
            else RawText(item.name, color = Color.White, fontSize = (9 * s).sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = (6 * s).dp, bottom = (3 * s).dp))
        }
        if (posterA > 0.01f) {
            if (item.poster.isNotBlank()) AsyncImage(remember(item.poster) { req(ctx, item.poster, 300, 450) }, item.name, contentScale = ContentScale.Crop,
                modifier = Modifier.width((POSTER_W * s).dp).fillMaxHeight().graphicsLayer { alpha = posterA })
            else RawText(item.name, color = Color.White.copy(alpha = 0.7f * posterA), fontSize = (8 * s).sp, modifier = Modifier.align(Alignment.CenterStart).width((POSTER_W * s).dp).padding((8 * s).dp))
        }
        if (item.rating.isNotBlank() && !open && posterA > 0.5f) Box(Modifier.align(Alignment.TopStart).padding((4 * s).dp).clip(RoundedCornerShape((2 * s).dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = (2 * s).dp, vertical = (1 * s).dp)) {
            ImdbBadge(item.rating, s, small = true)
        }
        if (infoA > 0.01f) Column(Modifier.fillMaxSize().graphicsLayer { alpha = infoA; translationY = (1f - infoA) * (if (together) 12 else 6) * s * density }) {
            Spacer(Modifier.height((POSTER_W * OPEN_W * 9 / 16 * s).dp))
            Column(Modifier.padding(horizontal = (6 * s).dp, vertical = (4 * s).dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size((14 * s).dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
                        PlayGlyph(Modifier.size((5.5f * s).dp).padding(start = (0.5f * s).dp), Color.Black)
                    }
                    Spacer(Modifier.width((4 * s).dp))
                    RingGlyph(s, "+")
                    Spacer(Modifier.weight(1f))
                    RingGlyph(s, "v")
                }
                Spacer(Modifier.height((5 * s).dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.rating.isNotBlank()) {
                        Text("${item.rating} IMDb", color = GREEN, fontSize = (6.5f * s).sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width((4 * s).dp))
                    }
                    val facts = listOfNotNull(item.year.takeIf { it.isNotBlank() }, item.runtime.takeIf { it.isNotBlank() })
                    Text(facts.joinToString("  "), color = Color.White.copy(alpha = 0.8f), fontSize = (6.5f * s).sp, maxLines = 1)
                }
                if (item.genres.isNotEmpty()) {
                    Spacer(Modifier.height((3 * s).dp))
                    Text(item.genres.joinToString("  •  "), color = Color.White.copy(alpha = 0.9f), fontSize = (6.5f * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** A small round outlined button glyph on the wide card: + (save) or v (more). */
@Composable
private fun RingGlyph(s: Float, kind: String) {
    Canvas(Modifier.size((14 * s).dp)) {
        val w = size.width
        drawCircle(Color.White.copy(alpha = 0.7f), w / 2 - w * 0.04f, style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.07f))
        val st = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.08f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        val p = androidx.compose.ui.graphics.Path()
        if (kind == "+") { p.moveTo(w * 0.5f, w * 0.3f); p.lineTo(w * 0.5f, w * 0.7f); p.moveTo(w * 0.3f, w * 0.5f); p.lineTo(w * 0.7f, w * 0.5f) }
        else { p.moveTo(w * 0.32f, w * 0.42f); p.lineTo(w * 0.5f, w * 0.6f); p.lineTo(w * 0.68f, w * 0.42f) }
        drawPath(p, Color.White, style = st)
    }
}

/** The end of a library row: the whole list, in the Library. */
@Composable
private fun SeeAllCard(s: Float, modifier: Modifier, onOpen: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier.width((POSTER_W * 0.8f * s).dp).height((POSTER_W * 1.5f * s).dp)
            .onFocusChanged { focused = it.isFocused }
            .tvPress(onKey = backKey(), onClick = onOpen)
            .cardFocus(s, focused, 5f)
            .background(Color.White.copy(alpha = if (focused) 0.16f else 0.07f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("See all", color = Color.White, fontSize = (9 * s).sp, fontWeight = FontWeight.SemiBold)
            Text("in the Library", color = Color.White.copy(alpha = 0.6f), fontSize = (6.5f * s).sp)
        }
    }
}

// ───────────────────────────── apps ─────────────────────────────

/**
 * Your apps in a grid, [perRow] to a row (Settings → Apps & icons), the Apps folder ("All apps", if shown) where you
 * put it: OK opens, hold OK for its options. [onRow] gives each row's top and bottom on the screen; [onFocusRow] the
 * row with the focus.
 */
@Composable
private fun AppsGrid(
    app: GlassApp, s: Float, perRow: Int, appsFocus: FocusRequester, up: FocusRequester, below: FocusRequester, folderFocus: FocusRequester, tileFocus: HashMap<String, FocusRequester>,
    movingPkg: String?, onMoveKey: (KeyEvent) -> Boolean, onMenu: (AppEntry) -> Unit, onFolder: () -> Unit, onFolderMenu: () -> Unit,
    onRow: (Int, Float, Float) -> Unit, onFocusRow: (Int) -> Unit,
) {
    val ctx = LocalContext.current
    val settings = app.settings
    val allApps by app.apps.apps.collectAsState()
    // Kids mode: only the apps chosen for it (hidden ones too, when chosen), and no Apps folder.
    val kids = settings.kidsMode
    val apps = remember(allApps, settings.dockOrder, settings.hiddenApps, settings.showMobileApps, kids, settings.kidsApps) {
        if (kids) settings.kidsApps.toSet().let { ok -> app.apps.ordered(includeHidden = true).filter { it.pkg in ok } } else app.apps.ordered()
    }
    val tiles: List<AppEntry?> = remember(apps, settings.folderPos, settings.showFolder, kids) {
        val p = settings.folderPos.let { if (it < 0 || it > apps.size) apps.size else it }
        apps.toMutableList<AppEntry?>().apply { if (settings.showFolder && !kids) add(p, null) }
    }
    // Moving an app: it keeps the focus at every step (laid out again in its new place).
    LaunchedEffect(tiles, movingPkg) {
        val p = movingPkg ?: return@LaunchedEffect
        delay(30)
        runCatching { (if (p == FOLDER_KEY) folderFocus else tileFocus[p])?.requestFocus() }
    }
    var last by remember { mutableStateOf<String?>(null) }
    val keys = tiles.map { it?.pkg ?: FOLDER_KEY }
    val entryKey = keys.firstOrNull { it == last } ?: keys.firstOrNull()
    val n = perRow.coerceIn(APPS_MIN, APPS_MAX)
    val rows = tiles.chunked(n)
    val gap = (10 * s).dp
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = (EDGE * s).dp, vertical = (6 * s).dp)) {
        val tileW = (maxWidth - gap * (n - 1)) / n
        Column(Modifier.focusGroup(), verticalArrangement = Arrangement.spacedBy((4 * s).dp)) {
            rows.forEachIndexed { r, row ->
                Row(
                    Modifier.onGloballyPositioned { c -> val y = c.positionInRoot().y; onRow(r, y, y + c.size.height) },
                    horizontalArrangement = Arrangement.spacedBy(gap),
                ) {
                    row.forEach { e ->
                        val k = e?.pkg ?: FOLDER_KEY
                        key(k) {
                            val base = Modifier.then(if (k == entryKey) Modifier.focusRequester(appsFocus) else Modifier)
                                .focusProperties { if (r == 0) this.up = up; if (r == rows.lastIndex) down = below }
                                .onFocusChanged { if (it.isFocused) { last = k; onFocusRow(r) } }
                            if (e == null) AppTile(app, null, s, tileW, movingPkg == FOLDER_KEY, base.focusRequester(folderFocus), onMoveKey, onFolder, onFolderMenu)
                            else AppTile(app, e, s, tileW, movingPkg == e.pkg, base.focusRequester(tileFocus.getOrPut(e.pkg) { FocusRequester() }), onMoveKey,
                                { app.apps.open(ctx, e) }, { onMenu(e) })
                        }
                    }
                }
            }
        }
    }
}

/** How many apps a row of the grid can hold (Settings → Apps & icons → Apps per row). */
internal const val APPS_MIN = 3
internal const val APPS_MAX = 8

/** The Apps folder's place in the row (move mode). */
internal const val FOLDER_KEY = "__apps_folder__"

/** One app (or, for null, the Apps folder): its icon on a dark rounded plate, its name under it when selected. */
@Composable
private fun AppTile(
    app: GlassApp, entry: AppEntry?, s: Float, width: androidx.compose.ui.unit.Dp, moving: Boolean, modifier: Modifier,
    onMoveKey: (KeyEvent) -> Boolean, onClick: () -> Unit, onLong: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val back = backKey()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier.width(width).height(width / 1.6f)
                .onFocusChanged { focused = it.isFocused }
                .tvPress(onLong = onLong, onKey = { ev -> onMoveKey(ev) || back(ev) }, onClick = onClick)
                .cardFocus(s, focused, 7f, if (moving) 1.08f else 1.05f)
                .background(Color(0xFF26262A)),
            contentAlignment = Alignment.Center,
        ) {
            if (entry == null) {
                val all by app.apps.apps.collectAsState()
                val preview = remember(all, app.settings.dockOrder) { app.apps.ordered(includeHidden = true).take(6) }
                Column(Modifier.fillMaxSize().padding((6 * s).dp), verticalArrangement = Arrangement.spacedBy((3 * s).dp)) {
                    preview.chunked(3).forEach { row ->
                        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy((3 * s).dp)) {
                            row.forEach { e ->
                                val art by produceState(app.apps.cachedArtwork(e), e.pkg, app.settings.iconPack, app.icons.rev.intValue) { value = app.apps.artwork(e) }
                                Box(Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape((3 * s).dp)).background(Color.White.copy(alpha = 0.06f))) {
                                    IconArt(art, "", "liquid", s * 0.35f)
                                }
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            } else {
                val art by produceState(app.apps.cachedArtwork(entry), entry.pkg, app.settings.iconPack, app.icons.rev.intValue) { value = app.apps.artwork(entry) }
                IconArt(art, entry.label, "liquid", s)
            }
            if (moving) Text("◀ ▶ ▲ ▼  ·  OK", color = Color.White, fontSize = (7 * s).sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = (3 * s).dp).clip(RoundedCornerShape((4 * s).dp))
                    .background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = (5 * s).dp, vertical = (1 * s).dp))
        }
        Spacer(Modifier.height((5 * s).dp))
        Text(if (entry == null) "All apps" else entry.label, color = Color.White.copy(alpha = if (focused) 0.95f else 0f), fontSize = (7.5f * s).sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(width), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

// ───────────────────────────── menus ─────────────────────────────

/** Hold OK on a title in a row: open it in your other app; save it to (or take it out of) your Library. */
private fun titleMenu(app: GlassApp, ctx: android.content.Context, item: HeroItem, scope: kotlinx.coroutines.CoroutineScope, onDialog: (DialogState) -> Unit, from: com.glasslauncher.tv.data.MyCollection? = null): DialogState {
    val settings = app.settings
    val options = ArrayList<Pair<String, () -> Unit>>()
    for (t in NuvioLaunch.others(ctx, settings, item))
        options += "Open in ${NuvioLaunch.shortName(t)}" to { NuvioLaunch.open(ctx, settings, app.http, item, t) }
    val inNuvio = app.library.nuvioList.value.any { it.key == item.key }
    val inStremio = app.library.stremioList.value.any { it.key == item.key }
    if (app.library.has(item.key)) options += "Remove from Library" to { app.library.toggle(item); toast(ctx, "Removed from Library · ${item.name}") }
    else options += "Save to Library" to { app.library.toggle(item); toast(ctx, "Saved to Library · ${item.name}") }
    if (inNuvio) options += "Remove from my Nuvio library" to {
        app.library.removeFromNuvio(item) { ok -> toast(ctx.applicationContext, if (ok) "Removed from your Nuvio library · ${item.name}" else "Nuvio: couldn't remove ${item.name}") }
    }
    if (inStremio) options += "Remove from my Stremio library" to {
        app.library.removeFromStremio(item) { ok -> toast(ctx.applicationContext, if (ok) "Removed from your Stremio library · ${item.name}" else "Stremio: couldn't remove ${item.name}") }
    }
    if (settings.homeMine) options += "Add to a collection" to {
        scope.launch { delay(80); onDialog(collectionMenu(app, ctx, scope, item, onDialog)) }
        Unit
    }
    if (from != null) options += "Remove from ${from.name}" to {
        settings.myCollections = Collections.write(Collections.toggle(Collections.parse(settings.myCollections), from.slot, item))
        toast(ctx, "Removed from ${from.name} · ${item.name}")
    }
    return MenuDialog(item.name, options)
}

/** Hold OK on Continue watching: a title's menu (other app, mark watched, remove), a channel's (record, remove). */
private fun watchMenu(app: GlassApp, ctx: android.content.Context, scope: kotlinx.coroutines.CoroutineScope, item: HeroItem, onDialog: (DialogState) -> Unit) {
    val settings = app.settings
    val cards = LiveCards
    val remove = {
        if (cards.isLive(item)) cards.hide(ctx, item) else app.cw.remove(item.key)
        toast(ctx, "Removed from Continue Watching · ${item.name}")
    }
    val channel = item.id.removePrefix("ch:").toIntOrNull()
    if (!cards.isLive(item)) {
        val options = ArrayList<Pair<String, () -> Unit>>()
        for (t in NuvioLaunch.others(ctx, settings, item))
            options += "Open in ${NuvioLaunch.shortName(t)}" to { NuvioLaunch.open(ctx, settings, app.http, item, t) }
        if (app.cw.canMarkWatched(item.key)) options += (if (item.sub.isNotBlank()) "Mark ${item.sub} as watched" else "Mark as watched") to {
            app.cw.markWatched(item.key, item.name) { ok ->
                toast(ctx, if (ok) "Marked as watched · ${item.name}" else "Couldn't mark ${item.name} as watched. Try again")
            }
        }
        options += "Remove from Continue Watching" to { remove() }
        onDialog(MenuDialog(item.name, options))
    } else if (item.type == cards.TYPE_RECORDING || channel == null) remove()
    else scope.launch {
        // A channel on now (the last one, a followed match): record what's on, or take the card out.
        val recording = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { com.glasslauncher.tv.live.LiveHome.recordingNow(ctx, channel) }.getOrDefault(false)
        }
        onDialog(MenuDialog(item.name, listOf(
            (if (recording) "Stop recording" else "Record this show") to {
                scope.launch {
                    val said = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { com.glasslauncher.tv.live.LiveHome.recordNow(ctx, channel) }.getOrElse { "Couldn't record: ${it.message}" }
                    }
                    toast(ctx, said)
                }
                Unit
            },
            "Remove from Continue Watching" to { remove() },
        )))
    }
}
