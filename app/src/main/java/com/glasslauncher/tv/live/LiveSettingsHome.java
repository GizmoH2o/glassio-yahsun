package com.glasslauncher.tv.live;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;

/**
 * Settings' first screen: Settings and search at the top, three cards saying how the connection, the guide and the
 * subscription are, then the sections, three to a row, each with how it's set. The design's cards, corners and focus.
 * Also the cards the connection check and the Guide page are made of. Measurements in a 1920×1080 space.
 */
final class LiveSettingsHome {
    static final int SEARCH = -1, FIRST_SECTION = 3, TOTAL = 12;
    static final int GOOD = 0xFF46D369, WARN = 0xFFFFB340, BAD = 0xFFFF6961, NONE = 0;
    private static final float X = 96, R = 1824, GAP = 20, COLUMN = (R - X - 2 * GAP) / 3,
            STATUS_Y = 160, STATUS_H = 186, GRID_Y = 366, CELL_H = 204;

    /** Where the focus goes from `f` by a step of `dx` or `dy`. Pure. */
    static int move(int f, int dx, int dy) {
        if (f == SEARCH) return dy > 0 ? FIRST_SECTION - 1 : SEARCH;
        if (dx != 0) {
            int to = f + dx;
            return to < 0 || to >= TOTAL || to / 3 != f / 3 ? f : to;
        }
        if (dy < 0) return f < 3 ? SEARCH : f - 3;
        if (dy > 0) return f + 3 >= TOTAL ? f : f + 3;
        return f;
    }

    /** Whether search words `query` fit any of `names` (a row, its section, its group). Pure. */
    static boolean found(String query, String... names) {
        String q = Xtream.fold(query);
        for (String name : names) if (Xtream.fold(name).contains(q)) return true;
        return false;
    }

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final TextPaint fit = new TextPaint();
    private Paint title, big, name, note, head;
    private float k = 1;

    LiveSettingsHome() {
        edge.setStyle(Paint.Style.STROKE);
    }

    void size(float scale) {
        k = scale;
        title = Ui.text(Ui.displaySemibold, 62 * k, 0xFFFFFFFF);
        big = Ui.text(Ui.semibold, 34 * k, 0xFFFFFFFF);
        name = Ui.text(Ui.semibold, 32 * k, 0xFFFFFFFF);
        note = Ui.text(Ui.medium, 25 * k, 0xFFFFFFFF);
        head = Ui.text(Ui.semibold, 25 * k, 0xFFFFFFFF);
    }

    /** `status`: {head, big, small} three times; `dots` their colors; `names`, `notes` the sections'. */
    void draw(Canvas c, int focus, String[][] status, int[] dots, String[] names, String[] notes) {
        c.drawText(Ui.tr("Ajustes", "Settings"), X * k, 112 * k, title);
        r.set((R - 400) * k, 52 * k, R * k, 124 * k);
        card(c, r, focus == SEARCH, 18);
        note.setColor(focus == SEARCH ? LiveTheme.ink : 0xFFFFFFFF);
        c.drawText(Ui.tr("Buscar nos ajustes", "Search settings"), r.left + 30 * k, r.centerY() + Ui.halfCap(note), note);
        for (int i = 0; i < 3; i++) {
            float x = X + i * (COLUMN + GAP);
            r.set(x * k, STATUS_Y * k, (x + COLUMN) * k, (STATUS_Y + STATUS_H) * k);
            boolean on = i == focus;
            card(c, r, on, 22);
            float tx = r.left + 30 * k, room = r.width() - 60 * k;
            head.setColor(on ? onFocus() : 0xE6FFFFFF);
            c.drawText(ellipsize(status[i][0], head, room), tx, r.top + 52 * k, head);
            big.setColor(on ? LiveTheme.ink : 0xFFFFFFFF);
            float bx = tx;
            if (dots[i] != NONE) {
                fill.setColor(on ? LiveTheme.ink : dots[i]);
                c.drawCircle(tx + 10 * k, r.top + 100 * k, 10 * k, fill);
                bx += 34 * k;
            }
            c.drawText(ellipsize(status[i][1], big, room - (bx - tx)), bx, r.top + 112 * k, big);
            note.setColor(on ? onFocus() : 0xE6FFFFFF);
            c.drawText(ellipsize(status[i][2], note, room), tx, r.top + 154 * k, note);
        }
        for (int i = 0; i < names.length; i++) {
            float x = X + i % 3 * (COLUMN + GAP), y = GRID_Y + i / 3 * (CELL_H + GAP);
            r.set(x * k, y * k, (x + COLUMN) * k, (y + CELL_H) * k);
            boolean on = i + FIRST_SECTION == focus;
            card(c, r, on, 22);
            name.setColor(on ? LiveTheme.ink : 0xFFFFFFFF);
            note.setColor(on ? onFocus() : 0xE6FFFFFF);
            float room = r.width() - 60 * k;
            c.drawText(ellipsize(names[i], name, room), r.left + 30 * k, r.centerY() - 6 * k, name);
            c.drawText(ellipsize(notes[i], note, room), r.left + 30 * k, r.centerY() + 38 * k, note);
        }
    }

    /** Quieter words on the focus: the ink itself a little see-through, so they read on a colored focus too. */
    private static int onFocus() {
        return Ui.alpha(LiveTheme.ink, 0.86f);
    }

    /** A card at `box`: the focus' color when `on`, a little bigger; else the design's card and edge. */
    void card(Canvas c, RectF box, boolean on, float radius) {
        float rr = LiveTheme.panelRound(radius * k);
        if (on) {
            box.inset(-5 * k, -5 * k);
            fill.setColor(LiveTheme.tile);
            fill.setShadowLayer(24 * k, 0, 10 * k, 0x80000000);
            c.drawRoundRect(box, rr, rr, fill);
            fill.clearShadowLayer();
            box.inset(5 * k, 5 * k);
            return;
        }
        fill.setColor((LiveTheme.card & 0x00FFFFFF) | 0xF0000000);
        c.drawRoundRect(box, rr, rr, fill);
        fill.setColor(0x14FFFFFF);
        c.drawRoundRect(box, rr, rr, fill);
        if (LiveTheme.sheetRim() != 0) {
            edge.setStrokeWidth(1.5f * k);
            edge.setColor(LiveTheme.sheetRim());
            c.drawRoundRect(box, rr, rr, edge);
        }
    }

    private String ellipsize(String s, Paint p, float width) {
        fit.set(p);
        return TextUtils.ellipsize(s == null ? "" : s, fit, Math.max(1, width), TextUtils.TruncateAt.END).toString();
    }
}
