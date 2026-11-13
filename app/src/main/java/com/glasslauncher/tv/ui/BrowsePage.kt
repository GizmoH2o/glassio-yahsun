package com.glasslauncher.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.glasslauncher.tv.data.Browse
import com.glasslauncher.tv.data.HeroItem
import com.glasslauncher.tv.data.HeroPicks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The title last selected on each genre or person page, so Back from a title's page comes back to it. */
private object BrowseMemory { val last = HashMap<String, String>() }

/**
 * A genre's page or a person's (mockups 30, 31), on the title pages' stack: what [page] is says which. Rows of posters
 * (OK opens a title over it); Back closes it.
 */
@Composable
fun BrowsePage(app: GlassApp, page: HeroItem, s: Float, onClose: () -> Unit, onOpen: (HeroItem) -> Unit) {
    BackHandler { onClose() }
    val kids = app.settings.kidsMode
    val saved by app.library.saved.collectAsState()
    val watch by app.library.watchlist.collectAsState()
    val nuvio by app.library.nuvioList.collectAsState()
    val stremio by app.library.stremioList.collectAsState()
    val cw by app.cw.items.collectAsState()
    val mine = remember(saved, watch, nuvio, stremio, cw) { (cw + saved + watch + nuvio + stremio).distinctBy { it.key }.let { l -> if (kids) l.filter { HeroPicks.isKids(it) } else l } }
    val focusFirst = remember { FocusRequester() }
    val back = remember { FocusRequester() }
    val memo = BrowseMemory.last
    var holds by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Ds.Bg).onFocusChanged { holds = it.hasFocus }) {
        if (page.type == Browse.GENRE) GenreBody(app, page.name, s, kids, mine, focusFirst, back, memo, onOpen)
        else PersonBody(app, page, s, kids, mine, focusFirst, back, memo, onOpen)
    }
    // The rows come in from the internet: until one's there to take it, keep asking (the remembered title, else the first).
    LaunchedEffect(page.key) {
        delay(160)
        repeat(80) {
            if (holds) return@LaunchedEffect
            if (memo[page.key] == null || runCatching { back.requestFocus() }.isFailure) runCatching { focusFirst.requestFocus() }
            delay(250)
        }
    }
}

/** A row of posters with its heading; the remembered one takes [back], the first [first] when asked. */
@Composable
private fun PosterRow(title: String, list: List<HeroItem>, s: Float, pageKey: String, memo: HashMap<String, String>, back: FocusRequester, first: FocusRequester?, onOpen: (HeroItem) -> Unit) {
    if (list.isEmpty()) return
    Spacer(Modifier.height((12 * s).dp))
    DsHeading(title, s, Modifier.padding(start = (EDGE * s).dp))
    LazyRow(contentPadding = PaddingValues(start = (EDGE * s).dp, end = (EDGE * s).dp, top = (5 * s).dp, bottom = (5 * s).dp), horizontalArrangement = Arrangement.spacedBy((9 * s).dp)) {
        itemsIndexed(list, key = { _, h -> h.key }) { i, h ->
            val m = when {
                memo[pageKey] == h.key -> Modifier.focusRequester(back)
                i == 0 && first != null -> Modifier.focusRequester(first)
                else -> Modifier
            }
            SmallPoster(h, s, m.nav(left = edge(i == 0), right = edge(i == list.lastIndex)), onFocused = { memo[pageKey] = h.key }) { onOpen(h) }
        }
    }
}

@Composable
private fun GenreBody(app: GlassApp, start: String, s: Float, kids: Boolean, mine: List<HeroItem>, first: FocusRequester, back: FocusRequester,
                      memo: HashMap<String, String>, onOpen: (HeroItem) -> Unit) {
    val all = Browse.genres(kids)
    var name by remember(start) { mutableStateOf(start) }
    val g = Browse.genreNamed(name) ?: all.first()
    val pageKey = "${Browse.GENRE}:$start"
    val yours = remember(g, mine) { mine.filter { Browse.inGenre(it, g) } }
    val popular by produceState<Pair<List<HeroItem>, List<HeroItem>>?>(null, g.name, kids) {
        value = null
        value = withContext(Dispatchers.IO) { app.recs.genre(g.movie, g.tv, kids) }.let { (f, t) -> if (kids) f to t.filter { HeroPicks.isKids(it) } else f to t }
    }
    val accent = LocalAccent.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = (20 * s).dp, bottom = (40 * s).dp)) {
        // The genres along the top: another one changes this page (Back still closes it).
        Row(Modifier.padding(start = (EDGE * s).dp), horizontalArrangement = Arrangement.spacedBy((5 * s).dp)) {
            all.forEachIndexed { i, x ->
                DsButton(x.name, s, (if (x.name == g.name && yours.isEmpty()) Modifier.focusRequester(first) else Modifier)
                    .nav(up = FocusRequester.Cancel, left = edge(i == 0), right = edge(i == all.lastIndex)), primary = x.name == g.name) { name = x.name }
            }
        }
        Spacer(Modifier.height((16 * s).dp))
        Column(Modifier.padding(start = (EDGE * s).dp)) {
            Text(g.name.uppercase(), color = accent, fontSize = (8 * s).sp, fontWeight = FontWeight.Bold)
            Text(g.line, color = Color.White, fontSize = (19 * s).sp, fontWeight = FontWeight.ExtraBold)
            Text("From your lists and what's popular now.", color = Text2, fontSize = (9 * s).sp)
        }
        PosterRow(Lang.tr(g.name) + " " + Lang.tr("in your lists"), yours, s, pageKey, memo, back, first, onOpen)
        val p = popular
        if (p == null) Text("Loading…", color = Text2, fontSize = (9 * s).sp, modifier = Modifier.padding(start = (EDGE * s).dp, top = (14 * s).dp))
        else {
            PosterRow(Lang.tr("Popular films"), p.first, s, pageKey, memo, back, null, onOpen)
            PosterRow(Lang.tr("Popular series"), p.second, s, pageKey, memo, back, null, onOpen)
            if (p.first.isEmpty() && p.second.isEmpty() && yours.isEmpty())
                Text("Nothing here yet. Popular titles need the internet and a TMDB key (Settings › Home).", color = Text2, fontSize = (9 * s).sp, modifier = Modifier.padding(start = (EDGE * s).dp, top = (14 * s).dp))
        }
    }
}

@Composable
private fun PersonBody(app: GlassApp, page: HeroItem, s: Float, kids: Boolean, mine: List<HeroItem>, first: FocusRequester, back: FocusRequester,
                       memo: HashMap<String, String>, onOpen: (HeroItem) -> Unit) {
    val ctx = LocalContext.current
    val id = page.id.toIntOrNull() ?: 0
    val got by produceState<Pair<com.glasslauncher.tv.data.Recs.PersonInfo, List<HeroItem>>?>(null, id) {
        value = withContext(Dispatchers.IO) { app.recs.person(id) }
    }
    var loaded by remember(id) { mutableStateOf(false) }
    LaunchedEffect(got) { if (got != null) loaded = true else { delay(8000); loaded = true } }
    val info = got?.first
    val titles = got?.second.orEmpty().let { l -> if (kids) l.filter { HeroPicks.isKids(it) } else l }
    val mineKeys = remember(mine) { mine.map { it.key }.toSet() + mine.map { it.id }.toSet() }
    val yours = titles.filter { it.key in mineKeys || it.id in mineKeys }
    val accent = LocalAccent.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = (26 * s).dp, bottom = (40 * s).dp)) {
        Row(Modifier.padding(start = (EDGE * s).dp, end = (EDGE * s).dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size((92 * s).dp).clip(CircleShape).background(Ds.Surface), contentAlignment = Alignment.Center) {
                if (page.poster.isNotBlank()) AsyncImage(remember(page.poster) { ImageRequest.Builder(ctx).data(page.poster.replace("/w185", "/h632")).size(400, 400).build() }, page.name,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else RawText(page.name.split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }, color = Text2, fontSize = (26 * s).sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width((16 * s).dp))
            Column(Modifier.weight(1f)) {
                val dept = info?.dept.orEmpty().ifBlank { page.genres.firstOrNull().orEmpty() }
                val eyebrow = listOfNotNull(dept.takeIf { it.isNotBlank() }?.let { Lang.tr(it) }, info?.count?.takeIf { it > 0 }?.let { "$it " + Lang.tr("titles") }).joinToString("  ·  ")
                if (eyebrow.isNotBlank()) RawText(eyebrow.uppercase(), color = accent, fontSize = (8 * s).sp, fontWeight = FontWeight.Bold)
                RawText(page.name, color = Color.White, fontSize = (22 * s).sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val born = listOf(info?.born.orEmpty().let { if (it.isNotBlank()) Lang.tr("Born") + " $it" else "" }, info?.place.orEmpty().let { if (it.isNotBlank()) Lang.tr("in") + " $it" else "" })
                    .filter { it.isNotBlank() }.joinToString(" ")
                if (born.isNotBlank()) RawText(born, color = Text2, fontSize = (9 * s).sp, maxLines = 1)
                if (!info?.bio.isNullOrBlank()) RawText(info!!.bio, color = Color.White.copy(alpha = 0.78f), fontSize = (9.5f * s).sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = (4 * s).dp).fillMaxWidth(0.8f))
            }
        }
        PosterRow(Lang.tr("Known for"), titles, s, page.key, memo, back, first, onOpen)
        PosterRow(Lang.tr("In your lists"), yours, s, page.key, memo, back, null, onOpen)
        if (titles.isEmpty()) Text(if (!loaded) "Loading…" else "Nothing found for them. This needs the internet and a TMDB key (Settings › Home).",
            color = Text2, fontSize = (9 * s).sp, modifier = Modifier.padding(start = (EDGE * s).dp, top = (16 * s).dp))
    }
}
