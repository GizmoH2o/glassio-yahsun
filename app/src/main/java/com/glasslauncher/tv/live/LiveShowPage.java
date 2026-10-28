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
    private final Names names;
    private final Page page;
    private final List<Xtream.Channel> airChannels = new ArrayList<>();
    private final List<Xtream.Program> airShows = new ArrayList<>();
    private LiveTmdb.Info info;
    private boolean searched;
    /** 0: the buttons; 1 on: the airings (1 is the first). */
    private int row, button;

    LiveShowPage(Context c, Xtream.Lineup lineup, Xtream.Channel channel, Xtream.Program show, List<String> labels,
            List<String> icons, List<Runnable> actions, Names names, Airing airing) {
        super(c, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        this.channel = channel;
        this.show = show;
        this.labels = labels;
        this.icons = icons;
        this.actions = actions;
        this.airing = airing;
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
                Xtream.mark(p);
                chs.add(ch);
                ps.add(p);
            }
            page.post(() -> {
                airChannels.addAll(chs);
                airShows.addAll(ps);
                searched = true;
                page.invalidate();
            });
        }, "airings").start();
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
        }

        @Override protected void onDraw(Canvas c) {
            if (fill == null) return;
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
            String own = ep == null ? show.about : LiveEpisode.aboutWithout(show.about, show.sub);
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
