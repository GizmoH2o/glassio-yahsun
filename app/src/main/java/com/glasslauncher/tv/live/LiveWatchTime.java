package com.glasslauncher.tv.live;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * How long each channel was watched, a day at a time, the last 30 days: what Suggest favorites goes by. Each profile's
 * own, on this TV only; nothing is counted with that off. A channel counts from 2 minutes on, so zapping doesn't.
 */
final class LiveWatchTime {
    private LiveWatchTime() {}

    static final String FILE = "live-watch.json";
    static final int DAYS = 30, MIN_SECONDS = 120;

    /** What to add after `step` more seconds on a channel watched `session` seconds by now (with them). Pure. */
    static int counts(int session, int step) {
        if (session < MIN_SECONDS) return 0;
        return session - step < MIN_SECONDS ? session : step;
    }

    /** `all` ({channel: {day: seconds}}) with `seconds` more for `channel` on `day`, without days too old. Pure. */
    static void add(JSONObject all, int channel, long day, int seconds) throws JSONException {
        String key = String.valueOf(channel);
        JSONObject c = all.optJSONObject(key);
        if (c == null) all.put(key, c = new JSONObject());
        c.put(String.valueOf(day), c.optInt(String.valueOf(day)) + seconds);
        for (Iterator<String> it = all.keys(); it.hasNext(); ) {
            JSONObject one = all.optJSONObject(it.next());
            if (one == null) continue;
            List<String> old = new ArrayList<>();
            for (Iterator<String> d = one.keys(); d.hasNext(); ) {
                String k = d.next();
                if (Long.parseLong(k) <= day - DAYS) old.add(k);
            }
            for (String k : old) one.remove(k);
            if (one.length() == 0) it.remove();
        }
    }

    /** Seconds by channel, all its days. Pure. */
    static Map<Integer, Integer> totals(JSONObject all) {
        Map<Integer, Integer> out = new HashMap<>();
        for (Iterator<String> it = all.keys(); it.hasNext(); ) {
            String key = it.next();
            JSONObject c = all.optJSONObject(key);
            int n = 0;
            if (c != null) for (Iterator<String> d = c.keys(); d.hasNext(); ) n += c.optInt(d.next());
            try {
                if (n > 0) out.put(Integer.parseInt(key), n);
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    private static long today() {
        long now = System.currentTimeMillis();
        return (now + java.util.TimeZone.getDefault().getOffset(now)) / (24 * 3600_000L);
    }

    private static File file(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), FILE);
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

    static Map<Integer, Integer> totals(Context c) {
        return totals(read(c));
    }

    /** Background thread. */
    static void add(Context c, int channel, int seconds) {
        LiveLock.run(c, () -> {
            try {
                JSONObject all = read(c);
                add(all, channel, today(), seconds);
                Xtream.write(file(c), all.toString());
            } catch (IOException | JSONException | RuntimeException e) {
                AppLog.w("live: watch time: " + e.getMessage());
            }
        });
    }

    static void clear(Context c) {
        //noinspection ResultOfMethodCallIgnored
        file(c).delete();
    }
}
