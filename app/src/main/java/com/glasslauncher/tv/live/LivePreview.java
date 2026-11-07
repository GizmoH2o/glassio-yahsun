package com.glasslauncher.tv.live;

import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.SurfaceView;
import android.widget.FrameLayout;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import java.util.Collections;

/**
 * The guide's channel preview: resting on another channel's row, a second, silent player fills the guide's video window
 * with it while the channel you're watching carries on. It's a second stream, so it stays out of the way: the lightest
 * version of the channel, never while something's recording, and a pause when the provider says no.
 */
final class LivePreview {
    /** How long a row has to be rested on (moving through the guide opens nothing), and the pause after a refusal. */
    static final long REST = 1500, PAUSE = 5 * 60_000L;

    private final Live live;
    private final FrameLayout root;
    private final Handler main = new Handler(Looper.getMainLooper());
    private SurfaceView surface;
    private ExoPlayer player;
    private Xtream.Channel aimed, shown;
    private final RectF box = new RectF();
    private long refusedAt, openedAt;

    LivePreview(Live live, FrameLayout root) {
        this.live = live;
        this.root = root;
    }

    /** Whether to preview a row: a real channel, not the one playing, not locked, with a stream to spare. Pure. */
    static boolean wanted(boolean on, boolean smart, boolean same, boolean locked, boolean recording, boolean resting) {
        return on && !smart && !same && !locked && !recording && !resting;
    }

    /** Whether an HTTP answer means the account's streams are all in use. Pure. */
    static boolean limit(int http) {
        return http == 509 || http == 458 || http == 403 || http == 429;
    }

    /** Whether it's still pausing after a refusal at `refusedAt` (0: never). Pure. */
    static boolean resting(long refusedAt, long now) {
        return refusedAt > 0 && now - refusedAt < PAUSE;
    }

    private final Runnable open = this::open;

    /**
     * The row the guide rests on, and its window in pixels; null: none (the guide's shut, its groups are out). Called
     * while the guide draws, so the views change just after the frame: changing them during it crashes the draw.
     */
    void aim(Xtream.Channel ch, RectF window) {
        if (window != null && !window.equals(box)) {
            box.set(window);
            main.post(this::place);
        }
        if (ch == aimed) return;
        aimed = ch;
        main.post(() -> {
            main.removeCallbacks(open);
            stop();
            if (aimed != null) main.postDelayed(open, REST);
        });
    }

    private void open() {
        Xtream.Channel ch = aimed;
        if (ch == null) return;
        Xtream.Channel playing = live.playing();
        if (!wanted(LiveOptions.channelPreview(live), ch.smart, playing != null && playing.id == ch.id, live.locked(ch),
                LiveRecordings.anyRecording(live), resting(refusedAt, System.currentTimeMillis()))) return;
        surface = new SurfaceView(live);
        surface.setZOrderMediaOverlay(true);
        root.addView(surface, new FrameLayout.LayoutParams(1, 1, Gravity.TOP | Gravity.START));
        place();
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory().setUserAgent(Live.USER_AGENT)
                .setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(8_000).setReadTimeoutMs(12_000);
        player = new ExoPlayer.Builder(live)
                .setStuckPlayingDetectionTimeoutMs(Integer.MAX_VALUE)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(Xtream.resolving(live, new DefaultDataSource.Factory(live, http))))
                .build();
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build());
        player.setVolume(0);
        player.setVideoSurfaceView(surface);
        final long began = openedAt = System.currentTimeMillis();
        player.addListener(new Player.Listener() {
            @Override public void onRenderedFirstFrame() {
                AppLog.w("preview: " + ch.name + " showing after " + (System.currentTimeMillis() - began) + " ms");
            }

            @Override public void onPlayerError(PlaybackException e) {
                int code = 0;
                for (Throwable t = e; t != null; t = t.getCause()) {
                    if (t instanceof androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                        code = ((androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) t).responseCode;
                    }
                }
                AppLog.w("preview: " + e.getErrorCodeName() + (code > 0 ? " (HTTP " + code + ")" : "") + ", " + ch.name);
                if (limit(code)) refusedAt = System.currentTimeMillis();
                stop();
            }
        });
        // The lightest version: a preview needs no more, and it costs the connection least.
        Xtream.Channel src = LiveMerge.pick(live, ch, false, Collections.emptySet());
        if (ch.sources != null) for (Xtream.Channel s : ch.sources) if (src == null || LiveMerge.need(s) < LiveMerge.need(src)) src = s;
        if (src == null) src = ch;
        String format = live.previewFormat();
        String url = Xtream.streamUrl(live, src, format);
        player.setMediaItem(new MediaItem.Builder().setUri(url).setMimeType(Xtream.mime(live, url, format)).build());
        player.prepare();
        player.play();
        shown = ch;
    }

    private void place() {
        if (surface == null || box.isEmpty()) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surface.getLayoutParams();
        lp.width = Math.round(box.width());
        lp.height = Math.round(box.height());
        lp.leftMargin = Math.round(box.left);
        lp.topMargin = Math.round(box.top);
        surface.setLayoutParams(lp);
    }

    /** Whatever's showing goes (the row it was for stays aimed at). */
    void stop() {
        if (player != null) {
            androidx.media3.exoplayer.DecoderCounters n = player.getVideoDecoderCounters();
            if (n != null && shown != null) {
                n.ensureUpdated();
                AppLog.w("preview: " + shown.name + " ended: " + n.renderedOutputBufferCount + " frames shown, " + n.droppedBufferCount + " dropped, "
                        + (System.currentTimeMillis() - openedAt) / 1000 + " s");
            }
            player.release();
            player = null;
        }
        if (surface != null) {
            root.removeView(surface);
            surface = null;
        }
        shown = null;
    }

    /** Live TV's going away, or the guide shut. */
    void close() {
        aimed = null;
        main.removeCallbacks(open);
        stop();
    }
}
