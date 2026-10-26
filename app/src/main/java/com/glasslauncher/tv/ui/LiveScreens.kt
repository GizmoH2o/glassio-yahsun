package com.glasslauncher.tv.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.glasslauncher.tv.GlassApp
import com.glasslauncher.tv.live.LiveHome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Live TV and Following, the home screen's pages for Live TV (the IPTV player, in its own process): what Zero's home
 * screen tabs showed, drawn the Glassio way. A graphite page like the Library's grey one, Inter, clear glass cards
 * with a light edge, the white focus plate of the menu, and the soft grow of the Library's posters.
 */

/** What the pages read last, kept while Glassio runs: a page shows it at once and reads again behind it. */
private object LiveMemory {
    @Volatile var ready: Boolean? = null
    @Volatile var page: LiveHome.Page? = null
    @Volatile var sports: List<LiveHome.Airing> = emptyList()
    @Volatile var following: LiveHome.Following? = null
    @Volatile var readAt = 0L
}

/** Reads both pages ahead (the menu just opened): opening one then shows it at once. At most once a minute. */
fun prefetchLive(app: GlassApp) {
    val now = System.currentTimeMillis()
    if (now - LiveMemory.readAt < 60_000) return
    LiveMemory.readAt = now
    Thread({
        runCatching {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            Thread.sleep(700)       // the menu opens first: this reads files and the guide
            val r = timed("ready") { LiveHome.ready(app) }
            LiveMemory.ready = r
            if (r) {
                LiveMemory.page = timed("Live TV page") { LiveHome.page(app) }
                LiveMemory.sports = timed("sports") { LiveHome.sportsNow(app) }
            }
            LiveMemory.following = timed("Following page") { LiveHome.following(app) }
        }
    }, "live-prefetch").apply { isDaemon = true }.start()
}

private inline fun <T> timed(what: String, read: () -> T): T {
    val t = android.os.SystemClock.elapsedRealtime()
    return read().also { com.glasslauncher.tv.data.Glog.i("Live: $what read in ${android.os.SystemClock.elapsedRealtime() - t} ms") }
}

private val LIVE_RED = Color(0xFFE5322D)
private val INK = Color(0xFF111114)

/** Live TV: the channel last watched, the guide, On now (favorites and recent channels), sports, starting soon. */
@Composable
fun LiveTvScreen(app: GlassApp, onClose: () -> Unit, onFollowing: () -> Unit) {
    val s = LocalScale.current
    val ctx = LocalContext.current
    BackHandler { onClose() }
    var ready by remember { mutableStateOf(LiveMemory.ready) }
    var page by remember { mutableStateOf(LiveMemory.page) }
    var sports by remember { mutableStateOf(LiveMemory.sports) }
    var rev by remember { mutableIntStateOf(0) }
    // The page now from what the TV has (the sports row, a search of the whole guide, just after); what's on from
    // the provider and the shows' pictures fill in after that.
    LaunchedEffect(Unit) {
        while (true) {
            val r = withContext(Dispatchers.IO) { timed("ready") { LiveHome.ready(app) } }
            val p = if (r) withContext(Dispatchers.IO) { timed("Live TV page") { LiveHome.page(app) } } else null
            ready = r; page = p; rev++
            LiveMemory.ready = r; LiveMemory.page = p
            if (p != null) {
                sports = withContext(Dispatchers.IO) { timed("sports") { LiveHome.sportsNow(app) } }
                LiveMemory.sports = sports
                if (withContext(Dispatchers.IO) { LiveHome.fillFromProvider(app, p) }) rev++
                repeat(6) {
                    delay(2_500)
                    val sp = sports
                    if (withContext(Dispatchers.IO) { LiveHome.fillArt(app, p) or LiveHome.fillArt(app, sp) }) rev++
                }
            }
            delay(45_000)
        }
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(ready, page != null) { if (ready != null) focusFirst(first) }
    val r = ready
    val p = page
    val sp = sports
    val shown = rev
    LivePage(s) {
        header("Live TV", if (r == false) "Watch your TV channels with a full guide" else "OK watches · in the guide, hold OK for a channel's menu", s)
        if (r == false) {
            item(key = "setup") {
                EmptyCardView("Set up Live TV", "Sign in with your provider's details: Xtream Codes, an M3U link or a MAG portal. They stay on the TV.",
                    s, Modifier.width((520 * s).dp).height((130 * s).dp).focusRequester(first)) { open(ctx, LiveHome.open(ctx, -1, false)) }
            }
            return@LivePage
        }
        if (p == null) return@LivePage
        item(key = "top") {
            Row(Modifier.fillMaxWidth().height((150 * s).dp), horizontalArrangement = Arrangement.spacedBy((16 * s).dp)) {
                FeatureCard(p.last, s, shown, Modifier.width((520 * s).dp).fillMaxHeight().focusRequester(first)) {
                    open(ctx, LiveHome.open(ctx, p.last?.channel ?: -1, false))
                }
                ActionTile("TV guide", "guide", s, Modifier.weight(1f).fillMaxHeight()) { open(ctx, LiveHome.open(ctx, -1, true)) }
                ActionTile("Search", "search", s, Modifier.weight(1f).fillMaxHeight()) { open(ctx, LiveHome.search(ctx)) }
                ActionTile("Following", "star", s, Modifier.weight(1f).fillMaxHeight()) { onFollowing() }
            }
        }
        cardRow("On now", p.onNow, s, now = true, shown) { open(ctx, LiveHome.open(ctx, it.channel, false)) }
        cardRow("Sports live now", sp, s, now = true, shown) { open(ctx, LiveHome.open(ctx, it.channel, false)) }
        cardRow("Starting soon", p.soon, s, now = false, shown) { open(ctx, LiveHome.open(ctx, it.channel, false)) }
        if (p.onNow.isEmpty() && sp.isEmpty() && p.soon.isEmpty())
            hint("Your favorite channels show here, with what's on now. Add some in Live TV: hold OK on a channel.", s)
    }
}

/** Following: the next match of what you follow in a spotlight, then what you follow, then what's coming up. */
@Composable
fun FollowingScreen(app: GlassApp, onClose: () -> Unit) {
    val s = LocalScale.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var team by remember { mutableStateOf<String?>(null) }
    var data by remember { mutableStateOf(LiveMemory.following) }
    var teamData by remember { mutableStateOf<LiveHome.Team?>(null) }
    var ready by remember { mutableStateOf(true) }
    var dialog by remember { mutableStateOf<DialogState?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var rev by remember { mutableIntStateOf(0) }
    var stopArmedAt by remember { mutableLongStateOf(0L) }
    /** The team whose page was just left: back on Following, the focus goes to its chip. */
    var cameFrom by remember { mutableStateOf<String?>(null) }
    val leaveTeam = { cameFrom = team; team = null; teamData = null }
    BackHandler(enabled = dialog == null) { if (team != null) leaveTeam() else onClose() }
    LaunchedEffect(team, reload) {
        while (true) {
            val t = team
            val r = withContext(Dispatchers.IO) { LiveHome.ready(app) }
            if (t == null) data = withContext(Dispatchers.IO) { timed("Following page") { LiveHome.following(app) } }.also { LiveMemory.following = it }
            else teamData = withContext(Dispatchers.IO) { timed("team page") { LiveHome.team(app, t) } }
            ready = r
            rev++
            delay(60_000)
        }
    }
    // The live score (ESPN's boards, only for a followed team on air) read while the page is open.
    androidx.compose.runtime.DisposableEffect(Unit) {
        val watching = LiveHome.watchScores(app) { reload++ }
        onDispose { LiveHome.stopScores(app, watching) }
    }
    val first = remember { FocusRequester() }
    val chip = remember { FocusRequester() }
    LaunchedEffect(team, data != null, teamData != null) {
        val back = cameFrom
        if (team == null && back != null && data?.terms?.contains(back) == true) focusFirst(chip) else focusFirst(first)
    }
    val follow: () -> Unit = {
        dialog = TextEntry("Follow a team or a show", "", "Every match and episode in the guide shows here, with reminders when it starts.") { name ->
            if (name.isNotBlank()) scope.launch {
                withContext(Dispatchers.IO) { LiveHome.follow(app, name) }
                toast(ctx, "Following ${name.trim()}")
                delay(1_500); reload++      // its airings come in the background
            }
        }
    }
    val toggle: (LiveHome.Airing, Boolean) -> Unit = { a, remind ->
        scope.launch { toast(ctx, withContext(Dispatchers.IO) { LiveHome.toggle(app, a, remind) }); rev++ }
    }
    val watch: (LiveHome.Airing) -> Unit = { open(ctx, LiveHome.open(ctx, it.channel, false)) }
    val t = team
    val d = data
    val td = teamData
    val r = ready
    val shown = rev
    val armed = stopArmedAt != 0L && System.currentTimeMillis() - stopArmedAt < 5_000

    LivePage(s) {
        if (t == null) {
            header("Following", if (!r) "Sign in to Live TV to see when your teams and shows are on" else "The teams and shows you follow, from the TV guide", s)
            if (d == null) return@LivePage
            val spot = d.spot
            item(key = "spot") {
                if (spot != null) Spotlight(spot, s, shown, Modifier.fillMaxWidth().height((170 * s).dp).focusRequester(first)) { watch(spot) }
                else EmptyCardView(
                    if (d.terms.isEmpty()) "Follow your teams and shows" else "Nothing in the next hours",
                    if (d.terms.isEmpty()) "Choose a team or a show: every match shows here, with the live score, and a reminder before it starts."
                    else "Upcoming matches and episodes show here as soon as they're in the guide.",
                    s, Modifier.fillMaxWidth().height((120 * s).dp).focusRequester(first), onClick = follow,
                )
            }
            section("Following", s)
            item(key = "chips") {
                EdgeRow(s, (10 * s).dp, (4 * s).dp) { first ->
                    items(d.terms.size, key = { "t:" + d.terms[it] }) { i ->
                        val term = d.terms[i]
                        ChipView(term, s, (if (i == 0) first else Modifier).then(if (term == cameFrom) Modifier.focusRequester(chip) else Modifier), onLong = {
                            dialog = MenuDialog(term, listOf(
                                "Open" to { stopArmedAt = 0; team = term },
                                "Stop following" to {
                                    scope.launch { withContext(Dispatchers.IO) { LiveHome.unfollow(app, term) }; toast(ctx, "Stopped following $term"); reload++ }
                                },
                            ))
                        }) { stopArmedAt = 0; team = term }
                    }
                    item(key = "more") { ChipView("+  Follow a team or show", s, if (d.terms.isEmpty()) first else Modifier, null, follow) }
                    if (r) item(key = "search") { ChipView("Search in Live TV", s, Modifier, null) { open(ctx, LiveHome.search(ctx)) } }
                }
            }
            eventRows("Also on", d.also, s, false, shown, toggle, watch)
            eventRows("Coming up", d.next, s, false, shown, toggle, watch)
            eventRows("Replays and shows", d.shows, s, false, shown, toggle, watch)
        } else {
            header(t, "The next 7 days in the guide · Remind and Record at the right", s)
            item(key = "teamChips") {
                Row(horizontalArrangement = Arrangement.spacedBy((10 * s).dp)) {
                    ChipView("‹  Following", s, Modifier.focusRequester(first), null) { leaveTeam() }
                    ChipView(if (armed) "OK again to stop following" else "Stop following", s, Modifier, null) {
                        val now = System.currentTimeMillis()
                        if (stopArmedAt == 0L || now - stopArmedAt > 5_000) stopArmedAt = now
                        else {
                            stopArmedAt = 0
                            scope.launch {
                                withContext(Dispatchers.IO) { LiveHome.unfollow(app, t) }
                                toast(ctx, "Stopped following $t")
                                team = null; teamData = null; reload++
                            }
                        }
                    }
                }
            }
            if (td == null) { hint("Looking in the guide…", s); return@LivePage }
            if (td.matches.isEmpty() && td.shows.isEmpty()) hint("No matches in the next 7 days. They show here as soon as they're in the guide.", s)
            td.matches.forEach { a ->
                if (a.day.isNotEmpty()) section(a.day, s, "day:" + a.key())
                item(key = "m:" + a.key()) { EventRowView(a, s, shown, true, toggle) { watch(a) } }
            }
            eventRows("Replays and shows", td.shows, s, true, shown, toggle, watch)
        }
    }
    DialogHost(dialog) { dialog = null }
}

// MARK: - The page

/** The graphite page: one scrolling column, the selected row kept at the same height (as the Library does). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun LivePage(s: Float, content: LazyListScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF16161A))) {
        val density = LocalDensity.current
        val bw = with(density) { maxWidth.roundToPx() }; val bh = with(density) { maxHeight.roundToPx() }
        val bg = remember(bw, bh) { graphitePage(bw, bh) }
        androidx.compose.foundation.Image(bg, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        val rowScroll = androidx.compose.foundation.gestures.LocalBringIntoViewSpec.current
        CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides RowPivot, LocalRowScroll provides rowScroll) {
            LazyColumn(
                contentPadding = PaddingValues(start = (MARGIN * s).dp, end = (MARGIN * s).dp, top = (34 * s).dp, bottom = (60 * s).dp),
                verticalArrangement = Arrangement.spacedBy((12 * s).dp),
                modifier = Modifier.fillMaxSize(),
                content = content,
            )
        }
    }
}

/** The page's margin at each side. */
private const val MARGIN = 56f

/** The default sideways scrolling, kept for the rows (the page itself keeps the selected row at one height). */
private val LocalRowScroll = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.foundation.gestures.BringIntoViewSpec?> { null }

/** A row of cards from one edge of the screen to the other, starting at the page's margin. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun EdgeRow(s: Float, gap: Dp, vertical: Dp, content: androidx.compose.foundation.lazy.LazyListScope.(first: Modifier) -> Unit) {
    val rowScroll = LocalRowScroll.current
    val first = remember { FocusRequester() }
    val body = @Composable {
        LazyRow(
            Modifier.requiredWidth((960 * s).dp).focusRestorer(first),
            horizontalArrangement = Arrangement.spacedBy(gap),
            contentPadding = PaddingValues(horizontal = (MARGIN * s).dp, vertical = vertical),
        ) { content(Modifier.focusRequester(first)) }
    }
    if (rowScroll != null) CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides rowScroll) { body() } else body()
}

private fun LazyListScope.header(title: String, sub: String, s: Float) = item(key = "header") {
    Column(Modifier.padding(bottom = (6 * s).dp)) {
        Text(title, color = Color.White, fontSize = (26 * s).sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height((4 * s).dp))
        Text(sub, color = Color.White.copy(alpha = 0.55f), fontSize = (10 * s).sp)
    }
}

private fun LazyListScope.section(title: String, s: Float, key: String = "s:$title") = item(key = key) {
    Text(title, color = Color.White.copy(alpha = 0.8f), fontSize = (13 * s).sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = (10 * s).dp))
}

private fun LazyListScope.hint(text: String, s: Float) = item(key = "hint") {
    Text(text, color = AppleSecondary, fontSize = (11 * s).sp, modifier = Modifier.padding(top = (10 * s).dp))
}

private fun LazyListScope.cardRow(title: String, list: List<LiveHome.Airing>, s: Float, now: Boolean, rev: Int, onOpen: (LiveHome.Airing) -> Unit) {
    if (list.isEmpty()) return
    section(title, s)
    item(key = "row:$title") {
        // By place, not by program: a card whose program changes (what's on, found later) keeps the focus.
        EdgeRow(s, (16 * s).dp, (8 * s).dp) { first ->
            items(list.size) { i -> ProgramCard(list[i], s, rev, now, if (i == 0) first else Modifier) { onOpen(list[i]) } }
        }
    }
}

private fun LazyListScope.eventRows(title: String, list: List<LiveHome.Airing>, s: Float, actions: Boolean, rev: Int,
                                    toggle: (LiveHome.Airing, Boolean) -> Unit, onOpen: (LiveHome.Airing) -> Unit) {
    if (list.isEmpty()) return
    section(title, s)
    list.forEach { a -> item(key = title + ":" + a.key()) { EventRowView(a, s, rev, actions, toggle) { onOpen(a) } } }
}

// MARK: - Pieces

/** How a selectable thing shows it's selected, as everywhere in Glassio. */
private enum class Look {
    /** A picture card (Library posters, Continue Watching): it grows and its soft shadow deepens. */
    LIFT,
    /** A row of text (Settings): its glass brightens. */
    ROW,
    /** A button (the menu's rows): the white plate, dark text on it. */
    PLATE,
}

/** Something you can select, in one of Glassio's [Look]s. [content] gets 0..1 for the focus. */
@Composable
private fun Focusable(
    modifier: Modifier, radius: Dp, onLong: (() -> Unit)? = null, onClick: () -> Unit,
    grow: Float = 1.05f, look: Look = Look.LIFT, content: @Composable BoxScope.(Float) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val f by animateFloatAsState(if (focused) 1f else 0f, tween(160, easing = FastOutSlowInEasing), label = "liveFocus")
    val shape = RoundedCornerShape(radius)
    Box(
        modifier
            .graphicsLayer { val k = 1f + ((if (look == Look.ROW) 1f else grow) - 1f) * f; scaleX = k; scaleY = k }
            .then(if (look != Look.LIFT || DebugHide.intValue and 4194304 != 0) Modifier else Modifier.drawBehind {
                val sh = CardShadow.get()
                val pad = size.width * CardShadow.PAD
                drawImage(sh, dstOffset = androidx.compose.ui.unit.IntOffset((-pad).toInt(), (-pad + size.height * (0.03f + 0.04f * f)).toInt()),
                    dstSize = androidx.compose.ui.unit.IntSize((size.width + 2 * pad).toInt(), (size.height + 2 * pad).toInt()),
                    alpha = 0.45f + 0.35f * f, filterQuality = androidx.compose.ui.graphics.FilterQuality.Low)
            })
            .clip(shape)
            .background(when (look) {
                Look.PLATE -> lerp(Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.95f), f)
                Look.ROW -> lerp(Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.26f), f)
                Look.LIFT -> Color.White.copy(alpha = 0.07f)
            })
            .onFocusChanged { focused = it.isFocused }
            .tvPress(onLong = onLong, onClick = onClick),
    ) { content(f) }
}

@Composable
private fun ChipView(text: String, s: Float, modifier: Modifier, onLong: (() -> Unit)?, onClick: () -> Unit) {
    Focusable(modifier.height((30 * s).dp), (15 * s).dp, onLong, onClick, grow = 1.04f, look = Look.PLATE) { f ->
        Text(text, color = lerp(Color.White, INK, f), fontSize = (11 * s).sp, fontWeight = FontWeight.Medium,
            maxLines = 1, modifier = Modifier.align(Alignment.Center).padding(horizontal = (16 * s).dp))
    }
}

@Composable
private fun EmptyCardView(title: String, sub: String, s: Float, modifier: Modifier, onClick: () -> Unit) {
    Focusable(modifier, (18 * s).dp, onClick = onClick, look = Look.ROW) { _ ->
        Column(Modifier.align(Alignment.CenterStart).padding(horizontal = (26 * s).dp)) {
            Text(title, color = Color.White, fontSize = (17 * s).sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height((6 * s).dp))
            Text(sub, color = AppleSecondary, fontSize = (10.5f * s).sp, maxLines = 3)
            Spacer(Modifier.height((10 * s).dp))
            Text("OK", color = Color.White.copy(alpha = 0.5f), fontSize = (9 * s).sp, fontWeight = FontWeight.SemiBold, letterSpacing = (0.8f * s).sp)
        }
    }
}

/** The channel last watched: its program's picture behind, the title and how far in it is. */
@Composable
private fun FeatureCard(a: LiveHome.Airing?, s: Float, rev: Int, modifier: Modifier, onClick: () -> Unit) {
    rev.hashCode()     // read: a refresh (the program found, its picture) redraws the card
    Focusable(modifier, (18 * s).dp, onClick = onClick, grow = 1.03f) { _ ->
        if (a != null && a.art.isNotEmpty()) {
            Picture(a.art, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.82f), Color.Black.copy(alpha = 0.35f), Color.Transparent))))
        }
        Column(Modifier.align(Alignment.BottomStart).padding((20 * s).dp)) {
            Text(if (a == null) "LIVE TV" else "CONTINUE WATCHING", color = Color.White.copy(alpha = 0.7f), fontSize = (8.5f * s).sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = (1f * s).sp)
            Spacer(Modifier.height((4 * s).dp))
            Text(when { a == null -> "Open Live TV"; a.title.isNotEmpty() -> a.title; else -> a.channelName }, color = Color.White,
                fontSize = (19 * s).sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height((4 * s).dp))
            if (a != null) Row(verticalAlignment = Alignment.CenterVertically) {
                if (a.logo.isNotEmpty()) { Logo(a.logo, Modifier.size((30 * s).dp, (16 * s).dp)); Spacer(Modifier.width((8 * s).dp)) }
                Text(channelLine(a), color = AppleSecondary, fontSize = (10 * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (a != null && a.end > a.start) {
                Spacer(Modifier.height((8 * s).dp))
                Progress(a, s, Modifier.width((200 * s).dp))
            }
        }
    }
}

@Composable
private fun ActionTile(label: String, glyph: String, s: Float, modifier: Modifier, onClick: () -> Unit) {
    Focusable(modifier, (18 * s).dp, onClick = onClick, grow = 1.05f, look = Look.PLATE) { f ->
        val c = lerp(Color.White, INK, f)
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size((26 * s).dp)) { liveGlyph(glyph, c) }
            Spacer(Modifier.height((10 * s).dp))
            Text(label, color = c, fontSize = (11.5f * s).sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

/** A program card: its picture (else the channel's logo on glass), then the title and the channel or the time. */
@Composable
private fun ProgramCard(a: LiveHome.Airing, s: Float, rev: Int, now: Boolean, modifier: Modifier, onClick: () -> Unit) {
    rev.hashCode()
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.width((168 * s).dp).onFocusChanged { focused = it.hasFocus }) {
        Focusable(modifier.fillMaxWidth().height((94.5f * s).dp), (12 * s).dp, onClick = onClick, grow = 1.07f) { _ ->
            if (a.art.isNotEmpty()) Picture(a.art, Modifier.fillMaxSize())
            else if (a.logo.isNotEmpty()) Logo(a.logo, Modifier.align(Alignment.Center).size((96 * s).dp, (48 * s).dp))
            else Text(a.channelName, color = Color.White.copy(alpha = 0.75f), fontSize = (12 * s).sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, modifier = Modifier.align(Alignment.Center).padding((10 * s).dp))
            if (a.art.isNotEmpty() && a.logo.isNotEmpty()) Box(Modifier.align(Alignment.TopStart).padding((7 * s).dp).clip(RoundedCornerShape((5 * s).dp))
                .background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = (5 * s).dp, vertical = (3 * s).dp)) {
                Logo(a.logo, Modifier.size((28 * s).dp, (13 * s).dp))
            }
            if (a.live) LivePill(s, Modifier.align(Alignment.TopEnd).padding((7 * s).dp))
            if (now && a.end > a.start) Progress(a, s, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = (10 * s).dp, vertical = (8 * s).dp))
        }
        Spacer(Modifier.height((8 * s).dp))
        Text(a.title.ifEmpty { a.channelName }, color = Color.White.copy(alpha = if (focused) 0.95f else 0.75f), fontSize = (10 * s).sp,
            fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(if (now) channelLine(a) else "${LiveHome.time(a.start)} · ${LiveHome.until(a.start - System.currentTimeMillis())} · ${a.channelName}",
            color = AppleSecondary.copy(alpha = 0.8f), fontSize = (8.5f * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The next match: the competition, the teams large, LIVE and the score (or when it starts), the channel. */
@Composable
private fun Spotlight(a: LiveHome.Airing, s: Float, rev: Int, modifier: Modifier, onClick: () -> Unit) {
    rev.hashCode()
    val now = System.currentTimeMillis()
    val on = a.start <= now
    Focusable(modifier, (20 * s).dp, onClick = onClick, grow = 1.02f) { _ ->
        if (a.art.isNotEmpty()) {
            Picture(a.art, Modifier.align(Alignment.CenterEnd).fillMaxHeight().width((420 * s).dp))
            Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width((420 * s).dp)
                .background(Brush.horizontalGradient(listOf(Color(0xFF1E1E22), Color(0xFF1E1E22).copy(alpha = 0.55f), Color.Transparent))))
        }
        Column(Modifier.align(Alignment.CenterStart).padding(horizontal = (28 * s).dp)) {
            // The line under a match is "competition  ·  Also on …": the competition on top, the other channels below.
            val comp = a.sub.substringBefore("  ·  ").takeIf { !it.startsWith("Also on ") }.orEmpty()
            val also = a.sub.substringAfter("Also on ", "")
            Text(comp.ifEmpty { "FOLLOWING" }.uppercase(), color = Color.White.copy(alpha = 0.7f), fontSize = (9 * s).sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = (1.2f * s).sp, maxLines = 1)
            Spacer(Modifier.height((6 * s).dp))
            Text(a.teams.ifEmpty { a.title }, color = Color.White, fontSize = (28 * s).sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width((560 * s).dp))
            Spacer(Modifier.height((10 * s).dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (on) {
                    LivePill(s)
                    Spacer(Modifier.width((10 * s).dp))
                    Text(a.score.ifEmpty { "Started at ${LiveHome.time(a.start)}" }, color = Color.White,
                        fontSize = ((if (a.score.isEmpty()) 13 else 18) * s).sp, fontWeight = FontWeight.SemiBold)
                } else {
                    Text("${LiveHome.day(a.start, now)}  ·  ${LiveHome.time(a.start)}   ·   ${LiveHome.until(a.start - now)}",
                        color = Color.White, fontSize = (13 * s).sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height((14 * s).dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (a.logo.isNotEmpty()) { Logo(a.logo, Modifier.size((36 * s).dp, (18 * s).dp)); Spacer(Modifier.width((8 * s).dp)) }
                Text(channelLine(a) + (if (also.isEmpty()) "" else "  ·  Also on $also"),
                    color = AppleSecondary, fontSize = (10 * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A row of what's coming up: the day and the time, the match and its competition, the channel; LIVE or the bell. */
@Composable
private fun EventRowView(a: LiveHome.Airing, s: Float, rev: Int, actions: Boolean, toggle: (LiveHome.Airing, Boolean) -> Unit, onOpen: () -> Unit) {
    rev.hashCode()     // a reminder or a recording set: the bell and the dot redraw
    val now = System.currentTimeMillis()
    val on = a.start <= now
    val ahead = actions && a.start > now
    Row(Modifier.fillMaxWidth().height((50 * s).dp), horizontalArrangement = Arrangement.spacedBy((10 * s).dp)) {
        Focusable(Modifier.weight(1f).fillMaxHeight(), (14 * s).dp, onClick = onOpen, look = Look.ROW) { _ ->
            Row(Modifier.fillMaxSize().padding(horizontal = (18 * s).dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width((96 * s).dp)) {
                    Text(LiveHome.day(a.start, now).uppercase(), color = Color.White.copy(alpha = 0.6f), fontSize = (8 * s).sp,
                        fontWeight = FontWeight.SemiBold, letterSpacing = (0.8f * s).sp, maxLines = 1)
                    Text(LiveHome.time(a.start), color = Color.White, fontSize = (12 * s).sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
                Box(Modifier.padding(vertical = (12 * s).dp).width(0.6.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.16f)))
                Spacer(Modifier.width((16 * s).dp))
                Column(Modifier.weight(1f)) {
                    Text(a.teams.ifEmpty { a.title }, color = Color.White, fontSize = (12 * s).sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (a.sub.isNotEmpty()) Text(a.sub, color = AppleSecondary, fontSize = (9 * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (a.logo.isNotEmpty()) { Logo(a.logo, Modifier.size((34 * s).dp, (16 * s).dp)); Spacer(Modifier.width((8 * s).dp)) }
                Text(a.channelName, color = Color.White.copy(alpha = 0.8f), fontSize = (10 * s).sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.width((130 * s).dp))
                if (on) {
                    LivePill(s)
                    if (a.score.isNotEmpty()) { Spacer(Modifier.width((8 * s).dp)); Text(a.score, color = Color.White, fontSize = (11 * s).sp, fontWeight = FontWeight.SemiBold) }
                } else if (!ahead && (a.remind || a.record)) {
                    Canvas(Modifier.size((14 * s).dp)) { liveGlyph(if (a.record) "recordOn" else "bell", if (a.record) LIVE_RED else Color.White) }
                }
            }
        }
        if (ahead) {
            RoundAction(if (a.remind) "bell" else "bellOff", a.remind, s) { toggle(a, true) }
            RoundAction("record", a.record, s) { toggle(a, false) }
        }
    }
}

/** Remind or Record beside a row: filled when set, the white plate when selected. */
@Composable
private fun RoundAction(glyph: String, on: Boolean, s: Float, onClick: () -> Unit) {
    Focusable(Modifier.size((50 * s).dp), (25 * s).dp, onClick = onClick, grow = 1.08f, look = Look.PLATE) { f ->
        val rec = glyph == "record" && on
        val c = if (rec) LIVE_RED else lerp(if (on) Color.White else Color.White.copy(alpha = 0.7f), INK, f)
        Canvas(Modifier.align(Alignment.Center).size((18 * s).dp)) { liveGlyph(if (rec) "recordOn" else glyph, c) }
    }
}

@Composable
private fun LivePill(s: Float, modifier: Modifier = Modifier) {
    Text("LIVE", color = Color.White, fontSize = (8 * s).sp, fontWeight = FontWeight.Bold, letterSpacing = (0.8f * s).sp,
        modifier = modifier.clip(RoundedCornerShape((4 * s).dp)).background(LIVE_RED).padding(horizontal = (6 * s).dp, vertical = (2.5f * s).dp))
}

@Composable
private fun Progress(a: LiveHome.Airing, s: Float, modifier: Modifier) {
    val k = ((System.currentTimeMillis() - a.start).toFloat() / (a.end - a.start).coerceAtLeast(1)).coerceIn(0f, 1f)
    Canvas(modifier.height((3 * s).dp)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(Color.White.copy(alpha = 0.25f), cornerRadius = r)
        drawRoundRect(Color.White, size = Size(size.width * k, size.height), cornerRadius = r)
    }
}

@Composable
private fun Picture(url: String, modifier: Modifier) {
    val ctx = LocalContext.current
    val req = remember(url) { ImageRequest.Builder(ctx).data(url).size(640, 360).build() }
    AsyncImage(req, null, contentScale = ContentScale.Crop, modifier = modifier)
}

@Composable
private fun Logo(url: String, modifier: Modifier) {
    val ctx = LocalContext.current
    val req = remember(url) { ImageRequest.Builder(ctx).data(url).size(256, 128).build() }
    AsyncImage(req, null, contentScale = ContentScale.Fit, modifier = modifier)
}

private suspend fun focusFirst(req: FocusRequester) {
    repeat(25) {
        delay(40)
        if (runCatching { req.requestFocus() }.getOrDefault(false)) return
    }
}

private fun channelLine(a: LiveHome.Airing) = (if (a.number > 0) "${a.number}  ·  " else "") + a.channelName

private fun open(ctx: Context, i: Intent) {
    try { ctx.startActivity(i) } catch (e: Exception) { toast(ctx, "Live TV couldn't open") }
}

/** Thin line glyphs, as the menu's. */
private fun DrawScope.liveGlyph(kind: String, c: Color) {
    val w = size.width
    val st = Stroke(w * 0.09f, cap = StrokeCap.Round)
    when (kind) {
        "guide" -> {
            drawRoundRect(c, Offset(w * 0.06f, w * 0.14f), Size(w * 0.88f, w * 0.72f), CornerRadius(w * 0.12f), style = st)
            for (y in listOf(0.38f, 0.62f)) drawLine(c, Offset(w * 0.06f, w * y), Offset(w * 0.94f, w * y), w * 0.07f)
            drawLine(c, Offset(w * 0.36f, w * 0.14f), Offset(w * 0.36f, w * 0.86f), w * 0.07f)
        }
        "search" -> {
            drawCircle(c, w * 0.28f, Offset(w * 0.42f, w * 0.42f), style = st)
            drawLine(c, Offset(w * 0.63f, w * 0.63f), Offset(w * 0.88f, w * 0.88f), w * 0.1f, StrokeCap.Round)
        }
        "star" -> {
            val p = Path()
            for (i in 0 until 10) {
                val r = if (i % 2 == 0) w * 0.44f else w * 0.19f
                val a = Math.toRadians(-90.0 + i * 36.0)
                val x = (w / 2 + r * Math.cos(a)).toFloat(); val y = (w * 0.53f + r * Math.sin(a)).toFloat()
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close()
            drawPath(p, c, style = Stroke(w * 0.08f, join = StrokeJoin.Round))
        }
        "bell", "bellOff" -> {
            val p = Path().apply {
                moveTo(w * 0.2f, w * 0.72f); lineTo(w * 0.8f, w * 0.72f)
                lineTo(w * 0.72f, w * 0.6f); lineTo(w * 0.72f, w * 0.42f)
                cubicTo(w * 0.72f, w * 0.2f, w * 0.28f, w * 0.2f, w * 0.28f, w * 0.42f)
                lineTo(w * 0.28f, w * 0.6f); close()
            }
            if (kind == "bell") drawPath(p, c) else drawPath(p, c, style = st)
            drawLine(c, Offset(w * 0.42f, w * 0.86f), Offset(w * 0.58f, w * 0.86f), w * 0.09f, StrokeCap.Round)
        }
        "record" -> drawCircle(c, w * 0.34f, Offset(w / 2, w / 2), style = st)
        "recordOn" -> drawCircle(c, w * 0.36f, Offset(w / 2, w / 2))
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private object RowPivot : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        // The selected row at a third of the page (the first rows simply stay where they are).
        val target = containerSize * 0.32f
        val d = if (size > containerSize) offset else offset - target
        return if (kotlin.math.abs(d) < 24f) 0f else d
    }
}

/** The graphite page: a soft gradient from the top left, dithered so the TV shows no steps. */
private fun graphitePage(w: Int, h: Int): ImageBitmap {
    val b = android.graphics.Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
    val p = android.graphics.Paint(android.graphics.Paint.DITHER_FLAG)
    p.shader = android.graphics.LinearGradient(0f, 0f, w * 0.25f, h.toFloat(),
        intArrayOf(0xFF2E2E34.toInt(), 0xFF1D1D21.toInt(), 0xFF0E0E11.toInt()), floatArrayOf(0f, 0.55f, 1f), android.graphics.Shader.TileMode.CLAMP)
    android.graphics.Canvas(b).drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
    b.setHasAlpha(false)
    return b.copy(android.graphics.Bitmap.Config.ARGB_8888, false).also { b.recycle(); it.prepareToDraw() }.asImageBitmap()
}
