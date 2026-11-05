package com.glasslauncher.tv.live;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A show's own page (Apple TV's, Sky's): its picture across the screen, the name, year, genres, age rating and TMDB
 * score, the synopsis and cast, its buttons (watch, record, remind, every episode: what the guide offers for it),
 * then when it's on again on any channel. OK on another airing opens that one's choices; Back closes.
 */
final class LiveShowPage extends Dialog {
    /** Another airing picked: the guide's choices for it (watch it on now, else remind or record). */
    interface Airing {
        void open(Xtream.Channel channel, Xtream.Program p);
    }

    /** A channel as Live TV shows it: its name without the country, and that country's tag ("" for none, or tags off). */
    interface Names {
        String name(Xtream.Channel ch);
        String tag(Xtream.Channel ch);
    }

    private static final int MAX_AIRINGS = 6;
    private final Xtream.Channel channel;
    private final Xtream.Program show;
    private final List<String> labels, icons;
    private final List<Runnable> actions;
    private final Airing airing;
    private final java.util.function.Consumer<LiveRecordings.Rec> recording;
    private final Names names;
    /** Watch again: what's over and can still be seen, newest first; a recording's Rec (else null: the catch-up). */
    private final List<Xtream.Channel> pastChannels = new ArrayList<>();
    private final List<Xtream.Program> pastShows = new ArrayList<>();
    private final List<LiveRecordings.Rec> pastRecs = new ArrayList<>();
    /** Glassio 3's page: which row has the focus (0 buttons, 1 Coming up, 2 Watch again) and where in each. */
    private int airAt, pastAt;
    private final Page page;
    private final List<Xtream.Channel> airChannels = new ArrayList<>();
    private final List<Xtream.Program> airShows = new ArrayList<>();
    private LiveTmdb.Info info;
    private boolean searched;
    /** 0: the buttons; 1 on: the airings (1 is the first). */
    private int row, button;

    LiveShowPage(Context c, Xtream.Lineup lineup, Xtream.Channel channel, Xtream.Program show, List<String> labels,
            List<String> icons, List<Runnable> actions, Names names, Airing airing, java.util.function.Consumer<LiveRecordings.Rec> recording) {
        super(c, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        this.channel = channel;
        this.show = show;
        this.labels = labels;
        this.icons = icons;
        this.actions = actions;
        this.airing = airing;
        this.recording = recording;
        this.names = names;
        page = new Page(c);
        info = LiveTmdb.of(c, show.title, () -> page.post(() -> {
            info = LiveTmdb.of(getContext(), show.title, null);
            page.invalidate();
        }));
        findAirings(c, lineup);
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
        page.announceForAccessibility(show.title);
    }

    /** When it's on again (the full guide, the next week), on the channels of the list. */
    private void findAirings(Context c, final Xtream.Lineup lineup) {
        final String folded = Xtream.fold(LiveArt.name(show.title));
        final Context app = c.getApplicationContext();
        if (folded.isEmpty() || lineup == null || !LiveOptions.fullGuide(app)) {
            searched = true;
            return;
        }
        new Thread(() -> {
            long now = System.currentTimeMillis();
            List<XmltvStore.Found> found = XmltvStore.findAll(app, folded.split(" "), now, now + 7 * 24 * 3600_000L, 200);
            Map<String, Xtream.Channel> byEpg = new HashMap<>();
            // This channel first: another one with the same guide (the same feed, in another category) would list this
            // very airing as another, under the other's number.
            if (channel.epg != null && !channel.epg.isEmpty()) byEpg.put(channel.epg.toLowerCase(Locale.ROOT), channel);
            for (Xtream.Channel ch : lineup.all()) {
                if (ch.epg == null || ch.epg.isEmpty()) continue;
                String id = ch.epg.toLowerCase(Locale.ROOT);
                if (!byEpg.containsKey(id)) byEpg.put(id, ch);
            }
            final List<Xtream.Channel> chs = new ArrayList<>();
            final List<Xtream.Program> ps = new ArrayList<>();
            final List<Object[]> over = pastOf(app, folded, byEpg, now);
            for (XmltvStore.Found f : found) {
                if (ps.size() >= MAX_AIRINGS) break;
                Xtream.Channel ch = f.epg == null ? null : byEpg.get(f.epg.toLowerCase(Locale.ROOT));
                if (ch == null || !Xtream.fold(LiveArt.name(f.title)).equals(folded)) continue;
                if (ch.id == channel.id && f.start == show.start) continue;        // this one
                if (f.end <= now) continue;                                         // over already
                Xtream.Program p = new Xtream.Program();
                p.title = f.title;
                p.start = f.start;
                p.end = f.end;
                p.icon = f.icon == null ? "" : f.icon;
                p.sub = f.sub;
                p.about = f.about;
                Xtream.mark(p);
                chs.add(ch);
                ps.add(p);
            }
            page.post(() -> {
                airChannels.addAll(chs);
                airShows.addAll(ps);
                for (Object[] o : over) {
                    pastChannels.add((Xtream.Channel) o[0]);
                    pastShows.add((Xtream.Program) o[1]);
                    pastRecs.add((LiveRecordings.Rec) o[2]);
                }
                searched = true;
                page.invalidate();
            });
        }, "airings").start();
    }

    private static final int MAX_PAST = 12;

    /** This show's past airings still on the provider's catch-up, and its recordings: newest first, each {channel, show, recording or null}. */
    private List<Object[]> pastOf(Context app, String folded, Map<String, Xtream.Channel> byEpg, long now) {
        List<Object[]> out = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (LiveRecordings.Rec r : LiveRecordings.all(app)) {
            if (!LiveRecordings.DONE.equals(r.state) || r.ch == null || !Xtream.fold(LiveArt.name(r.title)).equals(folded)) continue;
            Xtream.Program p = new Xtream.Program();
            p.title = r.title;
            p.about = r.about == null ? "" : r.about;
            p.start = r.start;
            p.end = r.end;
            seen.add(r.start);
            out.add(new Object[] {r.ch, p, r});
        }
        for (XmltvStore.Found f : XmltvStore.findAll(app, folded.split(" "), now - 14 * 24 * 3600_000L, now, 200)) {
            Xtream.Channel ch = f.epg == null ? null : byEpg.get(f.epg.toLowerCase(Locale.ROOT));
            if (ch == null || ch.archive <= 0 || f.end > now || f.start < now - ch.archive * 24 * 3600_000L) continue;
            if (!Xtream.fold(LiveArt.name(f.title)).equals(folded) || !seen.add(f.start)) continue;
            Xtream.Program p = new Xtream.Program();
            p.title = f.title;
            p.start = f.start;
            p.end = f.end;
            p.icon = f.icon == null ? "" : f.icon;
            p.sub = f.sub;
                p.about = f.about;
            Xtream.mark(p);
            out.add(new Object[] {ch, p, null});
        }
        java.util.Collections.sort(out, (a, b) -> Long.compare(((Xtream.Program) b[1]).start, ((Xtream.Program) a[1]).start));
        return out.size() > MAX_PAST ? new ArrayList<>(out.subList(0, MAX_PAST)) : out;
    }

    /** Glassio 3's keys: ◀ ▶ along a row, ▲ ▼ between the rows that have something. */
    private void glassio3Key(int code, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (row == 0) button = Math.max(0, button - 1);
                else if (row == 1) airAt = Math.max(0, airAt - 1);
                else pastAt = Math.max(0, pastAt - 1);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (row == 0) button = Math.min(labels.size() - 1, button + 1);
                else if (row == 1) airAt = Math.min(airShows.size() - 1, airAt + 1);
                else pastAt = Math.min(pastShows.size() - 1, pastAt + 1);
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (row == 2 && !airShows.isEmpty()) row = 1;
                else if (row > 0 && !labels.isEmpty()) row = 0;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (row == 0 && !airShows.isEmpty()) row = 1;
                else if (row < 2 && !pastShows.isEmpty()) row = 2;
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (e.getRepeatCount() != 0) return;
                if (row == 0) {
                    if (button < actions.size()) {
                        dismiss();
                        actions.get(button).run();
                    }
                } else if (row == 1 && airAt < airShows.size()) {
                    dismiss();
                    airing.open(airChannels.get(airAt), airShows.get(airAt));
                } else if (row == 2 && pastAt < pastShows.size()) {
                    dismiss();
                    if (pastRecs.get(pastAt) != null) recording.accept(pastRecs.get(pastAt));
                    else airing.open(pastChannels.get(pastAt), pastShows.get(pastAt));
                }
                return;
            default:
                return;
        }
        if (row == 0 && !labels.isEmpty()) page.announceForAccessibility(labels.get(button));
        else if (row == 1 && airAt < airShows.size()) page.announceForAccessibility(airShows.get(airAt).title + ", " + when(airShows.get(airAt)));
        else if (row == 2 && pastAt < pastShows.size()) page.announceForAccessibility(pastShows.get(pastAt).title + ", " + day(pastShows.get(pastAt).start));
        page.invalidate();
    }

    // MARK: - Keys

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (code == KeyEvent.KEYCODE_BACK) {
            if (!down && !e.isCanceled()) dismiss();
            return true;
        }
        if (code == KeyEvent.KEYCODE_HOME) return super.dispatchKeyEvent(e);
        if (!down) return true;
        if (LiveTheme.cinema()) {
            glassio3Key(code, e);
            return true;
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (row == 0) button = Math.max(0, button - 1);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (row == 0) button = Math.min(labels.size() - 1, button + 1);
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                row = Math.max(labels.isEmpty() ? 1 : 0, row - 1);
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                row = Math.min(airShows.size(), row + 1);
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (e.getRepeatCount() == 0) pick();
                return true;
            default:
                return true;
        }
        if (row == 0 && !labels.isEmpty()) page.announceForAccessibility(labels.get(button));
        else if (row > 0) page.announceForAccessibility(airingLine(row - 1));
        page.invalidate();
        return true;
    }

    private void pick() {
        if (row == 0) {
            if (button >= actions.size()) return;
            dismiss();
            actions.get(button).run();
        } else if (row - 1 < airShows.size()) {
            dismiss();
            airing.open(airChannels.get(row - 1), airShows.get(row - 1));
        }
    }

    private String airingLine(int i) {
        Xtream.Program p = airShows.get(i);
        return when(p) + "  ·  " + names.name(airChannels.get(i));
    }

    /** An airing's day and time ("Tomorrow  12:10 AM"), or "On now" (OK watches it). */
    private static String when(Xtream.Program p) {
        long now = System.currentTimeMillis();
        return p.start <= now && p.end > now ? Ui.tr("Passando agora", "On now") : day(p.start) + "  " + Ui.time(p.start);
    }

    /** Today, Tomorrow, else the weekday. */
    private static String day(long at) {
        long now = System.currentTimeMillis();
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd", Locale.ROOT);
        String d = f.format(new Date(at));
        if (d.equals(f.format(new Date(now)))) return Ui.tr("Hoje", "Today");
        if (d.equals(f.format(new Date(now + 24 * 3600_000L)))) return Ui.tr("Amanhã", "Tomorrow");
        return new SimpleDateFormat("EEE d MMM", Ui.locale()).format(new Date(at));
    }

    private static boolean isToday(long at) {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd", Locale.ROOT);
        return f.format(new Date(at)).equals(f.format(new Date()));
    }

    /** "Tonight"-less: "Today  ·  20:45", or On now. */
    private static String whenLine(Xtream.Program p) {
        long now = System.currentTimeMillis();
        return p.start <= now && p.end > now ? Ui.tr("Passando agora", "On now") : day(p.start) + "  ·  " + Ui.time(p.start);
    }

    // MARK: - Drawing

    /** The page, drawn by hand (1920 × 1080 space, k = width / 1920). */
    private final class Page extends View {
        private static final float LEFT = 120, TEXT_W = 980;
        private final TextPaint fit = new TextPaint();
        private final Spring in = new Spring(0.32f, 0.9f, 0), at = new Spring(0.28f, 0.9f, 0);
        private final Rect src = new Rect();
        private final RectF box = new RectF();
        private final Paint tagPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Paint fill, image, shadeLeft, shadeBottom, kicker, title, meta, pill, star, body, label, labelOn, head, airTime, airName, episode;
        private TextPaint aboutPaint;
        private StaticLayout about;
        private String aboutText;
        private float k;
        private long last;

        Page(Context c) {
            super(c);
            in.target = 1;
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            k = w / 1920f;
            fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            image = new Paint(Paint.FILTER_BITMAP_FLAG);
            shadeLeft = new Paint();
            // Over the picture's own edges (it fills the right 72% and the top 78%), so none of them shows.
            float left = w * 0.28f;
            int pane = LiveTheme.pane;      // the style's dark (Glassio's black, Apple TV's deep blue...)
            shadeLeft.setShader(new LinearGradient(left, 0, left + w * 0.5f, 0, new int[] {pane, (pane & 0x00FFFFFF) | 0xB3000000, pane & 0x00FFFFFF},
                    new float[] {0, 0.45f, 1}, Shader.TileMode.CLAMP));
            shadeBottom = new Paint();
            shadeBottom.setShader(new LinearGradient(0, h * 0.4f, 0, h * 0.78f, pane & 0x00FFFFFF, pane, Shader.TileMode.CLAMP));
            kicker = Ui.text(Ui.semibold, 26 * k, 0xB3FFFFFF);
            title = Ui.text(Ui.displaySemibold, 76 * k, 0xFFF5F5F7);
            meta = Ui.text(Ui.medium, 28 * k, 0xCCFFFFFF);
            episode = Ui.text(Ui.medium, 30 * k, 0xF2FFFFFF);
            pill = Ui.text(Ui.semibold, 22 * k, 0xFFFFFFFF);
            star = Ui.text(Ui.semibold, 28 * k, 0xFFF5C518);
            aboutPaint = new TextPaint(Ui.text(Ui.regular, 28 * k, 0xD9FFFFFF));
            body = Ui.text(Ui.regular, 24 * k, 0x99FFFFFF);
            label = Ui.text(Ui.semibold, 26 * k, 0xFFF5F5F7);
            labelOn = Ui.text(Ui.semibold, 26 * k, LiveTheme.ink);
            head = Ui.text(Ui.semibold, 30 * k, 0xFFF5F5F7);
            airTime = Ui.text(Ui.semibold, 26 * k, 0xFFF5F5F7);
            airName = Ui.text(Ui.regular, 26 * k, 0x99FFFFFF);
            about = null;
            g3Shade = null;
            g3RingPaint = null;
        }

        // Glassio 3's page, in the 1920 space.
        private Paint g3Shade, g3Bottom, g3Kicker, g3Title, g3Meta, g3Button, g3ButtonOn, g3Head, g3When, g3Ep, g3Ch, g3EpOn, g3WhenOn, g3ChOn;
        private TextPaint g3About;
        private StaticLayout g3AboutLayout;
        private String g3AboutText;
        private final Spring g3Buttons = new Spring(0.28f, 0.9f, 0), g3Air = new Spring(0.28f, 0.9f, 0), g3Past = new Spring(0.28f, 0.9f, 0);
        private final android.graphics.Path g3Clip = new android.graphics.Path();
        private static final int BG = 0xFF0B0B0D;
        private static final float G3_LEFT = 72, G3_IMG_W = 1350, G3_IMG_H = 690;

        private void g3Paints(float w) {
            float ix = w - G3_IMG_W * k;
            g3Shade = new Paint();
            g3Shade.setShader(new LinearGradient(ix, 0, w, 0, new int[] {BG, 0x8C0B0B0D, 0x000B0B0D}, new float[] {0, 0.45f, 1}, Shader.TileMode.CLAMP));
            g3Bottom = new Paint();
            g3Bottom.setShader(new LinearGradient(0, 450 * k, 0, G3_IMG_H * k, 0x000B0B0D, BG, Shader.TileMode.CLAMP));
            g3Kicker = Ui.text(Ui.semibold, 18 * k, 0xFFE50914);
            g3Kicker.setLetterSpacing(0.08f);
            g3Title = Ui.text(Ui.displaySemibold, 66 * k, 0xFFFFFFFF);
            g3Meta = Ui.text(Ui.medium, 21 * k, 0xFFD0D0D4);
            g3About = new TextPaint(Ui.text(Ui.regular, 22.5f * k, 0xFFD0D0D4));
            g3Button = Ui.text(Ui.medium, 24 * k, 0xFFFFFFFF);
            g3ButtonOn = Ui.text(Ui.semibold, 24 * k, BG);
            g3Head = Ui.text(Ui.semibold, 27 * k, 0xFFFFFFFF);
            g3When = Ui.text(Ui.semibold, 18 * k, 0xFFB3B3B8);
            g3Ep = Ui.text(Ui.medium, 21 * k, 0xFFFFFFFF);
            g3Ch = Ui.text(Ui.regular, 18 * k, 0xFFB3B3B8);
            g3WhenOn = Ui.text(Ui.semibold, 18 * k, 0xFF4A4A50);
            g3EpOn = Ui.text(Ui.medium, 21 * k, BG);
            g3ChOn = Ui.text(Ui.regular, 18 * k, 0xFF4A4A50);
        }

        /** "S2 · E7  The Signal Box", or the show's name without an episode. */
        private String episodeLine(Xtream.Program p) {
            LiveEpisode ep = LiveEpisode.of(getContext(), p, false, this::postInvalidate);
            if (ep == null) return p.title.isEmpty() ? channel.name : p.title;
            String n = (ep.season > 0 ? "S" + ep.season + " · " : "") + (ep.episode > 0 ? "E" + ep.episode : "");
            return ep.title == null || ep.title.isEmpty() ? n : n + "  " + ep.title;
        }

        /** The picture filling `to` with round corners; none: the panel's colour. */
        private void g3Picture(Canvas c, Bitmap b, RectF to, float radius) {
            fill.setShader(null);
            fill.setColor(0xFF1C1C1F);
            c.drawRoundRect(to, radius, radius, fill);
            if (b == null) return;
            float s = Math.max(to.width() / b.getWidth(), to.height() / b.getHeight());
            float cw = to.width() / s, chh = to.height() / s;
            src.set((int) ((b.getWidth() - cw) / 2), (int) ((b.getHeight() - chh) / 2), (int) ((b.getWidth() + cw) / 2), (int) ((b.getHeight() + chh) / 2));
            c.save();
            g3Clip.reset();
            g3Clip.addRoundRect(to, radius, radius, android.graphics.Path.Direction.CW);
            c.clipPath(g3Clip);
            c.drawBitmap(b, src, to, image);
            c.restore();
        }

        /** The mockup's page: the picture at the top right, the words and buttons over it, Coming up, then Watch again. */
        private void drawGlassio3(Canvas c) {
            long up = SystemClock.uptimeMillis(), now = System.currentTimeMillis();
            float dt = last == 0 ? 1 / 60f : Math.min(0.05f, (up - last) / 1000f);
            last = up;
            float w = getWidth(), h = getHeight();
            if (g3Shade == null) g3Paints(w);
            boolean moving = in.step(dt);
            float a = Ui.clamp(in.value);
            c.drawColor(BG);
            String url = !show.icon.isEmpty() ? show.icon : info != null ? info.backdrop : "";
            Bitmap hero = url.isEmpty() ? null : LiveLogos.get(getContext(), url, this::postInvalidate);
            if (hero != null) {
                box.set(w - G3_IMG_W * k, 0, w, G3_IMG_H * k);
                float s = Math.max(box.width() / hero.getWidth(), box.height() / hero.getHeight());
                float cw = box.width() / s, chh = box.height() / s;
                src.set((int) ((hero.getWidth() - cw) / 2), (int) ((hero.getHeight() - chh) / 2), (int) ((hero.getWidth() + cw) / 2), (int) ((hero.getHeight() + chh) / 2));
                image.setAlpha(Math.round(255 * a));
                c.drawBitmap(hero, src, box, image);
                image.setAlpha(255);
                c.drawRect(box, g3Shade);
                c.drawRect(box, g3Bottom);
            }
            float x = G3_LEFT * k, tw = 960 * k;
            boolean onNow = show.start <= now && show.end > now, past = show.end <= now;
            String when = onNow ? Ui.tr("AGORA", "ON NOW")
                    : past ? Ui.tr("Exibido", "Aired").toUpperCase(Ui.locale()) + "  ·  " + day(show.start).toUpperCase(Ui.locale()) + "  ·  " + Ui.time(show.start)
                    : isToday(show.start) ? Ui.tr("ÀS ", "ON AT ") + Ui.time(show.start)
                    : day(show.start).toUpperCase(Ui.locale()) + "  ·  " + Ui.time(show.start);
            String where = (channel.num > 0 ? channel.num + " " : "") + names.name(channel);
            c.drawText(ellipsize(when + "  ·  " + where.toUpperCase(Ui.locale()), g3Kicker, tw), x, 72 * k, g3Kicker);
            String name = show.title.isEmpty() ? channel.name : show.title;
            c.drawText(ellipsize(name, g3Title, 1500 * k), x, 147 * k, g3Title);
            // Year, genres, the episode, how long, the age, the score.
            LiveEpisode ep = LiveEpisode.of(getContext(), show, true, this::postInvalidate);
            StringBuilder m = new StringBuilder();
            if (info != null && !info.year.isEmpty()) m.append(info.year);
            if (info != null && !info.genres.isEmpty()) m.append(m.length() > 0 ? "  ·  " : "").append(info.genres);
            if (ep != null && (ep.season > 0 || ep.episode > 0)) {
                m.append(m.length() > 0 ? "  ·  " : "").append(ep.season > 0 ? "S" + ep.season + " · " : "").append(ep.episode > 0 ? "E" + ep.episode : "");
            }
            long mins = (show.end - show.start) / 60_000;
            if (mins > 0) m.append(m.length() > 0 ? "  ·  " : "").append(mins).append(" min");
            int age = LiveTmdb.age(getContext(), show);
            if (age > 0) m.append("  ·  ").append(age).append("+");
            if (info != null && info.votes >= 5 && info.score > 0) m.append("  ·  ★ ").append(String.format(Locale.ROOT, "%.1f", info.score));
            float y = 197 * k;
            if (m.length() > 0) c.drawText(ellipsize(m.toString(), g3Meta, tw), x, y, g3Meta);
            y += 21 * k;
            String own = LiveNames.about(getContext(), show.title, ep == null ? show.about : LiveEpisode.aboutWithout(show.about, show.sub));
            String text = !own.isEmpty() ? own : info != null ? info.overview : "";
            if (!text.equals(g3AboutText)) {
                g3AboutText = text;
                g3AboutLayout = text.isEmpty() ? null : StaticLayout.Builder.obtain(text, 0, text.length(), g3About, Math.round(tw))
                        .setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(6 * k, 1).build();
            }
            if (g3AboutLayout != null) {
                c.save();
                c.translate(x, y);
                g3AboutLayout.draw(c);
                c.restore();
                y += g3AboutLayout.getHeight();
            }
            // The buttons, the one in focus white; along the row when they don't fit.
            float by = y + 20 * k, bh = 68 * k, gap = 15 * k;
            float focusEnd = 0, bx = 0;
            for (int i = 0; i < labels.size(); i++) {
                float bw = g3Button.measureText(labels.get(i)) + 66 * k;
                if (i == button) focusEnd = bx + bw;
                bx += bw + gap;
            }
            g3Buttons.target = Math.max(0, focusEnd - (w - x - 72 * k));
            moving |= g3Buttons.step(dt);
            bx = x - g3Buttons.value;
            for (int i = 0; i < labels.size(); i++) {
                String l = labels.get(i);
                float bw = g3Button.measureText(l) + 66 * k;
                boolean on = row == 0 && i == button;
                box.set(bx, by, bx + bw, by + bh);
                fill.setShader(null);
                fill.setColor(on ? 0xFFFFFFFF : 0x2EFFFFFF);
                c.drawRoundRect(box, 18 * k, 18 * k, fill);
                c.drawText(l, bx + 33 * k, by + bh / 2 + Ui.halfCap(g3Button), on ? g3ButtonOn : g3Button);
                bx += bw + gap;
            }
            // Coming up.
            c.drawText(Ui.tr("Em breve", "Coming up"), x, 472 * k, g3Head);
            float cy = 495 * k, cw3 = 420 * k, ch3 = 118 * k, step = cw3 + 18 * k;
            if (airShows.isEmpty()) {
                c.drawText(searched ? Ui.tr("Não passa de novo nos próximos dias", "Not on again in the next few days")
                        : Ui.tr("Procurando…", "Looking…"), x, cy + 30 * k, g3Ch);
            }
            g3Air.target = Math.max(0, (airAt + 1) * step - (w - x - 72 * k));
            moving |= g3Air.step(dt);
            for (int i = 0; i < airShows.size(); i++) {
                float cx = x + i * step - g3Air.value;
                if (cx > w || cx + cw3 < 0) continue;
                boolean on = row == 1 && i == airAt;
                box.set(cx, cy, cx + cw3, cy + ch3);
                fill.setColor(on ? 0xFFFFFFFF : 0xFF1C1C1F);
                c.drawRoundRect(box, 18 * k, 18 * k, fill);
                Xtream.Program p = airShows.get(i);
                Xtream.Channel ac = airChannels.get(i);
                float tx = cx + 21 * k, room = cw3 - 42 * k;
                c.drawText(ellipsize(whenLine(p), on ? g3WhenOn : g3When, room), tx, cy + 36 * k, on ? g3WhenOn : g3When);
                c.drawText(ellipsize(episodeLine(p), on ? g3EpOn : g3Ep, room), tx, cy + 68 * k, on ? g3EpOn : g3Ep);
                c.drawText(ellipsize((ac.num > 0 ? ac.num + " " : "") + names.name(ac), on ? g3ChOn : g3Ch, room), tx, cy + 97 * k, on ? g3ChOn : g3Ch);
            }
            // Watch again: what can still be seen, from the catch-up or a recording.
            if (!pastShows.isEmpty()) {
                c.drawText(Ui.tr("Assistir de novo", "Watch again"), x, 670 * k, g3Head);
                float py = 693 * k, pw = 384 * k, ph = 216 * k, pstep = pw + 24 * k;
                g3Past.target = Math.max(0, (pastAt + 1) * pstep - (w - x - 72 * k));
                moving |= g3Past.step(dt);
                for (int i = 0; i < pastShows.size(); i++) {
                    float px = x + i * pstep - g3Past.value;
                    if (px > w || px + pw < 0) continue;
                    Xtream.Program p = pastShows.get(i);
                    boolean on = row == 2 && i == pastAt;
                    String art = !p.icon.isEmpty() ? p.icon : info != null ? info.backdrop : "";
                    box.set(px, py, px + pw, py + ph);
                    g3Picture(c, art.isEmpty() ? null : LiveLogos.get(getContext(), art, this::postInvalidate), box, 18 * k);
                    if (on) {
                        Paint ring = g3Ring();
                        box.inset(2.25f * k, 2.25f * k);
                        c.drawRoundRect(box, 16 * k, 16 * k, ring);
                    }
                    c.drawText(ellipsize(episodeLine(p), g3Ep, pw), px, py + ph + 36 * k, g3Ep);
                    String how = pastRecs.get(i) != null ? Ui.tr("Gravado", "Recorded") : Ui.tr("Reprise", "Catch-up");
                    c.drawText(ellipsize(day(p.start) + "  " + Ui.time(p.start) + "  ·  " + how, g3Ch, pw), px, py + ph + 63 * k, g3Ch);
                }
            }
            if (moving) postInvalidateOnAnimation(); else last = 0;
        }

        private Paint g3RingPaint;

        private Paint g3Ring() {
            if (g3RingPaint == null) {
                g3RingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                g3RingPaint.setStyle(Paint.Style.STROKE);
                g3RingPaint.setColor(0xFFFFFFFF);
                g3RingPaint.setStrokeWidth(4.5f * k);
            }
            return g3RingPaint;
        }

        @Override protected void onDraw(Canvas c) {
            if (fill == null) return;
            if (LiveTheme.cinema()) {
                drawGlassio3(c);
                return;
            }
            long up = SystemClock.uptimeMillis(), now = System.currentTimeMillis();
            float dt = last == 0 ? 1 / 60f : Math.min(0.05f, (up - last) / 1000f);
            last = up;
            at.target = row;
            boolean moving = in.step(dt) | at.step(dt);
            float a = Ui.clamp(in.value), w = getWidth(), h = getHeight();
            c.drawColor(LiveTheme.pane);

            // The picture: the guide's, else TMDB's, filling the right of the screen.
            String url = !show.icon.isEmpty() ? show.icon : info != null ? info.backdrop : "";
            Bitmap b = url.isEmpty() ? null : LiveLogos.get(getContext(), url, this::postInvalidate);
            if (b != null) {
                float iw = w * 0.72f, ih = h * 0.78f, s = Math.max(iw / b.getWidth(), ih / b.getHeight());
                float cw = iw / s, chh = ih / s;
                src.set((int) ((b.getWidth() - cw) / 2), (int) ((b.getHeight() - chh) / 2),
                        (int) ((b.getWidth() + cw) / 2), (int) ((b.getHeight() + chh) / 2));
                box.set(w - iw, 0, w, ih);
                image.setAlpha(Math.round(255 * a));
                c.drawBitmap(b, src, box, image);
                c.drawRect(box, shadeLeft);
                c.drawRect(box, shadeBottom);
            }

            c.save();
            c.translate(0, (1 - a) * 40 * k);
            float x = LEFT * k, y = 150 * k;
            boolean onNow = show.start <= now && show.end > now, past = show.end <= now;
            String when = (onNow ? Ui.tr("Agora", "Now") : day(show.start)) + "  ·  " + Ui.time(show.start) + " – " + Ui.time(show.end);
            // The channel and its country tag, then when: the name cut short first, never the tag or the time.
            String rest = "  ·  " + when;
            float lineEnd = textAndTag(c, (channel.num > 0 ? channel.num + "  " : "") + names.name(channel), names.tag(channel), x, y, kicker,
                    TEXT_W * k - kicker.measureText(rest));
            c.drawText(ellipsize(rest, kicker, x + TEXT_W * k - lineEnd), lineEnd, y, kicker);
            y += 92 * k;
            String name = show.title.isEmpty() ? channel.name : show.title;
            c.drawText(ellipsize(name, title, 1500 * k), x, y, title);

            // Pills and facts: LIVE / LATER / CATCH-UP, year, genres, rating, TMDB score.
            y += 62 * k;
            float px = x;
            px = drawPill(c, onNow ? Ui.tr("AO VIVO", "LIVE") : past ? Ui.tr("REPRISE", "CATCH-UP") : Ui.tr("DEPOIS", "LATER"), px, y,
                    onNow ? 0xFFE50914 : past ? 0xFF2F6FED : 0x40FFFFFF) + 14 * k;
            int age = LiveTmdb.age(getContext(), show);
            if (age > 0) px = drawPill(c, age + "+", px, y, LiveTheme.chip()) + 14 * k;
            if (show.fresh) px = drawPill(c, Ui.tr("NOVO", "NEW"), px, y, LiveTheme.chip()) + 14 * k;
            StringBuilder facts = new StringBuilder();
            if (info != null && !info.year.isEmpty()) facts.append(info.year);
            if (info != null && !info.genres.isEmpty()) facts.append(facts.length() > 0 ? "  ·  " : "").append(info.genres);
            if (facts.length() > 0) {
                String f = facts.toString();
                c.drawText(ellipsize(f, meta, 760 * k), px + 4 * k, y + 15 * k + Ui.halfCap(meta), meta);
                px += Math.min(760 * k, meta.measureText(f)) + 28 * k;
            }
            if (info != null && info.votes >= 5 && info.score > 0) {
                String s = "★ " + String.format(Locale.ROOT, "%.1f", info.score);
                c.drawText(s, px, y + 15 * k + Ui.halfCap(star), star);
                px += star.measureText(s) + 10 * k;
                c.drawText("TMDB", px, y + 15 * k + Ui.halfCap(body), body);
            }

            // The episode (its season and number on a chip, then its title), the synopsis (the guide's, else TMDB's) up
            // to five lines, the cast under it.
            y += 70 * k;
            LiveEpisode ep = LiveEpisode.of(getContext(), show, true, this::postInvalidate);
            if (ep != null) {
                LiveEpisode.draw(c, ep, x, y + episode.getTextSize() * 0.8f, episode, TEXT_W * k, k);
                y += episode.getTextSize() * 1.9f;
            }
            String own = LiveNames.about(getContext(), show.title, ep == null ? show.about : LiveEpisode.aboutWithout(show.about, show.sub));
            String text = !own.isEmpty() ? own : info != null ? info.overview : "";
            if (!text.equals(aboutText)) {
                aboutText = text;
                about = text.isEmpty() ? null : StaticLayout.Builder.obtain(text, 0, text.length(), aboutPaint, Math.round(TEXT_W * k))
                        .setMaxLines(5).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(6 * k, 1)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
            }
            if (about != null) {
                c.save();
                c.translate(x, y);
                about.draw(c);
                c.restore();
                y += about.getHeight() + 20 * k;
            } else if (info == null && !searched) {
                y += 20 * k;
            }
            if (info != null && !info.cast.isEmpty()) {
                y += 28 * k;
                c.drawText(ellipsize(Ui.tr("Elenco: ", "Starring: ") + info.cast, body, TEXT_W * k), x, y, body);
            }

            // The buttons.
            float by = Math.max(y + 56 * k, 640 * k), bh = 76 * k;
            float bx = x;
            for (int i = 0; i < labels.size(); i++) {
                boolean on = row == 0 && i == button;
                String l = labels.get(i);
                float tw = label.measureText(l), bw = tw + 108 * k;
                box.set(bx, by, bx + bw, by + bh);
                fill.setColor(on ? LiveTheme.tile : LiveTheme.surface | 0x0A000000);
                c.drawRoundRect(box, bh / 2 * Math.min(1, LiveTheme.round), bh / 2 * Math.min(1, LiveTheme.round), fill);
                Symbols.draw(getContext(), c, icons.get(i), bx + 46 * k, by + bh / 2, 30 * k, on ? LiveTheme.ink : 0xFFF5F5F7);
                c.drawText(l, bx + 76 * k, by + bh / 2 + Ui.halfCap(label), on ? labelOn : label);
                bx += bw + 18 * k;
                if (bx > 1800 * k) break;
            }

            // When it's on again.
            float ay = by + bh + 76 * k;
            c.drawText(Ui.tr("Outras vezes na grade", "More airings"), x, ay, head);
            ay += 22 * k;
            if (airShows.isEmpty()) {
                c.drawText(searched ? Ui.tr("Não passa de novo nos próximos dias", "Not on again in the next few days")
                        : Ui.tr("Procurando…", "Looking…"), x, ay + 40 * k, body);
            }
            float rh = 58 * k;
            int fits = Math.max(1, (int) ((h - 30 * k - ay) / rh)), first = Math.max(0, row - fits);     // scrolled to the focus
            for (int i = first; i < airShows.size() && i < first + fits; i++) {
                float ry = ay + (i - first) * rh;
                float on = row == i + 1 ? 1 : 0;
                if (on > 0.01f) {
                    box.set(x - 20 * k, ry, x + TEXT_W * k, ry + rh - 6 * k);
                    fill.setColor(Ui.alpha(LiveTheme.surface | 0x0A000000, on));
                    c.drawRoundRect(box, 14 * k * LiveTheme.round, 14 * k * LiveTheme.round, fill);
                }
                Xtream.Program p = airShows.get(i);
                float ty = ry + (rh - 6 * k) / 2 + Ui.halfCap(airTime);
                c.drawText(when(p), x, ty, airTime);
                Xtream.Channel ch = airChannels.get(i);
                textAndTag(c, (ch.num > 0 ? ch.num + "  " : "") + names.name(ch), names.tag(ch), x + 360 * k, ty, airName, 560 * k);
            }
            c.restore();
            if (moving) postInvalidateOnAnimation(); else last = 0;
        }

        /** A small tag at x (its top at y); returns its right edge. */
        private float drawPill(Canvas c, String text, float x, float y, int color) {
            float tw = pill.measureText(text), pw = tw + 26 * k, ph = 34 * k;
            fill.setColor(color);
            c.drawRoundRect(x, y, x + pw, y + ph, 8 * k, 8 * k, fill);
            c.drawText(text, x + 13 * k, y + ph / 2 + Ui.halfCap(pill), pill);
            return x + pw;
        }

        /** `text` at `x`, then its country tag; both within `room` (the text cut short, never the tag). Returns where they end. */
        private float textAndTag(Canvas c, String text, String tag, float x, float base, Paint p, float room) {
            LiveCountry.label(p, tagPaint);
            float tagW = LiveCountry.tagWidth(tag, p, tagPaint, k);
            String t = ellipsize(text, p, Math.max(1, room - tagW));
            c.drawText(t, x, base, p);
            float end = x + p.measureText(t);
            return tag.isEmpty() ? end : LiveCountry.drawTag(c, tag, end + 12 * k, base, p, tagPaint, k);
        }

        private String ellipsize(String s, Paint p, float width) {
            fit.set(p);
            return TextUtils.ellipsize(s, fit, Math.max(1, width), TextUtils.TruncateAt.END).toString();
        }
    }
}
