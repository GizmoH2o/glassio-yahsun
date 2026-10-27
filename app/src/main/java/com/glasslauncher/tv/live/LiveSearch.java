package com.glasslauncher.tv.live;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Live TV's search, as YouTube TV's and Apple TV's: the search bar across the top with the microphone at its end
 * (voice search: Google's voice input, or the remote's search key here), the keyboard at the left, and the results
 * at the right as rows of tiles, the channels (by name or number) then the shows (by title: the full guide, and the
 * guides on the TV), each with its picture, in three rows: on now, catch-up (over, on a channel that keeps it) and
 * later. The rows glide up so the focused one stays in view. Typed nothing yet: the channels watched lately.
 * ← → ↑ ↓ move between them, OK types a letter or picks; a remote's or a keyboard's letters and numbers type too.
 */
final class LiveSearch extends Dialog {
    private final LiveView view;
    private final Live live;
    private final Page page;
    private String query = "";
    private final List<Xtream.Channel> channels = new ArrayList<>();
    /** A show found: its channel, the airing, and when (as shown under it). */
    private static final class Hit {
        final Xtream.Channel ch;
        final Xtream.Program p;
        final String when;

        Hit(Xtream.Channel ch, Xtream.Program p, String when) {
            this.ch = ch;
            this.p = p;
            this.when = when;
        }
    }

    // The shows found, in three rows: on now, catch-up (over, and its channel keeps it), coming up later.
    private static final int ON_NOW = 0, CATCHUP = 1, LATER = 2, GROUPS = 3;
    private final List<List<Hit>> groups = java.util.Arrays.asList(new ArrayList<Hit>(), new ArrayList<Hit>(), new ArrayList<Hit>());
    private boolean searchingShows;
    private int gen;

    // The focus: the microphone, the keyboard, the channels' row, a shows' row (SHOWS + its group).
    private static final int BAR = 0, KEYS = 1, CHANNELS = 2, SHOWS = 3;
    // Two pages of keys (letters and numbers; symbols), six rows of six, then the bottom row: the page switch, space,
    // delete, clear (BOTTOM_COL: where each starts, BOTTOM_SPAN: how many columns it takes).
    private static final String LETTERS = "abcdefghijklmnopqrstuvwxyz1234567890";
    private static final String SYMBOLS = "&'-.,:!?#+()/@*\"_;=%$€£~[]<>|^{}\\`°§";
    private static final int COLS = 6, ROWS = 7, CHARS = 36;
    private static final int PAGE_KEY = CHARS, SPACE_KEY = CHARS + 1, DELETE_KEY = CHARS + 2, CLEAR_KEY = CHARS + 3;
    private static final int[] BOTTOM_COL = {0, 1, 3, 4}, BOTTOM_SPAN = {1, 2, 1, 2};
    private boolean symbols;
    private int zone = KEYS, key, chAt;
    /** In the bar: the focus on Follow (else on the microphone). */
    private boolean onFollow;
    private final int[] showAt = new int[GROUPS];
    private final int[] rowBuf = new int[1 + GROUPS];

    LiveSearch(Context c, LiveView view, Live live, String start) {
        super(c, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        this.view = view;
        this.live = live;
        page = new Page(c);
        query = start == null ? "" : start;
        search();
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
        page.announceForAccessibility(Ui.tr("Buscar. Digite ou aperte o microfone para falar.", "Search. Type, or press the microphone to speak."));
    }

    // MARK: - Searching

    /** The query typed (or said): the channels now, the shows from the guide as they come. */
    void setQuery(String q) {
        query = q == null ? "" : q;
        chAt = 0;
        java.util.Arrays.fill(showAt, 0);
        search();
        if (zone >= CHANNELS && rowOf(zone) < 0) zone = KEYS;     // that row has nothing now
        page.invalidate();
    }

    private void search() {
        channels.clear();
        for (List<Hit> g : groups) g.clear();
        final int mine = ++gen;
        Xtream.Lineup lineup = view.lineup();
        String q = Xtream.fold(query);
        if (lineup == null) return;
        if (q.isEmpty()) {      // nothing typed: what was watched lately
            channels.addAll(view.historyChannels());
            searchingShows = false;
            return;
        }
        String[] words = q.split(" ");
        List<Xtream.Channel> rest = new ArrayList<>();
        for (Xtream.Channel ch : lineup.all()) {
            boolean match = true;
            for (String word : words) if (!ch.key.contains(word)) match = false;
            if (!match && !q.equals(String.valueOf(ch.num))) continue;
            if (ch.key.startsWith(words[0])) channels.add(ch); else rest.add(ch);
            if (channels.size() + rest.size() >= 60) break;
        }
        channels.addAll(rest);
        // The shows: the guide already loaded at once, the full guide after (a background thread). Back as far as
        // the channels' catch-up goes.
        Set<String> seen = new HashSet<>();
        long now = System.currentTimeMillis();
        int archive = 0;
        for (Xtream.Channel ch : lineup.all()) archive = Math.max(archive, ch.archive);
        final long from = now - Math.min(14, archive) * 24 * 3600_000L;
        for (Object[] f : view.loadedShows(words, 120, from)) addShow((Xtream.Channel) f[0], (Xtream.Program) f[1], seen, now);
        sortGroups();
        if (query.trim().length() < 3) {
            searchingShows = false;
            return;
        }
        searchingShows = true;
        final Context app = getContext().getApplicationContext();
        final long to = now + LiveOptions.daysAhead(app) * 24 * 3600_000L;
        final boolean full = LiveOptions.fullGuide(app);
        new Thread(() -> {
            List<XmltvStore.Found> found = full ? XmltvStore.findAll(app, words, from, to, 300) : new ArrayList<XmltvStore.Found>();
            List<Object[]> saved = savedShows(app, lineup, words, from);
            page.post(() -> {
                if (mine != gen) return;
                searchingShows = false;
                long at = System.currentTimeMillis();
                for (Object[] f : saved) addShow((Xtream.Channel) f[0], (Xtream.Program) f[1], seen, at);
                Xtream.Lineup l = view.lineup();
                if (l != null && !found.isEmpty()) {
                    Map<String, List<Xtream.Channel>> byEpg = new java.util.HashMap<>();
                    for (Xtream.Channel ch : l.all()) {
                        if (ch.epg == null || ch.epg.isEmpty()) continue;
                        String id = ch.epg.toLowerCase(Locale.ROOT);
                        List<Xtream.Channel> list = byEpg.get(id);
                        if (list == null) byEpg.put(id, list = new ArrayList<>());
                        list.add(ch);
                    }
                    for (XmltvStore.Found f : found) {
                        List<Xtream.Channel> chs = f.epg == null ? null : byEpg.get(f.epg.toLowerCase(Locale.ROOT));
                        if (chs == null) continue;
                        Xtream.Program p = new Xtream.Program();
                        p.title = f.title;
                        p.start = f.start;
                        p.end = f.end;
                        p.icon = f.icon == null ? "" : f.icon;
                        Xtream.mark(p);
                        for (Xtream.Channel ch : chs) addShow(ch, p, seen, at);
                    }
                }
                sortGroups();
                page.invalidate();
            });
        }, "search").start();
    }

    /**
     * The guides saved on the TV (every channel browsed, kept between visits): shows ending after `from` with every
     * word, a few per channel, by start. Background thread.
     */
    private static List<Object[]> savedShows(Context c, Xtream.Lineup lineup, String[] words, long from) {
        List<Object[]> out = new ArrayList<>();
        java.io.File[] files = new java.io.File(c.getCacheDir(), "live-epg").listFiles();
        for (int i = 0; files != null && i < files.length && out.size() < 120; i++) {
            String n = files[i].getName();
            if (!n.endsWith(".json")) continue;
            int id;
            try {
                id = Integer.parseInt(n.substring(0, n.length() - 5));
            } catch (NumberFormatException e) {
                continue;
            }
            Xtream.Channel ch = lineup.find(id);
            Xtream.SavedGuide g = ch == null ? null : Xtream.savedGuide(c, id);
            if (g == null) continue;
            int found = 0;
            for (Xtream.Program p : g.programs) {
                if (p.end <= from) continue;
                String t = Xtream.fold(p.title);
                boolean match = true;
                for (String w : words) if (!t.contains(w)) match = false;
                if (match) {
                    out.add(new Object[] {ch, p});
                    if (++found >= 8) break;
                }
            }
        }
        java.util.Collections.sort(out, (a, b) -> Long.compare(((Xtream.Program) a[1]).start, ((Xtream.Program) b[1]).start));
        return out;
    }

    /** Show `p` on `ch`, in its row: on now, catch-up (only when the channel keeps it), or later. */
    private void addShow(Xtream.Channel ch, Xtream.Program p, Set<String> seen, long now) {
        if (ch == null || p == null) return;
        int g = p.start <= now && p.end > now ? ON_NOW : p.end <= now ? CATCHUP : LATER;
        if (g == CATCHUP && !LiveView.replayable(ch, p, now)) return;
        List<Hit> list = groups.get(g);
        if (list.size() >= 40 || !seen.add(ch.id + ":" + p.start)) return;
        // The same show on another copy of the channel (same name, other quality or list): once.
        if (!seen.add(ch.name.trim().toLowerCase(Locale.ROOT) + "|" + p.title + "|" + p.start)) return;
        list.add(new Hit(ch, p, whenOf(g, p, now)));
    }

    /** Under a show: "Now", "Thu 22, 8:00 PM" (catch-up, or later on another day), "9:30 PM" (later today). */
    private static String whenOf(int group, Xtream.Program p, long now) {
        if (group == ON_NOW) return Ui.tr("Agora", "Now");
        java.util.Calendar a = java.util.Calendar.getInstance(), b = java.util.Calendar.getInstance();
        a.setTimeInMillis(now);
        b.setTimeInMillis(p.start);
        boolean today = a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR)
                && a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR);
        if (group == LATER && today) return Ui.time(p.start);
        return new java.text.SimpleDateFormat("EEE d", Ui.locale()).format(new java.util.Date(p.start)) + ", " + Ui.time(p.start);
    }

    /** On now and later, soonest first; catch-up, the latest first. */
    private void sortGroups() {
        java.util.Collections.sort(groups.get(ON_NOW), (a, b) -> Long.compare(a.p.start, b.p.start));
        java.util.Collections.sort(groups.get(CATCHUP), (a, b) -> Long.compare(b.p.start, a.p.start));
        java.util.Collections.sort(groups.get(LATER), (a, b) -> Long.compare(a.p.start, b.p.start));
    }

    /** The result rows on the page, top to bottom (their zones, in rowBuf): the channels, then each kind of show found. */
    private int resultRows() {
        int n = 0;
        if (!channels.isEmpty()) rowBuf[n++] = CHANNELS;
        for (int g = 0; g < GROUPS; g++) if (!groups.get(g).isEmpty()) rowBuf[n++] = SHOWS + g;
        return n;
    }

    /** Where zone `z` is among the result rows (-1: not there). */
    private int rowOf(int z) {
        int n = resultRows();
        for (int i = 0; i < n; i++) if (rowBuf[i] == z) return i;
        return -1;
    }

    private int countOf(int z) {
        return z == CHANNELS ? channels.size() : z >= SHOWS ? groups.get(z - SHOWS).size() : 0;
    }

    private int atOf(int z) {
        return z == CHANNELS ? chAt : z >= SHOWS ? showAt[z - SHOWS] : 0;
    }

    private void setAt(int z, int i) {
        if (z == CHANNELS) chAt = i;
        else if (z >= SHOWS) showAt[z - SHOWS] = i;
    }

    // MARK: - Keys

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) dismiss();
            return true;
        }
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
                break;
            case KeyEvent.KEYCODE_HOME:
                return super.dispatchKeyEvent(e);
            default:
                break;
        }
        page.invalidate();
        return true;
    }

    private String chars() {
        return symbols ? SYMBOLS : LETTERS;
    }

    private int row() {
        return key < CHARS ? key / COLS : ROWS - 1;
    }

    /** The column a key starts at (the bottom row's keys are wider). */
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
        } else if (zone >= CHANNELS) {
            int i = rowOf(zone);
            zone = i > 0 ? rowBuf[i - 1] : BAR;
        }
    }

    /** Whether what's typed can be followed (a team, a show: three letters or more). */
    private boolean followable() {
        return Xtream.fold(query).replace(" ", "").length() >= 3;
    }

    private void down() {
        onFollow = false;
        if (zone == BAR) zone = KEYS;
        else if (zone == KEYS) {
            if (row() < ROWS - 1) keyAt(row() + 1, col());
        } else if (zone >= CHANNELS) {
            int i = rowOf(zone), n = resultRows();
            if (i >= 0 && i + 1 < n) zone = rowBuf[i + 1];
        }
    }

    private void left() {
        if (zone == BAR) {
            if (followable()) onFollow = true;
            return;
        }
        if (zone == KEYS) {
            if (key >= CHARS) {
                if (key > CHARS) key--;
            } else if (col() > 0) keyAt(row(), col() - 1);
        } else if (zone >= CHANNELS) {
            int at = atOf(zone);
            if (at > 0) setAt(zone, at - 1); else toKeys();
        }
    }

    private void right() {
        if (zone == BAR) {
            onFollow = false;
            return;
        }
        if (zone == KEYS) {
            if (key >= CHARS ? key < CLEAR_KEY : col() < COLS - 1) {
                if (key >= CHARS) key++; else keyAt(row(), col() + 1);
            } else if (resultRows() > 0) zone = rowBuf[0];
        } else if (zone >= CHANNELS) {
            setAt(zone, Math.max(0, Math.min(countOf(zone) - 1, atOf(zone) + 1)));
        }
    }

    private void toKeys() {
        zone = KEYS;
        keyAt(Math.min(row(), ROWS - 2), COLS - 1);
    }

    private void press() {
        switch (zone) {
            case BAR:
                if (onFollow && followable()) toggleFollow(); else listen();
                return;
            case KEYS:
                if (key < CHARS) setQuery(query + chars().charAt(key));
                else if (key == PAGE_KEY) {
                    symbols = !symbols;
                    page.announceForAccessibility(symbols ? Ui.tr("Símbolos", "Symbols") : Ui.tr("Letras", "Letters"));
                } else if (key == SPACE_KEY) {
                    if (!query.isEmpty() && !query.endsWith(" ")) setQuery(query + " ");
                } else if (key == DELETE_KEY) backspace();
                else setQuery("");
                return;
            case CHANNELS:
                if (chAt < channels.size()) {
                    Xtream.Channel ch = channels.get(chAt);
                    dismiss();
                    view.watchFromSearch(ch);
                }
                return;
            default:
                List<Hit> list = zone >= SHOWS ? groups.get(zone - SHOWS) : null;
                int at = atOf(zone);
                if (list != null && at < list.size()) {
                    Hit hit = list.get(at);
                    dismiss();
                    view.showFromSearch(hit.ch, hit.p);
                }
        }
    }

    /** Follow what's typed (every airing of it reminded, a row on Home), or stop following it. */
    private void toggleFollow() {
        Context c = getContext();
        String term = query.trim();
        if (LiveFollow.follows(c, term)) {
            LiveFollow.remove(c, term);
            view.flash(Ui.tr("Deixou de seguir ", "Not following ") + term);
        } else {
            LiveFollow.add(c, term);
            view.flash(Ui.tr("Seguindo ", "Following ") + term + Ui.tr(": um lembrete a cada vez que passar", ": a reminder every time it's on"));
        }
        page.invalidate();
    }

    private void backspace() {
        if (!query.isEmpty()) setQuery(query.substring(0, query.length() - 1));
    }

    /** Google's voice input; what was said becomes the search. */
    private void listen() {
        live.voiceSearch(said -> {
            if (said != null && !said.trim().isEmpty()) {
                if (view.voiceWatch(said)) {        // "watch …": that channel, at once
                    dismiss();
                    return;
                }
                setQuery(said.trim());
                zone = resultRows() > 0 ? rowBuf[0] : KEYS;
                page.invalidate();
            }
        });
    }

    // MARK: - Drawing

    /** The page, drawn by hand (1920 × 1080 space, k = width / 1920). */
    private final class Page extends View {
        private static final float X = 80, R = 1840, BAR_TOP = 72, BAR_H = 96, KEY_W = 96, KEY_H = 76, GAP = 10,
                KEYS_TOP = 220, RES_X = 780, CH_TOP = 268, CH_W = 220, CH_H = 132, CH_STEP = 332;
        /** The shows' cards by style (as Live TV's Home: bigger for YouTube TV, smaller for Sky). */
        private float SH_W = 304, SH_H = 171, SH_STEP = 340;
        private final RectF r = new RectF(), logo = new RectF(), img = new RectF();
        private final TextPaint fit = new TextPaint();
        private final Spring in = new Spring(0.32f, 0.9f, 0), chScroll = new Spring(0.3f, 1f, 0), vScroll = new Spring(0.3f, 1f, 0);
        private final Spring[] shScroll = {new Spring(0.3f, 1f, 0), new Spring(0.3f, 1f, 0), new Spring(0.3f, 1f, 0)};
        private Paint fill, stroke, barText, barHint, keyText, keyTextOn, heading, name, nameOn, sub, empty;
        private final android.graphics.Path path = new android.graphics.Path();
        private float k;
        private long last;

        Page(Context c) {
            super(c);
            in.target = 1;
            switch (LiveTheme.layoutStyle()) {
                case LiveTheme.APPLE:
                case LiveTheme.DISNEY: SH_W = 352; break;
                case LiveTheme.YOUTUBE: SH_W = 368; break;
                case LiveTheme.NOW:
                case LiveTheme.NETFLIX: SH_W = 320; break;
                case LiveTheme.SKY: SH_W = 272; break;
                case LiveTheme.SLING:
                case LiveTheme.PLEX:
                case LiveTheme.PLUTO: SH_W = 288; break;
                case LiveTheme.HULU:
                case LiveTheme.MAX:
                case LiveTheme.PRIME: SH_W = 336; break;
                default: break;
            }
            SH_H = SH_W * 9 / 16;
            SH_STEP = SH_H + 169;
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            k = w / 1920f;
            fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(3 * k);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            barText = Ui.text(Ui.medium, 38 * k, 0xFFF5F5F7);
            barHint = Ui.text(Ui.regular, 36 * k, 0x80FFFFFF);
            keyText = Ui.text(Ui.medium, 32 * k, 0xD9FFFFFF);
            keyText.setTextAlign(Paint.Align.CENTER);
            keyTextOn = Ui.text(Ui.semibold, 34 * k, 0xFFFFFFFF);
            keyTextOn.setTextAlign(Paint.Align.CENTER);
            heading = Ui.text(Ui.semibold, 30 * k, 0xE6FFFFFF);
            name = Ui.text(Ui.medium, 24 * k, 0x99FFFFFF);
            nameOn = Ui.text(Ui.semibold, 24 * k, 0xFFF5F5F7);
            sub = Ui.text(Ui.regular, 22 * k, 0x80FFFFFF);
            empty = Ui.text(Ui.regular, 27 * k, 0x80FFFFFF);
        }

        @Override protected void onDraw(Canvas c) {
            if (fill == null) return;
            long now = SystemClock.uptimeMillis();
            float dt = last == 0 ? 1 / 60f : Math.min(0.05f, (now - last) / 1000f);
            last = now;
            boolean moving = in.step(dt) | chScroll.step(dt) | vScroll.step(dt);
            for (Spring sc : shScroll) moving |= sc.step(dt);
            Marquee.moving = false;
            float a = Ui.clamp(in.value);
            LiveTheme.background(c, getWidth(), getHeight(), a);
            c.save();
            c.translate(0, (1 - a) * 40 * k);
            drawBar(c);
            drawKeys(c);
            drawResults(c, System.currentTimeMillis());
            c.restore();
            if (moving || Marquee.moving) postInvalidateOnAnimation(); else last = 0;
        }

        private void drawBar(Canvas c) {
            float micW = BAR_H, chipR = R - micW - 20;
            // Follow (what's typed): a pill left of the microphone, while there's something to follow.
            if (followable()) {
                boolean following = LiveFollow.follows(getContext(), query.trim()), on = zone == BAR && onFollow;
                String label = following ? Ui.tr("Seguindo", "Following") : Ui.tr("Seguir", "Follow");
                float cw = barText.measureText(label) / k + 110;
                r.set((chipR - cw) * k, BAR_TOP * k, chipR * k, (BAR_TOP + BAR_H) * k);
                if (on) r.inset(-4 * k, -4 * k);
                fill.setColor(on ? LiveTheme.tile : following ? 0x33FFFFFF : LiveTheme.surface);
                c.drawRoundRect(r, r.height() / 2, r.height() / 2, fill);
                Symbols.draw(getContext(), c, following ? "check" : "add", r.left + 44 * k, r.centerY(), 30 * k, on ? LiveTheme.ink : 0xE6FFFFFF);
                barText.setColor(on ? LiveTheme.ink : 0xFFF5F5F7);
                c.drawText(label, r.left + 74 * k, r.centerY() + Ui.halfCap(barText), barText);
                barText.setColor(0xFFF5F5F7);
                chipR -= cw + 20;
            }
            r.set(X * k, BAR_TOP * k, chipR * k, (BAR_TOP + BAR_H) * k);
            fill.setColor(0x1FFFFFFF);
            c.drawRoundRect(r, BAR_H / 2 * k, BAR_H / 2 * k, fill);
            Symbols.draw(getContext(), c, "search", r.left + 52 * k, r.centerY(), 34 * k, 0xB3FFFFFF);
            float tx = r.left + 96 * k, room = r.width() - 130 * k;
            if (query.isEmpty()) {
                c.drawText(Ui.tr("Busque canais e programas", "Search channels and shows"), tx, r.centerY() + Ui.halfCap(barHint), barHint);
            } else {
                String shown = ellipsizeStart(query, barText, room);
                c.drawText(shown, tx, r.centerY() + Ui.halfCap(barText), barText);
                // The caret, blinking.
                if ((SystemClock.uptimeMillis() / 530) % 2 == 0) {
                    float cx = tx + barText.measureText(shown) + 6 * k;
                    fill.setColor(LiveTheme.text);
                    c.drawRect(cx, r.centerY() - 22 * k, cx + 3 * k, r.centerY() + 22 * k, fill);
                }
                postInvalidateDelayed(530);
            }
            // The microphone: voice search.
            float mx = (R - micW / 2) * k, my = r.centerY();
            boolean on = zone == BAR && !(onFollow && followable());
            fill.setColor(on ? LiveTheme.tile : LiveTheme.surface);
            c.drawCircle(mx, my, (on ? BAR_H / 2 + 6 : BAR_H / 2) * k, fill);
            Symbols.draw(getContext(), c, "mic", mx, my, 36 * k, on ? LiveTheme.ink : 0xCCFFFFFF);
            if (on) {
                sub.setTextAlign(Paint.Align.RIGHT);
                c.drawText(Ui.tr("Busca por voz", "Voice search"), (R - micW - 40) * k, (BAR_TOP + BAR_H + 40) * k, sub);
                sub.setTextAlign(Paint.Align.LEFT);
            }
        }

        private void drawKeys(Canvas c) {
            String keys = chars();
            for (int row = 0; row < ROWS; row++) {
                int cols = row < ROWS - 1 ? COLS : BOTTOM_COL.length;
                for (int i = 0; i < cols; i++) {
                    int startCol = row < ROWS - 1 ? i : BOTTOM_COL[i], span = row < ROWS - 1 ? 1 : BOTTOM_SPAN[i];
                    float x = (X + startCol * (KEY_W + GAP)) * k, y = (KEYS_TOP + row * (KEY_H + GAP)) * k;
                    r.set(x, y, x + (span * KEY_W + (span - 1) * GAP) * k, y + KEY_H * k);
                    int index = row < ROWS - 1 ? row * COLS + i : CHARS + i;
                    boolean on = zone == KEYS && key == index;
                    if (on) r.inset(-4 * k, -4 * k);
                    fill.setColor(on ? LiveTheme.tile : LiveTheme.surface);
                    c.drawRoundRect(r, 16 * k * LiveTheme.round, 16 * k * LiveTheme.round, fill);
                    int color = on ? LiveTheme.ink : 0xD9FFFFFF;
                    Paint p = on ? keyTextOn : keyText;
                    keyTextOn.setColor(LiveTheme.ink);
                    if (index < CHARS) {
                        c.drawText(keys, index, index + 1, r.centerX(), r.centerY() + Ui.halfCap(p), p);
                    } else if (index == SPACE_KEY) {     // space: a bar
                        fill.setColor(color);
                        c.drawRoundRect(r.centerX() - 36 * k, r.centerY() + 6 * k, r.centerX() + 36 * k, r.centerY() + 11 * k, 3 * k, 3 * k, fill);
                    } else if (index == DELETE_KEY) {
                        drawDelete(c, r.centerX(), r.centerY(), color);
                    } else {        // the page switch, clear: their names, smaller
                        float ts = p.getTextSize();
                        p.setTextSize(24 * k);
                        String label = index == PAGE_KEY ? (symbols ? "abc" : "&?#") : Ui.tr("Limpar tudo", "Clear all");
                        c.drawText(ellipsize(label, p, r.width() - 12 * k), r.centerX(), r.centerY() + Ui.halfCap(p), p);
                        p.setTextSize(ts);
                    }
                }
            }
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

        private void drawResults(Canvas c, long now) {
            float x0 = RES_X * k, inner = (R - RES_X) * k;
            boolean typed = !Xtream.fold(query).isEmpty();
            // The blocks top to bottom: the channels, then each kind of show found (or one "Shows" saying why none).
            int shown = 0;
            for (int g = 0; g < GROUPS; g++) if (!groups.get(g).isEmpty()) shown++;
            int focus = zone == CHANNELS ? 0 : -1;
            if (zone >= SHOWS) {
                focus = 1;
                for (int g = 0; g < zone - SHOWS; g++) if (!groups.get(g).isEmpty()) focus++;
            }
            // The page glides up so the focused block is in view (back to the top at the search bar).
            float t = vScroll.target;
            if (zone == BAR) t = 0;
            else if (focus >= 0) {
                float top = blockTop(focus), bottom = top + (focus == 0 ? CH_H + 60 : SH_H + 90);
                if (top - t < CH_TOP) t = top - CH_TOP;
                if (bottom - t > 1060) t = bottom - 1060;
            }
            vScroll.target = Math.max(0, t);
            c.save();
            c.clipRect(x0 - 20 * k, (CH_TOP - 72) * k, (R + 40) * k, getHeight());
            c.translate(0, -vScroll.value * k);
            float y = CH_TOP;
            c.drawText(typed ? Ui.tr("Canais", "Channels") : Ui.tr("Assistidos por último", "Recently watched"),
                    x0, (y - 24) * k, heading);
            if (channels.isEmpty()) {
                c.drawText(typed ? Ui.tr("Nenhum canal com esse nome.", "No channels match.")
                        : Ui.tr("Os canais que você assistir aparecem aqui.", "Channels you watch show up here."), x0, (y + 60) * k, empty);
            } else {
                chScroll.target = scrollFor(chAt, CH_W, chScroll.target, channels.size(), inner);
                c.save();
                c.clipRect(x0 - 12 * k, (y - 12) * k, (R + 40) * k, (y + CH_H + 80) * k);
                for (int i = 0; i < channels.size(); i++) {
                    float tx = x0 + (i * (CH_W + GAP * 2) - chScroll.value) * k;
                    if (tx > (R + 40) * k || tx + CH_W * k < x0 - 20 * k) continue;
                    boolean on = zone == CHANNELS && i == chAt;
                    r.set(tx, y * k, tx + CH_W * k, (y + CH_H) * k);
                    if (on) r.inset(-6 * k, -6 * k);
                    tile(c, on);
                    logo.set(r);
                    logo.inset(28 * k, 22 * k);
                    view.drawLogo(c, channels.get(i), logo, 1);
                    Xtream.Channel ch = channels.get(i);
                    Paint p = on ? nameOn : name;
                    String label = (ch.num > 0 ? ch.num + "  " : "") + ch.name;
                    if (on) nameMarquee.draw(c, label, tx, (y + CH_H + 36) * k, CH_W * k, p, k);     // focused: a long name scrolls
                    else c.drawText(ellipsize(label, p, CH_W * k), tx, (y + CH_H + 36) * k, p);
                }
                c.restore();
            }
            if (typed) {
                int block = 1;
                for (int g = 0; g < GROUPS; g++) {
                    if (groups.get(g).isEmpty()) continue;
                    drawShows(c, g, blockTop(block++), x0, inner);
                }
                float last = blockTop(block);
                if (shown == 0) {
                    c.drawText(Ui.tr("Programas", "Shows"), x0, (last - 24) * k, heading);
                    c.drawText(searchingShows ? Ui.tr("Buscando no guia…", "Searching the guide…")
                            : Ui.tr("Nenhum programa com esse nome no guia.", "No shows by that name in the guide."), x0, (last + 60) * k, empty);
                } else if (searchingShows) {
                    c.drawText(Ui.tr("Buscando no guia…", "Searching the guide…"), x0, (last - 24) * k, empty);
                }
            }
            c.restore();
        }

        /** Where block `i` starts (1920 units, before the page glides): the channels first, the shows' rows below. */
        private float blockTop(int i) {
            return i == 0 ? CH_TOP : CH_TOP + CH_STEP + (i - 1) * SH_STEP;
        }

        /** One kind of show (on now, catch-up, later): its name, then its tiles with each show's picture. */
        private void drawShows(Canvas c, int g, float y, float x0, float inner) {
            List<Hit> list = groups.get(g);
            String head = g == ON_NOW ? Ui.tr("Passando agora", "On now") : g == CATCHUP ? Ui.tr("Para rever", "Catch-up")
                    : Ui.tr("Mais tarde", "Later");
            c.drawText(head, x0, (y - 24) * k, heading);
            int at = showAt[g];
            Spring sc = shScroll[g];
            sc.target = scrollFor(at, SH_W, sc.target, list.size(), inner);
            c.save();
            c.clipRect(x0 - 12 * k, (y - 12) * k, (R + 40) * k, (y + SH_H + 110) * k);
            for (int i = 0; i < list.size(); i++) {
                float tx = x0 + (i * (SH_W + GAP * 2) - sc.value) * k;
                if (tx > (R + 40) * k || tx + SH_W * k < x0 - 20 * k) continue;
                boolean on = zone == SHOWS + g && i == at;
                Hit hit = list.get(i);
                r.set(tx, y * k, tx + SH_W * k, (y + SH_H) * k);
                if (on) r.inset(-6 * k, -6 * k);
                tile(c, on);
                Bitmap art = view.artOf(hit.p);
                if (art != null) {
                    img.set(r);
                    img.inset(on ? 6 * k : 0, on ? 6 * k : 0);
                    view.drawCropped(c, art, img, 14 * k);
                } else {
                    logo.set(r);
                    logo.inset(70 * k, 44 * k);
                    view.drawLogo(c, hit.ch, logo, 1);
                }
                Paint np = on ? nameOn : name;
                String line2 = hit.when + "  ·  " + hit.ch.name;
                if (on) {       // focused: a long name scrolls across
                    titleMarquee.draw(c, hit.p.title, tx, (y + SH_H + 36) * k, SH_W * k, np, k);
                    subMarquee.draw(c, line2, tx, (y + SH_H + 68) * k, SH_W * k, sub, k);
                } else {
                    c.drawText(ellipsize(hit.p.title, np, SH_W * k), tx, (y + SH_H + 36) * k, np);
                    c.drawText(ellipsize(line2, sub, SH_W * k), tx, (y + SH_H + 68) * k, sub);
                }
            }
            c.restore();
        }

        /** Tile `r`: the design's focus on it (filled, or a ring: LiveView.drawFocus), else at rest with its glass edge. */
        private void tile(Canvas c, boolean on) {
            float rr = 18 * k * LiveTheme.round;
            if (on) {
                view.drawFocus(c, r, rr, 1);
                return;
            }
            fill.setColor(LiveTheme.surface);
            c.drawRoundRect(r, rr, rr, fill);
            LiveTheme.rim(c, r, rr, 1.5f * k);
        }

        /** The row's scroll (in 1920 units) keeping tile `at` in view. */
        private float scrollFor(int at, float tileW, float now, int n, float inner) {
            float step = tileW + GAP * 2, x = at * step, room = inner / k;
            float t = now;
            if (x < t) t = x;
            if (x + tileW > t + room) t = x + tileW - room;
            return Math.max(0, Math.min(t, Math.max(0, n * step - GAP * 2 - room)));
        }

        private final Marquee nameMarquee = new Marquee(), titleMarquee = new Marquee(), subMarquee = new Marquee();

        private String ellipsize(String s, Paint p, float width) {
            fit.set(p);
            return TextUtils.ellipsize(s, fit, Math.max(1, width), TextUtils.TruncateAt.END).toString();
        }

        /** A long search: its end shows (what's being typed). */
        private String ellipsizeStart(String s, Paint p, float width) {
            fit.set(p);
            return TextUtils.ellipsize(s, fit, Math.max(1, width), TextUtils.TruncateAt.START).toString();
        }
    }
}
