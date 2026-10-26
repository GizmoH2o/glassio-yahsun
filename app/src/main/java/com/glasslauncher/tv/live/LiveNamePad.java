package com.glasslauncher.tv.live;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
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
 * A name typed with the remote (a profile's), as Search types: the field at the top, the keyboard under it (letters
 * and numbers, a page of symbols, space, delete, Done in the theme's color). A remote's or a keyboard's letters type
 * too. Done (or OK on it) gives the name; Back cancels.
 */
final class LiveNamePad extends Dialog {
    interface Done {
        void got(String name);
    }

    private static final String LETTERS = "abcdefghijklmnopqrstuvwxyz1234567890";
    private static final String SYMBOLS = "&'-.,:!?#+()/@*\"_;=%$€£~[]<>|^{}\\`°§";
    private static final int COLS = 6, ROWS = 7, CHARS = 36, MAX = 20;
    private static final int PAGE_KEY = CHARS, SPACE_KEY = CHARS + 1, DELETE_KEY = CHARS + 2, DONE_KEY = CHARS + 3;
    private static final int[] BOTTOM_COL = {0, 1, 3, 4}, BOTTOM_SPAN = {1, 2, 1, 2};
    private final String title;
    private final Done done;
    private final Pad pad;
    private String text;
    private int key;
    private boolean symbols, caps = true;

    static void ask(Context c, String title, String start, Done done) {
        new LiveNamePad(c, title, start, done).show();
    }

    private LiveNamePad(Context c, String title, String start, Done done) {
        super(c, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        this.title = title;
        this.done = done;
        text = start == null ? "" : start;
        caps = text.isEmpty();
        pad = new Pad(c);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(pad);
        Window w = getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        pad.setFocusable(true);
        pad.requestFocus();
        pad.announceForAccessibility(title);
    }

    // MARK: - Keys

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) dismiss();
            return true;
        }
        if (code == KeyEvent.KEYCODE_HOME) return super.dispatchKeyEvent(e);
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (code == KeyEvent.KEYCODE_DEL) {
            delete();
        } else if (e.getUnicodeChar() > 0 && !Character.isISOControl(e.getUnicodeChar()) && code != KeyEvent.KEYCODE_DPAD_CENTER
                && code != KeyEvent.KEYCODE_ENTER) {
            type((char) e.getUnicodeChar());
        } else {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP: if (row() > 0) keyAt(row() - 1, col()); break;
                case KeyEvent.KEYCODE_DPAD_DOWN: if (row() < ROWS - 1) keyAt(row() + 1, col()); break;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (key >= CHARS) { if (key > CHARS) key--; } else if (col() > 0) keyAt(row(), col() - 1);
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (key >= CHARS) { if (key < DONE_KEY) key++; } else if (col() < COLS - 1) keyAt(row(), col() + 1);
                    break;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (e.getRepeatCount() == 0) press();
                    break;
                default:
                    break;
            }
        }
        pad.invalidate();
        return true;
    }

    private int row() {
        return key < CHARS ? key / COLS : ROWS - 1;
    }

    private int col() {
        return key < CHARS ? key % COLS : BOTTOM_COL[key - CHARS];
    }

    private void keyAt(int row, int col) {
        if (row < ROWS - 1) {
            key = row * COLS + col;
            return;
        }
        int i = BOTTOM_COL.length - 1;
        while (i > 0 && BOTTOM_COL[i] > col) i--;
        key = CHARS + i;
    }

    private void press() {
        if (key < CHARS) type((symbols ? SYMBOLS : LETTERS).charAt(key));
        else if (key == PAGE_KEY) symbols = !symbols;
        else if (key == SPACE_KEY) { if (!text.isEmpty() && !text.endsWith(" ")) type(' '); }
        else if (key == DELETE_KEY) delete();
        else finish();
    }

    /** A letter: a capital at the start of each word (a name's). */
    private void type(char ch) {
        if (text.length() >= MAX) return;
        if (caps && Character.isLetter(ch)) ch = Character.toUpperCase(ch);
        text += ch;
        caps = ch == ' ';
    }

    private void delete() {
        if (!text.isEmpty()) text = text.substring(0, text.length() - 1);
        caps = text.isEmpty() || text.endsWith(" ");
    }

    private void finish() {
        String name = text.trim();
        dismiss();
        done.got(name);
    }

    // MARK: - Drawing

    /** The page, drawn by hand (1920 × 1080 space, k = width / 1920). */
    private final class Pad extends View {
        private static final float FIELD_W = 900, FIELD_H = 96, FIELD_Y = 240, KEY_W = 96, KEY_H = 76, GAP = 10, KEYS_Y = 400;
        private final RectF r = new RectF();
        private final Path path = new Path();
        private final TextPaint fit = new TextPaint();
        private final Spring in = new Spring(0.32f, 0.9f, 0);
        private Paint fill, stroke, titleP, field, hint, keyText, keyTextOn;
        private float k;
        private long last;

        Pad(Context c) {
            super(c);
            in.target = 1;
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            k = w / 1920f;
            fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(3 * k);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            titleP = Ui.text(Ui.displaySemibold, 54 * k, 0xFFF5F5F7);
            titleP.setTextAlign(Paint.Align.CENTER);
            field = Ui.text(Ui.medium, 42 * k, 0xFFF5F5F7);
            hint = Ui.text(Ui.regular, 24 * k, 0x80FFFFFF);
            hint.setTextAlign(Paint.Align.CENTER);
            keyText = Ui.text(Ui.medium, 32 * k, 0xD9FFFFFF);
            keyText.setTextAlign(Paint.Align.CENTER);
            keyTextOn = Ui.text(Ui.semibold, 34 * k, 0xFFFFFFFF);
            keyTextOn.setTextAlign(Paint.Align.CENTER);
        }

        @Override protected void onDraw(Canvas c) {
            if (fill == null) return;
            long up = SystemClock.uptimeMillis();
            float dt = last == 0 ? 1 / 60f : Math.min(0.05f, (up - last) / 1000f);
            last = up;
            boolean moving = in.step(dt);
            float a = Ui.clamp(in.value), cx = getWidth() / 2f;
            LiveTheme.background(c, getWidth(), getHeight(), a);
            c.save();
            c.translate(0, (1 - a) * 40 * k);
            c.drawText(title, cx, 170 * k, titleP);
            // The field: the name so far, and its blinking caret.
            r.set(cx - FIELD_W / 2 * k, FIELD_Y * k, cx + FIELD_W / 2 * k, (FIELD_Y + FIELD_H) * k);
            fill.setColor(0x1FFFFFFF);
            c.drawRoundRect(r, FIELD_H / 2 * k, FIELD_H / 2 * k, fill);
            String shown = ellipsizeStart(text, field, r.width() - 120 * k);
            float tx = r.left + 52 * k;
            c.drawText(shown, tx, r.centerY() + Ui.halfCap(field), field);
            if ((up / 530) % 2 == 0) {
                float x = tx + field.measureText(shown) + 6 * k;
                fill.setColor(LiveTheme.text);
                c.drawRect(x, r.centerY() - 24 * k, x + 3 * k, r.centerY() + 24 * k, fill);
            }
            postInvalidateDelayed(530);
            drawKeys(c, cx);
            c.drawText(Ui.tr("Os números e letras do controle também digitam", "Your remote's numbers and letters type too"),
                    cx, 1052 * k, hint);
            c.restore();
            if (moving) postInvalidateOnAnimation(); else last = 0;
        }

        private void drawKeys(Canvas c, float cx) {
            String keys = symbols ? SYMBOLS : LETTERS;
            float left = cx - (COLS * KEY_W + (COLS - 1) * GAP) / 2 * k;
            for (int row = 0; row < ROWS; row++) {
                int cols = row < ROWS - 1 ? COLS : BOTTOM_COL.length;
                for (int i = 0; i < cols; i++) {
                    int startCol = row < ROWS - 1 ? i : BOTTOM_COL[i], span = row < ROWS - 1 ? 1 : BOTTOM_SPAN[i];
                    float x = left + startCol * (KEY_W + GAP) * k, y = (KEYS_Y + row * (KEY_H + GAP)) * k;
                    r.set(x, y, x + (span * KEY_W + (span - 1) * GAP) * k, y + KEY_H * k);
                    int index = row < ROWS - 1 ? row * COLS + i : CHARS + i;
                    boolean on = key == index;
                    if (on) r.inset(-4 * k, -4 * k);
                    fill.setColor(on ? LiveTheme.tile : index == DONE_KEY ? 0x33FFFFFF : LiveTheme.surface);
                    c.drawRoundRect(r, 16 * k * LiveTheme.round, 16 * k * LiveTheme.round, fill);
                    int color = on ? LiveTheme.ink : 0xD9FFFFFF;
                    Paint p = on ? keyTextOn : keyText;
                    keyTextOn.setColor(LiveTheme.ink);
                    if (index < CHARS) {
                        String s = keys.substring(index, index + 1);
                        if (caps && !symbols) s = s.toUpperCase(Ui.locale());
                        c.drawText(s, r.centerX(), r.centerY() + Ui.halfCap(p), p);
                    } else if (index == SPACE_KEY) {
                        fill.setColor(color);
                        c.drawRoundRect(r.centerX() - 36 * k, r.centerY() + 6 * k, r.centerX() + 36 * k, r.centerY() + 11 * k, 3 * k, 3 * k, fill);
                    } else if (index == DELETE_KEY) {
                        drawDelete(c, r.centerX(), r.centerY(), color);
                    } else {
                        float ts = p.getTextSize();
                        p.setTextSize(24 * k);
                        String label = index == PAGE_KEY ? (symbols ? "abc" : "&?#") : Ui.tr("Pronto", "Done");
                        c.drawText(label, r.centerX(), r.centerY() + Ui.halfCap(p), p);
                        p.setTextSize(ts);
                    }
                }
            }
        }

        /** ⌫: a key shape with an ×. */
        private void drawDelete(Canvas c, float x, float y, int color) {
            stroke.setColor(color);
            float s = 15 * k;
            path.reset();
            path.moveTo(x - s * 1.4f, y);
            path.lineTo(x - s * 0.6f, y - s);
            path.lineTo(x + s * 1.3f, y - s);
            path.lineTo(x + s * 1.3f, y + s);
            path.lineTo(x - s * 0.6f, y + s);
            path.close();
            c.drawPath(path, stroke);
            c.drawLine(x - s * 0.1f, y - s * 0.4f, x + s * 0.7f, y + s * 0.4f, stroke);
            c.drawLine(x + s * 0.7f, y - s * 0.4f, x - s * 0.1f, y + s * 0.4f, stroke);
        }

        private String ellipsizeStart(String s, Paint p, float width) {
            fit.set(p);
            return TextUtils.ellipsize(s, fit, Math.max(1, width), TextUtils.TruncateAt.START).toString();
        }
    }
}
