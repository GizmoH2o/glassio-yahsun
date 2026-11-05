package com.glasslauncher.tv.live;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * NEW and LIVE for the programmes whose listing doesn't mark them, from what the rest of the guide says. Only on firm
 * evidence: a badge that's sometimes wrong is worse than one that's missing. Big guides mark a show on one channel and
 * not on the next, so a mark carries to the same show on elsewhere; the words in a title don't count ("Morning Live",
 * and an unmarked match is most often a replay). Pure.
 */
final class LiveMarks {
    private LiveMarks() {}

    private static final long MINUTE = 60_000L, HOUR = 60 * MINUTE;
    /** Two listings this close are the same showing. */
    private static final long SAME_TIME = 15 * MINUTE;
    /** A new episode on again within this is the other coast's first showing, not a repeat. */
    private static final long SAME_NIGHT = 12 * HOUR;
    /** A programme in the small hours still belongs to the evening before: tonight's show, on after midnight. */
    private static final long NIGHT = 5 * HOUR;

    /** Live happens once: the same title at the same time, on another channel. */
    static boolean sameLiveShow(long startA, long startB) {
        return Math.abs(startA - startB) <= SAME_TIME;
    }

    /**
     * New on another channel: the same episode the same night, or, with one of them not saying which episode, the same
     * time. `episodeA` and `episodeB` are from {@link #episodeId}.
     */
    static boolean sameNewShow(long startA, int episodeA, long startB, int episodeB) {
        long apart = Math.abs(startA - startB);
        if (episodeA != 0 && episodeB != 0) return episodeA == episodeB && apart <= SAME_NIGHT;
        return apart <= SAME_TIME;
    }

    /** Which episode a listing is, to compare (0: it doesn't say): its sub-title, or the description's short first line. */
    static int episodeId(String sub, String about) {
        String line = sub == null ? "" : sub.trim();
        if (line.isEmpty() && about != null) {
            int nl = about.indexOf('\n');
            if (nl > 0 && nl <= 90) line = about.substring(0, nl).trim();
        }
        return line.isEmpty() ? 0 : Xtream.fold(line).hashCode();
    }

    /** "20261104", "2026-11-04 20:00:00" or "20261104200000 +0000" as yyyyMMdd; "" when it isn't a whole date ("2026"). */
    static String dayOf(String raw) {
        if (raw == null) return "";
        StringBuilder d = new StringBuilder(8);
        for (int i = 0; i < raw.length() && d.length() < 8; i++) {
            char ch = raw.charAt(i);
            if (ch >= '0' && ch <= '9') d.append(ch);
            else if (ch != '-' && ch != '/' && ch != '.') break;
        }
        if (d.length() < 8) return "";
        int month = Integer.parseInt(d.substring(4, 6)), day = Integer.parseInt(d.substring(6, 8));
        return month >= 1 && month <= 12 && day >= 1 && day <= 31 ? d.toString() : "";
    }

    /** First shown the day it's on (or the evening before, for one after midnight). */
    static boolean firstShownNow(String firstDay, long start, TimeZone zone) {
        if (firstDay == null || firstDay.isEmpty()) return false;
        return firstDay.equals(day(start, zone)) || firstDay.equals(day(start - NIGHT, zone));
    }

    /** First shown on a day before this showing's: a repeat, whatever else it says. */
    static boolean firstShownBefore(String firstDay, long start, TimeZone zone) {
        return firstDay != null && !firstDay.isEmpty() && !firstShownNow(firstDay, start, zone)
                && firstDay.compareTo(day(start, zone)) < 0;
    }

    private static String day(long ms, TimeZone zone) {
        Calendar c = Calendar.getInstance(zone, Locale.ROOT);
        c.setTimeInMillis(ms);
        return String.format(Locale.ROOT, "%04d%02d%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }
}
