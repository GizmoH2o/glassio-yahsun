package com.glasslauncher.tv.live;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** What Home's search lists from the guide and the recordings. Pure. */
final class LiveSearchRules {
    private LiveSearchRules() {}

    /**
     * The shows not over yet: what's on first, on `mine` (the guide channels of your favorites and recent ones) first
     * within that, then the soonest; one show on many channels at the same time once.
     */
    static List<XmltvStore.Found> shows(List<XmltvStore.Found> found, Set<String> mine, long now, int max) {
        List<XmltvStore.Found> list = new ArrayList<>();
        for (XmltvStore.Found f : found) if (f.end > now) list.add(f);
        Collections.sort(list, (a, b) -> {
            boolean onA = a.start <= now, onB = b.start <= now;
            if (onA != onB) return onA ? -1 : 1;
            boolean mineA = mine.contains(a.epg), mineB = mine.contains(b.epg);
            if (mineA != mineB) return mineA ? -1 : 1;
            return onA ? 0 : Long.compare(a.start, b.start);
        });
        Set<String> seen = new HashSet<>();
        List<XmltvStore.Found> out = new ArrayList<>();
        for (XmltvStore.Found f : list) {
            if (out.size() >= max) break;
            if (seen.add(plain(f.title) + "|" + f.start)) out.add(f);
        }
        return out;
    }

    private static String plain(String title) {
        return Xtream.fold(title.replace("[New]", "").replace("[Live]", "")).trim();
    }

    /** Whether `title` has all the words of `words`, in any order, whatever the case and accents. */
    static boolean matches(String title, String words) {
        String[] parts = Xtream.fold(words).trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) return false;
        String folded = Xtream.fold(title);
        for (String p : parts) if (!folded.contains(p)) return false;
        return true;
    }
}
