package com.glasslauncher.tv.live;

import android.content.Context;
import android.util.JsonReader;
import android.util.JsonToken;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Channel logos from iptv-org's open database (github.com/iptv-org/database, public domain): about 35,000 logos,
 * most of them PNGs around 450 px wide, for channels known by name and country. Fetched once a week while Live TV ›
 * Settings › Channel logos is Public database (two files, about 14 MB), kept as a small index of name, country and
 * the best logo's address; a channel finds its logo by its name (country tag and picture tags off) and its country.
 */
final class LivePublicLogos {
    private LivePublicLogos() {}

    private static final String CHANNELS = "https://iptv-org.github.io/api/channels.json";
    private static final String LOGOS = "https://iptv-org.github.io/api/logos.json";
    /** v2: with each channel's id too (guides that use iptv-org's ids, TLC.us, find it exactly). */
    private static final String INDEX = "live-public-logos-2.tsv", OLD_INDEX = "live-public-logos.tsv";
    private static final long FRESH = 7L * 24 * 3600_000L;
    /** After a fetch that failed, how long before the next (not on every opening: it's 14 MB). */
    private static final long RETRY = 6 * 3600_000L;
    /** When the database was last asked for; the matches the last look-up found. */
    private static final String TRIED = "live-public-logos.tried", MATCHES = "live-public-matches.json";
    /**
     * When the database was last found unchanged (the index is as current as at that time), and what its two files
     * were said to be (ETag and Last-Modified), to ask "only if changed" next time.
     */
    private static final String CHECKED = "live-public-logos.checked", VALIDATORS = "live-public-logos.etag";

    /** The index: a folded name to its entries, each {country, logo address}. */
    static final class Index {
        final Map<String, List<String[]>> byName = new HashMap<>();
    }

    private static Index cached;
    private static long cachedAt;

    /**
     * The index, fetched again when it's a week old (or missing); null when there's none and it can't be had.
     * Blocking: the first time downloads the database.
     */
    static synchronized Index index(Context c) {
        File f = new File(c.getFilesDir(), INDEX);
        //noinspection ResultOfMethodCallIgnored
        new File(c.getFilesDir(), OLD_INDEX).delete();
        if (due(c)) {
            touch(new File(c.getFilesDir(), TRIED));
            try {
                if (f.exists() && unchanged(c)) {       // nothing new since: no download, the index and its matches stay
                    touch(new File(c.getFilesDir(), CHECKED));
                    AppLog.w("live: public logos: the database hasn't changed");
                } else {
                    Map<String, String[]> validators = new HashMap<>();
                    build(f, validators);
                    saveValidators(c, validators);
                    AppLog.w("live: public logos: index built");
                }
            } catch (IOException | RuntimeException e) {
                AppLog.w("live: public logos: " + e.getMessage());     // an old index (if any) still serves
            }
        }
        if (!f.exists()) return null;
        if (cached == null || cachedAt != f.lastModified()) {
            Index idx = new Index();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    String[] p = line.split("\t", -1);
                    if (p.length != 3 || p[0].isEmpty() || p[2].isEmpty()) continue;
                    List<String[]> list = idx.byName.get(p[0]);
                    if (list == null) idx.byName.put(p[0], list = new ArrayList<>(2));
                    list.add(new String[] {p[1], p[2]});
                }
            } catch (IOException e) {
                return null;
            }
            cached = idx;
            cachedAt = f.lastModified();
        }
        return cached;
    }

    /** When the index was made (its file's time), 0 without one. */
    static long indexAt(Context c) {
        File f = new File(c.getFilesDir(), INDEX);
        return f.exists() ? f.lastModified() : 0;
    }

    /** Whether the database is to be fetched: no index, or a week old; not again within hours of a try that failed. */
    static boolean due(Context c) {
        long at = indexAt(c), now = System.currentTimeMillis();
        if (at != 0) at = Math.max(at, new File(c.getFilesDir(), CHECKED).lastModified());
        if (at != 0 && now - at <= FRESH) return false;
        File tried = new File(c.getFilesDir(), TRIED);
        return !tried.exists() || now - tried.lastModified() > RETRY;
    }

    private static void touch(File f) {
        try {
            if (!f.exists()) new FileOutputStream(f).close();
            //noinspection ResultOfMethodCallIgnored
            f.setLastModified(System.currentTimeMillis());
        } catch (IOException ignored) {
        }
    }

    /**
     * What the last look-up matched, kept so the next opening has the logos at once without reading the database:
     * each channel (its id) with what it was matched by (`signature`) and its logo ("" for none), and the index's time.
     */
    static final class Matches {
        long indexAt;
        final Map<Integer, String[]> byId = new HashMap<>();
    }

    /** A channel's name, country and guide id, as matched: a channel renamed or moved is looked up again. */
    static String signature(String name, String country, String epg) {
        return name + "	" + (country == null ? "" : country) + "	" + (epg == null ? "" : epg);
    }

    static synchronized Matches matches(Context c) {
        Matches m = new Matches();
        File f = new File(c.getFilesDir(), MATCHES);
        if (!f.exists()) return m;
        try (FileInputStream in = new FileInputStream(f)) {
            org.json.JSONObject o = new org.json.JSONObject(Xtream.read(in));
            m.indexAt = o.optLong("at");
            org.json.JSONObject all = o.optJSONObject("m");
            for (java.util.Iterator<String> it = all == null ? null : all.keys(); it != null && it.hasNext(); ) {
                String id = it.next();
                org.json.JSONArray a = all.optJSONArray(id);
                if (a != null && a.length() == 2) m.byId.put(Integer.parseInt(id), new String[] {a.optString(0), a.optString(1)});
            }
        } catch (IOException | org.json.JSONException | NumberFormatException e) {
            m.byId.clear();
        }
        return m;
    }

    static synchronized void saveMatches(Context c, long indexAt, Map<Integer, String[]> byId) {
        try {
            org.json.JSONObject all = new org.json.JSONObject();
            for (Map.Entry<Integer, String[]> e : byId.entrySet()) {
                all.put(String.valueOf(e.getKey()), new org.json.JSONArray().put(e.getValue()[0]).put(e.getValue()[1]));
            }
            Xtream.write(new File(c.getFilesDir(), MATCHES), new org.json.JSONObject().put("at", indexAt).put("m", all).toString());
        } catch (IOException | org.json.JSONException e) {
            AppLog.w("live: couldn't keep the public logos' matches");
        }
    }

    /** The database read into the index file: each channel's names (and other names) with its best logo. */
    /**
     * Whether neither of the database's files changed since the index was made: each asked "only if changed" with what
     * the server said of it then (ETag, Last-Modified); a 304 is that answer, without the file.
     */
    private static boolean unchanged(Context c) throws IOException {
        Map<String, String[]> known = validators(c);
        for (String url : new String[] {LOGOS, CHANNELS}) {
            String[] v = known.get(url);
            if (v == null || v[0].isEmpty() && v[1].isEmpty()) return false;
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(30_000);
            conn.setRequestProperty("User-Agent", Live.USER_AGENT);
            if (!v[0].isEmpty()) conn.setRequestProperty("If-None-Match", v[0]);
            if (!v[1].isEmpty()) conn.setRequestProperty("If-Modified-Since", v[1]);
            try {
                if (conn.getResponseCode() != HttpURLConnection.HTTP_NOT_MODIFIED) return false;
            } finally {
                conn.disconnect();
            }
        }
        return true;
    }

    private static Map<String, String[]> validators(Context c) {
        Map<String, String[]> out = new HashMap<>();
        File f = new File(c.getFilesDir(), VALIDATORS);
        if (!f.exists()) return out;
        try (FileInputStream in = new FileInputStream(f)) {
            org.json.JSONObject o = new org.json.JSONObject(Xtream.read(in));
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                String url = it.next();
                org.json.JSONArray a = o.optJSONArray(url);
                if (a != null && a.length() == 2) out.put(url, new String[] {a.optString(0), a.optString(1)});
            }
        } catch (IOException | org.json.JSONException ignored) {
        }
        return out;
    }

    private static void saveValidators(Context c, Map<String, String[]> v) {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            for (Map.Entry<String, String[]> e : v.entrySet()) o.put(e.getKey(), new org.json.JSONArray().put(e.getValue()[0]).put(e.getValue()[1]));
            Xtream.write(new File(c.getFilesDir(), VALIDATORS), o.toString());
        } catch (IOException | org.json.JSONException ignored) {
        }
    }

    private static void build(File out, Map<String, String[]> validators) throws IOException {
        Map<String, String> best = new HashMap<>();
        Map<String, Integer> bestScore = new HashMap<>();
        try (JsonReader r = open(LOGOS, validators)) {
            r.beginArray();
            while (r.hasNext()) {
                String channel = null, url = null, format = null, feed = null;
                boolean inUse = true;
                int width = 0;
                r.beginObject();
                while (r.hasNext()) {
                    String key = r.nextName();
                    if (r.peek() == JsonToken.NULL) {
                        r.nextNull();
                        continue;
                    }
                    switch (key) {
                        case "channel": channel = r.nextString(); break;
                        case "url": url = r.nextString(); break;
                        case "format": format = r.nextString(); break;
                        case "feed": feed = r.nextString(); break;
                        case "in_use": inUse = r.nextBoolean(); break;
                        case "width": width = r.nextInt(); break;
                        default: r.skipValue(); break;
                    }
                }
                r.endObject();
                if (channel == null || url == null || !inUse || !url.startsWith("https://")) continue;
                int score = score(format, feed == null, width);
                if (score < 0) continue;
                Integer had = bestScore.get(channel);
                if (had == null || score > had) {
                    best.put(channel, url);
                    bestScore.put(channel, score);
                }
            }
            r.endArray();
        }
        File part = new File(out.getPath() + ".part");
        int rows = 0;
        try (JsonReader r = open(CHANNELS, validators);
             BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(part), StandardCharsets.UTF_8))) {
            r.beginArray();
            while (r.hasNext()) {
                String id = null, name = null, country = "";
                List<String> names = new ArrayList<>();
                r.beginObject();
                while (r.hasNext()) {
                    String key = r.nextName();
                    if (r.peek() == JsonToken.NULL) {
                        r.nextNull();
                        continue;
                    }
                    switch (key) {
                        case "id": id = r.nextString(); break;
                        case "name": name = r.nextString(); break;
                        case "country": country = r.nextString(); break;
                        case "alt_names":
                            r.beginArray();
                            while (r.hasNext()) names.add(r.nextString());
                            r.endArray();
                            break;
                        default: r.skipValue(); break;
                    }
                }
                r.endObject();
                String url = id == null ? null : best.get(id);
                if (url == null || name == null) continue;
                names.add(0, name);
                String code = LiveCountry.code(country, false);
                w.write(ID + id.toLowerCase(java.util.Locale.ROOT) + "\t" + (code == null ? "" : code) + "\t" + url + "\n");
                for (String n : names) {
                    String key = key(n);
                    if (key.isEmpty()) continue;
                    w.write(key + "\t" + (code == null ? "" : code) + "\t" + url + "\n");
                    rows++;
                }
            }
            r.endArray();
        }
        if (rows == 0 || !part.renameTo(out) && !(out.delete() && part.renameTo(out))) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw new IOException("the logo database came empty");
        }
    }

    /** A logo's worth: one Android draws (no SVG), the channel's main one (not a feed's), the sharper the better. */
    static int score(String format, boolean mainFeed, int width) {
        if (format == null) return -1;
        int f;
        switch (format.toUpperCase(java.util.Locale.ROOT)) {
            case "PNG": f = 30; break;      // see-through, as logos look best on the guide's tiles
            case "WEBP": f = 20; break;
            case "JPEG": f = 0; break;
            default: return -1;
        }
        return (mainFeed ? 10_000 : 0) + Math.min(width, 1000) + f;
    }

    /** One of the database's files, read as it comes (compressed on the way: Android asks for gzip); what the server says of it into `validators`. */
    private static JsonReader open(String url, Map<String, String[]> validators) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(30_000);
        conn.setRequestProperty("User-Agent", Live.USER_AGENT);
        if (conn.getResponseCode() != 200) {
            conn.disconnect();
            throw new IOException("the logo database answered " + conn.getResponseCode());
        }
        String etag = conn.getHeaderField("ETag"), modified = conn.getHeaderField("Last-Modified");
        validators.put(url, new String[] {etag == null ? "" : etag, modified == null ? "" : modified});
        InputStream in = conn.getInputStream();
        return new JsonReader(new InputStreamReader(new java.io.BufferedInputStream(in, 64 * 1024), StandardCharsets.UTF_8));
    }

    // MARK: - Finding a channel's

    /** The words after a name that aren't it (a picture's, a feed's), dropped on both sides before comparing. */
    private static final java.util.Set<String> EXTRA = new java.util.HashSet<>(java.util.Arrays.asList("hd", "fhd", "uhd",
            "sd", "4k", "8k", "hevc", "h265", "h264", "1080p", "720p", "1080", "720", "raw", "hq", "backup", "low", "latency",
            "tv"));
    /** A region of a channel that has others (TLC West): the same logo as the channel itself. */
    private static final java.util.Set<String> REGION = new java.util.HashSet<>(java.util.Arrays.asList("east", "west",
            "pacific", "central", "north", "south", "1", "2"));

    /** An id's key in the index (a name's never starts with it: names are folded). */
    private static final String ID = "@";

    /** A guide's channel id as iptv-org writes its ids: "TLC.us" (a feed's "@East" off), lower case. */
    static String idKey(String epg) {
        if (epg == null) return "";
        String e = epg.trim();
        int at = e.indexOf('@');
        if (at >= 0) e = e.substring(0, at);
        return e.isEmpty() ? "" : ID + e.toLowerCase(java.util.Locale.ROOT);
    }

    /** As find(), the guide's id first: when it's iptv-org's, that channel's logo exactly. */
    static String find(Map<String, List<String[]>> byName, String name, String country, String epg) {
        String id = idKey(epg);
        if (!id.isEmpty()) {
            List<String[]> exact = byName.get(id);
            if (exact != null && !exact.isEmpty()) return exact.get(0)[1];
        }
        return find(byName, name, country);
    }

    /** A name as the index keeps it: folded, without the words that aren't the name. */
    static String key(String name) {
        String[] w = Xtream.fold(name).split(" ");
        StringBuilder b = new StringBuilder();
        for (String s : w) if (!s.isEmpty() && !EXTRA.contains(s)) b.append(b.length() == 0 ? "" : " ").append(s);
        return b.toString();
    }

    /**
     * The logo for a channel called `name` (its country tag off) in `country` ("" unknown), or null. The country's
     * own first; without one (or when it isn't listed), the logo most of the name's countries share.
     */
    static String find(Map<String, List<String[]>> byName, String name, String country) {
        String k = key(name);
        List<String[]> list = byName.get(k);
        if (list == null) {     // "TLC West": TLC's
            String[] w = k.split(" ");
            if (w.length > 1 && REGION.contains(w[w.length - 1])) list = byName.get(k.substring(0, k.lastIndexOf(' ')));
        }
        if (list == null || list.isEmpty()) return null;
        if (country != null && !country.isEmpty()) for (String[] e : list) if (e[0].equals(country)) return e[1];
        Map<String, Integer> count = new HashMap<>();
        String top = null;
        int topCount = 0;
        for (String[] e : list) {
            int n = count.merge(e[1], 1, Integer::sum);
            if (n > topCount) {
                top = e[1];
                topCount = n;
            }
        }
        return top;
    }
}
