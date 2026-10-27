package com.glasslauncher.tv.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glasslauncher.tv.live.LiveHome
import kotlinx.coroutines.delay

/**
 * The kick-off alert: a team you follow (Following) has just started playing while the home screen is in front. A
 * glass card under the weather says so, with Watch (Live TV, full screen), Small window (Watch on Home) and Not now.
 * Each match is told once; the card goes by itself after a while; Back closes it (MainActivity takes it first).
 */
object KickOff {
    val now = mutableStateOf<LiveHome.MatchDay?>(null)
    /** How long after kick-off the card can still come up. */
    private const val WINDOW_MS = 10 * 60_000L
    private const val PREFS = "glass_kickoff"

    /** Whether `m` kicked off within the last [WINDOW_MS] at `at` (live, with a key to remember it by). */
    fun justStarted(m: LiveHome.MatchDay?, at: Long): Boolean =
        m != null && m.live && m.key.isNotEmpty() && at >= m.start && at - m.start <= WINDOW_MS

    /** Blocking: whether `m` is a match to tell about now (just started, not told before); marks it told when so. */
    fun shouldTell(ctx: Context, m: LiveHome.MatchDay?, at: Long = System.currentTimeMillis()): Boolean {
        if (m == null || !justStarted(m, at)) return false
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val told = sp.getStringSet("told", emptySet()) ?: emptySet()
        if (m.key in told) return false
        // Kept small: only the last few matches.
        sp.edit().putStringSet("told", (told.toList().takeLast(20) + m.key).toSet()).apply()
        return true
    }

    fun close() { now.value = null }
}

@Composable
fun KickOffCard() {
    val m = KickOff.now.value ?: return
    val ctx = LocalContext.current
    val s = LocalConfiguration.current.screenWidthDp / 960f
    val watch = remember { FocusRequester() }
    // It goes by itself after half a minute; selected as it comes up, so OK watches.
    LaunchedEffect(m) { delay(250); runCatching { watch.requestFocus() } }
    LaunchedEffect(m) { delay(30_000); if (KickOff.now.value === m) KickOff.close() }
    val shape = RoundedCornerShape((14 * s).dp)
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.TopEnd).padding(top = (50 * s).dp, end = (40 * s).dp).width((272 * s).dp)
                .clip(shape).background(Color(0xF0181820)).border(0.8.dp, Color.White.copy(alpha = 0.22f), shape)
                .padding(horizontal = (12 * s).dp, vertical = (10 * s).dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("LIVE", color = Color.White, fontSize = (7 * s).sp, fontWeight = FontWeight.Bold, letterSpacing = (0.6f * s).sp,
                    modifier = Modifier.background(Color(0xFFE5342B), RoundedCornerShape((4 * s).dp)).padding(horizontal = (5 * s).dp, vertical = (1.5f * s).dp))
                Spacer(Modifier.width((7 * s).dp))
                Text(m.label.ifBlank { "FOLLOWING" } + "  ·  KICK-OFF", color = AppleSecondary, fontSize = (8 * s).sp,
                    fontWeight = FontWeight.SemiBold, letterSpacing = (0.6f * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height((6 * s).dp))
            Text(m.headline, color = Color.White, fontSize = (12 * s).sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            if (m.where.isNotBlank()) Text(m.where, color = AppleSecondary, fontSize = (9 * s).sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = (2 * s).dp))
            Spacer(Modifier.height((9 * s).dp))
            Row(horizontalArrangement = Arrangement.spacedBy((6 * s).dp)) {
                KickButton("Watch", s, Modifier.focusRequester(watch)) {
                    KickOff.close()
                    runCatching { ctx.startActivity(LiveHome.open(ctx, m.channel, false)) }
                }
                KickButton("Small window", s) {
                    KickOff.close()
                    Thread({
                        val mini = runCatching { LiveHome.miniFor(ctx.applicationContext, m.channel) }.getOrNull()
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            if (mini != null) MiniLive.now.value = mini else toast(ctx, "Couldn't open that channel in the small window")
                        }
                    }, "kickoff-mini").start()
                }
                KickButton("Not now", s) { KickOff.close() }
            }
        }
    }
}

/** A button on the card: a faint glass pill, the white plate with dark text when selected (Glassio's dialogs). */
@Composable
private fun KickButton(text: String, s: Float, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(
        text, color = if (focused) Color.Black else Color.White, fontSize = (9.5f * s).sp,
        fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape((8 * s).dp))
            .background(if (focused) Color.White.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.08f))
            .onFocusChanged { focused = it.isFocused }
            .tvPress(onClick = onClick)
            .padding(horizontal = (10 * s).dp, vertical = (5 * s).dp),
    )
}
