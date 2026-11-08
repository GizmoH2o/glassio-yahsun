package com.glasslauncher.tv.live;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * Channels renamed and renumbered by hand (TiviMate's, Sky's numbers): by the provider's channel id, in a small file,
 * put on the list each time it loads (a refresh keeps them). Taking an edit out brings the provider's name or
 * number back with the next load.
 */
final class LiveChannelEdits {
    private LiveChannelEdits() {}

    /** The edits on every channel of the list (each category's copy), in place. */
    static void apply(Context c, Xtream.Lineup l) {
        JSONObject all = read(c);
        if (all.length() == 0) return;
        for (Xtream.Category cg : l.categories) {
            for (Xtream.Channel ch : cg.channels) {
                JSONObject e = all.optJSONObject(String.valueOf(ch.id));
                if (e == null) continue;
                String name = e.optString("name", "");
                if (!name.isEmpty()) {
                    ch.name = name;
                    ch.key = Xtream.fold(name);
                }
                int num = e.optInt("num", 0);
                if (num > 0) ch.num = num;
                String epg = e.optString("epg", "");
                if (!epg.isEmpty()) ch.epg = epg;      // the guide chosen by hand: before (and over) matching by name
            }
        }
    }

    /** Whether its name or number was changed by hand. */
    static boolean edited(Context c, int id) {
        JSONObject e = read(c).optJSONObject(String.valueOf(id));
        return e != null && (e.has("name") || e.has("num"));
    }

    /** The guide ID chosen by hand ("" none: the playlist's, or found by name). */
    static String guide(Context c, int id) {
        JSONObject e = read(c).optJSONObject(String.valueOf(id));
        return e == null ? "" : e.optString("epg", "");
    }

    static synchronized void setGuide(Context c, int id, String epg) {
        edit(c, id, "epg", epg.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /** The channels whose guide was chosen by hand (matching by name leaves them be). */
    static java.util.Set<Integer> guided(Context c) {
        java.util.Set<Integer> out = new java.util.HashSet<>();
        JSONObject all = read(c);
        for (java.util.Iterator<String> it = all.keys(); it.hasNext(); ) {
            String k = it.next();
            JSONObject e = all.optJSONObject(k);
            if (e == null || e.optString("epg", "").isEmpty()) continue;
            try {
                out.add(Integer.parseInt(k));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    /** The guide found again on its own (the playlist's, or by name). */
    static synchronized void clearGuide(Context c, int id) {
        remove(c, id, "epg");
    }

    /** Every guide chosen by hand, gone (Settings › Guides chosen by hand). */
    static synchronized void clearGuides(Context c) {
        for (int id : guided(c)) remove(c, id, "epg");
    }

    static synchronized void setName(Context c, int id, String name) {
        edit(c, id, "name", name);
    }

    static synchronized void setNumber(Context c, int id, int num) {
        edit(c, id, "num", num);
    }

    /** The provider's name and number again (a guide chosen by hand stays). */
    static synchronized void clear(Context c, int id) {
        remove(c, id, "name");
        remove(c, id, "num");
    }

    private static void remove(Context c, int id, String field) {
        JSONObject all = read(c);
        JSONObject e = all.optJSONObject(String.valueOf(id));
        if (e == null) return;
        e.remove(field);
        if (e.length() == 0) all.remove(String.valueOf(id));
        save(c, all);
    }

    private static void edit(Context c, int id, String field, Object value) {
        JSONObject all = read(c);
        try {
            JSONObject e = all.optJSONObject(String.valueOf(id));
            if (e == null) e = new JSONObject();
            e.put(field, value);
            all.put(String.valueOf(id), e);
        } catch (JSONException ignored) {
        }
        save(c, all);
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

    private static void save(Context c, JSONObject all) {
        try {
            Xtream.write(file(c), all.toString());
        } catch (IOException e) {
            AppLog.w("live: couldn't save a channel's name or number");
        }
    }

    private static File file(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), "live-channel-edits.json");
    }
}
