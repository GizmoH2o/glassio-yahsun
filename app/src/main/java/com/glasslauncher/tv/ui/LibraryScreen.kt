package com.glasslauncher.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.glasslauncher.tv.GlassApp
import com.glasslauncher.tv.data.HeroItem
import com.glasslauncher.tv.data.NuvioLaunch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The Library, like the Apple TV app's: a calm grey page, posters five to a row with a soft shadow.
 * Its lists in tabs (what you saved with +, the Nuvio and Stremio libraries, the Trakt watchlist), shown when in use.
 * OK opens the title, hold OK (or Menu) removes it.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(app: GlassApp, onClose: () -> Unit) {
    val s = LocalScale.current
    val ctx = LocalContext.current
    val st = app.settings
    val saved by app.library.saved.collectAsState()
    val watch by app.library.watchlist.collectAsState()
    val status by app.library.status.collectAsState()
    val first = remember { FocusRequester() }
    BackHandler { onClose() }
    val nuvioAll by app.library.nuvioList.collectAsState()
    val nuvioStatus by app.library.nuvioStatus.collectAsState()
    val stremioAll by app.library.stremioList.collectAsState()
    val stremioStatus by app.library.stremioStatus.collectAsState()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { app.library.refreshWatchlist(); app.library.refreshNuvio(); app.library.refreshStremio() } }
    // One tab per list in use: what you saved here, the Nuvio account's library, the Stremio account's, the Trakt
    // watchlist. Each shows its whole list.
    val tabs = buildList {
        if (saved.isNotEmpty()) add(LibraryTab("saved", "Saved", saved, "") { it: HeroItem ->
            app.library.remove(it.key); toast(ctx, "Removed · ${it.name}")
        })
        if (st.libraryNuvio && app.library.nuvio.connected) add(LibraryTab("nuvio", "Nuvio", nuvioAll, nuvioStatus) { it: HeroItem ->
            app.library.removeFromNuvio(it) { ok -> toast(ctx.applicationContext, if (ok) "Removed from your Nuvio library · ${it.name}" else "Nuvio: couldn't remove ${it.name}") }
        })
        if (st.libraryStremio && app.library.stremio.connected) add(LibraryTab("stremio", "Stremio", stremioAll, stremioStatus) { it: HeroItem ->
            app.library.removeFromStremio(it) { ok -> toast(ctx.applicationContext, if (ok) "Removed from your Stremio library · ${it.name}" else "Stremio: couldn't remove ${it.name}") }
        })
        if (st.libraryTrakt && app.cw.trakt.connected) add(LibraryTab("trakt", "Trakt", watch, status) { it: HeroItem ->
            app.library.removeFromWatchlist(it); toast(ctx, "Removed from the Trakt watchlist · ${it.name}")
        })
    }
    var tabKey by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val tab = tabs.firstOrNull { it.key == tabKey } ?: tabs.firstOrNull()
    val shown = tab?.items.orEmpty()
    val hasAny = tabs.any { it.items.isNotEmpty() }
    LaunchedEffect(hasAny) { if (hasAny) { delay(60); runCatching { first.requestFocus() } } }

    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF26262B))) {
        // The grey page is painted once into a picture with dithering: a gradient drawn live on the TV showed bands.
        val density = androidx.compose.ui.platform.LocalDensity.current
        val bw = with(density) { maxWidth.roundToPx() }; val bh = with(density) { maxHeight.roundToPx() }
        val bg = remember(bw, bh) { greyPage(bw, bh) }
        androidx.compose.foundation.Image(bg, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        // TV scrolling: the selected row always sits at the same height. Moving left / right inside a row never
        // scrolls (the default "just bring it into view" nudged the page up and down with every poster).
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides PivotScroll) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(5),
            contentPadding = PaddingValues(start = (64 * s).dp, end = (64 * s).dp, top = (34 * s).dp, bottom = (40 * s).dp),
            horizontalArrangement = Arrangement.spacedBy((22 * s).dp),
            verticalArrangement = Arrangement.spacedBy((20 * s).dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Text("Library", color = Color.White, fontSize = (26 * s).sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height((4 * s).dp))
                    Text(
                        if (hasAny) "OK opens · hold OK to remove" else "Nothing here yet. On the home screen, press + next to Play to save a title.",
                        color = Color.White.copy(alpha = 0.55f), fontSize = (10 * s).sp,
                    )
                    // The tabs: moving onto one shows its list (as the Apple TV app's tabs do); Down goes into it.
                    if (tabs.size > 1) {
                        Spacer(Modifier.height((14 * s).dp))
                        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy((8 * s).dp)) {
                            tabs.forEachIndexed { i, t -> TabPill(t.label, t.key == tab?.key, i == 0, i == tabs.lastIndex, s) { tabKey = t.key } }
                        }
                    }
                }
            }
            if (tab != null) {
                if (tab.status.isNotBlank()) item(span = { GridItemSpan(maxLineSpan) }) { Section(tab.status, s) }
                if (tab.items.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("Nothing in this list yet.", color = Color.White.copy(alpha = 0.55f), fontSize = (11 * s).sp)
                }
                itemsIndexed(shown, key = { _, it -> tab.key + ":" + it.key }) { i, it ->
                    Poster(it, s, st.libraryTitles, if (i == 0) Modifier.focusRequester(first) else Modifier,
                        onOpen = { NuvioLaunch.open(ctx, st, app.http, it) },
                        onRemove = { tab.remove(it) })
                }
            }
        }
        }
    }
}

/** One of the Library's lists: its tab, its titles, why it couldn't be read ("" when it could), how a title leaves it. */
private class LibraryTab(val key: String, val label: String, val items: List<HeroItem>, val status: String, val remove: (HeroItem) -> Unit)

/**
 * A tab: a pill, lit when it's the list shown, white when the focus is on it; the focus coming onto it shows its list.
 * Left on the first and Right on the last stay put (the TV would otherwise drop into the posters below).
 */
@Composable
private fun TabPill(label: String, selected: Boolean, first: Boolean, last: Boolean, s: Float, onFocused: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .onPreviewKeyEvent { e ->
                e.type == KeyEventType.KeyDown && (first && e.key == Key.DirectionLeft || last && e.key == Key.DirectionRight)
            }
            .clip(RoundedCornerShape(50))
            .background(when { focused -> Color.White; selected -> Color.White.copy(alpha = 0.18f); else -> Color.Transparent })
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .focusable()
            .padding(horizontal = (16 * s).dp, vertical = (6 * s).dp),
    ) {
        Text(label, color = if (focused) Color(0xFF1C1C1E) else Color.White.copy(alpha = if (selected) 1f else 0.6f),
            fontSize = (12 * s).sp, fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Medium)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private object PivotScroll : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        // Top of the selected row at 30% of the page (the first rows simply stay where they are).
        val target = containerSize * 0.30f
        val d = if (size > containerSize) offset else offset - target
        // Same row (left / right): never a pixel of scroll.
        return if (kotlin.math.abs(d) < 24f) 0f else d
    }
}

@Composable
private fun Section(title: String, s: Float) {
    Text(title, color = Color.White.copy(alpha = 0.8f), fontSize = (13 * s).sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = (14 * s).dp))
}

@Composable
private fun Poster(item: HeroItem, s: Float, titles: Boolean, modifier: Modifier, onOpen: () -> Unit, onRemove: () -> Unit) {
    val ctx = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    // No overshoot: a bouncy grow made the posters wobble while you moved along a row.
    val k by animateFloatAsState(if (focused) 1.07f else 1f, androidx.compose.animation.core.tween(160, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "poster")
    val shape = RoundedCornerShape((10 * s).dp)
    val req = remember(item.poster) {
        ImageRequest.Builder(ctx).data(sharpPoster(item.poster).ifBlank { item.background }).size((180 * s * 2f).toInt(), (270 * s * 2f).toInt()).build()
    }
    Column(modifier.graphicsLayer { scaleX = k; scaleY = k }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                .drawBehind {
                    // Soft shadow from one pre-blurred picture (smooth on every TV; elevation shadows banded).
                    val sh = PosterShadow.get()
                    val pad = size.width * PosterShadow.PAD
                    drawImage(sh, dstOffset = androidx.compose.ui.unit.IntOffset((-pad).toInt(), (-pad + size.height * (if (focused) 0.05f else 0.03f)).toInt()),
                        dstSize = androidx.compose.ui.unit.IntSize((size.width + 2 * pad).toInt(), (size.height + 2 * pad).toInt()),
                        alpha = if (focused) 0.75f else 0.45f, filterQuality = androidx.compose.ui.graphics.FilterQuality.Low)
                }
                .clip(shape)
                .background(Color(0xFF4A4A50))
                .onFocusChanged { focused = it.isFocused }
                .tvPress(onLong = onRemove, onKey = { ev ->
                    if (ev.key == Key.Menu) { if (ev.type == KeyEventType.KeyDown) onRemove(); true } else false
                }, onClick = onOpen),
            contentAlignment = Alignment.Center,
        ) {
            Text(item.name, color = Color.White.copy(alpha = 0.6f), fontSize = (10 * s).sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding((8 * s).dp))
            AsyncImage(req, item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        if (titles) Text(item.name, color = Color.White.copy(alpha = if (focused) 0.95f else 0.6f), fontSize = (9.5f * s).sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = (7 * s).dp))
    }
}

/** The Library's grey page: a soft vertical gradient, dithered so the TV shows no steps. */
private fun greyPage(w: Int, h: Int): ImageBitmap {
    val b = android.graphics.Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
    val p = android.graphics.Paint(android.graphics.Paint.DITHER_FLAG)
    p.shader = android.graphics.LinearGradient(0f, 0f, w * 0.25f, h.toFloat(),
        intArrayOf(0xFF3B3B41.toInt(), 0xFF2B2B30.toInt(), 0xFF1E1E22.toInt()), floatArrayOf(0f, 0.55f, 1f), android.graphics.Shader.TileMode.CLAMP)
    android.graphics.Canvas(b).drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
    b.setHasAlpha(false)
    return b.copy(android.graphics.Bitmap.Config.ARGB_8888, false).also { b.recycle(); it.prepareToDraw() }.asImageBitmap()
}

/** One blurred rounded shape, drawn behind every poster (scaled), for a light, soft shadow. */
private object PosterShadow {
    /** Blur margin on each side, as a fraction of the poster width. */
    const val PAD = 0.16f
    private var img: ImageBitmap? = null
    fun get(): ImageBitmap = img ?: run {
        val w = 160; val h = 240; val pad = (w * PAD).toInt()
        val b = android.graphics.Bitmap.createBitmap(w + 2 * pad, h + 2 * pad, android.graphics.Bitmap.Config.ARGB_8888)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.DITHER_FLAG).apply {
            color = android.graphics.Color.argb(170, 0, 0, 0)
            maskFilter = android.graphics.BlurMaskFilter(pad * 0.8f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        android.graphics.Canvas(b).drawRoundRect(pad + 4f, pad + 8f, pad + w - 4f, pad + h.toFloat(), 14f, 14f, p)
        b.copy(android.graphics.Bitmap.Config.ARGB_8888, false).also { b.recycle(); it.prepareToDraw() }.asImageBitmap().also { img = it }
    }
}

/** A bigger copy of the poster when the site has one (catalog posters are often thumbnails: w185 / w342, "small"). */
private fun sharpPoster(u: String): String = when {
    u.isBlank() -> u
    "image.tmdb.org/t/p/" in u -> u.replace(Regex("/t/p/(w\\d+|original)/"), "/t/p/w780/")
    "images.metahub.space/poster/" in u -> u.replace(Regex("/poster/(small|medium)/"), "/poster/large/")
    else -> u
}
