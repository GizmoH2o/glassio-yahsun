package com.glasslauncher.tv.live;

import android.app.Dialog;
import android.content.Context;
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

/**
 * A setting's choices all at once, as tiles with a picture each (a theme picker's): the arrows move between them, OK
 * picks one, Back leaves it as it was. The one in use has a check; the focused one is raised in a white ring.
 */
final class LiveChooser extends Dialog {
    /** Draws choice `i`'s picture in `r` (screen pixels). */
    interface Picture {
        void draw(Canvas c, RectF r, int i);
    }

    interface Picked {
        void picked(int i);
    }

    private final String title;
    private final String[] labels;
    /** A line under each choice saying what it does (null: none). */
    private String[] details;
    /** The colors: Live TV's style, or the home screen settings' (white glass focus on smoked black). */
    private int bgTop = LiveTheme.bgTop, bgBottom = LiveTheme.bg, tile = LiveTheme.tile, ink = LiveTheme.ink, surface = LiveTheme.surface;
    private float round = LiveTheme.round;
    private boolean rim = LiveTheme.rim != 0;
    private final int current, perRow;
    private final float aspect;
    private final Picture picture;
    private final Picked picked;
    private final Page page;
    private int focus;

    private LiveChooser(Context c, String title, String[] labels, int current, int perRow, float aspect, Picture picture, Picked picked) {
        super(c, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        this.title = title;
        this.labels = labels;
        this.current = current;
        this.perRow = perRow;
        this.aspect = aspect;
        this.picture = picture;
        this.picked = picked;
        focus = Math.max(0, Math.min(labels.length - 1, current));
        page = new Page(c);
    }

    /** Choices that are words (no picture): each tile holds its name, five or fewer across. */
    static void showWords(Context c, String title, String[] labels, int current, Picked picked) {
        showWords(c, title, labels, null, current, false, picked);
    }

    /** Words with a line each saying what it does; `glass`: the home screen settings' look. */
    static void showWords(Context c, String title, String[] labels, String[] details, int current, boolean glass, Picked picked) {
        int across = Math.min(details != null ? 4 : 5, Math.max(2, labels.length <= 4 ? labels.length : (labels.length + 1) / 2));
        LiveChooser ch = new LiveChooser(c, title, labels, current, across, details != null ? 2.1f : 3.2f, null, picked);
        ch.details = details;
        if (glass) ch.glass();
        ch.show();
    }

    /** The home screen settings' look: smoked black, a white focus with dark text. */
    private void glass() {
        bgTop = 0xFF1A1E27;
        bgBottom = 0xFF06070A;
        tile = 0xFFF5F5F7;
        ink = 0xFF1C1C1E;
        surface = 0x1FFFFFFF;
        round = 1;
        rim = false;
    }

    /** Pictures with a line each, in the home screen settings' look. */
    static void showGlass(Context c, String title, String[] labels, String[] details, int current, int perRow, float aspect,
            Picture picture, Picked picked) {
        LiveChooser ch = new LiveChooser(c, title, labels, current, perRow, aspect, picture, picked);
        ch.details = details;
        ch.glass();
        ch.show();
    }

    /** Pictures with a line each saying what they are. */
    static void show(Context c, String title, String[] labels, String[] details, int current, int perRow, float aspect, Picture picture, Picked picked) {
        LiveChooser ch = new LiveChooser(c, title, labels, current, perRow, aspect, picture, picked);
        ch.details = details;
        ch.show();
    }

    /** Shows the choices; `aspect` is each picture's width over its height. */
    static void show(Context c, String title, String[] labels, int current, int perRow, float aspect, Picture picture, Picked picked) {
        new LiveChooser(c, title, labels, current, perRow, aspect, picture, picked).show();
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
        page.announceForAccessibility(title + ", " + labels[focus]);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (code == KeyEvent.KEYCODE_BACK) {
            if (!down && !e.isCanceled()) dismiss();
            return true;
        }
        if (code == KeyEvent.KEYCODE_HOME) return super.dispatchKeyEvent(e);
        if (!down) return true;
        int n = labels.length;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: if (focus % perRow > 0) focus--; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: if (focus % perRow < perRow - 1 && focus + 1 < n) focus++; break;
            case KeyEvent.KEYCODE_DPAD_UP: if (focus - perRow >= 0) focus -= perRow; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: focus = Math.min(n - 1, focus + perRow); break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (e.getRepeatCount() == 0) {
                    dismiss();
                    picked.picked(focus);
                }
                return true;
            default:
                return true;
        }
        page.announceForAccessibility(labels[focus]);
        page.invalidate();
        return true;
    }

    /** The page, drawn by hand (1920 × 1080 space, k = width / 1920). */
    private final class Page extends View {
        private static final float X = 120, TOP = 230, GAP = 32, LABEL = 58;
        private final Spring in = new Spring(0.32f, 0.9f, 0), at = new Spring(0.28f, 0.9f, 0), scroll = new Spring(0.3f, 1f, 0);
        private final RectF r = new RectF();
        private final android.graphics.Path clip = new android.graphics.Path();
        private final TextPaint fit = new TextPaint();
        private Paint fill, ring, heading, label, labelOn, word, detail, bgPaint;
        private int bgH;
        private float k;
        private long last;

        Page(Context c) {
            super(c);
            in.target = 1;
            at.snap(focus);
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            k = w / 1920f;
            fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            ring = new Paint(Paint.ANTI_ALIAS_FLAG);
            ring.setStyle(Paint.Style.STROKE);
            heading = Ui.text(Ui.displaySemibold, 46 * k, 0xFFF5F5F7);
            label = Ui.text(Ui.medium, 26 * k, 0xB3FFFFFF);
            label.setTextAlign(Paint.Align.CENTER);
            labelOn = Ui.text(Ui.semibold, 26 * k, 0xFFF5F5F7);
            labelOn.setTextAlign(Paint.Align.CENTER);
            word = Ui.text(Ui.semibold, 32 * k, 0xF2FFFFFF);
            word.setTextAlign(Paint.Align.CENTER);
            detail = Ui.text(Ui.regular, 22 * k, 0x99FFFFFF);
            detail.setTextAlign(Paint.Align.CENTER);
        }

        /** `text` centered at cx from y, wrapped at a space to two lines in `width`. */
        private void drawDetail(Canvas c, String text, float cx, float y, float width) {
            fit.set(detail);
            if (detail.measureText(text) <= width) {
                c.drawText(text, cx, y + Ui.halfCap(detail), detail);
                return;
            }
            int cut = detail.breakText(text, true, width, null);
            int space = text.lastIndexOf(' ', cut);
            if (space <= 0) space = cut;
            c.drawText(text.substring(0, space).trim(), cx, y + Ui.halfCap(detail), detail);
            String rest = TextUtils.ellipsize(text.substring(space).trim(), fit, width, TextUtils.TruncateAt.END).toString();
            c.drawText(rest, cx, y + 30 * k + Ui.halfCap(detail), detail);
        }

        @Override protected void onDraw(Canvas c) {
            if (fill == null) return;
            long up = SystemClock.uptimeMillis();
            float dt = last == 0 ? 1 / 60f : Math.min(0.05f, (up - last) / 1000f);
            last = up;
            // The tiles as big as fit: across the width, and two rows on the screen at most (more scroll; then the grid in
            // the middle).
            int row = focus / perRow, rows = (labels.length + perRow - 1) / perRow;
            float room = 1080 - TOP - 40;
            float tw = (1920 - 2 * X - (perRow - 1) * GAP) / perRow;
            float labelRoom = picture == null ? 0 : LABEL + (details != null ? 64 : 0);
            tw = Math.min(tw, (room / Math.min(rows, 2) - labelRoom - GAP) * aspect);
            float th = tw / aspect, step = th + labelRoom + GAP, x0 = (1920 - perRow * tw - (perRow - 1) * GAP) / 2;
            scroll.target = Math.max(0, Math.min(row * step - (room - step) / 2, rows * step - room));
            boolean moving = in.step(dt) | at.step(dt) | scroll.step(dt);
            at.target = focus;
            float a = Ui.clamp(in.value);
            if (bgPaint == null || bgH != getHeight()) {
                bgPaint = new Paint();
                bgPaint.setShader(new android.graphics.LinearGradient(0, 0, 0, getHeight(), bgTop, bgBottom, android.graphics.Shader.TileMode.CLAMP));
                bgH = getHeight();
            }
            bgPaint.setAlpha(Math.round(255 * a));
            c.drawRect(0, 0, getWidth(), getHeight(), bgPaint);
            c.save();
            c.translate(0, (1 - a) * 40 * k);
            c.drawText(title, x0 * k, 150 * k, heading);
            c.save();
            c.clipRect(0, (TOP - 30) * k, getWidth(), getHeight());
            for (int i = 0; i < labels.length; i++) {
                float x = x0 + (i % perRow) * (tw + GAP), y = TOP + (i / perRow) * step - scroll.value;
                if (y > 1080 || y + step < TOP - 40) continue;
                boolean on = i == focus;
                float grow = on ? 8 : 0;
                r.set((x - grow) * k, (y - grow) * k, (x + tw + grow) * k, (y + th + grow) * k);
                c.save();
                fill.setColor(picture == null ? (on ? tile : surface | 0x14000000) : 0xFF1C1C1E);
                c.drawRoundRect(r, 16 * k * round, 16 * k * round, fill);
                clip.reset();       // the picture inside the tile's rounded corners
                clip.addRoundRect(r, 16 * k * round, 16 * k * round, android.graphics.Path.Direction.CW);
                c.clipPath(clip);
                if (picture != null) {
                    picture.draw(c, r, i);
                } else {        // words: the name in the tile, in the focus' ink on it
                    word.setColor(on ? ink : 0xF2FFFFFF);
                    fit.set(word);
                    String w = TextUtils.ellipsize(labels[i], fit, r.width() - 24 * k, TextUtils.TruncateAt.END).toString();
                    boolean said = details != null && i < details.length && details[i] != null && !details[i].isEmpty();
                    c.drawText(w, r.centerX(), r.centerY() + Ui.halfCap(word) - (said ? 22 * k : 0), word);
                    if (said) {     // what it does, under its name (two lines at most)
                        detail.setColor(on ? Ui.alpha(ink, 0.75f) : 0x99FFFFFF);
                        drawDetail(c, details[i], r.centerX(), r.centerY() + 26 * k, r.width() - 36 * k);
                    }
                }
                c.restore();
                if (!on && rim) {       // glass: the light edge
                    ring.setStrokeWidth(1.5f * k);
                    LiveTheme.drawRim(c, r, 16 * k * round, ring);
                }
                if (on && picture != null) {
                    ring.setColor(tile);        // the focus around the picture, in the style's color
                    ring.setStrokeWidth(5 * k);
                    c.drawRoundRect(r, (16 * round + 2) * k, (16 * round + 2) * k, ring);
                }
                if (i == current) {     // the one in use: a check at the corner
                    float cx = r.right - 26 * k, cy = r.top + 26 * k;
                    boolean onLight = on && picture == null;      // on the focus: the check in its ink, to show on a white one
                    fill.setColor(onLight ? ink : 0xFFF5F5F7);
                    c.drawCircle(cx, cy, 18 * k, fill);
                    Symbols.draw(getContext(), c, "check", cx, cy, 22 * k, onLight ? tile : 0xFF1C1C1E);
                }
                if (picture == null) continue;      // the name is in the tile
                Paint p = on ? labelOn : label;
                fit.set(p);
                String text = TextUtils.ellipsize(labels[i], fit, tw * k, TextUtils.TruncateAt.END).toString();
                c.drawText(text, (x + tw / 2) * k, (y + th + 42) * k, p);
                if (details != null && i < details.length && details[i] != null) {     // two lines at most
                    detail.setColor(0x99FFFFFF);
                    drawDetail(c, details[i], (x + tw / 2) * k, (y + th + 70) * k, tw * k);
                }
            }
            c.restore();
            c.restore();
            if (moving) postInvalidateOnAnimation(); else last = 0;
        }
    }
}
