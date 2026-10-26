package com.glasslauncher.tv.live;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * What TMDB knows of a show in the guide, found by its exact name (with the person's TMDB key; none: nothing): its age
 * rating where the guide has none, its score, synopsis, year, genres, cast and pictures. For the show's page and the
 * Parental controls' age lock. Kept a month in a small file (live-tmdb.json), so a name is asked once.
 */
final class LiveTmdb {
    private LiveTmdb() {}

    static final class Info {
        int id, age = -1, votes;
        boolean tv;
        double score;
        String year = "", genres = "", overview = "", cast = "", backdrop = "", poster = "";
    }

    private static final String IMAGES = "https://image.tmdb.org/t/p/w1280", POSTERS = "https://image.tmdb.org/t/p/w342";
    private static final long KEEP = 30L * 24 * 3600_000L, RETRY = 24 * 3600_000L;
    private static final int MAX = 600;
    private static final Map<String, Info> FOUND = new HashMap<>();
    private static final Map<String, Long> NONE = new HashMap<>();      // names TMDB doesn't have, and when asked
    private static final Map<String, Long> AT = new HashMap<>();
    private static final Set<String> ASKING = new HashSet<>();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static boolean loaded;

    /** The age `p` is rated: the guide's, else TMDB's (asked in the background; -1 until then, or none). */
    static int age(Context c, Xtream.Program p) {
        if (p == null) return -1;
        if (p.age >= 0) return p.age;
        Info i = of(c, p.title, null);
        return i == null ? -1 : i.age;
    }

    /** TMDB's facts for the show called `title`, or null (not found, or asking: `found` runs when it comes). */
    static Info of(Context c, String title, Runnable found) {
        final String name = LiveArt.name(title);
        if (name.length() < 3) return null;
        final Context app = c.getApplicationContext();
        synchronized (FOUND) {
            if (!loaded) load(app);
            Info known = FOUND.get(name);
            if (known != null) return known;
            Long none = NONE.get(name);
            if (none != null && System.currentTimeMillis() - none < RETRY) return null;
            if (Prefs.tmdbKey(app).isEmpty() || !ASKING.add(name)) return null;
        }
        IO.execute(() -> {
            Info i = null;
            boolean answered = true;
            try {
                i = search(app, name);
            } catch (IOException | JSONException | RuntimeException e) {
                answered = false;       // no network: asked again next time
            }
            synchronized (FOUND) {
                ASKING.remove(name);
                long now = System.currentTimeMillis();
                if (i != null) {
                    FOUND.put(name, i);
                    AT.put(name, now);
                } else if (answered) {
                    NONE.put(name, now);
                }
                if (answered) save(app);
            }
            if (i != null && found != null) found.run();
        });
        return null;
    }

    /** The show (else the film) with exactly that name, with its details; null when TMDB has none. */
    private static Info search(Context c, String name) throws IOException, JSONException {
        String key = Prefs.tmdbKey(c), lang = "&language=" + Recs.lang();
        for (String kind : new String[] {"tv", "movie"}) {
            boolean tv = kind.equals("tv");
            JSONArray results = LiveArt.get(key, "/search/" + kind + "?query=" + URLEncoder.encode(name, "UTF-8") + lang).optJSONArray("results");
            for (int r = 0; results != null && r < results.length() && r < 5; r++) {
                JSONObject o = results.optJSONObject(r);
                if (o == null) continue;
                String a = o.optString(tv ? "name" : "title"), b = o.optString(tv ? "original_name" : "original_title");
                if (!a.equalsIgnoreCase(name) && !b.equalsIgnoreCase(name)) continue;
                JSONObject d = LiveArt.get(key, "/" + kind + "/" + o.optInt("id") + "?append_to_response="
                        + (tv ? "content_ratings,aggregate_credits" : "release_dates,credits") + lang);
                return info(d, tv);
            }
        }
        return null;
    }

    private static Info info(JSONObject d, boolean tv) {
        Info i = new Info();
        i.id = d.optInt("id");
        i.tv = tv;
        i.score = d.optDouble("vote_average", 0);
        i.votes = d.optInt("vote_count", 0);
        String date = d.optString(tv ? "first_air_date" : "release_date", "");
        i.year = date.length() >= 4 ? date.substring(0, 4) : "";
        i.overview = d.optString("overview", "");
        String back = d.optString("backdrop_path", ""), poster = d.optString("poster_path", "");
        i.backdrop = back.startsWith("/") ? IMAGES + back : "";
        i.poster = poster.startsWith("/") ? POSTERS + poster : "";
        StringBuilder g = new StringBuilder();
        JSONArray genres = d.optJSONArray("genres");
        for (int n = 0; genres != null && n < genres.length() && n < 3; n++) {
            JSONObject o = genres.optJSONObject(n);
            if (o != null) g.append(g.length() > 0 ? ", " : "").append(o.optString("name"));
        }
        i.genres = g.toString();
        JSONObject credits = d.optJSONObject(tv ? "aggregate_credits" : "credits");
        JSONArray people = credits == null ? null : credits.optJSONArray("cast");
        StringBuilder cast = new StringBuilder();
        for (int n = 0; people != null && n < people.length() && n < 5; n++) {
            JSONObject o = people.optJSONObject(n);
            if (o != null) cast.append(cast.length() > 0 ? ", " : "").append(o.optString("name"));
        }
        i.cast = cast.toString();
        i.age = tv ? tvAge(d.optJSONObject("content_ratings")) : movieAge(d.optJSONObject("release_dates"));
        return i;
    }

    /** The countries asked for a rating: the person's (tmdb_region), then the US and the UK. */
    private static String[] regions() {
        String mine = Config.TMDB_REGION.trim().toUpperCase(Locale.ROOT);
        return new String[] {mine.isEmpty() ? "US" : mine, "US", "GB"};
    }

    private static int tvAge(JSONObject ratings) {
        JSONArray list = ratings == null ? null : ratings.optJSONArray("results");
        if (list == null) return -1;
        for (String region : regions()) {
            for (int n = 0; n < list.length(); n++) {
                JSONObject o = list.optJSONObject(n);
                if (o != null && region.equals(o.optString("iso_3166_1"))) {
                    int age = XmltvStore.ageOf(o.optString("rating"));
                    if (age >= 0) return age;
                }
            }
        }
        return -1;
    }

    private static int movieAge(JSONObject dates) {
        JSONArray list = dates == null ? null : dates.optJSONArray("results");
        if (list == null) return -1;
        for (String region : regions()) {
            for (int n = 0; n < list.length(); n++) {
                JSONObject o = list.optJSONObject(n);
                if (o == null || !region.equals(o.optString("iso_3166_1"))) continue;
                JSONArray releases = o.optJSONArray("release_dates");
                for (int m = 0; releases != null && m < releases.length(); m++) {
                    JSONObject rel = releases.optJSONObject(m);
                    int age = rel == null ? -1 : XmltvStore.ageOf(rel.optString("certification"));
                    if (age >= 0) return age;
                }
            }
        }
        return -1;
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
                String name = it.next();
                JSONObject o = all.optJSONObject(name);
                if (o == null) continue;
                long at = o.optLong("at");
                if (now - at > KEEP) continue;
                if (o.optBoolean("none")) {
                    NONE.put(name, at);
                    continue;
                }
                Info i = new Info();
                i.id = o.optInt("id");
                i.tv = o.optBoolean("tv");
                i.age = o.optInt("age", -1);
                i.score = o.optDouble("score", 0);
                i.votes = o.optInt("votes");
                i.year = o.optString("year");
                i.genres = o.optString("genres");
                i.overview = o.optString("overview");
                i.cast = o.optString("cast");
                i.backdrop = o.optString("backdrop");
                i.poster = o.optString("poster");
                FOUND.put(name, i);
                AT.put(name, at);
            }
        } catch (IOException | JSONException ignored) {
        }
    }

    private static void save(Context c) {
        try {
            JSONObject all = new JSONObject();
            int n = 0;
            for (Map.Entry<String, Info> e : FOUND.entrySet()) {
                if (n++ >= MAX) break;
                Info i = e.getValue();
                Long at = AT.get(e.getKey());
                all.put(e.getKey(), new JSONObject().put("id", i.id).put("tv", i.tv).put("age", i.age).put("score", i.score)
                        .put("votes", i.votes).put("year", i.year).put("genres", i.genres).put("overview", i.overview)
                        .put("cast", i.cast).put("backdrop", i.backdrop).put("poster", i.poster).put("at", at == null ? 0 : at));
            }
            for (Map.Entry<String, Long> e : NONE.entrySet()) {
                if (n++ >= MAX) break;
                all.put(e.getKey(), new JSONObject().put("none", true).put("at", e.getValue()));
            }
            Xtream.write(file(c), all.toString());
        } catch (IOException | JSONException e) {
            AppLog.w("live: couldn't keep what TMDB said");
        }
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), "live-tmdb.json");
    }
}
