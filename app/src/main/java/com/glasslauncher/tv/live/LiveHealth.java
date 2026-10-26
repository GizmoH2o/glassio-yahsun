package com.glasslauncher.tv.live;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Each channel's trouble (frozen, connected again, another source, couldn't play), kept a week in a small file
 * (live-health.json): the info bar marks a channel that just had some, and Settings > Stream health lists the
 * channels with the most, to tell the provider about.
 */
final class LiveHealth {
    private LiveHealth() {}

    static final int FROZE = 0, RECONNECT = 1, SWITCHED = 2, FAILED = 3;
    private static final long KEEP = 7 * 24 * 3600_000L;
    private static final int PER_CHANNEL = 60;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static JSONObject all;

    /** A channel's week, added up. */
    static final class Entry {
        int id, froze, reconnects, switched, failed;
        String name = "";
        long last;

        int total() {
            return froze + reconnects + switched + failed;
        }
    }

    /** `kind` happened on `ch` just now (the file is written in the background). */
    static void note(Context c, Xtream.Channel ch, int kind) {
        if (ch == null) return;
        final Context app = c.getApplicationContext();
        final long now = System.currentTimeMillis();
        synchronized (LiveHealth.class) {
            JSONObject o = read(app);
            try {
                String key = String.valueOf(ch.id);
                JSONObject e = o.optJSONObject(key);
                if (e == null) o.put(key, e = new JSONObject());
                e.put("name", ch.name);
                JSONArray ev = e.optJSONArray("ev");
                if (ev == null) e.put("ev", ev = new JSONArray());
                ev.put(new JSONArray().put(now).put(kind));
                while (ev.length() > PER_CHANNEL) ev.remove(0);
            } catch (JSONException ignored) {
            }
        }
        IO.execute(() -> save(app));
    }

    /** How much trouble channel `id` had in the last `ms` (for the info bar). */
    static synchronized int recent(Context c, int id, long ms) {
        JSONObject e = read(c).optJSONObject(String.valueOf(id));
        JSONArray ev = e == null ? null : e.optJSONArray("ev");
        if (ev == null) return 0;
        long from = System.currentTimeMillis() - ms;
        int n = 0;
        for (int i = ev.length() - 1; i >= 0; i--) {
            JSONArray one = ev.optJSONArray(i);
            if (one == null || one.optLong(0) < from) break;
            n++;
        }
        return n;
    }

    /** The channels with trouble this week, the most first. */
    static synchronized List<Entry> week(Context c) {
        List<Entry> out = new ArrayList<>();
        long from = System.currentTimeMillis() - KEEP;
        JSONObject o = read(c);
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String key = it.next();
            JSONObject e = o.optJSONObject(key);
            JSONArray ev = e == null ? null : e.optJSONArray("ev");
            if (ev == null) continue;
            Entry en = new Entry();
            try {
                en.id = Integer.parseInt(key);
            } catch (NumberFormatException x) {
                continue;
            }
            en.name = e.optString("name");
            for (int i = 0; i < ev.length(); i++) {
                JSONArray one = ev.optJSONArray(i);
                if (one == null || one.optLong(0) < from) continue;
                en.last = Math.max(en.last, one.optLong(0));
                switch (one.optInt(1)) {
                    case FROZE: en.froze++; break;
                    case RECONNECT: en.reconnects++; break;
                    case SWITCHED: en.switched++; break;
                    default: en.failed++; break;
                }
            }
            if (en.total() > 0) out.add(en);
        }
        Collections.sort(out, (a, b) -> a.total() != b.total() ? b.total() - a.total() : Long.compare(b.last, a.last));
        return out;
    }

    /** "3 freezes · 2 reconnects · 1 switch": what happened, in words. */
    static String describe(Entry e) {
        List<String> parts = new ArrayList<>();
        if (Ui.ru) {
            if (e.froze > 0) parts.add(Russian.count(e.froze, "зависание", "зависания", "зависаний"));
            if (e.reconnects > 0) parts.add(Russian.count(e.reconnects, "переподключение", "переподключения", "переподключений"));
            if (e.switched > 0) parts.add(Russian.count(e.switched, "смена источника", "смены источника", "смен источника"));
            if (e.failed > 0) parts.add(Russian.count(e.failed, "сбой", "сбоя", "сбоев"));
            return android.text.TextUtils.join("  ·  ", parts);
        }
        if (e.froze > 0) parts.add(e.froze + (e.froze == 1 ? Ui.tr(" travada", " freeze") : Ui.tr(" travadas", " freezes")));
        if (e.reconnects > 0) parts.add(e.reconnects + (e.reconnects == 1 ? Ui.tr(" reconexão", " reconnect") : Ui.tr(" reconexões", " reconnects")));
        if (e.switched > 0) parts.add(e.switched + (e.switched == 1 ? Ui.tr(" troca de fonte", " source switch") : Ui.tr(" trocas de fonte", " source switches")));
        if (e.failed > 0) parts.add(e.failed + (e.failed == 1 ? Ui.tr(" falha", " failure") : Ui.tr(" falhas", " failures")));
        return android.text.TextUtils.join("  ·  ", parts);
    }

    static synchronized void clear(Context c) {
        all = new JSONObject();
        final Context app = c.getApplicationContext();
        IO.execute(() -> save(app));
    }

    /** The file, once (then kept in memory), without what's older than a week. */
    private static JSONObject read(Context c) {
        if (all != null) return all;
        all = new JSONObject();
        File f = file(c);
        if (!f.exists()) return all;
        try (FileInputStream in = new FileInputStream(f)) {
            JSONObject o = new JSONObject(Xtream.read(in));
            long from = System.currentTimeMillis() - KEEP;
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String key = it.next();
                JSONObject e = o.optJSONObject(key);
                JSONArray ev = e == null ? null : e.optJSONArray("ev");
                if (ev == null) continue;
                JSONArray kept = new JSONArray();
                for (int i = 0; i < ev.length(); i++) {
                    JSONArray one = ev.optJSONArray(i);
                    if (one != null && one.optLong(0) >= from) kept.put(one);
                }
                if (kept.length() > 0) all.put(key, new JSONObject().put("name", e.optString("name")).put("ev", kept));
            }
        } catch (IOException | JSONException ignored) {
        }
        return all;
    }

    private static void save(Context c) {
        String text;
        synchronized (LiveHealth.class) {
            text = read(c).toString();
        }
        try {
            Xtream.write(file(c), text);
        } catch (IOException e) {
            AppLog.w("live: couldn't keep the channels' health");
        }
    }

    private static File file(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), "live-health.json");
    }
}
