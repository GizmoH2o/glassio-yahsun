package com.glasslauncher.tv.live;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * What ESPN's public pages know about a followed team: its crest, league table, results and fixtures, and its match's
 * stats and lineups. Read when its page opens. A team kept spoiler-free gets its crest and fixtures, nothing that gives
 * a result away.
 */
public final class LiveTeamInfo {
    private LiveTeamInfo() {}

    private static final String SITE = "https://site.api.espn.com/apis/site/v2/sports/", V2 = "https://site.api.espn.com/apis/v2/sports/";
    /** Where a team is looked for. */
    private static final String[] LEAGUES = {"soccer/eng.1", "soccer/esp.1", "soccer/ger.1", "soccer/ita.1", "soccer/fra.1",
            "soccer/sco.1", "soccer/por.1", "soccer/ned.1", "soccer/eng.2", "soccer/usa.1", "football/nfl", "basketball/nba",
            "hockey/nhl", "baseball/mlb"};
    private static final long DAY = 24 * 3600_000L;

    /** A team as found: what its pages are asked with. */
    static final class Found {
        String league = "", id = "", crest = "";

        boolean none() {
            return id.isEmpty();
        }
    }

    /** One row of a table. */
    public static final class TableRow {
        public int rank;
        public String team = "";
        public String[] numbers = {};
        public boolean mine;
    }

    /** One match. */
    public static final class Game {
        public long start;
        public String title = "", score = "";
    }

    /** One line of a match's stats, each team's. */
    public static final class Stat {
        public String name = "", home = "", away = "";
    }

    /** A team's page. */
    public static final class Info {
        public String crest = "", league = "", homeName = "", awayName = "", homeLineup = "", awayLineup = "";
        public String[] columns = {};
        public final List<TableRow> table = new ArrayList<>();
        public final List<Game> results = new ArrayList<>(), fixtures = new ArrayList<>();
        public final List<Stat> stats = new ArrayList<>();
    }

    // MARK: - Finding the team

    /** The team as saved on the TV (no network), or null when it was never looked for. */
    static Found saved(Context c, String term) {
        JSONObject e = read(c).optJSONObject(term);
        if (e == null) return null;
        Found f = new Found();
        f.league = e.optString("league");
        f.id = e.optString("id");
        f.crest = e.optString("crest");
        return f;
    }

    /** The team: as saved, or looked for through the leagues (a month kept; a week when it isn't a team). Background thread. */
    static synchronized Found find(Context c, String term) {
        JSONObject all = read(c);
        JSONObject e = all.optJSONObject(term);
        if (e != null) {
            Found f = saved(c, term);
            long age = System.currentTimeMillis() - e.optLong("at");
            if (age < (f.none() ? 7 : 30) * DAY) return f;
        }
        LiveNames.Names names = LiveNames.of(c, term);
        Found out = new Found();
        boolean answered = false;
        for (String league : LEAGUES) {
            try {
                JSONObject page = new JSONObject(text(SITE + league + "/teams"));
                answered = true;
                JSONArray teams = page.optJSONArray("sports").optJSONObject(0).optJSONArray("leagues").optJSONObject(0).optJSONArray("teams");
                for (int i = 0; teams != null && i < teams.length() && out.none(); i++) {
                    JSONObject t = teams.optJSONObject(i) == null ? null : teams.optJSONObject(i).optJSONObject("team");
                    if (t != null && matches(names, t)) {
                        out.league = league;
                        out.id = t.optString("id");
                        JSONArray logos = t.optJSONArray("logos");
                        out.crest = logos == null || logos.length() == 0 || logos.optJSONObject(0) == null ? "" : logos.optJSONObject(0).optString("href");
                    }
                }
            } catch (IOException | JSONException | RuntimeException e2) {
                AppLog.w("team info: " + league + ": " + e2.getMessage());
            }
            if (!out.none()) break;
        }
        if (!answered) return out;      // no connection: looked for again next time
        try {
            all.put(term, new JSONObject().put("league", out.league).put("id", out.id).put("crest", out.crest).put("at", System.currentTimeMillis()));
            Xtream.write(file(c), all.toString());
        } catch (IOException | JSONException e3) {
            AppLog.w("team info: couldn't save");
        }
        return out;
    }

    private static boolean matches(LiveNames.Names names, JSONObject team) {
        String full = Xtream.fold(team.optString("displayName"));
        for (String key : new String[] {"displayName", "shortDisplayName", "name"}) {
            String n = Xtream.fold(team.optString(key));
            if (n.length() >= 3 && names.isTeam(n, full)) return true;
        }
        return false;
    }

    // MARK: - Its page

    /** Everything for its page; empty for a show or when nothing answers. Background thread. */
    static Info info(Context c, String term) {
        Info out = new Info();
        Found f = find(c, term);
        if (f.none()) return out;
        out.crest = f.crest;
        boolean spoilerFree = LiveOptions.spoilerFree(c).contains(LiveNames.key(term));
        String live = null;
        try {
            schedule(new JSONObject(text(SITE + f.league + "/teams/" + f.id + "/schedule?fixture=true")), out.fixtures, null);
            if (!spoilerFree) live = schedule(new JSONObject(text(SITE + f.league + "/teams/" + f.id + "/schedule")), null, out.results);
        } catch (IOException | JSONException | RuntimeException e) {
            AppLog.w("team info: schedule: " + e.getMessage());
        }
        if (spoilerFree) return out;
        try {
            standings(new JSONObject(text(V2 + f.league + "/standings")), f.id, f.league.startsWith("soccer/"), out);
        } catch (IOException | JSONException | RuntimeException e) {
            AppLog.w("team info: standings: " + e.getMessage());
        }
        if (live != null) {
            try {
                summary(new JSONObject(text(SITE + f.league + "/summary?event=" + live)), out);
            } catch (IOException | JSONException | RuntimeException e) {
                AppLog.w("team info: summary: " + e.getMessage());
            }
        }
        return out;
    }

    private static final int MAX_GAMES = 5;

    /** A schedule's matches into fixtures (the next, soonest first) and results (the last, newest first); the id of one in play. Pure. */
    static String schedule(JSONObject page, List<Game> fixtures, List<Game> results) {
        JSONArray events = page.optJSONArray("events");
        String live = null;
        List<Game> past = new ArrayList<>(), ahead = new ArrayList<>();
        for (int i = 0; events != null && i < events.length(); i++) {
            JSONObject ev = events.optJSONObject(i);
            JSONObject comp = ev == null ? null : first(ev.optJSONArray("competitions"));
            if (comp == null) continue;
            JSONObject type = comp.optJSONObject("status") == null ? null : comp.optJSONObject("status").optJSONObject("type");
            String state = type == null ? "pre" : type.optString("state", "pre");
            if ("in".equals(state)) live = ev.optString("id");
            Game g = new Game();
            g.start = when(ev.optString("date"));
            g.title = ev.optString("name");
            if ("pre".equals(state)) {
                ahead.add(g);
            } else if ("post".equals(state)) {
                g.score = score(comp.optJSONArray("competitors"));
                past.add(g);
            }
        }
        java.util.Collections.sort(ahead, (a, b) -> Long.compare(a.start, b.start));
        java.util.Collections.sort(past, (a, b) -> Long.compare(b.start, a.start));
        if (fixtures != null) fixtures.addAll(ahead.subList(0, Math.min(MAX_GAMES, ahead.size())));
        if (results != null) results.addAll(past.subList(0, Math.min(MAX_GAMES, past.size())));
        return live;
    }

    /** ARS 2–1 CHE, home first. */
    private static String score(JSONArray teams) {
        String home = "", away = "", hs = "", as = "";
        for (int i = 0; teams != null && i < teams.length(); i++) {
            JSONObject t = teams.optJSONObject(i);
            if (t == null) continue;
            JSONObject team = t.optJSONObject("team");
            String name = team == null ? "" : team.optString("abbreviation");
            Object sc = t.opt("score");
            String n = sc instanceof JSONObject ? ((JSONObject) sc).optString("displayValue") : sc == null ? "" : String.valueOf(sc);
            if ("home".equals(t.optString("homeAway"))) {
                home = name;
                hs = n;
            } else {
                away = name;
                as = n;
            }
        }
        return home + " " + hs + "–" + as + " " + away;
    }

    /** The table the team is in, the rows around it. Pure. */
    static void standings(JSONObject page, String id, boolean football, Info out) {
        JSONArray groups = page.optJSONArray("children");
        JSONObject own = null;
        for (int g = 0; groups != null && g < groups.length() && own == null; g++) {
            JSONObject group = groups.optJSONObject(g);
            JSONObject st = group == null ? null : group.optJSONObject("standings");
            JSONArray entries = st == null ? null : st.optJSONArray("entries");
            for (int i = 0; entries != null && i < entries.length(); i++) {
                JSONObject team = entries.optJSONObject(i).optJSONObject("team");
                if (team != null && id.equals(team.optString("id"))) own = group;
            }
        }
        if (own == null) return;
        out.league = own.optString("name");
        out.columns = football ? new String[] {"P", "GD", "Pts"} : new String[] {"W", "L", "Pct"};
        String[] keys = football ? new String[] {"gamesPlayed", "pointDifferential", "points"} : new String[] {"wins", "losses", "winPercent"};
        JSONArray entries = own.optJSONObject("standings").optJSONArray("entries");
        List<TableRow> rows = new ArrayList<>();
        int mine = -1;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.optJSONObject(i);
            TableRow r = new TableRow();
            JSONObject team = e.optJSONObject("team");
            r.team = team == null ? "" : team.optString("shortDisplayName", team.optString("displayName"));
            r.mine = team != null && id.equals(team.optString("id"));
            if (r.mine) mine = i;
            r.rank = i + 1;
            r.numbers = new String[keys.length];
            for (int k = 0; k < keys.length; k++) r.numbers[k] = stat(e.optJSONArray("stats"), keys[k]);
            String rank = stat(e.optJSONArray("stats"), "rank");
            try {
                if (!rank.isEmpty()) r.rank = Integer.parseInt(rank);
            } catch (NumberFormatException ignored) {
            }
            rows.add(r);
        }
        int from = Math.max(0, Math.min(mine - 2, rows.size() - 5)), to = Math.min(rows.size(), from + 5);
        out.table.addAll(rows.subList(from, to));
    }

    private static String stat(JSONArray stats, String key) {
        for (int i = 0; stats != null && i < stats.length(); i++) {
            JSONObject s = stats.optJSONObject(i);
            if (s != null && key.equals(s.optString("name"))) return s.optString("displayValue");
        }
        return "";
    }

    private static final int MAX_STATS = 6;

    /** A match's stats and starting lineups. Pure. */
    static void summary(JSONObject page, Info out) {
        JSONObject box = page.optJSONObject("boxscore");
        JSONArray teams = box == null ? null : box.optJSONArray("teams");
        if (teams != null && teams.length() == 2) {
            JSONObject a = teams.optJSONObject(0), b = teams.optJSONObject(1);
            out.homeName = name(a);
            out.awayName = name(b);
            JSONArray sa = a.optJSONArray("statistics"), sb = b.optJSONArray("statistics");
            for (int i = 0; sa != null && sb != null && i < sa.length() && out.stats.size() < MAX_STATS; i++) {
                JSONObject s = sa.optJSONObject(i);
                if (s == null || s.optString("label").isEmpty()) continue;
                Stat st = new Stat();
                st.name = s.optString("label");
                st.home = s.optString("displayValue");
                st.away = stat(sb, s.optString("name"));
                out.stats.add(st);
            }
        }
        JSONArray rosters = page.optJSONArray("rosters");
        for (int i = 0; rosters != null && i < rosters.length() && i < 2; i++) {
            JSONArray players = rosters.optJSONObject(i).optJSONArray("roster");
            StringBuilder line = new StringBuilder();
            for (int p = 0; players != null && p < players.length(); p++) {
                JSONObject pl = players.optJSONObject(p);
                if (pl == null || !pl.optBoolean("starter")) continue;
                JSONObject who = pl.optJSONObject("athlete");
                String n = who == null ? "" : who.optString("shortName", who.optString("displayName"));
                if (n.isEmpty()) continue;
                if (line.length() > 0) line.append(", ");
                line.append(n);
            }
            if (i == 0) out.homeLineup = line.toString();
            else out.awayLineup = line.toString();
        }
    }

    private static String name(JSONObject t) {
        JSONObject team = t == null ? null : t.optJSONObject("team");
        return team == null ? "" : team.optString("shortDisplayName", team.optString("displayName"));
    }

    private static JSONObject first(JSONArray a) {
        return a == null || a.length() == 0 ? null : a.optJSONObject(0);
    }

    /** ESPN's times (2026-10-25T14:00Z, seconds or not), in ms; 0 when unreadable. */
    private static long when(String iso) {
        for (String f : new String[] {"yyyy-MM-dd'T'HH:mm'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'"}) {
            try {
                java.text.SimpleDateFormat d = new java.text.SimpleDateFormat(f, java.util.Locale.ROOT);
                d.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                return d.parse(iso).getTime();
            } catch (java.text.ParseException | RuntimeException ignored) {
            }
        }
        return 0;
    }

    private static String text(String url) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(10_000);
        h.setReadTimeout(20_000);
        h.setRequestProperty("User-Agent", "GlassioLive");
        try (InputStream in = h.getInputStream()) {
            if (h.getResponseCode() != 200) throw new IOException("HTTP " + h.getResponseCode());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[32 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            h.disconnect();
        }
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

    private static File file(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), "live-teams.json");
    }
}
