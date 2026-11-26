package com.glasslauncher.tv.live;

import android.content.Context;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Suggested favorite channels: the ones watched most, those in History, and those that show the teams followed. */
public final class LiveSuggest {
    private LiveSuggest() {}

    static final int MOST = 12, TEAM_MOST = 3;
    /** Watched this long at least: named with its hours. */
    static final int WATCHED = 20 * 60;

    public static final class One {
        public int id, num;
        public String name = "", logo = "", why = "";
    }

    /** A channel's name without its quality: its copies have the same. Pure. */
    static String copyKey(String name) {
        return Xtream.fold(name).replaceAll("\\b(uhd|fhd|hd|sd|4k|2k|hevc|h265|h264|1080p|720p|raw|backup|low latency)\\b", " ").replaceAll("[^\\p{L}\\p{N}+]+", " ").trim();
    }

    /**
     * Which channels, in order, with why ("time", "history", "team"): the most watched first, then History's, with up to
     * TEAM_MOST of the teams' among them; `skip`ped ones out. Pure.
     */
    static LinkedHashMap<Integer, String> pick(Map<Integer, Integer> seconds, List<Integer> history, List<Integer> team, Set<Integer> skip) {
        LinkedHashMap<Integer, String> out = new LinkedHashMap<>();
        List<Integer> byTime = new ArrayList<>(seconds.keySet());
        java.util.Collections.sort(byTime, (a, b) -> Integer.compare(seconds.get(b), seconds.get(a)));
        int teams = 0;
        for (int id : team) if (!skip.contains(id)) teams++;
        int room = MOST - Math.min(TEAM_MOST, teams);
        for (int id : byTime) if (seconds.get(id) >= WATCHED && out.size() < room && !skip.contains(id)) out.put(id, "time");
        for (int id : history) if (out.size() < room && !skip.contains(id) && !out.containsKey(id)) out.put(id, "history");
        int n = 0;
        for (int id : team) {
            if (n >= TEAM_MOST || out.size() >= MOST) break;
            if (skip.contains(id) || out.containsKey(id)) continue;
            out.put(id, "team");
            n++;
        }
        return out;
    }

    /** Background thread: it reads the list, the guide and what was watched. */
    public static List<One> get(Context c) {
        List<One> out = new ArrayList<>();
        if (!LiveOptions.suggestFavorites(c)) return out;
        Xtream.Lineup l = Xtream.saved(c);
        if (l == null) return out;
        if (LiveProfiles.kids(c)) l = LiveProfiles.kidsOnly(c, l);
        Map<Integer, Xtream.Channel> byId = new HashMap<>();
        Map<String, List<Xtream.Channel>> byEpg = new HashMap<>();
        Set<Integer> away = LiveHide.away(c, l, true);
        for (Xtream.Channel ch : l.all()) {
            if (away.contains(ch.id)) continue;
            byId.put(ch.id, ch);
            if (ch.epg == null || ch.epg.isEmpty()) continue;
            String e = ch.epg.toLowerCase(java.util.Locale.ROOT);
            List<Xtream.Channel> at = byEpg.get(e);
            if (at == null) byEpg.put(e, at = new ArrayList<>());
            at.add(ch);
        }
        Set<Integer> skip = new HashSet<>();
        Set<String> copies = new HashSet<>();
        for (Xtream.Channel f : Xtream.favorites(c)) {
            skip.add(f.id);
            copies.add(copyKey(f.name));
        }
        Map<Integer, Integer> seconds = new HashMap<>();
        for (Map.Entry<Integer, Integer> e : LiveWatchTime.totals(c).entrySet()) if (byId.containsKey(e.getKey())) seconds.put(e.getKey(), e.getValue());
        List<Integer> history = new ArrayList<>();
        for (Xtream.Channel r : Xtream.recent(c)) if (byId.containsKey(r.id)) history.add(r.id);
        // One of each channel's copies: the one watched longest, else the latest in History.
        List<Integer> order = new ArrayList<>(seconds.keySet());
        java.util.Collections.sort(order, (a, b) -> Integer.compare(seconds.get(b), seconds.get(a)));
        order.addAll(history);
        Set<Integer> kept = new HashSet<>();
        for (int id : order) {
            if (kept.contains(id) || skip.contains(id)) continue;
            if (LiveHealth.recent(c, id, 7 * 24 * 3600_000L) > 40 || !copies.add(copyKey(byId.get(id).name))) skip.add(id);
            else kept.add(id);
        }
        List<Integer> team = teams(c, byEpg, skip, copies, kept);
        for (Map.Entry<Integer, String> e : pick(seconds, history, team, skip).entrySet()) {
            Xtream.Channel ch = byId.get(e.getKey());
            One o = new One();
            o.id = ch.id;
            o.num = ch.num;
            o.name = ch.name;
            o.logo = ch.logo == null ? "" : ch.logo;
            o.why = e.getValue();
            if (o.why.equals("time")) {
                int min = seconds.get(ch.id) / 60;
                o.why = min >= 90 ? Math.round(min / 60f) + " h this month" : min + " min this month";
            } else {
                o.why = o.why.equals("team") ? "Shows your team" : "Watched lately";
            }
            out.add(o);
        }
        return out;
    }

    /** The channels that show the teams followed most in the next week's guide, the most matches first. */
    private static List<Integer> teams(Context c, Map<String, List<Xtream.Channel>> byEpg, Set<Integer> skip, Set<String> copies, Set<Integer> kept) {
        List<Integer> out = new ArrayList<>();
        if (!LiveOptions.fullGuide(c)) return out;
        Map<Integer, Integer> n = new HashMap<>();
        Map<Integer, Xtream.Channel> at = new HashMap<>();
        long now = System.currentTimeMillis();
        try {
            for (String term : LiveFollow.all(c)) {
                List<XmltvStore.Found> found = LiveNames.find(c, LiveNames.of(c, Xtream.fold(term)), now, now + 7 * 24 * 3600_000L, 300);
                for (XmltvStore.Found f : found) {
                    List<Xtream.Channel> chs = f.epg == null ? null : byEpg.get(f.epg.toLowerCase(java.util.Locale.ROOT));
                    if (chs == null) continue;
                    Xtream.Channel ch = chs.get(0);
                    Integer was = n.get(ch.id);
                    n.put(ch.id, was == null ? 1 : was + 1);
                    at.put(ch.id, ch);
                }
            }
        } catch (RuntimeException e) {
            AppLog.w("suggest: " + e.getMessage());
        }
        List<Integer> ids = new ArrayList<>(n.keySet());
        java.util.Collections.sort(ids, (a, b) -> Integer.compare(n.get(b), n.get(a)));
        for (int id : ids) {
            if (kept.contains(id) || skip.contains(id) || out.size() >= TEAM_MOST || !copies.add(copyKey(at.get(id).name))) continue;
            out.add(id);
        }
        return out;
    }
}
