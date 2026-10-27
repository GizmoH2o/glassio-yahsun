package com.glasslauncher.tv.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.glasslauncher.tv.data.HeroItem
import com.glasslauncher.tv.data.LiveCards

/**
 * Continue Watching in its own row, as the Apple TV app's Up Next under its top shelf: always on screen along the
 * bottom of the page, landscape cards with the title's picture and logo, how far in you are, and the title under each.
 * The carousel above stays exactly as it is. Down from the carousel goes into the row, Down again to the apps (the page
 * scrolls up to show them), Up back to the carousel.
 *
 * OK opens the title in Nuvio; hold OK (or Menu) takes it out of Continue Watching. Live TV's cards lead the row (a
 * followed match on now, the channel last watched, new recordings): OK opens Live TV on them; hold OK on a channel's
 * card offers Record this show (or Stop recording) and Remove.
 */
@Composable
fun ContinueRow(
    items: List<HeroItem>, s: Float, entry: FocusRequester, up: FocusRequester, down: FocusRequester,
    /** How far down the row sits now (px): with the apps waiting below the screen, it sits at the bottom of the page. */
    drop: () -> Float = { 0f },
    onInside: (Boolean) -> Unit, onSelected: (HeroItem?) -> Unit, onOpen: (HeroItem) -> Unit, onRemove: (HeroItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    var inside by remember { mutableStateOf(false) }
    // The way in (from the carousel above, the dock below) is a card itself: the one you left the row on, else the
    // first. Asking the whole row for the focus left it on no card at all.
    var last by remember { mutableStateOf<String?>(null) }
    val entryKey = items.firstOrNull { it.key == last }?.key ?: items.firstOrNull()?.key
    Column(modifier.fillMaxWidth().graphicsLayer { translationY = drop() }) {
        Text("Continue Watching", color = Color.White.copy(alpha = 0.92f), fontSize = (13 * s).sp,
            fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(start = (ROW_START * s).dp))
        LazyRow(
            Modifier.fillMaxWidth()
                // Before the group it watches: in the row (any card) or not.
                .onFocusChanged {
                    if (it.hasFocus != inside) { inside = it.hasFocus; onInside(it.hasFocus) }
                    if (!it.hasFocus) onSelected(null)
                }
                .focusGroup(),
            horizontalArrangement = Arrangement.spacedBy((CARD_GAP * s).dp),
            contentPadding = PaddingValues(start = (ROW_START * s).dp, end = (ROW_START * s).dp, top = (8 * s).dp, bottom = (4 * s).dp),
        ) {
            items(items, key = { it.key }) { item ->
                ContinueCard(
                    item, s,
                    Modifier.then(if (item.key == entryKey) Modifier.focusRequester(entry) else Modifier)
                        .focusProperties { this.up = up; this.down = down },
                    onFocused = { last = item.key; onSelected(item) }, onOpen = { onOpen(item) }, onRemove = { onRemove(item) },
                )
            }
        }
    }
}

/** The row's left edge, as the carousel's text (dp at 960 wide). */
private const val ROW_START = 56f
/** A card's width (about six across, as Apple TV's Up Next) and the gap between cards. */
private const val CARD_W = 144f
private const val CARD_GAP = 16f

/** One card: the picture (16:9) with the logo and the progress bar on it, then the title and where you are. */
@Composable
private fun ContinueCard(
    item: HeroItem, s: Float, modifier: Modifier, onFocused: () -> Unit, onOpen: () -> Unit, onRemove: () -> Unit,
) {
    val ctx = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    // tvOS: the selected card grows and its shadow deepens; no outline.
    val k by animateFloatAsState(if (focused) 1.09f else 1f, tween(170, easing = FastOutSlowInEasing), label = "cwCard")
    val shape = RoundedCornerShape((10 * s).dp)
    val w = (CARD_W * s).dp
    val req = remember(item.background) {
        ImageRequest.Builder(ctx).data(item.background.ifBlank { item.poster }).size((CARD_W * s * 2f).toInt(), (CARD_W * 9 / 16 * s * 2f).toInt()).build()
    }
    Column(Modifier.width(w).graphicsLayer { scaleX = k; scaleY = k; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0.32f) }) {
        Box(
            modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .drawBehind {
                    val sh = CardShadow.get()
                    val pad = size.width * CardShadow.PAD
                    drawImage(sh, dstOffset = IntOffset((-pad).toInt(), (-pad + size.height * (if (focused) 0.07f else 0.04f)).toInt()),
                        dstSize = IntSize((size.width + 2 * pad).toInt(), (size.height + 2 * pad).toInt()),
                        alpha = if (focused) 0.8f else 0.5f, filterQuality = androidx.compose.ui.graphics.FilterQuality.Low)
                }
                .clip(shape)
                .background(Color(0xFF2A2A2F))
                .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
                .tvPress(onLong = onRemove, onClick = onOpen),
        ) {
            val live = LiveCards.isLive(item)
            // A Live TV card with no picture of the show: the channel's logo, large, in the middle of the card.
            val logoOnly = live && item.background.isBlank() && item.logo.isNotBlank()
            if (logoOnly) AsyncImage(
                remember(item.logo) { ImageRequest.Builder(ctx).data(item.logo).size((CARD_W * s).toInt(), (CARD_W * 9 / 16 * s).toInt()).build() }, null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.align(Alignment.Center).fillMaxSize().padding(horizontal = (30 * s).dp, vertical = (18 * s).dp),
            ) else {
                Text(item.name, color = Color.White.copy(alpha = 0.6f), fontSize = (11 * s).sp, maxLines = 2,
                    modifier = Modifier.align(Alignment.Center).padding((10 * s).dp))
                AsyncImage(req, item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            // A shade at the bottom, so the logo and the bar read on any picture.
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.62f))))
            // On now: Live TV's red LIVE tag, as on its own pages.
            if (live && item.type != LiveCards.TYPE_RECORDING) Text(
                "LIVE", color = Color.White, fontSize = (7 * s).sp, fontWeight = FontWeight.Bold, letterSpacing = (0.6f * s).sp,
                modifier = Modifier.align(Alignment.TopStart).padding((7 * s).dp)
                    .background(Color(0xFFE5342B), RoundedCornerShape((4 * s).dp)).padding(horizontal = (5 * s).dp, vertical = (1.5f * s).dp),
            )
            if (item.logo.isNotBlank() && !logoOnly) AsyncImage(
                remember(item.logo) { ImageRequest.Builder(ctx).data(item.logo).size((76 * s * 2).toInt(), (24 * s * 2).toInt()).build() }, null,
                contentScale = ContentScale.Fit, alignment = Alignment.BottomStart,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = (9 * s).dp, bottom = (12 * s).dp)
                    .size(width = (76 * s).dp, height = (24 * s).dp),
            )
            val p = item.progress.coerceIn(0f, 1f)
            if (item.progress >= 0f) Canvas(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = (9 * s).dp, vertical = (6 * s).dp).height((2.5f * s).dp)) {
                val r = CornerRadius(size.height / 2)
                drawRoundRect(Color.White.copy(alpha = 0.3f), cornerRadius = r)
                drawRoundRect(Color.White, size = Size(size.width * p, size.height), cornerRadius = r)
            }
        }
        Spacer(Modifier.height((7 * s).dp))
        Text(item.name, color = Color.White.copy(alpha = if (focused) 0.95f else 0.8f), fontSize = (10 * s).sp,
            fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.padding(top = (1 * s).dp), verticalAlignment = Alignment.CenterVertically) {
            Text(where(item), color = AppleSecondary.copy(alpha = if (focused) 1f else 0.8f), fontSize = (8.5f * s).sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (item.country.isNotEmpty()) { Spacer(Modifier.width((5 * s).dp)); CountryTag(item.country, s) }
        }
    }
}

/** "S10 · E12 · 23% left" (a series), "Movie · 40% left". */
private fun where(item: HeroItem): String {
    if (LiveCards.isLive(item)) return item.sub
    val left = (100 - (item.progress.coerceIn(0f, 1f) * 100).toInt()).coerceIn(1, 100)
    val what = item.sub.ifBlank { if (item.type.equals("movie", true)) "Movie" else "" }
    return listOf(what, "$left% left").filter { it.isNotBlank() }.joinToString("  ·  ")
}

/** One blurred rounded shape drawn behind every card (scaled): a soft shadow that never bands on the TV. */
internal object CardShadow {
    const val PAD = 0.12f
    private var img: ImageBitmap? = null
    fun get(): ImageBitmap = img ?: run {
        val w = 320; val h = 180; val pad = (w * PAD).toInt()
        val b = android.graphics.Bitmap.createBitmap(w + 2 * pad, h + 2 * pad, android.graphics.Bitmap.Config.ARGB_8888)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.DITHER_FLAG).apply {
            color = android.graphics.Color.argb(170, 0, 0, 0)
            maskFilter = android.graphics.BlurMaskFilter(pad * 0.8f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        android.graphics.Canvas(b).drawRoundRect(pad + 6f, pad + 10f, pad + w - 6f, pad + h.toFloat(), 18f, 18f, p)
        b.copy(android.graphics.Bitmap.Config.ARGB_8888, false).also { b.recycle(); it.prepareToDraw() }.asImageBitmap().also { img = it }
    }
}
