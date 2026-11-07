package com.glasslauncher.tv.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.glasslauncher.tv.GlassApp
import com.glasslauncher.tv.live.LiveHome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

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
/** The home page's Continue Watching cards: their red (LIVE, play, progress) and their plate. */
private val CW_RED: Color @Composable @androidx.compose.runtime.ReadOnlyComposable get() = LocalAccent.current
private val CW_CARD = Color(0xFF1C1C1F)
private val INK = Color(0xFF111114)

/** Live TV: the channel last watched, the guide, On now (favorites and recent channels), sports, starting soon. */
@Composable
fun LiveTvScreen(app: GlassApp, downAsk: Int = 0, toTabs: FocusRequester? = null, onClose: () -> Unit, onFollowing: () -> Unit) {
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
    LaunchedEffect(downAsk) { if (downAsk > 0) runCatching { first.requestFocus() } }     // Down from the tabs
    val r = ready
    val p = page
    val sp = sports
    val shown = rev
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        FavoritesPicker(app) { picking = false; rev++ }
        return
    }
    LivePage(s, top = if (r == false || p == null) 82f else 0f) {
        if (r == false || p == null) header("Live TV", if (r == false) "Watch your TV channels with a full guide" else "OK watches · in the guide, hold OK for a channel's menu", s)
        if (r == false) {
            item(key = "setup") {
                EmptyCardView("Set up Live TV", "Sign in with your provider's details: Xtream Codes, an M3U link or a MAG portal. They stay on the TV.",
                    s, Modifier.width((520 * s).dp).height((130 * s).dp).focusRequester(first)) { open(ctx, LiveHome.open(ctx, -1, false)) }
            }
            return@LivePage
        }
        if (p == null) return@LivePage
        item(key = "top") {
            LiveHero(p.last, s, shown, first, toTabs,
                onWatch = { open(ctx, LiveHome.open(ctx, p.last?.channel ?: -1, false)) },
                onGuide = { open(ctx, LiveHome.open(ctx, -1, true)) },
                onSearch = { open(ctx, LiveHome.search(ctx)) },
                onFollowing = if (app.settings.menuFollowing) null else onFollowing)     // a tab of its own, unless turned off
        }
        if (p.onNow.isEmpty()) item(key = "pick") {
            EmptyCardView("Choose your favorite channels", "They show here with what's on now, and first in Live TV.",
                s, Modifier.width((520 * s).dp).height((120 * s).dp)) { picking = true }
        }
        // The channels watched lately (newest first; the last one is the big card above), with what's on them now.
        cardRow("Recently watched", p.recent, s, now = true, shown) { open(ctx, LiveHome.open(ctx, it.channel, false)) }
        cardRow("Favorites on now", p.onNow, s, now = true, shown) { open(ctx, LiveHome.open(ctx, it.channel, false)) }
        cardRow("Sports live now", sp, s, now = true, shown) { open(ctx, LiveHome.open(ctx, it.channel, false)) }
        cardRow("Starting soon", p.soon, s, now = false, shown) { open(ctx, LiveHome.open(ctx, it.channel, false)) }
        if (p.onNow.isNotEmpty()) item(key = "pickMore") {
            ChipView("Change favorite channels", s, Modifier, null) { picking = true }
        }
    }
}

/** Spoiler-free: a followed team's scores hidden or shown again; [done]: the page reads them anew. */
private fun scoresChoice(ctx: Context, term: String, ask: (DialogState) -> Unit, done: () -> Unit): Pair<String, () -> Unit> {
    val hide: (Int) -> Unit = { hours ->
        LiveHome.setSpoilerFree(ctx, term, true, hours)
        toast(ctx, "Scores hidden · $term")
        done()
    }
    return if (LiveHome.spoilerFree(ctx, term)) "Show scores" to {
        LiveHome.setSpoilerFree(ctx, term, false, 0)
        toast(ctx, "Scores shown · $term")
        done()
    } else "Hide scores" to {
        ask(MenuDialog("Hide scores", listOf(
            "For 24 hours" to { hide(24) },
            "For 3 days" to { hide(72) },
            "Until I show them" to { hide(0) },
        )))
    }
}

/** Following: the next match of what you follow in a spotlight, then what you follow, then what's coming up. */
@Composable
fun FollowingScreen(app: GlassApp, downAsk: Int = 0, toTabs: FocusRequester? = null, onClose: () -> Unit) {
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
    var todayShown by remember { mutableStateOf(false) }
    var crests by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val todayFocus = remember { FocusRequester() }
    var today by remember { mutableStateOf<List<LiveHome.Airing>?>(null) }
    var info by remember { mutableStateOf<com.glasslauncher.tv.live.LiveTeamInfo.Info?>(null) }
    BackHandler(enabled = dialog == null) { if (todayShown) todayShown = false else if (team != null) leaveTeam() else onClose() }
    LaunchedEffect(team) {
        info = null
        val t = team
        if (t != null) info = withContext(Dispatchers.IO) { runCatching { LiveHome.teamInfo(app, t) }.getOrNull() }
        else crests = withContext(Dispatchers.IO) {
            runCatching { LiveHome.findTeams(app) }
            LiveHome.followed(app).associateWith { LiveHome.crest(app, it) }
        }
    }
    LaunchedEffect(todayShown) {
        if (todayShown) {
            today = null
            today = withContext(Dispatchers.IO) { timed("Today in sport") { LiveHome.todayInSport(app) } }
            delay(120)
            runCatching { todayFocus.requestFocus() }
        }
    }
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
    LaunchedEffect(downAsk) { if (downAsk > 0) runCatching { first.requestFocus() } }     // Down from the tabs
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
        if (todayShown) {
            header("Today in sport", "Live matches in the guide for the rest of today", s)
            val list = today
            if (list == null) { hint("Looking in the guide…", s); return@LivePage }
            if (list.isEmpty()) hint("Nothing live in the guide for the rest of today.", s)
            list.forEachIndexed { i, a ->
                item(key = "today:" + a.key()) {
                    EventRowView(a, s, shown, true, toggle, if (i == 0) Modifier.focusRequester(todayFocus) else Modifier) { watch(a) }
                }
            }
            return@LivePage
        }
        if (t == null) {
            header("Following", if (!r) "Sign in to Live TV to see when your teams and shows are on" else "The teams and shows you follow, from the TV guide", s)
            if (d == null) return@LivePage
            val spot = d.spot
            item(key = "spot") {
                if (spot != null) Spotlight(spot, s, shown, Modifier.fillMaxWidth().height((170 * s).dp).nav(up = toTabs).focusRequester(first)) { watch(spot) }
                else EmptyCardView(
                    if (d.terms.isEmpty()) "Follow your teams and shows" else "Nothing in the next hours",
                    if (d.terms.isEmpty()) "Choose a team or a show: every match shows here, with the live score, and a reminder before it starts."
                    else "Upcoming matches and episodes show here as soon as they're in the guide.",
                    s, Modifier.fillMaxWidth().height((120 * s).dp).nav(up = toTabs).focusRequester(first), onClick = follow,
                )
            }
            section("Following", s)
            item(key = "chips") {
                EdgeRow(s, (10 * s).dp, (4 * s).dp) { first ->
                    items(d.terms.size, key = { "t:" + d.terms[it] }) { i ->
                        val term = d.terms[i]
                        CrestChip(if (LiveHome.spoilerFree(ctx, term)) "$term · scores hidden" else term, crests[term].orEmpty(), s, modifier = (if (i == 0) first else Modifier).then(if (term == cameFrom) Modifier.focusRequester(chip) else Modifier), onLong = {
                            dialog = MenuDialog(term, listOf(
                                "Open" to { stopArmedAt = 0; team = term },
                                scoresChoice(ctx, term, { dialog = it }) { reload++ },
                                "Stop following" to {
                                    scope.launch { withContext(Dispatchers.IO) { LiveHome.unfollow(app, term) }; toast(ctx, "Stopped following $term"); reload++ }
                                },
                            ))
                        }, onClick = { stopArmedAt = 0; team = term })
                    }
                    item(key = "more") { ChipView("+  Follow a team or show", s, if (d.terms.isEmpty()) first else Modifier, null, follow) }
                    if (r) item(key = "today") { ChipView("Today in sport", s, Modifier, null) { todayShown = true } }
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
                    scoresChoice(ctx, t, { dialog = it }) { reload++ }.let { (label, act) -> ChipView(label, s, Modifier, null, act) }
                    ChipView(if (LiveHome.recordsMatches(ctx, t)) "Recording every match" else "Record every match", s, Modifier, null) {
                        val on = !LiveHome.recordsMatches(ctx, t)
                        scope.launch {
                            withContext(Dispatchers.IO) { LiveHome.setRecordsMatches(app, t, on) }
                            toast(ctx, if (on) "Recording every match · $t" else "Reminders only · $t")
                            reload++
                        }
                    }
                    ChipView("Also called", s, Modifier, null) {
                        dialog = TextEntry("Also called", LiveHome.alsoCalled(ctx, t), "Other names it has in the guide, with commas between them") { names ->
                            scope.launch {
                                withContext(Dispatchers.IO) { LiveHome.setAlsoCalled(app, t, names) }
                                toast(ctx, "Names saved")
                                reload++
                            }
                        }
                    }
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
            info?.let { teamInfoRows(it, s) }
        }
    }
    DialogHost(dialog) { dialog = null }
}

/** A team's page, under its matches: the match's stats and lineups, the table, results and fixtures. Each row can be selected, so the remote can scroll to it. */
private fun LazyListScope.teamInfoRows(info: com.glasslauncher.tv.live.LiveTeamInfo.Info, s: Float) {
    if (info.stats.isNotEmpty()) {
        section("Match stats", s, "stats")
        item(key = "statsHead") { InfoRow(s, info.homeName, "", info.awayName) }
        info.stats.forEach { st -> item(key = "stat:" + st.name) { InfoRow(s, st.home, st.name, st.away) } }
    }
    if (info.homeLineup.isNotEmpty() || info.awayLineup.isNotEmpty()) {
        section("Lineups", s, "lineups")
        if (info.homeLineup.isNotEmpty()) item(key = "lineupA") { InfoTextRow(s, info.homeName, info.homeLineup) }
        if (info.awayLineup.isNotEmpty()) item(key = "lineupB") { InfoTextRow(s, info.awayName, info.awayLineup) }
    }
    if (info.table.isNotEmpty()) {
        section(info.league.ifEmpty { "League table" }, s, "table")
        item(key = "tableHead") { TableRowView(s, "", "", info.columns, false, false) }
        info.table.forEach { r -> item(key = "tr:" + r.rank + r.team) { TableRowView(s, r.rank.toString(), r.team, r.numbers, r.mine, true) } }
    }
    if (info.results.isNotEmpty()) {
        section("Results", s, "results")
        info.results.forEach { g -> item(key = "res:" + g.start) { InfoTextRow(s, day(g.start), g.score) } }
    }
    if (info.fixtures.isNotEmpty()) {
        section("Fixtures", s, "fixtures")
        info.fixtures.forEach { g -> item(key = "fix:" + g.start) { InfoTextRow(s, day(g.start), g.title) } }
    }
}

private fun day(at: Long): String = java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()).format(java.util.Date(at))

@Composable
private fun InfoRow(s: Float, left: String, middle: String, right: String) {
    Focusable(Modifier.fillMaxWidth().height((30 * s).dp), (9 * s).dp, onClick = {}, look = Look.ROW) { _ ->
        RawText(left, color = Color.White, fontSize = (11 * s).sp, maxLines = 1, modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = (14 * s).dp))
        RawText(middle, color = AppleSecondary, fontSize = (10 * s).sp, maxLines = 1, modifier = Modifier.align(Alignment.Center))
        RawText(right, color = Color.White, fontSize = (11 * s).sp, maxLines = 1, modifier = Modifier.align(Alignment.CenterEnd).padding(horizontal = (14 * s).dp))
    }
}

@Composable
private fun InfoTextRow(s: Float, label: String, text: String) {
    Focusable(Modifier.fillMaxWidth().height((30 * s).dp), (9 * s).dp, onClick = {}, look = Look.ROW) { _ ->
        Row(Modifier.align(Alignment.CenterStart).padding(horizontal = (14 * s).dp), verticalAlignment = Alignment.CenterVertically) {
            RawText(label, color = AppleSecondary, fontSize = (10 * s).sp, maxLines = 1, modifier = Modifier.width((90 * s).dp))
            RawText(text, color = Color.White, fontSize = (11 * s).sp, maxLines = 1)
        }
    }
}

@Composable
private fun TableRowView(s: Float, rank: String, team: String, numbers: Array<String>, mine: Boolean, selectable: Boolean) {
    val row: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(horizontal = (14 * s).dp), verticalAlignment = Alignment.CenterVertically) {
            RawText(rank, color = AppleSecondary, fontSize = (10 * s).sp, maxLines = 1, modifier = Modifier.width((30 * s).dp))
            RawText(team, color = if (mine || !selectable) Color.White else AppleSecondary, fontSize = (11 * s).sp,
                fontWeight = if (mine) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, modifier = Modifier.weight(1f))
            numbers.forEach { n ->
                RawText(n, color = if (selectable) Color.White else AppleSecondary, fontSize = (10.5f * s).sp, maxLines = 1, modifier = Modifier.width((46 * s).dp))
            }
        }
    }
    if (!selectable) { Box(Modifier.fillMaxWidth().height((22 * s).dp), contentAlignment = Alignment.CenterStart) { row() }; return }
    Focusable(Modifier.fillMaxWidth().height((30 * s).dp), (9 * s).dp, onClick = {}, look = Look.ROW) { _ ->
        Box(Modifier.align(Alignment.CenterStart)) { row() }
    }
}

// MARK: - The page

/** The graphite page: one scrolling column, the selected row kept at the same height (as the Library does). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun LivePage(s: Float, top: Float = 34f, content: LazyListScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Ds.Bg)) {
        val rowScroll = androidx.compose.foundation.gestures.LocalBringIntoViewSpec.current
        CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides RowPivot, LocalRowScroll provides rowScroll) {
            LazyColumn(
                contentPadding = PaddingValues(start = (MARGIN * s).dp, end = (MARGIN * s).dp, top = (top * s).dp, bottom = (60 * s).dp),
                verticalArrangement = Arrangement.spacedBy((12 * s).dp),
                modifier = Modifier.fillMaxSize(),
                content = content,
            )
        }
    }
}

/** Favorite channels by category: a list at the left, its channels at the right; OK puts one in or takes it out. */
@Composable
private fun FavoritesPicker(app: GlassApp, onClose: () -> Unit) {
    val s = LocalScale.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler { onClose() }
    var picker by remember { mutableStateOf<LiveHome.Picker?>(null) }
    var names by remember { mutableStateOf(emptyList<String>()) }
    var at by remember { mutableIntStateOf(0) }
    var rows by remember { mutableStateOf(emptyList<LiveHome.Picker.Pick>()) }
    var rev by remember { mutableIntStateOf(0) }
    val firstCategory = remember { FocusRequester() }
    val shownCategory = remember { FocusRequester() }
    val firstChannel = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        val p = withContext(Dispatchers.IO) { LiveHome.picker(app) }
        val n = p.categories()
        rows = if (n.isEmpty()) emptyList() else withContext(Dispatchers.IO) { p.channels(0) }
        picker = p
        names = n
    }
    LaunchedEffect(names.isNotEmpty()) { if (names.isNotEmpty()) focusFirst(firstCategory) }
    val p = picker
    val count = rev.let { p?.count() ?: 0 }
    Column(Modifier.fillMaxSize().background(Ds.Bg).padding(start = (MARGIN * s).dp, end = (MARGIN * s).dp, top = (82 * s).dp)) {
        Text("Favorite channels", color = Color.White, fontSize = (22 * s).sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height((3 * s).dp))
        Text("OK adds or removes a channel · Back when you're done · %d chosen".format(count), color = AppleSecondary, fontSize = (10.5f * s).sp)
        Spacer(Modifier.height((14 * s).dp))
        if (p == null) return@Column
        if (names.isEmpty()) {
            Text("Nothing to choose from yet: open Live TV once so it can load your channels.", color = AppleSecondary, fontSize = (11 * s).sp)
            return@Column
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy((18 * s).dp)) {
            LazyColumn(
                Modifier.width((250 * s).dp).fillMaxHeight(),
                contentPadding = PaddingValues(top = (4 * s).dp, bottom = (40 * s).dp),
                verticalArrangement = Arrangement.spacedBy((5 * s).dp),
            ) {
                items(names.size, key = { "c:$it" }) { i ->
                    Focusable(
                        Modifier.fillMaxWidth().height((30 * s).dp)
                            .then(if (i == 0) Modifier.focusRequester(firstCategory) else Modifier)
                            .then(if (i == at) Modifier.focusRequester(shownCategory) else Modifier)
                            .nav(right = firstChannel),
                        (9 * s).dp,
                        onClick = {
                            scope.launch {
                                rows = withContext(Dispatchers.IO) { p.channels(i) }
                                at = i
                                delay(60)
                                runCatching { firstChannel.requestFocus() }
                            }
                        },
                        look = Look.ROW,
                    ) { f ->
                        RawText(names[i], color = lerp(if (i == at) Color.White else AppleSecondary, Color.White, f), fontSize = (10.5f * s).sp,
                            maxLines = 1, modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = (12 * s).dp))
                    }
                }
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxHeight(),
                contentPadding = PaddingValues(top = (4 * s).dp, bottom = (40 * s).dp),
                verticalArrangement = Arrangement.spacedBy((5 * s).dp),
            ) {
                items(rows.size, key = { "r:" + at + ":" + rows[it].id }) { i ->
                    val ch = rows[i]
                    val on = rev.let { p.favorite(ch.id) }
                    Focusable(
                        Modifier.fillMaxWidth().height((30 * s).dp)
                            .then(if (i == 0) Modifier.focusRequester(firstChannel) else Modifier)
                            .nav(left = shownCategory),
                        (9 * s).dp,
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { p.toggle(app, ch.id) }
                                rev++
                            }
                        },
                        look = Look.ROW,
                    ) { f ->
                        Row(Modifier.align(Alignment.CenterStart).padding(horizontal = (12 * s).dp), verticalAlignment = Alignment.CenterVertically) {
                            if (ch.num > 0) {
                                RawText(ch.num.toString(), color = AppleSecondary, fontSize = (10 * s).sp, maxLines = 1, modifier = Modifier.width((44 * s).dp))
                            }
                            RawText(ch.name, color = Color.White, fontSize = (11 * s).sp, maxLines = 1, modifier = Modifier.weight(1f))
                            if (on) Text("Favorite", color = Color.White, fontSize = (9.5f * s).sp, fontWeight = FontWeight.Medium, maxLines = 1)
                        }
                    }
                }
            }
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
    /** A picture card, as the home page's Continue Watching: it grows a little and gets a white outline. */
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
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer { val k = 1f + ((if (look == Look.ROW) 1f else grow) - 1f) * f; scaleX = k; scaleY = k }
            .then(if (look == Look.LIFT && focused) Modifier.border((1.6f * LocalScale.current).dp, Color.White, shape) else Modifier)
            .clip(shape)
            .background(when (look) {
                Look.PLATE -> lerp(Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.95f), f)
                Look.ROW -> lerp(Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.26f), f)
                Look.LIFT -> CW_CARD
            })
            .onFocusChanged { focused = it.isFocused }
            .tvPress(onLong = onLong, onClick = onClick),
    ) { content(f) }
}

@Composable
private fun ChipView(text: String, s: Float, modifier: Modifier, onLong: (() -> Unit)?, onClick: () -> Unit) =
    CrestChip(text, "", s, modifier, onLong, onClick)

@Composable
private fun CrestChip(text: String, icon: String, s: Float, modifier: Modifier, onLong: (() -> Unit)?, onClick: () -> Unit) {
    Focusable(modifier.height((30 * s).dp), (15 * s).dp, onLong, onClick, grow = 1.04f, look = Look.PLATE) { f ->
        Row(Modifier.align(Alignment.Center).padding(horizontal = (16 * s).dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon.isNotEmpty()) {       // a team's crest
                AsyncImage(icon, null, contentScale = ContentScale.Fit, modifier = Modifier.size((16 * s).dp))
                Spacer(Modifier.width((7 * s).dp))
            }
            Text(text, color = lerp(Color.White, INK, f), fontSize = (11 * s).sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
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

/**
 * The top of the page: the channel last watched, its program's picture across the screen behind the tabs, fading into
 * the page; what's on, how far in, and the page's buttons (Watch first). No picture: the same on the plain page.
 */
@Composable
private fun LiveHero(a: LiveHome.Airing?, s: Float, rev: Int, first: FocusRequester, toTabs: FocusRequester?, onWatch: () -> Unit, onGuide: () -> Unit, onSearch: () -> Unit, onFollowing: (() -> Unit)?) {
    rev.hashCode()     // read: a refresh (the program found, its picture) redraws it
    Box(Modifier.requiredWidth((960 * s).dp).height((290 * s).dp)) {
        if (a != null && a.art.isNotEmpty()) {
            Picture(a.art, Modifier.fillMaxSize(), 1280, 720)
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0.1f to Ds.Bg, 0.6f to Ds.Bg.copy(alpha = 0.55f), 1f to Color.Transparent)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Ds.Bg)))
        }
        Column(Modifier.align(Alignment.BottomStart).padding(start = (MARGIN * s).dp, end = (MARGIN * s).dp, bottom = (8 * s).dp)) {
            if (a != null) Row(verticalAlignment = Alignment.CenterVertically) {
                LivePill(s, Modifier)
                Spacer(Modifier.width((8 * s).dp))
                if (a.logo.isNotEmpty()) { Logo(a.logo, Modifier.size((30 * s).dp, (16 * s).dp)); Spacer(Modifier.width((8 * s).dp)) }
                RawText(channelLine(a), color = Color(0xFFE8E8EC), fontSize = (10.5f * s).sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (a.country.isNotEmpty()) { Spacer(Modifier.width((6 * s).dp)); CountryTag(a.country, s) }
            }
            Spacer(Modifier.height((6 * s).dp))
            RawText(when { a == null -> Lang.tr("Live TV"); a.title.isNotEmpty() -> a.title; else -> a.channelName }, color = Color.White, fontSize = (26 * s).sp,
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width((640 * s).dp))
            if (a != null && a.end > a.start) {
                Spacer(Modifier.height((5 * s).dp))
                RawText(LiveHome.time(a.start) + " – " + LiveHome.time(a.end), color = Color(0xFFD0D0D4), fontSize = (10.5f * s).sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height((6 * s).dp))
                Progress(a, s, Modifier.width((180 * s).dp), LocalAccent.current)
            }
            Spacer(Modifier.height((12 * s).dp))
            Row(horizontalArrangement = dsGap(s)) {
                DsButton("Watch", s, Modifier.nav(up = toTabs).focusRequester(first), primary = true, onClick = onWatch)
                DsButton("TV guide", s, Modifier.nav(up = toTabs), onClick = onGuide)
                DsButton("Search", s, Modifier.nav(up = toTabs), onClick = onSearch)
                if (onFollowing != null) DsButton("Following", s, Modifier.nav(up = toTabs), onClick = onFollowing)
            }
        }
    }
}

/** The channel's resolution (Live TV › Settings › Channels › Resolution badges): 4K and 2K gold, FHD white, the rest dark. */
@Composable
private fun ResBadge(t: String, s: Float, modifier: Modifier) {
    val (bg, fg) = when (t) {
        "4K", "2K" -> Color(0xFFE8B84A) to Color(0xFF1C1C1E)
        "FHD" -> Color(0xFFF5F5F7) to Color(0xFF1C1C1E)
        else -> Color(0xD91C1C1E) to Color(0xFFF5F5F7)
    }
    Text(t, color = fg, fontSize = (6.5f * s).sp, fontWeight = FontWeight.Bold,
        modifier = modifier.clip(RoundedCornerShape((3 * s).dp)).background(bg).padding(horizontal = (4 * s).dp, vertical = (1 * s).dp))
}

/**
 * A program card, as the home page's Continue Watching: the picture (else the channel's logo), darker at the bottom
 * with the channel's logo there, LIVE top left and the red progress along the bottom; under it, the title and the
 * channel or the time.
 */
@Composable
private fun ProgramCard(a: LiveHome.Airing, s: Float, rev: Int, now: Boolean, modifier: Modifier, onClick: () -> Unit) {
    rev.hashCode()
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.width((168 * s).dp).onFocusChanged { focused = it.hasFocus }) {
        Focusable(modifier.fillMaxWidth().height((94.5f * s).dp), (6 * s).dp, onClick = onClick) { _ ->
            val picture = a.art.isNotEmpty()
            if (picture) Picture(a.art, Modifier.fillMaxSize())
            else if (a.logo.isNotEmpty()) Logo(a.logo, Modifier.fillMaxSize().padding(horizontal = (34 * s).dp, vertical = (20 * s).dp))
            else RawText(a.channelName, color = Color.White.copy(alpha = 0.75f), fontSize = (12 * s).sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, modifier = Modifier.align(Alignment.Center).padding((10 * s).dp))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f))))
            if (a.live) Text("LIVE", color = Color.White, fontSize = (6.5f * s).sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopStart).padding((6 * s).dp).clip(RoundedCornerShape((3 * s).dp)).background(CW_RED)
                    .padding(horizontal = (4 * s).dp, vertical = (1 * s).dp))
            if (picture && a.logo.isNotEmpty()) Logo(a.logo, Modifier.align(Alignment.BottomStart)
                .padding(start = (8 * s).dp, bottom = (9 * s).dp).size(width = (52 * s).dp, height = (20 * s).dp), Alignment.BottomStart)
            if (a.badge.isNotEmpty()) ResBadge(a.badge, s, Modifier.align(Alignment.TopEnd).padding((6 * s).dp))
            if (now && a.end > a.start) {
                val k = ((System.currentTimeMillis() - a.start).toFloat() / (a.end - a.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                val accent = CW_RED
                Canvas(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height((2.5f * s).dp)) {
                    drawRect(Color.White.copy(alpha = 0.25f))
                    drawRect(accent, size = Size(size.width * k, size.height))
                }
            }
        }
        Spacer(Modifier.height((8 * s).dp))
        Text(a.title.ifEmpty { a.channelName }, color = Color.White.copy(alpha = if (focused) 0.95f else 0.75f), fontSize = (10 * s).sp,
            fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (now) channelLine(a) else "${LiveHome.time(a.start)} · ${LiveHome.until(a.start - System.currentTimeMillis())} · ${a.channelName}",
                color = AppleSecondary.copy(alpha = 0.8f), fontSize = (8.5f * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false))
            if (a.country.isNotEmpty()) { Spacer(Modifier.width((5 * s).dp)); CountryTag(a.country, s) }
        }
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
private fun EventRowView(a: LiveHome.Airing, s: Float, rev: Int, actions: Boolean, toggle: (LiveHome.Airing, Boolean) -> Unit, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    rev.hashCode()     // a reminder or a recording set: the bell and the dot redraw
    val now = System.currentTimeMillis()
    val on = a.start <= now
    val ahead = actions && a.start > now
    Row(Modifier.fillMaxWidth().height((50 * s).dp), horizontalArrangement = Arrangement.spacedBy((10 * s).dp)) {
        Focusable(Modifier.weight(1f).fillMaxHeight().then(modifier), (14 * s).dp, onClick = onOpen, look = Look.ROW) { _ ->
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
                    if (a.sub.isNotEmpty()) RawText(a.sub, color = AppleSecondary, fontSize = (9 * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (a.logo.isNotEmpty()) { Logo(a.logo, Modifier.size((34 * s).dp, (16 * s).dp)); Spacer(Modifier.width((8 * s).dp)) }
                Row(Modifier.width((130 * s).dp), verticalAlignment = Alignment.CenterVertically) {
                    RawText(a.channelName, color = Color.White.copy(alpha = 0.8f), fontSize = (10 * s).sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (a.country.isNotEmpty()) { Spacer(Modifier.width((5 * s).dp)); CountryTag(a.country, s) }
                }
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
private fun Progress(a: LiveHome.Airing, s: Float, modifier: Modifier, color: Color = Color.White) {
    val k = ((System.currentTimeMillis() - a.start).toFloat() / (a.end - a.start).coerceAtLeast(1)).coerceIn(0f, 1f)
    Canvas(modifier.height((3 * s).dp)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(Color.White.copy(alpha = 0.25f), cornerRadius = r)
        drawRoundRect(color, size = Size(size.width * k, size.height), cornerRadius = r)
    }
}

@Composable
private fun Picture(url: String, modifier: Modifier, w: Int = 640, h: Int = 360) {
    val ctx = LocalContext.current
    val req = remember(url) { ImageRequest.Builder(ctx).data(url).size(w, h).build() }
    AsyncImage(req, null, contentScale = ContentScale.Crop, modifier = modifier)
}

@Composable
private fun Logo(url: String, modifier: Modifier, alignment: Alignment = Alignment.Center) {
    val ctx = LocalContext.current
    val req = remember(url) { ImageRequest.Builder(ctx).data(url).size(256, 128).build() }
    AsyncImage(req, null, contentScale = ContentScale.Fit, alignment = alignment, modifier = modifier)
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


// MARK: - Today (Home › Today: the day on one page)

/**
 * Today: the weather, your matches today, new episodes (Up Next's and the shows you follow), recordings finished since
 * yesterday and what's set for today. Read again every minute while it shows; a section with nothing goes.
 */
@Composable
fun TodayScreen(app: GlassApp, active: Boolean, onTitle: (com.glasslauncher.tv.data.HeroItem) -> Unit, onClose: () -> Unit) {
    val s = LocalScale.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<LiveHome.Today?>(null) }
    var rev by remember { mutableIntStateOf(0) }
    var reload by remember { mutableIntStateOf(0) }
    val weather by app.weather.now.collectAsState()
    val days by app.weather.days.collectAsState()
    val cw by app.cw.items.collectAsState()
    val episodes = cw.filter { it.upNext }.take(6)
    BackHandler(enabled = active) { onClose() }
    LaunchedEffect(reload) {
        while (true) {
            data = withContext(Dispatchers.IO) { timed("Today page") { runCatching { LiveHome.today(app) }.getOrDefault(LiveHome.Today()) } }
            rev++
            delay(60_000)
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        val watching = LiveHome.watchScores(app) { reload++ }
        onDispose { LiveHome.stopScores(app, watching) }
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(data != null, active) { if (active) focusFirst(first) }
    val toggle: (LiveHome.Airing, Boolean) -> Unit = { a, remind ->
        scope.launch { toast(ctx, withContext(Dispatchers.IO) { LiveHome.toggle(app, a, remind) }); reload++ }
    }
    val watch: (LiveHome.Airing) -> Unit = { open(ctx, LiveHome.open(ctx, it.channel, false)) }
    val shown = rev
    val now = System.currentTimeMillis()
    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    val greeting = when { hour < 5 -> "Good night"; hour < 12 -> "Good morning"; hour < 18 -> "Good afternoon"; else -> "Good evening" }
    val date = java.text.SimpleDateFormat("EEEE d MMMM", Lang.locale()).format(java.util.Date(now))
    val f = app.settings.weatherF
    fun deg(c: Double) = if (c.isNaN()) "–" else ((if (f) c * 9 / 5 + 32 else c).roundToInt().toString() + "°")
    val w = weather
    val today = days.firstOrNull()
    val weatherLine = if (w == null) "" else buildString {
        append(w.temp(f))
        if (today != null) {
            append("  ·  ").append(Lang.weather(today.condition)).append("  ·  ").append(deg(today.hiC)).append(" / ").append(deg(today.loC))
            if (today.rain >= 0) append("  ·  ").append(Lang.tr("Rain ${today.rain}%"))
        }
        if (w.city.isNotBlank()) append("  ·  ").append(w.city)
    }
    var firstGiven = false
    fun firstMod(): Modifier = if (firstGiven) Modifier else { firstGiven = true; Modifier.focusRequester(first) }
    LivePage(s) {
        item(key = "header") {
            Column(Modifier.padding(bottom = (6 * s).dp)) {
                Text(greeting, color = Color.White, fontSize = (26 * s).sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Spacer(Modifier.height((4 * s).dp))
                RawText(date + if (weatherLine.isEmpty()) "" else "  ·  $weatherLine", color = Color.White.copy(alpha = 0.6f), fontSize = (11 * s).sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        val d = data
        if (d == null) { hint("Getting your day…", s); return@LivePage }
        if (d.matches.isNotEmpty()) {
            section("Your matches today", s)
            d.matches.forEach { a -> item(key = "m:" + a.key()) { EventRowView(a, s, shown, true, toggle, firstMod()) { watch(a) } } }
        }
        if (episodes.isNotEmpty() || d.shows.isNotEmpty()) {
            section("New episodes", s)
            episodes.forEach { e ->
                item(key = "e:" + e.key) {
                    TodayRow(s, "UP NEXT", e.sub, e.name, e.platform, firstMod()) { onTitle(e) }
                }
            }
            d.shows.forEach { a -> item(key = "sh:" + a.key()) { EventRowView(a, s, shown, true, toggle, firstMod()) { watch(a) } } }
        }
        if (d.recordings.isNotEmpty()) {
            section("Finished recordings", s)
            d.recordings.forEach { a ->
                item(key = "r:" + a.recording) {
                    TodayRow(s, LiveHome.day(a.end, now).uppercase(), LiveHome.time(a.start), a.title, channelLine(a), firstMod(), a.country) {
                        open(ctx, LiveHome.openRecording(ctx, a.recording))
                    }
                }
            }
        }
        if (d.set.isNotEmpty()) {
            section("Set for today", s)
            d.set.forEach { a -> item(key = "set:" + a.key() + a.record) { EventRowView(a, s, shown, true, toggle, firstMod()) { watch(a) } } }
        }
        if (d.matches.isEmpty() && d.shows.isEmpty() && episodes.isEmpty() && d.recordings.isEmpty() && d.set.isEmpty()) {
            item(key = "empty") {
                EmptyCardView("Nothing planned for today",
                    "Follow your teams and shows, set reminders or recordings in Live TV, and they show here.", s,
                    Modifier.fillMaxWidth().height((120 * s).dp).then(firstMod())) { onClose() }
            }
        }
    }
}

/** A plain row of Today's: a small label over a line at the left, the title, and where at the right. */
@Composable
private fun TodayRow(s: Float, label: String, line: String, title: String, where: String, modifier: Modifier, country: String = "", onClick: () -> Unit) {
    Focusable(Modifier.fillMaxWidth().height((50 * s).dp).then(modifier), (14 * s).dp, onClick = onClick, look = Look.ROW) { _ ->
        Row(Modifier.fillMaxSize().padding(horizontal = (18 * s).dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width((96 * s).dp)) {
                Text(label, color = Color.White.copy(alpha = 0.6f), fontSize = (8 * s).sp, fontWeight = FontWeight.SemiBold,
                    letterSpacing = (0.8f * s).sp, maxLines = 1)
                RawText(line, color = Color.White, fontSize = (12 * s).sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
            Box(Modifier.padding(vertical = (12 * s).dp).width(0.6.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.16f)))
            Spacer(Modifier.width((16 * s).dp))
            RawText(title, color = Color.White, fontSize = (12 * s).sp, fontWeight = FontWeight.Medium, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Row(Modifier.width((170 * s).dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                RawText(where, color = Color.White.copy(alpha = 0.8f), fontSize = (10 * s).sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (country.isNotEmpty()) { Spacer(Modifier.width((5 * s).dp)); CountryTag(country, s) }
            }
        }
    }
}
