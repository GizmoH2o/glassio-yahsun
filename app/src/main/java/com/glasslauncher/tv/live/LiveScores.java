package com.glasslauncher.tv.live;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Live scores for the teams followed (Settings > Reminders and recordings: Live scores, Goal alerts), from ESPN's
 * public scoreboards (no account). Only while a followed team is on air (the full guide has it now, or a match
 * panel says so) and only while Live TV or a match panel is on screen: the big all-leagues board now and then to find
 * the match, then its league's small board once a minute.
 */
final class LiveScores {
    /** One match's score, for one followed team. */
    static final class Score {
        String term = "";       // the followed words it's for, folded
        String event = "", board = "";     // ESPN's event id; the board it's read from ("soccer/eng.1")
        String home = "", away = "";       // the teams' short names (MUN, BOU)
        String homeName = "", awayName = "";     // and their full ones (the alerts' check of a channel)
        int homeScore, awayScore;
        String state = "pre", clock = "";  // pre, in or post; 63', HT, FT, or the kickoff time
        long start, endedAt;
        /** When the board last told it. */
        long readAt;
        /** Just found (on the big board): its first reading from its own board isn't a goal. */
        boolean fresh = true;
        /** Red cards seen so far (-1: not read yet, so the first reading isn't news). */
        int reds = -1;
        boolean toldHalf;
        /** Told it's a close game (only once a match). */
        boolean toldClose;

        /** MUN 1–0 BOU · 63' */
        String line() {
            return home + " " + homeScore + "–" + awayScore + " " + away + (clock.isEmpty() ? "" : "  ·  " + clock);
        }
    }

    interface Listener {
        void changed();

        /** A goal (a score going up) for `s`, `forHome`: the home side scored. */
        void goal(Score s, boolean forHome);

        /** Half-time, full-time or a red card: a line to show. */
        default void news(Score s, String line) {}

        /** A match that's close and late (LiveAlerts). */
        default void close(Score s) {}
    }

    private static final String ESPN = "https://site.api.espn.com/apis/site/v2/sports/";
    /** The boards' address: ESPN's, or a stand-in on the computer (TestHook), in memory only. */
    private static volatile String BASE = ESPN;
    /** Where a followed team's match is looked for: football everywhere, then the big American leagues. */
    /** Where the boards are read from now. */
    static String base() {
        return BASE;
    }

    private static final String[] BOARDS = {"soccer/all", "football/nfl", "basketball/nba", "hockey/nhl", "baseball/mlb"};
    private static final long FIND_EVERY = 20 * 60_000L, POLL = 60_000L, KEEP_ENDED = 30 * 60_000L;

    private static LiveScores instance;
    private final Context c;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Map<String, Score> scores = new ConcurrentHashMap<>();       // by followed words (folded)
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final List<String> wanted = new CopyOnWriteArrayList<>();          // told to look now (a match panel)
    private long foundAt;
    private boolean running;

    static synchronized LiveScores get(Context c) {
        if (instance == null) instance = new LiveScores(c.getApplicationContext());
        return instance;
    }

    private LiveScores(Context c) {
        this.c = c;
    }

    /** On screen: scores kept up to date (once a minute) while `l` listens. */
    void watch(Listener l) {
        listeners.add(l);
        if (running) return;
        running = true;
        main.post(tick);
    }

    void unwatch(Listener l) {
        listeners.remove(l);
        if (!listeners.isEmpty()) return;
        running = false;
        main.removeCallbacks(tick);
    }

    /** Scores from a stand-in (`base` ends in /; "off": ESPN's again), all found ones forgotten. TestHook. */
    void test(String base) {
        BASE = "off".equals(base) ? ESPN : base;
        scores.clear();
        foundAt = 0;
        changed();
    }

    /** A followed team's match is on (a match panel): look for its score now. */
    void want(String folded) {
        if (!wanted.contains(folded)) wanted.add(folded);
        foundAt = 0;
        if (running) {
            main.removeCallbacks(tick);
            main.post(tick);
        }
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            io.execute(LiveScores.this::refresh);
            main.postDelayed(this, POLL);
        }
    };

    /** The score for a show (its title, folded, has a followed team's words), or null. */
    Score forTitle(String folded) {
        if (scores.isEmpty() || folded == null) return null;
        for (Score s : scores.values()) if (LiveNames.of(c, s.term).in(folded)) return hidden(s.term) || stale(s.state, s.readAt, System.currentTimeMillis()) ? null : s;
        return null;
    }

    /** Whether any match is being followed (else nothing to look up). */
    boolean any() {
        return !scores.isEmpty();
    }

    Score forTerm(String folded) {
        Score s = scores.get(folded);
        return s == null || hidden(folded) || stale(s.state, s.readAt, System.currentTimeMillis()) ? null : s;
    }

    /** A match on whose board hasn't answered for this long: an old score shown as live would mislead, so none. */
    private static final long STALE = 5 * 60_000L;

    /** Whether a score can't be trusted any more: a match in play the board hasn't told about lately. Pure. */
    static boolean stale(String state, long readAt, long now) {
        return "in".equals(state) && readAt > 0 && now - readAt > STALE;
    }

    /** Nothing told about this match: its team is kept spoiler-free, or it's being recorded. */
    private boolean quiet(String folded) {
        return hidden(folded) || LiveAlerts.recording(c, folded);
    }

    /** A team kept spoiler-free: its score is still read, but shown nowhere and no goal is told. */
    private boolean hidden(String folded) {
        return LiveOptions.spoilerFree(c).contains(LiveNames.key(folded));
    }

    // MARK: - Background

    private void refresh() {
        if (!LiveOptions.scores(c)) {
            if (!scores.isEmpty()) {
                scores.clear();
                changed();
            }
            return;
        }
        long now = System.currentTimeMillis();
        // Ended a while ago, or no longer followed: gone.
        List<String> terms = new ArrayList<>();
        for (String t : LiveFollow.all(c)) terms.add(Xtream.fold(t));
        for (Map.Entry<String, Score> e : scores.entrySet()) {
            Score s = e.getValue();
            if (!terms.contains(e.getKey()) || s.endedAt > 0 && now - s.endedAt > KEEP_ENDED) scores.remove(e.getKey());
        }
        // Teams on air without a score yet: looked for now and then.
        List<String> look = new ArrayList<>();
        for (String t : terms) {
            if (scores.containsKey(t)) continue;
            if (wanted.contains(t) || onAir(t, now)) look.add(t);
        }
        wanted.clear();
        boolean changed = false;
        if (!look.isEmpty() && now - foundAt > FIND_EVERY) {
            foundAt = now;
            changed = find(look, now);
        }
        // The matches found: their boards, once each.
        Map<String, JSONArray> boards = new HashMap<>();
        for (Score s : scores.values()) {
            if (s.state.equals("post")) continue;
            if (s.state.equals("pre") && s.start - now > 30 * 60_000L) continue;     // kick-off still far off
            JSONArray events = boards.get(s.board);
            if (events == null && !boards.containsKey(s.board)) {
                try {
                    events = new JSONObject(text(BASE + s.board + "/scoreboard")).optJSONArray("events");
                } catch (Exception e) {
                    AppLog.w("scores: " + s.board + ": " + e.getMessage());
                }
                boards.put(s.board, events);
            }
            for (int i = 0; events != null && i < events.length(); i++) {
                JSONObject ev = events.optJSONObject(i);
                if (ev != null && s.event.equals(ev.optString("id"))) {
                    changed |= update(s, ev, !s.fresh);
                    s.fresh = false;
                    break;
                }
            }
        }
        if (changed) changed();
    }

    /** Whether the full guide has a show with these words on now (or starting within a quarter of an hour). */
    private boolean onAir(String folded, long now) {
        return !LiveNames.find(c, LiveNames.of(c, folded), now, now + 15 * 60_000L, 40).isEmpty();
    }

    /** Looks for each team's match of the day on the boards; true when one was found. */
    private boolean find(List<String> look, long now) {
        boolean any = false;
        for (String board : BOARDS) {
            if (look.isEmpty()) break;
            JSONArray events;
            try {
                events = new JSONObject(text(BASE + board + "/scoreboard")).optJSONArray("events");
            } catch (Exception e) {
                AppLog.w("scores: " + board + ": " + e.getMessage());
                continue;
            }
            for (int i = 0; events != null && i < events.length() && !look.isEmpty(); i++) {
                JSONObject ev = events.optJSONObject(i);
                JSONObject comp = ev == null ? null : first(ev.optJSONArray("competitions"));
                JSONArray teams = comp == null ? null : comp.optJSONArray("competitors");
                if (teams == null) continue;
                for (int t = 0; t < look.size(); t++) {
                    String term = look.get(t);
                    if (!plays(LiveNames.of(c, term), teams)) continue;
                    Score s = new Score();
                    s.term = term;
                    s.event = ev.optString("id");
                    s.board = board;
                    if (board.equals("soccer/all")) s.board = "soccer/" + league(s.event);      // its league's small board
                    update(s, ev, false);
                    if (s.state.equals("post") && now - s.start > 4 * 3600_000L) continue;     // yesterday's
                    scores.put(term, s);
                    AppLog.w("scores: following " + s.home + " v " + s.away + " (" + s.board + ")");
                    look.remove(t);
                    any = true;
                    break;
                }
            }
        }
        return any;
    }

    /** A football match's league (eng.1), from its summary; "all" when it can't tell (the big board then). */
    private static String league(String event) {
        try {
            JSONObject h = new JSONObject(text(BASE + "soccer/all/summary?event=" + event)).optJSONObject("header");
            String slug = h == null ? "" : h.optJSONObject("league") == null ? "" : h.optJSONObject("league").optString("slug");
            return slug.isEmpty() ? "all" : slug;
        } catch (Exception e) {
            return "all";
        }
    }

    /** Whether a team in `teams` is the followed one, under any of its names. */
    static boolean plays(LiveNames.Names names, JSONArray teams) {
        for (int i = 0; i < teams.length(); i++) {
            JSONObject team = teams.optJSONObject(i) == null ? null : teams.optJSONObject(i).optJSONObject("team");
            if (team == null) continue;
            String full = Xtream.fold(team.optString("displayName"));
            for (String key : new String[] {"displayName", "shortDisplayName", "name"}) {
                String n = Xtream.fold(team.optString(key));
                if (n.length() < 3) continue;
                if (names.isTeam(n, full)) return true;
            }
        }
        return false;
    }

    /** The event's teams, score, state and clock into `s`; with `tell`, a goal is told. True when anything changed. */
    private boolean update(Score s, JSONObject ev, boolean tell) {
        JSONObject comp = first(ev.optJSONArray("competitions"));
        JSONArray teams = comp == null ? null : comp.optJSONArray("competitors");
        if (teams == null) return false;
        s.readAt = System.currentTimeMillis();
        String before = s.line() + s.state;
        int oldHome = s.homeScore, oldAway = s.awayScore;
        boolean wasLive = s.state.equals("in");
        for (int i = 0; i < teams.length(); i++) {
            JSONObject t = teams.optJSONObject(i);
            if (t == null) continue;
            JSONObject team = t.optJSONObject("team");
            String name = team == null ? "" : team.optString("abbreviation", team.optString("shortDisplayName"));
            int score = parse(t.optString("score"));
            String full = team == null ? "" : team.optString("displayName", name);
            if ("home".equals(t.optString("homeAway"))) {
                s.home = name;
                s.homeName = full;
                s.homeScore = score;
            } else {
                s.away = name;
                s.awayName = full;
                s.awayScore = score;
            }
        }
        JSONObject status = ev.optJSONObject("status");
        JSONObject type = status == null ? null : status.optJSONObject("type");
        s.state = type == null ? "pre" : type.optString("state", "pre");
        s.start = when(ev.optString("date"));
        if (s.state.equals("in")) {
            String detail = type.optString("shortDetail");
            s.clock = detail.isEmpty() ? status.optString("displayClock") : detail;
        } else if (s.state.equals("post")) {
            s.clock = type.optString("shortDetail", "FT");
            if (s.endedAt == 0) s.endedAt = System.currentTimeMillis();
        } else {
            s.clock = s.start > 0 ? Ui.time(s.start) : "";
        }
        if (tell && wasLive && LiveOptions.goalAlerts(c)) {
            if (s.homeScore > oldHome) goal(s, true);
            else if (s.awayScore > oldAway) goal(s, false);
        }
        boolean half = type != null && halfTime(type.optString("name"), type.optString("shortDetail"));
        if (tell && !quiet(s.term)) {
            if (half && !s.toldHalf && LiveOptions.matchNews(c)) news(s, Ui.tr("Intervalo", "Half-time") + "  ·  " + s.line());
            if (wasLive && s.state.equals("post") && LiveOptions.matchNews(c)) news(s, Ui.tr("Fim de jogo", "Full-time") + "  ·  " + s.line());
        }
        if (half) s.toldHalf = true;
        if (!s.toldClose && LiveAlerts.closeGame(s.state, s.clock, s.homeScore, s.awayScore)) {
            s.toldClose = true;
            if (tell && LiveOptions.closeGames(c) && !quiet(s.term)) main.post(() -> { for (Listener l : listeners) l.close(s); });
        }
        JSONArray details = comp.optJSONArray("details");
        int reds = redCards(details);
        if (tell && s.reds >= 0 && reds > s.reds && !quiet(s.term) && LiveOptions.redCardAlerts(c)) {
            news(s, Ui.tr("Cartão vermelho", "Red card") + "  ·  " + s.line());
        }
        s.reds = reds;
        return !before.equals(s.line() + s.state);
    }

    /** Whether a match's status says half-time. Pure. */
    static boolean halfTime(String name, String detail) {
        return "STATUS_HALFTIME".equals(name) || "HT".equalsIgnoreCase(detail == null ? "" : detail.trim());
    }

    /** How many red cards a match's events hold. Pure. */
    static int redCards(JSONArray details) {
        int n = 0;
        for (int i = 0; details != null && i < details.length(); i++) {
            JSONObject d = details.optJSONObject(i);
            if (d == null) continue;
            JSONObject type = d.optJSONObject("type");
            if (d.optBoolean("redCard") || type != null && type.optString("text").toLowerCase(java.util.Locale.ROOT).contains("red card")) n++;
        }
        return n;
    }

    private void news(Score s, String line) {
        main.post(() -> { for (Listener l : listeners) l.news(s, line); });
    }

    /** ESPN's times (2026-10-25T14:00Z, seconds or not), in ms; 0 when unreadable. */
    static long when(String iso) {
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

    private static int parse(String n) {
        try {
            return Integer.parseInt(n.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static JSONObject first(JSONArray a) {
        return a == null || a.length() == 0 ? null : a.optJSONObject(0);
    }

    private void changed() {
        main.post(() -> { for (Listener l : listeners) l.changed(); });
    }

    private void goal(Score s, boolean forHome) {
        if (quiet(s.term)) return;
        main.post(() -> { for (Listener l : listeners) l.goal(s, forHome); });
    }

    static String text(String url) throws IOException {
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
}
