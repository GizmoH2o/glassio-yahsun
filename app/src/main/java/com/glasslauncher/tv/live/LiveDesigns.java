package com.glasslauncher.tv.live;

import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Designs to keep and share (Live TV › Settings › Design): Custom designs kept by name ("My designs", up to [MAX]), each
 * shared by its 8-digit code and a QR code, applied from the phone page, and the community's: a list curated in the app's
 * repository (designs.json: name, code, author), read once a day while Community designs is on. Nothing is sent anywhere.
 */
public final class LiveDesigns {
    private LiveDesigns() {}

    static final int MAX = 20;
    static final String COMMUNITY_URL = "https://raw.githubusercontent.com/GizmoH2o/glassio-yahsun/main/designs.json";
    /** Sent (to Glassio only) when the phone page put a design on: Live TV reads it again. */
    public static final String THEME = "com.glasslauncher.tv.live.action.THEME";

    static final class Design {
        final String name, code, author;

        Design(String name, String code, String author) {
            this.name = name;
            this.code = code;
            this.author = author;
        }
    }

    // MARK: - Pure

    private static String tidyName(String name) {
        String n = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        if (n.isEmpty()) n = "My design";
        return n.length() > 40 ? n.substring(0, 40) : n;
    }

    static List<Design> parse(String json) {
        List<Design> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && LiveTheme.validCode(o.optString("code"))) out.add(new Design(tidyName(o.optString("name")), o.optString("code"), ""));
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    private static String write(List<Design> list) {
        JSONArray a = new JSONArray();
        try {
            for (Design d : list) a.put(new JSONObject().put("name", d.name).put("code", d.code));
        } catch (JSONException ignored) {
        }
        return a.toString();
    }

    /** The list with `code` added as `name`; null when it's full or the code isn't a design. */
    static String add(String json, String name, String code) {
        if (!LiveTheme.validCode(code)) return null;
        List<Design> list = parse(json);
        if (list.size() >= MAX) return null;
        list.add(new Design(tidyName(name), code, ""));
        return write(list);
    }

    static String rename(String json, int i, String name) {
        List<Design> list = parse(json);
        if (i >= 0 && i < list.size()) list.set(i, new Design(tidyName(name), list.get(i).code, ""));
        return write(list);
    }

    static String remove(String json, int i) {
        List<Design> list = parse(json);
        if (i >= 0 && i < list.size()) list.remove(i);
        return write(list);
    }

    /** The community's designs from designs.json: those with a name and a good code, each name once. */
    static List<Design> community(String json) {
        List<Design> out = new ArrayList<>();
        Set<String> names = new HashSet<>();
        try {
            JSONArray a = new JSONObject(json).optJSONArray("designs");
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                String name = o.optString("name").trim(), code = o.optString("code").trim();
                if (name.isEmpty() || !LiveTheme.validCode(code) || !names.add(name.toLowerCase(Locale.ROOT))) continue;
                out.add(new Design(name.length() > 40 ? name.substring(0, 40) : name, code, o.optString("author").trim()));
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    // MARK: - Kept on the TV

    private static File file(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), "live-designs.json");
    }

    private static String read(File f) {
        if (!f.exists()) return "[]";
        try (FileInputStream in = new FileInputStream(f)) {
            return Xtream.read(in);
        } catch (IOException e) {
            return "[]";
        }
    }

    static synchronized List<Design> mine(Context c) {
        return parse(read(file(c)));
    }

    private static void save(Context c, String json) {
        try {
            Xtream.write(file(c), json);
        } catch (IOException e) {
            AppLog.w("live: couldn't keep the designs");
        }
    }

    /** False when there are [MAX] already. */
    static synchronized boolean keep(Context c, String name, String code) {
        String j = add(read(file(c)), name, code);
        if (j == null) return false;
        save(c, j);
        return true;
    }

    static synchronized void rename(Context c, int i, String name) {
        save(c, rename(read(file(c)), i, name));
    }

    static synchronized void remove(Context c, int i) {
        save(c, remove(read(file(c)), i));
    }

    // MARK: - The community's

    /** The test hook's: the community list from this file instead (the app's external files). */
    static volatile String testFile;

    /** The community's designs (read again once a day; the last ones kept). Blocking. */
    static List<Design> community(Context c) {
        if (testFile != null) return community(read(new File(testFile)));
        File cache = new File(c.getApplicationContext().getCacheDir(), "live-community-designs.json");
        if (!cache.exists() || System.currentTimeMillis() - cache.lastModified() > 24 * 3600_000L) {
            try {
                HttpURLConnection h = (HttpURLConnection) new URL(COMMUNITY_URL).openConnection();
                h.setConnectTimeout(8000);
                h.setReadTimeout(10000);
                h.setRequestProperty("User-Agent", "GlassioYahsun");
                try {
                    if (h.getResponseCode() == 200) Xtream.write(cache, Xtream.read(h.getInputStream()));
                } finally {
                    h.disconnect();
                }
            } catch (IOException e) {
                AppLog.w("live: the community designs couldn't be read: " + e.getMessage());
            }
        }
        return community(read(cache));
    }

    // MARK: - From the phone page (Home's process)

    /** A design code put on: the theme read first (its other choices kept), Custom from the code, Live TV told. */
    public static String apply(Context c, String code) {
        String k = code == null ? "" : code.replaceAll("\\D", "");
        if (!LiveTheme.validCode(k)) throw new IllegalArgumentException("That isn't a design code (8 digits)");
        synchronized (LiveTheme.class) {
            LiveTheme.load(c);
            LiveTheme.useCode(c, k);
        }
        c.sendBroadcast(new Intent(THEME).setPackage(c.getPackageName()));
        return "Design " + k + " is on: Live TV shows it";
    }

    /** The small page a shared QR code opens: the design's name, its colors and its code. */
    public static String page(String code, String name) {
        String k = code == null ? "" : code.replaceAll("\\D", "");
        if (!LiveTheme.validCode(k)) return "<!doctype html><meta charset=utf-8><p>Not a design code.";
        int[] s = LiveTheme.swatch(k);
        StringBuilder sw = new StringBuilder();
        String[] what = {"Background", "Panels", "Guide", "Focus", "On the focus", "Highlights"};
        for (int i = 0; i < s.length && i < what.length; i++) {
            sw.append("<div class=s><i style=\"background:#").append(String.format(Locale.ROOT, "%06X", s[i] & 0xFFFFFF))
                    .append("\"></i>").append(what[i]).append("</div>");
        }
        String n = name == null || name.trim().isEmpty() ? "A Glassio design" : name.trim();
        n = n.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        if (n.length() > 60) n = n.substring(0, 60);
        return "<!doctype html><html><head><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\"><title>" + n
                + "</title><style>body{margin:0;background:#0b0b0d;color:#fff;font:16px/1.5 -apple-system,Roboto,sans-serif}main{max-width:520px;margin:0 auto;padding:24px 16px}"
                + "h1{font-size:24px;margin:0 0 4px}.c{font:700 34px monospace;letter-spacing:.12em;margin:18px 0}.s{display:flex;align-items:center;gap:10px;margin:6px 0;color:#b3b3b8}"
                + ".s i{width:44px;height:28px;border-radius:8px;border:1px solid #333;display:inline-block}p{color:#b3b3b8}</style></head><body><main><h1>" + n
                + "</h1><p>A Live TV design for Glassio Yahsun.</p>" + sw + "<div class=c>" + k.substring(0, 4) + " " + k.substring(4)
                + "</div><p>On your TV: Live TV › Settings › Design › Design code, and type these 8 digits. Or paste them in Apply a design on your TV's phone page.</p></main></body></html>";
    }
}
