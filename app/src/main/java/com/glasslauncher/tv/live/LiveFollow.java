package com.glasslauncher.tv.live;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Following (NOW's, YouTube TV's): a team, a show or anything by its words ("Arsenal", "Formula 1"). Every airing on
 * any channel whose title has all of them, in the next day and a half, gets a reminder: from the full guide, and from
 * each channel's guide as Live TV loads it, again every few hours with the series' alarm. One reminder per airing
 * (a show on several copies of a channel: once); one cancelled by hand stays out. Home shows them in a row.
 */
final class LiveFollow {
    private LiveFollow() {}

    private static final long AHEAD = 36 * 3600_000L;
    /** At most this many reminders per word in the time ahead (a word as common as "news" won't flood the TV). */
    private static final int PER_TERM = 8;
    static final String SERIES = "follow:";

    /** What's followed, as typed ("Arsenal"), the order they were added. */
    static synchronized List<String> all(Context c) {
        List<String> out = new ArrayList<>();
        JSONArray a = read(c).optJSONArray("terms");
        for (int i = 0; a != null && i < a.length(); i++) {
            String t = a.optString(i);
            if (!t.trim().isEmpty()) out.add(t);
        }
        return out;
    }

    /** A followed word as it was typed ("Chelsea") for its folded form ("chelsea"); the folded one if it's gone. */
    static String named(Context c, String folded) {
        for (String t : all(c)) if (Xtream.fold(t).equals(folded)) return t;
        return folded;
    }

    static boolean follows(Context c, String term) {
        String k = Xtream.fold(term);
        for (String t : all(c)) if (Xtream.fold(t).equals(k)) return true;
        return false;
    }

    /** Follows `term`, and puts in its airings already in the full guide (a background thread). */
    static void add(Context c, String term) {
        final Context app = c.getApplicationContext();
        synchronized (LiveFollow.class) {
            if (follows(app, term)) return;
            JSONObject o = read(app);
            JSONArray a = o.optJSONArray("terms");
            if (a == null) a = new JSONArray();
            a.put(term.trim());
            try {
                o.put("terms", a);
            } catch (JSONException ignored) {
            }
            save(app, o);
        }
        LiveSeries.arm(app);
        new Thread(() -> sync(app), "follow").start();
    }

    /** Stops following `term`: its reminders still to come go too. */
    static void remove(Context c, String term) {
        String k = Xtream.fold(term);
        synchronized (LiveFollow.class) {
            JSONObject o = read(c);
            JSONArray a = o.optJSONArray("terms"), out = new JSONArray();
            for (int i = 0; a != null && i < a.length(); i++) if (!Xtream.fold(a.optString(i)).equals(k)) out.put(a.optString(i));
            try {
                o.put("terms", out);
            } catch (JSONException ignored) {
            }
            save(c, o);
        }
        for (LiveSchedule.Item it : LiveSchedule.all(c)) {
            if ((SERIES + k).equals(it.series)) LiveSchedule.remove(c, it.key());
        }
        LiveSeries.arm(c);
    }

    /** Whether a reminder came from something followed (a match alert). */
    static boolean isAlert(LiveSchedule.Item it) {
        return it != null && it.series != null && it.series.startsWith(SERIES);
    }

    /**
     * What a show's title offers to follow: a match's teams and its competition ("Premier League : Aston Villa v
     * Manchester City"), else the title itself (without a trailing episode or year).
     */
    static List<String> choices(String title) {
        List<String> out = new ArrayList<>();
        String t = title == null ? "" : title.trim();
        String comp = null, rest = t;
        int colon = t.indexOf(':');
        if (colon > 0) {
            comp = t.substring(0, colon).trim();
            rest = t.substring(colon + 1).trim();
        }
        String[] sides = rest.split("(?i)\\s+(?:v|vs\\.?|x|@)\\s+");
        if (sides.length == 2) {
            for (String s : sides) {
                String team = s.replaceAll("\\s*[(\\[].*$", "").trim();
                if (!team.isEmpty() && !out.contains(team)) out.add(team);
            }
            if (comp != null && !comp.isEmpty()) out.add(comp);
        } else {
            String show = t.replaceAll("\\s*[(\\[].*$", "").replaceAll("\\s+S\\d+\\s*E\\d+.*$", "").trim();
            if (!show.isEmpty()) out.add(show);
        }
        return out;
    }

    /** A reminder a follow put in, cancelled by hand: left out from now on. */
    static synchronized void skip(Context c, LiveSchedule.Item it) {
        if (it.series == null || !it.series.startsWith(SERIES)) return;
        JSONObject o = read(c);
        JSONArray s = o.optJSONArray("skip");
        if (s == null) s = new JSONArray();
        s.put(Xtream.fold(it.title) + "|" + it.start);
        try {
            o.put("skip", s);
        } catch (JSONException ignored) {
        }
        save(c, o);
    }

    /** Whether `title` has every word of `term` (both folded), as words. */
    static boolean matches(String term, String title) {
        String t = " " + title + " ";
        for (String w : term.split(" ")) if (!w.isEmpty() && !t.contains(" " + w + " ")) return false;
        return !term.isEmpty();
    }

    /** A channel's programs just loaded (Live TV's guide): the airings of what's followed into the schedule. */
    static void match(Context c, Xtream.Channel ch, List<Xtream.Program> programs) {
        List<String> terms = all(c);
        if (terms.isEmpty() || programs == null) return;
        long now = System.currentTimeMillis();
        Set<String> skipped = skipped(c);
        for (String term : terms) {
            String k = Xtream.fold(term);
            for (Xtream.Program p : programs) {
                if (p.start <= now || p.start > now + AHEAD) continue;
                String title = Xtream.fold(p.title);
                if (!matches(k, title)) continue;
                remind(c, k, ch, p, skipped);
            }
        }
    }

    /** How long after the alert's start an airing elsewhere still counts as the same match (a later kick-off coverage). */
    private static final long SAME_MATCH = 15 * 60_000L;

    /**
     * Every channel showing what match alert `it` is about at its start: its own channel first, then the others by
     * number, one per channel name (copies of a channel once), each {Xtream.Channel, Xtream.Program}. The full guide's,
     * when it's on; else only its own. Background thread.
     */
    static List<Object[]> airing(Context c, LiveSchedule.Item it) {
        List<Object[]> out = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Xtream.Program own = new Xtream.Program();
        own.title = it.title;
        own.start = it.start;
        own.end = it.end;
        out.add(new Object[] {it.ch, own});
        names.add(Xtream.fold(it.ch.name));
        if (!isAlert(it) || !LiveOptions.fullGuide(c)) return out;
        Xtream.Lineup lineup = Xtream.saved(c);
        if (lineup == null) return out;
        Map<String, List<Xtream.Channel>> byEpg = new HashMap<>();
        for (Xtream.Channel ch : lineup.all()) {
            if (ch.epg == null || ch.epg.isEmpty()) continue;
            String id = ch.epg.toLowerCase(Locale.ROOT);
            List<Xtream.Channel> l = byEpg.get(id);
            if (l == null) byEpg.put(id, l = new ArrayList<>());
            l.add(ch);
        }
        String term = it.series.substring(SERIES.length());
        boolean all = LiveOptions.followAll(c);
        List<Object[]> more = new ArrayList<>();
        for (XmltvStore.Found f : XmltvStore.findAll(c, term.split(" "), it.start, it.start + SAME_MATCH, 80)) {
            if (f.epg == null || !matches(term, Xtream.fold(f.title))) continue;
            Xtream.Program p = new Xtream.Program();
            p.title = f.title;
            p.start = f.start;
            p.end = f.end;
            Xtream.mark(p);
            if (!p.live && !all) continue;      // Match alerts: live events only
            List<Xtream.Channel> chs = byEpg.get(f.epg.toLowerCase(Locale.ROOT));
            if (chs == null) continue;
            for (Xtream.Channel ch : chs) if (names.add(Xtream.fold(ch.name))) more.add(new Object[] {ch, p});
        }
        java.util.Collections.sort(more, (a, b) -> Integer.compare(((Xtream.Channel) a[0]).num, ((Xtream.Channel) b[0]).num));
        out.addAll(more);
        return out;
    }

    /** Match alerts changed: the followed reminders made again (the ones cancelled by hand stay out). Background thread. */
    static void redo(Context c) {
        for (LiveSchedule.Item it : LiveSchedule.all(c)) {
            if (it.series != null && it.series.startsWith(SERIES)) LiveSchedule.remove(c, it.key());
        }
        sync(c);
    }

    /** Every followed word through the full guide (any channel), into reminders. Background thread. */
    static void sync(Context c) {
        List<String> terms = all(c);
        if (terms.isEmpty() || !LiveOptions.fullGuide(c)) return;
        Xtream.Lineup lineup = Xtream.saved(c);
        if (lineup == null) return;
        Map<String, Xtream.Channel> byEpg = new HashMap<>();
        for (Xtream.Channel ch : lineup.all()) {
            if (ch.epg == null || ch.epg.isEmpty()) continue;
            String id = ch.epg.toLowerCase(Locale.ROOT);
            if (!byEpg.containsKey(id)) byEpg.put(id, ch);
        }
        long now = System.currentTimeMillis();
        Set<String> skipped = skipped(c);
        for (String term : terms) {
            String k = Xtream.fold(term);
            int n = 0;
            for (XmltvStore.Found f : XmltvStore.findAll(c, k.split(" "), now, now + AHEAD, 80)) {
                if (n >= PER_TERM) break;
                if (f.start <= now || !matches(k, Xtream.fold(f.title))) continue;
                Xtream.Channel ch = f.epg == null ? null : byEpg.get(f.epg.toLowerCase(Locale.ROOT));
                if (ch == null) continue;
                Xtream.Program p = new Xtream.Program();
                p.title = f.title;
                p.start = f.start;
                p.end = f.end;
                Xtream.mark(p);     // its LIVE mark (taken out of the title)
                if (remind(c, k, ch, p, skipped)) n++;
            }
        }
    }

    /** A reminder for `p` on `ch` (unless one is there for that show then, on any channel, or it was cancelled). */
    private static boolean remind(Context c, String term, Xtream.Channel ch, Xtream.Program p, Set<String> skipped) {
        if (!p.live && !LiveOptions.followAll(c)) return false;     // Match alerts: live events only
        String id = Xtream.fold(p.title) + "|" + p.start;
        if (skipped.contains(id)) return false;
        int mine = 0;
        for (LiveSchedule.Item it : LiveSchedule.all(c)) {
            if (it.start == p.start && Xtream.fold(it.title).equals(Xtream.fold(p.title))) return false;     // there already
            if ((SERIES + term).equals(it.series)) mine++;
        }
        if (mine >= PER_TERM) return false;
        LiveSchedule.Item it = new LiveSchedule.Item();
        it.kind = LiveSchedule.REMIND;
        it.ch = ch;
        it.start = p.start;
        it.end = p.end;
        it.title = p.title;
        it.about = p.about;
        it.series = SERIES + term;
        LiveSchedule.add(c, it);
        AppLog.w("follow: reminder at " + p.start);
        return true;
    }

    private static Set<String> skipped(Context c) {
        Set<String> out = new HashSet<>();
        JSONArray s = read(c).optJSONArray("skip");
        for (int i = 0; s != null && i < s.length(); i++) out.add(s.optString(i));
        return out;
    }

    private static JSONObject read(Context c) {
        File f = file(c);
        if (!f.exists()) return new JSONObject();
        try (FileInputStream in = new FileInputStream(f)) {
            return new JSONObject(Xtream.read(in));
        } catch (IOException | JSONException e) {
            return new JSONObject();
        }
    }

    private static void save(Context c, JSONObject o) {
        try {
            // Only the skips still ahead are worth keeping.
            JSONArray s = o.optJSONArray("skip"), keep = new JSONArray();
            long old = System.currentTimeMillis() - 24 * 3600_000L;
            for (int i = 0; s != null && i < s.length(); i++) {
                String v = s.optString(i);
                int bar = v.lastIndexOf('|');
                try {
                    if (bar > 0 && Long.parseLong(v.substring(bar + 1)) > old) keep.put(v);
                } catch (NumberFormatException ignored) {
                }
            }
            o.put("skip", keep);
            Xtream.write(file(c), o.toString());
        } catch (IOException | JSONException e) {
            AppLog.w("live: couldn't save what's followed");
        }
    }

    private static File file(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), "live-follow.json");
    }
}
