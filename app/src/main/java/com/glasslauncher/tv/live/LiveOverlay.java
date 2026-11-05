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
    private static final int PLAY = 1, LIVE = 4, START_OVER = 5, INFO = 6, CHANNELS = 7, AUDIO = 8, MOMENTS = 9, OPTIONS = 10;
    /** On a later show (◀ ▶ in the peek) it stays up longer, to read it (Back closes it). */
    private static final long PEEK_LATER_MS = 20_000;
    private static final long HIDE_MS = 6000, BRIEF_MS = 3500;
    private static final float X = 96, R = 1824, GAP = 12, TOP_Y = 668, TITLE_Y = 748, SUB_Y = 800, BAR_Y = 880,
            LABEL_Y = 920, ROW_Y = 976, TAB_H = 64;
    private static final int INK = 0xFF1C1C1E, WHITE = 0xFFF5F5F7, RED = 0xFFFF453A, BLUE = 0xFF0A84FF;
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
    private final LiveRail rail;
    /** The match the show is (LiveMoments), found by its title; which show was asked to show it. */
    private LiveMoments.Match match;
    private Xtream.Program matchFor;
    private long matchAt;
    private String revealed = "";
    /** The Up next card: the show it's for, the show it was closed on, what else starts then; the check's running. */
    private Xtream.Program cardFor;
    private String cardClosed = "";
    private final java.util.ArrayList<String> alsoOn = new java.util.ArrayList<>();
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
        rail = new LiveRail(view, live);
    }

    void size(float scale, int width, int height) {
        k = scale;
        w = width;
        h = height;
        panel.size(scale, width, height);
        rail.size(scale);
        if (!checking) {
            checking = true;
            view.postDelayed(check, 5000);
        }
        title = Ui.text(Ui.displaySemibold, 72 * k, WHITE);
        cardTitle = Ui.text(Ui.displaySemibold, 42 * k, WHITE);
        // Glassio 3: the words stay readable over the brightest picture (with the stronger shade below).
        boolean clear = LiveTheme.cinemaBar();
        meta = Ui.text(Ui.medium, 28 * k, clear ? 0xFFFFFFFF : 0xD9FFFFFF);
        small = Ui.text(Ui.medium, 23 * k, clear ? 0xF2FFFFFF : 0xA6FFFFFF);
        badge = Ui.text(Ui.semibold, 18 * k, 0xE6FFFFFF);
        badge.setLetterSpacing(0.06f);
        clockPaint = Ui.numbers(Ui.displaySemibold, 46 * k, WHITE);
        clockPaint.setTextAlign(Paint.Align.RIGHT);
        aboutPaint = new TextPaint(Ui.text(Ui.regular, 24 * k, clear ? 0xF2FFFFFF : 0xA6FFFFFF));
        fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        glow.setColor(WHITE);
        glow.setShadowLayer(24 * k, 0, 8 * k, 0x80000000);
        stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        topShade = new LinearGradient(0, 0, 0, 240 * k, clear ? 0xCC000000 : 0x99000000, 0x00000000, Shader.TileMode.CLAMP);
        // The fade under the text, into the style's own background (black for Glassio's).
        int base = LiveTheme.bg & 0x00FFFFFF;
        bottomShade = clear
                // Dark well before the title starts (about y 640), nearly solid under the buttons.
                ? new LinearGradient(0, 480 * k, 0, h, new int[] {base, 0xD9000000 | base, 0xF0000000 | base, 0xFA000000 | base},
                        new float[] {0, 0.25f, 0.55f, 1}, Shader.TileMode.CLAMP)
                : new LinearGradient(0, 480 * k, 0, h, new int[] {base, 0xB3000000 | base, 0xF2000000 | base},
                        new float[] {0, 0.4f, 1}, Shader.TileMode.CLAMP);
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
            focus = 0;
            focusX.snap(buttonX(0));
            focusW.snap(buttonW(0));
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
        view.announceForAccessibility(Ui.time(p.start) + ", " + p.title);
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
        rail.close();
        view.invalidate();
    }

    /** Back: an open card or the rail closes, back to the tabs; else it all goes. */
    void back() {
        lastKey = SystemClock.uptimeMillis();
        if (panel.open()) panel.close();
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
        if (showing && SystemClock.uptimeMillis() - lastKey > hold && !paused && !panel.open() && !rail.open()) hide();
        boolean moving = appear.step(dt) | focusX.step(dt) | focusW.step(dt) | panel.step(dt) | rail.step(dt);
        if (!showing && !moving && appear.value <= 0.004f) {     // gone (settled, not on a bounce): the peek with it
            peek = null;
            peekAhead = 0;
            brief = false;
        }
        return moving;
    }

    /** The tabs for what's playing, then at the right end Start over, or Back to live on a past program. */
    private void rebuild() {
        count = 0;
        Xtream.Channel ch = live.playing();
        boolean past = live.catchup() != null || live.shifted();
        if (!view.canSeek() && ch != null) buttons[count++] = PLAY;     // no bar to press OK on
        buttons[count++] = INFO;
        buttons[count++] = CHANNELS;
        buttons[count++] = AUDIO;
        if (match != null) buttons[count++] = MOMENTS;
        buttons[count++] = OPTIONS;
        rightFrom = count;
        if (past) buttons[count++] = LIVE;
        else if (ch != null && ch.archive > 0) buttons[count++] = START_OVER;
        // The focus goes where its tab is now (the row changes between live and a past program).
        focus = Math.min(focus, count - 1);
        focusX.target = buttonX(focus);
        focusW.target = buttonW(focus);
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
        if (pillText == null) pillText = Ui.text(Ui.semibold, 27, WHITE);
        pillText.setTextSize(27);
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
            case OPTIONS: return Ui.tr("Opções", "Options");
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
            return false;
        }
        if (panel.open()) {        // the video stays clear beside the card
            panel.draw(c);
            c.restoreToCount(save);
            if (showing) view.postInvalidateDelayed(1000 - now % 1000);
            return false;
        }
        fill.setShader(topShade);
        c.drawRect(0, 0, w, 240 * k, fill);
        fill.setShader(bottomShade);
        c.drawRect(0, 480 * k, w, h, fill);
        fill.setShader(null);
        drawPanel(c);
        drawTop(c, ch, now);
        if (rail.open()) {
            rail.draw(c);
            c.restoreToCount(save);
            if (showing) view.postInvalidateDelayed(1000 - now % 1000);
            return false;
        }
        c.translate(0, (1 - a) * (1 - a) * 60 * k);

        // LIVE (or what it is instead), the score, then what the stream is.
        float x = X * k, y = TOP_Y * k;
        String pill = past == null ? Ui.tr("AO VIVO", "LIVE") : live.recorded() ? Ui.tr("GRAVADO", "RECORDED") : Ui.tr("REPRISE", "CATCH-UP");
        if (later != null) pill = Ui.tr("DEPOIS", "LATER");
        else if (live.paused() && peek == null) pill = Ui.tr("PAUSADO", "PAUSED");
        boolean lit = past == null && !behind && later == null && !live.paused();
        x = drawPill(c, pill, x, y, lit ? RED : (LiveTheme.text & 0x00FFFFFF) | 0x59000000, true) + 14 * k;
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
            LiveMoments.Match found = peek != null || later != null ? null
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
        if (past != null && match != null) {
            x = drawPill(c, hidden ? Ui.tr("Placar escondido", "Score hidden") : match.result(), x, y, SCORE, true) + 14 * k;
        } else if (scoreLine != null) {
            x = drawPill(c, scoreLine, x, y, SCORE, true) + 14 * k;
        }
        if (shown != ratedFor || (ratedAge < 0 && up - ratedAt > 2000)) {     // its rating: the guide's, else TMDB's
            ratedFor = shown;
            ratedAt = up;
            ratedAge = LiveTmdb.age(live, shown);
        }
        if (ratedAge > 0) x = drawPill(c, ratedAge + "+", x, y, LiveTheme.chip(), false) + 10 * k;
        // Trouble first (it matters more than the quality tags), then the tags that fit.
        if (ch != healthFor || up - healthAt > 3000) {
            healthFor = ch;
            healthAt = up;
            unstable = ch.id > 0 && LiveHealth.recent(live, ch.id, 10 * 60_000L) > 0;
        }
        if (unstable && peek == null) x = drawPill(c, Ui.tr("INSTÁVEL", "UNSTABLE"), x, y, 0xCCC77800, false) + 10 * k;
        if (peek == null) {
            for (String b : live.badges()) {
                if (x + badge.measureText(b) + 20 * k > R * k) break;
                x = drawPill(c, b, x, y, LiveTheme.chip(), false) + 10 * k;
            }
        }

        // The show's title, big; a long one scrolls across.
        String head = shown == null || shown.title.isEmpty() ? ch.name : shown.title;
        titleMarquee.draw(c, head, X * k, TITLE_Y * k, (R - X) * k, title, k);

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
        if (shown != null) sub.append(Ui.time(shown.start)).append(" – ").append(Ui.time(shown.end));
        if (!rightText.isEmpty()) sub.append(sub.length() > 0 ? "  ·  " : "").append(rightText);
        c.drawText(ellipsize(sub.toString(), meta, (R - X) * k), X * k, SUB_Y * k, meta);

        // The bar, its marks, the place in it, and where stacked skips will land.
        long target = peek == null ? live.skipTarget() : -1;
        if (target >= 0) {      // while skips are stacked the thumb stays where it is; the ring goes
            at = live.skipStart();
            f = length > 0 ? Ui.clamp(at / (float) length) : 0;
        }
        float barY = BAR_Y * k, barL = X * k, barR = R * k;
        boolean focused = armed() && onBar;
        float half = (focused ? 5 : 3) * k;
        fill.setColor(0x40FFFFFF);
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
                    c.drawRoundRect(mx - 3 * k, barY - 10 * k, mx + 3 * k, barY + 10 * k, 3 * k, 3 * k, fill);
                }
            }
            fill.setColor(WHITE);
            if (focused) c.drawCircle(end, barY, 14 * k, glow);
            else c.drawCircle(end, barY, 8 * k, fill);
            if (target >= 0 && length > 0) drawTarget(c, target, at, length, past, barL, barR, barY);
        }
        small.setTextAlign(Paint.Align.LEFT);
        float labelY = LABEL_Y * k;
        if (!startText.isEmpty()) c.drawText(startText, barL, labelY, small);
        if (focused) {
            String hint = live.minuteSkips() ? Ui.tr("◀ −1 min    ▶ +1 min    ▼ botões", "◀ −1 min    ▶ +1 min    ▼ buttons")
                    : Ui.tr("◀ −10 s    ▶ +30 s    ▼ botões", "◀ −10 s    ▶ +30 s    ▼ buttons");
            c.drawText(hint, barL + small.measureText(startText) + 30 * k, labelY, small);
        }
        small.setTextAlign(Paint.Align.RIGHT);
        if (!endText.isEmpty()) c.drawText(endText, barR, labelY, small);
        // Up next, at the right, before the end time.
        if (next != null && past == null) {
            String upNext = Ui.tr("A seguir", "Up next") + "  ·  " + Ui.time(next.start) + "  " + next.title;
            c.drawText(ellipsize(upNext, small, 860 * k), barR - small.measureText(endText) - 34 * k, labelY, small);
        }
        small.setTextAlign(Paint.Align.LEFT);

        if (peek == null) {
            drawTabs(c);
        } else {        // another channel's info (Sky's mini guide): OK tunes it; ◀ ▶ its later shows
            String hint = later != null ? Ui.tr("OK para lembrar ou gravar   ·   ◀ ▶ outros programas", "OK to remind or record   ·   ◀ ▶ other shows")
                    : Ui.tr("OK para assistir   ·   ▶ mais tarde", "OK to watch   ·   ▶ later shows");
            drawAbout(c, shown, ep);
            c.drawText(hint, X * k, 1030 * k, small);
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

    /** Where the skips pressed so far will land: a ring on the bar and, over it, by how much and when. */
    private void drawTarget(Canvas c, long target, long at, long length, Xtream.Program past, float barL, float barR, float barY) {
        float tx = barL + (barR - barL) * Ui.clamp(target / (float) length);
        stroke.setStrokeWidth(4 * k);
        stroke.setColor(WHITE);
        fill.setColor(INK);
        c.drawCircle(tx, barY, 12 * k, fill);
        c.drawCircle(tx, barY, 12 * k, stroke);
        String t = LiveCatchup.skipLabel(target, at) + (past == null ? "" : "  ·  " + Ui.time(past.start + target));
        float tw = meta.measureText(t), pw = tw + 36 * k, ph = 44 * k;
        float left = Math.max(barL, Math.min(barR - pw, tx - pw / 2)), top = barY - 22 * k - ph;
        r.set(left, top, left + pw, top + ph);
        fill.setColor(LiveTheme.tile);
        float rr = LiveTheme.panelRound(12 * k);
        c.drawRoundRect(r, rr, rr, fill);
        int keep = meta.getColor();
        meta.setColor(LiveTheme.ink);
        c.drawText(t, left + 18 * k, r.centerY() + Ui.halfCap(meta), meta);
        meta.setColor(keep);
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
        c.translate(X * k, 938 * k);
        about.draw(c);
        c.restore();
    }

    /** Just tuned: a small card at the top left with the channel and its show, gone in a moment. */
    private void drawBrief(Canvas c, Xtream.Channel ch, Xtream.Program shown, boolean past, long now, float off) {
        float left = 72 * k, top = (56 - off * 40) * k, ht = 128 * k, textX = left + 204 * k;
        String name = (ch.num > 0 ? ch.num + "  " : "") + view.nameOf(ch);
        String show = shown == null ? "" : shown.title;
        String note = shown == null ? "" : past ? Ui.tr("REPRISE", "CATCH-UP")
                : LiveView.minutesLeft((int) Math.max(1, Math.round((shown.end - now) / 60_000.0)));
        float textW = Math.min(760 * k, Math.max(Math.max(meta.measureText(name), meta.measureText(show)), small.measureText(note)));
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
            c.drawText(ellipsize(name, meta, textW), textX, top + ht / 2 + Ui.halfCap(meta), meta);
            return;
        }
        c.drawText(ellipsize(name, meta, textW), textX, top + 42 * k, meta);
        c.drawText(ellipsize(show, meta, textW), textX, top + 80 * k, meta);
        c.drawText(ellipsize(note, small, textW), textX, top + 112 * k, small);
    }

    /** Whether the Up next card is on screen (nothing else is, and a show's about to end). */
    boolean cardShowing() {
        return cardFor != null && !showing && view.watching();
    }

    /** Back on the card: gone, for this show. */
    void closeCard() {
        if (cardFor != null) cardClosed = showKey(cardFor);
        cardFor = null;
        view.invalidate();
    }

    /** OK on the card: what else is on, Favorites first. */
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
            if (!LiveRail.upNextDue(now, p.end) || showKey(p).equals(cardClosed)) return null;
            if (p != cardFor) findAlsoOn(list.get(i + 1));
            return p;
        }
        return null;
    }

    /** Up to two other shows starting then, on a favorite channel. */
    private void findAlsoOn(Xtream.Program next) {
        alsoOn.clear();
        Xtream.Lineup lineup = view.lineup();
        if (lineup == null || lineup.categories.isEmpty()) return;
        Xtream.Channel on = live.playing();
        List<Xtream.Channel> favorites = lineup.categories.get(0).channels;
        for (int i = 0; i < favorites.size() && i < 40 && alsoOn.size() < 2; i++) {
            Xtream.Channel ch = favorites.get(i);
            if (on != null && ch.id == on.id) continue;
            List<Xtream.Program> shows = view.programsOf(ch);
            if (shows == null) continue;
            for (Xtream.Program p : shows) {
                if (Math.abs(p.start - next.start) > 5 * 60_000L) continue;
                alsoOn.add(p.title + "  ·  " + view.nameOf(ch));
                break;
            }
        }
    }

    private void drawUpNext(Canvas c, long now) {
        if (!cardShowing()) return;
        Xtream.Channel ch = live.playing();
        List<Xtream.Program> list = ch == null ? null : view.programsOf(ch);
        int at = list == null ? -1 : list.indexOf(cardFor);
        if (at < 0 || at + 1 >= list.size()) return;
        Xtream.Program next = list.get(at + 1);
        float wd = 620 * k, pad = 32 * k, ht = (216 + alsoOn.size() * 40 + (alsoOn.isEmpty() ? 0 : 12)) * k;
        float right = R * k, bottom = 1016 * k, left = right - wd, top = bottom - ht;
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
        float room = wd - 2 * pad, x = left + pad, y = top + 50 * k;
        long left2 = Math.max(0, cardFor.end - now) / 1000;
        String in = Ui.tr("em ", "in ") + left2 / 60 + ":" + (left2 % 60 < 10 ? "0" : "") + left2 % 60;
        c.drawText(ellipsize(Ui.tr("A seguir em ", "Next on ") + view.nameOf(ch), small, room - small.measureText(in) - 20 * k), x, y, small);
        small.setTextAlign(Paint.Align.RIGHT);
        c.drawText(in, right - pad, y, small);
        small.setTextAlign(Paint.Align.LEFT);
        y += 58 * k;
        c.drawText(ellipsize(next.title, cardTitle, room), x, y, cardTitle);
        y += 42 * k;
        c.drawText(Ui.time(next.start) + " – " + Ui.time(next.end), x, y, small);
        for (String also : alsoOn) {
            y += 40 * k;
            c.drawText(ellipsize(Ui.tr("Também: ", "Also: ") + also, small, room), x, y, small);
        }
        y += 48 * k;
        c.drawText(ellipsize(Ui.tr("OK: o que mais está passando  ·  Voltar fecha", "OK: what else is on  ·  Back closes"), small, room), x, y, small);
        view.postInvalidateDelayed(1000 - now % 1000);
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
        Paint p = badge;
        float tw = p.measureText(text), ph = (big ? 34 : 30) * k, pw = tw + (big ? 26 : 20) * k;
        float top = baseline - ph / 2 - Ui.halfCap(meta) + 2 * k;
        r.set(x, top, x + pw, top + ph);
        fill.setColor(color);
        c.drawRoundRect(r, ph / 2, ph / 2, fill);
        c.drawText(text, x + (pw - tw) / 2, r.centerY() + Ui.halfCap(p), p);
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
                fill.setShader(band);
                c.drawRect(0, 590 * k, w, h, fill);
                fill.setShader(null);
                if (LiveTheme.infoBar() == LiveTheme.BAR_BAND_LINE) {
                    fill.setColor(LiveTheme.tile);
                    c.drawRect(0, 590 * k, w, 594 * k, fill);
                }
                break;
            case LiveTheme.BAR_SIDE:
                fill.setShader(side);
                c.drawRect(0, 480 * k, w * 0.62f, h, fill);
                fill.setShader(null);
                break;
            default:
                break;
        }
    }

    /** The named tabs; the focused one in the design's focus, a little bigger. */
    private void drawTabs(Canvas c) {
        float cy = ROW_Y * k, rad = TAB_H / 2 * k, rr = LiveTheme.panelRound(14 * k);
        boolean focused = armed && !onBar;
        if (pillText == null) pillText = Ui.text(Ui.semibold, 27, WHITE);
        pillText.setTextSize(27 * k);
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
            if (!on) {
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
        if (rail.open()) return rail.key(code);
        if (onBar) {        // the progress bar: ◀ ▶ seek, ↓ the buttons, OK play/pause (a past program)
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    view.seek(LiveCatchup.skipStep(live.minuteSkips(), code == KeyEvent.KEYCODE_DPAD_LEFT));
                    rebuild();
                    view.invalidate();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    onBar = false;
                    view.announceForAccessibility(name(buttons[focus], false));
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
        view.announceForAccessibility(name(buttons[focus], false));
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
                panel.show(name(AUDIO, false), "", this::audioRows);
                return;
            case MOMENTS:
                openMoments();
                return;
            case OPTIONS:
                panel.show(name(OPTIONS, false), "", this::optionRows);
                return;
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

    /** Info: the show's words, what can be done with it, and what's later on the channel. */
    private void openInfo(Xtream.Channel ch) {
        Xtream.Program p = view.playingShow();
        String text = p == null ? "" : LiveNames.about(live, p.title, p.about);
        panel.show(p == null ? view.nameOf(ch) : p.title, text, () -> infoRows(ch, p));
    }

    private java.util.List<LivePlayerPanel.Row> infoRows(Xtream.Channel ch, Xtream.Program p) {
        java.util.List<LivePlayerPanel.Row> rows = new java.util.ArrayList<>();
        rows.add(new LivePlayerPanel.Row(Ui.tr("Sobre o programa", "About the show"), () -> {
            hide();
            view.aboutPlaying();
        }));
        if (live.catchup() == null && view.recordable() != null) {
            rows.add(new LivePlayerPanel.Row(view.recordingNow() ? Ui.tr("Parar de gravar", "Stop recording") : Ui.tr("Gravar", "Record"),
                    view::toggleRecording));
        }
        boolean fav = Xtream.isFavorite(view.lineup(), ch.id);
        rows.add(new LivePlayerPanel.Row(fav ? Ui.tr("Tirar dos favoritos", "Remove from favorites") : Ui.tr("Pôr nos favoritos", "Add to favorites"),
                () -> view.toggleFavorite(ch)));
        if (p != null && !LiveFollow.choices(p.title).isEmpty()) {
            rows.add(new LivePlayerPanel.Row(Ui.tr("Seguir…", "Follow…"), view::followPlaying));
        }
        List<Xtream.Program> list = view.programsOf(ch);
        long now = System.currentTimeMillis();
        int shown = 0;
        for (int i = 0; list != null && i < list.size() && shown < 3; i++) {
            Xtream.Program later = list.get(i);
            if (later.start <= now) continue;
            if (shown++ == 0) rows.add(LivePlayerPanel.Row.header(Ui.tr("Depois em ", "Later on ") + view.nameOf(ch)));
            rows.add(new LivePlayerPanel.Row(Ui.time(later.start) + "  " + later.title, () -> view.laterPlaying(ch, later)));
        }
        return rows;
    }

    /** Audio & Subtitles: the tracks, checked where they are, and the subtitles' size. */
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
            return rows;
        }
        rows.add(new LivePlayerPanel.Row(Ui.tr("Desligadas", "Off"), () -> live.selectTrack(text, -1)).checked(live.selectedTrack(text) < 0));
        for (int i = 0; i < names.size(); i++) {
            final int which = i;
            rows.add(new LivePlayerPanel.Row(names.get(i), () -> live.selectTrack(text, which)).checked(live.selectedTrack(text) == i));
        }
        int size = LiveOptions.ccSize(live);
        rows.add(new LivePlayerPanel.Row(Ui.tr("Tamanho", "Subtitle size"), () -> {
            LiveOptions.setCaptions(live, (size + 1) % LiveOptions.CC_SIZES.length, LiveOptions.ccColor(live), LiveOptions.ccStyle(live));
            view.captionsChanged();
        }).value(String.valueOf(size + 1)));
        return rows;
    }

    /** Options: the rest of what the player does. */
    private java.util.List<LivePlayerPanel.Row> optionRows() {
        java.util.List<LivePlayerPanel.Row> rows = new java.util.ArrayList<>();
        rows.add(new LivePlayerPanel.Row(Ui.tr("Guia de TV", "TV guide"), () -> {
            hide();
            view.openGuide();
        }));
        rows.add(new LivePlayerPanel.Row(Ui.tr("Multivisão", "Multiview"), () -> {
            hide();
            live.openMultiview();
        }));
        rows.add(new LivePlayerPanel.Row(Ui.tr("Timer para dormir", "Sleep timer"), view::sleepMenu)
                .value(view.sleepAt() > 0 ? Ui.time(view.sleepAt()) : ""));
        rows.add(new LivePlayerPanel.Row(Ui.tr("Imagem", "Picture"), live::nextAspect).value(pictureName(live.aspectMode())));
        if (live.sources() != null) {
            Xtream.Channel s = live.source();
            rows.add(new LivePlayerPanel.Row(Ui.tr("Fonte", "Source"), this::chooseSource).value(s == null ? "" : LiveMerge.label(s)));
        }
        rows.add(new LivePlayerPanel.Row(Ui.tr("Sobre o sinal", "Stream info"), view::streamInfo));
        return rows;
    }

    /** Moments: asked for first when they'd spoil the match, then each goal and red card; OK goes to it. */
    private void openMoments() {
        Xtream.Program p = view.playingShow();
        if (match == null || p == null) return;
        panel.show(name(MOMENTS, false), momentsWords(p), () -> momentRows(p));
    }

    private String momentsWords(Xtream.Program p) {
        return matchHidden(p, live.catchup() != null)
                ? Ui.tr("Gols e cartões ficam escondidos até você pedir.", "Goals and cards stay hidden until you ask.") : "";
    }

    private java.util.List<LivePlayerPanel.Row> momentRows(Xtream.Program p) {
        java.util.List<LivePlayerPanel.Row> rows = new java.util.ArrayList<>();
        boolean past = live.catchup() != null;
        if (matchHidden(p, past)) {
            rows.add(new LivePlayerPanel.Row(Ui.tr("Mostrar momentos", "Show moments"), () -> {
                revealed = showKey(p);
                panel.show("");
                view.invalidate();
            }));
            return rows;
        }
        for (LiveMoments.Moment m : match.moments) {
            String kind = m.kind == LiveMoments.GOAL ? Ui.tr("Gol", "Goal") : Ui.tr("Cartão vermelho", "Red card");
            Runnable go = past || live.playing() != null && live.playing().archive > 0 ? () -> {
                hide();
                view.goToMoment(p, Math.max(0, m.at - 60_000));
            } : null;
            rows.add(new LivePlayerPanel.Row(m.minute + "′  " + kind, go).value(m.who));
        }
        return rows;
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
