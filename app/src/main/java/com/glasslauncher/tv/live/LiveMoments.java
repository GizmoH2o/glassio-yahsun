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
 * one Live scores reads, by date). Only for a show whose title names a team, and only while Live scores is on. Where
 * a moment is in the show is worked out from kick-off, so it's close, not exact.
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
        int homeScore, awayScore;
        final List<Moment> moments = new ArrayList<>();

        /** AEK 2–3 RMA */
        String result() {
            return home + " " + homeScore + "–" + awayScore + " " + away;
        }
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

    /** Whether the match stays hidden: on a past show with the guard on, or a team kept spoiler-free, until asked. Pure. */
    static boolean hidden(boolean guard, boolean teamHidden, boolean revealed, boolean past) {
        return (past && guard || teamHidden) && !revealed;
    }

    /** A scoreboard's event as a match with its moments in order; null without any. Pure. */
    static Match parse(JSONObject event, long showStart) {
        JSONArray comps = event.optJSONArray("competitions");
        JSONObject comp = comps == null ? null : comps.optJSONObject(0);
        JSONArray teams = comp == null ? null : comp.optJSONArray("competitors");
        JSONArray details = comp == null ? null : comp.optJSONArray("details");
        if (teams == null || details == null) return null;
        Match m = new Match();
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
        int home = 0, away = 0;
        for (int i = 0; i < details.length(); i++) {
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
            at.at = Math.max(0, position(kickOff, showStart, minute));
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
        return m.moments.isEmpty() ? null : m;
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
            return parse(ev, p.start);
        } catch (Exception e) {
            return null;
        }
    }
}
