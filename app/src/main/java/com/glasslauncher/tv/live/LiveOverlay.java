package com.glasslauncher.tv.live;

import android.app.AlertDialog;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;

import java.util.List;

/**
 * What's playing, over the video: the channel and the clock at the top; at the bottom LIVE (or CATCH-UP), the score and
 * what the stream is, the show's big title, its times, a bar across the width and a row of named tabs (Info, Channels,
 * Audio & Subtitles, Moments, Options). What used to be buttons is on the tabs' cards (LivePlayerPanel) and the rail
 * (LiveRail). A new channel only brings a small banner; near a show's end, a card says what's next. Every design
 * shares this layout: its panel (card, band, shade), colors, focus, corners and lettering are its own.
 * It slides up and fades in on a spring, one focus glides between the tabs, and it goes by itself a few seconds after
 * the last key (not while a past program is paused, or a card is open).
 * Measurements in a 1920×1080 space (k = width / 1920).
 */
final class LiveOverlay {
    static final int PLAY = 1, LIVE = 4, START_OVER = 5, INFO = 6, CHANNELS = 7, AUDIO = 8, MOMENTS = 9, SKIP_BACK = 11, SKIP_AHEAD = 12, MATCH = 13;
    /** A past show's lines stand this much higher: the skip's label goes between them and the bar. */
    private static final float PAST_LIFT = 38;
    /** On a later show (◀ ▶ in the peek) it stays up longer, to read it (Back closes it). */
    private static final long PEEK_LATER_MS = 20_000;
    private static final long HIDE_MS = 6000, BRIEF_MS = 3500;
    private static final float X = 96, R = 1824, GAP = 12, TOP_Y = 668, TITLE_Y = 748, SUB_Y = 800, BAR_Y = 880,
            LABEL_Y = 920, ROW_Y = 976, TAB_H = 64;
    private static final int INK = 0xFF1C1C1E, WHITE = 0xFFF5F5F7, RED = 0xFFD92D20;
    private static final int SCORE = 0xE6202A36;       // the score's pill: dark, the white numbers on it
    private static final int MARK = 0xFFFFB340;        // a goal or a card on the bar

    private final LiveView view;
    private final Live live;
    private final Marquee titleMarquee = new Marquee();
    private Xtream.Channel healthFor;
    private Xtream.Program scoreFor;
    private String scoreTitle = "", scoreLine;
    private long scoreAt;
    private long healthAt;
    private boolean unstable;
    private Xtream.Program ratedFor;
    private int ratedAge = -1;
    private long ratedAt;
    private final Spring appear = new Spring(0.32f, 1f, 0), focusX = new Spring(0.22f, 0.86f, 0), focusW = new Spring(0.22f, 0.86f, 120);
    private final int[] buttons = new int[8];
    private int count, focus;
    /** The first tab at the row's right end (Start over, Back to live), `count` for none. */
    private int rightFrom;
    /** Just tuned: the small banner, not the whole overlay. */
    private boolean brief;
    private final LivePlayerPanel panel;
    private final LivePlayerMoments moments;
    private final LivePlayerMatch centre;
    private final LivePlayerInfo info;
    private final LiveRail rail;
    /** The match the show is (LiveMoments), found by its title; which show was asked to show it. */
    private LiveMoments.Match match;
    private Xtream.Program matchFor;
    private long matchAt;
    private String revealed = "";
    private Paint bubble, thumbTime, upTitle;
    /** On the Score hidden button, above the bar. */
    private boolean onScore;
    /** A stand-in match (the test broadcast's), instead of ESPN's. */
    private LiveMoments.Match testMatch;
    /** The Up next card: the show it's for, the show it was closed on, what else starts then; the check's running. */
    private Xtream.Program cardFor;
    private String cardClosed = "";
    /** Another show starting then: where it's on, and why it's offered. */
    private static final class Also {
        final Xtream.Channel channel;
        final String title, why;

        Also(Xtream.Channel channel, String title, String why) {
            this.channel = channel;
            this.title = title;
            this.why = why;
        }
    }

    private final java.util.ArrayList<Also> alsoOn = new java.util.ArrayList<>();
    /** On the card: the tiles, then Stay here, then What else is on. */
    private int cardFocus;
    /** The card as if a show were ending (the test broadcast's). */
    private boolean testCard;
    private Shader cardShade;
    private Paint alsoHead, alsoWhy, alsoTitle, cardButton, cardMeta;
    private boolean checking;
    private long lastKey;
    /** Showing; and its buttons taking keys (shown by OK: not just after a channel changed). */
    private boolean showing, armed;
    /** The focus on the progress bar (◀ ▶ seek there; ↓ goes to the buttons, ↑ back), not on a button. */
    private boolean onBar;
    /** Another channel's info (↑ ↓ while watching, TiviMate's way): OK tunes it; null for what's playing. */
    private Xtream.Channel peek;
    /** In the peek (Sky's mini guide): how many shows after the one on now it shows (◀ ▶), 0 the one on now. */
    private int peekAhead;

    private float k = 1;
    private int w, h;
    private Paint title, meta, small, badge, clockPaint, fill, glow, cardTitle;
    private TextPaint aboutPaint;
    private final TextPaint fit = new TextPaint();
    private Shader topShade, bottomShade;
    private StaticLayout about;
    private String aboutText = "";
    private final RectF r = new RectF(), box = new RectF();
    private Paint stroke;
    private Shader band, side;
    private final RectF plate = new RectF();

    LiveOverlay(LiveView view, Live live) {
        this.view = view;
        this.live = live;
        panel = new LivePlayerPanel(view);
        moments = new LivePlayerMoments(view);
        centre = new LivePlayerMatch(view);
        info = new LivePlayerInfo(view);
        rail = new LiveRail(view, live);
    }

    void size(float scale, int width, int height) {
        k = scale;
        w = width;
        h = height;
        panel.size(scale, width, height);
        moments.size(scale);
        centre.size(scale);
        info.size(scale, width, height);
        rail.size(scale);
        if (!checking) {
            checking = true;
            view.postDelayed(check, 5000);
        }
        title = Ui.text(Ui.displaySemibold, 72 * k, WHITE);
        cardTitle = Ui.text(Ui.displaySemibold, 42 * k, WHITE);
        // Glassio 3: the words stay readable over the brightest picture (with the stronger shade below).
        // Every design: solid white on a dark fade, each word with a soft shadow, to read over the brightest picture.
        meta = Ui.text(Ui.medium, 30 * k, 0xFFFFFFFF);
        small = Ui.text(Ui.medium, 26 * k, 0xFFFFFFFF);
        title.setLetterSpacing(0.008f);
        title.setShadowLayer(10 * k, 0, 3 * k, 0xB3000000);
        meta.setShadowLayer(7 * k, 0, 2 * k, 0xCC000000);
        small.setShadowLayer(7 * k, 0, 2 * k, 0xCC000000);
        badge = Ui.text(Ui.semibold, 22 * k, 0xFFFFFFFF);
        badge.setLetterSpacing(0.04f);
        bubble = Ui.text(Ui.semibold, 26 * k, INK);
        thumbTime = Ui.numbers(Ui.semibold, 26 * k, 0xFFFFFFFF);
        thumbTime.setTextAlign(Paint.Align.CENTER);
        thumbTime.setShadowLayer(7 * k, 0, 2 * k, 0xCC000000);
        upTitle = Ui.text(Ui.semibold, 26 * k, 0xFFFFFFFF);
        upTitle.setShadowLayer(7 * k, 0, 2 * k, 0xCC000000);
        clockPaint = Ui.numbers(Ui.displaySemibold, 46 * k, WHITE);
        clockPaint.setTextAlign(Paint.Align.RIGHT);
        clockPaint.setShadowLayer(8 * k, 0, 2 * k, 0xCC000000);
        aboutPaint = new TextPaint(Ui.text(Ui.regular, 26 * k, 0xFFFFFFFF));
        fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        glow.setColor(WHITE);
        glow.setShadowLayer(24 * k, 0, 8 * k, 0x80000000);
        stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        topShade = new LinearGradient(0, 0, 0, 240 * k, 0xD9000000, 0x00000000, Shader.TileMode.CLAMP);
        cardShade = new LinearGradient(0, 480 * k, 0, h, new int[] {0x00000000, 0xB8000000, 0xEB000000},
                new float[] {0, 0.5f, 1}, Shader.TileMode.CLAMP);
        alsoHead = Ui.text(Ui.semibold, 26 * k, WHITE);
        alsoHead.setShadowLayer(7 * k, 0, 2 * k, 0xCC000000);
        alsoWhy = Ui.text(Ui.semibold, 22 * k, WHITE);
        alsoTitle = Ui.text(Ui.semibold, 26 * k, WHITE);
        cardButton = Ui.text(Ui.semibold, 26 * k, WHITE);
        cardButton.setTextAlign(Paint.Align.CENTER);
        cardMeta = Ui.text(Ui.medium, 25 * k, 0xFFFFFFFF);
        // The fade under the text, into the style's own background (black for Glassio's).
        int base = LiveTheme.bg & 0x00FFFFFF;
        // Dark well before the pills start (about y 640), nearly solid under the tabs.
        bottomShade = new LinearGradient(0, 480 * k, 0, h, new int[] {base, 0xE6000000 | base, 0xF5000000 | base, 0xFC000000 | base},
                new float[] {0, 0.25f, 0.55f, 1}, Shader.TileMode.CLAMP);
        // Sky Q's band: the style's blue, solid from just above the title.
        band = new LinearGradient(0, 590 * k, 0, h, (LiveTheme.bgTop & 0x00FFFFFF) | 0xF0000000,
                (LiveTheme.bg & 0x00FFFFFF) | 0xFA000000, Shader.TileMode.CLAMP);
        // Netflix's shade: the design's background from the left, so the text there reads over any picture; it fades
        // in from the top as the fade under the text does.
        side = new android.graphics.ComposeShader(
                new LinearGradient(0, 0, w * 0.62f, 0, (LiveTheme.bg & 0x00FFFFFF) | 0xE6000000, LiveTheme.bg & 0x00FFFFFF,
                        Shader.TileMode.CLAMP),
                new LinearGradient(0, 480 * k, 0, 720 * k, 0x00000000, 0xFF000000, Shader.TileMode.CLAMP),
                android.graphics.PorterDuff.Mode.DST_IN);
        about = null;
        aboutText = "";
    }

    // MARK: - Showing

    /**
     * Shows it (or keeps it up) for a few more seconds, its buttons for what's playing now. `user`: asked for (OK), so
     * the buttons take keys at once; shown by itself (a new channel), the first OK or ◀ ▶ only brings the focus.
     */
    void show(boolean user) {
        peek = null;
        boolean wasBrief = brief;
        brief = false;
        rebuild();
        if (wasBrief) {        // from the small banner: as if it had just come up
            showing = false;
        }
        if (!showing) {
            focus = count > 1 && buttons[1] == SKIP_AHEAD ? 1 : 0;
            focusX.snap(buttonX(focus));
            focusW.snap(buttonW(focus));
            armed = user;
            onBar = user && view.canSeek();     // OK: the focus on the bar first (YouTube's, Apple TV's)
        } else if (user && !armed) {
            armed = true;
            onBar = view.canSeek();
        }
        showing = true;
        appear.target = 1;
        lastKey = SystemClock.uptimeMillis();
        view.invalidate();
    }

    /** Just tuned: the small banner for a moment; the whole overlay stays if it's up and in use. */
    void brief() {
        if (showing && !brief) {
            show(false);
            return;
        }
        peek = null;
        brief = true;
        armed = false;
        showing = true;
        appear.target = 1;
        lastKey = SystemClock.uptimeMillis();
        view.invalidate();
    }

    /** The buttons again (the tracks changed), without keeping it up longer. */
    void refresh() {
        if (!showing) return;
        rebuild();
        view.invalidate();
    }

    /** Shows `ch`'s info (on now, next), without its buttons, for a few seconds: OK tunes it. */
    void peek(Xtream.Channel ch) {
        peek = ch;
        brief = false;
        peekAhead = 0;
        armed = false;
        showing = true;
        appear.target = 1;
        lastKey = SystemClock.uptimeMillis();
        view.invalidate();
    }

    /** ◀ ▶ in the peek: the channel's later shows (back to the one on now); false when there are none that way. */
    boolean peekStep(int dir) {
        if (!showing || peek == null) return false;
        List<Xtream.Program> list = view.programsOf(peek);
        int now = nowIndex(list);
        if (now < 0) return false;
        int to = Math.max(0, Math.min(list.size() - 1 - now, peekAhead + dir));
        lastKey = SystemClock.uptimeMillis();
        if (to == peekAhead) return false;
        peekAhead = to;
        Xtream.Program p = list.get(now + to);
        LiveSpeech.say(view, Ui.time(p.start) + ", " + p.title);
        view.invalidate();
        return true;
    }

    /** The show the peek is on (on now, or a later one with ◀ ▶), or null. */
    Xtream.Program peekProgram() {
        return showing ? peekShow() : null;
    }

    /** The peek's show, also while it fades out (what's drawn until it's gone). */
    private Xtream.Program peekShow() {
        if (peek == null) return null;
        List<Xtream.Program> list = view.programsOf(peek);
        int now = nowIndex(list);
        return now < 0 || now + peekAhead >= list.size() ? null : list.get(now + peekAhead);
    }

    private static int nowIndex(List<Xtream.Program> list) {
        if (list == null) return -1;
        long now = System.currentTimeMillis();
        for (int i = 0; i < list.size(); i++) if (list.get(i).start <= now && list.get(i).end > now) return i;
        return -1;
    }

    /** The channel shown instead of what's playing (↑ ↓), or null. */
    Xtream.Channel peeking() {
        return showing ? peek : null;
    }

    /** Its buttons take keys (OK brought it). */
    boolean armed() {
        return showing && armed && peek == null;
    }

    void hide() {
        // The peek stays until it has faded out (step): cleared now, the fade showed what's playing instead, its
        // buttons and all, turning see-through over the show you were reading.
        showing = false;
        appear.target = 0;
        panel.close();
        moments.close();
        centre.close();
        info.close();
        rail.close();
        onScore = false;
        view.invalidate();
    }

    /** Back: an open card or the rail closes, back to the tabs; else it all goes. */
    void back() {
        lastKey = SystemClock.uptimeMillis();
        if (panel.open()) panel.close();
        else if (moments.open()) moments.close();
        else if (centre.open()) centre.close();
        else if (info.open()) info.close();
        else if (rail.open()) rail.close();
        else hide();
        view.invalidate();
    }

    boolean visible() {
        return showing;
    }

    /** The whole overlay is up (not the small banner): what's over the video moves up out of its way. */
    boolean tall() {
        return showing && !brief;
    }

    boolean step(float dt) {
        long hold = brief ? BRIEF_MS : peek != null && peekAhead > 0 ? PEEK_LATER_MS : HIDE_MS;
        boolean paused = !brief && (live.catchup() != null || live.shifted()) && live.paused();
        if (showing && SystemClock.uptimeMillis() - lastKey > hold && !paused && !panel.open() && !moments.open() && !centre.open() && !info.open() && !rail.open()) hide();
        boolean moving = appear.step(dt) | focusX.step(dt) | focusW.step(dt) | panel.step(dt) | moments.step(dt) | centre.step(dt) | info.step(dt) | rail.step(dt);
        if (!showing && !moving && appear.value <= 0.004f) {     // gone (settled, not on a bounce): the peek with it
            peek = null;
            peekAhead = 0;
            brief = false;
        }
        return moving;
    }

    /** The tabs for what's playing, then at the right end Start over, or Back to live on a past program. */
    private void rebuild() {
        Xtream.Channel ch = live.playing();
        int[] row = tabs(live.catchup() != null || live.shifted(), view.canSeek(), ch != null, match != null && !match.moments.isEmpty(), ch != null && ch.archive > 0,
                match != null && !match.event.isEmpty() && LiveOptions.matchCentre(live));
        count = row.length;
        rightFrom = count;
        for (int i = 0; i < count; i++) {
            buttons[i] = row[i];
            if (row[i] == LIVE) rightFrom = i;      // at the far right
        }
        // The focus goes where its tab is now (the row changes between live and a past program).
        focus = Math.min(focus, count - 1);
        focusX.target = buttonX(focus);
        focusW.target = buttonW(focus);
    }

    /** The row of tabs: a past show has its own, the skips first, Back to live at the far right. Pure. */
    static int[] tabs(boolean past, boolean canSeek, boolean channel, boolean moments, boolean archive) {
        return tabs(past, canSeek, channel, moments, archive, false);
    }

    /** `centre`: the show is a match Match Centre can read. */
    static int[] tabs(boolean past, boolean canSeek, boolean channel, boolean moments, boolean archive, boolean centre) {
        int[] row = new int[7];
        int n = 0;
        if (past) {
            row[n++] = SKIP_BACK;
            row[n++] = SKIP_AHEAD;
            if (centre) row[n++] = MATCH;
            if (moments) row[n++] = MOMENTS;
            row[n++] = AUDIO;
            row[n++] = LIVE;
        } else {
            if (!canSeek && channel) row[n++] = PLAY;       // no bar to press OK on
            row[n++] = INFO;
            row[n++] = CHANNELS;
            row[n++] = AUDIO;
            if (centre) row[n++] = MATCH;
            if (moments) row[n++] = MOMENTS;
            if (archive) row[n++] = START_OVER;
        }
        return java.util.Arrays.copyOf(row, n);
    }

    private float buttonW(int i) {
        return pillWidth(i);
    }

    private float buttonX(int i) {
        if (i >= rightFrom) {
            float x = R;
            for (int j = count - 1; j >= i; j--) x -= buttonW(j) + (j < count - 1 ? GAP : 0);
            return x;
        }
        float x = X;
        for (int j = 0; j < i; j++) x += buttonW(j) + GAP;
        return x;
    }

    private Paint pillText;

    /** A tab's width in the 1920 space: its longest name, so it doesn't change size as it changes state. */
    private float pillWidth(int i) {
        if (pillText == null) pillText = Ui.text(Ui.semibold, 29, WHITE);
        pillText.setTextSize(29);
        float wd = pillText.measureText(name(buttons[i], false));
        if (buttons[i] == PLAY) wd = Math.max(wd, pillText.measureText(name(PLAY, true)));
        return wd + 60;
    }

    /** A tab's name ([other]: its other state, for measuring). */
    private String name(int b, boolean other) {
        switch (b) {
            case INFO: return Ui.tr("Info", "Info");
            case CHANNELS: return Ui.tr("Canais", "Channels");
            case AUDIO: return Ui.tr("Áudio e legendas", "Audio & Subtitles");
            case MOMENTS: return Ui.tr("Momentos", "Moments");
            case MATCH: return Ui.tr("Partida", "Match");
            case SKIP_BACK: return "◀ " + (live.minuteSkips() ? "1 min" : "10 s");
            case SKIP_AHEAD: return (live.minuteSkips() ? "1 min" : "30 s") + " ▶";
            case PLAY: return live.paused() != other ? Ui.tr("Continuar", "Play") : Ui.tr("Pausar", "Pause");
            case LIVE: return Ui.tr("Voltar ao vivo", "Back to live");
            default: return Ui.tr("Começar do início", "Start over");
        }
    }

    // MARK: - Drawing

    /** True while it moves (and, while it shows, the clock and the progress do: a redraw each second). */
    boolean draw(Canvas c, long up) {
        long now = System.currentTimeMillis();
        float a = Ui.clamp(appear.value);
        if (a <= 0.004f) {
            drawUpNext(c, now);
            return false;
        }
        Xtream.Channel ch = peek != null ? peek : live.playing();
        if (ch == null) return false;
        Xtream.Program past = peek != null ? null : live.catchup();
        boolean behind = peek == null && live.shifted();     // live TV paused, or playing behind
        List<Xtream.Program> list = view.programsOf(ch);
        Xtream.Program onNow = null, next = null;
        if (list != null) {
            for (int i = 0; i < list.size(); i++) {
                Xtream.Program p = list.get(i);
                if (p.start <= now && p.end > now) {
                    onNow = p;
                    if (i + 1 < list.size()) next = list.get(i + 1);
                }
            }
        }
        Xtream.Program shown = past != null ? past : onNow;
        // The peek on a later show (◀ ▶): that one, and the one after it as up next.
        Xtream.Program later = peek != null && peekAhead > 0 ? peekShow() : null;
        if (later != null) {
            shown = later;
            int at = list.indexOf(later);
            next = at >= 0 && at + 1 < list.size() ? list.get(at + 1) : null;
        }
        int save = a < 0.995f ? c.saveLayerAlpha(0, 0, w, h, Math.round(255 * a)) : c.save();
        if (brief) {
            drawBrief(c, ch, shown, past != null, now, (1 - a) * (1 - a));
            c.restoreToCount(save);
            // Only a frame looks at the clock (step), and once the picture's on nothing else may ask for one.
            if (showing) view.postInvalidateDelayed(briefWake(lastKey, SystemClock.uptimeMillis()));
            return false;
        }
        if (info.open()) {
            info.draw(c);
            c.restoreToCount(save);
            if (showing) view.postInvalidateDelayed(1000 - now % 1000);
            return false;
        }
        if (panel.open()) {        // the video stays clear beside the card
            panel.draw(c);
            c.restoreToCount(save);
            if (showing) view.postInvalidateDelayed(1000 - now % 1000);
            return false;
        }
        if (centre.open()) {        // the top of the picture stays clear
            shade(c, bottomShade, 0, 240 * k, w, h);
            centre.draw(c);
            c.restoreToCount(save);
            if (showing) view.postInvalidateDelayed(1000 - now % 1000);
            return false;
        }
        if (moments.open()) {       // the picture stays clear above them
            shade(c, bottomShade, 0, 480 * k, w, h);
            drawPanel(c);
            long length = past != null ? live.length() : onNow != null ? onNow.end - onNow.start : 0;
            long at = past != null ? live.position() : onNow != null ? now - onNow.start - (behind ? live.behind() : 0) : 0;
            moments.draw(c, length > 0 ? Ui.clamp(at / (float) length) : 0, length);
            c.restoreToCount(save);
            if (showing) view.postInvalidateDelayed(1000 - now % 1000);
            return false;
        }
        if (past == null) shade(c, topShade, 0, 0, w, 240 * k);
        shade(c, bottomShade, 0, 480 * k, w, h);
        drawPanel(c);
        if (past == null) drawTop(c, ch, now);
        if (rail.open()) {
            rail.draw(c);
            c.restoreToCount(save);
            if (showing) view.postInvalidateDelayed(1000 - now % 1000);
            return false;
        }
        c.translate(0, (1 - a) * (1 - a) * 60 * k);

        // LIVE (or what it is instead), the score, then what the stream is.
        float raise = past != null ? PAST_LIFT : 0;
        float x = X * k, y = (TOP_Y - raise) * k;
        String pill = past == null ? Ui.tr("AO VIVO", "LIVE") : live.recorded() ? Ui.tr("GRAVADO", "RECORDED") : Ui.tr("REPRISE", "CATCH-UP");
        if (later != null) pill = Ui.tr("DEPOIS", "LATER");
        else if (live.paused() && peek == null) pill = Ui.tr("PAUSADO", "PAUSED");
        boolean lit = past == null && !behind && later == null && !live.paused();
        x = drawPill(c, pill, x, y, lit ? RED : past != null ? LiveTheme.tile : (LiveTheme.text & 0x00FFFFFF) | 0x59000000,
                past != null && !lit ? LiveTheme.ink : 0xFFFFFFFF, true) + 16 * k;
        if (past != null) {        // when it was, and where
            String when = LiveCatchup.dayName(past.start, now) + "  ·  " + view.nameWithCountry(ch);
            c.drawText(ellipsize(when, small, 760 * k), x, y, small);
            x += Math.min(760 * k, small.measureText(when)) + 16 * k;
        }
        // A followed team's match on live: its score (Live scores), looked up again every second.
        if (shown != scoreFor || up - scoreAt > 1000) {
            if (shown != scoreFor) scoreTitle = shown == null ? "" : Xtream.fold(shown.title);
            scoreFor = shown;
            scoreAt = up;
            LiveScores.Score s = past == null && later == null && LiveOptions.scores(live) ? LiveScores.get(live).forTitle(scoreTitle) : null;
            scoreLine = s == null || s.state.equals("pre") ? null : s.line();
        }
        if (shown != matchFor || up - matchAt > 5000) {        // the match this show is, for Moments
            boolean changed = shown != matchFor;
            matchFor = shown;
            matchAt = up;
            LiveMoments.Match found = testMatch != null ? testMatch : peek != null || later != null ? null
                    : LiveMoments.get(live, shown, () -> view.post(() -> {
                        matchAt = 0;        // found: looked at on the next frame, not in five seconds
                        refresh();
                    }));
            if (found != match || changed) {
                match = found;
                rebuild();
            }
        }
        boolean hidden = matchHidden(shown, past != null);
        if (past != null && match != null) {       // a button: OK shows it, and hides it again
            boolean on = armed() && onScore;
            x = drawPill(c, hidden ? Ui.tr("Placar escondido", "Score hidden") : match.result() + " · FT", x, y,
                    on ? LiveTheme.tile : SCORE, on ? LiveTheme.ink : 0xFFFFFFFF, true) + 14 * k;
        } else if (scoreLine != null) {
            x = drawPill(c, scoreLine, x, y, SCORE, true) + 14 * k;
        }
        if (shown != ratedFor || (ratedAge < 0 && up - ratedAt > 2000)) {     // its rating: the guide's, else TMDB's
            ratedFor = shown;
            ratedAt = up;
            ratedAge = LiveTmdb.rating(live, shown, null);
        }
        if (past == null && (ratedAge > 0 || ratedAge == LiveRating.NONE && shown != null && live.lockAge() > 0)) {
            x = drawPill(c, LiveRating.label(ratedAge, true), x, y, LiveTheme.chip(), false) + 10 * k;     // "12+"; "No rating" under the lock
        }
        // Trouble first (it matters more than the quality tags), then the tags that fit.
        if (ch != healthFor || up - healthAt > 3000) {
            healthFor = ch;
            healthAt = up;
            unstable = ch.id > 0 && LiveHealth.recent(live, ch.id, 10 * 60_000L) > 0;
        }
        if (unstable && peek == null) x = drawPill(c, Ui.tr("INSTÁVEL", "UNSTABLE"), x, y, 0xCCC77800, false) + 10 * k;
        if (peek == null && past == null) {        // what the stream is, in words
            String tech = TextUtils.join("  ·  ", live.badges());
            if (x + small.measureText(tech) <= R * k) c.drawText(tech, x + 2 * k, y, small);
        }

        // The show's title, big; a long one scrolls across.
        String head = shown == null || shown.title.isEmpty() ? ch.name : shown.title;
        titleMarquee.draw(c, head, X * k, (TITLE_Y - raise) * k, (R - X) * k, title, k);

        // How far it is: the show on now, or the past show's position.
        float f = 0;
        long at = 0, length = 0;
        String rightText = "", startText = "", endText = "";
        if (past != null) {
            at = live.position();
            length = live.length();
            f = length > 0 ? Ui.clamp(at / (float) length) : 0;
            rightText = Math.max(0, at) / 60_000 + Ui.tr(" min vistos", " min in") + "  ·  " + LiveView.minutesLeft((int) Math.max(0, (length - at) / 60_000));
            startText = Ui.time(past.start);
            endText = Ui.time(past.end);
        } else if (behind) {        // the buffer from its oldest to now; how far behind live
            at = live.position();
            length = live.length();
            f = length > 0 ? Ui.clamp(at / (float) length) : 0;
            rightText = live.paused() && live.behind() < 2000 ? Ui.tr("Pausado", "Paused")
                    : "−" + LiveView.clock(live.behind()) + Ui.tr(" do ao vivo", " behind live");
        } else if (later != null) {      // a later show: when it starts
            rightText = startsIn(later.start, now);
        } else if (onNow != null) {
            f = Ui.clamp((now - onNow.start) / (float) (onNow.end - onNow.start));
            rightText = LiveView.minutesLeft((int) Math.max(1, Math.round((onNow.end - now) / 60_000.0)));
            startText = Ui.time(onNow.start);
            endText = Ui.time(onNow.end);
            at = now - onNow.start;
            length = onNow.end - onNow.start;
        }

        // Under the title: the episode, the times, how long is left, the channel's words about a later show.
        LiveEpisode ep = LiveEpisode.of(live, shown, true, view::postInvalidate);
        StringBuilder sub = new StringBuilder();
        if (ep != null && !ep.label().isEmpty()) sub.append(ep.label()).append("  ·  ");
        if (shown != null && past == null) sub.append(Ui.time(shown.start)).append(" – ").append(Ui.time(shown.end));
        if (!rightText.isEmpty()) sub.append(sub.length() > 0 ? "  ·  " : "").append(rightText);
        c.drawText(ellipsize(sub.toString(), meta, (R - X) * k), X * k, (SUB_Y - raise) * k, meta);

        // The bar, its marks, the place in it, and where stacked skips will land.
        long target = peek == null ? live.skipTarget() : -1;
        if (target >= 0) {      // while skips are stacked the thumb stays where it is; the ring goes
            at = live.skipStart();
            f = length > 0 ? Ui.clamp(at / (float) length) : 0;
        }
        float barY = BAR_Y * k, barL = X * k, barR = R * k;
        boolean focused = armed() && onBar;
        float half = 5 * k;
        fill.setColor(0x42F5F5F7);
        c.drawRoundRect(barL, barY - half, barR, barY + half, half, half, fill);
        if (later == null && (past != null || behind || onNow != null)) {
            float end = barL + (barR - barL) * f;
            fill.setColor(LiveTheme.text);
            c.drawRoundRect(barL, barY - half, Math.max(barL + 2 * half, end), barY + half, half, half, fill);
            if (match != null && !hidden && length > 0 && peek == null) {
                fill.setColor(MARK);
                for (LiveMoments.Moment m : match.moments) {
                    if (m.at > length) continue;
                    float mx = barL + (barR - barL) * m.at / length;
                    c.drawRoundRect(mx - 6 * k, barY - 12 * k, mx + 6 * k, barY + 12 * k, 6 * k, 6 * k, fill);
                }
            }
            if (target >= 0 && length > 0) drawSpan(c, target, length, end, barL, barR, barY);
            fill.setColor(WHITE);
            glow.setColor(WHITE);       // the tabs leave it in the focus color
            if (focused) c.drawCircle(end, barY, 16 * k, glow);
            else c.drawCircle(end, barY, 13 * k, fill);
            if (target >= 0 && length > 0) {
                drawTarget(c, target, at, length, past, barL, barR, barY);
            } else if (past != null) {
                drawBubble(c, Ui.tr("Agora", "Now") + " " + Ui.time(past.start + at), end, barL, barR, barY);
            } else if (peek == null) {       // the time at the thumb
                float tx = Math.max(barL + 60 * k, Math.min(barR - 60 * k, end));
                c.drawText(Ui.time(now - (behind ? live.behind() : 0)), tx, barY - 26 * k, thumbTime);
            }
        }
        small.setTextAlign(Paint.Align.LEFT);
        float labelY = LABEL_Y * k;
        if (!startText.isEmpty()) c.drawText(startText, barL, labelY, small);
        if (peek == null) {
            String hint = target >= 0 ? Ui.tr("Parou de apertar? Vai para aqui em 1 s", "Stopped pressing? It jumps here in 1 s")
                    : past != null || behind ? Ui.tr("◀ ▶ some os saltos", "◀ ▶ add up skips")
                    : focused ? Ui.tr("◀ Voltar", "◀ Rewind") : "";
            small.setTextAlign(Paint.Align.CENTER);
            c.drawText(hint, (barL + barR) / 2, labelY, small);
        }
        small.setTextAlign(Paint.Align.RIGHT);
        if (!endText.isEmpty()) c.drawText(endText, barR, labelY, small);
        small.setTextAlign(Paint.Align.LEFT);

        if (peek == null) {
            drawTabs(c, past == null && !behind ? next : null);
        } else {        // another channel's info (Sky's mini guide): OK tunes it; ◀ ▶ its later shows
            String hint = later != null ? Ui.tr("OK para lembrar ou gravar   ·   ◀ ▶ outros programas", "OK to remind or record   ·   ◀ ▶ other shows")
                    : Ui.tr("OK para assistir   ·   ▶ mais tarde", "OK to watch   ·   ▶ later shows");
            drawAbout(c, shown, ep);
            c.drawText(hint, X * k, 1036 * k, small);
        }
        c.restoreToCount(save);
        if (showing) view.postInvalidateDelayed(1000 - now % 1000);
        return false;
    }

    /** The channel at the top left, the clock and what's going on (recording, the sleep timer) at the top right. */
    private void drawTop(Canvas c, Xtream.Channel ch, long now) {
        box.set(X * k, 44 * k, (X + 124) * k, 106 * k);
        view.drawLogo(c, ch, box, 1, false, true);
        String line = (ch.num > 0 ? ch.num + "  " : "") + view.nameOf(ch);
        drawNameAndTag(c, line, view.tagOf(ch), (X + 144) * k, 86 * k, 900 * k);
        c.drawText(Ui.time(now), (1920 - X) * k, 92 * k, clockPaint);
        float end = (1920 - X) * k - clockPaint.measureText(Ui.time(now)) - 26 * k;
        if (view.recordingNow()) {
            String rec = Ui.tr("GRAVANDO", "REC");
            float pw = badge.measureText(rec) + 54 * k;
            r.set(end - pw, 54 * k, end, 90 * k);
            fill.setColor((LiveTheme.card & 0x00FFFFFF) | 0xE6000000);
            c.drawRoundRect(r, 18 * k, 18 * k, fill);
            fill.setColor(RED);
            c.drawCircle(r.left + 22 * k, r.centerY(), 8 * k, fill);
            c.drawText(rec, r.left + 40 * k, r.centerY() + Ui.halfCap(badge), badge);
        }
        long sleep = view.sleepAt();
        if (sleep > 0) {
            small.setTextAlign(Paint.Align.RIGHT);
            c.drawText(Ui.tr("Dorme às ", "Sleeps at ") + Ui.time(sleep), (1920 - X) * k, 132 * k, small);
            small.setTextAlign(Paint.Align.LEFT);
        }
    }

    /** The stretch the stacked skips cover, lighter than what's been watched. */
    private void drawSpan(Canvas c, long target, long length, float end, float barL, float barR, float barY) {
        float tx = barL + (barR - barL) * Ui.clamp(target / (float) length);
        fill.setColor(0x80F5F5F7);
        c.drawRect(Math.min(end, tx), barY - 5 * k, Math.max(end, tx), barY + 5 * k, fill);
    }

    /** Where the skips pressed so far will land: a ring on the bar and, over it, by how much and when. */
    private void drawTarget(Canvas c, long target, long at, long length, Xtream.Program past, float barL, float barR, float barY) {
        float tx = barL + (barR - barL) * Ui.clamp(target / (float) length);
        stroke.setStrokeWidth(4 * k);
        stroke.setColor(WHITE);
        fill.setColor(INK);
        c.drawCircle(tx, barY, 16 * k, fill);
        c.drawCircle(tx, barY, 16 * k, stroke);
        String t = LiveCatchup.skipLabel(target, at) + (past == null ? "" : " · " + Ui.time(past.start + target));
        drawBubble(c, t, tx, barL, barR, barY);
    }

    /** A label over the bar at x, in the design's focus; it stays on the bar's width. */
    private void drawBubble(Canvas c, String t, float x, float barL, float barR, float barY) {
        float tw = bubble.measureText(t), pw = tw + 40 * k, ph = 52 * k;
        float left = Math.max(barL, Math.min(barR - pw, x - pw / 2)), top = barY - 97 * k;
        r.set(left, top, left + pw, top + ph);
        fill.setColor(LiveTheme.tile);
        float rr = LiveTheme.panelRound(14 * k);
        c.drawRoundRect(r, rr, rr, fill);
        bubble.setColor(LiveTheme.ink);
        c.drawText(t, left + 20 * k, r.centerY() + Ui.halfCap(bubble), bubble);
    }

    /** The peek has no tabs: its two lines about the show go there. */
    private void drawAbout(Canvas c, Xtream.Program shown, LiveEpisode ep) {
        String text = shown == null ? "" : ep == null ? shown.about : LiveEpisode.aboutWithout(shown.about, shown.sub);
        if (shown != null) text = LiveNames.about(live, shown.title, text);
        int aboutW = Math.max(1, Math.round((R - X) * k));
        if (!text.equals(aboutText) || about == null || about.getWidth() != aboutW) {
            aboutText = text;
            about = text.isEmpty() ? null : StaticLayout.Builder.obtain(text, 0, text.length(), aboutPaint,
                    aboutW).setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0, 1.05f).build();
        }
        if (about == null) return;
        c.save();
        c.translate(X * k, 930 * k);
        about.draw(c);
        c.restore();
    }

    /** How long until a banner shown at `shownAt` has had its time, and a little over. Pure. */
    static long briefWake(long shownAt, long now) {
        return Math.max(0, shownAt + BRIEF_MS - now) + 50;
    }

    /** Just tuned: a small card at the top left with the channel and its show, gone in a moment. */
    private void drawBrief(Canvas c, Xtream.Channel ch, Xtream.Program shown, boolean past, long now, float off) {
        float left = 72 * k, top = (56 - off * 40) * k, ht = 128 * k, textX = left + 204 * k;
        String name = (ch.num > 0 ? ch.num + "  " : "") + view.nameOf(ch);
        String show = shown == null ? "" : shown.title;
        String note = shown == null ? "" : past ? Ui.tr("REPRISE", "CATCH-UP")
                : LiveView.minutesLeft((int) Math.max(1, Math.round((shown.end - now) / 60_000.0)));
        String briefTag = view.tagOf(ch);
        LiveCountry.label(meta, tagLabel);
        float nameW = meta.measureText(name) + LiveCountry.tagWidth(briefTag, meta, tagLabel, k);
        float textW = Math.min(760 * k, Math.max(Math.max(nameW, meta.measureText(show)), small.measureText(note)));
        r.set(left, top, textX + textW + 36 * k, top + ht);
        float rr = LiveTheme.panelRound(24 * k);
        fill.setColor(LiveTheme.sheet());
        fill.setShadowLayer(26 * k, 0, 10 * k, 0x80000000);
        c.drawRoundRect(r, rr, rr, fill);
        fill.clearShadowLayer();
        if (LiveTheme.sheetRim() != 0) {
            stroke.setStrokeWidth(1.5f * k);
            stroke.setColor(LiveTheme.sheetRim());
            c.drawRoundRect(r, rr, rr, stroke);
        }
        box.set(left + 22 * k, top + 26 * k, left + 182 * k, top + 102 * k);
        view.drawLogo(c, ch, box, 1);
        if (show.isEmpty()) {       // no guide: the channel alone, in the middle
            drawNameAndTag(c, name, briefTag, textX, top + ht / 2 + Ui.halfCap(meta), textW);
            return;
        }
        drawNameAndTag(c, name, briefTag, textX, top + 42 * k, textW);
        c.drawText(ellipsize(show, meta, textW), textX, top + 80 * k, meta);
        c.drawText(ellipsize(note, small, textW), textX, top + 112 * k, small);
    }

    /**
     * A fade over the video. A shader takes its paint's alpha, and `fill` keeps whatever was drawn last (the tabs'
     * faint light would leave the next frame's fade nearly gone), so it's made opaque first.
     */
    private void shade(Canvas c, Shader shader, float left, float top, float right, float bottom) {
        fill.setColor(0xFF000000);
        fill.setShader(shader);
        c.drawRect(left, top, right, bottom, fill);
        fill.setShader(null);
    }

    /** Whether the Up next card is on screen (nothing else is, and a show's about to end). */
    boolean cardShowing() {
        return cardFor != null && !showing && view.watching();
    }

    /** Back on the card: gone, for this show. */
    void closeCard() {
        if (cardFor != null) cardClosed = showKey(cardFor);
        cardFor = null;
        testCard = false;
        view.invalidate();
    }

    /** A place on the card's row after ◀ or ▶: the `tiles`, then its two buttons. Pure. */
    static int cardMove(int focus, int dir, int tiles) {
        return Math.max(0, Math.min(tiles + 1, focus + dir));
    }

    /** The same place on the card's row once there are more `tiles`: a button stays that button. Pure. */
    static int cardRefocus(int focus, int had, int tiles) {
        return focus >= had ? focus - had + tiles : focus;
    }

    /** ◀ ▶ and OK on the card; false for the rest, which do what they do while watching. */
    boolean cardKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                cardFocus = cardMove(cardFocus, code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1, alsoOn.size());
                view.invalidate();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (cardFocus < alsoOn.size()) {
                    Xtream.Channel to = alsoOn.get(cardFocus).channel;
                    closeCard();
                    view.tuneFromPlayer(to);
                } else if (cardFocus == alsoOn.size()) {
                    closeCard();
                } else {
                    cardChannels();
                }
                return true;
            default:
                return false;
        }
    }

    /** The card now, on whatever's on (the test broadcast's). */
    void testUpNext() {
        testCard = true;
        cardClosed = "";
        cardFor = dueCard(System.currentTimeMillis());
        view.invalidate();
    }

    /** What else is on, Favorites first. */
    void cardChannels() {
        closeCard();
        show(true);
        rail.show(true);
    }

    /** Every few seconds: whether the Up next card comes or goes. */
    private final Runnable check = new Runnable() {
        @Override public void run() {
            if (!view.isAttachedToWindow()) {
                checking = false;
                return;
            }
            Xtream.Program was = cardFor;
            cardFor = dueCard(System.currentTimeMillis());
            if (cardFor != was) view.invalidate();
            view.postDelayed(this, 5000);
        }
    };

    /** The show the card is for: on a channel watched live, in its last two minutes, with a show after it. */
    private Xtream.Program dueCard(long now) {
        Xtream.Channel ch = live.playing();
        if (ch == null || live.catchup() != null || live.shifted() || !LiveOptions.upNextCard(live) || !view.watching()) return null;
        List<Xtream.Program> list = view.programsOf(ch);
        if (list == null) return null;
        for (int i = 0; i + 1 < list.size(); i++) {
            Xtream.Program p = list.get(i);
            if (p.start > now || p.end <= now) continue;
            if (!testCard && !LiveRail.upNextDue(now, p.end) || showKey(p).equals(cardClosed)) return null;
            // Again until it has its three: the other channels' guides are still loading the first time.
            if (p != cardFor || alsoOn.size() < 3) findAlsoOn(list.get(i + 1), p != cardFor);
            return p;
        }
        return null;
    }

    /** Up to three other shows starting then: on a favorite channel first, then a recent one. */
    private void findAlsoOn(Xtream.Program next, boolean fresh) {
        int had = alsoOn.size();
        alsoOn.clear();
        Xtream.Lineup lineup = view.lineup();
        if (lineup != null && !lineup.categories.isEmpty()) {
            addAlso(lineup.categories.get(0).channels, next, Ui.tr("Favorito", "Favorite"));
        }
        addAlso(view.historyChannels(), next, Ui.tr("Recentes", "Recent"));
        cardFocus = fresh ? alsoOn.size() : cardRefocus(cardFocus, had, alsoOn.size());      // Stay here to begin with
        view.invalidate();
    }

    private void addAlso(List<Xtream.Channel> channels, Xtream.Program next, String why) {
        Xtream.Channel on = live.playing();
        for (int i = 0; i < channels.size() && i < 40 && alsoOn.size() < 3; i++) {
            Xtream.Channel ch = channels.get(i);
            if (on != null && ch.id == on.id || hasAlso(ch)) continue;
            List<Xtream.Program> shows = view.programsOf(ch);
            if (shows == null) continue;
            for (Xtream.Program p : shows) {
                if (Math.abs(p.start - next.start) > 5 * 60_000L) continue;
                alsoOn.add(new Also(ch, p.title, followed(p.title) ? Ui.tr("Seguindo", "Following") : why));
                break;
            }
        }
    }

    private boolean hasAlso(Xtream.Channel ch) {
        for (Also a : alsoOn) if (a.channel.id == ch.id) return true;
        return false;
    }

    private boolean followed(String title) {
        String folded = Xtream.fold(title);
        for (String term : LiveFollow.all(live)) if (LiveNames.of(live, Xtream.fold(term)).in(folded)) return true;
        return false;
    }

    private void drawUpNext(Canvas c, long now) {
        if (!cardShowing()) return;
        Xtream.Channel ch = live.playing();
        List<Xtream.Program> list = ch == null ? null : view.programsOf(ch);
        int at = list == null ? -1 : list.indexOf(cardFor);
        if (at < 0 || at + 1 >= list.size()) return;
        Xtream.Program next = list.get(at + 1);
        shade(c, cardShade, 0, 480 * k, w, h);
        drawAlso(c, next);
        // The card: 600 wide, its bottom at 1008.
        float wd = 600 * k, pad = 32 * k, ht = 312 * k, right = R * k, bottom = 1008 * k, left = right - wd, top = bottom - ht;
        r.set(left, top, right, bottom);
        float rr = LiveTheme.panelRound(28 * k);
        fill.setColor(LiveTheme.sheet());
        fill.setShadowLayer(34 * k, 0, 14 * k, 0x99000000);
        c.drawRoundRect(r, rr, rr, fill);
        fill.clearShadowLayer();
        if (LiveTheme.sheetRim() != 0) {
            stroke.setStrokeWidth(1.5f * k);
            stroke.setColor(LiveTheme.sheetRim());
            c.drawRoundRect(r, rr, rr, stroke);
        }
        float room = wd - 2 * pad, x = left + pad, y = top + 56 * k;
        long seconds = Math.max(0, cardFor.end - now) / 1000;
        String in = Ui.tr("em ", "in ") + seconds / 60 + ":" + (seconds % 60 < 10 ? "0" : "") + seconds % 60;
        c.drawText(ellipsize(Ui.tr("A seguir em ", "Next on ") + view.nameWithCountry(ch), cardMeta, room - cardMeta.measureText(in) - 20 * k), x, y, cardMeta);
        cardMeta.setTextAlign(Paint.Align.RIGHT);
        c.drawText(in, right - pad, y, cardMeta);
        cardMeta.setTextAlign(Paint.Align.LEFT);
        y += 60 * k;
        c.drawText(ellipsize(next.title, cardTitle, room), x, y, cardTitle);
        y += 44 * k;
        c.drawText(Ui.time(next.start) + " – " + Ui.time(next.end), x, y, cardMeta);
        // How near it is.
        y += 24 * k;
        float played = Ui.clamp(1 - (cardFor.end - now) / 120_000f);
        fill.setColor(0x3DF5F5F7);
        c.drawRoundRect(x, y, x + room, y + 6 * k, 3 * k, 3 * k, fill);
        fill.setColor(LiveTheme.text);
        c.drawRoundRect(x, y, x + Math.max(6 * k, room * played), y + 6 * k, 3 * k, 3 * k, fill);
        y += 28 * k;
        float buttonW = (room - 12 * k) / 2, buttonH = 64 * k;
        drawCardButton(c, Ui.tr("Ficar aqui", "Stay here"), x, y, buttonW, buttonH, cardFocus == alsoOn.size());
        drawCardButton(c, Ui.tr("O que mais está passando", "What else is on"), x + buttonW + 12 * k, y, buttonW, buttonH, cardFocus == alsoOn.size() + 1);
        view.postInvalidateDelayed(1000 - now % 1000);
    }

    private void drawCardButton(Canvas c, String text, float x, float y, float width, float height, boolean on) {
        r.set(x, y, x + width, y + height);
        float rr = LiveTheme.panelRound(14 * k);
        if (on) {
            glow.setColor(LiveTheme.tile);
            c.drawRoundRect(r, rr, rr, glow);
        } else {
            fill.setColor(0x24F5F5F7);
            c.drawRoundRect(r, rr, rr, fill);
        }
        cardButton.setColor(on ? LiveTheme.ink : WHITE);
        c.drawText(ellipsize(text, cardButton, width - 16 * k), r.centerX(), r.centerY() + Ui.halfCap(cardButton), cardButton);
    }

    /** What else starts then: three tiles at the left; OK tunes the one in focus. */
    private void drawAlso(Canvas c, Xtream.Program next) {
        if (alsoOn.isEmpty()) return;
        float gap = 18 * k, width = (1000 * k - 2 * gap) / 3, height = 118 * k, bottom = 1008 * k, top = bottom - height;
        float round = LiveTheme.panelRound(18 * k);
        c.drawText(Ui.tr("Também começando às ", "Also starting at ") + Ui.time(next.start), X * k, top - 22 * k, alsoHead);
        for (int i = 0; i < alsoOn.size(); i++) {
            Also a = alsoOn.get(i);
            boolean on = i == cardFocus;
            float left = (X * k) + i * (width + gap);
            r.set(left, top, left + width, bottom);
            int save = c.save();
            if (on) {
                c.scale(1.05f, 1.05f, r.centerX(), r.centerY());
                glow.setColor(LiveTheme.tile);
                c.drawRoundRect(r, round, round, glow);
            } else {
                fill.setColor((LiveTheme.card & 0x00FFFFFF) | 0xF0000000);
                c.drawRoundRect(r, round, round, fill);
                fill.setColor(0x1FFFFFFF);
                c.drawRoundRect(r, round, round, fill);
                LiveTheme.rim(c, r, round, 1.5f * k);
            }
            int ink = on ? LiveTheme.ink : WHITE;
            alsoWhy.setColor((ink & 0x00FFFFFF) | 0xE6000000);
            alsoTitle.setColor(ink);
            float x = left + 20 * k, room = width - 40 * k;
            c.drawText(ellipsize(view.nameWithCountry(a.channel) + " · " + a.why, alsoWhy, room), x, top + 44 * k, alsoWhy);
            c.drawText(ellipsize(a.title, alsoTitle, room), x, top + 86 * k, alsoTitle);
            c.restoreToCount(save);
        }
    }

    /** Whether the match stays hidden on `shown` for now (the guard, a team kept spoiler-free), until it's asked for. */
    private boolean matchHidden(Xtream.Program shown, boolean past) {
        return shown != null && LiveMoments.hidden(LiveOptions.spoilerGuard(live), LiveNames.spoiled(live, shown.title),
                revealed.equals(showKey(shown)), past);
    }

    private static String showKey(Xtream.Program p) {
        return p.start + "|" + p.title;
    }

    /**
     * When a later show starts: "Starts in 25 min", "Starts in 1 h 5 min" (under 2 h), else the time ("Starts at
     * 3:00 AM" today, "Tomorrow  1:00 AM", "Sat  9:00 PM").
     */
    static String startsIn(long start, long now) {
        long in = Math.max(1, Math.round((start - now) / 60_000.0));
        if (in < 60) return Ui.tr("Começa em ", "Starts in ") + in + " min";
        if (in < 120) return Ui.tr("Começa em ", "Starts in ") + "1 h" + (in % 60 > 0 ? " " + in % 60 + " min" : "");
        java.util.Calendar a = java.util.Calendar.getInstance(), b = java.util.Calendar.getInstance();
        a.setTimeInMillis(now);
        b.setTimeInMillis(start);
        if (a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) && a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR))
            return Ui.tr("Começa às ", "Starts at ") + Ui.time(start);
        a.add(java.util.Calendar.DAY_OF_YEAR, 1);
        boolean tomorrow = a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) && a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR);
        String day = tomorrow ? Ui.tr("Amanhã", "Tomorrow") : new java.text.SimpleDateFormat("EEE", Ui.locale()).format(new java.util.Date(start));
        return day + "  " + Ui.time(start);
    }

    /** A rounded tag (filled with `color`) at x, on the line at baseline y; returns where it ends. */
    private final Paint tagLabel = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** The channel's line, then its country tag (LiveCountry); returns where it ends. */
    private float drawNameAndTag(Canvas c, String line, String tag, float x, float y, float room) {
        LiveCountry.label(meta, tagLabel);
        float tagW = LiveCountry.tagWidth(tag, meta, tagLabel, k);
        String t = ellipsize(line, meta, Math.max(1, room - tagW));
        c.drawText(t, x, y, meta);
        float end = x + meta.measureText(t);
        return tag.isEmpty() ? end : LiveCountry.drawTag(c, tag, end + 12 * k, y, meta, tagLabel, k);
    }

    private float drawPill(Canvas c, String text, float x, float baseline, int color, boolean big) {
        return drawPill(c, text, x, baseline, color, 0xFFFFFFFF, big);
    }

    private float drawPill(Canvas c, String text, float x, float baseline, int color, int ink, boolean big) {
        Paint p = badge;
        float tw = p.measureText(text), ph = (big ? 40 : 36) * k, pw = tw + (big ? 30 : 24) * k;
        float top = baseline - ph / 2 - Ui.halfCap(meta) + 2 * k;
        r.set(x, top, x + pw, top + ph);
        fill.setColor(color);
        float rr = LiveTheme.panelRound(8 * k);
        c.drawRoundRect(r, rr, rr, fill);
        p.setColor(ink);
        c.drawText(text, x + (pw - tw) / 2, r.centerY() + Ui.halfCap(p), p);
        p.setColor(0xFFFFFFFF);
        return r.right;
    }

    /**
     * The design's panel behind the text and the buttons: Apple TV's and Disney+'s a floating card of glass with a
     * light edge (Disney+'s less round, its edge in its blue), Sky Q's and Pluto TV's a solid band across the bottom
     * with a line of the focus color on top, Plex's the band alone, Netflix's a shade from the left; the others only
     * the fade.
     */
    private void drawPanel(Canvas c) {
        switch (LiveTheme.infoBar()) {
            case LiveTheme.BAR_CARD: {
                boolean apple = LiveTheme.infoStyle() != LiveTheme.DISNEY;
                float rr = (apple ? 44 : 28) * k;
                plate.set((X - 40) * k, 596 * k, (1920 - X + 40) * k, 1044 * k);
                fill.setColor((LiveTheme.bgTop & 0x00FFFFFF) | 0x8C000000);      // glass tinted by the design's own color
                c.drawRoundRect(plate, rr, rr, fill);
                fill.setColor(0x1AFFFFFF);
                c.drawRoundRect(plate, rr, rr, fill);
                stroke.setStrokeWidth(2 * k);
                stroke.setColor(apple ? 0x40FFFFFF : (LiveTheme.text & 0x00FFFFFF) | 0x66000000);
                c.drawRoundRect(plate, rr, rr, stroke);
                break;
            }
            case LiveTheme.BAR_BAND_LINE:
            case LiveTheme.BAR_BAND:
                shade(c, band, 0, 590 * k, w, h);
                if (LiveTheme.infoBar() == LiveTheme.BAR_BAND_LINE) {
                    fill.setColor(LiveTheme.tile);
                    c.drawRect(0, 590 * k, w, 594 * k, fill);
                }
                break;
            case LiveTheme.BAR_SIDE:
                shade(c, side, 0, 480 * k, w * 0.62f, h);
                break;
            default:
                break;
        }
    }

    /** The named tabs; the focused one in the design's focus, a little bigger. */
    private void drawTabs(Canvas c, Xtream.Program next) {
        float cy = ROW_Y * k, rad = TAB_H / 2 * k, rr = LiveTheme.panelRound(14 * k);
        boolean focused = armed && !onBar;
        if (pillText == null) pillText = Ui.text(Ui.semibold, 29, WHITE);
        pillText.setTextSize(29 * k);
        if (focused) {
            float fx = focusX.value * k, fw = focusW.value * k, grow = 4 * k;
            r.set(fx - grow, cy - rad - grow, fx + fw + grow, cy + rad + grow);
            glow.setColor(LiveTheme.tile);
            c.drawRoundRect(r, rr + grow, rr + grow, glow);
        }
        for (int i = 0; i < count; i++) {
            float bx = buttonX(i) * k, bw = buttonW(i) * k;
            boolean on = focused && i == focus && Math.abs(focusX.value - buttonX(i)) < 40;
            r.set(bx, cy - rad, bx + bw, cy + rad);
            if (!on && buttons[i] == LIVE) {
                fill.setColor(RED);
                c.drawRoundRect(r, rr, rr, fill);
            } else if (!on) {
                fill.setColor((LiveTheme.card & 0x00FFFFFF) | 0xF0000000);      // solid enough to read over any picture
                c.drawRoundRect(r, rr, rr, fill);
                fill.setColor(0x1FFFFFFF);
                c.drawRoundRect(r, rr, rr, fill);
                LiveTheme.rim(c, r, rr, 1.5f * k);
            }
            pillText.setColor(on ? LiveTheme.ink : WHITE);
            float tw = pillText.measureText(name(buttons[i], false));
            c.drawText(name(buttons[i], false), bx + (bw - tw) / 2, cy + Ui.halfCap(pillText), pillText);
        }
        if (next == null || count == 0) return;
        // What's next, at the right of the row.
        float left = (buttonX(count - 1) + buttonW(count - 1) + 40) * k, right = R * k;
        String when = Ui.tr("A seguir", "Up next") + "  " + Ui.time(next.start) + "  ·  ";
        float room = right - left - small.measureText(when);
        if (room < 160 * k) return;
        String show = ellipsize(next.title, upTitle, room);
        float at = right - upTitle.measureText(show);
        c.drawText(show, at, cy + Ui.halfCap(upTitle), upTitle);
        c.drawText(when, at - small.measureText(when), cy + Ui.halfCap(small), small);
    }

    static String pictureName(int mode) {
        return mode == Live.ZOOM ? Ui.tr("Ampliar", "Zoom") : mode == Live.STRETCH ? Ui.tr("Esticar", "Stretch") : Ui.tr("Ajustar", "Fit");
    }

    private String ellipsize(String s, Paint p, float width) {
        fit.set(p);
        return TextUtils.ellipsize(s == null ? "" : s, fit, Math.max(1, width), TextUtils.TruncateAt.END).toString();
    }

    // MARK: - Keys

    /** A key (down) while it shows: ◀ ▶ move between the buttons, OK presses one; false for the rest. */
    boolean key(int code) {
        lastKey = SystemClock.uptimeMillis();
        if (!armed()) return false;     // shown by itself: the keys do what they do while watching
        if (panel.open()) return panel.key(code);
        if (moments.open()) return moments.key(code);
        if (centre.open()) return centre.key(code);
        if (info.open()) return info.key(code);
        if (rail.open()) return rail.key(code);
        if (onScore) {      // above the bar: OK shows the score, or hides it again
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    onScore = false;
                    break;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER: {
                    Xtream.Program p = view.playingShow();
                    if (p != null) revealed = revealed.equals(showKey(p)) ? "" : showKey(p);
                    break;
                }
                default:
                    break;
            }
            view.invalidate();
            return true;
        }
        if (onBar) {        // the progress bar: ◀ ▶ seek, ↓ the buttons, OK play/pause (a past program)
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (live.catchup() == null || match == null) return false;
                    onScore = true;
                    view.invalidate();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    view.seek(LiveCatchup.skipStep(live.minuteSkips(), code == KeyEvent.KEYCODE_DPAD_LEFT));
                    rebuild();
                    view.invalidate();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    onBar = false;
                    LiveSpeech.say(view, name(buttons[focus], false));
                    view.invalidate();
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    live.togglePause();     // live TV too: it pauses (the TV keeps it from there)
                    rebuild();
                    view.invalidate();
                    return true;
                default:
                    return false;
            }
        }
        if (code == KeyEvent.KEYCODE_DPAD_UP && view.canSeek()) {      // back up to the bar
            onBar = true;
            view.invalidate();
            return true;
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                moveFocus(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                moveFocus(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                press(buttons[focus]);
                return true;
            default:
                return false;
        }
    }

    private void moveFocus(int dir) {
        int next = Math.max(0, Math.min(count - 1, focus + dir));
        if (next == focus) return;
        focus = next;
        focusX.target = buttonX(focus);
        focusW.target = buttonW(focus);
        LiveSpeech.say(view, name(buttons[focus], false));
        view.invalidate();
    }

    private void press(int b) {
        Xtream.Channel ch = live.playing();
        if (ch == null) return;
        switch (b) {
            case INFO:
                openInfo(ch);
                return;
            case CHANNELS:
                rail.show(false);
                return;
            case AUDIO:
                panel.show(name(AUDIO, false), this::audioRows);
                return;
            case MATCH:
                openCentre();
                return;
            case MOMENTS:
                openMoments();
                return;
            case SKIP_BACK:
            case SKIP_AHEAD:
                view.seek(LiveCatchup.skipStep(live.minuteSkips(), b == SKIP_BACK));
                break;
            case PLAY:
                live.togglePause();
                break;
            case LIVE:
                focus = 0;      // first: playing it live shows the live tabs, the focus on the first
                live.backToLive();
                return;
            default:
                focus = 0;
                view.startOverPlaying();
                return;
        }
        rebuild();
        view.invalidate();
    }

    /** Info: the show, what can be done with it and what's later on the channel. */
    private void openInfo(Xtream.Channel ch) {
        Xtream.Program p = view.playingShow();
        List<LivePlayerInfo.Item> tabs = new java.util.ArrayList<>();
        int on = 0;
        for (int i = 0; i < count; i++) {
            final int b = buttons[i];
            if (b == INFO) on = tabs.size();
            tabs.add(new LivePlayerInfo.Item(name(b, false), () -> {
                info.close();
                press(b);
            }));
        }
        StringBuilder meta = new StringBuilder();
        LiveEpisode ep = LiveEpisode.of(live, p, true, view::postInvalidate);
        if (ep != null && !ep.label().isEmpty()) meta.append(ep.label());
        String tag = view.tagOf(ch);
        if (!tag.isEmpty()) meta.append(meta.length() > 0 ? "  ·  " : "").append(tag);
        if (p != null) meta.append(meta.length() > 0 ? "  ·  " : "").append((p.end - p.start) / 60_000).append(" min");
        info.show(p == null ? view.nameOf(ch) : p.title, Ui.tr("AO VIVO", "LIVE"), RED, meta.toString(),
                p == null ? "" : LiveNames.about(live, p.title, p.about), tabs, on, () -> infoActions(ch, p),
                Ui.tr("Depois em ", "Later on ") + view.nameWithCountry(ch), laterOn(ch));
    }

    /** What can be done from Info, in the mockup's order, then the rest of what the player does. */
    private List<LivePlayerInfo.Item> infoActions(Xtream.Channel ch, Xtream.Program p) {
        List<LivePlayerInfo.Item> items = new java.util.ArrayList<>();
        if (live.catchup() == null && view.recordable() != null) {
            items.add(new LivePlayerInfo.Item(view.recordingNow() ? Ui.tr("Parar de gravar", "Stop recording") : Ui.tr("Gravar", "Record"),
                    view::toggleRecording));
            if (p != null) items.add(new LivePlayerInfo.Item(Ui.tr("Gravar série", "Record series"), () -> view.seriesPlaying(p)));
        }
        boolean fav = Xtream.isFavorite(view.lineup(), ch.id);
        items.add(new LivePlayerInfo.Item(Ui.tr("Favorito", "Favorite") + (fav ? "  ✓" : ""), () -> view.toggleFavorite(ch)));
        if (p != null && !LiveFollow.choices(p.title).isEmpty()) {
            items.add(new LivePlayerInfo.Item(Ui.tr("Seguir…", "Follow…"), view::followPlaying));
        }
        items.add(new LivePlayerInfo.Item(Ui.tr("Timer para dormir", "Sleep timer")
                + (view.sleepAt() > 0 ? "  ·  " + Ui.time(view.sleepAt()) : ""), view::sleepMenu));
        items.add(new LivePlayerInfo.Item(Ui.tr("Imagem", "Picture") + ": " + pictureName(live.aspectMode()), live::nextAspect));
        items.add(new LivePlayerInfo.Item(Ui.tr("Multivisão", "Multiview"), () -> {
            hide();
            live.openMultiview();
        }));
        items.add(new LivePlayerInfo.Item(Ui.tr("Sobre o sinal", "Stream info"), view::streamInfo));
        items.add(new LivePlayerInfo.Item(Ui.tr("Guia de TV", "TV guide"), () -> {
            hide();
            view.openGuide();
        }));
        if (live.sources() != null) {
            Xtream.Channel s = live.source();
            items.add(new LivePlayerInfo.Item(Ui.tr("Fonte", "Source") + (s == null ? "" : ": " + LiveMerge.label(s)), this::chooseSource));
        }
        items.add(new LivePlayerInfo.Item(Ui.tr("Sobre o programa", "About the show"), () -> {
            hide();
            view.aboutPlaying();
        }));
        return items;
    }

    /** The next three shows on the channel; OK offers to remind, record or watch. */
    private List<LivePlayerInfo.Later> laterOn(Xtream.Channel ch) {
        List<LivePlayerInfo.Later> out = new java.util.ArrayList<>();
        List<Xtream.Program> list = view.programsOf(ch);
        long now = System.currentTimeMillis();
        for (int i = 0; list != null && i < list.size() && out.size() < 3; i++) {
            final Xtream.Program later = list.get(i);
            if (later.start <= now) continue;
            out.add(new LivePlayerInfo.Later(Ui.time(later.start), later.title, () -> view.laterPlaying(ch, later)));
        }
        return out;
    }

    /** Audio & Subtitles: the tracks, the one playing filled, the subtitles' size, and the switches at the bottom. */
    private java.util.List<LivePlayerPanel.Row> audioRows() {
        java.util.List<LivePlayerPanel.Row> rows = new java.util.ArrayList<>();
        int audio = androidx.media3.common.C.TRACK_TYPE_AUDIO, text = androidx.media3.common.C.TRACK_TYPE_TEXT;
        List<String> names = live.trackNames(audio);
        if (!names.isEmpty()) rows.add(LivePlayerPanel.Row.header(Ui.tr("Áudio", "Audio")));
        for (int i = 0; i < names.size(); i++) {
            final int which = i;
            rows.add(new LivePlayerPanel.Row(names.get(i), () -> live.selectTrack(audio, which)).checked(live.selectedTrack(audio) == i));
        }
        names = live.trackNames(text);
        rows.add(LivePlayerPanel.Row.header(Ui.tr("Legendas", "Subtitles")));
        if (names.isEmpty()) {
            rows.add(new LivePlayerPanel.Row(Ui.tr("Sem legendas neste canal", "No subtitles on this channel"), null));
        } else {
            rows.add(new LivePlayerPanel.Row(Ui.tr("Desligadas", "Off"), () -> live.selectTrack(text, -1)).checked(live.selectedTrack(text) < 0));
            for (int i = 0; i < names.size(); i++) {
                final int which = i;
                rows.add(new LivePlayerPanel.Row(names.get(i), () -> live.selectTrack(text, which)).checked(live.selectedTrack(text) == i));
            }
        }
        rows.add(LivePlayerPanel.Row.sizes(Ui.tr("Tamanho", "Subtitle size"), new String[] {"S", "M", "L", "XL"}, LiveOptions.ccSize(live), size -> {
            LiveOptions.setCaptions(live, size, LiveOptions.ccColor(live), LiveOptions.ccStyle(live));
            view.captionsChanged();
        }));
        // Night mode's hours (Settings) keep it on: the switch shows that, and isn't for changing then.
        boolean hours = NightMode.now(live);
        rows.add(new LivePlayerPanel.Row(Ui.tr("Modo noturno", "Night mode"),
                hours ? null : () -> live.setNightSound(!LiveOptions.nightSound(live))).toggle(hours || LiveOptions.nightSound(live)).pinned());
        rows.add(new LivePlayerPanel.Row(Ui.tr("Lembrar neste canal", "Remember for this channel"),
                () -> LiveOptions.setKeepChannelTracks(live, !LiveOptions.keepChannelTracks(live))).toggle(LiveOptions.keepChannelTracks(live)));
        return rows;
    }

    /** Moments: asked for first when they'd spoil the match, then each goal and red card; OK goes to it. */
    private void openMoments() {
        Xtream.Program p = view.playingShow();
        if (match == null || p == null) return;
        boolean past = live.catchup() != null;
        boolean canGo = past || live.playing() != null && live.playing().archive > 0;
        moments.show(match, matchHidden(p, past), () -> {
            revealed = showKey(p);
            moments.unlock();
        }, canGo ? at -> {
            hide();
            view.goToMoment(p, Math.max(0, at - 60_000));
        } : null);
    }

    /** Match Centre: asked for first when it would spoil the match, as Moments is. */
    private void openCentre() {
        Xtream.Program p = view.playingShow();
        if (match == null || p == null || match.event.isEmpty()) return;
        centre.show(match.event, matchHidden(p, live.catchup() != null), () -> {
            revealed = showKey(p);
            centre.unlock();
        });
    }

    /** Match Centre on one real match by ESPN's event id, for looking at it on the TV (the test broadcast's). */
    void testCentre(String event, boolean locked) {
        show(true);
        centre.show(event, locked, centre::unlock);
    }

    /** A stand-in match, for looking at Moments on the TV without one on (the test broadcast's). */
    void testMoments(int count, boolean locked) {
        Xtream.Program p = view.playingShow();
        long length = live.catchup() != null ? live.length() : p == null ? 60 * 60_000L : p.end - p.start;
        testMatch = count > 0 ? LiveMoments.sample(count, length) : null;
        match = testMatch;
        show(true);
        if (testMatch != null) moments.show(testMatch, locked, moments::unlock, null);
    }

    /** A merged channel's sources in a list, Automatic first (the best the connection carries); the choice is checked. */
    private void chooseSource() {
        final List<Xtream.Channel> list = live.sources();
        if (list == null) return;
        Xtream.Channel now = live.source();
        String[] items = new String[list.size() + 1];
        items[0] = Ui.tr("Automática (a melhor para a sua internet)", "Automatic (the best for your internet)");
        int on = 0;
        for (int i = 0; i < list.size(); i++) {
            Xtream.Channel s = list.get(i);
            items[i + 1] = live.sourceLabel(s);
            if (!live.autoSource() && now != null && s.id == now.id) on = i + 1;
        }
        LiveSheet.menu(view.getContext())
                .setTitle(Ui.tr("Fonte", "Source"))
                .setSingleChoiceItems(items, on, (d, which) -> {
                    live.chooseSource(which == 0 ? -1 : list.get(which - 1).id);
                    d.dismiss();
                    rebuild();
                    view.invalidate();
                })
                .show();
    }

    /** The audio tracks (or subtitles, with Off first) in a list; the one playing is checked. */
    /** The remote's CC button: the subtitles' list. */
    void chooseSubtitles() {
        chooseTrack(androidx.media3.common.C.TRACK_TYPE_TEXT, Ui.tr("Legendas", "Subtitles"));
    }

    private void chooseTrack(int type, String heading) {
        boolean text = type == androidx.media3.common.C.TRACK_TYPE_TEXT;
        List<String> names = live.trackNames(type);
        String[] items = new String[names.size() + (text ? 1 : 0)];
        if (text) items[0] = Ui.tr("Desligadas", "Off");
        for (int i = 0; i < names.size(); i++) items[i + (text ? 1 : 0)] = names.get(i);
        int on = live.selectedTrack(type) + (text ? 1 : 0);
        LiveSheet.menu(view.getContext())
                .setTitle(heading)
                .setSingleChoiceItems(items, Math.max(0, on), (d, which) -> {
                    live.selectTrack(type, which - (text ? 1 : 0));
                    d.dismiss();
                    view.invalidate();
                })
                .show();
    }
}
