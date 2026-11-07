package com.glasslauncher.tv.live;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Which sources of a merged channel keep failing. Live moves on when a source fails, but only until the channel changes;
 * this remembers, so a channel doesn't start on the same bad source again and again. A few hours only: a source that
 * was bad this evening may be fine tomorrow. A source picked by hand is never passed over (Live.pickSource).
 */
final class LiveReliability {
    private LiveReliability() {}

    private static final long WINDOW_MS = 6 * 3600_000L;
    private static final int TIMES = 3, KEPT = 8;

    private static Map<Integer, long[]> troubles;

    /** Whether those failure times say to pass the source over for now. Pure. */
    static boolean avoid(long[] times, long now) {
        return recent(times, now).length >= TIMES;
    }

    /** The times still in the window, at most a handful. Pure. */
    static long[] recent(long[] times, long now) {
        long[] kept = new long[times.length];
        int n = 0;
        for (long t : times) if (now - t < WINDOW_MS && n < KEPT) kept[n++] = t;
        return Arrays.copyOf(kept, n);
    }

    /** `pick` passing over `bad`; when that leaves nothing, `pick` as it is: a bad source beats no picture. Pure. */
    static Xtream.Channel choose(Set<Integer> bad, Function<Set<Integer>, Xtream.Channel> pick) {
        if (bad.isEmpty()) return pick.apply(bad);
        Xtream.Channel out = pick.apply(bad);
        return out != null ? out : pick.apply(new HashSet<>());
    }

    /** The sources of `ch` to pass over now. */
    static synchronized Set<Integer> bad(Context c, Xtream.Channel ch) {
        Set<Integer> out = new HashSet<>();
        if (ch.sources == null) return out;
        load(c);
        long now = System.currentTimeMillis();
        for (Xtream.Channel s : ch.sources) {
            long[] t = troubles.get(s.id);
            if (t != null && avoid(t, now)) out.add(s.id);
        }
        return out;
    }

    /** Source `id` failed just now. */
    static synchronized void note(Context c, int id) {
        load(c);
        long now = System.currentTimeMillis();
        long[] had = troubles.get(id);
        long[] all = new long[(had == null ? 0 : had.length) + 1];
        all[0] = now;
        if (had != null) System.arraycopy(had, 0, all, 1, had.length);
        troubles.put(id, recent(all, now));
        save(c);
    }

    /** All forgotten (the test broadcast's). */
    static synchronized void clear(Context c) {
        troubles = new HashMap<>();
        //noinspection ResultOfMethodCallIgnored
        file(c).delete();
    }

    private static void load(Context c) {
        if (troubles != null) return;
        troubles = new HashMap<>();
        File f = file(c);
        if (!f.exists()) return;
        try (FileInputStream in = new FileInputStream(f)) {
            JSONObject o = new JSONObject(Xtream.read(in));
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                JSONArray a = o.getJSONArray(k);
                long[] t = new long[a.length()];
                for (int i = 0; i < t.length; i++) t[i] = a.getLong(i);
                troubles.put(Integer.parseInt(k), t);
            }
        } catch (IOException | JSONException | NumberFormatException ignored) {
        }
    }

    private static void save(Context c) {
        JSONObject o = new JSONObject();
        try {
            for (Map.Entry<Integer, long[]> e : troubles.entrySet()) {
                JSONArray a = new JSONArray();
                for (long t : e.getValue()) a.put(t);
                o.put(String.valueOf(e.getKey()), a);
            }
        } catch (JSONException ignored) {
        }
        final String text = o.toString();
        final File f = file(c);
        new Thread(() -> {
            try {
                Xtream.write(f, text);
            } catch (IOException ignored) {
            }
        }, "source-trouble").start();
    }

    private static File file(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), "live-source-trouble.json");
    }
}
