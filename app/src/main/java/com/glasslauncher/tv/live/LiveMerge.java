package com.glasslauncher.tv.live;

import android.content.Context;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Similar channels as one: in a category, "BBC One", "BBC One HD", "UK: BBC One FHD" and "BBC One HEVC" are the same
 * channel in other qualities, so Live TV shows one BBC One with those as its sources, the best picture first (the same
 * name in another category is another feed, and stays apart). What plays is the best
 * the connection carries (its speed, measured while watching, is kept in a small file); catch-up and the guide come
 * from the source that has them. A real difference stays apart ("BBC One London", "ITV +1"), and so does a channel the
 * person keeps separate.
 */
final class LiveMerge {
    private LiveMerge() {}

    /** The picture's tiers: 4 (4K) to 1 (SD); a channel without a tag counts as HD. */
    static final int SD = 1, HD = 2, FHD = 3, UHD = 4;

    /** What a source needs (kbit/s) by tier; HEVC about 60% of that. */
    private static final int[] NEED = {0, 2500, 5000, 8000, 20000};

    // The tags that tell a quality or a codec, not the channel (as the key's words).
    private static final Set<String> TAGS = new HashSet<>(Arrays.asList(
            "4k", "8k", "uhd", "fhd", "hd", "sd", "hevc", "h265", "h264", "x265", "x264", "avc",
            "2160p", "2160", "1080p", "1080i", "1080", "720p", "720", "576p", "576i", "576", "480p", "480",
            "50fps", "60fps", "fps", "hdr", "raw", "hq", "lq", "hb", "lb", "lbw", "backup", "bk"));

    // A country code before the name: "UK: ", "UK | ", "|UK| ", "[UK] ", "UK - ".
    private static final java.util.regex.Pattern PREFIX =
            java.util.regex.Pattern.compile("^\\s*[|\\[(]?\\s*([A-Za-z]{2,3})\\s*[|\\]):\\-]\\s*(?=\\S)");
    // The same tags in a name as it shows ("BBC One HD (1080p)" → "BBC One").
    private static final java.util.regex.Pattern SHOWN_TAGS = java.util.regex.Pattern.compile(
            "(?i)(?<![\\p{L}\\p{N}])(4k|8k|uhd|fhd|full ?hd|hd|sd|hevc|h\\.?26[45]|x26[45]|avc|(?:2160|1080|720|576|480)[pi]?"
                    + "|[56]0 ?fps|hdr|raw|hq|lq|hb|lb|backup)(?![\\p{L}\\p{N}])");
    private static final java.util.regex.Pattern EMPTY_BRACKETS = java.util.regex.Pattern.compile("[(\\[{]\\s*[)\\]}]");
    private static final java.util.regex.Pattern LOOSE_ENDS = java.util.regex.Pattern.compile("^[\\s:\\-·]+|[\\s|:\\-·]+$");

    /** A name read apart: the country code (or ""), the rest as compared, its tier and whether it's HEVC. */
    private static final class Name {
        String prefix = "", rest = "";
        int quality = HD;
        boolean hevc;
    }

    private static Name read(String name) {
        Name n = new Name();
        String s = name == null ? "" : name;
        boolean ascii = true;
        for (int i = 0; i < s.length() && ascii; i++) ascii = s.charAt(i) < 128;
        // Superscript tags (ᴴᴰ, ᵁᴴᴰ) are letters again, accents go.
        if (!ascii) s = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKD).replaceAll("\\p{M}+", "");
        java.util.regex.Matcher m = PREFIX.matcher(s);
        String body = s;
        if (m.lookingAt()) {
            n.prefix = m.group(1).toLowerCase(java.util.Locale.ROOT);
            body = s.substring(m.end());
        }
        n.rest = words(body, n);
        if (n.rest.isEmpty() && !n.prefix.isEmpty()) {     // "TVJ - HD": that was the name, not a country
            n.prefix = "";
            n.rest = words(s, n);
        }
        return n;
    }

    /** The words that name the channel, lowercase ("+" kept: "ITV +1" isn't ITV); the tags set the tier on `n`. */
    private static String words(String s, Name n) {
        StringBuilder out = new StringBuilder(s.length());
        int max = 0;
        boolean hevc = false;
        String last = "";
        int i = 0, len = s.length();
        while (i < len) {
            while (i < len && !word(s.charAt(i))) i++;
            int start = i;
            while (i < len && word(s.charAt(i))) i++;
            if (start == i) break;
            String w = s.substring(start, i).toLowerCase(java.util.Locale.ROOT);
            int q = tier(w, last);
            if (w.equals("hevc") || w.equals("h265") || w.equals("x265") || w.equals("265") && last.equals("h")) hevc = true;
            boolean tag = TAGS.contains(w) || (w.equals("265") || w.equals("264")) && last.equals("h") || w.equals("hd") && last.equals("full");
            if (tag) {
                // "H 265", "Full HD": the word before was part of the tag
                if ((last.equals("h") || last.equals("full")) && out.length() >= last.length()
                        && out.substring(out.length() - last.length()).equals(last)) {
                    out.setLength(Math.max(0, out.length() - last.length() - 1));
                }
                max = Math.max(max, q);
            } else {
                if (out.length() > 0) out.append(' ');
                out.append(w);
            }
            last = w;
        }
        n.quality = max > 0 ? max : HD;
        n.hevc = hevc;
        return out.toString();
    }

    private static boolean word(char c) {
        return Character.isLetterOrDigit(c) || c == '+';
    }

    private static int tier(String w, String last) {
        switch (w) {
            case "4k": case "8k": case "uhd": case "2160p": case "2160": return UHD;
            case "fhd": case "1080p": case "1080i": case "1080": return FHD;
            case "hd": return last.equals("full") ? FHD : HD;
            case "720p": case "720": case "hq": return HD;
            case "sd": case "576p": case "576i": case "576": case "480p": case "480": case "lq": case "lbw": return SD;
            default: return 0;
        }
    }

    /** The name as it shows, without its quality tags ("UK: BBC One FHD" → "UK: BBC One"). */
    private static String shown(String name) {
        String s = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFKC);
        s = SHOWN_TAGS.matcher(s).replaceAll(" ");
        s = EMPTY_BRACKETS.matcher(s).replaceAll(" ");
        s = s.replaceAll("\\s{2,}", " ");
        s = LOOSE_ENDS.matcher(s).replaceAll("");
        return s.isEmpty() ? name : s;
    }

    private static Boolean hevcDecoder;

    /** Whether the TV decodes HEVC (then HEVC sources come first in a tier: the same picture for less). */
    static synchronized boolean hevcOk() {
        if (hevcDecoder == null) {
            boolean ok = false;
            try {
                for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                    if (info.isEncoder()) continue;
                    for (String type : info.getSupportedTypes()) ok |= type.equalsIgnoreCase("video/hevc");
                }
            } catch (RuntimeException ignored) {
            }
            hevcDecoder = ok;
        }
        return hevcDecoder;
    }

    /**
     * Merges the lineup's similar channels within each of the provider's categories (All channels and the category
     * show one for each), unless their group is in `separate` (kept apart: its channels then carry the group, to be
     * merged again). Returns every provider channel's id to the channel that shows it.
     */
    static Map<Integer, Xtream.Channel> apply(Xtream.Lineup l, Set<String> separate) {
        List<Xtream.Channel> every = l.all();
        Map<Integer, Xtream.Channel> owner = new HashMap<>();
        Map<Xtream.Channel, String> prefixOf = new HashMap<>();
        Map<String, List<Xtream.Channel>> byRest = new LinkedHashMap<>();
        for (Xtream.Channel ch : every) {
            owner.put(ch.id, ch);
            Name n = read(ch.name);
            ch.quality = n.quality;
            ch.hevc = n.hevc;
            if (n.rest.isEmpty()) continue;
            prefixOf.put(ch, n.prefix);
            // Only within the provider's category: the same name in another category is another feed.
            String key = ch.category + "/" + n.rest;
            List<Xtream.Channel> g = byRest.get(key);
            if (g == null) byRest.put(key, g = new ArrayList<>());
            g.add(ch);
        }
        boolean hevc = hevcOk();
        Map<Xtream.Channel, Xtream.Channel> shownAs = new HashMap<>();
        int merged = 0, sources = 0;
        StringBuilder some = new StringBuilder();
        for (Map.Entry<String, List<Xtream.Channel>> e : byRest.entrySet()) {
            List<Xtream.Channel> list = e.getValue();
            if (list.size() < 2) continue;
            // Two countries' channels of the same name stay apart ("US: Fox", "UK: Fox"); those without a code go with the first.
            Map<String, List<Xtream.Channel>> byPrefix = new LinkedHashMap<>();
            for (Xtream.Channel ch : list) {
                String p = prefixOf.get(ch);
                if (!p.isEmpty() && !byPrefix.containsKey(p)) byPrefix.put(p, new ArrayList<>());
            }
            if (byPrefix.size() <= 1) {
                byPrefix.clear();
                byPrefix.put("", list);
            } else {
                String first = byPrefix.keySet().iterator().next();
                for (Xtream.Channel ch : list) {
                    String p = prefixOf.get(ch);
                    byPrefix.get(p.isEmpty() ? first : p).add(ch);
                }
            }
            for (Map.Entry<String, List<Xtream.Channel>> g : byPrefix.entrySet()) {
                List<Xtream.Channel> members = g.getValue();
                if (members.size() < 2) continue;
                String group = g.getKey().isEmpty() ? e.getKey() : g.getKey() + ":" + e.getKey();
                if (separate.contains(group)) {
                    for (Xtream.Channel ch : members) ch.group = group;
                    continue;
                }
                Xtream.Channel lead = lead(members, group, hevc);
                for (Xtream.Channel ch : members) {
                    shownAs.put(ch, lead);
                    owner.put(ch.id, lead);
                }
                if (merged++ < 6) some.append(some.length() == 0 ? "" : ", ").append(lead.name).append(" (").append(members.size()).append(')');
                sources += members.size();
            }
        }
        if (merged == 0) return owner;
        // Each list with the merged channel where its first source was, once.
        for (int i = 1; i < l.categories.size(); i++) {
            List<Xtream.Channel> list = l.categories.get(i).channels;
            List<Xtream.Channel> out = new ArrayList<>(list.size());
            Set<Xtream.Channel> seen = new HashSet<>();
            for (Xtream.Channel ch : list) {
                Xtream.Channel s = shownAs.get(ch);
                if (s == null) out.add(ch);
                else if (seen.add(s)) out.add(s);
            }
            list.clear();
            list.addAll(out);
        }
        AppLog.w("live: " + sources + " channels merged into " + merged + ": " + some + (hevc ? "" : "; no HEVC decoder"));
        return owner;
    }

    /** The merged channel: its sources best first; its id, catch-up and guide from the source with the most catch-up. */
    private static Xtream.Channel lead(List<Xtream.Channel> members, String group, boolean hevc) {
        List<Xtream.Channel> src = new ArrayList<>(members);
        java.util.Collections.sort(src, (a, b) -> Integer.compare(rank(b, hevc), rank(a, hevc)));   // stable: the provider's order after
        Xtream.Channel anchor = src.get(0), shortest = members.get(0);
        for (Xtream.Channel s : src) if (s.archive > anchor.archive) anchor = s;
        for (Xtream.Channel s : members) if (s.name.length() < shortest.name.length()) shortest = s;
        Xtream.Channel lead = new Xtream.Channel();
        lead.id = anchor.id;
        lead.num = members.get(0).num;
        lead.name = shown(shortest.name);
        lead.key = Xtream.fold(lead.name);
        lead.archive = anchor.archive;
        lead.category = members.get(0).category;
        lead.logo = anchor.logo;
        for (Xtream.Channel s : src) if (lead.logo == null || lead.logo.isEmpty()) lead.logo = s.logo;
        lead.epg = anchor.epg;
        for (Xtream.Channel s : src) if (lead.epg == null || lead.epg.isEmpty()) lead.epg = s.epg;
        lead.quality = src.get(0).quality;
        lead.hevc = src.get(0).hevc;
        lead.group = group;
        lead.sources = src;
        return lead;
    }

    /** Better first: the tier, then HEVC when the TV decodes it (else HEVC last). */
    private static int rank(Xtream.Channel s, boolean hevc) {
        boolean h = isHevc(s);
        return tier(s) * 4 + (h ? (hevc ? 1 : -8) : 0);
    }

    /** A source's tier: from its picture once it played (many providers don't tag the names), else from its name. */
    static int tier(Xtream.Channel s) {
        int seen = seen(s.id);
        if (seen <= 0) return s.quality;
        int h = seen / 10;
        return h >= 1800 ? UHD : h >= 1000 ? FHD : h >= 700 ? HD : SD;
    }

    private static boolean isHevc(Xtream.Channel s) {
        int seen = seen(s.id);
        return seen > 0 ? seen % 10 == 1 : s.hevc;
    }

    /** What a source needs from the connection, kbit/s. */
    static int need(Xtream.Channel s) {
        int n = NEED[Math.max(SD, Math.min(UHD, tier(s)))];
        return isHevc(s) ? n * 6 / 10 : n;
    }

    /** "1080p · HEVC" (as it played), or "FHD" (from the name, not played yet): on the Source button and its list. */
    static String label(Xtream.Channel s) {
        int seen = seen(s.id);
        String t = seen > 0 ? (seen / 10 >= 1800 ? "4K" : seen / 10 + "p") : short_(s);
        return isHevc(s) ? t + " · HEVC" : t;
    }

    /** The tier in a few letters (the Source button): 4K, FHD, HD, SD. */
    static String short_(Xtream.Channel s) {
        int t = tier(s);
        return t == UHD ? "4K" : t == FHD ? "FHD" : t == SD ? "SD" : "HD";
    }

    /** The best first, by what's known now (what played may have changed the order since the list was made). */
    private static List<Xtream.Channel> ranked(Xtream.Channel ch) {
        List<Xtream.Channel> out = new ArrayList<>(ch.sources);
        final boolean hevc = hevcOk();
        java.util.Collections.sort(out, (a, b) -> Integer.compare(rank(b, hevc), rank(a, hevc)));
        return out;
    }

    /**
     * The source of `ch` to play: the best one the connection carries (all of them while its speed isn't known yet),
     * else the lightest; on a past program only those with catch-up. Not those in `skip` (they failed); null when none
     * is left. A channel on its own plays itself.
     */
    static Xtream.Channel pick(Context c, Xtream.Channel ch, boolean past, Set<Integer> skip) {
        if (ch.sources == null) return skip.contains(ch.id) ? null : ch;
        seen(c);
        int speed = speed(c);
        Xtream.Channel best = null, light = null;
        for (Xtream.Channel s : ranked(ch)) {
            if (skip.contains(s.id) || past && s.archive <= 0) continue;
            if (light == null || need(s) < need(light)) light = s;
            if (best == null && (speed <= 0 || need(s) <= speed * 8 / 10)) best = s;
        }
        return best != null ? best : light;
    }

    /** The source after `now` that needs less from the connection (stalls step down to it), or null. */
    static Xtream.Channel lighter(Xtream.Channel ch, Xtream.Channel now, boolean past, Set<Integer> skip) {
        if (ch.sources == null || now == null) return null;
        Xtream.Channel out = null;
        for (Xtream.Channel s : ranked(ch)) {
            if (s.id == now.id || skip.contains(s.id) || past && s.archive <= 0 || need(s) >= need(now)) continue;
            if (out == null || need(s) > need(out)) out = s;     // the best of the lighter ones
        }
        return out;
    }

    /** The sources in the Source list: the best first, as known now. */
    static List<Xtream.Channel> inOrder(Xtream.Channel ch) {
        return ch.sources == null ? null : ranked(ch);
    }

    // MARK: - What each source really is (its picture's height and codec once it played)

    private static Map<Integer, Integer> seen;

    /** height * 10 + (1 when HEVC), 0 when not played yet. */
    private static synchronized int seen(int id) {
        if (seen == null) return 0;
        Integer v = seen.get(id);
        return v == null ? 0 : v;
    }

    /** Reads what's known (once per process). */
    private static synchronized void seen(Context c) {
        if (seen != null) return;
        seen = new HashMap<>();
        File f = seenFile(c);
        if (!f.exists()) return;
        try (FileInputStream in = new FileInputStream(f)) {
            org.json.JSONObject o = new org.json.JSONObject(Xtream.read(in));
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                seen.put(Integer.parseInt(k), o.optInt(k));
            }
        } catch (IOException | org.json.JSONException | NumberFormatException ignored) {
        }
    }

    /** Source `id` played at `height` lines (HEVC or not): kept, and the order follows. */
    static synchronized void played(Context c, int id, int height, boolean hevc) {
        if (height <= 0) return;
        seen(c);
        int v = height * 10 + (hevc ? 1 : 0);
        Integer was = seen.put(id, v);
        if (was != null && was == v) return;
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            for (Map.Entry<Integer, Integer> e : seen.entrySet()) o.put(String.valueOf(e.getKey()), (int) e.getValue());
        } catch (org.json.JSONException ignored) {
        }
        final String text = o.toString();
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                Xtream.write(seenFile(app), text);
            } catch (IOException ignored) {
            }
        }).start();
    }

    // MARK: - The version picked for each channel

    private static Map<String, Integer> preferred;

    private static String keyOf(Xtream.Channel ch) {
        return ch.group != null ? "g:" + ch.group : "c:" + ch.id;
    }

    /** The source the person picked for channel `ch` (its SD, HD or FHD version), kept for next time; -1 for none. */
    static synchronized int preferred(Context c, Xtream.Channel ch) {
        if (ch == null || ch.sources == null) return -1;
        if (preferred == null) {
            preferred = new HashMap<>();
            File f = preferredFile(c);
            if (f.exists()) {
                try (FileInputStream in = new FileInputStream(f)) {
                    org.json.JSONObject o = new org.json.JSONObject(Xtream.read(in));
                    for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                        String k = it.next();
                        preferred.put(k, o.optInt(k));
                    }
                } catch (IOException | org.json.JSONException ignored) {
                }
            }
        }
        Integer id = preferred.get(keyOf(ch));
        if (id == null) return -1;
        for (Xtream.Channel s : ch.sources) if (s.id == id) return id;
        return -1;      // that version is gone from the list
    }

    /** Remembers source `id` for channel `ch` (-1: Automatic again). */
    static synchronized void prefer(Context c, Xtream.Channel ch, int id) {
        if (ch == null || ch.sources == null) return;
        preferred(c, ch);       // read first
        if (id < 0) preferred.remove(keyOf(ch));
        else preferred.put(keyOf(ch), id);
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            for (Map.Entry<String, Integer> e : preferred.entrySet()) o.put(e.getKey(), (int) e.getValue());
        } catch (org.json.JSONException ignored) {
        }
        final String text = o.toString();
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                Xtream.write(preferredFile(app), text);
            } catch (IOException ignored) {
            }
        }).start();
    }

    private static File preferredFile(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), "live-preferred.json");
    }

    private static File seenFile(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), "live-sources.json");
    }

    // MARK: - The connection's speed

    private static int speedKbps = -1;

    /** Another playlist: what's known is read again (its own files). */
    static synchronized void forget() {
        preferred = null;       // another playlist: its own picks
        seen = null;
        speedKbps = -1;
    }

    /** The connection's speed as last measured, kbit/s (0: not yet). Read once per process. */
    static synchronized int speed(Context c) {
        if (speedKbps < 0) {
            speedKbps = 0;
            File f = speedFile(c);
            if (f.exists()) {
                try (FileInputStream in = new FileInputStream(f)) {
                    speedKbps = Math.max(0, Integer.parseInt(Xtream.read(in).trim()));
                } catch (IOException | NumberFormatException ignored) {
                }
            }
        }
        return speedKbps;
    }

    /** A fast transfer seen (a stream's first burst, a past program downloading): the speed goes up toward it. */
    static synchronized void measured(Context c, int kbps) {
        int now = speed(c);
        if (kbps <= now) return;     // a live stream comes at its own pace: a slower sample says nothing
        save(c, now <= 0 ? kbps : (now * 7 + kbps * 3) / 10);
    }

    /** It stalled at `kbps`: the speed is below that. */
    static synchronized void slower(Context c, int kbps) {
        int now = speed(c);
        if (now <= 0 || now > kbps) save(c, kbps);
    }

    private static void save(Context c, int kbps) {
        speedKbps = kbps;
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                Xtream.write(speedFile(app), String.valueOf(kbps));
            } catch (IOException ignored) {
            }
        }).start();
    }

    private static File speedFile(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), "live-speed.txt");
    }
}
