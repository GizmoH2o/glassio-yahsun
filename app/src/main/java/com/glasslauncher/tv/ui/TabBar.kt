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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glasslauncher.tv.GlassApp

/**
 * The tabs in the top bar, Apple TV style: Home, Live TV, Following, Library, Search. Lit when it's the page on, white when
 * selected; OK goes there. Live TV, Following and Library can be turned off in Settings › Look & motion; none in Kids mode, whose
 * only page is Home. [current]'s tab holds [entry], so Up from a page lands on the tab of that page.
 */
@Composable
fun TabBar(app: GlassApp, current: Screen, s: Float, entry: FocusRequester, modifier: Modifier = Modifier, onFocus: (Boolean) -> Unit = {}, onDown: (() -> Unit)? = null, onTab: (Screen) -> Unit) {
    val st = app.settings
    if (st.kidsMode) return
    val tabs = buildList {
        add("Home" to Screen.Home)
        if (st.menuLive) add("Live TV" to Screen.LiveTv)
        if (st.menuLive && st.menuFollowing) add("Following" to Screen.Following)
        if (st.menuLibrary) add("Library" to Screen.Library)
        add("Search" to Screen.Search)
    }
    Row(
        modifier.clip(RoundedCornerShape(50)).background(Color(0xFF1E1E22).copy(alpha = 0.72f)).padding((2.5f * s).dp),
        horizontalArrangement = Arrangement.spacedBy((2 * s).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { (label, to) ->
            var focused by remember { mutableStateOf(false) }
            val on = to == current
            Text(
                label, color = if (focused) Ds.Ink else if (on) Color.White else Color(0xFFD0D0D4), fontSize = (11.5f * s).sp,
                fontWeight = if (focused || on) FontWeight.Bold else FontWeight.SemiBold,
                modifier = (if (on) Modifier.focusRequester(entry) else Modifier)
                    .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
                    // Down to the page: asked of the page itself, as the arrow alone can land on a list as a whole, with nothing selected.
                    .tvPress(onKey = { ev ->
                        if (onDown != null && ev.key == Key.DirectionDown) { if (ev.type == KeyEventType.KeyDown) onDown(); true } else false
                    }, onClick = { onTab(to) })
                    .clip(RoundedCornerShape(50))
                    .background(if (focused) Color.White else if (on) Color.White.copy(alpha = 0.2f) else Color.Transparent)
                    .padding(horizontal = (13 * s).dp, vertical = (5.5f * s).dp),
            )
        }
    }
}
