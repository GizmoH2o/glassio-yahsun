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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * A title's own page: its picture and details, Resume or Play, its trailer with sound, its streaming app, My Library and
 * Not interested; a series' seasons and episodes; More like this. [onOpen] goes to another title's page.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun TitlePage(app: GlassApp, item: HeroItem, s: Float, onClose: () -> Unit, onOpen: (HeroItem) -> Unit) {
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
    val hasSeasons = details.seasons.isNotEmpty() && !item.type.equals("movie", true)
    val seasonEntry = remember { RowEntry() }
    val epEntry = remember { RowEntry() }
    val moreEntry = remember { RowEntry() }
    LaunchedEffect(item.key) { delay(120); runCatching { first.requestFocus() } }
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

    Box(Modifier.fillMaxSize().background(Ds.Bg).focusProperties { exit = { FocusRequester.Cancel } }.focusGroup()) {
        val pic = shown.background.ifBlank { shown.poster }
        if (pic.isNotBlank()) AsyncImage(
            remember(pic) { ImageRequest.Builder(ctx).data(pic).size(1280, 720).build() }, null, contentScale = ContentScale.Crop,
            modifier = Modifier.align(Alignment.TopEnd).fillMaxWidth(0.72f).aspectRatio(16f / 9f),
        )
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0.3f to Ds.Bg, 0.62f to Ds.Bg.copy(alpha = 0.45f), 0.88f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.32f to Color.Transparent, 0.74f to Ds.Bg)))

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = (24 * s).dp, bottom = (24 * s).dp)) {
            Column(Modifier.padding(start = (EDGE * s).dp).width((410 * s).dp)) {
                Text(if (shown.type.equals("movie", true)) "FILM" else "SERIES", color = LocalAccent.current, fontSize = (7.5f * s).sp, fontWeight = FontWeight.Bold, letterSpacing = (1.5f * s).sp)
                Spacer(Modifier.height((4 * s).dp))
                Title(shown, st.titleMode.takeIf { it != "hidden" } ?: "text", st.titleScale * 0.9f, s)
                Spacer(Modifier.height((8 * s).dp))
                val facts = listOf(shown.year, shown.runtime).filter { it.isNotBlank() } + shown.genres.take(3).joinToString(" · ").takeIf { it.isNotBlank() }.let { listOfNotNull(it) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((6 * s).dp)) {
                    if (shown.rating.isNotBlank()) ImdbBadge(shown.rating, s)
                    Text(facts.joinToString("  ·  "), color = Color(0xFFE8E8EC), fontSize = (8.5f * s).sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (shown.overview.isNotBlank()) {
                    Spacer(Modifier.height((7 * s).dp))
                    Text(shown.overview, color = Color(0xFFD0D0D4), fontSize = (9.5f * s).sp, lineHeight = (13.5f * s).sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
                if (details.cast.isNotEmpty()) {
                    Spacer(Modifier.height((5 * s).dp))
                    Text("Starring " + details.cast.take(4).joinToString(", "), color = Text2, fontSize = (8 * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height((11 * s).dp))
            // The buttons. With no player app for titles on this TV, no Play: the trailer and any streaming app it's on.
            Row(Modifier.padding(start = (EDGE * s).dp), horizontalArrangement = dsGap(s), verticalAlignment = Alignment.CenterVertically) {
                var firstUsed = false
                val toRows = Modifier.nav(down = if (hasSeasons) seasonEntry.req else if (more.isNotEmpty()) moreEntry.req else FocusRequester.Cancel)
                fun firstMod(): Modifier = if (firstUsed) toRows else { firstUsed = true; toRows.nav(left = FocusRequester.Cancel).focusRequester(first) }
                if (canPlay) DsButton(if (resume != null) "Resume" + (resume.sub.takeIf { it.isNotBlank() }?.let { " $it" } ?: "") else "Play", s, firstMod(), primary = true,
                    leading = { c -> PlayGlyph(Modifier.size((8.5f * s).dp), c) }) { NuvioLaunch.open(ctx, st, app.http, resume ?: item) }
                links.firstOrNull()?.let { l ->
                    DsButton(l.service, s, firstMod(), leading = { ServiceIcon(l.pkg, s) }) { runCatching { ctx.startActivity(l.intent()) }.onFailure { toast(ctx, "${l.service} couldn't open it") } }
                }
                if (trailerIds != null) DsButton("Trailer", s, firstMod()) { trailerOn = true }
                DsButton(if (inLibrary) "✓  In Library" else "+  My Library", s, firstMod().then(if (kids) Modifier.nav(right = FocusRequester.Cancel) else Modifier)) {
                    val now = app.library.toggle(item)
                    toast(ctx, if (now) "Saved to Library · ${item.name}" else "Removed from Library · ${item.name}")
                }
                if (!kids) DsButton("Not interested", s, toRows.nav(right = FocusRequester.Cancel)) {
                    st.heroHidden = (st.heroHidden + item.key).distinct()
                    toast(ctx, "${item.name} won't show on the big picture again (Settings › Home to bring it back)")
                    onClose()
                }
            }

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
                                modifier = seasonEntry.of(i).nav(up = first, down = epEntry.req, left = edge(i == 0), right = edge(i == seasonKeys.lastIndex)).onFocusChanged { f = it.isFocused; if (it.isFocused) season = n }.tvPress(onClick = { season = n })
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
                        EpisodeCard(e, item, s, now, if (current) resume?.progress ?: -1f else -1f, epEntry.of(i).nav(up = seasonEntry.req, down = if (more.isNotEmpty()) moreEntry.req else FocusRequester.Cancel, left = edge(i == 0), right = edge(i == eps.lastIndex))) {
                            if (canPlay) NuvioLaunch.openEpisode(ctx, st, app.http, item, e.season, e.number)
                            else toast(ctx, "No app for titles on this TV: Nuvio or Stremio plays them")
                        }
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
                    itemsIndexed(more, key = { _, h -> h.key }) { i, h -> SmallPoster(h, s, moreEntry.of(i).nav(up = if (hasSeasons) epEntry.req else first, down = FocusRequester.Cancel, left = edge(i == 0), right = edge(i == more.lastIndex))) { onOpen(h) } }
                }
            }
        }
        if (trailerOn) trailerIds?.let { ids -> TrailerPlayer(ids) { trailerOn = false } }
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
