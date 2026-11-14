package com.glasslauncher.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.draw.drawBehind
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glasslauncher.tv.GlassApp

/** Where the tabs end on screen (px; 0: none shown): the home screen's weather stops short of them. */
val tabsRight = androidx.compose.runtime.mutableFloatStateOf(0f)

/**
 * The tabs in the top bar, Apple TV style: Home, Live TV, Following, Library, Search. Lit when it's the page on, white when
 * selected; OK goes there. Live TV, Following and Library can be turned off in Settings › Look & motion; none in Kids mode, whose
 * only page is Home. [current]'s tab holds [entry], so Up from a page lands on the tab of that page.
 */
@Composable
fun TabBar(app: GlassApp, current: Screen, s: Float, entry: FocusRequester, modifier: Modifier = Modifier, onFocus: (Boolean) -> Unit = {}, onDown: (() -> Unit)? = null, onTab: (Screen) -> Unit) {
    val st = app.settings
    if (st.kidsMode) { tabsRight.floatValue = 0f; return }
    val tabs = buildList {
        add("Home" to Screen.Home)
        if (st.menuLive) add("Live TV" to Screen.LiveTv)
        // My List (Netflix's): the saved titles, their lists, and what you follow, one page with filters.
        if (st.menuLibrary) add("My List" to Screen.Library)
        // Following lives in My List now; its own tab only while you're on its page (from the menu).
        // Sport (Settings): its own tab while you follow a team or a show; Following, only while you're on its page.
        val follows = androidx.compose.runtime.remember(current) { runCatching { com.glasslauncher.tv.live.LiveHome.followed(app).isNotEmpty() }.getOrDefault(false) }
        if (st.menuLive && st.sportPage && st.sportTab && follows) add("Sport" to Screen.Following)
        else if (st.menuLive && st.menuFollowing && current == Screen.Following) add((if (st.sportPage) "Sport" else "Following") to Screen.Following)
        add("Search" to Screen.Search)
    }
    // One pill that slides from tab to tab (as tvOS's top bar does), rather than each tab lighting up on its own:
    // white under the selected tab, faint under the page's own while the selection is elsewhere.
    val spots = remember { androidx.compose.runtime.mutableStateMapOf<Screen, Pair<Float, Float>>() }
    var focusedTab by remember { mutableStateOf<Screen?>(null) }
    // Arriving by a tab (OK on it): the bar you pressed goes with its page, so this one, the new page's own, takes the
    // focus on its tab at once; else it falls on the first thing there (Home) and the pill shows Home for a moment.
    val mine = remember { FocusRequester() }
    // Any other way in (the menu, Live TV's own button): the focus falls on the first tab, Home, as the bar comes.
    // Moved on to the page's own tab, but only in its first moments (a move to Home later is yours).
    val born = remember { android.os.SystemClock.uptimeMillis() }
    val barScope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.compose.runtime.LaunchedEffect(current) {
        if (TabHandoff.pending != current) return@LaunchedEffect
        repeat(20) {
            if (runCatching { mine.requestFocus() }.isSuccess && focusedTab == current) { TabHandoff.pending = null; return@LaunchedEffect }
            kotlinx.coroutines.delay(25)
        }
        TabHandoff.pending = null
    }
    val target = focusedTab ?: current
    val spot = spots[target]
    val reduce = LocalReduceMotion.current
    val pillX = remember { androidx.compose.animation.core.Animatable(0f) }
    val pillW = remember { androidx.compose.animation.core.Animatable(0f) }
    var placed by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(spot) {
        val (x, w) = spot ?: return@LaunchedEffect
        if (!placed || reduce) { pillX.snapTo(x); pillW.snapTo(w); placed = true; return@LaunchedEffect }
        val spec = androidx.compose.animation.core.spring<Float>(dampingRatio = 0.86f, stiffness = 520f)
        kotlinx.coroutines.coroutineScope {
            launch { pillX.animateTo(x, spec) }
            launch { pillW.animateTo(w, spec) }
        }
    }
    val pillColor by androidx.compose.animation.animateColorAsState(
        if (focusedTab != null) Color.White else Color.White.copy(alpha = 0.2f), androidx.compose.animation.core.tween(180), label = "pill")
    Row(
        modifier.onGloballyPositioned { tabsRight.floatValue = it.boundsInWindow().right }
            .clip(RoundedCornerShape(50)).background(Color(0xFF1E1E22).copy(alpha = 0.72f)).padding((2.5f * s).dp)
            .drawBehind {
                if (placed && spots[target] != null) drawRoundRect(pillColor, topLeft = androidx.compose.ui.geometry.Offset(pillX.value, 0f),
                    size = androidx.compose.ui.geometry.Size(pillW.value, size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f))
            },
        horizontalArrangement = Arrangement.spacedBy((2 * s).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { (label, to) ->
            var focused by remember { mutableStateOf(false) }
            val on = to == current
            val ink by androidx.compose.animation.animateColorAsState(
                if (focused) Ds.Ink else if (on) Color.White else Color(0xFFD0D0D4), androidx.compose.animation.core.tween(160), label = "tabInk")
            Text(
                label, color = ink, fontSize = (11.5f * s).sp,
                fontWeight = if (focused || on) FontWeight.Bold else FontWeight.SemiBold,
                modifier = (if (on) Modifier.focusRequester(entry).focusRequester(mine) else Modifier)
                    .onPlaced { spots[to] = it.positionInParent().x to it.size.width.toFloat() }
                    .onFocusChanged {
                        focused = it.isFocused; onFocus(it.isFocused)
                        if (it.isFocused) focusedTab = to else if (focusedTab == to) focusedTab = null
                        if (it.isFocused && to == Screen.Home && current != Screen.Home && android.os.SystemClock.uptimeMillis() - born < 900)
                            barScope.launch { runCatching { mine.requestFocus() } }
                        TabHandoff.barFocused = focusedTab != null
                    }
                    // Down to the page: asked of the page itself, as the arrow alone can land on a list as a whole, with nothing selected.
                    .tvPress(onKey = { ev ->
                        if (onDown != null && ev.key == Key.DirectionDown) { if (ev.type == KeyEventType.KeyDown) onDown(); true } else false
                    }, onClick = { if (to != Screen.Home && to != current) TabHandoff.pending = to; onTab(to) })
                    // The page you're on: a small dot under its name too (tvOS), seen while the selection is elsewhere.
                    .drawBehind {
                        if (on && focusedTab != null && focusedTab != to) drawCircle(Color.White.copy(alpha = 0.85f), radius = 1.5f * s * density,
                            center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height - 2.2f * s * density))
                    }
                    .padding(horizontal = (13 * s).dp, vertical = (5.5f * s).dp),
            )
        }
    }
}

/** The tab just chosen (OK on it), for the next page's bar to take the focus on; null once it has. */
internal object TabHandoff {
    @Volatile var pending: Screen? = null
    /** A tab has the focus (a page leaves it there as it opens). */
    @Volatile var barFocused = false
}
