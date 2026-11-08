package com.glasslauncher.tv.live;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Match cards (Settings › Home › Match cards): a card whose show is a match ("Atalanta v Kairat") drawn in its teams'
 * colours with their crests, and its score. The day's matches come from ESPN's public boards (the ones Live scores
 * reads), read now and then while a page with cards is open: every ten minutes, every minute while one is being played.
 * A show is that match when both of its sides are its two teams, by any of their names.
 */
public final class LiveMatchCards {
    private LiveMatchCards() {}

    /** A match on the boards: its teams (names, crests, colours) and how it stands. */
    public static final class Match {
        public String homeName = "", awayName = "", home = "", away = "", homeLogo = "", awayLogo = "";
        /** The colours to draw each half in (ARGB), told apart from each other (distinct()). */
        public int homeColor, awayColor;
        public int homeScore, awayScore;
        /** pre, in or post; the clock (63', HT, FT) or the kick-off time. */
        public String state = "pre", clock = "";
        public long start;
        /** A team kept spoiler-free plays: no score shown. */
        public boolean quiet;
        /** Every name of each team, as compared (folded, without club words). */
        final List<String> homeKeys = new ArrayList<>(), awayKeys = new ArrayList<>();

        /** Both crests: the card can be drawn (else it stays as it was). */
        public boolean drawable() {
            return !homeLogo.isEmpty() && !awayLogo.isEmpty();
        }

        /** The pill: 2–1 · 63', FT 4–4, or Starts 7:30 PM; "" when it would give a spoiler-free match away. */
        public String pill() {
            if (state.equals("pre")) return start > 0 ? Ui.tr("Começa ", "Starts ") + Ui.time(start) : "";
            if (quiet) return "";
            if (state.equals("post")) return clock.isEmpty() ? homeScore + "–" + awayScore : clock + " " + homeScore + "–" + awayScore;
            return homeScore + "–" + awayScore + (clock.isEmpty() ? "" : " · " + clock);
        }
    }

    private static final String[] BOARDS = {"soccer/all", "football/nfl", "basketball/nba", "hockey/nhl", "baseball/mlb"};
    private static final long EVERY = 10 * 60_000L, LIVE_EVERY = 60_000L;
    private static volatile List<Match> matches = new ArrayList<>();
    private static long readAt;

    /** The boards read again when it's time (background thread); true when they were. */
    public static synchronized boolean refresh(Context c) {
        long now = System.currentTimeMillis();
        boolean playing = false;
        for (Match m : matches) if (m.state.equals("in")) playing = true;
        if (now - readAt < (playing ? LIVE_EVERY : EVERY)) return false;
        readAt = now;
        List<Match> out = new ArrayList<>();
        for (String board : BOARDS) {
            try {
                JSONArray events = new JSONObject(LiveScores.text(LiveScores.base() + board + "/scoreboard")).optJSONArray("events");
                for (int i = 0; events != null && i < events.length(); i++) {
                    Match m = read(events.optJSONObject(i));
                    if (m != null) out.add(m);
                }
            } catch (Exception e) {
                AppLog.w("match cards: " + board + ": " + e.getMessage());
            }
        }
        Set<String> quiet = LiveOptions.spoilerFree(c);
        for (Match m : out) m.quiet = quiet.contains(LiveNames.key(m.homeName)) || quiet.contains(LiveNames.key(m.awayName));
        if (!out.isEmpty() || matches.isEmpty()) matches = out;     // a board that couldn't be read keeps what there was
        return true;
    }

    /** The match a show is (its title), or null. */
    public static Match find(String title) {
        String key = title == null ? null : LiveHome.teamsKey(title);
        if (key == null) return null;
        int bar = key.indexOf('|');
        String a = key.substring(0, bar), b = key.substring(bar + 1);
        for (Match m : matches) if (is(m, a, b)) return m;
        return null;
    }

    // MARK: - Pure

    /** An event of a board as a match, or null (not two teams). */
    static Match read(JSONObject ev) {
        if (ev == null) return null;
        JSONArray comps = ev.optJSONArray("competitions");
        JSONObject comp = comps == null ? null : comps.optJSONObject(0);
        JSONArray teams = comp == null ? null : comp.optJSONArray("competitors");
        if (teams == null || teams.length() != 2) return null;
        Match m = new Match();
        int homeAlt = 0, awayAlt = 0;
        for (int i = 0; i < 2; i++) {
            JSONObject t = teams.optJSONObject(i), team = t == null ? null : t.optJSONObject("team");
            if (team == null) return null;
            boolean home = "home".equals(t.optString("homeAway")) || i == 0 && !"away".equals(t.optString("homeAway"));
            String name = team.optString("displayName"), abbr = team.optString("abbreviation", team.optString("shortDisplayName"));
            String logo = team.optString("logo");
            if (logo.isEmpty() && team.optJSONArray("logos") != null && team.optJSONArray("logos").optJSONObject(0) != null) {
                logo = team.optJSONArray("logos").optJSONObject(0).optString("href");
            }
            int color = colour(team.optString("color")), alt = colour(team.optString("alternateColor"));
            List<String> keys = home ? m.homeKeys : m.awayKeys;
            for (String k : new String[] {"displayName", "shortDisplayName", "name", "location"}) {
                String s = key(team.optString(k));
                if (s.length() >= 3 && !keys.contains(s)) keys.add(s);
            }
            if (home) {
                m.homeName = name; m.home = abbr; m.homeLogo = logo; m.homeColor = color; homeAlt = alt;
                m.homeScore = parse(t.optString("score"));
            } else {
                m.awayName = name; m.away = abbr; m.awayLogo = logo; m.awayColor = color; awayAlt = alt;
                m.awayScore = parse(t.optString("score"));
            }
        }
        int[] two = distinct(m.homeColor, homeAlt, m.awayColor, awayAlt);
        m.homeColor = two[0];
        m.awayColor = two[1];
        JSONObject status = ev.optJSONObject("status"), type = status == null ? null : status.optJSONObject("type");
        m.state = type == null ? "pre" : type.optString("state", "pre");
        m.start = LiveScores.when(ev.optString("date"));
        if (m.state.equals("in")) {
            String detail = type.optString("shortDetail");
            m.clock = detail.isEmpty() ? status.optString("displayClock") : detail;
        } else if (m.state.equals("post")) {
            m.clock = type.optString("shortDetail", "FT");
        }
        return m;
    }

    /** A team's name as compared: folded, without club words, by its main name (as the guide's sides are). */
    static String key(String name) {
        String key = LiveHome.teamsKey("x v " + (name == null ? "" : name));      // the same reading as the titles get
        if (key == null) return "";
        int bar = key.indexOf('|');
        String a = key.substring(0, bar), b = key.substring(bar + 1);
        return a.equals("x") ? b : a;
    }

    /** Whether a show's two sides (as compared) are this match's teams, either way round. */
    static boolean is(Match m, String a, String b) {
        return named(a, m.homeKeys) && named(b, m.awayKeys) || named(a, m.awayKeys) && named(b, m.homeKeys);
    }

    /** Whether a side is one of a team's names: the same, or one is the other with words before or after it. */
    static boolean named(String side, List<String> names) {
        if (side.isEmpty()) return false;
        for (String n : names) {
            if (n.equals(side)) return true;
            String shorter = n.length() < side.length() ? n : side, longer = shorter == n ? side : n;
            if (shorter.length() >= 4 && (" " + longer + " ").contains(" " + shorter + " ")) return true;
        }
        return false;
    }

    /** ESPN's colour ("1e4fa0") as ARGB; 0 none. */
    static int colour(String hex) {
        if (hex == null) return 0;
        String h = hex.trim().replace("#", "");
        if (!h.matches("(?i)[0-9a-f]{6}")) return 0;
        return 0xFF000000 | Integer.parseInt(h, 16);
    }

    /**
     * The two halves' colours, told apart: the away team's second colour when its first is too close to the home
     * team's (or missing), else the home team's second; a neutral grey for one that has none.
     */
    static int[] distinct(int home, int homeAlt, int away, int awayAlt) {
        if (home == 0) home = homeAlt != 0 ? homeAlt : 0xFF2C2C30;
        if (away == 0) away = awayAlt != 0 ? awayAlt : 0xFF3A3A40;
        if (distance(home, away) < 90) {
            if (awayAlt != 0 && distance(home, awayAlt) >= 90) away = awayAlt;
            else if (homeAlt != 0 && distance(homeAlt, away) >= 90) home = homeAlt;
        }
        return new int[] {home, away};
    }

    static int distance(int a, int b) {
        int dr = (a >> 16 & 0xFF) - (b >> 16 & 0xFF), dg = (a >> 8 & 0xFF) - (b >> 8 & 0xFF), db = (a & 0xFF) - (b & 0xFF);
        return (int) Math.sqrt(dr * dr + dg * dg + db * db);
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
