package com.glasslauncher.tv.live;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * An episode's season and number from TMDB, for guides that send only its title ("The Ghouls"): the show found as the
 * show's page finds it (LiveTmdb), then its seasons' episode names, matched to the title. With the person's TMDB key;
 * none: nothing. Each show's episodes are kept a month in a small file (live-tmdb-episodes.json).
 */
final class LiveTmdbEpisodes {
    private LiveTmdbEpisodes() {}

    private static final long KEEP = 30L * 24 * 3600_000L;
    /** The shows kept in the file, the most recent first; the seasons asked of one show; the episodes kept of one. */
    private static final int MAX_SHOWS = 60, MAX_SEASONS = 40, MAX_EPISODES = 3000;
    /** Each show (TMDB's id): its episodes' names (as `key` makes them), each its season × 1000 + number. */
    private static final Map<Integer, Map<String, Integer>> SHOWS = new HashMap<>();
    private static final Map<Integer, Long> AT = new HashMap<>();
    private static final Set<Integer> ASKING = new HashSet<>();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static boolean loaded;
    /** Bumped each time a show's episodes come: what wasn't known may be now. */
    private static volatile int changes;

    static int changes() {
        return changes;
    }

    /**
     * The season and number ({season, episode}) of the episode called `episode` of the show called `show`, or null:
     * not a series TMDB knows, no episode by that name, or not asked yet (with `ask`, asked in the background, and
     * `found` runs when the answer comes).
     */
    static int[] find(Context c, String show, String episode, boolean ask, Runnable found) {
        String wanted = key(episode);
        if (wanted.isEmpty()) return null;
        LiveTmdb.Info info = ask ? LiveTmdb.of(c, show, found) : LiveTmdb.known(c, show);
        if (info == null || !info.tv || info.id <= 0) return null;
        final Context app = c.getApplicationContext();
        Map<String, Integer> eps;
        synchronized (SHOWS) {
            if (!loaded) load(app);
            eps = SHOWS.get(info.id);
            if (eps == null) {
                if (ask && !Prefs.tmdbKey(app).isEmpty() && ASKING.add(info.id)) fetch(app, info.id, found);
                return null;
            }
        }
        String memo = info.id + "\n" + wanted;
        int[] known = MATCHED.get(memo);
        if (known != null) return known.length == 0 ? null : known;
        Integer n = match(eps, episode);
        int[] se = n == null ? new int[0] : new int[] {n / 1000, n % 1000};
        if (MATCHED.size() > 4000) MATCHED.clear();
        MATCHED.put(memo, se);
        return n == null ? null : se;
    }

    /** Each episode line asked of each show, and what it matched ({} for nothing): matching runs once per line. */
    private static final Map<String, int[]> MATCHED = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The episode in `eps` (names as `key` makes them, each its season × 1000 + number) that the guide's `episode`
     * names, or null. Tried in turn, the whole line, then each part of a double bill ("Turtle Boy; Onesies; Tradies"):
     * the same name; the same once spacing, "the"/"a"/"and" and "pt"/"part" don't count ("Turtle Boy" is TMDB's
     * "Turtleboy"); else the one name spelt nearly the same (a letter or two in ten), with the same numbers in it and
     * no other as close.
     */
    static Integer match(Map<String, Integer> eps, String episode) {
        if (eps == null || eps.isEmpty() || episode == null) return null;
        java.util.List<String> tries = new java.util.ArrayList<>();
        tries.add(episode);
        String[] parts = episode.split("[;/|]");
        if (parts.length > 1) for (String part : parts) if (!part.trim().isEmpty()) tries.add(part);
        // Each in turn, the first part before the next (a double bill is numbered by its first episode).
        Map<String, Integer> loose = null;
        for (String t : tries) {
            Integer n = eps.get(key(t));
            if (n != null) return n;
            if (loose == null) {        // the names as compared loosely; one that two episodes share says nothing
                loose = new HashMap<>();
                for (Map.Entry<String, Integer> e : eps.entrySet()) {
                    String l = loose(e.getKey());
                    if (l.isEmpty()) continue;
                    Integer had = loose.put(l, e.getValue());
                    if (had != null && !had.equals(e.getValue())) loose.put(l, -1);
                }
            }
            n = loose.get(loose(t));
            if (n != null && n >= 0) return n;
            n = nearest(loose, loose(t));
            if (n != null) return n;
        }
        return null;
    }

    /** A name as compared loosely: its key without "the", "a", "an", "and", "pt"/"part" spelt alike, nor spaces. */
    static String loose(String name) {
        StringBuilder out = new StringBuilder();
        for (String w : key(name).split(" ")) {
            if (w.isEmpty() || w.equals("the") || w.equals("a") || w.equals("an") || w.equals("and")) continue;
            out.append(w.equals("pt") ? "part" : w);
        }
        return out.toString();
    }

    /** The loose name spelt nearly as `want` (at most one letter in ten off, the same numbers), when only one is. */
    private static Integer nearest(Map<String, Integer> loose, String want) {
        if (want.length() < 6) return null;
        String digits = want.replaceAll("[^0-9]", "");
        int allowed = Math.max(1, want.length() / 10), best = Integer.MAX_VALUE;
        Integer found = null;
        boolean tie = false;
        for (Map.Entry<String, Integer> e : loose.entrySet()) {
            String name = e.getKey();
            if (e.getValue() < 0 || Math.abs(name.length() - want.length()) > allowed) continue;
            if (!name.replaceAll("[^0-9]", "").equals(digits)) continue;
            int d = distance(want, name, allowed);
            if (d > allowed) continue;
            if (d < best) {
                best = d;
                found = e.getValue();
                tie = false;
            } else if (d == best && !e.getValue().equals(found)) {
                tie = true;
            }
        }
        return tie ? null : found;
    }

    /** How many letters to change, add or drop to make `a` into `b` (stops counting past `limit`). */
    private static int distance(String a, String b, int limit) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            int low = cur[0];
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                low = Math.min(low, cur[j]);
            }
            if (low > limit) return limit + 1;
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    /** An episode's name as compared: folded, its punctuation gone. */
    static String key(String name) {
        if (name == null) return "";
        return Xtream.fold(name).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    /** Show `id`'s episodes: in the TV's language, and in English too (most guides' names) when that's another one. */
    private static void fetch(Context app, int id, Runnable found) {
        IO.execute(() -> {
            Map<String, Integer> eps = new HashMap<>();
            boolean answered = true;
            try {
                String lang = Recs.lang();
                episodes(app, id, lang, eps);
                if (!lang.toLowerCase(java.util.Locale.ROOT).startsWith("en")) episodes(app, id, "en-US", eps);
            } catch (IOException | JSONException | RuntimeException e) {
                answered = false;       // no network: asked again next time
            }
            synchronized (SHOWS) {
                ASKING.remove(id);
                if (answered) {
                    SHOWS.put(id, eps);
                    AT.put(id, System.currentTimeMillis());
                    changes++;
                    save(app);
                }
            }
            if (answered && !eps.isEmpty() && found != null) found.run();
        });
    }

    private static void episodes(Context app, int id, String lang, Map<String, Integer> out) throws IOException, JSONException {
        String key = Prefs.tmdbKey(app), q = "?language=" + lang;
        JSONArray seasons = LiveArt.get(key, "/tv/" + id + q).optJSONArray("seasons");
        int asked = 0;
        for (int s = 0; seasons != null && s < seasons.length() && asked < MAX_SEASONS; s++) {
            JSONObject o = seasons.optJSONObject(s);
            int season = o == null ? -1 : o.optInt("season_number", -1);
            if (season < 1) continue;       // the specials (season 0) aren't what a guide numbers
            asked++;
            JSONArray list = LiveArt.get(key, "/tv/" + id + "/season/" + season + q).optJSONArray("episodes");
            for (int e = 0; list != null && e < list.length() && out.size() < MAX_EPISODES; e++) {
                JSONObject ep = list.optJSONObject(e);
                if (ep == null) continue;
                int number = ep.optInt("episode_number", -1);
                String name = key(ep.optString("name"));
                if (number < 0 || number >= 1000 || name.isEmpty() || out.containsKey(name)) continue;
                out.put(name, season * 1000 + number);
            }
        }
    }

    // MARK: - The file

    private static void load(Context c) {
        loaded = true;
        File f = file(c);
        if (!f.exists()) return;
        try (FileInputStream in = new FileInputStream(f)) {
            JSONObject all = new JSONObject(Xtream.read(in));
            long now = System.currentTimeMillis();
            for (Iterator<String> it = all.keys(); it.hasNext(); ) {
                String id = it.next();
                JSONObject o = all.optJSONObject(id);
                if (o == null || now - o.optLong("at") > KEEP) continue;
                JSONObject e = o.optJSONObject("e");
                Map<String, Integer> eps = new HashMap<>();
                for (Iterator<String> names = e == null ? null : e.keys(); names != null && names.hasNext(); ) {
                    String name = names.next();
                    eps.put(name, e.optInt(name));
                }
                SHOWS.put(Integer.parseInt(id), eps);
                AT.put(Integer.parseInt(id), o.optLong("at"));
            }
        } catch (IOException | JSONException | NumberFormatException ignored) {
        }
    }

    private static void save(Context c) {
        try {
            java.util.List<Integer> ids = new java.util.ArrayList<>(SHOWS.keySet());
            ids.sort((a, b) -> Long.compare(AT.getOrDefault(b, 0L), AT.getOrDefault(a, 0L)));
            JSONObject all = new JSONObject();
            for (int n = 0; n < ids.size() && n < MAX_SHOWS; n++) {
                int id = ids.get(n);
                all.put(String.valueOf(id), new JSONObject().put("at", AT.getOrDefault(id, 0L)).put("e", new JSONObject(SHOWS.get(id))));
            }
            Xtream.write(file(c), all.toString());
        } catch (IOException | JSONException e) {
            AppLog.w("live: couldn't keep TMDB's episodes");
        }
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), "live-tmdb-episodes.json");
    }
}
