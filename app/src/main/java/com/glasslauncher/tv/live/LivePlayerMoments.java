package com.glasslauncher.tv.live;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;

import java.util.function.LongConsumer;

/**
 * The player's Moments: across the bottom of the picture, the show's bar with a mark at each goal and red card, and a
 * row of tiles under it; ◀ ▶ move, OK goes to that moment, Back closes. While they'd spoil the match there are no marks,
 * only a card that asks first. The design's colors, corners and focus.
 */
final class LivePlayerMoments {
    /** Tiles on screen; more slide along with the focus. */
    static final int TILES = 5;

    private static final float X = 96, R = 1824, HEAD_Y = 756, BAR_Y = 820, TOP = 864, TILE_H = 156, GAP = 20, PAD = 22;
    private static final int TEXT = 0xFFF5F5F7, SOFT = 0xE6F5F5F7, MARK = 0xFFFFB340;

    private final LiveView view;
    private final Spring slide = new Spring(0.28f, 0.9f, 0), grow = new Spring(0.28f, 0.8f, 1);
    private final RectF r = new RectF();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), lift = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint fit = new TextPaint();
    private Paint heading, hint, minute, kind, who, ask, say, button;
    private float k = 1;
    private boolean open, locked;
    private LiveMoments.Match match;
    private Runnable reveal;
    private LongConsumer go;
    private int focus, first;

    LivePlayerMoments(LiveView view) {
        this.view = view;
    }

    void size(float scale) {
        k = scale;
        heading = Ui.text(Ui.displaySemibold, 52 * k, TEXT);
        heading.setShadowLayer(10 * k, 0, 3 * k, 0xB3000000);
        hint = Ui.text(Ui.medium, 24 * k, TEXT);
        hint.setTextAlign(Paint.Align.RIGHT);
        hint.setShadowLayer(7 * k, 0, 2 * k, 0xCC000000);
        minute = Ui.text(Ui.semibold, 24 * k, SOFT);
        kind = Ui.text(Ui.displaySemibold, 30 * k, TEXT);
        who = Ui.text(Ui.medium, 24 * k, SOFT);
        ask = Ui.text(Ui.displaySemibold, 34 * k, TEXT);
        say = Ui.text(Ui.medium, 26 * k, SOFT);
        button = Ui.text(Ui.semibold, 28 * k, TEXT);
        lift.setShadowLayer(24 * k, 0, 8 * k, 0x80000000);
    }

    boolean open() {
        return open;
    }

    /** `locked`: asks first, and OK runs `reveal`. `go`: to a moment's place in the show; null where it can't. */
    void show(LiveMoments.Match match, boolean locked, Runnable reveal, LongConsumer go) {
        this.match = match;
        this.locked = locked;
        this.reveal = reveal;
        this.go = go;
        focus = 0;
        first = 0;
        grow.snap(1);
        slide.snap(0);
        slide.target = 1;
        open = true;
        view.announceForAccessibility(locked ? Ui.tr("Mostrar momentos", "Show moments") : label(0));
        view.invalidate();
    }

    /** The moments themselves, now they've been asked for. */
    void unlock() {
        locked = false;
        view.announceForAccessibility(label(0));
        view.invalidate();
    }

    void close() {
        open = false;
        slide.target = 0;
    }

    boolean step(float dt) {
        return slide.step(dt) | grow.step(dt);
    }

    /** The tile a press lands on. Pure. */
    static int step(int focus, int dir, int count) {
        return Math.max(0, Math.min(count - 1, focus + dir));
    }

    private String kindOf(LiveMoments.Moment m) {
        return m.kind == LiveMoments.GOAL ? Ui.tr("Gol", "Goal") : Ui.tr("Cartão vermelho", "Red card");
    }

    private String label(int i) {
        LiveMoments.Moment m = match.moments.get(i);
        return m.minute + " min, " + kindOf(m) + ", " + m.who;
    }

    /** ◀ ▶ and OK; ▲ ▼ do nothing here; true for those, so they don't reach what's under it. */
    boolean key(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: {
                if (locked) return true;
                int to = step(focus, code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1, match.moments.size());
                if (to == focus) return true;
                focus = to;
                first = LiveRail.window(focus, match.moments.size(), TILES, first);
                grow.snap(0);
                grow.target = 1;
                view.announceForAccessibility(label(focus));
                view.invalidate();
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (locked) reveal.run();
                else if (go != null) go.accept(match.moments.get(focus).at);
                return true;
            default:
                return false;
        }
    }

    /** `played`: how far the show is, 0 to 1; `length`: how long it is, for the marks (0: no marks). */
    void draw(Canvas c, float played, long length) {
        float a = Ui.clamp(slide.value);
        c.save();
        c.translate(0, (1 - a) * (1 - a) * 60 * k);
        c.drawText(Ui.tr("Momentos", "Moments"), X * k, HEAD_Y * k, heading);
        int all = match.moments.size();
        String back = Ui.tr("Voltar", "Back");
        if (!locked && all > TILES) back = (focus + 1) + " / " + all + "   ·   " + back;
        c.drawText(back, R * k, HEAD_Y * k, hint);
        drawBar(c, played, length);
        if (locked) drawAsk(c);
        else for (int i = first; i < Math.min(all, first + TILES); i++) if (i != focus) drawTile(c, i);
        if (!locked) drawTile(c, focus);        // last: it's bigger than its place
        c.restore();
    }

    private void drawBar(Canvas c, float played, long length) {
        float half = 5 * k, left = X * k, right = R * k, y = BAR_Y * k;
        fill.setColor((TEXT & 0x00FFFFFF) | 0x42000000);
        c.drawRoundRect(left, y - half, right, y + half, half, half, fill);
        fill.setColor(LiveTheme.text);
        c.drawRoundRect(left, y - half, Math.max(left + 2 * half, left + (right - left) * played), y + half, half, half, fill);
        if (locked || length <= 0) return;
        fill.setColor(MARK);
        for (LiveMoments.Moment m : match.moments) {
            if (m.at > length) continue;
            float x = left + (right - left) * m.at / length;
            c.drawRoundRect(x - 6 * k, y - 12 * k, x + 6 * k, y + 12 * k, 6 * k, 6 * k, fill);
        }
    }

    /** A card as solid as the player's tabs, to read over any picture. */
    private void card(Canvas c, float round) {
        fill.setColor((LiveTheme.card & 0x00FFFFFF) | 0xF0000000);
        c.drawRoundRect(r, round, round, fill);
        fill.setColor(0x1FFFFFFF);
        c.drawRoundRect(r, round, round, fill);
        LiveTheme.rim(c, r, round, 1.5f * k);
    }

    private void drawTile(Canvas c, int i) {
        LiveMoments.Moment m = match.moments.get(i);
        boolean on = i == focus;
        float width = (R - X - (TILES - 1) * GAP) / TILES, left = (X + (i - first) * (width + GAP)) * k;
        float round = LiveTheme.panelRound(20 * k);
        int save = c.save();
        r.set(left, TOP * k, left + width * k, (TOP + TILE_H) * k);
        if (on) {
            float scale = 1 + 0.05f * Ui.clamp(grow.value);
            c.scale(scale, scale, r.centerX(), r.centerY());
            lift.setColor(LiveTheme.tile);
            c.drawRoundRect(r, round, round, lift);
        } else {
            card(c, round);
        }
        int ink = on ? LiveTheme.ink : TEXT, soft = (ink & 0x00FFFFFF) | 0xE6000000;
        float x = left + PAD * k, room = (width - 2 * PAD) * k;
        minute.setColor(soft);
        kind.setColor(ink);
        who.setColor(soft);
        c.drawText(m.minute + " min", x, r.top + 48 * k, minute);
        c.drawText(ellipsize(kindOf(m), kind, room), x, r.top + 92 * k, kind);
        c.drawText(ellipsize(m.who, who, room), x, r.top + 130 * k, who);
        c.restoreToCount(save);
    }

    /** Asked first, so opening Moments never spoils the match. */
    private void drawAsk(Canvas c) {
        r.set(X * k, 870 * k, R * k, 1020 * k);
        card(c, LiveTheme.panelRound(24 * k));
        float top = r.top, right = r.right, mid = r.centerY();
        String words = Ui.tr("Mostrar momentos", "Show moments");
        float width = button.measureText(words) + 64 * k;
        float room = right - 38 * k - width - 32 * k - (X + 38) * k;
        c.drawText(ellipsize(Ui.tr("Os momentos podem estragar a partida", "Moments can spoil the match"), ask, room),
                (X + 38) * k, top + 66 * k, ask);
        c.drawText(ellipsize(Ui.tr("Gols e cartões ficam escondidos até você pedir. Você pode mudar isso nos Ajustes.",
                "Goals and cards stay hidden until you say so. You can change this in Settings."), say, room),
                (X + 38) * k, top + 112 * k, say);
        r.set(right - 38 * k - width, mid - 40 * k, right - 38 * k, mid + 40 * k);
        lift.setColor(LiveTheme.tile);
        float round = LiveTheme.panelRound(16 * k);
        c.drawRoundRect(r, round, round, lift);
        button.setColor(LiveTheme.ink);
        c.drawText(words, r.left + 32 * k, mid + Ui.halfCap(button), button);
    }

    private String ellipsize(String s, Paint p, float width) {
        fit.set(p);
        return TextUtils.ellipsize(s == null ? "" : s, fit, Math.max(1, width), TextUtils.TruncateAt.END).toString();
    }
}
