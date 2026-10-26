package com.glasslauncher.tv.live;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A MAG (Stalker) portal: what a MAG box does, from its MAC address. A handshake gives a token; with it, the genres and
 * the channels; each channel's stream address is asked for as it plays (create_link: they expire), so the player
 * resolves "zerotv-mag:<cmd>" on its loading thread (Xtream.resolve). The MAC is the provider's login: never logged.
 */
final class LiveMag {
    private LiveMag() {}

    static final String SCHEME = "zerotv-mag:";
    private static final String UA = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3";

    /** The portal's token, for the endpoint and MAC it was given for (one portal at a time). */
    private static String token, tokenFor;

    /** The portal's API address for what was typed ("http://host:8080/c/", "…/stalker_portal/c"): the one that answers. */
    static String endpoint(String portal, String mac) throws IOException {
        String base = Xtream.normalize(portal);
        if (base.isEmpty()) throw new IOException(Ui.tr("Preencha o endereço do portal.", "Fill in the portal address."));
        base = base.replaceFirst("/c$", "").replaceFirst("/index\\.html$", "");
        List<String> tries = new ArrayList<>();
        if (base.endsWith(".php")) tries.add(base);
        tries.add(base + "/portal.php");
        tries.add(base + "/server/load.php");
        tries.add(base + "/stalker_portal/server/load.php");
        IOException last = null;
        for (String url : tries) {
            try {
                handshake(url, mac);
                return url;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null && last.getMessage() != null && last.getMessage().contains("MAC") ? last
                : new IOException(Ui.tr("O endereço não respondeu como um portal MAG.", "That address didn't answer like a MAG portal."));
    }

    /** Checks the portal and the MAC, as signing in. Background thread. */
    static void signIn(String endpoint, String mac) throws IOException {
        JSONObject profile = js(call(endpoint, mac, "type=stb&action=get_profile&hd=1&ver=ImageDescription:%200.2.18-r23-250;%20"
                + "ImageDate:%20Thu%20Sep%2013%2011:31:16%20EEST%202018;%20PORTAL%20version:%205.6.1;%20API%20Version:%20JS%20API%20"
                + "version:%20343&num_banks=2&stb_type=MAG250&image_version=218&video_out=hdmi&auth_second_step=1&hw_version=1.7-BD-00"
                + "&not_valid_token=0"));
        if (profile == null) throw new IOException(Ui.tr("O portal não aceitou este MAC.", "The portal didn't accept this MAC."));
        String blocked = profile.optString("blocked", "0"), status = profile.optString("status", "0");
        if ("1".equals(blocked) || (!status.isEmpty() && !"0".equals(status) && profile.optInt("id", 0) == 0)) {
            throw new IOException(Ui.tr("O portal não aceitou este MAC.", "The portal didn't accept this MAC."));
        }
    }

    /** The genres and channels, in Xtream's saved form ("url" each channel's command). Background thread. */
    static JSONObject fetch(String endpoint, String mac) throws IOException {
        try {
            JSONArray genres = jsArray(call(endpoint, mac, "type=itv&action=get_genres"));
            JSONObject all = js(call(endpoint, mac, "type=itv&action=get_all_channels"));
            JSONArray data = all == null ? null : all.optJSONArray("data");
            if (data == null) throw new IOException(Ui.tr("O portal não mandou canais.", "The portal sent no channels."));
            String host = endpoint.replaceFirst("^(https?://[^/]+).*$", "$1");
            JSONArray cats = new JSONArray(), chans = new JSONArray();
            for (int i = 0; genres != null && i < genres.length(); i++) {
                JSONObject g = genres.optJSONObject(i);
                if (g == null || "*".equals(g.optString("id"))) continue;
                cats.put(new JSONObject().put("id", g.optString("id")).put("name", g.optString("title")));
            }
            for (int i = 0; i < data.length(); i++) {
                JSONObject o = data.optJSONObject(i);
                if (o == null || o.optInt("id", 0) <= 0) continue;
                String logo = o.optString("logo", "");
                if (!logo.isEmpty() && !logo.startsWith("http")) logo = host + (logo.startsWith("/") ? "" : "/stalker_portal/misc/logos/320/") + logo;
                chans.put(new JSONObject().put("id", o.optInt("id")).put("num", o.optInt("number", i + 1))
                        .put("name", o.optString("name")).put("logo", logo).put("epg", o.optString("xmltv_id", ""))
                        .put("cat", o.optString("tv_genre_id")).put("arch", 0).put("url", o.optString("cmd")));
            }
            return new JSONObject().put("categories", cats).put("channels", chans);
        } catch (JSONException e) {
            throw new IOException(Ui.tr("A lista de canais veio num formato inesperado.", "The channel list came in an unexpected format."));
        }
    }

    /** A channel's playable address now, from its command (create_link). Background thread (the player's loader). */
    static String link(String endpoint, String mac, String cmd) throws IOException {
        String direct = strip(cmd);
        // Commands with the real address in them (not the portal's placeholder) play as they are.
        if (direct.startsWith("http") && !direct.contains("localhost") && !direct.contains("127.0.0.1")) return direct;
        JSONObject o = js(call(endpoint, mac, "type=itv&action=create_link&cmd=" + Uri.encode(cmd)
                + "&series=&forced_storage=undefined&disable_ad=0&download=0"));
        String url = o == null ? "" : strip(o.optString("cmd"));
        if (!url.startsWith("http")) throw new IOException("no stream link");
        return url;
    }

    /** What's on channel `id` from now on (the portal's short guide). Background thread. */
    static List<Xtream.Program> programs(String endpoint, String mac, int id, int limit) throws IOException {
        List<Xtream.Program> out = new ArrayList<>();
        JSONArray a = jsArray(call(endpoint, mac, "type=itv&action=get_short_epg&ch_id=" + id + "&size=" + limit));
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Xtream.Program p = new Xtream.Program();
            p.start = o.optLong("start_timestamp") * 1000;
            p.end = o.optLong("stop_timestamp") * 1000;
            p.title = o.optString("name");
            p.about = o.optString("descr");
            if (p.end > p.start) {
                Xtream.mark(p);
                out.add(p);
            }
        }
        return out;
    }

    private static String strip(String cmd) {
        String s = cmd == null ? "" : cmd.trim();
        int space = s.indexOf(' ');
        return space > 0 && !s.substring(0, space).contains("://") ? s.substring(space + 1).trim() : s;
    }

    // MARK: - The portal's API

    private static void handshake(String endpoint, String mac) throws IOException {
        JSONObject o = parse(request(endpoint, mac, "type=stb&action=handshake&token=&prehash=0", null));
        String t = o == null ? "" : o.optString("token", "");
        if (t.isEmpty()) throw new IOException("no token");
        token = t;
        tokenFor = endpoint + "|" + mac;
    }

    /** A call with the token (a handshake first, and again once if the token has expired). */
    private static String call(String endpoint, String mac, String query) throws IOException {
        if (token == null || !(endpoint + "|" + mac).equals(tokenFor)) handshake(endpoint, mac);
        String body = request(endpoint, mac, query, token);
        if (body.contains("Authorization failed") || body.trim().isEmpty()) {
            handshake(endpoint, mac);
            body = request(endpoint, mac, query, token);
        }
        return body;
    }

    private static String request(String endpoint, String mac, String query, String bearer) throws IOException {
        HttpURLConnection c;
        try {
            c = (HttpURLConnection) new URL(endpoint + "?" + query + "&JsHttpRequest=1-xml").openConnection();
        } catch (IllegalArgumentException e) {
            throw new IOException(Ui.tr("Endereço do portal inválido.", "That portal address isn't valid."));
        }
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("X-User-Agent", "Model: MAG250; Link: WiFi");
        c.setRequestProperty("Cookie", "mac=" + Uri.encode(mac.toUpperCase(Locale.ROOT)) + "; stb_lang=en; timezone="
                + Uri.encode(java.util.TimeZone.getDefault().getID()));
        if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException(Ui.tr("O servidor respondeu com o erro ", "The server answered with error ") + code + ".");
            try (InputStream in = c.getInputStream()) {
                return Xtream.read(in);
            }
        } catch (java.net.UnknownHostException | java.net.ConnectException | java.net.SocketTimeoutException e) {
            throw new IOException(Ui.tr("Não deu para falar com o servidor. Confira o endereço.", "Couldn't reach the server. Check the address."));
        } finally {
            c.disconnect();
        }
    }

    private static JSONObject parse(String body) {
        try {
            return new JSONObject(body).optJSONObject("js");
        } catch (JSONException e) {
            return null;
        }
    }

    private static JSONObject js(String body) {
        return parse(body);
    }

    private static JSONArray jsArray(String body) {
        try {
            Object js = new JSONObject(body).opt("js");
            if (js instanceof JSONArray) return (JSONArray) js;
            if (js instanceof JSONObject) return ((JSONObject) js).optJSONArray("data");
        } catch (JSONException ignored) {
        }
        return null;
    }

    /** A MAC as the portal wants it: six pairs, upper case, colons ("001a79…" works too). Null if it isn't one. */
    static String mac(String typed) {
        String hex = typed == null ? "" : typed.replaceAll("[^0-9A-Fa-f]", "").toUpperCase(Locale.ROOT);
        if (hex.length() != 12) return null;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 12; i += 2) b.append(i == 0 ? "" : ":").append(hex, i, i + 2);
        return b.toString();
    }
}
