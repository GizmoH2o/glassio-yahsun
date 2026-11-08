package com.glasslauncher.tv.live;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * The player's Channels tab: what's on now across the bottom as cards, over the video, with its filters above and the
 * focused channel's later shows below. ◀ ▶ go along the cards, ▲ ▼ change the filter, OK tunes. Its colors, focus and
 * corners are the design's. Measurements in a 1920×1080 space.
 */
final class LiveRail {
    private static final int VISIBLE = 5;
    private static final float X = 96, CARD_W = 326, CARD_H = 214, CARD_GAP = 24, CHIP_Y = 640, CARD_Y = 686, LATER_Y = 966;
    private static final long UP_NEXT_MS = 120_000;

    /** Whether a show ending at `end` is in its last two minutes. Pure. */
    static boolean upNextDue(long now, long end) {
        long left = end - now;
        return left > 0 && left <= UP_NEXT_MS;
    }

    /** The first card in view: `first` moved as little as it takes to show `focus`. Pure. */
    static int window(int focus, int size, int visible, int first) {
        if (size <= visible) return 0;
        if (focus < first) first = focus;
        else if (focus >= first + visible) first = focus - visible + 1;
        return Math.max(0, Math.min(first, size - visible));
    }

    private final LiveView view;
    private final Live live;
    private final List<String> names = new ArrayList<>();
    private final List<List<Xtream.Channel>> lists = new ArrayList<>();
    private int filter, focus, first;
    private boolean open;
    private final Spring slide = new Spring(0.28f, 0.9f, 0);
    private float k = 1;
    private Paint name, show, note, chip, fill;
    private final TextPaint fit = new TextPaint();
    private final RectF r = new RectF(), logo = new RectF();

    LiveRail(LiveView view, Live live) {
        this.view = view;
        this.live = live;
    }

    void size(float scale) {
        k = scale;
        name = Ui.text(Ui.medium, 24 * k, 0xFFFFFFFF);
        show = Ui.text(Ui.semibold, 30 * k, 0xFFFFFFFF);
        note = Ui.text(Ui.medium, 25 * k, 0xFFFFFFFF);
        chip = Ui.text(Ui.semibold, 26 * k, 0xFFFFFFFF);
        fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    }

    boolean open() {
        return open;
    }

    void close() {
        open = false;
        slide.target = 0;
    }

    boolean step(float dt) {
        return slide.step(dt);
    }

    /** Opens on Recent (Favorites when `favorites`, or nothing's recent), the playing channel in focus when it's there. */
    void show(boolean favorites) {
        Xtream.Lineup lineup = view.lineup();
        if (lineup == null) return;
        names.clear();
        lists.clear();
        add(Ui.tr("Recentes", "Recent"), view.historyChannels());
        int recent = lists.size();
        if (lineup.categories.size() > 0) add(Ui.tr("Favoritos", "Favorites"), lineup.categories.get(0).channels);
        Xtream.Category group = view.playingCategory();
        boolean own = group != null && lineup.categories.indexOf(group) > 1;
        if (own) add(group.name, group.channels);
        if (lineup.categories.size() > 1) add(Ui.tr("Todos", "All"), lineup.categories.get(1).channels);
        if (lists.isEmpty()) return;
        filter = favorites && recent < lists.size() ? recent : 0;
        focusPlaying();
        open = true;
        slide.snap(0);
        slide.target = 1;
        view.invalidate();
    }

    private void add(String title, List<Xtream.Channel> list) {
        if (list == null || list.isEmpty()) return;
        names.add(title);
        lists.add(list);
    }

    private void focusPlaying() {
        focus = 0;
        Xtream.Channel on = live.playing();
        List<Xtream.Channel> list = lists.get(filter);
        for (int i = 0; on != null && i < list.size(); i++) if (list.get(i).id == on.id) focus = i;
        first = window(focus, list.size(), VISIBLE, 0);
    }

    /** ◀ ▶ ▲ ▼ and OK; false for the rest. */
    boolean key(int code) {
        List<Xtream.Channel> list = lists.get(filter);
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: {
                int to = Math.max(0, Math.min(list.size() - 1, focus + (code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1)));
                if (to != focus) {
                    focus = to;
                    first = window(focus, list.size(), VISIBLE, first);
                    Xtream.Channel ch = list.get(focus);
                    LiveSpeech.say(view, view.spoken(ch));
                }
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN: {
                int to = Math.max(0, Math.min(lists.size() - 1, filter + (code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1)));
                if (to != filter) {
                    filter = to;
                    focusPlaying();
                    LiveSpeech.say(view, names.get(filter));
                }
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                close();
                view.tuneFromPlayer(list.get(focus));
                return true;
            default:
                return false;
        }
    }

    void draw(Canvas c) {
        float a = Ui.clamp(slide.value);
        c.save();
        c.translate(0, (1 - a) * 50 * k);
        drawChips(c);
        List<Xtream.Channel> list = lists.get(filter);
        int last = Math.min(list.size(), first + VISIBLE);
        // The focused one last: it's bigger, over its neighbours.
        for (int i = first; i < last; i++) if (i != focus) card(c, list.get(i), i - first, false);
        if (focus >= first && focus < last) card(c, list.get(focus), focus - first, true);
        later(c, list.get(focus));
        c.restore();
    }

    private void drawChips(Canvas c) {
        float x = X * k;
        for (int i = 0; i < names.size(); i++) {
            String t = ellipsize(names.get(i), chip, 340 * k);
            float tw = chip.measureText(t);
            r.set(x, (CHIP_Y - 24) * k, x + tw + 44 * k, (CHIP_Y + 24) * k);
            boolean on = i == filter;
            fill.setColor(on ? LiveTheme.tile : LiveTheme.chip());
            c.drawRoundRect(r, 24 * k * Math.min(1, LiveTheme.round), 24 * k * Math.min(1, LiveTheme.round), fill);
            chip.setColor(on ? LiveTheme.ink : 0xFFFFFFFF);
            c.drawText(t, x + 22 * k, CHIP_Y * k + Ui.halfCap(chip), chip);
            x = r.right + 12 * k;
        }
    }

    private void card(Canvas c, Xtream.Channel ch, int slot, boolean on) {
        float left = (X + slot * (CARD_W + CARD_GAP)) * k, top = CARD_Y * k, wd = CARD_W * k, ht = CARD_H * k;
        c.save();
        if (on) {
            c.translate(0, -8 * k);
            c.scale(1.06f, 1.06f, left + wd / 2, top + ht / 2);
        }
        r.set(left, top, left + wd, top + ht);
        float rr = LiveTheme.panelRound(20 * k);
        int ink = on ? LiveTheme.ink : 0xFFFFFFFF;
        if (on) fill.setShadowLayer(24 * k, 0, 10 * k, 0x99000000);
        fill.setColor(on ? LiveTheme.tile : (LiveTheme.card & 0x00FFFFFF) | 0xE6000000);
        c.drawRoundRect(r, rr, rr, fill);
        fill.clearShadowLayer();
        if (!on) LiveTheme.rim(c, r, rr, 1.5f * k);
        float pad = 20 * k, inner = wd - 2 * pad;
        logo.set(left + pad, top + pad, left + pad + 112 * k, top + pad + 56 * k);
        view.drawLogoBare(c, ch, logo, 1, on && LiveTheme.lightFocus(), true);
        float badgeTop = top + pad;
        if (ch.num > 0) {
            note.setColor(ink);
            note.setAlpha(235);
            note.setTextAlign(Paint.Align.RIGHT);
            c.drawText(String.valueOf(ch.num), left + wd - pad, top + 52 * k, note);
            note.setTextAlign(Paint.Align.LEFT);
            badgeTop = top + 62 * k;
        }
        // Its resolution badge under the number, at the card's right.
        LiveBadge.drawTopRight(c, view.badgeOf(ch), left + wd - pad, badgeTop, note, k);
        long now = System.currentTimeMillis();
        Xtream.Program p = onNow(ch, now);
        show.setColor(ink);
        c.drawText(ellipsize(p == null ? ch.name : p.title, show, inner), left + pad, top + 122 * k, show);
        if (p != null) {
            note.setColor(ink);
            note.setAlpha(240);
            boolean here = live.playing() != null && live.playing().id == ch.id;
            String left2 = LiveView.minutesLeft((int) Math.max(1, Math.round((p.end - now) / 60_000.0)));
            c.drawText(ellipsize(here ? Ui.tr("Assistindo", "Watching") + "  ·  " + left2 : left2, note, inner), left + pad, top + 158 * k, note);
            float y = top + 182 * k, end = left + pad + inner * Ui.clamp((now - p.start) / (float) Math.max(1, p.end - p.start));
            fill.setColor(on ? (LiveTheme.ink & 0x00FFFFFF) | 0x33000000 : 0x40FFFFFF);
            c.drawRoundRect(left + pad, y, left + pad + inner, y + 6 * k, 3 * k, 3 * k, fill);
            fill.setColor(on ? LiveTheme.ink : LiveTheme.text);
            c.drawRoundRect(left + pad, y, Math.max(left + pad + 6 * k, end), y + 6 * k, 3 * k, 3 * k, fill);
        }
        c.restore();
    }

    private Xtream.Program onNow(Xtream.Channel ch, long now) {
        List<Xtream.Program> list = view.programsOf(ch);
        if (list != null) for (Xtream.Program p : list) if (p.start <= now && p.end > now) return p;
        return null;
    }

    private void later(Canvas c, Xtream.Channel ch) {
        long now = System.currentTimeMillis();
        float x = X * k, base = LATER_Y * k + Ui.halfCap(chip);
        chip.setColor(0xFFFFFFFF);
        String head = Ui.tr("Depois em ", "Later on ") + view.nameWithCountry(ch);
        head = ellipsize(head, chip, 460 * k);
        c.drawText(head, x, base, chip);
        x += chip.measureText(head) + 24 * k;
        List<Xtream.Program> list = view.programsOf(ch);
        int shown = 0;
        float room = (1920 - X - 300) * k;
        for (int i = 0; list != null && i < list.size() && shown < 3; i++) {
            Xtream.Program p = list.get(i);
            if (p.start <= now) continue;
            String t = ellipsize(Ui.time(p.start) + "  " + p.title, note, 330 * k);
            float tw = note.measureText(t);
            if (x + tw + 40 * k > room) break;
            r.set(x, (LATER_Y - 26) * k, x + tw + 40 * k, (LATER_Y + 26) * k);
            fill.setColor(LiveTheme.chip());
            float rr = LiveTheme.panelRound(14 * k);
            c.drawRoundRect(r, rr, rr, fill);
            note.setColor(0xFFFFFFFF);
            c.drawText(t, x + 20 * k, LATER_Y * k + Ui.halfCap(note), note);
            x = r.right + 14 * k;
            shown++;
        }
        note.setColor(0xFFFFFFFF);
        note.setTextAlign(Paint.Align.RIGHT);
        c.drawText(Ui.tr("OK para assistir  ·  Voltar fecha", "OK tunes  ·  Back closes"), (1920 - X) * k, base, note);
        note.setTextAlign(Paint.Align.LEFT);
    }

    private String ellipsize(String s, Paint p, float width) {
        fit.set(p);
        return TextUtils.ellipsize(s == null ? "" : s, fit, Math.max(1, width), TextUtils.TruncateAt.END).toString();
    }
}
