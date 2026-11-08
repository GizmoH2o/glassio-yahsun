package com.glasslauncher.tv.live;

import android.app.Dialog;
import android.content.Context;
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
 * Guide… › Search, full screen (Settings › Guide search): as Live TV's search, Apple TV's and Netflix's. Which channel
 * it's for at the top, the search bar under it with the microphone at its end, the keyboard at the left and the
 * guide's channels at the right, narrowed with every letter (no OK to search). Each row: its name, its ID and where it
 * comes from; the one in use has a check. The focused row takes the white plate, a little bigger, with what's on it
 * now. Typed nothing: the suggestions. OK on a row chooses it; Back closes. Searching runs off the screen's thread.
 */
final class LiveGuideSearch extends Dialog {
    /** Voice input: what was said, to `done` (null for nothing). */
    interface Voice {
        void listen(Consumer<String> done);
    }

    private final String header, nowId;
    private final List<String[]> all, suggestions;
    private final Consumer<String[]> onPick;
    private final Voice voice;
    private final Page page;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Map<String, String> onNow = new HashMap<>();      // guide ID: what's on it now ("" none)
    private String query;
    private List<String[]> results = new ArrayList<>();
    private int gen;
    private boolean searching;

    // The focus: the microphone, the keyboard, the results.
    private static final int BAR = 0, KEYS = 1, LIST = 2;
    private static final String LETTERS = "abcdefghijklmnopqrstuvwxyz1234567890";
    private static final String SYMBOLS = "&'-.,:!?#+()/@*\"_;=%$€£~[]<>|^{}\\`°§";
    private static final int COLS = 6, ROWS = 7, CHARS = 36, MAX_RESULTS = 200;
    private static final int PAGE_KEY = CHARS, SPACE_KEY = CHARS + 1, DELETE_KEY = CHARS + 2, CLEAR_KEY = CHARS + 3;
    private static final int[] BOTTOM_COL = {0, 1, 3, 4}, BOTTOM_SPAN = {1, 2, 1, 2};
    private boolean symbols;
    private int zone = KEYS, key, at;

    /**
     * `header`: the channel, as shown ("2094  Cinemax Hits US"). `all`: the guide's channels ({id, name, source}).
     * `suggestions`: shown while nothing's typed. `nowId`: the guide ID in use ("" none). `start`: typed already.
     */
    LiveGuideSearch(Context c, String header, String start, String nowId, List<String[]> all, List<String[]> suggestions,
            Voice voice, Consumer<String[]> onPick) {
        super(c, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        this.header = header;
        this.nowId = nowId == null ? "" : nowId;
        this.all = all;
        this.suggestions = suggestions;
        this.voice = voice;
        this.onPick = onPick;
        page = new Page(c);
        query = start == null ? "" : start;
        search();
        setOnDismissListener(d -> io.shutdownNow());
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
        LiveSpeech.say(page, LiveSpeech.join(Ui.tr("Buscar no guia", "Search the guide"), header));
    }

    // MARK: - Searching

    private void setQuery(String q) {
        query = q == null ? "" : q;
        at = 0;
        page.list.snap(0);
        search();
        page.invalidate();
    }

    /** The rows for what's typed: the suggestions for nothing, else every name or ID with it (in the background). */
    private void search() {
        final int mine = ++gen;
        if (query.trim().isEmpty()) {
            results = withNowFirst(suggestions);
            searching = false;
            lookUpOnNow();
            return;
        }
        searching = true;
        final String q = query;
        try {
            io.execute(() -> {
                final List<String[]> found = LiveView.guideSearch(q, all, MAX_RESULTS);
                page.post(() -> {
                    if (mine != gen) return;        // typed more since
                    results = found;
                    searching = false;
                    if (zone == LIST && results.isEmpty()) zone = KEYS;
                    at = Math.min(at, Math.max(0, results.size() - 1));
                    lookUpOnNow();
                    page.invalidate();
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {     // closed
        }
    }

    /** The guide in use first (when it's among them), then the rest. */
    private List<String[]> withNowFirst(List<String[]> list) {
        List<String[]> out = new ArrayList<>();
        String[] current = null;
        for (String[] g : all) {
            if (g[0].equals(nowId)) {
                current = g;
                break;
            }
        }
        if (current != null) out.add(current);
        for (String[] g : list) if (!g[0].equals(nowId)) out.add(g);
        return out;
    }

    /** What's on now on the focused row's guide channel, looked up once (shown when it comes). */
    private void lookUpOnNow() {
        if (zone != LIST || at >= results.size()) return;
        final String id = results.get(at)[0];
        if (onNow.containsKey(id)) return;
        onNow.put(id, "");
        final Context app = getContext().getApplicationContext();
        try {
            io.execute(() -> {
                long now = System.currentTimeMillis();
                String title = "";
                List<Xtream.Program> ps = XmltvStore.programs(app, id, now - 60_000, now + 60_000);
                for (Xtream.Program p : ps) {
                    if (p.start <= now && p.end > now) {
                        title = p.title;
                        break;
                    }
                }
                final String t = title;
                page.post(() -> {
                    onNow.put(id, t);
                    page.invalidate();
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
    }

    // MARK: - Keys

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) dismiss();
            return true;
        }
        if (code == KeyEvent.KEYCODE_HOME) return super.dispatchKeyEvent(e);
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (code == KeyEvent.KEYCODE_SEARCH || code == KeyEvent.KEYCODE_VOICE_ASSIST) {
            listen();
            return true;
        }
        if (code == KeyEvent.KEYCODE_DEL) {
            backspace();
            return true;
        }
        int ch = e.getUnicodeChar();
        if (ch > 0 && !Character.isISOControl(ch) && code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER) {
            setQuery(query + (char) ch);      // a remote's numbers, a keyboard's letters
            return true;
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: up(); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: down(); break;
            case KeyEvent.KEYCODE_DPAD_LEFT: left(); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: right(); break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (e.getRepeatCount() == 0) press();
                page.invalidate();
                return true;
            default:
                return true;
        }
        LiveSpeech.say(page, focusSaid());
        lookUpOnNow();
        page.invalidate();
        return true;
    }

    private String focusSaid() {
        if (zone == BAR) return Ui.tr("Busca por voz", "Voice search");
        if (zone == KEYS) {
            if (key < CHARS) return String.valueOf(chars().charAt(key));
            if (key == PAGE_KEY) return symbols ? Ui.tr("Letras", "Letters") : Ui.tr("Símbolos", "Symbols");
            if (key == SPACE_KEY) return Ui.tr("Espaço", "Space");
            if (key == DELETE_KEY) return Ui.tr("Apagar", "Delete");
            return Ui.tr("Limpar", "Clear");
        }
        if (at < results.size()) {
            String[] g = results.get(at);
            return LiveSpeech.join(g[1], g[0], g[0].equals(nowId) ? Ui.tr("Agora", "Now") : "");
        }
        return "";
    }

    private String chars() {
        return symbols ? SYMBOLS : LETTERS;
    }

    private int row() {
        return key < CHARS ? key / COLS : ROWS - 1;
    }

    private int col() {
        return key < CHARS ? key % COLS : BOTTOM_COL[key - CHARS];
    }

    private void keyAt(int row, int col) {
        if (row < ROWS - 1) {
            key = row * COLS + col;
            return;
        }
        int i = BOTTOM_COL.length - 1;
        while (i > 0 && BOTTOM_COL[i] > col) i--;
        key = CHARS + i;
    }

    private void up() {
        if (zone == KEYS) {
            if (row() == 0) zone = BAR; else keyAt(row() - 1, col());
        } else if (zone == LIST) {
            if (at > 0) at--; else zone = BAR;
        }
    }

    private void down() {
        if (zone == BAR) zone = KEYS;
        else if (zone == KEYS) {
            if (row() < ROWS - 1) keyAt(row() + 1, col());
        } else if (zone == LIST && at + 1 < results.size()) at++;
    }

    private void left() {
        if (zone == KEYS) {
            if (key >= CHARS) {
                if (key > CHARS) key--;
            } else if (col() > 0) keyAt(row(), col() - 1);
        } else if (zone == LIST) {
            zone = KEYS;
            keyAt(Math.min(row(), ROWS - 2), COLS - 1);
        }
    }

    private void right() {
        if (zone == KEYS) {
            if (key >= CHARS ? key < CLEAR_KEY : col() < COLS - 1) {
                if (key >= CHARS) key++; else keyAt(row(), col() + 1);
            } else if (!results.isEmpty()) zone = LIST;
        }
    }

    private void press() {
        if (zone == BAR) {
            listen();
        } else if (zone == KEYS) {
            if (key < CHARS) setQuery(query + chars().charAt(key));
            else if (key == PAGE_KEY) symbols = !symbols;
            else if (key == SPACE_KEY) {
                if (!query.isEmpty() && !query.endsWith(" ")) setQuery(query + " ");
            } else if (key == DELETE_KEY) backspace();
            else setQuery("");
        } else if (at < results.size()) {
            String[] g = results.get(at);
            dismiss();
            onPick.accept(g);
        }
    }

    private void backspace() {
        if (!query.isEmpty()) setQuery(query.substring(0, query.length() - 1));
    }

    private void listen() {
        if (voice == null) return;
        voice.listen(said -> {
            if (said == null || said.trim().isEmpty()) return;
            setQuery(said.trim());
            zone = KEYS;
            page.invalidate();
        });
    }

    // MARK: - Drawing

    /** The page, drawn by hand (1920 × 1080 space, k = width / 1920). */
    private final class Page extends View {
        private static final float X = 80, R = 1840, HEAD_Y = 84, BAR_TOP = 116, BAR_H = 96, KEY_W = 96, KEY_H = 76, GAP = 10,
                KEYS_TOP = 264, RES_X = 780, RES_TOP = 300, ROW_H = 88, ROW_STEP = 96, RES_BOTTOM = 1040;
        private final RectF r = new RectF();
        private final TextPaint fit = new TextPaint();
        private final Spring in = new Spring(0.32f, 0.9f, 0), list = new Spring(0.3f, 1f, 0), plate = new Spring(0.28f, 0.85f, 0);
        private Paint fill, stroke, headP, headName, barText, barHint, keyText, keyTextOn, count, name, id, side;
        private final android.graphics.Path path = new android.graphics.Path();
        private float k;
        private long last;

        Page(Context c) {
            super(c);
            in.target = 1;
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            k = w / 1920f;
            fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(3 * k);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            headP = Ui.text(Ui.regular, 26 * k, 0x99FFFFFF);
            headName = Ui.text(Ui.semibold, 26 * k, 0xFFF5F5F7);
            barText = Ui.text(Ui.medium, 38 * k, 0xFFF5F5F7);
            barHint = Ui.text(Ui.regular, 36 * k, 0x80FFFFFF);
            keyText = Ui.text(Ui.medium, 32 * k, 0xD9FFFFFF);
            keyText.setTextAlign(Paint.Align.CENTER);
            keyTextOn = Ui.text(Ui.semibold, 34 * k, 0xFFFFFFFF);
            keyTextOn.setTextAlign(Paint.Align.CENTER);
            count = Ui.text(Ui.medium, 24 * k, 0x80FFFFFF);
            name = Ui.text(Ui.medium, 32 * k, 0xE6FFFFFF);
            id = new Paint(Paint.ANTI_ALIAS_FLAG);
            id.setTypeface(Typeface.MONOSPACE);
            id.setTextSize(22 * k);
            id.setColor(0x80FFFFFF);
            side = Ui.text(Ui.regular, 24 * k, 0x80FFFFFF);
            side.setTextAlign(Paint.Align.RIGHT);
        }

        @Override protected void onDraw(Canvas c) {
            if (fill == null) return;
            long now = SystemClock.uptimeMillis();
            float dt = last == 0 ? 1 / 60f : Math.min(0.05f, (now - last) / 1000f);
            last = now;
            // The list keeps the focused row in view: it glides once the focus nears the bottom.
            int visible = (int) ((RES_BOTTOM - RES_TOP) / ROW_STEP);
            list.target = Math.max(0, Math.min(at - visible / 2, results.size() - visible)) * ROW_STEP;
            if (list.target < 0) list.target = 0;
            plate.target = zone == LIST ? 1 : 0;
            boolean moving = in.step(dt) | list.step(dt) | plate.step(dt);
            float a = Ui.clamp(in.value);
            LiveTheme.background(c, getWidth(), getHeight(), a);
            c.save();
            c.translate(0, (1 - a) * 40 * k);
            drawHead(c);
            drawBar(c, now);
            drawKeys(c);
            drawResults(c);
            c.restore();
            if (moving) postInvalidateOnAnimation(); else last = 0;
        }

        /** "Guide for 2094  Cinemax Hits US". */
        private void drawHead(Canvas c) {
            String lead = Ui.tr("Guia para ", "Guide for ");
            c.drawText(lead, X * k, HEAD_Y * k, headP);
            c.drawText(ellipsize(header, headName, (R - X) * k - headP.measureText(lead)), X * k + headP.measureText(lead), HEAD_Y * k, headName);
        }

        private void drawBar(Canvas c, long now) {
            float micW = BAR_H, barR = R - micW - 20;
            r.set(X * k, BAR_TOP * k, barR * k, (BAR_TOP + BAR_H) * k);
            fill.setColor(0x1FFFFFFF);
            c.drawRoundRect(r, BAR_H / 2 * k, BAR_H / 2 * k, fill);
            Symbols.draw(getContext(), c, "search", r.left + 52 * k, r.centerY(), 34 * k, 0xB3FFFFFF);
            float tx = r.left + 96 * k, room = r.width() - 130 * k;
            if (query.isEmpty()) {
                c.drawText(Ui.tr("Nome ou ID do canal no guia", "A guide channel's name or ID"), tx, r.centerY() + Ui.halfCap(barHint), barHint);
            } else {
                String shown = ellipsizeStart(query, barText, room);
                c.drawText(shown, tx, r.centerY() + Ui.halfCap(barText), barText);
                if ((now / 530) % 2 == 0 && zone != LIST) {
                    float cx = tx + barText.measureText(shown) + 6 * k;
                    fill.setColor(LiveTheme.text);
                    c.drawRect(cx, r.centerY() - 22 * k, cx + 3 * k, r.centerY() + 22 * k, fill);
                }
                postInvalidateDelayed(530);
            }
            if (voice == null) return;
            float mx = (R - micW / 2) * k, my = r.centerY();
            boolean on = zone == BAR;
            fill.setColor(on ? LiveTheme.tile : LiveTheme.surface);
            c.drawCircle(mx, my, (on ? BAR_H / 2 + 6 : BAR_H / 2) * k, fill);
            Symbols.draw(getContext(), c, "mic", mx, my, 36 * k, on ? LiveTheme.ink : 0xCCFFFFFF);
        }

        private void drawKeys(Canvas c) {
            String keys = chars();
            float left = X * k;
            for (int row = 0; row < ROWS; row++) {
                int cols = row < ROWS - 1 ? COLS : BOTTOM_COL.length;
                for (int i = 0; i < cols; i++) {
                    int startCol = row < ROWS - 1 ? i : BOTTOM_COL[i], span = row < ROWS - 1 ? 1 : BOTTOM_SPAN[i];
                    float x = left + startCol * (KEY_W + GAP) * k, y = (KEYS_TOP + row * (KEY_H + GAP)) * k;
                    r.set(x, y, x + (span * KEY_W + (span - 1) * GAP) * k, y + KEY_H * k);
                    int index = row < ROWS - 1 ? row * COLS + i : CHARS + i;
                    boolean on = zone == KEYS && key == index;
                    if (on) r.inset(-4 * k, -4 * k);
                    fill.setColor(on ? LiveTheme.tile : row == ROWS - 1 ? LiveTheme.surface : 0x0DFFFFFF);
                    c.drawRoundRect(r, 16 * k * LiveTheme.round, 16 * k * LiveTheme.round, fill);
                    int color = on ? LiveTheme.ink : 0xD9FFFFFF;
                    Paint p = on ? keyTextOn : keyText;
                    keyTextOn.setColor(LiveTheme.ink);
                    if (index < CHARS) {
                        String s = keys.substring(index, index + 1);
                        c.drawText(s, r.centerX(), r.centerY() + Ui.halfCap(p), p);
                    } else if (index == SPACE_KEY) {
                        fill.setColor(color);
                        c.drawRoundRect(r.centerX() - 36 * k, r.centerY() + 6 * k, r.centerX() + 36 * k, r.centerY() + 11 * k, 3 * k, 3 * k, fill);
                    } else if (index == DELETE_KEY) {
                        drawDelete(c, r.centerX(), r.centerY(), color);
                    } else {
                        float ts = p.getTextSize();
                        p.setTextSize(24 * k);
                        p.setColor(color);
                        String label = index == PAGE_KEY ? (symbols ? "abc" : "&?#") : Ui.tr("Limpar", "Clear");
                        c.drawText(label, r.centerX(), r.centerY() + Ui.halfCap(p), p);
                        p.setTextSize(ts);
                        keyText.setColor(0xD9FFFFFF);
                    }
                }
            }
        }

        private void drawResults(Canvas c) {
            float left = RES_X * k, right = R * k;
            String what = searching ? Ui.tr("Buscando…", "Searching…")
                    : query.trim().isEmpty() ? Ui.tr("Sugestões", "Suggestions")
                    : results.isEmpty() ? Ui.tr("Nenhum canal do guia com isso", "No guide channel has that")
                    : results.size() >= MAX_RESULTS ? Ui.tr("Os primeiros ", "The first ") + MAX_RESULTS + Ui.tr(": digite mais", ": type more")
                    : results.size() + (results.size() == 1 ? Ui.tr(" canal do guia", " guide channel") : Ui.tr(" canais do guia", " guide channels"));
            c.drawText(what, left + 24 * k, (RES_TOP - 28) * k, count);
            c.save();
            c.clipRect(left - 20 * k, (RES_TOP - 8) * k, right + 20 * k, RES_BOTTOM * k);
            float scroll = list.value * k;
            for (int i = 0; i < results.size(); i++) {
                float y = RES_TOP * k + i * ROW_STEP * k - scroll;
                if (y + ROW_H * k < (RES_TOP - 8) * k) continue;
                if (y > RES_BOTTOM * k) break;
                String[] g = results.get(i);
                boolean focused = zone == LIST && i == at, current = g[0].equals(nowId);
                r.set(left, y, right, y + ROW_H * k);
                if (focused) {
                    float grow = 8 * k * Ui.clamp(plate.value);
                    r.inset(-grow, -grow / 2);
                    fill.setColor(LiveTheme.tile);
                    c.drawRoundRect(r, 18 * k * LiveTheme.round, 18 * k * LiveTheme.round, fill);
                } else if (current) {
                    fill.setColor(LiveTheme.surface);
                    c.drawRoundRect(r, 18 * k * LiveTheme.round, 18 * k * LiveTheme.round, fill);
                }
                int ink = focused ? LiveTheme.ink : 0xE6FFFFFF, soft = focused ? (LiveTheme.ink & 0x00FFFFFF) | 0x99000000 : 0x80FFFFFF;
                float x = r.left + 28 * k, cy = r.centerY();
                if (current) {
                    Symbols.draw(getContext(), c, "check", x + 14 * k, cy, 28 * k, ink);
                } else {
                    // Its initial, in a small rounded square (as a logo would sit).
                    fill.setColor(focused ? 0x22000000 : 0x1FFFFFFF);
                    c.drawRoundRect(x, cy - 18 * k, x + 36 * k, cy + 18 * k, 8 * k, 8 * k, fill);
                    keyText.setColor(soft);
                    float ts = keyText.getTextSize();
                    keyText.setTextSize(20 * k);
                    String initial = initialOf(g[1]);
                    c.drawText(initial, x + 18 * k, cy + Ui.halfCap(keyText), keyText);
                    keyText.setTextSize(ts);
                    keyText.setColor(0xD9FFFFFF);
                }
                x += 60 * k;
                // On the right: what's on now (the focused row), "Now" (the one in use) or where it comes from.
                String right1 = focused && !onNowOf(g[0]).isEmpty() ? Ui.tr("Agora: ", "On now: ") + onNowOf(g[0])
                        : current ? Ui.tr("Em uso", "Now")
                        : LiveGuideSources.isProvider(g[2]) ? Ui.tr("Provedor", "Provider") : g[2];
                // The name first (up to over half the row), then its ID, and what's left for the right side.
                float end = r.right - 28 * k;
                name.setColor(ink);
                name.setTypeface(focused ? Ui.semibold : Ui.medium);
                String n = ellipsize(g[1], name, (end - x) * 0.55f);
                c.drawText(n, x, cy + Ui.halfCap(name), name);
                x += name.measureText(n) + 18 * k;
                id.setColor(soft);
                String idText = ellipsize(g[0], id, Math.min(320 * k, end - x));
                c.drawText(idText, x, cy + Ui.halfCap(id), id);
                x += id.measureText(idText) + 32 * k;
                side.setColor(soft);
                if (end - x > 60 * k) c.drawText(ellipsize(right1, side, end - x), end, cy + Ui.halfCap(side), side);
            }
            c.restore();
        }

        /** The name's first letter, past its country and quality tags ("4K: Sky Sports" → S). */
        private String initialOf(String guideName) {
            String key = LiveMerge.nameKey(guideName == null ? "" : guideName);
            if (key.isEmpty()) key = guideName == null ? "" : guideName.replaceAll("^[^\\p{L}\\p{N}]+", "");
            return key.isEmpty() ? "?" : key.substring(0, 1).toUpperCase(Ui.locale());
        }

        private String onNowOf(String guideId) {
            String t = onNow.get(guideId);
            return t == null ? "" : t;
        }

        /** ⌫: a key shape with an ×. */
        private void drawDelete(Canvas c, float x, float y, int color) {
            stroke.setColor(color);
            float s = 15 * k;
            path.reset();
            path.moveTo(x - s * 1.4f, y);
            path.lineTo(x - s * 0.6f, y - s);
            path.lineTo(x + s * 1.3f, y - s);
            path.lineTo(x + s * 1.3f, y + s);
            path.lineTo(x - s * 0.6f, y + s);
            path.close();
            c.drawPath(path, stroke);
            c.drawLine(x - s * 0.1f, y - s * 0.4f, x + s * 0.7f, y + s * 0.4f, stroke);
            c.drawLine(x + s * 0.7f, y - s * 0.4f, x - s * 0.1f, y + s * 0.4f, stroke);
        }

        private String ellipsize(String s, Paint p, float width) {
            fit.set(p);
            return TextUtils.ellipsize(s == null ? "" : s, fit, Math.max(1, width), TextUtils.TruncateAt.END).toString();
        }

        private String ellipsizeStart(String s, Paint p, float width) {
            fit.set(p);
            return TextUtils.ellipsize(s, fit, Math.max(1, width), TextUtils.TruncateAt.START).toString();
        }
    }
}
