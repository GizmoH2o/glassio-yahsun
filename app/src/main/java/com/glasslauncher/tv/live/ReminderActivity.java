package com.glasslauncher.tv.live;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * A reminder, over whatever is on (another app too): a card at the bottom right sliding in, the channel's logo, when
 * it starts, the show and the channel, and Watch (focused) or Dismiss. It goes by itself after half a minute; Watch
 * opens Live TV on the channel. The window around it is see-through, so what's playing goes on behind.
 * Switching by itself (LiveOptions.remindAuto): the card comes again just before the start, counts down and switches,
 * unless dismissed (Dismiss on the first card cancels that too).
 * A match alert (a team followed) is a panel at the right instead: every channel showing the match now (the full
 * guide's), to pick one; it goes by itself after a minute and never switches by itself.
 */
public class ReminderActivity extends Activity {
    private static final String EXTRA_ITEM = "com.glasslauncher.tv.live.extra.REMINDER";
    private static final long STAYS_MS = 30_000, PANEL_STAYS_MS = 60_000;
    /** How long before the start the switching card counts down. */
    static final long COUNTDOWN_MS = 10_000;

    private LiveSchedule.Item item;
    private Card card;
    private Panel panel;
    /** Counting down to switching (else the card leaves by itself). */
    private boolean countdown;
    private final Runnable timeUp = () -> {
        if (countdown) watch();
        else finish();
    };

    static Intent intent(Context c, LiveSchedule.Item it) {
        Intent i = new Intent(c, ReminderActivity.class);
        try {
            i.putExtra(EXTRA_ITEM, it.json().toString());
        } catch (JSONException ignored) {
        }
        return i;
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        LiveTheme.load(this);
        show(getIntent());
    }

    /** Another reminder while this one is up: it takes the card. */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        show(intent);
    }

    private void show(Intent intent) {
        LiveSchedule.Item it;
        try {
            it = LiveSchedule.Item.of(new JSONObject(intent.getStringExtra(EXTRA_ITEM)));
        } catch (JSONException | NullPointerException e) {
            it = null;
        }
        if (it == null) {
            if (item == null) finish();
            return;
        }
        // Already watching that channel in Live TV: nothing to remind of (unless asked to, in Settings).
        if (!LiveOptions.remindWatching(this) && Live.watching(this, it.ch.id)) {
            AppLog.w("live: reminder skipped, that channel is on");
            if (item == null) finish();
            return;
        }
        if (LiveFollow.isAlert(it) && !LiveOptions.matchCard(this)) {
            showPanel(it);
            return;
        }
        if (panel != null) return;      // a match's panel is up: it stays
        item = it;
        long left = item.start - System.currentTimeMillis();
        // A match alert (something followed) always asks: it mustn't cut into what's on by itself.
        boolean auto = LiveOptions.remindAuto(this) && !LiveFollow.isAlert(item);
        countdown = auto && left <= COUNTDOWN_MS + 5_000;
        // Ahead of time, switching by itself: the switch comes as the show starts (Dismiss takes it out).
        if (auto && !countdown) LiveSchedule.add(this, item.as(LiveSchedule.SWITCH));
        if (card == null) {
            card = new Card(this);
            setContentView(card);
        }
        card.focusWatch = true;
        card.removeCallbacks(timeUp);
        card.postDelayed(timeUp, countdown ? Math.max(0, left) : STAYS_MS);
        card.invalidate();
        card.announceForAccessibility((countdown ? Ui.tr("Mudando para ", "Switching to ") + item.ch.name + ": "
                : Ui.tr("Lembrete: ", "Reminder: ")) + item.title + ", " + item.ch.name);
    }

    /** A match alert: the panel, its own channel at once, the others as the full guide finds them. */
    private void showPanel(LiveSchedule.Item it) {
        // The same match again (another channel's alert for it): the panel already has it.
        if (panel != null && item != null && it.series.equals(item.series) && Math.abs(it.start - item.start) < 15 * 60_000L) return;
        item = it;
        if (panel == null) {
            panel = new Panel(this);
            setContentView(panel);
        }
        final Panel p = panel;
        p.set(it);
        if (LiveOptions.scores(this)) {
            LiveScores.get(this).watch(p);
            LiveScores.get(this).want(it.series.substring(LiveFollow.SERIES.length()));
        }
        p.removeCallbacks(timeUp);
        p.postDelayed(timeUp, PANEL_STAYS_MS);
        p.announceForAccessibility(Ui.tr("Ao vivo agora: ", "On now: ") + LiveFollow.named(this, it.series.substring(LiveFollow.SERIES.length())));
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            java.util.List<Object[]> found = LiveFollow.airing(app, it);
            p.post(() -> {
                if (item != it) return;
                // Already watching one of them: nothing to point to.
                for (Object[] row : LiveOptions.remindWatching(app) ? new java.util.ArrayList<Object[]>() : found) {
                    if (Live.watching(app, ((Xtream.Channel) row[0]).id)) {
                        AppLog.w("live: match panel skipped, it's on");
                        finish();
                        return;
                    }
                }
                p.found(found);
            });
        }, "match-panel").start();
    }

    /** Dismissed: no switching later either. */
    private void dismiss() {
        LiveSchedule.remove(this, item.as(LiveSchedule.SWITCH).key());
        finish();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (panel != null) return panel.key(e) || super.dispatchKeyEvent(e);
        if (card == null) return super.dispatchKeyEvent(e);
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                card.focusWatch = !card.focusWatch;
                card.invalidate();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (card.focusWatch) watch(); else dismiss();
                return true;
            case KeyEvent.KEYCODE_BACK:
                dismiss();
                return true;
            default:
                return super.dispatchKeyEvent(e);    // Home and the rest: as usual
        }
    }

    private void watch() {
        watch(item.ch);
    }

    private void watch(Xtream.Channel ch) {
        LiveSchedule.remove(this, item.as(LiveSchedule.SWITCH).key());
        try {
            startActivity(HomeApps.liveIntent(this, ch.id, false));
        } catch (RuntimeException e) {
            AppLog.w("live: couldn't open the reminded channel: " + e.getMessage());
        }
        finish();
    }

    @Override public void finish() {
        if (card != null) card.removeCallbacks(timeUp);
        if (panel != null) {
            panel.removeCallbacks(timeUp);
            LiveScores.get(this).unwatch(panel);
        }
        super.finish();
        overridePendingTransition(0, android.R.anim.fade_out);
    }

    /**
     * A match alert's panel at the right (1920 × 1080 space): ON NOW, the team, the match, then a row per channel showing
     * it (logo, number and name, its title there), the focus in Live TV's theme; ↑ ↓ choose, OK watches, Back closes.
     */
    private final class Panel extends View implements LiveScores.Listener {
        private static final float W = 720, MARGIN = 48, ROW_H = 104, GAP = 8;
        private final Spring in = new Spring(0.4f, 1f, 0);
        private final RectF r = new RectF(), b = new RectF();
        private final TextPaint fit = new TextPaint();
        private final java.util.List<Object[]> rows = new java.util.ArrayList<>();
        private Paint fill, glow, small, title, meta, rowName, rowMeta, hint;
        private float k;
        private long last;
        private int focus, scroll;
        private boolean loading = true;
        private String team = "";

        Panel(Context c) {
            super(c);
            in.target = 1;
        }

        void set(LiveSchedule.Item it) {
            team = LiveFollow.named(getContext(), it.series.substring(LiveFollow.SERIES.length()));
            rows.clear();
            Xtream.Program p = new Xtream.Program();
            p.title = it.title;
            p.start = it.start;
            p.end = it.end;
            rows.add(new Object[] {it.ch, p});
            focus = scroll = 0;
            loading = true;
            invalidate();
        }

        @Override public void changed() {
            invalidate();
        }

        @Override public void goal(LiveScores.Score s, boolean forHome) {
            invalidate();
        }

        void found(java.util.List<Object[]> list) {
            if (!list.isEmpty()) {
                rows.clear();
                rows.addAll(list);
            }
            focus = Math.min(focus, rows.size() - 1);
            loading = false;
            invalidate();
        }

        boolean key(KeyEvent e) {
            int code = e.getKeyCode();
            if (code != KeyEvent.KEYCODE_DPAD_UP && code != KeyEvent.KEYCODE_DPAD_DOWN && code != KeyEvent.KEYCODE_DPAD_CENTER
                    && code != KeyEvent.KEYCODE_ENTER && code != KeyEvent.KEYCODE_NUMPAD_ENTER && code != KeyEvent.KEYCODE_BACK
                    && code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) {
                return false;      // Home and the rest: as usual
            }
            if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
            removeCallbacks(timeUp);
            postDelayed(timeUp, PANEL_STAYS_MS);       // still choosing: it stays
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN: {
                    int next = Math.max(0, Math.min(rows.size() - 1, focus + (code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1)));
                    if (next != focus) {
                        focus = next;
                        Xtream.Channel ch = (Xtream.Channel) rows.get(focus)[0];
                        announceForAccessibility((ch.num > 0 ? ch.num + " " : "") + ch.name);
                        invalidate();
                    }
                    return true;
                }
                case KeyEvent.KEYCODE_BACK:
                    finish();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    return true;
                default:
                    watch((Xtream.Channel) rows.get(focus)[0]);
                    return true;
            }
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            k = w / 1920f;
            fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            glow = new Paint(Paint.ANTI_ALIAS_FLAG);
            glow.setShadowLayer(40 * k, 0, 14 * k, 0x99000000);
            small = Ui.text(Ui.semibold, 22 * k, LiveTheme.text);
            title = Ui.text(Ui.displaySemibold, 44 * k, 0xFFF5F5F7);
            meta = Ui.text(Ui.medium, 24 * k, 0xB3FFFFFF);
            rowName = Ui.text(Ui.semibold, 27 * k, 0xFFF5F5F7);
            rowMeta = Ui.text(Ui.medium, 21 * k, 0xB3FFFFFF);
            hint = Ui.text(Ui.medium, 21 * k, 0x8CFFFFFF);
        }

        @Override protected void onDraw(Canvas c) {
            long now = SystemClock.uptimeMillis();
            float dt = last == 0 ? 1 / 60f : Math.min(0.05f, (now - last) / 1000f);
            last = now;
            boolean moving = in.step(dt);
            float a = Ui.clamp(in.value);
            float right = getWidth() - MARGIN * k, left = right - W * k, top = MARGIN * k, bottom = getHeight() - MARGIN * k;
            c.translate((1 - a) * 120 * k, 0);
            r.set(left, top, right, bottom);
            glow.setColor(Ui.alpha(0xF2161618, a));
            c.drawRoundRect(r, 28 * k * LiveTheme.round, 28 * k * LiveTheme.round, glow);
            float x = left + 36 * k, tw = W * k - 72 * k;
            c.drawText(Ui.tr("AO VIVO AGORA", "ON NOW"), x, top + 62 * k, small);
            LiveScores.Score score = item == null ? null : LiveScores.get(getContext()).forTerm(item.series.substring(LiveFollow.SERIES.length()));
            if (score != null) {        // the score, at the line's end
                String line = score.line();
                c.drawText(line, right - 36 * k - small.measureText(line), top + 62 * k, small);
            }
            c.drawText(ellipsize(team, title, tw), x, top + 118 * k, title);
            c.drawText(ellipsize(item.title, meta, tw), x, top + 158 * k, meta);
            // The channels, the focused one kept in view.
            float rowsTop = top + 196 * k, rowsBottom = bottom - 76 * k, step = (ROW_H + GAP) * k;
            int fits = Math.max(1, (int) ((rowsBottom - rowsTop + GAP * k) / step));
            if (focus < scroll) scroll = focus;
            if (focus >= scroll + fits) scroll = focus - fits + 1;
            for (int i = scroll; i < rows.size() && i < scroll + fits; i++) {
                Xtream.Channel ch = (Xtream.Channel) rows.get(i)[0];
                Xtream.Program p = (Xtream.Program) rows.get(i)[1];
                boolean on = i == focus;
                float y = rowsTop + (i - scroll) * step;
                b.set(left + 20 * k, y, right - 20 * k, y + ROW_H * k);
                fill.setColor(on ? LiveTheme.tile : 0x14FFFFFF);
                fill.setAlpha(Math.round(Color.alpha(fill.getColor()) * a));
                float round = 18 * k * LiveTheme.round;
                c.drawRoundRect(b, round, round, fill);
                Bitmap logo = LiveLogos.get(getContext(), ch.logo, this::postInvalidate);
                float lw = 120 * k, lh = 64 * k, lx = b.left + 20 * k, ly = b.centerY() - lh / 2;
                if (logo != null) {
                    float s = Math.min(lw / logo.getWidth(), lh / logo.getHeight());
                    float dw = logo.getWidth() * s, dh = logo.getHeight() * s;
                    r.set(lx + (lw - dw) / 2, ly + (lh - dh) / 2, lx + (lw - dw) / 2 + dw, ly + (lh - dh) / 2 + dh);
                    fill.setColor(0xFFFFFFFF);
                    fill.setAlpha(Math.round(255 * a));
                    c.drawBitmap(logo, null, r, fill);
                }
                float tx = lx + lw + 24 * k, rw = b.right - 20 * k - tx;
                rowName.setColor(on ? LiveTheme.ink : 0xFFF5F5F7);
                rowMeta.setColor(on ? Ui.alpha(LiveTheme.ink, 0.75f) : 0xB3FFFFFF);
                c.drawText(ellipsize((ch.num > 0 ? ch.num + "  " : "") + ch.name, rowName, rw), tx, b.top + 44 * k, rowName);
                c.drawText(ellipsize(p.title, rowMeta, rw), tx, b.top + 78 * k, rowMeta);
            }
            String foot = loading ? Ui.tr("Procurando outros canais…", "Looking for other channels…")
                    : rows.size() == 1 ? Ui.tr("OK para assistir · Voltar fecha", "OK to watch · Back to close")
                    : rows.size() + Ui.tr(" canais · OK para assistir · Voltar fecha", " channels · OK to watch · Back to close");
            c.drawText(ellipsize(foot, hint, tw), x, bottom - 34 * k, hint);
            if (moving) postInvalidateOnAnimation();
        }

        private String ellipsize(String s, Paint p, float width) {
            fit.set(p);
            return TextUtils.ellipsize(s == null ? "" : s, fit, Math.max(1, width), TextUtils.TruncateAt.END).toString();
        }
    }

    /** The card, drawn by hand like the rest of Glassio (1920 × 1080 space, k = width / 1920). */
    private final class Card extends View {
        boolean focusWatch = true;
        private final Spring in = new Spring(0.4f, 1f, 0);
        private final RectF r = new RectF(), b = new RectF();
        private final Xtream.Program show = new Xtream.Program();
        private final android.graphics.Matrix artMatrix = new android.graphics.Matrix();
        private final Paint artPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private Bitmap artBitmap;
        private android.graphics.BitmapShader artShader;
        private final TextPaint fit = new TextPaint();
        private Paint fill, glow, small, title, meta, button, buttonOn;
        private float k;
        private long last;

        Card(Context c) {
            super(c);
            in.target = 1;
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            k = w / 1920f;
            fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            glow = new Paint(Paint.ANTI_ALIAS_FLAG);
            glow.setShadowLayer(40 * k, 0, 14 * k, 0x99000000);
            small = Ui.text(Ui.semibold, 22 * k, LiveTheme.text);     // Live TV's theme
            title = Ui.text(Ui.displaySemibold, 40 * k, 0xFFF5F5F7);
            meta = Ui.text(Ui.medium, 24 * k, 0xB3FFFFFF);
            button = Ui.text(Ui.semibold, 24 * k, 0xFFF5F5F7);
            button.setTextAlign(Paint.Align.CENTER);
            buttonOn = Ui.text(Ui.semibold, 24 * k, LiveTheme.ink);     // the text on the focus, in Live TV's theme
            buttonOn.setTextAlign(Paint.Align.CENTER);
        }

        @Override protected void onDraw(Canvas c) {
            long now = SystemClock.uptimeMillis();
            float dt = last == 0 ? 1 / 60f : Math.min(0.05f, (now - last) / 1000f);
            last = now;
            boolean moving = in.step(dt);
            float a = Ui.clamp(in.value);
            float w = 760 * k, h = 250 * k, right = getWidth() - 64 * k, bottom = getHeight() - 64 * k;
            c.translate((1 - a) * 80 * k, 0);
            r.set(right - w, bottom - h, right, bottom);
            glow.setColor(Ui.alpha(0xF2161618, a));
            c.drawRoundRect(r, 28 * k, 28 * k, glow);
            // At the left, the show's picture (its name found on TMDB), else the channel's logo as it is.
            show.title = item.title;
            String artUrl = LiveArt.of(getContext(), show, this::postInvalidate);
            Bitmap art = artUrl.isEmpty() ? null : LiveLogos.get(getContext(), artUrl, this::postInvalidate);
            Bitmap logo = art != null ? null : LiveLogos.get(getContext(), item.ch.logo, this::postInvalidate);
            float x = r.left + 32 * k;
            if (art != null) {
                float aw = 224 * k, ah = aw * 9 / 16;
                b.set(x, r.top + 32 * k, x + aw, r.top + 32 * k + ah);
                if (art != artBitmap) {
                    artBitmap = art;
                    artShader = new android.graphics.BitmapShader(art, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
                }
                float s = Math.max(b.width() / art.getWidth(), b.height() / art.getHeight());
                artMatrix.setScale(s, s);
                artMatrix.postTranslate(b.centerX() - art.getWidth() * s / 2, b.centerY() - art.getHeight() * s / 2);
                artShader.setLocalMatrix(artMatrix);
                artPaint.setShader(artShader);
                artPaint.setAlpha(Math.round(255 * a));
                c.drawRoundRect(b, 14 * k, 14 * k, artPaint);
                x += aw + 28 * k;
            } else if (logo != null) {
                float lw = 150 * k, lh = 90 * k, s = Math.min(lw / logo.getWidth(), lh / logo.getHeight());
                float dw = logo.getWidth() * s, dh = logo.getHeight() * s;
                b.set(x + (lw - dw) / 2, r.top + 40 * k + (lh - dh) / 2, x + (lw - dw) / 2 + dw, r.top + 40 * k + (lh - dh) / 2 + dh);
                fill.setAlpha(Math.round(255 * a));
                c.drawBitmap(logo, null, b, fill);
                x += lw + 28 * k;
            }
            long left = item.start - System.currentTimeMillis();
            String when = countdown ? Ui.tr("Mudando de canal em ", "Switching in ") + Math.max(0, (left + 999) / 1000) + " s"
                    : left > 30_000 ? Ui.tr("Começa às ", "Starts at ") + Ui.time(item.start) : Ui.tr("Começando agora", "Starting now");
            float tw = r.right - 32 * k - x;
            c.drawText(when.toUpperCase(Ui.locale()), x, r.top + 58 * k, small);
            c.drawText(ellipsize(item.title.isEmpty() ? item.ch.name : item.title, title, tw), x, r.top + 106 * k, title);
            c.drawText(ellipsize((item.ch.num > 0 ? item.ch.num + "  " : "") + item.ch.name, meta, tw), x, r.top + 142 * k, meta);
            // Watch, then Dismiss.
            float bw = 210 * k, bh = 60 * k, by = r.bottom - 32 * k - bh, bx = r.right - 32 * k - 2 * bw - 16 * k;
            drawButton(c, bx, by, bw, bh, countdown ? Ui.tr("Assistir já", "Watch now") : Ui.tr("Assistir", "Watch"), focusWatch);
            drawButton(c, bx + bw + 16 * k, by, bw, bh, Ui.tr("Dispensar", "Dismiss"), !focusWatch);
            if (moving) postInvalidateOnAnimation();
            else if (countdown) postInvalidateDelayed(250);      // the seconds
        }

        private void drawButton(Canvas c, float x, float y, float w, float h, String text, boolean on) {
            b.set(x, y, x + w, y + h);
            fill.setColor(on ? LiveTheme.tile : 0x2EFFFFFF);     // the focus: Live TV's theme
            c.drawRoundRect(b, h / 2, h / 2, fill);
            Paint p = on ? buttonOn : button;
            c.drawText(text, b.centerX(), b.centerY() + Ui.halfCap(p), p);
        }

        private String ellipsize(String s, Paint p, float width) {
            fit.set(p);
            return TextUtils.ellipsize(s, fit, Math.max(1, width), TextUtils.TruncateAt.END).toString();
        }
    }
}
