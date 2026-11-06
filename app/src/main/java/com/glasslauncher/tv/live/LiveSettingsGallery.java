package com.glasslauncher.tv.live;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;

/**
 * Settings' Look: what's playing, large, with the player of the design in focus drawn over it, that design's name and
 * words beside it with the times of day, and every design in a row underneath. ◀ ▶ go through the designs and the
 * preview follows; OK uses one. Measurements in a 1920×1080 space.
 */
final class LiveSettingsGallery {
    /** The rows above the designs: morning, night, late night, more options; TILES: the designs themselves. */
    static final int TILES = 4;
    static final float[] BOX = {96, 124, 1160, 652};
    private static final int VISIBLE = 7;
    private static final float X = 96, R = 1824, COLUMN_X = 1288, TILE_Y = 806, TILE_H = 196, TILE_GAP = 16,
            TILE_W = (R - X - (VISIBLE - 1) * TILE_GAP) / VISIBLE, ROW_Y = 412, ROW_H = 68, ROW_STEP = 76;

    /** Where the focus goes from row `f` by `dy`. Pure. */
    static int moveRow(int f, int dy) {
        return Math.max(0, Math.min(TILES, f + dy));
    }

    /** What the gallery asks of the settings. */
    interface Host {
        void use(int style);

        /** A time-of-day row pressed: 0 morning, 1 night, 2 late night. */
        void timeRow(int row);

        void moreOptions();
    }

    private final LiveView view;
    private final LiveSettingsHome cards;
    private final Host host;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final TextPaint fit = new TextPaint();
    private Paint title, name, label, value, hint, showTitle, showSub, tab, pill;
    private TextPaint words;
    private StaticLayout about;
    private String aboutShown;
    private float k = 1;
    private int place, first, row = TILES;
    /** Each design's colors {card, focus, ink}, read as it opens (reading them every frame would switch designs 15 times). */
    private final int[][] colors = new int[LiveTheme.STYLES][3];

    LiveSettingsGallery(LiveView view, LiveSettingsHome cards, Host host) {
        this.view = view;
        this.cards = cards;
        this.host = host;
        edge.setStyle(Paint.Style.STROKE);
    }

    void size(float scale) {
        k = scale;
        title = Ui.text(Ui.displaySemibold, 44 * k, 0xFFFFFFFF);
        name = Ui.text(Ui.displaySemibold, 40 * k, 0xFFFFFFFF);
        label = Ui.text(Ui.semibold, 27 * k, 0xFFFFFFFF);
        value = Ui.text(Ui.medium, 26 * k, 0xFFFFFFFF);
        value.setTextAlign(Paint.Align.RIGHT);
        hint = Ui.text(Ui.medium, 25 * k, 0xE6FFFFFF);
        hint.setTextAlign(Paint.Align.RIGHT);
        showTitle = Ui.text(Ui.displaySemibold, 48 * k, 0xFFFFFFFF);
        showSub = Ui.text(Ui.medium, 23 * k, 0xFFFFFFFF);
        tab = Ui.text(Ui.semibold, 21 * k, 0xFFFFFFFF);
        pill = Ui.text(Ui.semibold, 18 * k, 0xFFFFFFFF);
        words = new TextPaint(Ui.text(Ui.regular, 26 * k, 0xF2FFFFFF));
        about = null;
    }

    void open() {
        place = Math.max(0, LiveTheme.placeOf(LiveTheme.style()));
        first = LiveRail.window(place, LiveTheme.STYLES, VISIBLE, Math.max(0, place - VISIBLE / 2));
        row = TILES;
        for (int i = 0; i < LiveTheme.STYLES; i++) {
            final int[] one = colors[i];
            LiveTheme.asStyle(LiveTheme.ordered(i), () -> {
                one[0] = (LiveTheme.card & 0x00FFFFFF) | 0xFF000000;
                one[1] = LiveTheme.tile;
                one[2] = LiveTheme.ink;
            });
        }
    }

    void key(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (row == TILES) {
                    place = Math.max(0, Math.min(LiveTheme.STYLES - 1, place + (code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1)));
                    first = LiveRail.window(place, LiveTheme.STYLES, VISIBLE, first);
                    view.announceForAccessibility(LiveTheme.styleName(LiveTheme.ordered(place)));
                }
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                row = moveRow(row, -1);
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                row = moveRow(row, 1);
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (row == TILES) host.use(LiveTheme.ordered(place));
                else if (row == TILES - 1) host.moreOptions();
                else host.timeRow(row);
                break;
            default:
                break;
        }
        view.invalidate();
    }

    /** `box`: the live window; `show`, `sub`: what's playing; `times`: the rows' values. */
    void draw(Canvas c, RectF box, String show, String sub, String[] times) {
        c.drawText(Ui.tr("Aparência", "Look"), X * k, 96 * k, title);
        c.drawText(Ui.tr("OK para usar  ·  Vale para todo o app", "OK uses it  ·  The whole app follows"), R * k, 92 * k, hint);
        final int style = LiveTheme.ordered(place);
        LiveTheme.asStyle(style, () -> drawPreview(c, box, show, sub));
        drawColumn(c, style, times);
        int last = Math.min(LiveTheme.STYLES, first + VISIBLE);
        for (int i = first; i < last; i++) drawTile(c, i, i - first);
    }

    /** The player as the design in focus has it, over the video. */
    private void drawPreview(Canvas c, RectF box, String show, String sub) {
        c.save();
        c.clipRect(box);
        int base = LiveTheme.bg & 0x00FFFFFF;
        float fadeTop = box.bottom - 410 * k;
        fill.setShader(new LinearGradient(0, fadeTop, 0, box.bottom, new int[] {base, 0xE6000000 | base, 0xFA000000 | base},
                new float[] {0, 0.4f, 1}, Shader.TileMode.CLAMP));
        fill.setColor(0xFF000000);
        c.drawRect(box.left, fadeTop, box.right, box.bottom, fill);
        fill.setShader(null);
        float left = box.left + 40 * k, right = box.right - 40 * k, plateTop = box.bottom - 310 * k;
        switch (LiveTheme.infoBar()) {
            case LiveTheme.BAR_CARD: {
                r.set(box.left + 18 * k, plateTop - 22 * k, box.right - 18 * k, box.bottom - 18 * k);
                float rr = LiveTheme.panelRound(28 * k);
                fill.setColor((LiveTheme.bgTop & 0x00FFFFFF) | 0xB3000000);
                c.drawRoundRect(r, rr, rr, fill);
                edge.setStrokeWidth(1.5f * k);
                edge.setColor(0x40FFFFFF);
                c.drawRoundRect(r, rr, rr, edge);
                break;
            }
            case LiveTheme.BAR_BAND_LINE:
            case LiveTheme.BAR_BAND:
                fill.setColor((LiveTheme.bgTop & 0x00FFFFFF) | 0xF5000000);
                c.drawRect(box.left, plateTop - 22 * k, box.right, box.bottom, fill);
                if (LiveTheme.infoBar() == LiveTheme.BAR_BAND_LINE) {
                    fill.setColor(LiveTheme.tile);
                    c.drawRect(box.left, plateTop - 22 * k, box.right, plateTop - 18 * k, fill);
                }
                break;
            default:
                break;
        }
        float y = plateTop + 20 * k;
        String live = Ui.tr("AO VIVO", "LIVE");
        float pw = pill.measureText(live) + 26 * k;
        r.set(left, y - 16 * k, left + pw, y + 16 * k);
        fill.setColor(0xFFD92D20);
        c.drawRoundRect(r, 16 * k, 16 * k, fill);
        c.drawText(live, left + 13 * k, y + Ui.halfCap(pill), pill);
        y += 80 * k;
        c.drawText(ellipsize(show, showTitle, right - left), left, y, showTitle);
        y += 42 * k;
        c.drawText(ellipsize(sub, showSub, right - left), left, y, showSub);
        y += 34 * k;
        fill.setColor(0x40FFFFFF);
        c.drawRoundRect(left, y, right, y + 6 * k, 3 * k, 3 * k, fill);
        fill.setColor(LiveTheme.text);
        c.drawRoundRect(left, y, left + (right - left) * 0.7f, y + 6 * k, 3 * k, 3 * k, fill);
        y += 26 * k;
        String[] tabs = {Ui.tr("Info", "Info"), Ui.tr("Canais", "Channels"), Ui.tr("Áudio", "Audio")};
        float x = left, rr = LiveTheme.panelRound(12 * k);
        for (int i = 0; i < tabs.length; i++) {
            float tw = tab.measureText(tabs[i]) + 44 * k;
            r.set(x, y, x + tw, y + 48 * k);
            fill.setColor(i == 0 ? LiveTheme.tile : (LiveTheme.card & 0x00FFFFFF) | 0xF0000000);
            c.drawRoundRect(r, rr, rr, fill);
            tab.setColor(i == 0 ? LiveTheme.ink : 0xFFFFFFFF);
            c.drawText(tabs[i], x + 22 * k, r.centerY() + Ui.halfCap(tab), tab);
            x = r.right + 10 * k;
        }
        c.restore();
    }

    private void drawColumn(Canvas c, int style, String[] times) {
        float x = COLUMN_X * k, width = (R - COLUMN_X) * k;
        c.drawText(ellipsize(LiveTheme.styleName(style), name, width), x, 170 * k, name);
        String text = LiveTheme.styleAbout(style);
        if (about == null || !text.equals(aboutShown)) {
            aboutShown = text;
            about = StaticLayout.Builder.obtain(text, 0, text.length(), words, Math.max(1, Math.round(width)))
                    .setLineSpacing(0, 1.12f).setMaxLines(5).setEllipsize(TextUtils.TruncateAt.END).build();
        }
        c.save();
        c.translate(x, 192 * k);
        about.draw(c);
        c.restore();
        hint.setTextAlign(Paint.Align.LEFT);
        c.drawText(Ui.tr("Por hora do dia", "By time of day"), x, (ROW_Y - 16) * k, hint);
        hint.setTextAlign(Paint.Align.RIGHT);
        String[] names = {Ui.tr("De manhã", "Morning"), Ui.tr("À noite", "Night"), Ui.tr("De madrugada", "Late night"), Ui.tr("Mais opções", "More options")};
        for (int i = 0; i < names.length; i++) {
            float top = ROW_Y + i * ROW_STEP;
            r.set(x, top * k, x + width, (top + ROW_H) * k);
            boolean on = row == i;
            cards.card(c, r, on, 14);
            label.setColor(on ? LiveTheme.ink : 0xFFFFFFFF);
            c.drawText(names[i], r.left + 24 * k, r.centerY() + Ui.halfCap(label), label);
            if (i < times.length) {
                value.setColor(on ? LiveTheme.ink : 0xFFFFFFFF);
                c.drawText(ellipsize(times[i], value, width * 0.5f), r.right - 24 * k, r.centerY() + Ui.halfCap(value), value);
            }
        }
        label.setColor(0xFFFFFFFF);
        value.setColor(0xFFFFFFFF);
    }

    private void drawTile(Canvas c, int i, int slot) {
        float left = (X + slot * (TILE_W + TILE_GAP)) * k, top = TILE_Y * k;
        r.set(left, top, left + TILE_W * k, top + TILE_H * k);
        float rr = LiveTheme.panelRound(16 * k);
        fill.setColor(colors[i][0]);
        c.drawRoundRect(r, rr, rr, fill);
        fill.setColor(0x14FFFFFF);
        c.drawRoundRect(r, rr, rr, fill);
        int s = LiveTheme.ordered(i);
        fill.setColor(colors[i][1]);
        c.drawRoundRect(r.left + 18 * k, r.bottom - 76 * k, r.left + 88 * k, r.bottom - 66 * k, 5 * k, 5 * k, fill);
        label.setTextSize(24 * k);
        label.setColor(0xFFFFFFFF);
        c.drawText(ellipsize(LiveTheme.styleName(s), label, r.width() - 36 * k), r.left + 18 * k, r.bottom - 26 * k, label);
        label.setTextSize(27 * k);
        if (s == LiveTheme.style()) {
            value.setColor(0xFFFFFFFF);
            c.drawText("✓", r.right - 18 * k, r.top + 40 * k, value);
        }
        if (i == place) {
            edge.setStrokeWidth((row == TILES ? 5 : 2) * k);
            edge.setColor(row == TILES ? LiveTheme.tile : 0x80FFFFFF);
            r.inset(-3 * k, -3 * k);
            c.drawRoundRect(r, rr, rr, edge);
        }
    }

    private String ellipsize(String s, Paint p, float width) {
        fit.set(p);
        return TextUtils.ellipsize(s == null ? "" : s, fit, Math.max(1, width), TextUtils.TruncateAt.END).toString();
    }
}
