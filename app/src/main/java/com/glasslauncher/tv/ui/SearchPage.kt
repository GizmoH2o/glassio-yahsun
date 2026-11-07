package com.glasslauncher.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glasslauncher.tv.GlassApp
import com.glasslauncher.tv.data.HeroItem
import com.glasslauncher.tv.data.Moods
import com.glasslauncher.tv.live.LiveHome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val KEYS = "abcdefghijklmnopqrstuvwxyz1234567890".map { it.toString() }

/**
 * Search everything from one box: your library, titles (Cinemeta, no account needed), what's on in the TV guide, your
 * recordings, Live TV's channels and your apps; each can be left out with Search everything off. A keyboard of
 * its own (the system's covers half the screen), results as you type. [query] is held by Home, so coming back from a
 * title finds it as you left it; [active]: this page is the one on (not under a title's page).
 */
@Composable
fun SearchPage(app: GlassApp, s: Float, query: MutableState<String>, active: Boolean, downAsk: Int = 0, toTabs: FocusRequester? = null, onClose: () -> Unit, onTitle: (HeroItem) -> Unit) {
    val ctx = LocalContext.current
    val lastKey = remember { mutableStateOf("a") }
    val keyFocus = remember { FocusRequester() }
    var inResults by remember { mutableStateOf(false) }
    // Back: from the results to the keyboard, then the words cleared, then out.
    BackHandler(enabled = active) {
        when {
            inResults -> runCatching { keyFocus.requestFocus() }
            query.value.isNotEmpty() -> query.value = ""
            else -> onClose()
        }
    }
    val q = query.value
    val titles by produceState(emptyList<HeroItem>(), q) {
        if (q.length < 2) { value = emptyList(); return@produceState }
        delay(350)      // not at every key
        value = withContext(Dispatchers.IO) { app.recs.search(q) }.take(20)
    }
    val channels by produceState(emptyList<LiveHome.Airing>(), q) {
        if (q.length < 2) { value = emptyList(); return@produceState }
        delay(350)
        value = withContext(Dispatchers.IO) { runCatching { LiveHome.findChannels(ctx, q, 12) }.getOrDefault(emptyList()) }
    }
    val everything = app.settings.searchEverything
    val shows by produceState(emptyList<LiveHome.Airing>(), q, everything) {
        if (q.length < 2 || !everything) { value = emptyList(); return@produceState }
        delay(350)
        value = withContext(Dispatchers.IO) { runCatching { LiveHome.findShows(ctx, q, 12) }.getOrDefault(emptyList()) }
    }
    val recordings by produceState(emptyList<LiveHome.Airing>(), q, everything) {
        if (q.length < 2 || !everything) { value = emptyList(); return@produceState }
        delay(350)
        value = withContext(Dispatchers.IO) { runCatching { LiveHome.findRecordings(ctx, q, 12) }.getOrDefault(emptyList()) }
    }
    val saved by app.library.saved.collectAsState()
    val watch by app.library.watchlist.collectAsState()
    val nuvio by app.library.nuvioList.collectAsState()
    val stremio by app.library.stremioList.collectAsState()
    val mine = remember(q, everything, saved, watch, nuvio, stremio) {
        if (q.length < 2 || !everything) emptyList()
        else (saved + watch + nuvio + stremio).distinctBy { it.key }.filter { it.name.contains(q.trim(), ignoreCase = true) }.take(20)
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val allApps by app.apps.apps.collectAsState()
    val apps = remember(q, allApps) { if (q.length < 2) emptyList() else app.apps.ordered(includeHidden = true).filter { it.label.contains(q, ignoreCase = true) }.take(8) }
    val rows by app.recs.rows.collectAsState()
    LaunchedEffect(Unit) { if (rows.trending.isEmpty()) withContext(Dispatchers.IO) { runCatching { app.recs.refresh() } } }
    // Nothing typed: the mood and time filters, across your library, the catalogs and the charts.
    val moods = app.settings.moodFilters
    val kids = app.settings.kidsMode
    var mood by remember { mutableStateOf<Moods.Mood?>(null) }
    var maxMinutes by remember { mutableStateOf(0) }
    val filtering = moods && q.length < 2 && (mood != null || maxMinutes > 0)
    val catalog by app.catalogs.items.collectAsState()
    val going by app.cw.items.collectAsState()
    val info by app.shelf.info.collectAsState()
    val pool = remember(filtering, saved, watch, nuvio, stremio, catalog, rows) {
        if (!filtering) emptyList<HeroItem>() else (saved + watch + nuvio + stremio) + going.filter { it.type == "movie" || it.type == "series" } + catalog + rows.trending + rows.films + rows.series
    }
    var looking by remember { mutableStateOf(false) }
    LaunchedEffect(pool) {
        if (pool.isEmpty()) return@LaunchedEffect
        looking = true
        runCatching { app.shelf.lookUp(Moods.lacking(pool.distinctBy { it.key }).take(Moods.LOOKUP)) }
        looking = false
    }
    val fitting = remember(pool, mood, maxMinutes, info, kids) {
        val mineKeys = (saved + watch + nuvio + stremio).map { it.key }.toSet()
        Moods.pick(pool.map { app.shelf.filled(it) }, mood, maxMinutes, mineKeys, kids)
    }
    val lastTitle = remember { mutableStateOf<String?>(null) }
    val lastFocus = remember { FocusRequester() }
    LaunchedEffect(downAsk) { if (downAsk > 0) runCatching { keyFocus.requestFocus() } }     // Down from the tabs
    // In front again: back on the title you came from, else the keyboard.
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        delay(120)
        if (lastTitle.value == null || runCatching { lastFocus.requestFocus() }.isFailure) runCatching { keyFocus.requestFocus() }
    }

    // Each row of results, top to bottom; the last column of keys goes to the first, any card's Left to the keyboard.
    val mineRow = remember { RowEntry() }
    val titleRow = remember { RowEntry() }
    val showRow = remember { RowEntry() }
    val recordRow = remember { RowEntry() }
    val channelRow = remember { RowEntry() }
    val appRow = remember { RowEntry() }
    val moodRow = remember { RowEntry() }
    val timeRow = remember { RowEntry() }
    val chips = moods && q.length < 2
    val hasTitles = if (filtering) fitting.isNotEmpty() else if (q.length < 2) rows.trending.isNotEmpty() else titles.isNotEmpty()
    val hasChannels = q.length >= 2 && channels.isNotEmpty()
    val hasApps = q.length >= 2 && apps.isNotEmpty()
    val order = listOfNotNull(moodRow.takeIf { chips }, timeRow.takeIf { chips }, mineRow.takeIf { q.length >= 2 && mine.isNotEmpty() }, titleRow.takeIf { hasTitles },
        showRow.takeIf { q.length >= 2 && shows.isNotEmpty() }, recordRow.takeIf { q.length >= 2 && recordings.isNotEmpty() },
        channelRow.takeIf { hasChannels }, appRow.takeIf { hasApps })
    fun above(r: RowEntry) = order.getOrNull(order.indexOf(r) - 1)?.req
    fun below(r: RowEntry) = order.getOrNull(order.indexOf(r) + 1)?.req ?: FocusRequester.Cancel
    val toResults = order.firstOrNull()?.req ?: FocusRequester.Cancel

    // Voice, where the TV has a recogniser: what's said goes in the box, and it searches as if typed.
    val voice = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { query.value = it.trim().take(60) }
    }
    val voiceAsk = remember(Lang.code.value) {
        android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, Lang.locale().toLanguageTag())
    }
    val canVoice = remember { voiceAsk.resolveActivity(ctx.packageManager) != null }

    Row(Modifier.fillMaxSize().padding(start = (EDGE * s).dp, top = (6 * s).dp), horizontalArrangement = Arrangement.spacedBy((22 * s).dp)) {
        // No scrolling or focus group on either side (it all fits): those columns take the focus themselves, with nothing to show for it.
        Column(Modifier.width((206 * s).dp).fillMaxHeight()) {
            Text(if (everything) "Search everything" else "Titles, channels and apps", color = Text2, fontSize = (8.5f * s).sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height((4 * s).dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape((Ds.R_CONTROL * s).dp)).background(Ds.Surface).padding(horizontal = (11 * s).dp, vertical = (7.5f * s).dp)) {
                Text(q.ifEmpty { "Type with the keys below" }, color = if (q.isEmpty()) Text2 else Color.White, fontSize = (12.5f * s).sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height((8 * s).dp))
            KEYS.chunked(6).forEachIndexed { rowAt, line ->
                Row(Modifier.padding(bottom = (3.5f * s).dp), horizontalArrangement = Arrangement.spacedBy((3.5f * s).dp)) {
                    line.forEach { k ->
                        Key(k, s, lastKey, keyFocus, Modifier.weight(1f).then(if (k == line.last()) Modifier.nav(right = toResults) else Modifier).then(if (rowAt == 0) Modifier.nav(up = toTabs) else Modifier)) { query.value = (q + k).take(60) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy((3.5f * s).dp)) {
                Key("Space", s, lastKey, keyFocus, Modifier.weight(2f)) { if (q.isNotEmpty() && !q.endsWith(" ")) query.value = "$q " }
                Key("Delete", s, lastKey, keyFocus, Modifier.weight(1.4f)) { query.value = q.dropLast(1) }
                Key("Clear all", s, lastKey, keyFocus, Modifier.weight(1.2f).nav(right = toResults)) { query.value = "" }
            }
            if (canVoice) {
                Spacer(Modifier.height((3.5f * s).dp))
                Key("Voice search", s, lastKey, keyFocus, Modifier.fillMaxWidth().nav(right = toResults)) { runCatching { voice.launch(voiceAsk) } }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(androidx.compose.foundation.rememberScrollState()).onFocusChanged { inResults = it.hasFocus }) {
            // Nothing typed: what's trending, so the page is never empty.
            if (q.length < 2) {
                if (chips) {
                    DsHeading("In the mood for", s)
                    LazyRow(state = moodRow.state, contentPadding = PaddingValues(end = (EDGE * s).dp, top = (2 * s).dp, bottom = (3 * s).dp), horizontalArrangement = Arrangement.spacedBy((5 * s).dp)) {
                        val list = if (kids) Moods.ALL.filter { it.label != Moods.KIDS } else Moods.ALL
                        itemsIndexed(list, key = { _, it -> it.label }) { i, it ->
                            Chip(it.label, mood == it, s, moodRow.of(i).nav(up = toTabs, down = timeRow.req, left = if (i == 0) keyFocus else null, right = edge(i == list.lastIndex))) { mood = if (mood == it) null else it }
                        }
                    }
                    LazyRow(state = timeRow.state, contentPadding = PaddingValues(end = (EDGE * s).dp, top = (2 * s).dp, bottom = (4 * s).dp), horizontalArrangement = Arrangement.spacedBy((5 * s).dp)) {
                        itemsIndexed(Moods.TIMES, key = { _, it -> it.first }) { i, it ->
                            Chip(it.second, maxMinutes == it.first, s, timeRow.of(i).nav(up = moodRow.req, down = below(timeRow), left = if (i == 0) keyFocus else null, right = edge(i == Moods.TIMES.lastIndex))) { maxMinutes = if (maxMinutes == it.first) 0 else it.first }
                        }
                    }
                    Spacer(Modifier.height((8 * s).dp))
                }
                if (filtering) {
                    val heading = listOfNotNull(mood?.label?.let { Lang.tr(it) }, Moods.TIMES.firstOrNull { it.first == maxMinutes }?.second?.let { Lang.tr(it) }).joinToString("  ·  ")
                    if (fitting.isNotEmpty()) TitlesRow(heading, fitting, s, titleRow, FocusRequester.Cancel, keyFocus, timeRow.req, lastTitle, lastFocus, onTitle)
                    else Text(if (looking) "Looking through your titles…" else if (mood != null && maxMinutes > 0) "Nothing fits both yet. Try one filter." else "Nothing fits this yet.", color = Text2, fontSize = (11 * s).sp, modifier = Modifier.padding(top = (2 * s).dp))
                } else if (rows.trending.isNotEmpty()) TitlesRow("Trending now", rows.trending, s, titleRow, FocusRequester.Cancel, keyFocus, if (chips) timeRow.req else toTabs, lastTitle, lastFocus, onTitle)
            } else {
                if (mine.isEmpty() && titles.isEmpty() && shows.isEmpty() && recordings.isEmpty() && channels.isEmpty() && apps.isEmpty()) Text("Nothing found for “${q.trim()}” yet.", color = Text2, fontSize = (11 * s).sp, modifier = Modifier.padding(top = (2 * s).dp))
                if (mine.isNotEmpty()) TitlesRow("In your library", mine, s, mineRow, below(mineRow), keyFocus, toTabs, lastTitle, lastFocus, onTitle)
                if (titles.isNotEmpty()) {
                    if (mine.isNotEmpty()) Spacer(Modifier.height((10 * s).dp))
                    TitlesRow("Titles", titles, s, titleRow, below(titleRow), keyFocus, if (mine.isNotEmpty()) mineRow.req else toTabs, lastTitle, lastFocus, onTitle)
                }
                if (shows.isNotEmpty()) {
                    Spacer(Modifier.height((10 * s).dp))
                    DsHeading("On TV", s)
                    LazyRow(state = showRow.state, contentPadding = PaddingValues(end = (EDGE * s).dp, top = (4 * s).dp, bottom = (4 * s).dp), horizontalArrangement = Arrangement.spacedBy((6 * s).dp)) {
                        itemsIndexed(shows, key = { _, a -> a.key() }) { i, a ->
                            TextCard(a.title, LiveHome.whenLine(a) + "  ·  " + a.channelWithCountry(), s, showRow.of(i).nav(up = above(showRow), down = below(showRow), left = if (i == 0) keyFocus else null, right = edge(i == shows.lastIndex))) {
                                if (a.onNow(System.currentTimeMillis())) ctx.startActivity(LiveHome.open(ctx, a.channel, false))
                                else scope.launch { toast(ctx, withContext(Dispatchers.IO) { LiveHome.toggle(ctx, a, true) }) }
                            }
                        }
                    }
                }
                if (recordings.isNotEmpty()) {
                    Spacer(Modifier.height((10 * s).dp))
                    DsHeading("Your recordings", s)
                    LazyRow(state = recordRow.state, contentPadding = PaddingValues(end = (EDGE * s).dp, top = (4 * s).dp, bottom = (4 * s).dp), horizontalArrangement = Arrangement.spacedBy((6 * s).dp)) {
                        itemsIndexed(recordings, key = { _, a -> a.recording }) { i, a ->
                            TextCard(a.title, a.channelWithCountry(), s, recordRow.of(i).nav(up = above(recordRow), down = below(recordRow), left = if (i == 0) keyFocus else null, right = edge(i == recordings.lastIndex))) {
                                ctx.startActivity(LiveHome.openRecording(ctx, a.recording))
                            }
                        }
                    }
                }
                if (channels.isNotEmpty()) {
                    Spacer(Modifier.height((10 * s).dp))
                    DsHeading("Live TV", s)
                    LazyRow(state = channelRow.state, contentPadding = PaddingValues(end = (EDGE * s).dp, top = (4 * s).dp, bottom = (4 * s).dp), horizontalArrangement = Arrangement.spacedBy((8 * s).dp)) {
                        itemsIndexed(channels, key = { _, a -> a.channel }) { i, a -> ChannelCard(a, s, channelRow.of(i).nav(up = above(channelRow), down = below(channelRow), left = if (i == 0) keyFocus else null, right = edge(i == channels.lastIndex))) { runCatching { ctx.startActivity(LiveHome.open(ctx, a.channel, false)) } } }
                    }
                }
                if (apps.isNotEmpty()) {
                    Spacer(Modifier.height((10 * s).dp))
                    DsHeading("Apps", s)
                    LazyRow(state = appRow.state, contentPadding = PaddingValues(end = (EDGE * s).dp, top = (4 * s).dp, bottom = (4 * s).dp), horizontalArrangement = Arrangement.spacedBy((8 * s).dp)) {
                        itemsIndexed(apps, key = { _, e -> e.pkg }) { i, e -> TextCard(e.label, "", s, appRow.of(i).nav(up = above(appRow), down = FocusRequester.Cancel, left = if (i == 0) keyFocus else null, right = edge(i == apps.lastIndex))) { app.apps.open(ctx, e) } }
                    }
                }
            }
        }
    }
}

/** A filter: OK turns it on or off. */
@Composable
private fun Chip(label: String, on: Boolean, s: Float, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val accent = LocalAccent.current
    Text(
        (if (on) "✓  " else "") + Lang.tr(label), color = if (focused) Ds.Ink else if (on) Color.White else Text2, fontSize = (9.5f * s).sp,
        fontWeight = if (focused || on) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1,
        modifier = modifier.onFocusChanged { focused = it.isFocused }.tvPress(onKey = backKey(), onClick = onClick)
            .clip(RoundedCornerShape(50)).background(if (focused) Color.White else if (on) accent else Ds.Surface)
            .padding(horizontal = (9 * s).dp, vertical = (3.5f * s).dp),
    )
}

@Composable
private fun TitlesRow(title: String, list: List<HeroItem>, s: Float, entry: RowEntry, down: FocusRequester, keys: FocusRequester, toTabs: FocusRequester?, lastTitle: MutableState<String?>, lastFocus: FocusRequester, onTitle: (HeroItem) -> Unit) {
    DsHeading(title, s)
    LazyRow(state = entry.state, contentPadding = PaddingValues(end = (EDGE * s).dp, top = (4 * s).dp, bottom = (4 * s).dp), horizontalArrangement = Arrangement.spacedBy((8 * s).dp)) {
        itemsIndexed(list, key = { _, h -> h.key }) { i, h ->
            SmallPoster(h, s, entry.of(i).nav(up = toTabs, down = down, left = if (i == 0) keys else null, right = edge(i == list.lastIndex)).then(if (h.key == lastTitle.value) Modifier.focusRequester(lastFocus) else Modifier), onFocused = { lastTitle.value = h.key }) { onTitle(h) }
        }
    }
}

@Composable
private fun Key(label: String, s: Float, lastKey: MutableState<String>, keyFocus: FocusRequester, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier.height((26 * s).dp)
            .then(if (lastKey.value == label) Modifier.focusRequester(keyFocus) else Modifier)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) lastKey.value = label }
            .tvPress(onClick = onClick)
            .clip(RoundedCornerShape((Ds.R_CARD * s).dp))
            .background(if (focused) Color.White else Ds.Surface),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (focused) Ds.Ink else Color.White, fontSize = (if (label.length > 1) 8.5f else 11f).times(s).sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
}

@Composable
private fun ChannelCard(a: LiveHome.Airing, s: Float, modifier: Modifier, onClick: () -> Unit) {
    TextCard((if (a.number > 0) "${a.number}  " else "") + a.channelWithCountry(), a.title, s, modifier, onClick)
}

/** A plain card of text (a channel, an app). */
@Composable
internal fun TextCard(title: String, sub: String, s: Float, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier.width((150 * s).dp)
            .onFocusChanged { focused = it.isFocused }
            .tvPress(onKey = backKey(), onClick = onClick)
            .cardFocus(s, focused)
            .background(if (focused) Color.White else Ds.Surface)
            .padding(horizontal = (9 * s).dp, vertical = (7 * s).dp),
    ) {
        Text(title, color = if (focused) Ds.Ink else Color.White, fontSize = (9.5f * s).sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(sub.ifBlank { " " }, color = if (focused) Ds.InkSoft else Text2, fontSize = (8 * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
