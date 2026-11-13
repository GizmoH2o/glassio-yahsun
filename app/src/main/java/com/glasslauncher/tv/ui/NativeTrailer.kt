package com.glasslauncher.tv.ui

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import android.view.TextureView
import android.widget.FrameLayout
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import com.glasslauncher.tv.data.Glog
import com.glasslauncher.tv.data.YouTubeStreams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * A trailer played by Glassio itself (ExoPlayer), from YouTube's own streams (YouTubeStreams): no web view and its
 * 200 MB renderer, which a 1.8 GB TV couldn't hold beside Glassio. As NuvioTV plays its trailers.
 *
 * [texture]: drawn in a TextureView, which fades with what it's in (the card trailer's small window); otherwise a
 * SurfaceView, the TV's own video plane under the app, seen as the page's picture over it fades away (the GPU never
 * touches the frames). [ids]: the title's trailers, in turn; one that won't play, the next. [onPlaying] at its first
 * frame, [onWaiting] while the next loads, [onEnded] when it's over, stopped or none plays; [onProgress] 0 to 1.
 */
@UnstableApi
@SuppressLint("ViewConstructor")
class NativeTrailer(
    ctx: Context, ids: String, private val muted: Boolean, texture: Boolean,
    private val onPlaying: () -> Unit, private val onWaiting: () -> Unit, private val onEnded: () -> Unit,
    private val onProgress: (Float) -> Unit = {},
) : FrameLayout(ctx) {
    private val list = ids.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    private var at = 0
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var released = false
    private var played = false
    private var fading = false
    private val small = smallMemoryTv(ctx)
    private val surface: android.view.View = if (texture) TextureView(ctx) else SurfaceView(ctx)

    private val player: ExoPlayer = ExoPlayer.Builder(ctx)
        .setLoadControl(
            DefaultLoadControl.Builder()
                // A trailer needs little ahead of it, and the bytes are capped even before the time is reached: a
                // high-bitrate trailer otherwise keeps taking memory until Android closes Glassio (as NuvioTV found).
                .setBufferDurationsMs(15_000, 30_000, 2_000, 4_000)
                .setTargetBufferBytes((if (small) 12 else 24) * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build(),
        )
        .build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
            volume = 0f
        }

    init {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        isFocusable = false; isFocusableInTouchMode = false
        addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        if (surface is TextureView) player.setVideoTextureView(surface) else player.setVideoSurfaceView(surface as SurfaceView)
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (played) return
                played = true
                player.videoFormat?.let { f -> Glog.w("Trailer playing ${list.getOrNull(at)}: ${f.sampleMimeType} ${f.width}x${f.height} ${f.codecs ?: ""}") }
                if (!muted) ramp(1f, 1200)
                onPlaying()
                main.postDelayed({ if (!released) TrailerGuard.playedWell(context) }, 30_000)
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) next(ended = true)
            }
            override fun onPlayerError(error: PlaybackException) {
                Glog.w("Trailer: ${list.getOrNull(at)} won't play: ${error.errorCodeName}")
                next(ended = false)
            }
        })
        TrailerMemory.playing[this] = Runnable { onEnded() }
        TrailerGuard.arm(ctx)
        VideoDecoders.app = ctx.applicationContext
        start()
        main.post { tick.run() }
    }

    /** The trailer at [at]: its streams, then playing. None of them: over. */
    private fun start() {
        val maxHeight = if (small) 720 else 1080
        scope.launch {
            while (at < list.size && !released) {
                val src = YouTubeStreams.resolve(list[at], maxHeight, VideoDecoders::canDecode)
                if (released) return@launch
                if (src != null) { play(src); return@launch }
                at++
            }
            if (!released) onEnded()
        }
    }

    private fun play(src: YouTubeStreams.Source) {
        played = false; fading = false
        player.volume = 0f
        val factory = DefaultMediaSourceFactory(YouTubeChunks())
        if (src.audioUrl != null) {
            player.setMediaSource(MergingMediaSource(
                factory.createMediaSource(MediaItem.fromUri(src.videoUrl)),
                factory.createMediaSource(MediaItem.fromUri(src.audioUrl)),
            ))
        } else {
            val item = MediaItem.Builder().setUri(src.videoUrl).apply { if (src.hls) setMimeType(MimeTypes.APPLICATION_M3U8) }.build()
            player.setMediaSource(factory.createMediaSource(item))
        }
        player.prepare()
        player.playWhenReady = true
    }

    /** This one is over: the next of the title's trailers, if [ended] is false (it wouldn't play), else done. */
    private fun next(ended: Boolean) {
        if (released) return
        if (ended || played) { onEnded(); return }
        at++
        if (at < list.size) { onWaiting(); player.stop(); start() } else onEnded()
    }

    // How far through it is, and its sound going out over its last seconds.
    private val tick = object : Runnable {
        override fun run() {
            if (released) return
            val d = player.duration
            if (d > 0 && played) {
                val p = player.currentPosition
                onProgress((p.toFloat() / d).coerceIn(0f, 1f))
                if (!muted && !fading && d - p < 1_800) { fading = true; ramp(0f, 1500) }
            }
            main.postDelayed(this, 250)
        }
    }

    private var rampTo = 0f
    private fun ramp(to: Float, ms: Long) {
        rampTo = to
        val from = player.volume
        val start = android.os.SystemClock.uptimeMillis()
        main.post(object : Runnable {
            override fun run() {
                if (released || rampTo != to) return
                val k = ((android.os.SystemClock.uptimeMillis() - start).toFloat() / ms).coerceIn(0f, 1f)
                player.volume = from + (to - from) * k
                if (k < 1f) main.postDelayed(this, 40)
            }
        })
    }

    /** Stops it: the sound fades out a quarter of a second, then the player goes. */
    fun release() {
        if (released) return
        released = true
        TrailerMemory.playing.remove(this)
        TrailerGuard.disarm(context)
        scope.cancel()
        main.removeCallbacksAndMessages(null)
        val p = player
        if (!muted && p.volume > 0.05f && played) {
            val from = p.volume
            val start = android.os.SystemClock.uptimeMillis()
            main.post(object : Runnable {
                override fun run() {
                    val k = ((android.os.SystemClock.uptimeMillis() - start) / 280f).coerceIn(0f, 1f)
                    p.volume = from * (1f - k)
                    if (k < 1f) main.postDelayed(this, 30) else p.release()
                }
            })
        } else p.release()
    }
}

/** Whether this TV has a decoder for a stream: its codecs string at its size and frame rate (as the player asks). */
@UnstableApi
internal object VideoDecoders {
    private val cache = ConcurrentHashMap<String, Boolean>()
    @Volatile var app: Context? = null

    fun canDecode(codecs: String, width: Int, height: Int, fps: Int): Boolean = cache.getOrPut("$codecs|$width|$height|$fps") {
        val c = codecs.trim().lowercase()
        val mime = when {
            c.startsWith("av01") -> MimeTypes.VIDEO_AV1
            c.startsWith("vp09") || c.startsWith("vp9") -> MimeTypes.VIDEO_VP9
            c.startsWith("avc1") -> MimeTypes.VIDEO_H264
            c.startsWith("hev1") || c.startsWith("hvc1") -> MimeTypes.VIDEO_H265
            else -> return@getOrPut true
        }
        val format = Format.Builder().setSampleMimeType(mime).setCodecs(codecs).apply {
            if (width > 0 && height > 0) setWidth(width).setHeight(height)
            if (fps > 0) setFrameRate(fps.toFloat())
        }.build()
        val c0 = app ?: return@getOrPut true
        runCatching { MediaCodecUtil.getDecoderInfos(mime, false, false).any { it.isFormatSupported(c0, format) } }.getOrDefault(true)
    }
}

/**
 * YouTube's streams read in 10 MB pieces, each asked with YouTube's own `&range=` (it slows down or drops a connection
 * that takes a whole stream at once, and serves pieces at full speed). Other links as they are. From NuvioTV's
 * YoutubeChunkedDataSourceFactory (GPL-3.0).
 */
@UnstableApi
private class YouTubeChunks(private val chunk: Long = 10L * 1024 * 1024) : DataSource.Factory {
    override fun createDataSource(): DataSource = Chunked(
        DefaultHttpDataSource.Factory().setConnectTimeoutMs(15_000).setReadTimeoutMs(15_000).setAllowCrossProtocolRedirects(true).createDataSource(), chunk)

    private class Chunked(private val up: DefaultHttpDataSource, private val chunk: Long) : DataSource {
        private var yt = false
        private var spec: DataSpec? = null
        private var total = C.LENGTH_UNSET.toLong()
        private var start = 0L
        private var end = 0L
        private var read = 0L

        override fun addTransferListener(transferListener: TransferListener) = up.addTransferListener(transferListener)

        override fun open(dataSpec: DataSpec): Long {
            yt = dataSpec.uri.host.orEmpty().contains("googlevideo.com")
            if (!yt) return up.open(dataSpec)
            spec = dataSpec; start = dataSpec.position; total = dataSpec.length
            return openChunk()
        }

        private fun openChunk(): Long {
            val s = spec ?: throw IllegalStateException("not open")
            end = if (total != C.LENGTH_UNSET.toLong()) minOf(start + chunk - 1, start + total - 1) else start + chunk - 1
            val uri = s.uri.buildUpon().appendQueryParameter("range", "$start-$end").build()
            read = 0
            up.open(s.buildUpon().setUri(uri).setPosition(0).setLength(C.LENGTH_UNSET.toLong()).build())
            return total
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (!yt) return up.read(buffer, offset, length)
            val n = up.read(buffer, offset, length)
            if (n == C.RESULT_END_OF_INPUT) {
                val got = read
                up.close()
                if (got < end - start + 1) return C.RESULT_END_OF_INPUT
                start += got
                if (total != C.LENGTH_UNSET.toLong()) { total -= got; if (total <= 0) return C.RESULT_END_OF_INPUT }
                return try { openChunk(); up.read(buffer, offset, length) } catch (e: Exception) { C.RESULT_END_OF_INPUT }
            }
            read += n
            return n
        }

        override fun getUri(): Uri? = up.uri
        override fun close() { up.close(); spec = null }
    }
}
