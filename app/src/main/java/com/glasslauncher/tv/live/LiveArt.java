package com.glasslauncher.tv.live;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A show's picture for Live TV's guide and list (YouTube TV's): the full guide's own when it has one, else the
 * show's backdrop on TMDB, found by its name (with the person's TMDB key; none: no pictures). Looked up once per
 * name and kept while Live TV is open; the images themselves go through LiveLogos (its disk cache).
 */
final class LiveArt {
    private LiveArt() {}

    private static final String API = "https://api.themoviedb.org/3", IMAGES = "https://image.tmdb.org/t/p/w780";
    private static final Map<String, String> FOUND = new HashMap<>();
    private static final Set<String> ASKED = new HashSet<>();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    /** The TMDB key the answers in FOUND came with: another key (or a first one) asks again. */
    private static String keyUsed = "";

    /** The picture's address for `p`, "" when there's none (yet: `found` runs when the search ends). */
    static String of(Context c, Xtream.Program p, Runnable found) {
        if (p == null) return "";
        if (p.icon != null && !p.icon.isEmpty()) return p.icon;
        String name = name(p.title);
        if (name.length() < 3) return "";
        String key = Prefs.tmdbKey(c);
        if (key.isEmpty()) {                // nothing asked, nothing kept: a key typed later finds them all
            AppLog.w("art: no TMDB key");
            return "";
        }
        synchronized (FOUND) {
            if (!key.equals(keyUsed)) {
                keyUsed = key;
                FOUND.clear();
            }
            String known = FOUND.get(name);
            if (known != null) return known;
            if (!ASKED.add(name)) return "";
        }
        final Context app = c.getApplicationContext();
        AppLog.w("art: asking TMDB for " + name);
        IO.execute(() -> {
            String url = search(app, name);
            synchronized (FOUND) {
                if (url != null) FOUND.put(name, url); else ASKED.remove(name);     // an error: asked again later
            }
            AppLog.w("art: " + name + (url == null ? ": TMDB couldn't be asked" : url.isEmpty() ? ": none on TMDB" : ": found"));
            if (url != null && !url.isEmpty() && found != null) found.run();
        });
        return "";
    }

    /** The same name, whatever the case and the punctuation ("Get Jiro" is TMDB's "Get Jiro!"). */
    private static boolean same(String a, String b) {
        String x = Xtream.fold(a).replace(" ", ""), y = Xtream.fold(b).replace(" ", "");
        return !x.isEmpty() && x.equals(y);
    }

    /** "PL Stories - EP21", "The Repair Shop (HD)": the show's own name. */
    static String name(String title) {
        return title == null ? "" : title.replaceAll("\\s*[\\[(][^\\])]*[\\])]", "")
                .replaceAll("\\s+-\\s+(EP|Ep|Episode|S\\d).*$", "").replaceAll("\\s+S\\d+\\s*E\\d+.*$", "").trim();
    }

    /** The backdrop of the show (else the film) with exactly that name; "" none, null when TMDB couldn't be asked. */
    private static String search(Context c, String name) {
        String key = Prefs.tmdbKey(c);
        if (key.isEmpty()) return "";
        for (String kind : new String[] {"tv", "movie"}) {
            try {
                JSONArray results = get(key, "/search/" + kind + "?query=" + URLEncoder.encode(name, "UTF-8")).optJSONArray("results");
                for (int i = 0; results != null && i < results.length() && i < 5; i++) {
                    JSONObject o = results.optJSONObject(i);
                    if (o == null) continue;
                    String a = o.optString(kind.equals("tv") ? "name" : "title"), b = o.optString(kind.equals("tv") ? "original_name" : "original_title");
                    String path = o.optString("backdrop_path", "");
                    if (path.startsWith("/") && (same(a, name) || same(b, name))) return IMAGES + path;
                }
            } catch (IOException | RuntimeException | org.json.JSONException e) {
                return null;
            }
        }
        return "";
    }

    static JSONObject get(String key, String path) throws IOException, org.json.JSONException {
        boolean bearer = key.startsWith("eyJ");     // a read access token (v4), else an API key (v3)
        String url = API + path + (bearer ? "" : "&api_key=" + URLEncoder.encode(key, "UTF-8"));
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(10000);
        if (bearer) conn.setRequestProperty("Authorization", "Bearer " + key);
        try (InputStream in = conn.getInputStream()) {
            return new JSONObject(Xtream.read(in));
        } finally {
            conn.disconnect();
        }
    }
}
