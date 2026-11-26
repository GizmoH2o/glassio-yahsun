package com.glasslauncher.tv.live;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * The TV was turned off while something records (RecordOff): a card (Black screen, Turn off anyway, Keep watching; the
 * first by itself after 8 seconds), then black, at the lowest light and silent, until the recordings are over: the TV
 * goes to sleep then. Any key shows what's recording for a moment, OK or Back the card. Home leaves it.
 */
public class RecordOffActivity extends Activity {
    private static final long ASK_MS = 8_000, LINE_MS = 10_000, TICK_MS = 1_000;

    private Screen view;
    private boolean black, sleeping;
    private int focus;
    private long askUntil, lineUntil, lastCheck;
    private LiveRecordings.Rec rec;
    private long next;
    private AudioFocusRequest silence;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            view.postDelayed(this, TICK_MS);
            step();
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        LiveTheme.load(this);
        if (Build.VERSION.SDK_INT >= 27) {
            setTurnScreenOn(true);
            setShowWhenLocked(true);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new Screen(this);
        setContentView(view);
        RecordOff.shown = this;
        show(getIntent());
        view.post(tick);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        show(intent);
    }

    private void show(Intent intent) {
        RecordOff.touch(this);
        if (intent.getBooleanExtra(RecordOff.BLACK, false)) black();
        else ask();
    }

    /** The question, counting down to black. */
    private void ask() {
        black = false;
        focus = 0;
        askUntil = SystemClock.uptimeMillis() + ASK_MS;
        light(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE);
        hush(false);
        check();
        view.invalidate();
        LiveSpeech.say(view, line() + ". " + name(0));
    }

    private void black() {
        black = true;
        lineUntil = SystemClock.uptimeMillis() + LINE_MS;
        light(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF);
        hush(true);
        check();
        view.invalidate();
        AppLog.w("live: black screen while recording");
    }

    private void light(float level) {
        WindowManager.LayoutParams p = getWindow().getAttributes();
        p.screenBrightness = level;
        getWindow().setAttributes(p);
    }

    /** Whatever else plays, quiet while it's black. */
    private void hush(boolean on) {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (on && silence == null) {
            silence = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).build();
            am.requestAudioFocus(silence);
        } else if (!on && silence != null) {
            am.abandonAudioFocusRequest(silence);
            silence = null;
        }
    }

    /** What's recording and what's next, read now. */
    private void check() {
        lastCheck = SystemClock.uptimeMillis();
        rec = RecordOff.recording(this);
        next = rec == null ? RecordOff.next(this) : 0;
    }

    private void step() {
        if (sleeping || isFinishing()) return;
        long now = SystemClock.uptimeMillis();
        RecordOff.touch(this);
        if (!getSystemService(PowerManager.class).isInteractive()) {     // not on yet: its seconds start when it is
            askUntil = now + ASK_MS;
            lineUntil = now + LINE_MS;
            return;
        }
        if (now - lastCheck >= 5_000) {
            check();
            if (rec == null && !RecordOff.waits(next, System.currentTimeMillis())) {
                if (black) off();
                else finish();      // over while asked: the TV is on and someone may be there
                return;
            }
        }
        if (!black) {
            if (now >= askUntil) black();
            else view.invalidate();      // the seconds
        }
    }

    /** Off, for real: the TV's standby. */
    private void off() {
        if (sleeping) return;
        sleeping = true;
        RecordOff.letGo = true;
        view.invalidate();
        AppLog.w("live: the TV to sleep, " + (rec == null ? "the recording is over" : "as asked"));
        RecordOff.sleep(this, () -> {       // couldn't: the TV's own settings turn it off
            RecordOff.letGo = false;
            finish();
        });
    }

    /** The screen went off: Power again, or ours. */
    void gone() {
        finish();
    }

    private void choose() {
        if (focus == 0) black();
        else if (focus == 1) off();
        else finish();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE) return super.dispatchKeyEvent(e);
        if (e.getAction() != KeyEvent.ACTION_DOWN || sleeping || e.getRepeatCount() > 0) return true;
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (black) {
            if (ok || code == KeyEvent.KEYCODE_BACK) ask();
            else lineUntil = SystemClock.uptimeMillis() + LINE_MS;
            view.invalidate();
            return true;
        }
        askUntil = SystemClock.uptimeMillis() + ASK_MS;      // still choosing
        if (ok) choose();
        else if (code == KeyEvent.KEYCODE_BACK) finish();
        else if (code == KeyEvent.KEYCODE_DPAD_LEFT) focus = Math.max(0, focus - 1);
        else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) focus = Math.min(2, focus + 1);
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) LiveSpeech.say(view, name(focus));
        view.invalidate();
        return true;
    }

    /** Home: whoever pressed it is watching again. */
    @Override protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (!sleeping) finish();
    }

    @Override public void finish() {
        done();
        super.finish();
        overridePendingTransition(0, 0);
    }

    @Override protected void onDestroy() {
        done();
        super.onDestroy();
    }

    private void done() {
        view.removeCallbacks(tick);
        hush(false);
        if (RecordOff.shown == this) {
            RecordOff.shown = null;
            RecordOff.untouch(this);
        }
    }

    private static String name(int i) {
        return i == 0 ? Ui.tr("Tela preta", "Black screen") : i == 1 ? Ui.tr("Desligar mesmo assim", "Turn off anyway")
                : Ui.tr("Continuar assistindo", "Keep watching");
    }

    private String line() {
        return rec != null && rec.fetch ? Ui.tr("Da reprise: ", "From catch-up: ") + LiveStreams.name(rec) : rec != null ? Ui.tr("Gravando até ", "Recording until ") + Ui.time(rec.end)
                : next > 0 ? Ui.tr("Próxima gravação: ", "Next recording: ") + Ui.time(next) : Ui.tr("Gravando", "Recording");
    }

    /** Drawn by hand (1920 × 1080 space). */
    private final class Screen extends View {
        private final RectF r = new RectF();
        private final TextPaint fit = new TextPaint();
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Paint title, meta, button, buttonOn, faint;
        private float k;

        Screen(Context c) {
            super(c);
            setBackgroundColor(Color.BLACK);
            setFocusable(true);
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            k = w / 1920f;
            title = Ui.text(Ui.displaySemibold, 46 * k, 0xFFF5F5F7);
            meta = Ui.text(Ui.medium, 27 * k, 0xB3FFFFFF);
            button = Ui.text(Ui.semibold, 27 * k, 0xFFF5F5F7);
            button.setTextAlign(Paint.Align.CENTER);
            buttonOn = Ui.text(Ui.semibold, 27 * k, LiveTheme.ink);
            buttonOn.setTextAlign(Paint.Align.CENTER);
            faint = Ui.text(Ui.medium, 25 * k, 0xFF5F5E5A);
            faint.setTextAlign(Paint.Align.RIGHT);
        }

        @Override protected void onDraw(Canvas c) {
            if (sleeping) return;
            if (black) {
                long left = lineUntil - SystemClock.uptimeMillis();
                if (left <= 0) return;
                float a = Math.min(1, left / 1500f), x = getWidth() - 80 * k, y = getHeight() - 70 * k;
                String s = line();
                faint.setAlpha(Math.round(255 * a));
                c.drawText(s, x, y, faint);
                fill.setColor(0xFF791F1F);
                fill.setAlpha(Math.round(255 * a));
                c.drawCircle(x - faint.measureText(s) - 24 * k, y - 9 * k, 8 * k, fill);
                postInvalidateDelayed(left > 1500 ? left - 1500 : 50);
                return;
            }
            float round = 28 * k * LiveTheme.round, left = 80 * k, right = getWidth() - 80 * k, bottom = getHeight() - 70 * k;
            r.set(left, bottom - 300 * k, right, bottom);
            fill.setColor(0xFF1E1E22);
            c.drawRoundRect(r, round, round, fill);
            float x = left + 48 * k, w = r.width() - 96 * k, y = r.top;
            c.drawText(cut(line(), title, w), x, y + 76 * k, title);
            String on = rec == null ? "" : LiveCountry.named(getContext(), rec.ch);
            String show = rec == null || rec.title.isEmpty() ? on : rec.title + "  ·  " + on;
            c.drawText(cut(show, meta, w), x, y + 122 * k, meta);
            c.drawText(cut(Ui.tr("A TV fica ligada com a tela preta para terminar, depois desliga.",
                    "The TV stays on with a black screen to finish it, then turns off."), meta, w), x, y + 162 * k, meta);
            long s = Math.max(1, (askUntil - SystemClock.uptimeMillis() + 999) / 1000);
            for (int i = 0; i < 3; i++) {
                String t = i == 0 ? name(0) + "  ·  " + s : name(i);
                float bw = button.measureText(i == 0 ? name(0) + "  ·  8" : t) + 64 * k;
                r.set(x, bottom - 108 * k, x + bw, bottom - 40 * k);
                fill.setColor(i == focus ? LiveTheme.tile : 0x2EFFFFFF);
                c.drawRoundRect(r, 34 * k, 34 * k, fill);
                Paint p = i == focus ? buttonOn : button;
                c.drawText(t, r.centerX(), r.centerY() + Ui.halfCap(p), p);
                x += bw + 20 * k;
            }
        }

        private String cut(String s, Paint p, float w) {
            fit.set(p);
            return TextUtils.ellipsize(s, fit, Math.max(1, w), TextUtils.TruncateAt.END).toString();
        }
    }
}
