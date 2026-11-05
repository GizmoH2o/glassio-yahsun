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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The player's Info tab, across the lower part of the picture: the tabs; at the left the show, its words and what can
 * be done with it; at the right what's later on the channel. The arrows move between the three, OK presses, Back
 * closes. The design's colors, corners and focus. The buttons are asked for again after a press, so a choice shows.
 */
final class LivePlayerInfo {
    static final class Item {
        final String text;
        final Runnable press;

        Item(String text, Runnable press) {
            this.text = text;
            this.press = press;
        }
    }

    static final class Later {
        final String time, title;
        final Runnable press;

        Later(String time, String title, Runnable press) {
            this.time = time;
            this.title = title;
            this.press = press;
        }
    }

    private static final int TABS = 0, ACTIONS = 1, LATER = 2;
    private static final float X = 96, R = 1824, MID = 1016, RIGHT_X = 1088, BOTTOM = 1020, TAB_TOP = 500, TAB_H = 58,
            CHIP_H = 62, CHIP_GAP = 14, TAB_GAP = 14, LATER_TOP = 664, LATER_H = 84, LATER_GAP = 14;
    private static final int TEXT = 0xFFF5F5F7, SOFT = 0xF2F5F5F7;

    private final LiveView view;
    private final Spring slide = new Spring(0.28f, 0.9f, 0);
    private final RectF r = new RectF();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), lift = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint fit = new TextPaint();
    private Paint heading, meta, tag, chip, tab, head, time, title, remind;
    private TextPaint words;
    private Shader shade;
    private float k = 1;
    private int w, h;
    private boolean open;
    private String show = "", metaText = "", tagText = "", body = "", laterHead = "";
    private int tagColor;
    private List<Item> tabs = new ArrayList<>(), items = new ArrayList<>();
    private List<Later> later = new ArrayList<>();
    private Supplier<List<Item>> source;
    private int area, tabOn, tabFocus, itemFocus, laterFocus;
    private float[] widths = new float[0], mids = new float[0], lefts = new float[0];
    private int[] line = new int[0];
    private int lineCount;
    private StaticLayout about;

    LivePlayerInfo(LiveView view) {
        this.view = view;
    }

    void size(float scale, int width, int height) {
        k = scale;
        w = width;
        h = height;
        heading = Ui.text(Ui.displaySemibold, 60 * k, TEXT);
        heading.setShadowLayer(10 * k, 0, 3 * k, 0xB3000000);
        meta = Ui.text(Ui.medium, 25 * k, TEXT);
        meta.setShadowLayer(7 * k, 0, 2 * k, 0xCC000000);
        tag = Ui.text(Ui.semibold, 22 * k, 0xFFFFFFFF);
        tag.setLetterSpacing(0.04f);
        chip = Ui.text(Ui.semibold, 25 * k, TEXT);
        tab = Ui.text(Ui.semibold, 26 * k, TEXT);
        head = Ui.text(Ui.semibold, 26 * k, TEXT);
        head.setShadowLayer(7 * k, 0, 2 * k, 0xCC000000);
        time = Ui.numbers(Ui.semibold, 26 * k, TEXT);
        title = Ui.text(Ui.medium, 26 * k, TEXT);
        remind = Ui.text(Ui.semibold, 22 * k, TEXT);
        words = new TextPaint(Ui.text(Ui.regular, 28 * k, TEXT));
        words.setShadowLayer(7 * k, 0, 2 * k, 0xCC000000);
        lift.setShadowLayer(24 * k, 0, 8 * k, 0x80000000);
        int base = LiveTheme.bg & 0x00FFFFFF;
        shade = new LinearGradient(0, 220 * k, 0, h, new int[] {base, 0xDB000000 | base, 0xF2000000 | base},
                new float[] {0, 0.4f, 1}, Shader.TileMode.CLAMP);
        about = null;
        if (source != null) layout();
    }

    boolean open() {
        return open;
    }

    /** The show (`tag`, its pill; `tabOn`, which of `tabs` this is), its `actions` and what's `later`. */
    void show(String show, String tag, int tagColor, String meta, String body, List<Item> tabs, int tabOn,
            Supplier<List<Item>> actions, String laterHead, List<Later> later) {
        this.show = show;
        tagText = tag;
        this.tagColor = tagColor;
        metaText = meta;
        this.body = body == null ? "" : body;
        this.tabs = tabs;
        this.tabOn = tabOn;
        source = actions;
        this.laterHead = laterHead;
        this.later = later;
        about = null;
        reload();
        area = ACTIONS;
        tabFocus = tabOn;
        itemFocus = 0;
        laterFocus = 0;
        open = true;
        slide.snap(0);
        slide.target = 1;
        if (!items.isEmpty()) view.announceForAccessibility(show + ", " + items.get(0).text);
        view.invalidate();
    }

    private void reload() {
        items = source.get();
        itemFocus = Math.min(itemFocus, Math.max(0, items.size() - 1));
        layout();
    }

    private void layout() {
        int n = items.size();
        widths = new float[n];
        for (int i = 0; i < n; i++) widths[i] = chip.measureText(items.get(i).text) / k + 52;
        line = lines(widths, MID - X, CHIP_GAP);
        lefts = new float[n];
        mids = new float[n];
        lineCount = 0;
        float x = X;
        for (int i = 0; i < n; i++) {
            if (i > 0 && line[i] != line[i - 1]) x = X;
            lefts[i] = x;
            mids[i] = x + widths[i] / 2;
            x += widths[i] + CHIP_GAP;
            lineCount = line[i] + 1;
        }
    }

    void close() {
        open = false;
        slide.target = 0;
    }

    boolean step(float dt) {
        return slide.step(dt);
    }

    /** Which line each button is on, filling each line in turn. Pure. */
    static int[] lines(float[] widths, float room, float gap) {
        int[] out = new int[widths.length];
        int on = 0;
        float used = 0;
        for (int i = 0; i < widths.length; i++) {
            float need = used == 0 ? widths[i] : used + gap + widths[i];
            if (used > 0 && need > room) {
                on++;
                need = widths[i];
            }
            used = need;
            out[i] = on;
        }
        return out;
    }

    /** The button on `toLine` whose middle is nearest x; -1 when there's no such line. Pure. */
    static int nearest(float[] mids, int[] line, int toLine, float x) {
        int best = -1;
        for (int i = 0; i < mids.length; i++) {
            if (line[i] != toLine) continue;
            if (best < 0 || Math.abs(mids[i] - x) < Math.abs(mids[best] - x)) best = i;
        }
        return best;
    }

    /** The arrows and OK; true for all of them, so they don't reach what's under it. */
    boolean key(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                across(code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                upDown(code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                press();
                break;
            default:
                return false;
        }
        view.invalidate();
        return true;
    }

    private void across(int dir) {
        if (area == TABS) {
            tabFocus = Math.max(0, Math.min(tabs.size() - 1, tabFocus + dir));
            view.announceForAccessibility(tabs.get(tabFocus).text);
        } else if (area == LATER) {
            if (dir < 0 && !items.isEmpty()) {
                area = ACTIONS;
                view.announceForAccessibility(items.get(itemFocus).text);
            }
        } else {
            int to = itemFocus + dir;
            if (to >= 0 && to < items.size() && line[to] == line[itemFocus]) {
                itemFocus = to;
                view.announceForAccessibility(items.get(itemFocus).text);
            } else if (dir > 0 && !later.isEmpty()) {
                area = LATER;
                laterFocus = Math.min(laterFocus, later.size() - 1);
                view.announceForAccessibility(later.get(laterFocus).time + ", " + later.get(laterFocus).title);
            }
        }
    }

    private void upDown(int dir) {
        if (area == TABS) {
            if (dir > 0 && !items.isEmpty()) area = ACTIONS;
            else if (dir > 0 && !later.isEmpty()) area = LATER;
            return;
        }
        if (area == LATER) {
            int to = laterFocus + dir;
            if (to < 0) {
                area = TABS;
            } else if (to < later.size()) {
                laterFocus = to;
                view.announceForAccessibility(later.get(to).time + ", " + later.get(to).title);
            }
            return;
        }
        int to = nearest(mids, line, line[itemFocus] + dir, mids[itemFocus]);
        if (to >= 0) {
            itemFocus = to;
            view.announceForAccessibility(items.get(to).text);
        } else if (dir < 0) {
            area = TABS;
            tabFocus = tabOn;
        }
    }

    private void press() {
        if (area == TABS) {
            if (tabFocus == tabOn) area = ACTIONS;
            else tabs.get(tabFocus).press.run();
            return;
        }
        if (area == LATER) {
            later.get(laterFocus).press.run();
            return;
        }
        if (items.isEmpty()) return;
        items.get(itemFocus).press.run();
        if (open) reload();
    }

    void draw(Canvas c) {
        fill.setColor(0xFF000000);       // a shader takes its paint's alpha
        fill.setShader(shade);
        c.drawRect(0, 220 * k, w, h, fill);
        fill.setShader(null);
        float a = Ui.clamp(slide.value);
        c.save();
        c.translate(0, (1 - a) * (1 - a) * 60 * k);
        drawTabs(c);
        drawShow(c);
        for (int i = 0; i < items.size(); i++) drawChip(c, i);
        drawLater(c);
        c.restore();
    }

    private void card(Canvas c, float round) {
        fill.setColor((LiveTheme.card & 0x00FFFFFF) | 0xF0000000);       // solid enough to read over any picture
        c.drawRoundRect(r, round, round, fill);
        fill.setColor(0x1FFFFFFF);
        c.drawRoundRect(r, round, round, fill);
        LiveTheme.rim(c, r, round, 1.5f * k);
    }

    private void focusFill(Canvas c, float round) {
        lift.setColor(LiveTheme.tile);
        c.drawRoundRect(r, round, round, lift);
    }

    /** Three lines of buttons push the tabs and the show up, to keep them off the words. */
    private float raise() {
        return lineCount > 2 ? 30 : 0;
    }

    private void drawTabs(Canvas c) {
        float x = X * k, round = LiveTheme.panelRound(14 * k), top = TAB_TOP - raise();
        for (int i = 0; i < tabs.size(); i++) {
            String s = tabs.get(i).text;
            float width = tab.measureText(s) + 56 * k;
            boolean on = area == TABS ? i == tabFocus : i == tabOn;
            r.set(x, top * k, x + width, (top + TAB_H) * k);
            if (on) focusFill(c, round);
            else card(c, round);
            tab.setColor(on ? LiveTheme.ink : TEXT);
            c.drawText(s, x + 28 * k, r.centerY() + Ui.halfCap(tab), tab);
            x += width + TAB_GAP * k;
        }
    }

    private void drawShow(Canvas c) {
        float left = X * k, room = (MID - X) * k;
        c.drawText(ellipsize(show, heading, room), left, (642 - raise()) * k, heading);
        float pillW = tag.measureText(tagText) + 28 * k, top = (668 - raise()) * k;
        r.set(left, top, left + pillW, top + 38 * k);
        fill.setColor(tagColor);
        float round = LiveTheme.panelRound(8 * k);
        c.drawRoundRect(r, round, round, fill);
        c.drawText(tagText, left + 14 * k, r.centerY() + Ui.halfCap(tag), tag);
        c.drawText(ellipsize(metaText, meta, room - pillW - 14 * k), r.right + 14 * k, r.centerY() + Ui.halfCap(meta), meta);
        if (body.isEmpty()) return;
        int maxLines = lineCount > 2 ? 2 : 3;
        if (about == null || about.getLineCount() > maxLines) {
            about = StaticLayout.Builder.obtain(body, 0, body.length(), words, Math.max(1, Math.round(room)))
                    .setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0, 1.14f).build();
        }
        c.save();
        c.translate(left, (720 - raise()) * k);
        about.draw(c);
        c.restore();
    }

    private void drawChip(Canvas c, int i) {
        float top = BOTTOM - (lineCount - line[i]) * CHIP_H - (lineCount - line[i] - 1) * CHIP_GAP;
        float round = LiveTheme.panelRound(14 * k);
        boolean on = area == ACTIONS && i == itemFocus;
        r.set(lefts[i] * k, top * k, (lefts[i] + widths[i]) * k, (top + CHIP_H) * k);
        int save = c.save();
        if (on) {
            c.scale(1.06f, 1.06f, r.centerX(), r.centerY());
            focusFill(c, round);
        } else {
            card(c, round);
        }
        chip.setColor(on ? LiveTheme.ink : TEXT);
        c.drawText(items.get(i).text, r.left + 26 * k, r.centerY() + Ui.halfCap(chip), chip);
        c.restoreToCount(save);
    }

    private void drawLater(Canvas c) {
        if (later.isEmpty()) return;
        float left = RIGHT_X * k, right = R * k;
        c.drawText(ellipsize(laterHead, head, right - left), left, 642 * k, head);
        String words = Ui.tr("Lembrar", "Remind me");
        float buttonW = remind.measureText(words) + 36 * k, round = LiveTheme.panelRound(18 * k);
        for (int i = 0; i < later.size(); i++) {
            Later one = later.get(i);
            float top = (LATER_TOP + i * (LATER_H + LATER_GAP)) * k;
            r.set(left, top, right, top + LATER_H * k);
            card(c, round);
            float mid = r.centerY();
            c.drawText(one.time, left + 24 * k, mid + Ui.halfCap(time), time);
            float textX = left + 24 * k + time.measureText("00:00 PM") + 18 * k;
            c.drawText(ellipsize(one.title, title, right - 24 * k - buttonW - 18 * k - textX), textX, mid + Ui.halfCap(title), title);
            boolean on = area == LATER && i == laterFocus;
            r.set(right - 24 * k - buttonW, mid - 24 * k, right - 24 * k, mid + 24 * k);
            float small = LiveTheme.panelRound(12 * k);
            if (on) {
                focusFill(c, small);
            } else {
                fill.setColor(0x24F5F5F7);
                c.drawRoundRect(r, small, small, fill);
            }
            remind.setColor(on ? LiveTheme.ink : TEXT);
            c.drawText(words, r.left + 18 * k, mid + Ui.halfCap(remind), remind);
        }
    }

    private String ellipsize(String s, Paint p, float width) {
        fit.set(p);
        return TextUtils.ellipsize(s == null ? "" : s, fit, Math.max(1, width), TextUtils.TruncateAt.END).toString();
    }
}
