package com.glasslauncher.tv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glasslauncher.tv.GlassApp
import com.glasslauncher.tv.live.LiveHome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Test only (am start --es ticker_test "LABEL|score line|where"): a match shown as live; "" off. */
val TickerTest = mutableStateOf("")

/**
 * A team you follow playing now (Live TV's Following): its competition, the score and minute, and the channel, on a
 * glass strip in the middle of Home's top bar, read again whenever the live score moves (and every half minute). Not focusable:
 * it never takes the remote from the page.
 */
@Composable
fun MatchTicker(app: GlassApp, s: Float, modifier: Modifier) {
    var bump by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val watching = LiveHome.watchScores(app) { bump++ }
        onDispose { LiveHome.stopScores(app, watching) }
    }
    val real by produceState<LiveHome.MatchDay?>(null, bump) {
        while (true) {
            value = withContext(Dispatchers.IO) { runCatching { LiveHome.matchDay(app) }.getOrNull() }?.takeIf { it.live }
            delay(30_000)
        }
    }
    val test = TickerTest.value.split('|').takeIf { it.size == 3 }
    val label = test?.get(0) ?: real?.label
    val line = test?.get(1) ?: real?.headline
    val where = test?.get(2) ?: real?.where?.substringAfter("·")?.trim()
    val still = LocalReduceMotion.current
    AnimatedVisibility(line != null, modifier, enter = if (still) fadeIn() else fadeIn() + slideInVertically { it }, exit = if (still) fadeOut() else fadeOut() + slideOutVertically { it }) {
        Row(
            // At most the room between the clock and the weather. The score line keeps its room; the competition gets
            // what's left (none: it goes), the channel a little.
            Modifier.widthIn(max = (290 * s).dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.62f))
                .border((0.8f * s).dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(50))
                .padding(horizontal = (10 * s).dp, vertical = (4 * s).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("LIVE", color = Color.White, fontSize = (6.5f * s).sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape((3 * s).dp)).background(LocalAccent.current).padding(horizontal = (4 * s).dp, vertical = (1 * s).dp))
            Spacer(Modifier.width((6 * s).dp))
            if (!label.isNullOrBlank()) {
                Text(label.uppercase(), color = Color.White.copy(alpha = 0.65f), fontSize = (7 * s).sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).widthIn(max = (90 * s).dp))
                Spacer(Modifier.width((6 * s).dp))
            }
            Text(line.orEmpty(), color = Color.White, fontSize = (9.5f * s).sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = (150 * s).dp))
            if (!where.isNullOrBlank()) {
                Spacer(Modifier.width((6 * s).dp))
                Text(where, color = Color.White.copy(alpha = 0.7f), fontSize = (7.5f * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = (64 * s).dp))
            }
        }
    }
}
