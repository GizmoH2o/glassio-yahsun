package com.glasslauncher.tv.live;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * The card at the right of the player for Audio & Subtitles, drawn over the video, which keeps playing: a title, then
 * groups of rows, the one chosen filled, then sizes to pick from and switches at the bottom. The design's card, edge and
 * corners. ▲ ▼ move, ◀ ▶ change a row of sizes, OK presses, Back closes. Rows are asked for again after a press, so a
 * choice shows at once.
 */
final class LivePlayerPanel {
    static final class Row {
        final String text;
        boolean header, checked, toggle, on, pinned;
        Runnable press;
        String[] sizes;
        int size;
        IntConsumer pick;

        Row(String text, Runnable press) {
            this.text = text;
            this.press = press;
        }

        static Row header(String text) {
            Row r = new Row(text, null);
            r.header = true;
            return r;
        }

        /** Sizes to pick from with ◀ ▶, `size` chosen. */
        static Row sizes(String text, String[] labels, int size, IntConsumer pick) {
            Row r = new Row(text, null);
            r.sizes = labels;
            r.size = size;
            r.pick = pick;
            return r;
        }

        Row checked(boolean on) {
            checked = on;
            return this;
        }

        /** A switch. */
        Row toggle(boolean on) {
            toggle = true;
            this.on = on;
            return this;
        }

        /** This row and those after it go to the bottom of the card. */
        Row pinned() {
            pinned = true;
            return this;
        }

        boolean takesFocus() {
            return press != null || sizes != null;
        }
    }

    private static final float W = 720, EDGE = 72, PAD = 40, ROW = 64, HEAD = 46, GAP = 8, GROUP = 18, TITLE = 72,
            CHIP_W = 72, CHIP_H = 56, CHIP_GAP = 8;
    private static final int TEXT = 0xFFF5F5F7, ON = 0xFF1B7F3B, OFF = 0xFF4A4A50;

    private final LiveView view;
    private final Spring slide = new Spring(0.28f, 0.9f, 0), scroll = new Spring(0.24f, 0.95f, 0);
    private final RectF box = new RectF(), row = new RectF();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint fit = new TextPaint();
    private Paint heading, line, back, header, chip;
    private Shader side;
    private float k = 1;
    private int w, h;
    private boolean open;
    private String title = "";
    private Supplier<List<Row>> source;
    private List<Row> rows = new ArrayList<>();
    private int focus;

    LivePlayerPanel(LiveView view) {
        this.view = view;
        edge.setStyle(Paint.Style.STROKE);
    }

    void size(float scale, int width, int height) {
        k = scale;
        w = width;
        h = height;
        heading = Ui.text(Ui.displaySemibold, 38 * k, TEXT);
        line = Ui.text(Ui.semibold, 27 * k, TEXT);
        back = Ui.text(Ui.medium, 24 * k, 0xE6F5F5F7);
        back.setTextAlign(Paint.Align.RIGHT);
        header = Ui.text(Ui.semibold, 24 * k, 0xE6F5F5F7);
        chip = Ui.text(Ui.semibold, 24 * k, TEXT);
        chip.setTextAlign(Paint.Align.CENTER);
        side = new LinearGradient(w, 0, w / 2f, 0, 0xB8000000, 0x00000000, Shader.TileMode.CLAMP);
    }

    boolean open() {
        return open;
    }

    void show(String heading, Supplier<List<Row>> rows) {
        title = heading;
        source = rows;
        reload();
        focus = -1;
        move(1);
        scroll.snap(target());
        open = true;
        slide.snap(0);
        slide.target = 1;
        if (focus >= 0) LiveSpeech.say(view, title + ", " + this.rows.get(focus).text);
        view.invalidate();
    }

    /** The rows again (a choice made), the focus where it was. */
    void reload() {
        if (source != null) rows = source.get();
        if (focus >= rows.size() || focus >= 0 && !rows.get(focus).takesFocus()) {
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
            if (!rows.get(i).takesFocus()) continue;
            focus = i;
            scroll.target = target();
            return;
        }
    }

    /** The size a press of ◀ ▶ lands on. Pure. */
    static int sizeStep(int size, int dir, int count) {
        return Math.max(0, Math.min(count - 1, size + dir));
    }

    /** The room for the rows, under the title. */
    private float room() {
        return 1080 - 2 * EDGE - 2 * PAD - TITLE;
    }

    private float height(int i) {
        Row one = rows.get(i);
        return one.header ? HEAD + (i > 0 ? GROUP : 0) : (one.sizes != null ? ROW + GROUP : ROW) + GAP;
    }

    private float rowTop(int index) {
        float y = 0, all = 0;
        for (int i = 0; i < rows.size(); i++) all += height(i);
        for (int i = 0; i < index; i++) y += height(i);
        // What's pinned goes to the bottom when everything fits.
        for (int i = 0; i < rows.size() && i <= index; i++) {
            if (rows.get(i).pinned) {
                y += Math.max(0, room() - all);
                break;
            }
        }
        return y;
    }

    /** How far the rows are scrolled for the focused one to be in the middle of the room. */
    private float target() {
        if (focus < 0 || rows.isEmpty()) return 0;
        float all = rowTop(rows.size() - 1) + height(rows.size() - 1);
        return Math.max(0, Math.min(all - room(), rowTop(focus) + ROW / 2 - room() / 2));
    }

    /** ▲ ▼, ◀ ▶ on sizes, and OK; the rest of ◀ ▶ do nothing here; true for those, so they don't reach what's under it. */
    boolean key(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN: {
                int was = focus;
                move(code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
                if (focus != was) LiveSpeech.say(view, rows.get(focus).text);
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: {
                Row one = focus >= 0 ? rows.get(focus) : null;
                if (one == null || one.sizes == null) return true;
                int to = sizeStep(one.size, code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1, one.sizes.length);
                if (to != one.size) {
                    one.pick.accept(to);
                    reload();
                    LiveSpeech.say(view, one.text + ", " + one.sizes[to]);
                }
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (focus >= 0 && rows.get(focus).press != null) {
                    rows.get(focus).press.run();
                    if (open) reload();
                }
                return true;
            default:
                return false;
        }
    }

    void draw(Canvas c) {
        fill.setColor(0xFF000000);       // a shader takes its paint's alpha
        fill.setShader(side);
        c.drawRect(w / 2f, 0, w, h, fill);
        fill.setShader(null);
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
        String backWord = Ui.tr("Voltar", "Back");
        c.drawText(ellipsize(title, heading, right - left - back.measureText(backWord) - 20 * k), left, y, heading);
        c.drawText(backWord, right, y, back);
        y = box.top + (PAD + TITLE) * k;
        c.save();
        c.clipRect(x, y - 10 * k, box.right, box.bottom - 24 * k);
        float base = y - scroll.value * k;
        for (int i = 0; i < rows.size(); i++) {
            Row one = rows.get(i);
            float top = base + rowTop(i) * k;
            if (top + ROW * k < y - 10 * k || top > box.bottom) continue;
            if (one.header) {
                c.drawText(ellipsize(one.text, header, right - left), left, top + ((i > 0 ? GROUP : 0) + 30) * k, header);
            } else if (one.sizes != null) {
                drawSizes(c, one, i == focus, left, right, top);
            } else {
                drawRow(c, one, i == focus, left, right, top);
            }
        }
        c.restore();
    }

    private void outline(Canvas c, float round) {
        float out = 6 * k;
        row.inset(-out, -out);
        edge.setStrokeWidth(4 * k);
        edge.setColor(LiveTheme.tile);
        c.drawRoundRect(row, round + out, round + out, edge);
        row.inset(out, out);
    }

    private void drawRow(Canvas c, Row one, boolean on, float left, float right, float top) {
        row.set(left, top, right, top + ROW * k);
        float round = LiveTheme.panelRound(14 * k);
        fill.setColor(one.checked ? LiveTheme.tile : 0x1AF5F5F7);
        c.drawRoundRect(row, round, round, fill);
        if (on) outline(c, round);
        float mid = row.centerY(), end = right - 22 * k;
        int ink = one.checked ? LiveTheme.ink : one.takesFocus() ? TEXT : 0xE6F5F5F7;
        if (one.checked) {
            line.setColor(ink);
            float mark = line.measureText("✓");
            c.drawText("✓", end - mark, mid + Ui.halfCap(line), line);
            end -= mark + 20 * k;
        }
        if (one.toggle) {
            float tw = 68 * k, th = 38 * k, knob = 30 * k, gap = 4 * k;
            float tl = end - tw;
            fill.setColor(one.on ? ON : OFF);
            c.drawRoundRect(tl, mid - th / 2, end, mid + th / 2, th / 2, th / 2, fill);
            fill.setColor(TEXT);
            c.drawCircle(one.on ? end - gap - knob / 2 : tl + gap + knob / 2, mid, knob / 2, fill);
            end -= tw + 20 * k;
        }
        line.setColor(ink);
        c.drawText(ellipsize(one.text, line, end - left - 22 * k), left + 22 * k, mid + Ui.halfCap(line), line);
    }

    private void drawSizes(Canvas c, Row one, boolean on, float left, float right, float top) {
        float mid = top + (GROUP + ROW / 2) * k;
        line.setColor(TEXT);
        float x = right - (one.sizes.length * CHIP_W + (one.sizes.length - 1) * CHIP_GAP) * k;
        c.drawText(ellipsize(one.text, line, x - left - 12 * k), left, mid + Ui.halfCap(line), line);
        float round = LiveTheme.panelRound(12 * k);
        for (int i = 0; i < one.sizes.length; i++) {
            boolean chosen = i == one.size;
            row.set(x, mid - CHIP_H / 2 * k, x + CHIP_W * k, mid + CHIP_H / 2 * k);
            fill.setColor(chosen ? LiveTheme.tile : 0x1AF5F5F7);
            c.drawRoundRect(row, round, round, fill);
            if (on && chosen) outline(c, round);
            chip.setColor(chosen ? LiveTheme.ink : TEXT);
            c.drawText(one.sizes[i], row.centerX(), mid + Ui.halfCap(chip), chip);
            x += (CHIP_W + CHIP_GAP) * k;
        }
    }

    private String ellipsize(String s, Paint p, float width) {
        fit.set(p);
        return TextUtils.ellipsize(s == null ? "" : s, fit, Math.max(1, width), TextUtils.TruncateAt.END).toString();
    }
}
