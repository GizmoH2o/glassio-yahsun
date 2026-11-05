package com.glasslauncher.tv.live;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether TMDB's entry of the same name is the programme on the guide: BBC's "Breakfast" isn't the horror series called
 * that. The same name isn't enough for a little-known entry, and nothing is better than the wrong facts. Pure.
 */
final class LiveTmdbMatch {
    private LiveTmdbMatch() {}

    /** Enough people rated it: a programme on TV with its name is most likely this one. */
    private static final int WELL_KNOWN = 50;
    private static final Pattern SEASON = Pattern.compile("(?i)(?<![a-z0-9])S(\\d{1,2})\\s*[·.\\-]?\\s*E\\d");
    private static final Set<String> COMMON = new HashSet<>(Arrays.asList("about", "after", "their", "there", "these", "those",
            "which", "while", "where", "would", "could", "other", "with", "from", "that", "when", "into"));

    /** Well known, or it says what the guide says: two words in common besides the title's. */
    static boolean plausible(String title, int votes, String overview, String about) {
        if (votes >= WELL_KNOWN) return true;
        Set<String> theirs = words(overview);
        theirs.removeAll(words(title));
        Set<String> ours = words(about);
        ours.retainAll(theirs);
        return ours.size() >= 2;
    }

    /** A film longer than the time it's on for isn't this programme (its length unknown: it may be). */
    static boolean fitsSlot(int runtimeMinutes, long slotMinutes) {
        return runtimeMinutes <= 0 || slotMinutes <= 0 || runtimeMinutes <= slotMinutes * 1.1;
    }

    /** A programme in a numbered season isn't a film, nor a series with fewer seasons (one more may have just begun). */
    static boolean fitsSeason(boolean tv, int guideSeason, int seasons) {
        if (guideSeason <= 0) return true;
        return tv && (seasons <= 0 || guideSeason <= seasons + 1);
    }

    /** The season the guide gives ("S07 E75 …"), 0: none. */
    static int seasonIn(String text) {
        if (text == null) return 0;
        Matcher m = SEASON.matcher(text);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static Set<String> words(String text) {
        Set<String> out = new HashSet<>();
        if (text == null) return out;
        for (String w : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (w.length() >= 5 && !COMMON.contains(w)) out.add(w);
        }
        return out;
    }
}
