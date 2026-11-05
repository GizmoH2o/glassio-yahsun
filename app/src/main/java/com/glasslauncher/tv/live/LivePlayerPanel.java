package com.glasslauncher.tv.live;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The card at the right of the player for its tabs (Info, Audio & Subtitles, Moments, Options): a title, some words,
 * then rows, drawn over the video, which keeps playing. The design's card, edge, corners and focus. ▲ ▼ move, OK
 * presses, Back closes. Rows are asked for again after a press, so a choice shows at once.
 */
final class LivePlayerPanel {
    static final class Row {
        final String text;
        String value = "";
        boolean header, checked;
        Runnable press;

        Row(String text, Runnable press) {
            this.text = text;
            this.press = press;
        }

        static Row header(String text) {
            Row r = new Row(text, null);
            r.header = true;
            return r;
        }

        Row value(String v) {
            value = v == null ? "" : v;
            return this;
        }

        Row checked(boolean on) {
            checked = on;
            return this;
        }
    }

    private static final float W = 720, EDGE = 72, PAD = 40, ROW = 68, HEAD = 52, GAP = 6;

    private final LiveView view;
    private final Spring slide = new Spring(0.28f, 0.9f, 0), scroll = new Spring(0.24f, 0.95f, 0);
    private final RectF box = new RectF(), row = new RectF();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint fit = new TextPaint();
    private Paint heading, line, value, header;
    private TextPaint words;
    private float k = 1;
    private int w, h;
    private boolean open;
    private String title = "", body = "";
    private Supplier<List<Row>> source;
    private List<Row> rows = new ArrayList<>();
    private int focus;
    private StaticLayout about;

    LivePlayerPanel(LiveView view) {
        this.view = view;
        edge.setStyle(Paint.Style.STROKE);
    }

    void size(float scale, int width, int height) {
        k = scale;
        w = width;
        h = height;
        heading = Ui.text(Ui.displaySemibold, 38 * k, 0xFFF5F5F7);
        line = Ui.text(Ui.medium, 29 * k, 0xFFF5F5F7);
        value = Ui.text(Ui.medium, 26 * k, 0xF2F5F5F7);
        value.setTextAlign(Paint.Align.RIGHT);
        header = Ui.text(Ui.semibold, 25 * k, 0xE6F5F5F7);
        words = new TextPaint(Ui.text(Ui.regular, 28 * k, 0xFFF5F5F7));
        about = null;
    }

    boolean open() {
        return open;
    }

    void show(String heading, String text, Supplier<List<Row>> rows) {
        title = heading;
        body = text == null ? "" : text;
        source = rows;
        about = null;
        reload();
        focus = -1;
        move(1);
        scroll.snap(target());
        open = true;
        slide.snap(0);
        slide.target = 1;
        if (focus >= 0) view.announceForAccessibility(title + ", " + this.rows.get(focus).text);
        view.invalidate();
    }

    /** New words on the same card (Moments, once it's asked to show them). */
    void show(String text) {
        body = text == null ? "" : text;
        about = null;
    }

    /** The rows again (a choice made), the focus where it was. */
    void reload() {
        if (source != null) rows = source.get();
        if (focus >= rows.size() || focus >= 0 && rows.get(focus).press == null) {
            focus = -1;
            move(1);
        }
    }

    void close() {
        open = false;
        slide.target = 0;
    }

    boolean step(float dt) {
        return slide.step(dt) | scroll.step(dt);
    }

    private void move(int dir) {
        for (int i = focus + dir; i >= 0 && i < rows.size(); i += dir) {
            if (rows.get(i).press == null) continue;
            focus = i;
            scroll.target = target();
            return;
        }
    }

    private float rowTop(int index) {
        float y = 0;
        for (int i = 0; i < index; i++) y += rows.get(i).header ? HEAD : ROW + GAP;
        return y;
    }

    /** How far the rows are scrolled for the focused one to be in the middle of the room. */
    private float target() {
        if (focus < 0) return 0;
        float room = (1080 - 2 * EDGE - 2 * PAD - 70 - bodyHeight() / k);
        float all = rowTop(rows.size());
        return Math.max(0, Math.min(all - room, rowTop(focus) + ROW / 2 - room / 2));
    }

    private float bodyHeight() {
        layout();
        return about == null ? 0 : about.getHeight() + 22 * k;
    }

    private void layout() {
        int width = Math.max(1, Math.round((W - 2 * PAD) * k));
        if (about != null || body.isEmpty() || words == null) return;
        about = StaticLayout.Builder.obtain(body, 0, body.length(), words, width).setMaxLines(6)
                .setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0, 1.1f).build();
    }

    /** ▲ ▼ and OK; ◀ ▶ do nothing here; true for those, so they don't reach what's under it. */
    boolean key(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN: {
                int was = focus;
                move(code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
                if (focus != was) view.announceForAccessibility(rows.get(focus).text);
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (focus >= 0) {
                    rows.get(focus).press.run();
                    if (open) reload();
                }
                return true;
            default:
                return false;
        }
    }

    void draw(Canvas c) {
        float a = Ui.clamp(slide.value), x = w - (EDGE + W) * k + (1 - a) * 90 * k;
        box.set(x, EDGE * k, x + W * k, h - EDGE * k);
        float rr = LiveTheme.panelRound(32 * k);
        fill.setColor(LiveTheme.sheet());
        fill.setShadowLayer(40 * k, 0, 16 * k, 0x8C000000);
        c.drawRoundRect(box, rr, rr, fill);
        fill.clearShadowLayer();
        if (LiveTheme.sheetRim() != 0) {
            edge.setStrokeWidth(1.5f * k);
            edge.setColor(LiveTheme.sheetRim());
            c.drawRoundRect(box, rr, rr, edge);
        }
        float left = x + PAD * k, right = box.right - PAD * k, y = box.top + (PAD + 34) * k;
        c.drawText(ellipsize(title, heading, right - left), left, y, heading);
        y += 26 * k;
        layout();
        if (about != null) {
            c.save();
            c.translate(left, y);
            about.draw(c);
            c.restore();
            y += about.getHeight() + 22 * k;
        }
        c.save();
        c.clipRect(x, y, box.right, box.bottom - 24 * k);
        float base = y - scroll.value * k;
        for (int i = 0; i < rows.size(); i++) {
            Row one = rows.get(i);
            float top = base + rowTop(i) * k;
            if (top + ROW * k < y || top > box.bottom) continue;
            if (one.header) {
                c.drawText(ellipsize(one.text, header, right - left), left, top + 38 * k, header);
                continue;
            }
            boolean on = i == focus;
            row.set(left - 14 * k, top, right + 14 * k, top + ROW * k);
            if (on) {
                fill.setColor(LiveTheme.tile);
                float round = LiveTheme.panelRound(14 * k);
                c.drawRoundRect(row, round, round, fill);
            }
            int ink = on ? LiveTheme.ink : one.press == null ? 0xE6F5F5F7 : 0xFFF5F5F7;
            float mid = row.centerY();
            float end = right;
            if (one.checked) {
                value.setColor(ink);
                c.drawText("✓", end, mid + Ui.halfCap(value), value);
                end -= 40 * k;
            }
            if (!one.value.isEmpty()) {
                value.setColor(on ? LiveTheme.ink : 0xF2F5F5F7);
                String v = ellipsize(one.value, value, (right - left) * 0.52f);
                c.drawText(v, end, mid + Ui.halfCap(value), value);
                end -= value.measureText(v) + 20 * k;
            }
            line.setColor(ink);
            c.drawText(ellipsize(one.text, line, end - left), left, mid + Ui.halfCap(line), line);
        }
        c.restore();
    }

    private String ellipsize(String s, Paint p, float width) {
        fit.set(p);
        return TextUtils.ellipsize(s == null ? "" : s, fit, Math.max(1, width), TextUtils.TruncateAt.END).toString();
    }
}
