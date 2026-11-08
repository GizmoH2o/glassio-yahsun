package com.glasslauncher.tv.live;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the full guide comes from (Live TV › Settings › Guide › Guide sources): the provider's own, and XMLTV links of
 * your own, in order. All of them make one guide; a channel whose guide ID is in more than one takes it from the first
 * (XmltvStore). Each link keeps how its last update went. The one link of before ("Guide source", which replaced the
 * provider's) becomes the first link, the provider's off, as it was.
 */
final class LiveGuideSources {
    private LiveGuideSources() {}

    /** One link: its address and whether it's in use. */
    static final class Link {
        final String url;
        final boolean on;

        Link(String url, boolean on) {
            this.url = url;
            this.on = on;
        }
    }

    /** A source to read now: its name (the provider's, or the link's site) and its address. */
    static final class Source {
        final String name, url;
        final boolean provider;

        Source(String name, String url, boolean provider) {
            this.name = name;
            this.url = url;
            this.provider = provider;
        }
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("live-options", Context.MODE_PRIVATE);      // per playlist: LivePlaylists puts them aside
    }

    /** The one link of before, carried over the first time (it stood in for the provider's guide). */
    private static void migrate(Context c) {
        SharedPreferences s = sp(c);
        if (s.getBoolean("guideMigrated", false)) return;
        String old = LiveOptions.guideUrl(c);
        SharedPreferences.Editor e = s.edit().putBoolean("guideMigrated", true);
        if (!old.isEmpty()) {
            List<Link> one = new ArrayList<>();
            one.add(new Link(old, true));
            e.putString("guideLinks", write(one)).putBoolean("guideProvider", false);
        }
        e.apply();
    }

    /** Whether the provider's own guide is one of the sources (on unless turned off). */
    static boolean provider(Context c) {
        migrate(c);
        return sp(c).getBoolean("guideProvider", true);
    }

    static void setProvider(Context c, boolean on) {
        migrate(c);
        sp(c).edit().putBoolean("guideProvider", on).apply();
    }

    /** Your links, in order. */
    static List<Link> links(Context c) {
        migrate(c);
        return read(sp(c).getString("guideLinks", "[]"));
    }

    static void setLinks(Context c, List<Link> links) {
        migrate(c);
        sp(c).edit().putString("guideLinks", write(links)).apply();
    }

    /** The sources to read, in order: the provider's (when on and it has one), then the links that are on. */
    static List<Source> active(Context c) {
        List<Source> out = new ArrayList<>();
        if (provider(c)) {
            String p = Xtream.xmltvUrl(c);
            if (p != null && !p.isEmpty()) out.add(new Source(Ui.tr("Guia do provedor", "Provider's guide"), p, true));
        }
        for (Link l : links(c)) if (l.on) out.add(new Source(site(l.url), l.url, false));
        return out;
    }

    /** Whether a source's name (as XmltvStore keeps it) is the provider's guide. */
    static boolean isProvider(String name) {
        return name == null || name.equals("Guia do provedor") || name.equals("Provider's guide") || name.equals(Ui.tr("Guia do provedor", "Provider's guide"));
    }

    /** How a source's last update went ("" never read), in words. */
    static String status(Context c, String url) {
        return sp(c).getString("guideStatus:" + url.hashCode(), "");
    }

    static void setStatus(Context c, String url, String status) {
        sp(c).edit().putString("guideStatus:" + url.hashCode(), status).apply();
    }

    // MARK: - Pure

    /** A link's site, its name in lists (never its whole address: it may carry a login). */
    static String site(String url) {
        if (url == null) return "";
        String s = url.replaceFirst("(?i)^https?://", "");
        int at = s.indexOf('@'), slash = s.indexOf('/');
        if (at >= 0 && (slash < 0 || at < slash)) s = s.substring(at + 1);
        slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(0, colon);
        return s.isEmpty() ? url : s;
    }

    /** Whether `url` can be a link (http or https, something after). */
    static boolean valid(String url) {
        return url != null && url.trim().matches("(?i)^https?://.+");
    }

    static List<Link> read(String json) {
        List<Link> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null || !valid(o.optString("u"))) continue;
                out.add(new Link(o.optString("u").trim(), o.optBoolean("on", true)));
            }
        } catch (JSONException ignored) {      // nothing readable: no links
        }
        return out;
    }

    static String write(List<Link> links) {
        JSONArray a = new JSONArray();
        try {
            for (Link l : links) a.put(new JSONObject().put("u", l.url).put("on", l.on));
        } catch (JSONException ignored) {
        }
        return a.toString();
    }

    /** `links` with the one at `i` moved by `by` (-1 up, +1 down), kept within the list. */
    static List<Link> moved(List<Link> links, int i, int by) {
        List<Link> out = new ArrayList<>(links);
        int j = i + by;
        if (i < 0 || i >= out.size() || j < 0 || j >= out.size()) return out;
        Link l = out.remove(i);
        out.add(j, l);
        return out;
    }
}
