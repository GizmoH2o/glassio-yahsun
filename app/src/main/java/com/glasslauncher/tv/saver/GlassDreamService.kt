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

private val R_FONT_SEMIBOLD = com.glasslauncher.tv.R.font.inter_semibold
private val R_FONT_MEDIUM = com.glasslauncher.tv.R.font.inter_medium

/**
 * System screensaver (Settings > Device Preferences > Screen saver): plays the same aerial playlist, with a
 * followed team's next match bottom left.
 */
@UnstableApi
class GlassDreamService : DreamService() {
    private var player: ExoPlayer? = null
    private val ui = Handler(Looper.getMainLooper())
    private var view: PlayerView? = null
    /** A followed team's next match (Live TV's Following), bottom left: its label, the match, where it's on. */
    private var matchBox: android.widget.LinearLayout? = null
    private val matchLines = arrayOfNulls<android.widget.TextView>(3)
    private var scores: Any? = null
    private val matchTick = object : Runnable {
        override fun run() { readMatch(); ui.postDelayed(this, 30_000) }
    }

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
        val d = resources.displayMetrics.density
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            visibility = android.view.View.GONE
            setPadding((20 * d).toInt(), (14 * d).toInt(), (20 * d).toInt(), (14 * d).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 18 * d
                setColor(0x61000000)
                setStroke((0.8f * d).toInt().coerceAtLeast(1), 0x2EFFFFFF)
            }
        }
        val faces = listOf(R_FONT_SEMIBOLD, R_FONT_SEMIBOLD, R_FONT_MEDIUM)
        val sizes = listOf(10f, 20f, 12f)
        val colors = listOf(0xB3FFFFFF.toInt(), Color.WHITE, 0xFFB3B3B8.toInt())
        for (i in 0..2) {
            matchLines[i] = android.widget.TextView(this).apply {
                textSize = sizes[i]
                setTextColor(colors[i])
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                maxWidth = (520 * d).toInt()
                runCatching { typeface = androidx.core.content.res.ResourcesCompat.getFont(this@GlassDreamService, faces[i]) }
                if (i == 0) letterSpacing = 0.12f
                if (i > 0) setPadding(0, (4 * d).toInt(), 0, 0)
            }
            box.addView(matchLines[i])
        }
        matchBox = box
        val root = android.widget.FrameLayout(this)
        root.addView(view)
        root.addView(box, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM or android.view.Gravity.START).apply {
            leftMargin = (56 * d).toInt(); bottomMargin = (40 * d).toInt()
        })
        setContentView(root)
    }

    private fun readMatch() {
        val app = application as GlassApp
        if (!app.settings.saverMatch) { matchBox?.visibility = android.view.View.GONE; return }
        thread(name = "dream-match") {
            val m = runCatching { com.glasslauncher.tv.live.LiveHome.matchDay(app) }.getOrNull()
            ui.post {
                val box = matchBox ?: return@post
                if (m == null) { box.visibility = android.view.View.GONE; return@post }
                matchLines[0]?.text = m.label
                matchLines[1]?.text = m.headline
                matchLines[2]?.text = m.where
                box.visibility = android.view.View.VISIBLE
            }
        }
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        val app = application as GlassApp
        if (app.settings.saverMatch) scores = com.glasslauncher.tv.live.LiveHome.watchScores(app) { readMatch() }
        ui.post(matchTick)
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
        ui.removeCallbacks(matchTick)
        scores?.let { com.glasslauncher.tv.live.LiveHome.stopScores(application, it) }; scores = null
        player?.release(); player = null
        super.onDreamingStopped()
    }
}
