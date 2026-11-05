package com.glasslauncher.tv.live;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Catch-up's rules, apart from the player. A provider's catch-up stream can't be trusted to seek: it may say it can and
 * give a length many times the show's (one 80-minute match said 17 hours, then 1), and a seek inside it can hang. So a
 * provider's past show moves only by asking for its address again, and addresses start on the minute.
 */
final class LiveCatchup {
    private LiveCatchup() {}

    /** The days of catch-up for a playlist that has it and doesn't say how many. */
    static final int DEFAULT_DAYS = 5;
    /** Further on than this since the last try, a stream wasn't stuck. */
    private static final long PROGRESS_MS = 30_000;
    private static final long MINUTE = 60_000;

    /** Where in the show (ms from `start`, can be just under 0) the address for `from` begins: its clock minute. */
    static long minute(long start, long from) {
        return Math.floorDiv(start + from, MINUTE) * MINUTE - start;
    }

    /** One press of ◀ or ▶ on the bar: a minute on a provider's catch-up, else 10 s back and 30 s ahead. */
    static long skipStep(boolean minutes, boolean back) {
        return minutes ? (back ? -MINUTE : MINUTE) : (back ? -10_000 : 30_000);
    }

    /** The skips stacked so far, from `at` to `to`: "+3 min", "−2 min", under a minute "+30 s". */
    static String skipLabel(long to, long at) {
        long by = to - at, size = Math.abs(by);
        String sign = by < 0 ? "−" : "+";
        return size >= MINUTE ? sign + Math.round(size / (double) MINUTE) + " min" : sign + size / 1000 + " s";
    }

    /** Where a skip to `to` from `at` asks to go: ahead, always further on than the minute it would otherwise start at. */
    static long skip(long start, long to, long at) {
        long minute = minute(start, to);
        return to > at && minute <= at ? minute + MINUTE : to;
    }

    /** The show's position: where it's going while a new address opens (`pending` >= 0), else where it's got to. */
    static long shown(long pending, long base, long playerAt) {
        return pending >= 0 ? pending : base + playerAt;
    }

    /** Whether a stream failing at `at`, having last been tried from `last` (< 0: never), got nowhere since. */
    static boolean stuck(long at, long last) {
        return last >= 0 && at - last < PROGRESS_MS;
    }

    /** Where to try again: the spot itself, then a minute further for each try that got nowhere, short of the end. */
    static long retryFrom(long at, int stuckTries, long length) {
        return Math.max(at, Math.min(at + stuckTries * MINUTE, length - MINUTE));
    }

    /** A playlist channel's days of catch-up from its attributes: 0 for none. */
    static int days(String mode, String catchupDays, String tvgRec) {
        for (String days : new String[] {catchupDays, tvgRec}) {
            try {
                int n = Integer.parseInt(days.trim());
                if (n > 0) return n;
            } catch (NumberFormatException ignored) {
            }
        }
        return mode.isEmpty() ? 0 : DEFAULT_DAYS;
    }

    private static final Pattern XC = Pattern.compile("^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/(\\d+)\\.(\\w+)(\\?.*)?$");
    private static final Pattern FLUSSONIC_HLS = Pattern.compile("^(.*)/(index|mono|video)\\.m3u8(\\?.*)?$");
    private static final Pattern FLUSSONIC_TS = Pattern.compile("^(.*)/mpegts(\\?.*)?$");
    private static final Pattern SCALED = Pattern.compile("\\{(duration|offset):(\\d+)\\}");

    /**
     * A playlist channel's past show from `start` to `end` (ms), by its `catchup` attribute: default (the source is
     * the address), append (the source goes after the channel's), shift, flussonic / fs, or xc. Null for a kind not
     * known here or one missing what it needs.
     */
    static String m3u(String url, String mode, String source, long start, long end, long now, TimeZone zone) {
        long seconds = (end - start) / 1000;
        switch (mode.toLowerCase(Locale.ROOT)) {
            case "default":
                return source.isEmpty() ? null : fill(source, start, end, now, zone);
            case "append":
                return source.isEmpty() ? null : url + fill(source, start, end, now, zone);
            case "shift":
                return url + (url.contains("?") ? "&" : "?") + "utc=" + start / 1000 + "&lutc=" + now / 1000;
            case "flussonic":
            case "fs": {
                Matcher hls = FLUSSONIC_HLS.matcher(url);
                if (hls.matches()) return hls.group(1) + "/" + hls.group(2) + "-" + start / 1000 + "-" + seconds + ".m3u8" + rest(hls.group(3));
                Matcher ts = FLUSSONIC_TS.matcher(url);
                return ts.matches() ? ts.group(1) + "/timeshift_abs-" + start / 1000 + ".ts" + rest(ts.group(2)) : null;
            }
            case "xc": {
                Matcher m = XC.matcher(url);
                if (!m.matches()) return null;
                long minutes = Math.max(1, (seconds + 59) / 60);
                return m.group(1) + "/timeshift/" + m.group(2) + "/" + m.group(3) + "/" + minutes + "/"
                        + format("yyyy-MM-dd:HH-mm", start, zone) + "/" + m.group(4) + "." + m.group(5) + rest(m.group(6));
            }
            default:
                return null;
        }
    }

    private static String rest(String query) {
        return query == null ? "" : query;
    }

    private static String format(String pattern, long at, TimeZone zone) {
        SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.ROOT);
        f.setTimeZone(zone);
        return f.format(new Date(at));
    }

    /** A catch-up source's placeholders, in the two spellings playlists use. */
    private static String fill(String source, long start, long end, long now, TimeZone zone) {
        long seconds = (end - start) / 1000, offset = (now - start) / 1000;
        Matcher m = SCALED.matcher(source);
        StringBuffer scaled = new StringBuffer();
        while (m.find()) {
            long value = m.group(1).equals("duration") ? seconds : offset;
            long by = Math.max(1, Long.parseLong(m.group(2)));
            m.appendReplacement(scaled, String.valueOf(value / by));
        }
        m.appendTail(scaled);
        return scaled.toString()
                .replace("{utcend}", String.valueOf(end / 1000)).replace("${end}", String.valueOf(end / 1000))
                .replace("{utc}", String.valueOf(start / 1000)).replace("${start}", String.valueOf(start / 1000))
                .replace("{lutc}", String.valueOf(now / 1000)).replace("${now}", String.valueOf(now / 1000))
                .replace("${timestamp}", String.valueOf(now / 1000))
                .replace("{duration}", String.valueOf(seconds)).replace("${duration}", String.valueOf(seconds))
                .replace("${offset}", String.valueOf(offset))
                .replace("{Y}", format("yyyy", start, zone)).replace("{m}", format("MM", start, zone))
                .replace("{d}", format("dd", start, zone)).replace("{H}", format("HH", start, zone))
                .replace("{M}", format("mm", start, zone)).replace("{S}", format("ss", start, zone));
    }
}
