package com.glasslauncher.tv.live;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * A football match's Match Centre: score, timeline, stats, lineups and table, from ESPN's public match summary (the
 * scoreboard's event id is all it needs). Pure parsing, so it's tested on the computer; {@link #load} reads it.
 */
final class LiveMatchCentre {
    private LiveMatchCentre() {}

    static final int TIMELINE = 0, STATS = 1, LINEUPS = 2, TABLE = 3;
    static final int GOAL = 0, YELLOW = 1, RED = 2, CHANGE = 3;

    static final class Stat {
        String label = "", home = "", away = "";
    }

    static final class Player {
        String number = "", name = "", position = "";
    }

    static final class Lineup {
        String formation = "";
        final List<Player> starters = new ArrayList<>();
    }

    static final class Event {
        String minute = "", who = "";
        int kind;
        boolean home;
    }

    static final class Row {
        String team = "", rank = "", played = "", won = "", drawn = "", lost = "", difference = "", points = "";
        boolean mine;
    }

    static final class Data {
        String home = "", away = "", homeName = "", awayName = "", clock = "", state = "pre", league = "";
        int homeScore, awayScore;
        final List<Stat> stats = new ArrayList<>();
        final Lineup homeLine = new Lineup(), awayLine = new Lineup();
        final List<Event> events = new ArrayList<>();
        final List<Row> table = new ArrayList<>();
    }

    /** ESPN's stat name to what's shown, in the order shown. */
    private static final String[][] STATS_SHOWN = {
            {"possessionPct", "Possession"}, {"totalShots", "Shots"}, {"shotsOnTarget", "On target"}, {"wonCorners", "Corners"},
            {"saves", "Saves"}, {"foulsCommitted", "Fouls"}, {"yellowCards", "Yellow cards"}, {"redCards", "Red cards"}, {"offsides", "Offsides"},
    };

    private static JSONObject first(JSONArray a) {
        return a == null ? null : a.optJSONObject(0);
    }

    static Data parse(JSONObject page) {
        Data d = new Data();
        JSONObject header = page.optJSONObject("header");
        JSONObject comp = header == null ? null : first(header.optJSONArray("competitions"));
        if (header != null && header.optJSONObject("league") != null) d.league = header.optJSONObject("league").optString("name");
        if (comp != null) {
            JSONObject status = comp.optJSONObject("status"), type = status == null ? null : status.optJSONObject("type");
            if (type != null) {
                d.state = type.optString("state", "pre");
                d.clock = type.optString("shortDetail");
            }
            JSONArray sides = comp.optJSONArray("competitors");
            for (int i = 0; sides != null && i < sides.length(); i++) {
                JSONObject t = sides.optJSONObject(i), team = t == null ? null : t.optJSONObject("team");
                if (team == null) continue;
                int score = number(t.optString("score"));
                if ("home".equals(t.optString("homeAway"))) {
                    d.home = team.optString("abbreviation");
                    d.homeName = team.optString("displayName");
                    d.homeScore = score;
                } else {
                    d.away = team.optString("abbreviation");
                    d.awayName = team.optString("displayName");
                    d.awayScore = score;
                }
            }
        }
        stats(page.optJSONObject("boxscore"), d);
        lineups(page.optJSONArray("rosters"), d);
        events(page.optJSONArray("keyEvents"), d);
        table(page.optJSONObject("standings"), d);
        return d;
    }

    private static int number(String n) {
        try {
            return Integer.parseInt(n.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void stats(JSONObject box, Data d) {
        JSONArray teams = box == null ? null : box.optJSONArray("teams");
        Map<String, String> home = new HashMap<>(), away = new HashMap<>();
        for (int i = 0; teams != null && i < teams.length(); i++) {
            JSONObject t = teams.optJSONObject(i);
            JSONArray list = t == null ? null : t.optJSONArray("statistics");
            Map<String, String> to = t != null && "home".equals(t.optString("homeAway")) ? home : away;
            for (int j = 0; list != null && j < list.length(); j++) {
                JSONObject s = list.optJSONObject(j);
                if (s != null) to.put(s.optString("name"), s.optString("displayValue"));
            }
        }
        for (String[] shown : STATS_SHOWN) {
            String a = home.get(shown[0]), b = away.get(shown[0]);
            if (a == null || b == null) continue;
            Stat s = new Stat();
            s.label = shown[1];
            boolean share = shown[0].equals("possessionPct");
            s.home = share ? percent(a) : a;
            s.away = share ? percent(b) : b;
            d.stats.add(s);
        }
    }

    private static String percent(String v) {
        try {
            return Math.round(Double.parseDouble(v)) + "%";
        } catch (NumberFormatException e) {
            return v;
        }
    }

    /** How much of a stat's bar is the home side's, 0 to 1; half when neither has any. Pure. */
    static float share(String home, String away) {
        float a = value(home), b = value(away);
        return a + b <= 0 ? 0.5f : a / (a + b);
    }

    /** Whether neither side has any of a stat. Pure. */
    static boolean none(Stat s) {
        return value(s.home) + value(s.away) <= 0;
    }

    private static float value(String v) {
        try {
            return Float.parseFloat(v.replace("%", "").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void lineups(JSONArray rosters, Data d) {
        for (int i = 0; rosters != null && i < rosters.length(); i++) {
            JSONObject r = rosters.optJSONObject(i);
            if (r == null) continue;
            Lineup line = "home".equals(r.optString("homeAway")) ? d.homeLine : d.awayLine;
            line.formation = r.optString("formation");
            JSONArray list = r.optJSONArray("roster");
            for (int j = 0; list != null && j < list.length(); j++) {
                JSONObject p = list.optJSONObject(j);
                if (p == null || !p.optBoolean("starter")) continue;
                JSONObject who = p.optJSONObject("athlete"), where = p.optJSONObject("position");
                Player out = new Player();
                out.number = p.optString("jersey");
                out.name = who == null ? "" : who.optString("displayName");
                out.position = where == null ? "" : where.optString("abbreviation");
                line.starters.add(out);
            }
        }
    }

    private static void events(JSONArray list, Data d) {
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject e = list.optJSONObject(i);
            if (e == null) continue;
            JSONObject type = e.optJSONObject("type"), clock = e.optJSONObject("clock"), team = e.optJSONObject("team");
            String text = type == null ? "" : type.optString("text").toLowerCase(Locale.ROOT);
            int kind;
            if (e.optBoolean("scoringPlay") || text.startsWith("goal") || text.startsWith("penalty - scored") || text.startsWith("own goal")) kind = GOAL;
            else if (text.contains("yellow")) kind = YELLOW;
            else if (text.contains("red card")) kind = RED;
            else if (text.startsWith("substitution")) kind = CHANGE;
            else continue;
            Event out = new Event();
            out.kind = kind;
            out.minute = clock == null ? "" : clock.optString("displayValue");
            out.home = team != null && team.optString("displayName").equals(d.homeName);
            JSONArray people = e.optJSONArray("participants");
            String one = person(people, 0), two = person(people, 1);
            out.who = kind == CHANGE && !two.isEmpty() ? one + " ↔ " + two : one;
            d.events.add(out);
        }
    }

    private static String person(JSONArray people, int i) {
        JSONObject p = people == null ? null : people.optJSONObject(i), who = p == null ? null : p.optJSONObject("athlete");
        return who == null ? "" : who.optString("displayName");
    }

    private static void table(JSONObject standings, Data d) {
        JSONObject group = standings == null ? null : first(standings.optJSONArray("groups"));
        JSONObject inner = group == null ? null : group.optJSONObject("standings");
        JSONArray entries = inner == null ? null : inner.optJSONArray("entries");
        for (int i = 0; entries != null && i < entries.length(); i++) {
            JSONObject e = entries.optJSONObject(i);
            if (e == null) continue;
            Map<String, String> stats = new HashMap<>();
            JSONArray list = e.optJSONArray("stats");
            for (int j = 0; list != null && j < list.length(); j++) {
                JSONObject s = list.optJSONObject(j);
                if (s != null) stats.put(s.optString("name"), s.optString("displayValue"));
            }
            Row r = new Row();
            r.team = e.optString("team");
            r.rank = get(stats, "rank");
            r.played = get(stats, "gamesPlayed");
            r.won = get(stats, "wins");
            r.drawn = get(stats, "ties");
            r.lost = get(stats, "losses");
            r.difference = get(stats, "pointDifferential");
            r.points = get(stats, "points");
            r.mine = r.team.equals(d.homeName) || r.team.equals(d.awayName);
            d.table.add(r);
        }
    }

    private static String get(Map<String, String> stats, String name) {
        String v = stats.get(name);
        return v == null ? "" : v;
    }

    /** The sections there's something to show in. Pure. */
    static int[] sections(Data d) {
        int[] all = new int[4];
        int n = 0;
        if (!d.events.isEmpty()) all[n++] = TIMELINE;
        if (!d.stats.isEmpty()) all[n++] = STATS;
        if (!d.homeLine.starters.isEmpty() || !d.awayLine.starters.isEmpty()) all[n++] = LINEUPS;
        if (!d.table.isEmpty()) all[n++] = TABLE;
        return java.util.Arrays.copyOf(all, n);
    }

    /** The first row of a list `count` long showing `room` rows, as far down as asked. Pure. */
    static int top(int wanted, int room, int count) {
        return Math.max(0, Math.min(wanted, count - room));
    }

    /** The row of the first of the match's two sides in the table; 0 without one. Pure. */
    static int firstMine(List<Row> table) {
        for (int i = 0; i < table.size(); i++) if (table.get(i).mine) return i;
        return 0;
    }

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    /** Reads match `event`; `done` runs on another thread, with null when it couldn't. */
    static void load(Context c, String event, Consumer<Data> done) {
        IO.execute(() -> {
            Data d = null;
            try {
                d = parse(new JSONObject(LiveScores.text(LiveScores.base() + "soccer/all/summary?event=" + event)));
            } catch (Exception e) {
                AppLog.w("match centre: " + e.getMessage());
            }
            done.accept(d);
        });
    }
}
