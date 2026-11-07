package com.glasslauncher.tv.live;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/**
 * The Today page's day (Home › Today: your matches, new episodes, finished recordings and what's set, with the weather):
 * what's left of today, what counts in it, the recordings since yesterday, the morning it may open by itself in. Pure.
 */
public final class LiveToday {
    private LiveToday() {}

    /** Since yesterday: a recording finished this long ago or less. */
    static final long RECENT_MS = 24 * 3600_000L;
    /** The morning: from 5 to noon. */
    static final int MORNING_FROM = 5, MORNING_TO = 12;

    /** {now, the coming midnight}. */
    static long[] day(long now, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(now);
        c.add(Calendar.DAY_OF_MONTH, 1);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return new long[] {now, c.getTimeInMillis()};
    }

    /** Whether something from `start` to `end` is today's: on now, or starting before midnight. */
    static boolean within(long start, long end, long[] day) {
        return end > day[0] && start < day[1];
    }

    /** Whether a recording that ended at `end` finished since yesterday. */
    static boolean recent(long end, long now) {
        return end <= now && now - end <= RECENT_MS;
    }

    public static boolean morning(long now, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(now);
        int h = c.get(Calendar.HOUR_OF_DAY);
        return h >= MORNING_FROM && h < MORNING_TO;
    }

    /** The day as yyyymmdd. */
    public static int dayKey(long now, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(now);
        return c.get(Calendar.YEAR) * 10_000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH);
    }

    /** Whether Today opens by itself now: in the morning, not yet today (`lastDay`, the day it last did). */
    public static boolean opensItself(long now, TimeZone tz, int lastDay) {
        return morning(now, tz) && dayKey(now, tz) != lastDay;
    }

    /** The card's line, as its parts: "2 matches", "1 new episode", "1 recording", "3 set for today"; none for nothing. */
    public static List<String> counts(int matches, int episodes, int recordings, int set) {
        List<String> out = new ArrayList<>();
        if (matches > 0) out.add(matches + (matches == 1 ? " match" : " matches"));
        if (episodes > 0) out.add(episodes + (episodes == 1 ? " new episode" : " new episodes"));
        if (recordings > 0) out.add(recordings + (recordings == 1 ? " recording" : " recordings"));
        if (set > 0) out.add(set + " set for today");
        return out;
    }
}
