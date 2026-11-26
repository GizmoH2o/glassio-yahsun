package com.glasslauncher.tv.live;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Multiview (TiviMate's): two to four channels at once, in a grid (two side by side, one large and two small, or
 * two by two), or picture-in-picture. The arrow keys move between the boxes; the focused one has the sound and a white
 * frame. OK opens a box full screen (or picks its channel, as set), the channel buttons change it, OK held (or Menu) offers
 * the rest: reconnecting, the sound kept there, changing places, the layout, saved views and a wall of your teams' live
 * matches; Back closes. A box that stops tries again a few times, then says why, with what can be done.
 * Never more boxes than the account may stream at once.
 * Measurements in a 1920×1080 space (k = width / 1920).
 */
public class Multiview extends Activity {
    static final String EXTRA_CHANNEL = "com.glasslauncher.tv.live.extra.MULTI_CHANNEL", EXTRA_UNLOCKED = "com.glasslauncher.tv.live.extra.MULTI_UNLOCKED",
            EXTRA_SECOND = "com.glasslauncher.tv.live.extra.MULTI_SECOND";
    /** The channel to play full screen when Multiview leaves (in the result). */
    static final String RESULT_CHANNEL = "com.glasslauncher.tv.live.extra.MULTI_RESULT";

    private static final int MAX_BOXES = 4;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private FrameLayout root;
    private Grid grid;
    private Xtream.Lineup lineup;
    private final List<Box> boxes = new ArrayList<>();
    private int layout = 2, focus, allowed = MAX_BOXES;
    /** Picture-in-picture: two boxes, the second small over the first. */
    private boolean pip;
    private boolean unlocked, okDown, okHeld;

    /** One box: its channel (null: empty), its player and surface, what it says (loading, an error). */
    private final class Box {
        Xtream.Channel channel;
        ExoPlayer player;
        SurfaceView surface;
        /** Its surface is above the others (picture-in-picture's small one). */
        boolean over;
        String status = "";
        boolean tried;      // the other format, once
        /** Tries again after it dropped, and when it last began to play. */
        int again;
        long readyAt;
        /** Its player decodes on the video chip; else in software. */
        boolean hw;
        /** The chip refused this box: software from then on. */
        boolean refused;
        String format;
        Xtream.Program onNow;
        /** Why it stopped for good ("" while it plays or tries), over its buttons: the account's streams, the chip. */
        String problem = "";
        boolean limit, noRoom, waitFree;
        int button;
        /** When it tries again (uptime), 0: not waiting to. */
        long retryAt;
        /** The version of a merged channel picked here, or null: the best. */
        Xtream.Channel version, source;
        /** What it had drawn, when that last changed (the freeze check). */
        int drawn;
        long movedAt;
        String score = "";
        boolean testFrozen;
    }

    /** The box that keeps the sound while the focus moves, or null: the focused one. */
    private Box soundOn;
    private long freshenedAt;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LiveTheme.load(this);
        Ui.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new FrameLayout(this);
        root.setBackgroundColor(LiveTheme.bg);
        grid = new Grid(this);
        root.addView(grid, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        TestHook.register(this, testHook);
        unlocked = getIntent().getBooleanExtra(EXTRA_UNLOCKED, false);
        final int first = getIntent().getIntExtra(EXTRA_CHANNEL, -1);
        for (int i = 0; i < MAX_BOXES; i++) boxes.add(new Box());
        final Context app = getApplicationContext();
        io.execute(() -> {
            Xtream.Lineup saved = Xtream.saved(app);
            if (saved != null) {        // as Live TV shows them: the edits and groups made by hand, a Kids profile's channels only
                LiveChannelEdits.apply(app, saved);
                LiveGroups.apply(app, saved);
                if (LiveProfiles.kids(app)) saved = LiveProfiles.kidsOnly(app, saved);
            }
            final Xtream.Lineup l = saved;
            int max = Xtream.maxConnections(app);
            final List<LiveRecordings.Rec> recs = LiveStreams.recording(app);
            main.post(() -> {
                if (isFinishing()) return;
                lineup = l;
                allowed = Math.max(1, Math.min(MAX_BOXES, max));
                AppLog.w("multiview: the account allows " + max + " streams at once");
                if (allowed < 2) {
                    grid.say(Ui.tr("Seu provedor permite só um canal por vez.", "Your provider allows only one stream at a time."));
                }
                // Glassio 3: the 2×2 grid, the empty boxes ready for a channel (they play nothing until one's chosen).
                layout = Math.max(1, Math.min(LiveTheme.cinema() ? 4 : 2, allowed));
                reserve(recs);
                Xtream.Channel ch = l == null ? null : l.find(first);
                if (ch != null) setChannel(0, ch);
                int second = getIntent().getIntExtra(EXTRA_SECOND, -1);     // the test broadcast's second box, by number
                if (second >= 0 && l != null) for (Xtream.Channel c : l.all()) if (c.num == second) setChannel(1, c);
                relayout();
            });
        });
    }

    /** What records, each using one of the account's streams: a box each at the end, in use. */
    private List<LiveRecordings.Rec> reserved = new ArrayList<>();
    private int streams;

    private void reserve(List<LiveRecordings.Rec> recs) {
        if (LiveOptions.streamLimit(this) == LiveOptions.LIMIT_OFF) return;
        if (streams == 0) streams = allowed;
        reserved = recs;
        allowed = Math.max(0, streams - recs.size());
        for (int i = allowed; i < MAX_BOXES; i++) {
            Box b = boxes.get(i);
            stop(b);
            clear(b);
            b.channel = null;
            b.status = b.score = "";
            if (soundOn == b) soundOn = null;
        }
        if (allowed == 0) grid.say(Ui.tr("Todos os canais estão gravando", "All your streams are recording"));
        grid.invalidate();
    }

    /** The recording using box `i`, or null. */
    private LiveRecordings.Rec using(int i) {
        return i >= allowed && i - allowed < reserved.size() ? reserved.get(i - allowed) : null;
    }

    /** A recording about to start by itself: the last box goes, a moment before. */
    private final android.content.BroadcastReceiver streamWanted = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context c, android.content.Intent i) {
            boolean playing = false;
            int n = 0;
            for (Box b : boxes) if (b.player != null) n++;
            if (i.getBooleanExtra(LiveStreams.EXTRA_ONLY, false)) {
                setResultCode(getResultCode() + n);
                return;
            }
            playing = n > 0;
            if (!playing || LiveOptions.streamLimit(c) != LiveOptions.LIMIT_ASKS) return;
            setResultCode(RESULT_FIRST_USER);
            LiveSchedule.Item it = null;
            try {
                it = LiveSchedule.Item.of(new org.json.JSONObject(i.getStringExtra(LiveStreams.EXTRA_ITEM)));
            } catch (org.json.JSONException | NullPointerException ignored) {
            }
            String title = it == null ? "" : it.title.isEmpty() ? it.ch.name : it.title;
            grid.say(Ui.tr("Gravação em 20 s: ", "Recording in 20 s: ") + title + Ui.tr(". O último quadro fecha.", ". The last box closes."));
            main.postDelayed(() -> { if (!isFinishing()) reserve(LiveStreams.recording(Multiview.this)); }, LiveStreams.LET_GO_MS);
        }
    };

    /** A Kids profile's watching counts here too; its time over (or bedtime), back to Live TV, which says so. */
    private long familyLast;
    private final Runnable family = new Runnable() {
        @Override public void run() {
            Context c = Multiview.this;
            if (LiveFamily.on(c) && LiveProfiles.kids(c)) {
                long now = System.currentTimeMillis();
                String id = LiveProfiles.active(c).id;
                boolean watching = false;
                for (Box b : boxes) watching |= b.player != null && b.player.isPlaying();
                if (watching) LiveFamily.add(c, id, now, LiveFamily.tick(familyLast, now));
                familyLast = watching ? now : 0;
                if (LiveFamily.stopNow(c, id, now) != LiveFamily.NONE) {
                    finish();
                    return;
                }
            }
            main.postDelayed(this, 15_000);
        }
    };

    @Override protected void onStart() {
        super.onStart();
        familyLast = 0;
        main.removeCallbacks(family);
        main.post(family);
        main.removeCallbacks(watch);
        main.postDelayed(watch, 3000);
        LiveScores.get(this).watch(scores);
        registerReceiver(streamWanted, new android.content.IntentFilter(LiveStreams.WANTED), null, null,
                android.os.Build.VERSION.SDK_INT >= 33 ? Context.RECEIVER_NOT_EXPORTED : 0);
    }

    @Override protected void onStop() {
        try {
            unregisterReceiver(streamWanted);
        } catch (IllegalArgumentException ignored) {
        }
        main.removeCallbacks(family);
        main.removeCallbacks(watch);
        LiveScores.get(this).unwatch(scores);
        for (Box b : boxes) {
            stop(b);
            clear(b);
        }
        super.onStop();
    }

    @Override protected void onRestart() {
        super.onRestart();
        for (int i = 0; i < layout; i++) if (boxes.get(i).channel != null) start(boxes.get(i));
    }

    @Override protected void onDestroy() {
        io.shutdownNow();
        try {
            unregisterReceiver(testHook);
        } catch (IllegalArgumentException ignored) {
        }
        super.onDestroy();
    }

    // MARK: - When a box stops

    /** Nothing wrong with it (any more). */
    private void clear(Box b) {
        b.problem = "";
        b.limit = b.noRoom = b.waitFree = false;
        b.button = 0;
        b.retryAt = 0;
        b.movedAt = 0;
        b.drawn = 0;
        b.testFrozen = false;
    }

    /** It stopped for good: why, with what can be done about it. */
    private void fail(Box b, String why) {
        stop(b);
        b.retryAt = 0;
        b.status = "";
        b.problem = why;
        b.button = 0;
        AppLog.w("multiview: " + (b.channel == null ? "" : b.channel.name + ": ") + why);
        if (boxes.indexOf(b) == focus) LiveSpeech.say(grid, why);
        grid.invalidate();
    }

    /** Its own stream closed, then opened again after a moment (the provider still counts the old one). */
    private void freshen(Box b, String why) {
        AppLog.w("multiview: " + (b.channel == null ? "" : b.channel.name + ": ") + why + ", again");
        freshenedAt = SystemClock.uptimeMillis();
        if (!tryAgain(b)) fail(b, Ui.tr("O canal não responde", "No answer from the channel"));
    }

    /** Pressed: from the first try again. */
    private void reconnect(Box b) {
        if (b.channel == null) return;
        stop(b);
        clear(b);
        b.again = 0;
        b.tried = false;
        b.status = "…";
        main.postDelayed(() -> {
            if (b.channel != null && b.player == null && b.problem.isEmpty() && boxes.indexOf(b) < layout) start(b);
        }, 1500);
        grid.invalidate();
    }

    private List<Xtream.Channel> versions(Box b) {
        List<Xtream.Channel> all = b.channel == null ? null : LiveMerge.inOrder(b.channel);
        return all == null ? new ArrayList<>() : all;
    }

    /** The version after the one that stopped; or, with no room on the chip, a lighter one (null: none). */
    private Xtream.Channel otherVersion(Box b) {
        List<Xtream.Channel> all = versions(b);
        if (b.noRoom) {
            Xtream.Channel best = null;
            for (Xtream.Channel s : all) {
                if (b.source != null && LiveMerge.need(s) < LiveMerge.need(b.source) && (best == null || LiveMerge.need(s) > LiveMerge.need(best))) best = s;
            }
            return best;
        }
        if (all.size() < 2) return null;
        int at = -1;
        for (int i = 0; i < all.size(); i++) if (b.source != null && all.get(i).id == b.source.id) at = i;
        return all.get((at + 1) % all.size());
    }

    /** The buttons of a box that stopped: {text, what it does}. */
    private List<Object[]> buttons(Box b) {
        List<Object[]> out = new ArrayList<>();
        if (b.waitFree) out.add(new Object[] {Ui.tr("Esperando um canal livre", "Waiting for a stream"), (Runnable) () -> reconnect(b)});
        else if (b.limit) {
            out.add(new Object[] {Ui.tr("Tentar quando houver", "Try when one is free"), (Runnable) () -> {
                b.waitFree = true;
                grid.invalidate();
            }});
        } else {
            out.add(new Object[] {Ui.tr("Reconectar", "Reconnect"), (Runnable) () -> reconnect(b)});
        }
        final Xtream.Channel other = otherVersion(b);
        if (other != null) {
            out.add(new Object[] {b.noRoom ? Ui.tr("Versão mais leve", "Lighter version") : Ui.tr("Outra versão", "Another version"), (Runnable) () -> {
                b.version = other;
                b.refused = false;
                reconnect(b);
            }});
        }
        out.add(new Object[] {Ui.tr("Trocar o canal", "Change the channel"), (Runnable) () -> pick(boxes.indexOf(b))});
        return out;
    }

    /** Every few seconds: the boxes standing still, those waiting for a stream, the scores. */
    private final Runnable watch = new Runnable() {
        @Override public void run() {
            long up = SystemClock.uptimeMillis();
            boolean check = LiveOptions.multiFreeze(Multiview.this);
            int playing = 0;
            for (Box b : boxes) if (b.player != null) playing++;
            for (Box b : boxes) {
                if (b.waitFree && playing + LiveStreams.recording(Multiview.this).size() < (streams > 0 ? streams : allowed) && up - freshenedAt > 15_000) {
                    freshenedAt = up;
                    b.waitFree = false;
                    reconnect(b);
                    continue;
                }
                if (b.player == null || b.channel == null || !b.problem.isEmpty() || b.retryAt > 0) continue;
                int state = b.player.getPlaybackState();
                androidx.media3.exoplayer.DecoderCounters n = b.player.getVideoDecoderCounters();
                int drawn = n == null ? (int) b.player.getCurrentPosition() : n.renderedOutputBufferCount + n.skippedOutputBufferCount + n.droppedBufferCount;
                if (!b.testFrozen && (drawn != b.drawn || b.movedAt == 0)) {
                    b.drawn = drawn;
                    b.movedAt = up;
                }
                if (check && b.player.getPlayWhenReady() && up - freshenedAt > 8_000
                        && LiveMultiRules.frozen(state == Player.STATE_READY, b.readyAt > 0, b.movedAt, up)) freshen(b, "the picture stands still");
            }
            readScores();
            main.postDelayed(this, 3000);
        }
    };

    private final LiveScores.Listener scores = new LiveScores.Listener() {
        @Override public void changed() {
            readScores();
        }

        @Override public void goal(LiveScores.Score s, boolean forHome) {
            readScores();
        }
    };

    private void readScores() {
        boolean new_ = false;
        LiveScores all = LiveScores.get(this);
        for (Box b : boxes) {
            String t = "";
            if (b.channel != null && b.onNow != null && all.any()) {
                LiveScores.Score s = all.forTitle(Xtream.fold(b.onNow.title));
                if (s != null && !s.state.equals("pre")) t = s.homeScore + " – " + s.awayScore + (s.state.equals("in") ? "  ·  " + s.clock : "  ·  FT");
            }
            if (!t.equals(b.score)) {
                b.score = t;
                new_ = true;
            }
        }
        if (new_) grid.invalidate();
    }

    /** TestHook: box `multibox` dropped, failed, frozen or at the limit (`multitest`). */
    private final android.content.BroadcastReceiver testHook = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String what = i.getStringExtra("multitest");
            int at = i.getIntExtra("multibox", 0);
            if (what == null || at >= boxes.size() || boxes.get(at).channel == null) return;
            Box b = boxes.get(at);
            if (what.equals("drop")) freshen(b, "test");
            else if (what.equals("fail")) fail(b, Ui.tr("O canal não responde", "No answer from the channel"));
            else if (what.equals("freeze")) b.testFrozen = true;
            else if (what.equals("limit")) {
                fail(b, atLimit());
                b.limit = true;
            } else if (what.equals("room")) {
                fail(b, Ui.tr("A TV não toca mais um ao mesmo tempo", "The TV can't play another one at once"));
                b.noRoom = true;
            }
        }
    };

    private String atLimit() {
        List<LiveRecordings.Rec> recs = LiveStreams.recording(this);
        return Ui.tr("O limite de canais do provedor foi atingido", "Your provider's stream limit is reached")
                + (recs.isEmpty() ? "" : "  ·  " + LiveStreams.uses(recs, null));
    }

    // MARK: - The grid

    /** Box `i`'s place on screen (1920 space) in the current layout. */
    private void place(int layout, int i, RectF out) {
        if (pip) {
            float[] at = LiveMultiRules.placePip(i);
            out.set(at[0], at[1], at[2], at[3]);
        } else if (layout <= 1) {
            out.set(160, 90, 1760, 990);
        } else if (layout == 2) {
            float w = 924, h = 520, y = 280;
            out.set(24 + i * (w + 24), y, 24 + i * (w + 24) + w, y + h);
        } else if (layout == 3) {
            if (i == 0) out.set(24, 191, 1264, 889);
            else out.set(1288, 191 + (i - 1) * 354, 1896, 191 + (i - 1) * 354 + 344);
        } else {
            // 16:9 boxes above the line of help at the foot.
            float w = 805, h = 453, x0 = 143, y0 = 36, gap = 24;
            int col = i % 2, row = i / 2;
            out.set(x0 + col * (w + gap), y0 + row * (h + gap), x0 + col * (w + gap) + w, y0 + row * (h + gap) + h);
        }
    }

    /** The surfaces where the layout puts them; boxes beyond it stop. */
    private void relayout() {
        int sw = root.getWidth();
        if (sw <= 0) {
            root.post(this::relayout);
            return;
        }
        float k = sw / 1920f;
        RectF r = new RectF();
        for (int i = 0; i < MAX_BOXES; i++) {
            Box b = boxes.get(i);
            if (i >= layout) {
                stop(b);
                b.channel = null;
                if (b.surface != null) {
                    root.removeView(b.surface);
                    b.surface = null;
                }
                continue;
            }
            boolean over = pip && i == 1;     // the small picture has to be above the big one
            if (b.surface != null && b.over != over) {
                root.removeView(b.surface);
                b.surface = null;
            }
            if (b.surface == null) {
                b.surface = new SurfaceView(this);
                b.over = over;
                if (over) b.surface.setZOrderMediaOverlay(true);
                root.addView(b.surface, 0, new FrameLayout.LayoutParams(1, 1, Gravity.TOP | Gravity.START));
                if (b.player != null) b.player.setVideoSurfaceView(b.surface);
            }
            place(layout, i, r);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) b.surface.getLayoutParams();
            lp.width = Math.round(r.width() * k);
            lp.height = Math.round(r.height() * k);
            lp.leftMargin = Math.round(r.left * k);
            lp.topMargin = Math.round(r.top * k);
            b.surface.setLayoutParams(lp);
        }
        focus = Math.min(focus, layout - 1);
        sound();
        grid.invalidate();
    }

    // MARK: - Playing

    private void setChannel(int i, Xtream.Channel ch) {
        Box b = boxes.get(i);
        if (using(i) != null) {
            grid.say(Ui.tr("Em uso por uma gravação: ", "In use by a recording: ") + LiveStreams.name(using(i)));
            return;
        }
        if (!unlocked && locked(ch)) {
            grid.say(Ui.tr("Este canal pede o PIN: abra-o na TV ao vivo.", "This channel asks for the PIN: open it in Live TV."));
            return;
        }
        stop(b);
        b.channel = ch;
        clear(b);
        b.version = null;
        b.score = "";
        b.tried = false;
        b.again = 0;
        b.refused = false;
        String f = LiveOptions.format(this);
        b.format = !f.equals(LiveOptions.AUTO) ? f : lineup == null ? "ts" : lineup.format;
        b.onNow = null;
        start(b);
        loadOnNow(b, ch);
        grid.invalidate();
    }

    private boolean locked(Xtream.Channel ch) {
        if (LiveOptions.isLocked(this, ch.category)) return true;
        if (ch.sources != null) for (Xtream.Channel s : ch.sources) if (LiveOptions.isLocked(this, s.category)) return true;
        return false;
    }

    private void start(Box b) {
        if (b.channel == null || b.surface == null && boxes.indexOf(b) >= layout) return;
        if (b.player == null) {
            // Every box on the video chip (two at 1080p50 run at full speed here; in software a box manages 7 fps).
            // With the setting off, the focused box only, and it moves with the focus.
            boolean allChip = LiveOptions.multiviewHardware(this);
            b.hw = LiveMultiRules.hardware(allChip, b.refused, boxes.indexOf(b), focus);
            if (b.hw && !allChip) {
                for (Box o : boxes) {
                    if (o != b && o.player != null && o.hw) {     // it moves here: that one goes to software
                        stop(o);
                        final Box other = o;
                        main.postDelayed(() -> { if (other.channel != null && other.player == null) start(other); }, 600);
                    }
                }
            }
            DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory().setUserAgent(Live.USER_AGENT)
                    .setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(10_000).setReadTimeoutMs(20_000);
            // Sound decoded here (no passthrough: the TV takes one such output at a time, and a box without its
            // output never moves on); the muted boxes take no sound at all (setSound).
            DefaultRenderersFactory renderers = new DefaultRenderersFactory(this) {
                @Override protected androidx.media3.exoplayer.audio.AudioSink buildAudioSink(Context c, boolean floatOutput, boolean params) {
                    return new androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(c)
                            .setAudioCapabilities(androidx.media3.exoplayer.audio.AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                            .setEnableFloatOutput(floatOutput).setEnableAudioTrackPlaybackParams(params).build();
                }
            }.setEnableDecoderFallback(true);
            if (!b.hw) {
                renderers.setMediaCodecSelector((mime, secure, tunneling) -> {
                    List<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> all =
                            androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling);
                    if (!mime.startsWith("video/")) return all;
                    List<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> soft = new ArrayList<>();
                    for (androidx.media3.exoplayer.mediacodec.MediaCodecInfo i : all) if (i.softwareOnly) soft.add(i);
                    return soft.isEmpty() ? all : soft;
                });
            }
            // No "stuck" check: with two decoders at once it misfires, and each retry opens one more stream
            // the provider counts (it then refuses them, HTTP 509).
            b.player = new ExoPlayer.Builder(this, renderers)
                    .setStuckPlayingDetectionTimeoutMs(Integer.MAX_VALUE)
                    .setMediaSourceFactory(new DefaultMediaSourceFactory(Xtream.resolving(this, new DefaultDataSource.Factory(this, http))))
                    .setLoadControl(LiveOptions.loadControl(LiveOptions.buffer(this)))
                    .build();
            if (b.surface != null) b.player.setVideoSurfaceView(b.surface);
            final Box box = b;
            b.player.addAnalyticsListener(new androidx.media3.exoplayer.analytics.AnalyticsListener() {
                @Override public void onVideoDecoderInitialized(EventTime t, String decoder, long at, long took) {
                    AppLog.w("multiview: " + (box.channel == null ? "" : box.channel.name) + " decodes with " + decoder);
                }
            });
            b.player.addListener(new Player.Listener() {
                @Override public void onPlaybackStateChanged(int s) {
                    if (s == Player.STATE_BUFFERING) box.status = "…";
                    else if (s == Player.STATE_READY) {
                        box.status = "";      // (an error's message stays)
                        if (box.readyAt == 0) box.readyAt = android.os.SystemClock.uptimeMillis();
                    } else if (s == Player.STATE_ENDED) freshen(box, "the stream ended");      // live has no end: the provider closed it
                    grid.invalidate();
                }

                @Override public void onPlayerError(PlaybackException e) {
                    int http = 0;
                    for (Throwable t = e; t != null; t = t.getCause()) {
                        if (t instanceof androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                            http = ((androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) t).responseCode;
                        }
                    }
                    Throwable cause = e.getCause();
                    AppLog.w("multiview: " + e.getErrorCodeName() + (http > 0 ? " (HTTP " + http + ")" : "")
                            + (box.channel == null ? "" : ", " + box.channel.name)
                            + (cause == null ? "" : " — " + cause.getClass().getSimpleName() + ": " + cause.getMessage()));
                    boolean limit = http == 509 || http == 458 || http == 403 || http == 429;
                    if (limit) {        // the account's streams are all in use: no retry (it'd only add one)
                        fail(box, atLimit());
                        box.limit = true;
                        return;
                    }
                    boolean dropped = box.readyAt > 0;      // it was playing: the same again, its format is right
                    if (!box.tried && !dropped) {       // the other format, once (as Live TV does)
                        box.tried = true;
                        box.format = box.format.equals("ts") ? "m3u8" : "ts";
                        start(box);
                        return;
                    }
                    boolean decoder = e.errorCode >= PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                            && e.errorCode < PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED;
                    if (decoder && box.hw && !box.refused) {      // the chip has no room for it: in software, then
                        box.refused = true;
                        stop(box);
                        start(box);
                        return;
                    }
                    if (!decoder && tryAgain(box)) return;
                    fail(box, decoder ? Ui.tr("A TV não toca mais um ao mesmo tempo", "The TV can't play another one at once")
                            : http > 0 ? Ui.tr("Não deu para tocar este canal", "Couldn't play this channel") + " (HTTP " + http + ")"
                            : Ui.tr("O canal não responde", "No answer from the channel"));
                    box.noRoom = decoder;
                }
            });
        }
        Xtream.Channel src = LiveMerge.pick(this, b.channel, false, Collections.emptySet());
        if (!b.hw && b.channel.sources != null) {      // in software: the lightest version (less to decode)
            for (Xtream.Channel s : b.channel.sources) if (src == null || LiveMerge.need(s) < LiveMerge.need(src)) src = s;
        }
        if (b.version != null) src = b.version;
        if (src == null) src = b.channel;
        b.source = src;
        b.problem = "";
        b.retryAt = 0;
        b.movedAt = 0;
        b.status = "…";
        String url = Xtream.streamUrl(this, src, b.format);
        b.player.setMediaItem(new MediaItem.Builder().setUri(url).setMimeType(Xtream.mime(this, url, b.format)).build());
        b.player.prepare();
        b.player.play();
        sound();
    }

    /** The box's channel dropped: again after a moment, a few times (counted anew once it has played for a while). */
    private boolean tryAgain(Box b) {
        long up = android.os.SystemClock.uptimeMillis();
        if (b.readyAt > 0 && up - b.readyAt > 30_000) b.again = 0;
        if (b.again >= LiveMultiRules.TRIES || b.channel == null) return false;
        b.again++;
        b.status = "";
        stop(b);        // its stream goes first: the provider counts it a moment longer
        final long at = up + 3000L * b.again;
        b.retryAt = at;
        grid.invalidate();
        main.postDelayed(() -> {
            if (b.retryAt != at || b.channel == null || boxes.indexOf(b) >= layout) return;
            b.retryAt = 0;
            start(b);
        }, 3000L * b.again);
        return true;
    }

    private void stop(Box b) {
        b.readyAt = 0;
        b.movedAt = 0;
        if (b.player == null) return;
        b.player.release();
        b.player = null;
    }

    /** The sound from the focused box only; the others without any (their audio tracks off). */
    private void sound() {
        for (int i = 0; i < boxes.size(); i++) {
            ExoPlayer p = boxes.get(i).player;
            if (p == null) continue;
            boolean on = soundOn != null && soundOn.channel != null ? boxes.get(i) == soundOn : i == focus;
            p.setTrackSelectionParameters(p.getTrackSelectionParameters().buildUpon()
                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, !on).build());
            p.setVolume(on ? 1f : 0f);
        }
    }

    /** What's on now on `ch` (the box's line): the full guide, else the provider's short one. */
    private void loadOnNow(Box b, Xtream.Channel ch) {
        final Context app = getApplicationContext();
        io.execute(() -> {
            long now = System.currentTimeMillis();
            Xtream.Program found = null;
            try {
                List<Xtream.Program> list = ch.epg == null || ch.epg.isEmpty() ? null : XmltvStore.programs(app, ch.epg, now - 6 * 3600_000L, now + 6 * 3600_000L);
                if (list == null || list.isEmpty()) list = Xtream.programs(app, ch.id, 2);
                for (Xtream.Program p : list) if (p.start <= now && p.end > now) found = p;
            } catch (Exception ignored) {
            }
            final Xtream.Program p = found;
            main.post(() -> {
                if (b.channel == ch) {
                    b.onNow = p;
                    readScores();
                    grid.invalidate();
                }
            });
        });
    }

    // MARK: - Keys

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (lineup == null && code != KeyEvent.KEYCODE_BACK) return true;
        switch (code) {
            case KeyEvent.KEYCODE_BACK:
                if (!down) {
                    if (LiveOptions.multiOkFull(this)) finish();      // the channel it was opened from
                    else leave(focus);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:     // OK picks the channel; held, the box's options
                if (down) {
                    if (e.getRepeatCount() == 0) {
                        okDown = true;
                        okHeld = false;
                    } else if (okDown && !okHeld) {
                        okHeld = true;
                        options();
                    }
                } else {
                    boolean act = okDown && !okHeld && !e.isCanceled();
                    okDown = okHeld = false;
                    if (act) ok();
                }
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (down && e.getRepeatCount() == 0) options();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down && !onButtons(code)) move(code);
                return true;
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                if (down) zap(code == KeyEvent.KEYCODE_CHANNEL_UP ? 1 : -1);
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    /** OK: a stopped box's button, an empty box's channel, else full screen (or the channel picked, as set). */
    private void ok() {
        Box b = boxes.get(focus);
        if (!b.problem.isEmpty()) {
            List<Object[]> all = buttons(b);
            ((Runnable) all.get(Math.min(b.button, all.size() - 1))[1]).run();
        } else if (b.channel == null || !LiveOptions.multiOkFull(this)) pick(focus);
        else leave(focus);
    }

    /** ◀ ▶ among a stopped box's buttons (past the last: the next box). */
    private boolean onButtons(int code) {
        Box b = boxes.get(focus);
        if (b.problem.isEmpty() || code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
        List<Object[]> all = buttons(b);
        int to = LiveMultiRules.button(Math.min(b.button, all.size() - 1), all.size(), code == KeyEvent.KEYCODE_DPAD_RIGHT);
        if (to < 0) return false;
        b.button = to;
        LiveSpeech.say(grid, (String) all.get(to)[0]);
        grid.invalidate();
        return true;
    }

    /** The focused box gets the hardware decoder (its player again), the one that had it goes to software. */
    private final Runnable rebalance = () -> {
        if (isFinishing() || focus >= boxes.size()) return;
        Box b = boxes.get(focus);
        if (b.channel == null || b.player != null && b.hw || !b.problem.isEmpty() || b.retryAt > 0) return;
        stop(b);
        start(b);
    };

    /** The nearest box that way (by their centers). */
    private void move(int code) {
        RectF from = new RectF(), to = new RectF();
        place(layout, focus, from);
        int best = -1;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < layout; i++) {
            if (i == focus) continue;
            place(layout, i, to);
            float dx = to.centerX() - from.centerX(), dy = to.centerY() - from.centerY();
            boolean ok = code == KeyEvent.KEYCODE_DPAD_LEFT ? dx < -1 : code == KeyEvent.KEYCODE_DPAD_RIGHT ? dx > 1
                    : code == KeyEvent.KEYCODE_DPAD_UP ? dy < -1 : dy > 1;
            if (!ok) continue;
            float d = Math.abs(dx) + Math.abs(dy);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        if (best < 0) return;
        focus = best;
        sound();
        main.removeCallbacks(rebalance);
        main.postDelayed(rebalance, 900);      // once the focus rests: the hardware decoder comes to it
        Box b = boxes.get(focus);
        LiveSpeech.say(grid, b.channel == null ? Ui.tr("Adicionar canal", "Add channel")
                : LiveSpeech.join(b.channel.num > 0 ? String.valueOf(b.channel.num) : "", LiveCountry.named(this, b.channel)));
        grid.invalidate();
    }

    /** The next (or previous) channel of the focused box's category. */
    private void zap(int dir) {
        Box b = boxes.get(focus);
        if (b.channel == null) return;
        List<Xtream.Channel> list = lineup.all();
        for (Xtream.Category cg : lineup.categories) {
            if (cg.id.equals(b.channel.category) && cg.channels.contains(b.channel)) list = cg.channels;
        }
        int at = list.indexOf(b.channel);
        if (at < 0 || list.isEmpty()) return;
        setChannel(focus, list.get((at + dir + list.size()) % list.size()));
    }

    /** Leaves Multiview, box `i`'s channel full screen in Live TV. */
    private void leave(int i) {
        Xtream.Channel ch = i < boxes.size() ? boxes.get(i).channel : null;
        if (ch != null) setResult(RESULT_OK, new Intent().putExtra(RESULT_CHANNEL, ch.id));
        finish();
    }

    // MARK: - Picking channels

    /** Box `i`'s channel: a category first (Favorites, History, then the provider's), then its channel, or a search. */
    private void pick(int i) {
        if (using(i) != null) {
            grid.say(Ui.tr("Em uso por uma gravação: ", "In use by a recording: ") + LiveStreams.name(using(i)));
            return;
        }
        List<String> names = new ArrayList<>();
        final List<List<Xtream.Channel>> lists = new ArrayList<>();
        names.add(Ui.tr("Buscar", "Search"));
        lists.add(null);
        List<Xtream.Channel> history = new ArrayList<>();
        for (Xtream.Channel r : Xtream.recent(this)) {
            Xtream.Channel ch = lineup.find(r.id);
            if (ch != null && !lineup.away.contains(ch.id) && !history.contains(ch)) history.add(ch);
        }
        for (int c = 0; c < lineup.categories.size(); c++) {
            Xtream.Category cg = lineup.categories.get(c);
            if (cg.channels.isEmpty()) continue;
            if (c == 1 && !history.isEmpty()) {
                names.add(Ui.tr("Histórico", "History"));
                lists.add(history);
            }
            if (c >= 2 && LiveOptions.hidden(this).contains(cg.id)) continue;
            names.add(cg.name + "  ·  " + cg.channels.size());
            lists.add(cg.channels);
        }
        LiveSheet.menu(this)
                .setTitle(Ui.tr("Canal para este quadro", "Channel for this box"))
                .setItems(names.toArray(new String[0]), (d, which) -> {
                    if (lists.get(which) == null) search(i);
                    else pickFrom(i, names.get(which), lists.get(which));
                })
                .show();
    }

    private void pickFrom(int i, String title, List<Xtream.Channel> list) {
        String[] items = new String[list.size()];
        for (int c = 0; c < list.size(); c++) {
            Xtream.Channel ch = list.get(c);
            items[c] = (ch.num > 0 ? ch.num + "   " : "") + LiveCountry.named(this, ch);
        }
        LiveSheet.menu(this)
                .setTitle(title)
                .setItems(items, (d, which) -> setChannel(i, list.get(which)))
                .show();
    }

    private void search(int i) {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setSingleLine(true);
        input.setHint(Ui.tr("Nome ou número do canal", "Channel name or number"));
        LiveTextEntry.show(this, Ui.tr("Buscar", "Search"), null, input, typed -> {
            String q = Xtream.fold(typed);
            if (q.isEmpty()) return;
            List<Xtream.Channel> found = new ArrayList<>();
            for (Xtream.Channel ch : lineup.all()) {
                if (ch.key.contains(q) || String.valueOf(ch.num).equals(q)) found.add(ch);
                if (found.size() >= 300) break;
            }
            if (found.isEmpty()) grid.say(Ui.tr("Nenhum canal com esse nome.", "No channels match."));
            else pickFrom(i, typed, found);
        });
    }

    /** The focused box's options: full screen, its channel, removing it, the layout. */
    private void options() {
        final Box b = boxes.get(focus);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (b.channel != null) {
            labels.add(Ui.tr("Tela cheia", "Full screen"));
            actions.add(() -> leave(focus));
            labels.add(Ui.tr("Trocar o canal", "Change the channel"));
            actions.add(() -> pick(focus));
            labels.add(Ui.tr("Reconectar", "Reconnect"));
            actions.add(() -> reconnect(b));
            labels.add(soundOn == b ? Ui.tr("Som com o foco", "Sound follows the focus") : Ui.tr("Manter o som aqui", "Keep sound here"));
            actions.add(() -> {
                soundOn = soundOn == b ? null : b;
                sound();
                grid.invalidate();
            });
            if (focus > 0 && (layout == 3 || pip)) {
                labels.add(Ui.tr("Tornar o grande", "Make it the big one"));
                actions.add(() -> swap(focus, 0));
            }
            if (filled() > 1) {
                labels.add(Ui.tr("Trocar com…", "Swap with…"));
                actions.add(this::swapWith);
            }
            labels.add(Ui.tr("Tirar deste quadro", "Remove from this box"));
            actions.add(() -> {
                stop(b);
                clear(b);
                b.channel = null;
                b.status = b.score = "";
                if (soundOn == b) soundOn = null;
                sound();
                grid.invalidate();
            });
        } else {
            labels.add(Ui.tr("Adicionar canal", "Add channel"));
            actions.add(() -> pick(focus));
        }
        labels.add(Ui.tr("Disposição", "Layout"));
        actions.add(this::layouts);
        LiveSheet.menu(this)
                .setTitle(b.channel == null ? Ui.tr("Multivisão", "Multiview") : b.channel.name)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    /** Two boxes change places. */
    private void swap(int a, int b) {
        Collections.swap(boxes, a, b);
        focus = b;
        relayout();
        sound();
    }

    private void swapWith() {
        final int from = focus;
        List<String> names = new ArrayList<>();
        final List<Integer> at = new ArrayList<>();
        for (int i = 0; i < layout; i++) {
            if (i == from || boxes.get(i).channel == null) continue;
            names.add(boxes.get(i).channel.name);
            at.add(i);
        }
        if (at.size() == 1) {
            swap(from, at.get(0));
            return;
        }
        LiveSheet.menu(this).setTitle(Ui.tr("Trocar com…", "Swap with…"))
                .setItems(names.toArray(new String[0]), (d, which) -> swap(from, at.get(which))).show();
    }

    /** How the boxes stand, the saved views and the wall of matches. */
    private void layouts() {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (allowed >= 2 && !pip) {
            labels.add(Ui.tr("Picture-in-picture", "Picture in picture"));
            actions.add(() -> {
                pip = true;
                layout = 2;
                relayout();
            });
        }
        labels.add(Ui.tr("Jogos ao vivo dos meus times", "My teams' live matches"));
        actions.add(this::wall);
        if (filled() > 0) {
            labels.add(Ui.tr("Salvar esta visão", "Save this view"));
            actions.add(this::saveView);
        }
        final List<LiveMultiRules.View> saved = LiveMultiRules.parse(LiveOptions.multiViews(this));
        if (!saved.isEmpty()) {
            labels.add(Ui.tr("Visões salvas", "Saved views"));
            actions.add(() -> views(saved));
        }
        for (int n = 2; n <= MAX_BOXES; n++) {
            final int count = n;
            if (n == layout && !pip) continue;
            labels.add(n == 2 ? Ui.tr("2 lado a lado", "2 side by side") : n == 3 ? Ui.tr("3: um grande, dois pequenos", "3: one large, two small")
                    : Ui.tr("4 em grade", "4 in a grid"));
            actions.add(() -> {
                if (count > allowed) {
                    grid.say(Ui.tr("Seu provedor permite ", "Your provider allows ") + allowed
                            + Ui.tr(" canais ao mesmo tempo.", " streams at a time."));
                    return;
                }
                pip = false;
                layout = count;
                relayout();
            });
        }
        LiveSheet.menu(this)
                .setTitle(Ui.tr("Disposição", "Layout"))
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    private int filled() {
        int n = 0;
        for (int i = 0; i < layout; i++) if (boxes.get(i).channel != null) n++;
        return n;
    }

    /** The channels on now, in order, as a view worth keeping. */
    private void saveView() {
        LiveMultiRules.View v = new LiveMultiRules.View();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < layout; i++) {
            Xtream.Channel ch = boxes.get(i).channel;
            if (ch == null) continue;
            v.channels.add(ch.id);
            names.add(ch.name);
        }
        v.pip = pip;
        v.name = LiveMultiRules.name(names);
        LiveOptions.setMultiViews(this, LiveMultiRules.write(LiveMultiRules.add(LiveMultiRules.parse(LiveOptions.multiViews(this)), v)));
        grid.say(Ui.tr("Visão salva", "View saved"));
    }

    private void views(List<LiveMultiRules.View> saved) {
        String[] names = new String[saved.size()];
        for (int i = 0; i < names.length; i++) names[i] = saved.get(i).name;
        LiveSheet.menu(this)
                .setTitle(Ui.tr("Visões salvas", "Saved views"))
                .setItems(names, (d, which) -> viewMenu(saved, which))
                .show();
    }

    private void viewMenu(List<LiveMultiRules.View> saved, int at) {
        LiveMultiRules.View v = saved.get(at);
        LiveSheet.menu(this)
                .setTitle(v.name)
                .setItems(new String[] {Ui.tr("Abrir", "Open"), Ui.tr("Apagar", "Delete")}, (d, which) -> {
                    if (which == 0) {
                        open(v);
                    } else {
                        LiveOptions.setMultiViews(this, LiveMultiRules.write(LiveMultiRules.remove(saved, at)));
                        grid.say(Ui.tr("Visão apagada", "View deleted"));
                    }
                })
                .show();
    }

    private void open(LiveMultiRules.View v) {
        List<Xtream.Channel> list = new ArrayList<>();
        for (int id : v.channels) {
            Xtream.Channel ch = lineup.find(id);
            if (ch != null) list.add(ch);
        }
        if (list.isEmpty()) {
            grid.say(Ui.tr("Esses canais não estão mais na lista.", "Those channels aren't in your list any more."));
            return;
        }
        fill(list, v.pip && list.size() == 2);
    }

    /** `list` in the boxes, as many as the account allows. */
    private void fill(List<Xtream.Channel> list, boolean asPip) {
        int count = Math.min(list.size(), allowed);
        for (Box b : boxes) {
            stop(b);
            clear(b);
            b.channel = null;
            b.status = b.score = "";
        }
        soundOn = null;
        pip = asPip;
        layout = Math.max(1, count);
        focus = 0;
        relayout();
        for (int i = 0; i < count; i++) setChannel(i, list.get(i));
        sound();
    }

    /** The boxes filled with the matches of the teams you follow that are on now. */
    private void wall() {
        final Context app = getApplicationContext();
        grid.say(Ui.tr("Procurando jogos…", "Looking for matches…"));
        io.execute(() -> {
            long now = System.currentTimeMillis();
            List<Integer> on = new ArrayList<>();
            for (Object[] o : LiveHome.airings(app, LiveFollow.all(app), now, 60_000, 12)) {
                if (((Xtream.Program) o[1]).start <= now) on.add(((Xtream.Channel) o[0]).id);
            }
            main.post(() -> {
                if (isFinishing()) return;
                List<Integer> ids = LiveMultiRules.wall(on, allowed);
                List<Xtream.Channel> list = new ArrayList<>();
                for (int id : ids) {
                    Xtream.Channel ch = lineup.find(id);
                    if (ch != null && (unlocked || !locked(ch))) list.add(ch);
                }
                if (list.isEmpty()) {
                    grid.say(Ui.tr("Nenhum dos seus times está jogando agora.", "None of your teams are playing now."));
                    return;
                }
                fill(list, false);
                if (on.size() > list.size()) grid.say(Ui.tr("Mais jogos no ar do que seu provedor permite.", "More matches on than your provider allows."));
            });
        });
    }

    // MARK: - Drawing: the frames, the labels, the focus

    private final class Grid extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), frame = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF(), logo = new RectF();
        private Paint name, meta, big, hint;
        private float k;
        private String message = "";
        private Paint pill, shade;
        private long messageAt;

        Grid(Context c) {
            super(c);
            setFocusable(true);
            frame.setStyle(Paint.Style.STROKE);
        }

        void say(String text) {
            message = text;
            messageAt = SystemClock.uptimeMillis();
            LiveSpeech.say(this, text);
            invalidate();
            postDelayed(this::invalidate, 3200);
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            k = w / 1920f;
            name = Ui.text(Ui.semibold, 27 * k, 0xFFFFFFFF);
            meta = Ui.text(Ui.regular, 19.5f * k, 0xFFD0D0D4);
            big = Ui.text(Ui.medium, 27 * k, 0xCCFFFFFF);
            hint = Ui.text(Ui.medium, 21 * k, 0xFFD0D0D4);
            pill = Ui.text(Ui.semibold, 18 * k, LiveTheme.ink);
            shade = new android.graphics.Paint();
            frame.setStrokeWidth(4.5f * k);
            relayout();
        }

        @Override protected void onDraw(Canvas c) {
            if (name == null) return;
            if (lineup == null) {
                c.drawText(Ui.tr("Carregando…", "Loading…"), 80 * k, 120 * k, big);
                return;
            }
            for (int i = 0; i < layout; i++) {
                Box b = boxes.get(i);
                place(layout, i, r);
                r.set(r.left * k, r.top * k, r.right * k, r.bottom * k);
                if (b.channel == null) {        // an empty box: + Add channel
                    fill.setColor(LiveTheme.cellLater);
                    c.drawRoundRect(r, 18 * k * LiveTheme.rounding(), 18 * k * LiveTheme.rounding(), fill);
                    LiveRecordings.Rec rec = using(i);
                    String t = rec != null ? Ui.tr("Em uso por uma gravação", "In use by a recording") : "+  " + Ui.tr("Adicionar canal", "Add channel");
                    c.drawText(t, r.centerX() - big.measureText(t) / 2, r.centerY() + Ui.halfCap(big) - (rec == null ? 0 : 18 * k), big);
                    if (rec != null) {
                        String by = ellipsize(LiveStreams.name(rec) + Ui.tr(" até ", " until ") + Ui.time(rec.end), meta, r.width() - 48 * k);
                        c.drawText(by, r.centerX() - meta.measureText(by) / 2, r.centerY() + 34 * k, meta);
                    }
                } else if (!b.problem.isEmpty()) {
                    drawStopped(c, b, i == focus);
                } else {
                    if (b.retryAt > 0) {        // dropped: when it tries again
                        fill.setColor(0xFF16161A);
                        c.drawRoundRect(r, 18 * k * LiveTheme.rounding(), 18 * k * LiveTheme.rounding(), fill);
                        String t = Ui.tr("Reconectando em ", "Reconnecting in ") + LiveMultiRules.seconds(b.retryAt, SystemClock.uptimeMillis())
                                + Ui.tr(" s  ·  tentativa ", " s  ·  try ") + b.again + Ui.tr(" de ", " of ") + LiveMultiRules.TRIES;
                        c.drawText(t, r.centerX() - name.measureText(t) / 2, r.centerY() + Ui.halfCap(name), name);
                        postInvalidateDelayed(500);
                    }
                    // At the bottom, shaded: what's on now (or the box's state), then the channel.
                    float lh = 165 * k;
                    shade.setShader(shadeFor(r.bottom - lh, r.bottom));
                    c.drawRect(r.left, r.bottom - lh, r.right, r.bottom, shade);
                    float x = r.left + 24 * k, room = r.width() - 48 * k;
                    String line2 = (b.channel.num > 0 ? b.channel.num + "  " : "") + LiveCountry.named(Multiview.this, b.channel);
                    String line1 = !b.status.isEmpty() && !b.status.equals("…") ? b.status
                            : b.onNow == null ? b.channel.name : b.onNow.title;
                    c.drawText(ellipsize(line1, name, room), x, r.bottom - 48 * k, name);
                    c.drawText(ellipsize(line2, meta, room), x, r.bottom - 20 * k, meta);
                    if (!b.score.isEmpty()) {
                        float pw = pill.measureText(b.score) + 30 * k;
                        logo.set(r.right - 21 * k - pw, r.top + 21 * k, r.right - 21 * k, r.top + 63 * k);
                        fill.setColor(0xE63A3A40);
                        c.drawRoundRect(logo, 12 * k, 12 * k, fill);
                        int was = pill.getColor();
                        pill.setColor(0xFFF5F5F7);
                        c.drawText(b.score, logo.left + 15 * k, logo.centerY() + Ui.halfCap(pill), pill);
                        pill.setColor(was);
                    }
                    boolean heard = soundOn != null && soundOn.channel != null ? b == soundOn : i == focus;
                    if (heard && (soundOn != null || !(pip && i == 1))) {       // the box whose sound is playing
                        String on = Ui.tr("Com som", "Sound on");
                        float pw = pill.measureText(on) + 30 * k;
                        logo.set(r.left + 21 * k, r.top + 21 * k, r.left + 21 * k + pw, r.top + 21 * k + 42 * k);
                        fill.setColor(LiveTheme.tile);
                        c.drawRoundRect(logo, 12 * k, 12 * k, fill);
                        c.drawText(on, logo.left + 15 * k, logo.centerY() + Ui.halfCap(pill), pill);
                    }
                    if (b.status.equals("…")) Ui.spinner(c, r.centerX(), r.centerY(), 24 * k,
                            SystemClock.uptimeMillis() / 1000f * 1.3f, frame);
                }
                if (i == focus) {       // the focus: a frame in the theme's color around it
                    frame.setColor(LiveTheme.tile);
                    r.inset(-2 * k, -2 * k);
                    c.drawRoundRect(r, 18 * k * LiveTheme.rounding(), 18 * k * LiveTheme.rounding(), frame);
                }
            }
            String help = LiveOptions.multiOkFull(Multiview.this)
                    ? Ui.tr("OK: tela cheia · segure OK: opções · Voltar: fechar", "OK: full screen · hold OK: options · Back: close")
                    : Ui.tr("OK escolhe o canal · segure OK: opções · Voltar: tela cheia",
                    "OK picks the channel · hold OK: options · Back: full screen");
            // At the foot: moving, and what OK does (only where there's room under the boxes).
            if (layout >= 2 && !pip) {
                String moveHelp = Ui.tr("Mova para escolher  ·  O canal selecionado toca o som", "Move to choose  ·  The selected channel plays its sound");
                float hy = 1040 * k;
                float inset = layout == 4 ? 143 : 24;       // in line with the boxes
                int playing = 0, max = streams > 0 ? streams : allowed;
                for (Box b : boxes) if (b.player != null) playing++;
                String used = LiveStreams.inUse(Math.min(max, playing + reserved.size()), max) + "  ·  ";
                int was = hint.getColor();
                if (playing + reserved.size() >= max) hint.setColor(0xFFFFB340);
                c.drawText(used, inset * k, hy, hint);
                hint.setColor(was);
                c.drawText(moveHelp, inset * k + hint.measureText(used), hy, hint);
                c.drawText(help, (1920 - inset) * k - hint.measureText(help), hy, hint);
            }
            if (!message.isEmpty() && SystemClock.uptimeMillis() - messageAt < 3000) {
                float tw = big.measureText(message);
                r.set(960 * k - tw / 2 - 30 * k, 520 * k, 960 * k + tw / 2 + 30 * k, 590 * k);
                fill.setColor(0xF21C1C1F);
                c.drawRoundRect(r, 34 * k, 34 * k, fill);
                c.drawText(message, 960 * k - tw / 2, r.centerY() + Ui.halfCap(big), big);
            }
            boolean spinning = false;
            for (int i = 0; i < layout; i++) spinning |= boxes.get(i).channel != null && boxes.get(i).status.equals("…");
            if (spinning) postInvalidateOnAnimation();
        }

        /** A box that stopped: its channel, why, and its buttons (the focused box's lit). */
        private void drawStopped(Canvas c, Box b, boolean focused) {
            float rr = 18 * k * LiveTheme.rounding(), cx = r.centerX(), cy = r.centerY(), room = r.width() - 48 * k;
            fill.setColor(0xFF16161A);
            c.drawRoundRect(r, rr, rr, fill);
            String t = ellipsize((b.onNow == null ? LiveCountry.named(Multiview.this, b.channel) : b.onNow.title) + Ui.tr(" parou", " stopped"), big, room);
            c.drawText(t, cx - big.measureText(t) / 2, cy - 64 * k, big);
            String why = ellipsize(b.problem, meta, room);
            c.drawText(why, cx - meta.measureText(why) / 2, cy - 20 * k, meta);
            List<Object[]> all = buttons(b);
            int n = all.size(), at = Math.min(b.button, n - 1), first = 0;
            float total = 0;
            for (Object[] o : all) total += pill.measureText((String) o[0]) + 60 * k;
            if (total - 12 * k > room) {        // a small box: the one in focus alone
                first = at;
                n = at + 1;
                total = pill.measureText((String) all.get(at)[0]) + 60 * k;
            }
            float x = cx - (total - 12 * k) / 2;
            for (int i = first; i < n; i++) {
                String s = (String) all.get(i)[0];
                float bw = pill.measureText(s) + 48 * k;
                boolean on = focused && i == at;
                logo.set(x, cy + 18 * k, x + bw, cy + 74 * k);
                fill.setColor(on ? LiveTheme.tile : 0xFF3A3A40);
                c.drawRoundRect(logo, 28 * k, 28 * k, fill);
                int was = pill.getColor();
                pill.setColor(on ? LiveTheme.ink : 0xFFF5F5F7);
                c.drawText(s, x + 24 * k, logo.centerY() + Ui.halfCap(pill), pill);
                pill.setColor(was);
                x += bw + 12 * k;
            }
        }

        private String ellipsize(String s, Paint p, float w) {
            android.text.TextPaint tp = new android.text.TextPaint(p);
            return TextUtils.ellipsize(s, tp, Math.max(1, w), TextUtils.TruncateAt.END).toString();
        }
    }

    /** The boxes' bottom shade, made once per place (a new gradient for every box on every frame added up). */
    private final java.util.HashMap<Long, android.graphics.Shader> shades = new java.util.HashMap<>();

    private android.graphics.Shader shadeFor(float top, float bottom) {
        long key = ((long) Float.floatToIntBits(top) << 32) | (Float.floatToIntBits(bottom) & 0xFFFFFFFFL);
        android.graphics.Shader sh = shades.get(key);
        if (sh == null) {
            if (shades.size() > 32) shades.clear();
            sh = new android.graphics.LinearGradient(0, top, 0, bottom, 0x000B0B0D, 0xE00B0B0D, android.graphics.Shader.TileMode.CLAMP);
            shades.put(key, sh);
        }
        return sh;
    }
}
