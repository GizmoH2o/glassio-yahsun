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
            }
        }
    }

    static boolean edited(Context c, int id) {
        return read(c).has(String.valueOf(id));
    }

    static synchronized void setName(Context c, int id, String name) {
        edit(c, id, "name", name);
    }

    static synchronized void setNumber(Context c, int id, int num) {
        edit(c, id, "num", num);
    }

    /** The provider's name and number again. */
    static synchronized void clear(Context c, int id) {
        JSONObject all = read(c);
        all.remove(String.valueOf(id));
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
