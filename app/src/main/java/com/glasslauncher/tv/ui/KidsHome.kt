package com.glasslauncher.tv.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val KIDS_BG = Color(0xFF14213D)
private val KIDS_SUN = Color(0xFFFFB703)

/**
 * Home for a kids profile (mockup 34): a hello with their letter, big Keep watching cards, then rounded posters
 * (their favourites, cartoons, family films) and their apps. Everything a little bigger and rounder; the grown-ups'
 * way out is the profile button at the top (behind the PIN), as before. [first] takes the focus coming home.
 */
@Composable
fun KidsHome(app: GlassApp, s: Float, first: FocusRequester, active: Boolean, onTitle: (HeroItem) -> Unit, onGrownUps: () -> Unit) {
    val ctx = LocalContext.current
    val name = remember(app.profiles.rev.intValue) { app.profiles.current().name }
    val cw by app.cw.items.collectAsState()
    val saved by app.library.saved.collectAsState()
    val watch by app.library.watchlist.collectAsState()
    val nuvio by app.library.nuvioList.collectAsState()
    val stremio by app.library.stremioList.collectAsState()
    val allApps by app.apps.apps.collectAsState()
    val keep = remember(cw) { cw.filter { HeroPicks.isKids(app.shelf.filled(it)) }.take(12) }
    val mine = remember(saved, watch, nuvio, stremio) { (saved + watch + nuvio + stremio).distinctBy { it.key }.map { app.shelf.filled(it) }.filter { HeroPicks.isKids(it) } }
    val cartoons by produceState(emptyList<HeroItem>()) {
        value = withContext(Dispatchers.IO) { app.recs.genre(16, 16, true).let { (f, t) -> (f + t.filter { HeroPicks.isKids(it) }).distinctBy { it.key } } }
    }
    val family by produceState(emptyList<HeroItem>()) {
        value = withContext(Dispatchers.IO) { app.recs.genre(10751, null, true).first }
    }
    val apps = remember(allApps, app.settings.kidsApps) { app.settings.kidsApps.toSet().let { ok -> app.apps.ordered(includeHidden = true).filter { it.pkg in ok } } }
    // Coming back (from a title's page): the card it was opened from; else the first.
    var lastKey by remember { mutableStateOf<String?>(null) }
    val back = remember { FocusRequester() }
    LaunchedEffect(active, keep.isNotEmpty(), mine.isNotEmpty(), cartoons.isNotEmpty()) {
        if (active && (lastKey == null || runCatching { back.requestFocus() }.isFailure)) runCatching { first.requestFocus() }
    }
    // The first card of the first row there is takes [first].
    val familyOnly = family.filter { f -> cartoons.none { it.key == f.key } }
    val firstRow = when { keep.isNotEmpty() -> "keep"; mine.isNotEmpty() -> "mine"; cartoons.isNotEmpty() -> "cartoons"; familyOnly.isNotEmpty() -> "family"; else -> "apps" }
    fun firstMod(row: String, i: Int, key: String = ""): Modifier =
        (if (row == firstRow && i == 0) Modifier.focusRequester(first) else Modifier)
            .then(if (key.isNotEmpty() && key == lastKey) Modifier.focusRequester(back) else Modifier)
            .onFocusChanged { if (it.isFocused && key.isNotEmpty()) lastKey = key }
    val open: (HeroItem) -> Unit = { h -> if (h.progress >= 0f && NuvioLaunch.playable(ctx, app.settings)) NuvioLaunch.open(ctx, app.settings, app.http, h) else onTitle(h) }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to KIDS_BG, 1f to Color(0xFF0E1830)))) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = (58 * s).dp, bottom = (36 * s).dp)) {
            Row(Modifier.padding(start = (EDGE * s).dp, end = (EDGE * s).dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size((30 * s).dp).clip(CircleShape).background(KIDS_SUN), contentAlignment = Alignment.Center) {
                    RawText(name.take(1).uppercase(), color = KIDS_BG, fontSize = (15 * s).sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.width((9 * s).dp))
                RawText(Lang.tr("Hi") + ", $name!", color = Color.White, fontSize = (17 * s).sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                DsButton(Lang.tr("Grown-ups"), s) { onGrownUps() }
            }
            if (keep.isNotEmpty()) {
                KidsHeading("Keep watching", s)
                LazyRow(contentPadding = PaddingValues(horizontal = (EDGE * s).dp, vertical = (6 * s).dp), horizontalArrangement = Arrangement.spacedBy((12 * s).dp)) {
                    itemsIndexed(keep, key = { _, h -> h.key }) { i, h ->
                        BigKidsCard(h, s, firstMod("keep", i, "keep:" + h.key).nav(left = edge(i == 0), right = edge(i == keep.lastIndex))) { open(h) }
                    }
                }
            }
            @Composable fun posters(row: String, title: String, list: List<HeroItem>) {
                if (list.isEmpty()) return
                KidsHeading(title, s)
                LazyRow(contentPadding = PaddingValues(horizontal = (EDGE * s).dp, vertical = (6 * s).dp), horizontalArrangement = Arrangement.spacedBy((10 * s).dp)) {
                    itemsIndexed(list, key = { _, h -> h.key }) { i, h ->
                        KidsPoster(h, s, firstMod(row, i, row + ":" + h.key).nav(left = edge(i == 0), right = edge(i == list.lastIndex))) { onTitle(h) }
                    }
                }
            }
            posters("mine", Lang.tr("Your favourites"), mine)
            posters("cartoons", Lang.tr("Cartoons"), cartoons)
            posters("family", Lang.tr("Family films"), familyOnly)
            if (apps.isNotEmpty()) {
                KidsHeading("Your apps", s)
                LazyRow(contentPadding = PaddingValues(horizontal = (EDGE * s).dp, vertical = (6 * s).dp), horizontalArrangement = Arrangement.spacedBy((10 * s).dp)) {
                    itemsIndexed(apps, key = { _, e -> e.pkg }) { i, e ->
                        var focused by remember { mutableStateOf(false) }
                        Box(Modifier.width((96 * s).dp).height((60 * s).dp).then(firstMod("apps", i))
                            .nav(left = edge(i == 0), right = edge(i == apps.lastIndex))
                            .onFocusChanged { focused = it.isFocused }.tvPress(onKey = backKey()) { AppLock.open(app, ctx, e) }
                            .cardFocus(s, focused, 16f, 1.07f).clip(RoundedCornerShape((16 * s).dp)).background(Color.White.copy(alpha = 0.1f)),
                            contentAlignment = Alignment.Center) {
                            val art by produceState(app.apps.cachedArtwork(e), e.pkg, app.settings.iconPack, app.icons.rev.intValue) { value = app.apps.artwork(e) }
                            IconArt(art, e.label, "liquid", s)
                        }
                    }
                }
            }
            if (keep.isEmpty() && mine.isEmpty() && cartoons.isEmpty() && apps.isEmpty())
                Text("Nothing to watch here yet", color = Color.White.copy(alpha = 0.8f), fontSize = (14 * s).sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = (EDGE * s).dp, top = (24 * s).dp))
        }
    }
}

@Composable
private fun KidsHeading(text: String, s: Float) {
    Text(text, color = Color.White, fontSize = (13 * s).sp, fontWeight = FontWeight.Black,
        modifier = Modifier.padding(start = (EDGE * s).dp, top = (16 * s).dp, bottom = (2 * s).dp))
}

/** Keep watching: a big rounded picture with how far in it is, the name under it. */
@Composable
private fun BigKidsCard(h: HeroItem, s: Float, modifier: Modifier, onClick: () -> Unit) {
    val ctx = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.width((172 * s).dp)) {
        Box(modifier.fillMaxWidth().height((97 * s).dp).onFocusChanged { focused = it.isFocused }.tvPress(onKey = backKey(), onClick = onClick)
            .cardFocus(s, focused, 14f, 1.06f).clip(RoundedCornerShape((14 * s).dp)).background(Color.White.copy(alpha = 0.1f))) {
            val pic = h.background.ifBlank { h.poster }
            if (pic.isNotBlank()) AsyncImage(remember(pic) { ImageRequest.Builder(ctx).data(pic).size(640, 360).build() }, h.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (h.progress > 0f) Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = (9 * s).dp, vertical = (8 * s).dp).fillMaxWidth().height((4 * s).dp)
                .clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.35f))) {
                Box(Modifier.fillMaxWidth(h.progress.coerceIn(0.04f, 1f)).height((4 * s).dp).clip(RoundedCornerShape(50)).background(KIDS_SUN))
            }
        }
        Spacer(Modifier.height((5 * s).dp))
        RawText(h.name + (if (h.sub.isNotBlank()) " · " + h.sub else ""), color = Color.White.copy(alpha = if (focused) 1f else 0.8f), fontSize = (9.5f * s).sp,
            fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A poster with big round corners. */
@Composable
private fun KidsPoster(h: HeroItem, s: Float, modifier: Modifier, onClick: () -> Unit) {
    val ctx = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    Box(modifier.width((78 * s).dp).height((110 * s).dp).onFocusChanged { focused = it.isFocused }.tvPress(onKey = backKey(), onClick = onClick)
        .cardFocus(s, focused, 12f, 1.07f).clip(RoundedCornerShape((12 * s).dp)).background(Color.White.copy(alpha = 0.1f))) {
        if (h.poster.isNotBlank()) AsyncImage(remember(h.poster) { ImageRequest.Builder(ctx).data(h.poster).size(300, 450).build() }, h.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else RawText(h.name, color = Color.White, fontSize = (9 * s).sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center).padding((6 * s).dp))
    }
}
