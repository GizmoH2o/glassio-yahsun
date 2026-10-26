package com.glasslauncher.tv.ui

import android.view.SurfaceView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.glasslauncher.tv.live.LiveHome

/**
 * Watch on Home (Live TV's menu): the channel goes on in a small glass window at the top right of the home screen,
 * under the weather, while you browse. It's selected as it opens: OK goes full screen in Live TV, Up / Down (or the
 * remote's channel buttons) change the channel, Left goes back to the home screen. Back selects it again, and Back on
 * it closes it (MainActivity takes Back first). Leaving the home screen (an app, Live TV, the screensaver) closes it
 * too, which lets the provider's one connection go.
 */
object MiniLive {
    val now = mutableStateOf<LiveHome.Mini?>(null)
    /** Whether the window has the focus (the keys above are its). */
    val selected = mutableStateOf(false)
    /** Bumped to give the window the focus (Back while it isn't selected). */
    val selectRequest = mutableIntStateOf(0)
    /** A channel change on its way: the caption says so, and another one waits for it. */
    val changing = mutableStateOf(false)

    fun close() { now.value = null; selected.value = false; changing.value = false }

    /** The channel before (-1) or after (1), in the same category as Live TV goes through them. */
    fun step(ctx: android.content.Context, dir: Int) {
        val m = now.value ?: return
        if (changing.value) return
        changing.value = true
        Thread({
            val next = runCatching { LiveHome.miniStep(ctx.applicationContext, m.channel, dir) }.getOrNull()
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                changing.value = false
                if (next != null && now.value != null) now.value = next
                else if (next == null && now.value != null) toast(ctx, "No other channel to go to")
            }
        }, "mini-live-step").start()
    }
}

@Composable
fun MiniLiveWindow() {
    val m = MiniLive.now.value ?: return
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_PAUSE) MiniLive.close() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    MiniWindow(m)
}

@Composable
private fun MiniWindow(m: LiveHome.Mini) {
    val ctx = LocalContext.current
    val s = LocalConfiguration.current.screenWidthDp / 960f
    val focus = LocalFocusManager.current
    // One player while the window shows: a channel change swaps what it plays (never two connections at once).
    val player = remember {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(LiveHome.userAgent())
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(20_000)
        ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx, http)))
            .build().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        toast(ctx, "Couldn't play ${MiniLive.now.value?.name.orEmpty()}")
                        MiniLive.close()
                    }
                })
                playWhenReady = true
            }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(m) {
        player.setMediaItem(MediaItem.Builder().setUri(m.url).apply { if (m.mime.isNotBlank()) setMimeType(m.mime) }.build())
        player.prepare()
    }
    val req = remember { FocusRequester() }
    val selected by MiniLive.selected
    val changing by MiniLive.changing
    // Selected as it opens, and again when Back asks for it.
    LaunchedEffect(MiniLive.selectRequest.intValue) { kotlinx.coroutines.delay(250); runCatching { req.requestFocus() } }
    val lift by animateFloatAsState(if (selected) 1f else 0f, tween(160), label = "miniLift")
    val shape = RoundedCornerShape((14 * s).dp)
    val inner = 9 * s
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.TopEnd).padding(top = (50 * s).dp, end = (40 * s).dp).width((272 * s).dp)
                .graphicsLayer { val k = 1f + 0.04f * lift; scaleX = k; scaleY = k; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0f) }
                .focusRequester(req)
                .onFocusChanged { MiniLive.selected.value = it.isFocused }
                .onPreviewKeyEvent { ev ->
                    val down = ev.type == KeyEventType.KeyDown
                    when (ev.key) {
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            // On release: full screen in Live TV on this channel (this window closes as the home screen goes).
                            if (ev.type == KeyEventType.KeyUp) runCatching { ctx.startActivity(LiveHome.open(ctx, m.channel, false)) }
                            true
                        }
                        Key.DirectionUp, Key.ChannelUp -> { if (down) MiniLive.step(ctx, -1); true }
                        Key.DirectionDown, Key.ChannelDown -> { if (down) MiniLive.step(ctx, 1); true }
                        Key.DirectionLeft -> { if (down) focus.moveFocus(FocusDirection.Left); true }
                        Key.DirectionRight -> true
                        else -> false
                    }
                }
                .focusable()
                .clip(shape).background(Color(0xF0181820))
                .border(if (selected) 1.5.dp else 0.8.dp, Color.White.copy(alpha = 0.22f + 0.6f * lift), shape)
                .padding((5 * s).dp),
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                // A SurfaceView (the video straight to the screen: nothing for the GPU to redraw), so square: the
                // frame's colour drawn over its corners rounds it.
                AndroidView(
                    factory = { c -> SurfaceView(c).also { v -> v.keepScreenOn = true; player.setVideoSurfaceView(v) } },
                    modifier = Modifier.fillMaxSize().background(Color.Black),
                )
                Canvas(Modifier.fillMaxSize()) {
                    val r = inner * density
                    val corners = Path().apply {
                        fillType = PathFillType.EvenOdd
                        addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
                        addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(r, r)))
                    }
                    drawPath(corners, Color(0xFF181820))
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = (4 * s).dp, end = (4 * s).dp, top = (6 * s).dp, bottom = (2 * s).dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("LIVE", color = Color.White, fontSize = (7 * s).sp, fontWeight = FontWeight.Bold, letterSpacing = (0.6f * s).sp,
                    modifier = Modifier.background(Color(0xFFE5342B), RoundedCornerShape((4 * s).dp)).padding(horizontal = (5 * s).dp, vertical = (1.5f * s).dp))
                Spacer(Modifier.width((7 * s).dp))
                Text(if (changing) "Changing channel…" else m.name, color = Color.White.copy(alpha = 0.92f), fontSize = (10 * s).sp,
                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(if (selected) "OK full screen  ·  ▲▼ channels" else "Back to select", color = AppleSecondary,
                    fontSize = (8 * s).sp, maxLines = 1)
            }
        }
    }
}
