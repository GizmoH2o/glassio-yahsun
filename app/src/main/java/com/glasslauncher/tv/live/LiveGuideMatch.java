package com.glasslauncher.tv.live;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Which channels should have a guide, and a guide for one that has none. A wrong guide is worse than none, so a name
 * only counts when exactly one guide channel has it and it's long enough not to be a coincidence. Pure.
 */
final class LiveGuideMatch {
    private LiveGuideMatch() {}

    private static final int SHORTEST = 4;
    /** "DAZN US 70: NO EVENT", "(FLSP 171) | hockey: …", "F1 TV 01 | Offline": a numbered slot, its name the event. */
    private static final Pattern EVENT_SLOT = Pattern.compile("^\\(?[\\p{L}0-9+ .]*?\\b\\d{1,4}\\)?\\s*[:|]");

    /** The guide file's names ({id: name}) as {name's key: id}, for the ids that have shows; a shared name has none. */
    static Map<String, String> unique(Map<String, String> file, Set<String> guided) {
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, String> e : file.entrySet()) {
            if (!guided.contains(e.getKey())) continue;
            String key = LiveMerge.nameKey(e.getValue());
            if (key.length() < SHORTEST) continue;
            String had = out.get(key);
            out.put(key, had == null || had.equals(e.getKey()) ? e.getKey() : "");
        }
        return out;
    }

    /** The guide channel with this channel's name, or null. */
    static String find(String name, Map<String, String> names) {
        String id = names.get(LiveMerge.nameKey(name));
        return id == null || id.isEmpty() ? null : id;
    }

    /**
     * The guide channels ({id, name, source}) most like a channel's name, best first, at most `max`: the same name,
     * then one starting with the other, then the most words in common (its name's or its ID's). For choosing by hand.
     */
    static List<String[]> suggest(String name, List<String[]> all, int max) {
        LiveCountry.Split split = LiveCountry.split(name == null ? "" : name);
        final String want = LiveMerge.nameKey(split.name == null ? "" : split.name);
        final String country = split.code == null ? "" : split.code.toLowerCase(java.util.Locale.ROOT);
        List<String[]> out = new ArrayList<>();
        if (want.isEmpty()) return out;
        final Map<String[], Integer> score = new HashMap<>();
        for (String[] g : all) {
            LiveCountry.Split gs = LiveCountry.split(g[1]);
            String gName = gs.name == null ? g[1] : gs.name;
            String bare = g[0].replaceAll("\\.[a-z]{2}$", "");      // bbc1.uk: bbc1
            int s = Math.max(likeness(want, LiveMerge.nameKey(gName)), likeness(want, LiveMerge.nameKey(bare.replaceAll("[._\\-]", " "))));
            if (s >= 25 && !country.isEmpty() && sameCountry(country, g[0], gs.code)) s += 15;      // its own country's first
            if (s >= 25) {
                score.put(g, s);
                out.add(g);
            }
        }
        Collections.sort(out, (a, b) -> score.get(b) - score.get(a));
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    /** Whether a guide channel (by its ID's ending, "cnn.us", or its name's tag) is of `country` (lowercase). */
    private static boolean sameCountry(String country, String id, String nameCode) {
        if (nameCode != null && nameCode.equalsIgnoreCase(country)) return true;
        String uk = country.equals("gb") ? "uk" : country.equals("uk") ? "gb" : country;
        return id.endsWith("." + country) || id.endsWith("." + uk);
    }

    /** 0 to 100: how alike two names (as compared) are. */
    static int likeness(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        if (a.equals(b)) return 100;
        Set<String> wa = new HashSet<>(Arrays.asList(a.split(" "))), wb = new HashSet<>(Arrays.asList(b.split(" ")));
        Set<String> both = new HashSet<>(wa);
        both.retainAll(wb);
        Set<String> any = new HashSet<>(wa);
        any.addAll(wb);
        int words = any.isEmpty() ? 0 : 60 * both.size() / any.size();
        boolean starts = a.startsWith(b + " ") || b.startsWith(a + " ");
        return Math.min(99, words + (starts ? 30 : 0));
    }

    /** Whether a channel should have a guide: not radio, adult channels or event slots, which never list one. */
    static boolean expectsGuide(String category, String name) {
        if (category != null) {
            if (LiveOptions.looksAdult(category)) return false;
            for (String w : Xtream.fold(category).split(" ")) if (w.equals("radio")) return false;
        }
        return name == null || !EVENT_SLOT.matcher(name).find();
    }
}
