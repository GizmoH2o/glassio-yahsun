package com.glasslauncher.tv.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.GraphicsLayerScope

/**
 * Glassio's motion, in one place, as the streaming apps do it: what comes in settles slowly (ease-out), what goes
 * leaves a little quicker the way it came (ease-in), and nothing just appears or vanishes.
 */
object Motion {
    /** Settling in: fast at first, slow at the end. */
    val EaseOut = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
    /** Leaving: slow at first, then away. */
    val EaseIn = CubicBezierEasing(0.4f, 0f, 1f, 1f)
    const val ENTER_MS = PAGE_ENTER_MS
    const val EXIT_MS = 240
    /** With Reduce motion: a plain fade, both ways. */
    const val PLAIN_MS = 150

    /** [ms] going out, from [enterMs] coming in: about two thirds. Pure. */
    fun exitFor(enterMs: Int): Int = (enterMs * 0.65f).toInt().coerceAtLeast(120)
}

/** Settings › Home › Follow the TV's animation speed: off, Glassio's motion runs at its own durations (see [atOwnSpeed]). */
val LocalFollowTvSpeed = androidx.compose.runtime.compositionLocalOf { false }

/**
 * The card last selected on any page ([cardFocus] marks it), so a title's page can grow out of it. Cleared when the
 * card loses the selection, so a page opened from somewhere else (the big picture's More info) doesn't use an old one.
 */
object CardOrigin {
    var coords: LayoutCoordinates? = null

    /** Where the selected card's centre is, as a fraction of the screen (0..1 both ways); null when there's none. */
    fun fraction(): Offset? {
        val c = coords?.takeIf { it.isAttached } ?: return null
        val root = c.findRootCoordinates().size
        if (root.width == 0 || root.height == 0) return null
        val b = c.boundsInRoot()
        return Offset((b.center.x / root.width).coerceIn(0f, 1f), (b.center.y / root.height).coerceIn(0f, 1f))
    }
}

/** Marks this card as the one a title's page grows out of while it's [focused] (see [CardOrigin]). */
@Composable
internal fun Modifier.markCardOrigin(focused: Boolean): Modifier {
    val ref = remember { arrayOfNulls<LayoutCoordinates>(1) }
    androidx.compose.runtime.SideEffect {
        val c = ref[0]
        if (focused) { if (c != null) CardOrigin.coords = c } else if (c != null && CardOrigin.coords === c) CardOrigin.coords = null
    }
    return onGloballyPositioned { ref[0] = it; if (focused) CardOrigin.coords = it }
}

/**
 * Something that comes and goes with an animation both ways: [content] is shown while [target] isn't null, coming in
 * with [transform] as its progress goes 0 → 1 over [enterMs]. A new [target] (another tab, another title) comes in
 * again. When [target] turns null it goes out the same way over [exitMs], as a still picture of its last frame: nothing
 * in it runs any more, takes the focus, answers Back or plays (a page left live for its way out took the focus back).
 */
@Composable
internal fun <T : Any> Presence(
    target: T?, enterMs: Int, exitMs: Int, modifier: Modifier = Modifier,
    transform: GraphicsLayerScope.(progress: Float, leaving: Boolean) -> Unit,
    content: @Composable (T) -> Unit,
) {
    var shown by remember { mutableStateOf(target) }
    if (target != null && target != shown) shown = target
    val p = remember { Animatable(0f) }
    val leaving = target == null && shown != null
    val follow = LocalFollowTvSpeed.current
    LaunchedEffect(target) {
        if (target != null) {
            p.snapTo(0f)
            atOwnSpeed(follow) { p.animateTo(1f, tween(enterMs, easing = Motion.EaseOut)) }
        } else if (shown != null) {
            atOwnSpeed(follow) { p.animateTo(0f, tween(exitMs, easing = Motion.EaseIn)) }
            shown = null
        }
    }
    val v = shown ?: return
    val picture = rememberGraphicsLayer()
    var size by remember { mutableStateOf(IntSize.Zero) }
    val d = LocalDensity.current
    Box(modifier.graphicsLayer { transform(p.value, leaving) }) {
        if (!leaving) Box(
            Modifier.onSizeChanged { size = it }
                .drawWithContent { picture.record { this@drawWithContent.drawContent() }; drawLayer(picture) },
        ) { content(v) }
        else Box(with(d) { Modifier.size(size.width.toDp(), size.height.toDp()) }.drawBehind { drawLayer(picture) })
    }
}

/**
 * A page, as on the streaming apps: it rises 20 dp and fades in, and on Back sinks and fades out the same way, a little
 * quicker. [origin] (a fraction of the screen): it grows out of there instead, as a title's page does from its card.
 * With [reduce] (Reduce motion): a plain fade.
 */
@Composable
internal fun <T : Any> PageLayer(target: T?, reduce: Boolean, origin: Offset? = null, content: @Composable (T) -> Unit) {
    val rise = with(LocalDensity.current) { 20.dp.toPx() }
    val enterMs = if (reduce) Motion.PLAIN_MS else Motion.ENTER_MS + if (origin != null) 60 else 0
    Presence(target, enterMs, if (reduce) Motion.PLAIN_MS else Motion.EXIT_MS, Modifier.fillMaxSize(), transform = { k, _ ->
        alpha = k
        if (!reduce) {
            if (origin != null) {
                transformOrigin = TransformOrigin(origin.x, origin.y)
                val sc = 0.86f + 0.14f * k
                scaleX = sc; scaleY = sc
            } else {
                translationY = (1f - k) * rise
                val sc = 0.98f + 0.02f * k
                scaleX = sc; scaleY = sc
            }
        }
    }) { v -> Box(Modifier.fillMaxSize()) { content(v) } }
}

/**
 * A pane changing over in place (a Settings category, a Library filter, a Live TV section), as tvOS does: the new one
 * fades in and settles from a little to the side, each time [key] changes. Nothing the first time, as the page itself
 * is coming in then. With Reduce motion: a short fade.
 */
@Composable
internal fun Modifier.paneEnter(key: Any?): Modifier {
    val reduce = LocalReduceMotion.current
    val follow = LocalFollowTvSpeed.current
    val p = remember { Animatable(1f) }
    val first = remember { mutableStateOf(true) }
    LaunchedEffect(key) {
        if (first.value) { first.value = false; return@LaunchedEffect }
        p.snapTo(0f)
        atOwnSpeed(follow) { p.animateTo(1f, tween(if (reduce) Motion.PLAIN_MS else 280, easing = Motion.EaseOut)) }
    }
    val shift = with(LocalDensity.current) { 14.dp.toPx() }
    return graphicsLayer { val v = p.value; alpha = v; if (!reduce) translationX = (1f - v) * shift }
}
