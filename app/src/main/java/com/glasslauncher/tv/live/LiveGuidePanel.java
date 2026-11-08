package com.glasslauncher.tv.live;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Guide… on a channel, as a panel (Settings › Guide panel): it slides in at the right over what's behind, dimmed, as
 * Apple TV's side panels. At the top the channel (its logo, number, name and country); under it a card with the guide
 * in use (its name, ID, how it was chosen, what's on now and next); then the suggestions, the focused one on the white
 * plate with what's on it now (as Netflix's rows); and at the bottom the pills: Search, Type an ID, and Automatic when
 * it was chosen by hand. ↑ ↓ move, ← → between the pills, OK chooses, Back closes.
 */
final class LiveGuidePanel extends Dialog {
    /** What the panel is for and what it does. */
    static final class Spec {
        String number = "", name = "", country = "", logoUrl = "";
        /** The guide ID in use ("" none), its name in the guide (null unknown), and how it came ("" none). */
        String nowId = "", nowName, how = "";
        boolean byHand;
        List<String[]> suggestions = new ArrayList<>();
        Consumer<String[]> onPick;
        Runnable onSearch, onType, onAuto;
    }

    private final Spec spec;
    private final Page page;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Map<String, String> onNow = new HashMap<>();      // guide ID: what's on it now ("" none)
    private final List<String[]> rows = new ArrayList<>();
    private final List<Integer> pills = new ArrayList<>();
    private static final int SEARCH = 0, TYPE = 1, AUTO = 2;
    private String nowNext = "";
    /** The focus: a suggestion (0…), or a pill (rows.size() + its index). */
    private int focus;

    LiveGuidePanel(Context c, Spec spec) {
        super(c, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        this.spec = spec;
        for (String[] g : spec.suggestions) if (!g[0].equals(spec.nowId) && rows.size() < 5) rows.add(g);
        if (spec.onSearch != null) pills.add(SEARCH);
        pills.add(TYPE);
        if (spec.byHand) pills.add(AUTO);
        page = new Page(c);
        setOnDismissListener(d -> io.shutdownNow());
        lookUp();
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(page);
        Window w = getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        page.setFocusable(true);
        page.requestFocus();
        LiveSpeech.say(page, LiveSpeech.join(Ui.tr("Guia de ", "Guide for ") + spec.name, inUseSaid(), focusSaid()));
    }

    // MARK: - Looking up

    /** What's on the guide in use now and next, and on the focused suggestion now (off the screen's thread). */
    private void lookUp() {
        final Context app = getContext().getApplicationContext();
        final String id = focus < rows.size() ? rows.get(focus)[0] : null;
        final boolean card = nowNext.isEmpty() && !spec.nowId.isEmpty();
        if (id == null && !card || id != null && onNow.containsKey(id) && !card) return;
        if (id != null) onNow.put(id, "");
        try {
            io.execute(() -> {
                long now = System.currentTimeMillis();
                String cardText = null, rowText = null;
                if (card) {
                    List<Xtream.Program> ps = XmltvStore.programs(app, spec.nowId, now - 60_000, now + 6 * 3600_000L);
                    Xtream.Program on = null, next = null;
                    for (Xtream.Program p : ps) {
                        if (p.start <= now && p.end > now) on = p;
                        else if (p.start > now && next == null) next = p;
                    }
                    cardText = on == null ? Ui.tr("Nada no guia agora", "Nothing in the guide now")
                            : on.title + Ui.tr(" · até ", " · until ") + Ui.time(on.end)
                                    + (next == null ? "" : Ui.tr(" · depois ", " · then ") + next.title);
                }
                if (id != null) {
                    for (Xtream.Program p : XmltvStore.programs(app, id, now - 60_000, now + 60_000)) {
                        if (p.start <= now && p.end > now) {
                            rowText = p.title;
                            break;
                        }
                    }
                }
                final String ct = cardText, rt = rowText;
                page.post(() -> {
                    if (ct != null) nowNext = ct;
                    if (id != null && rt != null) onNow.put(id, rt);
                    page.invalidate();
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {     // closed
        }
    }

    // MARK: - Keys

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) close(null);
            return true;
        }
        if (code == KeyEvent.KEYCODE_HOME) return super.dispatchKeyEvent(e);
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        int last = rows.size() + pills.size() - 1;
        boolean onPills = focus >= rows.size();
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (onPills) focus = rows.isEmpty() ? focus : rows.size() - 1;
                else if (focus > 0) focus--;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (!onPills) focus = focus + 1 < rows.size() ? focus + 1 : rows.size();
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (onPills && focus > rows.size()) focus--;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (onPills && focus < last) focus++;
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (e.getRepeatCount() == 0) press();
                return true;
            default:
                return true;
        }
        LiveSpeech.say(page, focusSaid());
        lookUp();
        page.invalidate();
        return true;
    }

    private void press() {
        if (focus < rows.size()) {
            final String[] g = rows.get(focus);
            close(() -> spec.onPick.accept(g));
            return;
        }
        int pill = pills.get(focus - rows.size());
        close(pill == SEARCH ? spec.onSearch : pill == TYPE ? spec.onType : spec.onAuto);
    }

    /** Slides out, then does `then`. */
    private void close(Runnable then) {
        page.out(() -> {
            dismiss();
            if (then != null) then.run();
        });
    }

    private String pillLabel(int pill) {
        return pill == SEARCH ? Ui.tr("Buscar", "Search") : pill == TYPE ? Ui.tr("Digitar um ID", "Type an ID") : Ui.tr("Automático", "Automatic");
    }

    private String inUseSaid() {
        if (spec.nowId.isEmpty()) return Ui.tr("Sem guia agora", "No guide now");
        return LiveSpeech.join(Ui.tr("Em uso", "In use"), spec.nowName != null ? spec.nowName : spec.nowId, spec.how);
    }

    private String focusSaid() {
        if (focus < rows.size()) return LiveSpeech.join(rows.get(focus)[1], rows.get(focus)[0]);
        return pillLabel(pills.get(focus - rows.size()));
    }

    // MARK: - Drawing

    /** The page, drawn by hand (1920 × 1080 space, k = width / 1920). */
    private final class Page extends View {
        private static final float W = 940, M = 40, PAD = 48, ROW_H = 84, ROW_STEP = 92, PILL_H = 76;
        private final RectF r = new RectF();
        private final TextPaint fit = new TextPaint();
        private final Spring in = new Spring(0.34f, 0.92f, 0), plate = new Spring(0.26f, 0.85f, 0);
        private Paint fill, small, title, cardTitle, cardSub, name, id, side, label, pill, pillOn, letter;
        private float k;
        private long last;
        private Runnable after;

        Page(Context c) {
            super(c);
            in.target = 1;
        }

        void out(Runnable then) {
            after = then;
            in.target = 0;
            invalidate();
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            k = w / 1920f;
            fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            small = Ui.text(Ui.medium, 24 * k, 0x8CFFFFFF);
            title = Ui.text(Ui.semibold, 40 * k, 0xFFF5F5F7);
            cardTitle = Ui.text(Ui.semibold, 34 * k, 0xFFF5F5F7);
            cardSub = Ui.text(Ui.regular, 26 * k, 0xBFFFFFFF);
            name = Ui.text(Ui.medium, 30 * k, 0xE6FFFFFF);
            id = new Paint(Paint.ANTI_ALIAS_FLAG);
            id.setTypeface(Typeface.MONOSPACE);
            id.setTextSize(21 * k);
            side = Ui.text(Ui.regular, 23 * k, 0x80FFFFFF);
            side.setTextAlign(Paint.Align.RIGHT);
            label = Ui.text(Ui.semibold, 24 * k, 0x80FFFFFF);
            pill = Ui.text(Ui.medium, 26 * k, 0xE6FFFFFF);
            pillOn = Ui.text(Ui.semibold, 26 * k, LiveTheme.ink);
            letter = Ui.text(Ui.semibold, 20 * k, 0xB3FFFFFF);
            letter.setTextAlign(Paint.Align.CENTER);
        }

        @Override protected void onDraw(Canvas c) {
            if (fill == null) return;
            long now = SystemClock.uptimeMillis();
            float dt = last == 0 ? 1 / 60f : Math.min(0.05f, (now - last) / 1000f);
            last = now;
            plate.target = 1;
            boolean moving = in.step(dt) | plate.step(dt);
            float a = Ui.clamp(in.value);
            c.drawColor(Ui.alpha(0x99000000, a));       // what's behind, dimmed
            float left = (1920 - W - M) * k + (1 - a) * (W + M) * k, right = left + W * k;
            r.set(left, M * k, right, (1080 - M) * k);
            fill.setColor(LiveTheme.sheet());
            c.drawRoundRect(r, 40 * k * LiveTheme.round, 40 * k * LiveTheme.round, fill);
            if (LiveTheme.sheetRim() != 0) {
                fill.setStyle(Paint.Style.STROKE);
                fill.setStrokeWidth(1.5f * k);
                fill.setColor(LiveTheme.sheetRim());
                c.drawRoundRect(r, 40 * k * LiveTheme.round, 40 * k * LiveTheme.round, fill);
                fill.setStyle(Paint.Style.FILL);
            }
            float x = left + PAD * k, x2 = right - PAD * k, y = (M + PAD) * k;
            y = drawHeader(c, x, x2, y);
            y = drawCard(c, x, x2, y + 28 * k);
            drawRows(c, x, x2, y + 44 * k);
            drawPills(c, x, (1080 - M - PAD) * k);
            if (moving) postInvalidateOnAnimation();
            else {
                last = 0;
                if (in.target == 0 && after != null) {
                    Runnable then = after;
                    after = null;
                    then.run();
                }
            }
        }

        /** The channel: its logo on a plate, "Guide for", its number and name, its country. */
        private float drawHeader(Canvas c, float x, float x2, float y) {
            float lw = 150 * k, lh = 90 * k;
            r.set(x, y, x + lw, y + lh);
            fill.setColor(0x1FFFFFFF);
            c.drawRoundRect(r, 16 * k, 16 * k, fill);
            Bitmap logo = LiveLogos.get(getContext(), spec.logoUrl, this::postInvalidate);
            if (logo != null) {
                float s = Math.min((lw - 20 * k) / logo.getWidth(), (lh - 16 * k) / logo.getHeight());
                float w = logo.getWidth() * s, h = logo.getHeight() * s;
                RectF dst = new RectF(r.centerX() - w / 2, r.centerY() - h / 2, r.centerX() + w / 2, r.centerY() + h / 2);
                c.drawBitmap(logo, null, dst, fill);
            } else {
                letter.setTextSize(34 * k);
                c.drawText(initialOf(spec.name), r.centerX(), r.centerY() + Ui.halfCap(letter), letter);
                letter.setTextSize(20 * k);
            }
            float tx = x + lw + 28 * k;
            c.drawText(Ui.tr("Guia de", "Guide for"), tx, y + 30 * k, small);
            String head = (spec.number.isEmpty() ? "" : spec.number + "  ") + spec.name;
            float room = x2 - tx - (spec.country.isEmpty() ? 0 : 90 * k);
            String shown = ellipsize(head, title, room);
            c.drawText(shown, tx, y + 78 * k, title);
            if (!spec.country.isEmpty()) {      // the country, as a pill
                float px = tx + title.measureText(shown) + 16 * k, pw = small.measureText(spec.country) + 24 * k;
                r.set(px, y + 50 * k, px + pw, y + 84 * k);
                fill.setStyle(Paint.Style.STROKE);
                fill.setStrokeWidth(2 * k);
                fill.setColor(0x80FFFFFF);
                c.drawRoundRect(r, 10 * k, 10 * k, fill);
                fill.setStyle(Paint.Style.FILL);
                c.drawText(spec.country, px + 12 * k, r.centerY() + Ui.halfCap(small), small);
            }
            return y + lh;
        }

        /** The guide in use: how it came, its name and ID, what's on now and next. */
        private float drawCard(Canvas c, float x, float x2, float y) {
            float h = spec.nowId.isEmpty() ? 112 * k : 168 * k;
            r.set(x, y, x2, y + h);
            fill.setColor(0x1AFFFFFF);
            c.drawRoundRect(r, 24 * k * LiveTheme.round, 24 * k * LiveTheme.round, fill);
            float tx = x + 28 * k;
            if (spec.nowId.isEmpty()) {
                c.drawText(Ui.tr("Sem guia agora", "No guide now"), tx, y + 50 * k, cardTitle);
                c.drawText(Ui.tr("Escolha um abaixo, ou busque.", "Choose one below, or search."), tx, y + 88 * k, cardSub);
                return y + h;
            }
            Symbols.draw(getContext(), c, "check", tx + 12 * k, y + 36 * k, 22 * k, 0xB3FFFFFF);
            c.drawText(Ui.tr("Em uso", "In use") + (spec.how.isEmpty() ? "" : " · " + spec.how), tx + 34 * k, y + 36 * k + Ui.halfCap(small), small);
            String n = ellipsize(spec.nowName != null ? spec.nowName : spec.nowId, cardTitle, (x2 - tx) * 0.6f);
            c.drawText(n, tx, y + 92 * k, cardTitle);
            if (spec.nowName != null) {
                id.setColor(0x8CFFFFFF);
                c.drawText(ellipsize(spec.nowId, id, x2 - 28 * k - tx - cardTitle.measureText(n) - 16 * k), tx + cardTitle.measureText(n) + 16 * k, y + 92 * k, id);
            }
            c.drawText(ellipsize(nowNext.isEmpty() ? "…" : nowNext, cardSub, x2 - 28 * k - tx), tx, y + 138 * k, cardSub);
            return y + h;
        }

        private void drawRows(Canvas c, float x, float x2, float y) {
            if (rows.isEmpty()) return;
            c.drawText(Ui.tr("Sugestões", "Suggestions"), x + 4 * k, y, label);
            y += 22 * k;
            for (int i = 0; i < rows.size(); i++) {
                String[] g = rows.get(i);
                boolean on = i == focus;
                r.set(x, y + i * ROW_STEP * k, x2, y + i * ROW_STEP * k + ROW_H * k);
                if (on) {
                    float grow = 8 * k * Ui.clamp(plate.value);
                    r.inset(-grow, -grow / 2);
                    fill.setColor(LiveTheme.tile);
                    c.drawRoundRect(r, 18 * k * LiveTheme.round, 18 * k * LiveTheme.round, fill);
                }
                int ink = on ? LiveTheme.ink : 0xE6FFFFFF, soft = on ? (LiveTheme.ink & 0x00FFFFFF) | 0x99000000 : 0x80FFFFFF;
                float tx = r.left + 24 * k, cy = r.centerY();
                fill.setColor(on ? 0x22000000 : 0x1FFFFFFF);
                c.drawRoundRect(tx, cy - 18 * k, tx + 36 * k, cy + 18 * k, 8 * k, 8 * k, fill);
                letter.setColor(soft);
                c.drawText(initialOf(g[1]), tx + 18 * k, cy + Ui.halfCap(letter), letter);
                tx += 56 * k;
                float end = r.right - 24 * k;
                name.setColor(ink);
                name.setTypeface(on ? Ui.semibold : Ui.medium);
                String n = ellipsize(g[1], name, (end - tx) * 0.5f);
                c.drawText(n, tx, cy + Ui.halfCap(name), name);
                tx += name.measureText(n) + 16 * k;
                id.setColor(soft);
                String idText = ellipsize(g[0], id, Math.min(240 * k, end - tx));
                c.drawText(idText, tx, cy + Ui.halfCap(id), id);
                tx += id.measureText(idText) + 24 * k;
                String now = onNow.get(g[0]);
                String right = on && now != null && !now.isEmpty() ? Ui.tr("Agora: ", "On now: ") + now
                        : LiveGuideSources.isProvider(g[2]) ? Ui.tr("Provedor", "Provider") : g[2];
                side.setColor(soft);
                if (end - tx > 60 * k) c.drawText(ellipsize(right, side, end - tx), end, cy + Ui.halfCap(side), side);
            }
        }

        /** The pills along the bottom. */
        private void drawPills(Canvas c, float x, float bottom) {
            float px = x;
            for (int i = 0; i < pills.size(); i++) {
                int p = pills.get(i);
                boolean on = focus == rows.size() + i;
                String text = pillLabel(p);
                Paint t = on ? pillOn : pill;
                pillOn.setColor(LiveTheme.ink);
                float w = t.measureText(text) + 112 * k;
                r.set(px, bottom - PILL_H * k, px + w, bottom);
                if (on) r.inset(-4 * k, -4 * k);
                fill.setColor(on ? LiveTheme.tile : 0x1FFFFFFF);
                c.drawRoundRect(r, r.height() / 2, r.height() / 2, fill);
                Symbols.draw(getContext(), c, p == SEARCH ? "search" : p == TYPE ? "edit" : "history", r.left + 46 * k, r.centerY(), 26 * k,
                        on ? LiveTheme.ink : 0xD9FFFFFF);
                c.drawText(text, r.left + 76 * k, r.centerY() + Ui.halfCap(t), t);
                px += w + 16 * k;
            }
        }

        /** The name's first letter, past its country and quality tags ("4K: Sky Sports" → S). */
        private String initialOf(String guideName) {
            String key = LiveMerge.nameKey(guideName == null ? "" : guideName);
            if (key.isEmpty()) key = guideName == null ? "" : guideName.replaceAll("^[^\\p{L}\\p{N}]+", "");
            return key.isEmpty() ? "?" : key.substring(0, 1).toUpperCase(Ui.locale());
        }

        private String ellipsize(String s, Paint p, float width) {
            fit.set(p);
            return TextUtils.ellipsize(s == null ? "" : s, fit, Math.max(1, width), TextUtils.TruncateAt.END).toString();
        }
    }
}
