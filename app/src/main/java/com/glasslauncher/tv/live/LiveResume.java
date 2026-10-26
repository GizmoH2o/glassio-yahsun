package com.glasslauncher.tv.live;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.media.tv.TvContract;
import android.net.Uri;

/**
 * A past program (catch-up, Start over) left before its end goes into Continue Watching: Android's Watch Next list,
 * which Glassio's home screen (and Google's) shows, with how far it got; OK there opens it in Live TV right where it
 * was. Finished, or barely started, it leaves the list. Background thread (the TV's provider is a database).
 */
final class LiveResume {
    private LiveResume() {}

    /** Under this, it wasn't really watched; over this share of it, it's done. */
    private static final long MIN_MS = 60_000;
    private static final float DONE = 0.95f;

    /** Where the program got to: kept in Continue Watching, or taken out (finished or barely started). */
    static void left(Context c, Xtream.Channel ch, Xtream.Program p, long position, long length) {
        if (ch == null || p == null) return;
        long total = length > 0 ? length : p.end - p.start;
        if (position < MIN_MS || (total > 0 && position >= total * DONE)) {
            AppLog.w("live: not in Continue Watching (" + position / 1000 + " s of " + total / 1000 + " s)");
            remove(c, ch, p);
            return;
        }
        keep(c, ch, p, position, total);
        ContentValues v = new ContentValues();
        v.put(TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE, TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE);
        v.put(TvContract.WatchNextPrograms.COLUMN_TYPE, TvContract.WatchNextPrograms.TYPE_TV_EPISODE);
        v.put(TvContract.WatchNextPrograms.COLUMN_TITLE, p.title.isEmpty() ? ch.name : p.title);
        v.put(TvContract.WatchNextPrograms.COLUMN_SHORT_DESCRIPTION, ch.name + (p.about.isEmpty() ? "" : " · " + p.about));
        String art = !p.icon.isEmpty() ? p.icon : ch.logo;
        if (art != null && !art.isEmpty()) {
            v.put(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI, art);
            v.put(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_ASPECT_RATIO, TvContract.WatchNextPrograms.ASPECT_RATIO_16_9);
        }
        v.put(TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, (int) Math.min(Integer.MAX_VALUE, position));
        v.put(TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS, (int) Math.min(Integer.MAX_VALUE, total));
        v.put(TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, System.currentTimeMillis());
        v.put(TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID, id(ch, p));
        v.put(TvContract.WatchNextPrograms.COLUMN_CONTENT_ID, id(ch, p));
        v.put(TvContract.WatchNextPrograms.COLUMN_INTENT_URI, intent(c, ch, p, position).toUri(Intent.URI_INTENT_SCHEME));
        // The TV's provider takes no selection: each entry is updated by its own row, kept here by the program's id.
        synchronized (LiveResume.class) {
            try {
                org.json.JSONObject rows = rows(c);
                long row = rows.optLong(id(ch, p), -1);
                int n = row < 0 ? 0 : c.getContentResolver().update(TvContract.buildWatchNextProgramUri(row), v, null, null);
                if (n == 0) {      // new, or taken out of Continue Watching meanwhile
                    Uri added = c.getContentResolver().insert(TvContract.WatchNextPrograms.CONTENT_URI, v);
                    if (added != null) rows.put(id(ch, p), android.content.ContentUris.parseId(added));
                    saveRows(c, rows);
                }
                AppLog.w("live: in Continue Watching at " + position / 1000 + " s of " + total / 1000 + " s" + (n == 0 ? " (new)" : ""));
            } catch (RuntimeException | org.json.JSONException e) {
                AppLog.w("live: couldn't keep it in Continue Watching: " + e.getMessage());
            }
        }
    }

    static void remove(Context c, Xtream.Channel ch, Xtream.Program p) {
        synchronized (LiveResume.class) {
            org.json.JSONObject rows = rows(c);
            long row = rows.optLong(id(ch, p), -1);
            if (row < 0) return;
            try {
                c.getContentResolver().delete(TvContract.buildWatchNextProgramUri(row), null, null);
            } catch (RuntimeException ignored) {
            }
            rows.remove(id(ch, p));
            saveRows(c, rows);
        }
        forget(c, ch, p);
    }

    // MARK: - Live TV's own list (its Home's Continue watching row), the newest first

    /** A program left before its end: its channel's id, the program, where it got to, how long it is. */
    static final class Entry {
        int channel;
        final Xtream.Program program = new Xtream.Program();
        long position, length, at;
    }

    private static final int KEEP = 20;

    static synchronized java.util.List<Entry> entries(Context c) {
        java.util.List<Entry> out = new java.util.ArrayList<>();
        org.json.JSONArray a = list(c);
        for (int i = 0; i < a.length(); i++) {
            org.json.JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Entry e = new Entry();
            e.channel = o.optInt("ch");
            e.program.start = o.optLong("start");
            e.program.end = o.optLong("end");
            e.program.title = o.optString("title");
            e.program.about = o.optString("about");
            e.program.icon = o.optString("icon");
            e.position = o.optLong("pos");
            e.length = o.optLong("len");
            e.at = o.optLong("at");
            out.add(e);
        }
        return out;
    }

    private static synchronized void keep(Context c, Xtream.Channel ch, Xtream.Program p, long position, long length) {
        try {
            org.json.JSONArray a = list(c), out = new org.json.JSONArray();
            out.put(new org.json.JSONObject().put("ch", ch.id).put("start", p.start).put("end", p.end).put("title", p.title)
                    .put("about", p.about).put("icon", p.icon).put("pos", position).put("len", length)
                    .put("at", System.currentTimeMillis()));
            for (int i = 0; i < a.length() && out.length() < KEEP; i++) {
                org.json.JSONObject o = a.optJSONObject(i);
                if (o != null && !(o.optInt("ch") == ch.id && o.optLong("start") == p.start)) out.put(o);
            }
            saveList(c, out);
        } catch (org.json.JSONException ignored) {
        }
    }

    private static synchronized void forget(Context c, Xtream.Channel ch, Xtream.Program p) {
        org.json.JSONArray a = list(c), out = new org.json.JSONArray();
        for (int i = 0; i < a.length(); i++) {
            org.json.JSONObject o = a.optJSONObject(i);
            if (o != null && !(o.optInt("ch") == ch.id && o.optLong("start") == p.start)) out.put(o);
        }
        if (out.length() != a.length()) saveList(c, out);
    }

    private static org.json.JSONArray list(Context c) {
        java.io.File f = new java.io.File(c.getFilesDir(), "live-resume.json");
        if (!f.exists()) return new org.json.JSONArray();
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[(int) Math.min(f.length(), 1 << 20)];
            int n = in.read(b);
            return new org.json.JSONArray(new String(b, 0, Math.max(0, n), java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException | org.json.JSONException e) {
            return new org.json.JSONArray();
        }
    }

    private static void saveList(Context c, org.json.JSONArray a) {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(c.getFilesDir(), "live-resume.json"))) {
            out.write(a.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException ignored) {
        }
    }

    /** Which Watch Next row is which program's (a small file: these come and go). */
    private static org.json.JSONObject rows(Context c) {
        java.io.File f = new java.io.File(c.getFilesDir(), "live-watch-next.json");
        if (!f.exists()) return new org.json.JSONObject();
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[(int) Math.min(f.length(), 1 << 20)];
            int n = in.read(b);
            return new org.json.JSONObject(new String(b, 0, Math.max(0, n), java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException | org.json.JSONException e) {
            return new org.json.JSONObject();
        }
    }

    private static void saveRows(Context c, org.json.JSONObject rows) {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(c.getFilesDir(), "live-watch-next.json"))) {
            out.write(rows.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException ignored) {
        }
    }

    /** One entry per program: the channel and when it started. */
    private static String id(Xtream.Channel ch, Xtream.Program p) {
        return "zero-live:" + ch.id + ":" + p.start;
    }

    /** Opens Live TV on the program, at `position`. */
    private static Intent intent(Context c, Xtream.Channel ch, Xtream.Program p, long position) {
        return new Intent().setComponent(new android.content.ComponentName(c, Live.class))
                .setData(Uri.parse("zero-live://resume/" + ch.id + "/" + p.start))     // a distinct intent per program
                .putExtra(Live.EXTRA_RESUME_CHANNEL, ch.id)
                .putExtra(Live.EXTRA_RESUME_START, p.start)
                .putExtra(Live.EXTRA_RESUME_END, p.end)
                .putExtra(Live.EXTRA_RESUME_TITLE, p.title)
                .putExtra(Live.EXTRA_RESUME_AT, position);
    }
}
