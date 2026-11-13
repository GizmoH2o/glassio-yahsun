package com.glasslauncher.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
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
import coil3.request.allowHardware
import coil3.toBitmap
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
    val scroll = rememberScrollState()
    // The trailer on the page (not the Trailer button's, which fills the screen): after a few seconds at the top of the
    // page, with sound; gone once the page is scrolled, another app opens it, or it ends.
    // Down from the first screen: the sections (episodes as a list, where to watch, cast, more like this).
    var inDetails by remember(item.key) { mutableStateOf(false) }
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
    val trailerAt = remember(item.key) { androidx.compose.runtime.mutableFloatStateOf(0f) }
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
    val dimA by androidx.compose.animation.core.animateFloatAsState(if (inDetails) 0.86f else 0f,
        androidx.compose.animation.core.tween(if (inDetails) 320 else 450, delayMillis = if (inDetails || reduce) 0 else 180), label = "dim")
    val uiA by androidx.compose.animation.core.animateFloatAsState(if (cinematic) 0f else 1f,
        androidx.compose.animation.core.tween(if (cinematic) 900 else 250), label = "cinematic")
    val hasSeasons = details.seasons.isNotEmpty() && !item.type.equals("movie", true)
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
    // The sections under the first screen, as its bar names them; Down goes to the first.
    val primary = canPlay || links.isNotEmpty()
    val sections = buildList {
        if (hasSeasons) add("Episodes")
        if (anywhere > 0) add("Where to watch")
        if (people.cast.isNotEmpty()) add("Cast")
        if (theirs.isNotEmpty() || more.isNotEmpty()) add("More like this")
    }
    var section by remember(item.key) { mutableStateOf("") }
    // The rows top to bottom, for Up and Down: each goes to the one beside it that's there.
    val stops = buildList {
        add(if (primary) first else iconsReq)
        if (hasSeasons) add(epEntry.req)
        if (anywhere > 0) add(watchEntry.req)
        if (people.cast.isNotEmpty()) add(castEntry.req)
        if (theirs.isNotEmpty()) add(withEntry.req)
        if (more.isNotEmpty()) add(moreEntry.req)
    }
    fun above(r: FocusRequester) = stops.getOrNull(stops.indexOf(r) - 1) ?: FocusRequester.Cancel
    fun below(r: FocusRequester) = stops.getOrNull(stops.indexOf(r) + 1) ?: FocusRequester.Cancel
    val top = stops.first()
    val nextAiring = onNow.firstOrNull() ?: later.firstOrNull()

    var pageTint by remember(item.key) { mutableStateOf<Color?>(null) }
    val pageBg by androidx.compose.animation.animateColorAsState(pageTint ?: Ds.Bg, androidx.compose.animation.core.tween(600), label = "pageTint")
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(pageBg).focusProperties { exit = { FocusRequester.Cancel } }.focusGroup()
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
                muted = false, shade = { uiA }, onProgress = { trailerAt.floatValue = it }) { inline = false; inlinePlaying = false }
        }
        val pic = shown.background.ifBlank { shown.poster }
        if (pic.isNotBlank()) AsyncImage(
            remember(pic) { ImageRequest.Builder(ctx).data(pic).size(1920, 1080).allowHardware(false).build() }, null, contentScale = ContentScale.Crop,
            // The page takes a little of the picture's colour, as Home does (Settings › Look & motion › Colour from the picture).
            onSuccess = { r -> if (st.backdropTint && !st.highContrast) scope.launch(kotlinx.coroutines.Dispatchers.Default) {
                BackdropTint.of(r.result.image.toBitmap())?.let { pageTint = Color(it) }
            } },
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = (1f - inlineShown.value) * (enter.value * 2.5f).coerceAtMost(1f) },
        )
        // The shades the words need: a band along the bottom, a little at the left.
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = uiA }.background(Brush.horizontalGradient(0f to pageBg.copy(alpha = 0.7f), 0.35f to pageBg.copy(alpha = 0.2f), 0.6f to Color.Transparent)))
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = uiA }.background(Brush.verticalGradient(0.3f to Color.Transparent, 0.6f to pageBg.copy(alpha = 0.72f), 1f to pageBg)))
        // Cinematic: only a soft corner behind Play.
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - uiA }.background(Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent),
            center = androidx.compose.ui.geometry.Offset(0f, Float.POSITIVE_INFINITY), radius = 900f)))
        // Cinematic: how far through the trailer is, and that it plays with sound.
        CinematicChrome({ trailerAt.floatValue }, { 1f - uiA }, s)
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = dimA }.background(pageBg))
        val rise = with(androidx.compose.ui.platform.LocalDensity.current) { 40.dp.toPx() }
        val screenPx = with(androidx.compose.ui.platform.LocalDensity.current) { screenH.toPx() }

        // ── The first screen: the title in the lower middle, everything else in one band along the bottom.
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - detailsA; if (!reduce) translationY = -detailsA * screenPx * 0.3f }
            .onFocusChanged { if (it.hasFocus) inDetails = false }) {
            val bandH = screenH * 0.29f
            Column(Modifier.align(Alignment.BottomStart).padding(start = (EDGE * s).dp, bottom = bandH + (6 * s).dp).width((420 * s).dp)
                .graphicsLayer { alpha = uiA }.wordsIn(0.2f), verticalArrangement = Arrangement.spacedBy((6 * s).dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((6 * s).dp)) {
                    if (nextAiring != null) Text((if (nextAiring.onNow(nowTime)) Lang.tr("ON NOW") else Lang.tr("ON LIVE TV")) + "  ·  " + nextAiring.channelName.uppercase(),
                        color = Color.White, fontSize = (7f * s).sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (0.5f * s).sp, maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape((3 * s).dp)).background(LocalAccent.current).padding(horizontal = (5 * s).dp, vertical = (2 * s).dp))
                    val kind = if (shown.type.equals("movie", true)) Lang.tr("FILM") else Lang.tr("SERIES")
                    Text(kind + (if (inLibrary) "  ·  " + Lang.tr("IN YOUR LIBRARY") else ""), color = Color(0xFFD0D0D4), fontSize = (7.5f * s).sp,
                        fontWeight = FontWeight.Bold, letterSpacing = (1.2f * s).sp, maxLines = 1)
                }
                Title(shown, st.titleMode.takeIf { it != "hidden" } ?: "text", st.titleScale * 1.05f, s)
                val facts = listOf(shown.year, shown.runtime).filter { it.isNotBlank() } + shown.genres.take(3).joinToString(", ").let { listOfNotNull(it.takeIf { g -> g.isNotBlank() }) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((6 * s).dp)) {
                    if (shown.rating.isNotBlank()) ImdbBadge(shown.rating, s)
                    Text(facts.joinToString("   |   "), color = Color(0xFFE0E0E4), fontSize = (8.5f * s).sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            // The band: Play and the round buttons, what's left of it, where else it is; the story and the people.
            // Cinematic: the band (Play alone left on it) settles near the bottom edge.
            val bandDrop = with(androidx.compose.ui.platform.LocalDensity.current) { (bandH - (78 * s).dp).toPx() }
            Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(bandH).graphicsLayer { translationY = (1f - uiA) * bandDrop }
                .padding(start = (EDGE * s).dp, end = (EDGE * s).dp, top = (8 * s).dp),
                horizontalArrangement = Arrangement.spacedBy((26 * s).dp)) {
                Column(Modifier.width((228 * s).dp).wordsIn(0.3f), verticalArrangement = Arrangement.spacedBy((7 * s).dp)) {
                    var label by remember { mutableStateOf("") }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((6 * s).dp)) {
                        val playMod = Modifier.weight(1f).focusRequester(first).nav(up = FocusRequester.Cancel, down = below(top), left = FocusRequester.Cancel)
                        if (canPlay) BigPlay(
                            if (resume != null) (if (resume.upNext) "Play" else "Resume") + (resume.sub.takeIf { it.isNotBlank() }?.let { " $it" } ?: "") else "Play",
                            s, playMod,
                        ) { NuvioLaunch.play(ctx, st, app.http, resume ?: item) }
                        else links.firstOrNull()?.let { l ->
                            BigPlay(l.service, s, playMod) { runCatching { ctx.startActivity(l.intent()) }.onFailure { toast(ctx, "${l.service} couldn't open it") } }
                        }
                        val moreActs = buildList<Pair<String, () -> Unit>> {
                            if (!kids && st.homeMine) add(Lang.tr("Add to a collection") to { onDialog(collectionMenu(app, ctx, scope, item, onDialog)) })
                            otherApps.forEach { l -> add(Lang.tr("Open in") + " " + l.service to { runCatching { ctx.startActivity(l.intent()) }.onFailure { toast(ctx, "${l.service} couldn't open it") }; Unit }) }
                            if (!kids) add(Lang.tr("Not interested") to {
                                st.heroHidden = (st.heroHidden + item.key).distinct()
                                toast(ctx, "${item.name} won't show on the big picture again (Settings › Home to bring it back)")
                                onClose()
                            })
                        }
                        val icons = buildList<Triple<String, String, () -> Unit>> {
                            add(Triple(if (inLibrary) "check" else "plus", if (inLibrary) "In Library" else "My Library") {
                                val now = app.library.toggle(item)
                                toast(ctx, if (now) "Saved to Library · ${item.name}" else "Removed from Library · ${item.name}")
                            })
                            if (trailerIds != null) add(Triple("trailer", "Trailer") { trailerOn = true })
                            if (moreActs.isNotEmpty()) add(Triple("more", "More") { onDialog(MenuDialog(item.name, moreActs)) })
                        }
                        Row(Modifier.graphicsLayer { alpha = uiA }, horizontalArrangement = Arrangement.spacedBy((6 * s).dp)) {
                            icons.forEachIndexed { i, (icon, name, act) ->
                                RoundIcon(icon, name, s, (if (i == 0) Modifier.focusRequester(iconsReq) else Modifier)
                                    .nav(up = FocusRequester.Cancel, down = below(top), left = if (i == 0 && !primary) FocusRequester.Cancel else null, right = edge(i == icons.lastIndex)),
                                    onFocus = { f -> if (f) label = name else if (label == name) label = "" }, onClick = act)
                            }
                        }
                    }
                    Box {
                    // Cinematic: in place of what's under Play, what the other buttons do.
                    Text(Lang.tr("Any other button: back to the page"), color = Color.White.copy(alpha = 0.6f), fontSize = (7.5f * s).sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = (2 * s).dp).graphicsLayer { alpha = 1f - uiA })
                    Column(Modifier.graphicsLayer { alpha = uiA }, verticalArrangement = Arrangement.spacedBy((5 * s).dp)) {
                        if (label.isNotBlank()) Text(label, color = Color.White, fontSize = (8 * s).sp, fontWeight = FontWeight.SemiBold)
                        else if (resume != null && resume.progress in 0.01f..0.99f) Row(Modifier.padding(top = (3 * s).dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f).height((2 * s).dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.25f))) {
                                Box(Modifier.fillMaxWidth(resume.progress).height((2 * s).dp).background(LocalAccent.current))
                            }
                        }
                        val where = buildList {
                            if (canPlay) links.firstOrNull()?.let { add(Lang.tr("Also in") + " " + it.service) }
                            nextAiring?.let { add(Lang.tr("On") + " " + it.channelName + "  " + LiveHome.whenLine(it)) }
                        }
                        if (where.isNotEmpty()) Text(where.joinToString("  ·  "), color = Text2, fontSize = (7.5f * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    }
                }
                Column(Modifier.weight(1f).graphicsLayer { alpha = uiA }.wordsIn(0.4f), verticalArrangement = Arrangement.spacedBy((6 * s).dp)) {
                    if (shown.overview.isNotBlank()) Text(shown.overview, color = Color(0xFFECECEF), fontSize = (10 * s).sp, lineHeight = (15 * s).sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    val cast = people.cast.take(3).map { it.name }.ifEmpty { details.cast.take(3) }
                    val who = buildList {
                        if (cast.isNotEmpty()) add(Lang.tr("Starring") + " " + cast.joinToString(", "))
                        if (people.makers.isNotEmpty()) add((if (shown.type.equals("movie", true)) Lang.tr("Directed by") else Lang.tr("Created by")) + " " + people.makers.joinToString(", "))
                    }
                    if (who.isNotEmpty()) Text(who.joinToString("  ·  "), color = Text2, fontSize = (7.5f * s).sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            // Cinematic: the title's logo at the top left, on a soft dark corner so it reads over any picture.
            if (uiA < 0.999f) {
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - uiA }.background(Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent),
                    center = androidx.compose.ui.geometry.Offset.Zero, radius = 900f)))
                Box(Modifier.padding(start = (EDGE * s).dp, top = (26 * s).dp).width((220 * s).dp).graphicsLayer { alpha = 1f - uiA }) {
                    Title(shown, st.titleMode.takeIf { it != "hidden" } ?: "text", st.titleScale * 0.7f, s)
                }
            }
            // The section bar: Down goes to the one in white.
            if (sections.isNotEmpty()) SectionBar(sections, sections.first(), s, Modifier.align(Alignment.BottomCenter).padding(bottom = (6 * s).dp).graphicsLayer { alpha = uiA * 0.9f })
        }

        // ── The sections: a header with the title and the bar, then the episodes as a list and the rows, the one in use
        // always near the top, its selection at the left.
        val headerH = (40 * s).dp
        val headroom = with(androidx.compose.ui.platform.LocalDensity.current) { (28 * s).dp.toPx() }
        val footroom = with(androidx.compose.ui.platform.LocalDensity.current) { (14 * s).dp.toPx() }
        val edgePx = with(androidx.compose.ui.platform.LocalDensity.current) { (EDGE * s).dp.toPx() }
        // Down the page it scrolls only when the selection would be cut off (the seasons stay beside the list), with room
        // left above for a row's heading.
        val pinTop = remember(headroom, footroom) { KeepInView(headroom, footroom) }
        val pinLeft = remember(edgePx) { PinAt(edgePx) }
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = detailsA; if (!reduce) translationY = (1f - detailsA) * rise }) {
            Row(Modifier.fillMaxWidth().height(headerH).padding(start = (EDGE * s).dp, end = (EDGE * s).dp, top = (14 * s).dp), verticalAlignment = Alignment.Top) {
                RawText(shown.name, color = Color.White, fontSize = (14 * s).sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width((10 * s).dp))
                Text(section.ifBlank { sections.firstOrNull().orEmpty() }, color = Text2, fontSize = (9 * s).sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = (4 * s).dp))
                Spacer(Modifier.weight(1f))
                if (sections.isNotEmpty()) SectionBar(sections, section.ifBlank { sections.first() }, s, Modifier.padding(top = (3 * s).dp))
            }
            Box(Modifier.fillMaxSize().padding(top = headerH)) {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides pinTop) {
            Column(Modifier.fillMaxSize().onFocusChanged { if (it.hasFocus) inDetails = true }.verticalScroll(scroll).padding(bottom = screenH * 0.6f)) {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides pinLeft) {

            if (hasSeasons) {
                val now = remember { System.currentTimeMillis() }
                val fromResume = remember(resume?.sub) { Regex("S(\\d+)").find(resume?.sub.orEmpty())?.groupValues?.get(1)?.toIntOrNull() }
                val epNow = remember(resume?.sub) { Regex("E(\\d+)").find(resume?.sub.orEmpty())?.groupValues?.get(1)?.toIntOrNull() }
                var season by remember(item.key) { mutableIntStateOf(fromResume ?: -1) }
                val seasonOn = season.takeIf { it in details.seasons } ?: details.seasons.keys.first()
                val seasonKeys = details.seasons.keys.toList()
                val seasonReqs = remember(seasonKeys) { seasonKeys.map { FocusRequester() } }
                val eps = details.seasons[seasonOn].orEmpty()
                Row(Modifier.padding(start = (EDGE * s).dp, end = (EDGE * s).dp, top = (6 * s).dp).onFocusChanged { if (it.hasFocus) section = "Episodes" },
                    horizontalArrangement = Arrangement.spacedBy((14 * s).dp)) {
                    // Seasons down the left; the one shown, light.
                    Column(Modifier.width((96 * s).dp), verticalArrangement = Arrangement.spacedBy((3 * s).dp)) {
                        seasonKeys.forEachIndexed { i, n ->
                            var f by remember { mutableStateOf(false) }
                            Text(
                                if (n == 0) Lang.tr("Specials") else Lang.tr("Season") + " $n", color = if (f) Ds.Ink else if (n == seasonOn) Color.White else Text2, fontSize = (10 * s).sp,
                                fontWeight = if (f || n == seasonOn) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1,
                                modifier = Modifier.fillMaxWidth().focusRequester(seasonReqs[i])
                                    .nav(up = if (i == 0) above(epEntry.req) else null, down = if (i == seasonKeys.lastIndex) below(epEntry.req) else null, left = FocusRequester.Cancel, right = epEntry.req)
                                    .onFocusChanged { f = it.isFocused; if (it.isFocused) season = n }.tvPress(onClick = { season = n; runCatching { epEntry.req.requestFocus() } })
                                    .clip(RoundedCornerShape((7 * s).dp)).background(if (f) Color.White else if (n == seasonOn) Ds.Glass else Color.Transparent)
                                    .padding(horizontal = (9 * s).dp, vertical = (6 * s).dp),
                            )
                        }
                        Text(eps.size.toString() + " " + Lang.tr("episodes"), color = Text2, fontSize = (7.5f * s).sp, modifier = Modifier.padding(start = (9 * s).dp, top = (5 * s).dp))
                    }
                    // The episodes, one line each; the selected one opens out with its picture and story.
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy((3 * s).dp)) {
                        val toSeason = seasonReqs.getOrNull(seasonKeys.indexOf(seasonOn)) ?: FocusRequester.Cancel
                        eps.forEachIndexed { i, e ->
                            androidx.compose.runtime.key("$seasonOn:${e.number}") {
                                val current = resume != null && seasonOn == fromResume && e.number == epNow
                                EpisodeLine(e, item, s, now, if (current) resume?.progress ?: -1f else -1f,
                                    (if (i == 0) Modifier.focusRequester(epEntry.req) else Modifier)
                                        .nav(up = if (i == 0) above(epEntry.req) else null, down = if (i == eps.lastIndex) below(epEntry.req) else null, left = toSeason, right = FocusRequester.Cancel)) {
                                    if (canPlay) NuvioLaunch.openEpisode(ctx, st, app.http, item, e.season, e.number)
                                    else toast(ctx, "No app for titles on this TV: Nuvio or Stremio plays them")
                                }
                            }
                        }
                    }
                }
            }

            if (anywhere > 0) {
                Column(Modifier.onFocusChanged { if (it.hasFocus) section = "Where to watch" }) {
                Spacer(Modifier.height((12 * s).dp))
                DsHeading("Where to watch", s, Modifier.padding(start = (EDGE * s).dp))
                LazyRow(
                    state = watchEntry.state,
                    contentPadding = PaddingValues(start = (EDGE * s).dp, end = (EDGE * s).dp, top = (5 * s).dp, bottom = (5 * s).dp),
                    horizontalArrangement = Arrangement.spacedBy((6 * s).dp),
                ) {
                    fun place(i: Int) = watchEntry.of(i).nav(up = above(watchEntry.req), down = below(watchEntry.req), left = edge(i == 0), right = edge(i == anywhere - 1))
                    fun airing(a: LiveHome.Airing) {
                        if (a.onNow(System.currentTimeMillis())) ctx.startActivity(LiveHome.open(ctx, a.channel, false))
                        else scope.launch { toast(ctx, withContext(Dispatchers.IO) { LiveHome.toggle(ctx, a, true) }) }
                    }
                    itemsIndexed(onNow, key = { _, a -> "now:${a.channel}:${a.start}" }) { i, a ->
                        TextCard(a.channelName, LiveHome.whenLine(a), s, place(i), titleTag = a.country) { airing(a) }
                    }
                    itemsIndexed(recorded, key = { _, a -> "rec:${a.recording}" }) { i, a ->
                        TextCard(a.channelName.ifBlank { a.title }, "Your recording", s, place(onNow.size + i), titleTag = if (a.channelName.isBlank()) "" else a.country) { ctx.startActivity(LiveHome.openRecording(ctx, a.recording)) }
                    }
                    itemsIndexed(otherApps, key = { _, l -> "app:${l.service}" }) { i, l ->
                        TextCard(l.service, "Streaming", s, place(onNow.size + recorded.size + i)) {
                            runCatching { ctx.startActivity(l.intent()) }.onFailure { toast(ctx, "${l.service} couldn't open it") }
                        }
                    }
                    itemsIndexed(later, key = { _, a -> "later:${a.channel}:${a.start}" }) { i, a ->
                        TextCard(a.channelName, LiveHome.whenLine(a), s, place(onNow.size + recorded.size + otherApps.size + i), titleTag = a.country) { airing(a) }
                    }
                }
                }
            }

            if (people.cast.isNotEmpty()) {
                Column(Modifier.onFocusChanged { if (it.hasFocus) section = "Cast" }) {
                Spacer(Modifier.height((12 * s).dp))
                DsHeading("Cast", s, Modifier.padding(start = (EDGE * s).dp))
                LazyRow(
                    state = castEntry.state,
                    contentPadding = PaddingValues(start = (EDGE * s).dp, end = (EDGE * s).dp, top = (5 * s).dp, bottom = (5 * s).dp),
                    horizontalArrangement = Arrangement.spacedBy((6 * s).dp),
                ) {
                    itemsIndexed(people.cast, key = { _, p -> p.id }) { i, p ->
                        PersonCard(p, s, castEntry.of(i).nav(up = above(castEntry.req), down = below(castEntry.req), left = edge(i == 0), right = edge(i == people.cast.lastIndex))) { person = p }
                    }
                }
                }
            }

            if (theirs.isNotEmpty()) {
                Column(Modifier.onFocusChanged { if (it.hasFocus) section = "More like this" }) {
                Spacer(Modifier.height((12 * s).dp))
                DsHeading(Lang.tr("More with") + " " + person?.name.orEmpty(), s, Modifier.padding(start = (EDGE * s).dp))
                LazyRow(
                    state = withEntry.state,
                    contentPadding = PaddingValues(start = (EDGE * s).dp, end = (EDGE * s).dp, top = (5 * s).dp, bottom = (5 * s).dp),
                    horizontalArrangement = Arrangement.spacedBy((9 * s).dp),
                ) {
                    itemsIndexed(theirs, key = { _, h -> h.key }) { i, h ->
                        SmallPoster(h, s, withEntry.of(i).nav(up = above(withEntry.req), down = below(withEntry.req), left = edge(i == 0), right = edge(i == theirs.lastIndex))) { onOpen(h) }
                    }
                }
                }
            }

            if (more.isNotEmpty()) {
                Column(Modifier.onFocusChanged { if (it.hasFocus) section = "More like this" }) {
                Spacer(Modifier.height((12 * s).dp))
                DsHeading("More like this", s, Modifier.padding(start = (EDGE * s).dp))
                LazyRow(
                    state = moreEntry.state,
                    contentPadding = PaddingValues(start = (EDGE * s).dp, end = (EDGE * s).dp, top = (5 * s).dp, bottom = (5 * s).dp),
                    horizontalArrangement = Arrangement.spacedBy((9 * s).dp),
                ) {
                    itemsIndexed(more, key = { _, h -> h.key }) { i, h -> SmallPoster(h, s, moreEntry.of(i).nav(up = above(moreEntry.req), down = FocusRequester.Cancel, left = edge(i == 0), right = edge(i == more.lastIndex))) { onOpen(h) } }
                }
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

/**
 * An episode in the list (take B): one line, its number, name and when; selected, it opens out on a white card with its
 * picture (and what's left of it), its date and its story.
 */
@Composable
private fun EpisodeLine(e: TitleDetails.Episode, series: HeroItem, s: Float, now: Long, progress: Float, modifier: Modifier, onClick: () -> Unit) {
    val ctx = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    val name = e.name.ifBlank { "Episode ${e.number}" }
    val date = if (e.released > 0) java.text.SimpleDateFormat("d MMM yyyy", Lang.locale()).format(java.util.Date(e.released)) else ""
    val upcoming = e.released > now
    Box(
        modifier.fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .tvPress(onKey = backKey(), onClick = onClick)
            .animateContentSize(androidx.compose.animation.core.tween(220))
            .clip(RoundedCornerShape((9 * s).dp)).background(if (focused) Color.White else Color.Transparent)
            .padding(horizontal = (10 * s).dp, vertical = (if (focused) 8 else 5).let { (it * s).dp }),
    ) {
        if (!focused) Row(verticalAlignment = Alignment.CenterVertically) {
            RawText("${e.number}", color = Text2, fontSize = (10 * s).sp, fontWeight = FontWeight.Bold, modifier = Modifier.width((20 * s).dp))
            RawText(name, color = if (progress >= 0.99f) Text2 else Color.White, fontSize = (10 * s).sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (upcoming) Text(Lang.tr("Out") + " " + java.text.SimpleDateFormat("d MMM", Lang.locale()).format(java.util.Date(e.released)), color = Text2, fontSize = (8 * s).sp)
        } else Row(horizontalArrangement = Arrangement.spacedBy((10 * s).dp)) {
            RawText("${e.number}", color = Ds.Ink, fontSize = (12 * s).sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.width((14 * s).dp))
            Box(Modifier.width((150 * s).dp).aspectRatio(16f / 9f).clip(RoundedCornerShape((6 * s).dp)).background(Ds.Surface), contentAlignment = Alignment.Center) {
                val pic = e.thumb.ifBlank { series.background }
                if (pic.isNotBlank()) AsyncImage(remember(pic) { ImageRequest.Builder(ctx).data(pic).size(480, 270).build() }, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.size((22 * s).dp).clip(CircleShape).background(Color.White.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) { PlayGlyph(Modifier.size((8 * s).dp), Ds.Ink) }
                if (progress >= 0f) {
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height((2.5f * s).dp).background(Color.White.copy(alpha = 0.3f)))
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress.coerceIn(0f, 1f)).height((2.5f * s).dp).background(LocalAccent.current))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy((3 * s).dp)) {
                RawText(name, color = Ds.Ink, fontSize = (11.5f * s).sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val meta = listOf("S${e.season} · E${e.number}", if (upcoming) Lang.tr("Out") + " " + date else date).filter { it.isNotBlank() }
                Text(meta.joinToString("  ·  "), color = Ds.InkSoft, fontSize = (8 * s).sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                if (e.overview.isNotBlank()) RawText(e.overview, color = Color(0xFF26262A), fontSize = (8.5f * s).sp, lineHeight = (12 * s).sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The page's sections in a row (take B), the one in use (or where Down goes) in white. */
@Composable
private fun SectionBar(sections: List<String>, current: String, s: Float, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy((12 * s).dp)) {
        sections.forEach { n -> Text(n, color = if (n == current) Color.White else Color.White.copy(alpha = 0.5f), fontSize = (8 * s).sp, fontWeight = FontWeight.Bold) }
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
                .background(Ds.Surface)
                .watchedBar(h.key, s),
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
        modifier.size((30 * s).dp)
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
                "more" -> { drawCircle(fg, w * 0.09f, androidx.compose.ui.geometry.Offset(0.15f * w, 0.5f * w)); drawCircle(fg, w * 0.09f, androidx.compose.ui.geometry.Offset(0.5f * w, 0.5f * w)); drawCircle(fg, w * 0.09f, androidx.compose.ui.geometry.Offset(0.85f * w, 0.5f * w)) }
                else -> { drawCircle(fg, 0.43f * w, style = stroke); drawPath(p(0.2f, 0.2f, 0.8f, 0.8f), fg, style = stroke) }
            }
        }
    }
}

/** More info's Play (or the app it plays in): a white pill beside the round buttons, a little larger when selected. */
@Composable
private fun BigPlay(label: String, s: Float, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier.height((30 * s).dp)
            .onFocusChanged { focused = it.isFocused }
            .tvPress(onClick = onClick)
            .graphicsLayer { val k = if (focused) 1.04f else 1f; scaleX = k; scaleY = k }
            .clip(RoundedCornerShape(50)).background(if (focused) Color.White else Color.White.copy(alpha = 0.85f)),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayGlyph(Modifier.size((9 * s).dp), Ds.Ink)
        Spacer(Modifier.width((7 * s).dp))
        Text(label, color = Ds.Ink, fontSize = (11.5f * s).sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}


/** Scrolls only when the selection would be cut off, keeping [before] px clear before it and [after] px after it. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private class KeepInView(private val before: Float, private val after: Float) : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = when {
        offset - before < 0f -> offset - before
        offset + size + after > containerSize -> minOf(offset + size + after - containerSize, offset - before)
        else -> 0f
    }
}

/** Scrolls so the selection sits [at] px in from the start (the top of the rows, the left of a row), as far as the list goes. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private class PinAt(private val at: Float) : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - at
}
