package com.glasslauncher.tv.live;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Who's watching (Sky's, YouTube TV's profiles): each person's favorites, History and Continue watching. The active
 * profile's files are where Live TV (and the home screen's rows) read them; the others wait in files/profiles/<id>,
 * moved in and out on a switch, as LivePlaylists does with accounts. A Kids profile sees only children's channels
 * (kidsOnly), and leaving it asks for the parental PIN.
 */
final class LiveProfiles {
    private LiveProfiles() {}

    static final class Profile {
        String id, name;
        boolean kids;
        int color;
    }

    /** What's each profile's own. */
    private static final String[] FILES = {"live-favorites.json", "live-recent.json", "live-resume.json", "live-theme.txt"};
    /** Files a profile that has none of its own starts with a copy of (the style on screen), not without. */
    private static final java.util.Set<String> INHERIT = new java.util.HashSet<>(java.util.Arrays.asList("live-theme.txt"));
    /** The circles' colors, one per profile in turn. */
    static final int[] COLORS = {0xFFE5322D, 0xFF2F6FE4, 0xFF30B566, 0xFFF2A100, 0xFF9B5DE5, 0xFF00B4D8, 0xFFEF476F};
    /** What makes a channel (by its name or its group's) a children's one. */
    static final String[] KIDS_WORDS = {"kids", "kid", "cartoon", "nick", "disney", "boomerang", "cbbc", "cbeebies",
            "baby", "babyfirst", "babytv", "junior", "pbs kids", "nickelodeon", "nicktoons", "toon", "discovery family", "tiny pop", "jr"};
    private static final String MAIN = "main";

    static synchronized List<Profile> all(Context c) {
        List<Profile> out = new ArrayList<>();
        JSONArray a = registry(c).optJSONArray("list");
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Profile p = new Profile();
            p.id = o.optString("id");
            p.name = o.optString("name");
            p.kids = o.optBoolean("kids");
            p.color = o.optInt("color", COLORS[i % COLORS.length]);
            out.add(p);
        }
        if (out.isEmpty()) {        // before any was made: one, everyone's
            Profile p = new Profile();
            p.id = MAIN;
            p.name = Ui.tr("Principal", "Main");
            p.color = COLORS[0];
            out.add(p);
        }
        return out;
    }

    static synchronized Profile active(Context c) {
        String id = registry(c).optString("active", MAIN);
        List<Profile> list = all(c);
        for (Profile p : list) if (p.id.equals(id)) return p;
        return list.get(0);
    }

    /** Whether the one watching is a Kids profile. */
    static boolean kids(Context c) {
        return active(c).kids;
    }

    static synchronized Profile add(Context c, String name, boolean kids) {
        List<Profile> list = all(c);
        Profile p = new Profile();
        p.id = "p" + System.currentTimeMillis();
        p.name = name;
        p.kids = kids;
        p.color = COLORS[list.size() % COLORS.length];
        list.add(p);
        save(c, list, active(c).id);
        return p;
    }

    static synchronized void rename(Context c, String id, String name) {
        List<Profile> list = all(c);
        for (Profile p : list) if (p.id.equals(id)) p.name = name;
        save(c, list, active(c).id);
    }

    /** Takes a profile out (never the one watching): its favorites and History go with it. */
    static synchronized void remove(Context c, String id) {
        if (id.equals(active(c).id)) return;
        List<Profile> list = all(c);
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).id.equals(id)) list.remove(i);
        save(c, list, active(c).id);
        File dir = dir(c, id);
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
        dir.delete();
    }

    /** The profile watching becomes `id`: the current one's files go to its folder, `id`'s come out of its. */
    static synchronized void switchTo(Context c, String id) {
        Profile from = active(c);
        if (from.id.equals(id)) return;
        File out = dir(c, from.id), in = dir(c, id);
        out.mkdirs();
        for (String name : FILES) {
            File here = new File(c.getFilesDir(), name), kept = new File(out, name);
            kept.delete();
            if (here.exists() && !here.renameTo(kept)) here.delete();
            File back = new File(in, name);
            if (back.exists()) {
                back.renameTo(here);
            } else if (INHERIT.contains(name) && kept.exists()) {
                try (java.io.FileInputStream copy = new java.io.FileInputStream(kept)) {
                    Xtream.write(here, Xtream.read(copy));
                } catch (java.io.IOException ignored) {
                }
            }
        }
        save(c, all(c), id);
        AppLog.w("live: profile " + (active(c).kids ? "(kids)" : "") + " now on");
    }

    /**
     * The list as a Kids profile sees it: its favorites, then the children's channels (by their names or their
     * groups') in "All channels" and in each group that has any.
     */
    static Xtream.Lineup kidsOnly(Xtream.Lineup l) {
        Xtream.Lineup out = new Xtream.Lineup();
        out.at = l.at;
        out.version = l.version;
        out.format = l.format;
        for (int i = 0; i < l.categories.size(); i++) {
            Xtream.Category cg = l.categories.get(i);
            boolean groupIsKids = isKids(cg.name);
            Xtream.Category keep = new Xtream.Category();
            keep.id = cg.id;
            keep.name = cg.name;
            for (Xtream.Channel ch : cg.channels) {
                if (i == 0 || groupIsKids || isKids(ch.name) || isKids(groupName(l, ch))) keep.channels.add(ch);
            }
            if (i <= 1 || !keep.channels.isEmpty()) out.categories.add(keep);      // Favorites and All always
        }
        java.util.Set<Xtream.Channel> kept = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        kept.addAll(out.all());
        kept.addAll(out.categories.get(0).channels);
        for (java.util.Map.Entry<Integer, Xtream.Channel> e : l.byId.entrySet()) {     // with a merged channel's sources
            if (kept.contains(e.getValue())) out.byId.put(e.getKey(), e.getValue());
        }
        return out;
    }

    private static String groupName(Xtream.Lineup l, Xtream.Channel ch) {
        for (Xtream.Category cg : l.categories) if (cg.id != null && cg.id.equals(ch.category)) return cg.name;
        return "";
    }

    static boolean isKids(String name) {
        String n = " " + Xtream.fold(name) + " ";
        // Short words only as words ("jr" not inside another one), the longer ones anywhere ("cartoon" in "cartoonito").
        for (String w : KIDS_WORDS) if (w.length() <= 4 ? n.contains(" " + w + " ") : n.contains(w)) return true;
        return false;
    }

    private static File dir(Context c, String id) {
        return new File(new File(c.getFilesDir(), "profiles"), id);
    }

    private static JSONObject registry(Context c) {
        File f = new File(c.getFilesDir(), "live-profiles.json");
        if (!f.exists()) return new JSONObject();
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            return new JSONObject(Xtream.read(in));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static void save(Context c, List<Profile> list, String active) {
        try {
            JSONArray a = new JSONArray();
            for (Profile p : list) a.put(new JSONObject().put("id", p.id).put("name", p.name).put("kids", p.kids).put("color", p.color));
            Xtream.write(new File(c.getFilesDir(), "live-profiles.json"), new JSONObject().put("list", a).put("active", active).toString());
        } catch (JSONException | java.io.IOException ignored) {
        }
    }
}
