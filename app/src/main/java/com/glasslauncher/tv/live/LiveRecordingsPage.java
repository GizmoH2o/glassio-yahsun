package com.glasslauncher.tv.live;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Recordings, a page of their own (YouTube TV's library, Apple TV's rows): the ones recording now, those recorded,
 * those set for later (one by one, or every episode), as rows of tiles with each show's picture, its day and time,
 * how long it is and its channel. OK plays one (recording now: from its start), held OK (or Menu) its choices:
 * play, stop recording, delete; on one set for later, cancel it. ← → ↑ ↓ move, Back closes.
 */
final class LiveRecordingsPage extends LiveDialog {
    private final LiveView view;
    private final Live live;
    private final Page page;
    // The rows: recording now, recorded, set for later (a failed one is with the recorded, marked).
    private final List<LiveRecordings.Rec> now = new ArrayList<>(), done = new ArrayList<>(), over = new ArrayList<>();
    private final List<LiveSchedule.Item> later = new ArrayList<>();
    private final List<Integer> rows = new ArrayList<>();       // which rows there are, in order: NOW, DONE, LATER
    private static final int NOW = 0, DONE = 1, LATER = 2, ATTENTION = 3;
    /** What went wrong (recordings), then later ones that won't fit (the schedule's): the first row. */
    private final List<Object> attention = new ArrayList<>();
    private int row, at;
    private final int[] atOf = new int[4];
    private long size, free;
    private boolean okDown, okHeld;

    /** Later recordings that won't fit the account's streams, by key; the streams once the account has said. */
    private final java.util.Set<String> clashing = new java.util.HashSet<>();
    private volatile int streams = 1;

    /** Marks what won't fit: the ones recording now take their streams first. */
    private void findClashes() {
        clashing.clear();
        if (!LiveOptions.recordClashes(getContext())) {
            arrange();
            return;
        }
        List<long[]> times = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (LiveRecordings.Rec r : this.now) times.add(new long[] {now, now + 3600_000L});
        int first = times.size();
        for (LiveSchedule.Item it : later) times.add(new long[] {it.start, it.end});
        for (int i : LiveSchedule.clashes(times, streams)) if (i >= first) clashing.add(later.get(i - first).key());
        arrange();
    }

    /** The rows, with the first one (Needs attention) when there's something for it. */
    private void arrange() {
        boolean on = LiveOptions.attention(getContext());
        attention.clear();
        done.clear();
        for (LiveRecordings.Rec r : over) {
            if (on && LiveRecordings.trouble(r)) attention.add(r); else done.add(r);
        }
        if (on) for (LiveSchedule.Item it : later) if (clashing.contains(it.key())) attention.add(it);
        rows.clear();
        if (!attention.isEmpty()) rows.add(ATTENTION);
        if (!now.isEmpty()) rows.add(NOW);
        if (!done.isEmpty()) rows.add(DONE);
        if (!later.isEmpty()) rows.add(LATER);
        row = Math.max(0, Math.min(row, rows.size() - 1));
        for (int r = 0; r < 4; r++) atOf[r] = Math.max(0, Math.min(atOf[r], count(r) - 1));
        at = rows.isEmpty() ? 0 : Math.max(0, Math.min(at, count(rows.get(row)) - 1));
    }

    LiveRecordingsPage(Context c, LiveView view, Live live) {
        super(c);
        this.view = view;
        this.live = live;
        page = new Page(c);
        reload();
        new Thread(() -> {        // the account's streams: it may ask the server
            int max = Xtream.maxConnections(getContext());
            page.post(() -> {
                streams = Math.max(1, max);
                findClashes();
                page.invalidate();
            });
        }, "recordings-streams").start();
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(page);
        Window w = getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        page.setFocusable(true);
        page.requestFocus();
        LiveSpeech.say(page, Ui.tr("Gravações", "Recordings"));
    }

    /** The recordings and the schedule's recordings, read again (one stopped, deleted, cancelled). */
    private void reload() {
        Context c = getContext();
        now.clear();
        over.clear();
        later.clear();
        size = 0;
        free = LiveRecordings.folder(c).getUsableSpace();
        List<LiveRecordings.Rec> all = LiveRecordings.all(c);
        for (int i = all.size() - 1; i >= 0; i--) {     // the newest first
            LiveRecordings.Rec r = all.get(i);
            size += r.size;
            if (LiveRecordings.RECORDING.equals(r.state)) now.add(r); else over.add(r);
        }
        for (LiveSchedule.Item it : LiveSchedule.all(c)) if (LiveSchedule.RECORD.equals(it.kind)) later.add(it);
        findClashes();
        page.invalidate();
    }

    private int count(int kind) {
        return kind == NOW ? now.size() : kind == DONE ? done.size() : kind == ATTENTION ? attention.size() : later.size();
    }

    private int kind() {
        return rows.isEmpty() ? -1 : rows.get(row);
    }

    // MARK: - Keys

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (code == KeyEvent.KEYCODE_BACK) {
            if (!down && !e.isCanceled()) dismiss();
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            // OK acts when let go; held, the choices.
            if (down) {
                if (e.getRepeatCount() == 0) {
                    okDown = true;
                    okHeld = false;
                } else if (okDown && !okHeld) {
                    okHeld = true;
                    choices();
                }
            } else {
                if (okDown && !okHeld && !e.isCanceled()) pick();
                okDown = okHeld = false;
            }
            return true;
        }
        if (!down) return true;
        switch (code) {
            case KeyEvent.KEYCODE_MENU:
                choices();
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (row > 0) {
                    atOf[kind()] = at;
                    row--;
                    at = atOf[kind()];
                }
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (row < rows.size() - 1) {
                    atOf[kind()] = at;
                    row++;
                    at = atOf[kind()];
                }
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                at = Math.max(0, at - 1);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (kind() >= 0) at = Math.min(count(kind()) - 1, at + 1);
                break;
            case KeyEvent.KEYCODE_HOME:
                return super.dispatchKeyEvent(e);
            default:
                break;
        }
        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            LiveSpeech.say(page, focusSaid());
        }
        page.invalidate();
        return true;
    }

    /** The focused recording (or one set for later), as TalkBack says it. */
    private String focusSaid() {
        LiveRecordings.Rec r = rec();
        if (r != null) return LiveSpeech.join(r.title.isEmpty() ? view.nameWithCountry(r.ch) : r.title, when(r.start, r.end), view.nameWithCountry(r.ch),
                r.state.equals(LiveRecordings.RECORDING) ? Ui.tr("gravando", "recording") : "");
        LiveSchedule.Item it = item();
        if (it != null) {
            return LiveSpeech.join(it.title, when(it.start, it.end), view.nameWithCountry(it.ch), Ui.tr("gravação marcada", "recording set"));
        }
        return "";
    }

    /** OK: a recording plays; one set for later, or with something wrong, its choices. */
    private void pick() {
        int kind = kind();
        if (kind == LATER || kind == ATTENTION) {
            choices();
            return;
        }
        LiveRecordings.Rec r = rec();
        if (r == null) return;
        if (empty(r)) {     // missed, or nothing came: its menu (why, and Delete), nothing to play
            choices();
            return;
        }
        dismiss();
        view.playFromRecordings(r);
    }

    /** Nothing to play: missed (no file), or the file is gone. */
    private static boolean empty(LiveRecordings.Rec r) {
        return r.file.isEmpty() || !new java.io.File(r.file).exists();
    }

    private LiveRecordings.Rec rec() {
        int kind = kind();
        if (kind == ATTENTION) return at < attention.size() && attention.get(at) instanceof LiveRecordings.Rec ? (LiveRecordings.Rec) attention.get(at) : null;
        List<LiveRecordings.Rec> list = kind == NOW ? now : kind == DONE ? done : null;
        return list == null || at >= list.size() ? null : list.get(at);
    }

    /** Held OK: play, stop recording, delete; on one set for later, cancel it. */
    /** The one set for later in focus, or null. */
    private LiveSchedule.Item item() {
        if (kind() == LATER) return at < later.size() ? later.get(at) : null;
        return kind() == ATTENTION && at < attention.size() && attention.get(at) instanceof LiveSchedule.Item ? (LiveSchedule.Item) attention.get(at) : null;
    }

    /** What recording `it` clashes with: those on at the same time, now and later. */
    private List<Object> against(LiveSchedule.Item it) {
        List<Object> o = new ArrayList<>();
        for (LiveRecordings.Rec r : now) if (!r.fetch && r.end > it.start) o.add(r);
        for (LiveSchedule.Item x : later) if (!x.key().equals(it.key()) && x.start < it.end && x.end > it.start) o.add(x);
        return o;
    }

    private static String nameOf(Object o) {
        if (o instanceof LiveRecordings.Rec) return LiveStreams.name((LiveRecordings.Rec) o);
        LiveSchedule.Item it = (LiveSchedule.Item) o;
        return it.title.isEmpty() ? it.ch.name : it.title;
    }

    /** The next showing of `title` in the guides (its own channel's first when they start together), or null. Background thread. */
    private Object[] nextShowing(Xtream.Channel ch, String title) {
        Context c = getContext();
        long from = System.currentTimeMillis() + 60_000;
        String key = Xtream.fold(title);
        if (key.isEmpty()) return null;
        Object[] best = null;
        List<Xtream.Program> own = view.programsOf(ch);
        if (own != null) for (Xtream.Program p : own) {
            if (p.start > from && Xtream.fold(p.title).equals(key) && (best == null || p.start < ((Xtream.Program) best[1]).start)) best = new Object[] {ch, p};
        }
        if (LiveOptions.fullGuide(c)) {
            Xtream.Lineup l = Xtream.saved(c);
            for (XmltvStore.Found f : LiveNames.find(c, LiveNames.of(c, key), from, from + 7 * 24 * 3600_000L, 60)) {
                if (f.epg == null || !Xtream.fold(f.title).equals(key) || best != null && f.start >= ((Xtream.Program) best[1]).start || l == null) continue;
                for (Xtream.Channel x : l.all()) {
                    if (x.epg == null || !x.epg.equalsIgnoreCase(f.epg)) continue;
                    Xtream.Program p = new Xtream.Program();
                    p.title = f.title;
                    p.start = f.start;
                    p.end = f.end;
                    best = new Object[] {x, p};
                    break;
                }
            }
        }
        return best;
    }

    /** What went wrong, and what can be done about it; the next showing is looked for first. */
    private void attend() {
        final LiveRecordings.Rec r = rec();
        final LiveSchedule.Item clash = item();
        if (r == null && clash == null) return;
        final Xtream.Channel ch = r != null ? r.ch : clash.ch;
        final String title = r != null ? r.title : clash.title;
        final Object[] next = {null};
        final boolean[] shown = {false};
        final Runnable show = () -> {
            if (shown[0] || !isShowing()) return;
            shown[0] = true;
            if (r != null) attendTo(r, (Object[]) next[0]); else attendTo(clash, (Object[]) next[0]);
        };
        new Thread(() -> {
            try {
                next[0] = nextShowing(ch, title);
            } catch (RuntimeException e) {
                AppLog.w("recordings: " + e.getMessage());
            }
            page.post(show);
        }, "next-showing").start();
        page.postDelayed(show, 2500);       // a long guide: without it
    }

    private String dayTime(long t) {
        boolean near = t - System.currentTimeMillis() < 20 * 3600_000L;
        return (near ? "" : new java.text.SimpleDateFormat("EEE ", Ui.locale()).format(new java.util.Date(t))) + Ui.time(t);
    }

    private void addNext(Object[] next, List<String> labels, List<String> icons, List<Runnable> acts) {
        if (next == null) return;
        final Xtream.Channel ch = (Xtream.Channel) next[0];
        final Xtream.Program p = (Xtream.Program) next[1];
        final Context c = getContext();
        if (LiveSchedule.has(c, LiveSchedule.RECORD, ch.id, p.start)) return;
        labels.add(Ui.tr("Gravar de novo ", "Record next showing ") + dayTime(p.start));
        icons.add("record");
        acts.add(() -> {
            LiveSchedule.add(c, view.item(LiveSchedule.RECORD, ch, p));
            view.flash(Ui.tr("Grava às ", "Records at ") + dayTime(p.start));
            reload();
        });
    }

    private void attendTo(final LiveRecordings.Rec r, Object[] next) {
        final Context c = getContext();
        long nowMs = System.currentTimeMillis();
        List<String> labels = new ArrayList<>(), icons = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();
        if (r.showEnd() > nowMs + 60_000 && !r.fetch) {
            labels.add(Ui.tr("Gravar o resto", "Record the rest"));
            icons.add("record");
            acts.add(() -> {
                dismiss();
                live.recordNow(LiveRecordings.itemOf(r));
            });
        }
        int archive = live.archiveOf(r.ch);
        if (LiveRecordings.inCatchup(r.showStart(), r.showEnd(), archive, nowMs) && LiveOptions.missedShows(c) != LiveOptions.MISSED_OFF) {
            LiveRecordings.Rec have = LiveRecordings.find(c, LiveRecordings.fetchId(r.ch.id, r.showStart()));
            if (have == null || LiveRecordings.trouble(have)) {
                labels.add(Ui.tr("Pegar da reprise", "Get it from catch-up"));
                icons.add("history");
                acts.add(() -> {
                    LiveSchedule.Item it = LiveRecordings.itemOf(r);
                    it.ch = live.channelOf(r.ch);
                    if (r.fetch) LiveRecordings.delete(c, r.id);
                    RecordService.fetch(c, it);
                    view.flash(Ui.tr("Da reprise, assim que houver um canal livre", "From catch-up, as soon as there's a stream"));
                    page.postDelayed(this::reload, 1200);
                });
            }
        }
        if (LiveRecordings.inCatchup(r.showStart(), r.showEnd(), archive, nowMs)) {
            labels.add(Ui.tr("Ver a reprise", "Watch catch-up"));
            icons.add("play");
            acts.add(() -> {
                Xtream.Program p = new Xtream.Program();
                p.title = r.title;
                p.about = r.about;
                p.start = r.showStart();
                p.end = r.showEnd();
                dismiss();
                view.playPast(live.channelOf(r.ch), p);
            });
        }
        if (!empty(r)) {
            labels.add(Ui.tr("Assistir o que há", "Watch what there is"));
            icons.add("play");
            acts.add(() -> {
                dismiss();
                view.playFromRecordings(r);
            });
        }
        addNext(next, labels, icons, acts);
        labels.add(Ui.tr("Dispensar", "Dismiss"));
        icons.add("close");
        acts.add(() -> {
            LiveRecordings.dismiss(c, r.id);
            reload();
        });
        labels.add(Ui.tr("Apagar", "Delete"));
        icons.add("delete");
        acts.add(() -> {
            LiveRecordings.delete(c, r.id);
            reload();
        });
        LiveSheet.menu(c).setTitle(LiveStreams.name(r))
                .setMessage(whatHappened(r) + "  ·  " + when(r.showStart(), r.showEnd()) + "  ·  " + view.nameWithCountry(r.ch))
                .setIcons(icons.toArray(new String[0]))
                .setItems(labels.toArray(new String[0]), (d, w) -> acts.get(w).run()).show();
    }

    private void attendTo(final LiveSchedule.Item it, Object[] next) {
        final Context c = getContext();
        List<String> labels = new ArrayList<>(), icons = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();
        final List<Object> other = against(it);
        for (final Object o : other.subList(0, Math.min(3, other.size()))) {
            labels.add((o instanceof LiveRecordings.Rec ? Ui.tr("Parar ", "Stop ") : Ui.tr("Cancelar ", "Cancel ")) + nameOf(o));
            icons.add(o instanceof LiveRecordings.Rec ? "stop" : "close");
            acts.add(() -> {
                if (o instanceof LiveRecordings.Rec) RecordService.stop(c, ((LiveRecordings.Rec) o).id);
                else LiveSchedule.cancel(c, ((LiveSchedule.Item) o).key());
                page.postDelayed(this::reload, 800);
            });
        }
        labels.add(Ui.tr("Cancelar esta", "Cancel this one"));
        icons.add("close");
        acts.add(() -> {
            LiveSchedule.cancel(c, it.key());
            view.flash(Ui.tr("Gravação cancelada", "Recording cancelled"));
            reload();
        });
        addNext(next, labels, icons, acts);
        StringBuilder names = new StringBuilder();
        for (Object o : other) names.append(names.length() > 0 ? ", " : "").append(nameOf(o));
        LiveSheet.menu(c).setTitle(nameOf(it))
                .setMessage(Ui.tr("Sem canal livre junto com: ", "No stream to spare with: ") + names + "  ·  " + when(it.start, it.end))
                .setIcons(icons.toArray(new String[0]))
                .setItems(labels.toArray(new String[0]), (d, w) -> acts.get(w).run()).show();
    }

    /** "Missed: the TV was off", "2 gaps, 4 min missing". */
    private static String whatHappened(LiveRecordings.Rec r) {
        StringBuilder s = new StringBuilder();
        if (!r.why.isEmpty()) s.append(Character.toUpperCase(r.why.charAt(0))).append(r.why.substring(1));
        if (r.missing() > LiveRecordings.GAPS_MATTER_MS) s.append(s.length() > 0 ? "  ·  " : "").append(gapsOf(r));
        return s.toString();
    }

    private static String gapsOf(LiveRecordings.Rec r) {
        long sec = r.missing() / 1000;
        String much = sec < 60 ? sec + " s" : Math.round(sec / 60.0) + " min";
        return (r.gaps.size() == 1 ? Ui.tr("Parou uma vez, faltam ", "Stopped once, missing ") : Ui.tr("Parou ", "Stopped ") + r.gaps.size()
                + Ui.tr(" vezes, faltam ", " times, missing ")) + much;
    }

    private void choices() {
        final Context c = getContext();
        if (kind() == ATTENTION) {
            attend();
            return;
        }
        if (kind() == LATER) {
            if (at >= later.size()) return;
            final LiveSchedule.Item it = later.get(at);
            LiveSheet.menu(c)
                    .setTitle(it.title.isEmpty() ? it.ch.name : it.title)
                    .setMessage(when(it.start, it.end) + "  ·  " + view.nameWithCountry(it.ch))
                    .setIcons("close")
                    .setItems(new String[] {Ui.tr("Cancelar a gravação", "Cancel the recording")}, (d, which) -> {
                        LiveSchedule.cancel(c, it.key());
                        view.flash(Ui.tr("Gravação cancelada", "Recording cancelled"));
                        reload();
                    })
                    .show();
            return;
        }
        final LiveRecordings.Rec r = rec();
        if (r == null) return;
        final boolean on = LiveRecordings.RECORDING.equals(r.state), playable = on || !empty(r);
        List<String> labels = new ArrayList<>(), icons = new ArrayList<>();
        if (playable) {
            labels.add(Ui.tr("Assistir", "Watch"));
            icons.add("play");
        }
        if (on) {
            labels.add(Ui.tr("Parar de gravar", "Stop recording"));
            icons.add("stop");
        }
        labels.add(Ui.tr("Apagar", "Delete"));
        icons.add("delete");
        LiveSheet.menu(c)
                .setTitle(r.title.isEmpty() ? r.ch.name : r.title)
                .setMessage((r.why.isEmpty() ? "" : Character.toUpperCase(r.why.charAt(0)) + r.why.substring(1) + "  ·  ")
                        + when(r.start, r.end) + "  ·  " + view.nameWithCountry(r.ch))
                .setIcons(icons.toArray(new String[0]))
                .setItems(labels.toArray(new String[0]), (DialogInterface d, int which) -> {
                    if (playable && which == 0) {
                        dismiss();
                        view.playFromRecordings(r);
                    } else if (on && which == 1) {
                        RecordService.stop(c, r.id);
                        view.flash(Ui.tr("Gravação parada", "Recording stopped"));
                        page.postDelayed(this::reload, 800);
                    } else {
                        if (on) RecordService.stop(c, r.id);
                        final Context app = c.getApplicationContext();
                        new Thread(() -> {
                            LiveRecordings.delete(app, r.id);
                            page.post(this::reload);
                        }, "delete").start();
                        view.flash(Ui.tr("Gravação apagada", "Recording deleted"));
                    }
                })
                .show();
    }

    /** "Thu 22, 8:00 PM · 1 h 5 min". */
    private static String when(long start, long end) {
        java.text.SimpleDateFormat day = new java.text.SimpleDateFormat("EEE d", Ui.locale());
        long min = Math.max(1, Math.round((end - start) / 60_000.0));
        String length = min >= 60 ? (min / 60) + " h" + (min % 60 > 0 ? " " + (min % 60) + " min" : "") : min + " min";
        return day.format(new java.util.Date(start)) + ", " + Ui.time(start) + "  ·  " + length;
    }

    private static String sizeOf(long bytes) {
        if (bytes >= 1_000_000_000L) return String.format(Ui.locale(), "%.1f GB", bytes / 1e9);
        return Math.max(1, bytes / 1_000_000) + " MB";
    }

    // MARK: - Drawing

    /** The page, drawn by hand (1920 × 1080 space, k = width / 1920). */
    private final class Page extends View {
        private static final float X = 96, R = 1824, TITLE_Y = 132, TOP = 230, ROW_STEP = 384, TILE_W = 384, TILE_H = 216, GAP = 24;
        private final RectF r = new RectF(), img = new RectF(), logo = new RectF();
        private final TextPaint fit = new TextPaint();
        private final Spring in = new Spring(0.32f, 0.9f, 0), rowY = new Spring(0.3f, 1f, 0);
        private final Spring[] scroll = {new Spring(0.3f, 1f, 0), new Spring(0.3f, 1f, 0), new Spring(0.3f, 1f, 0), new Spring(0.3f, 1f, 0)};
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean soft;
        private long stampAt, stampSeen;
        private Paint fill, title, meta, heading, name, nameOn, sub, empty, pill;
        private float k;
        private long last;
        private final Xtream.Program show = new Xtream.Program();

        Page(Context c) {
            super(c);
            in.target = 1;
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            k = w / 1920f;
            fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            title = Ui.text(Ui.displaySemibold, 54 * k, 0xFFF5F5F7);
            meta = Ui.text(Ui.regular, 26 * k, 0x8CFFFFFF);
            heading = Ui.text(Ui.semibold, 30 * k, 0xE6FFFFFF);
            name = Ui.text(Ui.medium, 26 * k, 0xB3FFFFFF);
            nameOn = Ui.text(Ui.semibold, 26 * k, 0xFFF5F5F7);
            sub = Ui.text(Ui.regular, 22 * k, 0x80FFFFFF);
            empty = Ui.text(Ui.regular, 30 * k, 0x99FFFFFF);
            pill = Ui.text(Ui.semibold, 18 * k, 0xFFFFFFFF);
        }

        @Override protected void onDraw(Canvas c) {
            if (fill == null) return;
            long nowMs = SystemClock.uptimeMillis();
            float dt = last == 0 ? 1 / 60f : Math.min(0.05f, (nowMs - last) / 1000f);
            last = nowMs;
            boolean moving = in.step(dt) | rowY.step(dt);
            for (Spring s : scroll) moving |= s.step(dt);
            float a = Ui.clamp(in.value);
            LiveTheme.background(c, getWidth(), getHeight(), a);
            c.save();
            c.translate(0, (1 - a) * 40 * k);
            c.drawText(Ui.tr("Gravações", "Recordings"), X * k, TITLE_Y * k, title);
            int total = now.size() + over.size();
            String info = total + " " + (total == 1 ? Ui.tr("gravação", "recording") : Ui.tr("gravações", "recordings"))
                    + (size > 0 ? "  ·  " + sizeOf(size) : "") + (later.isEmpty() ? "" : "  ·  " + later.size() + Ui.tr(" marcadas", " set for later"))
                    + "  ·  " + sizeOf(free) + Ui.tr(" livres", " free")
                    + (clashing.isEmpty() ? "" : "  ·  " + clashing.size() + Ui.tr(" com conflito", " won't fit"));
            c.drawText(info, X * k + title.measureText(Ui.tr("Gravações", "Recordings")) + 28 * k, TITLE_Y * k, meta);
            // How full the recordings' place is, at the right.
            long room = LiveRecordings.folder(getContext()).getTotalSpace();
            if (room > 0) {
                float bw = 240 * k, bx = R * k - bw, by = TITLE_Y * k - 16 * k;
                fill.setColor(0x26FFFFFF);
                c.drawRoundRect(bx, by, bx + bw, by + 10 * k, 5 * k, 5 * k, fill);
                fill.setColor(free < 2_000_000_000L ? 0xFFFFB340 : 0xFF8E8E93);
                c.drawRoundRect(bx, by, bx + Math.max(10 * k, bw * Ui.clamp(1 - free / (float) room)), by + 10 * k, 5 * k, 5 * k, fill);
            }
            if (rows.isEmpty()) {
                // In the middle of the page: what's missing and how to get it.
                float mid = getWidth() / 2f, top = getHeight() * 0.36f;
                Symbols.draw(getContext(), c, "recordings", mid, top, 72 * k, 0x99FFFFFF);
                String none = Ui.tr("Nenhuma gravação ainda.", "No recordings yet.");
                c.drawText(none, mid - empty.measureText(none) / 2, top + 96 * k, empty);
                String how = Ui.tr("No guia, aperte OK num programa e escolha Gravar; ou o botão vermelho do player.",
                        "In the guide, press OK on a show and choose Record; or the player's red button.");
                c.drawText(how, mid - sub.measureText(how) / 2, top + 144 * k, sub);
                c.restore();
                if (moving) postInvalidateOnAnimation(); else last = 0;     // the page still fading in
                return;
            }
            // The rows glide up so the focused one stays in view (three fit).
            rowY.target = Math.max(0, row - 1);
            c.save();
            c.clipRect(0, (TOP - 60) * k, getWidth(), getHeight());
            for (int i = 0; i < rows.size(); i++) {
                float y = (TOP + (i - rowY.value) * ROW_STEP) * k;
                if (y > getHeight() || y + ROW_STEP * k < (TOP - 80) * k) continue;
                drawRow(c, rows.get(i), y, i == row);
            }
            c.restore();
            c.restore();
            long stamp = nowMs - stampAt < 2000 ? stampSeen : LiveRecordings.stamp(getContext());
            if (nowMs - stampAt >= 2000) stampAt = nowMs;
            if (stampSeen != 0 && stamp != stampSeen) post(LiveRecordingsPage.this::reload);       // further on, or over
            stampSeen = stamp;
            if (moving) postInvalidateOnAnimation();
            else {
                last = 0;
                if (!now.isEmpty()) postInvalidateDelayed(1000);
            }
        }

        private void drawRow(Canvas c, int kind, float y, boolean focused) {
            String head = kind == ATTENTION ? Ui.tr("Precisam de atenção", "Needs attention") + "  ·  " + attention.size() : kind == NOW ? Ui.tr("Gravando agora", "Recording now") : kind == DONE ? Ui.tr("Gravados", "Recorded")
                    : Ui.tr("Marcadas para depois", "Set for later");
            c.drawText(head, X * k, y, heading);
            int n = count(kind), focus = focused ? at : atOf[kind];
            float inner = (R - X) / 1, step = TILE_W + GAP;
            float t = scroll[kind].target, fx = focus * step;
            if (fx < t) t = fx;
            if (fx + TILE_W > t + inner) t = fx + TILE_W - inner;
            scroll[kind].target = Math.max(0, Math.min(t, Math.max(0, n * step - GAP - inner)));
            float top = y + 28 * k;
            long nowMs = System.currentTimeMillis();
            for (int i = 0; i < n; i++) {
                float tx = (X + i * step - scroll[kind].value) * k;
                if (tx > getWidth() || tx + TILE_W * k < 0) continue;
                boolean on = focused && i == at;
                String t1, t2;
                Xtream.Channel ch;
                long start, end;
                boolean recording = false, failed = false;
                String why = "";
                boolean missed = false;
                Object one = kind == ATTENTION ? attention.get(i) : kind == LATER ? later.get(i) : (kind == NOW ? now : done).get(i);
                LiveRecordings.Rec whole = one instanceof LiveRecordings.Rec ? (LiveRecordings.Rec) one : null;
                if (whole == null) {
                    LiveSchedule.Item it = (LiveSchedule.Item) one;
                    t1 = it.title.isEmpty() ? it.ch.name : it.title;
                    ch = it.ch;
                    start = it.start;
                    end = it.end;
                } else {
                    LiveRecordings.Rec rec = whole;
                    t1 = rec.title.isEmpty() ? rec.ch.name : rec.title;
                    ch = rec.ch;
                    start = rec.start;
                    end = rec.end;
                    recording = kind == NOW;
                    failed = LiveRecordings.FAILED.equals(rec.state) || !rec.why.isEmpty() && !recording;
                    why = rec.why;
                    missed = rec.file.isEmpty();
                }
                if (whole != null && recording && whole.fetch) {
                    t2 = (whole.waiting ? Ui.tr("Esperando um canal livre", "Waiting for a stream")
                            : Ui.tr("Da reprise  ·  ", "From catch-up  ·  ") + Math.min(99, 100 * whole.got / Math.max(1, end - start)) + "%") + "  ·  " + view.nameWithCountry(ch);
                } else if (whole != null && recording && !whole.gaps.isEmpty()) {
                    t2 = Ui.tr("Até ", "Until ") + Ui.time(end) + "  ·  " + gapsOf(whole);
                } else if (kind == ATTENTION && whole == null) {
                    List<Object> o = against((LiveSchedule.Item) one);
                    t2 = dayTime(start) + "  ·  " + Ui.tr("Sem canal livre", "No stream to spare") + (o.isEmpty() ? "" : Ui.tr(" com ", " with ") + (o.size() == 1 ? nameOf(o.get(0)) : o.size() + Ui.tr(" outras", " others")));
                } else if (kind == ATTENTION) {
                    t2 = whatHappened(whole) + (LiveRecordings.inCatchup(whole.showStart(), whole.showEnd(), live.archiveOf(ch), nowMs)
                            ? "  ·  " + Ui.tr("A reprise tem", "Catch-up has it") : "");
                } else
                t2 = !why.isEmpty() ? Character.toUpperCase(why.charAt(0)) + why.substring(1) + "  ·  " + view.nameWithCountry(ch)
                        : when(start, end) + "  ·  " + view.nameWithCountry(ch);
                r.set(tx, top, tx + TILE_W * k, top + TILE_H * k);
                if (on) r.inset(-8 * k, -8 * k);
                soft = LiveTheme.cinema() && LiveOptions.guideSoft(getContext()) && !LiveTheme.highContrast;
                fill.setColor(on ? soft ? 0xFF3A3A40 : LiveTheme.tile : LiveTheme.surface);
                c.drawRoundRect(r, 20 * k * LiveTheme.round, 20 * k * LiveTheme.round, fill);
                if (!on) LiveTheme.rim(c, r, 20 * k * LiveTheme.round, 1.5f * k);
                show.title = t1;
                show.icon = "";
                Bitmap art = view.artOf(show);
                if (art != null) {
                    img.set(r);
                    if (on) img.inset(7 * k, 7 * k);
                    view.drawCropped(c, art, img, 16 * k * LiveTheme.round);
                } else {
                    logo.set(r);
                    logo.inset(90 * k, 60 * k);
                    view.drawLogo(c, ch, logo, 1);
                }
                if (on && soft) {
                    line.setStyle(Paint.Style.STROKE);
                    line.setStrokeWidth(4 * k);
                    line.setColor(LiveTheme.tile);
                    float rr = 20 * k * LiveTheme.round;
                    c.drawRoundRect(r, rr, rr, line);
                }
                if (whole != null && !whole.gaps.isEmpty() && !recording) drawHoles(c, whole, start, end);
                if (kind == ATTENTION && whole == null) {
                    drawPill(c, Ui.tr("CONFLITO", "CLASH"), r.left + 16 * k, r.top + 16 * k, 0xFFD92D20);
                } else if (kind == ATTENTION && why.isEmpty()) {
                    drawPill(c, whole.gaps.size() == 1 ? Ui.tr("1 FALHA", "1 GAP") : whole.gaps.size() + Ui.tr(" FALHAS", " GAPS"), r.left + 16 * k, r.top + 16 * k, 0xE6854F0B);
                } else if (recording && whole.fetch) {
                    drawPill(c, Ui.tr("REPRISE", "CATCH-UP"), r.left + 16 * k, r.top + 16 * k, 0xCC3A3A3C);
                    bar(c, 0, 1, 0x66000000);
                    bar(c, 0, Ui.clamp(whole.got / (float) Math.max(1, end - start)), 0xFFF5F5F7);
                } else if (recording && !whole.gaps.isEmpty()) {
                    drawPill(c, "REC", r.left + 16 * k, r.top + 16 * k, 0xFFFF453A);
                    drawHoles(c, whole, start, end);
                } else if (recording) {        // REC, and how far it's got
                    drawPill(c, "REC", r.left + 16 * k, r.top + 16 * k, 0xFFFF453A);
                    float f = Ui.clamp((nowMs - start) / (float) Math.max(1, end - start));
                    fill.setColor(0x66000000);
                    c.drawRect(r.left + 16 * k, r.bottom - 22 * k, r.right - 16 * k, r.bottom - 16 * k, fill);
                    fill.setColor(0xFFFF453A);
                    c.drawRect(r.left + 16 * k, r.bottom - 22 * k, r.left + 16 * k + (r.width() - 32 * k) * f, r.bottom - 16 * k, fill);
                } else if (failed) {
                    drawPill(c, missed ? Ui.tr("PERDIDA", "MISSED") : Ui.tr("INCOMPLETA", "INCOMPLETE"), r.left + 16 * k, r.top + 16 * k,
                            missed ? 0xCCC0392B : 0xCC3A3A3C);
                } else if (kind == LATER) {
                    if (clashing.contains(later.get(i).key())) drawPill(c, Ui.tr("CONFLITO", "CLASH"), r.left + 16 * k, r.top + 56 * k, 0xFFD92D20);
                    drawPill(c, start - nowMs < 24 * 3600_000L ? Ui.time(start) : Ui.tr("MARCADA", "SET"), r.left + 16 * k, r.top + 16 * k, 0xCC3A3A3C);
                }
                Paint np = on ? nameOn : name;
                c.drawText(ellipsize(t1, np, TILE_W * k), tx, top + (TILE_H + 42) * k, np);
                c.drawText(ellipsize(t2, sub, TILE_W * k), tx, top + (TILE_H + 74) * k, sub);
            }
        }

        private void bar(Canvas c, float from, float to, int color) {
            fill.setColor(color);
            float x0 = r.left + 16 * k, bw = r.width() - 32 * k;
            c.drawRect(x0 + bw * Ui.clamp(from), r.bottom - 22 * k, x0 + bw * Ui.clamp(to), r.bottom - 16 * k, fill);
        }

        /** The recording as far as it's got, with its holes. */
        private void drawHoles(Canvas c, LiveRecordings.Rec rec, long start, long end) {
            float all = Math.max(1, end - start);
            long to = Math.min(end, LiveRecordings.RECORDING.equals(rec.state) ? System.currentTimeMillis() : end), from = start;
            bar(c, 0, 1, 0x66000000);
            for (long[] g : rec.gaps) {
                if (g[0] > from) bar(c, (from - start) / all, (Math.min(to, g[0]) - start) / all, 0xFFFF453A);
                from = Math.max(from, g[1]);
            }
            if (to > from) bar(c, (from - start) / all, (to - start) / all, 0xFFFF453A);
        }

        private void drawPill(Canvas c, String text, float x, float y, int color) {
            float w = pill.measureText(text) + 22 * k, h = 32 * k;
            fill.setColor(color);
            c.drawRoundRect(x, y, x + w, y + h, 8 * k, 8 * k, fill);
            c.drawText(text, x + 11 * k, y + h / 2 + Ui.halfCap(pill), pill);
        }

        private String ellipsize(String s, Paint p, float width) {
            fit.set(p);
            return TextUtils.ellipsize(s, fit, Math.max(1, width), TextUtils.TruncateAt.END).toString();
        }
    }
}
