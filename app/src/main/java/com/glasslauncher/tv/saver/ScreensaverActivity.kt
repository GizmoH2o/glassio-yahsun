package com.glasslauncher.tv.saver

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.glasslauncher.tv.GlassApp
import com.glasslauncher.tv.ui.clockText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.material3.Text

/** Full-screen aerial video (or backdrop slideshow). Any key press or touch dismisses it. */
class ScreensaverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val app = application as GlassApp
        if (app.settings.saverMode == "system") {
            // Hand over to the TV's own screensaver (e.g. Aerial Views): the posters play for the few seconds
            // until the system starts it, then this screen steps aside. If the local shell is not allowed,
            // the posters simply stay.
            setContent { Box(Modifier.fillMaxSize().background(Color.Black)) }
            Thread {
                val now = SystemDream.startNow(applicationContext)
                runOnUiThread {
                    if (now.isSuccess) { finish(); overridePendingTransition(0, 0); return@runOnUiThread }
                    // No shell (USB debugging not allowed): SystemUI's "Start now", when the TV lets an app open it.
                    if (now.exceptionOrNull() !is IllegalStateException && SystemDream.startByIntent(this)) {
                        finish(); overridePendingTransition(0, 0); return@runOnUiThread
                    }
                    // Fallback: let the system start it after its shortest timeout, posters meanwhile.
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    setContent { com.glasslauncher.tv.ui.GlassType { SaverContent(app, forceSlideshow = true) } }
                    Thread {
                        if (SystemDream.arm(applicationContext).isSuccess)
                            SystemDream.awaitAndRestore(applicationContext) { runOnUiThread { if (!isFinishing) finish() } }
                        else runOnUiThread {
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            android.widget.Toast.makeText(this, "Allow USB debugging for Glassio to use the TV screensaver", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }.apply { isDaemon = true }.start()
                }
            }.apply { isDaemon = true }.start()
            return
        }
        setContent { com.glasslauncher.tv.ui.GlassType { SaverContent(app) } }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) finish()
        return true
    }
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean { finish(); return true }
    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean { if (ev.actionMasked != MotionEvent.ACTION_HOVER_EXIT) finish(); return true }
}

@OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun SaverContent(app: GlassApp, forceSlideshow: Boolean = false) {
    val ctx = LocalContext.current
    val slideshow = forceSlideshow || app.settings.saverMode != "aerial"
    val urls by produceState(emptyList<String>()) {
        value = if (!slideshow) withContext(Dispatchers.IO) { AerialSource.playlist(app) } else emptyList()
    }
    var failed by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (urls.isNotEmpty() && !failed) {
            val player = remember(urls) {
                ExoPlayer.Builder(ctx).build().apply {
                    setMediaItems(urls.map { MediaItem.fromUri(it) })
                    repeatMode = Player.REPEAT_MODE_ALL
                    volume = 0f
                    addListener(object : Player.Listener {
                        var errors = 0
                        override fun onPlayerError(error: PlaybackException) {
                            errors++
                            if (errors > 6) failed = true else { seekToNextMediaItem(); prepare(); play() }
                        }
                        override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_READY) errors = 0 }
                    })
                    prepare(); playWhenReady = true
                }
            }
            DisposableEffect(player) { onDispose { player.release() } }
            AndroidView(
                factory = { c ->
                    PlayerView(c).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        this.player = player
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Slideshow(app)
        }
        // The clock and, under it, a followed team's next match: top right over the posters, bottom left over the videos.
        val match = if (app.settings.saverMatch) rememberMatchDay(app) else null
        if (app.settings.saverClock || match != null) androidx.compose.foundation.layout.Column(
            if (slideshow) Modifier.align(Alignment.TopEnd).padding(end = 56.dp, top = 36.dp)
            else Modifier.align(Alignment.BottomStart).padding(start = 56.dp, bottom = 40.dp),
            horizontalAlignment = if (slideshow) Alignment.End else Alignment.Start,
        ) {
            if (match != null && !slideshow) { MatchCard(match); androidx.compose.foundation.layout.Spacer(Modifier.size(14.dp)) }
            if (app.settings.saverClock) {
                val t by produceState("") { while (true) { value = clockText(app.settings.clock24h); delay(15_000) } }
                Text(t, color = Color.White.copy(alpha = 0.9f), fontSize = 34.sp)
            }
            if (match != null && slideshow) { androidx.compose.foundation.layout.Spacer(Modifier.size(14.dp)); MatchCard(match) }
        }
    }
}

/**
 * A followed team's next match (Live TV's Following), read again every half minute and whenever its live score moves.
 */
@Composable
private fun rememberMatchDay(app: GlassApp): com.glasslauncher.tv.live.LiveHome.MatchDay? {
    var bump by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val watching = com.glasslauncher.tv.live.LiveHome.watchScores(app) { bump++ }
        onDispose { com.glasslauncher.tv.live.LiveHome.stopScores(app, watching) }
    }
    val m by produceState<com.glasslauncher.tv.live.LiveHome.MatchDay?>(null, bump) {
        while (true) {
            value = withContext(Dispatchers.IO) { runCatching { com.glasslauncher.tv.live.LiveHome.matchDay(app) }.getOrNull() }
            delay(30_000)
        }
    }
    return m
}

/** The match on a small glass card: the competition, the match (or its score), LIVE or the kick-off and the channel. */
@Composable
private fun MatchCard(m: com.glasslauncher.tv.live.LiveHome.MatchDay) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)
    androidx.compose.foundation.layout.Column(
        Modifier.clip(shape).background(Color.Black.copy(alpha = 0.38f))
            .border(0.8.dp, Color.White.copy(alpha = 0.18f), shape).padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Text(m.label, color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp, letterSpacing = 1.2.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 1)
        Text(m.headline, color = Color.White, fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp).widthIn(max = 520.dp))
        androidx.compose.foundation.layout.Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (m.live) {
                Text("LIVE", color = Color.White, fontSize = 9.sp, letterSpacing = 0.8.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                        .background(Color(0xFFE5322D)).padding(horizontal = 6.dp, vertical = 2.dp))
                androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
            }
            // "LIVE  ·  574 MUTV": the pill says LIVE already.
            Text(if (m.live) m.where.substringAfter("·").trim() else m.where, color = Color(0xFFB3B3B8), fontSize = 12.sp, maxLines = 1)
        }
    }
}

/** Slow Ken Burns over the catalog backdrops, with each title's logo. */
@Composable
private fun Slideshow(app: GlassApp) {
    val raw by app.catalogs.items.collectAsState()
    val enriched by app.catalogs.enrichedItems.collectAsState()
    val items = remember(raw) { raw.filter { it.background.isNotBlank() } }
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(items.size) { while (items.size > 1) { delay(12_000); index++ } }
    val item = items.takeIf { it.isNotEmpty() }?.let { val r = it[index.mod(it.size)]; enriched[r.key] ?: r }
    val url = item?.background
    val t = rememberInfiniteTransition(label = "kb")
    val scale by t.animateFloat(1.0f, 1.14f, infiniteRepeatable(tween(12_000, easing = LinearEasing), RepeatMode.Reverse), label = "s")
    Crossfade(targetState = url, animationSpec = tween(1600), label = "show") { u ->
        Box(Modifier.fillMaxSize().clipToBounds()) {
            if (u != null) AsyncImage(
                model = u, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale }.alpha(0.95f),
            )
        }
    }
    // Soft shade so the logo reads on bright frames.
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(
        0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f))))
    Crossfade(targetState = item, animationSpec = tween(1600), label = "logo") { it ->
        Box(Modifier.fillMaxSize().padding(start = 64.dp, bottom = 52.dp), contentAlignment = Alignment.BottomStart) {
            if (it != null) {
                if (it.logo.isNotBlank()) AsyncImage(
                    model = it.logo, contentDescription = it.name, contentScale = ContentScale.Fit,
                    alignment = Alignment.BottomStart,
                    modifier = Modifier.size(380.dp, 150.dp),
                ) else Text(it.name, color = Color.White, fontSize = 40.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            }
        }
    }
}
