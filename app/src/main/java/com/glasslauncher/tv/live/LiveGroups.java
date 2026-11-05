package com.glasslauncher.tv.live;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Your own groups of channels ("Football", across every country): categories made by hand, after Favorites and All
 * channels. Hold OK on a channel to put it in one. A group with no channels left is gone.
 */
final class LiveGroups {
    private LiveGroups() {}

    static synchronized List<String> names(Context c) {
        List<String> out = new ArrayList<>();
        Iterator<String> it = read(c).keys();
        while (it.hasNext()) out.add(it.next());
        java.util.Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    static synchronized boolean has(Context c, String group, int channel) {
        JSONArray ids = read(c).optJSONArray(group);
        for (int i = 0; ids != null && i < ids.length(); i++) if (ids.optInt(i) == channel) return true;
        return false;
    }

    /** Puts the channel in the group, or takes it out; true when it's in now. */
    static synchronized boolean toggle(Context c, String group, int channel) {
        JSONObject all = read(c);
        JSONArray ids = all.optJSONArray(group), out = new JSONArray();
        boolean was = false;
        for (int i = 0; ids != null && i < ids.length(); i++) {
            if (ids.optInt(i) == channel) was = true;
            else out.put(ids.optInt(i));
        }
        if (!was) out.put(channel);
        try {
            if (out.length() == 0) all.remove(group);
            else all.put(group, out);
            Xtream.write(file(c), all.toString());
        } catch (IOException | JSONException e) {
            AppLog.w("live: couldn't save the groups");
        }
        return !was;
    }

    /** The groups into the list, right after All channels. */
    static void apply(Context c, Xtream.Lineup l) {
        JSONObject all = read(c);
        int at = Math.min(2, l.categories.size());
        for (String name : names(c)) {
            JSONArray ids = all.optJSONArray(name);
            Xtream.Category cg = new Xtream.Category();
            cg.id = "group:" + name;
            cg.name = name;
            for (int i = 0; ids != null && i < ids.length(); i++) {
                Xtream.Channel ch = l.find(ids.optInt(i));
                if (ch != null) cg.channels.add(ch);
            }
            if (!cg.channels.isEmpty()) l.categories.add(at++, cg);
        }
    }

    /** Channels the guide knows nothing about out of the categories (Favorites stays as you made it). No guide yet: all stay. Pure. */
    static void onlyGuided(Xtream.Lineup l, Set<String> guided) {
        if (guided.isEmpty()) return;
        for (int i = l.categories.size() - 1; i >= 1; i--) {
            List<Xtream.Channel> list = l.categories.get(i).channels;
            for (int j = list.size() - 1; j >= 0; j--) {
                String epg = list.get(j).epg;
                if (epg == null || !guided.contains(epg.toLowerCase(java.util.Locale.ROOT))) list.remove(j);
            }
            if (i > 1 && list.isEmpty()) l.categories.remove(i);
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
        return new File(c.getApplicationContext().getFilesDir(), "live-groups.json");
    }
}
