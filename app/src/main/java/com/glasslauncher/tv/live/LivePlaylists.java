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
        /** The name given to it ("": none). */
        String label = "";
        /** What was known of it as it was last in use. */
        int channels;
        long guideAt;

        String host() {
            return server.replaceFirst("^https?://", "").replaceFirst("[:/].*$", "");
        }

        /** Its own name, else the user and the server's host. */
        String name() {
            return label.isEmpty() ? user + "  ·  " + host() : label;
        }
    }

    /** What a playlist's card says. */
    static final class Info {
        int channels, max, reminders, recordings;
        long daysLeft = -1, guideAt;
    }

    /** Each account's own files (in the app's files). */
    private static final String[] FILES = {"live-login.json", "live-channels.json", "live-favorites.json", "live-recent.json",
            "live-sources.json", "live-preferred.json", "live-speed.txt", "live-schedule.json", "live-series.json", "live-watch-next.json",
            "live-resume.json", "live-account.json", "live-guide-shift.txt", LiveWatchTime.FILE};
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
            pl.label = p.optString("label");
            pl.channels = p.optInt("channels");
            pl.guideAt = p.optLong("guideAt");
            out.add(pl);
        }
        // The account signed in before there were playlists: the first one.
        if (out.isEmpty() && !journalFile(c).exists() && Xtream.signedIn(c)) {
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
    static void switchTo(Context c, String id) {
        LiveLock.run(c, () -> switchToNow(c, id));
    }

    private static synchronized void switchToNow(Context c, String id) {
        List<Playlist> list = all(c);
        String now = active(c);
        if (id.equals(now)) return;
        journal(c, now, id, false, null);
        if (now != null) {
            for (Playlist p : list) if (p.id.equals(now)) p.guideAt = XmltvStore.updatedAt(c);
            stash(c, now);
        }
        journal(c, now, id, true, null);
        unstash(c, id);
        setPending(c, "");
        save(c, list, id, null);
        journalDone(c);
        armOthers(c);
    }

    // MARK: - A switch cut short (the power going): finished at the next start

    private static File journalFile(Context c) {
        return new File(c.getFilesDir(), "live-switch.json");
    }

    private static void journal(Context c, String from, String to, boolean out, String back) {
        try {
            Xtream.write(journalFile(c), new JSONObject().put("from", from == null ? "" : from).put("to", to == null ? "" : to)
                    .put("out", out).put("back", back == null ? "" : back).toString());
        } catch (IOException | JSONException e) {
            AppLog.w("live: switch: " + e.getMessage());
        }
    }

    private static void journalDone(Context c) {
        //noinspection ResultOfMethodCallIgnored
        journalFile(c).delete();
    }

    /** The switch under way when everything stopped, if there was one: the rest of it. */
    static void finish(Context c) {
        LiveLock.run(c, () -> finishNow(c));
    }

    private static synchronized void finishNow(Context c) {
        File f = journalFile(c);
        if (!f.exists()) return;
        try (FileInputStream in = new FileInputStream(f)) {
            JSONObject o = new JSONObject(Xtream.read(in));
            String from = o.optString("from"), to = o.optString("to"), back = o.optString("back");
            AppLog.w("live: finishing a playlist switch");
            List<Playlist> list = all(c);
            if (!from.isEmpty() && !o.optBoolean("out")) stash(c, from, true);
            if (!to.isEmpty()) unstash(c, to);
            save(c, list, to.isEmpty() ? null : to, back.isEmpty() ? null : back);
        } catch (IOException | JSONException | RuntimeException e) {
            AppLog.w("live: switch: " + e.getMessage());
        }
        journalDone(c);
        armOthers(c);
    }

    // MARK: - What's known of each

    /** Its login's file while it's put aside, or null (the one in use, or none known). */
    static File loginOf(Context c, String id) {
        String now = active(c);
        if (id == null || id.isEmpty() || id.equals("null") || id.equals(now)) return null;
        File f = new File(dir(c, id), "live-login.json");
        return f.exists() || now != null ? f : null;      // removed since: no login, rather than another account's

    }

    static void rename(Context c, String id, String label) {
        LiveLock.run(c, () -> renameNow(c, id, label));
    }

    private static synchronized void renameNow(Context c, String id, String label) {
        List<Playlist> list = all(c);
        for (Playlist p : list) if (p.id.equals(id)) p.label = label == null ? "" : label.trim();
        save(c, list, active(c), returnTo(c));
    }

    /** The one in use has `channels` (its card, once it's put aside). */
    static void seen(Context c, int channels) {
        LiveLock.run(c, () -> seenNow(c, channels));
    }

    private static synchronized void seenNow(Context c, int channels) {
        List<Playlist> list = all(c);
        String now = active(c);
        boolean change = false;
        for (Playlist p : list) if (p.id.equals(now) && p.channels != channels) {
            p.channels = channels;
            change = true;
        }
        if (change) save(c, list, now, returnTo(c));
    }

    static Info info(Context c, Playlist p) {
        Info n = new Info();
        boolean on = p.id.equals(active(c));
        n.channels = p.channels;
        n.guideAt = on ? XmltvStore.updatedAt(c) : p.guideAt;
        Xtream.Account a = on ? Xtream.savedAccount(c) : Xtream.savedAccount(new File(dir(c, p.id), "live-account.json"));
        if (a != null) {
            n.max = a.max;
            if (a.expires > 0) n.daysLeft = Math.max(0, (a.expires - System.currentTimeMillis()) / (24 * 3600_000L));
        }
        for (LiveSchedule.Item it : on ? LiveSchedule.all(c) : LiveSchedule.in(new File(dir(c, p.id), "live-schedule.json"))) {
            if (LiveSchedule.RECORD.equals(it.kind)) n.recordings++;
            else if (LiveSchedule.REMIND.equals(it.kind)) n.reminders++;
        }
        return n;
    }

    /** The playlist to use once what's recording is over ("": none). */
    static synchronized String pending(Context c) {
        return registry(c).optString("pending");
    }

    static void setPending(Context c, String id) {
        LiveLock.run(c, () -> setPendingNow(c, id));
    }

    private static synchronized void setPendingNow(Context c, String id) {
        JSONObject o = registry(c);
        try {
            o.put("pending", id);
            Xtream.write(new File(c.getFilesDir(), "live-playlists.json"), o.toString());
        } catch (IOException | JSONException e) {
            AppLog.w("live: couldn't save the playlists");
        }
    }

    // MARK: - What's set on the ones put aside

    static final String OTHERS = "com.glasslauncher.tv.live.action.OTHER_PLAYLIST";
    private static final long TOLD_BEFORE = 3600_000L;
    /** One card at a time: the next no sooner than this. */
    private static final long APART = 60_000;

    /** An alarm an hour before the first thing set on each playlist put aside (Tell me about other playlists). */
    static synchronized void armOthers(Context c) {
        android.app.AlarmManager am = (android.app.AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        String now = active(c);
        if (am == null) return;
        long t = System.currentTimeMillis(), toldAt = Math.min(t, registry(c).optLong("toldAt"));
        for (Playlist p : all(c)) {
            android.app.PendingIntent pi = otherIntent(c, p.id);
            am.cancel(pi);
            if (p.id.equals(now) || !LiveOptions.tellOthers(c)) continue;
            LiveSchedule.Item first = nextOn(c, p.id);
            if (first == null) continue;
            long at = Math.max(Math.max(t + 2000, toldAt + APART), first.start - TOLD_BEFORE);
            try {
                if (android.os.Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setWindow(android.app.AlarmManager.RTC_WAKEUP, at, 60_000, pi);
                else am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pi);
            } catch (SecurityException e) {
                am.setWindow(android.app.AlarmManager.RTC_WAKEUP, at, 60_000, pi);
            }
        }
    }

    private static android.app.PendingIntent otherIntent(Context c, String id) {
        return android.app.PendingIntent.getBroadcast(c, ("other" + id).hashCode(), new android.content.Intent(c, ScheduleReceiver.class)
                .setAction(OTHERS).setData(android.net.Uri.parse("zero-playlist:" + id)),
                android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** The first reminder or recording still ahead on playlist `id`, not told of yet. */
    private static LiveSchedule.Item nextOn(Context c, String id) {
        long now = System.currentTimeMillis();
        String told = registry(c).optString("told");
        for (LiveSchedule.Item it : LiveSchedule.in(new File(dir(c, id), "live-schedule.json"))) {
            if (it.start > now && !LiveSchedule.SWITCH.equals(it.kind) && !told.contains("|" + id + ":" + it.key() + "|")) return it;
        }
        return null;
    }

    /** The alarm: what it's for (told now, not again), or null. */
    static LiveSchedule.Item tell(Context c, String id) {
        final LiveSchedule.Item[] out = new LiveSchedule.Item[1];
        LiveLock.run(c, () -> out[0] = tellNow(c, id));
        return out[0];
    }

    private static synchronized LiveSchedule.Item tellNow(Context c, String id) {
        LiveSchedule.Item it = id.equals(active(c)) || !LiveOptions.tellOthers(c) ? null : nextOn(c, id);
        if (it != null && it.start - System.currentTimeMillis() > TOLD_BEFORE + 60_000) it = null;
        if (it != null) {
            JSONObject o = registry(c);
            String told = o.optString("told");
            if (told.length() > 4000) told = "";
            try {
                o.put("toldAt", System.currentTimeMillis());
                o.put("told", told + "|" + id + ":" + it.key() + "|");
                Xtream.write(new File(c.getFilesDir(), "live-playlists.json"), o.toString());
            } catch (IOException | JSONException ignored) {
            }
        }
        armOthers(c);
        return it;
    }

    static String nameOf(Context c, String id) {
        for (Playlist p : all(c)) if (p.id.equals(id)) return p.name();
        return "";
    }

    /** The one in use put aside, for the sign-in screen (a new account); Back there brings it back. */
    static void startAdding(Context c) {
        LiveLock.run(c, () -> startAddingNow(c));
    }

    private static synchronized void startAddingNow(Context c) {
        List<Playlist> list = all(c);
        String now = active(c);
        journal(c, now, null, false, now);
        if (now != null) stash(c, now);
        save(c, list, null, now);
        journalDone(c);
    }

    /** The sign-in left without a new account: the one before, back. False when there was none. */
    static boolean cancelAdding(Context c) {
        final boolean[] out = new boolean[1];
        LiveLock.run(c, () -> out[0] = cancelAddingNow(c));
        return out[0];
    }

    private static synchronized boolean cancelAddingNow(Context c) {
        String back = returnTo(c);
        if (back == null || active(c) != null) return false;
        journal(c, null, back, true, null);
        unstash(c, back);
        save(c, all(c), back, null);
        journalDone(c);
        return true;
    }

    /** Signed in (the sign-in screen): that account is in use, a new playlist or the one that was. */
    static void signedIn(Context c) {
        LiveLock.run(c, () -> signedInNow(c));
    }

    private static synchronized void signedInNow(Context c) {
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
            for (int i = 0; i < list.size(); i++) if (list.get(i).id.equals(now)) {
                me.label = list.get(i).label;
                me.channels = list.get(i).channels;
                list.set(i, me);
            }
        }
        save(c, list, me.id, null);
        armOthers(c);
    }

    /**
     * The one in use removed (its files went with signing out): another one in use if there is one, its id (null:
     * none left, the sign-in screen).
     */
    static String removeActive(Context c) {
        final String[] out = new String[1];
        LiveLock.run(c, () -> out[0] = removeActiveNow(c));
        return out[0];
    }

    private static synchronized String removeActiveNow(Context c) {
        List<Playlist> list = all(c);
        String now = active(c);
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).id.equals(now)) list.remove(i);
        if (list.isEmpty()) {
            save(c, list, null, null);
            return null;
        }
        String next = list.get(0).id;
        journal(c, null, next, true, null);
        unstash(c, next);
        save(c, list, next, null);
        journalDone(c);
        return next;
    }

    /** One put aside, removed with everything it kept. */
    static void remove(Context c, String id) {
        LiveLock.run(c, () -> removeNow(c, id));
    }

    private static synchronized void removeNow(Context c, String id) {
        List<Playlist> list = all(c);
        if (id.equals(active(c))) return;
        if (id.equals(pending(c))) setPending(c, "");
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
        stash(c, id, false);
    }

    /** `again`: the rest of one cut short: what's put aside already stays (a file made since is another's). */
    private static void stash(Context c, String id, boolean again) {
        LiveSchedule.disarmAll(c);      // its reminders' alarms (they come back with it)
        XmltvStore.release();
        File dir = dir(c, id);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        for (String name : FILES) putAside(new File(c.getFilesDir(), name), new File(dir, name), again);
        putAside(new File(c.getCacheDir(), "live-epg"), new File(c.getCacheDir(), "live-epg-" + id), again);
        putAside(c.getDatabasePath(GUIDE_DB), c.getDatabasePath("live-guide-" + id + ".db"), again);
        //noinspection ResultOfMethodCallIgnored
        new File(c.getDatabasePath(GUIDE_DB).getPath() + "-journal").delete();
        // Its own settings, and where it was (the last channel and category).
        SharedPreferences opt = options(c), ui = ui(c);
        JSONObject s = new JSONObject();
        // There already (a switch cut short, finished now): the settings were cleared since, not to be written over it.
        if (!new File(dir, "settings.json").exists()) try {
            for (String k : OPTION_SETS) s.put(k, new JSONArray(opt.getStringSet(k, new HashSet<>())));
            s.put("guideUrl", opt.getString("guideUrl", ""));
            s.put("fullGuide", opt.getBoolean("fullGuide", false));
            s.put("guideLinks", opt.getString("guideLinks", "[]"));
            s.put("guideProvider", opt.getBoolean("guideProvider", true));
            s.put("guideMigrated", opt.getBoolean("guideMigrated", false));
            s.put("channel", ui.getInt("channel", -1));
            s.put("category", ui.getString("category", ""));
            Xtream.write(new File(dir, "settings.json"), s.toString());
        } catch (IOException | JSONException e) {
            AppLog.w("live: couldn't put a playlist's settings aside");
        }
        SharedPreferences.Editor e = opt.edit();
        for (String k : OPTION_SETS) e.remove(k);
        e.remove("guideUrl").remove("fullGuide").remove("guideLinks").remove("guideProvider").remove("guideMigrated");
        for (String k : opt.getAll().keySet()) if (k.startsWith("guideStatus:")) e.remove(k);
        e.commit();
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
                e.putString("guideUrl", s.optString("guideUrl")).putBoolean("fullGuide", s.optBoolean("fullGuide"))
                        .putString("guideLinks", s.optString("guideLinks", "[]")).putBoolean("guideProvider", s.optBoolean("guideProvider", true))
                        .putBoolean("guideMigrated", s.optBoolean("guideMigrated", false)).commit();
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
        LiveSchedule.armAll(c, Ui.tr("outra lista estava em uso", "another playlist was in use"), id);
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

    /** `from` to `to`; `again`, with one there already: that one stays, `from` goes. */
    static void putAside(File from, File to, boolean again) {
        if (again && to.exists()) deleteTree(from);
        else move(from, to);
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
            for (Playlist p : list) a.put(new JSONObject().put("id", p.id).put("server", p.server).put("user", p.user)
                    .put("label", p.label).put("channels", p.channels).put("guideAt", p.guideAt));
            JSONObject was = registry(c);
            Xtream.write(new File(c.getFilesDir(), "live-playlists.json"), new JSONObject().put("list", a)
                    .put("pending", was.optString("pending")).put("told", was.optString("told")).put("toldAt", was.optLong("toldAt")).put("active", active == null ? "" : active).put("return", returnTo == null ? "" : returnTo).toString());
        } catch (IOException | JSONException e) {
            AppLog.w("live: couldn't save the playlists");
        }
    }
}
