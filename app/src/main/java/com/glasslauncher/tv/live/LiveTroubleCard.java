package com.glasslauncher.tv.live;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * A channel that keeps stopping: what can be done about it, over the video. Only what there is for that channel; nothing
 * happens by itself. ◀ ▶ go between the choices, OK takes one, Back (or waiting) stays.
 */
final class LiveTroubleCard {
    /** This many stops within this long brings it. */
    static final int TIMES = 3;
    static final long WITHIN_MS = 5 * 60_000L;
    /** Stopping again this soon after is the same stop (its tries). */
    static final long SAME_MS = 8000;
    /** Put away, it isn't offered again on that channel for this long. */
    static final long SNOOZE_MS = 10 * 60_000L;
    private static final long SHOWN_MS = 20_000, GUARD_MS = 700;

    static final class Choice {
        final String text;
        final Runnable act;

        Choice(String text, Runnable act) {
            this.text = text;
            this.act = act;
        }
    }

    private final LiveView view;
    private final RectF r = new RectF();
    private final TextPaint fit = new TextPaint();
    private Paint head, line, button, fill;
    private String title = "", note = "";
    private final List<Choice> choices = new ArrayList<>();
    private int focus, channel;
    private long shownAt;
    private boolean showing, pressed;
    private Runnable away;
    private final Runnable late = this::back;

    LiveTroubleCard(LiveView view) {
        this.view = view;
    }

    /** `times` with one more stop at `now`: those still counting; the same list when it's the last one over again. Pure. */
    static List<Long> stop(List<Long> times, long now) {
        if (!times.isEmpty() && now - times.get(times.size() - 1) < SAME_MS) return times;
        List<Long> out = new ArrayList<>();
        for (long t : times) if (now - t < WITHIN_MS) out.add(t);
        out.add(now);
        return out;
    }

    /** `away`: put away without a choice. */
    void show(int channel, String title, String note, List<Choice> list, Runnable away) {
        this.channel = channel;
        this.title = title;
        this.note = note;
        this.away = away;
        choices.clear();
        choices.addAll(list);
        choices.add(new Choice(Ui.tr("Ficar aqui", "Stay here"), null));
        focus = 0;
        pressed = false;
        showing = true;
        shownAt = SystemClock.uptimeMillis();
        stay();
        LiveSpeech.say(view, title + ", " + choices.get(0).text);
        view.invalidate();
    }

    boolean showing() {
        return showing;
    }

    /** What's playing: another channel (or a past show) and it goes. */
    void on(Xtream.Channel ch, boolean live) {
        if (showing && (ch == null || ch.id != channel || !live)) hide();
    }

    private void stay() {
        view.removeCallbacks(late);
        view.postDelayed(late, SHOWN_MS);
    }

    private void hide() {
        showing = false;
        view.removeCallbacks(late);
        view.invalidate();
    }

    void back() {
        if (!showing) return;
        hide();
        if (away != null) away.run();
    }

    /** A key; `clear`: it's on screen, nothing over it. False for the ones that aren't its own. */
    boolean key(KeyEvent e, boolean clear) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN, ok = code == KeyEvent.KEYCODE_DPAD_CENTER
                || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (ok && pressed) {
            if (!down) pressed = false;
            return true;
        }
        if (!showing || !clear) return false;
        if (ok) {
            if (!down) return false;        // pressed before it came: what it was for ends there
            if (e.getRepeatCount() > 0) return true;
            pressed = true;
            if (SystemClock.uptimeMillis() - shownAt < GUARD_MS) return true;     // meant for what was there before
            Choice c = choices.get(focus);
            if (c.act == null) {
                back();
            } else {
                hide();
                c.act.run();
            }
            return true;
        }
        if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
        if (down) {
            int to = Math.max(0, Math.min(choices.size() - 1, focus + (code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1)));
            if (to != focus) LiveSpeech.say(view, choices.get(to).text);
            focus = to;
            stay();
            view.invalidate();
        }
        return true;
    }

    void draw(Canvas c, float k) {
        if (!showing) return;
        if (head == null) {
            head = Ui.text(Ui.semibold, 34 * k, 0xFFFFFFFF);
            line = Ui.text(Ui.medium, 25 * k, 0xFFD2D2D7);
            button = Ui.text(Ui.semibold, 26 * k, 0xFFFFFFFF);
            fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        }
        float pad = 36 * k, gap = 12 * k, most = 1560 * k, buttons = -gap;
        // Long names (a copy's, the match's channel) give way so all the buttons are there.
        float each = (most - (choices.size() - 1) * gap) / choices.size() - 56 * k;
        String[] texts = new String[choices.size()];
        for (int i = 0; i < texts.length; i++) {
            texts[i] = ellipsize(choices.get(i).text, button, each);
            buttons += button.measureText(texts[i]) + 56 * k + gap;
        }
        float room = Math.min(most, Math.max(buttons, head.measureText(title)));
        float wd = room + 2 * pad, ht = 226 * k, x = (1920 * k - wd) / 2, y = (1080 - 72) * k - ht;
        r.set(x, y, x + wd, y + ht);
        float rr = LiveTheme.panelRound(24 * k);
        fill.setColor((LiveTheme.card & 0x00FFFFFF) | 0xF5000000);
        c.drawRoundRect(r, rr, rr, fill);
        LiveTheme.rim(c, r, rr, 1.5f * k);
        c.drawText(ellipsize(title, head, room), x + pad, y + 58 * k, head);
        c.drawText(ellipsize(note, line, room), x + pad, y + 98 * k, line);
        float bx = x + pad, round = LiveTheme.panelRound(14 * k);
        for (int i = 0; i < texts.length; i++) {
            float bw = button.measureText(texts[i]) + 56 * k;
            r.set(bx, y + 128 * k, bx + bw, y + 190 * k);
            boolean on = i == focus;
            fill.setColor(on ? LiveTheme.tile : 0x2EFFFFFF);
            c.drawRoundRect(r, round, round, fill);
            button.setColor(on ? LiveTheme.ink : 0xFFFFFFFF);
            c.drawText(texts[i], bx + 28 * k, r.centerY() + Ui.halfCap(button), button);
            bx += bw + gap;
        }
    }

    private String ellipsize(String s, Paint p, float width) {
        fit.set(p);
        return TextUtils.ellipsize(s, fit, Math.max(1, width), TextUtils.TruncateAt.END).toString();
    }
}
