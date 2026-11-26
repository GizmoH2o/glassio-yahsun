package com.glasslauncher.tv.live;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Whether two listings are one match, whatever each channel calls it and whenever its program starts. Pure. */
final class LiveMatchGroup {
    /** The two sides ("manchester united|sporting cp"). */
    final String pair;
    /** A youth side's, the women's: another match than the first team's. */
    final boolean youth, women;
    /** A show about it (a preview, Match Day Live). */
    final boolean show;
    final long start;

    /** Programs of one match start within this of each other (a channel's build-up, another's kick-off). */
    static final long NEAR = 3 * 3600_000L;
    /** A show about a match (its preview, its review) is on within this of it. */
    private static final long ABOUT = 9 * 3600_000L;
    /** Starting this close together, the same two sides are the same match, marked youth or women or not. */
    private static final long SAME = 15 * 60_000L;

    private static final Set<String> YOUTH = new HashSet<>(Arrays.asList("youth", "u16", "u17", "u18", "u19", "u20", "u21", "u23",
            "juvenil", "juniores", "primavera"));
    private static final Set<String> WOMEN = new HashSet<>(Arrays.asList("women", "womens", "wsl", "femenino", "femenina", "feminino",
            "feminina", "femminile", "feminine", "frauen"));

    /** The same two sides again this long after, the same day: shown again, whatever its LIVE mark says. */
    private static final long AGAIN = 16 * 3600_000L;
    /** Sports that do play twice in a day. */
    final boolean twice;
    private final String title;

    private LiveMatchGroup(String pair, boolean youth, boolean women, boolean show, boolean twice, String title, long start) {
        this.title = title;
        this.twice = twice;
        this.pair = pair;
        this.youth = youth;
        this.women = women;
        this.show = show;
        this.start = start;
    }

    /** The match `title` is of or about; null: no match. */
    static LiveMatchGroup of(String title, long start) {
        if (title == null) return null;
        boolean youth = false, women = title.contains("(W)") || title.contains("(F)");
        for (String w : Xtream.fold(title).split(" ")) {
            youth |= YOUTH.contains(w);
            women |= WOMEN.contains(w);
        }
        // The sides without those marks ("Manchester United U19" is Manchester United's).
        StringBuilder t = new StringBuilder();
        for (String w : title.split(" ")) {
            String f = Xtream.fold(w).toLowerCase(Locale.ROOT);
            if (!f.isEmpty() && !f.equals("youth") && (YOUTH.contains(f) || WOMEN.contains(f))) continue;
            if (t.length() > 0) t.append(' ');
            t.append(w);
        }
        String pair = LiveHome.sides(t.toString());
        return pair == null ? null : new LiveMatchGroup(pair, youth, women, LiveHome.aboutMatch(title),
                (" " + Xtream.fold(title) + " ").matches(".* (mlb|baseball|beisbol|softball) .*"), Xtream.fold(title), start);
    }

    /** Whether `o` is of the same match; `team` (null: not a team known) is the one followed, its main name. */
    boolean same(LiveMatchGroup o, String team) {
        if (o == null) return false;
        long apart = Math.abs(start - o.start);
        if (pair.equals(o.pair) && !show && !o.show && apart <= SAME) return true;
        if (apart > (show != o.show ? ABOUT : NEAR) || youth != o.youth || women != o.women) return false;
        return pair.equals(o.pair) || team != null && has(team) && o.has(team);      // a team plays one match at a time
    }

    /** Whether it's `o`'s match shown again later that day (under the same title: the same two sides' youth match isn't always marked). */
    boolean rerunOf(LiveMatchGroup o) {
        return o != null && !o.show && !twice && title.equals(o.title)
                && start > o.start + NEAR && start < o.start + AGAIN;
    }

    /**
     * Whether it's a match among `all` shown again under another title: the same two sides earlier that day, on many more
     * channels then (so the first team's match is never a rerun of a youth match nobody marked, on one or two).
     */
    boolean shownAgain(java.util.List<LiveMatchGroup> all) {
        if (twice || show) return false;
        for (LiveMatchGroup o : all) {
            if (o == null || o.show || !pair.equals(o.pair) || youth != o.youth || women != o.women || start <= o.start + NEAR || start >= o.start + AGAIN) continue;
            int then = o.count(all);
            if (then >= 3 && then >= 2 * count(all)) return true;
        }
        return false;
    }

    private int count(java.util.List<LiveMatchGroup> all) {
        int n = 0;
        for (LiveMatchGroup o : all) if (o != null && !o.show && pair.equals(o.pair) && same(o, null)) n++;
        return n;
    }

    private boolean has(String team) {
        return pair.startsWith(team + "|") || pair.endsWith("|" + team);
    }
}
