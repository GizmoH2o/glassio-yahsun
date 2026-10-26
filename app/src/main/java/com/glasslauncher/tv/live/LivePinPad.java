package com.glasslauncher.tv.live;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

/**
 * The parental PIN, as Apple TV's passcode: a card over the dimmed screen, its title, a dot for each number typed,
 * and a number pad (1 to 9, delete, 0, OK); the remote's numbers type too. Checking the PIN, the right one opens at
 * once; a wrong one shakes the dots and clears them, the card staying for another try. Back cancels.
 */
final class LivePinPad extends Dialog {
    /** What was typed: true when it's done (the card closes), false when it isn't right (it shakes, to try again). */
    interface Done {
        boolean got(String pin);
    }

    private static final String[] KEYS = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "del", "0", "ok"};
    private static final int MAX = 8;
    private final String title, hint;
    private final boolean checkAsTyped;
    private final Done done;
    private final Runnable cancel;
    private final Pad pad;
    private String typed = "";
    private int focus = 0;
    private long shakeAt;
    private boolean finished;

    /**
     * `checkAsTyped`: `done` is tried as soon as there are 4 numbers, and after each one more (checking a PIN);
     * otherwise only on OK (a new PIN).
     */
    static void ask(Context c, String title, String hint, boolean checkAsTyped, Done done, Runnable cancel) {
        new LivePinPad(c, title, hint, checkAsTyped, done, cancel).show();
    }

    /** Numbers that aren't secret (a channel's number): shown as typed, not as dots. */
    static void askNumber(Context c, String title, String hint, Done done) {
        LivePinPad pad = new LivePinPad(c, title, hint, false, done, null);
        pad.plain = true;
        pad.show();
    }

    private boolean plain;

    private LivePinPad(Context c, String title, String hint, boolean checkAsTyped, Done done, Runnable cancel) {
        super(c, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        this.title = title;
        this.hint = hint == null ? "" : hint;
        this.checkAsTyped = checkAsTyped;
        this.done = done;
        this.cancel = cancel;
        pad = new Pad(c);
        setOnCancelListener(d -> {
            if (!finished && this.cancel != null) this.cancel.run();
        });
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
        pad.announceForAccessibility(title + (hint.isEmpty() ? "" : ". " + hint));
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) cancel();
            return true;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
            type((char) ('0' + code - KeyEvent.KEYCODE_0));
        } else if (code >= KeyEvent.KEYCODE_NUMPAD_0 && code <= KeyEvent.KEYCODE_NUMPAD_9) {
            type((char) ('0' + code - KeyEvent.KEYCODE_NUMPAD_0));
        } else if (code == KeyEvent.KEYCODE_DEL) {
            delete();
        } else {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP: if (focus >= 3) focus -= 3; break;
                case KeyEvent.KEYCODE_DPAD_DOWN: if (focus < 9) focus += 3; break;
                case KeyEvent.KEYCODE_DPAD_LEFT: if (focus % 3 > 0) focus--; break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: if (focus % 3 < 2) focus++; break;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (e.getRepeatCount() == 0) press(KEYS[focus]);
                    break;
                case KeyEvent.KEYCODE_HOME:
                    return super.dispatchKeyEvent(e);
                default:
                    break;
            }
        }
        pad.invalidate();
        return true;
    }

    private void press(String key) {
        if (key.equals("del")) delete();
        else if (key.equals("ok")) submit();
        else type(key.charAt(0));
    }

    private void type(char digit) {
        if (typed.length() >= MAX) return;
        typed += digit;
        pad.announceForAccessibility(typed.length() + "");
        if (checkAsTyped && typed.length() >= 4 && done.got(typed)) finish(true);
        pad.invalidate();
    }

    private void delete() {
        if (!typed.isEmpty()) typed = typed.substring(0, typed.length() - 1);
    }

    private void submit() {
        if (typed.isEmpty()) return;
        if (done.got(typed)) {
            finish(true);
            return;
        }
        // Not right: the dots shake and clear, to try again.
        shakeAt = SystemClock.uptimeMillis();
        typed = "";
        pad.announceForAccessibility(Ui.tr("PIN errado", "Wrong PIN"));
        pad.invalidate();
    }

    private void finish(boolean ok) {
        finished = ok;
        dismiss();
    }

    /** The card, drawn by hand (1920 × 1080 space, k = width / 1920). */
    private final class Pad extends View {
        private static final float CARD_W = 640, KEY_W = 168, KEY_H = 100, GAP = 16;
        private final RectF r = new RectF();
        private final Spring in = new Spring(0.32f, 0.9f, 0);
        private Paint fill, ring, titleP, hintP, keyP, keyOn, warn;
        private float k;
        private long last;

        Pad(Context c) {
            super(c);
            in.target = 1;
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            k = w / 1920f;
            fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            ring = new Paint(Paint.ANTI_ALIAS_FLAG);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(3 * k);
            titleP = Ui.text(Ui.displaySemibold, 40 * k, 0xFFF5F5F7);
            titleP.setTextAlign(Paint.Align.CENTER);
            hintP = Ui.text(Ui.regular, 25 * k, 0x99FFFFFF);
            hintP.setTextAlign(Paint.Align.CENTER);
            keyP = Ui.numbers(Ui.medium, 40 * k, 0xE6FFFFFF);
            keyP.setTextAlign(Paint.Align.CENTER);
            keyOn = Ui.numbers(Ui.semibold, 42 * k, 0xFFFFFFFF);
            keyOn.setTextAlign(Paint.Align.CENTER);
            warn = Ui.text(Ui.medium, 25 * k, 0xFFFF6B5E);
            warn.setTextAlign(Paint.Align.CENTER);
        }

        @Override protected void onDraw(Canvas c) {
            if (fill == null) return;
            long now = SystemClock.uptimeMillis();
            float dt = last == 0 ? 1 / 60f : Math.min(0.05f, (now - last) / 1000f);
            last = now;
            boolean moving = in.step(dt);
            float a = Ui.clamp(in.value), w = getWidth(), h = getHeight(), cx = w / 2;
            c.drawColor(Ui.alpha(0xB3000000, a));
            float cardH = 860 * k, top = (h - cardH) / 2 + (1 - a) * 40 * k;
            r.set(cx - CARD_W / 2 * k, top, cx + CARD_W / 2 * k, top + cardH);
            fill.setColor(Ui.alpha(LiveTheme.panel, a));      // solid: what's behind doesn't show through
            c.drawRoundRect(r, LiveTheme.panelRound(36 * k), LiveTheme.panelRound(36 * k), fill);
            Symbols.draw(getContext(), c, plain ? "list" : "lock", cx, top + 70 * k, 40 * k, 0xCCFFFFFF);
            c.drawText(title, cx, top + 150 * k, titleP);
            if (!hint.isEmpty()) c.drawText(hint, cx, top + 192 * k, hintP);
            // The dots: one for each number typed (at least four places), shaking a moment after a wrong one.
            long since = now - shakeAt;
            float shake = since < 450 ? (float) Math.sin(since / 25.0) * 18 * k * (1 - since / 450f) : 0;
            if (since < 450) moving = true;
            int places = plain ? 0 : Math.max(4, typed.length() + (typed.length() < MAX && typed.length() >= 4 ? 1 : 0));
            float dotGap = 44 * k, dy = top + 262 * k, dx0 = cx - (places - 1) * dotGap / 2 + shake;
            if (plain) c.drawText(typed.isEmpty() ? "—" : typed, cx, dy + Ui.halfCap(titleP), titleP);     // the number itself
            for (int i = 0; i < places; i++) {
                if (i < typed.length()) {
                    fill.setColor(0xFFF5F5F7);
                    c.drawCircle(dx0 + i * dotGap, dy, 11 * k, fill);
                } else {
                    ring.setColor(0x66FFFFFF);
                    c.drawCircle(dx0 + i * dotGap, dy, 10 * k, ring);
                }
            }
            if (since < 1800 && shakeAt > 0) c.drawText(Ui.tr("PIN errado", "Wrong PIN"), cx, dy + 56 * k, warn);
            // The pad: three across, four down.
            float gx = cx - (3 * KEY_W + 2 * GAP) / 2 * k, gy = top + 360 * k;
            for (int i = 0; i < KEYS.length; i++) {
                float x = gx + (i % 3) * (KEY_W + GAP) * k, y = gy + (i / 3) * (KEY_H + GAP) * k;
                r.set(x, y, x + KEY_W * k, y + KEY_H * k);
                boolean on = i == focus;
                if (on) r.inset(-4 * k, -4 * k);
                fill.setColor(on ? LiveTheme.tile : LiveTheme.surface);
                c.drawRoundRect(r, 20 * k * LiveTheme.round, 20 * k * LiveTheme.round, fill);
                String key = KEYS[i];
                int color = on ? LiveTheme.ink : 0xD9FFFFFF;
                if (key.equals("del")) {
                    drawDelete(c, r.centerX(), r.centerY(), color);
                } else if (key.equals("ok")) {
                    Symbols.draw(getContext(), c, "check", r.centerX(), r.centerY(), 36 * k, color);
                } else {
                    Paint p = on ? keyOn : keyP;
                    keyOn.setColor(LiveTheme.ink);
                    c.drawText(key, r.centerX(), r.centerY() + Ui.halfCap(p), p);
                }
            }
            if (moving) postInvalidateOnAnimation(); else last = 0;
        }

        /** ⌫: a key shape with an ×. */
        private void drawDelete(Canvas c, float x, float y, int color) {
            ring.setColor(color);
            float s = 20 * k;
            android.graphics.Path p = pathDel;
            p.reset();
            p.moveTo(x - s * 1.4f, y);
            p.lineTo(x - s * 0.6f, y - s);
            p.lineTo(x + s * 1.3f, y - s);
            p.lineTo(x + s * 1.3f, y + s);
            p.lineTo(x - s * 0.6f, y + s);
            p.close();
            c.drawPath(p, ring);
            c.drawLine(x - s * 0.1f, y - s * 0.4f, x + s * 0.7f, y + s * 0.4f, ring);
            c.drawLine(x + s * 0.7f, y - s * 0.4f, x - s * 0.1f, y + s * 0.4f, ring);
        }

        private final android.graphics.Path pathDel = new android.graphics.Path();
    }
}
