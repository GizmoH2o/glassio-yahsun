package com.glasslauncher.tv.live;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Which logo each channel shows, beyond the setting (Live TV › Settings › Channel logos): a channel's own pick (hold
 * OK on it › Logo: its provider's, the guide's, the public database's), and the logos Live TV ended up with, kept
 * for the home screen's process (its Live TV cards show the same ones).
 */
final class LiveLogoChoice {
    private LiveLogoChoice() {}

    private static final String PICKS = "live-logo-picks.json", SHOWN = "live-logo-shown.json";

    // MARK: - A channel's own pick

    /** Channel `id`'s pick (LiveOptions.LOGOS_*), -1: the setting's. */
    static synchronized int pick(Context c, int id) {
        Integer v = picks(c).get(id);
        return v == null ? -1 : v;
    }

    /** Every channel's pick, by id (read once, kept as it changes). */
    static synchronized Map<Integer, Integer> picks(Context c) {
        if (picks == null) {
            picks = new HashMap<>();
            JSONObject o = read(new File(c.getFilesDir(), PICKS));
            if (o != null) {
                Iterator<String> keys = o.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    int v = o.optInt(k, -1);
                    try {
                        if (v >= LiveOptions.LOGOS_PROVIDER && v <= LiveOptions.LOGOS_PUBLIC) picks.put(Integer.parseInt(k), v);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return picks;
    }

    private static Map<Integer, Integer> picks;

    /** Channel `id` shows `source`'s logo from now on (-1: as the setting says). */
    static synchronized void setPick(Context c, int id, int source) {
        Map<Integer, Integer> p = picks(c);
        if (source < 0) p.remove(id); else p.put(id, source);
        JSONObject o = new JSONObject();
        try {
            for (Map.Entry<Integer, Integer> e : p.entrySet()) o.put(String.valueOf(e.getKey()), (int) e.getValue());
            Xtream.write(new File(c.getFilesDir(), PICKS), o.toString());
        } catch (JSONException | IOException e) {
            AppLog.w("live: couldn't keep the channel's logo");
        }
    }

    // MARK: - What Live TV shows, for the home screen

    /** Live TV's side: each channel's logo where it isn't the provider's (background thread). */
    static void saveShown(Context c, Map<Integer, String> shown) {
        JSONObject o = new JSONObject();
        try {
            for (Map.Entry<Integer, String> e : shown.entrySet()) o.put(String.valueOf(e.getKey()), e.getValue());
            Xtream.write(new File(c.getFilesDir(), SHOWN), o.toString());
        } catch (JSONException | IOException e) {
            AppLog.w("live: couldn't keep the logos shown");
        }
    }

    private static Map<Integer, String> shown;
    private static long shownAt = -1;

    /** The home screen's side: channel `id`'s logo as Live TV shows it, or null (the provider's). */
    static synchronized String shown(Context c, int id) {
        File f = new File(c.getFilesDir(), SHOWN);
        long at = f.exists() ? f.lastModified() : 0;
        if (shown == null || at != shownAt) {
            shown = new HashMap<>();
            JSONObject o = at == 0 ? null : read(f);
            if (o != null) {
                Iterator<String> keys = o.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    try {
                        shown.put(Integer.parseInt(k), o.optString(k));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            shownAt = at;
        }
        String v = shown.get(id);
        return v == null || v.isEmpty() ? null : v;
    }

    private static JSONObject read(File f) {
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            return new JSONObject(Xtream.read(in));
        } catch (IOException | JSONException e) {
            return null;
        }
    }
}
