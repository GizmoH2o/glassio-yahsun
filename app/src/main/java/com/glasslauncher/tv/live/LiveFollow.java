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
    /** A cancelled match as it's written (its other listings stay out too). */
    private static final String MATCH = "=";

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
        String k = LiveNames.key(term);
        for (String t : all(c)) if (LiveNames.key(t).equals(k)) return true;
        return false;
    }

    /** Whether every match of what's followed is recorded, not just reminded of. */
    static synchronized boolean recordsMatches(Context c, String term) {
        JSONArray a = read(c).optJSONArray("record");
        String k = Xtream.fold(term);
        for (int i = 0; a != null && i < a.length(); i++) if (k.equals(a.optString(i))) return true;
        return false;
    }

    /** Sets it, and makes the airings ahead again that way. Background thread. */
    static void setRecordsMatches(Context c, String term, boolean on) {
        String k = Xtream.fold(term);
        LiveLock.run(c, () -> { synchronized (LiveFollow.class) {
            JSONObject o = read(c);
            JSONArray a = o.optJSONArray("record"), out = new JSONArray();
            for (int i = 0; a != null && i < a.length(); i++) if (!k.equals(a.optString(i))) out.put(a.optString(i));
            if (on) out.put(k);
            try {
                o.put("record", out);
            } catch (JSONException ignored) {
            }
            save(c, o);
        } });
        for (LiveSchedule.Item it : LiveSchedule.all(c)) {
            if ((SERIES + k).equals(it.series)) LiveSchedule.remove(c, it.key());
        }
        sync(c);
    }

    /** The other names typed for what's followed ("Man U, MUFC"). */
    static synchronized String alsoCalled(Context c, String term) {
        JSONObject also = read(c).optJSONObject("also");
        return also == null ? "" : also.optString(Xtream.fold(term));
    }

    /** All the names typed, by what's followed (folded). */
    static synchronized Map<String, String> alsoAll(Context c) {
        Map<String, String> out = new HashMap<>();
        JSONObject also = read(c).optJSONObject("also");
        if (also == null) return out;
        java.util.Iterator<String> keys = also.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            out.put(k, also.optString(k));
        }
        return out;
    }

    /** Sets the other names, then looks through the guide again with them. Background thread. */
    static void setAlsoCalled(Context c, String term, String typed) {
        String k = Xtream.fold(term), names = typed == null ? "" : typed.trim();
        LiveLock.run(c, () -> { synchronized (LiveFollow.class) {
            JSONObject o = read(c);
            JSONObject also = o.optJSONObject("also");
            try {
                if (also == null) also = new JSONObject();
                if (names.isEmpty()) also.remove(k);
                else also.put(k, names);
                o.put("also", also);
            } catch (JSONException ignored) {
            }
            save(c, o);
        } });
        LiveNames.changed();
        for (LiveSchedule.Item it : LiveSchedule.all(c)) {
            if ((SERIES + k).equals(it.series)) LiveSchedule.remove(c, it.key());
        }
        sync(c);
    }

    /** Follows `term`, and puts in its airings already in the full guide (a background thread). */
    static void add(Context c, String term) {
        final Context app = c.getApplicationContext();
        LiveLock.run(app, () -> { synchronized (LiveFollow.class) {
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
        } });
        LiveSeries.arm(app);
        new Thread(() -> sync(app), "follow").start();
    }

    /** Stops following `term`: its reminders still to come go too. */
    static void remove(Context c, String term) {
        String k = LiveNames.key(term);
        Set<String> gone = new HashSet<>();
        gone.add(SERIES + Xtream.fold(term));
        LiveLock.run(c, () -> { synchronized (LiveFollow.class) {
            JSONObject o = read(c);
            JSONArray a = o.optJSONArray("terms"), out = new JSONArray();
            JSONObject also = o.optJSONObject("also");
            for (int i = 0; a != null && i < a.length(); i++) {
                String t = a.optString(i);
                if (!LiveNames.key(t).equals(k)) {
                    out.put(t);
                    continue;
                }
                gone.add(SERIES + Xtream.fold(t));
                if (also != null) also.remove(Xtream.fold(t));
            }
            try {
                o.put("terms", out);
            } catch (JSONException ignored) {
            }
            save(c, o);
        } });
        LiveNames.changed();
        for (LiveSchedule.Item it : LiveSchedule.all(c)) {
            if (it.series != null && gone.contains(it.series)) LiveSchedule.remove(c, it.key());
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
        String[] sides = rest.split("(?i)\\s+(?:v|vs\\.?|x|@|/)\\s+");
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
    static void skip(Context c, LiveSchedule.Item it) {
        LiveLock.run(c, () -> skipNow(c, it));
    }

    private static synchronized void skipNow(Context c, LiveSchedule.Item it) {
        if (it.series == null || !it.series.startsWith(SERIES)) return;
        JSONObject o = read(c);
        JSONArray s = o.optJSONArray("skip");
        if (s == null) s = new JSONArray();
        s.put(Xtream.fold(it.title) + "|" + it.start);
        if (LiveMatchGroup.of(it.title, it.start) != null) s.put(MATCH + it.title + "|" + it.start);
        try {
            o.put("skip", s);
        } catch (JSONException ignored) {
        }
        save(c, o);
    }

    /** A cancelled one is back: not left out any more. */
    static void unskip(Context c, LiveSchedule.Item it) {
        LiveLock.run(c, () -> unskipNow(c, it));
    }

    private static synchronized void unskipNow(Context c, LiveSchedule.Item it) {
        if (it.series == null || !it.series.startsWith(SERIES)) return;
        JSONObject o = read(c);
        JSONArray s = o.optJSONArray("skip"), keep = new JSONArray();
        String id = Xtream.fold(it.title) + "|" + it.start, match = MATCH + it.title + "|" + it.start;
        for (int i = 0; s != null && i < s.length(); i++) if (!id.equals(s.optString(i)) && !match.equals(s.optString(i))) keep.put(s.optString(i));
        try {
            o.put("skip", keep);
        } catch (JSONException ignored) {
        }
        save(c, o);
    }

    /** How good a channel is to watch something on: a favorite, then the most watched, then History; trouble counts against. Pure. */
    static long rank(boolean favorite, int seconds, boolean recent, int trouble) {
        return (favorite ? 1_000_000_000L : 0) + seconds * 10L + (recent ? 5_000 : 0) - trouble * 2_000L;
    }

    /** rank, for many channels (its files read once). */
    static final class Rank {
        private final Context c;
        private final Set<Integer> favorites = new HashSet<>(), recent = new HashSet<>();
        private final Map<Integer, Integer> seconds;

        Rank(Context c) {
            this.c = c;
            for (Xtream.Channel f : Xtream.favorites(c)) favorites.add(f.id);
            for (Xtream.Channel r : Xtream.recent(c)) recent.add(r.id);
            seconds = LiveWatchTime.totals(c);
        }

        long of(Xtream.Channel ch) {
            Integer s = seconds.get(ch.id);
            return rank(favorites.contains(ch.id), s == null ? 0 : s, recent.contains(ch.id), LiveHealth.recent(c, ch.id, 7 * 24 * 3600_000L));
        }
    }

    /** The saved channels with the edits and the guides found by name, as Live TV has them; null: none. Background thread. */
    private static Xtream.Lineup matched(Context c) {
        Xtream.Lineup l = Xtream.saved(c);
        if (l == null) return null;
        LiveChannelEdits.apply(c, l);
        LiveGroups.apply(c, l);
        XmltvStore.matchNames(c, l);
        return l;
    }

    /** The channels only in groups hidden or locked. */
    private static Set<Integer> away(Context c, Xtream.Lineup l) {
        return LiveHide.away(c, l, true);
    }

    /** Every channel showing `it` then ({channel, program}), its own first, then the best. Background thread. */
    static List<Object[]> showing(Context c, LiveSchedule.Item it) {
        List<Object[]> out = new ArrayList<>(), more = new ArrayList<>();
        Xtream.Program own = new Xtream.Program();
        own.title = it.title;
        own.start = it.start;
        own.end = it.end;
        out.add(new Object[] {it.ch, own});
        Xtream.Lineup l = LiveOptions.fullGuide(c) ? matched(c) : null;
        String key = Xtream.fold(it.title), teams = LiveHome.teamsKey(it.title);
        if (l == null || key.isEmpty()) return out;
        Set<Integer> away = away(c, l);
        Map<String, List<Xtream.Channel>> byEpg = new HashMap<>();
        for (Xtream.Channel ch : l.all()) {
            if (ch.epg == null || ch.epg.isEmpty() || away.contains(ch.id)) continue;
            String id = ch.epg.toLowerCase(Locale.ROOT);
            List<Xtream.Channel> at = byEpg.get(id);
            if (at == null) byEpg.put(id, at = new ArrayList<>());
            at.add(ch);
        }
        final Rank rank = new Rank(c);
        List<String[]> words = new ArrayList<>();
        words.add(key.split(" "));
        for (XmltvStore.Found f : XmltvStore.findAny(c, words, it.start - 15 * 60_000L, it.start + 15 * 60_000L, 200)) {
            if (Math.abs(f.start - it.start) > 15 * 60_000L || f.epg == null) continue;
            if (!Xtream.fold(f.title).equals(key) && (teams == null || !teams.equals(LiveHome.teamsKey(f.title)))) continue;
            List<Xtream.Channel> chs = byEpg.get(f.epg.toLowerCase(Locale.ROOT));
            if (chs == null) continue;
            Xtream.Program p = new Xtream.Program();
            p.title = f.title;
            p.start = f.start;
            p.end = f.end;
            for (Xtream.Channel ch : chs) if (ch.id != it.ch.id) more.add(new Object[] {ch, p});
        }
        java.util.Collections.sort(more, (a, b) -> Long.compare(rank.of((Xtream.Channel) b[0]), rank.of((Xtream.Channel) a[0])));
        Set<String> names = new HashSet<>();        // one of each channel's copies: the best
        names.add(LiveSuggest.copyKey(it.ch.name));
        for (Object[] m : more) {
            if (out.size() > 20) break;
            if (names.add(LiveSuggest.copyKey(((Xtream.Channel) m[0]).name))) out.add(m);
        }
        return out;
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
        if (terms.isEmpty() || programs == null || LiveHide.away(c).contains(ch.id)) return;
        long now = System.currentTimeMillis();
        Set<String> skipped = skipped(c);
        for (String term : terms) {
            String k = Xtream.fold(term);
            LiveNames.Names names = LiveNames.of(c, k);
            LiveSchedule.dropMoved(c, ch.id, SERIES + k, programs);
            for (Xtream.Program p : programs) {
                if (p.start <= now || p.start > now + AHEAD) continue;
                if (!names.in(Xtream.fold(p.title))) continue;
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
        Set<Integer> hide = LiveHide.away(c, lineup, false);
        for (Xtream.Channel ch : lineup.all()) {
            if (ch.epg == null || ch.epg.isEmpty() || hide.contains(ch.id)) continue;
            String id = ch.epg.toLowerCase(Locale.ROOT);
            List<Xtream.Channel> l = byEpg.get(id);
            if (l == null) byEpg.put(id, l = new ArrayList<>());
            l.add(ch);
        }
        String term = it.series.substring(SERIES.length());
        boolean all = LiveOptions.followAll(c);
        List<Object[]> more = new ArrayList<>();
        for (XmltvStore.Found f : LiveNames.find(c, LiveNames.of(c, term), it.start, it.start + SAME_MATCH, 80)) {
            if (f.epg == null) continue;
            Xtream.Program p = new Xtream.Program();
            p.title = f.title;
            p.start = f.start;
            p.end = f.end;
            Xtream.mark(p);
            LiveTags.apply(c, p, f.tags);
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
        Xtream.Lineup lineup = LiveOptions.bestChannel(c) ? matched(c) : Xtream.saved(c);
        if (lineup == null) return;
        Map<String, Xtream.Channel> byEpg = new HashMap<>();
        Set<Integer> away = away(c, lineup), hide = LiveHide.away(c, lineup, false);
        for (LiveSchedule.Item it : LiveSchedule.all(c)) {      // set before its channel was hidden
            if (isAlert(it) && !it.mine && hide.contains(it.ch.id)) LiveSchedule.remove(c, it.key());
        }
        final Rank rank = LiveOptions.bestChannel(c) ? new Rank(c) : null;
        final Map<Integer, Long> ranks = new HashMap<>();
        for (Xtream.Channel ch : lineup.all()) {
            if (ch.epg == null || ch.epg.isEmpty() || hide.contains(ch.id)) continue;
            String id = ch.epg.toLowerCase(Locale.ROOT);
            Xtream.Channel had = byEpg.get(id);
            if (rank != null) ranks.put(ch.id, rank.of(ch) - (away.contains(ch.id) ? 2_000_000_000L : 0));
            if (had == null || rank != null && ranks.get(ch.id) > ranks.get(had.id)) byEpg.put(id, ch);
        }
        long now = System.currentTimeMillis();
        Set<String> skipped = skipped(c);
        for (String term : terms) {
            String k = Xtream.fold(term);
            int n = 0;
            List<XmltvStore.Found> found = LiveNames.find(c, LiveNames.of(c, k), now, now + AHEAD, 80);
            if (rank != null) {     // the same match on several: the best channel's first (it's the one set)
                java.util.Collections.sort(found, (a, b) -> {
                    if (a.start != b.start) return Long.compare(a.start, b.start);
                    return Long.compare(rankOn(b, byEpg, ranks), rankOn(a, byEpg, ranks));
                });
            }
            List<Object[]> list = new ArrayList<>();        // {channel, program, its match or null}
            for (XmltvStore.Found f : found) {
                if (f.start <= now) continue;
                Xtream.Channel ch = f.epg == null ? null : byEpg.get(f.epg.toLowerCase(Locale.ROOT));
                if (ch == null) continue;
                Xtream.Program p = new Xtream.Program();
                p.title = f.title;
                p.start = f.start;
                p.end = f.end;
                Xtream.mark(p);
                LiveTags.apply(c, p, f.tags);     // its LIVE mark (taken out of the title)
                if (p.live || LiveOptions.followAll(c)) list.add(new Object[] {ch, p, LiveMatchGroup.of(p.title, p.start)});
            }
            // One listing of each match: the match itself before a show about it, then the best channel's, then the first.
            String team = LiveNames.known(k);
            java.util.Collections.sort(list, (a, b) -> {
                LiveMatchGroup ga = (LiveMatchGroup) a[2], gb = (LiveMatchGroup) b[2];
                boolean sa = ga != null && ga.show, sb = gb != null && gb.show;
                if (sa != sb) return sa ? 1 : -1;
                if (rank == null || ga == null || gb == null) return 0;
                return Long.compare(ranks.get(((Xtream.Channel) b[0]).id), ranks.get(((Xtream.Channel) a[0]).id));
            });
            List<LiveMatchGroup> matches = new ArrayList<>();
            for (Object[] one : list) matches.add((LiveMatchGroup) one[2]);
            List<Object[]> chosen = new ArrayList<>();
            for (Object[] one : list) {
                LiveMatchGroup g = (LiveMatchGroup) one[2];
                boolean again = false;
                for (Object[] had : chosen) again |= g != null && g.same((LiveMatchGroup) had[2], team);
                for (Object[] first : list) again |= g != null && g.rerunOf((LiveMatchGroup) first[2]);
                again |= g != null && g.shownAgain(matches);
                if (!again) chosen.add(one);
            }
            java.util.Collections.sort(chosen, (a, b) -> Long.compare(((Xtream.Program) a[1]).start, ((Xtream.Program) b[1]).start));
            for (LiveSchedule.Item it : LiveSchedule.all(c)) {       // a rerun set before
                if (!(SERIES + k).equals(it.series) || it.mine || it.auto || it.lead >= 0 || it.at > 0) continue;
                LiveMatchGroup g = LiveMatchGroup.of(it.title, it.start);
                boolean again = false;
                for (Object[] first : list) again |= g != null && g.rerunOf((LiveMatchGroup) first[2]);
                again |= g != null && g.shownAgain(matches);
                if (again) LiveSchedule.remove(c, it.key());
            }
            for (Object[] one : chosen) {
                if (n >= PER_TERM) break;
                tidy(c, k, team, (Xtream.Channel) one[0], (Xtream.Program) one[1], (LiveMatchGroup) one[2], rank != null, list);
                if (remind(c, k, (Xtream.Channel) one[0], (Xtream.Program) one[1], skipped)) n++;
            }
        }
    }

    /** A match that started (told of): not again for its other listings or its reruns. */
    static void told(Context c, LiveSchedule.Item it) {
        LiveLock.run(c, () -> toldNow(c, it));
    }

    private static synchronized void toldNow(Context c, LiveSchedule.Item it) {
        if (it == null || it.series == null || !it.series.startsWith(SERIES)) return;
        LiveMatchGroup g = LiveMatchGroup.of(it.title, it.start);
        if (g == null || g.show) return;
        String id = MATCH + it.title + "|" + it.start;
        JSONObject o = read(c);
        JSONArray s = o.optJSONArray("skip");
        if (s == null) s = new JSONArray();
        for (int i = 0; i < s.length(); i++) if (id.equals(s.optString(i))) return;
        s.put(id);
        try {
            o.put("skip", s);
        } catch (JSONException ignored) {
        }
        save(c, o);
    }

    /** The reminders there for one match: one stays (as it was set, when it was; else `p` on `ch`, with `best`). */
    private static void tidy(Context c, String term, String team, Xtream.Channel ch, Xtream.Program p, LiveMatchGroup g, boolean best,
                             List<Object[]> list) {
        if (g == null) return;
        long soon = System.currentTimeMillis() + 10 * 60_000L;
        List<LiveSchedule.Item> own = new ArrayList<>();
        LiveSchedule.Item stays = null;
        for (LiveSchedule.Item it : LiveSchedule.all(c)) {
            LiveMatchGroup of = LiveMatchGroup.of(it.title, it.start);
            if (LiveSchedule.SWITCH.equals(it.kind) || of == null || !g.same(of, team)) continue;
            boolean mine = (SERIES + term).equals(it.series);
            if (!mine) continue;
            LiveSchedule.Item moved = moved(it, list);
            if (moved != null) {        // its channel shows it at another time now: as it was set
                LiveSchedule.remove(c, it.key());
                LiveSchedule.add(c, moved);
                it = moved;
            }
            if (it.mine || it.auto || it.lead >= 0 || it.at > 0 || it.start < soon) stays = it;
            else own.add(it);
            if (mine && stays == null && !of.show && !best) stays = it;
            else if (mine && stays == null && it.ch.id == ch.id && it.start == p.start) stays = it;
        }
        for (LiveSchedule.Item it : own) {
            if (it != stays) LiveSchedule.remove(c, it.key());
        }
    }

    /** `it` at its channel's new time (the same title there, at no longer `it`'s time), or null. */
    private static LiveSchedule.Item moved(LiveSchedule.Item it, List<Object[]> list) {
        if (it.at > 0 || it.ch.epg == null || it.ch.epg.isEmpty()) return null;
        Xtream.Program to = null;
        for (Object[] one : list) {
            Xtream.Program p = (Xtream.Program) one[1];
            if (!it.ch.epg.equalsIgnoreCase(((Xtream.Channel) one[0]).epg) || Math.abs(p.start - it.start) > LiveMatchGroup.NEAR
                    || !Xtream.fold(p.title).equals(Xtream.fold(it.title))) continue;
            if (p.start == it.start) return null;
            if (to == null || Math.abs(p.start - it.start) < Math.abs(to.start - it.start)) to = p;
        }
        if (to == null) return null;
        LiveSchedule.Item out = it.as(it.kind);
        out.was = it.was > 0 ? it.was : it.start;
        out.start = to.start;
        out.end = to.end;
        AppLog.w("follow: the guide moved a match to " + to.start);
        return out;
    }

    /** Whether the match was told of already and is on now. */
    private static boolean started(Context c, LiveMatchGroup g, String team) {
        for (LiveSchedule.Item it : LiveSchedule.onNow(c)) {
            LiveMatchGroup of = LiveMatchGroup.of(it.title, it.start);
            if (of != null && !of.show && g.same(of, team)) return true;
        }
        return false;
    }

    private static long rankOn(XmltvStore.Found f, Map<String, Xtream.Channel> byEpg, Map<Integer, Long> ranks) {
        Xtream.Channel ch = f.epg == null ? null : byEpg.get(f.epg.toLowerCase(Locale.ROOT));
        Long r = ch == null ? null : ranks.get(ch.id);
        return r == null ? Long.MIN_VALUE : r;
    }

    /** A reminder for `p` on `ch` (unless one is there for that show then, on any channel, or it was cancelled). */
    private static boolean remind(Context c, String term, Xtream.Channel ch, Xtream.Program p, Set<String> skipped) {
        if (!p.live && !LiveOptions.followAll(c)) return false;     // Match alerts: live events only
        String id = Xtream.fold(p.title) + "|" + p.start;
        if (skipped.contains(id)) return false;
        // The same match under another name ("Man Utd v Roma"), or a show about it: cancelled, told of or set already is the same.
        String match = LiveHome.teamsKey(p.title), team = LiveNames.known(term);
        LiveMatchGroup g = LiveMatchGroup.of(p.title, p.start);
        if (g != null) {
            for (String s : skipped) {
                int bar = s.lastIndexOf('|');
                if (bar <= 0 || !s.startsWith(MATCH)) continue;
                try {
                    LiveMatchGroup of = LiveMatchGroup.of(s.substring(MATCH.length(), bar), Long.parseLong(s.substring(bar + 1)));
                    if (of != null && (g.show || !of.show) && (g.same(of, team) || g.rerunOf(of))) return false;
                } catch (NumberFormatException ignored) {
                }
            }
            if (started(c, g, team)) return false;
        }
        int mine = 0;
        for (LiveSchedule.Item it : LiveSchedule.all(c)) {
            if (it.start == p.start && Xtream.fold(it.title).equals(Xtream.fold(p.title))) return false;     // there already
            LiveMatchGroup of = g == null ? null : LiveMatchGroup.of(it.title, it.start);
            if (of != null && (g.show || !of.show) && (g.same(of, team) || g.rerunOf(of))) return false;
            if ((SERIES + term).equals(it.series)) mine++;
        }
        if (mine >= PER_TERM) return false;
        LiveSchedule.Item it = new LiveSchedule.Item();
        it.kind = match != null && recordsMatches(c, term) ? LiveSchedule.RECORD : LiveSchedule.REMIND;     // a show about it: a reminder
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
