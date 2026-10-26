package com.glasslauncher.tv.live;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The favorites as a file in the TV's Downloads ("Glassio Yahsun Live favorites.json"), to bring back after signing out, on
 * another provider (matched by name: providers number their channels differently) or after a reset of Live TV.
 * Through MediaStore (Android 10+), so no storage permission: Android lets an app read back only the files it wrote
 * itself, which is the case unless Glassio was uninstalled in between.
 */
final class LiveBackup {
    private LiveBackup() {}

    static final String NAME = "Glassio Yahsun Live favorites.json";

    static boolean available() {
        return Build.VERSION.SDK_INT >= 29;
    }

    /** Writes the favorites (replacing the last export). Background thread. */
    static void export(Context c, List<Xtream.Channel> favorites) throws IOException {
        if (!available()) throw new IOException("Android 10 or newer");
        JSONArray a = new JSONArray();
        try {
            for (Xtream.Channel ch : favorites) {
                a.put(new JSONObject().put("id", ch.id).put("num", ch.num).put("name", ch.name).put("epg", ch.epg == null ? "" : ch.epg));
            }
        } catch (JSONException e) {
            throw new IOException(e);
        }
        ContentResolver r = c.getContentResolver();
        Uri old = find(c);
        if (old != null) r.delete(old, null, null);
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, NAME);
        v.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/");
        Uri uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("no Downloads");
        try (OutputStream out = r.openOutputStream(uri)) {
            if (out == null) throw new IOException("can't write");
            out.write(new JSONObject().put("favorites", a).put("at", System.currentTimeMillis()).toString().getBytes(StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    /**
     * The exported favorites found in `all` (the lineup's channels): by number and name, else by name alone, else by
     * the guide's id. Null when there's no export to read. Background thread.
     */
    static List<Xtream.Channel> read(Context c, List<Xtream.Channel> all) throws IOException {
        Uri uri = find(c);
        if (uri == null) return null;
        JSONArray a;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            a = new JSONObject(Xtream.read(in)).optJSONArray("favorites");
        } catch (JSONException e) {
            throw new IOException(e);
        }
        List<Xtream.Channel> out = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Xtream.Channel ch = match(all, o.optInt("id"), o.optInt("num"), Xtream.fold(o.optString("name")), o.optString("epg"));
            if (ch != null && seen.add(ch.id)) out.add(ch);
        }
        return out;
    }

    private static Xtream.Channel match(List<Xtream.Channel> all, int id, int num, String name, String epg) {
        Xtream.Channel byName = null, byEpg = null;
        for (Xtream.Channel ch : all) {
            boolean same = Xtream.fold(ch.name).equals(name);
            if (same && (ch.id == id || ch.num == num)) return ch;     // the same provider
            if (same && byName == null) byName = ch;
            if (!epg.isEmpty() && epg.equalsIgnoreCase(ch.epg) && byEpg == null) byEpg = ch;
        }
        return byName != null ? byName : byEpg;
    }

    // MARK: - Everything (Glassio Live backup.json)

    static final String FULL = "Glassio Yahsun Live backup.json";
    /** The settings kept in SharedPreferences: Live TV's options, its look, the remote's buttons. */
    private static final String[] PREFS = {"live-options", "live-ui", "live-keys"};
    /** Never in the file (it's in Downloads, where other apps could read it): the parental PIN. */
    private static final Set<String> SECRET = new HashSet<>(java.util.Arrays.asList("pin", "salt"));
    /** The small files: History, reminders and recordings set for later, series, look, profiles, the start flags. */
    private static final String[] FILES = {"live-recent.json", "live-schedule.json", "live-series.json", "live-theme.txt",
            "live-remind.txt", "live-resume.json", "live-profiles.json", "live-follow.json", "live-channel-edits.json", "live-start-boot",
            "live-start-wake", "live-no-previews"};

    /**
     * Writes everything but the provider's login and the PIN (replacing the last backup): the favorites (by name, as
     * export does), every setting, the files above, the other profiles' lists. Background thread.
     */
    static void exportAll(Context c, List<Xtream.Channel> favorites) throws IOException {
        if (!available()) throw new IOException("Android 10 or newer");
        try {
            JSONArray favs = new JSONArray();
            for (Xtream.Channel ch : favorites) {
                favs.put(new JSONObject().put("id", ch.id).put("num", ch.num).put("name", ch.name).put("epg", ch.epg == null ? "" : ch.epg));
            }
            JSONObject prefs = new JSONObject();
            for (String name : PREFS) {
                JSONObject values = new JSONObject();
                for (java.util.Map.Entry<String, ?> e : c.getSharedPreferences(name, Context.MODE_PRIVATE).getAll().entrySet()) {
                    if (SECRET.contains(e.getKey()) || e.getValue() == null) continue;
                    Object v = e.getValue();
                    // A guide's address with the login in it (many providers' are) stays out too.
                    if (v instanceof String && ((String) v).toLowerCase(java.util.Locale.ROOT).matches(".*(password|passwd|pass|username|user)=.*")) continue;
                    String type = v instanceof Boolean ? "b" : v instanceof Integer ? "i" : v instanceof Long ? "l" : v instanceof Float ? "f"
                            : v instanceof Set ? "s" : "t";
                    values.put(e.getKey(), new JSONObject().put("t", type).put("v", v instanceof Set ? new JSONArray((Set<?>) v) : v));
                }
                prefs.put(name, values);
            }
            JSONObject files = new JSONObject();
            for (String name : FILES) {
                java.io.File f = new java.io.File(c.getFilesDir(), name);
                files.put(name, f.exists() ? text(f) : JSONObject.NULL);
            }
            JSONObject profiles = new JSONObject();
            java.io.File[] dirs = new java.io.File(c.getFilesDir(), "profiles").listFiles();
            for (int i = 0; dirs != null && i < dirs.length; i++) {
                java.io.File[] kept = dirs[i].listFiles();
                if (kept == null) continue;
                JSONObject one = new JSONObject();
                for (java.io.File f : kept) one.put(f.getName(), text(f));
                profiles.put(dirs[i].getName(), one);
            }
            write(c, FULL, new JSONObject().put("version", 1).put("at", System.currentTimeMillis()).put("favorites", favs)
                    .put("prefs", prefs).put("files", files).put("profiles", profiles).toString());
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    /**
     * Brings the backup back: the settings (the PIN as it is now), the files, the other profiles' lists, and the
     * favorites found in `all` (by name, as read does). False when there's no backup. Background thread; the caller
     * starts Live TV again after.
     */
    static boolean restoreAll(Context c, List<Xtream.Channel> all) throws IOException {
        Uri uri = find(c, FULL);
        if (uri == null) return false;
        JSONObject o;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) return false;
            o = new JSONObject(Xtream.read(in));
        } catch (JSONException e) {
            throw new IOException(e);
        }
        JSONObject prefs = o.optJSONObject("prefs");
        for (String name : PREFS) {
            JSONObject values = prefs == null ? null : prefs.optJSONObject(name);
            if (values == null) continue;
            android.content.SharedPreferences sp = c.getSharedPreferences(name, Context.MODE_PRIVATE);
            android.content.SharedPreferences.Editor e = sp.edit();
            for (String key : sp.getAll().keySet()) if (!SECRET.contains(key)) e.remove(key);
            java.util.Iterator<String> keys = values.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject v = values.optJSONObject(key);
                if (v == null || SECRET.contains(key)) continue;
                switch (v.optString("t")) {
                    case "b": e.putBoolean(key, v.optBoolean("v")); break;
                    case "i": e.putInt(key, v.optInt("v")); break;
                    case "l": e.putLong(key, v.optLong("v")); break;
                    case "f": e.putFloat(key, (float) v.optDouble("v")); break;
                    case "s": {
                        Set<String> set = new HashSet<>();
                        JSONArray a = v.optJSONArray("v");
                        for (int i = 0; a != null && i < a.length(); i++) set.add(a.optString(i));
                        e.putStringSet(key, set);
                        break;
                    }
                    default: e.putString(key, v.optString("v")); break;
                }
            }
            e.commit();
        }
        JSONObject files = o.optJSONObject("files");
        for (String name : FILES) {
            if (files == null || !files.has(name)) continue;
            java.io.File f = new java.io.File(c.getFilesDir(), name);
            if (files.isNull(name)) f.delete(); else Xtream.write(f, files.optString(name));
        }
        JSONObject profiles = o.optJSONObject("profiles");
        if (profiles != null) {
            java.util.Iterator<String> ids = profiles.keys();
            while (ids.hasNext()) {
                String id = ids.next();
                JSONObject one = profiles.optJSONObject(id);
                if (one == null || id.contains("/") || id.contains("..")) continue;
                java.io.File dir = new java.io.File(new java.io.File(c.getFilesDir(), "profiles"), id);
                dir.mkdirs();
                java.util.Iterator<String> names = one.keys();
                while (names.hasNext()) {
                    String n = names.next();
                    if (!n.contains("/") && !n.contains("..")) Xtream.write(new java.io.File(dir, n), one.optString(n));
                }
            }
        }
        JSONArray a = o.optJSONArray("favorites");
        List<Xtream.Channel> favs = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject f = a.optJSONObject(i);
            if (f == null) continue;
            Xtream.Channel ch = match(all, f.optInt("id"), f.optInt("num"), Xtream.fold(f.optString("name")), f.optString("epg"));
            if (ch != null && seen.add(ch.id)) favs.add(ch);
        }
        Xtream.saveFavorites(c, favs);
        return true;
    }

    private static String text(java.io.File f) throws IOException {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            return Xtream.read(in);
        }
    }

    /** `name` in Downloads, the old one replaced. */
    private static void write(Context c, String name, String text) throws IOException {
        ContentResolver r = c.getContentResolver();
        Uri old = find(c, name);
        if (old != null) r.delete(old, null, null);
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/");
        Uri uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("no Downloads");
        try (OutputStream out = r.openOutputStream(uri)) {
            if (out == null) throw new IOException("can't write");
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static Uri find(Context c) {
        return find(c, NAME);
    }

    private static Uri find(Context c, String name) {
        if (!available()) return null;
        String[] cols = {MediaStore.MediaColumns._ID};
        try (Cursor q = c.getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cols,
                MediaStore.MediaColumns.DISPLAY_NAME + " = ?", new String[] {name}, MediaStore.MediaColumns.DATE_MODIFIED + " DESC")) {
            if (q != null && q.moveToFirst()) return Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, String.valueOf(q.getLong(0)));
        } catch (RuntimeException e) {
            AppLog.w("live: favorites export unreadable: " + e.getMessage());
        }
        return null;
    }
}
