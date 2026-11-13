package com.glasslauncher.tv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.composed
import kotlinx.coroutines.launch
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Glassio's design: tvOS's behaviour with Netflix's hierarchy. One near-black ground, one surface, three corner sizes.
 * What's selected lifts and turns white (buttons, rows) or gets a white outline (pictures). Red (the accent) only for
 * what's live, new or in progress.
 */
object Ds {
    val Bg = Color(0xFF0B0B0D)
    val Surface = Color(0xFF1C1C1F)
    val SurfaceHi = Color(0xFF2A2A2F)
    /** A control over a picture, when it isn't selected. */
    val Glass = Color.White.copy(alpha = 0.18f)
    /** Text on a selected (white) control. */
    val Ink = Color(0xFF0B0B0D)
    val InkSoft = Color(0xFF4A4A50)
    val On = Color(0xFF34C759)
    val OffTrack = Color(0xFF4A4A50)
    val Good = Color(0xFF46D369)
    val GoodOnWhite = Color(0xFF1B7F3B)
    val Bad = Color(0xFFFF6961)
    val BadOnWhite = Color(0xFFC62828)

    /** Corners, in the screen's scale units: pictures and keys, buttons and rows, panels. */
    const val R_CARD = 6f
    const val R_CONTROL = 9f
    const val R_PANEL = 14f
}

/** Settings › Look & motion: no lift, slide or zoom; a stronger selection and solid backing behind text. */
val LocalReduceMotion = compositionLocalOf { false }
val LocalHighContrast = compositionLocalOf { false }

/** Secondary text: soft grey, nearly white with High contrast. */
val Text2: Color @Composable @ReadOnlyComposable get() = if (LocalHighContrast.current) Color(0xFFEDEDF0) else Color(0xFFB3B3B8)

/**
 * A title's logo that is nearly all black on its see-through ground (some studios' are), which can't be read on a dark
 * page: it's drawn white instead. A solid rectangle isn't one: its black is its background. Pure. [pixels]: ARGB.
 */
/** Logos found to be empty pictures (see [logoIsEmpty]): shown as the name from then on. */
internal val emptyLogos: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

/** A logo with next to nothing in it (under 1% of it not see-through): there is no logo to show. Pure. [pixels]: ARGB. */
internal fun logoIsEmpty(pixels: IntArray): Boolean = pixels.count { (it ushr 24) >= 64 } < pixels.size / 100 + 1

internal fun logoIsDark(pixels: IntArray): Boolean {
    var seen = 0
    var bright = 0.0
    for (p in pixels) {
        if ((p ushr 24) < 128) continue
        seen++
        // Its strongest colour, not how light it looks: a deep red logo is a colour, and reads.
        bright += maxOf((p shr 16) and 255, (p shr 8) and 255, p and 255) / 255.0
    }
    if (seen == 0 || seen > pixels.size * 0.9) return false
    return bright / seen < 0.35
}

/**
 * The way into a LazyRow from outside it. Found by the arrows on their own, a LazyRow takes the focus as a whole, with
 * nothing showing it and the arrows then doing nothing. So whatever leads to a row says so ([nav], up = entry.req), as
 * Home's rows do: the first card on show takes it. [of] goes on each card by its index, [state] on the row.
 */
@Stable
class RowEntry {
    val state = LazyListState()
    val req = FocusRequester()
    fun of(i: Int): Modifier = if (i == state.firstVisibleItemIndex) Modifier.focusRequester(req) else Modifier
}

/**
 * Around everything in a window: Back goes to the BackHandlers from whatever has the focus. Left unanswered, Compose
 * takes Back for "leave this control": the focus is lost, nothing shows it and the press does nothing else.
 */
@Composable
fun Modifier.backGoesBack(): Modifier {
    val owner = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current
    var down by remember { mutableStateOf(false) }
    return onKeyEvent { ev ->
        if (ev.key != Key.Back) return@onKeyEvent false
        if (ev.type == KeyEventType.KeyDown) down = true
        else if (ev.type == KeyEventType.KeyUp && down) { down = false; owner?.onBackPressedDispatcher?.onBackPressed() }
        true
    }
}

/** The end of a row: the arrow stays there, or it would jump to whatever it finds that way. */
fun edge(end: Boolean): FocusRequester? = if (end) FocusRequester.Cancel else null

/** Where Up, Down, Left and Right go from here; null: wherever the arrows find. */
fun Modifier.nav(up: FocusRequester? = null, down: FocusRequester? = null, left: FocusRequester? = null, right: FocusRequester? = null): Modifier =
    focusProperties {
        if (up != null) this.up = up
        if (down != null) this.down = down
        if (left != null) this.left = left
        if (right != null) this.right = right
    }.then(if (left == FocusRequester.Cancel || right == FocusRequester.Cancel) endBounce(left == FocusRequester.Cancel, right == FocusRequester.Cancel) else Modifier)

/**
 * At a row's end (Left on its first, Right on its last): the card gives a small nudge that way and springs back, as
 * tvOS does, so you know the row ends. Not with Reduce motion; Settings › Look & motion › Bounce at a row's end.
 */
private fun endBounce(atStart: Boolean, atEnd: Boolean): Modifier = Modifier.composed {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as? com.glasslauncher.tv.GlassApp
    if (app?.settings?.rowBounce == false || LocalReduceMotion.current) return@composed Modifier
    val off = remember { androidx.compose.animation.core.Animatable(0f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { 7.dp.toPx() }
    Modifier.onPreviewKeyEvent { ev ->
        if (ev.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) {
            val dir = when {
                atEnd && ev.key == androidx.compose.ui.input.key.Key.DirectionRight -> 1f
                atStart && ev.key == androidx.compose.ui.input.key.Key.DirectionLeft -> -1f
                else -> 0f
            }
            if (dir != 0f && !off.isRunning) scope.launch {
                off.animateTo(dir * px, androidx.compose.animation.core.tween(90, easing = Motion.EaseOut))
                off.animateTo(0f, androidx.compose.animation.core.spring(0.45f, 700f))
            }
        }
        false
    }.graphicsLayer { translationX = off.value }
}

/** Night mode: everything dimmed from its hour until the morning. */
@Composable
fun NightDim() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var on by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            on = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.glasslauncher.tv.live.NightMode.now(ctx) }
            kotlinx.coroutines.delay(15_000)
        }
    }
    if (on) androidx.compose.foundation.layout.Box(
        Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = com.glasslauncher.tv.live.NightMode.DIM_PERCENT / 100f)),
    )
}

/** A streaming app's own icon, as the TV has it installed (nothing when it isn't): beside its name on an "open in" button. */
@Composable
fun ServiceIcon(pkg: String, s: Float) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val icon = remember(pkg) {
        runCatching {
            val d = ctx.packageManager.getApplicationIcon(pkg)
            val b = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, 96, 96)
            d.draw(android.graphics.Canvas(b))
            b.asImageBitmap()
        }.getOrNull()
    } ?: return
    androidx.compose.foundation.Image(icon, null, Modifier.height((11 * s).dp).width((11 * s).dp).clip(RoundedCornerShape((2.5f * s).dp)))
}

/** The selected control's lift (nothing with Reduce motion). */
@Composable
fun liftOf(focused: Boolean, grow: Float = 1.06f): Float {
    val reduce = LocalReduceMotion.current
    val k by animateFloatAsState(if (focused && !reduce) grow else 1f, if (reduce) tween(0) else spring(0.8f, 700f), label = "lift")
    return k
}

/**
 * A button: white with dark text when selected, glass otherwise ([primary]: nearly white, like Play). [leading]: its
 * glyph, told the colour to draw in. OK and hold OK through [tvPress].
 */
@Composable
fun DsButton(
    label: String, s: Float, modifier: Modifier = Modifier, primary: Boolean = false, onKey: ((KeyEvent) -> Boolean)? = null,
    onLong: (() -> Unit)? = null, leading: (@Composable (Color) -> Unit)? = null, onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val k = liftOf(focused)
    val fg = if (focused || primary) Ds.Ink else Color.White
    Row(
        modifier.graphicsLayer { scaleX = k; scaleY = k }
            .onFocusChanged { focused = it.isFocused }
            .tvPress(onLong = onLong, onKey = onKey, onClick = onClick)
            .clip(RoundedCornerShape((Ds.R_CONTROL * s).dp))
            .background(if (focused) Color.White else if (primary) Color.White.copy(alpha = 0.88f) else Ds.Glass)
            .padding(horizontal = (13 * s).dp, vertical = (7.5f * s).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) { leading(fg); Spacer(Modifier.width((6 * s).dp)) }
        Text(label, color = fg, fontSize = (11.5f * s).sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The switch of a setting, like tvOS's: green when on. */
@Composable
fun DsSwitch(on: Boolean, s: Float) {
    Box(Modifier.width((34 * s).dp).height((20 * s).dp).clip(RoundedCornerShape(50)).background(if (on) Ds.On else Ds.OffTrack).padding((2.5f * s).dp)) {
        Box(Modifier.width((15 * s).dp).height((15 * s).dp).clip(RoundedCornerShape(50)).background(Color.White).align(if (on) Alignment.CenterEnd else Alignment.CenterStart))
    }
}

/** A row's title above a page's content, the same on every page. */
@Composable
fun DsHeading(text: String, s: Float, modifier: Modifier = Modifier) {
    Text(text, color = Color.White, fontSize = (12.5f * s).sp, fontWeight = FontWeight.Bold, modifier = modifier.padding(bottom = (6 * s).dp))
}

/** Space between a row of controls. */
fun dsGap(s: Float) = Arrangement.spacedBy((8 * s).dp)

/**
 * Over any poster: how far you are in it (Continue watching's red bar at its foot), so every card of a title you've
 * started says so, not only the Continue watching row's. Nothing when it isn't started.
 */
@Composable
fun Modifier.watchedBar(key: String, s: Float): Modifier {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as? com.glasslauncher.tv.GlassApp ?: return this
    val cw by app.cw.items.collectAsState()
    val p = remember(cw, key) { cw.firstOrNull { it.key == key }?.progress ?: -1f }
    if (p < 0f) return this
    val accent = LocalAccent.current
    return this.drawWithContent {
        drawContent()
        val h = 2.5f * s * density
        drawRect(Color.White.copy(alpha = 0.25f), topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - h), size = androidx.compose.ui.geometry.Size(size.width, h))
        drawRect(accent, topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - h), size = androidx.compose.ui.geometry.Size(size.width * p.coerceIn(0.03f, 1f), h))
    }
}

/** The line under a card, the same everywhere (Search, More like this, My List): rating · year · its first genre. Pure. */
fun cardDetail(item: com.glasslauncher.tv.data.HeroItem): String = listOfNotNull(
    item.rating.takeIf { it.isNotBlank() && it != "0" && it != "0.0" },
    item.year.takeIf { it.isNotBlank() }?.take(4),
    item.genres.firstOrNull()?.substringBefore(" & ")?.trim()?.takeIf { it.isNotBlank() },
).joinToString(" · ")

/** Whether a new episode of [item] is out (Airing this week's, as My List's NEW badge has it). */
@Composable
fun isNewOut(item: com.glasslauncher.tv.data.HeroItem): Boolean {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as? com.glasslauncher.tv.GlassApp ?: return false
    val airing by app.shelf.airing.collectAsState()
    return remember(airing, item.id) { airing.any { it.out && it.item.id == item.id } }
}

/**
 * "Because you watched …": the title last watched (Continue watching's first with an IMDb id, not [except]) and
 * titles like it (TMDB's recommendations), as Home's row has it; null while there's none.
 */
@Composable
fun becauseYouWatched(except: String = ""): Pair<com.glasslauncher.tv.data.HeroItem, List<com.glasslauncher.tv.data.HeroItem>>? {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as? com.glasslauncher.tv.GlassApp ?: return null
    val cw by app.cw.items.collectAsState()
    val seen = cw.firstOrNull { it.id.startsWith("tt") && it.key != except }
    val list by androidx.compose.runtime.produceState(emptyList<com.glasslauncher.tv.data.HeroItem>(), seen?.key, except) {
        value = if (seen == null) emptyList() else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            app.recs.similar(seen).filter { it.key != except && it.key != seen.key }.let { l ->
                if (app.settings.kidsMode) l.filter { com.glasslauncher.tv.data.HeroPicks.isKids(it) } else l
            }.take(12)
        }
    }
    return if (seen == null || list.isEmpty()) null else seen to list
}

/** Which way the selection last moved (for Poster tilt): -1 left, 1 right, 0 up or down. */
object Tilt {
    @Volatile var dx = 0
    @Volatile var dy = 0
    /** Bumped on each arrow pressed (the big picture's parallax follows). */
    val moves = androidx.compose.runtime.mutableIntStateOf(0)
    fun cameFrom(code: Int) {
        if (code in android.view.KeyEvent.KEYCODE_DPAD_UP..android.view.KeyEvent.KEYCODE_DPAD_RIGHT) moves.intValue++
        when (code) {
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> { dx = -1; dy = 0 }
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> { dx = 1; dy = 0 }
            android.view.KeyEvent.KEYCODE_DPAD_UP -> { dx = 0; dy = -1 }
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> { dx = 0; dy = 1 }
        }
    }
}

/**
 * Poster tilt (Settings › Look & motion): the poster selected leans a few degrees toward where you came from, as if
 * the move nudged it, and springs flat, as tvOS's posters do. Nothing with it off or with Reduce motion.
 */
@Composable
fun Modifier.posterTilt(focused: Boolean): Modifier {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as? com.glasslauncher.tv.GlassApp ?: return this
    if (!app.settings.posterTilt || LocalReduceMotion.current) return this
    val rx = remember { androidx.compose.animation.core.Animatable(0f) }
    val ry = remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(focused) {
        if (!focused) { launch { rx.animateTo(0f, tween(160)) }; ry.animateTo(0f, tween(160)); return@LaunchedEffect }
        // Lean back toward where it came from (Right: its left edge nearer), then settle with a little give.
        rx.snapTo(Tilt.dy * 7f); ry.snapTo(-Tilt.dx * 9f)
        launch { rx.animateTo(0f, androidx.compose.animation.core.spring(0.42f, 260f)) }
        ry.animateTo(0f, androidx.compose.animation.core.spring(0.42f, 260f))
    }
    return this.graphicsLayer { rotationX = rx.value; rotationY = ry.value; cameraDistance = 14f * density }
}
