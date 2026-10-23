package com.glasslauncher.tv.saver

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.glasslauncher.tv.GlassApp
import kotlin.concurrent.thread

/** System screensaver (Settings > Device Preferences > Screen saver): plays the same aerial playlist. */
@UnstableApi
class GlassDreamService : DreamService() {
    private var player: ExoPlayer? = null
    private val ui = Handler(Looper.getMainLooper())
    private var view: PlayerView? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = true
        view = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            setBackgroundColor(Color.BLACK)
        }
        setContentView(view)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        val app = application as GlassApp
        thread(name = "aerial-list") {
            val urls = AerialSource.playlist(app)
            ui.post {
                if (urls.isEmpty() || view == null) return@post
                val p = ExoPlayer.Builder(this).build()
                p.setMediaItems(urls.map { MediaItem.fromUri(it) })
                p.repeatMode = Player.REPEAT_MODE_ALL
                p.volume = 0f
                p.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) { p.seekToNextMediaItem(); p.prepare(); p.play() }
                })
                p.prepare(); p.playWhenReady = true
                view?.player = p
                player = p
            }
        }
    }

    override fun onDreamingStopped() {
        player?.release(); player = null
        super.onDreamingStopped()
    }
}
