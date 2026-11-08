package com.glasslauncher.tv.live;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * The player's stream badges as pictures (Live TV › Settings › Channels › Stream badges): the badge set's pictures
 * for the resolution, the video codec, HDR, the sound and its channels, each on its tag (the set's colors), instead of
 * the words. The set is a JSON file (GizmoH2o/glassio-yahsun-badges: groups and "filters", each with an id, a picture
 * and its tag's colors), read again once a week, the last one kept. Its pictures come from where it says, as the
 * channels' logos do (LiveLogos); one that isn't here (yet) is said in words.
 */
final class LiveBadgeArt {
    private LiveBadgeArt() {}

    static final String SET_URL = "https://raw.githubusercontent.com/GizmoH2o/glassio-yahsun-badges/refs/heads/main/mono_white_badges.json";
    private static final long REFRESH = 7 * 24 * 3600_000L;

    /** One of the set's badges: its picture and its tag. */
    static final class Art {
        final String url;
        final int tag, border, ink;
        final boolean filled;

        Art(String url, int tag, int border, int ink, boolean filled) {
            this.url = url;
            this.tag = tag;
            this.border = border;
            this.ink = ink;
            this.filled = filled;
        }
    }

    /** A fact about the stream: the set's badge for it ("" none) and the words for it. */
    static final class Tag {
        final String id, words;

        Tag(String id, String words) {
            this.id = id;
            this.words = words;
        }
    }

    private static volatile Map<String, Art> set;
    private static volatile boolean loading;

    /** The set's badges by id (pure): those with a picture, enabled. */
    static Map<String, Art> parse(String json) {
        Map<String, Art> out = new HashMap<>();
        try {
            JSONArray filters = new JSONObject(json).optJSONArray("filters");
            if (filters == null) return out;
            for (int i = 0; i < filters.length(); i++) {
                JSONObject f = filters.optJSONObject(i);
                if (f == null || !f.optBoolean("isEnabled", true)) continue;
                String id = f.optString("id"), url = f.optString("imageURL");
                if (id.isEmpty() || !(url.startsWith("https://") || url.startsWith("http://"))) continue;
                out.put(id, new Art(url, color(f.optString("tagColor"), 0xFFFFFFFF), color(f.optString("borderColor"), 0xFFFFFFFF),
                        color(f.optString("textColor"), 0xFF000000), !"outline".equalsIgnoreCase(f.optString("tagStyle"))));
            }
        } catch (org.json.JSONException ignored) {      // not the set: nothing from it, the words instead
        }
        return out;
    }

    /** "#RRGGBB" or "#AARRGGBB" (pure: no Android); anything else, `fallback`. */
    static int color(String s, int fallback) {
        if (s == null || !s.startsWith("#") || (s.length() != 7 && s.length() != 9)) return fallback;
        try {
            long v = Long.parseLong(s.substring(1), 16);
            return s.length() == 7 ? (int) (0xFF000000L | v) : (int) v;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** The set's badge `id`, or null (not in the set, or the set not here yet: it's asked for, `loaded` runs after). */
    static Art art(Context c, String id, Runnable loaded) {
        Map<String, Art> s = set;
        if (s == null) {
            load(c, loaded);
            return null;
        }
        return id == null || id.isEmpty() ? null : s.get(id);
    }

    private static void load(Context c, Runnable loaded) {
        if (loading) return;
        loading = true;
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            File cache = new File(app.getFilesDir(), "live-badge-set.json");
            String json = null;
            if (!cache.exists() || System.currentTimeMillis() - cache.lastModified() > REFRESH) {
                try {
                    json = fetch(SET_URL);
                    try (FileOutputStream out = new FileOutputStream(cache)) {
                        out.write(json.getBytes(StandardCharsets.UTF_8));
                    }
                } catch (IOException e) {
                    AppLog.w("live: the badge set couldn't be read: " + e.getMessage());
                }
            }
            if (json == null && cache.exists()) {
                try {
                    json = new String(java.nio.file.Files.readAllBytes(cache.toPath()), StandardCharsets.UTF_8);
                } catch (IOException ignored) {
                }
            }
            set = json == null ? new HashMap<>() : parse(json);
            loading = false;
            if (loaded != null) loaded.run();
        }, "live-badge-set").start();
    }

    private static String fetch(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("User-Agent", Live.USER_AGENT);
        try (InputStream in = conn.getInputStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                if (out.size() > 2_000_000) throw new IOException("badge set too big");
            }
            return out.toString("UTF-8");
        } finally {
            conn.disconnect();
        }
    }

    // MARK: - The stream's facts, as the set's ids (pure)

    /** The resolution's badge for a picture `lines` tall (the shorter side). */
    static String resolutionId(int lines) {
        return lines >= 2000 ? "r-4k" : lines >= 1400 ? "r-1440p" : lines >= 1000 ? "r-1080" : lines >= 700 ? "r-720"
                : lines >= 560 ? "r-576p" : lines >= 460 ? "r-480p" : lines >= 340 ? "r-360p" : lines >= 230 ? "r-240p" : "r-144p";
    }

    /** The video codec's badge (its MIME type); "" for none in the set. */
    static String videoId(String mime) {
        if (mime == null) return "";
        switch (mime) {
            case "video/hevc": return "s-h265";
            case "video/avc": return "s-h264";
            case "video/av01": return "ge-av1";
            default: return "";
        }
    }

    /** The sound's badge (its MIME type); "" for none in the set. */
    static String audioId(String mime) {
        if (mime == null) return "";
        switch (mime) {
            case "audio/eac3-joc": return "a-atmos-ddplus";
            case "audio/eac3": return "a-dp";
            case "audio/ac3": return "a-dd";
            case "audio/mp4a-latm": return "a-aac";
            case "audio/opus": return "a-opus";
            case "audio/flac": return "a-flac";
            case "audio/vnd.dts": return "a-dts";
            case "audio/vnd.dts.hd": return "a-dtshd";
            case "audio/true-hd": return "a-th";
            default: return "";
        }
    }

    /** The channels' badge (how many); "" for none in the set (mono). */
    static String channelsId(int count) {
        return count >= 8 ? "ch-71" : count >= 7 ? "ch-61" : count >= 6 ? "ch-51" : count == 2 ? "ch-20" : "";
    }

    // MARK: - Drawing

    /** The drawing's paints, made the first time it draws (not when the set is read: that runs off Android too, in tests). */
    private static final class Pens {
        static final Paint FILL = new Paint(Paint.ANTI_ALIAS_FLAG), RIM = new Paint(Paint.ANTI_ALIAS_FLAG),
                PIC = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        static final RectF BOX = new RectF(), DST = new RectF();
        static final Paint WORDS = new Paint(Paint.ANTI_ALIAS_FLAG);

        static {
            RIM.setStyle(Paint.Style.STROKE);
            WORDS.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD));
        }
    }

    /** The tag of words with no picture (the frame rate): the set's tag colors, its resolution badge's, else white on black. */
    private static Art wordsLook(Context c) {
        Art a = art(c, "r-1080", null);
        return a != null ? a : new Art("", 0xFFFFFFFF, 0xFFFFFFFF, 0xFF000000, true);
    }

    /** How wide `words` are on a tag `h` tall (wordsTag). */
    static float wordsWidth(String words, float h) {
        Paint p = Pens.WORDS;
        p.setTextSize(h * 0.5f);
        return p.measureText(words) + 2 * h * 0.32f;
    }

    /**
     * `words` on a tag like the set's (a white mono badge: its tag colors, bold lettering), `h` tall with its middle at
     * `mid`, from x: what the set has no picture for (the frame rate), or a picture not here yet. Returns its right edge.
     */
    static float wordsTag(Context c, Canvas canvas, String words, float x, float mid, float h, float k) {
        Art a = wordsLook(c);
        Paint p = Pens.WORDS;
        p.setTextSize(h * 0.5f);
        RectF box = Pens.BOX;
        box.set(x, mid - h / 2, x + wordsWidth(words, h), mid + h / 2);
        float rr = 6 * k;
        if (a.filled) {
            Pens.FILL.setColor(a.tag);
            canvas.drawRoundRect(box, rr, rr, Pens.FILL);
        }
        Pens.RIM.setColor(a.border);
        Pens.RIM.setStrokeWidth(Math.max(1, 1.5f * k));
        canvas.drawRoundRect(box, rr, rr, Pens.RIM);
        p.setColor(a.ink);
        canvas.drawText(words, x + h * 0.32f, mid + Ui.halfCap(p), p);
        return box.right;
    }

    /** How wide tag `t` is as a picture `h` tall, or -1 when it can't be one (as draw). */
    static float width(Context c, Tag t, float h, float k, Runnable loaded) {
        Art a = art(c, t.id, loaded);
        Bitmap b = a == null ? null : LiveLogos.get(c, a.url, loaded);
        if (b == null || b.getHeight() <= 0) return -1;
        return h * 0.62f * b.getWidth() / b.getHeight() + 2 * h * 0.32f;
    }

    /**
     * Tag `t` as the set's picture on its tag, `h` tall with its middle at `mid`, from x; returns its right edge, or -1
     * when it can't be a picture (not in the set, or its picture not here yet: the caller says it in words). Main thread.
     */
    static float draw(Context c, Canvas canvas, Tag t, float x, float mid, float h, float k, Runnable loaded) {
        Art a = art(c, t.id, loaded);
        Bitmap b = a == null ? null : LiveLogos.get(c, a.url, loaded);
        if (b == null || b.getHeight() <= 0) return -1;
        Paint FILL = Pens.FILL, RIM = Pens.RIM, PIC = Pens.PIC;
        RectF BOX = Pens.BOX, DST = Pens.DST;
        float glyphH = h * 0.62f, glyphW = glyphH * b.getWidth() / b.getHeight(), pad = h * 0.32f;
        BOX.set(x, mid - h / 2, x + glyphW + 2 * pad, mid + h / 2);
        float rr = 6 * k;
        if (a.filled) {
            FILL.setColor(a.tag);
            canvas.drawRoundRect(BOX, rr, rr, FILL);
        }
        RIM.setColor(a.border);
        RIM.setStrokeWidth(Math.max(1, 1.5f * k));
        canvas.drawRoundRect(BOX, rr, rr, RIM);
        DST.set(x + pad, mid - glyphH / 2, x + pad + glyphW, mid + glyphH / 2);
        PIC.setColorFilter(new PorterDuffColorFilter(a.ink, PorterDuff.Mode.SRC_IN));
        canvas.drawBitmap(b, null, DST, PIC);
        return BOX.right;
    }
}
