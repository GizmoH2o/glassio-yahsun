package com.glasslauncher.tv.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import coil3.compose.AsyncImage
import com.glasslauncher.tv.data.HeroItem
import com.glasslauncher.tv.data.NuvioLaunch

/**
 * Pressing Play: the title's picture, its name, what plays and "Opening in Nuvio", for a moment before Nuvio opens over
 * it, so going to another app doesn't look like Glassio vanishing (as Netflix covers starting a title). It goes when
 * Glassio is back in front.
 */
object Handoff {
    /** The title, the app it goes to (NuvioLaunch's target), when. */
    data class Going(val item: HeroItem, val target: String, val at: Long)
    val now = mutableStateOf<Going?>(null)
    private const val SHOW_MS = 450L
    private val main = Handler(Looper.getMainLooper())

    /** Shows it, then [start] (the app opening) a moment later. */
    /** Glassio's context, for keeping what went to be watched (Android may close Glassio meanwhile). */
    @Volatile var ctx: android.content.Context? = null

    fun go(item: HeroItem, target: String, start: () -> Unit) {
        val g = Going(item, target, SystemClock.uptimeMillis())
        ctx?.let { com.glasslauncher.tv.live.NavSound.play(it) }
        last = item
        now.value = g
        ctx?.getSharedPreferences("handoff", 0)?.edit()?.putString("key", item.key)?.putLong("at", System.currentTimeMillis())?.apply()
        main.postDelayed({ start() }, SHOW_MS)
        // Nothing opened (the app missing, a link refused): it goes by itself.
        main.postDelayed({ if (now.value === g) now.value = null }, 6_000)
    }

    /** Glassio in front again (MainActivity.onResume): the cover goes, though not during Play's own moment. */
    fun back(c: android.content.Context) {
        ctx = c.applicationContext
        now.value?.let { g -> if (SystemClock.uptimeMillis() - g.at > SHOW_MS + 300) now.value = null }
        // Back from watching (not a quick look; kept in a file, so a Glassio closed meanwhile still knows): Up next.
        val p = c.getSharedPreferences("handoff", 0)
        val key = p.getString("key", null) ?: return
        val away = System.currentTimeMillis() - p.getLong("at", 0)
        if (away < 20_000) return
        p.edit().clear().apply()
        if (away < 6 * 3_600_000L) returnedKey.value = key
    }

    /** The key of the title just watched, for Home's Up next card (taken by it). */
    val returnedKey = mutableStateOf<String?>(null)

    /** The title last sent to be watched (while Glassio stayed open), if Continue watching has nothing for it. */
    @Volatile var last: HeroItem? = null

    /** The Up next card is up (Home then takes no focus, so it can't pull the selection back to its big picture). */
    val cardOn = mutableStateOf(false)

    /** "Nuvio" for Nuvio Legacy / (official) / Arc, "Stremio". Pure. */
    fun shortName(target: String): String = (NuvioLaunch.NAMES[target] ?: "").substringBefore(' ')
}

@Composable
fun HandoffHost(s: Float) {
    val g = Handoff.now.value ?: return
    val item = g.item
    val a = remember(g) { Animatable(0f) }
    val line = remember(g) { Animatable(0f) }
    LaunchedEffect(g) { a.animateTo(1f, tween(220, easing = Motion.EaseOut)) }
    LaunchedEffect(g) { line.animateTo(1f, tween(2_400, easing = LinearEasing)) }
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = a.value }.background(Ds.Bg)) {
        val pic = item.background.ifBlank { item.poster }
        if (pic.isNotBlank()) AsyncImage(sized(pic, "w1280"), null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.04f; scaleY = 1.04f })
        Box(Modifier.fillMaxSize().background(Color(0x8C0B0B0D)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Ds.Bg)))
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy((9 * s).dp)) {
            Box(Modifier.width((300 * s).dp), contentAlignment = Alignment.Center) { Title(item, "logo", 1.0f, s) }
            val what = item.sub.takeIf { it.isNotBlank() }?.let { (if (item.upNext) Lang.tr("Play") else Lang.tr("Resume")) + " " + it } ?: Lang.tr("Play")
            Text(what, color = Color(0xFFE0E0E4), fontSize = (9 * s).sp, fontWeight = FontWeight.SemiBold)
            val app = Handoff.shortName(g.target)
            if (app.isNotBlank()) Row(
                Modifier.padding(top = (5 * s).dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.14f))
                    .padding(start = (6 * s).dp, end = (10 * s).dp, top = (4.5f * s).dp, bottom = (4.5f * s).dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((5 * s).dp),
            ) {
                ServiceIcon(NuvioLaunch.TARGETS[g.target] ?: "", s)
                Text(Lang.tr("Opening in") + " " + app, color = Color.White, fontSize = (8 * s).sp, fontWeight = FontWeight.Bold)
            }
            Box(Modifier.width((110 * s).dp).height((1.6f * s).dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.2f))) {
                Box(Modifier.fillMaxWidth(line.value).height((1.6f * s).dp).background(Color.White))
            }
        }
    }
}

/**
 * Back from watching: what's next for the title just watched, as Netflix offers it. A card at the bottom right of Home
 * with its picture, Up next (the next episode) or Continue (the rest), Play and Not now; it goes by itself after a few
 * seconds or with Back. [restore]: where the selection goes after it.
 */
@Composable
fun UpNextHost(app: com.glasslauncher.tv.GlassApp, s: Float, restore: androidx.compose.ui.focus.FocusRequester) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var card by androidx.compose.runtime.remember { mutableStateOf<HeroItem?>(null) }
    var backTo by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    // Watched through snapshotFlow: clearing the key mustn't restart (cancel) this wait, as keying on it did.
    LaunchedEffect(Unit) { androidx.compose.runtime.snapshotFlow { Handoff.returnedKey.value }.collect { key ->
        if (key == null) return@collect
        Handoff.returnedKey.value = null
        val b = app.cw.items.value.firstOrNull { it.key == key }
        // Continue Watching catches up after watching (a few seconds): its entry for the title, with the next episode.
        var entry: HeroItem? = null
        for (i in 0 until 16) {
            entry = app.cw.items.value.firstOrNull { it.key == key }
            if (entry != null && (b == null || entry.sub != b.sub || entry.upNext)) break
            kotlinx.coroutines.delay(500)
        }
        card = entry ?: Handoff.last?.takeIf { it.key == key } ?: return@collect
        kotlinx.coroutines.delay(12_000)
        if (card != null) { card = null; backTo++ }
    } }
    // The card gone: the selection back on Home once Home can take it again (a frame later): on the card you played,
    // in its row, else the big picture.
    LaunchedEffect(backTo) {
        if (backTo > 0) {
            androidx.compose.runtime.withFrameNanos { }; kotlinx.coroutines.delay(30)
            if (RowHere.back?.let { runCatching { it.requestFocus() }.isSuccess } != true) runCatching { restore.requestFocus() }
        }
    }
    androidx.compose.runtime.DisposableEffect(card != null) { Handoff.cardOn.value = card != null; onDispose { Handoff.cardOn.value = false } }
    val c = card ?: return
    val play = androidx.compose.runtime.remember(c) { androidx.compose.ui.focus.FocusRequester() }
    var has by androidx.compose.runtime.remember(c) { mutableStateOf(false) }
    // Home puts its selection back on the big picture as Glassio comes back: asked again until the card has kept it.
    LaunchedEffect(c) {
        kotlinx.coroutines.delay(80)
        var held = 0
        repeat(30) {
            if (has) { if (++held >= 4) return@LaunchedEffect } else { held = 0; runCatching { play.requestFocus() } }
            kotlinx.coroutines.delay(100)
        }
    }
    androidx.activity.compose.BackHandler { card = null; backTo++ }
    val a = androidx.compose.runtime.remember(c) { Animatable(0f) }
    LaunchedEffect(c) { a.animateTo(1f, tween(320, easing = Motion.EaseOut)) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        Row(
            Modifier.padding(end = (EDGE * s).dp, bottom = (30 * s).dp).onFocusChanged { has = it.hasFocus }.graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 24 * density }
                .clip(RoundedCornerShape((Ds.R_PANEL * s).dp)).background(Ds.Surface.copy(alpha = 0.96f)).padding((8 * s).dp),
            horizontalArrangement = Arrangement.spacedBy((10 * s).dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            val pic = c.background.ifBlank { c.poster }
            Box(Modifier.width((120 * s).dp).height((68 * s).dp).clip(RoundedCornerShape((Ds.R_CARD * s).dp)).background(Ds.SurfaceHi)) {
                if (pic.isNotBlank()) AsyncImage(sized(pic, "w780"), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Column(verticalArrangement = Arrangement.spacedBy((3 * s).dp)) {
                Text(if (c.upNext) Lang.tr("UP NEXT") else Lang.tr("CONTINUE"), color = Color(0xFFFF6B5E), fontSize = (6.5f * s).sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (0.6f * s).sp)
                RawText(c.name, color = Color.White, fontSize = (10 * s).sp, fontWeight = FontWeight.Bold, maxLines = 1)
                if (c.sub.isNotBlank()) RawText(c.sub, color = Text2, fontSize = (7.5f * s).sp, maxLines = 1)
                Row(Modifier.padding(top = (3 * s).dp), horizontalArrangement = Arrangement.spacedBy((5 * s).dp)) {
                    DsButton(if (c.upNext) "Play" else "Resume", s, Modifier.focusRequester(play), primary = true) {
                        card = null; NuvioLaunch.play(ctx, app.settings, app.http, c)
                    }
                    DsButton("Not now", s) { card = null; backTo++ }
                }
            }
        }
    }
}
