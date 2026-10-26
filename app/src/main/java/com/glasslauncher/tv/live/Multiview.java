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
 * two by two). The arrow keys move between the boxes; the focused one has the sound and a white frame. OK picks a
 * box's channel, the channel buttons change it, OK held (or Menu) offers full screen, removing it and the layout;
 * Back leaves, the focused channel full screen in Live TV. Never more boxes than the account may stream at once.
 * Measurements in a 1920×1080 space (k = width / 1920).
 */
public class Multiview extends Activity {
    static final String EXTRA_CHANNEL = "com.glasslauncher.tv.live.extra.MULTI_CHANNEL", EXTRA_UNLOCKED = "com.glasslauncher.tv.live.extra.MULTI_UNLOCKED";
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
    private boolean unlocked, okDown, okHeld;

    /** One box: its channel (null: empty), its player and surface, what it says (loading, an error). */
    private final class Box {
        Xtream.Channel channel;
        ExoPlayer player;
        SurfaceView surface;
        String status = "";
        boolean tried;      // the other format, once
        /** Its player has the TV's one hardware video decoder (the focused box's); else it decodes in software. */
        boolean hw;
        String format;
        Xtream.Program onNow;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LiveTheme.load(this);
        Ui.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B0B0D);
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
                layout = Math.max(1, Math.min(2, allowed));
                Xtream.Channel ch = l == null ? null : l.find(first);
                if (ch != null) setChannel(0, ch);
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
    private static void place(int layout, int i, RectF out) {
        if (layout <= 1) {
            out.set(160, 90, 1760, 990);
        } else if (layout == 2) {
            float w = 924, h = 520, y = 280;
            out.set(24 + i * (w + 24), y, 24 + i * (w + 24) + w, y + h);
        } else if (layout == 3) {
            if (i == 0) out.set(24, 191, 1264, 889);
            else out.set(1288, 191 + (i - 1) * 354, 1896, 191 + (i - 1) * 354 + 344);
        } else {
            float w = 924, h = 520;
            int col = i % 2, row = i / 2;
            out.set(24 + col * (w + 24), 14 + row * (h + 12), 24 + col * (w + 24) + w, 14 + row * (h + 12) + h);
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
            if (b.surface == null) {
                b.surface = new SurfaceView(this);
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
            // The TV runs one hardware video decoder at a time (a second one stalls, the first stutters): the
            // focused box has it, the others decode in software (choppier: they're the ones without the sound).
            b.hw = boxes.indexOf(b) == focus;
            if (b.hw) {
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
        for (int n = 2; n <= MAX_BOXES; n++) {
            final int count = n;
            if (n == layout) continue;
            labels.add(n == 2 ? Ui.tr("2 lado a lado", "2 side by side") : n == 3 ? Ui.tr("3: um grande, dois pequenos", "3: one large, two small")
                    : Ui.tr("4 em grade", "4 in a grid"));
            actions.add(() -> {
                if (count > allowed) {
                    grid.say(Ui.tr("Seu provedor permite ", "Your provider allows ") + allowed
                            + Ui.tr(" canais ao mesmo tempo.", " streams at a time."));
                    return;
                }
                layout = count;
                relayout();
            });
        }
        LiveSheet.menu(this)
                .setTitle(b.channel == null ? Ui.tr("Multivisão", "Multiview") : b.channel.name)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    // MARK: - Drawing: the frames, the labels, the focus

    private final class Grid extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), frame = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF(), logo = new RectF();
        private Paint name, meta, big, hint;
        private float k;
        private String message = "";
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
            name = Ui.text(Ui.semibold, 28 * k, 0xFFF5F5F7);
            meta = Ui.text(Ui.regular, 22 * k, 0xB3FFFFFF);
            big = Ui.text(Ui.medium, 30 * k, 0xCCFFFFFF);
            hint = Ui.text(Ui.regular, 22 * k, 0x99FFFFFF);
            frame.setStrokeWidth(6 * k);
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
                    fill.setColor(0xFF1C1C1F);
                    c.drawRoundRect(r, 14 * k, 14 * k, fill);
                    String t = "+  " + Ui.tr("Adicionar canal", "Add channel");
                    c.drawText(t, r.centerX() - big.measureText(t) / 2, r.centerY() + Ui.halfCap(big), big);
                } else {
                    // The label at the bottom: logo, name, what's on now (or the box's state).
                    float lh = 74 * k;
                    fill.setColor(0xB3000000);
                    c.drawRect(r.left, r.bottom - lh, r.right, r.bottom, fill);
                    Bitmap bm = LiveLogos.get(getContext(), b.channel.logo, this::postInvalidate);
                    float x = r.left + 18 * k;
                    if (bm != null) {
                        float bh = 44 * k, bw = Math.min(90 * k, bm.getWidth() * bh / Math.max(1, bm.getHeight()));
                        logo.set(x, r.bottom - lh / 2 - bh / 2, x + bw, r.bottom - lh / 2 + bh / 2);
                        c.drawBitmap(bm, null, logo, fill);
                        x = logo.right + 16 * k;
                    }
                    String line1 = (b.channel.num > 0 ? b.channel.num + "  " : "") + b.channel.name;
                    String line2 = !b.status.isEmpty() && !b.status.equals("…") ? b.status
                            : b.onNow == null ? "" : b.onNow.title + "  ·  " + Ui.time(b.onNow.start) + " – " + Ui.time(b.onNow.end);
                    float room = r.right - 18 * k - x - (i == focus ? 40 * k : 0);
                    c.drawText(ellipsize(line1, name, room), x, r.bottom - lh + 32 * k, name);
                    c.drawText(ellipsize(line2, meta, room), x, r.bottom - 14 * k, meta);
                    if (i == focus) drawSpeaker(c, r.right - 34 * k, r.bottom - lh / 2);
                    if (b.status.equals("…")) Ui.spinner(c, r.centerX(), r.centerY(), 24 * k,
                            SystemClock.uptimeMillis() / 1000f * 1.3f, frame);
                }
                if (i == focus) {       // the focus: a frame in the theme's color around it
                    frame.setColor(LiveTheme.text);
                    r.inset(-4 * k, -4 * k);
                    c.drawRoundRect(r, 16 * k, 16 * k, frame);
                }
            }
            String help = Ui.tr("OK escolhe o canal · segure OK: opções · Voltar: tela cheia",
                    "OK picks the channel · hold OK: options · Back: full screen");
            if (layout == 2) c.drawText(help, 24 * k, 860 * k, hint);
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

        private void drawSpeaker(Canvas c, float cx, float cy) {
            Symbols.draw(getContext(), c, "speaker", cx, cy, 24 * k, 0xFFF5F5F7);
        }

        private String ellipsize(String s, Paint p, float w) {
            android.text.TextPaint tp = new android.text.TextPaint(p);
            return TextUtils.ellipsize(s, tp, Math.max(1, w), TextUtils.TruncateAt.END).toString();
        }
    }
}
