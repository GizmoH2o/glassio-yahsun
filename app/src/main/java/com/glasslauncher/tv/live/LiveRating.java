package com.glasslauncher.tv.live;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The age lock's decision (Live TV › Settings › Family › Lock shows rated, and Kids profiles' own): a show's rating is the
 * guide's, else TMDB's (asked in the background, LiveTmdb.rating); a show nobody rated plays unless the parents chose
 * "Shows with no rating: Need the PIN". Pure.
 */
final class LiveRating {
    private LiveRating() {}

    /** A rating still being asked of TMDB; none known (the guide has none, TMDB none or no TMDB token). */
    static final int ASKING = -2, NONE = -1;
    /** How long an unknown show's start waits for TMDB's answer. */
    static final long WAIT_MS = 3_000;

    /** Whether a show rated `rated` asks for the PIN under the lock at `age` (0: off). */
    static boolean locked(int age, int rated, boolean unratedNeedsPin) {
        if (age <= 0) return false;
        if (rated < 0) return unratedNeedsPin;
        return rated >= age;
    }

    /** Whether a show's start waits for its rating: the lock on and TMDB being asked. */
    static boolean waitFor(int age, int rated) {
        return age > 0 && rated == ASKING;
    }

    /** The countries whose ratings are taken, in turn: yours (the setting, else the TV's), then the English-speaking ones. */
    static String[] regions(String setting, String tvCountry) {
        Set<String> out = new LinkedHashSet<>();
        String mine = gb(setting.trim().isEmpty() ? tvCountry : setting);
        if (!mine.isEmpty()) out.add(mine);
        for (String r : new String[] {"GB", "US", "IE", "CA", "AU"}) out.add(r);
        return out.toArray(new String[0]);
    }

    private static String gb(String code) {
        String c = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        return c.equals("UK") ? "GB" : c;
    }

    /** The rating as the info bar says it ("12+"); "No rating" only with the lock on; "" while asking. */
    static String label(int rated, boolean lockOn) {
        if (rated == ASKING) return "";
        if (rated == NONE) return lockOn ? Ui.tr("Sem classificação", "No rating") : "";
        if (rated == 0) return Ui.tr("Livre", "All ages");
        return rated + "+";
    }
}
