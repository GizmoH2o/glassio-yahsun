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
final class LiveRecordingsPage extends Dialog {
    private final LiveView view;
    private final Live live;
    private final Page page;
    // The rows: recording now, recorded, set for later (a failed one is with the recorded, marked).
    private final List<LiveRecordings.Rec> now = new ArrayList<>(), done = new ArrayList<>();
    private final List<LiveSchedule.Item> later = new ArrayList<>();
    private final List<Integer> rows = new ArrayList<>();       // which rows there are, in order: NOW, DONE, LATER
    private static final int NOW = 0, DONE = 1, LATER = 2;
    private int row, at;
    private final int[] atOf = new int[3];
    private long size;
    private boolean okDown, okHeld;

    LiveRecordingsPage(Context c, LiveView view, Live live) {
        super(c, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        this.view = view;
        this.live = live;
        page = new Page(c);
        reload();
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
        page.announceForAccessibility(Ui.tr("Gravações", "Recordings"));
    }

    /** The recordings and the schedule's recordings, read again (one stopped, deleted, cancelled). */
    private void reload() {
        Context c = getContext();
        now.clear();
        done.clear();
        later.clear();
        size = 0;
        List<LiveRecordings.Rec> all = LiveRecordings.all(c);
        for (int i = all.size() - 1; i >= 0; i--) {     // the newest first
            LiveRecordings.Rec r = all.get(i);
            size += r.size;
            if (LiveRecordings.RECORDING.equals(r.state)) now.add(r); else done.add(r);
        }
        for (LiveSchedule.Item it : LiveSchedule.all(c)) if (LiveSchedule.RECORD.equals(it.kind)) later.add(it);
        rows.clear();
        if (!now.isEmpty()) rows.add(NOW);
        if (!done.isEmpty()) rows.add(DONE);
        if (!later.isEmpty()) rows.add(LATER);
        row = Math.max(0, Math.min(row, rows.size() - 1));
        for (int r = 0; r < 3; r++) atOf[r] = Math.max(0, Math.min(atOf[r], count(r) - 1));
        at = rows.isEmpty() ? 0 : atOf[rows.get(row)];
        page.invalidate();
    }

    private int count(int kind) {
        return kind == NOW ? now.size() : kind == DONE ? done.size() : later.size();
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
        page.invalidate();
        return true;
    }

    /** OK: a recording plays; one set for later, its choices. */
    private void pick() {
        int kind = kind();
        if (kind == LATER) {
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
        List<LiveRecordings.Rec> list = kind == NOW ? now : kind == DONE ? done : null;
        return list == null || at >= list.size() ? null : list.get(at);
    }

    /** Held OK: play, stop recording, delete; on one set for later, cancel it. */
    private void choices() {
        final Context c = getContext();
        if (kind() == LATER) {
            if (at >= later.size()) return;
            final LiveSchedule.Item it = later.get(at);
            LiveSheet.menu(c)
                    .setTitle(it.title.isEmpty() ? it.ch.name : it.title)
                    .setMessage(when(it.start, it.end) + "  ·  " + it.ch.name)
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
                        + when(r.start, r.end) + "  ·  " + r.ch.name)
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
        private final Spring[] scroll = {new Spring(0.3f, 1f, 0), new Spring(0.3f, 1f, 0), new Spring(0.3f, 1f, 0)};
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
            int total = now.size() + done.size();
            String info = total + " " + (total == 1 ? Ui.tr("gravação", "recording") : Ui.tr("gravações", "recordings"))
                    + (size > 0 ? "  ·  " + sizeOf(size) : "") + (later.isEmpty() ? "" : "  ·  " + later.size() + Ui.tr(" marcadas", " set for later"));
            c.drawText(info, X * k + title.measureText(Ui.tr("Gravações", "Recordings")) + 28 * k, TITLE_Y * k, meta);
            if (rows.isEmpty()) {
                c.drawText(Ui.tr("Nenhuma gravação ainda.", "No recordings yet."), X * k, (TOP + 80) * k, empty);
                c.drawText(Ui.tr("No guia, aperte OK num programa e escolha Gravar; ou o botão vermelho do player.",
                        "In the guide, press OK on a show and choose Record; or the player's red button."), X * k, (TOP + 130) * k, sub);
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
            if (moving || !now.isEmpty()) postInvalidateOnAnimation(); else last = 0;
        }

        private void drawRow(Canvas c, int kind, float y, boolean focused) {
            String head = kind == NOW ? Ui.tr("Gravando agora", "Recording now") : kind == DONE ? Ui.tr("Gravados", "Recorded")
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
                if (kind == LATER) {
                    LiveSchedule.Item it = later.get(i);
                    t1 = it.title.isEmpty() ? it.ch.name : it.title;
                    ch = it.ch;
                    start = it.start;
                    end = it.end;
                } else {
                    LiveRecordings.Rec rec = (kind == NOW ? now : done).get(i);
                    t1 = rec.title.isEmpty() ? rec.ch.name : rec.title;
                    ch = rec.ch;
                    start = rec.start;
                    end = rec.end;
                    recording = kind == NOW;
                    failed = LiveRecordings.FAILED.equals(rec.state) || !rec.why.isEmpty() && !recording;
                    why = rec.why;
                    missed = rec.file.isEmpty();
                }
                t2 = !why.isEmpty() ? Character.toUpperCase(why.charAt(0)) + why.substring(1) + "  ·  " + ch.name
                        : when(start, end) + "  ·  " + ch.name;
                r.set(tx, top, tx + TILE_W * k, top + TILE_H * k);
                if (on) r.inset(-8 * k, -8 * k);
                fill.setColor(on ? LiveTheme.tile : LiveTheme.surface);
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
                if (recording) {        // REC, and how far it's got
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
                    drawPill(c, start - nowMs < 24 * 3600_000L ? Ui.time(start) : Ui.tr("MARCADA", "SET"), r.left + 16 * k, r.top + 16 * k, 0xCC3A3A3C);
                }
                Paint np = on ? nameOn : name;
                c.drawText(ellipsize(t1, np, TILE_W * k), tx, top + (TILE_H + 42) * k, np);
                c.drawText(ellipsize(t2, sub, TILE_W * k), tx, top + (TILE_H + 74) * k, sub);
            }
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
