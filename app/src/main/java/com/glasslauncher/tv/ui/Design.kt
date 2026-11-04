package com.glasslauncher.tv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
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
internal fun logoIsDark(pixels: IntArray): Boolean {
    var seen = 0
    var lum = 0.0
    for (p in pixels) {
        if ((p ushr 24) < 128) continue
        seen++
        lum += (0.2126 * ((p shr 16) and 255) + 0.7152 * ((p shr 8) and 255) + 0.0722 * (p and 255)) / 255.0
    }
    if (seen == 0 || seen > pixels.size * 0.9) return false
    return lum / seen < 0.14
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

/** Where Up, Down, Left and Right go from here; null: wherever the arrows find. */
fun Modifier.nav(up: FocusRequester? = null, down: FocusRequester? = null, left: FocusRequester? = null, right: FocusRequester? = null): Modifier =
    focusProperties {
        if (up != null) this.up = up
        if (down != null) this.down = down
        if (left != null) this.left = left
        if (right != null) this.right = right
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
