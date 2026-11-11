package com.glasslauncher.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.glasslauncher.tv.GlassApp
import com.glasslauncher.tv.data.HeroItem
import com.glasslauncher.tv.data.HeroPicks
import com.glasslauncher.tv.data.NuvioLaunch
import com.glasslauncher.tv.data.StreamLinks
import com.glasslauncher.tv.data.TitleDetails
import com.glasslauncher.tv.data.TitlePeople
import com.glasslauncher.tv.live.LiveHome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A title's own page (More info). Its first screen, as on the streaming apps: the picture across the whole screen, the
 * title at the top left, round buttons and a large Play at the bottom left, the story in the middle and the people at the
 * right. Its trailer plays there by itself with sound after a few seconds (Settings › Home › Trailer on More info), and a
 * few seconds later the page steps aside for it, Play alone at the bottom left. Down: a series' seasons and episodes,
 * where to watch (Live TV, your recordings, its other apps), the cast with their other titles, More like this.
 * [onOpen] goes to another title's page.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TitlePage(app: GlassApp, item: HeroItem, s: Float, onClose: () -> Unit, onOpen: (HeroItem) -> Unit, onDialog: (DialogState) -> Unit = {}) {
    val ctx = LocalContext.current
    val st = app.settings
    BackHandler { onClose() }
    val details by produceState(TitleDetails.NONE, item.key) { value = withContext(Dispatchers.IO) { TitleDetails.load(app.http, item) } }
    LaunchedEffect(item.key) { if (st.tmdbToken.isNotBlank()) withContext(Dispatchers.IO) { runCatching { app.catalogs.enrich(item) } } }
    val enriched by app.catalogs.enrichedItems.collectAsState()
    val rich = enriched[item.key] ?: item
    val shown = rich.copy(
        overview = rich.overview.ifBlank { details.overview }, background = rich.background.ifBlank { details.background }, logo = rich.logo.ifBlank { details.logo },
        rating = rich.rating.ifBlank { details.rating }, year = rich.year.ifBlank { details.year }, genres = rich.genres.ifEmpty { details.genres }, runtime = rich.runtime.ifBlank { details.runtime },
    )
    val cw by app.cw.items.collectAsState()
    val resume = cw.firstOrNull { it.key == item.key }
    val canPlay = remember(item.key) { NuvioLaunch.playable(ctx, st) }
    val trailerIds by produceState<String?>(null, item.key) { value = withContext(Dispatchers.IO) { trailerOf(app, item) } }
    val links by produceState(emptyList<StreamLinks.Link>(), item.key) {
        value = withContext(Dispatchers.IO) { runCatching { StreamLinks.forTitle(ctx, app.http, item.id, item.name, shown.platform) }.getOrDefault(emptyList()) }
    }
    val saved by app.library.saved.collectAsState()
    val inLibrary = saved.any { it.key == item.key }
    val kids = st.kidsMode
    var trailerOn by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val iconsReq = remember { FocusRequester() }
    val linkReq = remember { FocusRequester() }
    val scroll = rememberScrollState()
    // The trailer on the page (not the Trailer button's, which fills the screen): after a few seconds at the top of the
    // page, with sound; gone once the page is scrolled, another app opens it, or it ends.
    // Down from the first screen: the details layout (Prime Video's), its panel at the top following the selection.
    var inDetails by remember(item.key) { mutableStateOf(false) }
    var panel by remember(item.key) { mutableStateOf<PanelInfo?>(null) }
    val atTop = !inDetails
    // Its own setting, not Home's Trailers (which is about the big picture): still never on a TV where trailers keep failing.
    val autoOk = st.titleTrailer && TrailerGuard.allowed(ctx, if (st.trailerMode == "on") "on" else "auto")
    var inline by remember(item.key) { mutableStateOf(false) }
    var inlinePlaying by remember(item.key) { mutableStateOf(false) }
    LaunchedEffect(trailerIds, autoOk, atTop, trailerOn) {
        if (!atTop || trailerOn) { inline = false; inlinePlaying = false }
        if (autoOk && trailerIds != null && atTop && !trailerOn && !inline) { delay(TITLE_TRAILER_AFTER_MS); inline = true }
    }
    val inlineShown = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(inline, inlinePlaying) { inlineShown.animateTo(if (inline && inlinePlaying) 1f else 0f, androidx.compose.animation.core.tween(700)) }
    // Cinematic: a few seconds into it, untouched, everything but Play fades away; the next button only brings it back.
    var cinematic by remember(item.key) { mutableStateOf(false) }
    var swallowUp by remember { mutableStateOf(false) }
    LaunchedEffect(inlinePlaying, app.interaction.longValue, atTop) {
        cinematic = false
        if (inlinePlaying && atTop) { delay(TITLE_CINEMATIC_AFTER_MS); cinematic = true; runCatching { first.requestFocus() } }
    }
    // Coming in: the picture first, then each block of words rising into place a moment apart (none with Reduce motion).
    val reduce = LocalReduceMotion.current
    val enter = remember(item.key) { androidx.compose.animation.core.Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(item.key) { enter.animateTo(1f, androidx.compose.animation.core.tween(700, easing = androidx.compose.animation.core.LinearEasing)) }
    val rise = with(androidx.compose.ui.platform.LocalDensity.current) { 20.dp.toPx() }
    fun Modifier.wordsIn(delay: Float): Modifier = graphicsLayer {
        val t = ((enter.value - delay) / (1f - delay)).coerceIn(0f, 1f)
        val e = 1f - (1f - t) * (1f - t) * (1f - t)
        alpha *= e; translationY = (1f - e) * rise
    }
    val easeOut = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
    val detailsA by androidx.compose.animation.core.animateFloatAsState(if (inDetails) 1f else 0f,
        androidx.compose.animation.core.tween(if (reduce) 150 else 380, easing = easeOut), label = "details")
    // The picture dims as the details come in; going back up, it brightens last.
    val dimA by androidx.compose.animation.core.animateFloatAsState(if (inDetails) 0.74f else 0f,
        androidx.compose.animation.core.tween(if (inDetails) 320 else 450, delayMillis = if (inDetails || reduce) 0 else 180), label = "dim")
    val uiA by androidx.compose.animation.core.animateFloatAsState(if (cinematic) 0f else 1f,
        androidx.compose.animation.core.tween(if (cinematic) 900 else 250), label = "cinematic")
    val hasSeasons = details.seasons.isNotEmpty() && !item.type.equals("movie", true)
    val seasonEntry = remember { RowEntry() }
    val epEntry = remember { RowEntry() }
    val moreEntry = remember { RowEntry() }
    val watchEntry = remember { RowEntry() }
    val castEntry = remember { RowEntry() }
    val withEntry = remember { RowEntry() }
    val scope = rememberCoroutineScope()
    val full = st.titleRich
    val airings by produceState(emptyList<LiveHome.Airing>(), item.key, full) {
        // This title itself, not an episode of another show that happens to share its name (The Assassin, a T J Hooker).
        value = if (!full) emptyList() else withContext(Dispatchers.IO) {
            runCatching { LiveHome.airingsOf(ctx, item.name, 8) }.getOrDefault(emptyList()).filter { it.title.trim().equals(item.name.trim(), ignoreCase = true) }
        }
    }
    val recorded by produceState(emptyList<LiveHome.Airing>(), item.key, full) {
        value = if (!full) emptyList() else withContext(Dispatchers.IO) { runCatching { LiveHome.recordingsOf(ctx, item.name, 8) }.getOrDefault(emptyList()) }
    }
    val people by produceState(TitlePeople.NONE, item.key, full) {
        value = if (!full) TitlePeople.NONE else withContext(Dispatchers.IO) { app.recs.people(item) }
    }
    // OK on someone in the cast: their other titles, in a row under it.
    var person by remember(item.key) { mutableStateOf<TitlePeople.Person?>(null) }
    val theirs by produceState(emptyList<HeroItem>(), person) {
        val p = person
        value = emptyList()
        if (p != null) {
            val got = withContext(Dispatchers.IO) { app.recs.titlesOf(p.id) }.filter { it.key != item.key }.let { l -> if (kids) l.filter { HeroPicks.isKids(it) } else l }
            value = got
            if (got.isEmpty()) toast(ctx, "No other titles found for ${p.name}")
        }
    }
    LaunchedEffect(theirs) { if (theirs.isNotEmpty()) { delay(120); runCatching { withEntry.req.requestFocus() } } }
    LaunchedEffect(item.key, canPlay || links.isNotEmpty()) {
        delay(120)
        runCatching { (if (canPlay || links.isNotEmpty()) first else iconsReq).requestFocus() }
        // The system may have put the focus on a row lower down first, scrolling there: the page starts at its top.
        scroll.scrollTo(0)
    }
    val more by produceState(emptyList<HeroItem>(), item.key) {
        value = withContext(Dispatchers.IO) {
            val like = app.recs.similar(item).ifEmpty {
                // No TMDB: what the catalogs have in the same genres.
                val g = shown.genres.map { it.lowercase() }.toSet()
                (app.catalogs.items.value + app.recs.rows.value.films + app.recs.rows.value.series)
                    .filter { it.key != item.key && it.genres.any { x -> x.lowercase() in g } }.distinctBy { it.key }.take(12)
            }
            if (kids) like.filter { HeroPicks.isKids(it) } else like
        }
    }

    // Where to watch: what's on now, your recordings, its other apps, then what's on later.
    val nowTime = remember(airings) { System.currentTimeMillis() }
    val onNow = airings.filter { it.onNow(nowTime) }
    val later = airings.filterNot { it.onNow(nowTime) }
    val otherApps = if (full) links.drop(1) else emptyList()
    val anywhere = onNow.size + recorded.size + otherApps.size + later.size
    // The rows top to bottom, for Up and Down: each goes to the one beside it that's there.
    val primary = canPlay || links.isNotEmpty()
    val stops = buildList {
        add(iconsReq)
        if (primary) add(first)
        if (canPlay && links.isNotEmpty()) add(linkReq)
        if (hasSeasons) { add(seasonEntry.req); add(epEntry.req) }
        if (anywhere > 0) add(watchEntry.req)
        if (people.cast.isNotEmpty()) add(castEntry.req)
        if (theirs.isNotEmpty()) add(withEntry.req)
        if (more.isNotEmpty()) add(moreEntry.req)
    }
    fun above(r: FocusRequester) = stops.getOrNull(stops.indexOf(r) - 1) ?: FocusRequester.Cancel
    fun below(r: FocusRequester) = stops.getOrNull(stops.indexOf(r) + 1) ?: FocusRequester.Cancel

    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(Ds.Bg).focusProperties { exit = { FocusRequester.Cancel } }.focusGroup()
        .onPreviewKeyEvent { ev ->
            val down = ev.type == androidx.compose.ui.input.key.KeyEventType.KeyDown
            when {
                // Cinematic: OK on Play plays; any other button brings the page back, and does nothing else.
                cinematic && ev.isSelect -> false
                cinematic && down -> { cinematic = false; swallowUp = true; true }
                swallowUp && !down -> { swallowUp = false; true }
                else -> false
            }
        }) {
        val screenH = maxHeight
        // The trailer, under the picture that fades away once it plays (the TV's video plane shows through).
        if (inline) trailerIds?.let { ids ->
            HeroTrailer(ids, { inlineShown.value }, onPlaying = { inlinePlaying = true }, onWaiting = { inlinePlaying = false },
                muted = false, shade = { uiA }) { inline = false; inlinePlaying = false }
        }
        val pic = shown.background.ifBlank { shown.poster }
        if (pic.isNotBlank()) AsyncImage(
            remember(pic) { ImageRequest.Builder(ctx).data(pic).size(1920, 1080).build() }, null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = (1f - inlineShown.value) * (enter.value * 2.5f).coerceAtMost(1f) },
        )
        // The shades the words need; the darker the further down the page, so the rows read as on Home.
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = uiA }.background(Brush.horizontalGradient(0f to Ds.Bg, 0.24f to Ds.Bg.copy(alpha = 0.82f), 0.5f to Ds.Bg.copy(alpha = 0.3f), 0.7f to Color.Transparent)))
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = uiA }.background(Brush.verticalGradient(0.45f to Color.Transparent, 0.75f to Ds.Bg.copy(alpha = 0.82f), 1f to Ds.Bg)))
        // Cinematic: only a soft corner behind Play.
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - uiA }.background(Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent),
            center = androidx.compose.ui.geometry.Offset(0f, Float.POSITIVE_INFINITY), radius = 900f)))
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = dimA }.background(Ds.Bg))
        val rise = with(androidx.compose.ui.platform.LocalDensity.current) { 40.dp.toPx() }
        val screenPx = with(androidx.compose.ui.platform.LocalDensity.current) { screenH.toPx() }

            // ── The first screen: up and away as the details come in, back down on Up from their first row.
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - detailsA; if (!reduce) translationY = -detailsA * screenPx * 0.3f }
                .onFocusChanged { if (it.hasFocus) inDetails = false }) {
                Column(Modifier.padding(start = (EDGE * s).dp, top = (22 * s).dp).width((330 * s).dp).graphicsLayer { alpha = uiA }.wordsIn(0.2f)) {
                    val kind = if (shown.type.equals("movie", true)) "FILM" else "SERIES"
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(kind, color = LocalAccent.current, fontSize = (7.5f * s).sp, fontWeight = FontWeight.Bold, letterSpacing = (1.5f * s).sp)
                        if (inLibrary) Text("  ·  " + Lang.tr("IN YOUR LIBRARY"), color = Color(0xFFD0D0D4), fontSize = (7.5f * s).sp, fontWeight = FontWeight.Bold, letterSpacing = (1.5f * s).sp)
                    }
                    Spacer(Modifier.height((5 * s).dp))
                    Title(shown, st.titleMode.takeIf { it != "hidden" } ?: "text", st.titleScale * 0.95f, s)
                }

                // Bottom left: round buttons, then the large Play (or the app it's on), what's left of it, its app.
                Column(Modifier.align(Alignment.BottomStart).padding(start = (EDGE * s).dp, bottom = (26 * s).dp).width((196 * s).dp).wordsIn(0.3f),
                    verticalArrangement = Arrangement.spacedBy((8 * s).dp)) {
                    var label by remember { mutableStateOf("") }
                    Column(Modifier.graphicsLayer { alpha = uiA }) {
                        Row(horizontalArrangement = Arrangement.spacedBy((7 * s).dp)) {
                            val icons = buildList<Triple<String, String, () -> Unit>> {
                                add(Triple(if (inLibrary) "check" else "plus", if (inLibrary) "In Library" else "My Library") {
                                    val now = app.library.toggle(item)
                                    toast(ctx, if (now) "Saved to Library · ${item.name}" else "Removed from Library · ${item.name}")
                                })
                                if (trailerIds != null) add(Triple("trailer", "Trailer") { trailerOn = true })
                                if (!kids && st.homeMine) add(Triple("stack", "Add to a collection") { onDialog(collectionMenu(app, ctx, scope, item, onDialog)) })
                                if (!kids) add(Triple("ban", "Not interested") {
                                    st.heroHidden = (st.heroHidden + item.key).distinct()
                                    toast(ctx, "${item.name} won't show on the big picture again (Settings › Home to bring it back)")
                                    onClose()
                                })
                            }
                            icons.forEachIndexed { i, (icon, name, act) ->
                                RoundIcon(icon, name, s, (if (i == 0) Modifier.focusRequester(iconsReq) else Modifier)
                                    .nav(up = FocusRequester.Cancel, down = below(iconsReq), left = edge(i == 0), right = edge(i == icons.lastIndex)),
                                    onFocus = { f -> if (f) label = name else if (label == name) label = "" }, onClick = act)
                            }
                        }
                        Text(label.ifBlank { " " }, color = Color.White, fontSize = (8 * s).sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = (4 * s).dp))
                    }
                    if (canPlay) BigPlay(
                        if (resume != null) (if (resume.upNext) "Play" else "Resume") + (resume.sub.takeIf { it.isNotBlank() }?.let { " $it" } ?: "") else "Play",
                        s, Modifier.focusRequester(first).nav(up = above(first), down = below(first), left = FocusRequester.Cancel, right = FocusRequester.Cancel),
                    ) { NuvioLaunch.play(ctx, st, app.http, resume ?: item) }
                    else links.firstOrNull()?.let { l ->
                        BigPlay(l.service, s, Modifier.focusRequester(first).nav(up = above(first), down = below(first), left = FocusRequester.Cancel, right = FocusRequester.Cancel)) {
                            runCatching { ctx.startActivity(l.intent()) }.onFailure { toast(ctx, "${l.service} couldn't open it") }
                        }
                    }
                    Column(Modifier.graphicsLayer { alpha = uiA }, verticalArrangement = Arrangement.spacedBy((8 * s).dp)) {
                        if (resume != null && resume.progress in 0.01f..0.99f) Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f).height((2 * s).dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.25f))) {
                                Box(Modifier.fillMaxWidth(resume.progress).height((2 * s).dp).background(LocalAccent.current))
                            }
                        }
                        if (canPlay) links.firstOrNull()?.let { l ->
                            DsButton(l.service, s, Modifier.fillMaxWidth().focusRequester(linkReq).nav(up = above(linkReq), down = below(linkReq), left = FocusRequester.Cancel, right = FocusRequester.Cancel),
                                leading = { ServiceIcon(l.pkg, s) }) { runCatching { ctx.startActivity(l.intent()) }.onFailure { toast(ctx, "${l.service} couldn't open it") } }
                        }
                    }
                }

                // The story, in the middle.
                Column(Modifier.align(Alignment.BottomStart).padding(start = ((EDGE + 222) * s).dp, bottom = (30 * s).dp).width((265 * s).dp).graphicsLayer { alpha = uiA }.wordsIn(0.38f),
                    verticalArrangement = Arrangement.spacedBy((6 * s).dp)) {
                    val nextAiring = onNow.firstOrNull() ?: later.firstOrNull()
                    if (nextAiring != null) Text((if (nextAiring.onNow(nowTime)) Lang.tr("ON NOW") else Lang.tr("ON LIVE TV")) + "  ·  " + nextAiring.channelName.uppercase(),
                        color = Ds.Ink, fontSize = (7f * s).sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (0.5f * s).sp, maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape((3 * s).dp)).background(Color.White).padding(horizontal = (5 * s).dp, vertical = (2 * s).dp))
                    if (shown.overview.isNotBlank()) Text(shown.overview, color = Color(0xFFECECEF), fontSize = (9.5f * s).sp, lineHeight = (14 * s).sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    val facts = shown.genres.take(3) + listOf(shown.runtime, shown.year).filter { it.isNotBlank() }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((6 * s).dp)) {
                        if (shown.rating.isNotBlank()) ImdbBadge(shown.rating, s)
                        Text(facts.joinToString("  ·  "), color = Color(0xFFB9B9C0), fontSize = (8.5f * s).sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }

                // The people, at the right.
                Column(Modifier.align(Alignment.BottomEnd).padding(end = (EDGE * s).dp, bottom = (30 * s).dp).width((150 * s).dp).graphicsLayer { alpha = uiA }.wordsIn(0.46f),
                    verticalArrangement = Arrangement.spacedBy((5 * s).dp)) {
                    val cast = people.cast.take(4).map { it.name }.ifEmpty { details.cast.take(4) }
                    if (people.makers.isNotEmpty()) Text((if (shown.type.equals("movie", true)) Lang.tr("Directed by") + " " else Lang.tr("Created by") + " ") + people.makers.joinToString(", "),
                        color = Text2, fontSize = (8 * s).sp, lineHeight = (11 * s).sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (cast.isNotEmpty()) Text(Lang.tr("Cast") + ": " + cast.joinToString(", "), color = Text2, fontSize = (8 * s).sp, lineHeight = (11 * s).sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }

                if (stops.size > (if (primary) 2 else 1) + (if (canPlay && links.isNotEmpty()) 1 else 0)) Row(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = (7 * s).dp).graphicsLayer { alpha = uiA * 0.6f },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val next = when { hasSeasons -> Lang.tr("Episodes"); anywhere > 0 -> Lang.tr("Where to watch"); people.cast.isNotEmpty() -> Lang.tr("Cast"); else -> Lang.tr("More like this") }
                    Text("⌄  " + next, color = Color.White, fontSize = (8 * s).sp, fontWeight = FontWeight.Bold)
                }
            }

            // ── The details: a panel at the top about what's selected, the rows under it, the one in use always just
            // under the panel and its selection at the left (Prime Video's).
            val panelH = screenH * 0.36f
            val headroom = with(androidx.compose.ui.platform.LocalDensity.current) { (26 * s).dp.toPx() }
            val edgePx = with(androidx.compose.ui.platform.LocalDensity.current) { (EDGE * s).dp.toPx() }
            val pinTop = remember(headroom) { PinAt(headroom) }
            val pinLeft = remember(edgePx) { PinAt(edgePx) }
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = detailsA; if (!reduce) translationY = (1f - detailsA) * rise }) {
                DetailPanel(panel ?: PanelInfo(shown.key, shown.name, item = shown, body = shown.overview), s, Modifier.height(panelH))
                Box(Modifier.fillMaxSize().padding(top = panelH)) {
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides pinTop) {
                Column(Modifier.fillMaxSize().onFocusChanged { if (it.hasFocus) inDetails = true }.verticalScroll(scroll).padding(bottom = screenH * 0.5f)) {
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides pinLeft) {

            if (hasSeasons) {
                val now = remember { System.currentTimeMillis() }
                val fromResume = remember(resume?.sub) { Regex("S(\\d+)").find(resume?.sub.orEmpty())?.groupValues?.get(1)?.toIntOrNull() }
                val epNow = remember(resume?.sub) { Regex("E(\\d+)").find(resume?.sub.orEmpty())?.groupValues?.get(1)?.toIntOrNull() }
                var season by remember(item.key) { mutableIntStateOf(fromResume ?: -1) }
                val seasonOn = season.takeIf { it in details.seasons } ?: details.seasons.keys.first()
                Spacer(Modifier.height((14 * s).dp))
                Row(Modifier.padding(start = (EDGE * s).dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Episodes", color = Color.White, fontSize = (12.5f * s).sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width((10 * s).dp))
                    LazyRow(state = seasonEntry.state, horizontalArrangement = Arrangement.spacedBy((3 * s).dp)) {
                        val seasonKeys = details.seasons.keys.toList()
                        itemsIndexed(seasonKeys, key = { _, n -> n }) { i, n ->
                            var f by remember { mutableStateOf(false) }
                            Text(
                                if (n == 0) "Specials" else "Season $n", color = if (f) Ds.Ink else if (n == seasonOn) Color.White else Text2, fontSize = (9.5f * s).sp,
                                fontWeight = if (f || n == seasonOn) FontWeight.Bold else FontWeight.SemiBold,
                                // The focus coming onto a season shows it, as the Library's tabs do.
                                modifier = seasonEntry.of(i).nav(up = above(seasonEntry.req), down = epEntry.req, left = edge(i == 0), right = edge(i == seasonKeys.lastIndex)).onFocusChanged { f = it.isFocused; if (it.isFocused) { season = n
                                    panel = PanelInfo("season:$n", shown.name, item = shown, kicker = (if (n == 0) Lang.tr("Specials") else Lang.tr("Season") + " $n") + "  ·  " + details.seasons[n].orEmpty().size + " " + Lang.tr("episodes"), body = shown.overview) } }.tvPress(onClick = { season = n })
                                    .clip(RoundedCornerShape(50)).background(if (f) Color.White else if (n == seasonOn) Ds.Glass else Color.Transparent)
                                    .padding(horizontal = (9 * s).dp, vertical = (3.5f * s).dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height((7 * s).dp))
                val eps = details.seasons[seasonOn].orEmpty()
                LaunchedEffect(seasonOn) { runCatching { epEntry.state.scrollToItem(0) } }
                LazyRow(
                    state = epEntry.state,
                    contentPadding = PaddingValues(start = (EDGE * s).dp, end = (EDGE * s).dp, top = (5 * s).dp, bottom = (5 * s).dp),
                    horizontalArrangement = Arrangement.spacedBy((9 * s).dp),
                ) {
                    itemsIndexed(eps, key = { _, e -> "$seasonOn:${e.number}" }) { i, e ->
                        val current = resume != null && seasonOn == fromResume && e.number == epNow
                        EpisodeCard(e, item, s, now, if (current) resume?.progress ?: -1f else -1f, epEntry.of(i).nav(up = seasonEntry.req, down = below(epEntry.req), left = edge(i == 0), right = edge(i == eps.lastIndex))
                            .onFocusChanged { if (it.hasFocus) panel = PanelInfo("ep:${e.season}:${e.number}", "${e.number}. ${e.name.ifBlank { "Episode ${e.number}" }}",
                                kicker = "S${e.season} · E${e.number}" + (if (e.released > 0) "  ·  " + java.text.SimpleDateFormat("d MMM yyyy", Lang.locale()).format(java.util.Date(e.released)) else ""),
                                body = e.overview) }) {
                            if (canPlay) NuvioLaunch.openEpisode(ctx, st, app.http, item, e.season, e.number)
                            else toast(ctx, "No app for titles on this TV: Nuvio or Stremio plays them")
                        }
                    }
                }
            }

            if (anywhere > 0) {
                Spacer(Modifier.height((12 * s).dp))
                DsHeading("Where to watch", s, Modifier.padding(start = (EDGE * s).dp))
                LazyRow(
                    state = watchEntry.state,
                    contentPadding = PaddingValues(start = (EDGE * s).dp, end = (EDGE * s).dp, top = (5 * s).dp, bottom = (5 * s).dp),
                    horizontalArrangement = Arrangement.spacedBy((6 * s).dp),
                ) {
                    fun place(i: Int, title: String, sub: String) = watchEntry.of(i).nav(up = above(watchEntry.req), down = below(watchEntry.req), left = edge(i == 0), right = edge(i == anywhere - 1))
                        .onFocusChanged { if (it.hasFocus) panel = PanelInfo("watch:$i", title, kicker = Lang.tr("Where to watch"), body = sub) }
                    fun airing(a: LiveHome.Airing) {
                        if (a.onNow(System.currentTimeMillis())) ctx.startActivity(LiveHome.open(ctx, a.channel, false))
                        else scope.launch { toast(ctx, withContext(Dispatchers.IO) { LiveHome.toggle(ctx, a, true) }) }
                    }
                    itemsIndexed(onNow, key = { _, a -> "now:${a.channel}:${a.start}" }) { i, a ->
                        TextCard(a.channelName, LiveHome.whenLine(a), s, place(i, a.channelName, LiveHome.whenLine(a)), titleTag = a.country) { airing(a) }
                    }
                    itemsIndexed(recorded, key = { _, a -> "rec:${a.recording}" }) { i, a ->
                        TextCard(a.channelName.ifBlank { a.title }, "Your recording", s, place(onNow.size + i, a.channelName.ifBlank { a.title }, Lang.tr("Your recording")), titleTag = if (a.channelName.isBlank()) "" else a.country) { ctx.startActivity(LiveHome.openRecording(ctx, a.recording)) }
                    }
                    itemsIndexed(otherApps, key = { _, l -> "app:${l.service}" }) { i, l ->
                        TextCard(l.service, "Streaming", s, place(onNow.size + recorded.size + i, l.service, Lang.tr("Streaming"))) {
                            runCatching { ctx.startActivity(l.intent()) }.onFailure { toast(ctx, "${l.service} couldn't open it") }
                        }
                    }
                    itemsIndexed(later, key = { _, a -> "later:${a.channel}:${a.start}" }) { i, a ->
                        TextCard(a.channelName, LiveHome.whenLine(a), s, place(onNow.size + recorded.size + otherApps.size + i, a.channelName, LiveHome.whenLine(a)), titleTag = a.country) { airing(a) }
                    }
                }
            }

            if (people.cast.isNotEmpty()) {
                Spacer(Modifier.height((12 * s).dp))
                DsHeading("Cast", s, Modifier.padding(start = (EDGE * s).dp))
                LazyRow(
                    state = castEntry.state,
                    contentPadding = PaddingValues(start = (EDGE * s).dp, end = (EDGE * s).dp, top = (5 * s).dp, bottom = (5 * s).dp),
                    horizontalArrangement = Arrangement.spacedBy((6 * s).dp),
                ) {
                    itemsIndexed(people.cast, key = { _, p -> p.id }) { i, p ->
                        PersonCard(p, s, castEntry.of(i).nav(up = above(castEntry.req), down = below(castEntry.req), left = edge(i == 0), right = edge(i == people.cast.lastIndex))
                            .onFocusChanged { if (it.hasFocus) panel = PanelInfo("person:${p.id}", p.name, kicker = p.role, body = Lang.tr("OK for their other titles")) }) { person = p }
                    }
                }
            }

            if (theirs.isNotEmpty()) {
                Spacer(Modifier.height((12 * s).dp))
                DsHeading(Lang.tr("More with") + " " + person?.name.orEmpty(), s, Modifier.padding(start = (EDGE * s).dp))
                LazyRow(
                    state = withEntry.state,
                    contentPadding = PaddingValues(start = (EDGE * s).dp, end = (EDGE * s).dp, top = (5 * s).dp, bottom = (5 * s).dp),
                    horizontalArrangement = Arrangement.spacedBy((9 * s).dp),
                ) {
                    itemsIndexed(theirs, key = { _, h -> h.key }) { i, h ->
                        SmallPoster(h, s, withEntry.of(i).nav(up = above(withEntry.req), down = below(withEntry.req), left = edge(i == 0), right = edge(i == theirs.lastIndex)),
                            onFocused = { panel = PanelInfo(h.key, h.name, item = h, body = h.overview) }) { onOpen(h) }
                    }
                }
            }

            if (more.isNotEmpty()) {
                Spacer(Modifier.height((12 * s).dp))
                DsHeading("More like this", s, Modifier.padding(start = (EDGE * s).dp))
                LazyRow(
                    state = moreEntry.state,
                    contentPadding = PaddingValues(start = (EDGE * s).dp, end = (EDGE * s).dp, top = (5 * s).dp, bottom = (5 * s).dp),
                    horizontalArrangement = Arrangement.spacedBy((9 * s).dp),
                ) {
                    itemsIndexed(more, key = { _, h -> h.key }) { i, h -> SmallPoster(h, s, moreEntry.of(i).nav(up = above(moreEntry.req), down = FocusRequester.Cancel, left = edge(i == 0), right = edge(i == more.lastIndex)),
                        onFocused = { panel = PanelInfo(h.key, h.name, item = h, body = h.overview) }) { onOpen(h) } }
                }
            }
                }
                }
                }
                }
            }
        if (trailerOn) trailerIds?.let { ids -> TrailerPlayer(ids) { trailerOn = false } }
    }
}

/** Someone in the cast: their photo, name and role. */
@Composable
private fun PersonCard(p: TitlePeople.Person, s: Float, modifier: Modifier, onClick: () -> Unit) {
    val ctx = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier.width((74 * s).dp).onFocusChanged { focused = it.isFocused }.tvPress(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size((58 * s).dp).cardFocus(s, focused, 29f, 1.06f).clip(CircleShape).background(Ds.Surface), contentAlignment = Alignment.Center) {
            if (p.photo.isNotBlank()) AsyncImage(remember(p.photo) { ImageRequest.Builder(ctx).data(p.photo).size(200, 200).build() }, p.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else RawText(p.name.split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }, color = Text2, fontSize = (14 * s).sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height((5 * s).dp))
        RawText(p.name, color = Color.White, fontSize = (8.5f * s).sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        RawText(p.role.ifBlank { " " }, color = Text2, fontSize = (7.5f * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun EpisodeCard(e: TitleDetails.Episode, series: HeroItem, s: Float, now: Long, progress: Float, modifier: Modifier, onClick: () -> Unit) {
    val ctx = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    Column(modifier.width((132 * s).dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .onFocusChanged { focused = it.isFocused }
                .tvPress(onKey = backKey(), onClick = onClick)
                .cardFocus(s, focused)
                .background(Ds.Surface),
        ) {
            val pic = e.thumb.ifBlank { series.background }
            if (pic.isNotBlank()) AsyncImage(remember(pic) { ImageRequest.Builder(ctx).data(pic).size(360, 200).build() }, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (progress >= 0f) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height((2 * s).dp).background(Color.White.copy(alpha = 0.3f)))
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress.coerceIn(0f, 1f)).height((2 * s).dp).background(LocalAccent.current))
            }
        }
        Spacer(Modifier.height((4 * s).dp))
        Text("${e.number} · ${e.name.ifBlank { "Episode ${e.number}" }}", color = if (focused) Color.White else Color(0xFFE8E8EC), fontSize = (8.5f * s).sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (e.released > now) Text("Out " + java.text.SimpleDateFormat("EEE d MMM", Lang.locale()).format(java.util.Date(e.released)), color = Text2, fontSize = (7.5f * s).sp, maxLines = 1)
    }
}

/** A small poster (More like this, Search): OK goes to its title page. */
@Composable
internal fun SmallPoster(h: HeroItem, s: Float, modifier: Modifier, width: Float = 84f, onFocused: () -> Unit = {}, onClick: () -> Unit) {
    val ctx = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    Column(modifier.width((width * s).dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
                .tvPress(onKey = backKey(), onClick = onClick)
                .cardFocus(s, focused)
                .background(Ds.Surface),
            contentAlignment = Alignment.Center,
        ) {
            RawText(h.name, color = Text2, fontSize = (8 * s).sp, modifier = Modifier.padding((4 * s).dp))
            if (h.poster.isNotBlank()) AsyncImage(remember(h.poster) { ImageRequest.Builder(ctx).data(h.poster).size(240, 360).build() }, h.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/** More info: before its trailer plays (ms on the page), and before the page steps aside for it (ms of the trailer untouched). */
internal const val TITLE_TRAILER_AFTER_MS = 4_000L
internal const val TITLE_CINEMATIC_AFTER_MS = 5_000L

/** A round button with a drawn icon (More info): white when selected; [onFocus] tells which, for its name under the row. */
@Composable
private fun RoundIcon(icon: String, name: String, s: Float, modifier: Modifier, onFocus: (Boolean) -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val fg = if (focused) Ds.Ink else Color.White
    Box(
        modifier.size((27 * s).dp)
            .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
            .tvPress(onClick = onClick)
            .graphicsLayer { val k = if (focused) 1.08f else 1f; scaleX = k; scaleY = k }
            .clip(CircleShape).background(if (focused) Color.White else Ds.Glass)
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(Modifier.size((12 * s).dp)) {
            val w = size.width; val sw = w * 0.11f
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(sw, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
            fun p(vararg pts: Float) = androidx.compose.ui.graphics.Path().apply { moveTo(pts[0] * w, pts[1] * w); var i = 2; while (i < pts.size) { lineTo(pts[i] * w, pts[i + 1] * w); i += 2 } }
            when (icon) {
                "check" -> drawPath(p(0.12f, 0.52f, 0.4f, 0.8f, 0.9f, 0.22f), fg, style = stroke)
                "plus" -> { drawPath(p(0.5f, 0.1f, 0.5f, 0.9f), fg, style = stroke); drawPath(p(0.1f, 0.5f, 0.9f, 0.5f), fg, style = stroke) }
                "trailer" -> {
                    drawRoundRect(fg, androidx.compose.ui.geometry.Offset(0.04f * w, 0.18f * w), androidx.compose.ui.geometry.Size(0.92f * w, 0.64f * w),
                        androidx.compose.ui.geometry.CornerRadius(0.14f * w), style = stroke)
                    drawPath(p(0.4f, 0.34f, 0.66f, 0.5f, 0.4f, 0.66f).apply { close() }, fg)
                }
                "stack" -> { drawRoundRect(fg, androidx.compose.ui.geometry.Offset(0.08f * w, 0.08f * w), androidx.compose.ui.geometry.Size(0.84f * w, 0.84f * w),
                    androidx.compose.ui.geometry.CornerRadius(0.16f * w), style = stroke); drawPath(p(0.5f, 0.3f, 0.5f, 0.7f), fg, style = stroke); drawPath(p(0.3f, 0.5f, 0.7f, 0.5f), fg, style = stroke) }
                else -> { drawCircle(fg, 0.43f * w, style = stroke); drawPath(p(0.2f, 0.2f, 0.8f, 0.8f), fg, style = stroke) }
            }
        }
    }
}

/** More info's large Play (or the app it plays in): white, the width of the buttons above it, a little larger when selected. */
@Composable
private fun BigPlay(label: String, s: Float, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().height((31 * s).dp)
            .onFocusChanged { focused = it.isFocused }
            .tvPress(onClick = onClick)
            .graphicsLayer { val k = if (focused) 1.04f else 1f; scaleX = k; scaleY = k }
            .clip(RoundedCornerShape((8 * s).dp)).background(if (focused) Color.White else Color.White.copy(alpha = 0.85f)),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayGlyph(Modifier.size((9 * s).dp), Ds.Ink)
        Spacer(Modifier.width((7 * s).dp))
        Text(label, color = Ds.Ink, fontSize = (11.5f * s).sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}


/** What the details' panel is about: a title (its logo, rating, year, genres), or an episode, a channel, someone in the cast. */
internal data class PanelInfo(val key: String, val title: String, val item: HeroItem? = null, val kicker: String = "", val body: String = "")

/** The details' panel (Prime Video's): about what's selected, changing over with a quick fade. */
@Composable
private fun DetailPanel(info: PanelInfo, s: Float, modifier: Modifier) {
    val st = (LocalContext.current.applicationContext as GlassApp).settings
    androidx.compose.animation.Crossfade(info, modifier.fillMaxWidth(), animationSpec = androidx.compose.animation.core.tween(220), label = "panel") { p ->
        Column(Modifier.fillMaxSize().padding(start = (EDGE * s).dp, top = (22 * s).dp, end = (EDGE * s).dp).width((360 * s).dp),
            verticalArrangement = Arrangement.spacedBy((5 * s).dp)) {
            val h = p.item
            if (h != null) Title(h, st.titleMode.takeIf { it != "hidden" } ?: "text", st.titleScale * 0.55f, s)
            else Text(p.title.uppercase(Lang.locale()), color = Color.White, fontSize = (13 * s).sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (p.kicker.isNotBlank()) Text(p.kicker, color = Color(0xFFD0D0D4), fontSize = (8.5f * s).sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (h != null) {
                val facts = listOf(h.year).filter { it.isNotBlank() } + h.genres.take(3)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((6 * s).dp)) {
                    if (h.rating.isNotBlank()) ImdbBadge(h.rating, s)
                    Text(facts.joinToString("  ·  "), color = Color(0xFFB9B9C0), fontSize = (8.5f * s).sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (p.body.isNotBlank()) Text(p.body, color = Color(0xFFECECEF), fontSize = (9.5f * s).sp, lineHeight = (14 * s).sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width((330 * s).dp))
        }
    }
}

/** Scrolls so the selection sits [at] px in from the start (the top of the rows, the left of a row), as far as the list goes. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private class PinAt(private val at: Float) : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - at
}
