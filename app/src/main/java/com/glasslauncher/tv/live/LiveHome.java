package com.glasslauncher.tv.live;

import android.app.Application;
import android.content.Context;
import android.content.Intent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What Glassio's home screen shows of Live TV (its Live TV and Following pages, drawn in Compose): the same choices
 * Zero's home screen tabs made (HomePages), as plain data. Every read is blocking: call it off the main thread.
 */
public final class LiveHome {
    private LiveHome() {}

    /** One program on one channel, as a page shows it. */
    public static final class Airing {
        public int channel, number;
        public String channelName = "", logo = "", title = "", teams = "", sub = "", art = "", term = "";
        public long start, end;
        /** A live broadcast (the guide's mark); a reminder or a recording set for it. */
        public boolean live, remind, record;
        /** The live score's line ("2 – 1  63'"), "" without one. */
        public String score = "";
        /** On a team's page: the day's name above its first match ("Today", "Sat 18"), "" for the next ones. */
        public String day = "";
        Xtream.Channel ch;
        Xtream.Program p;

        public boolean onNow(long now) {
            return start <= now && end > now;
        }

        /** Same airing (for keeping the focus on it as the page is read again). */
        public String key() {
            return channel + ":" + start + ":" + title;
        }
    }

    /** The Following page: the spotlight (the soonest match, on now first), then the rows under it. */
    public static final class Following {
        public final List<String> terms = new ArrayList<>();
        public Airing spot;
        public final List<Airing> also = new ArrayList<>(), next = new ArrayList<>(), shows = new ArrayList<>();
    }

    /** A followed team's (or show's) page: its next 7 days' matches (each with its day's name), then the rest. */
    public static final class Team {
        public final List<Airing> matches = new ArrayList<>(), shows = new ArrayList<>();
    }

    /** The Live TV page: the channel last watched, the favorites and recent channels on now, sports, soon. */
    public static final class Page {
        public Airing last;
        public final List<Airing> onNow = new ArrayList<>(), sports = new ArrayList<>(), soon = new ArrayList<>();
    }

    // MARK: - Glassio's side

    /** Every process: Live TV's settings readable, its crashes kept; the home screen's process watches the TV waking. */
    public static void init(Application app) {
        CrashLog.install(app);
        Prefs.init(app);
        if (app.getPackageName().equals(Application.getProcessName())) LiveStart.watchWake(app);
    }

    /** The home screen is showing: Live TV's "open when the TV starts". */
    public static void homeShown(Context c) {
        LiveStart.homeShown(c);
    }

    /** Signed in to a provider, with its channels read. */
    public static boolean ready(Context c) {
        return Xtream.ready(c);
    }

    /** Live TV on a channel (-1: the one last watched), or on its guide. */
    public static Intent open(Context c, int channel, boolean guide) {
        return HomeApps.liveIntent(c, channel, guide);
    }

    /** Live TV's own search (its results have Follow). */
    public static Intent search(Context c) {
        return HomeApps.liveIntent(c, -1, false).putExtra(Live.EXTRA_SEARCH, "");
    }

    public static List<String> followed(Context c) {
        return LiveFollow.all(c);
    }

    public static boolean follows(Context c, String term) {
        return LiveFollow.follows(c, term);
    }

    /** Follows a team or a show (its airings already in the full guide come in the background). */
    public static void follow(Context c, String term) {
        if (term == null || term.trim().isEmpty()) return;
        LiveFollow.add(c, term.trim());
    }

    /** Stops following it: its reminders still to come go too. */
    public static void unfollow(Context c, String term) {
        LiveFollow.remove(c, term);
    }

    /**
     * Remind (or Record, `remind` false) set for an airing, or cancelled when it was. Returns what to tell the
     * person ("Reminder at 8:00 PM").
     */
    public static String toggle(Context c, Airing a, boolean remind) {
        boolean set = remind ? a.remind : a.record;
        String kind = remind ? LiveSchedule.REMIND : LiveSchedule.RECORD;
        if (set) {
            LiveSchedule.cancel(c, kind + ":" + a.ch.id + ":" + a.p.start);
        } else {
            LiveSchedule.Item s = new LiveSchedule.Item();
            s.kind = kind;
            s.ch = a.ch;
            s.start = a.p.start;
            s.end = a.p.end;
            s.title = a.p.title;
            s.about = a.p.about;
            LiveSchedule.add(c, s);
        }
        if (remind) a.remind = !set;
        else a.record = !set;
        return remind ? (set ? "Reminder cancelled" : "Reminder at " + Ui.time(a.p.start))
                : (set ? "Recording cancelled" : "Records at " + Ui.time(a.p.start));
    }

    /** "Today", "Tomorrow", "Sat 18". */
    public static String day(long t, long now) {
        java.util.Calendar a = java.util.Calendar.getInstance(), b = java.util.Calendar.getInstance();
        a.setTimeInMillis(now);
        b.setTimeInMillis(t);
        int days = (b.get(java.util.Calendar.YEAR) - a.get(java.util.Calendar.YEAR)) * 366 + b.get(java.util.Calendar.DAY_OF_YEAR)
                - a.get(java.util.Calendar.DAY_OF_YEAR);
        if (days == 0) return "Today";
        if (days == 1) return "Tomorrow";
        return new java.text.SimpleDateFormat("EEE d", Locale.US).format(new java.util.Date(t));
    }

    /** "8:00 PM" (Live TV's own way). */
    public static String time(long t) {
        return Ui.time(t);
    }

    /** "in 45 min", "in 2 h 10 min". */
    public static String until(long ms) {
        long m = Math.max(1, (ms + 59_999) / 60_000);
        if (m < 60) return "in " + m + " min";
        long h = m / 60, rest = m % 60;
        return "in " + h + " h" + (rest > 0 ? " " + rest + " min" : "");
    }

    // MARK: - Match day (the screensaver)

    /** A followed team's next match, as the screensaver shows it (two lines under its clock). */
    public static final class MatchDay {
        /** "FOLLOWING" or the competition ("PREMIER LEAGUE"); the score once it's on (MUN 1–0 BOU · 63'), else the match. */
        public String label = "", headline = "";
        /** LIVE (or the kick-off time) and the channel. */
        public String where = "";
        public boolean live;
        public int channel;
        /** When it starts (wall clock), and the same key while it's the same match (the home screen's kick-off alert). */
        public long start;
        public String key = "";
    }

    /**
     * The soonest followed match starting within 18 hours, from the follow reminders (nothing asked online); null
     * when there's none. From half an hour before, its live score is asked for (Live scores on).
     */
    public static MatchDay matchDay(Context c) {
        LiveMatchDay.Match m = LiveMatchDay.next(c);
        if (m == null) return null;
        long now = System.currentTimeMillis();
        if (LiveMatchDay.soon(m, now) && LiveOptions.scores(c)) LiveScores.get(c).want(m.term);
        MatchDay out = new MatchDay();
        String comp = competition(m.title);
        out.label = (comp.isEmpty() ? "Following" : comp).toUpperCase(Locale.US);
        out.headline = LiveMatchDay.headline(m, LiveMatchDay.score(c, m));
        out.where = LiveMatchDay.where(m, now);
        out.live = now >= m.start;
        out.channel = m.ch.id;
        out.start = m.start;
        out.key = m.ch.id + ":" + m.start;
        return out;
    }

    /**
     * Live scores read while a page or the screensaver shows them (ESPN's boards, only for a followed team on air);
     * `changed` runs on the main thread when one moves. Hand the result back to stopScores.
     */
    public static Object watchScores(Context c, Runnable changed) {
        LiveScores.Listener l = new LiveScores.Listener() {
            @Override public void changed() {
                changed.run();
            }

            @Override public void goal(LiveScores.Score s, boolean forHome) {
                changed.run();
            }
        };
        if (LiveOptions.scores(c)) LiveScores.get(c).watch(l);
        return l;
    }

    public static void stopScores(Context c, Object watching) {
        if (watching instanceof LiveScores.Listener) LiveScores.get(c).unwatch((LiveScores.Listener) watching);
    }

    // MARK: - Following

    public static Following following(Context c) {
        Following out = new Following();
        long now = System.currentTimeMillis();
        List<String> terms = LiveFollow.all(c);
        out.terms.addAll(terms);
        if (terms.isEmpty()) return out;
        Set<String> marks = marks(c);
        Map<String, Long> earlier = earlier(c, terms, now);
        List<Object[]> list = airings(c, terms, now, 36 * 3600_000L, 40);
        if (!list.isEmpty() && LiveOptions.scores(c)) LiveScores.get(c).want((String) list.get(0)[2]);
        Map<String, Long> first = firstAirings(list, earlier);
        for (int i = 1; i < list.size(); i++) {
            if (!firstAiring(list.get(i), first)) continue;
            if (firstAiring(list.get(0), first)) break;
            list = new ArrayList<>(list);       // a match ahead of the shows in the spotlight
            list.add(0, list.remove(i));
            break;
        }
        if (list.isEmpty()) return out;
        out.spot = airing(c, list.get(0), marks);
        // After the spotlight: the same match on other channels, the other matches, then replays and the shows about
        // them (a match's later airings are replays: Match Day Review, the 9:00 one on the club's channel).
        Xtream.Program sp = (Xtream.Program) list.get(0)[1];
        String spotKey = teamsKey(sp.title);
        List<Object[]> also = new ArrayList<>(), next = new ArrayList<>(), shows = new ArrayList<>();
        for (int i = 1; i < list.size(); i++) {
            Xtream.Program p = (Xtream.Program) list.get(i)[1];
            String k = teamsKey(p.title);
            if (k != null && k.equals(spotKey) && Math.abs(p.start - sp.start) <= SAME_AIRING) also.add(list.get(i));
            else if (firstAiring(list.get(i), first)) next.add(list.get(i));
            else shows.add(list.get(i));
        }
        int left = 12;
        for (int i = 0; i < also.size() && left > 0; i++, left--) out.also.add(airing(c, also.get(i), marks));
        next = merged(next);
        for (int i = 0; i < next.size() && left > 0; i++, left--) out.next.add(airing(c, next.get(i), marks));
        for (int i = 0; i < shows.size() && left > 0; i++, left--) out.shows.add(airing(c, shows.get(i), marks));
        return out;
    }

    public static Team team(Context c, String name) {
        Team out = new Team();
        long now = System.currentTimeMillis();
        Set<String> marks = marks(c);
        Map<String, Long> earlier = earlier(c, Collections.singletonList(name), now);
        List<Object[]> list = airings(c, Collections.singletonList(name), now, 7 * 24 * 3600_000L, 200);
        Collections.sort(list, (a, b) -> Long.compare(((Xtream.Program) a[1]).start, ((Xtream.Program) b[1]).start));
        Map<String, Long> first = firstAirings(list, earlier);
        List<Object[]> matches = new ArrayList<>(), shows = new ArrayList<>();
        for (Object[] a : list) (firstAiring(a, first) ? matches : shows).add(a);
        matches = merged(matches);
        String day = null;
        for (int i = 0; i < matches.size() && i < 24; i++) {
            Airing a = airing(c, matches.get(i), marks);
            String d = day(a.start, now);
            a.day = d.equals(day) ? "" : d;
            day = d;
            out.matches.add(a);
        }
        for (int i = 0; i < shows.size() && i < 10; i++) out.shows.add(airing(c, shows.get(i), marks));
        return out;
    }

    /** Airings of the same match within this of each other: the same airing on other channels. */
    private static final long SAME_AIRING = 30 * 60_000L;

    private static Airing airing(Context c, Object[] o, Set<String> marks) {
        Airing a = airing(c, (Xtream.Channel) o[0], (Xtream.Program) o[1]);
        a.term = o.length > 2 ? (String) o[2] : "";
        // The line under it: the competition, and the match's other channels.
        String comp = competition(a.title), also = o.length > 3 ? (String) o[3] : "";
        a.sub = also.isEmpty() ? comp : comp.isEmpty() ? also : comp + "  ·  " + also;
        a.remind = marks.contains(LiveSchedule.REMIND + ":" + a.channel + ":" + a.start);
        a.record = marks.contains(LiveSchedule.RECORD + ":" + a.channel + ":" + a.start);
        long now = System.currentTimeMillis();
        if (a.onNow(now) && !a.term.isEmpty() && LiveOptions.scores(c)) {
            LiveScores.Score s = LiveScores.get(c).forTerm(a.term);
            if (s != null && !"pre".equals(s.state)) a.score = s.homeScore + " – " + s.awayScore + (s.clock.isEmpty() ? "" : "   " + s.clock);
        }
        return a;
    }

    private static Airing airing(Context c, Xtream.Channel ch, Xtream.Program p) {
        Airing a = new Airing();
        a.ch = ch;
        a.p = p;
        a.channel = ch.id;
        a.number = ch.num;
        a.channelName = ch.name == null ? "" : ch.name;
        a.logo = ch.logo == null ? "" : ch.logo;
        if (p != null) {
            a.title = p.title;
            a.teams = LiveMatchDay.teams(p.title);
            a.start = p.start;
            a.end = p.end;
            a.live = p.live;
            // The guide's picture, else TMDB's for the show (found once, then known).
            a.art = LiveArt.of(c, p, null);
        }
        return a;
    }

    /**
     * One row per match: its other channels at about the same time go into the first one's 4th place ("Also on MUTV,
     * TV4"), in order. Airings that aren't matches stay as they are.
     */
    private static List<Object[]> merged(List<Object[]> list) {
        List<Object[]> out = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        for (Object[] a : list) {
            Xtream.Program p = (Xtream.Program) a[1];
            String k = teamsKey(p.title);
            int same = -1;
            for (int i = 0; k != null && i < out.size(); i++) {
                if (k.equals(keys.get(i)) && Math.abs(((Xtream.Program) out.get(i)[1]).start - p.start) <= SAME_AIRING) same = i;
            }
            if (same < 0) {
                out.add(new Object[] {a[0], a[1], a[2], ""});
                keys.add(k);
                continue;
            }
            Object[] row = out.get(same);
            String name = ((Xtream.Channel) a[0]).name;
            row[3] = ((String) row[3]).isEmpty() ? "Also on " + name : row[3] + ", " + name;
        }
        return out;
    }

    /** Whether `airing` is a match's first airing (or the same one on another channel): not a replay, not a show. */
    private static boolean firstAiring(Object[] airing, Map<String, Long> first) {
        Xtream.Program p = (Xtream.Program) airing[1];
        String k = teamsKey(p.title);
        return k != null && first.containsKey(k) && p.start <= first.get(k) + SAME_AIRING;
    }

    /** Each match's first airing (its teams' key → start), counting those earlier today; later ones are replays. */
    private static Map<String, Long> firstAirings(List<Object[]> list, Map<String, Long> earlier) {
        Map<String, Long> out = new HashMap<>(earlier);
        for (Object[] a : list) {
            Xtream.Program p = (Xtream.Program) a[1];
            String k = teamsKey(p.title);
            if (k == null) continue;
            Long had = out.get(k);
            if (had == null || p.start < had) out.put(k, p.start);
        }
        return out;
    }

    private static Set<String> marks(Context c) {
        Set<String> out = new HashSet<>();
        for (LiveSchedule.Item it : LiveSchedule.all(c)) if (it.ch != null) out.add(it.key());
        return out;
    }

    /**
     * The airings of `terms` from now to `ahead`: from the full guide (on now too), else the follow reminders. As the
     * reminders count them: live events only, unless Match alerts is All airings. One channel per airing; on now
     * first, a match (X v Y) before the shows about it; at most `max`.
     */
    private static List<Object[]> airings(Context c, List<String> terms, long now, long ahead, int max) {
        List<Object[]> out = new ArrayList<>();
        Set<String> seen = new HashSet<>(), folded = new HashSet<>();
        boolean all = LiveOptions.followAll(c);
        Map<String, Xtream.Channel> byEpg = LiveOptions.fullGuide(c) ? byEpg(c) : new HashMap<>();
        for (String term : terms) {
            String k = Xtream.fold(term);
            folded.add(k);
            if (byEpg.isEmpty()) continue;
            for (XmltvStore.Found f : XmltvStore.findAll(c, k.split(" "), now, now + ahead, max * 2)) {
                Xtream.Channel ch = f.epg == null ? null : byEpg.get(f.epg.toLowerCase(Locale.ROOT));
                if (ch == null) continue;
                Xtream.Program p = new Xtream.Program();
                p.title = f.title;
                p.start = f.start;
                p.end = f.end;
                p.icon = f.icon == null ? "" : f.icon;
                Xtream.mark(p);
                if (!p.live && !all) continue;
                if (!LiveFollow.matches(k, Xtream.fold(p.title))) continue;
                if (!seen.add(Xtream.fold(p.title) + "|" + p.start)) continue;
                out.add(new Object[] {ch, p, k});
            }
        }
        for (LiveSchedule.Item it : LiveSchedule.all(c)) {     // without the full guide (or a channel it lacks)
            if (it.series == null || !it.series.startsWith(LiveFollow.SERIES) || it.ch == null || it.end <= now) continue;
            if (it.start > now + ahead || !folded.contains(it.series.substring(LiveFollow.SERIES.length()))) continue;
            if (it.kind.equals(LiveSchedule.SWITCH) || !seen.add(Xtream.fold(it.title) + "|" + it.start)) continue;
            Xtream.Program p = new Xtream.Program();
            p.title = it.title;
            p.start = it.start;
            p.end = it.end;
            p.about = it.about;
            out.add(new Object[] {it.ch, p, it.series.substring(LiveFollow.SERIES.length())});
        }
        Collections.sort(out, (a, b) -> {
            Xtream.Program x = (Xtream.Program) a[1], y = (Xtream.Program) b[1];
            boolean xn = x.start <= now, yn = y.start <= now;
            if (xn != yn) return xn ? -1 : 1;
            if (xn && match(x.title) != match(y.title)) return match(x.title) ? -1 : 1;
            return Long.compare(x.start, y.start);
        });
        while (out.size() > max) out.remove(out.size() - 1);
        return out;
    }

    /** The matches of `terms` that started in the last 12 hours, each one's earliest start (from the full guide). */
    private static Map<String, Long> earlier(Context c, List<String> terms, long now) {
        Map<String, Long> out = new HashMap<>();
        if (!LiveOptions.fullGuide(c)) return out;
        for (String term : terms) {
            String k = Xtream.fold(term);
            for (XmltvStore.Found f : XmltvStore.findAll(c, k.split(" "), now - 12 * 3600_000L, now, 80)) {
                if (f.start >= now || !LiveFollow.matches(k, Xtream.fold(f.title))) continue;
                String key = teamsKey(f.title);
                if (key == null) continue;
                Long had = out.get(key);
                if (had == null || f.start < had) out.put(key, f.start);
            }
        }
        return out;
    }

    /** "Manchester United v Bournemouth": a match, not a show about one. */
    private static boolean match(String title) {
        return teamsKey(title) != null;
    }

    // What a show about a match has in its name (Match Day Review: Arsenal v Spurs), folded.
    private static final String[] SHOWS = {"review", "highlights", "preview", "reaction", "build up", "classic", "replay",
            "rewind", "magazine", "match day", "matchday", "analysis", "news", "show", "podcast", "goals", "best of",
            "story", "talk", "countdown", "press conference", "extra time", "warm up", "the big match", "flashback"};
    // Between the two sides of a match, in the title as it's written (fold drops the "@" and "-").
    private static final String[] VERSUS = {" v ", " vs ", " vs. ", " x ", " - ", " – ", " @ ", " at "};
    // Left out of a side's name, so "AFC Bournemouth" and "Bournemouth" are the same team.
    // "(F)" and "(W)": the women's side, as the guide marks it on some channels and not others.
    private static final Set<String> CLUB_WORDS = new HashSet<>(java.util.Arrays.asList("fc", "afc", "cf", "sc", "the", "f", "w"));

    /** The two sides of a match, sorted and folded ("bournemouth|manchester united"); null for anything else. */
    private static String teamsKey(String title) {
        String f = " " + Xtream.fold(title) + " ";
        for (String w : SHOWS) if (f.contains(" " + w + " ")) return null;
        String t = LiveMatchDay.teams(title), low = t.toLowerCase(Locale.ROOT);
        for (String v : VERSUS) {
            int at = low.indexOf(v);
            if (at <= 0 || at + v.length() >= t.length()) continue;
            String a = side(t.substring(0, at)), b = side(t.substring(at + v.length()));
            if (a.isEmpty() || b.isEmpty()) return null;
            return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
        }
        return null;
    }

    private static String side(String s) {
        StringBuilder b = new StringBuilder();
        for (String w : Xtream.fold(s).split(" ")) {
            if (w.isEmpty() || CLUB_WORDS.contains(w)) continue;
            if (b.length() > 0) b.append(' ');
            b.append(w);
        }
        return b.toString();
    }

    private static String competition(String title) {
        int at = title.lastIndexOf(':');
        return at > 0 ? title.substring(0, at).trim() : "";
    }

    // MARK: - The home screen's Continue Watching

    /** A card Live TV adds to the front of the home screen's Continue Watching row. */
    public static final class HomeCard {
        /** MATCH (a followed match on now), CHANNEL (the channel last watched), RECORDING (a finished recording). */
        public static final int MATCH = 0, CHANNEL = 1, RECORDING = 2;
        public int kind, channel;
        /** Stays the same while it's the same card (hidden with hold OK by it). */
        public String key = "", title = "", sub = "", art = "", logo = "", recording = "";
        /** How far into the show (0 to 1), -1: none. */
        public float progress = -1;
    }

    /** The finished recordings shown, at most, and how long one stays there after it ended. */
    private static final int RECORDINGS_MAX = 3;
    private static final long RECORDING_DAYS = 14 * 24 * 3600_000L;

    /**
     * The cards, in order: a followed match on now, the channel last watched (what's on it now), then the newest finished
     * recordings. Empty when Live TV isn't signed in.
     */
    public static List<HomeCard> homeCards(Context c) {
        List<HomeCard> out = new ArrayList<>();
        if (!Xtream.ready(c)) return out;
        long now = System.currentTimeMillis();
        LiveMatchDay.Match m = LiveMatchDay.next(c);
        int matchChannel = -1;
        if (m != null && m.ch != null && now >= m.start && now < m.end) {
            HomeCard k = new HomeCard();
            k.kind = HomeCard.MATCH;
            k.channel = matchChannel = m.ch.id;
            k.key = "live-match:" + m.ch.id + ":" + m.start;
            k.title = LiveMatchDay.headline(m, LiveMatchDay.score(c, m));
            k.sub = "Following  ·  " + channelLine(m.ch);
            k.logo = m.ch.logo == null ? "" : m.ch.logo;
            k.art = LiveArt.of(c, onNow(c, m.ch, now), null);
            k.progress = progress(m.start, m.end, now);
            out.add(k);
        }
        List<Xtream.Channel> recent = Xtream.recent(c);
        if (!recent.isEmpty() && recent.get(0).id != matchChannel) {
            Xtream.Channel ch = recent.get(0);
            Xtream.Program p = onNow(c, ch, now);
            HomeCard k = new HomeCard();
            k.kind = HomeCard.CHANNEL;
            k.channel = ch.id;
            k.key = "live-channel:" + ch.id;
            k.title = p != null && !p.title.isEmpty() ? p.title : (ch.name == null ? "" : ch.name);
            k.sub = channelLine(ch);
            k.logo = ch.logo == null ? "" : ch.logo;
            k.art = LiveArt.of(c, p, null);
            if (p != null) k.progress = progress(p.start, p.end, now);
            out.add(k);
        }
        List<LiveRecordings.Rec> done = new ArrayList<>();
        for (LiveRecordings.Rec r : LiveRecordings.all(c)) {
            if (!LiveRecordings.DONE.equals(r.state) || r.file == null || r.file.isEmpty() || r.ch == null) continue;
            if (now - r.end > RECORDING_DAYS || !new java.io.File(r.file).exists()) continue;
            done.add(r);
        }
        Collections.sort(done, (a, b) -> Long.compare(b.end, a.end));
        for (int i = 0; i < done.size() && i < RECORDINGS_MAX; i++) {
            LiveRecordings.Rec r = done.get(i);
            Xtream.Program p = new Xtream.Program();
            p.start = r.start;
            p.end = r.end;
            p.title = r.title == null ? "" : r.title;
            p.about = r.about == null ? "" : r.about;
            HomeCard k = new HomeCard();
            k.kind = HomeCard.RECORDING;
            k.channel = r.ch.id;
            k.recording = r.id;
            k.key = "live-rec:" + r.id;
            k.title = p.title.isEmpty() ? (r.ch.name == null ? "" : r.ch.name) : p.title;
            k.sub = "Recorded  ·  " + (r.ch.name == null ? "" : r.ch.name) + "  ·  " + day(r.start, now);
            k.logo = r.ch.logo == null ? "" : r.ch.logo;
            k.art = LiveArt.of(c, p, null);
            out.add(k);
        }
        return out;
    }

    /** Whether what's on now on `channel` is being recorded (the home card's menu: Record or Stop recording). Blocking. */
    public static boolean recordingNow(Context c, int channel) {
        Xtream.Channel ch = channelOf(c, channel);
        Xtream.Program p = ch == null ? null : onNow(c, ch, System.currentTimeMillis());
        return p != null && LiveRecordings.recording(c, ch.id, p.start);
    }

    /**
     * From the home card: records what's on now on `channel` until it ends (as Live TV's Record button), or stops the
     * recording when it already is. Returns what to tell the person. Blocking.
     */
    public static String recordNow(Context c, int channel) {
        Xtream.Channel ch = channelOf(c, channel);
        if (ch == null) return "That channel isn't in your list any more";
        Xtream.Program p = onNow(c, ch, System.currentTimeMillis());
        if (p == null) return "No guide for this channel: there's no telling when the show ends";
        if (LiveRecordings.recording(c, ch.id, p.start)) {
            RecordService.stop(c, LiveRecordings.idOf(ch.id, p.start));
            return "Recording stopped";
        }
        LiveSchedule.Item it = new LiveSchedule.Item();
        it.kind = LiveSchedule.RECORD;
        it.ch = ch;
        it.start = p.start;
        it.end = p.end;
        it.title = p.title;
        it.about = p.about;
        RecordService.start(c, it);
        return "Recording " + (p.title.isEmpty() ? ch.name : p.title) + " until " + Ui.time(p.end);
    }

    /** `channel` from the saved list (the one kept for the small window when it's there). */
    private static Xtream.Channel channelOf(Context c, int channel) {
        Xtream.Lineup l;
        synchronized (LiveHome.class) {
            l = miniLineup;
        }
        if (l == null) l = Xtream.saved(c);
        return l == null ? null : l.find(channel);
    }

    /** Live TV playing a finished recording (from its card). */
    public static Intent openRecording(Context c, String id) {
        return HomeApps.liveIntent(c, -1, false).putExtra(Live.EXTRA_RECORDING, id);
    }

    private static String channelLine(Xtream.Channel ch) {
        return (ch.num > 0 ? ch.num + "  " : "") + (ch.name == null ? "" : ch.name);
    }

    private static float progress(long start, long end, long now) {
        return end > start ? Math.max(0, Math.min(1, (now - start) / (float) (end - start))) : -1;
    }

    // MARK: - Watch on Home

    /** A channel handed from Live TV to the home screen's small window: its address (it carries the login: never logged). */
    public static final class Mini {
        public String url = "", mime = "", name = "", logo = "";
        public int channel;
    }

    private static final String MINI_FILE = "mini_live.json";
    /** A handover older than this is stale (the home screen wasn't opened from it). */
    private static final long MINI_FRESH = 60_000;

    /** Live TV's side: the channel for the home screen, in a file of the app's own. False when it couldn't be written. */
    static boolean putMini(Context c, String url, String mime, Xtream.Channel ch) {
        try {
            org.json.JSONObject o = new org.json.JSONObject().put("url", url).put("mime", mime == null ? "" : mime)
                    .put("name", ch.name == null ? "" : ch.name).put("logo", ch.logo == null ? "" : ch.logo)
                    .put("channel", ch.id).put("at", System.currentTimeMillis());
            Xtream.write(new java.io.File(c.getFilesDir(), MINI_FILE), o.toString());
            return true;
        } catch (java.io.IOException | org.json.JSONException e) {
            AppLog.w("live: couldn't hand the channel to the home screen");
            return false;
        }
    }

    /** The home screen's side: the channel handed over (read once, then gone), null when there's none or it's stale. */
    public static Mini takeMini(Context c) {
        java.io.File f = new java.io.File(c.getFilesDir(), MINI_FILE);
        if (!f.exists()) return null;
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            org.json.JSONObject o = new org.json.JSONObject(Xtream.read(in));
            if (Math.abs(System.currentTimeMillis() - o.optLong("at")) > MINI_FRESH) return null;
            Mini m = new Mini();
            m.url = o.optString("url");
            m.mime = o.optString("mime");
            m.name = o.optString("name");
            m.logo = o.optString("logo");
            m.channel = o.optInt("channel", -1);
            return m.url.isEmpty() ? null : m;
        } catch (java.io.IOException | org.json.JSONException e) {
            return null;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    /** The channel list read for the small window's channel changes, kept while its file stays the same. */
    private static Xtream.Lineup miniLineup;
    private static long miniLineupAt;

    /**
     * The channel before (`dir` -1) or after (1) `channel` for the small window: in its own category (the first of the
     * provider's that has it, else All channels), round, skipping channels locked behind the parental PIN; with a
     * Kids profile, among the children's channels only. Its address asked for now (a MAG portal's). Null when there's
     * none. Blocking: the first call reads the whole channel list.
     */
    public static Mini miniStep(Context c, int channel, int dir) {
        Xtream.Lineup l;
        synchronized (LiveHome.class) {
            long at = Xtream.listFile(c).lastModified();
            if (miniLineup == null || miniLineupAt != at) {
                miniLineup = Xtream.saved(c);
                if (miniLineup != null && LiveProfiles.kids(c)) miniLineup = LiveProfiles.kidsOnly(miniLineup);
                miniLineupAt = at;
            }
            l = miniLineup;
        }
        if (l == null) return null;
        Xtream.Channel now = l.find(channel);
        if (now == null) return null;
        List<Xtream.Channel> list = null;
        for (int i = 2; i < l.categories.size() && list == null; i++) if (l.categories.get(i).channels.contains(now)) list = l.categories.get(i).channels;
        if (list == null) list = l.all();
        int at = list.indexOf(now);
        if (at < 0 || list.size() < 2) return null;
        for (int step = 1; step < list.size(); step++) {
            Xtream.Channel ch = list.get(Math.floorMod(at + dir * step, list.size()));
            if (LiveOptions.isLockedAnyProcess(c, ch.category)) continue;
            return miniOf(c, l, ch);
        }
        return null;
    }

    /**
     * `channel` for the small window (the kick-off alert's Small window): null when it isn't in the list, is locked
     * behind the parental PIN, or its address can't be had. Blocking.
     */
    public static Mini miniFor(Context c, int channel) {
        Xtream.Lineup l = Xtream.saved(c);
        if (l != null && LiveProfiles.kids(c)) l = LiveProfiles.kidsOnly(l);
        Xtream.Channel ch = l == null ? null : l.find(channel);
        if (ch == null || LiveOptions.isLockedAnyProcess(c, ch.category)) return null;
        return miniOf(c, l, ch);
    }

    /** `ch`'s address asked for now (a MAG portal's), for the small window; null when it can't be had. */
    private static Mini miniOf(Context c, Xtream.Lineup l, Xtream.Channel ch) {
        Xtream.Channel src = ch.sources != null && !ch.sources.isEmpty() ? ch.sources.get(0) : ch;
        String format = LiveOptions.format(c);
        if (format.equals(LiveOptions.AUTO)) format = l.format;
        try {
            String url = Xtream.resolve(c, Xtream.streamUrl(c, src, format));
            Mini m = new Mini();
            m.url = url;
            m.mime = Xtream.mime(c, url, format);
            if (m.mime == null) m.mime = "";
            m.name = ch.name == null ? "" : ch.name;
            m.logo = ch.logo == null ? "" : ch.logo;
            m.channel = ch.id;
            return m;
        } catch (java.io.IOException e) {
            AppLog.w("live: the small window couldn't get the channel's address");
            return null;
        }
    }

    /** The channel the small window changed to, for Live TV's next start (Live.show reads it once, then it's gone). */
    static final String NEXT_CHANNEL_FILE = "live-next-channel.txt";

    /**
     * The small window went to `channel`: it's the one watched last now (History, the home screen's Live TV card) and the
     * one Live TV opens on next. Blocking.
     */
    public static void miniWatched(Context c, int channel) {
        Xtream.Channel ch = channelOf(c, channel);
        if (ch == null) return;
        Xtream.watched(c, ch);
        try {
            Xtream.write(new java.io.File(c.getFilesDir(), NEXT_CHANNEL_FILE), String.valueOf(ch.id));
        } catch (java.io.IOException e) {
            AppLog.w("live: couldn't keep the small window's channel");
        }
    }

    /** Live TV's side: the channel the small window left on (-1: none), read once. */
    static int takeNextChannel(Context c) {
        java.io.File f = new java.io.File(c.getFilesDir(), NEXT_CHANNEL_FILE);
        if (!f.exists()) return -1;
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            return Integer.parseInt(Xtream.read(in).trim());
        } catch (java.io.IOException | NumberFormatException e) {
            return -1;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    /** The user agent Live TV's own player sends (some providers only answer it). */
    public static String userAgent() {
        return Live.USER_AGENT;
    }

    // MARK: - Live TV

    /** The On now row's cards: the favorites, then up to this many with the channels watched lately. */
    private static final int LIVE_MAX = 16;

    public static Page page(Context c) {
        Page out = new Page();
        if (!Xtream.ready(c)) return out;
        long now = System.currentTimeMillis();
        List<Xtream.Channel> recent = Xtream.recent(c);
        if (!recent.isEmpty()) out.last = airing(c, recent.get(0), onNow(c, recent.get(0), now));
        Set<Integer> seen = new HashSet<>();
        List<Xtream.Channel> list = new ArrayList<>();
        for (Xtream.Channel ch : Xtream.favorites(c)) if (seen.add(ch.id)) list.add(ch);
        for (Xtream.Channel ch : recent) if (list.size() < LIVE_MAX && seen.add(ch.id)) list.add(ch);
        for (Xtream.Channel ch : list) out.onNow.add(airing(c, ch, onNow(c, ch, now)));
        for (Object[] o : soon(c, now)) out.soon.add(airing(c, (Xtream.Channel) o[0], (Xtream.Program) o[1]));
        return out;
    }

    /** Sports live now (read apart from the page: it searches the whole guide once for each sport). */
    public static List<Airing> sportsNow(Context c) {
        List<Airing> out = new ArrayList<>();
        for (Object[] o : sports(c, System.currentTimeMillis())) out.add(airing(c, (Xtream.Channel) o[0], (Xtream.Program) o[1]));
        return out;
    }

    /**
     * What's on now on the cards that have nothing yet, from the provider (Xtream's short guide), a few at a time:
     * the network, so after the page first showed what the TV already had. True when something was found.
     */
    public static boolean fillFromProvider(Context c, Page page) {
        List<Airing> missing = new ArrayList<>();
        if (page.last != null && page.last.p == null) missing.add(page.last);
        for (Airing a : page.onNow) if (a.p == null) missing.add(a);
        if (missing.isEmpty()) return false;
        final long t = System.currentTimeMillis();
        final Map<Integer, Xtream.Program> found = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        List<java.util.concurrent.Callable<Void>> jobs = new ArrayList<>();
        Set<Integer> asked = new HashSet<>();
        for (final Airing a : missing) {
            if (!asked.add(a.channel)) continue;
            jobs.add(() -> {
                try {
                    for (Xtream.Program p : Xtream.programs(c, a.channel, 2)) if (p.start <= t && p.end > t) found.put(a.channel, p);
                } catch (java.io.IOException | RuntimeException ignored) {   // no "on now" for that one this time
                }
                return null;
            });
        }
        try {
            pool.invokeAll(jobs, 20, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        } finally {
            pool.shutdownNow();
        }
        if (found.isEmpty()) return false;
        if (page.last != null && page.last.p == null && found.containsKey(page.last.channel)) {
            page.last = airing(c, page.last.ch, found.get(page.last.channel));
        }
        for (int i = 0; i < page.onNow.size(); i++) {
            Airing a = page.onNow.get(i);
            if (a.p == null && found.containsKey(a.channel)) page.onNow.set(i, airing(c, a.ch, found.get(a.channel)));
        }
        return true;
    }

    /** Pictures TMDB found since a row was read (LiveArt keeps them): true when one came in. */
    public static boolean fillArt(Context c, List<Airing> row) {
        boolean changed = false;
        for (Airing a : row) {
            if (!a.art.isEmpty() || a.p == null) continue;
            String art = LiveArt.of(c, a.p, null);
            if (!art.isEmpty()) {
                a.art = art;
                changed = true;
            }
        }
        return changed;
    }

    /** Pictures TMDB found since the page was read (LiveArt keeps them): true when one came in. */
    public static boolean fillArt(Context c, Page page) {
        boolean changed = false;
        List<List<Airing>> rows = new ArrayList<>();
        rows.add(page.onNow);
        rows.add(page.sports);
        rows.add(page.soon);
        for (List<Airing> row : rows) {
            for (Airing a : row) {
                if (!a.art.isEmpty() || a.p == null) continue;
                String art = LiveArt.of(c, a.p, null);
                if (!art.isEmpty()) {
                    a.art = art;
                    changed = true;
                }
            }
        }
        if (page.last != null && page.last.art.isEmpty() && page.last.p != null) {
            String art = LiveArt.of(c, page.last.p, null);
            if (!art.isEmpty()) {
                page.last.art = art;
                changed = true;
            }
        }
        return changed;
    }

    private static Xtream.Program onNow(Context c, Xtream.Channel ch, long now) {
        if (LiveOptions.fullGuide(c)) {
            List<Xtream.Program> l = XmltvStore.programs(c, ch.epg, now, now + 1);
            if (l != null && !l.isEmpty()) return l.get(0);
        }
        Xtream.SavedGuide g = Xtream.savedGuide(c, ch.id);
        if (g != null) for (Xtream.Program p : g.programs) if (p.start <= now && p.end > now) return p;
        return null;
    }

    private static final String[] SPORTS = {"premier league", "champions league", "nfl", "nba", "mlb", "nhl", "grand prix",
            "formula 1", "ufc", "boxing", "cricket", "rugby", "golf", "tennis", "la liga", "serie a", "bundesliga"};
    private static final String[] FILLER = {"paid program", "teleshopping", "infomercial", "off air", "sign off",
            "to be announced", "no information", "programming will resume", "highlights"};

    private static boolean filler(String title) {
        String t = Xtream.fold(title);
        if (t.isEmpty()) return true;
        for (String f : FILLER) if (t.contains(f)) return true;
        return false;
    }

    /** Live sports on now, on the channels of the list (the full guide's), one tile per event. */
    private static List<Object[]> sports(Context c, long now) {
        List<Object[]> out = new ArrayList<>();
        if (!LiveOptions.fullGuide(c)) return out;
        Map<String, Xtream.Channel> byEpg = byEpg(c);
        if (byEpg.isEmpty()) return out;
        Set<String> seen = new HashSet<>();
        List<String[]> phrases = new ArrayList<>();
        for (String w : SPORTS) phrases.add(w.split(" "));
        List<XmltvStore.Found> found = XmltvStore.findAny(c, phrases, now, now + 1, 5000);
        for (String[] words : phrases) {
            List<XmltvStore.Found> mine = new ArrayList<>();
            for (XmltvStore.Found f : found) if (mine.size() < 30 && hasAll(f.key, words)) mine.add(f);
            for (XmltvStore.Found f : mine) {
                Xtream.Channel ch = f.epg == null ? null : byEpg.get(f.epg.toLowerCase(Locale.ROOT));
                if (ch == null || filler(f.title) || !seen.add(Xtream.fold(f.title) + "|" + f.start)) continue;
                Xtream.Program p = new Xtream.Program();
                p.title = f.title;
                p.start = f.start;
                p.end = f.end;
                p.icon = f.icon == null ? "" : f.icon;
                Xtream.mark(p);
                out.add(new Object[] {ch, p});
                if (out.size() >= 16) return out;
            }
        }
        return out;
    }

    /** Whether the folded title has every word (as the guide's LIKE search does). */
    private static boolean hasAll(String key, String[] words) {
        for (String w : words) if (!key.contains(w.replace("%", "").replace("_", ""))) return false;
        return true;
    }

    /** What starts within an hour and a half on the favorites and the channels watched lately. */
    private static List<Object[]> soon(Context c, long now) {
        List<Object[]> out = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        List<Xtream.Channel> yours = new ArrayList<>(Xtream.favorites(c));
        yours.addAll(Xtream.recent(c));
        for (Xtream.Channel ch : yours) {
            if (!seen.add(ch.id)) continue;
            List<Xtream.Program> l = LiveOptions.fullGuide(c) ? XmltvStore.programs(c, ch.epg, now, now + 90 * 60_000L) : null;
            if (l == null) {
                Xtream.SavedGuide g = Xtream.savedGuide(c, ch.id);
                l = g == null ? null : g.programs;
            }
            if (l == null) continue;
            for (Xtream.Program p : l) {
                if (p.start > now && p.start < now + 90 * 60_000L && !filler(p.title)) {
                    out.add(new Object[] {ch, p});
                    break;
                }
            }
            if (out.size() >= 16) break;
        }
        Collections.sort(out, (a, b) -> Long.compare(((Xtream.Program) a[1]).start, ((Xtream.Program) b[1]).start));
        return out;
    }

    private static Map<String, Xtream.Channel> epgCache;
    private static long epgStamp, epgAt;

    /**
     * The channels by guide id, kept while the saved list stays the same (and for at most five minutes, as merging
     * settings changed in Live TV don't touch the list's file).
     */
    private static synchronized Map<String, Xtream.Channel> byEpg(Context c) {
        long stamp = Xtream.savedStamp(c), now = System.currentTimeMillis();
        if (epgCache != null && stamp == epgStamp && now - epgAt < 5 * 60_000L) return epgCache;
        Map<String, Xtream.Channel> out = readByEpg(c);
        epgCache = out;
        epgStamp = stamp;
        epgAt = now;
        return out;
    }

    private static Map<String, Xtream.Channel> readByEpg(Context c) {
        Map<String, Xtream.Channel> out = new HashMap<>();
        Xtream.Lineup l = Xtream.saved(c);
        if (l == null) return out;
        for (Xtream.Channel ch : l.all()) {
            if (ch.epg == null || ch.epg.isEmpty()) continue;
            String id = ch.epg.toLowerCase(Locale.ROOT);
            if (!out.containsKey(id)) out.put(id, ch);
        }
        return out;
    }
}
