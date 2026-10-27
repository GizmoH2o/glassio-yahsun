package com.glasslauncher.tv.live;

import android.app.AlertDialog;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;

import java.util.List;

/**
 * What's playing, over the video (Apple TV / YouTube TV's player): the clock at the top; at the bottom the channel's
 * logo, the program's title, LIVE (or CATCH-UP), the channel and the program's times, what the stream is (1080p,
 * HEVC, 5.1…), the synopsis, how far it is, what's next, and a row of round buttons (Guide, About the show, Start over,
 * the favorite, audio, subtitles, the picture's fit; on a past program also play/pause and LIVE, back to
 * the channel live). It slides up and fades in on a spring, one white focus glides between the buttons, and it goes
 * by itself a few seconds after the last key (not while a past program is paused).
 * Measurements in a 1920×1080 space (k = width / 1920).
 */
final class LiveOverlay {
    private static final int GUIDE = 0, PLAY = 1, LIVE = 4, START_OVER = 5, FAVORITE = 6, AUDIO = 7,
            SUBTITLES = 8, PICTURE = 9, SOURCE = 10, RECORD = 11, SLEEP = 12, ABOUT = 13;
    private static final long HIDE_MS = 6000, SKIP_BACK = 10_000, SKIP_AHEAD = 30_000;
    private static final float X = 96, R = 1824, BTN = 72, GAP = 20, ROW_Y = 956, LIVE_W = 136;
    /** The text column, right of the logo (196 × 110). */
    private static final float TEXT_X = X + 224;
    private static final int INK = 0xFF1C1C1E, WHITE = 0xFFF5F5F7, RED = 0xFFFF453A, BLUE = 0xFF0A84FF;
    private static final int SCORE = 0xE6202A36;       // the score's pill: dark, the white numbers on it

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
    private final Spring appear = new Spring(0.32f, 1f, 0), focusX = new Spring(0.22f, 0.86f, 0), focusW = new Spring(0.22f, 0.86f, BTN);
    private final int[] buttons = new int[14];
    private int count, focus;
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
    private Paint title, meta, small, badge, clockPaint, label, fill, glow, stroke, iconText;
    private TextPaint aboutPaint;
    private final TextPaint fit = new TextPaint();
    private Shader topShade, bottomShade;
    private StaticLayout about;
    private String aboutText = "";
    /** How many lines the synopsis was laid out for (one under an episode, else two). */
    private int aboutMax;
    /** The episode's title, beside its season and number. */
    private Paint episodePaint;
    private final RectF r = new RectF(), box = new RectF();
    private final Path path = new Path(), moonCut = new Path();
    private Shader band, side;
    private final android.graphics.RectF panel = new android.graphics.RectF();

    LiveOverlay(LiveView view, Live live) {
        this.view = view;
        this.live = live;
    }

    void size(float scale, int width, int height) {
        k = scale;
        w = width;
        h = height;
        title = Ui.text(Ui.displaySemibold, 52 * k, WHITE);
        meta = Ui.text(Ui.medium, 26 * k, 0xD9FFFFFF);
        small = Ui.text(Ui.medium, 23 * k, 0xA6FFFFFF);
        badge = Ui.text(Ui.semibold, 18 * k, 0xE6FFFFFF);
        badge.setLetterSpacing(0.06f);
        clockPaint = Ui.numbers(Ui.displaySemibold, 46 * k, WHITE);
        clockPaint.setTextAlign(Paint.Align.RIGHT);
        label = Ui.text(Ui.medium, 21 * k, WHITE);
        label.setTextAlign(Paint.Align.CENTER);
        iconText = Ui.numbers(Ui.semibold, 20 * k, WHITE);
        iconText.setTextAlign(Paint.Align.CENTER);
        aboutPaint = new TextPaint(Ui.text(Ui.regular, 24 * k, 0xA6FFFFFF));
        episodePaint = Ui.text(Ui.medium, 25 * k, 0xE6FFFFFF);
        fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        glow.setColor(WHITE);
        glow.setShadowLayer(24 * k, 0, 8 * k, 0x80000000);
        stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        topShade = new LinearGradient(0, 0, 0, 240 * k, 0x99000000, 0x00000000, Shader.TileMode.CLAMP);
        // The fade under the text, into the style's own background (black for Glassio's).
        int base = LiveTheme.bg & 0x00FFFFFF;
        bottomShade = new LinearGradient(0, 480 * k, 0, h, new int[] {base, 0xB3000000 | base, 0xF2000000 | base},
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
        rebuild();
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

    /** The buttons again (the tracks changed), without keeping it up longer. */
    void refresh() {
        if (!showing) return;
        rebuild();
        view.invalidate();
    }

    /** Shows `ch`'s info (on now, next), without its buttons, for a few seconds: OK tunes it. */
    void peek(Xtream.Channel ch) {
        peek = ch;
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
        if (!showing || peek == null) return null;
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
        peek = null;
        showing = false;
        appear.target = 0;
        view.invalidate();
    }

    boolean visible() {
        return showing;
    }

    boolean step(float dt) {
        if (showing && SystemClock.uptimeMillis() - lastKey > HIDE_MS && !((live.catchup() != null || live.shifted()) && live.paused())) hide();
        return appear.step(dt) | focusX.step(dt) | focusW.step(dt);
    }

    /** The buttons for what's playing: a past program's first, then the rest (audio and subtitles when there's a choice). */
    private void rebuild() {
        count = 0;
        Xtream.Channel ch = live.playing();
        // A past program, or live TV paused or behind (the TV's buffer): play/pause, LIVE (◀ ▶ on the bar skip). Live: pause.
        boolean past = live.catchup() != null || live.shifted();
        if (past) {
            buttons[count++] = PLAY;
            buttons[count++] = LIVE;
        } else if (ch != null) {
            buttons[count++] = PLAY;
        }
        buttons[count++] = GUIDE;
        if (ch != null) buttons[count++] = ABOUT;     // its page: picture, synopsis, cast
        if (!past && ch != null && ch.archive > 0) buttons[count++] = START_OVER;
        if (!past && ch != null && view.recordable() != null) buttons[count++] = RECORD;     // the show on now
        buttons[count++] = FAVORITE;
        buttons[count++] = SLEEP;
        if (live.sources() != null) buttons[count++] = SOURCE;     // a merged channel: which of its streams
        if (live.trackNames(androidx.media3.common.C.TRACK_TYPE_AUDIO).size() > 1) buttons[count++] = AUDIO;
        if (!live.trackNames(androidx.media3.common.C.TRACK_TYPE_TEXT).isEmpty()) buttons[count++] = SUBTITLES;
        buttons[count++] = PICTURE;
        // The focus goes where its button is now (the row changes between live and a past program).
        focus = Math.min(focus, count - 1);
        focusX.target = buttonX(focus);
        focusW.target = buttonW(focus);
    }

    private float buttonW(int i) {
        return buttons[i] == LIVE ? LIVE_W : BTN;
    }

    private float buttonX(int i) {
        float x = X;
        for (int j = 0; j < i; j++) x += buttonW(j) + GAP;
        return x;
    }

    // MARK: - Drawing

    /** True while it moves (and, while it shows, the clock and the progress do: a redraw each second). */
    boolean draw(Canvas c, long up) {
        float a = Ui.clamp(appear.value);
        if (a <= 0.004f) return false;
        Xtream.Channel ch = peek != null ? peek : live.playing();
        if (ch == null) return false;
        long now = System.currentTimeMillis();
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
        Xtream.Program later = peek != null && peekAhead > 0 ? peekProgram() : null;
        if (later != null) {
            shown = later;
            int at = list.indexOf(later);
            next = at >= 0 && at + 1 < list.size() ? list.get(at + 1) : null;
        }
        int save = a < 0.995f ? c.saveLayerAlpha(0, 0, w, h, Math.round(255 * a)) : c.save();
        fill.setShader(topShade);
        c.drawRect(0, 0, w, 240 * k, fill);
        fill.setShader(bottomShade);
        c.drawRect(0, 480 * k, w, h, fill);
        fill.setShader(null);
        drawPanel(c);
        c.drawText(Ui.time(now), (1920 - X) * k, 100 * k, clockPaint);
        long sleep = view.sleepAt();
        if (sleep > 0) {
            small.setTextAlign(Paint.Align.RIGHT);
            c.drawText(Ui.tr("Dorme às ", "Sleeps at ") + Ui.time(sleep), (1920 - X) * k, 140 * k, small);
            small.setTextAlign(Paint.Align.LEFT);
        }
        c.translate(0, (1 - a) * (1 - a) * 60 * k);

        float textR = R;     // the show's picture is on its page (About the show), not over what's playing
        // The logo, the title, LIVE and the channel, what the stream is.
        float x = TEXT_X * k;
        String head = shown == null || shown.title.isEmpty() ? ch.name : shown.title;
        titleMarquee.draw(c, head, x, 680 * k, (textR - TEXT_X) * k, title, k);     // a long name scrolls across
        float y = 728 * k;
        String pill = past == null ? Ui.tr("AO VIVO", "LIVE") : live.recorded() ? Ui.tr("GRAVADO", "RECORDED") : Ui.tr("REPRISE", "CATCH-UP");
        // Behind live: LIVE greyed (YouTube TV's), as it isn't now.
        if (later != null) pill = Ui.tr("DEPOIS", "LATER");
        x = drawPill(c, pill, x, y, past != null ? BLUE : behind || later != null ? 0x59FFFFFF : RED, true) + 18 * k;
        // A followed team's match on live: its score (Live scores), looked up again every second.
        if (shown != scoreFor || up - scoreAt > 1000) {
            if (shown != scoreFor) scoreTitle = shown == null ? "" : Xtream.fold(shown.title);
            scoreFor = shown;
            scoreAt = up;
            LiveScores.Score s = past == null && later == null && LiveOptions.scores(live) ? LiveScores.get(live).forTitle(scoreTitle) : null;
            scoreLine = s == null || s.state.equals("pre") ? null : s.line();
        }
        if (scoreLine != null) x = drawPill(c, scoreLine, x, y, SCORE, true) + 18 * k;
        String when = shown == null ? "" : "  ·  " + Ui.time(shown.start) + " – " + Ui.time(shown.end);
        String tag = view.tagOf(ch);
        String line = (ch.num > 0 ? ch.num + "  " : "") + view.nameOf(ch);
        float lineW = Math.min(900 * k, (textR - 60) * k - x);
        if (tag.isEmpty()) line += when;
        float lineEnd = drawNameAndTag(c, line, tag, x, y, lineW);
        if (!tag.isEmpty()) {
            String rest = ellipsize(when, meta, Math.max(1, x + lineW - lineEnd));
            c.drawText(rest, lineEnd, y, meta);
            lineEnd += meta.measureText(rest);
        }
        x = lineEnd + 22 * k;
        if (shown != ratedFor || (ratedAge < 0 && up - ratedAt > 2000)) {     // its rating: the guide's, else TMDB's
            ratedFor = shown;
            ratedAt = up;
            ratedAge = LiveTmdb.age(live, shown);
        }
        int rated = ratedAge;
        if (rated > 0) x = drawPill(c, rated + "+", x, y, LiveTheme.chip(), false) + 10 * k;
        // Trouble first (it matters more than the quality tags), then the tags that fit before the picture.
        if (ch != healthFor || up - healthAt > 3000) {
            healthFor = ch;
            healthAt = up;
            unstable = ch.id > 0 && LiveHealth.recent(live, ch.id, 10 * 60_000L) > 0;
        }
        if (unstable && peek == null) x = drawPill(c, Ui.tr("INSTÁVEL", "UNSTABLE"), x, y, 0xCCC77800, false) + 10 * k;
        float tagsEnd = (textR - 20) * k;
        if (peek == null) {
            for (String b : live.badges()) {
                if (x + badge.measureText(b) + 20 * k > tagsEnd) break;
                x = drawPill(c, b, x, y, LiveTheme.chip(), false) + 10 * k;
            }
        }

        // The episode (its season and number on a chip, then its title), then the synopsis: two lines in all.
        LiveEpisode ep = LiveEpisode.of(live, shown, true, view::postInvalidate);
        String text = shown == null ? "" : ep == null ? shown.about : LiveEpisode.aboutWithout(shown.about, shown.sub);
        float aboutTop = 752 * k;
        if (ep != null) {
            LiveEpisode.draw(c, ep, TEXT_X * k, 752 * k + episodePaint.getTextSize(), episodePaint, (textR - TEXT_X) * k, k);
            aboutTop = 752 * k + episodePaint.getTextSize() * 1.55f;
        }
        int aboutW = Math.max(1, Math.round((textR - TEXT_X) * k)), aboutLines = ep == null ? 2 : 1;
        if (!text.equals(aboutText) || about == null || about.getWidth() != aboutW || aboutMax != aboutLines) {
            aboutText = text;
            aboutMax = aboutLines;
            about = text.isEmpty() ? null : StaticLayout.Builder.obtain(text, 0, text.length(), aboutPaint,
                    aboutW).setMaxLines(aboutLines).setEllipsize(TextUtils.TruncateAt.END)
                    .setLineSpacing(0, 1.05f).build();
        }
        if (about != null) {
            c.save();
            c.translate(TEXT_X * k, aboutTop);
            about.draw(c);
            c.restore();
        }
        // The logo: steady (the setting's default), in the middle of where the title and two lines of synopsis go, so it
        // stays put whatever a show has to say; else centered on the text beside it, as it was (it moved with it).
        float blockTop = 680 * k - 2 * Ui.halfCap(title);
        float blockBottom = LiveOptions.playerLogoSteady(live) ? 752 * k + aboutPaint.getFontSpacing() * 2.05f
                : about != null ? aboutTop + about.getHeight() : ep != null ? aboutTop : 740 * k;
        float logoY = (blockTop + blockBottom) / 2;
        box.set(X * k, logoY - 55 * k, (X + 196) * k, logoY + 55 * k);
        view.drawLogo(c, ch, box, 1);

        // How far it is: the program on now (live), or the past program's position; then the times.
        float f = 0;
        String right = "";
        if (past != null) {
            long at = live.position(), length = live.length();
            f = length > 0 ? Ui.clamp(at / (float) length) : 0;
            right = LiveView.clock(at) + " / " + LiveView.clock(length);
        } else if (behind) {        // the buffer from its oldest to now; how far behind live
            long at = live.position(), length = live.length();
            f = length > 0 ? Ui.clamp(at / (float) length) : 0;
            right = live.paused() && live.behind() < 2000 ? Ui.tr("Pausado", "Paused")
                    : "−" + LiveView.clock(live.behind()) + Ui.tr(" do ao vivo", " behind live");
        } else if (later != null) {      // a later show: how long until it starts
            long in = Math.max(1, Math.round((later.start - now) / 60_000.0));
            right = Ui.tr("Começa em ", "Starts in ") + (in >= 60 ? in / 60 + " h " + (in % 60 > 0 ? in % 60 + " min" : "") : in + " min");
        } else if (onNow != null) {
            f = Ui.clamp((now - onNow.start) / (float) (onNow.end - onNow.start));
            right = LiveView.minutesLeft((int) Math.max(1, Math.round((onNow.end - now) / 60_000.0)));
        }
        float barY = 856 * k, barL = X * k, barR = R * k;
        fill.setColor(0x40FFFFFF);
        c.drawRoundRect(barL, barY - 3 * k, barR, barY + 3 * k, 3 * k, 3 * k, fill);
        if (later == null && (past != null || behind || onNow != null)) {
            fill.setColor(past != null ? BLUE : RED);
            float end = barL + (barR - barL) * f;
            c.drawRoundRect(barL, barY - 3 * k, Math.max(barL + 6 * k, end), barY + 3 * k, 3 * k, 3 * k, fill);
            fill.setColor(WHITE);
            if (armed() && onBar) {     // the focus on the bar: a bigger thumb with its shadow, ◀ ▶ seek
                c.drawCircle(end, barY, 14 * k, glow);
            } else {
                c.drawCircle(end, barY, 8 * k, fill);
            }
        }
        if (armed() && onBar) {
            String hint = Ui.tr("◀ −10 s    ▶ +30 s    ▼ botões", "◀ −10 s    ▶ +30 s    ▼ buttons");
            c.drawText(hint, barL, barY + 36 * k, small);
        }
        small.setTextAlign(Paint.Align.RIGHT);
        c.drawText(right, barR, barY + 36 * k, small);
        // Up next, at the right of the buttons; its episode's chip at the end ("S1 · E4": the next of a run).
        if (next != null && past == null) {
            LiveEpisode nextEp = LiveEpisode.of(live, next, true, view::postInvalidate);
            String label = nextEp == null ? "" : nextEp.label();
            float base = ROW_Y * k + Ui.halfCap(small), chipW = label.isEmpty() ? 0 : LiveEpisode.chipWidth(label, small, k) + 14 * k;
            String upNext = Ui.tr("A seguir", "Up next") + "  ·  " + Ui.time(next.start) + "  " + next.title;
            c.drawText(ellipsize(upNext, small, 760 * k - chipW), barR - chipW, base, small);
            if (chipW > 0) LiveEpisode.drawChip(c, label, barR - chipW + 14 * k, base, small, k);
        }
        small.setTextAlign(Paint.Align.LEFT);

        if (peek == null) {
            drawButtons(c, ch);
        } else {        // another channel's info (Sky's mini guide): OK tunes it; ◀ ▶ its later shows
            String hint = later != null ? Ui.tr("OK para lembrar ou gravar   ·   ◀ ▶ outros programas", "OK to remind or record   ·   ◀ ▶ other shows")
                    : Ui.tr("OK para assistir   ·   ▶ mais tarde", "OK to watch   ·   ▶ later shows");
            c.drawText(hint, X * k, ROW_Y * k + Ui.halfCap(meta), meta);
        }
        c.restoreToCount(save);
        if (showing) view.postInvalidateDelayed(1000 - now % 1000);
        return false;
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
                panel.set((X - 40) * k, 596 * k, (1920 - X + 40) * k, 1044 * k);
                fill.setColor((LiveTheme.bgTop & 0x00FFFFFF) | 0x8C000000);      // glass tinted by the design's own color
                c.drawRoundRect(panel, rr, rr, fill);
                fill.setColor(0x1AFFFFFF);
                c.drawRoundRect(panel, rr, rr, fill);
                stroke.setStrokeWidth(2 * k);
                stroke.setColor(apple ? 0x40FFFFFF : (LiveTheme.text & 0x00FFFFFF) | 0x66000000);
                c.drawRoundRect(panel, rr, rr, stroke);
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

    private void drawButtons(Canvas c, Xtream.Channel ch) {
        float cy = ROW_Y * k, rad = BTN / 2 * k;
        // The focus: a pill in the theme's color gliding between them, a little bigger.
        glow.setColor(LiveTheme.tile);
        float fx = focusX.value * k, fw = focusW.value * k, grow = 4 * k;
        boolean buttonsFocused = armed && !onBar;
        if (buttonsFocused) {
            r.set(fx - grow, cy - rad - grow, fx + fw + grow, cy + rad + grow);
            float gr = (rad + grow) * Math.min(1, LiveTheme.round);
            c.drawRoundRect(r, gr, gr, glow);
        }
        for (int i = 0; i < count; i++) {
            float bx = buttonX(i) * k, bw = buttonW(i) * k;
            boolean on = buttonsFocused && i == focus && Math.abs(focusX.value - buttonX(i)) < BTN / 2;
            if (!on) {
                fill.setColor(LiveTheme.glassio() ? 0x2EFFFFFF : (LiveTheme.surface | 0x14000000));
                r.set(bx, cy - rad, bx + bw, cy + rad);
                c.drawRoundRect(r, rad * Math.min(1, LiveTheme.round), rad * Math.min(1, LiveTheme.round), fill);
            }
            drawIcon(c, buttons[i], bx + bw / 2, cy, on ? LiveTheme.ink : WHITE, ch);
        }
        if (!buttonsFocused) return;
        // The focused one's name, under it.
        float lx = (focusX.value + focusW.value / 2) * k;
        c.drawText(name(buttons[focus]), lx, cy + rad + 32 * k, label);
    }

    private void drawIcon(Canvas c, int b, float cx, float cy, int color, Xtream.Channel ch) {
        float s = 26 * k;
        stroke.setColor(color);
        stroke.setStrokeWidth(3.2f * k);
        fill.setColor(color);
        switch (b) {
            case GUIDE:
                Symbols.draw(view.getContext(), c, "apps", cx, cy, 26 * k, color);
                break;
            case ABOUT:
                Symbols.draw(view.getContext(), c, "info", cx, cy, 28 * k, color);
                break;
            case PLAY:
                if (live.paused()) {
                    Symbols.draw(view.getContext(), c, "play", cx + 2 * k, cy, 28 * k, color);
                } else {
                    c.drawRoundRect(cx - 10 * k, cy - 12 * k, cx - 3 * k, cy + 12 * k, 2 * k, 2 * k, fill);
                    c.drawRoundRect(cx + 3 * k, cy - 12 * k, cx + 10 * k, cy + 12 * k, 2 * k, 2 * k, fill);
                }
                break;
            case LIVE: {
                String t = Ui.tr("AO VIVO", "LIVE");
                float tw = badge.measureText(t), x0 = cx - (tw + 22 * k) / 2;
                fill.setColor(RED);
                c.drawCircle(x0 + 6 * k, cy, 6 * k, fill);
                int keep = badge.getColor();
                badge.setColor(color);
                c.drawText(t, x0 + 22 * k, cy + Ui.halfCap(badge), badge);
                badge.setColor(keep);
                break;
            }
            case START_OVER: {
                r.set(cx - s * 0.42f, cy - s * 0.42f, cx + s * 0.42f, cy + s * 0.42f);
                c.drawArc(r, -60, 300, false, stroke);
                float ax = cx + s * 0.21f, ay = cy - s * 0.37f;
                c.drawLine(ax, ay, ax - s * 0.3f, ay - s * 0.16f, stroke);
                c.drawLine(ax, ay, ax - s * 0.09f, ay + s * 0.28f, stroke);
                break;
            }
            case RECORD: {      // a red dot; recording: a square to stop
                fill.setColor(RED);
                if (view.recordingNow()) c.drawRoundRect(cx - 10 * k, cy - 10 * k, cx + 10 * k, cy + 10 * k, 3 * k, 3 * k, fill);
                else c.drawCircle(cx, cy, 12 * k, fill);
                break;
            }
            case FAVORITE: {
                boolean fav = Xtream.isFavorite(view.lineup(), ch.id);
                heart(cx, cy + 1 * k, s * 0.95f);
                if (fav) {
                    fill.setColor(RED);
                    c.drawPath(path, fill);
                } else {
                    c.drawPath(path, stroke);
                }
                break;
            }
            case AUDIO:
                Symbols.draw(view.getContext(), c, "speaker", cx, cy, 26 * k, color);
                break;
            case SLEEP: {       // a crescent moon (filled in the theme's color while the timer runs)
                float rad = s * 0.42f;
                path.reset();
                path.addCircle(cx, cy, rad, Path.Direction.CW);
                moonCut.reset();
                moonCut.addCircle(cx + rad * 0.55f, cy - rad * 0.4f, rad * 0.85f, Path.Direction.CW);
                path.op(moonCut, Path.Op.DIFFERENCE);
                fill.setColor(view.sleepAt() > 0 ? LiveTheme.text : color);
                c.drawPath(path, fill);
                break;
            }
            case SOURCE: {      // the source's tier in a box ("FHD")
                Xtream.Channel src = live.source();
                String t = src == null ? "HD" : LiveMerge.short_(src);
                r.set(cx - 22 * k, cy - 13 * k, cx + 22 * k, cy + 13 * k);
                c.drawRoundRect(r, 5 * k, 5 * k, stroke);
                iconText.setColor(color);
                float ts = iconText.getTextSize();
                iconText.setTextSize(15 * k);
                c.drawText(t, cx, cy + Ui.halfCap(iconText), iconText);
                iconText.setTextSize(ts);
                break;
            }
            case SUBTITLES: {
                r.set(cx - 17 * k, cy - 12 * k, cx + 17 * k, cy + 12 * k);
                c.drawRoundRect(r, 5 * k, 5 * k, stroke);
                iconText.setColor(color);
                float ts = iconText.getTextSize();
                iconText.setTextSize(15 * k);
                c.drawText("CC", cx, cy + Ui.halfCap(iconText), iconText);
                iconText.setTextSize(ts);
                if (live.selectedTrack(androidx.media3.common.C.TRACK_TYPE_TEXT) < 0) c.drawLine(cx - 19 * k, cy + 15 * k, cx + 19 * k, cy - 15 * k, stroke);
                break;
            }
            default: {     // the picture's fit: four corners
                float e = 14 * k, l = 7 * k;
                path.reset();
                path.moveTo(cx - e, cy - e + l); path.lineTo(cx - e, cy - e); path.lineTo(cx - e + l, cy - e);
                path.moveTo(cx + e - l, cy - e); path.lineTo(cx + e, cy - e); path.lineTo(cx + e, cy - e + l);
                path.moveTo(cx + e, cy + e - l); path.lineTo(cx + e, cy + e); path.lineTo(cx + e - l, cy + e);
                path.moveTo(cx - e + l, cy + e); path.lineTo(cx - e, cy + e); path.lineTo(cx - e, cy + e - l);
                c.drawPath(path, stroke);
                break;
            }
        }
    }

    private void heart(float cx, float cy, float s) {
        path.reset();
        path.moveTo(cx, cy + s * 0.42f);
        path.cubicTo(cx - s * 0.95f, cy - s * 0.1f, cx - s * 0.48f, cy - s * 0.78f, cx, cy - s * 0.28f);
        path.cubicTo(cx + s * 0.48f, cy - s * 0.78f, cx + s * 0.95f, cy - s * 0.1f, cx, cy + s * 0.42f);
        path.close();
    }

    /** The button's name (under it when focused, and for TalkBack). */
    private String name(int b) {
        switch (b) {
            case GUIDE: return Ui.tr("Guia", "Guide");
            case ABOUT: return Ui.tr("Sobre o programa", "About the show");
            case PLAY: return live.paused() ? Ui.tr("Continuar", "Play") : Ui.tr("Pausar", "Pause");
            case LIVE: return Ui.tr("Voltar ao vivo", "Back to live");
            case START_OVER: return Ui.tr("Começar do início", "Start over");
            case RECORD: return view.recordingNow() ? Ui.tr("Parar de gravar", "Stop recording") : Ui.tr("Gravar", "Record");
            case FAVORITE: {
                Xtream.Channel ch = live.playing();
                return ch != null && Xtream.isFavorite(view.lineup(), ch.id) ? Ui.tr("Tirar dos favoritos", "Remove from favorites")
                        : Ui.tr("Pôr nos favoritos", "Add to favorites");
            }
            case AUDIO: return Ui.tr("Áudio", "Audio");
            case SLEEP: return Ui.tr("Timer para dormir", "Sleep timer")
                    + (view.sleepAt() > 0 ? ": " + Ui.time(view.sleepAt()) : "");
            case SUBTITLES: return Ui.tr("Legendas", "Subtitles");
            case SOURCE: {
                Xtream.Channel s = live.source();
                return Ui.tr("Fonte", "Source") + (s == null ? "" : ": " + LiveMerge.label(s))
                        + (live.autoSource() ? Ui.tr(" (automática)", " (automatic)") : "");
            }
            default: return Ui.tr("Imagem", "Picture") + ": " + pictureName(live.aspectMode());
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
        if (onBar) {        // the progress bar: ◀ ▶ seek, ↓ the buttons, OK play/pause (a past program)
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    view.seek(code == KeyEvent.KEYCODE_DPAD_LEFT ? -SKIP_BACK : SKIP_AHEAD);
                    rebuild();
                    view.invalidate();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    onBar = false;
                    view.announceForAccessibility(name(buttons[focus]));
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
        view.announceForAccessibility(name(buttons[focus]));
        view.invalidate();
    }

    private void press(int b) {
        Xtream.Channel ch = live.playing();
        if (ch == null) return;
        switch (b) {
            case GUIDE:
                hide();
                view.openGuide();
                return;
            case ABOUT:
                hide();
                view.aboutPlaying();
                return;
            case PLAY:
                live.togglePause();
                break;
            case LIVE:
                focus = 0;      // first: playing it live shows the live buttons, the focus on the first
                live.backToLive();
                return;
            case START_OVER:
                focus = 0;
                view.startOverPlaying();
                return;
            case RECORD:
                view.toggleRecording();
                break;
            case FAVORITE:
                view.toggleFavorite(ch);
                break;
            case AUDIO:
                chooseTrack(androidx.media3.common.C.TRACK_TYPE_AUDIO, Ui.tr("Áudio", "Audio"));
                break;
            case SLEEP:
                view.sleepMenu();
                break;
            case SUBTITLES:
                chooseTrack(androidx.media3.common.C.TRACK_TYPE_TEXT, Ui.tr("Legendas", "Subtitles"));
                break;
            case SOURCE:
                chooseSource();
                break;
            default:
                int mode = live.nextAspect();
                view.flash(Ui.tr("Imagem", "Picture") + ": " + pictureName(mode));
                break;
        }
        rebuild();
        view.invalidate();
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
