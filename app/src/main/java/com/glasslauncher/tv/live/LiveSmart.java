package com.glasslauncher.tv.live;

import android.content.Context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Smart channels: rows of the guide made from a rule (the teams you follow, new episodes tonight, your own words) rather
 * than one channel. Each show in the row says which real channel has it ({@link Xtream.Program#via}): OK there tunes to
 * that channel, so nothing is ever played from the rule itself.
 */
final class LiveSmart {
    private LiveSmart() {}

    /** The presets' numbers; your own rules come after them. */
    static final int FOLLOWING = 1, NEW = 2;
    private static final int FIRST_ID = -1000;
    /** How far ahead a row looks, and how many channels the new-episodes rule reads. */
    private static final long AHEAD = 24 * 3600_000L;
    private static final int NEW_CHANNELS = 40;
    private static final int FOUND_MAX = 400;
    /** Longer than this, an entry is a channel's filler rather than a show. */
    private static final long BLOCK = 6 * 3600_000L;

    /** A rule of your own: what it's called, which is also what it looks for. */
    static final class Rule {
        int id;
        String name = "";
    }

    static int idOf(int rule) {
        return FIRST_ID - rule;
    }

    static int ruleOf(int id) {
        return FIRST_ID - id;
    }

    /** `words` folded, as the guide's titles are searched; empty for none. */
    static String[] words(String words) {
        String folded = Xtream.fold(words).trim();
        return folded.isEmpty() ? new String[0] : folded.split("\\s+");
    }

    // MARK: - The rules

    static List<Rule> addRule(List<Rule> rules, String name) {
        List<Rule> out = new ArrayList<>(rules);
        String wanted = Xtream.fold(name).trim();
        if (wanted.isEmpty()) return out;
        int id = NEW;
        for (Rule r : rules) {
            if (Xtream.fold(r.name).trim().equals(wanted)) return out;
            id = Math.max(id, r.id);
        }
        Rule r = new Rule();
        r.id = id + 1;
        r.name = name.trim();
        out.add(r);
        return out;
    }

    static List<Rule> removeRule(List<Rule> rules, int id) {
        List<Rule> out = new ArrayList<>();
        for (Rule r : rules) if (r.id != id) out.add(r);
        return out;
    }

    static String write(List<Rule> rules) {
        org.json.JSONArray a = new org.json.JSONArray();
        try {
            for (Rule r : rules) a.put(new org.json.JSONObject().put("id", r.id).put("name", r.name));
        } catch (org.json.JSONException ignored) {
        }
        return a.toString();
    }

    static List<Rule> parse(String json) {
        List<Rule> out = new ArrayList<>();
        try {
            org.json.JSONArray a = new org.json.JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                Rule r = new Rule();
                r.id = a.getJSONObject(i).getInt("id");
                r.name = a.getJSONObject(i).getString("name");
                out.add(r);
            }
        } catch (org.json.JSONException ignored) {
        }
        return out;
    }

    static List<Rule> rules(Context c) {
        return parse(LiveOptions.smartRules(c));
    }

    static void add(Context c, String name) {
        LiveOptions.setSmartRules(c, write(addRule(rules(c), name)));
    }

    static void remove(Context c, int rule) {
        LiveOptions.setSmartRules(c, write(removeRule(rules(c), rule)));
    }

    // MARK: - The rows

    private static Xtream.Channel row(int rule, String name) {
        Xtream.Channel ch = new Xtream.Channel();
        ch.id = idOf(rule);
        ch.name = name;
        ch.key = Xtream.fold(name);
        ch.logo = ch.epg = "";
        ch.category = "smart";
        ch.smart = true;
        return ch;
    }

    /** The rows there are; none with Smart channels off, on a Kids profile, or without the full guide they read. */
    static List<Xtream.Channel> channels(Context c) {
        List<Xtream.Channel> out = new ArrayList<>();
        if (!LiveOptions.smartChannels(c) || !LiveOptions.fullGuide(c) || LiveProfiles.kids(c)) return out;
        if (LiveOptions.smartFollowing(c)) out.add(row(FOLLOWING, Ui.tr("Ao vivo dos times que você segue", "Live from teams you follow")));
        if (LiveOptions.smartNew(c)) out.add(row(NEW, Ui.tr("Episódios novos hoje", "New episodes tonight")));
        for (Rule r : rules(c)) out.add(row(r.id, r.name));
        return out;
    }

    /** What the row shows from now on. Blocking: it reads the guide. */
    static List<Xtream.Program> schedule(Context c, Xtream.Channel smart) {
        long now = System.currentTimeMillis();
        int rule = ruleOf(smart.id);
        List<Xtream.Program> found = new ArrayList<>();
        if (rule == FOLLOWING) {
            for (Object[] o : LiveHome.airings(c, LiveFollow.all(c), now, AHEAD, FOUND_MAX)) add(found, (Xtream.Program) o[1], (Xtream.Channel) o[0]);
        } else if (rule == NEW) {
            List<Xtream.Channel> own = new ArrayList<>(Xtream.favorites(c));
            own.addAll(Xtream.recent(c));
            List<String> read = new ArrayList<>();
            for (Xtream.Channel ch : own) {
                if (read.size() >= NEW_CHANNELS || ch.epg == null || ch.epg.isEmpty() || read.contains(ch.epg) || LiveOptions.isLockedAnyProcess(c, ch.category)) continue;
                read.add(ch.epg);
                List<Xtream.Program> list = XmltvStore.programs(c, ch.epg, now, now + AHEAD);
                if (list != null) for (Xtream.Program p : list) if (p.fresh) add(found, p, ch);
            }
        } else {
            String[] parts = words(smart.name);
            if (parts.length > 0) {
                List<String[]> phrases = new ArrayList<>();
                phrases.add(parts);
                Map<String, Xtream.Channel> byEpg = LiveHome.byEpg(c);
                for (XmltvStore.Found f : XmltvStore.findAny(c, phrases, now, now + AHEAD, FOUND_MAX)) {
                    Xtream.Channel ch = f.epg == null ? null : byEpg.get(f.epg.toLowerCase(Locale.ROOT));
                    if (ch == null || LiveOptions.isLockedAnyProcess(c, ch.category)) continue;
                    Xtream.Program p = new Xtream.Program();
                    p.title = f.title;
                    p.start = f.start;
                    p.end = f.end;
                    p.icon = f.icon == null ? "" : f.icon;
                    p.about = f.about == null ? "" : f.about;
                    Xtream.mark(p);
                    add(found, p, ch);
                }
            }
        }
        return lineUp(found, now);
    }

    private static void add(List<Xtream.Program> found, Xtream.Program p, Xtream.Channel ch) {
        p.via = ch;
        p.sub = ch.name;     // the tile's second line: where it's on
        found.add(p);
    }

    /**
     * One row from what was found: in time order and never two at once. What's on now counts as starting together, so a
     * live one goes ahead of a repeat that began earlier; a live show also goes ahead of another starting with it.
     */
    static List<Xtream.Program> lineUp(List<Xtream.Program> found, long now) {
        List<Xtream.Program> list = new ArrayList<>(), real = new ArrayList<>();
        for (Xtream.Program p : found) {
            if (p.end <= now) continue;
            list.add(p);
            if (p.end - p.start <= BLOCK) real.add(p);
        }
        if (!real.isEmpty()) list = real;     // a whole day of "Premier League Channel" would hide the matches
        Collections.sort(list, (a, b) -> {
            long x = Math.max(a.start, now), y = Math.max(b.start, now);
            if (x != y) return Long.compare(x, y);
            if (a.live != b.live) return a.live ? -1 : 1;
            return Long.compare(a.start, b.start);
        });
        List<Xtream.Program> out = new ArrayList<>();
        long free = Long.MIN_VALUE;
        for (Xtream.Program p : list) {
            if (Math.max(p.start, now) < free) continue;
            out.add(p);
            free = p.end;
        }
        return out;
    }

    static Xtream.Program onNow(List<Xtream.Program> row, long now) {
        if (row != null) for (Xtream.Program p : row) if (p.start <= now && p.end > now) return p;
        return null;
    }

    static Xtream.Program next(List<Xtream.Program> row, long now) {
        if (row != null) for (Xtream.Program p : row) if (p.start > now) return p;
        return null;
    }
}
