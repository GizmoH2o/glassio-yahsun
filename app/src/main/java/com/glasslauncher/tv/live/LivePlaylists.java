package com.glasslauncher.tv.live;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Several Xtream accounts. One is in use: its files are where the rest of Live TV reads them; the others' are put
 * aside in files/playlists/<id>/ (their channels, favorites, History, reminders, series, what each source turned out
 * to be), their guide (cache and full guide) and their own settings (hidden, locked and separate categories, the
 * full guide's link) with them. Switching puts the one in use aside and brings the other back. The rest (playback,
 * sorting, the theme, the remote's buttons, the PIN, recordings) stays the same for all.
 */
final class LivePlaylists {
    private LivePlaylists() {}

    static final class Playlist {
        String id, server = "", user = "";

        /** The server's host and the user (what the list shows). */
        String name() {
            String host = server.replaceFirst("^https?://", "").replaceFirst("[:/].*$", "");
            return user + "  ·  " + host;
        }
    }

    /** Each account's own files (in the app's files). */
    private static final String[] FILES = {"live-login.json", "live-channels.json", "live-favorites.json", "live-recent.json",
            "live-sources.json", "live-preferred.json", "live-speed.txt", "live-schedule.json", "live-series.json", "live-watch-next.json",
            "live-resume.json", "live-account.json", "live-guide-shift.txt"};
    private static final String[] OPTION_SETS = {"hidden", "locked", "separate"};
    private static final String GUIDE_DB = "live-guide.db";

    // MARK: - The list

    static synchronized List<Playlist> all(Context c) {
        List<Playlist> out = new ArrayList<>();
        JSONObject o = registry(c);
        JSONArray a = o.optJSONArray("list");
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject p = a.optJSONObject(i);
            if (p == null) continue;
            Playlist pl = new Playlist();
            pl.id = p.optString("id");
            pl.server = p.optString("server");
            pl.user = p.optString("user");
            out.add(pl);
        }
        // The account signed in before there were playlists: the first one.
        if (out.isEmpty() && Xtream.signedIn(c)) {
            Playlist pl = current(c, "p1");
            out.add(pl);
            save(c, out, pl.id, null);
        }
        return out;
    }

    /** The one in use (null while adding one, on the sign-in screen). */
    static synchronized String active(Context c) {
        all(c);
        String a = registry(c).optString("active");
        return a.isEmpty() ? null : a;
    }

    /** While adding one: the one to go back to if the sign-in is left (null otherwise). */
    static synchronized String returnTo(Context c) {
        String r = registry(c).optString("return");
        return r.isEmpty() ? null : r;
    }

    // MARK: - Switching

    /** `id` in use instead of the one now. Live TV loads its channels again after. */
    static synchronized void switchTo(Context c, String id) {
        List<Playlist> list = all(c);
        String now = active(c);
        if (id.equals(now)) return;
        if (now != null) stash(c, now);
        unstash(c, id);
        save(c, list, id, null);
    }

    /** The one in use put aside, for the sign-in screen (a new account); Back there brings it back. */
    static synchronized void startAdding(Context c) {
        List<Playlist> list = all(c);
        String now = active(c);
        if (now != null) stash(c, now);
        save(c, list, null, now);
    }

    /** The sign-in left without a new account: the one before, back. False when there was none. */
    static synchronized boolean cancelAdding(Context c) {
        String back = returnTo(c);
        if (back == null || active(c) != null) return false;
        unstash(c, back);
        save(c, all(c), back, null);
        return true;
    }

    /** Signed in (the sign-in screen): that account is in use, a new playlist or the one that was. */
    static synchronized void signedIn(Context c) {
        List<Playlist> list = all(c);
        String now = active(c);
        Playlist me = current(c, now);
        if (now == null) {      // a new one (or the first ever)
            Set<String> ids = new HashSet<>();
            for (Playlist p : list) ids.add(p.id);
            int n = 1;
            while (ids.contains("p" + n)) n++;
            me.id = "p" + n;
            list.add(me);
        } else {
            for (int i = 0; i < list.size(); i++) if (list.get(i).id.equals(now)) list.set(i, me);
        }
        save(c, list, me.id, null);
    }

    /**
     * The one in use removed (its files went with signing out): another one in use if there is one, its id (null:
     * none left, the sign-in screen).
     */
    static synchronized String removeActive(Context c) {
        List<Playlist> list = all(c);
        String now = active(c);
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).id.equals(now)) list.remove(i);
        if (list.isEmpty()) {
            save(c, list, null, null);
            return null;
        }
        String next = list.get(0).id;
        unstash(c, next);
        save(c, list, next, null);
        return next;
    }

    /** One put aside, removed with everything it kept. */
    static synchronized void remove(Context c, String id) {
        List<Playlist> list = all(c);
        if (id.equals(active(c))) return;
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).id.equals(id)) list.remove(i);
        File dir = dir(c, id);
        File[] files = dir.listFiles();
        //noinspection ResultOfMethodCallIgnored
        if (files != null) for (File f : files) f.delete();
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
        deleteTree(new File(c.getCacheDir(), "live-epg-" + id));
        //noinspection ResultOfMethodCallIgnored
        c.getDatabasePath("live-guide-" + id + ".db").delete();
        save(c, list, active(c), returnTo(c));
    }

    // MARK: - Putting aside and back

    private static void stash(Context c, String id) {
        LiveSchedule.disarmAll(c);      // its reminders' alarms (they come back with it)
        XmltvStore.release();
        File dir = dir(c, id);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        for (String name : FILES) move(new File(c.getFilesDir(), name), new File(dir, name));
        move(new File(c.getCacheDir(), "live-epg"), new File(c.getCacheDir(), "live-epg-" + id));
        move(c.getDatabasePath(GUIDE_DB), c.getDatabasePath("live-guide-" + id + ".db"));
        //noinspection ResultOfMethodCallIgnored
        new File(c.getDatabasePath(GUIDE_DB).getPath() + "-journal").delete();
        // Its own settings, and where it was (the last channel and category).
        SharedPreferences opt = options(c), ui = ui(c);
        JSONObject s = new JSONObject();
        try {
            for (String k : OPTION_SETS) s.put(k, new JSONArray(opt.getStringSet(k, new HashSet<>())));
            s.put("guideUrl", opt.getString("guideUrl", ""));
            s.put("fullGuide", opt.getBoolean("fullGuide", false));
            s.put("channel", ui.getInt("channel", -1));
            s.put("category", ui.getString("category", ""));
            Xtream.write(new File(dir, "settings.json"), s.toString());
        } catch (IOException | JSONException e) {
            AppLog.w("live: couldn't put a playlist's settings aside");
        }
        SharedPreferences.Editor e = opt.edit();
        for (String k : OPTION_SETS) e.remove(k);
        e.remove("guideUrl").remove("fullGuide").commit();
        ui.edit().clear().commit();
        LiveMerge.forget();
    }

    private static void unstash(Context c, String id) {
        File dir = dir(c, id);
        for (String name : FILES) move(new File(dir, name), new File(c.getFilesDir(), name));
        move(new File(c.getCacheDir(), "live-epg-" + id), new File(c.getCacheDir(), "live-epg"));
        move(c.getDatabasePath("live-guide-" + id + ".db"), c.getDatabasePath(GUIDE_DB));
        File sf = new File(dir, "settings.json");
        if (sf.exists()) {
            try (FileInputStream in = new FileInputStream(sf)) {
                JSONObject s = new JSONObject(Xtream.read(in));
                SharedPreferences.Editor e = options(c).edit();
                for (String k : OPTION_SETS) {
                    JSONArray a = s.optJSONArray(k);
                    Set<String> set = new HashSet<>();
                    for (int i = 0; a != null && i < a.length(); i++) set.add(a.optString(i));
                    e.putStringSet(k, set);
                }
                e.putString("guideUrl", s.optString("guideUrl")).putBoolean("fullGuide", s.optBoolean("fullGuide")).commit();
                SharedPreferences.Editor u = ui(c).edit();
                if (s.optInt("channel", -1) >= 0) u.putInt("channel", s.optInt("channel"));
                u.putString("category", s.optString("category")).commit();
            } catch (IOException | JSONException e) {
                AppLog.w("live: a playlist's settings unreadable");
            }
            //noinspection ResultOfMethodCallIgnored
            sf.delete();
        }
        LiveMerge.forget();
        LiveSchedule.armAll(c);
        LiveSeries.arm(c);
    }

    // MARK: - Files

    private static Playlist current(Context c, String id) {
        Playlist p = new Playlist();
        p.id = id;
        p.server = Xtream.server(c);
        p.user = Xtream.user(c);
        return p;
    }

    private static void move(File from, File to) {
        if (!from.exists()) return;
        if (to.exists()) deleteTree(to);
        //noinspection ResultOfMethodCallIgnored
        to.getParentFile().mkdirs();
        if (!from.renameTo(to)) AppLog.w("live: couldn't move " + from.getName());
    }

    private static void deleteTree(File f) {
        File[] in = f.listFiles();
        if (in != null) for (File x : in) deleteTree(x);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static File dir(Context c, String id) {
        return new File(new File(c.getFilesDir(), "playlists"), id);
    }

    private static SharedPreferences options(Context c) {
        return c.getApplicationContext().getSharedPreferences("live-options", Context.MODE_PRIVATE);
    }

    private static SharedPreferences ui(Context c) {
        return c.getApplicationContext().getSharedPreferences("live-ui", Context.MODE_PRIVATE);
    }

    private static JSONObject registry(Context c) {
        File f = new File(c.getFilesDir(), "live-playlists.json");
        if (!f.exists()) return new JSONObject();
        try (FileInputStream in = new FileInputStream(f)) {
            return new JSONObject(Xtream.read(in));
        } catch (IOException | JSONException e) {
            return new JSONObject();
        }
    }

    private static void save(Context c, List<Playlist> list, String active, String returnTo) {
        JSONArray a = new JSONArray();
        try {
            for (Playlist p : list) a.put(new JSONObject().put("id", p.id).put("server", p.server).put("user", p.user));
            Xtream.write(new File(c.getFilesDir(), "live-playlists.json"), new JSONObject().put("list", a)
                    .put("active", active == null ? "" : active).put("return", returnTo == null ? "" : returnTo).toString());
        } catch (IOException | JSONException e) {
            AppLog.w("live: couldn't save the playlists");
        }
    }
}
