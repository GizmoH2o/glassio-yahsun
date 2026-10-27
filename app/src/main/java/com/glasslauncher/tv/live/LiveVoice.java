package com.glasslauncher.tv.live;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A channel asked for by name: "Play TLC on Glassio Yahsun" (Google's voice), Google TV's search, "TLC USA", "TLC US",
 * "channel 2340". The words around the name go ("watch", "on Glassio Yahsun"), a country at either end is read as one
 * (its code, as the country tags show it), then the channel whose name fits best: the name itself, then the name with
 * only a picture tag after it (TLC HD), then one starting with it (TLC West). Without a country, the ones you watch win.
 * Turned off (Live TV › Settings › Channels and categories), Glassio isn't offered to the TV for this at all.
 */
final class LiveVoice {
    private LiveVoice() {}

    /** What was said, read: the channel's name (folded), its country code ("" for none), or its number (-1: none). */
    static final class Query {
        String name = "", country = "";
        /** Everything said but the lead and trail words, no country taken out ("usa network"). */
        String whole = "";
        int number = -1;
    }

    /** Said before or after the name, never part of it. */
    private static final String[] LEAD = {"can you put on", "can you play", "i want to watch", "lets watch", "let s watch",
            "please play", "please put on", "switch over to", "switch to", "change to", "change the channel to", "turn on",
            "put on", "tune to", "tune in to", "go to", "show me", "open", "watch", "play", "start", "the channel", "channel"};
    private static final String[] TRAIL = {"on glassio yahsun", "in glassio yahsun", "on glassio", "in glassio",
            "on live tv", "in live tv", "on yahsun", "live tv", "live", "channel", "now", "please", "tv channel"};
    /** The quality tags a channel's name may end in, apart from its name. */
    private static final java.util.Set<String> PICTURE = new java.util.HashSet<>(java.util.Arrays.asList("hd", "fhd", "uhd",
            "sd", "4k", "8k", "hevc", "h265", "h264", "1080p", "720p", "1080", "720", "raw", "hq", "backup", "plus"));

    /** Countries said by name, folded, to their codes (each country's English name, and the usual other ways). */
    private static final Map<String, String> SPOKEN = new HashMap<>();

    static {
        for (String code : Locale.getISOCountries()) {
            String n = Xtream.fold(new Locale("", code).getDisplayCountry(Locale.ENGLISH));
            String c = LiveCountry.code(code, false);
            if (!n.isEmpty() && c != null) SPOKEN.put(n, c);
        }
        String[][] more = {{"usa", "US"}, {"us", "US"}, {"u s", "US"}, {"u s a", "US"}, {"america", "US"},
                {"united states", "US"}, {"united states of america", "US"}, {"the us", "US"}, {"the usa", "US"},
                {"uk", "UK"}, {"u k", "UK"}, {"britain", "UK"}, {"great britain", "UK"}, {"united kingdom", "UK"},
                {"england", "UK"}, {"the uk", "UK"}, {"gb", "UK"}, {"canada", "CA"}, {"canadian", "CA"},
                {"american", "US"}, {"british", "UK"}, {"jamaican", "JM"}, {"australian", "AU"}, {"irish", "IE"},
                {"mexican", "MX"}, {"uae", "AE"}, {"emirates", "AE"}, {"arabic", LiveCountry.ARABIC}, {"arab", LiveCountry.ARABIC}};
        for (String[] m : more) SPOKEN.put(m[0], m[1]);
    }

    /** What was said, read (it never throws: an empty name when nothing's left). */
    static Query parse(String said) {
        Query q = new Query();
        String s = Xtream.fold(said);
        boolean changed = true;
        while (changed) {       // "please watch the channel TLC on Glassio": every lead and trail word goes
            changed = false;
            for (String w : LEAD) if (s.startsWith(w + " ")) { s = s.substring(w.length() + 1); changed = true; }
            for (String w : TRAIL) if (s.endsWith(" " + w)) { s = s.substring(0, s.length() - w.length() - 1); changed = true; }
            if (s.startsWith("the ") && s.length() > 4) { s = s.substring(4); changed = true; }
        }
        q.whole = s.trim();
        // A number on its own: the channel number.
        if (s.matches("\\d{1,5}")) {
            q.number = Integer.parseInt(s);
            return q;
        }
        // A country at the end ("tlc usa", "tlc from the us", "tlc united states"), else at the start ("us tlc").
        String[] w = s.split(" ");
        for (int n = Math.min(4, w.length - 1); n >= 1 && q.country.isEmpty(); n--) {
            String tail = join(w, w.length - n, w.length);
            String code = SPOKEN.get(tail);
            if (code == null) continue;
            String rest = join(w, 0, w.length - n);
            if (rest.endsWith(" from")) rest = rest.substring(0, rest.length() - 5);
            else if (rest.endsWith(" from the")) rest = rest.substring(0, rest.length() - 9);
            if (rest.isEmpty()) continue;
            q.country = code;
            s = rest;
        }
        if (q.country.isEmpty()) {
            for (int n = Math.min(4, w.length - 1); n >= 1; n--) {
                String head = join(w, 0, n);
                String code = SPOKEN.get(head);
                if (code == null) continue;
                q.country = code;
                s = join(w, n, w.length);
                break;
            }
        }
        q.name = s.trim();
        return q;
    }

    /** "Add TLC to favourites": the channel said (folded) and whether it goes in or out. */
    static final class Favorite {
        final String channel;
        final boolean add;

        Favorite(String channel, boolean add) {
            this.channel = channel;
            this.add = add;
        }
    }

    private static final String FAVS = "(?:my |the )?(?:favorites|favourites|favorite|favourite|favs|favoritos|favoritas)";
    private static final java.util.regex.Pattern[] FAV_ADD = {
            java.util.regex.Pattern.compile("^(?:please )?(?:add|put|save) (.+) (?:to|in|into|on) " + FAVS + "(?: please)?$"),
            java.util.regex.Pattern.compile("^(?:please )?(?:favorite|favourite|like) (.+?)(?: please)?$"),
            java.util.regex.Pattern.compile("^(?:adicionar|adiciona|adicione|colocar|coloca|coloque|por|poe) (.+) (?:aos|nos|em|a|na) " + FAVS + "$")};
    private static final java.util.regex.Pattern[] FAV_REMOVE = {
            java.util.regex.Pattern.compile("^(?:please )?(?:remove|delete|take|drop) (.+?)(?: out)? (?:from|of) " + FAVS + "(?: please)?$"),
            java.util.regex.Pattern.compile("^(?:please )?(?:unfavorite|unfavourite|unlike) (.+?)(?: please)?$"),
            java.util.regex.Pattern.compile("^(?:tirar|tira|tire|remover|remova|remove) (.+) (?:dos|das|de) " + FAVS + "$")};

    /** What was said as a favorites command ("add TLC to my favourites", "remove 302 from favorites"), else null. */
    static Favorite parseFavorite(String said) {
        String s = Xtream.fold(said);
        for (int add = 0; add < 2; add++) {
            for (java.util.regex.Pattern p : add == 0 ? FAV_ADD : FAV_REMOVE) {
                java.util.regex.Matcher m = p.matcher(s);
                if (!m.matches()) continue;
                String ch = m.group(1).trim();
                if (ch.startsWith("channel ")) ch = ch.substring(8);
                if (ch.startsWith("the ") && ch.length() > 4) ch = ch.substring(4);
                if (!ch.isEmpty()) return new Favorite(ch, add == 0);
            }
        }
        return null;
    }

    private static String join(String[] w, int from, int to) {
        StringBuilder b = new StringBuilder();
        for (int i = from; i < to; i++) b.append(b.length() == 0 ? "" : " ").append(w[i]);
        return b.toString();
    }

    /**
     * How well channel `name` (as the provider writes it, its country tag already off) fits the name said: 100 the
     * same, 95 the same but a picture tag (TLC HD), 60 a name that starts with it (TLC West), 40 one that has it in
     * whole words, 0 none.
     */
    static int fit(String name, String said) {
        String n = Xtream.fold(name);
        if (n.isEmpty() || said.isEmpty()) return 0;
        if (n.equals(said)) return 100;
        if (said.replace(" ", "").equals(n.replace(" ", ""))) return 98;     // "t l c", "e news"
        List<String> words = new ArrayList<>(java.util.Arrays.asList(n.split(" ")));
        while (!words.isEmpty() && PICTURE.contains(words.get(words.size() - 1))) words.remove(words.size() - 1);
        String bare = String.join(" ", words);
        if (bare.equals(said)) return 95;
        if (n.startsWith(said + " ")) return 60;
        if ((" " + n + " ").contains(" " + said + " ")) return 40;
        return 0;
    }

    /**
     * The channel for `q` among `channels` (the lineup's, every one once), or null. `country` gives each channel's
     * country; `liked` scores the ones watched (favorites, lately watched) when the country doesn't decide.
     */
    static Xtream.Channel best(List<Xtream.Channel> channels, Query q, java.util.function.Function<Xtream.Channel, String> country,
                               java.util.function.ToIntFunction<Xtream.Channel> liked) {
        if (q.number >= 0) {
            for (Xtream.Channel ch : channels) if (ch.num == q.number) return ch;
            return null;
        }
        if (q.name.isEmpty()) return null;
        Xtream.Channel best = null;
        int bestScore = 0;
        for (Xtream.Channel ch : channels) {
            String cc = country.apply(ch);
            // The whole of what was said as the channel's whole name ("USA Network": not USA's "Network").
            int whole = q.country.isEmpty() ? 0 : fit(ch.name, q.whole);
            int score;
            if (whole >= 95) {
                score = whole * 10 + 300;
            } else {
                int f = fit(LiveCountry.split(ch.name).name, q.name);
                if (f == 0) continue;
                score = f * 10;
                if (!q.country.isEmpty()) {
                    if (cc.equals(q.country)) score += 300;
                    else if (!cc.isEmpty()) continue;       // another country's
                    else score -= 100;
                }
            }
            score += liked.applyAsInt(ch);
            if (best == null || score > bestScore || score == bestScore && ch.name.length() < best.name.length()) {
                best = ch;
                bestScore = score;
            }
        }
        return bestScore >= 400 ? best : null;
    }

    /** The voice entry and Google TV's search on or off, as the setting is (any process; runs as Glassio starts too). */
    static void apply(Context c) {
        boolean on = LiveOptions.voiceChannels(c);
        int state = on ? PackageManager.COMPONENT_ENABLED_STATE_DEFAULT : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        PackageManager pm = c.getPackageManager();
        for (Class<?> k : new Class<?>[] {LiveVoiceActivity.class, LiveSearchProvider.class}) {
            ComponentName cn = new ComponentName(c, k);
            try {
                if (pm.getComponentEnabledSetting(cn) != state) pm.setComponentEnabledSetting(cn, state, PackageManager.DONT_KILL_APP);
            } catch (RuntimeException e) {
                AppLog.w("live: voice on/off: " + e.getMessage());
            }
        }
    }

    // MARK: - Finding it in the saved list

    /** The saved list (a Kids profile: the children's channels only) and each channel's country; null when signed out. */
    static final class Catalog {
        final List<Xtream.Channel> channels = new ArrayList<>();
        final Map<String, String> categoryCountry;
        final java.util.Set<Integer> favorites = new java.util.HashSet<>();
        final Map<Integer, Integer> recent = new HashMap<>();
        final String usualCountry;

        Catalog(Xtream.Lineup l, List<Xtream.Channel> lately) {
            categoryCountry = LiveCountry.categories(l);
            java.util.Set<Integer> seen = new java.util.HashSet<>();
            for (Xtream.Channel ch : l.all()) if (seen.add(ch.id)) channels.add(ch);
            if (!l.categories.isEmpty()) for (Xtream.Channel ch : l.categories.get(0).channels) favorites.add(ch.id);
            for (int i = 0; i < lately.size() && i < 20; i++) recent.putIfAbsent(lately.get(i).id, 20 - i);
            Xtream.Channel last = lately.isEmpty() ? null : l.find(lately.get(0).id);
            usualCountry = last == null ? "" : LiveCountry.of(last, categoryCountry);
        }

        Xtream.Channel find(Query q) {
            return best(channels, q, ch -> LiveCountry.of(ch, categoryCountry), ch -> {
                int s = favorites.contains(ch.id) ? 60 : 0;
                Integer r = recent.get(ch.id);
                if (r != null) s += r * 3;
                if (!usualCountry.isEmpty() && usualCountry.equals(LiveCountry.of(ch, categoryCountry))) s += 40;
                return s;
            });
        }

        /** The best few for Google TV's search (the best first), each fitting at least as "starts with". */
        List<Xtream.Channel> some(Query q, int max) {
            List<Xtream.Channel> out = new ArrayList<>();
            Xtream.Channel first = find(q);
            if (first != null) out.add(first);
            if (q.name.isEmpty()) return out;
            for (Xtream.Channel ch : channels) {
                if (out.size() >= max) break;
                if (ch == first) continue;
                String cc = LiveCountry.of(ch, categoryCountry);
                if (!q.country.isEmpty() && !cc.isEmpty() && !cc.equals(q.country)) continue;
                if (fit(LiveCountry.split(ch.name).name, q.name) >= 60) out.add(ch);
            }
            return out;
        }

        String countryOf(Xtream.Channel ch) {
            return LiveCountry.of(ch, categoryCountry);
        }
    }

    private static Catalog cached;
    private static long cachedAt;

    /** The catalog, read again only when the saved list changed. Blocking (the first time reads the whole list). */
    static synchronized Catalog catalog(Context c) {
        long at = Xtream.listFile(c).lastModified();
        if (cached == null || cachedAt != at) {
            Xtream.Lineup l = Xtream.saved(c);
            if (l == null) return null;
            if (LiveProfiles.kids(c)) l = LiveProfiles.kidsOnly(l);
            cached = new Catalog(l, Xtream.recent(c));
            cachedAt = at;
        }
        return cached;
    }
}
