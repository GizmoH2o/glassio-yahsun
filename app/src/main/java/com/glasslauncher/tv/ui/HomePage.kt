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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
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
import com.glasslauncher.tv.data.LiveCards
import com.glasslauncher.tv.data.NuvioLaunch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The page's left edge, as the big picture's text (dp at 960 wide). */
private const val EDGE = 48f
private const val ROW_GAP = 14f
private const val AIRING_W = 104f
private const val WATCH_W = 168f
private const val POSTER_W = 100f
/** A poster rested on opens up to this much wider (its picture on top, its details under it). */
private const val OPEN_W = 1.32f
private val RED = Color(0xFFE50914)
private val GREEN = Color(0xFF46D369)
private val IMDB = Color(0xFFF5C518)
private val CARD = Color(0xFF1C1C1F)

/** Which part of the page holds the focus: the big picture, or a row (in page order). */
private enum class Part { Hero, Airing, Watch, Movies, Series, Apps }

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
    shownUrl: androidx.compose.runtime.State<String?>, onBackdrop: (String?) -> Unit,
    onHeroFocus: (Boolean) -> Unit, onDialog: (DialogState) -> Unit,
    onAppMenu: (AppEntry) -> Unit, onFolder: () -> Unit, onFolderMenu: () -> Unit, onLibrary: () -> Unit,
    /** How far down the page is, 0 (the top) to 1: the top bar fades as it goes. */
    down: androidx.compose.runtime.MutableFloatState,
) {
    val settings = app.settings
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val catalogItems by app.catalogs.items.collectAsState()
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

    // ── what the rows hold
    val watchItems = remember(liveCards, cwItems, enriched, settings.continueMode, settings.artChoice) {
        if (settings.continueMode == "off") liveCards
        else liveCards + cwItems.map { c -> (enriched[c.key] ?: c).let { withArtwork(it, settings.artChoice, settings.artPick(it.key)) }.copy(progress = c.progress, sub = c.sub, source = c.source) }
    }
    val showAiring = settings.homeAiring && airing.isNotEmpty()
    val showMovies = settings.homeMovies && movies.isNotEmpty()
    val showSeries = settings.homeSeries && series.isNotEmpty()
    val parts = buildList {
        add(Part.Hero)
        if (showAiring) add(Part.Airing)
        if (watchItems.isNotEmpty()) add(Part.Watch)
        if (showMovies) add(Part.Movies)
        if (showSeries) add(Part.Series)
        add(Part.Apps)
    }
    val entry = remember { mutableStateMapOf<Part, FocusRequester>() }
    fun req(p: Part) = if (p == Part.Hero) heroFocus else if (p == Part.Apps) appsFocus else entry.getOrPut(p) { FocusRequester() }
    fun above(p: Part) = req(parts.getOrNull(parts.indexOf(p) - 1) ?: Part.Hero)
    fun below(p: Part) = parts.getOrNull(parts.indexOf(p) + 1)?.let { req(it) } ?: FocusRequester.Cancel
    var inPart by remember { mutableStateOf(Part.Hero) }
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

    // ── the big picture: the catalogs' titles in turn, or the title selected in a row
    var index by remember { mutableIntStateOf(0) }
    val slides = catalogItems
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
    // Its text changes with its picture (or after a moment, if the picture is slow).
    var textItem by remember { mutableStateOf<HeroItem?>(null) }
    LaunchedEffect(hero?.key, hero?.logo, hero?.overview) {
        val h = hero
        if (h == null || textItem == null || h.background.isBlank() || textItem?.key == h.key) { textItem = h; return@LaunchedEffect }
        kotlinx.coroutines.withTimeoutOrNull(1200) { androidx.compose.runtime.snapshotFlow { shownUrl.value }.first { it == h.background } }
        textItem = h
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
    // On its own: the next title every few seconds while the big picture is selected (or nothing is).
    LaunchedEffect(index, slides.size, settings.heroAutoplay, settings.heroIntervalSec, visible, paused, resumed, inPart) {
        if (settings.heroAutoplay && visible && !paused && resumed && slides.size > 1 && inPart == Part.Hero) {
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
    val screenPx = screenH * density
    val spot = if (inPart == Part.Apps && appRow > 0) appRows[appRow] else tops[inPart]?.let { t ->
        // The apps' first row: from the "Apps" title to that row's bottom.
        if (inPart == Part.Apps) appRows[0]?.let { r -> t.first to r.second } ?: t else t
    }
    LaunchedEffect(inPart, spot, screenPx) {
        val (top, bottom) = spot ?: return@LaunchedEffect
        // The row as low on the screen as it fits, so what's above it shows (the big picture, back at the first row);
        // a row taller than the screen from its top.
        val margin = 26 * s * density
        val target = if (inPart == Part.Hero) 0f else minOf(bottom - screenPx + margin, top - 70 * s * density).coerceAtLeast(0f)
        offset.animateTo(target, tween(420, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)))
    }
    val heroH = screenH * 0.66f
    LaunchedEffect(Unit) { androidx.compose.runtime.snapshotFlow { offset.value }.collect { down.floatValue = (it / (40 * s * density)).coerceIn(0f, 1f) } }

    // Back: from a row to the first row, from there to the big picture (then, as before, the screensaver if set).
    val firstRow = parts.getOrNull(1)
    BackHandler(enabled = visible && movingPkg == null && inPart != Part.Hero && inPart != firstRow) { runCatching { firstRow?.let { req(it).requestFocus() } } }
    BackHandler(enabled = visible && movingPkg == null && inPart != Part.Hero && inPart == firstRow) { runCatching { heroFocus.requestFocus() } }

    val defaultSpec = LocalBringIntoViewSpec.current
    Box(Modifier.fillMaxSize().clipToBounds()) {
        // Further down the page the picture darkens, so the rows read over it.
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = (offset.value / (heroH * density * 0.6f)).coerceIn(0f, 0.72f) }.background(Color(0xFF0B0B0D)))
        // Nothing scrolls by itself: the page moves (above) and each row scrolls along (its own default).
        CompositionLocalProvider(LocalBringIntoViewSpec provides NoPageScroll) {
            Column(
                Modifier.fillMaxWidth().layout { m, c ->
                    val p = m.measure(c.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                    layout(p.width, c.maxHeight) { p.placeWithLayer(0, 0) { translationY = -offset.value } }
                },
            ) {
                // ── the big picture
                Box(Modifier.fillMaxWidth().height(heroH.dp).onGloballyPositioned { tops[Part.Hero] = it.positionInParent().y to it.positionInParent().y + it.size.height }) {
                    val shown = textItem
                    val fade = { (1f - offset.value / (heroH * density * 0.55f)).coerceIn(0f, 1f) }
                    if (shown != null && !app.showSetup.value) {
                        HeroText(app, shown, s, savedList.any { it.key == shown.key }, heroFocus,
                            down = parts.getOrNull(1)?.let { req(it) } ?: appsFocus,
                            onFocus = { f -> if (f) { inPart = Part.Hero; selected = null }; onHeroFocus(f) },
                            onPrev = { app.navAt = System.currentTimeMillis(); SlideDir.dir = -1; index-- },
                            onNext = { app.navAt = System.currentTimeMillis(); SlideDir.dir = 1; index++ },
                            onPlay = { NuvioLaunch.open(ctx, settings, app.http, shown) },
                            onSave = {
                                val now = app.library.toggle(shown)
                                toast(ctx, if (now) "Saved to Library · ${shown.name}" else "Removed from Library · ${shown.name}")
                            },
                            onArt = {
                                val base = enriched[shown.key] ?: shown
                                val options = artOptions(base)
                                if (options.size < 2) toast(ctx, "No other pictures for ${shown.name} (add a TMDB token for more)")
                                else {
                                    val cur = options.indexOf(shown.background).coerceAtLeast(0)
                                    settings.setArtPick(shown.key, options[(cur + 1) % options.size])
                                    toast(ctx, "Picture ${(cur + 1) % options.size + 1} of ${options.size} · ${shown.name}  (hold OK again for the next)")
                                }
                            },
                            modifier = Modifier.align(Alignment.BottomStart).graphicsLayer { alpha = fade() })
                        if (slides.size > 1 && hero == slide) Dashes(slides.size, index.mod(slides.size), s,
                            Modifier.align(Alignment.BottomEnd).padding(end = (EDGE * s).dp, bottom = (12 * s).dp).graphicsLayer { alpha = fade() })
                    } else {
                        EmptyHint(app, status, s, heroFocus, parts.getOrNull(1)?.let { req(it) } ?: appsFocus,
                            Modifier.align(Alignment.TopStart).graphicsLayer { alpha = fade() },
                            { f -> if (f) inPart = Part.Hero; onHeroFocus(f) }) { cat ->
                            app.requestSettings.intValue = cat; app.requestSettingsRev.intValue++
                        }
                    }
                }
                @Composable fun section(part: Part, title: String, note: String = "", content: @Composable () -> Unit) {
                    Column(
                        Modifier.fillMaxWidth().padding(top = (ROW_GAP * s).dp)
                            .onGloballyPositioned { tops[part] = it.positionInParent().y to it.positionInParent().y + it.size.height }
                            .onFocusChanged { if (it.hasFocus) inPart = part },
                    ) {
                        Row(Modifier.padding(start = (EDGE * s).dp, bottom = (6 * s).dp), verticalAlignment = Alignment.Bottom) {
                            Text(title, color = Color.White, fontSize = (12.5f * s).sp, fontWeight = FontWeight.Bold)
                            if (note.isNotBlank()) {
                                Spacer(Modifier.width((8 * s).dp))
                                Text(note, color = Color.White.copy(alpha = 0.5f), fontSize = (7.5f * s).sp, modifier = Modifier.padding(bottom = (1.5f * s).dp))
                            }
                        }
                        CompositionLocalProvider(LocalBringIntoViewSpec provides defaultSpec) { content() }
                    }
                }
                // ── Airing this week
                if (showAiring) section(Part.Airing, "Airing this week", "New episodes of shows in your library") {
                    CardRow(s, req(Part.Airing), above(Part.Airing), below(Part.Airing), airing.map { it.item.key + ":" + it.episode.season + ":" + it.episode.episode }) { i, mod ->
                        val a = airing[i]
                        AiringCard(a, s, mod, onFocused = { selected = a.item }, onOpen = { NuvioLaunch.open(ctx, settings, app.http, a.item) },
                            onHold = { onDialog(titleMenu(app, ctx, a.item)) })
                    }
                }
                // ── Continue watching
                if (watchItems.isNotEmpty()) section(Part.Watch, "Continue watching") {
                    CardRow(s, req(Part.Watch), above(Part.Watch), below(Part.Watch), watchItems.map { it.key }) { i, mod ->
                        val item = watchItems[i]
                        WatchCard(item, newEps[item.key] ?: 0, s, mod, onFocused = { selected = if (LiveCards.isLive(item)) null else item },
                            onOpen = { if (LiveCards.isLive(item)) LiveCards.open(ctx, item) else NuvioLaunch.open(ctx, settings, app.http, item) },
                            onHold = { watchMenu(app, ctx, scope, item, onDialog) })
                    }
                }
                // ── your library
                @Composable fun posters(part: Part, title: String, list: List<HeroItem>) = section(part, title) {
                    CardRow(s, req(part), above(part), below(part), list.map { it.key } + "__all__") { i, mod ->
                        if (i == list.size) SeeAllCard(s, mod, onLibrary)
                        else {
                            val item = list[i]
                            PosterCard(app.shelf.filled(item), s, settings.homeExpand, mod, onFocused = { selected = item },
                                onOpen = { NuvioLaunch.open(ctx, settings, app.http, item) },
                                onHold = { onDialog(titleMenu(app, ctx, item)) })
                        }
                    }
                }
                if (showMovies) posters(Part.Movies, "Movies in your library", movies)
                if (showSeries) posters(Part.Series, "Series in your library", series)
                // ── apps
                section(Part.Apps, "Apps") {
                    AppsGrid(app, s, settings.appsPerRow, appsFocus, above(Part.Apps), folderFocus, tileFocus, movingPkg, onMoveKey, onAppMenu, onFolder, onFolderMenu,
                        onRow = { r, top, bottom -> appRows[r] = (top + offset.value) to (bottom + offset.value) }, onFocusRow = { appRow = it })
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
    // A card new at the front (Live TV's channel just watched): the row stays on the old first one by itself, half of
    // the new one cut off at the edge. Not in the row, it shows from the start.
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(keys.firstOrNull()) {
        if (!inside && state.firstVisibleItemIndex <= 1) runCatching { state.scrollToItem(0) }
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
    app: GlassApp, item: HeroItem, s: Float, saved: Boolean, heroFocus: FocusRequester, down: FocusRequester,
    onFocus: (Boolean) -> Unit, onPrev: () -> Unit, onNext: () -> Unit, onPlay: () -> Unit, onSave: () -> Unit, onArt: () -> Unit,
    modifier: Modifier,
) {
    val settings = app.settings
    val saveFocus = remember { FocusRequester() }
    val back = backKey()
    var onPlayBtn by remember { mutableStateOf(false) }
    var onSaveBtn by remember { mutableStateOf(false) }
    LaunchedEffect(onPlayBtn || onSaveBtn) { onFocus(onPlayBtn || onSaveBtn) }
    Column(modifier.padding(start = (EDGE * s).dp, bottom = (6 * s).dp).width((330 * s).dp)) {
        AnimatedContent(
            targetState = item, contentKey = { it.key },
            transitionSpec = { fadeIn(tween(settings.transitionMs)) togetherWith fadeOut(tween((settings.transitionMs * 0.4f).toInt())) },
            label = "heroText",
        ) { it ->
            Column {
                // A small kind line over the logo ("SERIES", "FILM"), then the logo.
                Text(if (it.type.equals("movie", true)) "FILM" else "SERIES", color = RED, fontSize = (7.5f * s).sp,
                    fontWeight = FontWeight.Bold, letterSpacing = (1.5f * s).sp)
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
        Spacer(Modifier.height((12 * s).dp))
        // Play, and + to save it to your Library (✓ once it's there). ◀ on Play and ▶ on + change the title.
        Row(verticalAlignment = Alignment.CenterVertically) {
            HeroButton("Play", true, s, onPlayBtn,
                Modifier.focusRequester(heroFocus).focusProperties { this.down = down; right = saveFocus }
                    .onFocusChanged { onPlayBtn = it.isFocused }
                    .tvPress(onLong = onArt, onKey = { ev -> if (ev.key == Key.DirectionLeft && ev.type == KeyEventType.KeyDown) { onPrev(); true } else back(ev) }, onClick = onPlay))
            Spacer(Modifier.width((8 * s).dp))
            HeroButton(if (saved) "In Library" else "My Library", false, s, onSaveBtn,
                Modifier.focusRequester(saveFocus).focusProperties { this.down = down; left = heroFocus }
                    .onFocusChanged { onSaveBtn = it.isFocused }
                    .tvPress(onKey = { ev -> if (ev.key == Key.DirectionRight && ev.type == KeyEventType.KeyDown) { onNext(); true } else back(ev) }, onClick = onSave),
                saved = saved)
        }
    }
}

/** Play (white, with its triangle) or + My Library (grey, ✓ when saved); the selected one grows and lights up. */
@Composable
private fun HeroButton(label: String, play: Boolean, s: Float, focused: Boolean, modifier: Modifier, saved: Boolean = false) {
    val sc by animateFloatAsState(if (focused) 1.07f else 1f, spring(0.7f, 700f), label = "heroBtn")
    val bg = when {
        focused -> Color.White
        play -> Color.White.copy(alpha = 0.88f)
        else -> Color(0xFF6D6D6E).copy(alpha = 0.7f)
    }
    val fg = if (focused || play) Color.Black else Color.White
    Row(
        modifier.graphicsLayer { scaleX = sc; scaleY = sc }.clip(RoundedCornerShape((4 * s).dp)).background(bg)
            .padding(horizontal = (12 * s).dp, vertical = (5.5f * s).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (play) PlayGlyph(Modifier.size((8.5f * s).dp), fg)
        else Canvas(Modifier.size((9 * s).dp)) {
            val w = size.width; val h = size.height
            val st = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.14f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
            val p = androidx.compose.ui.graphics.Path()
            if (saved) { p.moveTo(w * 0.14f, h * 0.52f); p.lineTo(w * 0.40f, h * 0.78f); p.lineTo(w * 0.88f, h * 0.24f) }
            else { p.moveTo(w * 0.5f, h * 0.1f); p.lineTo(w * 0.5f, h * 0.9f); p.moveTo(w * 0.1f, h * 0.5f); p.lineTo(w * 0.9f, h * 0.5f) }
            drawPath(p, fg, style = st)
        }
        Spacer(Modifier.width((6 * s).dp))
        Text(label, color = fg, fontSize = (10 * s).sp, fontWeight = FontWeight.SemiBold)
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
private fun ImdbBadge(rating: String, s: Float, small: Boolean = false) {
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
private fun backKey(): (KeyEvent) -> Boolean {
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
private fun Modifier.cardFocus(s: Float, focused: Boolean, radius: Float = 6f, grow: Float = 1.06f): Modifier {
    val sc by animateFloatAsState(if (focused) grow else 1f, spring(0.75f, 600f), label = "cardScale")
    val shape = RoundedCornerShape((radius * s).dp)
    return this.zIndex(if (focused) 1f else 0f).graphicsLayer { scaleX = sc; scaleY = sc }
        .border(if (focused) (1.6f * s).dp else 0.dp, if (focused) Color.White else Color.Transparent, shape).clip(shape)
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
            Text(a.item.name, color = Color.White, fontSize = (7.5f * s).sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val line = listOfNotNull("S${e.season} · E${e.episode}", e.title.takeIf { it.isNotBlank() }, if (a.more > 0) "+${a.more} more" else null).joinToString(" · ")
            Text(line, color = Color.White.copy(alpha = 0.75f), fontSize = (6 * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(airDay(e.released, now), color = Color.White.copy(alpha = 0.75f), fontSize = (6 * s).sp)
                Spacer(Modifier.width((3 * s).dp))
                Text(if (e.released <= now) "Out now" else "in " + airIn(e.released, now), color = if (e.released <= now) GREEN else Color(0xFFFF4B4B),
                    fontSize = (6.5f * s).sp, fontWeight = FontWeight.Bold)
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

/** Continue watching: the picture with its logo and progress; NEW EPISODE and +N when newer episodes are out. */
@Composable
private fun WatchCard(item: HeroItem, newer: Int, s: Float, modifier: Modifier, onFocused: () -> Unit, onOpen: () -> Unit, onHold: () -> Unit) {
    val ctx = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    val live = LiveCards.isLive(item)
    Box(
        modifier.width((WATCH_W * s).dp).height((WATCH_W * 9 / 16 * s).dp)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .tvPress(onLong = onHold, onKey = backKey(), onClick = onOpen)
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
        if (live && item.type != LiveCards.TYPE_RECORDING) Text("LIVE", color = Color.White, fontSize = (6.5f * s).sp, fontWeight = FontWeight.Bold,
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
        // The title: its logo, else its name.
        if (!logoOnly) {
            if (item.logo.isNotBlank()) AsyncImage(remember(item.logo) { req(ctx, item.logo, 300, 100) }, item.name, contentScale = ContentScale.Fit, alignment = Alignment.BottomStart,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = (8 * s).dp, bottom = (12 * s).dp).size(width = (82 * s).dp, height = (26 * s).dp))
            else Text(item.name, color = Color.White, fontSize = (8.5f * s).sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = (8 * s).dp, bottom = (12 * s).dp, end = (8 * s).dp))
        }
        // Selected: where you are (S2 · E5 · 40% left).
        if (focused) {
            val where = whereText(item)
            if (where.isNotBlank() || item.country.isNotEmpty()) Row(Modifier.align(Alignment.BottomEnd).padding(end = (8 * s).dp, bottom = (12 * s).dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(where, color = Color.White.copy(alpha = 0.9f), fontSize = (6.5f * s).sp, fontWeight = FontWeight.Medium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                // A Live TV card's channel country (US, UK…).
                if (item.country.isNotEmpty()) { Spacer(Modifier.width((4 * s).dp)); CountryTag(item.country, s * 0.9f) }
            }
        }
        if (newer > 0) Text("NEW EPISODE", color = Color.White, fontSize = (5.5f * s).sp, fontWeight = FontWeight.Black,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = (5 * s).dp).clip(RoundedCornerShape((1.5f * s).dp)).background(RED).padding(horizontal = (4 * s).dp))
        if (item.progress >= 0f) {
            val p = item.progress.coerceIn(0f, 1f)
            Canvas(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height((2.5f * s).dp)) {
                drawRect(Color.White.copy(alpha = 0.25f))
                drawRect(RED, size = Size(size.width * p, size.height))
            }
        }
    }
}

/** "S10 · E12 · 23% left" (a series), "Movie · 40% left", a live card's own line. */
private fun whereText(item: HeroItem): String {
    if (LiveCards.isLive(item)) return item.sub
    if (item.progress < 0f) return item.sub
    val left = (100 - Math.round(item.progress.coerceIn(0f, 1f) * 100)).coerceIn(1, 100)
    val what = item.sub.ifBlank { if (item.type.equals("movie", true)) "Movie" else "" }
    return listOf(what, "$left% left").filter { it.isNotBlank() }.joinToString("  ·  ")
}

/**
 * A poster with its IMDb rating. Rested on, it opens up into a wide card: the picture with the logo on top, then
 * Play / + / more, the rating, year and runtime, and the genres (the posters beside it make room).
 */
@Composable
private fun PosterCard(item: HeroItem, s: Float, expand: Boolean, modifier: Modifier, onFocused: () -> Unit, onOpen: () -> Unit, onHold: () -> Unit) {
    val ctx = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(focused, expand) {
        if (!focused || !expand) { open = false; return@LaunchedEffect }
        delay(650)
        open = true
    }
    val w by animateDpAsState(((if (open) POSTER_W * OPEN_W else POSTER_W) * s).dp, tween(260, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)), label = "posterW")
    val h = (POSTER_W * 1.5f * s).dp
    Box(
        modifier.width(w).height(h)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .tvPress(onLong = onHold, onKey = backKey(), onClick = onOpen)
            .cardFocus(s, focused, 5f, if (open) 1.03f else 1.06f)
            .background(CARD),
    ) {
        val openA by animateFloatAsState(if (open) 1f else 0f, tween(220), label = "posterOpen")
        if (item.poster.isNotBlank()) AsyncImage(remember(item.poster) { req(ctx, item.poster, 300, 450) }, item.name, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 1f - openA })
        else Text(item.name, color = Color.White.copy(alpha = 0.7f), fontSize = (8 * s).sp, modifier = Modifier.align(Alignment.Center).padding((8 * s).dp))
        if (item.rating.isNotBlank() && !open) Box(Modifier.align(Alignment.TopStart).padding((4 * s).dp).clip(RoundedCornerShape((2 * s).dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = (2 * s).dp, vertical = (1 * s).dp)) {
            ImdbBadge(item.rating, s, small = true)
        }
        if (openA > 0.01f) Column(Modifier.fillMaxSize().graphicsLayer { alpha = openA }) {
            Box(Modifier.fillMaxWidth().height((POSTER_W * OPEN_W * 9 / 16 * s).dp)) {
                val pic = item.background.ifBlank { item.poster }
                AsyncImage(remember(pic) { req(ctx, pic, 480, 270) }, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to CARD)))
                if (item.logo.isNotBlank()) AsyncImage(remember(item.logo) { req(ctx, item.logo, 300, 100) }, item.name, contentScale = ContentScale.Fit, alignment = Alignment.BottomStart,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = (6 * s).dp, bottom = (3 * s).dp).size(width = (80 * s).dp, height = (22 * s).dp))
                else Text(item.name, color = Color.White, fontSize = (9 * s).sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = (6 * s).dp, bottom = (3 * s).dp))
            }
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
    app: GlassApp, s: Float, perRow: Int, appsFocus: FocusRequester, up: FocusRequester, folderFocus: FocusRequester, tileFocus: HashMap<String, FocusRequester>,
    movingPkg: String?, onMoveKey: (KeyEvent) -> Boolean, onMenu: (AppEntry) -> Unit, onFolder: () -> Unit, onFolderMenu: () -> Unit,
    onRow: (Int, Float, Float) -> Unit, onFocusRow: (Int) -> Unit,
) {
    val ctx = LocalContext.current
    val settings = app.settings
    val allApps by app.apps.apps.collectAsState()
    val apps = remember(allApps, settings.dockOrder, settings.hiddenApps, settings.showMobileApps) { app.apps.ordered() }
    val tiles: List<AppEntry?> = remember(apps, settings.folderPos, settings.showFolder) {
        val p = settings.folderPos.let { if (it < 0 || it > apps.size) apps.size else it }
        apps.toMutableList<AppEntry?>().apply { if (settings.showFolder) add(p, null) }
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
                                .focusProperties { if (r == 0) this.up = up; if (r == rows.lastIndex) down = FocusRequester.Cancel }
                                .onFocusChanged { if (it.isFocused) { last = k; onFocusRow(r) } }
                            if (e == null) AppTile(app, null, s, tileW, movingPkg == FOLDER_KEY, base.focusRequester(folderFocus), onMoveKey, onFolder, onFolderMenu)
                            else AppTile(app, e, s, tileW, movingPkg == e.pkg, base.focusRequester(tileFocus.getOrPut(e.pkg) { FocusRequester() }), onMoveKey,
                                { app.apps.launchIntent(e)?.let { runCatching { ctx.startActivity(it) } } }, { onMenu(e) })
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
private fun titleMenu(app: GlassApp, ctx: android.content.Context, item: HeroItem): DialogState {
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
