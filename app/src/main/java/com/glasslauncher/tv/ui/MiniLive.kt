package com.glasslauncher.tv.ui

import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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
 * Watch on Home (Live TV's menu): the channel goes on in a small glass window at the top right of the home screen
 * while you browse, under the weather. Back closes it (MainActivity takes it first); so does leaving the home screen (an app, Live TV itself, the screensaver), which
 * lets the provider's one connection go. Opening Live TV again goes on full screen with the same channel.
 */
object MiniLive {
    val now = mutableStateOf<LiveHome.Mini?>(null)

    fun close() { now.value = null }
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
    key(m) { MiniWindow(m) }
}

@Composable
private fun MiniWindow(m: LiveHome.Mini) {
    val ctx = LocalContext.current
    val s = LocalConfiguration.current.screenWidthDp / 960f
    val player = remember(m) {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(LiveHome.userAgent())
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(20_000)
        ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx, http)))
            .build().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                setMediaItem(MediaItem.Builder().setUri(m.url).apply { if (m.mime.isNotBlank()) setMimeType(m.mime) }.build())
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        toast(ctx, "Couldn't play ${m.name}")
                        MiniLive.close()
                    }
                })
                prepare()
                playWhenReady = true
            }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    val shape = RoundedCornerShape((14 * s).dp)
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.TopEnd).padding(top = (50 * s).dp, end = (40 * s).dp).width((272 * s).dp)
                .clip(shape).background(Color(0xF0181820)).border(0.8.dp, Color.White.copy(alpha = 0.22f), shape)
                .padding((5 * s).dp),
        ) {
            // A SurfaceView (the video straight to the screen, nothing for the GPU to redraw), so square: the glass
            // frame around it rounds the window.
            AndroidView(
                factory = { c -> SurfaceView(c).also { v -> v.keepScreenOn = true; player.setVideoSurfaceView(v) } },
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black),
            )
            Row(Modifier.fillMaxWidth().padding(start = (4 * s).dp, end = (4 * s).dp, top = (6 * s).dp, bottom = (2 * s).dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("LIVE", color = Color.White, fontSize = (7 * s).sp, fontWeight = FontWeight.Bold, letterSpacing = (0.6f * s).sp,
                    modifier = Modifier.background(Color(0xFFE5342B), RoundedCornerShape((4 * s).dp)).padding(horizontal = (5 * s).dp, vertical = (1.5f * s).dp))
                Spacer(Modifier.width((7 * s).dp))
                Text(m.name, color = Color.White.copy(alpha = 0.92f), fontSize = (10 * s).sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("Back closes", color = AppleSecondary, fontSize = (8.5f * s).sp, maxLines = 1)
            }
        }
    }
}
