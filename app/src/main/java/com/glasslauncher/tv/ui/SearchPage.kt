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
import com.glasslauncher.tv.live.LiveHome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val KEYS = "abcdefghijklmnopqrstuvwxyz1234567890".map { it.toString() }

/**
 * Search everything from one box: titles (Cinemeta, no account needed), Live TV's channels and your apps. A keyboard of
 * its own (the system's covers half the screen), results as you type. [query] is held by Home, so coming back from a
 * title finds it as you left it; [active]: this page is the one on (not under a title's page).
 */
@Composable
fun SearchPage(app: GlassApp, s: Float, query: MutableState<String>, active: Boolean, onClose: () -> Unit, onTitle: (HeroItem) -> Unit) {
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
    val allApps by app.apps.apps.collectAsState()
    val apps = remember(q, allApps) { if (q.length < 2) emptyList() else app.apps.ordered(includeHidden = true).filter { it.label.contains(q, ignoreCase = true) }.take(8) }
    val rows by app.recs.rows.collectAsState()
    LaunchedEffect(Unit) { if (rows.trending.isEmpty()) withContext(Dispatchers.IO) { runCatching { app.recs.refresh() } } }
    val lastTitle = remember { mutableStateOf<String?>(null) }
    val lastFocus = remember { FocusRequester() }
    // In front again: back on the title you came from, else the keyboard.
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        delay(120)
        if (lastTitle.value == null || runCatching { lastFocus.requestFocus() }.isFailure) runCatching { keyFocus.requestFocus() }
    }

    // Each row of results, top to bottom; the last column of keys goes to the first, any card's Left to the keyboard.
    val titleRow = remember { RowEntry() }
    val channelRow = remember { RowEntry() }
    val appRow = remember { RowEntry() }
    val hasTitles = if (q.length < 2) rows.trending.isNotEmpty() else titles.isNotEmpty()
    val hasChannels = q.length >= 2 && channels.isNotEmpty()
    val hasApps = q.length >= 2 && apps.isNotEmpty()
    val order = listOfNotNull(titleRow.takeIf { hasTitles }, channelRow.takeIf { hasChannels }, appRow.takeIf { hasApps })
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
            Text("Titles, channels and apps", color = Text2, fontSize = (8.5f * s).sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height((4 * s).dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape((Ds.R_CONTROL * s).dp)).background(Ds.Surface).padding(horizontal = (11 * s).dp, vertical = (7.5f * s).dp)) {
                Text(q.ifEmpty { "Type with the keys below" }, color = if (q.isEmpty()) Text2 else Color.White, fontSize = (12.5f * s).sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height((8 * s).dp))
            KEYS.chunked(6).forEach { line ->
                Row(Modifier.padding(bottom = (3.5f * s).dp), horizontalArrangement = Arrangement.spacedBy((3.5f * s).dp)) {
                    line.forEach { k ->
                        Key(k, s, lastKey, keyFocus, Modifier.weight(1f).then(if (k == line.last()) Modifier.nav(right = toResults) else Modifier)) { query.value = (q + k).take(60) }
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
        Column(Modifier.weight(1f).fillMaxHeight().onFocusChanged { inResults = it.hasFocus }) {
            // Nothing typed: what's trending, so the page is never empty.
            if (q.length < 2) {
                if (rows.trending.isNotEmpty()) TitlesRow("Trending now", rows.trending, s, titleRow, FocusRequester.Cancel, keyFocus, lastTitle, lastFocus, onTitle)
            } else {
                if (titles.isEmpty() && channels.isEmpty() && apps.isEmpty()) Text("Nothing found for “${q.trim()}” yet.", color = Text2, fontSize = (11 * s).sp, modifier = Modifier.padding(top = (2 * s).dp))
                if (titles.isNotEmpty()) TitlesRow("Titles", titles, s, titleRow, below(titleRow), keyFocus, lastTitle, lastFocus, onTitle)
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

@Composable
private fun TitlesRow(title: String, list: List<HeroItem>, s: Float, entry: RowEntry, down: FocusRequester, keys: FocusRequester, lastTitle: MutableState<String?>, lastFocus: FocusRequester, onTitle: (HeroItem) -> Unit) {
    DsHeading(title, s)
    LazyRow(state = entry.state, contentPadding = PaddingValues(end = (EDGE * s).dp, top = (4 * s).dp, bottom = (4 * s).dp), horizontalArrangement = Arrangement.spacedBy((8 * s).dp)) {
        itemsIndexed(list, key = { _, h -> h.key }) { i, h ->
            SmallPoster(h, s, entry.of(i).nav(down = down, left = if (i == 0) keys else null, right = edge(i == list.lastIndex)).then(if (h.key == lastTitle.value) Modifier.focusRequester(lastFocus) else Modifier), onFocused = { lastTitle.value = h.key }) { onTitle(h) }
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
    TextCard((if (a.number > 0) "${a.number}  " else "") + a.channelName, a.title, s, modifier, onClick)
}

/** A plain card of text (a channel, an app). */
@Composable
private fun TextCard(title: String, sub: String, s: Float, modifier: Modifier, onClick: () -> Unit) {
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
