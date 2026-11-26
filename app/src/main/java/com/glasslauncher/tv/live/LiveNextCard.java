package com.glasslauncher.tv.live;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;

/**
 * What plays after a past show or a recording, in a small card in the corner: it counts the last seconds down, so the
 * next show starts as this one finishes. ◀ ▶ go between its two buttons, OK presses one, Back is Not now. While the
 * player's bar is in use the card only shows, out of its way.
 */
final class LiveNextCard {
    private static final float W = 520, H = 214, EDGE = 96;

    private final LiveView view;
    private final RectF r = new RectF();
    private Paint head, name, button, fill;
    private final TextPaint fit = new TextPaint();
    private String title = "", label = "";
    private Runnable play, refuse;
    private int left, focus;
    private boolean showing, pressed;

    LiveNextCard(LiveView view) {
        this.view = view;
    }

    void show(String title, String label, int left, Runnable play, Runnable refuse) {
        this.title = title;
        this.label = label;
        this.left = left;
        this.play = play;
        this.refuse = refuse;
        headline = no = null;
        focus = 0;
        pressed = false;
        showing = true;
        LiveSpeech.say(view, Ui.tr("A seguir: ", "Up next: ") + title);
        view.invalidate();
    }

    private String headline, no;

    /** Another question than what's next: its own line (the seconds after it), any time, with its own second button. */
    void ask(String headline, String title, String label, String no, int left, Runnable play, Runnable refuse) {
        show(title, label, left, play, refuse);
        this.headline = headline;
        this.no = no;
        LiveSpeech.say(view, headline + left + ". " + title);
        view.invalidate();
    }

    /** One of those is up: it stays, live too. */
    boolean any() {
        return showing && headline != null;
    }

    private String refusal() {
        return no == null ? Ui.tr("Agora não", "Not now") : no;
    }

    void left(int seconds) {
        if (!showing || seconds == left) return;
        left = seconds;
        view.invalidate();
    }

    void hide() {
        if (!showing) return;
        showing = false;
        view.invalidate();
    }

    boolean showing() {
        return showing;
    }

    /** Back on it: not now. */
    void back() {
        act(false);
    }

    private void act(boolean yes) {
        Runnable run = yes ? play : refuse;
        hide();
        if (run != null) run.run();
    }

    /** A key while it shows; false for the ones that aren't its own. A press that began on it ends on it. */
    boolean key(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN, ok = code == KeyEvent.KEYCODE_DPAD_CENTER
                || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (ok && pressed) {
            if (!down) pressed = false;
            return true;
        }
        if (!showing) return false;
        if (ok) {
            if (down && e.getRepeatCount() == 0) {
                pressed = true;
                act(focus == 0);
            }
            return true;
        }
        if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
        if (down) {
            focus = code == KeyEvent.KEYCODE_DPAD_LEFT ? 0 : 1;
            LiveSpeech.say(view, focus == 0 ? label : refusal());
            view.invalidate();
        }
        return true;
    }

    /** `high`: the player's bar is up, so at the top instead. */
    void draw(Canvas c, float k, boolean high) {
        if (!showing) return;
        if (head == null) {
            head = Ui.text(Ui.medium, 24 * k, 0xFFD2D2D7);
            name = Ui.text(Ui.semibold, 32 * k, 0xFFFFFFFF);
            button = Ui.text(Ui.semibold, 26 * k, 0xFFFFFFFF);
            fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        }
        float x = (1920 - EDGE - W) * k, y = (high ? 72 : 1080 - 72 - H) * k, pad = 30 * k, rr = LiveTheme.panelRound(24 * k);
        r.set(x, y, x + W * k, y + H * k);
        fill.setColor((LiveTheme.card & 0x00FFFFFF) | 0xF5000000);
        c.drawRoundRect(r, rr, rr, fill);
        LiveTheme.rim(c, r, rr, 1.5f * k);
        c.drawText(headline == null ? Ui.tr("A seguir em ", "Up next in ") + left : headline + left + " s", x + pad, y + 50 * k, head);
        fit.set(name);
        c.drawText(TextUtils.ellipsize(title, fit, W * k - 2 * pad, TextUtils.TruncateAt.END).toString(), x + pad, y + 98 * k, name);
        float bx = x + pad;
        for (int i = 0; i < 2; i++) {
            String t = i == 0 ? label : refusal();
            float bw = button.measureText(t) + 48 * k, round = LiveTheme.panelRound(14 * k);
            r.set(bx, y + 126 * k, bx + bw, y + 184 * k);
            boolean on = !high && i == focus;
            fill.setColor(on ? LiveTheme.tile : 0x2EFFFFFF);
            c.drawRoundRect(r, round, round, fill);
            button.setColor(on ? LiveTheme.ink : 0xFFFFFFFF);
            c.drawText(t, bx + 24 * k, r.centerY() + Ui.halfCap(button), button);
            bx += bw + 12 * k;
        }
    }
}
