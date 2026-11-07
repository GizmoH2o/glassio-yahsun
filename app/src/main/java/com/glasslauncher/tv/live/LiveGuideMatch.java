package com.glasslauncher.tv.live;

import java.util.HashMap;
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

    /** Whether a channel should have a guide: not radio, adult channels or event slots, which never list one. */
    static boolean expectsGuide(String category, String name) {
        if (category != null) {
            if (LiveOptions.looksAdult(category)) return false;
            for (String w : Xtream.fold(category).split(" ")) if (w.equals("radio")) return false;
        }
        return name == null || !EVENT_SLOT.matcher(name).find();
    }
}
