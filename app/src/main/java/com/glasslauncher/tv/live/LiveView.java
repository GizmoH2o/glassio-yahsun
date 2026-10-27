package com.glasslauncher.tv.live;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;

import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Live TV's screens, drawn over the video (Live), dark like the home screen:
 *  - signing in: the server, username and password, typed on the TV;
 *  - watching, with TiviMate's keys: OK shows what's playing with its buttons (Guide, Start over, Record, LIVE, the
 *    favorite, audio, subtitles…, LiveOverlay); ↑ ↓ show the next and previous channel's info (OK tunes it); the
 *    channel buttons change channel; ◀ the channel list, ▶ the last channel; on the player's bar (OK) ◀ ▶ seek
 *    (◀ live: back into the catch-up), ↓ its buttons; Back the guide; held: OK the menu, ↑ History, ↓ Search;
 *  - the guide (Back): the category's channels as rows, their programs along a time line, the playing channel in a
 *    window at the top right; ◀ held goes into the catch-up (Back: now again); Back goes back to the channel full
 *    screen (before anything plays, it brings the categories); ◀ from now brings the menu
 *    rail and the groups beside it (TiviMate's), and Back there goes back to the guide (with nothing playing, twice
 *    leaves Live TV); held Back, back to the video;
 *  - the channel list (←), TiviMate's: the channels with what's on now, the focused one's schedule and show beside
 *    them, ← the categories; Search (or the remote's Search key) finds channels by name or number;
 *  - the number keys tune a channel by its number (a box at the top right shows which, as you type);
 *  - catch-up: on channels the provider records, the guide goes back in time (←) and OK plays a past program, which
 *    ◀ ▶ skip through and OK pauses.
 * Everything moves on springs (Spring), as on Apple TV and YouTube TV: one white focus glides from item to item (the
 * text under it turns dark exactly where it covers it) and grows a little with a soft shadow; the rows and the time
 * line scroll smoothly; the guide fades in as its grid rises, the list slides in from the left, the player slides up;
 * a new channel fades in from black on its first frame. Nothing is redrawn while nothing moves.
 * Measurements in a 1920×1080 space (k = width / 1920).
 */
final class LiveView extends View {
    private static final int SIGN_IN = 0, WATCH = 1, LIST = 2, GUIDE = 3, SETTINGS = 4;
    private static final float MARGIN = 80, ROW_H = 64, ROW_STEP = 70, LIST_TOP = 200;
    private static final float CAT_X = 80, CAT_W = 400, CH_X = 520, PANEL_W = 1280;
    // The channel list (TiviMate's): the categories' column, the channels' panel and its header, the schedule beside it.
    private static final float GR_W = 500, GR_TOP = 40, GR_STEP = 78, C_TOP = 76, SC_W = 520;
    /** The channel list's width and row pitch by style (applyLayout): YouTube TV's wide and tall, Sky's and Sling's slim. */
    private static float CH_W = 600, C_STEP = 126;
    private static final long EXIT_MS = 2000;
    private static final int INK = 0xFF1C1C1E, WHITE = 0xFFF5F5F7, DIM = 0x99FFFFFF, LIVE_RED = 0xFFFF453A, PAST = 0xFF64D2FF;
    private static final long DIGITS_MS = 1600, DAY = 24 * 3600_000L, SKIP_BACK = 10_000, SKIP_AHEAD = 30_000;
    /** The focus grows this much (the tvOS focus), with a shadow below it. */
    private static final float GROW = 0.035f;

    private final Live live;
    private int mode = WATCH;
    private float k = 1;
    private int w, h;

    // Motion: the screen coming in (0 → 1) or going (→ 0, then `closingTo`); a new channel fading in from black.
    private final Spring appear = new Spring(0.38f, 1f, 1), dip = new Spring(0.24f, 1f, 0);
    private int closingTo = -1;
    private long lastFrame;

    // Signing in: the rows (0 server, 1 username, 2 password, 3 the button), what was typed, the focus's row.
    private int field;
    private String server = "", user = "", pass = "";
    /** The sign-in's kind of playlist (Xtream.XTREAM, M3U, MAG): its fields follow. */
    private String signKind = Xtream.XTREAM;
    private final Spring sField = new Spring(0.22f, 0.86f, 0);

    // The list: which column has the focus (0 categories, 1 channels), each one's focused row and first visible row;
    // the springs that slide the focus (row, column) and scroll each column.
    private Xtream.Lineup lineup;
    /** The focused category (SETTINGS_ROW: the Settings row, drawn first, above Favorites), the playing one. */
    private int column = 1, cat, catTop, ch, chTop, playCat;
    private static final int SETTINGS_ROW = -1;
    private final Spring lCat = new Spring(0.22f, 0.86f, 0), lCh = new Spring(0.22f, 0.86f, 0), lCol = new Spring(0.24f, 1f, 1);
    private final Spring lCatTop = new Spring(0.3f, 1f, 0), lChTop = new Spring(0.3f, 1f, 0);

    // The status line (errors, loading), the buffering spinner, the subtitle on screen.
    private String subtitle = "";
    private StaticLayout subtitleLayout, sampleLayout;
    private int captionStyle;
    private TextPaint subtitlePaint;
    private String status = "";
    private boolean buffering;
    private long spinAt, backAt;

    // Typing a channel number: the digits so far and the channel they tune (shown as you type), when the last came.
    private String digits = "";
    private Xtream.Channel digitsChannel;
    private long digitsAt;
    private final Runnable digitsDone = this::commitDigits;

    // Search: a category of its own (third, after All channels), the channels whose names match what was typed.
    private static final String SEARCH = "search";
    private final Xtream.Category search = new Xtream.Category();
    // Recordings: a category of its own (after Search), a stand-in channel per recording (its id below zero).
    private final Xtream.Category recordings = new Xtream.Category();
    private final Map<Integer, LiveRecordings.Rec> recById = new HashMap<>();
    private final Map<Integer, String> recNote = new HashMap<>();
    // History: the channels watched lately (the remote's long ↑, TiviMate's), after Recordings.
    private final Xtream.Category history = new Xtream.Category();
    private long recStamp = -1;
    private String query = "";

    // OK in the guide acts when it's let go (held: in or out of the favorites); only if it was pressed there.
    private boolean okDown, okHeld;

    private Paint title, bigTitle, warn, label, labelOn, small, number, numberOn, pillText, fill, glow, spinner, line, typed;
    private Paint lName, lShow, lHead, lSmall, lNum, lGroup, lTime, lTitle;
    private TextPaint lCardTitle, lCardAbout;
    private StaticLayout cardTitle, cardAbout;
    private String cardTitleText = "", cardAboutText = "";
    private final java.util.Calendar listCal = java.util.Calendar.getInstance();
    private final java.text.SimpleDateFormat dayFormat = new java.text.SimpleDateFormat("EEEE, d MMM", Ui.locale());
    /** The guide's day, short (Sun, 25 Oct), in the language's own order. */
    private final java.text.SimpleDateFormat dayShort = new java.text.SimpleDateFormat(
            android.text.format.DateFormat.getBestDateTimePattern(Ui.locale(), "EEEdMMM"), Ui.locale());
    /** The list came from the guide (its Back): Back there leaves Live TV (twice); from watching, back to the video. */
    private boolean listFromGuide;
    /** The screen the settings were opened from (WATCH, GUIDE or LIST), to go back to. */
    private int settingsFrom = LIST;
    /** A key held while watching (TiviMate's long presses): which, and whether its long press already acted. */
    private int pressCode = -1;
    private boolean pressLong, backLong;
    /** With the focus in the schedule (column 2, → from a channel): the focused program's place in its programs. */
    private int sIdx;
    // The guide's groups (◀ in the guide, TiviMate's): the menu rail and the categories beside the guide, which
    // moves over; ↑ ↓ pick the category (the guide follows), ← the rail, → or OK back to the guide.
    private static final float GP_RAIL = 110, GP_W = 600;
    private boolean gGroups, gRail;
    private int gRailIdx, gGroupIdx, gGroupTopI;
    private final List<Integer> panelCats = new ArrayList<>();
    private final Spring gPanel = new Spring(0.26f, 1f, 0), gGroupRow = new Spring(0.22f, 0.86f, 0), gGroupTop = new Spring(0.3f, 1f, 0);
    /** The rail opening (1: its names beside its icons, YouTube TV's side menu) while it has the focus. */
    private final Spring gRailOpen = new Spring(0.26f, 1f, 0), gRailRow = new Spring(0.22f, 0.86f, 0);
    private static final String[] RAIL_ICONS = {"home", "search", "list", "recordings", "history", "settings"};
    private TextPaint fit;
    private final RectF r = new RectF(), box = new RectF(), dst = new RectF(), focusRect = new RectF();
    private Shader panelShade;

    /** Live TV's own settings (the categories' last row), drawn here when mode is SETTINGS. */
    private final LiveSettings settings;
    /** What's playing and its buttons, over the video while watching. */
    private final LiveOverlay overlay;
    /** Every category, the hidden ones too (lineup.categories leaves those out). */
    private final List<Xtream.Category> allCategories = new ArrayList<>();

    LiveView(Live a) {
        super(a);
        live = a;
        settings = new LiveSettings(this, a);
        overlay = new LiveOverlay(this, a);
        setFocusable(true);
        setContentDescription(Ui.tr("TV ao vivo", "Live TV"));
    }

    void release() {
        epgIo.shutdownNow();
    }

    // MARK: - What Live tells it

    void showSignIn() {
        // Shown at once, whatever was fading out: Add a playlist closes the settings first, and that fade, still going,
        // took the screen back to the (now empty) player when it ended, a black screen.
        closingTo = -1;
        appear.snap(1);
        appear.target = 1;
        mode = SIGN_IN;
        field = 0;
        sField.snap(0);
        signKind = Xtream.kind(getContext());
        server = Xtream.server(getContext());
        user = Xtream.second(getContext());
        pass = "";
        status = "";
        invalidate();
    }

    void setLineup(Xtream.Lineup l, String categoryId) {
        lineup = l;
        categoryCountry = LiveCountry.categories(l);
        final java.util.Map<String, String> forHome = categoryCountry;
        final android.content.Context app = getContext().getApplicationContext();
        new Thread(() -> LiveCountry.saveCategories(app, forHome), "live-countries").start();
        // Search and Recordings are pages of their own (the menu, the rail), not rows in the list.
        recordings.id = "recordings";
        recordings.name = Ui.tr("Gravações", "Recordings");
        history.id = "history";
        history.name = Ui.tr("Histórico", "History");
        if (!l.categories.contains(history)) l.categories.add(Math.min(2, l.categories.size()), history);
        refreshHistory();
        recStamp = -1;
        refreshRecordings();
        runSearch();
        providerOrder.clear();
        providerOrder.addAll(l.categories);
        List<Xtream.Channel> every = l.all();
        position.clear();
        for (int i = 0; i < every.size(); i++) position.put(every.get(i), i);
        sort();
        // The saved category; without one, Favorites when there are any, else all channels.
        int at = !l.categories.isEmpty() && !l.categories.get(0).channels.isEmpty() ? 0 : Math.min(1, l.categories.size() - 1);
        // (Not Search: what was typed is gone.)
        for (int i = 0; i < l.categories.size(); i++) if (l.categories.get(i).id.equals(categoryId) && l.categories.get(i) != search) at = i;
        playCat = cat = Math.max(0, at);
        Xtream.Channel playing = live.playing();
        ch = 0;
        if (playing != null) {
            List<Xtream.Channel> list = l.categories.get(cat).channels;
            for (int i = 0; i < list.size(); i++) if (list.get(i).id == playing.id) ch = i;
        }
        if (mode == SIGN_IN) mode = WATCH;
        keepVisible();
        aimList(true);
        invalidate();
    }

    // The provider's order of the categories and of the channels (their place in All channels), for sorting back.
    private final List<Xtream.Category> providerOrder = new ArrayList<>();
    private final Map<Xtream.Channel, Integer> position = new HashMap<>();

    /**
     * The categories and their channels in the order picked in the settings (by name, by number, or the provider's);
     * Favorites keep the order they were put in and Search its own. Then the hidden categories leave.
     */
    private void sort() {
        int byChannel = LiveOptions.channelSort(getContext()), byCategory = LiveOptions.categorySort(getContext());
        allCategories.clear();
        List<Xtream.Category> mine = new ArrayList<>();
        for (int i = 0; i < providerOrder.size(); i++) {
            Xtream.Category cg = providerOrder.get(i);
            if (i < 2 || cg == search || cg == recordings || cg == history) allCategories.add(cg); else mine.add(cg);
        }
        if (byCategory == LiveOptions.BY_NAME) Collections.sort(mine, (a, b) -> fold(a.name).compareTo(fold(b.name)));
        allCategories.addAll(mine);
        java.util.Comparator<Xtream.Channel> order;
        if (byChannel == LiveOptions.BY_NAME) {
            order = (a, b) -> {
                int c = folded(a).compareTo(folded(b));
                return c != 0 ? c : Integer.compare(place(a), place(b));
            };
        } else if (byChannel == LiveOptions.BY_NUMBER) {    // channels without a number last
            order = (a, b) -> a.num == b.num ? Integer.compare(place(a), place(b))
                    : a.num <= 0 ? 1 : b.num <= 0 ? -1 : Integer.compare(a.num, b.num);
        } else {
            order = (a, b) -> Integer.compare(place(a), place(b));
        }
        for (int i = 1; i < providerOrder.size(); i++) {
            Xtream.Category cg = providerOrder.get(i);
            if (cg != search && cg != recordings && cg != history) Collections.sort(cg.channels, order);
        }
        filterHidden();
    }

    private int place(Xtream.Channel ch) {
        Integer p = position.get(ch);
        return p == null ? Integer.MAX_VALUE : p;
    }

    private static String folded(Xtream.Channel ch) {
        return ch.key;
    }


    /** The sort changed in the settings: everything in the new order, the focus on the same channels. */
    void sortChanged() {
        if (lineup == null) return;
        String catId = idAt(cat), playId = idAt(playCat), guideId = idAt(gCat);
        List<Xtream.Channel> list = channels(), rows = guideChannels();
        Xtream.Channel focused = ch < list.size() ? list.get(ch) : null, guideRow = gRow < rows.size() ? rows.get(gRow) : null;
        sort();
        if (cat != SETTINGS_ROW) cat = indexOf(catId);
        playCat = indexOf(playId);
        gCat = indexOf(guideId);
        list = channels();
        ch = Math.max(0, list.indexOf(focused));
        rows = guideChannels();
        gRow = Math.max(0, rows.indexOf(guideRow));
        invalidate();
    }

    /**
     * The Recordings category's rows, again when the recordings changed (one is recording, one was deleted): each a
     * stand-in channel (the channel's logo, when and how long) with the show's title before it.
     */
    private void refreshRecordings() {
        long stamp = LiveRecordings.stamp(getContext());
        if (stamp == recStamp) return;
        recStamp = stamp;
        recordings.channels.clear();
        recById.clear();
        recNote.clear();
        java.text.SimpleDateFormat day = new java.text.SimpleDateFormat("EEE d MMM", Ui.locale());
        List<LiveRecordings.Rec> all = LiveRecordings.all(getContext());
        for (int i = 0; i < all.size(); i++) {
            LiveRecordings.Rec r = all.get(i);
            Xtream.Channel pc = new Xtream.Channel();
            pc.id = -(i + 1);
            pc.logo = r.ch.logo;
            pc.category = r.ch.category;
            long minutes = Math.max(1, (Math.min(r.end, System.currentTimeMillis()) - r.start) / 60_000);
            pc.name = day.format(new java.util.Date(r.start)) + ", " + Ui.time(r.start) + "  ·  " + minutes + " min";
            recordings.channels.add(pc);
            recById.put(pc.id, r);
            String title = r.title.isEmpty() ? r.ch.name : r.title;
            recNote.put(pc.id, LiveRecordings.RECORDING.equals(r.state) ? "● " + title
                    : LiveRecordings.FAILED.equals(r.state) ? title + " (" + Ui.tr("falhou", "failed") + ")" : title);
        }
    }

    /** History's channels: the ones watched lately, as the lineup shows them (the latest first). */
    private void refreshHistory() {
        history.channels.clear();
        if (lineup == null) return;
        for (Xtream.Channel r : Xtream.recent(getContext())) {
            Xtream.Channel ch = lineup.find(r.id);
            if (ch != null && !history.channels.contains(ch)) history.channels.add(ch);
        }
    }

    /** The lineup without the categories hidden in the settings (Favorites, All channels and Search always stay). */
    private void filterHidden() {
        Set<String> hidden = LiveOptions.hidden(getContext());
        lineup.categories.clear();
        for (int i = 0; i < allCategories.size(); i++) {
            Xtream.Category cg = allCategories.get(i);
            if (i < 2 || cg == search || cg == recordings || cg == history || !hidden.contains(cg.id)) lineup.categories.add(cg);
        }
    }

    /** A category was hidden or shown: the list changes, the focus stays on the same categories (by id). */
    void hiddenChanged() {
        if (lineup == null) return;
        String catId = idAt(cat), playId = idAt(playCat), guideId = idAt(gCat);
        filterHidden();
        if (cat != SETTINGS_ROW) cat = indexOf(catId);
        playCat = indexOf(playId);
        gCat = indexOf(guideId);
        invalidate();
    }

    private String idAt(int i) {
        return i >= 0 && i < lineup.categories.size() ? lineup.categories.get(i).id : "";
    }

    /** The category with `id` (a hidden one: All channels). */
    private int indexOf(String id) {
        for (int i = 0; i < lineup.categories.size(); i++) if (lineup.categories.get(i).id.equals(id)) return i;
        return Math.min(1, lineup.categories.size() - 1);
    }

    /** The provider's own categories, hidden ones too: what the settings list. */
    List<Xtream.Category> providerCategories() {
        List<Xtream.Category> out = new ArrayList<>();
        for (int i = 2; i < allCategories.size(); i++) {
            Xtream.Category cg = allCategories.get(i);
            if (cg != search && cg != recordings && cg != history) out.add(cg);
        }
        return out;
    }

    Xtream.Lineup lineup() {
        return lineup;
    }

    /** The channels ↑ ↓ go through, and the guide opens on: category `id` (Favorites, from the home screen's row). */
    void useCategory(String id) {
        if (lineup == null) return;
        for (int i = 0; i < lineup.categories.size(); i++) if (lineup.categories.get(i).id.equals(id)) playCat = i;
    }

    /** The category the playing channel was picked from (saved with it). */
    /**
     * A channel plays: its own group (the provider's category it's in) becomes the one the guide, the list and
     * channel up / down follow, wherever it was picked (Favorites, History, Search); one hidden: as it was.
     */
    void followGroup(Xtream.Channel ch) {
        if (lineup == null || ch == null || ch.category == null || ch.category.isEmpty()) return;
        for (int i = 2; i < lineup.categories.size(); i++) {
            Xtream.Category cg = lineup.categories.get(i);
            if (cg == search || cg == recordings || cg == history) continue;
            if (ch.category.equals(cg.id)) {
                playCat = i;
                return;
            }
        }
    }

    String categoryId() {
        return lineup == null || playCat >= lineup.categories.size() ? "" : lineup.categories.get(playCat).id;
    }

    void openList() {
        if (lineup == null) return;
        live.setPreview(false);
        enter(LIST);
        listFromGuide = false;
        refreshHistory();
        column = 1;
        cat = playCat;
        Xtream.Channel playing = live.playing();
        List<Xtream.Channel> list = channels();
        ch = 0;
        for (int i = 0; playing != null && i < list.size(); i++) if (list.get(i).id == playing.id) ch = i;
        keepVisible();
        aimList(true);
        announce();
        invalidate();
    }

    /** A channel (or program) starts, or a key while watching: what's playing shows over the video. */
    void showBanner(Xtream.Channel c) {
        Xtream.Program past = live.catchup();
        announceForAccessibility((c.num > 0 ? c.num + " " : "") + c.name + (past == null ? "" : ", " + past.title));
        overlay.show(false);     // drawn only while watching: from the guide or the list, it shows as they go
        invalidate();
    }

    /** The player's tracks changed: the overlay's buttons (audio, subtitles) and the stream's tags follow. */
    void tracksChanged() {
        overlay.refresh();
    }

    /** What the subtitles say now ("" for nothing). */
    void setSubtitle(String text) {
        if (text.equals(subtitle)) return;
        subtitle = text;
        subtitleLayout = null;
        invalidate();
    }

    /** The subtitle, centered, as the settings' Captions say: above the overlay while it shows. */
    private void drawSubtitle(Canvas c) {
        if (subtitle.isEmpty() || mode != WATCH) return;
        if (subtitleLayout == null) subtitleLayout = captionLayout(subtitle);
        drawCaption(c, subtitleLayout, w / 2f, overlay.visible() ? 560 * k : h - 64 * k);
    }

    /** A sample of the captions as set (the settings' Captions), centered on cx, its last line at `bottom`. */
    void drawCaptionSample(Canvas c, float cx, float bottom) {
        if (sampleLayout == null) {
            sampleLayout = captionLayout(Ui.tr("Assim ficam as legendas.", "This is how captions will look."));
        }
        drawCaption(c, sampleLayout, cx, bottom);
    }

    /** The captions' look changed: the paint and the lines are made again. */
    void captionsChanged() {
        subtitlePaint = null;
        subtitleLayout = sampleLayout = null;
        invalidate();
    }

    private StaticLayout captionLayout(String text) {
        if (subtitlePaint == null) {
            android.content.Context ctx = getContext();
            captionStyle = LiveOptions.ccStyle(ctx);
            subtitlePaint = new TextPaint(Ui.text(Ui.medium, LiveOptions.CC_SIZES[LiveOptions.ccSize(ctx)] * k,
                    LiveOptions.CC_COLORS[LiveOptions.ccColor(ctx)]));
            subtitlePaint.setStrokeJoin(Paint.Join.ROUND);
        }
        return StaticLayout.Builder.obtain(text, 0, text.length(), subtitlePaint, Math.round(1400 * k))
                .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER).setMaxLines(4).build();
    }

    /** Lines of captions: on a dark band, a black box, or outlined in black with nothing behind. */
    private void drawCaption(Canvas c, StaticLayout layout, float cx, float bottom) {
        float widest = 0;
        for (int i = 0; i < layout.getLineCount(); i++) widest = Math.max(widest, layout.getLineWidth(i));
        float top = bottom - layout.getHeight();
        if (captionStyle != LiveOptions.CC_OUTLINE) {
            fill.setShader(null);
            fill.setColor(captionStyle == LiveOptions.CC_BOX ? 0xFF000000 : 0xB3000000);
            r.set(cx - widest / 2 - 20 * k, top - 10 * k, cx + widest / 2 + 20 * k, bottom + 10 * k);
            c.drawRoundRect(r, captionStyle == LiveOptions.CC_BOX ? 4 * k : 12 * k, captionStyle == LiveOptions.CC_BOX ? 4 * k : 12 * k, fill);
        }
        c.save();
        c.translate(cx - 700 * k, top);
        if (captionStyle == LiveOptions.CC_OUTLINE) {       // the outline first, the letters over it
            TextPaint p = subtitlePaint;
            int color = p.getColor();
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(3, p.getTextSize() / 7));
            p.setColor(0xFF000000);
            layout.draw(c);
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
        }
        layout.draw(c);
        c.restore();
    }

    /** A channel starts: the video goes to black until its first frame (tuned), so nothing half-decoded shows. */
    void tuning() {
        dip.snap(1);
        invalidate();
    }

    /** The new channel's first frame is on screen: it fades in. */
    void tuned() {
        dip.target = 0;
        invalidate();
    }

    void setStatus(String s) {
        status = s == null ? "" : s;
        if (!status.isEmpty()) announceForAccessibility(status);
        invalidate();
    }

    void setBuffering(boolean on) {
        if (on == buffering) return;
        buffering = on;
        spinAt = SystemClock.uptimeMillis();
        invalidate();
    }

    /** Another screen comes in: it fades (and slides) in from nothing. */
    private void enter(int next) {
        if (mode != next || closingTo >= 0) appear.snap(0);
        appear.target = 1;
        closingTo = -1;
        mode = next;
    }

    /** The screen goes (fading out), then `next` takes over. */
    private void leave(int next) {
        closingTo = next;
        appear.target = 0;
        invalidate();
    }

    // MARK: - Size and paints

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        title = Ui.text(Ui.displaySemibold, 46 * k, WHITE);
        bigTitle = Ui.text(Ui.displaySemibold, 64 * k, WHITE);
        warn = Ui.text(Ui.regular, 24 * k, 0xFFFFC266);
        label = Ui.text(Ui.medium, 28 * k, 0xCCFFFFFF);
        labelOn = Ui.text(Ui.semibold, 28 * k, WHITE);
        small = Ui.text(Ui.regular, 23 * k, DIM);
        number = Ui.numbers(Ui.medium, 25 * k, DIM);
        numberOn = Ui.numbers(Ui.semibold, 25 * k, 0xCCFFFFFF);
        pillText = Ui.text(Ui.semibold, 19 * k, 0xFFFFFFFF);
        pillText.setLetterSpacing(0.08f);
        fit = new TextPaint();
        fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        // The focus: white with a soft shadow below (it "rises").
        glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        glow.setColor(WHITE);
        glow.setShadowLayer(26 * k, 0, 10 * k, 0x80000000);
        spinner = new Paint(Paint.ANTI_ALIAS_FLAG);
        spinner.setStyle(Paint.Style.STROKE);
        spinner.setStrokeCap(Paint.Cap.ROUND);
        spinner.setStrokeWidth(5 * k);
        spinner.setColor(0xE6FFFFFF);
        line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        typed = Ui.numbers(Ui.semibold, 76 * k, WHITE);
        int ps = LiveTheme.pane & 0x00FFFFFF;
        panelShade = new LinearGradient(0, 0, PANEL_W * k, 0, new int[] {0xF5000000 | ps, 0xEB000000 | ps, ps},
                new float[] {0, 0.8f, 1}, Shader.TileMode.CLAMP);
        overlay.size(k, w, h);
        subtitlePaint = null;
        subtitleLayout = sampleLayout = null;
        gTitle = null;     // the guide's paints, for the new size
        lName = null;      // and the list's
        settings.size(k);
        keepVisible();
        aimList(true);
    }

    // MARK: - Drawing

    /** One frame of every spring; true while any of them moves. */
    private boolean step() {
        long now = SystemClock.uptimeMillis();
        long gap = now - lastFrame;
        float dt = lastFrame == 0 || gap > 100 ? 1 / 60f : gap / 1000f;    // after a pause, no jump
        lastFrame = now;
        boolean moving = appear.step(dt) | dip.step(dt) | sField.step(dt);
        moving |= lCat.step(dt) | lCh.step(dt) | lCol.step(dt) | lCatTop.step(dt) | lChTop.step(dt);
        moving |= fRow.step(dt) | fA.step(dt) | fB.step(dt) | fCol.step(dt) | gRowsTop.step(dt) | gTimeOff.step(dt) | info.step(dt);
        gRailOpen.target = gGroups && gRail ? 1 : 0;
        gRailRow.target = gRailIdx;
        moving |= settings.step(dt) | overlay.step(dt) | gPanel.step(dt) | gGroupRow.step(dt) | gGroupTop.step(dt)
                | gRailOpen.step(dt) | gRailRow.step(dt);
        if (closingTo >= 0 && appear.value <= 0.01f) {    // the screen has gone: the next one is there already
            mode = closingTo;
            closingTo = -1;
            appear.snap(1);
            moving = true;
        }
        return moving;
    }

    @Override protected void onDraw(Canvas c) {
        if (w == 0) return;
        Marquee.moving = false;
        boolean moving = step();
        long up = SystemClock.uptimeMillis();
        if (mode == SIGN_IN) {
            drawSignIn(c);
        } else {
            drawDip(c);
            if (mode == GUIDE) drawGuide(c, up);
            else if (mode == LIST) drawList(c);
            else if (mode == SETTINGS) settings.draw(c, Ui.clamp(appear.value), w, h);
            else overlay.draw(c, up);
            drawSubtitle(c);
            if (buffering && status.isEmpty()) {
                // Over the video: the preview's middle in the guide, the right side beside the list, else the screen's.
                float cx = mode == GUIDE ? (P_X + P_W / 2 + gShift()) * k : mode == LIST || mode == SETTINGS ? (PANEL_W + 1920) / 2f * k : w / 2f;
                float cy = mode == GUIDE ? (P_Y + P_H / 2) * k : mode == WATCH && overlay.visible() ? 380 * k : h / 2f;   // above the overlay
                Ui.spinner(c, cx, cy, (mode == GUIDE ? 22 : 30) * k, (up - spinAt) / 1000f * 1.3f, spinner);
                moving = true;
            }
            drawStatus(c);
            if (drawDigits(c, up)) moving = true;
            if (drawGoal(c, up)) moving = true;
        }
        if (moving || Marquee.moving) postInvalidateOnAnimation();     // a long title scrolling
    }

    /** Black over the video while a channel starts, fading out on its first frame (only the window in the guide). */
    private void drawDip(Canvas c) {
        if (dip.value <= 0.004f) return;
        fill.setShader(null);
        fill.setColor(Ui.alpha(0xFF000000, Ui.clamp(dip.value)));
        if (mode == GUIDE) c.drawRect((P_X + gShift()) * k, P_Y * k, (P_X + P_W + gShift()) * k, (P_Y + P_H) * k, fill);
        else c.drawRect(0, 0, w, h, fill);
    }

    private void drawSignIn(Canvas c) {
        LiveTheme.background(c, w, h, 1);
        float x = 160 * k;
        Symbols.draw(getContext(), c, "live_tv", x + 60 * k, 360 * k, 120 * k, 0xEBFFFFFF);
        c.drawText(Ui.tr("TV ao vivo", "Live TV"), x, 500 * k, bigTitle);
        c.drawText(Ui.tr("Entre com os dados do seu provedor de IPTV:", "Sign in with your IPTV provider's details:"), x, 556 * k, small);
        c.drawText(Ui.tr("Xtream Codes, um link M3U ou um portal MAG.", "Xtream Codes, an M3U link or a MAG portal."), x, 592 * k, small);
        c.drawText(Ui.tr("Eles ficam só nesta TV.", "They stay on this TV only."), x, 628 * k, small);
        String[] names = fieldNames();
        int fields = names.length, rows = fields + 2;
        String[] labels = new String[fields + 1], values = new String[fields + 1];
        labels[0] = Ui.tr("Tipo de lista", "Playlist type");
        values[0] = kindName(signKind);
        String[] typed = {server, user, pass};
        for (int i = 0; i < fields; i++) {
            labels[i + 1] = names[i];
            boolean secret = signKind.equals(Xtream.XTREAM) && i == 2;
            values[i + 1] = typed[i].isEmpty() ? (optional(i) ? Ui.tr("Opcional", "Optional") : "—") : secret ? "••••••••" : typed[i];
        }
        float left = 1000 * k, right = 1824 * k, top = 300 * k, step = 82 * k, rowH = 72 * k;
        // Every row on faint glass (Glassio's rows), the button a little brighter, then the focus gliding over them.
        fill.setShader(null);
        for (int i = 0; i < rows; i++) {
            r.set(left, top + i * step, right, top + i * step + rowH);
            fill.setColor(i == rows - 1 ? 0x24FFFFFF : 0x0FFFFFFF);
            c.drawRoundRect(r, 18 * k, 18 * k, fill);
        }
        float fy = top + sField.value * step;
        focusRect.set(left, fy, right, fy + rowH);
        grow(focusRect, GROW / 2);
        glow.setColor(LiveTheme.tile);      // the focus in the theme's color, its text in the style's ink
        c.drawRoundRect(focusRect, 18 * k, 18 * k, glow);
        labelOn.setColor(LiveTheme.ink);
        numberOn.setColor(Ui.alpha(LiveTheme.ink, 0.8f));
        for (int pass = 0; pass < 2; pass++) {
            c.save();
            if (pass == 1) c.clipRect(focusRect);
            else c.clipOutRect(focusRect);      // under the focus only the bold text (no double text)
            for (int i = 0; i < rows; i++) {
                float y = top + i * step;
                r.set(left, y, right, y + rowH);
                Paint p = pass == 1 ? labelOn : label;
                float base = r.centerY() + Ui.halfCap(p);
                if (i == rows - 1) {
                    String s = Ui.tr("Entrar", "Sign in");
                    c.drawText(s, r.centerX() - p.measureText(s) / 2, base, p);
                    continue;
                }
                c.drawText(labels[i], r.left + 30 * k, base, p);
                Paint v = pass == 1 ? numberOn : number;
                String value = ellipsize(values[i], v, 480 * k);
                c.drawText(value, r.right - 30 * k - v.measureText(value), base, v);
            }
            c.restore();
        }
        drawStatusAt(c, left + 30 * k, top + rows * step + 46 * k);
    }

    /** The fields for the sign-in's kind of playlist. */
    private String[] fieldNames() {
        if (signKind.equals(Xtream.M3U)) return new String[] {Ui.tr("Link da lista", "Playlist link"), Ui.tr("Link do guia", "Guide link")};
        if (signKind.equals(Xtream.MAG)) return new String[] {Ui.tr("Endereço do portal", "Portal address"), Ui.tr("Endereço MAC", "MAC address")};
        return new String[] {Ui.tr("Servidor", "Server"), Ui.tr("Usuário", "Username"), Ui.tr("Senha", "Password")};
    }

    /** M3U's guide link may stay empty (the list's own, or none). */
    private boolean optional(int field) {
        return signKind.equals(Xtream.M3U) && field == 1;
    }

    private static String kindName(String kind) {
        return kind.equals(Xtream.M3U) ? Ui.tr("Link M3U", "M3U link") : kind.equals(Xtream.MAG) ? Ui.tr("Portal MAG", "MAG portal") : "Xtream Codes";
    }

    /** The kinds of playlist to pick from, each saying what it asks for. */
    private void chooseKind() {
        final String[] kinds = {Xtream.XTREAM, Xtream.M3U, Xtream.MAG};
        String[] labels = new String[kinds.length];
        for (int i = 0; i < kinds.length; i++) labels[i] = kindName(kinds[i]);
        String[] details = {Ui.tr("Servidor, usuário e senha", "Server, username and password"),
                Ui.tr("Um link .m3u ou .m3u8", "An .m3u or .m3u8 link"),
                Ui.tr("O portal e o MAC registrado", "The portal and the MAC it knows")};
        LiveChooser.showWords(getContext(), Ui.tr("Tipo de lista", "Playlist type"), labels, details,
                java.util.Arrays.asList(kinds).indexOf(signKind), false, i -> {
                    if (kinds[i].equals(signKind)) return;
                    signKind = kinds[i];
                    server = user = pass = "";
                    if (signKind.equals(Xtream.MAG)) user = "00:1A:79:";      // MAG boxes' own prefix, to finish typing
                    status = "";
                    field = 1;
                    sField.target = field;
                    invalidate();
                });
    }

    /** 1:05:09, or 05:09 under an hour. */
    static String clock(long ms) {
        long s = Math.max(0, ms / 1000), h = s / 3600, m = s / 60 % 60;
        String mm = (m < 10 ? "0" : "") + m, ss = (s % 60 < 10 ? "0" : "") + s % 60;
        return h > 0 ? h + ":" + mm + ":" + ss : mm + ":" + ss;
    }

    /**
     * The number being typed, at the top right: the digits, the channel they'd tune (its logo and name) and a line
     * that runs out until it tunes. True while it shows (the line moves).
     */
    private boolean drawDigits(Canvas c, long up) {
        if (digits.isEmpty()) return false;
        float right = (1920 - MARGIN) * k, top = 60 * k, bw = 600 * k, bh = 150 * k;
        r.set(right - bw, top, right, top + bh);
        fill.setShader(null);
        fill.setColor((LiveTheme.card & 0x00FFFFFF) | 0xF2000000);
        c.drawRoundRect(r, LiveTheme.panelRound(28 * k), LiveTheme.panelRound(28 * k), fill);
        box.set(r.left + 24 * k, r.top + 26 * k, r.left + 24 * k + 176 * k, r.bottom - 26 * k);
        if (digitsChannel != null) drawLogo(c, digitsChannel, box, 1);
        float x = digitsChannel != null ? box.right + 28 * k : r.left + 34 * k;
        c.drawText(digits, x, r.top + 84 * k, typed);
        String name = digitsChannel != null ? digitsChannel.name : Ui.tr("Nenhum canal", "No channel");
        c.drawText(ellipsize(name, small, r.right - 30 * k - x), x, r.top + 122 * k, small);
        float f = 1 - Ui.clamp((up - digitsAt) / (float) DIGITS_MS);
        fill.setColor(0x33FFFFFF);
        c.drawRect(r.left + 28 * k, r.bottom - 6 * k, r.right - 28 * k, r.bottom - 3 * k, fill);
        fill.setColor(WHITE);
        c.drawRect(r.left + 28 * k, r.bottom - 6 * k, r.left + 28 * k + (bw - 56 * k) * f, r.bottom - 3 * k, fill);
        return true;
    }

    /**
     * The channel list over the video, TiviMate's way: the category's channels in a panel at the left (logo, name,
     * what's on now and how far it is), the focused channel's schedule beside it and its show's details in a card at
     * the right, over the video. ← brings the categories in from the left (the channels move over, the schedule
     * goes). The focus is a red tile; the rows and the panels move on springs.
     */
    private void drawList(Canvas c) {
        if (lineup == null) return;
        if (lName == null) makeListPaints();
        float a = Ui.clamp(appear.value);
        int save = a < 0.995f ? c.saveLayerAlpha(0, 0, w, h, Math.round(255 * a)) : c.save();
        c.translate(-(1 - a) * 90 * k, 0);
        float col = Ui.clamp(lCol.value);       // 1: the channels in front; 0: the categories beside them
        float gx = -GR_W * col * k, cx = gx + GR_W * k;
        List<Xtream.Channel> list = channels();
        // The categories (sliding out to the left while the channels are in front).
        if (col < 0.995f) {
            // Frosted: dark glass over the video (it shows through faintly), a fine light edge at its side.
            fill.setShader(null);
            fill.setColor((LiveTheme.bg & 0x00FFFFFF) | 0xE6000000);
            c.drawRect(gx, 0, gx + GR_W * k, h, fill);
            fill.setColor(0x14FFFFFF);
            c.drawRect(gx + GR_W * k - 1.5f * k, 0, gx + GR_W * k, h, fill);
            drawCategories(c, gx);
        }
        // The channels' panel: its header (the category, how many), then the rows.
        fill.setShader(null);
        fill.setColor((LiveTheme.bg & 0x00FFFFFF) | 0xDE000000);
        c.drawRect(cx, 0, cx + CH_W * k, h, fill);
        fill.setColor((LiveTheme.card & 0x00FFFFFF) | 0xB3000000);
        c.drawRect(cx, 0, cx + CH_W * k, C_TOP * k, fill);
        fill.setColor(0x14FFFFFF);
        c.drawRect(cx + CH_W * k - 1.5f * k, 0, cx + CH_W * k, h, fill);
        String heading = cat >= 0 && cat < lineup.categories.size() ? lineup.categories.get(cat).name : Ui.tr("Ajustes", "Settings");
        String count = list == recordings.channels
                ? list.size() + " " + (list.size() == 1 ? Ui.tr("gravação", "recording") : Ui.tr("gravações", "recordings"))
                : list.size() + Ui.tr(" canais", " channels");
        if (searchingShows && list == search.channels) count = Ui.tr("buscando…", "searching…");
        float countW = lSmall.measureText(count);
        c.drawText(ellipsize(heading, lHead, (CH_W - 90) * k - countW), cx + 32 * k, C_TOP / 2 * k + Ui.halfCap(lHead), lHead);
        c.drawText(count, cx + (CH_W - 28) * k - countW, C_TOP / 2 * k + Ui.halfCap(lSmall), lSmall);
        c.save();
        c.clipRect(cx, C_TOP * k, cx + CH_W * k, h);
        drawChannels(c, list, cx, col);
        c.restore();
        if (list.isEmpty() && cat >= 0 && cat < lineup.categories.size()) {
            String empty = lineup.categories.get(cat) == recordings
                    ? Ui.tr("Segure OK num programa do guia para gravar.", "Hold OK on a show in the guide to record it.")
                    : lineup.categories.get(cat) == history ? Ui.tr("Os canais que você assistir aparecem aqui.", "Channels you watch show up here.")
                    : lineup.categories.get(cat) != search ? Ui.tr("Nenhum canal aqui.", "No channels here.")
                    : query.trim().isEmpty() ? Ui.tr("Aperte OK e digite o nome ou o número de um canal.", "Press OK and type a channel's name or number.")
                    : Ui.tr("Nenhum canal com esse nome.", "No channels match.");
            c.drawText(ellipsize(empty, lSmall, (CH_W - 64) * k), cx + 32 * k, (C_TOP + 60) * k, lSmall);
        }
        // The focused channel's schedule and its show (with the channels in front).
        if (col > 0.01f && ch < list.size() && list != recordings.channels) {
            int layer = col < 0.995f ? c.saveLayerAlpha(0, 0, w, h, Math.round(255 * col)) : c.save();
            drawSchedule(c, list.get(ch), cx + CH_W * k);
            c.restoreToCount(layer);
        }
        c.restoreToCount(save);
        if (list == recordings.channels) {     // the list follows while one is recording
            refreshRecordings();
            postInvalidateDelayed(2000);
        } else {
            postInvalidateDelayed(30_000 - System.currentTimeMillis() % 30_000);   // the progress bars move on
        }
    }

    /** The list's paints (made once per size). */
    /** History's number of channels changed in the settings. */
    void historyChanged() {
        refreshHistory();
        invalidate();
    }

    /** A new theme: the paints with its color made again (on the next frame). */
    void themeChanged() {
        lName = null;
        gTitle = null;
        invalidate();
    }

    private void makeListPaints() {
        lName = Ui.text(Ui.medium, 30 * k, WHITE);
        lShow = Ui.text(Ui.regular, 27 * k, LiveTheme.text);
        lHead = Ui.text(Ui.semibold, 30 * k, WHITE);
        lSmall = Ui.text(Ui.regular, 24 * k, DIM);
        lNum = Ui.numbers(Ui.medium, 24 * k, 0x80FFFFFF);
        lGroup = Ui.text(Ui.medium, 32 * k, 0xE6FFFFFF);
        lTime = Ui.numbers(Ui.regular, 27 * k, 0x99FFFFFF);
        lTitle = Ui.text(Ui.regular, 27 * k, 0xE6FFFFFF);
        lCardTitle = new TextPaint(Ui.text(Ui.semibold, 38 * k, WHITE));
        lCardAbout = new TextPaint(Ui.text(Ui.regular, 26 * k, 0xB3FFFFFF));
        cardTitle = cardAbout = null;
        cardTitleText = cardAboutText = "";
    }

    /** The categories, each with how many channels; the theme's tile on the focused one. */
    private void drawCategories(Canvas c, float gx) {
        int n = lineup.categories.size(), rows = catRows();
        float top = lCatTop.value;
        int first = Math.max(0, (int) Math.floor(top)), last = Math.min(n, first + rows + 2);
        // The focus (or, with the channels in front, the chosen category's soft tile).
        catRow(gx, lCat.value, top, focusRect);
        fill.setColor(column == 0 ? LiveTheme.tile : LiveTheme.surface);
        c.drawRoundRect(focusRect, 14 * k * LiveTheme.round, 14 * k * LiveTheme.round, fill);
        for (int i = first; i < last; i++) {
            catRow(gx, i, top, r);
            boolean onTile = column == 0 && i == cat;
            lGroup.setColor(onTile ? LiveTheme.ink : 0xE6FFFFFF);
            lNum.setColor(onTile ? Ui.alpha(LiveTheme.ink, 0.6f) : 0x80FFFFFF);
            float base = r.centerY() + Ui.halfCap(lGroup);
            Xtream.Category cg = lineup.categories.get(i);
            String size = cg == search && query.trim().isEmpty() ? "" : String.valueOf(cg.channels.size());
            c.drawText(size, r.right - 24 * k - lNum.measureText(size), r.centerY() + Ui.halfCap(lNum), lNum);
            float tx = r.left + 28 * k;
            if (cg == search) {
                drawMagnifier(c, tx + 12 * k, r.centerY(), 13 * k, 0xE6FFFFFF);
                tx += 40 * k;
            }
            textAndTag(c, nameOfCategory(cg.name), tagOfCategory(cg.name), tx, base, lGroup, r.right - 24 * k - tx - 20 * k - lNum.measureText(size));
        }
        lGroup.setColor(0xE6FFFFFF);
        lNum.setColor(0x80FFFFFF);
    }

    /**
     * The channels: logo, number and name, what's on now (red) and a thin bar of how far it is; at the right ▶ on the
     * one playing, the catch-up mark, the heart of a favorite. The red tile on the focused one (with the channels in
     * front; `col` fades it).
     */
    private void drawChannels(Canvas c, List<Xtream.Channel> list, float cx, float col) {
        int rows = chRows();
        float top = lChTop.value;
        int first = Math.max(0, (int) Math.floor(top)), last = Math.min(list.size(), first + rows + 2);
        if (!list.isEmpty()) {
            chRow(cx, lCh.value, top, focusRect);
            fill.setColor(Ui.alpha(column == 2 ? 0x33FFFFFF : LiveTheme.tile, col));
            c.drawRoundRect(focusRect, 14 * k * LiveTheme.round, 14 * k * LiveTheme.round, fill);
        }
        Xtream.Channel playing = live.playing();
        long now = System.currentTimeMillis();
        for (int i = first; i < last; i++) {
            Xtream.Channel channel = list.get(i);
            chRow(cx, i, top, r);
            float onTile = column != 2 && i == ch ? col : 0;
            lName.setColor(LiveTheme.onTile(WHITE, onTile));
            lNum.setColor(LiveTheme.onTile(0x80FFFFFF, onTile));
            lShow.setColor(LiveTheme.onTile(LiveTheme.text, onTile));
            float rs = C_STEP / 126f, half = Math.min(34, 34 * rs);
            dst.set(r.left + 20 * k, r.centerY() - half * k, r.left + (20 + half * 104 / 34) * k, r.centerY() + half * k);
            drawLogo(c, channel, dst, 1);
            float x = r.left + (42 + half * 104 / 34) * k, right = r.right - 22 * k;
            // The marks at the top right: playing, catch-up, favorite.
            float mx = right - 14 * k, my = r.top + 36 * rs * k;
            if (playing != null && playing.id == channel.id) {
                drawPlayMark(c, mx, my, 13 * k, LiveTheme.onTile(LiveTheme.text, onTile));
                mx -= 40 * k;
            }
            if (channel.archive > 0 && channel.id > 0) {
                drawReplay(c, mx, my, 13 * k, 0xCCFFFFFF);
                mx -= 40 * k;
            }
            if (Xtream.isFavorite(lineup, channel.id)) {
                drawHeart(c, mx, my, 22 * k, LIVE_RED);
                mx -= 40 * k;
            }
            // Second line: what's on now (or what search found, or the recording's show).
            String second;
            float f = -1;
            if (list == search.channels && found.get(channel.id) != null) {
                second = found.get(channel.id);
            } else if (list == recordings.channels) {
                second = recNote.get(channel.id);
            } else {
                Xtream.Program p = onNow(channel, now);
                second = p == null ? "" : p.title;
                if (p != null) f = Ui.clamp((now - p.start) / (float) (p.end - p.start));
            }
            boolean two = second != null && !second.isEmpty();
            String num = channel.num > 0 ? channel.num + "  " : "";
            float nameY = two ? r.top + 46 * rs * k : r.centerY() + Ui.halfCap(lName);     // alone: in the middle
            c.drawText(num, x, nameY, lNum);
            float nx = x + lNum.measureText(num);
            textAndTag(c, nameOf(channel), tagOf(channel), nx, nameY, lName, mx + 14 * k - nx - 10 * k);
            if (two && i == ch) listMarquee.draw(c, second, x, r.top + 86 * rs * k, right - x, lShow, k);
            else if (two) c.drawText(ellipsize(second, lShow, right - x), x, r.top + 86 * rs * k, lShow);
            if (f >= 0) {
                float by = r.top + 104 * rs * k;
                fill.setColor(0x33FFFFFF);
                c.drawRect(x, by, right, by + 4 * k, fill);
                fill.setColor(0xB3FFFFFF);
                c.drawRect(x, by, x + (right - x) * f, by + 4 * k, fill);
            }
        }
    }

    /** The program on now on `channel` from what's loaded (asks for its guide when it isn't), or null. */
    private Xtream.Program onNow(Xtream.Channel channel, long now) {
        if (channel.id <= 0) return null;
        List<Xtream.Program> list = programs(channel);
        if (list == null) return null;
        int at = programAt(list, now);
        return at >= 0 ? list.get(at) : null;
    }

    /**
     * Beside the channels: the focused channel's header (logo, name, its category) and its schedule (time and title,
     * the show on now in red, a heading where a day starts); further right, the show on now's details in a card.
     */
    private void drawSchedule(Canvas c, Xtream.Channel channel, float x0) {
        float sw = SC_W * k;
        fill.setShader(null);
        fill.setColor((LiveTheme.pane & 0x00FFFFFF) | 0xEB000000);
        c.drawRect(x0, 0, x0 + sw, h, fill);
        box.set(x0 + 24 * k, 30 * k, x0 + 124 * k, 94 * k);
        drawLogo(c, channel, box, 1);
        float tx = box.right + 22 * k;
        textAndTag(c, nameOf(channel), tagOf(channel), tx, 56 * k, lHead, x0 + sw - 24 * k - tx);
        String where = channel.category == null ? "" : nameOfCategory(categoryName(channel.category));
        c.drawText(ellipsize(where, lSmall, x0 + sw - 24 * k - tx), tx, 92 * k, lSmall);
        if (channel.id <= 0) return;
        long now = System.currentTimeMillis();
        List<Xtream.Program> list = programs(channel);
        float y = 170 * k, step = 72 * k;
        if (list == null || list.isEmpty()) {
            c.drawText(list == null ? Ui.tr("Carregando o guia…", "Loading the guide…") : Ui.tr("Sem guia para este canal", "No guide for this channel"),
                    x0 + 28 * k, y, lSmall);
            return;
        }
        int nowAt = programAt(list, now);
        boolean inside = column == 2;
        int focused = inside ? Math.max(0, Math.min(list.size() - 1, sIdx)) : nowAt;
        int from = Math.max(0, (focused < 0 ? 0 : focused) - 3);
        java.util.Calendar cal = listCal;
        int today = dayOf(cal, now), lastDay = from > 0 ? dayOf(cal, list.get(from - 1).start) : today;
        float timeW = lTime.measureText("00:00 PM") + 24 * k;
        Xtream.Program shown = focused >= 0 ? list.get(focused) : null;
        for (int i = from; i < list.size() && y < h - 20 * k; i++) {
            Xtream.Program p = list.get(i);
            int day = dayOf(cal, p.start);
            if (day != lastDay) {       // a day starts: its name
                lastDay = day;
                String name = day == today ? Ui.tr("Hoje", "Today") : day == today + 1 ? Ui.tr("Amanhã", "Tomorrow")
                        : day == today - 1 ? Ui.tr("Ontem", "Yesterday") : dayFormat.format(new java.util.Date(p.start));
                c.drawText(name, x0 + 28 * k, y, lShow);
                y += step;
                if (y >= h - 20 * k) break;
            }
            boolean focusHere = inside && i == focused;
            if (focusHere) {     // the focus, in the schedule
                r.set(x0 + 12 * k, y - 48 * k, x0 + sw - 12 * k, y + 22 * k);
                fill.setColor(LiveTheme.tile);
                c.drawRoundRect(r, 14 * k * LiveTheme.round, 14 * k * LiveTheme.round, fill);
            }
            boolean on = i == nowAt;
            lShow.setColor(focusHere ? LiveTheme.ink : LiveTheme.text);
            lTime.setColor(focusHere ? LiveTheme.ink : 0x99FFFFFF);
            lTitle.setColor(focusHere ? LiveTheme.ink : 0xE6FFFFFF);
            Paint tp = on ? lShow : lTime, np = on ? lShow : lTitle;
            float end = x0 + sw - 24 * k;
            if (p.end <= now && replayable(channel, p, now)) {      // over, in the catch-up: it plays
                drawReplay(c, end - 12 * k, y - 10 * k, 12 * k, 0xB3FFFFFF);
                end -= 40 * k;
            }
            c.drawText(Ui.time(p.start), x0 + 28 * k, y, tp);
            c.drawText(ellipsize(p.title, np, end - (x0 + 28 * k + timeW)), x0 + 28 * k + timeW, y, np);
            y += step;
        }
        if (shown != null) drawShowCard(c, shown, x0 + sw + 30 * k, now);
    }

    private static int dayOf(java.util.Calendar cal, long t) {
        cal.setTimeInMillis(t);
        return cal.get(java.util.Calendar.YEAR) * 400 + cal.get(java.util.Calendar.DAY_OF_YEAR);
    }

    /** The category's name for an id (the provider's), or "". */
    private String categoryName(String id) {
        for (Xtream.Category cg : allCategories) if (id.equals(cg.id)) return cg.name;
        return "";
    }

    // MARK: - Country tags

    /** Each category's country (from its name: "US | Entertainment"), for the channels whose names don't say. */
    private java.util.Map<String, String> categoryCountry = new java.util.HashMap<>();
    private final Paint tagPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** `ch`'s country tag: "" with the tags off (Live TV's settings) or none known. */
    String tagOf(Xtream.Channel ch) {
        return ch == null || !LiveCountry.on(getContext()) ? "" : LiveCountry.of(ch, categoryCountry);
    }

    /** `ch`'s name as shown: without its country when the tag shows it ("US: TLC" → "TLC"). */
    String nameOf(Xtream.Channel ch) {
        if (ch == null || ch.name == null) return "";
        if (!LiveCountry.on(getContext())) return ch.name;
        String n = LiveCountry.splitCached(ch.name, false).name;
        return n.isEmpty() ? ch.name : n;
    }

    /** A category's tag and its name as shown ("US | Entertainment" → US, "Entertainment"). */
    private String tagOfCategory(String name) {
        return name == null || !LiveCountry.on(getContext()) ? "" : LiveCountry.splitCached(name, true).code;
    }

    private String nameOfCategory(String name) {
        if (name == null) return "";
        if (!LiveCountry.on(getContext())) return name;
        String n = LiveCountry.splitCached(name, true).name;
        return n.isEmpty() ? name : n;
    }

    /** `text` at `x`, then its tag; both within `room` (the text cut short, never the tag). Returns where they end. */
    private float textAndTag(Canvas c, String text, String tag, float x, float base, Paint p, float room) {
        LiveCountry.label(p, tagPaint);
        float tagW = LiveCountry.tagWidth(tag, p, tagPaint, k);
        String t = ellipsize(text, p, Math.max(1, room - tagW));
        c.drawText(t, x, base, p);
        float end = x + p.measureText(t);
        if (tag.isEmpty()) return end;
        return LiveCountry.drawTag(c, tag, end + 12 * k, base, p, tagPaint, k);
    }

    /** How wide textAndTag draws (its text cut to `room` as it would be). */
    private float textAndTagWidth(String text, String tag, Paint p, float room) {
        LiveCountry.label(p, tagPaint);
        float tagW = LiveCountry.tagWidth(tag, p, tagPaint, k);
        return p.measureText(ellipsize(text, p, Math.max(1, room - tagW))) + tagW;
    }

    /** The show's card at the right, over the video: title, times with a small bar and the minutes left, synopsis. */
    private void drawShowCard(Canvas c, Xtream.Program p, float x, long now) {
        float cw = (1872 * k) - x, pad = 32 * k, inner = Math.max(1, cw - 2 * pad);
        if (cw < 200 * k) return;
        if (!p.title.equals(cardTitleText) || cardTitle == null || cardTitle.getWidth() != Math.round(inner)) {
            cardTitleText = p.title;
            cardTitle = StaticLayout.Builder.obtain(p.title, 0, p.title.length(), lCardTitle, Math.round(inner))
                    .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).build();
        }
        String about = p.about == null ? "" : p.about;
        if (!about.equals(cardAboutText) || (cardAbout == null && !about.isEmpty()) || cardAbout != null && cardAbout.getWidth() != Math.round(inner)) {
            cardAboutText = about;
            cardAbout = about.isEmpty() ? null : StaticLayout.Builder.obtain(about, 0, about.length(), lCardAbout, Math.round(inner))
                    .setMaxLines(6).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0, 1.05f).build();
        }
        Bitmap art = artOf(p);
        float artH = art == null ? 0 : Math.min(cw * 9 / 16, 300 * k);
        float top = 48 * k, hgt = artH + pad + cardTitle.getHeight() + 64 * k + (cardAbout == null ? 0 : cardAbout.getHeight() + 16 * k) + pad;
        r.set(x, top, x + cw, top + hgt);
        fill.setColor((LiveTheme.card & 0x00FFFFFF) | 0xEB000000);
        c.drawRoundRect(r, 18 * k * LiveTheme.round, 18 * k * LiveTheme.round, fill);
        if (art != null) {      // the show's picture across the card's top, fading into it
            artRect.set(x, top, x + cw, top + artH);
            drawCropped(c, art, artRect, 18 * k);
            cardFade.setShader(cardFadeShader(top + artH * 0.55f, top + artH));
            c.drawRect(x, top + artH * 0.55f, x + cw, top + artH, cardFade);
            top += artH;
        }
        c.save();
        c.translate(x + pad, top + pad);
        cardTitle.draw(c);
        c.restore();
        float y = top + pad + cardTitle.getHeight() + 44 * k;
        String times = Ui.time(p.start) + " – " + Ui.time(p.end);
        c.drawText(times, x + pad, y, lTime);
        float bx = x + pad + lTime.measureText(times) + 24 * k, bw = 90 * k;
        if (p.start > now || p.end <= now) {     // not on now: its day instead of how far it is
            int d = dayOf(listCal, p.start) - dayOf(listCal, now);     // the TV's own days
            String when = p.end <= now ? Ui.tr("Já passou", "Ended") : d == 0 ? Ui.tr("Hoje", "Today")
                    : d == 1 ? Ui.tr("Amanhã", "Tomorrow") : dayFormat.format(new java.util.Date(p.start));
            c.drawText(when, x + pad + lTime.measureText(times) + 28 * k, y, lTime);
            if (cardAbout != null) {
                c.save();
                c.translate(x + pad, y + 24 * k);
                cardAbout.draw(c);
                c.restore();
            }
            return;
        }
        float f = Ui.clamp((now - p.start) / (float) Math.max(1, p.end - p.start));
        fill.setColor(0x40FFFFFF);
        c.drawRoundRect(bx, y - 12 * k, bx + bw, y - 6 * k, 3 * k, 3 * k, fill);
        fill.setColor(WHITE);
        c.drawRoundRect(bx, y - 12 * k, bx + bw * f, y - 6 * k, 3 * k, 3 * k, fill);
        c.drawText(LiveView.minutesLeft((int) Math.max(1, Math.round((p.end - now) / 60_000.0))), bx + bw + 24 * k, y, lTime);
        if (cardAbout != null) {
            c.save();
            c.translate(x + pad, y + 24 * k);
            cardAbout.draw(c);
            c.restore();
        }
    }

    // MARK: - The shows' pictures

    private static float ART_X = 1180, ART_H = 410;
    private final RectF artRect = new RectF();
    private final android.graphics.Matrix artMatrix = new android.graphics.Matrix();
    private final Paint artPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG), cardFade = new Paint();
    private Bitmap artBitmap;
    private BitmapShader artShader;
    private Shader artFadeLeft, artFadeDown, artFadeTop, cardFadeShader;
    private float cardFadeFrom = -1, cardFadeTo = -1;

    /** The show's picture (the full guide's, else TMDB's), null while there's none. */
    Bitmap artOf(Xtream.Program p) {
        String url = LiveArt.of(getContext(), p, this::postInvalidate);
        return url.isEmpty() ? null : LiveLogos.get(getContext(), url, this::postInvalidate);
    }

    /** `b` filling `dst` (cropped to it, centered), its corners rounded `radius`. */
    void drawCropped(Canvas c, Bitmap b, RectF dst, float radius) {
        if (b != artBitmap) {
            artBitmap = b;
            artShader = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        }
        float s = Math.max(dst.width() / b.getWidth(), dst.height() / b.getHeight());
        artMatrix.setScale(s, s);
        artMatrix.postTranslate(dst.centerX() - b.getWidth() * s / 2, dst.centerY() - b.getHeight() * s / 2);
        artShader.setLocalMatrix(artMatrix);
        artPaint.setShader(artShader);
        c.drawRoundRect(dst, radius, radius, artPaint);
    }

    /** The card's fade from its picture into it (made again only when the card moves). */
    private Shader cardFadeShader(float from, float to) {
        if (cardFadeShader == null || from != cardFadeFrom || to != cardFadeTo) {
            cardFadeFrom = from;
            cardFadeTo = to;
            cardFadeShader = new LinearGradient(0, from, 0, to, LiveTheme.card & 0x00FFFFFF, LiveTheme.card, Shader.TileMode.CLAMP);
        }
        return cardFadeShader;
    }

    /** A small ▶: the channel playing. */
    private void drawPlayMark(Canvas c, float cx, float cy, float s, int color) {
        heart.reset();
        heart.moveTo(cx - s * 0.7f, cy - s);
        heart.lineTo(cx + s, cy);
        heart.lineTo(cx - s * 0.7f, cy + s);
        heart.close();
        fill.setColor(color);
        c.drawPath(heart, fill);
    }

    /** The rect of category row `row` (a fraction while gliding), the column at `gx`, scrolled to `top`. */
    private void catRow(float gx, float row, float top, RectF out) {
        float y = (GR_TOP + (row - top) * GR_STEP) * k;
        out.set(gx + 24 * k, y, gx + (GR_W - 24) * k, y + (GR_STEP - 8) * k);
    }

    /** The rect of channel row `row`, the panel at `cx`, scrolled to `top`. */
    private void chRow(float cx, float row, float top, RectF out) {
        float y = (C_TOP + 10 + (row - top) * C_STEP) * k;
        out.set(cx + 12 * k, y, cx + (CH_W - 12) * k, y + (C_STEP - 8) * k);
    }

    /** Sets the list's springs to the focus (snap: no movement). */
    private void aimList(boolean snap) {
        lCat.target = cat;
        lCh.target = ch;
        lCol.target = Math.min(1, column);
        lCatTop.target = catTop;
        lChTop.target = chTop;
        if (snap) {
            lCat.snap(cat);
            lCh.snap(ch);
            lCol.snap(Math.min(1, column));
            lCatTop.snap(catTop);
            lChTop.snap(chTop);
        }
    }

    /** The channel's logo fit inside `box` on a soft tile; without one, its first letters. */
    void drawLogo(Canvas c, Xtream.Channel channel, RectF box, float alpha) {
        drawLogo(c, channel, box, alpha, false);
    }

    /**
     * The channel's logo fit inside `box`, as it is (its own transparency, no tile behind it); `onLight`: it's under
     * the white focus, so it gets a dark tile (a white logo would vanish). Without a logo, its first letters on a tile.
     */
    void drawLogo(Canvas c, Xtream.Channel channel, RectF box, float alpha, boolean onLight) {
        drawLogo(c, channel, box, alpha, onLight, false);
    }

    /** As above; `left`: at the box's left (the guide's column, set so), else in its middle. */
    void drawLogo(Canvas c, Xtream.Channel channel, RectF box, float alpha, boolean onLight, boolean left) {
        Bitmap b = logo(channel.logo);
        // Another source's logo that can't be had (gone from its site): the provider's own.
        if (b == null && LiveLogos.failed(channel.logo) && channel.providerLogo != null && !channel.providerLogo.isEmpty()
                && !channel.providerLogo.equals(channel.logo)) b = logo(channel.providerLogo);
        if (left && b == null) {        // its letters on a tile at the left, a logo's shape, not across the whole box
            tileBox.set(box.left, box.top, Math.min(box.right, box.left + box.height() * 1.7f), box.bottom);
            box = tileBox;
        }
        fill.setShader(null);
        if (b == null || onLight) {
            // On the white focus: a dark tile (a white logo would vanish); else the style's card.
            fill.setColor(Ui.alpha(onLight ? 0xFF2C2C2E : LiveTheme.card, alpha));
            c.drawRoundRect(box, 10 * k * LiveTheme.round, 10 * k * LiveTheme.round, fill);
        }
        if (b != null) {
            float pad = 6 * k, bw = box.width() - 2 * pad, bh = box.height() - 2 * pad;
            float s = Math.min(bw / b.getWidth(), bh / b.getHeight());
            float dw = b.getWidth() * s, dh = b.getHeight() * s;
            float lx = left ? box.left + pad : box.centerX() - dw / 2;
            logoRect.set(lx, box.centerY() - dh / 2, lx + dw, box.centerY() + dh / 2);
            if (!onLight && LiveLogos.isDark(b)) {      // a dark logo on a light plate, so it shows
                markRect.set(logoRect.left - pad, logoRect.top - pad, logoRect.right + pad, logoRect.bottom + pad);
                fill.setColor(Ui.alpha(0xE6E8E8ED, alpha));
                c.drawRoundRect(markRect, 8 * k * LiveTheme.round, 8 * k * LiveTheme.round, fill);
                fill.setColor(0xFFFFFFFF);
            }
            fill.setAlpha(Math.round(255 * alpha));
            c.drawBitmap(b, null, logoRect, fill);
            fill.setAlpha(255);
            return;
        }
        String letters = initials(channel.name);
        pillText.setAlpha(Math.round(0xB3 * alpha));
        c.drawText(letters, box.centerX() - pillText.measureText(letters) / 2, box.centerY() + Ui.halfCap(pillText), pillText);
        pillText.setAlpha(255);
    }

    private final RectF logoRect = new RectF(), markRect = new RectF(), tileBox = new RectF();

    private void drawStatus(Canvas c) {
        if (status.isEmpty()) return;
        float cx = mode == LIST || mode == SETTINGS ? (PANEL_W + 1920) / 2f * k : w / 2f;
        String s = ellipsize(status, label, 900 * k);
        float tw = label.measureText(s);
        r.set(cx - tw / 2 - 30 * k, h / 2f - 34 * k, cx + tw / 2 + 30 * k, h / 2f + 34 * k);
        fill.setShader(null);
        fill.setColor((LiveTheme.card & 0x00FFFFFF) | 0xE6000000);
        c.drawRoundRect(r, 34 * k, 34 * k, fill);
        c.drawText(s, cx - tw / 2, r.centerY() + Ui.halfCap(label), label);
    }

    private void drawStatusAt(Canvas c, float x, float y) {
        if (status.isEmpty()) return;
        c.drawText(ellipsize(status, warn, 790 * k), x, y, warn);
    }

    /** Grows `rect` by `by` (a fraction) around its center: the tvOS focus. */
    private static void grow(RectF rect, float by) {
        float dx = rect.width() * by / 2, dy = rect.height() * by / 2;
        rect.inset(-dx, -dy);
    }

    // MARK: - Keys

    boolean onKey(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (closingTo >= 0 && down) {    // a key while a screen goes: it's gone
            mode = closingTo;
            closingTo = -1;
            appear.snap(1);
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            // Back: watching, the guide (TiviMate's); the guide, back to the video (before anything plays, the
            // categories, then twice out of Live TV). Held: watching, out of Live TV. It acts when let go (held: once, at once).
            if (down) {
                if (e.getRepeatCount() == 0) {
                    backLong = false;
                } else if (!backLong && digits.isEmpty() && (mode == WATCH || mode == GUIDE)) {
                    backLong = true;
                    if (mode == GUIDE && live.playing() != null) closeGuide(); else if (mode == WATCH) live.finish();
                }
                return true;
            }
            if (backLong || e.isCanceled()) {
                backLong = false;
                return true;
            }
            if (mode == SIGN_IN && live.cancelAdding()) return true;     // adding a playlist: back to the one before
            if (!digits.isEmpty()) {    // a number half typed: Back forgets it
                clearDigits();
                return true;
            }
            if (mode == WATCH && overlay.visible()) {    // the overlay goes first
                overlay.hide();
                return true;
            }
            // A past program, or live TV behind: Back goes to its channel live.
            if (mode == WATCH && (live.catchup() != null || live.shifted())) {
                live.backToLive();
                return true;
            }
            if (mode == GUIDE && gGroups) {      // the guide's groups: back to the guide (nothing playing: twice, out)
                if (live.playing() != null) closeGuideGroups(); else exitPress();
                invalidate();
                return true;
            }
            // In the guide's past: Back comes to now first.
            if (mode == GUIDE && gCol == 1 && inPast()) {
                gTime = System.currentTimeMillis();
                guideMoved();
                invalidate();
                return true;
            }
            if (mode == SETTINGS) {
                settings.back();
                return true;
            }
            if (mode == LIST && lineup != null) {
                listBack();
                return true;
            }
            if (mode == GUIDE && live.playing() != null) {      // the guide: back to the channel, full screen
                closeGuide();
                return true;
            }
            if (mode == GUIDE && lineup != null) {      // nothing playing yet: the groups beside the guide (TiviMate's)
                openGuideGroups();
                return true;
            }
            if (mode == WATCH && live.playing() != null && lineup != null) {
                openGuide(playCat);
                return true;
            }
            live.finish();      // signing in, or nothing to watch: out of Live TV
            return true;
        }
        boolean ours = isOurs(code);
        if (!ours) return false;
        // The channel stopped and gave up connecting (its status says so): OK tries again.
        if (mode == WATCH && !buffering && !status.isEmpty() && live.playing() != null && !overlay.visible()
                && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            if (down && e.getRepeatCount() == 0) live.retryNow();
            return true;
        }
        if (mode == WATCH && digits.isEmpty() && lineup != null && holdable(code)) return watchPress(e, down);
        if (mode == GUIDE && gGroups && digits.isEmpty()) {
            if (down) guideGroupsKey(code);
            return true;
        }
        if (mode == GUIDE && code == KeyEvent.KEYCODE_DPAD_LEFT && digits.isEmpty()) return guideLeft(e, down);
        if (mode == GUIDE && ok(code) && digits.isEmpty()) return guideOk(e, down);
        if (mode == GUIDE && (code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || code == KeyEvent.KEYCODE_MEDIA_REWIND)
                && digits.isEmpty()) {
            return guideSkip(e, down);
        }
        if (mode == LIST && ok(code) && digits.isEmpty() && column == 1 && cat >= 0 && cat < lineup.categories.size()
                && lineup.categories.get(cat) == recordings) {
            return recordingOk(e, down);
        }
        if (mode == LIST && ok(code) && digits.isEmpty() && column == 1 && cat >= 0 && cat < lineup.categories.size()
                && lineup.categories.get(cat) != search) {
            return channelOk(e, down);
        }
        if (!down) return true;
        int digit = digit(code);
        if (digit >= 0) {
            typeDigit(digit);
            return true;
        }
        if (!digits.isEmpty() && ok(code)) {   // OK tunes the typed number now
            commitDigits();
            return true;
        }
        if (code == KeyEvent.KEYCODE_SEARCH) {
            if (mode != SIGN_IN) openSearch();
            return true;
        }
        if (code == KeyEvent.KEYCODE_VOICE_ASSIST && mode != SIGN_IN && lineup != null) {     // a remote's voice key: say it
            live.voiceSearch(said -> {
                if (said == null || said.trim().isEmpty() || voiceWatch(said)) return;
                overlay.hide();
                new LiveSearch(getContext(), this, live, said.trim()).show();
            });
            return true;
        }
        if (code == KeyEvent.KEYCODE_MENU && mode != SIGN_IN && lineup != null) {   // the remote's Menu: the settings
            if (mode != SETTINGS) openSettings();
            return true;
        }
        switch (mode) {
            case SIGN_IN: signInKey(code); break;
            case LIST: listKey(code); break;
            case GUIDE: guideKey(code); break;
            case SETTINGS: settings.key(code); break;
            default: watchKey(code); break;
        }
        invalidate();
        return true;
    }

    private boolean isOurs(int code) {
        if (digit(code) >= 0) return mode != SIGN_IN;
        Xtream.Channel playing = live.playing();
        switch (code) {
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:     // start over, on a channel with catch-up
                return mode == WATCH && playing != null && playing.archive > 0;
            case KeyEvent.KEYCODE_CAPTIONS:     // the remote's CC: the subtitles
                return mode == WATCH && playing != null;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                return mode == WATCH && playing != null;        // live TV pauses too
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:     // in the guide: two hours on
                return mode == GUIDE || (live.catchup() != null || live.shifted()) && mode == WATCH;
            case KeyEvent.KEYCODE_MEDIA_REWIND:     // live, on a channel with catch-up: start over; in the guide: back
                return mode == GUIDE || mode == WATCH && (live.catchup() != null || live.shifted() || playing != null && playing.archive > 0);
            case KeyEvent.KEYCODE_SEARCH:
            case KeyEvent.KEYCODE_LAST_CHANNEL:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_INFO:
            case KeyEvent.KEYCODE_GUIDE:
                return true;
            case KeyEvent.KEYCODE_MEDIA_RECORD:
                return mode == WATCH;
            case KeyEvent.KEYCODE_PROG_RED:      // TiviMate's color keys
            case KeyEvent.KEYCODE_PROG_GREEN:
            case KeyEvent.KEYCODE_PROG_YELLOW:
            case KeyEvent.KEYCODE_PROG_BLUE:
                return mode == WATCH || mode == GUIDE;
            default:
                return false;
        }
    }

    /** The keys with a long press while watching (TiviMate's): they act when let go, or held, at once. */
    private static boolean holdable(int code) {
        return ok(code) || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_MENU;
    }

    private boolean watchPress(KeyEvent e, boolean down) {
        if (overlay.armed() && !ok(e.getKeyCode())) {     // the player's bar and buttons: at once, held repeats
            if (down) {
                watchKey(e.getKeyCode());
                invalidate();
            }
            return true;
        }
        if (down) {
            if (e.getRepeatCount() == 0) {
                pressCode = e.getKeyCode();
                pressLong = false;
            } else if (pressCode == e.getKeyCode() && !pressLong) {
                pressLong = true;
                watchLong(pressCode);
                invalidate();
            }
            return true;
        }
        boolean act = pressCode == e.getKeyCode() && !pressLong && !e.isCanceled();
        pressCode = -1;
        pressLong = false;
        if (act) {
            watchKey(e.getKeyCode());
            invalidate();
        }
        return true;
    }

    /** A key held while watching: as set in Remote buttons (by default OK the menu, ▶ the last channel, ↑ History…). */
    private void watchLong(int code) {
        String slot = LiveKeys.slotOf(code, true);
        if (slot != null) act(LiveKeys.action(getContext(), slot));
    }

    /** The menu (long OK or Menu while watching): where to go. */
    private void showMenu() {
        String sleep = Ui.tr("Timer para dormir", "Sleep timer") + (sleepAt > 0 ? "  ·  " + Ui.time(sleepAt) : "");
        String[] items = {Ui.tr("Início", "Home"), Ui.tr("Ver no início", "Watch on Home"), Ui.tr("Buscar", "Search"), Ui.tr("Lista de canais", "Channels list"), Ui.tr("Histórico", "History"),
                Ui.tr("Perfis", "Profiles"),
                Ui.tr("Gravações", "Recordings"), Ui.tr("Guia de TV", "TV guide"), Ui.tr("Multivisão", "Multiview"),
                sleep, Ui.tr("Ajustes", "Settings"), Ui.tr("Sair da TV ao vivo", "Exit Live TV")};
        overlay.hide();
        // The channel on, and its show, at the top.
        Xtream.Channel on = live.playing();
        List<Xtream.Program> progs = on == null ? null : programs(on);
        int at = progs == null ? -1 : programAt(progs, System.currentTimeMillis());
        LiveSheet.menu(getContext())
                .setTitle(on == null ? Ui.tr("TV ao vivo", "Live TV") : (on.num > 0 ? on.num + "  " : "") + on.name)
                .setMessage(at >= 0 ? progs.get(at).title : "")
                .setIcons("home", "tv", "search", "list", "history", "profiles", "recordings", "guide", "multiview", "bedtime", "settings", "exit")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: openHome(); break;
                        case 1: live.watchOnHome(); break;     // the channel in a small window on the home screen
                        case 2: openSearch(); break;
                        case 3: openList(); break;
                        case 4: openHistory(); break;
                        case 5: openProfiles(); break;
                        case 6: openRecordings(); break;
                        case 7: openGuide(playCat); break;
                        case 8: live.openMultiview(); break;
                        case 9: sleepMenu(); break;
                        case 10: openSettings(); break;
                        default: live.finish(); break;
                    }
                    invalidate();
                })
                .show();
    }

    // MARK: - Sleep timer

    /** When the TV goes to sleep (wall clock), 0 when the timer is off. */
    private long sleepAt;
    private LiveSheet sleepCard;
    private final Runnable sleepTick = this::sleepTick;
    private static final long SLEEP_WARNING = 60_000;

    /** When the sleep timer goes off (0: it's off); the player shows it. */
    long sleepAt() {
        return sleepAt;
    }

    /** In so many minutes, or at the end of the show on now; or the timer off. */
    void sleepMenu() {
        long now = System.currentTimeMillis();
        Xtream.Channel playing = live.playing();
        List<Xtream.Program> progs = playing == null ? null : programs(playing);
        int at = progs == null ? -1 : programAt(progs, now);
        Xtream.Program show = at >= 0 ? progs.get(at) : null;
        List<String> labels = new ArrayList<>();
        final List<Long> times = new ArrayList<>();
        if (sleepAt > 0) {
            labels.add(Ui.tr("Desligar o timer", "Turn the timer off") + "  ·  " + Ui.time(sleepAt));
            times.add(0L);
        }
        for (int m : new int[] {15, 30, 60, 90, 120}) {
            labels.add(m < 60 ? m + " min" : m % 60 == 0 ? (m / 60) + " h" : (m / 60) + " h " + (m % 60) + " min");
            times.add(now + m * 60_000L);
        }
        if (show != null && show.end > now + SLEEP_WARNING) {
            labels.add(Ui.tr("No fim deste programa", "At the end of this show") + "  ·  " + Ui.time(show.end));
            times.add(show.end);
        }
        String[] icons = new String[labels.size()];
        for (int i = 0; i < icons.length; i++) icons[i] = times.get(i) == 0 ? "close" : i == icons.length - 1 && show != null
                && times.get(i) == show.end ? "schedule" : "timer";
        LiveSheet.menu(getContext())
                .setTitle(Ui.tr("Timer para dormir", "Sleep timer"))
                .setIcons(icons)
                .setItems(labels.toArray(new String[0]), (d, which) -> setSleep(times.get(which)))
                .show();
    }

    private void setSleep(long at) {
        sleepAt = at;
        removeCallbacks(sleepTick);
        if (sleepCard != null) {
            sleepCard.dismiss();
            sleepCard = null;
        }
        if (at <= 0) {
            flash(Ui.tr("Timer desligado", "Sleep timer off"));
            return;
        }
        // The card a minute before (straight away when that's sooner).
        postDelayed(sleepTick, Math.max(0, at - SLEEP_WARNING - System.currentTimeMillis()));
        flash(Ui.tr("A TV dorme às ", "The TV sleeps at ") + Ui.time(at));
    }

    // MARK: - Up next

    private LiveSheet upNextCard;
    private int upNextLeft;
    private Runnable upNextPlay, upNextTick;
    private static final int UP_NEXT_S = 10;

    /**
     * The card at the end of a past program or a recording: what's next, playing in UP_NEXT_S seconds (counting down),
     * or now; dismissed (Back, or the other button), `cancel` (the channel live).
     */
    void upNext(String title, String sub, String playLabel, Runnable play, Runnable cancel) {
        dismissUpNext();
        upNextLeft = UP_NEXT_S;
        upNextPlay = play;
        final Runnable[] done = {null};
        upNextCard = LiveSheet.menu(getContext())
                .setTitle(Ui.tr("A seguir: ", "Up next: ") + title)
                .setMessage(upNextText(sub))
                .setIcons("play", "close")
                .setItems(new String[] {playLabel, Ui.tr("Agora não", "Not now")}, (d, which) -> {
                    Runnable r = which == 0 ? upNextPlay : cancel;
                    dismissUpNext();
                    r.run();
                })
                .setOnCancelListener(d -> {     // Back: no
                    dismissUpNext();
                    cancel.run();
                })
                .create();
        upNextCard.show();
        upNextTick = () -> {
            if (upNextCard == null) return;
            if (--upNextLeft <= 0) {
                Runnable r = upNextPlay;
                dismissUpNext();
                r.run();
                return;
            }
            upNextCard.setMessage(upNextText(sub));
            postDelayed(upNextTick, 1000);
        };
        postDelayed(upNextTick, 1000);
    }

    private String upNextText(String sub) {
        return sub + "  ·  " + Ui.tr("Começa em ", "Plays in ") + upNextLeft + " s";
    }

    private void dismissUpNext() {
        if (upNextTick != null) removeCallbacks(upNextTick);
        upNextTick = null;
        if (upNextCard != null) {
            upNextCard.setOnCancelListener(null);
            upNextCard.dismiss();
        }
        upNextCard = null;
    }

    /** Live TV left the screen (Home, another app): the timer goes off quietly, its card with it. */
    void cancelSleep() {
        dismissUpNext();        // an Up next card goes with the screen too
        if (sleepAt <= 0 && sleepCard == null) return;
        sleepAt = 0;
        removeCallbacks(sleepTick);
        if (sleepCard != null) {
            sleepCard.setOnCancelListener(null);
            sleepCard.dismiss();
            sleepCard = null;
        }
    }

    /** The last minute: a card counting down (Keep watching, Sleep now); then the TV goes to sleep. */
    private void sleepTick() {
        if (sleepAt <= 0) return;
        long left = sleepAt - System.currentTimeMillis();
        if (left <= 0) {
            if (sleepCard != null) sleepCard.dismiss();
            sleepCard = null;
            sleepAt = 0;
            goToSleep();
            return;
        }
        String text = Ui.tr("A TV vai dormir em ", "The TV goes to sleep in ") + ((left + 999) / 1000) + " s.";
        if (sleepCard == null) {
            sleepCard = LiveSheet.menu(getContext())
                    .setTitle(Ui.tr("Timer para dormir", "Sleep timer"))
                    .setMessage(text)
                    .setPositiveButton(Ui.tr("Continuar assistindo", "Keep watching"), (d, w) -> {
                        sleepCard = null;
                        setSleep(0);
                    })
                    .setNegativeButton(Ui.tr("Dormir agora", "Sleep now"), (d, w) -> {
                        sleepCard = null;
                        sleepAt = 0;
                        removeCallbacks(sleepTick);
                        goToSleep();
                    })
                    .setOnCancelListener(d -> {      // Back: as Keep watching
                        sleepCard = null;
                        setSleep(0);
                    })
                    .setIcons("play", "bedtime")
                    .create();
            sleepCard.show();
        } else {
            sleepCard.setMessage(text);
        }
        postDelayed(sleepTick, 1000);
    }

    /**
     * The TV's own sleep key, through the TV's ADB (the connection the home screen's switch uses), then Live TV
     * closes (the stream stops). Without that connection Live TV only closes and the TV sleeps by its own settings.
     */
    private void goToSleep() {
        final android.content.Context app = getContext().getApplicationContext();
        new Thread(() -> {
            if (!com.glasslauncher.tv.data.LocalShell.runCommand(app, "input keyevent KEYCODE_SLEEP")) {
                AppLog.w("live: couldn't put the TV to sleep");
            }
            post(live::finish);
        }, "sleep").start();
    }

    /** History (the channels watched lately), in the list. */
    private void openHistory() {
        refreshHistory();
        openCategory(history);
    }

    /** The list on category `cg`, its channels in front. */
    private void openCategory(Xtream.Category cg) {
        openList();
        int at = lineup.categories.indexOf(cg);
        if (at < 0) return;
        cat = at;
        ch = 0;
        chTop = 0;
        column = channels().isEmpty() ? 0 : 1;
        keepVisible();
        aimList(true);
        announce();
    }

    /** Back in the list: from watching, back to the video; from the guide, the categories, then (twice) out. */
    private void listBack() {
        if (column == 2) {      // the schedule: back to the channels
            column = 1;
            aimList(false);
            invalidate();
        } else if (listFromGuide && column == 1) {
            column = 0;
            keepVisible();
            aimList(false);
            announce();
            invalidate();
        } else if (listFromGuide || live.playing() == null) {
            exitPress();
        } else {
            leave(WATCH);
        }
    }

    /** Back twice within 2 s: out of Live TV (the first one says so). */
    private void exitPress() {
        long now = SystemClock.uptimeMillis();
        if (now - backAt < EXIT_MS) {
            live.finish();
            return;
        }
        backAt = now;
        String again = Ui.tr("Aperte Voltar de novo para sair", "Press Back again to exit");
        setStatus(again);
        postDelayed(() -> { if (status.equals(again)) setStatus(""); }, EXIT_MS);
    }

    /** ↑ ↓ while watching: the next (or previous) channel's info, without tuning it (OK does). */
    private void peekNext(int dir) {
        Xtream.Channel from = overlay.peeking() != null ? overlay.peeking() : live.playing();
        if (from == null || playCat >= lineup.categories.size()) return;
        List<Xtream.Channel> list = lineup.categories.get(playCat).channels;
        if (list.isEmpty()) return;
        int at = 0;
        for (int i = 0; i < list.size(); i++) if (list.get(i).id == from.id) at = i;
        Xtream.Channel next = list.get((at + dir + list.size()) % list.size());
        programs(next);     // its guide, if it isn't here yet
        overlay.peek(next);
        announceForAccessibility((next.num > 0 ? next.num + " " : "") + next.name);
    }

    private static boolean ok(int code) {
        return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    /** 0 to 9 for the number keys (the remote's and a keyboard's number pad), else -1. */
    private static int digit(int code) {
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) return code - KeyEvent.KEYCODE_0;
        if (code >= KeyEvent.KEYCODE_NUMPAD_0 && code <= KeyEvent.KEYCODE_NUMPAD_9) return code - KeyEvent.KEYCODE_NUMPAD_0;
        return -1;
    }

    // MARK: - Channel numbers

    /** One more digit of a channel number (also from the home screen's number keys, through Live). */
    void typeDigit(int d) {
        if (lineup == null || mode == SIGN_IN || mode == SETTINGS) return;
        if (digits.length() >= 5) digits = "";
        digits += d;
        digitsChannel = byNumber(Integer.parseInt(digits));
        digitsAt = SystemClock.uptimeMillis();
        removeCallbacks(digitsDone);
        postDelayed(digitsDone, DIGITS_MS);
        announceForAccessibility(digits + (digitsChannel == null ? "" : ", " + digitsChannel.name));
        invalidate();
    }

    private void clearDigits() {
        removeCallbacks(digitsDone);
        digits = "";
        digitsChannel = null;
        invalidate();
    }

    private Xtream.Channel byNumber(int n) {
        for (Xtream.Channel ch : lineup.all()) if (ch.num == n) return ch;
        for (Xtream.Channel ch : lineup.all()) {      // a merged channel's other sources keep their numbers
            if (ch.sources != null) for (Xtream.Channel s : ch.sources) if (s.num == n) return ch;
        }
        return null;
    }

    /**
     * The typed number tunes its channel. In the guide the focus goes to its row when the category has it, in the
     * channel list to it likewise; otherwise it plays, full screen.
     */
    private void commitDigits() {
        String typedNumber = digits;
        Xtream.Channel target = digitsChannel;
        clearDigits();
        if (typedNumber.isEmpty() || lineup == null) return;
        if (target == null) {
            flash(Ui.tr("Nenhum canal ", "No channel ") + typedNumber);
            return;
        }
        if (mode == GUIDE) {
            List<Xtream.Channel> rows = guideChannels();
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).id != target.id) continue;
                gRow = i;
                gCol = 1;
                gTime = System.currentTimeMillis();
                guideMoved();
                return;
            }
        } else if (mode == LIST && column == 1) {
            List<Xtream.Channel> list = channels();
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).id != target.id) continue;
                ch = i;
                keepVisible();
                aimList(false);
                announce();
                return;
            }
        }
        tune(target);
        if (mode == GUIDE) closeGuide();
        else if (mode == LIST) leave(WATCH);
    }

    /** Plays `target` live; ↑ ↓ then go through the same category when it's in it, else all channels. */
    private void tune(Xtream.Channel target) {
        boolean inCategory = false;
        if (playCat < lineup.categories.size()) for (Xtream.Channel c : lineup.categories.get(playCat).channels) if (c.id == target.id) inCategory = true;
        if (!inCategory) playCat = Math.min(1, lineup.categories.size() - 1);
        live.play(target);
    }

    /** A message for two seconds. */
    void flash(String message) {
        setStatus(message);
        postDelayed(() -> { if (status.equals(message)) setStatus(""); }, 2000);
    }

    // MARK: - The parental PIN

    /** Asks for the PIN: `ok` when it's right; a wrong one (or Cancel) runs `cancel` (may be null). */
    /** The parental PIN asked (the number pad): `ok` once it's right, `cancel` when left. */
    void askPin(Runnable ok, Runnable cancel) {
        LivePinPad.ask(getContext(), Ui.tr("PIN dos pais", "Parental PIN"), Ui.tr("Digite o PIN", "Enter the PIN"), true, pin -> {
            if (!LiveOptions.checkPin(getContext(), pin)) return false;
            ok.run();
            invalidate();
            return true;
        }, cancel);
    }

    /** A new PIN, typed twice (4 to 8 digits); `done` once it's kept. */
    void askNewPin(Runnable done) {
        askDigits(Ui.tr("Novo PIN", "New PIN"), first -> {
            if (first.length() < 4 || first.length() > 8) {
                flash(Ui.tr("O PIN tem de 4 a 8 números", "The PIN needs 4 to 8 digits"));
                return;
            }
            askDigits(Ui.tr("O mesmo PIN de novo", "The same PIN again"), second -> {
                if (!second.equals(first)) {
                    flash(Ui.tr("Os PINs não batem", "The PINs don't match"));
                    return;
                }
                LiveOptions.setPin(getContext(), first);
                done.run();
                invalidate();
            });
        });
    }

    private interface Typed {
        void got(String text);
    }

    /** A new PIN's numbers (the number pad), on OK. */
    private void askDigits(String title, Typed then) {
        LivePinPad.ask(getContext(), title, Ui.tr("4 a 8 números", "4 to 8 digits"), false, pin -> {
            then.got(pin);
            return true;
        }, null);
    }

    // MARK: - Search

    /** The list on Search, with the keyboard up (the remote's Search key). */
    /** Search, the page (YouTube TV's, Apple TV's): the keyboard, voice, the channels and the shows found. */
    private void openSearch() {
        searchFor("");
    }

    /** Search, with words already typed (from the home screen's search). */
    void searchFor(String words) {
        if (lineup == null) return;
        overlay.hide();
        new LiveSearch(getContext(), this, live, words).show();
    }

    /** Who's watching? (the profiles). */
    void openProfiles() {
        overlay.hide();
        new LiveProfilesPage(getContext(), this, live).show();
    }

    /** Home: the home screen's Live TV tab (what was Live TV's own Home: on now, sports, starting soon). */
    void openHome() {
        overlay.hide();
        try {
            getContext().startActivity(new android.content.Intent(getContext(), com.glasslauncher.tv.MainActivity.class)
                    .putExtra("open_live", true).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            AppLog.w("live: couldn't open the home screen: " + e.getMessage());
        }
    }

    /** Recordings, the page (recording now, recorded, set for later). */
    void openRecordings() {
        if (lineup == null) return;
        overlay.hide();
        new LiveRecordingsPage(getContext(), this, live).show();
    }

    /** A recording picked on its page: it plays, full screen. */
    void playFromRecordings(LiveRecordings.Rec r) {
        live.playRecording(r);
        if (mode != WATCH) leave(WATCH);
        invalidate();
    }

    /** History's channels (the search page with nothing typed). */
    List<Xtream.Channel> historyChannels() {
        refreshHistory();
        return new ArrayList<>(history.channels);
    }

    /** Shows ending after `from` whose titles have every word, in the guide already loaded: {channel, program}, by start. */
    List<Object[]> loadedShows(String[] words, int max, long from) {
        List<Object[]> out = new ArrayList<>();
        if (lineup == null) return out;
        synchronized (epg) {
            for (Map.Entry<Integer, List<Xtream.Program>> e : epg.entrySet()) {
                Xtream.Channel channel = lineup.find(e.getKey());
                if (channel == null) continue;
                int found = 0;
                for (Xtream.Program p : e.getValue()) {
                    if (p.end <= from) continue;
                    String t = fold(p.title);
                    boolean match = true;
                    for (String w : words) if (!t.contains(w)) match = false;
                    if (match) {
                        out.add(new Object[] {channel, p});
                        if (++found >= 8) break;        // a few airings per channel
                    }
                }
                if (out.size() >= max) break;
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(((Xtream.Program) a[1]).start, ((Xtream.Program) b[1]).start));
        return out;
    }

    // MARK: - Subscription

    /** The subscription ends within a week: a card says so (once a day). */
    void subscriptionEnding(Xtream.Account a) {
        long now = System.currentTimeMillis(), left = a.expires - now;
        if (a.expires <= 0 || left > 7 * 24 * 3600_000L) return;
        android.content.SharedPreferences p = getContext().getSharedPreferences("live-ui", android.content.Context.MODE_PRIVATE);
        String today = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT).format(new java.util.Date(now));
        if (today.equals(p.getString("subscriptionWarned", ""))) return;
        p.edit().putString("subscriptionWarned", today).apply();
        long days = Math.max(0, left / (24 * 3600_000L));
        String when = new java.text.SimpleDateFormat("EEE d MMM", Ui.locale()).format(new java.util.Date(a.expires));
        LiveSheet.menu(getContext())
                .setTitle(left <= 0 ? Ui.tr("Sua assinatura de IPTV acabou", "Your IPTV subscription has ended")
                        : Ui.tr("Sua assinatura de IPTV acaba em ", "Your IPTV subscription ends in ") + (days <= 0 ? Ui.tr("menos de um dia", "under a day")
                        : days + (days == 1 ? Ui.tr(" dia", " day") : Ui.tr(" dias", " days"))))
                .setMessage(when + "  ·  " + Ui.tr("Renove com o seu provedor para continuar assistindo.", "Renew with your provider to keep watching."))
                .setIcons("check")
                .setItems(new String[] {"OK"}, (d, w) -> { })
                .show();
    }

    // MARK: - Voice: "watch …"

    /** The words that mean "put it on", in the languages Live TV speaks (then the channel's name or number). */
    private static final String[] WATCH_WORDS = {"watch", "play", "put on", "tune to", "switch to", "change to", "go to",
            "turn on", "assistir", "assista", "colocar", "coloca", "coloque", "mudar para", "muda para", "ver", "pon",
            "poner", "cambia a", "cambiar a", "sintoniza", "schau", "schaue", "zeige", "zeig", "schalte auf", "wechsle zu",
            "включи", "смотреть", "покажи", "переключи на"};
    /** What a channel's name carries besides its name: a country, its picture's quality (left out when comparing). */
    private static final Set<String> NAME_TAGS = new HashSet<>(java.util.Arrays.asList("uk", "us", "usa", "ca", "au", "ie",
            "fr", "de", "es", "it", "nl", "pt", "br", "4k", "uhd", "fhd", "hd", "sd", "hevc", "h265", "raw", "vip", "backup",
            "the", "channel", "tv"));

    /**
     * Said to search: "watch BBC One", "put on 302", "assistir Globo"… tunes the channel it names at once (the best
     * match: the whole name, then its start, then all its words; a favorite or one watched lately first). True when
     * it did; anything else stays a search.
     */
    boolean voiceWatch(String said) {
        if (lineup == null || said == null) return false;
        String q = fold(said), rest = null;
        for (String w : WATCH_WORDS) {
            String f = fold(w);
            if (q.startsWith(f + " ")) {
                rest = q.substring(f.length() + 1).trim();
                break;
            }
        }
        if (rest == null || rest.isEmpty()) return false;
        for (String lead : new String[] {"channel ", "the ", "canal ", "o canal ", "el canal ", "kanal ", "den ", "канал "}) {
            if (rest.startsWith(lead)) rest = rest.substring(lead.length());
        }
        Xtream.Channel best = bestChannel(rest);
        if (best == null) return false;
        AppLog.w("live: voice tunes " + best.name);
        watchFromSearch(best);
        return true;
    }

    /** The channel `words` names best (a number: that channel), or null when none matches all of them. */
    private Xtream.Channel bestChannel(String words) {
        String digitsOnly = words.replace(" ", "");
        if (digitsOnly.matches("\\d{1,5}")) {
            int n = Integer.parseInt(digitsOnly);
            for (Xtream.Channel ch : lineup.all()) if (ch.num == n) return ch;
            return null;
        }
        String want = core(words);
        if (want.isEmpty()) return null;
        String[] parts = want.split(" ");
        Set<Integer> yours = new HashSet<>();
        if (!lineup.categories.isEmpty()) for (Xtream.Channel ch : lineup.categories.get(0).channels) yours.add(ch.id);
        for (Xtream.Channel ch : history.channels) yours.add(ch.id);
        Xtream.Channel best = null;
        int bestScore = 0, bestLen = Integer.MAX_VALUE;
        for (Xtream.Channel ch : lineup.all()) {
            String name = core(ch.name);
            int score;
            if (name.equals(want)) score = 100;
            else if (name.startsWith(want + " ")) score = 80;
            else {
                boolean all = true;
                for (String p : parts) if (!(" " + name + " ").contains(" " + p + " ")) all = false;
                score = all ? 60 : 0;
            }
            if (score == 0) continue;
            if (yours.contains(ch.id)) score += 15;
            if (score > bestScore || score == bestScore && name.length() < bestLen) {
                best = ch;
                bestScore = score;
                bestLen = name.length();
            }
        }
        return best;
    }

    /** A name folded, without its country and quality tags ("UK: BBC One HD" → "bbc one"). */
    private static String core(String name) {
        StringBuilder b = new StringBuilder();
        for (String w : fold(name).split(" ")) {
            if (w.isEmpty() || NAME_TAGS.contains(w)) continue;
            if (b.length() > 0) b.append(' ');
            b.append(w);
        }
        return b.toString();
    }

    /** A channel picked in search: on, full screen. */
    void watchFromSearch(Xtream.Channel channel) {
        live.play(channel);
        if (mode != WATCH) leave(WATCH);
        invalidate();
    }

    /** A show picked in search: on now, its channel; later, remind or record it; over, its catch-up when there is one. */
    void showFromSearch(Xtream.Channel channel, Xtream.Program p) {
        long now = System.currentTimeMillis();
        if (p.start > now) {
            laterMenu(channel, p, () -> watchFromSearch(channel));
        } else if (p.end <= now && replayable(channel, p, now)) {
            live.playCatchup(channel, p);
            if (mode != WATCH) leave(WATCH);
        } else {
            watchFromSearch(channel);
        }
    }

    /**
     * The channels whose names have every word typed (ignoring case and accents), the ones starting with it first;
     * or the channel with that number.
     */
    private void runSearch() {
        search.id = SEARCH;
        String q = fold(query);
        search.name = q.isEmpty() ? Ui.tr("Buscar", "Search") : Ui.tr("Buscar", "Search") + ": " + query.trim();
        search.channels.clear();
        if (lineup == null || q.isEmpty()) return;
        String[] words = q.split(" ");
        List<Xtream.Channel> rest = new ArrayList<>();
        for (Xtream.Channel channel : lineup.all()) {
            String name = channel.key;
            boolean match = true;
            for (String word : words) if (!name.contains(word)) match = false;
            if (!match && !q.equals(String.valueOf(channel.num))) continue;
            if (name.startsWith(words[0])) search.channels.add(channel); else rest.add(channel);
            if (search.channels.size() + rest.size() >= 500) break;
        }
        search.channels.addAll(rest);
        searchShows(words);
    }

    // Shows found by search (the full guide): what each channel's row says after its name.
    private final Map<Integer, String> found = new HashMap<>();
    private int searchGen;
    /** The full guide is being searched for shows (the list's header says so meanwhile). */
    private boolean searchingShows;

    /**
     * Then the channels airing a show with that title (now or in the days ahead), after the ones found by name:
     * looked up in the full guide on a background thread, the newest search only.
     */
    private void searchShows(String[] words) {
        found.clear();
        final int gen = ++searchGen;
        final android.content.Context c = getContext();
        searchingShows = false;
        if (!LiveOptions.fullGuide(c) || query.trim().length() < 3) return;
        searchingShows = true;
        final long now = System.currentTimeMillis(), to = now + LiveOptions.daysAhead(c) * DAY;
        epgIo.execute(() -> {
            Map<String, XmltvStore.Found> shows = XmltvStore.find(c, words, now, to, 200);
            post(() -> {
                if (gen != searchGen || lineup == null) return;
                searchingShows = false;
                invalidate();
                Set<Integer> have = new HashSet<>();
                for (Xtream.Channel ch : search.channels) have.add(ch.id);
                for (Xtream.Channel ch : lineup.all()) {
                    if (ch.epg == null || ch.epg.isEmpty() || have.contains(ch.id)) continue;
                    XmltvStore.Found f = shows.get(ch.epg.toLowerCase(Locale.ROOT));
                    if (f == null) continue;
                    have.add(ch.id);
                    search.channels.add(ch);
                    found.put(ch.id, f.title + "  ·  " + (f.start <= now ? Ui.tr("agora", "now") : Ui.time(f.start)));
                }
                invalidate();
            });
        });
    }

    private static String fold(String s) {
        return Xtream.fold(s);
    }

    /** A magnifying glass about `s` across, centered at (cx, cy): Search's mark. */
    private void drawMagnifier(Canvas c, float cx, float cy, float s, int color) {
        line.setColor(color);
        line.setStrokeWidth(0.22f * s);
        c.drawCircle(cx - 0.12f * s, cy - 0.12f * s, 0.5f * s, line);
        c.drawLine(cx + 0.25f * s, cy + 0.25f * s, cx + 0.7f * s, cy + 0.7f * s, line);
    }

    private void signInKey(int code) {
        int last = fieldNames().length + 1;     // the type, the fields, then Sign in
        if (code == KeyEvent.KEYCODE_DPAD_UP) field = Math.max(0, field - 1);
        else if (code == KeyEvent.KEYCODE_DPAD_DOWN) field = Math.min(last, field + 1);
        else if (ok(code)) {
            if (field == last) live.signIn(signKind, server, user, pass);
            else if (field == 0) chooseKind();
            else ask(field - 1);
        }
        sField.target = field;
    }

    /** Types a field with the TV's keyboard. */
    private void ask(int which) {
        EditText input = new EditText(getContext());
        input.setSingleLine(true);
        boolean m3u = signKind.equals(Xtream.M3U), mag = signKind.equals(Xtream.MAG);
        if (which == 0) {
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            input.setHint(m3u ? "http://provider.tv/playlist.m3u" : mag ? "http://portal.tv:8080/c/" : "http://provider.tv:8080");
            input.setText(server);
        } else if (which == 1) {
            input.setInputType(InputType.TYPE_CLASS_TEXT | (m3u ? InputType.TYPE_TEXT_VARIATION_URI : InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
            if (m3u) input.setHint("http://provider.tv/guide.xml");
            if (mag) input.setHint("00:1A:79:12:34:56");
            input.setText(user);
            input.setSelection(input.getText().length());
        } else {
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        LiveTextEntry.show(getContext(), fieldNames()[which], null, input, text -> typed(which, text));
    }

    private void typed(int which, String text) {
        if (which == 0) server = text.trim();
        else if (which == 1) user = text.trim();
        else pass = text;
        field = Math.min(fieldNames().length + 1, which + 2);
        sField.target = field;
        status = "";
        invalidate();
    }

    /**
     * Keys while watching, TiviMate's: OK what's playing with its buttons; ← the channel list; → the last channel;
     * ↑ ↓ the next and previous channel's info (OK tunes it); the channel buttons change channel; Menu the menu; Guide
     * and red the guide, green the categories, yellow the buttons, blue the settings.
     */
    private void watchKey(int code) {
        if (lineup == null) return;
        if (overlay.armed() && overlay.key(code)) return;
        Xtream.Channel peek = overlay.peeking();
        // The channel shown with ↑ ↓ (Sky's mini guide): ◀ ▶ its later shows; OK tunes it, or on a later show, remind
        // or record it.
        if (peek != null && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            overlay.peekStep(code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
            return;
        }
        if (ok(code) && peek != null) {
            Xtream.Program p = overlay.peekProgram();
            if (p != null && p.start > System.currentTimeMillis()) {
                laterMenu(peek, p, () -> {
                    overlay.hide();
                    live.play(peek);
                });
                return;
            }
            overlay.hide();
            live.play(peek);
            return;
        }
        // A past program or live TV behind: the media keys skip and pause; live TV: play/pause pauses it.
        boolean pauseKey = code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_MEDIA_PAUSE
                || code == KeyEvent.KEYCODE_MEDIA_PLAY;
        if ((live.catchup() != null || live.shifted() || pauseKey) && catchupKey(code)) return;
        switch (code) {     // the buttons with one meaning
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                startOver(live.playing());
                return;
            case KeyEvent.KEYCODE_LAST_CHANNEL:
                if (live.previous() != null) tune(live.previous());
                return;
            case KeyEvent.KEYCODE_CHANNEL_UP: zap(1); return;
            case KeyEvent.KEYCODE_CHANNEL_DOWN: zap(-1); return;
            case KeyEvent.KEYCODE_INFO:
                if (live.playing() != null) showBanner(live.playing());
                return;
            case KeyEvent.KEYCODE_GUIDE: openGuide(playCat); return;
            case KeyEvent.KEYCODE_MEDIA_RECORD: toggleRecording(); return;
            case KeyEvent.KEYCODE_CAPTIONS:
                if (live.trackNames(androidx.media3.common.C.TRACK_TYPE_TEXT).isEmpty()) {
                    flash(Ui.tr("Sem legendas neste canal", "No subtitles on this channel"));
                } else {
                    overlay.chooseSubtitles();
                }
                return;
            default: break;
        }
        String slot = LiveKeys.slotOf(code, false);     // the rest: as set in Remote buttons
        if (slot != null) act(LiveKeys.action(getContext(), slot));
    }

    /** What a button does while watching (LiveKeys). */
    private void act(int action) {
        Xtream.Channel playing = live.playing();
        switch (action) {
            case LiveKeys.CONTROLS: if (playing != null) overlay.show(true); break;
            case LiveKeys.INFO: if (playing != null) showBanner(playing); break;
            case LiveKeys.CHANNEL_LIST: openList(); break;
            case LiveKeys.GROUPS:
                openList();
                column = 0;
                keepVisible();
                aimList(true);
                break;
            case LiveKeys.GUIDE: openGuide(playCat); break;
            case LiveKeys.LAST: if (live.previous() != null) tune(live.previous()); break;
            case LiveKeys.NEXT_INFO: peekNext(1); break;
            case LiveKeys.PREV_INFO: peekNext(-1); break;
            case LiveKeys.CH_UP: zap(1); break;
            case LiveKeys.CH_DOWN: zap(-1); break;
            case LiveKeys.MENU: showMenu(); break;
            case LiveKeys.HISTORY: openHistory(); break;
            case LiveKeys.SEARCH: openSearch(); break;
            case LiveKeys.RECORD: toggleRecording(); break;
            case LiveKeys.START_OVER: startOver(playing); break;
            case LiveKeys.FAVORITE: if (playing != null) toggleFavorite(playing); break;
            case LiveKeys.SETTINGS: openSettings(); break;
            case LiveKeys.REWIND: seek(-SKIP_BACK); break;
            case LiveKeys.MULTIVIEW: live.openMultiview(); break;
            case LiveKeys.SLEEP: sleepMenu(); break;
            case LiveKeys.EXIT: live.finish(); break;
            default: break;
        }
    }

    /** The show on now on `channel`, from its start (catch-up); false when there's none to start over. */
    private boolean startOver(Xtream.Channel channel) {
        if (channel == null || channel.archive <= 0) return false;
        long now = System.currentTimeMillis();
        List<Xtream.Program> list = programs(channel);
        if (list == null) {
            flash(Ui.tr("Carregando o guia…", "Loading the guide…"));
            return false;
        }
        int at = programAt(list, now);
        Xtream.Program p = at >= 0 ? list.get(at) : null;
        if (p == null || p.start > now || p.start < catchupFrom(channel, now)) {
            flash(Ui.tr("Este programa não está na reprise", "This program isn't in the catch-up"));
            return false;
        }
        live.playCatchup(channel, p);
        return true;
    }

    /** A past program, the overlay hidden: rewind and fast-forward skip, play/pause pauses; the rest as on live TV. */
    private boolean catchupKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (!live.skip(-SKIP_BACK)) flash(Ui.tr("Este programa não deixa voltar nem avançar", "This program can't skip back or ahead"));
                break;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (!live.skip(SKIP_AHEAD)) flash(Ui.tr("Este programa não deixa voltar nem avançar", "This program can't skip back or ahead"));
                break;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                live.togglePause();
                break;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (live.paused()) live.togglePause();
                break;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (!live.paused()) live.togglePause();
                break;
            default:
                return false;
        }
        showBanner(live.playing());
        return true;
    }

    /** The next (or previous) channel in the category it was picked from, going round at the ends. */
    private void zap(int dir) {
        Xtream.Channel playing = live.playing();
        if (playing == null || playCat >= lineup.categories.size()) return;
        List<Xtream.Channel> list = lineup.categories.get(playCat).channels;
        if (list.isEmpty()) return;
        int at = 0;
        for (int i = 0; i < list.size(); i++) if (list.get(i).id == playing.id) at = i;
        live.play(list.get((at + dir + list.size()) % list.size()));
    }

    private void listKey(int code) {
        List<Xtream.Channel> list = channels();
        if (column == 2) {
            scheduleKey(code, list);
            return;
        }
        boolean jump = false;     // went round the categories: no gliding through all of them
        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_CHANNEL_UP) {
            if (column == 0) jump = moveCategory(-1); else ch = Math.max(0, ch - (code == KeyEvent.KEYCODE_CHANNEL_UP ? chRows() : 1));
        } else if (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_CHANNEL_DOWN) {
            if (column == 0) jump = moveCategory(1);
            else ch = Math.min(Math.max(0, list.size() - 1), ch + (code == KeyEvent.KEYCODE_CHANNEL_DOWN ? chRows() : 1));
        } else if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
            column = 0;
        } else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (column == 0) {
                if (cat >= 0 && !list.isEmpty()) column = 1;
            } else if (ch < list.size() && list != recordings.channels && list.get(ch).id > 0) {
                // Into the channel's schedule, on the show on now (TiviMate's).
                List<Xtream.Program> progs = programs(list.get(ch));
                if (progs != null && !progs.isEmpty()) {
                    int at = programAt(progs, System.currentTimeMillis());
                    sIdx = at >= 0 ? at : 0;
                    column = 2;
                }
            }
        } else if (ok(code)) {
            if (column == 0 && cat == SETTINGS_ROW) {
                openSettings();
                return;
            } else if (column == 0 && lineup.categories.get(cat) == search) {
                openSearch();
            } else if (column == 0 && lineup.categories.get(cat) == recordings) {
                if (!list.isEmpty()) column = 1;     // no guide for recordings: their list
            } else if (column == 1 && lineup.categories.get(cat) == recordings) {
                LiveRecordings.Rec r = ch < list.size() ? recById.get(list.get(ch).id) : null;
                if (r != null) {
                    live.playRecording(r);
                    leave(WATCH);
                }
            } else if (column == 0) {
                if (!list.isEmpty()) column = 1;     // a category: its channels
            } else if (ch < list.size()) {
                playCat = cat;
                live.play(list.get(ch));
                leave(WATCH);
            }
        }
        keepVisible();
        aimList(jump);
        announce();
    }

    /**
     * The focus in the channel's schedule: ↑ ↓ through its programs, ← back to the channels; OK on the show on now
     * watches it, on one that's over plays it from the catch-up, on one later offers a reminder or a recording.
     */
    private void scheduleKey(int code, List<Xtream.Channel> list) {
        Xtream.Channel channel = ch < list.size() ? list.get(ch) : null;
        List<Xtream.Program> progs = channel == null ? null : programs(channel);
        if (progs == null || progs.isEmpty()) {
            column = 1;
            aimList(false);
            return;
        }
        int last = progs.size() - 1;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: sIdx = Math.max(0, sIdx - 1); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: sIdx = Math.min(last, sIdx + 1); break;
            case KeyEvent.KEYCODE_CHANNEL_UP: sIdx = Math.max(0, sIdx - 6); break;
            case KeyEvent.KEYCODE_CHANNEL_DOWN: sIdx = Math.min(last, sIdx + 6); break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                column = 1;
                aimList(false);
                break;
            default:
                if (!ok(code)) break;
                Xtream.Program p = progs.get(Math.max(0, Math.min(last, sIdx)));
                long now = System.currentTimeMillis();
                if (p.start <= now && p.end > now) {
                    playCat = cat;
                    live.play(channel);
                    leave(WATCH);
                } else if (p.end <= now) {
                    if (!replayable(channel, p, now)) {
                        flash(Ui.tr("Este programa não está na reprise", "This program isn't in the catch-up"));
                    } else {
                        playCat = cat;
                        live.playCatchup(channel, p);
                        leave(WATCH);
                    }
                } else {
                    laterMenu(channel, p, () -> {
                        playCat = cat;
                        live.play(channel);
                        leave(WATCH);
                    });
                }
                break;
        }
        Xtream.Program p = progs.get(Math.max(0, Math.min(last, sIdx)));
        announceForAccessibility(Ui.time(p.start) + ", " + p.title);
    }

    /** A show later on: be reminded, or record it (each a toggle), or watch the channel now (TiviMate's). */
    private void laterMenu(Xtream.Channel channel, Xtream.Program p, Runnable watch) {
        android.content.Context c = getContext();
        boolean remind = LiveSchedule.has(c, LiveSchedule.REMIND, channel.id, p.start);
        boolean record = LiveSchedule.has(c, LiveSchedule.RECORD, channel.id, p.start);
        String[] items = {remind ? Ui.tr("Cancelar o lembrete", "Cancel the reminder") : Ui.tr("Lembrar", "Remind me"),
                record ? Ui.tr("Cancelar a gravação", "Cancel the recording") : Ui.tr("Gravar", "Record"),
                Ui.tr("Todos os episódios…", "Every episode…"),
                Ui.tr("Seguir…", "Follow…"),
                Ui.tr("Assistir agora", "Watch now"),
                Ui.tr("Sobre o programa", "About the show")};
        LiveSheet.menu(c)
                .setTitle(p.title.isEmpty() ? channel.name : p.title)
                .setMessage(Ui.time(p.start) + " – " + Ui.time(p.end) + "  ·  " + channel.name)
                .setIcons(remind ? "close" : "bell", record ? "stop" : "record", "series", "add", "play", "info")
                .setItems(items, (d, which) -> {
                    if (which == 5) {       // its page, with these same choices as buttons
                        List<String> l = new ArrayList<>(), i = new ArrayList<>();
                        List<Runnable> a = new ArrayList<>();
                        l.add(items[0]);
                        i.add(remind ? "close" : "bell");
                        a.add(() -> {
                            if (remind) LiveSchedule.cancel(c, LiveSchedule.REMIND + ":" + channel.id + ":" + p.start);
                            else LiveSchedule.add(c, item(LiveSchedule.REMIND, channel, p));
                            flash(remind ? Ui.tr("Lembrete cancelado", "Reminder cancelled") : Ui.tr("Lembrete às ", "Reminder at ") + Ui.time(p.start));
                            marksAt = 0;
                            invalidate();
                        });
                        l.add(items[1]);
                        i.add(record ? "stop" : "record");
                        a.add(() -> {
                            if (record) LiveSchedule.cancel(c, LiveSchedule.RECORD + ":" + channel.id + ":" + p.start);
                            else LiveSchedule.add(c, item(LiveSchedule.RECORD, channel, p));
                            flash(record ? Ui.tr("Gravação cancelada", "Recording cancelled") : Ui.tr("Grava às ", "Records at ") + Ui.time(p.start));
                            marksAt = 0;
                            invalidate();
                        });
                        l.add(items[2]);
                        i.add("series");
                        a.add(() -> seriesMenu(channel, p));
                        l.add(items[3]);
                        i.add("add");
                        a.add(() -> followMenu(p));
                        l.add(items[4]);
                        i.add("play");
                        a.add(watch);
                        openShowPage(channel, p, l, i, a);
                        return;
                    }
                    if (which == 4) {
                        watch.run();
                        return;
                    }
                    if (which == 3) {
                        followMenu(p);
                        return;
                    }
                    if (which == 2) {
                        seriesMenu(channel, p);
                        return;
                    }
                    if (which == 0) {
                        if (remind) LiveSchedule.cancel(c, LiveSchedule.REMIND + ":" + channel.id + ":" + p.start);
                        else LiveSchedule.add(c, item(LiveSchedule.REMIND, channel, p));
                        flash(remind ? Ui.tr("Lembrete cancelado", "Reminder cancelled") : Ui.tr("Lembrete às ", "Reminder at ") + Ui.time(p.start));
                    } else {
                        if (record) LiveSchedule.cancel(c, LiveSchedule.RECORD + ":" + channel.id + ":" + p.start);
                        else LiveSchedule.add(c, item(LiveSchedule.RECORD, channel, p));
                        flash(record ? Ui.tr("Gravação cancelada", "Recording cancelled") : Ui.tr("Grava às ", "Records at ") + Ui.time(p.start));
                    }
                    marksAt = 0;
                    invalidate();
                })
                .show();
    }

    /** What to follow from a show: a match's teams and competition, or the show; one followed already: stop. */
    private void followMenu(Xtream.Program p) {
        final android.content.Context c = getContext();
        final List<String> terms = LiveFollow.choices(p.title);
        if (terms.isEmpty()) return;
        String[] labels = new String[terms.size()];
        String[] icons = new String[terms.size()];
        for (int i = 0; i < labels.length; i++) {
            boolean on = LiveFollow.follows(c, terms.get(i));
            labels[i] = terms.get(i);       // the title says Follow; one followed already has a check (OK: stop)
            icons[i] = on ? "check" : "add";
        }
        LiveSheet.menu(c)
                .setTitle(Ui.tr("Seguir", "Follow"))
                .setMessage(Ui.tr("Um alerta cada vez que passa, em qualquer canal (Alertas de jogos, nos Ajustes).",
                        "An alert each time it's on, on any channel (Match alerts, in Settings)."))
                .setIcons(icons)
                .setItems(labels, (d, which) -> {
                    String term = terms.get(which);
                    if (LiveFollow.follows(c, term)) {
                        LiveFollow.remove(c, term);
                        flash(Ui.tr("Deixou de seguir ", "Not following ") + term);
                    } else {
                        LiveFollow.add(c, term);
                        flash(Ui.tr("Seguindo ", "Following ") + term);
                    }
                    marksAt = 0;
                    invalidate();
                })
                .show();
    }

    /** What's playing: the past program (catch-up, a recording), else the show on now; null without a guide. */
    Xtream.Program playingShow() {
        Xtream.Channel channel = live.playing();
        if (channel == null) return null;
        Xtream.Program past = live.catchup();
        if (past != null) return past;
        List<Xtream.Program> list = programs(channel);
        int at = list == null ? -1 : programAt(list, System.currentTimeMillis());
        return at >= 0 ? list.get(at) : null;
    }

    /** The player's About the show: what's playing on its own page, with what can be done with it. */
    void aboutPlaying() {
        final Xtream.Channel channel = live.playing();
        final Xtream.Program p = playingShow();
        if (channel == null) return;
        if (p == null) {
            flash(Ui.tr("O guia não tem este programa", "The guide doesn't have this show"));
            return;
        }
        long now = System.currentTimeMillis();
        List<String> labels = new ArrayList<>(), icons = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (live.catchup() == null && channel.archive > 0 && p.start >= catchupFrom(channel, now)) {
            labels.add(Ui.tr("Começar do início", "Start over"));
            icons.add("start_over");
            actions.add(() -> startOver(channel));
        }
        labels.add(Ui.tr("Todos os episódios…", "Every episode…"));
        icons.add("series");
        actions.add(() -> seriesMenu(channel, p));
        if (!LiveFollow.choices(p.title).isEmpty()) {
            labels.add(Ui.tr("Seguir…", "Follow…"));
            icons.add("add");
            actions.add(() -> followMenu(p));
        }
        openShowPage(channel, p, labels, icons, actions);
    }

    /** A show's own page; another airing on it opens that one's choices (on now: watched). */
    private void openShowPage(Xtream.Channel channel, Xtream.Program p, List<String> labels, List<String> icons, List<Runnable> actions) {
        new LiveShowPage(getContext(), lineup, channel, p, labels, icons, actions, (ch, show) -> {
            long now = System.currentTimeMillis();
            Runnable watch = () -> {
                if (mode == GUIDE) closeGuide();
                live.play(ch);
            };
            if (show.start <= now) watch.run();
            else laterMenu(ch, show, watch);
        }).show();
    }

    /**
     * Every airing of this show on this channel: reminded or recorded, all of them or only the new episodes (TiviMate's
     * series); or that series stopped.
     */
    private void seriesMenu(Xtream.Channel channel, Xtream.Program p) {
        android.content.Context c = getContext();
        String title = p.title.isEmpty() ? channel.name : p.title;
        LiveSeries.Rule remind = LiveSeries.find(c, LiveSchedule.REMIND, channel.id, title);
        LiveSeries.Rule record = LiveSeries.find(c, LiveSchedule.RECORD, channel.id, title);
        List<String> labels = new ArrayList<>(), icons = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (remind != null) {
            labels.add(Ui.tr("Parar os lembretes da série", "Stop the series' reminders"));
            icons.add("stop");
            actions.add(() -> {
                LiveSeries.remove(c, remind);
                flash(Ui.tr("Lembretes da série parados", "Series reminders stopped"));
            });
        } else {
            labels.add(Ui.tr("Lembrar todos os episódios", "Remind me of every episode"));
            icons.add("bell");
            actions.add(() -> setSeries(LiveSchedule.REMIND, channel, title, false));
            labels.add(Ui.tr("Lembrar só os episódios novos", "Remind me of new episodes only"));
            icons.add("bell");
            actions.add(() -> setSeries(LiveSchedule.REMIND, channel, title, true));
        }
        if (record != null) {
            labels.add(Ui.tr("Parar de gravar a série", "Stop recording the series"));
            icons.add("stop");
            actions.add(() -> {
                LiveSeries.remove(c, record);
                flash(Ui.tr("Gravação da série parada", "Series recording stopped"));
            });
        } else {
            labels.add(Ui.tr("Gravar todos os episódios", "Record every episode"));
            icons.add("record");
            actions.add(() -> setSeries(LiveSchedule.RECORD, channel, title, false));
            labels.add(Ui.tr("Gravar só os episódios novos", "Record new episodes only"));
            icons.add("record");
            actions.add(() -> setSeries(LiveSchedule.RECORD, channel, title, true));
        }
        LiveSheet.menu(c)
                .setTitle(title)
                .setIcons(icons.toArray(new String[0]))
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    actions.get(which).run();
                    marksAt = 0;
                    invalidate();
                })
                .show();
    }

    private void setSeries(String kind, Xtream.Channel channel, String title, boolean newOnly) {
        LiveSeries.Rule r = new LiveSeries.Rule();
        r.kind = kind;
        r.ch = channel;
        r.title = title;
        r.newOnly = newOnly;
        LiveSeries.add(getContext(), r);
        // What the guide already has for this channel, now (the rest as it loads).
        List<Xtream.Program> list;
        synchronized (epg) {
            list = epg.get(channel.id);
        }
        if (list != null) {
            final List<Xtream.Program> had = new ArrayList<>(list);
            epgIo.execute(() -> {
                LiveSeries.match(getContext(), channel, had);
                LiveFollow.match(getContext(), channel, had);
                marksAt = 0;
                postInvalidate();
            });
        }
        flash(kind.equals(LiveSchedule.REMIND)
                ? (newOnly ? Ui.tr("Lembrete dos episódios novos", "Reminding you of new episodes") : Ui.tr("Lembrete de todos os episódios", "Reminding you of every episode"))
                : (newOnly ? Ui.tr("Gravando os episódios novos", "Recording new episodes") : Ui.tr("Gravando todos os episódios", "Recording every episode")));
    }

    /** One row up or down through the categories, round at the ends; true when it went round. */
    private boolean moveCategory(int dir) {
        int n = lineup.categories.size(), row = cat;
        if (n == 0) return false;
        int next = (row + dir + n) % n;
        if (next == row) return false;
        boolean round = Math.abs(next - row) > 1;
        cat = next;
        ch = 0;
        chTop = 0;
        lCh.snap(0);       // another category's channels: they start at the top
        lChTop.snap(0);
        return round;
    }

    /** Live TV's settings, over the video; closing them goes back to where they were opened from. */
    private void openSettings() {
        if (mode == SETTINGS) return;
        settingsFrom = mode;
        live.setPreview(false);
        enter(SETTINGS);
        settings.open();
        invalidate();
    }

    /** From the settings back where they were opened: watching, the guide, or the categories (on the playing channel's). */
    void closeSettings() {
        if (mode != SETTINGS) return;
        settings.closed();      // where it was, for next time
        if (settingsFrom == WATCH && live.playing() != null) {
            leave(WATCH);
            return;
        }
        if (settingsFrom == GUIDE && lineup != null && !lineup.categories.isEmpty()) {
            enter(GUIDE);       // where it was (the settings may have hidden channels: kept in range)
            gCat = Math.max(0, Math.min(lineup.categories.size() - 1, gCat));
            int rows = guideChannels().size();
            gRow = Math.max(0, Math.min(rows - 1, gRow));
            gTop = scroll(gRow, gTop, G_ROWS, rows);
            aimGuide(true);
            live.setPreview(true);
            announceGuide();
            invalidate();
            return;
        }
        enter(LIST);
        column = 0;
        cat = lineup == null ? 0 : Math.max(0, Math.min(playCat, lineup.categories.size() - 1));
        ch = 0;
        chTop = 0;
        keepVisible();
        aimList(true);
        announce();
        invalidate();
    }

    void confirmSignOut() {
        boolean others = LivePlaylists.all(getContext()).size() > 1;
        LiveSheet.menu(getContext())
                .setTitle(Ui.tr("Sair da conta", "Sign out"))
                .setMessage(others
                        ? Ui.tr("Sair desta conta? Os favoritos, o Histórico e os lembretes dela somem; a TV ao vivo passa para a outra lista.",
                                "Sign out of this account? Its favorites, History and reminders go; Live TV moves to your other playlist.")
                        : Ui.tr("Sair do seu provedor de IPTV nesta TV? Os canais somem até você entrar de novo.",
                        "Sign out of your IPTV provider on this TV? The channels go away until you sign in again."))
                .setPositiveButton(Ui.tr("Sair", "Sign out"), (d, b) -> live.signOut())
                .setNegativeButton(Ui.tr("Cancelar", "Cancel"), null)
                .setIcons("exit", "close")
                .show();
    }

    // MARK: - The guide (OK while watching)
    //
    // The category's channels as rows, their programs along the time line (two hours across, in half-hour steps),
    // the "now" line in red. Above: the focused program (title, time left, synopsis) and, at the top right, the
    // playing channel in a small window. Each channel's programs come from the provider's short guide when its row
    // first shows, kept for half an hour, the next rows fetched ahead.
    // The focus is one white tile on springs: its row (fRow), its start and end on the time line (fA, fB, minutes
    // from gBase) and how far it is in the programs (fCol: 0 = on the logos). The rows and the time line scroll on
    // springs too (gRowsTop, gTimeOff); the details above fade up when the focused program changes (info).

    // TiviMate's guide: the channels' column (logo, name, marks), the programs' grid beside it.
    /** The channels' column (the logo over its name, the marks at its end), then the programs' grid. */
    private static final float G_LOGO_X = 32, G_COL_R = 296, G_MARKS_W = 36, G_GRID_X = 308, G_GRID_R = 1900;
    private static float G_TIMES_BASE = 448, G_TOP = 476, G_ROW_H = 80, G_ROW_STEP = 88, G_MINUTES = 120;
    /** The playing channel's window, at the top left (Live puts the video there while the guide is open). */
    static float P_X = 32, P_Y = 24, P_W = 640, P_H = 360;
    /** The focused program's details, right of the window. */
    private static float G_INFO_X = 716, G_INFO_R = 1872;
    private static final long HALF_HOUR = 30 * 60_000L, HOUR = 3600_000L;
    /** When this Live TV started: guides saved before it are fetched again ("update the guide on start"). */
    private final long sessionAt = System.currentTimeMillis();
    private static int G_ROWS = 6;
    private static final int G_AHEAD = 8;
    /** The synopsis' lines in the guide's details; the show's picture behind them (only with the video at the left). */
    private static int G_ABOUT_LINES = 3;
    private static boolean G_ART = true;

    /**
     * The guide's layout for the style on screen: where the video is and how big, the details beside it, how tall
     * the rows are, how many show and how many hours across (Sky Q's video at the right and slim rows, YouTube TV's
     * compact top and tall rows, NOW's big picture, Sling's small one, Hulu's large one at the right).
     */
    /** Compact guide rows (Settings > Guide > Guide size): set before applyLayout. */
    static boolean compact;

    static void applyLayout() {
        float px = 32, pw = 640, ph = 360, infoX = 716, infoR = 1872, chipY = 350, times = 448, top = 476, rowH = 80, step = 88;
        float minutes = 120, artX = 1180, artH = 410;
        int rows = 6, lines = 3;
        boolean art = true;
        switch (LiveTheme.layoutStyle()) {
            case LiveTheme.SKY:
                px = 1248; infoX = 32; infoR = 1216; rowH = 66; step = 72; rows = 8; minutes = 90; art = false;
                break;
            case LiveTheme.YOUTUBE:
                pw = 480; ph = 270; infoX = 544; lines = 1; chipY = 286; times = 384; top = 412; rowH = 88; step = 96;
                rows = 7; minutes = 150; artH = 330;
                break;
            case LiveTheme.NOW:
                pw = 800; ph = 450; infoX = 864; lines = 4; chipY = 386; times = 528; top = 556; artX = 1300; artH = 470;
                break;
            case LiveTheme.SLING:
                px = 1440; pw = 448; ph = 252; infoX = 32; infoR = 1408; lines = 1; chipY = 286; times = 384; top = 412;
                rowH = 68; step = 74; rows = 9; art = false;
                break;
            case LiveTheme.HULU:
                px = 1152; pw = 736; ph = 414; infoX = 32; infoR = 1120; lines = 4; chipY = 386; times = 492; top = 520;
                rowH = 84; step = 92; art = false;
                break;
            default:
                break;
        }
        // The filters are gone: the time line sits under the video or the details, whichever ends lower.
        times = Math.max(chipY, P_Y + ph) + 50;
        top = times + 28;
        if (compact) rowH = Math.round(rowH * 0.76f);
        step = rowH + 4;
        rows = Math.max(4, (int) ((1080 - 12 - top) / step));
        P_X = px;
        P_W = pw;
        P_H = ph;
        G_INFO_X = infoX;
        G_INFO_R = infoR;
        CHIP_Y = chipY;
        G_TIMES_BASE = times;
        G_TOP = top;
        G_ROW_H = rowH;
        G_ROW_STEP = step;
        G_ROWS = rows;
        G_MINUTES = minutes;
        G_ABOUT_LINES = lines;
        G_ART = art;
        switch (LiveTheme.layoutStyle()) {
            case LiveTheme.YOUTUBE: CH_W = 680; C_STEP = 144; break;
            case LiveTheme.HULU: CH_W = 640; C_STEP = 134; break;
            case LiveTheme.SKY:
            case LiveTheme.SLING: CH_W = 560; C_STEP = 104; break;
            default: CH_W = 600; C_STEP = 126; break;
        }
        ART_X = artX;
        ART_H = artH;
    }

    private Paint mini;
    private final RectF miniRect = new RectF();

    /**
     * The guide in miniature, as the style on screen draws it (Settings > Style): its background, where the video and
     * the details are, the filters, the rows and the focus, at `width` from (left, top). Bars stand for the text.
     */
    void drawStylePreview(Canvas c, float left, float top, float width) {
        if (mini == null) mini = new Paint(Paint.ANTI_ALIAS_FLAG);
        float s = width / 1920f, rr = 10 * LiveTheme.round;
        c.save();
        c.translate(left, top);
        c.scale(s, s);
        miniRect.set(0, 0, 1920, 1080);
        c.clipRect(miniRect);
        LiveTheme.background(c, 1920, 1080, 1);
        // The video and its progress.
        mini.setColor(0xFF000000);
        miniRect.set(P_X, P_Y, P_X + P_W, P_Y + P_H);
        c.drawRoundRect(miniRect, 14, 14, mini);
        mini.setColor(LiveTheme.text);
        c.drawRect(P_X, P_Y + P_H + 4, P_X + P_W * 0.4f, P_Y + P_H + 12, mini);
        // The details: title, time, synopsis.
        float ix = G_INFO_X, iw = Math.min(G_INFO_R - G_INFO_X, 1100);
        mini.setColor(0xF2FFFFFF);
        c.drawRoundRect(ix, 66, ix + iw * 0.5f, 110, 12, 12, mini);
        mini.setColor(0x80FFFFFF);
        c.drawRoundRect(ix, 140, ix + iw * 0.35f, 164, 10, 10, mini);
        for (int i = 0; i < G_ABOUT_LINES; i++) c.drawRoundRect(ix, 196 + i * 40, ix + iw * (i == G_ABOUT_LINES - 1 ? 0.6f : 0.95f), 216 + i * 40, 8, 8, mini);
        // The rows: a logo, the name, the programs; the focus on the fourth row's first one.
        float cell = (G_GRID_R - G_GRID_X) / (G_MINUTES / 30f);
        mini.setColor(LiveTheme.text);
        c.drawRect(G_GRID_X + cell * 0.4f, G_TIMES_BASE + 10, G_GRID_X + cell * 0.4f + 4, 1080, mini);
        for (int r = 0; r < G_ROWS; r++) {
            float y = G_TOP + r * G_ROW_STEP;
            if (y > 1080) break;
            float colMid = (G_LOGO_X + G_COL_R - G_MARKS_W) / 2;
            mini.setColor(LiveTheme.surface);
            c.drawRoundRect(colMid - 60, y + 8, colMid + 60, y + G_ROW_H - 30, 10, 10, mini);
            mini.setColor(r == 3 ? LiveTheme.text : 0x99FFFFFF);
            c.drawRoundRect(colMid - 70, y + G_ROW_H - 20, colMid + 70, y + G_ROW_H - 10, 5, 5, mini);
            float x = G_GRID_X;
            int i = 0;
            while (x < G_GRID_R - 20) {
                float cw = cell * (((r + i) % 3 == 0) ? 2 : 1) - 4;
                float right = Math.min(G_GRID_R, x + cw);
                boolean focus = r == 3 && i == 0;
                mini.setColor(focus ? LiveTheme.tile : i == 0 ? LiveTheme.cellNow : LiveTheme.cellLater);
                miniRect.set(x, y, right, y + G_ROW_H);
                c.drawRoundRect(miniRect, rr, rr, mini);
                mini.setColor(focus ? LiveTheme.ink : 0xB3FFFFFF);
                c.drawRoundRect(x + 20, y + G_ROW_H / 2 - 9, x + Math.min(cw - 40, 220), y + G_ROW_H / 2 + 9, 8, 8, mini);
                x += cw + 4;
                i++;
            }
        }
        c.restore();
        // Its frame.
        mini.setStyle(Paint.Style.STROKE);
        mini.setStrokeWidth(2 * k);
        mini.setColor(0x33FFFFFF);
        miniRect.set(left, top, left + width, top + width * 9 / 16);
        c.drawRoundRect(miniRect, 12 * k, 12 * k, mini);
        mini.setStyle(Paint.Style.FILL);
    }

    /** The style changed: its layout, lettering and colors, everywhere at once (the video moves with the guide's). */
    void restyle() {
        applyLayout();
        cardFadeShader = null;
        if (w > 0) onSizeChanged(w, h, w, h);
        gChip_text = null;
        gAbout = null;
        themeChanged();
        live.layoutChanged();
        if (mode == GUIDE) {
            List<Xtream.Channel> rows = guideChannels();
            gTop = scroll(gRow, 0, G_ROWS, rows.size());
            gRowsTop.snap(gTop);
        }
    }

    private int gCat, gRow, gTop, gCol = 1;   // gCol: 0 the logos, 1 the programs
    /**
     * The guide's filters (Sling's, YouTube TV's): All, then Favorites, Sports, Movies, News, Kids across every
     * channel; gChips while the focus is on them (↑ from the top row), gFiltered the channels the filter keeps.
     */
    private static final int F_ALL = 0, F_FAVORITES = 1, F_SPORTS = 2, F_MOVIES = 3, F_NEWS = 4, F_KIDS = 5, FILTERS = 6;
    private int gFilter, gChip;
    private boolean gChips;
    private List<Xtream.Channel> gFiltered;
    private static float CHIP_Y = 350;
    private static final float CHIP_H = 48;
    private long gTime, gStart, gBase;         // the focused moment; the grid's left edge; where the minutes count from
    private final Spring fRow = new Spring(0.22f, 0.86f, 0), fA = new Spring(0.22f, 0.86f, 0), fB = new Spring(0.22f, 0.86f, 0);
    private final Spring fCol = new Spring(0.24f, 1f, 1), gRowsTop = new Spring(0.3f, 1f, 0), gTimeOff = new Spring(0.34f, 1f, 0);
    private final Spring info = new Spring(0.26f, 1f, 1);
    private long infoKey = Long.MIN_VALUE;
    private final Map<Integer, List<Xtream.Program>> epg = new HashMap<>();
    private final Map<Integer, Long> epgAt = new HashMap<>();
    private final Set<Integer> epgPending = new HashSet<>();
    /** When each channel's programs were last asked for (drawn): the ones scrolled past aren't fetched. */
    private final Map<Integer, Long> epgWanted = new HashMap<>();
    // The newest request first (the rows on screen now, not the ones scrolled past on the way).
    private final ExecutorService epgIo = new java.util.concurrent.ThreadPoolExecutor(3, 3, 30, java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.LinkedBlockingDeque<Runnable>() {
                @Override
                public boolean offer(Runnable r) {
                    return offerFirst(r);
                }
            });
    private StaticLayout gAbout;
    private String gAboutText = "";
    private final RectF preview = new RectF(), cell = new RectF();
    private Paint gTitle, gClock, gMeta, gCell, gCellOn, gTimeText, gLeft, gName, gNameOn, gBadge;
    private final RectF badgeRect = new RectF();
    private int pendingBadge;       // 1 NEW, 2 LIVE: drawn on the next tile (over it)
    private String pendingBadgeText = "", badgeNew = "NEW", badgeLive = "LIVE", badgeRepeat = "REPEAT";
    private TextPaint gAboutPaint;

    private void makeGuidePaints() {
        gTitle = Ui.text(Ui.displaySemibold, 46 * k, WHITE);
        gClock = Ui.numbers(Ui.medium, 24 * k, LiveTheme.text);
        gMeta = Ui.text(Ui.regular, 27 * k, 0xB3FFFFFF);
        gAboutPaint = new TextPaint(Ui.text(Ui.regular, 27 * k, 0x99FFFFFF));
        float cs = compact ? 0.88f : 1;     // compact rows: smaller lettering in the grid
        gCell = Ui.text(Ui.regular, 26 * cs * k, 0xCCFFFFFF);
        gCellOn = Ui.text(Ui.medium, 26 * cs * k, LiveTheme.ink);      // over the focus
        gTimeText = Ui.numbers(Ui.regular, 27 * k, 0xB3FFFFFF);
        gLeft = Ui.text(Ui.regular, 27 * k, 0xB3FFFFFF);
        gName = Ui.text(Ui.medium, 19 * cs * k, 0xB3FFFFFF);
        gSub = Ui.text(Ui.regular, 21 * cs * k, 0x99FFFFFF);
        gSubOn = Ui.text(Ui.regular, 21 * cs * k, Ui.alpha(LiveTheme.ink, 0.75f));
        int top = LiveTheme.bgTop;      // the picture fades into the page's own background
        artFadeLeft = new LinearGradient(ART_X * k, 0, (ART_X + 420) * k, 0, top, top & 0x00FFFFFF, Shader.TileMode.CLAMP);
        artFadeDown = new LinearGradient(0, (ART_H - 150) * k, 0, ART_H * k, top & 0x00FFFFFF, top, Shader.TileMode.CLAMP);
        artFadeTop = new LinearGradient(0, 0, 0, 110 * k, (top & 0x00FFFFFF) | 0xCC000000, top & 0x00FFFFFF, Shader.TileMode.CLAMP);
        gNameOn = Ui.text(Ui.medium, 19 * cs * k, LiveTheme.text);
        gBadge = Ui.text(Ui.semibold, 16 * cs * k, WHITE);
        badgeRepeat = Ui.tr("REPRISE", "REPEAT");
        badgeNew = Ui.tr("NOVO", "NEW");
        badgeLive = Ui.tr("AO VIVO", "LIVE");
        gAboutText = "";
        gAbout = null;
    }

    /** Opens the guide on category `category`, the playing channel's row focused at the current time. */
    void openGuide(int category) {
        if (lineup == null || lineup.categories.isEmpty()) return;
        enter(GUIDE);
        gGroups = gRail = false;
        gPanel.snap(0);
        live.shiftPreview(0);
        gFilter = F_ALL;        // a category chosen: no filter
        gFiltered = null;
        gChips = false;
        gHead = 0;
        gCat = Math.max(0, Math.min(lineup.categories.size() - 1, category));
        List<Xtream.Channel> rows = guideChannels();
        Xtream.Channel playing = live.playing();
        gRow = 0;
        for (int i = 0; playing != null && i < rows.size(); i++) if (rows.get(i).id == playing.id) gRow = i;
        gCol = 1;
        long now = System.currentTimeMillis();
        gTime = now;
        gBase = gStart = now - now % HALF_HOUR;
        gTop = scroll(gRow, 0, G_ROWS, rows.size());
        aimGuide(true);
        live.setPreview(true);
        announceGuide();
        invalidate();
    }

    /** The guide on the category the playing channel came from (Live: the first time, to pick a channel). */
    void openGuide() {
        openGuide(playCat);
    }

    private void closeGuide() {
        if (gGroups) closeGuideGroups();
        live.setPreview(false);     // the video grows back as the guide fades out
        leave(WATCH);
    }

    /** From the guide: the categories (and their channels) on the left. */
    private void openCategories() {
        live.setPreview(false);
        enter(LIST);
        listFromGuide = true;
        column = 0;
        cat = gCat;
        ch = 0;
        chTop = 0;
        keepVisible();
        aimList(true);
        announce();
    }

    private List<Xtream.Channel> guideChannels() {
        if (lineup == null || gCat >= lineup.categories.size()) return Collections.emptyList();
        if (gFilter != F_ALL && gFiltered != null) return gFiltered;
        return lineup.categories.get(gCat).channels;
    }

    private static String filterName(int f) {
        switch (f) {
            case F_FAVORITES: return Ui.tr("Favoritos", "Favorites");
            case F_SPORTS: return Ui.tr("Esportes", "Sports");
            case F_MOVIES: return Ui.tr("Filmes", "Movies");
            case F_NEWS: return Ui.tr("Notícias", "News");
            case F_KIDS: return Ui.tr("Infantil", "Kids");
            default: return Ui.tr("Tudo", "All");
        }
    }

    // What each filter looks for in a channel's name and its group's (folded), and in the show on now's title (sports).
    private static final String[][] FILTER_WORDS = {{}, {},
            {"sport", "espn", "dazn", "tnt sports", "bein", "eurosport", "f1", "nfl", "nba", "mlb", "nhl", "golf", "racing",
                    "premier league", "laliga", "fox soccer", "tennis", "fight", "ufc", "wwe", "fanduel", "bally"},
            {"movie", "cinema", "film", "hbo", "showtime", "starz", "cinemax", "tcm", "amc", "fx movie", "epix", "mgm",
                    "hallmark movies", "lifetime movies", "paramount", "kino"},
            {"news", "cnn", "msnbc", "fox business", "cnbc", "bloomberg", "euronews", "al jazeera", "c-span", "cspan",
                    "newsmax", "weather", "france 24", "dw ", "nbc now"},
            {"kids", "kid", "cartoon", "nick", "disney", "boomerang", "cbbc", "cbeebies", "baby", "junior", "pbs kids",
                    "nicktoons", "toon", "discovery family", "pop", "tiny pop", "jr"}};
    private static final String[] SPORTS_TITLES = {"football", "soccer", "nfl", "nba", "mlb", "nhl", "cricket", "rugby", "golf",
            "tennis", "grand prix", "formula 1", "boxing", "ufc", "racing", "premier league", "champions league", "serie a",
            "laliga", "bundesliga", "match", " v ", " vs "};

    /** Filter `f` on: the channels it keeps, every category's, in the provider's order (the guide from its top). */
    private void applyFilter(int f) {
        gFilter = f;
        gFiltered = null;
        if (f != F_ALL && lineup != null) {
            java.util.Map<String, String> groups = new java.util.HashMap<>();
            for (Xtream.Category cg : lineup.categories) groups.put(cg.id, fold(cg.name));
            List<Xtream.Channel> out = new ArrayList<>();
            if (f == F_FAVORITES) {
                out.addAll(lineup.categories.get(0).channels);
            } else {
                long now = System.currentTimeMillis();
                for (Xtream.Channel ch : lineup.all()) {
                    String g = groups.get(ch.category), name = fold(ch.name);
                    boolean keep = false;
                    for (String word : FILTER_WORDS[f]) {
                        if (name.contains(word) || g != null && g.contains(word)) {
                            keep = true;
                            break;
                        }
                    }
                    if (!keep && f == F_SPORTS) {       // a sport on a channel of any kind, now (its guide, when here)
                        List<Xtream.Program> progs;
                        synchronized (epg) {
                            progs = epg.get(ch.id);
                        }
                        int at = programAt(progs, now);
                        if (at >= 0) {
                            String t = " " + fold(progs.get(at).title) + " ";
                            for (String word : SPORTS_TITLES) if (t.contains(word)) keep = true;
                        }
                    }
                    if (keep) out.add(ch);
                }
            }
            gFiltered = out;
        }
        gRow = 0;
        gTop = 0;
        gCol = 1;
        gTime = System.currentTimeMillis();
        List<Xtream.Channel> rows = guideChannels();
        if (rows.isEmpty()) flash(Ui.tr("Nenhum canal com este filtro", "No channels with this filter"));
        aimGuide(true);
        announceForAccessibility(filterName(f) + ", " + rows.size());
        invalidate();
    }

    /** The filters' row, at the right above the time line: the one on (white), the focus (the theme's tile). */
    private void drawChips(Canvas c) {
        float x = G_INFO_X * k, y = CHIP_Y * k;
        for (int i = 0; i < FILTERS; i++) {
            String name = filterName(i);
            float tw = gChip_text.measureText(name), cw = tw + 44 * k;
            r.set(x, y, x + cw, y + CHIP_H * k);
            boolean on = gFilter == i, focused = gChips && gChip == i;
            if (focused) {
                r.inset(-4 * k, -4 * k);
                fill.setColor(LiveTheme.tile);
            } else {
                fill.setColor(on ? 0xFFF5F5F7 : 0x1FFFFFFF);
            }
            fill.setShader(null);
            c.drawRoundRect(r, r.height() / 2, r.height() / 2, fill);
            gChip_text.setColor(focused ? LiveTheme.ink : on ? 0xFF1C1C1E : 0xD9FFFFFF);
            c.drawText(name, r.centerX() - tw / 2, r.centerY() + Ui.halfCap(gChip_text), gChip_text);
            x += cw + 14 * k;
        }
    }

    private Paint gChip_text;

    /** The channel's programs, or null while they load (asked for the first time, or again when old). */
    /** The channel's programs for the overlay (null while they load). */
    List<Xtream.Program> programsOf(Xtream.Channel channel) {
        return programs(channel);
    }

    /** The playing channel's show on now, from its start (the overlay's Start over). */
    void startOverPlaying() {
        startOver(live.playing());
    }

    private List<Xtream.Program> programs(Xtream.Channel channel) {
        long now = System.currentTimeMillis();
        synchronized (epg) {
            List<Xtream.Program> list = epg.get(channel.id);
            Long at = epgAt.get(channel.id);
            boolean stale = at == null || !fresh(at, list, now);
            epgWanted.put(channel.id, now);
            if ((list == null || stale) && epgPending.add(channel.id)) {
                final Xtream.Channel chan = channel;
                final List<Xtream.Program> had = list;
                epgIo.execute(() -> loadGuide(chan, had));
            }
            return list;
        }
    }

    /**
     * A channel's guide, on a background thread: the one saved on the TV while it's fresh, else the provider's,
     * with the programs that are over kept from before (the past days picked in the settings), then saved.
     */
    private void loadGuide(Xtream.Channel channel, List<Xtream.Program> had) {
        synchronized (epg) {
            Long wanted = epgWanted.get(channel.id);
            if (wanted == null || System.currentTimeMillis() - wanted > 3000) {     // scrolled past: asked again when seen
                epgPending.remove(channel.id);
                postInvalidate();
                return;
            }
        }
        try {
            fetchGuide(channel, had);
        } catch (RuntimeException e) {      // a reply it can't read: the row shows the channel, not "…" for good
            AppLog.w("guide " + channel.id + ": " + e);
            synchronized (epg) {
                epgPending.remove(channel.id);
                epgAt.put(channel.id, System.currentTimeMillis());
                if (!epg.containsKey(channel.id)) epg.put(channel.id, new ArrayList<>());
            }
            postInvalidate();
        }
    }

    private void fetchGuide(Xtream.Channel channel, List<Xtream.Program> had) {
        android.content.Context c = getContext();
        long now = System.currentTimeMillis();
        List<Xtream.Program> got = null, before = had;
        long at = now;
        // The full guide first, when it has this channel.
        if (LiveOptions.fullGuide(c)) {
            List<Xtream.Program> full = XmltvStore.programs(c, channel.epg, guideFloor(channel, now), now + LiveOptions.daysAhead(c) * DAY);
            if (full != null && !full.isEmpty()) {
                synchronized (epg) {
                    epgPending.remove(channel.id);
                    epgAt.put(channel.id, now);
                    epg.put(channel.id, full);
                }
                LiveSeries.match(c, channel, full);
                LiveFollow.match(c, channel, full);
                postInvalidate();
                return;
            }
        }
        if (had == null) {
            Xtream.SavedGuide saved = Xtream.savedGuide(c, channel.id);
            if (saved != null && fresh(saved.at, saved.programs, now)) {
                got = saved.programs;
                at = saved.at;
            } else if (saved != null) {
                before = saved.programs;
            }
        }
        if (got == null) {
            try {
                got = keepPast(channel, before, Xtream.guide(c, channel, 12), now);
                Xtream.saveGuide(c, channel.id, got, now);
            } catch (IOException e) {
                got = before;     // the provider can't be reached: what there was, until the next try
            }
        }
        synchronized (epg) {
            epgPending.remove(channel.id);
            epgAt.put(channel.id, at);
            epg.put(channel.id, got == null ? new ArrayList<>() : got);
        }
        LiveSeries.match(c, channel, got);
        LiveFollow.match(c, channel, got);
        postInvalidate();
    }

    /** A new full guide is in use: every channel's programs are read again (from it). */
    void guideReplaced() {
        synchronized (epg) {
            epg.clear();
            epgAt.clear();
        }
        if (!query.trim().isEmpty()) runSearch();
        invalidate();
    }

    /**
     * Whether a guide fetched at `at` will do: not older than the hours picked, not from before this Live TV when it
     * updates on start, and with programs for at least the next hour (the short guide runs out).
     */
    private boolean fresh(long at, List<Xtream.Program> list, long now) {
        android.content.Context c = getContext();
        if (now - at > LiveOptions.guideHours(c) * HOUR || at > now) return false;
        if (LiveOptions.guideOnStart(c) && at < sessionAt) return false;
        return list == null || list.isEmpty() || list.get(list.size() - 1).end > now + HOUR;
    }

    /** The new programs, after the ones from `before` that are over and still within the days kept. */
    private List<Xtream.Program> keepPast(Xtream.Channel channel, List<Xtream.Program> before, List<Xtream.Program> fresh, long now) {
        long from = guideFloor(channel, now);
        List<Xtream.Program> out = new ArrayList<>();
        long firstNew = fresh.isEmpty() ? Long.MAX_VALUE : fresh.get(0).start;
        if (before != null) for (Xtream.Program p : before) if (p.end > from && p.end <= firstNew && p.end <= now) out.add(p);
        for (Xtream.Program p : fresh) if (p.end > from) out.add(p);
        return out;
    }

    /** How far back the guide goes on `channel`: its catch-up days, or the past days kept, whichever is more. */
    private long guideFloor(Xtream.Channel channel, long now) {
        return now - Math.max(channel.archive, LiveOptions.pastDays(getContext())) * DAY;
    }

    /** The program on at `time` (or, before any starts, the first one), as an index; -1 = none. */
    private static int programAt(List<Xtream.Program> list, long time) {
        if (list == null) return -1;
        for (int i = 0; i < list.size(); i++) if (list.get(i).end > time) return i;
        return -1;
    }

    /** How far back the channel's catch-up goes: its days, or now (none). */
    private static long catchupFrom(Xtream.Channel channel, long now) {
        return channel.archive > 0 ? now - channel.archive * DAY : now;
    }

    /** Whether program `p` (over) can play from the channel's catch-up. */
    static boolean replayable(Xtream.Channel channel, Xtream.Program p, long now) {
        return channel.archive > 0 && p.end <= now && p.start >= catchupFrom(channel, now);
    }

    /** Whether the guide's focus is on a program that's over. */
    private boolean inPast() {
        List<Xtream.Channel> rows = guideChannels();
        if (gRow >= rows.size()) return false;
        List<Xtream.Program> list = programs(rows.get(gRow));
        int at = programAt(list, gTime);
        return at >= 0 && list.get(at).end <= System.currentTimeMillis();
    }

    private float minutes(long t) {
        return (t - gBase) / 60_000f;
    }

    /** The x of minute `m` (from gBase) with the time line where its spring is now. */
    private float timeX(float m) {
        return (G_GRID_X + (m - gTimeOff.value) * ((G_GRID_R - G_GRID_X) / G_MINUTES)) * k;
    }

    /** The top of row `row` (a fraction while gliding) with the rows where their spring is now; `rise`: coming in. */
    private float rowY(float row, float rise) {
        return (G_TOP + (row - gRowsTop.value) * G_ROW_STEP) * k + rise;
    }

    /** Sets the guide's springs to the focus (snap: no movement), and fades the details up for a new program. */
    private void aimGuide(boolean snap) {
        List<Xtream.Channel> rows = guideChannels();
        gRowsTop.target = gTop;
        gTimeOff.target = minutes(gStart);
        fRow.target = gRow;
        fCol.target = gCol;
        float winA = gTimeOff.target, winB = winA + G_MINUTES;
        List<Xtream.Program> list = gRow < rows.size() ? programs(rows.get(gRow)) : null;
        int at = programAt(list, gTime);
        Xtream.Program p = at >= 0 ? list.get(at) : null;
        fA.target = p == null ? winA : Math.max(winA, minutes(p.start));
        fB.target = p == null ? winB : Math.min(winB, minutes(p.end));
        if (snap) {
            gRowsTop.snap(gRowsTop.target);
            gTimeOff.snap(gTimeOff.target);
            fRow.snap(fRow.target);
            fCol.snap(fCol.target);
            fA.snap(fA.target);
            fB.snap(fB.target);
        }
        long key = gRow * 1_000_003L + (gCol == 0 ? -1 : p == null ? 0 : p.start / 60_000);
        if (key != infoKey) {
            infoKey = key;
            if (snap) info.snap(1); else info.snap(0);
            info.target = 1;
        }
    }

    /**
     * How far the guide is moved over by its groups (1920 space). With the video at the right (Sky's, Sling's, Hulu's
     * layouts) it stays and the groups come in over it (Sky Q's), so the video doesn't leave the screen.
     */
    private float gShift() {
        return videoRight() ? 0 : GP_W * Ui.clamp(gPanel.value);
    }

    private static boolean videoRight() {
        return P_X > 960;
    }

    private void drawGuide(Canvas c, long up) {
        float dx = gShift() * k, panel = GP_W * Ui.clamp(gPanel.value) * k;
        c.save();
        c.translate(dx, 0);
        drawGuideBody(c, up);
        c.restore();
        if (gPanel.value > 0.004f) {
            if (dx == 0) {      // over the guide: it darkens behind the groups
                fill.setShader(null);
                fill.setColor(Ui.alpha(0x99000000, Ui.clamp(gPanel.value)));
                c.drawRect(panel, 0, w, h, fill);
            }
            drawGuideGroups(c, panel);
        }
    }

    /** The rail (Search, the channel list, Recordings, History, Settings) and the categories, sliding in at the left. */
    private void drawGuideGroups(Canvas c, float dx) {
        if (lName == null) makeListPaints();
        float x0 = dx - GP_W * k, a = Ui.clamp(appear.value);
        int save = a < 0.995f ? c.saveLayerAlpha(0, 0, w, h, Math.round(255 * a)) : c.save();
        fill.setShader(null);
        fill.setColor(LiveTheme.railColor);     // the style's: Glassio's greys, the others from their background
        c.drawRect(x0, 0, x0 + GP_RAIL * k, h, fill);
        fill.setColor(LiveTheme.column);
        c.drawRect(x0 + GP_RAIL * k, 0, dx, h, fill);
        // The categories, the focused one (or the guide's, from the rail) on a tile.
        int rows = catRows();
        float top = gGroupTop.value;
        int first = Math.max(0, (int) Math.floor(top)), last = Math.min(panelCats.size(), first + rows + 2);
        catRow(x0 + GP_RAIL * k - 24 * k, gGroupRow.value, top, focusRect);
        focusRect.right = dx - 24 * k;
        fill.setColor(gRail ? LiveTheme.surface : LiveTheme.tile);
        c.drawRoundRect(focusRect, 14 * k * LiveTheme.round, 14 * k * LiveTheme.round, fill);
        for (int i = first; i < last; i++) {
            Xtream.Category cg = lineup.categories.get(panelCats.get(i));
            catRow(x0 + GP_RAIL * k - 24 * k, i, top, r);
            r.right = dx - 24 * k;
            boolean onTile = !gRail && i == Math.round(gGroupRow.value);
            lGroup.setColor(onTile ? LiveTheme.ink : 0xE6FFFFFF);
            lNum.setColor(onTile ? Ui.alpha(LiveTheme.ink, 0.6f) : 0x80FFFFFF);
            String size = String.valueOf(cg.channels.size());
            c.drawText(size, r.right - 24 * k - lNum.measureText(size), r.centerY() + Ui.halfCap(lNum), lNum);
            textAndTag(c, nameOfCategory(cg.name), tagOfCategory(cg.name), r.left + 28 * k, r.centerY() + Ui.halfCap(lGroup), lGroup,
                    r.width() - 80 * k - lNum.measureText(size));
        }
        lGroup.setColor(0xE6FFFFFF);
        lNum.setColor(0x80FFFFFF);
        drawRail(c, x0);
        c.restoreToCount(save);
    }

    /**
     * The rail: its icons in a column (Search, the channel list, Recordings, History; Settings at the foot). With the
     * focus it opens over the categories, YouTube TV's side menu: each icon with its name, the focused one on the
     * theme's tile; without it, a soft mark on none and the icons alone.
     */
    private void drawRail(Canvas c, float x0) {
        float open = Ui.clamp(gRailOpen.value), wide = (GP_RAIL + (RAIL_OPEN_W - GP_RAIL) * open) * k;
        if (open > 0.01f) {     // the panel opening over the categories, a shadow at its edge
            fill.setShader(null);
            fill.setColor(Ui.alpha(0x99000000, open));
            c.drawRect(x0 + wide, 0, x0 + GP_W * k, h, fill);
        }
        fill.setColor(LiveTheme.railColor);
        c.drawRect(x0, 0, x0 + wide, h, fill);
        fill.setColor(0x14FFFFFF);
        c.drawRect(x0 + wide - 1.5f * k, 0, x0 + wide, h, fill);
        String[] names = {Ui.tr("Início", "Home"), Ui.tr("Buscar", "Search"), Ui.tr("Lista de canais", "Channels list"),
                Ui.tr("Gravações", "Recordings"), Ui.tr("Histórico", "History"), Ui.tr("Ajustes", "Settings")};
        if (gRail) {        // the focus, gliding between the items
            float fy = railY(gRailRow.value);
            r.set(x0 + 18 * k, fy - 38 * k, x0 + wide - 18 * k, fy + 38 * k);
            fill.setColor(LiveTheme.tile);
            c.drawRoundRect(r, 20 * k * LiveTheme.round, 20 * k * LiveTheme.round, fill);
        }
        for (int i = 0; i < RAIL_ICONS.length; i++) {
            float cy = railY(i), cx = x0 + GP_RAIL / 2 * k;
            boolean on = gRail && i == gRailIdx;
            Symbols.draw(getContext(), c, RAIL_ICONS[i], cx, cy, 30 * k, on ? LiveTheme.ink : 0xB3FFFFFF);
            if (open > 0.05f) {
                int was = lGroup.getColor();
                lGroup.setColor(on ? LiveTheme.ink : was);      // on the focus: its ink (dark on a white one)
                lGroup.setAlpha(Math.round(255 * open * (on ? 1 : 0.75f)));
                c.drawText(ellipsize(names[i], lGroup, wide - (GP_RAIL + 30) * k), x0 + (GP_RAIL - 4) * k, cy + Ui.halfCap(lGroup), lGroup);
                lGroup.setColor(was);
            }
        }
    }

    private static final float RAIL_OPEN_W = 400;

    /** Rail item `i`'s middle (a fraction while gliding): the first four from the top, Settings at the foot. */
    private float railY(float i) {
        if (i <= 4) return (260 + i * 100) * k;
        return (660 + (i - 4) * 340) * k;
    }

    /** ◀ in the guide (or Back before anything plays): the rail and the categories beside it, the focus on the guide's category. */
    private void openGuideGroups() {
        panelCats.clear();
        for (int i = 0; i < lineup.categories.size(); i++) {
            Xtream.Category cg = lineup.categories.get(i);
            if (cg != search && cg != recordings) panelCats.add(i);
        }
        if (panelCats.isEmpty()) return;
        gGroups = true;
        gRail = false;
        gGroupIdx = Math.max(0, panelCats.indexOf(gCat));
        gGroupTopI = scroll(gGroupIdx, Math.max(0, gGroupIdx - catRows() / 2), catRows(), panelCats.size());
        gGroupRow.snap(gGroupIdx);
        gGroupTop.snap(gGroupTopI);
        gPanel.target = 1;
        live.shiftPreview(videoRight() ? 0 : GP_W);       // the window moves over with the guide (TiviMate's)
        announceForAccessibility(lineup.categories.get(gCat).name);
        invalidate();
    }

    private void closeGuideGroups() {
        gGroups = gRail = false;
        gPanel.target = 0;
        live.shiftPreview(0);
        invalidate();
    }

    /** Keys with the guide's groups beside it. */
    private void guideGroupsKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_CHANNEL_DOWN: {
                int d = code == KeyEvent.KEYCODE_DPAD_UP ? -1 : code == KeyEvent.KEYCODE_DPAD_DOWN ? 1
                        : code == KeyEvent.KEYCODE_CHANNEL_UP ? -catRows() : catRows();
                if (gRail) {
                    gRailIdx = Math.max(0, Math.min(RAIL_ICONS.length - 1, gRailIdx + (d > 0 ? 1 : -1)));
                } else {
                    gGroupIdx = Math.max(0, Math.min(panelCats.size() - 1, gGroupIdx + d));
                    useGuideCategory(panelCats.get(gGroupIdx));
                    gGroupTopI = scroll(gGroupIdx, gGroupTopI, catRows(), panelCats.size());
                    gGroupRow.target = gGroupIdx;
                    gGroupTop.target = gGroupTopI;
                    announceForAccessibility(lineup.categories.get(gCat).name);
                }
                break;
            }
            case KeyEvent.KEYCODE_DPAD_LEFT:
                gRail = true;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (gRail) gRail = false; else closeGuideGroups();
                break;
            default:
                if (!ok(code)) break;
                if (!gRail) {
                    closeGuideGroups();
                    break;
                }
                int which = gRailIdx;
                closeGuideGroups();
                switch (which) {
                    case 0: openHome(); break;
                    case 1: openSearch(); break;
                    case 2: openList(); break;
                    case 3: openRecordings(); break;
                    case 4: openHistory(); break;
                    default: openSettings(); break;
                }
                break;
        }
        invalidate();
    }

    /** The guide on category `index` (from its groups): its rows from the top, the playing channel's if it has it. */
    private void useGuideCategory(int index) {
        gFilter = F_ALL;
        gFiltered = null;
        gChips = false;
        gHead = 0;
        gCat = index;
        List<Xtream.Channel> rows = guideChannels();
        Xtream.Channel playing = live.playing();
        gRow = 0;
        for (int i = 0; playing != null && i < rows.size(); i++) if (rows.get(i).id == playing.id) gRow = i;
        gTop = scroll(gRow, 0, G_ROWS, rows.size());
        long now = System.currentTimeMillis();
        if (gTime < now) gTime = now;
        aimGuide(true);
    }

    /**
     * ◀ held in the guide: into the row's catch-up, on the program before the one on now (◀ ▶ then go through the
     * past; Back comes back to now).
     */
    private void enterGuideCatchup() {
        List<Xtream.Channel> rows = guideChannels();
        if (gRow >= rows.size()) return;
        Xtream.Channel channel = rows.get(gRow);
        if (channel.archive <= 0) {
            flash(Ui.tr("Este canal não tem reprise", "This channel has no catch-up"));
            return;
        }
        List<Xtream.Program> list = programs(channel);
        long now = System.currentTimeMillis();
        int at = programAt(list, now);
        Xtream.Program p = list != null && at > 0 ? list.get(at - 1) : null;
        if (p == null || !replayable(channel, p, now)) {
            flash(Ui.tr("Nada na reprise ainda", "Nothing in the catch-up yet"));
            return;
        }
        gCol = 1;
        gTime = p.start;
        guideMoved();
        flash(Ui.tr("Reprise: ◀ ▶ entre os programas, Voltar para agora", "Catch-up: ◀ ▶ through the shows, Back for now"));
    }

    /** ◀ in the guide: in the past (catch-up), at once and held repeating; else held, into the catch-up. */
    private boolean guideLeft(KeyEvent e, boolean down) {
        if (inPast() || gHead > 0) {
            if (down) {
                guideKey(KeyEvent.KEYCODE_DPAD_LEFT);
                invalidate();
            }
            return true;
        }
        if (down) {
            if (e.getRepeatCount() == 0) {
                pressCode = KeyEvent.KEYCODE_DPAD_LEFT;
                pressLong = false;
            } else if (pressCode == KeyEvent.KEYCODE_DPAD_LEFT && !pressLong) {
                pressLong = true;
                enterGuideCatchup();
                invalidate();
            }
            return true;
        }
        boolean act = pressCode == KeyEvent.KEYCODE_DPAD_LEFT && !pressLong && !e.isCanceled();
        pressCode = -1;
        pressLong = false;
        if (act) {
            guideKey(KeyEvent.KEYCODE_DPAD_LEFT);
            invalidate();
        }
        return true;
    }

    private void drawGuideBody(Canvas c, long up) {
        if (gTitle == null) makeGuidePaints();
        long now = System.currentTimeMillis();
        postInvalidateDelayed(60_000 - now % 60_000);   // the clock and the "now" line move on
        aimGuide(false);    // the focused row's programs may have just arrived
        refreshMarks();
        float a = Ui.clamp(appear.value), rise = (1 - a) * (1 - a) * 70 * k;
        int save = a < 0.995f ? c.saveLayerAlpha(0, 0, w, h, Math.round(255 * a)) : c.save();
        // Dark everywhere but the preview window, where the video shows through.
        preview.set(P_X * k, P_Y * k, (P_X + P_W) * k, (P_Y + P_H) * k);
        c.save();
        c.clipOutRect(preview);
        LiveTheme.background(c, w, h, 1);
        c.restore();
        List<Xtream.Channel> rows = guideChannels();
        Xtream.Channel focus = gRow < rows.size() ? rows.get(gRow) : null;
        List<Xtream.Program> fp = focus == null ? null : programs(focus);
        int fi = programAt(fp, gCol == 1 ? gTime : now);
        drawGuideInfo(c, focus, fi >= 0 ? fp.get(fi) : null, fp == null, now);
        drawPreviewFrame(c, now);
        c.translate(0, rise);
        // The time line.
        float view = gTimeOff.value;
        long from = gBase + (long) (Math.floor(view / 30) * HALF_HOUR), to = gBase + (long) ((view + G_MINUTES) * 60_000L);
        c.save();
        c.clipRect(G_GRID_X * k, 0, w, h);
        for (long t = from; t < to; t += HALF_HOUR) c.drawText(Ui.time(t), timeX(minutes(t)) + 4 * k, G_TIMES_BASE * k, gTimeText);
        c.restore();
        // The day over the channels (the hour too when it fits), then two buttons: the categories, a time to go to.
        float bcy = (G_TIMES_BASE - 9) * k, b2 = (G_COL_R - 20) * k, b1 = b2 - 50 * k;
        // The day on screen: the time line's (another day once moved there), the hour only for today.
        long shown = gBase + (long) (view * 60_000L);
        String day = dayShort.format(new java.util.Date(shown));
        boolean today = day.equals(dayShort.format(new java.util.Date(now)));
        String withTime = today ? day + "  " + Ui.time(now) : day;
        float roomDay = b1 - 26 * k - G_LOGO_X * k;
        c.drawText(ellipsize(gClock.measureText(withTime) <= roomDay ? withTime : day, gClock, roomDay), G_LOGO_X * k, G_TIMES_BASE * k, gClock);
        drawHeadButton(c, "list", b1, bcy, gHead == 1);
        drawHeadButton(c, "schedule", b2, bcy, gHead == 2);
        fill.setShader(null);
        fill.setColor(0x26FFFFFF);
        c.drawRect(G_LOGO_X * k, (G_TIMES_BASE + 16) * k, w, (G_TIMES_BASE + 18) * k, fill);
        // The rows: the logos' focus (fading out as it moves into the programs), the logos and the cells.
        c.save();
        c.clipRect(0, (G_TOP - 16) * k, w, h);
        float col = Ui.clamp(fCol.value), fy = rowY(fRow.value, 0);
        float away = gHead > 0 ? 0.3f : 1;     // a header button has the focus: the row's focus faint
        if (col < 0.99f) {      // the channels' column has the focus: a red tile on the row
            focusRect.set((G_LOGO_X - 14) * k, fy, G_COL_R * k, fy + G_ROW_H * k);
            fill.setShader(null);
            fill.setColor(Ui.alpha(LiveTheme.tile, (1 - col) * away));
            c.drawRoundRect(focusRect, 14 * k * LiveTheme.round, 14 * k * LiveTheme.round, fill);
        }
        int first = Math.max(0, (int) Math.floor(gRowsTop.value)), last = Math.min(rows.size(), first + G_ROWS + 2);
        Xtream.Channel playing = live.playing();
        for (int r = first; r < last; r++) {
            Xtream.Channel channel = rows.get(r);
            float y = rowY(r, 0), cy = y + G_ROW_H / 2 * k;
            float onTile = r == gRow ? 1 - col : 0;
            // The logo, and the channel's number and name small under it, both centered in the column (a long name is
            // cut at its end: the number always shows).
            float left = G_LOGO_X * k, right = (G_COL_R - G_MARKS_W) * k, mid = (left + right) / 2;
            boolean atLeft = LiveOptions.guideLogosLeft(getContext());
            cell.set(left + 8 * k, y + 5 * k, right - 8 * k, y + (G_ROW_H - (compact ? 23 : 28)) * k);
            drawLogo(c, channel, cell, 1, false, atLeft);
            Paint np = r == gRow ? gNameOn : gName;
            np.setColor(r == gRow ? LiveTheme.onTile(LiveTheme.text, onTile) : 0xB3FFFFFF);
            String nm = nameOf(channel), label = channel.num > 0 ? channel.num + "  " + nm : nm, tag = tagOf(channel);
            float room = right - left - 8 * k, tw = textAndTagWidth(label, tag, np, room);
            textAndTag(c, label, tag, atLeft ? left + 8 * k : mid - tw / 2, y + (G_ROW_H - (compact ? 7 : 9)) * k, np, room);
            // The marks at the column's end, one over the other: ▶ the one playing, catch-up's arrow, the heart.
            int marks = (playing != null && playing.id == channel.id ? 1 : 0) + (channel.archive > 0 ? 1 : 0)
                    + (Xtream.isFavorite(lineup, channel.id) ? 1 : 0);
            float mx = (G_COL_R - G_MARKS_W / 2 - 4) * k, my = cy - (marks - 1) * 12 * k;
            if (playing != null && playing.id == channel.id) {
                drawPlayMark(c, mx, my, 10 * k, LiveTheme.onTile(LiveTheme.text, onTile));
                my += 24 * k;
            }
            if (channel.archive > 0) {
                drawReplay(c, mx, my, 10 * k, LiveTheme.onTile(0xCCFFFFFF, onTile));
                my += 24 * k;
            }
            if (Xtream.isFavorite(lineup, channel.id)) drawHeart(c, mx, my, 17 * k, LIVE_RED);
            drawRowCells(c, channel, y, now, false);
        }
        // The programs' focus: one white tile gliding along the row and between rows, a little bigger, raised.
        if (col > 0.01f) {
            float x1 = Math.max(G_GRID_X * k, timeX(fA.value)), x2 = Math.min(G_GRID_R * k, timeX(fB.value)) - 6 * k;
            focusRect.set(x1, fy, Math.max(x1 + 12 * k, x2), fy + G_ROW_H * k);
            fill.setShader(null);
            fill.setColor(Ui.alpha(LiveTheme.tile, col * away));
            c.drawRoundRect(focusRect, 10 * k * LiveTheme.round, 10 * k * LiveTheme.round, fill);
            if (col > 0.5f && gHead == 0) {    // the titles again over the red (where it covers them)
                c.save();
                c.clipRect(focusRect);
                int r0 = Math.max(first, (int) Math.floor(fRow.value)), r1 = Math.min(last - 1, (int) Math.ceil(fRow.value));
                for (int r = r0; r <= r1; r++) drawRowCells(c, rows.get(r), rowY(r, 0), now, true);
                c.restore();
            }
        }
        c.restore();
        // The "now" line last, in front of the programs and the focus, its dot in the time line.
        float nowX = timeX(minutes(now));
        if (nowX >= G_GRID_X * k && nowX <= G_GRID_R * k) {
            fill.setShader(null);
            fill.setColor(LIVE_RED);
            c.drawRect(nowX - 1.25f * k, (G_TIMES_BASE + 17) * k, nowX + 1.25f * k, h, fill);
            float ty = (G_TIMES_BASE + 10) * k;
            nowMark.rewind();
            nowMark.moveTo(nowX - 9 * k, ty);
            nowMark.lineTo(nowX + 9 * k, ty);
            nowMark.lineTo(nowX, ty + 12 * k);
            nowMark.close();
            c.drawPath(nowMark, fill);
        }
        c.restoreToCount(save);
        // Prefetch the rows just below, so scrolling down doesn't wait.
        for (int r = gTop + G_ROWS + 1; r < Math.min(rows.size(), gTop + G_ROWS + 1 + G_AHEAD); r++) programs(rows.get(r));
    }

    /** A row's programs: the tiles and their titles (`ink`: only the titles, dark, for under the focus). */
    private void drawRowCells(Canvas c, Xtream.Channel channel, float y, long now, boolean ink) {
        float gridL = G_GRID_X * k, gridR = G_GRID_R * k, y2 = y + G_ROW_H * k;
        List<Xtream.Program> list = programs(channel);
        if (list == null || list.isEmpty()) {
            // Still loading, or no guide for this channel: one tile with its name.
            cell.set(gridL, y, gridR - 4 * k, y2);
            drawTile(c, list == null ? "…" : channel.name, "", ink, LiveTheme.cellLater, 0, 0, null, false, false);
            return;
        }
        float m0 = gTimeOff.value, m1 = m0 + G_MINUTES;
        for (int i = 0; i < list.size(); i++) {
            Xtream.Program p = list.get(i);
            float a = minutes(p.start), b = minutes(p.end);
            if (b <= m0 || a >= m1) continue;
            float x1 = Math.max(gridL, timeX(a)), x2 = Math.min(gridR, timeX(b)) - 4 * k;
            if (x2 - x1 < 6 * k) continue;
            cell.set(x1, y, x2, y2);
            String text = p.title.isEmpty() ? channel.name : p.title;
            int tag = p.live ? 1 : p.fresh ? 2 : p.repeat ? 3 : 0;
            String score = scoreTag(p);     // a followed team's match: its score instead of the tag
            if (score != null) tag = score.startsWith("FT") ? 5 : 4;
            if (p.end > now) {
                String key = channel.id + ":" + p.start;
                drawTile(c, text, p.sub, ink, p.start <= now ? LiveTheme.cellNow : LiveTheme.cellLater, 0, tag, score,
                        recorded.contains(key), reminded.contains(key));
                continue;
            }
            // Over: in the catch-up (it plays), as bright as later ones with the replay mark in the theme's color;
            // gone, darker.
            boolean replay = replayable(channel, p, now);
            boolean mark = replay && x2 - x1 > 70 * k;
            drawTile(c, text, p.sub, ink, replay ? LiveTheme.cellLater : LiveTheme.cellPast, mark ? 30 * k : 0, tag == 1 ? 0 : tag, score, false, false);
            if (mark) drawReplay(c, cell.left + 26 * k, mark && !p.sub.isEmpty() ? cell.centerY() - 14 * k : cell.centerY(), 12 * k,
                    ink ? LiveTheme.ink : LiveTheme.text);
        }
    }

    /**
     * The program's badge (LIVE, else NEW: YouTube TV's), its left edge at x and centered on y; how much room it
     * takes. It's drawn with the tile (drawCell, right after the tile itself), so it shows over the focus too.
     */
    private float drawBadge(Canvas c, Xtream.Program p, float x, float y, boolean ink) {
        String s = p.live ? badgeLive : badgeNew;
        float tw = gBadge.measureText(s), bw = tw + 20 * k, bh = 30 * k;
        badgeRect.set(x, y - bh / 2, x + bw, y + bh / 2);
        pendingBadge = p.live ? 2 : 1;
        pendingBadgeText = s;
        return bw + 12 * k;
    }

    private void drawPendingBadge(Canvas c) {
        if (pendingBadge == 0) return;
        fill.setShader(null);
        fill.setColor(pendingBadge == 2 ? LIVE_RED : TAG_NEW);
        c.drawRoundRect(badgeRect, 6 * k, 6 * k, fill);
        c.drawText(pendingBadgeText, badgeRect.left + 10 * k, badgeRect.centerY() + Ui.halfCap(gBadge), gBadge);
        pendingBadge = 0;
    }

    /**
     * One program tile, TiviMate's way: the title, its tag after it (`tag` 1 LIVE, 2 NEW, 3 REPEAT), the recording's
     * dot and the reminder's bell, and the episode (`sub`) small under it; without one, the title alone, centered.
     * `ink`: only the text, dark, for under the focus. `indent`: room kept at the start (catch-up's arrow).
     */
    private void drawTile(Canvas c, String title, String sub, boolean ink, int color, float indent, int tag, String score,
            boolean rec, boolean bell) {
        if (!ink) {
            fill.setShader(null);
            fill.setColor(color);
            c.drawRoundRect(cell, 5 * k * LiveTheme.round, 5 * k * LiveTheme.round, fill);
            if (LiveTheme.rim != 0) {       // glass: a light edge
                if (rimPaint == null) {
                    rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                    rimPaint.setStyle(Paint.Style.STROKE);
                }
                rimPaint.setStrokeWidth(1.5f * k);
                LiveTheme.drawRim(c, cell, 5 * k * LiveTheme.round, rimPaint);
            }
        }
        float x = cell.left + 16 * k + indent, room = cell.right - 12 * k - x;
        if (room < 24 * k) return;
        Paint tp = ink ? gCellOn : gCell;
        float rs = cell.height() / (80 * k);       // the row's size against the comfortable one
        boolean two = !sub.isEmpty() && cell.height() >= 56 * k;
        float base = two ? cell.centerY() - 5 * k * rs : cell.centerY() + Ui.halfCap(tp);
        // The tags' room: kept only when the title still has a fair share.
        String tagText = score != null ? score : tag == 1 ? badgeLive : tag == 2 ? badgeNew : tag == 3 ? badgeRepeat : null;
        float tagW = tagText == null ? 0 : gBadge.measureText(tagText) + 14 * k;
        float tagsW = (tagText == null ? 0 : tagW + 10 * k) + (rec ? 22 * k : 0) + (bell ? 28 * k : 0);
        if (room - tagsW < 70 * k) tagsW = 0;
        float titleRoom = room - tagsW;
        int fits = tp.breakText(title, true, titleRoom, null);
        if (fits < Math.min(title.length(), 3)) return;     // too narrow for a name: blank, not a lone "…"
        float tw;
        if (ink && fits < title.length() && cell.contains(focusRect.centerX(), focusRect.centerY())) {
            cellMarquee.draw(c, title, x, base, titleRoom, tp, k);
            tw = titleRoom;
        } else {
            String shown = ellipsize(title, tp, titleRoom);
            c.drawText(shown, x, base, tp);
            tw = tp.measureText(shown);
        }
        if (tagsW > 0) {
            float tx = x + tw + 10 * k, mid = base - Ui.halfCap(tp);
            if (tagText != null) {
                badgeRect.set(tx, mid - 12 * k * Math.min(1, rs + 0.1f), tx + tagW, mid + 12 * k * Math.min(1, rs + 0.1f));
                fill.setShader(null);
                fill.setColor(tag == 1 || tag == 4 ? LIVE_RED : tag == 2 ? TAG_NEW : TAG_REPEAT);
                c.drawRoundRect(badgeRect, 5 * k, 5 * k, fill);
                c.drawText(tagText, tx + 7 * k, mid + Ui.halfCap(gBadge), gBadge);
                tx += tagW + 10 * k;
            }
            if (rec) {
                fill.setShader(null);
                fill.setColor(LIVE_RED);
                c.drawCircle(tx + 7 * k, mid, 7 * k, fill);
                tx += 22 * k;
            }
            if (bell) Symbols.draw(getContext(), c, "bell", tx + 9 * k, mid, 19 * k, ink ? LiveTheme.ink : 0xCCFFFFFF);
        }
        if (two) {
            Paint sp = ink ? gSubOn : gSub;
            c.drawText(ellipsize(sub, sp, room + indent), cell.left + 16 * k, cell.centerY() + 25 * k * rs, sp);
        }
    }

    private static final int TAG_NEW = 0xFF2E9E5B, TAG_REPEAT = 0xFF4E6B82;

    private String goalTeam = "", goalLine = "";
    private long goalAt;
    private Paint goalBig, goalSmall;
    private static final long GOAL_MS = 6000;

    /** A goal in a followed team's match (Goal alerts): a card at the top right for a few seconds. */
    void goal(String team, String line) {
        card(Ui.tr("GOL!  ", "GOAL!  ") + team, line);
    }

    /** A card at the top right for a few seconds: a goal, a recording that failed. */
    void card(String head, String line) {
        goalTeam = head;
        goalLine = line;
        goalAt = SystemClock.uptimeMillis();
        announceForAccessibility(head + ", " + line);
        invalidate();
    }

    /** The goal card, sliding in and out; true while it's on screen. */
    private boolean drawGoal(Canvas c, long up) {
        long t = up - goalAt;
        if (goalAt == 0 || t > GOAL_MS) return false;
        if (goalBig == null || goalBig.getTextSize() != 40 * k) {
            goalBig = Ui.text(Ui.displaySemibold, 40 * k, WHITE);
            goalSmall = Ui.text(Ui.medium, 27 * k, 0xE6FFFFFF);
        }
        float a = Ui.clamp(Math.min(t / 300f, (GOAL_MS - t) / 400f));
        String head = goalTeam;
        float cw = Math.max(goalBig.measureText(head), goalSmall.measureText(goalLine)) + 80 * k;
        float right = w - 48 * k + (1 - a) * 60 * k, top = 48 * k;
        r.set(right - cw, top, right, top + 140 * k);
        int save = c.saveLayerAlpha(r.left - 2, r.top - 2, r.right + 2, r.bottom + 2, Math.round(255 * a));
        fill.setShader(null);
        fill.setColor(LiveTheme.sheet());      // Glassio: its dark glass
        c.drawRoundRect(r, LiveTheme.panelRound(24 * k), LiveTheme.panelRound(24 * k), fill);
        fill.setColor(LIVE_RED);
        c.drawRoundRect(r.left + 18 * k, r.top + 26 * k, r.left + 24 * k, r.bottom - 26 * k, 3 * k, 3 * k, fill);
        c.drawText(head, r.left + 44 * k, r.top + 62 * k, goalBig);
        c.drawText(goalLine, r.left + 44 * k, r.top + 108 * k, goalSmall);
        c.restoreToCount(save);
        return true;
    }

    /** Each program's score tag (a followed team's match, Live scores), "" for none; kept until the scores change. */
    private final Map<Xtream.Program, String> scoreTags = new java.util.IdentityHashMap<>();

    private String scoreTag(Xtream.Program p) {
        LiveScores scores = LiveScores.get(getContext());
        if (!scores.any()) return null;
        String t = scoreTags.get(p);
        if (t == null) {
            t = "";
            LiveScores.Score s = scores.forTitle(Xtream.fold(p.title));
            // That match's own airing (not a replay later on): it starts around the kick-off.
            if (s != null && !s.state.equals("pre") && s.start > 0 && p.start <= s.start + 2 * HOUR && p.end > s.start) {
                t = s.state.equals("in") ? s.homeScore + "–" + s.awayScore + "  " + s.clock : "FT  " + s.homeScore + "–" + s.awayScore;
            }
            scoreTags.put(p, t);
        }
        return t.isEmpty() ? null : t;
    }

    /** New live scores: the info bar and the guide show them. */
    void scoresChanged() {
        scoreTags.clear();
        overlay.refresh();
        invalidate();
    }

    /** A round button in the guide's header: the theme's tile when focused, faint glass otherwise. */
    private void drawHeadButton(Canvas c, String symbol, float cx, float cy, boolean focused) {
        fill.setShader(null);
        fill.setColor(focused ? LiveTheme.tile : 0x1FFFFFFF);
        float r = (focused ? 23 : 20) * k;
        c.drawCircle(cx, cy, r, fill);
        Symbols.draw(getContext(), c, symbol, cx, cy, 24 * k, focused ? LiveTheme.ink : 0xD9FFFFFF);
    }

    /** The header button with the focus: 0 none (the rows), 1 the categories, 2 a time to go to. */
    private int gHead;

    private String headName() {
        return gHead == 1 ? Ui.tr("Categorias", "Categories") : Ui.tr("Ir para um horário", "Go to a time");
    }

    /** The guide at time `t` (on the focused row), the time line starting at its half hour. */
    private void jumpGuide(long t) {
        List<Xtream.Channel> rows = guideChannels();
        if (rows.isEmpty()) return;
        long now = System.currentTimeMillis();
        long last = now + LiveOptions.daysAhead(getContext()) * DAY - 2 * HOUR;
        gCol = 1;
        gTime = Math.max(guideFloor(rows.get(gRow), now), Math.min(last, t));
        gStart = gTime - gTime % HALF_HOUR;
        guideMoved();
        invalidate();
    }

    /** Today's or tomorrow's hour `h` (0-23), from now on. */
    private static long nextHour(long now, int h, boolean tomorrow) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(now);
        if (tomorrow) cal.add(java.util.Calendar.DAY_OF_MONTH, 1);
        cal.set(java.util.Calendar.HOUR_OF_DAY, h);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    /** The header's time button: now, two hours on, tonight, tomorrow morning and evening. */
    private void timeMenu() {
        long now = System.currentTimeMillis(), from = gCol == 1 ? gTime : now;
        long later = from + 2 * HOUR - (from + 2 * HOUR) % HALF_HOUR;
        long tonight = nextHour(now, 20, false), morning = nextHour(now, 8, true), evening = nextHour(now, 20, true);
        List<String> labels = new ArrayList<>();
        List<Long> times = new ArrayList<>();
        labels.add(Ui.tr("Agora", "Now"));
        times.add(now);
        labels.add(Ui.tr("2 horas depois", "2 hours later") + "  ·  " + Ui.time(later));
        times.add(later);
        if (tonight > now + HOUR) {
            labels.add(Ui.tr("Hoje à noite", "Tonight") + "  ·  " + Ui.time(tonight));
            times.add(tonight);
        }
        labels.add(Ui.tr("Amanhã de manhã", "Tomorrow morning") + "  ·  " + Ui.time(morning));
        times.add(morning);
        labels.add(Ui.tr("Amanhã à noite", "Tomorrow evening") + "  ·  " + Ui.time(evening));
        times.add(evening);
        LiveSheet.menu(getContext())
                .setTitle(Ui.tr("Ir para", "Go to"))
                .asList()
                .setItems(labels.toArray(new String[0]), (d, which) -> jumpGuide(times.get(which)))
                .show();
    }

    /** ⏩ ⏪ in the guide: two hours on or back; held, tonight (tomorrow morning once it's late) or now. */
    private boolean guideSkip(KeyEvent e, boolean down) {
        if (!down || guideChannels().isEmpty()) return true;
        boolean ahead = e.getKeyCode() == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD;
        long now = System.currentTimeMillis();
        if (e.getRepeatCount() == 0) {
            jumpGuide((gCol == 1 ? gTime : now) + (ahead ? 2 : -2) * HOUR);
        } else if (e.getRepeatCount() == 1) {
            long tonight = nextHour(now, 20, false);
            jumpGuide(!ahead ? now : tonight > now + HOUR ? tonight : nextHour(now, 8, true));
            flash(Ui.tr("Guia: ", "Guide: ") + dayShort.format(new java.util.Date(gTime)) + "  " + Ui.time(gTime));
        }
        return true;
    }
    private final android.graphics.Path nowMark = new android.graphics.Path();
    private Paint gSub, gSubOn;

    private Paint rimPaint;
    private final Marquee cellMarquee = new Marquee(), guideMarquee = new Marquee(), listMarquee = new Marquee();

    /** A circular arrow about `s` across, centered at (cx, cy): catch-up's mark. */
    private void drawReplay(Canvas c, float cx, float cy, float s, int color) {
        line.setColor(color);
        line.setStrokeWidth(0.24f * s);
        markRect.set(cx - s * 0.6f, cy - s * 0.6f, cx + s * 0.6f, cy + s * 0.6f);     // its own: callers keep theirs in r
        c.drawArc(markRect, -60, 300, false, line);
        float ax = cx + s * 0.3f, ay = cy - s * 0.52f;
        c.drawLine(ax, ay, ax - s * 0.42f, ay - s * 0.22f, line);
        c.drawLine(ax, ay, ax - s * 0.12f, ay + s * 0.4f, line);
    }

    /** The focused program: title, how long is left (or when it starts), the time and channel, the synopsis. */
    private void drawGuideInfo(Canvas c, Xtream.Channel channel, Xtream.Program p, boolean loading, long now) {
        float x = G_INFO_X * k, room = (G_INFO_R - G_INFO_X) * k;
        if (channel == null) return;
        float a = Ui.clamp(info.value);
        int save = a < 0.995f ? c.saveLayerAlpha(x - 10 * k, 0, w, (CHIP_Y + 10) * k, Math.round(255 * a)) : c.save();
        // The show's picture only while nothing plays (with a channel on, its video is the picture).
        Bitmap art = !G_ART || gCol == 0 || live.playing() != null ? null : artOf(p);
        if (art != null) {      // YouTube TV's: the show behind its details, at the right, fading out to the left and down
            artRect.set(ART_X * k, 0, w, ART_H * k);
            drawCropped(c, art, artRect, 0);
            fill.setShader(artFadeLeft);
            c.drawRect(artRect, fill);
            fill.setShader(artFadeDown);
            c.drawRect(artRect, fill);
            fill.setShader(artFadeTop);      // the category's name at the top right stays readable over it
            c.drawRect(artRect.left, 0, artRect.right, 110 * k, fill);
            fill.setShader(null);
            room = Math.min(room, (ART_X + 300 - G_INFO_X) * k);      // the text over the faded part
        }
        // The category at the top right (TiviMate's "provider · group").
        String catRaw = gFilter != F_ALL ? filterName(gFilter) + "  ·  " + guideChannels().size()
                : lineup == null || gCat >= lineup.categories.size() ? "" : lineup.categories.get(gCat).name;
        String catTag = gFilter != F_ALL ? "" : tagOfCategory(catRaw), cats = gFilter != F_ALL ? catRaw : nameOfCategory(catRaw);
        float cw = textAndTagWidth(cats, catTag, gMeta, 900 * k);
        textAndTag(c, cats, catTag, G_INFO_R * k - cw, 60 * k, gMeta, 900 * k);
        if (art != null) cw = 0;      // over the picture, the title has its own room
        c.translate(0, (1 - a) * 16 * k);
        if (gCol == 0) {    // on a logo: what OK does there
            c.drawText(Ui.tr("Favorito, nome, número", "Favorite, name, number"), x, 104 * k, gTitle);
            textAndTag(c, (channel.num > 0 ? channel.num + "  " : "") + nameOf(channel), tagOf(channel), x, 160 * k, gMeta, room);
            gAboutPaint.setAlpha(0x99);
            c.drawText(Ui.tr("OK para pôr nos favoritos, renomear ou mudar o número (o vermelho: favorito).",
                    "OK to favorite it, rename it or change its number (red: favorite)."), x, 212 * k, gAboutPaint);
            c.restoreToCount(save);
            return;
        }
        String titleText = p == null || p.title.isEmpty() ? channel.name : p.title;
        boolean badged = p != null && (p.live || p.fresh);
        // With the groups open the guide slides right: the title loses that much room at the screen's edge.
        float titleRoom = room - cw - 30 * k - (badged ? 140 * k : 0) - (art == null ? gShift() * k : 0);
        float drawn = guideMarquee.draw(c, titleText, x, 104 * k, titleRoom, gTitle, k);
        if (badged) {
            drawBadge(c, p, x + drawn + 20 * k, 104 * k - Ui.halfCap(gTitle), false);
            drawPendingBadge(c);
        }
        float y = 160 * k;
        if (p != null && p.start <= now && p.end > now) {
            float bar = 120 * k, f = (now - p.start) / (float) (p.end - p.start);
            fill.setShader(null);
            fill.setColor(0x40FFFFFF);
            c.drawRoundRect(x, y - 6 * k, x + bar, y, 3 * k, 3 * k, fill);
            fill.setColor(WHITE);
            c.drawRoundRect(x, y - 6 * k, x + Math.max(6 * k, bar * f), y, 3 * k, 3 * k, fill);
            String left = minutesLeft((int) Math.max(1, Math.round((p.end - now) / 60_000.0)));
            c.drawText(left, x + bar + 18 * k, y + 2 * k, gLeft);
            if (channel.archive > 0 && p.start >= catchupFrom(channel, now)) {    // it can start over
                gLeft.setColor(PAST);
                c.drawText("·  " + Ui.tr("Segure OK para começar do início", "Hold OK to start over"),
                        x + bar + 18 * k + gLeft.measureText(left) + 16 * k, y + 2 * k, gLeft);
                gLeft.setColor(LIVE_RED);
            }
        } else if (p != null && p.end <= now) {
            gLeft.setColor(PAST);
            c.drawText(replayable(channel, p, now) ? Ui.tr("Reprise  ·  OK para assistir", "Catch-up  ·  Press OK to watch")
                    : Ui.tr("Já passou", "Already aired"), x, y + 2 * k, gLeft);
            gLeft.setColor(LIVE_RED);
        } else if (p != null) {
            c.drawText(Ui.tr("Começa às ", "Starts at ") + Ui.time(p.start), x, y + 2 * k, gLeft);
        }
        String when = p == null ? (loading ? "" : Ui.tr("Sem guia para este canal", "No guide for this channel"))
                : Ui.time(p.start) + " – " + Ui.time(p.end);
        textAndTag(c, (when.isEmpty() ? "" : when + "  ·  ") + (channel.num > 0 ? channel.num + "  " : "") + nameOf(channel), tagOf(channel),
                x, 212 * k, gMeta, room);
        String about = p == null ? "" : p.about;
        if (!about.equals(gAboutText) || gAbout == null || gAbout.getWidth() != Math.max(1, Math.round(room))) {
            gAboutText = about;
            gAbout = about.isEmpty() ? null : StaticLayout.Builder.obtain(about, 0, about.length(), gAboutPaint,
                    Math.max(1, Math.round(room))).setMaxLines(G_ABOUT_LINES).setEllipsize(TextUtils.TruncateAt.END)
                    .setLineSpacing(0, 1.08f).build();
        }
        if (gAbout != null) {
            c.translate(x, 236 * k);
            gAbout.draw(c);
        }
        c.restoreToCount(save);
    }

    /** Under the playing channel's window, how far its current program is. */
    private void drawPreviewFrame(Canvas c, long now) {
        Xtream.Channel playing = live.playing();
        fill.setShader(null);
        if (playing == null) {
            fill.setColor(LiveTheme.card);
            c.drawRoundRect(preview, 14 * k * LiveTheme.round, 14 * k * LiveTheme.round, fill);
            return;
        }
        List<Xtream.Program> list = programs(playing);
        int i = programAt(list, now);
        if (i >= 0 && list.get(i).start <= now) {
            Xtream.Program p = list.get(i);
            float f = (now - p.start) / (float) (p.end - p.start);
            fill.setColor(0x40FFFFFF);
            c.drawRect(preview.left, preview.bottom + 4 * k, preview.right, preview.bottom + 9 * k, fill);
            fill.setColor(LiveTheme.text);
            c.drawRect(preview.left, preview.bottom + 4 * k, preview.left + preview.width() * Ui.clamp(f), preview.bottom + 9 * k, fill);
        }
    }

    /** In or out of the favorites: the Favorites category changes now, the file (read by the home screen) after. */
    void toggleFavorite(Xtream.Channel channel) {
        if (lineup == null || lineup.categories.isEmpty()) return;
        boolean on = !Xtream.isFavorite(lineup, channel.id);
        List<Xtream.Channel> favs = lineup.categories.get(0).channels;
        if (on) favs.add(channel);
        else for (int i = favs.size() - 1; i >= 0; i--) if (favs.get(i).id == channel.id) favs.remove(i);
        final Xtream.Channel ch = channel;
        epgIo.execute(() -> Xtream.setFavorite(getContext(), ch, on));
        announceForAccessibility(on ? Ui.tr("Nos favoritos", "Added to favorites") : Ui.tr("Fora dos favoritos", "Removed from favorites"));
        invalidate();
    }

    private final android.graphics.Path heart = new android.graphics.Path();

    /** A heart about `s` wide, centered at (cx, cy): the favorites' mark. */
    private void drawHeart(Canvas c, float cx, float cy, float s, int color) {
        heart.reset();
        heart.moveTo(cx, cy + s * 0.42f);
        heart.cubicTo(cx - s * 0.95f, cy - s * 0.1f, cx - s * 0.48f, cy - s * 0.78f, cx, cy - s * 0.28f);
        heart.cubicTo(cx + s * 0.48f, cy - s * 0.78f, cx + s * 0.95f, cy - s * 0.1f, cx, cy + s * 0.42f);
        heart.close();
        fill.setShader(null);
        fill.setColor(color);
        c.drawPath(heart, fill);
    }

    static String minutesLeft(int minutes) {
        String h = minutes >= 60 ? (minutes / 60) + "" : "", m = (minutes % 60) + "";
        if (Ui.ru) return "Осталось " + (h.isEmpty() ? "" : h + " ч ") + m + " мин";
        if (Ui.es) return "Quedan " + (h.isEmpty() ? "" : h + " h ") + m + " min";
        if (Ui.de) return "Noch " + (h.isEmpty() ? "" : h + " Std. ") + m + " Min.";
        if (Ui.en) return (h.isEmpty() ? "" : h + "h ") + m + " min left";
        return "Faltam " + (h.isEmpty() ? "" : h + " h ") + m + " min";
    }

    /** OK in the guide acts when let go; held, it puts the row's channel in or out of the favorites. */
    private boolean guideOk(KeyEvent e, boolean down) {
        if (down) {
            if (e.getRepeatCount() == 0) {
                okDown = true;
                okHeld = false;
            } else if (okDown && !okHeld) {
                okHeld = true;
                heldOk(false);
                invalidate();
            }
            return true;
        }
        boolean act = okDown && !okHeld && !e.isCanceled();
        okDown = okHeld = false;
        if (act) {
            guideKey(e.getKeyCode());
            invalidate();
        }
        return true;
    }

    /**
     * OK held in the guide: on the show on now of a channel with catch-up, a choice of starting it over or the
     * favorites; anywhere else, straight in or out of the favorites.
     */
    private void heldOk(boolean asPage) {
        List<Xtream.Channel> rows = guideChannels();
        if (gRow >= rows.size()) return;
        Xtream.Channel channel = rows.get(gRow);
        long now = System.currentTimeMillis();
        List<Xtream.Program> list = programs(channel);
        int at = programAt(list, gTime);
        Xtream.Program p = at >= 0 ? list.get(at) : null;
        android.content.Context c = getContext();
        boolean show = gCol == 1 && p != null;
        boolean onNow = show && p.start <= now && p.end > now, later = show && p.start > now;
        // What can be done with this show: start it over, be reminded, record it; and the channel's favorite.
        List<String> labels = new ArrayList<>(), icons = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (onNow && channel.archive > 0 && p.start >= catchupFrom(channel, now)) {
            labels.add(Ui.tr("Começar do início", "Start over"));
            icons.add("start_over");
            actions.add(() -> {
                playCat = gCat;
                if (startOver(channel)) closeGuide();
            });
        }
        if (later) {
            boolean set = LiveSchedule.has(c, LiveSchedule.REMIND, channel.id, p.start);
            labels.add(set ? Ui.tr("Cancelar o lembrete", "Cancel the reminder") : Ui.tr("Lembrar", "Remind me"));
            icons.add(set ? "close" : "bell");
            actions.add(() -> {
                if (set) LiveSchedule.cancel(c, LiveSchedule.REMIND + ":" + channel.id + ":" + p.start);
                else LiveSchedule.add(c, item(LiveSchedule.REMIND, channel, p));
                flash(set ? Ui.tr("Lembrete cancelado", "Reminder cancelled") : Ui.tr("Lembrete às ", "Reminder at ") + Ui.time(p.start));
                marksAt = 0;
            });
        }
        if (onNow || later) {
            boolean set = later ? LiveSchedule.has(c, LiveSchedule.RECORD, channel.id, p.start) : LiveRecordings.recording(c, channel.id, p.start);
            labels.add(set ? (later ? Ui.tr("Cancelar a gravação", "Cancel the recording") : Ui.tr("Parar de gravar", "Stop recording"))
                    : Ui.tr("Gravar", "Record"));
            icons.add(set ? "stop" : "record");
            actions.add(() -> {
                if (later && set) {
                    LiveSchedule.cancel(c, LiveSchedule.RECORD + ":" + channel.id + ":" + p.start);
                    flash(Ui.tr("Gravação cancelada", "Recording cancelled"));
                } else if (later) {
                    LiveSchedule.add(c, item(LiveSchedule.RECORD, channel, p));
                    flash(Ui.tr("Grava às ", "Records at ") + Ui.time(p.start));
                } else if (set) {
                    RecordService.stop(c, LiveRecordings.idOf(channel.id, p.start));
                    flash(Ui.tr("Gravação parada", "Recording stopped"));
                } else {
                    RecordService.start(c, item(LiveSchedule.RECORD, channel, p));
                    flash(Ui.tr("Gravando até ", "Recording until ") + Ui.time(p.end));
                }
                marksAt = 0;
            });
        }
        if (onNow || later) {
            labels.add(Ui.tr("Todos os episódios…", "Every episode…"));
            icons.add("series");
            actions.add(() -> seriesMenu(channel, p));
        }
        boolean grouped = channel.group != null && LiveOptions.merge(c);
        if (show && asPage) {       // its page: watching it first, then what the sheet would offer
            if (onNow || p.end <= now && replayable(channel, p, now) || later) {
                labels.add(0, later ? Ui.tr("Assistir o canal", "Watch the channel") : Ui.tr("Assistir", "Watch"));
                icons.add(0, "play");
                actions.add(0, () -> {
                    playCat = gCat;
                    if (p.end <= now) live.playCatchup(channel, p); else live.play(channel);
                    closeGuide();
                });
            }
            openShowPage(channel, p, labels, icons, actions);
            return;
        }
        if (show) {     // its page, first in the sheet
            final List<String> pl = new ArrayList<>(labels), pi = new ArrayList<>(icons);
            final List<Runnable> pa = new ArrayList<>(actions);
            labels.add(0, Ui.tr("Sobre o programa", "About the show"));
            icons.add(0, "info");
            actions.add(0, () -> {
                if (onNow) {
                    pl.add(0, Ui.tr("Assistir", "Watch"));
                    pi.add(0, "play");
                    pa.add(0, () -> {
                        playCat = gCat;
                        live.play(channel);
                        closeGuide();
                    });
                }
                openShowPage(channel, p, pl, pi, pa);
            });
        }
        if (labels.isEmpty() && !grouped) {     // nothing for this show: the favorite, straight away (as on the logos)
            favoriteRow();
            return;
        }
        labels.add(Xtream.isFavorite(lineup, channel.id) ? Ui.tr("Tirar dos favoritos", "Remove from favorites")
                : Ui.tr("Pôr nos favoritos", "Add to favorites"));
        icons.add(Xtream.isFavorite(lineup, channel.id) ? "heart_off" : "heart");
        actions.add(this::favoriteRow);
        if (channel.id > 0) {      // for a channel Glassio's player can't play
            labels.add(Ui.tr("Abrir em outro player", "Open in another player"));
            icons.add("exit");
            actions.add(() -> live.openElsewhere(channel));
        }
        if (grouped) {      // a merged channel shown as its sources, or one of those merged again
            boolean merged = channel.sources != null;
            labels.add(merged ? Ui.tr("Mostrar as fontes separadas", "Show sources separately")
                    : Ui.tr("Juntar com os canais parecidos", "Merge with similar channels"));
            icons.add("swap");
            actions.add(() -> {
                LiveOptions.setSeparate(c, channel.group, channel.name, merged);
                flash(merged ? Ui.tr("Fontes separadas: ", "Sources shown separately: ") + channel.name
                        : Ui.tr("Juntado de novo", "Merged again"));
                live.relist();
            });
        }
        LiveSheet.menu(c)
                .setTitle(p == null || p.title.isEmpty() ? channel.name : p.title)
                .setIcons(icons.toArray(new String[0]))
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    actions.get(which).run();
                    invalidate();
                })
                .show();
    }

    // MARK: - Recording from the player

    /** The show on now on the channel playing live (what Record records), or null. */
    Xtream.Program recordable() {
        Xtream.Channel ch = live.playing();
        if (ch == null || ch.id <= 0 || live.catchup() != null) return null;
        return onNow(ch, System.currentTimeMillis());
    }

    /** Whether the show on now is being recorded. */
    boolean recordingNow() {
        Xtream.Program p = recordable();
        return p != null && LiveRecordings.recording(getContext(), live.playing().id, p.start);
    }

    /** Record (the player's button, the remote's Record key): the show on now from now to its end, or stop it. */
    void toggleRecording() {
        Xtream.Program p = recordable();
        Xtream.Channel ch = live.playing();
        if (p == null) {
            flash(Ui.tr("Sem guia: não dá para saber até quando gravar", "No guide: there's no telling when the show ends"));
            return;
        }
        android.content.Context c = getContext();
        if (LiveRecordings.recording(c, ch.id, p.start)) {
            RecordService.stop(c, LiveRecordings.idOf(ch.id, p.start));
            flash(Ui.tr("Gravação parada", "Recording stopped"));
        } else {
            RecordService.start(c, item(LiveSchedule.RECORD, ch, p));
            flash(Ui.tr("Gravando até ", "Recording until ") + Ui.time(p.end));
        }
        marksAt = 0;
        postDelayed(overlay::refresh, 1500);     // its mark, once the recorder has started (or stopped)
    }

    /** Whether the player's bar seeks: a past program or a recording, or a live channel with catch-up (◀ goes back). */
    boolean canSeek() {
        Xtream.Channel ch = live.playing();
        return live.catchup() != null || live.shifted() || ch != null && ch.archive > 0;
    }

    /** ◀ ▶ on the player's bar: `ms` back or ahead; live, back into the catch-up (ahead is live already). */
    void seek(long ms) {
        if (live.catchup() != null || live.shifted()) {
            if (!live.skip(ms)) flash(Ui.tr("Este programa não deixa voltar nem avançar", "This program can't skip back or ahead"));
        } else if (ms < 0) {
            rewindLive();
        } else {
            flash(Ui.tr("Já está ao vivo", "You're watching it live"));
        }
    }

    /** ◀ on the player's bar on a live channel with catch-up: back into it, a little before now. */
    private void rewindLive() {
        Xtream.Channel ch = live.playing();
        if (ch == null || ch.archive <= 0) {
            flash(Ui.tr("Este canal não deixa voltar", "This channel can't rewind"));
            return;
        }
        long now = System.currentTimeMillis();
        Xtream.Program p = onNow(ch, now);
        if (p == null || p.start < catchupFrom(ch, now)) {
            flash(Ui.tr("Este programa não está na reprise", "This program isn't in the catch-up"));
            return;
        }
        live.playCatchup(ch, p, Math.max(0, now - p.start - SKIP_BACK));
    }

    private static LiveSchedule.Item item(String kind, Xtream.Channel channel, Xtream.Program p) {
        LiveSchedule.Item it = new LiveSchedule.Item();
        it.kind = kind;
        it.ch = channel;
        it.start = p.start;
        it.end = p.end;
        it.title = p.title;
        it.about = p.about;
        return it;
    }

    // The guide's marks: a bell on the shows with a reminder, a red dot on the ones being (or to be) recorded.
    private final Set<String> reminded = new HashSet<>(), recorded = new HashSet<>();
    private long marksAt;

    /** The marks, again at most once a second (the schedule and the recordings are small files). */
    private void refreshMarks() {
        long now = SystemClock.uptimeMillis();
        if (now - marksAt < 1000) return;
        marksAt = now;
        reminded.clear();
        recorded.clear();
        for (LiveSchedule.Item it : LiveSchedule.all(getContext())) {
            (LiveSchedule.reminder(it) ? reminded : recorded).add(it.ch.id + ":" + it.start);
        }
        for (LiveRecordings.Rec r : LiveRecordings.all(getContext())) {
            if (LiveRecordings.RECORDING.equals(r.state)) recorded.add(r.id.substring(4));     // "rec:" + channel:start
        }
    }

    /** OK on a channel in the list acts when let go (it plays); held, its menu: the favorite, out of History. */
    private boolean channelOk(KeyEvent e, boolean down) {
        if (down) {
            if (e.getRepeatCount() == 0) {
                okDown = true;
                okHeld = false;
            } else if (okDown && !okHeld) {
                okHeld = true;
                channelMenu();
            }
            return true;
        }
        boolean act = okDown && !okHeld && !e.isCanceled();
        okDown = okHeld = false;
        if (act) {
            listKey(e.getKeyCode());
            invalidate();
        }
        return true;
    }

    private void channelMenu() {
        List<Xtream.Channel> list = channels();
        if (ch >= list.size()) return;
        channelMenu(list.get(ch), lineup.categories.get(cat) == history);
    }

    /** A channel's choices: favorite, rename, renumber (the provider's again), out of History. */
    private void channelMenu(final Xtream.Channel channel, final boolean inHistory) {
        final android.content.Context c = getContext();
        final boolean edited = LiveChannelEdits.edited(c, channel.id);
        final List<String> labels = new ArrayList<>(), icons = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();
        boolean fav = Xtream.isFavorite(lineup, channel.id);
        labels.add(fav ? Ui.tr("Tirar dos favoritos", "Remove from favorites") : Ui.tr("Pôr nos favoritos", "Add to favorites"));
        icons.add(fav ? "heart_off" : "heart");
        acts.add(() -> toggleFavorite(channel));
        labels.add(Ui.tr("Renomear", "Rename"));
        icons.add("edit");
        acts.add(() -> LiveNamePad.ask(c, Ui.tr("Nome do canal", "Channel name"), channel.name, name -> {
            if (name.isEmpty()) return;
            LiveChannelEdits.setName(c, channel.id, name);
            flash(Ui.tr("Agora se chama ", "Now called ") + name);
            live.relist();
        }));
        labels.add(Ui.tr("Mudar o número", "Change number"));
        icons.add("list");
        acts.add(() -> LivePinPad.askNumber(c, Ui.tr("Número do canal", "Channel number"), Ui.tr("1 a 5 números", "1 to 5 digits"), typed -> {
            int n;
            try {
                n = Integer.parseInt(typed);
            } catch (NumberFormatException e) {
                return false;
            }
            if (n <= 0 || n > 99999) return false;
            LiveChannelEdits.setNumber(c, channel.id, n);
            flash(Ui.tr("Agora é o ", "Now number ") + n);
            live.relist();
            return true;
        }));
        if (edited) {
            labels.add(Ui.tr("Nome e número do provedor", "Provider's name and number"));
            icons.add("history");
            acts.add(() -> {
                LiveChannelEdits.clear(c, channel.id);
                flash(Ui.tr("Como o provedor o chama", "As your provider calls it"));
                live.relist();
            });
        }
        if (inHistory) {
            labels.add(Ui.tr("Tirar do Histórico", "Remove from History"));
            icons.add("delete");
            acts.add(() -> {
                history.channels.remove(channel);       // at once; the file after
                final android.content.Context app = c.getApplicationContext();
                epgIo.execute(() -> Xtream.removeRecent(app, channel.id));
                flash(Ui.tr("Tirado do Histórico", "Removed from History"));
            });
        }
        LiveSheet.menu(c)
                .setIcons(icons.toArray(new String[0]))
                .setTitle((channel.num > 0 ? channel.num + "  " : "") + channel.name)
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    acts.get(which).run();
                    if (mode == LIST) {
                        ch = Math.min(ch, Math.max(0, channels().size() - 1));
                        keepVisible();
                        aimList(false);
                    }
                    invalidate();
                })
                .show();
    }

    /** OK on a recording acts when let go (it plays); held, its menu: stop it (while recording), delete it. */
    private boolean recordingOk(KeyEvent e, boolean down) {
        if (down) {
            if (e.getRepeatCount() == 0) {
                okDown = true;
                okHeld = false;
            } else if (okDown && !okHeld) {
                okHeld = true;
                recordingMenu();
            }
            return true;
        }
        boolean act = okDown && !okHeld && !e.isCanceled();
        okDown = okHeld = false;
        if (act) {
            listKey(e.getKeyCode());
            invalidate();
        }
        return true;
    }

    private void recordingMenu() {
        List<Xtream.Channel> list = channels();
        LiveRecordings.Rec r = ch < list.size() ? recById.get(list.get(ch).id) : null;
        if (r == null) return;
        boolean on = LiveRecordings.RECORDING.equals(r.state);
        List<String> labels = new ArrayList<>();
        if (on) labels.add(Ui.tr("Parar de gravar", "Stop recording"));
        labels.add(Ui.tr("Apagar", "Delete"));
        LiveSheet.menu(getContext())
                .setIcons(on ? new String[] {"stop", "delete"} : new String[] {"delete"})
                .setTitle(r.title.isEmpty() ? r.ch.name : r.title)
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    if (on && which == 0) {
                        RecordService.stop(getContext(), r.id);
                        flash(Ui.tr("Gravação parada", "Recording stopped"));
                    } else {
                        if (on) RecordService.stop(getContext(), r.id);
                        final android.content.Context app = getContext().getApplicationContext();
                        epgIo.execute(() -> LiveRecordings.delete(app, r.id));
                        flash(Ui.tr("Gravação apagada", "Recording deleted"));
                    }
                    postDelayed(() -> {
                        recStamp = -1;
                        refreshRecordings();
                        ch = Math.min(ch, Math.max(0, channels().size() - 1));
                        keepVisible();
                        aimList(false);
                        invalidate();
                    }, 600);
                })
                .show();
    }

    /** The focused row's channel in or out of the favorites (the row may go, in Favorites). */
    private void favoriteRow() {
        List<Xtream.Channel> rows = guideChannels();
        if (gRow >= rows.size()) return;
        toggleFavorite(rows.get(gRow));
        rows = guideChannels();
        if (rows.isEmpty()) {
            aimGuide(false);
            return;
        }
        gRow = Math.min(gRow, rows.size() - 1);
        guideMoved();
    }

    private void guideKey(int code) {
        if (gHead > 0) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT: gHead = 1; break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: gHead = 2; break;
                case KeyEvent.KEYCODE_DPAD_DOWN: gHead = 0; break;
                case KeyEvent.KEYCODE_DPAD_UP: break;
                default:
                    if (ok(code)) {
                        int which = gHead;
                        gHead = 0;
                        if (which == 1) openGuideGroups(); else timeMenu();
                        invalidate();
                        return;
                    }
                    break;
            }
            if (gHead > 0) announceForAccessibility(headName());
            invalidate();
            return;
        }
        List<Xtream.Channel> rows = guideChannels();
        long now = System.currentTimeMillis();
        if (code == KeyEvent.KEYCODE_DPAD_UP && (rows.isEmpty() || gRow == 0)) {      // above the top row: the header
            gHead = 1;
            announceForAccessibility(headName());
            invalidate();
            return;
        }
        if (rows.isEmpty()) {
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) openGuideGroups();
            return;
        }
        List<Xtream.Program> list = gRow < rows.size() ? programs(rows.get(gRow)) : null;
        int at = programAt(list, gTime);
        long floor = guideFloor(rows.get(gRow), now);
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: gRow = Math.max(0, gRow - 1); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: gRow = Math.min(rows.size() - 1, gRow + 1); break;
            case KeyEvent.KEYCODE_CHANNEL_UP: gRow = Math.max(0, gRow - G_ROWS); break;
            case KeyEvent.KEYCODE_CHANNEL_DOWN: gRow = Math.min(rows.size() - 1, gRow + G_ROWS); break;
            case KeyEvent.KEYCODE_DPAD_LEFT: {
                // Back along the row to the show on now, then on into the past while the channel's catch-up has it
                // (TiviMate's, Sky's); in the past, on as far as the guide goes. Without catch-up, from now, the groups.
                boolean past = inPast();
                Xtream.Program prev = list != null && at > 0 ? list.get(at - 1) : null;
                boolean intoCatchup = !past && prev != null && prev.end <= now && replayable(rows.get(gRow), prev, now);
                if (gCol == 1 && prev != null && prev.end > floor && (past || prev.end > now || intoCatchup)) {
                    gTime = Math.max(floor, prev.start);
                    if (intoCatchup) flash(Ui.tr("Reprise: ◀ ▶ entre os programas, Voltar para agora", "Catch-up: ◀ ▶ through the shows, Back for now"));
                } else if (!past) {
                    openGuideGroups();
                    return;
                }
                break;
            }
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (gCol == 0) {
                    gCol = 1;
                    gTime = now;
                } else if (list != null && at >= 0 && at + 1 < list.size()) {
                    gTime = list.get(at + 1).start;
                }
                break;
            case KeyEvent.KEYCODE_INFO:     // on a show: its page
                if (gCol == 1 && at >= 0) {
                    heldOk(true);
                    return;
                }
                openCategories();
                return;
            case KeyEvent.KEYCODE_MENU:
                openCategories();
                return;
            case KeyEvent.KEYCODE_GUIDE:    // the remote's Guide button again: back to the show
                if (live.playing() != null) closeGuide();
                return;
            case KeyEvent.KEYCODE_PROG_RED:     // TiviMate's: red the favorite, green the categories, blue the settings
                favoriteRow();
                return;
            case KeyEvent.KEYCODE_PROG_GREEN:
                openCategories();
                return;
            case KeyEvent.KEYCODE_PROG_BLUE:
                openSettings();
                return;
            case KeyEvent.KEYCODE_PROG_YELLOW:
                return;
            default:
                if (gCol == 0) {    // OK on the logo: its choices (favorite, rename, renumber)
                    channelMenu(rows.get(gRow), false);
                    return;
                }
                Xtream.Program p = list != null && at >= 0 ? list.get(at) : null;
                if (p != null && p.end <= now) {   // a program that's over: from the catch-up
                    if (!replayable(rows.get(gRow), p, now)) {
                        flash(Ui.tr("Este programa não está na reprise", "This program isn't in the catch-up"));
                        return;
                    }
                    playCat = gCat;
                    live.playCatchup(rows.get(gRow), p);
                    closeGuide();
                    return;
                }
                Xtream.Channel row = rows.get(gRow);
                if (p != null && p.start > now) {     // a show later on: remind, record, or the channel now
                    laterMenu(row, p, () -> {
                        playCat = gCat;
                        live.play(row);
                        closeGuide();
                    });
                    return;
                }
                playCat = gCat;     // OK on a program: that channel, full screen
                live.play(row);
                closeGuide();
                return;
        }
        guideMoved();
    }

    /**
     * After the focus moved: never before what the row allows (now, or its catch-up), the time line following in
     * half-hour steps (not before the row's first half hour), the rows scrolled to it.
     */
    private void guideMoved() {
        List<Xtream.Channel> rows = guideChannels();
        if (rows.isEmpty()) return;
        long now = System.currentTimeMillis(), floor = guideFloor(rows.get(gRow), now);
        if (gTime < floor) gTime = floor;
        long firstHalf = floor - floor % HALF_HOUR;
        while (gTime >= gStart + (long) G_MINUTES * 60_000L - HALF_HOUR) gStart += HALF_HOUR;
        while (gTime < gStart && gStart > firstHalf) gStart -= HALF_HOUR;
        if (gStart < firstHalf) gStart = firstHalf;
        gTop = scroll(gRow, gTop, G_ROWS, rows.size());
        aimGuide(false);
        announceGuide();
    }

    /** TalkBack reads the focused channel and program. */
    private void announceGuide() {
        List<Xtream.Channel> rows = guideChannels();
        if (gRow >= rows.size()) return;
        Xtream.Channel channel = rows.get(gRow);
        List<Xtream.Program> list = programs(channel);
        int i = programAt(list, gCol == 1 ? gTime : System.currentTimeMillis());
        String text = (channel.num > 0 ? channel.num + " " : "") + channel.name;
        if (i >= 0) {
            Xtream.Program p = list.get(i);
            text += ", " + Ui.time(p.start) + " – " + Ui.time(p.end) + ", " + p.title;
        }
        announceForAccessibility(text);
    }

    // MARK: - Helpers

    private List<Xtream.Channel> channels() {
        if (lineup == null || cat < 0 || cat >= lineup.categories.size()) return Collections.emptyList();
        return lineup.categories.get(cat).channels;
    }

    private int catRows() {
        return (int) ((1080 - GR_TOP) / GR_STEP);
    }

    private int chRows() {
        return (int) ((1080 - C_TOP - 10) / C_STEP);
    }

    /** Scrolls each column so its focused row stays in view, a row in from the edges while there's more. */
    private void keepVisible() {
        catTop = scroll(cat, catTop, catRows(), lineup == null ? 0 : lineup.categories.size());
        chTop = scroll(ch, chTop, chRows(), channels().size());
    }

    private static int scroll(int focus, int top, int rows, int size) {
        if (focus < top + 1) top = focus - 1;
        else if (focus > top + rows - 2) top = focus - rows + 2;
        return Math.max(0, Math.min(Math.max(0, size - rows), top));
    }

    /** TalkBack reads the focused row. */
    private void announce() {
        if (lineup == null) return;
        String text;
        if (column == 0) {
            text = cat < 0 || cat >= lineup.categories.size() ? Ui.tr("Ajustes", "Settings")
                    : lineup.categories.get(cat).name + ", " + lineup.categories.get(cat).channels.size() + Ui.tr(" canais", " channels");
        } else {
            List<Xtream.Channel> list = channels();
            text = ch < list.size() ? (list.get(ch).num > 0 ? list.get(ch).num + " " : "") + list.get(ch).name : "";
        }
        if (!text.isEmpty()) announceForAccessibility(text);
    }

    private String ellipsize(String s, Paint p, float width) {
        fit.set(p);
        return TextUtils.ellipsize(s == null ? "" : s, fit, Math.max(1, width), TextUtils.TruncateAt.END).toString();
    }

    private static String initials(String name) {
        String n = name == null ? "" : name.replaceAll("[^\\p{L}\\p{N} ]", " ").trim();
        if (n.isEmpty()) return "TV";
        String[] words = n.split("\\s+");
        String s = words[0].substring(0, Math.min(2, words[0].length()));
        if (words.length > 1 && s.length() == 1) s += words[1].charAt(0);
        return s.toUpperCase(java.util.Locale.ROOT);
    }

    private Bitmap logo(String url) {
        return LiveLogos.get(getContext(), url, this::postInvalidate);
    }
}
