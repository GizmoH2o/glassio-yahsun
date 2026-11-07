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
 * frame. OK picks a box's channel, the channel buttons change it, OK held (or Menu) offers full screen, removing it, the
 * layout, saved views and a wall of your teams' live matches; Back leaves, the focused channel full screen in Live TV.
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
        /** Its player decodes on the video chip; else in software. */
        boolean hw;
        /** The chip refused this box: software from then on. */
        boolean refused;
        String format;
        Xtream.Program onNow;
    }

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
        unlocked = getIntent().getBooleanExtra(EXTRA_UNLOCKED, false);
        final int first = getIntent().getIntExtra(EXTRA_CHANNEL, -1);
        for (int i = 0; i < MAX_BOXES; i++) boxes.add(new Box());
        final Context app = getApplicationContext();
        io.execute(() -> {
            Xtream.Lineup l = Xtream.saved(app);
            int max = Xtream.maxConnections(app);
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
                Xtream.Channel ch = l == null ? null : l.find(first);
                if (ch != null) setChannel(0, ch);
                int second = getIntent().getIntExtra(EXTRA_SECOND, -1);     // the test broadcast's second box, by number
                if (second >= 0 && l != null) for (Xtream.Channel c : l.all()) if (c.num == second) setChannel(1, c);
                relayout();
            });
        });
    }

    @Override protected void onStop() {
        for (Box b : boxes) stop(b);
        super.onStop();
    }

    @Override protected void onRestart() {
        super.onRestart();
        for (int i = 0; i < layout; i++) if (boxes.get(i).channel != null) start(boxes.get(i));
    }

    @Override protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
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
        if (!unlocked && locked(ch)) {
            grid.say(Ui.tr("Este canal pede o PIN: abra-o na TV ao vivo.", "This channel asks for the PIN: open it in Live TV."));
            return;
        }
        stop(b);
        b.channel = ch;
        b.tried = false;
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
                    else if (s == Player.STATE_READY) box.status = "";      // (an error's message stays)
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
                        box.status = Ui.tr("O limite de canais do provedor foi atingido", "Your provider's stream limit is reached");
                        grid.invalidate();
                        return;
                    }
                    if (!box.tried) {       // the other format, once (as Live TV does)
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
                    box.status = decoder ? Ui.tr("A TV não toca mais um ao mesmo tempo", "The TV can't play another one at once")
                            : Ui.tr("Não deu para tocar este canal", "Couldn't play this channel");
                    grid.invalidate();
                }
            });
        }
        Xtream.Channel src = LiveMerge.pick(this, b.channel, false, Collections.emptySet());
        if (!b.hw && b.channel.sources != null) {      // in software: the lightest version (less to decode)
            for (Xtream.Channel s : b.channel.sources) if (src == null || LiveMerge.need(s) < LiveMerge.need(src)) src = s;
        }
        if (src == null) src = b.channel;
        b.status = "…";
        String url = Xtream.streamUrl(this, src, b.format);
        b.player.setMediaItem(new MediaItem.Builder().setUri(url).setMimeType(Xtream.mime(this, url, b.format)).build());
        b.player.prepare();
        b.player.play();
        sound();
    }

    private void stop(Box b) {
        if (b.player == null) return;
        b.player.release();
        b.player = null;
    }

    /** The sound from the focused box only; the others without any (their audio tracks off). */
    private void sound() {
        for (int i = 0; i < boxes.size(); i++) {
            ExoPlayer p = boxes.get(i).player;
            if (p == null) continue;
            boolean on = i == focus;
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
                if (!down) leave(focus);
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
                    if (act) pick(focus);
                }
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (down && e.getRepeatCount() == 0) options();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) move(code);
                return true;
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                if (down) zap(code == KeyEvent.KEYCODE_CHANNEL_UP ? 1 : -1);
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    /** The focused box gets the hardware decoder (its player again), the one that had it goes to software. */
    private final Runnable rebalance = () -> {
        if (isFinishing() || focus >= boxes.size()) return;
        Box b = boxes.get(focus);
        if (b.channel == null || b.player != null && b.hw) return;
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
        grid.announceForAccessibility(b.channel == null ? Ui.tr("Adicionar canal", "Add channel") : b.channel.name);
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
        List<String> names = new ArrayList<>();
        final List<List<Xtream.Channel>> lists = new ArrayList<>();
        names.add(Ui.tr("Buscar", "Search"));
        lists.add(null);
        List<Xtream.Channel> history = new ArrayList<>();
        for (Xtream.Channel r : Xtream.recent(this)) {
            Xtream.Channel ch = lineup.find(r.id);
            if (ch != null && !history.contains(ch)) history.add(ch);
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
            items[c] = (ch.num > 0 ? ch.num + "   " : "") + ch.name;
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
            labels.add(Ui.tr("Tirar deste quadro", "Remove from this box"));
            actions.add(() -> {
                stop(b);
                b.channel = null;
                b.status = "";
                grid.invalidate();
            });
        } else {
            labels.add(Ui.tr("Adicionar canal", "Add channel"));
            actions.add(() -> pick(focus));
        }
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
                .setTitle(b.channel == null ? Ui.tr("Multivisão", "Multiview") : b.channel.name)
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
            b.channel = null;
            b.status = "";
        }
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
            announceForAccessibility(text);
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
                    String t = "+  " + Ui.tr("Adicionar canal", "Add channel");
                    c.drawText(t, r.centerX() - big.measureText(t) / 2, r.centerY() + Ui.halfCap(big), big);
                } else {
                    // At the bottom, shaded: what's on now (or the box's state), then the channel.
                    float lh = 165 * k;
                    shade.setShader(new android.graphics.LinearGradient(0, r.bottom - lh, 0, r.bottom, 0x000B0B0D, 0xE00B0B0D, android.graphics.Shader.TileMode.CLAMP));
                    c.drawRect(r.left, r.bottom - lh, r.right, r.bottom, shade);
                    float x = r.left + 24 * k, room = r.width() - 48 * k;
                    String line2 = (b.channel.num > 0 ? b.channel.num + "  " : "") + b.channel.name;
                    String line1 = !b.status.isEmpty() && !b.status.equals("…") ? b.status
                            : b.onNow == null ? b.channel.name : b.onNow.title;
                    c.drawText(ellipsize(line1, name, room), x, r.bottom - 48 * k, name);
                    c.drawText(ellipsize(line2, meta, room), x, r.bottom - 20 * k, meta);
                    if (i == focus && !(pip && i == 1)) {       // the box whose sound is playing
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
            String help = Ui.tr("OK escolhe o canal · segure OK: opções · Voltar: tela cheia",
                    "OK picks the channel · hold OK: options · Back: full screen");
            // At the foot: moving, and what OK does (only where there's room under the boxes).
            if (layout >= 2 && !pip) {
                String moveHelp = Ui.tr("Mova para escolher  ·  O canal selecionado toca o som", "Move to choose  ·  The selected channel plays its sound");
                float hy = 1040 * k;
                float inset = layout == 4 ? 143 : 24;       // in line with the boxes
                c.drawText(moveHelp, inset * k, hy, hint);
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

        private String ellipsize(String s, Paint p, float w) {
            android.text.TextPaint tp = new android.text.TextPaint(p);
            return TextUtils.ellipsize(s, tp, Math.max(1, w), TextUtils.TruncateAt.END).toString();
        }
    }
}
