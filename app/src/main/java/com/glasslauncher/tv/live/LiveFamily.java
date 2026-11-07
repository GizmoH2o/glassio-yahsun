package com.glasslauncher.tv.live;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Family Mode for Kids profiles (Live TV › Settings › Parental): so much Live TV a day, a bedtime, and 30 minutes more
 * with the parents' PIN. Time counts only while something plays (live, catch-up, a recording), each Kids profile its own,
 * from nothing at midnight. The rules are pure; the profile's count is kept in Live TV's settings.
 */
final class LiveFamily {
    private LiveFamily() {}

    /** Minutes of Live TV a day (0: no limit). */
    static final int[] LIMITS = {0, 30, 60, 90, 120, 180};
    /** The hour watching stops (0: no bedtime); it may start again at WAKE. */
    static final int[] BEDTIMES = {0, 19, 20, 21, 22};
    static final int WAKE = 7;
    /** A Kids profile's own age rating: shows rated this or older ask for the PIN (0: as the main one's). */
    static final int[] KIDS_AGES = {0, 7, 10, 12, 14};

    static final long WARN_MS = 5 * 60_000L, MORE_MS = 30 * 60_000L;
    /** The most one tick may count: longer since the last, the TV slept or nothing played. */
    static final long TICK_MAX_MS = 20_000L;

    static final int NONE = 0, LIMIT = 1, BEDTIME = 2;

    // MARK: - The rules (pure)

    /** The day `now` falls on, as yyyymmdd. */
    static int day(long now, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(now);
        return c.get(Calendar.YEAR) * 10_000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH);
    }

    /** Time watched today: what was kept, if it was kept today. */
    static long used(int keptDay, long keptMs, int today) {
        return keptDay == today ? Math.max(0, keptMs) : 0;
    }

    /** What a tick at `now` counts, the last at `last` (0: none yet). */
    static long tick(long last, long now) {
        if (last <= 0 || now <= last) return 0;
        return Math.min(TICK_MAX_MS, now - last);
    }

    private static int hour(long now, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(now);
        return c.get(Calendar.HOUR_OF_DAY);
    }

    static boolean inBedtime(long now, TimeZone tz, int bed) {
        if (bed <= 0) return false;
        int h = hour(now, tz);
        return h >= bed || h < WAKE;
    }

    /** Why watching stops now (NONE, LIMIT or BEDTIME): `limit` minutes a day, `bed` an hour, `grace` extra time's end. */
    static int stop(long now, TimeZone tz, int limit, long usedMs, int bed, long grace) {
        if (now < grace) return NONE;
        if (limit > 0 && usedMs >= limit * 60_000L) return LIMIT;
        if (inBedtime(now, tz, bed)) return BEDTIME;
        return NONE;
    }

    /** How long until watching stops (Long.MAX_VALUE: it won't), counting watching from now on. */
    static long left(long now, TimeZone tz, int limit, long usedMs, int bed, long grace) {
        if (now < grace) {
            long more = grace - now;
            if (stop(grace, tz, limit, usedMs + more, bed, 0) != NONE) return more;
            // nothing stops when the extra time ends: as if it weren't there
        }
        long left = Long.MAX_VALUE;
        if (limit > 0) left = Math.max(0, limit * 60_000L - usedMs);
        if (bed > 0) left = Math.min(left, inBedtime(now, tz, bed) ? 0 : untilHour(now, tz, bed));
        return left;
    }

    private static long untilHour(long now, TimeZone tz, int hour) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= now) c.add(Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis() - now;
    }

    /** Whether the 5-minute warning shows now (once: `warned` once it has). */
    static boolean warnNow(long left, boolean warned) {
        return !warned && left > 0 && left <= WARN_MS;
    }

    // MARK: - Kept, per profile

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("live-family", Context.MODE_PRIVATE);
    }

    static int limit(Context c) {
        return sp(c).getInt("limit", 0);
    }

    static void setLimit(Context c, int minutes) {
        sp(c).edit().putInt("limit", minutes).apply();
    }

    static int bedtime(Context c) {
        return sp(c).getInt("bedtime", 0);
    }

    static void setBedtime(Context c, int hour) {
        sp(c).edit().putInt("bedtime", hour).apply();
    }

    static int kidsAge(Context c) {
        return sp(c).getInt("kidsAge", 0);
    }

    static void setKidsAge(Context c, int age) {
        sp(c).edit().putInt("kidsAge", age).apply();
    }

    /** Whether any rule is set (Kids profiles only), so the clock needs to run. */
    static boolean on(Context c) {
        return limit(c) > 0 || bedtime(c) > 0;
    }

    /** The profile's time watched today. */
    static long usedToday(Context c, String profile, long now) {
        SharedPreferences p = sp(c);
        return used(p.getInt("day." + profile, 0), p.getLong("used." + profile, 0), day(now, TimeZone.getDefault()));
    }

    /** Adds `ms` to the profile's time watched today. */
    static void add(Context c, String profile, long now, long ms) {
        if (ms <= 0) return;
        long used = usedToday(c, profile, now) + ms;
        sp(c).edit().putInt("day." + profile, day(now, TimeZone.getDefault())).putLong("used." + profile, used).apply();
    }

    /** The profile's time watched today becomes `ms` (the test hook). */
    static void setUsed(Context c, String profile, long now, long ms) {
        sp(c).edit().putInt("day." + profile, day(now, TimeZone.getDefault())).putLong("used." + profile, ms).apply();
    }

    static long grace(Context c, String profile) {
        return sp(c).getLong("grace." + profile, 0);
    }

    /** Extra time over (the test hook). */
    static void endGrace(Context c, String profile) {
        sp(c).edit().remove("grace." + profile).apply();
    }

    /** 30 minutes more from `now` (the parents' PIN given). */
    static void more(Context c, String profile, long now) {
        sp(c).edit().putLong("grace." + profile, now + MORE_MS).apply();
    }

    static int stopNow(Context c, String profile, long now) {
        return stop(now, TimeZone.getDefault(), limit(c), usedToday(c, profile, now), bedtime(c), grace(c, profile));
    }

    /** What stops watching next (LIMIT or BEDTIME; NONE when nothing will). */
    static int nextStop(Context c, String profile, long now) {
        long left = leftNow(c, profile, now);
        if (left == Long.MAX_VALUE) return NONE;
        return stop(now + left, TimeZone.getDefault(), limit(c), usedToday(c, profile, now) + left, bedtime(c), 0);
    }

    static long leftNow(Context c, String profile, long now) {
        return left(now, TimeZone.getDefault(), limit(c), usedToday(c, profile, now), bedtime(c), grace(c, profile));
    }

    // MARK: - Channels for Kids profiles, set from the channel menu

    /** In a file, not the settings: the home screen's process reads them too (its cards for a Kids profile). */
    private static final String FILE = "live-kids-channels.json";

    static java.util.Set<Integer> allowed(Context c) {
        return ids(rules(c).optString("allow", ""));
    }

    static java.util.Set<Integer> hidden(Context c) {
        return ids(rules(c).optString("hide", ""));
    }

    private static org.json.JSONObject rules(Context c) {
        java.io.File f = new java.io.File(c.getFilesDir(), FILE);
        if (!f.exists()) return new org.json.JSONObject();
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            return new org.json.JSONObject(Xtream.read(in));
        } catch (Exception e) {
            return new org.json.JSONObject();
        }
    }

    /** Allows `id` on Kids profiles (or not): taking it out of the hidden ones. */
    static synchronized void setAllowed(Context c, int id, boolean on) {
        java.util.Set<Integer> allow = allowed(c), hide = hidden(c);
        if (on) {
            allow.add(id);
            hide.remove(id);
        } else {
            allow.remove(id);
        }
        save(c, allow, hide);
    }

    /** Hides `id` from Kids profiles (or not): taking it out of the allowed ones. */
    static synchronized void setHidden(Context c, int id, boolean on) {
        java.util.Set<Integer> allow = allowed(c), hide = hidden(c);
        if (on) {
            hide.add(id);
            allow.remove(id);
        } else {
            hide.remove(id);
        }
        save(c, allow, hide);
    }

    private static void save(Context c, java.util.Set<Integer> allow, java.util.Set<Integer> hide) {
        try {
            Xtream.write(new java.io.File(c.getFilesDir(), FILE),
                    new org.json.JSONObject().put("allow", join(allow)).put("hide", join(hide)).toString());
        } catch (org.json.JSONException | java.io.IOException e) {
            AppLog.w("live: couldn't keep the Kids channels");
        }
    }

    static java.util.Set<Integer> ids(String s) {
        java.util.Set<Integer> out = new java.util.HashSet<>();
        if (s == null) return out;
        for (String part : s.split(",")) {
            try {
                if (!part.trim().isEmpty()) out.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    private static String join(java.util.Set<Integer> ids) {
        StringBuilder b = new StringBuilder();
        for (Integer id : ids) b.append(b.length() == 0 ? "" : ",").append(id);
        return b.toString();
    }
}
