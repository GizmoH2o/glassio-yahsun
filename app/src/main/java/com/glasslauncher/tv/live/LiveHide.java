package com.glasslauncher.tv.live;

import android.content.Context;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The channels of the categories hidden in the settings: left out of what Glassio picks by itself (match alerts, the home
 * screen's rows, suggestions) and, with Hide everywhere, of search, All channels and History too (a typed number and a
 * reminder or recording set by hand still find them). One that's also in a category shown, in a group of your own or a favorite isn't hidden.
 */
final class LiveHide {
    private LiveHide() {}

    /** The channels only in categories `out` says: favorites (the first one) and All channels don't count. Pure. */
    static Set<Integer> away(List<Xtream.Category> categories, Predicate<String> out, Set<Integer> keep) {
        Set<Integer> away = new HashSet<>(), shown = new HashSet<>(keep);
        for (int i = 2; i < categories.size(); i++) {
            Xtream.Category cg = categories.get(i);
            Set<Integer> in = out.test(cg.id) ? away : shown;
            for (Xtream.Channel ch : cg.channels) in.add(ch.id);
        }
        away.removeAll(shown);
        return away;
    }

    /** Those of `l`; `locked`: the ones behind the PIN too. */
    static Set<Integer> away(Context c, Xtream.Lineup l, boolean locked) {
        Set<String> hidden = LiveOptions.hidden(c);
        if (l == null || hidden.isEmpty() && !locked) return Collections.emptySet();
        Set<Integer> keep = new HashSet<>();
        if (!hidden.isEmpty()) {
            for (Xtream.Channel f : Xtream.favorites(c)) keep(l, keep, f.id);
            for (String g : LiveGroups.names(c)) if (!hidden.contains("group:" + g)) for (int id : LiveGroups.ids(c, g)) keep(l, keep, id);
        }
        Set<Integer> away = away(l.categories, hidden::contains, keep);
        if (locked) away.addAll(away(l.categories, id -> LiveOptions.isLockedAnyProcess(c, id), Collections.emptySet()));
        return away;
    }

    /** As the list shows it: a merged channel for one of its sources. */
    private static void keep(Xtream.Lineup l, Set<Integer> keep, int id) {
        Xtream.Channel ch = l.find(id);
        keep.add(ch == null ? id : ch.id);
    }

    /** `l` with the hidden channels out of All channels (Hide everywhere); its categories, and finding by id, as they were. */
    static Xtream.Lineup apply(Context c, Xtream.Lineup l) {
        if (l != null && LiveOptions.hideEverywhere(c)) apply(l, away(c, l, false));
        return l;
    }

    /** Pure. */
    static void apply(Xtream.Lineup l, Set<Integer> away) {
        if (away.isEmpty() || l.categories.size() < 2) return;
        Xtream.Category all = l.categories.get(1);
        l.everyone = new java.util.ArrayList<>(all.channels);
        l.away = away;
        all.channels.removeIf(ch -> away.contains(ch.id));
    }

    /** `list` (the channels watched lately) without the hidden ones, with Hide everywhere. Background thread. */
    static List<Xtream.Channel> shown(Context c, List<Xtream.Channel> list) {
        Set<Integer> away = LiveOptions.hideEverywhere(c) ? away(c) : Collections.<Integer>emptySet();
        if (away.isEmpty()) return list;
        List<Xtream.Channel> out = new java.util.ArrayList<>();
        for (Xtream.Channel ch : list) if (!away.contains(ch.id)) out.add(ch);
        return out;
    }

    private static Set<Integer> known;
    private static long knownFor;

    /** What it changes with. */
    static long stamp(Context c) {
        return (Xtream.lineupStamp(c) * 31 + LiveOptions.hidden(c).hashCode() + (LiveOptions.hideEverywhere(c) ? 1 : 0)) * 31 * 31 + Xtream.favoritesStamp(c) * 31 + LiveGroups.stamp(c);
    }

    /** Those hidden of the saved list, kept while nothing changes. Background thread: it reads the list. */
    static synchronized Set<Integer> away(Context c) {
        if (LiveOptions.hidden(c).isEmpty()) return Collections.emptySet();
        long now = stamp(c);
        if (known == null || knownFor != now) {
            Xtream.Lineup l = Xtream.saved(c);
            if (l == null) return Collections.emptySet();
            known = away(c, l, false);
            knownFor = now;
        }
        return known;
    }
}
