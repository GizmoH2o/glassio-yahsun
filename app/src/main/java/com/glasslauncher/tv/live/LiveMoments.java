package com.glasslauncher.tv.live;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A football match's goals and red cards, on the player's bar and its Moments tab, from ESPN's public scoreboard (the
 * one Live scores reads, by date). Only for a show whose title names a team, and only while Live scores is on. Each
 * moment sits where it really happened: the match's summary has the time of day of every goal and card. Without it,
 * from the minute: after the real kick-off (or second-half start) when known, stoppage time kept in its half. Then
 * Settings › Moments timing moves them all later, for a stream that runs behind.
 */
final class LiveMoments {
    private LiveMoments() {}

    static final int GOAL = 0, RED = 1;
    private static final long MINUTE = 60_000;
    /** Half-time, counted for the second half. */
    private static final long BREAK = 15 * MINUTE;
    /** A match on now is read again this often; one that wasn't found, this often. */
    private static final long LIVE_EVERY = 2 * MINUTE, MISSED_EVERY = 30 * MINUTE;

    static final class Moment {
        int minute, kind;
        long at;
        String who = "";
    }

    static final class Match {
        String home = "", away = "";
        /** ESPN's event id (Match Centre); "" for a stand-in. */
        String event = "";
        int homeScore, awayScore;
        final List<Moment> moments = new ArrayList<>();

        /** AEK 2–3 RMA */
        String result() {
            return home + " " + homeScore + "–" + awayScore + " " + away;
        }
    }

    /** A made-up match of `count` moments spread along a show that long, for the test broadcast. Pure. */
    static Match sample(int count, long length) {
        Match m = new Match();
        m.home = "HAR";
        m.away = "NOR";
        for (int i = 0; i < count; i++) {
            Moment at = new Moment();
            at.minute = 90 * (i + 1) / (count + 1);
            at.kind = i % 3 == 2 ? RED : GOAL;
            at.at = length * (i + 1) / (count + 1);
            at.who = at.kind == RED ? "NOR" : "HAR " + (i + 1) + "–0";
            m.moments.add(at);
        }
        return m;
    }

    /** "12'" is 12, "45'+2'" 47; -1 when it isn't a minute. Pure. */
    static int minuteOf(String clock) {
        int total = 0;
        boolean any = false;
        for (String part : (clock == null ? "" : clock).split("\\+")) {
            try {
                total += Integer.parseInt(part.replace("'", "").trim());
                any = true;
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return any ? total : -1;
    }

    /** How far into a show starting at `start` that minute of the match kicking off at `kickOff` is. Pure. */
    static long position(long kickOff, long start, int minute) {
        return kickOff - start + minute * MINUTE + (minute > 45 ? BREAK : 0);
    }

    /** "45'+2'" as {45, 2}, "63'" as {63, 0}; null when it isn't a minute. Pure. */
    static int[] clock(String clock) {
        String[] parts = (clock == null ? "" : clock).split("\\+");
        try {
            int base = Integer.parseInt(parts[0].replace("'", "").trim());
            int extra = parts.length > 1 ? Integer.parseInt(parts[1].replace("'", "").trim()) : 0;
            return new int[] {base, extra};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * The time of day a minute of the match was, from its kick-off and its second half's start (0: not known, then
     * 15 minutes after the first half's 45). Stoppage time stays in its half: "45'+3'" is before half-time. Pure.
     */
    static long estimate(long kickOff, long secondHalf, String clock) {
        int[] c = clock(clock);
        if (c == null) return -1;
        if (c[0] <= 45) return kickOff + (c[0] + c[1]) * MINUTE;
        long second = secondHalf > 0 ? secondHalf : kickOff + 45 * MINUTE + BREAK;
        return second + (c[0] - 45 + c[1]) * MINUTE;
    }

    /** A summary's key events with their times of day ({kind, clock, time}): goals and red cards; and kick-offs. */
    static final class Timeline {
        long kickOff, secondHalf;
        final List<Object[]> goals = new ArrayList<>(), reds = new ArrayList<>();
    }

    /** The match summary's key events read as a timeline. Pure. */
    static Timeline timeline(JSONArray keyEvents) {
        Timeline t = new Timeline();
        for (int i = 0; keyEvents != null && i < keyEvents.length(); i++) {
            JSONObject k = keyEvents.optJSONObject(i);
            if (k == null) continue;
            long at = LiveScores.when(k.optString("wallclock"));
            if (at <= 0) continue;
            JSONObject type = k.optJSONObject("type"), clock = k.optJSONObject("clock"), period = k.optJSONObject("period");
            String kind = type == null ? "" : type.optString("text").toLowerCase(Locale.ROOT);
            String shown = clock == null ? "" : clock.optString("displayValue");
            int half = period == null ? 0 : period.optInt("number");
            if (kind.equals("kickoff") && t.kickOff == 0) t.kickOff = at;
            else if (kind.contains("2nd half") && t.secondHalf == 0 || half == 2 && t.secondHalf == 0 && kind.startsWith("start")) t.secondHalf = at;
            if (k.optBoolean("shootout")) continue;
            // ESPN's own flag when it has one (a "Goal Disallowed" isn't one); else by the kind's name.
            boolean goal = k.has("scoringPlay") ? k.optBoolean("scoringPlay")
                    : kind.contains("goal") && !kind.contains("disallow") && !kind.contains("kick") || kind.contains("penalty - scored");
            boolean red = kind.contains("red card");
            if (goal) t.goals.add(new Object[] {shown, at});
            else if (red) t.reds.add(new Object[] {shown, at});
        }
        return t;
    }

    /**
     * Each moment at its time of day: the summary's, matched by kind and in order (the same minute when it can), else
     * from its minute; then as far into the show as that is, moved later by `shift`. Pure.
     */
    static void place(Match m, List<String> clocks, Timeline t, long scheduled, long showStart, long shift) {
        long kickOff = t != null && t.kickOff > 0 ? t.kickOff : scheduled;
        long secondHalf = t == null ? 0 : t.secondHalf;
        int goalAt = 0, redAt = 0;
        for (int i = 0; i < m.moments.size(); i++) {
            Moment at = m.moments.get(i);
            String shown = clocks.get(i);
            long when = -1;
            List<Object[]> same = t == null ? null : at.kind == GOAL ? t.goals : t.reds;
            int next = at.kind == GOAL ? goalAt : redAt;
            if (same != null && next < same.size()) {
                // The next one of its kind; a step further when that's a minute off and the following one isn't.
                int pick = next;
                if (!same.get(next)[0].equals(shown) && next + 1 < same.size() && same.get(next + 1)[0].equals(shown)) pick = next + 1;
                when = (Long) same.get(pick)[1];
                if (at.kind == GOAL) goalAt = pick + 1; else redAt = pick + 1;
            }
            if (when <= 0) when = estimate(kickOff, secondHalf, shown);
            at.at = Math.max(0, when - showStart + shift);
        }
    }

    /** Whether the match stays hidden: on a past show with the guard on, or a team kept spoiler-free, until asked. Pure. */
    static boolean hidden(boolean guard, boolean teamHidden, boolean revealed, boolean past) {
        return (past && guard || teamHidden) && !revealed;
    }

    /** A scoreboard's event as a match with its moments in order; null when it names no teams. Pure. */
    static Match parse(JSONObject event, long showStart) {
        return parse(event, showStart, null, 0);
    }

    /** As parse, with the match summary's key events (null without) and the moments moved `shift` later. Pure. */
    static Match parse(JSONObject event, long showStart, JSONArray keyEvents, long shift) {
        JSONArray comps = event.optJSONArray("competitions");
        JSONObject comp = comps == null ? null : comps.optJSONObject(0);
        JSONArray teams = comp == null ? null : comp.optJSONArray("competitors");
        JSONArray details = comp == null ? null : comp.optJSONArray("details");
        if (teams == null) return null;
        Match m = new Match();
        m.event = event.optString("id");
        String homeId = "";
        Map<String, String> names = new HashMap<>();
        for (int i = 0; i < teams.length(); i++) {
            JSONObject t = teams.optJSONObject(i);
            JSONObject team = t == null ? null : t.optJSONObject("team");
            if (team == null) continue;
            String name = team.optString("abbreviation", team.optString("shortDisplayName"));
            names.put(team.optString("id"), name);
            int score = parseScore(t.optString("score"));
            if ("home".equals(t.optString("homeAway"))) {
                m.home = name;
                m.homeScore = score;
                homeId = team.optString("id");
            } else {
                m.away = name;
                m.awayScore = score;
            }
        }
        long kickOff = LiveScores.when(event.optString("date"));
        List<String> clocks = new ArrayList<>();
        int home = 0, away = 0;
        for (int i = 0; details != null && i < details.length(); i++) {
            JSONObject d = details.optJSONObject(i);
            if (d == null || d.optBoolean("shootout")) continue;
            JSONObject type = d.optJSONObject("type"), clock = d.optJSONObject("clock"), team = d.optJSONObject("team");
            String kind = type == null ? "" : type.optString("text").toLowerCase(Locale.ROOT);
            boolean goal = d.optBoolean("scoringPlay"), red = d.optBoolean("redCard") || kind.contains("red card");
            int minute = minuteOf(clock == null ? "" : clock.optString("displayValue"));
            if (!goal && !red || minute < 0) continue;
            String id = team == null ? "" : team.optString("id");
            String side = names.containsKey(id) ? names.get(id) : "";
            Moment at = new Moment();
            at.minute = minute;
            at.kind = goal ? GOAL : RED;
            clocks.add(clock == null ? "" : clock.optString("displayValue"));
            if (goal) {
                if (id.equals(homeId)) home++; else away++;
                at.who = side + " " + home + "–" + away;
            } else {
                at.who = side;
            }
            JSONArray people = d.optJSONArray("athletesInvolved");
            JSONObject person = people == null ? null : people.optJSONObject(0);
            String who = person == null ? "" : person.optString("shortName");
            if (!who.isEmpty()) at.who += " · " + who;
            m.moments.add(at);
        }
        place(m, clocks, keyEvents == null ? null : timeline(keyEvents), kickOff, showStart, shift);
        return m.home.isEmpty() && m.away.isEmpty() ? null : m;     // a 0–0 is still a match, with no moments
    }

    private static int parseScore(String n) {
        try {
            return Integer.parseInt(n.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The event one of `teams` plays in, or null. Pure. */
    static JSONObject find(JSONArray events, List<LiveNames.Names> teams) {
        for (int i = 0; events != null && i < events.length(); i++) {
            JSONObject ev = events.optJSONObject(i);
            JSONArray comps = ev == null ? null : ev.optJSONArray("competitions");
            JSONObject comp = comps == null ? null : comps.optJSONObject(0);
            JSONArray sides = comp == null ? null : comp.optJSONArray("competitors");
            if (sides == null) continue;
            for (LiveNames.Names names : teams) if (LiveScores.plays(names, sides)) return ev;
        }
        return null;
    }

    private static final class Entry {
        Match match;
        long at;
        boolean asking;
    }

    private static final Map<String, Entry> CACHE = new HashMap<>();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    /** What's known of the match this show is (null: nothing, or not yet: `done` runs on another thread when found). */
    static Match get(Context context, Xtream.Program p, Runnable done) {
        if (p == null || p.title == null || !LiveOptions.scores(context)) return null;
        final Context c = context.getApplicationContext();
        long now = System.currentTimeMillis();
        if (p.start > now) return null;
        final String key = p.start + "|" + p.title;
        final Entry e;
        synchronized (CACHE) {
            Entry have = CACHE.get(key);
            if (have == null) {
                have = new Entry();
                CACHE.put(key, have);
            }
            e = have;
            boolean over = p.end <= now;
            long keep = e.match == null ? MISSED_EVERY : over ? Long.MAX_VALUE : LIVE_EVERY;
            if (e.asking || e.at > 0 && now - e.at < keep) return e.match;
            e.asking = true;
        }
        IO.execute(() -> {
            Match found = load(c, p);
            synchronized (CACHE) {
                e.asking = false;
                e.at = System.currentTimeMillis();
                if (found != null) e.match = found;
            }
            if (found != null) done.run();
        });
        return e.match;
    }

    /** The teams a title names: the ones followed that it has, and both sides of "A v B". */
    private static List<LiveNames.Names> teams(Context c, String title) {
        List<LiveNames.Names> out = new ArrayList<>();
        String folded = Xtream.fold(title);
        for (String term : LiveFollow.all(c)) {
            LiveNames.Names names = LiveNames.of(c, Xtream.fold(term));
            if (names.in(folded)) out.add(names);
        }
        List<String> choices = LiveFollow.choices(title);
        // Two sides, or two and the competition; one is the whole title: no match in it.
        for (int i = 0; choices.size() >= 2 && i < 2; i++) out.add(LiveNames.of(c, Xtream.fold(choices.get(i))));
        return out;
    }

    private static Match load(Context c, Xtream.Program p) {
        List<LiveNames.Names> teams = teams(c, p.title);
        if (teams.isEmpty()) return null;
        try {
            SimpleDateFormat day = new SimpleDateFormat("yyyyMMdd", Locale.ROOT);
            day.setTimeZone(TimeZone.getTimeZone("America/New_York"));     // the board's days
            JSONArray events = new JSONObject(LiveScores.text(LiveScores.base() + "soccer/all/scoreboard?dates="
                    + day.format(new Date(p.start)))).optJSONArray("events");
            JSONObject ev = find(events, teams);
            if (ev == null) return null;
            long kickOff = LiveScores.when(ev.optString("date"));
            if (kickOff < p.start - 60 * MINUTE || kickOff > p.end) return null;     // the team's other match
            JSONArray keyEvents = null;
            try {       // the times of day of its goals and cards
                keyEvents = new JSONObject(LiveScores.text(LiveScores.base() + "soccer/all/summary?event=" + ev.optString("id")))
                        .optJSONArray("keyEvents");
            } catch (Exception ignored) {       // none: from the minutes
            }
            return parse(ev, p.start, keyEvents, LiveOptions.momentsShift(c) * 1000L);
        } catch (Exception e) {
            return null;
        }
    }
}
