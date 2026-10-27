package com.glasslauncher.tv.live;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Live TV from an IPTV provider's Xtream Codes API: signing in (server, username, password), the categories and
 * channels, and each channel's stream address. The login stays in this app's private storage, in the :live process,
 * and is never logged (stream addresses carry it too). The channel list is saved, so Live TV opens on it right away
 * and refreshes in the background.
 */
final class Xtream {
    private Xtream() {}

    /** Refreshed in the background when older than this. */
    static final long LIST_EVERY = 12 * 3600_000L;

    static final class Channel {
        int id, num;
        /** Days of catch-up the provider keeps (its archive, tv_archive_duration); 0: none. */
        int archive;
        String name, logo, epg, category;
        /** The provider's own logo, kept when another source's is shown instead (LivePublicLogos, the guide's). */
        String providerLogo;
        /** The name as search and sorting compare it (fold): made with the list, off the screen's thread. */
        String key = "";
        /** Its picture's tier (LiveMerge.SD to UHD, from the name's tags) and whether it's HEVC. */
        int quality = LiveMerge.HD;
        boolean hevc;
        /** A merged channel's group (LiveMerge), or a channel's kept apart from its group; null for the others. */
        String group;
        /** A merged channel's sources (each one of the provider's channels), the best first; null for the others. */
        List<Channel> sources;
        /** An M3U channel's address, a MAG channel's command; "" for Xtream's (made from the login). */
        String url = "";
    }

    static final class Category {
        String id, name;
        final List<Channel> channels = new ArrayList<>();
    }

    /** Favorites, "All channels", then the provider's categories in its order, each with its channels. */
    static final class Lineup {
        final List<Category> categories = new ArrayList<>();
        long at;
        /** The saved list's version: older ones lack what came later (the catch-up days) and are fetched again. */
        int version;
        String format = "ts";   // the output the provider allows: "ts" (MPEG-TS) or "m3u8" (HLS)

        /** Every channel ("All channels", right after Favorites). */
        List<Channel> all() {
            return categories.size() > 1 ? categories.get(1).channels : new ArrayList<>();
        }

        /** Each of the provider's channel ids to the channel that shows it (a merged one for its sources). */
        final Map<Integer, Channel> byId = new HashMap<>();

        /** The channel showing provider channel `id` (a favorite or a recent one saved before merging finds it too), or null. */
        Channel find(int id) {
            return byId.get(id);
        }
    }

    /** The saved list's current version: 2 added the catch-up days. */
    static final int LIST_VERSION = 2;

    /** The favorites' category (first in the list, even while empty). */
    static final String FAVORITES = "fav";

    // The login and the favorites live in small private files rather than SharedPreferences: the home screen (:home)
    // reads them too, and SharedPreferences are cached per process (a sign-in in :live wouldn't reach it).
    /** What kind of playlist the login is: Xtream Codes, an M3U link, or a MAG (Stalker) portal. */
    static final String XTREAM = "xtream", M3U = "m3u", MAG = "mag";

    private static final class Login {
        /** M3U: server is the playlist's link, epg its guide's. MAG: server is the portal's API, mac its MAC. */
        String kind = XTREAM, server = "", user = "", pass = "", format = "ts", zone = "", epg = "", mac = "";
        /** How many streams the account may play at once (user_info.max_connections); 0: not known yet. */
        int max;
    }

    private static Login login(Context c) {
        Login l = new Login();
        File f = loginFile(c);
        if (!f.exists()) migrate(c);
        if (!f.exists()) return l;
        try (FileInputStream in = new FileInputStream(f)) {
            JSONObject o = new JSONObject(read(in));
            l.server = o.optString("server");
            l.user = o.optString("user");
            l.pass = o.optString("pass");
            l.format = o.optString("format", "ts");
            l.zone = o.optString("zone");
            l.max = o.optInt("max");
            l.kind = o.optString("kind", XTREAM);
            l.epg = o.optString("epg");
            l.mac = o.optString("mac");
        } catch (IOException | JSONException e) {
            AppLog.w("live: login unreadable");
        }
        return l;
    }

    private static void saveLogin(Context c, Login l) throws IOException {
        try {
            write(loginFile(c), new JSONObject().put("server", l.server).put("user", l.user).put("pass", l.pass)
                    .put("format", l.format).put("zone", l.zone).put("max", l.max).put("kind", l.kind).put("epg", l.epg)
                    .put("mac", l.mac).toString());
        } catch (JSONException e) {
            throw new IOException(e.getMessage());
        }
    }

    /** A login saved by the first version (in SharedPreferences) moves to the file once. */
    private static void migrate(Context c) {
        SharedPreferences old = c.getSharedPreferences("live", Context.MODE_PRIVATE);
        if (old.getString("server", "").isEmpty()) return;
        Login l = new Login();
        l.server = old.getString("server", "");
        l.user = old.getString("user", "");
        l.pass = old.getString("pass", "");
        l.format = old.getString("format", "ts");
        try {
            saveLogin(c, l);
            old.edit().clear().commit();
        } catch (IOException ignored) {
        }
    }

    static boolean signedIn(Context c) {
        Login l = login(c);
        if (l.kind.equals(M3U)) return !l.server.isEmpty();
        if (l.kind.equals(MAG)) return !l.server.isEmpty() && !l.mac.isEmpty();
        return !l.server.isEmpty() && !l.user.isEmpty();
    }

    static String kind(Context c) { return login(c).kind; }

    /** M3U: the guide link typed; MAG: the MAC; Xtream: "" (the sign-in screen's second field). */
    static String second(Context c) {
        Login l = login(c);
        return l.kind.equals(M3U) ? l.epg : l.kind.equals(MAG) ? l.mac : l.user;
    }

    /** Signed in, with a channel list: Live TV opens straight on the channels (the home screen's Live TV button). */
    static boolean ready(Context c) {
        return loginFile(c).exists() && listFile(c).exists();
    }

    static String server(Context c) { return login(c).server; }
    /** The account as the lists name it: Xtream's user, "M3U", or the MAG's MAC. */
    static String user(Context c) {
        Login l = login(c);
        return l.kind.equals(M3U) ? "M3U" : l.kind.equals(MAG) ? "MAG " + l.mac : l.user;
    }

    static void signOut(Context c) {
        //noinspection ResultOfMethodCallIgnored
        loginFile(c).delete();
        //noinspection ResultOfMethodCallIgnored
        listFile(c).delete();
        //noinspection ResultOfMethodCallIgnored
        favoritesFile(c).delete();     // another provider numbers its channels differently
        //noinspection ResultOfMethodCallIgnored
        recentFile(c).delete();
        c.getSharedPreferences("live", Context.MODE_PRIVATE).edit().clear().commit();
    }

    // MARK: - Favorites

    /** The favorite channels, in the order they were added (what the home screen's Live TV row shows). */
    static List<Channel> favorites(Context c) {
        List<Channel> out = new ArrayList<>();
        File f = favoritesFile(c);
        if (!f.exists()) return out;
        try (FileInputStream in = new FileInputStream(f)) {
            JSONArray a = new JSONArray(read(in));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null) out.add(channel(o));
            }
        } catch (IOException | JSONException e) {
            AppLog.w("live: favorites unreadable");
        }
        return out;
    }

    static void setFavorite(Context c, Channel ch, boolean on) {
        List<Channel> list = favorites(c);
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).id == ch.id) list.remove(i);
        if (on) list.add(ch);
        saveFavorites(c, list);
    }

    /** The favorites in this order (Live TV's settings reorder them; the home screen's row follows). */
    static void saveFavorites(Context c, List<Channel> list) {
        JSONArray a = new JSONArray();
        try {
            for (Channel f : list) a.put(json(f));
            write(favoritesFile(c), a.toString());
        } catch (IOException | JSONException e) {
            AppLog.w("live: couldn't save the favorites");
        }
    }

    // MARK: - Recently watched (the home screen's On now row, after the favorites)

    /** The channels watched lately, the latest first. */
    static List<Channel> recent(Context c) {
        List<Channel> out = new ArrayList<>();
        File f = recentFile(c);
        if (!f.exists()) return out;
        try (FileInputStream in = new FileInputStream(f)) {
            JSONArray a = new JSONArray(read(in));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null) out.add(channel(o));
            }
        } catch (IOException | JSONException e) {
            AppLog.w("live: recent channels unreadable");
        }
        return out;
    }

    /** `ch` was just watched: first in the recent ones. Background thread. */
    static void watched(Context c, Channel ch) {
        List<Channel> list = recent(c);
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).id == ch.id) list.remove(i);
        list.add(0, ch);
        saveRecent(c, list);
    }

    /** `id` out of History. */
    static void removeRecent(Context c, int id) {
        List<Channel> list = recent(c);
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).id == id) list.remove(i);
        saveRecent(c, list);
    }

    /** History emptied. */
    static void clearRecent(Context c) {
        saveRecent(c, new ArrayList<>());
    }

    /** History down to the number of channels picked in the settings (as one is watched, or the number changes). */
    static void trimRecent(Context c) {
        List<Channel> list = recent(c);
        if (list.size() > LiveOptions.historyMax(c)) saveRecent(c, list);
    }

    private static void saveRecent(Context c, List<Channel> list) {
        int max = LiveOptions.historyMax(c);
        while (list.size() > max) list.remove(list.size() - 1);
        JSONArray a = new JSONArray();
        try {
            for (Channel r : list) a.put(json(r));
            write(recentFile(c), a.toString());
        } catch (IOException | JSONException e) {
            AppLog.w("live: couldn't save the recent channels");
        }
    }

    /** When the recent channels last changed (0 = none). */
    static long recentStamp(Context c) {
        File f = recentFile(c);
        return f.exists() ? f.lastModified() * 31 + f.length() : 0;
    }

    private static File recentFile(Context c) {
        return new File(c.getFilesDir(), "live-recent.json");
    }

    /** The provider's stream format ("ts" or "m3u8"). */
    static String format(Context c) {
        return login(c).format;
    }

    /** When the favorites last changed (0 = none): the home screen reads them again only then. */
    static long favoritesStamp(Context c) {
        File f = favoritesFile(c);
        return f.exists() ? f.lastModified() * 31 + f.length() : 0;
    }

    static JSONObject json(Channel ch) throws JSONException {
        return new JSONObject().put("id", ch.id).put("num", ch.num).put("name", ch.name).put("logo", ch.logo)
                .put("epg", ch.epg).put("cat", ch.category).put("arch", ch.archive).put("url", ch.url);
    }

    static Channel channel(JSONObject o) {
        Channel ch = new Channel();
        ch.id = o.optInt("id");
        ch.num = o.optInt("num");
        ch.name = o.optString("name");
        ch.logo = o.optString("logo");
        ch.epg = o.optString("epg");
        ch.category = o.optString("cat");
        ch.archive = o.optInt("arch");
        ch.url = o.optString("url");
        return ch;
    }

    static void write(File f, String text) throws IOException {
        File tmp = new File(f.getPath() + "." + android.os.Process.myPid() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!tmp.renameTo(f)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("couldn't save " + f.getName());
        }
    }

    private static File loginFile(Context c) {
        return new File(c.getFilesDir(), "live-login.json");
    }

    private static File favoritesFile(Context c) {
        return new File(c.getFilesDir(), "live-favorites.json");
    }

    /** "provider.tv:8080" → "http://provider.tv:8080" (no trailing slash). */
    static String normalize(String server) {
        String s = server == null ? "" : server.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (s.endsWith("/player_api.php")) s = s.substring(0, s.length() - "/player_api.php".length());
        if (!s.isEmpty() && !s.matches("(?i)^https?://.*")) s = "http://" + s;
        return s;
    }

    /**
     * Checks the login with the provider and keeps it when it works. Background thread. Throws with a message for the
     * person (wrong password, expired account, server unreachable).
     */
    static void signIn(Context c, String server, String user, String pass) throws IOException {
        String base = normalize(server);
        if (base.isEmpty() || user.trim().isEmpty() || pass.isEmpty()) {
            throw new IOException(Ui.tr("Preencha o servidor, o usuário e a senha.", "Fill in the server, username and password."));
        }
        JSONObject info;
        try {
            info = new JSONObject(get(api(base, user.trim(), pass, null)));
        } catch (JSONException e) {
            throw new IOException(Ui.tr("O servidor não respondeu como um Xtream Codes.", "The server didn't answer like an Xtream Codes server."));
        }
        JSONObject u = info.optJSONObject("user_info");
        if (u == null || u.optInt("auth", 0) != 1) {
            throw new IOException(Ui.tr("O provedor recusou o usuário ou a senha.", "The provider rejected the username or password."));
        }
        String status = u.optString("status", "Active");
        if (!status.equalsIgnoreCase("Active")) {
            throw new IOException(Ui.tr("A conta não está ativa: ", "The account isn't active: ") + status);
        }
        Login l = new Login();
        l.server = base;
        l.user = user.trim();
        l.pass = pass;
        l.format = format(u.optJSONArray("allowed_output_formats"));
        l.zone = zoneOf(info);
        l.max = u.optInt("max_connections", 0);
        saveLogin(c, l);
    }

    /** An M3U playlist's link (and its guide's, optional): checked by reading it. Background thread. */
    static void signInM3u(Context c, String link, String guide) throws IOException {
        String url = link == null ? "" : link.trim();
        if (url.isEmpty()) throw new IOException(Ui.tr("Preencha o link da lista.", "Fill in the playlist link."));
        if (!url.matches("(?i)^https?://.*")) url = "http://" + url;
        LiveM3u.Parsed p = LiveM3u.parse(get(url));
        Login l = new Login();
        l.kind = M3U;
        l.server = url;
        l.epg = guide == null || guide.trim().isEmpty() ? p.guide : guide.trim();
        saveLogin(c, l);
    }

    /** A MAG portal's address and MAC: the portal found and asked. Background thread. */
    static void signInMag(Context c, String portal, String typedMac) throws IOException {
        String mac = LiveMag.mac(typedMac);
        if (mac == null) throw new IOException(Ui.tr("O MAC tem 12 letras e números, como 00:1A:79:12:34:56.",
                "A MAC has 12 letters and digits, like 00:1A:79:12:34:56."));
        String endpoint = LiveMag.endpoint(portal, mac);
        LiveMag.signIn(endpoint, mac);
        Login l = new Login();
        l.kind = MAG;
        l.server = endpoint;
        l.mac = mac;
        saveLogin(c, l);
    }

    /** MPEG-TS when allowed (it starts faster), else HLS. */
    private static String format(JSONArray allowed) {
        if (allowed == null || allowed.length() == 0) return "ts";
        boolean ts = false, hls = false;
        for (int i = 0; i < allowed.length(); i++) {
            String f = allowed.optString(i).toLowerCase(Locale.ROOT);
            ts |= f.equals("ts");
            hls |= f.equals("m3u8");
        }
        return ts || !hls ? "ts" : "m3u8";
    }

    /** The saved lineup, or null. Background thread (the list can hold thousands of channels). */
    static Lineup saved(Context c) {
        File f = listFile(c);
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            return parse(new JSONObject(read(in)), login(c).format, favorites(c), mergeKeeping(c));
        } catch (IOException | JSONException | RuntimeException e) {
            AppLog.w("live: saved list unreadable: " + e.getMessage());
            return null;
        }
    }

    /** Fetches the categories and channels from the provider and saves them. Background thread. */
    static Lineup fetch(Context c) throws IOException {
        Login l = login(c);
        String base = l.server, user = l.user, pass = l.pass;
        if (base.isEmpty()) throw new IOException("not signed in");
        if (!l.kind.equals(XTREAM)) {
            try {
                JSONObject all;
                if (l.kind.equals(M3U)) {
                    LiveM3u.Parsed p = LiveM3u.parse(get(base));
                    all = p.list;
                    if (l.epg.isEmpty() && !p.guide.isEmpty()) {     // the list gained a guide link
                        l.epg = p.guide;
                        saveLogin(c, l);
                    }
                } else {
                    all = LiveMag.fetch(base, l.mac);
                }
                all.put("at", System.currentTimeMillis()).put("v", LIST_VERSION);
                write(listFile(c), all.toString());
                return parse(all, l.format, favorites(c), mergeKeeping(c));
            } catch (JSONException e) {
                throw new IOException(e.getMessage());
            }
        }
        try {
            JSONArray cats = new JSONArray(get(api(base, user, pass, "get_live_categories")));
            JSONArray streams = new JSONArray(get(api(base, user, pass, "get_live_streams")));
            // Saved compact: only what Live TV uses.
            JSONArray outCats = new JSONArray(), outChannels = new JSONArray();
            for (int i = 0; i < cats.length(); i++) {
                JSONObject o = cats.optJSONObject(i);
                if (o == null) continue;
                outCats.put(new JSONObject().put("id", o.optString("category_id")).put("name", o.optString("category_name")));
            }
            for (int i = 0; i < streams.length(); i++) {
                JSONObject o = streams.optJSONObject(i);
                if (o == null || o.optInt("stream_id", 0) <= 0) continue;
                outChannels.put(new JSONObject().put("id", o.optInt("stream_id")).put("num", o.optInt("num", i + 1))
                        .put("name", o.optString("name")).put("logo", clean(o.optString("stream_icon")))
                        .put("epg", clean(o.optString("epg_channel_id"))).put("cat", o.optString("category_id"))
                        .put("arch", o.optInt("tv_archive", 0) == 1 ? Math.max(1, o.optInt("tv_archive_duration", 1)) : 0));
            }
            JSONObject all = new JSONObject().put("categories", outCats).put("channels", outChannels)
                    .put("at", System.currentTimeMillis()).put("v", LIST_VERSION);
            write(listFile(c), all.toString());
            return parse(all, l.format, favorites(c), mergeKeeping(c));
        } catch (JSONException e) {
            throw new IOException(Ui.tr("A lista de canais veio num formato inesperado.", "The channel list came in an unexpected format."));
        }
    }

    /** A program in the guide: from `start` to `end` (ms, UTC), its title and synopsis. */
    static final class Program {
        long start, end;
        String title = "", about = "";
        /** The show's picture (the full guide's icon), "" without one. */
        String icon = "";
        /** A new episode, a live broadcast (the guide's marks, taken out of the title: the badges). */
        boolean fresh, live;
        /** Shown before (the full guide's previously-shown): the guide's REPEAT tag. */
        boolean repeat;
        /** The episode's own title (the full guide's sub-title), "" without one: the tile's second line. */
        String sub = "";
        /** The age it's rated for (the full guide's rating: TV-14 is 14, R 17, 18 18…), -1 when it has none. */
        int age = -1;
    }

    private static final java.util.regex.Pattern MARK_BRACKET =
            java.util.regex.Pattern.compile("(?i)\\s*[\\[(](new|live|premiere|en vivo|ao vivo)[\\])]\\s*");
    private static final java.util.regex.Pattern MARK_PREFIX =
            java.util.regex.Pattern.compile("(?i)^(new|live|premiere)\\s*[:|\\-\u2013]\\s*");

    /**
     * The new and live marks providers put in titles: superscripts (ᴺᵉʷ, ᴸᶦᵛᵉ), in brackets ("(New)", "[LIVE]") or
     * first ("LIVE: …"). They come out of the title and set the flags (only ever on: running it twice is the same).
     */
    static void mark(Program p) {
        // The episode line: many guides send it as the description's own short first line ("S03 E09 Vae Victis").
        if (p.sub.isEmpty() && p.about != null) {
            int nl = p.about.indexOf('\n');
            if (nl > 0 && nl <= 90) p.sub = p.about.substring(0, nl).trim();
        }
        String t = p.title;
        if (t == null || t.isEmpty()) return;
        StringBuilder out = null;
        for (int i = 0; i < t.length(); ) {
            int j = i;
            while (j < t.length() && Character.getType(t.charAt(j)) == Character.MODIFIER_LETTER) j++;
            if (j - i >= 3) {      // a run of superscript letters: a mark when it reads as one
                String w = java.text.Normalizer.normalize(t.substring(i, j), java.text.Normalizer.Form.NFKC)
                        .replace('\u026A', 'i').toLowerCase(Locale.ROOT);
                boolean fresh = w.equals("new") || w.equals("premiere"), live = w.equals("live");
                if (fresh || live) {
                    p.fresh |= fresh;
                    p.live |= live;
                    if (out == null) out = new StringBuilder(t.substring(0, i));
                    i = j;
                    continue;
                }
            }
            if (j > i) {
                if (out != null) out.append(t, i, j);
                i = j;
                continue;
            }
            if (out != null) out.append(t.charAt(i));
            i++;
        }
        if (out != null) t = out.toString();
        java.util.regex.Matcher m = MARK_BRACKET.matcher(t);
        while (m.find()) {
            String w = m.group(1).toLowerCase(Locale.ROOT);
            if (w.equals("new") || w.equals("premiere")) p.fresh = true;
            else p.live = true;
        }
        t = m.replaceAll(" ");
        m = MARK_PREFIX.matcher(t);
        if (m.find()) {
            if (m.group(1).equalsIgnoreCase("live")) p.live = true;
            else p.fresh = true;
            t = t.substring(m.end());
        }
        t = t.replaceAll("\\s{2,}", " ").trim();
        if (!t.isEmpty()) p.title = t;
    }

    /**
     * The channel's guide for Live TV: on a channel with catch-up, its past days too (the provider's full table,
     * get_simple_data_table, kept to the catch-up days and the next half day); else from now on (the short guide).
     * Background thread.
     */
    static List<Program> guide(Context c, Channel ch, int limit) throws IOException {
        if (!kind(c).equals(XTREAM)) {      // M3U: the full guide only; MAG: the portal's
            if (kind(c).equals(MAG)) return programs(c, ch.id, limit);
            long now = System.currentTimeMillis();
            List<Program> full = XmltvStore.programs(c, ch.epg, now - 3 * 3600_000L, now + 24 * 3600_000L);
            return full == null ? new ArrayList<>() : full;
        }
        if (ch.archive <= 0) return programs(c, ch.id, limit);
        Login l = login(c);
        String url = api(l.server, l.user, l.pass, "get_simple_data_table") + "&stream_id=" + ch.id;
        long now = System.currentTimeMillis(), from = now - ch.archive * 24 * 3600_000L, to = now + 12 * 3600_000L;
        List<Program> out = listings(get(url), from, to, LiveOptions.guideShift(c));
        if (out.isEmpty()) return programs(c, ch.id, limit);     // a server without the full table
        return out;
    }

    /**
     * What's on the channel from now on: up to `limit` programs, in order (the provider's short guide, get_short_epg).
     * Empty when the provider has no guide for it. Background thread.
     */
    static List<Program> programs(Context c, int streamId, int limit) throws IOException {
        Login l = login(c);
        if (l.kind.equals(MAG)) {
            long shift = LiveOptions.guideShift(c);
            List<Program> out = LiveMag.programs(l.server, l.mac, streamId, limit);
            for (Program p : out) {
                p.start += shift;
                p.end += shift;
            }
            return out;
        }
        if (l.kind.equals(M3U)) return new ArrayList<>();     // its guide is the full one (XMLTV), by guide id
        String url = api(l.server, l.user, l.pass, "get_short_epg")
                + "&stream_id=" + streamId + "&limit=" + limit;
        return listings(get(url), 0, Long.MAX_VALUE, LiveOptions.guideShift(c));
    }

    /** A channel's guide as saved on the TV: when it was fetched and its programs. */
    static final class SavedGuide {
        long at;
        final List<Program> programs = new ArrayList<>();
    }

    /** The channel's saved guide, or null (never saved, or unreadable). Background thread. */
    static SavedGuide savedGuide(Context c, int streamId) {
        File f = guideFile(c, streamId);
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            JSONObject o = new JSONObject(read(in));
            SavedGuide g = new SavedGuide();
            g.at = o.optLong("at");
            JSONArray list = o.optJSONArray("p");
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONArray e = list.optJSONArray(i);
                if (e == null) continue;
                Program p = new Program();
                p.start = e.optLong(0);
                p.end = e.optLong(1);
                p.title = e.optString(2);
                p.about = e.optString(3);
                int marks = e.optInt(4);
                p.fresh = (marks & 1) != 0;
                p.live = (marks & 2) != 0;
                mark(p);
                g.programs.add(p);
            }
            return g;
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    /** Keeps the channel's guide on the TV (the cache: Android may clear it, it's fetched again then). */
    static void saveGuide(Context c, int streamId, List<Program> programs, long at) {
        try {
            JSONArray list = new JSONArray();
            for (Program p : programs) {
                list.put(new JSONArray().put(p.start).put(p.end).put(p.title).put(p.about).put((p.fresh ? 1 : 0) | (p.live ? 2 : 0)));
            }
            File f = guideFile(c, streamId);
            //noinspection ResultOfMethodCallIgnored
            f.getParentFile().mkdirs();
            write(f, new JSONObject().put("at", at).put("p", list).toString());
        } catch (IOException | JSONException e) {
            AppLog.w("live: couldn't save a guide");
        }
    }

    /** Signed out: the saved guides were that provider's. */
    static void clearGuides(Context c) {
        File[] files = new File(c.getCacheDir(), "live-epg").listFiles();
        //noinspection ResultOfMethodCallIgnored
        if (files != null) for (File f : files) f.delete();
    }

    private static File guideFile(Context c, int streamId) {
        return new File(new File(c.getCacheDir(), "live-epg"), streamId + ".json");
    }

    /**
     * The programs in a guide answer, moved by `shift` (the guide offset), that end after `from` and start before `to`,
     * in order, without overlaps.
     */
    private static List<Program> listings(String body, long from, long to, long shift) {
        List<Program> out = new ArrayList<>();
        JSONArray list;
        try {
            list = new JSONObject(body).optJSONArray("epg_listings");
        } catch (JSONException e) {
            return out;     // some servers answer [] or nothing for a channel without a guide
        }
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject e = list.optJSONObject(i);
            if (e == null) continue;
            Program p = new Program();
            p.start = e.optLong("start_timestamp", 0) * 1000;
            p.end = e.optLong("stop_timestamp", e.optLong("end_timestamp", 0)) * 1000;
            if (p.start <= 0 || p.end <= p.start) continue;
            p.start += shift;
            p.end += shift;
            if (p.end <= from || p.start >= to) continue;
            p.title = text(e.optString("title"));
            p.about = text(e.optString("description"));
            mark(p);
            out.add(p);
        }
        java.util.Collections.sort(out, (a, b) -> Long.compare(a.start, b.start));
        // The full table can list a program twice (two guide sources): the first one at each time wins.
        for (int i = out.size() - 1; i > 0; i--) if (out.get(i).start < out.get(i - 1).end) out.remove(i);
        return out;
    }

    /** Xtream sends the guide's text in Base64 (most servers); plain text from the others stays as it is. */
    private static String text(String s) {
        if (s == null || s.isEmpty() || s.equals("null")) return "";
        try {
            String decoded = new String(android.util.Base64.decode(s, android.util.Base64.DEFAULT), StandardCharsets.UTF_8).trim();
            if (!decoded.isEmpty() && decoded.indexOf('�') < 0 && !decoded.matches("(?s).*[\\x00-\\x08\\x0E-\\x1F].*")) return decoded;
        } catch (IllegalArgumentException ignored) {
        }
        return s.trim();
    }

    /** The provider's full guide (XMLTV) address (it carries the login: never logged). */
    static String xmltvUrl(Context c) {
        Login l = login(c);
        if (!l.kind.equals(XTREAM)) return l.kind.equals(M3U) ? l.epg : "";
        return l.server + "/xmltv.php?username=" + Uri.encode(l.user) + "&password=" + Uri.encode(l.pass);
    }

    /** The channel's stream address (it carries the login: never logged). */
    static String streamUrl(Context c, Channel ch, String format) {
        Login l = login(c);
        if (l.kind.equals(M3U)) return ch.url;
        if (l.kind.equals(MAG)) return LiveMag.SCHEME + ch.url;      // asked for as it plays (resolve)
        return l.server + "/live/" + Uri.encode(l.user) + "/" + Uri.encode(l.pass) + "/" + ch.id + "." + format;
    }

    /**
     * A past program from the catch-up (its stream address carries the login: never logged). The provider wants the
     * start in its own time zone, to the minute, and the length in minutes. Background thread (the zone may be asked).
     */
    static String catchupUrl(Context c, Channel ch, Program p, String format) throws IOException {
        Login l = login(c);
        if (!l.kind.equals(XTREAM)) throw new IOException(Ui.tr("Esta lista não tem reprise.", "This playlist has no catch-up."));
        if (l.zone.isEmpty()) {     // signed in before catch-up came: ask the server once
            try {
                l.zone = zoneOf(new JSONObject(get(api(l.server, l.user, l.pass, null))));
                if (!l.zone.isEmpty()) saveLogin(c, l);
            } catch (JSONException ignored) {
            }
        }
        java.text.SimpleDateFormat at = new java.text.SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.ROOT);
        at.setTimeZone(l.zone.isEmpty() ? java.util.TimeZone.getDefault() : java.util.TimeZone.getTimeZone(l.zone));
        long minutes = Math.max(1, (p.end - p.start + 59_999) / 60_000);
        return l.server + "/timeshift/" + Uri.encode(l.user) + "/" + Uri.encode(l.pass) + "/" + minutes + "/"
                + at.format(new java.util.Date(p.start)) + "/" + ch.id + "." + format;
    }

    /**
     * How many streams the account may play at once (Multiview's boxes), asked once and kept; 1 when the server
     * doesn't say. Background thread.
     */
    static int maxConnections(Context c) {
        Login l = login(c);
        if (!l.kind.equals(XTREAM)) return 1;
        if (l.max > 0) return l.max;
        try {
            JSONObject u = new JSONObject(get(api(l.server, l.user, l.pass, null))).optJSONObject("user_info");
            int max = u == null ? 0 : u.optInt("max_connections", 0);
            if (max > 0) {
                l.max = max;
                saveLogin(c, l);
                return max;
            }
        } catch (IOException | JSONException e) {
            AppLog.w("live: couldn't ask how many streams the account allows");
        }
        return 1;
    }

    /** The subscription, as the provider tells it (user_info): when it ends, its streams, how many play now. */
    static final class Account {
        /** When it ends (ms; 0: no end date), when it was asked. */
        long expires, at;
        int max, active;
        String status = "";
        boolean trial;
    }

    /** The provider asked again (the network: a background thread); kept for the settings. Null when it couldn't. */
    static Account account(Context c) {
        Login l = login(c);
        if (l.server.isEmpty() || !l.kind.equals(XTREAM)) return null;
        try {
            JSONObject u = new JSONObject(get(api(l.server, l.user, l.pass, null))).optJSONObject("user_info");
            if (u == null) return null;
            Account a = new Account();
            String exp = u.optString("exp_date", "");
            try {
                a.expires = exp.isEmpty() || exp.equals("null") ? 0 : Long.parseLong(exp.trim()) * 1000L;
            } catch (NumberFormatException e) {
                a.expires = 0;
            }
            a.max = u.optInt("max_connections", 0);
            a.active = u.optInt("active_cons", 0);
            a.status = clean(u.optString("status", ""));
            a.trial = "1".equals(u.optString("is_trial", "0"));
            a.at = System.currentTimeMillis();
            write(new File(c.getFilesDir(), "live-account.json"), new JSONObject().put("expires", a.expires).put("max", a.max)
                    .put("active", a.active).put("status", a.status).put("trial", a.trial).put("at", a.at).toString());
            return a;
        } catch (IOException | JSONException e) {
            AppLog.w("live: couldn't ask about the subscription");
            return null;
        }
    }

    /** The subscription as last asked (no network), or null. */
    static Account savedAccount(Context c) {
        File f = new File(c.getFilesDir(), "live-account.json");
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            JSONObject o = new JSONObject(read(in));
            Account a = new Account();
            a.expires = o.optLong("expires");
            a.max = o.optInt("max");
            a.active = o.optInt("active");
            a.status = o.optString("status");
            a.trial = o.optBoolean("trial");
            a.at = o.optLong("at");
            return a;
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    /**
     * The address to open for `url` now: a MAG channel's asked for from the portal (it expires), the others as they are.
     * Background thread.
     */
    static String resolve(Context c, String url) throws IOException {
        if (url == null || !url.startsWith(LiveMag.SCHEME)) return url;
        Login l = login(c);
        return LiveMag.link(l.server, l.mac, url.substring(LiveMag.SCHEME.length()));
    }

    /**
     * The stream's type for the player: Xtream's from the format; an M3U or MAG address's from its name (HLS when it
     * says .m3u8), else null (the player looks at the stream itself).
     */
    static String mime(Context c, String url, String format) {
        if (kind(c).equals(XTREAM)) return format.equals("m3u8") ? androidx.media3.common.MimeTypes.APPLICATION_M3U8
                : androidx.media3.common.MimeTypes.VIDEO_MP2T;
        return url != null && url.toLowerCase(Locale.ROOT).contains(".m3u8") ? androidx.media3.common.MimeTypes.APPLICATION_M3U8 : null;
    }

    /** The player's sources with MAG's addresses asked for as each one plays (on the loading thread). */
    static androidx.media3.datasource.DataSource.Factory resolving(Context c, androidx.media3.datasource.DataSource.Factory upstream) {
        Context app = c.getApplicationContext();
        return new androidx.media3.datasource.ResolvingDataSource.Factory(upstream, spec -> {
            String u = spec.uri.toString();
            if (!u.startsWith(LiveMag.SCHEME)) return spec;
            return spec.withUri(Uri.parse(resolve(app, u)));
        });
    }

    /** The server's time zone, from its answer to the login (server_info.timezone), or "". */
    private static String zoneOf(JSONObject info) {
        JSONObject server = info.optJSONObject("server_info");
        String zone = server == null ? "" : clean(server.optString("timezone"));
        return zone.isEmpty() || !java.util.Arrays.asList(java.util.TimeZone.getAvailableIDs()).contains(zone) ? "" : zone;
    }

    /** With merging on, the groups kept apart (none: an empty set); off, null. */
    private static java.util.Set<String> mergeKeeping(Context c) {
        return LiveOptions.merge(c) ? LiveOptions.separateGroups(c) : null;
    }

    /**
     * Favorites first (even while empty), then "All channels", then the provider's categories that have channels.
     * With `separate` (merging on), similar channels merged except those groups.
     */
    private static Lineup parse(JSONObject all, String format, List<Channel> favorites, java.util.Set<String> separate) {
        Lineup l = new Lineup();
        l.at = all.optLong("at");
        l.version = all.optInt("v", 1);
        l.format = format;
        Category favs = new Category();
        favs.id = FAVORITES;
        favs.name = Ui.tr("Favoritos", "Favorites");
        l.categories.add(favs);
        Category everything = new Category();
        everything.id = "";
        everything.name = Ui.tr("Todos os canais", "All channels");
        l.categories.add(everything);
        Map<String, Category> byId = new HashMap<>();
        JSONArray cats = all.optJSONArray("categories");
        for (int i = 0; cats != null && i < cats.length(); i++) {
            JSONObject o = cats.optJSONObject(i);
            if (o == null) continue;
            Category cat = new Category();
            cat.id = o.optString("id");
            cat.name = o.optString("name");
            byId.put(cat.id, cat);
            l.categories.add(cat);
        }
        JSONArray chans = all.optJSONArray("channels");
        for (int i = 0; chans != null && i < chans.length(); i++) {
            JSONObject o = chans.optJSONObject(i);
            if (o == null) continue;
            Channel ch = new Channel();
            ch.id = o.optInt("id");
            ch.num = o.optInt("num");
            ch.name = o.optString("name");
            ch.logo = o.optString("logo");
            ch.epg = o.optString("epg");
            ch.category = o.optString("cat");
            ch.archive = o.optInt("arch");
            ch.url = o.optString("url");
            ch.key = fold(ch.name);
            everything.channels.add(ch);
            Category cat = byId.get(ch.category);
            if (cat != null) cat.channels.add(ch);
        }
        if (separate != null) {
            l.byId.putAll(LiveMerge.apply(l, separate));
        } else {
            for (Channel ch : everything.channels) l.byId.put(ch.id, ch);
        }
        // The favorites as the provider lists them now (a channel it dropped drops out), in the order they were added
        // (a merged one once).
        for (Channel f : favorites) {
            Channel ch = l.byId.get(f.id);
            if (ch != null && !favs.channels.contains(ch)) favs.channels.add(ch);
        }
        // Empty categories only get in the way (but Favorites stays, to be found).
        for (int i = l.categories.size() - 1; i > 1; i--) if (l.categories.get(i).channels.isEmpty()) l.categories.remove(i);
        return l;
    }

    /** Lowercase, without accents or punctuation, single spaces: what search and sorting by name compare. */
    static String fold(String s) {
        if (s == null || s.isEmpty()) return "";
        // One pass, no regular expressions: it runs for every channel (thousands) as the list loads.
        boolean ascii = true;
        for (int i = 0; i < s.length() && ascii; i++) ascii = s.charAt(i) < 128;
        String n = ascii ? s : MARKS.matcher(java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)).replaceAll("");
        StringBuilder b = new StringBuilder(n.length());
        boolean gap = false;
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                if (gap && b.length() > 0) b.append(' ');
                b.append(Character.toLowerCase(c));
                gap = false;
            } else {
                gap = true;
            }
        }
        return b.toString();
    }

    private static final java.util.regex.Pattern MARKS = java.util.regex.Pattern.compile("\\p{M}+");

    /** Whether channel `id` is a favorite (in the lineup's Favorites). */
    static boolean isFavorite(Lineup l, int id) {
        if (l == null || l.categories.isEmpty()) return false;
        for (Channel ch : l.categories.get(0).channels) if (ch.id == id) return true;
        return false;
    }

    private static String clean(String s) {
        return s == null || s.equals("null") ? "" : s.trim();
    }

    /** Changes when the saved channel list does (Live TV fetched it again): for the home screen's cache of it. */
    static long savedStamp(Context c) {
        File f = listFile(c);
        return f.lastModified() * 31 + f.length();
    }

    static File listFile(Context c) {
        return new File(c.getFilesDir(), "live-channels.json");
    }

    private static String api(String base, String user, String pass, String action) {
        return base + "/player_api.php?username=" + Uri.encode(user) + "&password=" + Uri.encode(pass)
                + (action == null ? "" : "&action=" + action);
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
        } catch (IllegalArgumentException e) {
            throw new IOException(Ui.tr("Endereço do servidor inválido.", "That server address isn't valid."));
        }
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", Live.USER_AGENT);
        try {
            int code = c.getResponseCode();
            if (code == 401 || code == 403) {
                throw new IOException(Ui.tr("O provedor recusou o usuário ou a senha.", "The provider rejected the username or password."));
            }
            if (code != 200) throw new IOException(Ui.tr("O servidor respondeu com o erro ", "The server answered with error ") + code + ".");
            try (InputStream in = c.getInputStream()) {
                return read(in);
            }
        } catch (java.net.UnknownHostException | java.net.ConnectException | java.net.SocketTimeoutException e) {
            throw new IOException(Ui.tr("Não deu para falar com o servidor. Confira o endereço.", "Couldn't reach the server. Check the address."));
        } finally {
            c.disconnect();
        }
    }

    static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[65536];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
