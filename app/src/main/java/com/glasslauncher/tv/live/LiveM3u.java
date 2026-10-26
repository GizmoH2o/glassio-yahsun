package com.glasslauncher.tv.live;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An M3U playlist: each #EXTINF line's channel (its name, logo, group, guide id and number) and the address under it,
 * in Xtream's saved form ("url" each channel's address). Movies and series in the same list (Xtream's /movie/ and
 * /series/, files like .mp4) are left out: Live TV is live channels. The list's own guide link (x-tvg-url) comes along.
 */
final class LiveM3u {
    private LiveM3u() {}

    private static final Pattern ATTR = Pattern.compile("([A-Za-z0-9_-]+)=\"([^\"]*)\"");

    static final class Parsed {
        JSONObject list;
        String guide = "";
        int channels;
    }

    static Parsed parse(String body) throws IOException {
        if (body == null || !body.contains("#EXTINF")) {
            throw new IOException(Ui.tr("Este link não é uma lista M3U.", "That link isn't an M3U playlist."));
        }
        Parsed out = new Parsed();
        Map<String, String> groups = new LinkedHashMap<>();
        Set<Integer> ids = new HashSet<>();
        JSONArray chans = new JSONArray();
        String[] lines = body.split("\r?\n");
        Map<String, String> attrs = null;
        String name = null, group = null;
        try {
            for (String raw : lines) {
                String line = raw.trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("#EXTM3U")) {
                    Map<String, String> head = attrs(line);
                    String g = head.containsKey("x-tvg-url") ? head.get("x-tvg-url") : head.get("url-tvg");
                    if (g != null) out.guide = g.split(",")[0].trim();
                } else if (line.startsWith("#EXTINF")) {
                    int comma = commaAfterAttributes(line);
                    attrs = attrs(comma < 0 ? line : line.substring(0, comma));
                    name = comma < 0 ? "" : line.substring(comma + 1).trim();
                    group = attrs.get("group-title");
                } else if (line.startsWith("#EXTGRP:")) {
                    if (group == null || group.isEmpty()) group = line.substring(8).trim();
                } else if (!line.startsWith("#") && attrs != null) {
                    if (live(line)) {
                        if (name == null || name.isEmpty()) name = attrs.containsKey("tvg-name") ? attrs.get("tvg-name") : line;
                        String g = group == null || group.isEmpty() ? Ui.tr("Outros", "Other") : group;
                        String gid = groups.get(g);
                        if (gid == null) {
                            gid = "g" + (groups.size() + 1);
                            groups.put(g, gid);
                        }
                        int id = (line.hashCode() & 0x7fffffff) % 1_000_000_000 + 1;     // the same each refresh: favorites keep
                        while (!ids.add(id)) id = id % 1_000_000_000 + 1;
                        int num = 0;
                        try {
                            num = Integer.parseInt(attrs.containsKey("tvg-chno") ? attrs.get("tvg-chno").trim() : "0");
                        } catch (NumberFormatException ignored) {
                        }
                        chans.put(new JSONObject().put("id", id).put("num", num > 0 ? num : chans.length() + 1).put("name", name)
                                .put("logo", attrs.containsKey("tvg-logo") ? attrs.get("tvg-logo") : "")
                                .put("epg", attrs.containsKey("tvg-id") ? attrs.get("tvg-id") : "")
                                .put("cat", gid).put("arch", 0).put("url", line));
                    }
                    attrs = null;
                    name = group = null;
                }
            }
            JSONArray cats = new JSONArray();
            for (Map.Entry<String, String> e : groups.entrySet()) cats.put(new JSONObject().put("id", e.getValue()).put("name", e.getKey()));
            out.list = new JSONObject().put("categories", cats).put("channels", chans);
        } catch (JSONException e) {
            throw new IOException(e.getMessage());
        }
        out.channels = chans.length();
        if (out.channels == 0) throw new IOException(Ui.tr("A lista não tem canais ao vivo.", "The playlist has no live channels."));
        return out;
    }

    /** A live channel's address (not a movie or an episode). */
    private static boolean live(String url) {
        String u = url.toLowerCase(java.util.Locale.ROOT);
        if (u.contains("/movie/") || u.contains("/series/")) return false;
        int q = u.indexOf('?');
        String path = q < 0 ? u : u.substring(0, q);
        return !(path.endsWith(".mp4") || path.endsWith(".mkv") || path.endsWith(".avi") || path.endsWith(".mov"));
    }

    /** The name's comma: the first one outside the attributes' quotes. */
    private static int commaAfterAttributes(String line) {
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') quoted = !quoted;
            else if (ch == ',' && !quoted) return i;
        }
        return -1;
    }

    private static Map<String, String> attrs(String line) {
        Map<String, String> m = new java.util.HashMap<>();
        Matcher x = ATTR.matcher(line);
        while (x.find()) m.put(x.group(1).toLowerCase(java.util.Locale.ROOT), x.group(2).trim());
        return m;
    }
}
