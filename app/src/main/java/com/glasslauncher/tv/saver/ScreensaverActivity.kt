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
import androidx.compose.foundation.layout.offset
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
    private fun app() = application as GlassApp
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.glasslauncher.tv.data.HaEvents.screensaver(this)     // Home Assistant: the screensaver came on
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

    // The TV started its own screensaver on top (another app's): step aside, so one Back gets you home (issue #9).
    private val dreamReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: android.content.Intent) { if (!isFinishing) finish() }
    }
    override fun onStart() {
        super.onStart()
        runCatching {
            val f = android.content.IntentFilter(android.content.Intent.ACTION_DREAMING_STARTED)
            if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(dreamReceiver, f, android.content.Context.RECEIVER_EXPORTED)
            else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(dreamReceiver, f)
        }
    }
    override fun onStop() { runCatching { unregisterReceiver(dreamReceiver) }; super.onStop() }

    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // ◀ ▶ change the video, OK shows / hides the info; any other key ends it.
        val k = event.keyCode
        val st = app().settings
        // The posters: OK opens the title on screen (its More info page, as Prime Video's screensaver does).
        val poster = SaverControl.posterNow
        if (poster != null && st.saverMode == "slideshow" && (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) &&
            !com.glasslauncher.tv.ui.MusicUi.islandOpen.value) {
            if (event.action == KeyEvent.ACTION_UP) { app().requestTitle.value = poster; exit() }
            return true
        }
        if (st.saverMode != "system" && SaverControl.onKey(event, st)) return true
        if (event.action == KeyEvent.ACTION_DOWN) exit()
        return true
    }
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean { exit(); return true }
    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean { if (ev.actionMasked != MotionEvent.ACTION_HOVER_EXIT) exit(); return true }

    /**
     * Closes it faded to black, and the home screen then comes out of black. Closing straight away showed the home
     * screen half over the video for a moment, then it snapped (the video's surface went before the fade ended).
     */
    private var exiting = false
    private fun exit() {
        if (exiting || isFinishing) return
        exiting = true
        SaverControl.out.value = true
        window.decorView.postDelayed({
            com.glasslauncher.tv.ui.SaverGate.cover.floatValue = 1f
            finish(); @Suppress("DEPRECATION") overridePendingTransition(0, 0)
        }, 230)
    }
}

/**
 * The catalog backdrops with each title's logo. Pan (the default): each picture drifts slowly sideways, one to the left,
 * the next to the right, as Prime Video's screensaver does. Zoom: the slow Ken Burns of before.
 */
@Composable
internal fun Slideshow(app: GlassApp, posters: Boolean = true) {
    val raw by app.catalogs.items.collectAsState()
    val enriched by app.catalogs.enrichedItems.collectAsState()
    val items = remember(raw) { raw.filter { it.background.isNotBlank() } }
    var index by remember { mutableIntStateOf(0) }
    // The drift is for the Posters screensaver only; the posters standing in for the TV's own screensaver keep the zoom.
    val pan = posters && app.settings.saverMotion != "zoom"
    // Zoom: a new picture every 12 s. Pan: each picture says when its drift is done (below), and only then the next comes in.
    if (!pan) LaunchedEffect(items.size) { while (items.size > 1) { delay(SAVER_SLIDE_MS.toLong()); index++ } }
    val item = items.takeIf { it.isNotEmpty() }?.let { val r = it[index.mod(it.size)]; enriched[r.key] ?: r }
    val url = item?.background
    // OK opens it (ScreensaverActivity): the Posters screensaver only.
    androidx.compose.runtime.DisposableEffect(item, posters) {
        SaverControl.posterNow = if (posters) item else null
        onDispose { SaverControl.posterNow = null }
    }
    // The next picture, fetched while this one shows: it comes in at once (a full-size TMDB picture took seconds).
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(index, items.size) {
        val next = items.getOrNull((index + 1).mod(items.size.coerceAtLeast(1)))?.let { enriched[it.key] ?: it }?.background ?: return@LaunchedEffect
        coil3.SingletonImageLoader.get(ctx).enqueue(coil3.request.ImageRequest.Builder(ctx).data(next).build())
    }
    val t = rememberInfiniteTransition(label = "kb")
    val scale by t.animateFloat(1.0f, 1.14f, infiniteRepeatable(tween(12_000, easing = LinearEasing), RepeatMode.Reverse), label = "s")
    // The slide's number goes with its picture, so the direction flips with each one (and the fading-out picture keeps its own).
    Crossfade(targetState = url?.let { it to index }, animationSpec = tween(1600), label = "show") { slide ->
        Box(Modifier.fillMaxSize().clipToBounds()) {
            if (slide != null) {
                val (u, n) = slide
                // The drift, 0 → 1. It starts once the picture is in (a slow load no longer eats into it), runs while the
                // picture fades in, and when it reaches the far side the next picture fades in over this one, now still.
                val p = remember { androidx.compose.animation.core.Animatable(0f) }
                var ready by remember { mutableStateOf(false) }
                if (pan) LaunchedEffect(ready) {
                    if (!ready) { delay(4_000); ready = true; return@LaunchedEffect } // not loaded after 4 s: drift anyway
                    // In real time: the TV's animation speed (0.5× in Developer options on ours) halved the drift.
                    withContext(RealTime) { p.animateTo(1f, tween(SAVER_SLIDE_MS, easing = LinearEasing)) }
                    if (index == n && items.size > 1) index++
                }
                val left = n % 2 == 0
                Box(Modifier.fillMaxSize().graphicsLayer {
                    if (pan) {
                        scaleX = SAVER_PAN_SCALE; scaleY = SAVER_PAN_SCALE
                        // The room the enlarged picture has on each side, travelled from one side to the other.
                        val room = size.width * (SAVER_PAN_SCALE - 1f) / 2f
                        val d = room * (1f - 2f * p.value)
                        translationX = if (left) d else -d
                    } else { scaleX = scale; scaleY = scale }
                }.alpha(0.95f)) {
                    // The small copy Home already keeps (its glass is made from it) at once, the full picture over it:
                    // the screensaver no longer opens on black while that loads.
                    val small = com.glasslauncher.tv.ui.sized(u, "w780")
                    if (small != u) AsyncImage(model = small, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                        onState = { if (it is coil3.compose.AsyncImagePainter.State.Success) ready = true })
                    AsyncImage(
                        model = u, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                        onState = { if (it is coil3.compose.AsyncImagePainter.State.Success || it is coil3.compose.AsyncImagePainter.State.Error) ready = true },
                    )
                }
            }
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
            // What OK does here, small under the logo (Posters only).
            if (it != null && posters) Text(com.glasslauncher.tv.ui.Lang.tr("OK · More info"), color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, modifier = Modifier.offset(y = 26.dp))
        }
    }
}

private const val SAVER_SLIDE_MS = 12_000
/** How far the picture is enlarged for the pan: the travel is the extra width, 12% of the screen end to end. */
private const val SAVER_PAN_SCALE = 1.12f
/** Animations run at the clock's speed, whatever the TV's animation scale is set to. */
private object RealTime : androidx.compose.ui.MotionDurationScale { override val scaleFactor = 1f }
