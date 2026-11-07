package com.glasslauncher.tv.live;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The country a channel is for, from how providers write it: in its name ("US: TLC", "TLC (US)", "[UK] Sky News",
 * "TLC USA", a flag) or else its category's ("US | Entertainment"). Shown as a small glass tag beside the name (Live
 * TV › Settings › Channels and categories › Country tags), the name without it; voice search reads it too ("TLC USA").
 * The codes are ISO-3166's, the way people say them: US, UK, CA, JM.
 */
final class LiveCountry {
    private LiveCountry() {}

    /** A name read: its country code ("" when none) and the name without it. */
    static final class Split {
        final String code, name;

        Split(String code, String name) {
            this.code = code;
            this.name = name;
        }
    }

    private static final Set<String> ISO = new HashSet<>();
    /** Other ways providers write a country, to its code. */
    private static final Map<String, String> ALIAS = new HashMap<>();
    /** Codes that may stand in a name with nothing around them ("TLC USA"): only ones that aren't common words. */
    private static final Set<String> PLAIN = new HashSet<>(java.util.Arrays.asList("US", "USA", "UK", "CA", "CAN", "AU",
            "AUS", "NZ", "IRL", "JAM", "UAE", "KSA", "MX", "MEX", "BR", "DE", "FR", "ES", "PT", "NL", "JM", "PH", "ZA", "NG"));
    /** A picture's tags and the like, never a country (SD is also Sudan, TV Tuvalu, AM Armenia). */
    private static final Set<String> NOT = new HashSet<>(java.util.Arrays.asList("SD", "HD", "FHD", "UHD", "HQ", "LQ", "TV",
            "VIP", "PPV", "RAW", "HDR", "EPG", "VOD", "FM", "AM", "PM"));

    static {
        for (String c : Locale.getISOCountries()) ISO.add(c);
        String[][] alias = {{"USA", "US"}, {"UK", "UK"}, {"GB", "UK"}, {"CAN", "CA"}, {"AUS", "AU"}, {"UAE", "AE"},
                {"KSA", "SA"}, {"NZL", "NZ"}, {"IRL", "IE"}, {"JAM", "JM"}, {"MEX", "MX"}, {"BRA", "BR"}, {"GER", "DE"},
                {"DEU", "DE"}, {"FRA", "FR"}, {"ESP", "ES"}, {"ITA", "IT"}, {"POR", "PT"}, {"PRT", "PT"}, {"NED", "NL"},
                {"NLD", "NL"}, {"IND", "IN"}, {"PHI", "PH"}, {"PHL", "PH"}, {"RSA", "ZA"}, {"ZAF", "ZA"}, {"NGA", "NG"},
                {"ARG", "AR"}, {"TUR", "TR"}, {"POL", "PL"}, {"SWE", "SE"}, {"NOR", "NO"}, {"DEN", "DK"}, {"DNK", "DK"},
                {"FIN", "FI"}, {"BEL", "BE"}, {"SUI", "CH"}, {"CHE", "CH"}, {"AUT", "AT"}, {"GRE", "GR"}, {"GRC", "GR"},
                {"TTO", "TT"}, {"BRB", "BB"}, {"BAH", "BS"},
                {"USL", "US"}};       // USL: US Latino ("USL: CINE LATINO")
        for (String[] a : alias) ALIAS.put(a[0], a[1]);
    }

    private static final String SEP = "[:|\\-–—•»/]";
    private static final Pattern BRACKET_PREFIX = Pattern.compile("^\\s*[\\[(|]\\s*([A-Za-z]{2,6})\\s*[\\])|]\\s*" + SEP + "?\\s*(.+)$");
    private static final Pattern PUNCT_PREFIX = Pattern.compile("^\\s*([A-Za-z]{2,6})\\s*" + SEP + "\\s*(.+)$");
    private static final Pattern PLAIN_PREFIX = Pattern.compile("^\\s*([A-Z]{2,3})\\s+(.+)$");
    private static final Pattern BRACKET_SUFFIX = Pattern.compile("^(.+?)\\s*[\\[(|]\\s*([A-Za-z]{2,6})\\s*[\\])|]?\\s*$");
    private static final Pattern PUNCT_SUFFIX = Pattern.compile("^(.+?)\\s*" + SEP + "\\s*([A-Za-z]{2,6})\\s*$");
    private static final Pattern PLAIN_SUFFIX = Pattern.compile("^(.+?)\\s+([A-Z]{2,3})$");
    /** "AU (STAN 52) | Hong Kong China v USA": the code, then the feed's own bracket. */
    private static final Pattern CODE_BRACKET = Pattern.compile("^\\s*([A-Za-z]{2,3})\\s*(\\([^)]*\\)\\s*[|:].+)$");
    /** "DAZN UK 01: …", "Kayo AU 02: …": a service, its country, then the feed's number. */
    private static final Pattern FEED = Pattern.compile("^(.*\\p{L}.*?)\\s+([A-Z]{2,3})\\s+(\\d{1,3}\\s*:.*)$");
    /** "LBW: Cartoon Network": the low-bandwidth copy, said after the name instead. */
    private static final Pattern LOW_BW = Pattern.compile("^\\s*LBW\\s*[:|\\-]\\s*(.+)$");

    /** `raw` as a country code, null when it isn't one ("USA" → "US", "GB" → "UK"). */
    static String code(String raw, boolean plain) {
        if (raw == null) return null;
        String u = raw.toUpperCase(Locale.ROOT);
        if (NOT.contains(u)) return null;
        if (plain && !PLAIN.contains(u)) return null;
        String a = ALIAS.get(u);
        if (a != null) return a;
        return u.length() == 2 && ISO.contains(u) ? u : null;
    }

    /**
     * A name read for its country: from a flag, the start ("US: TLC", "[UK] Sky"), or the end ("TLC (US)", "TLC USA").
     * A bare code at the start counts only for a category ("USA Entertainment"): a channel's could be its name (USA
     * Network).
     */
    static Split split(String name) {
        return split(name, false);
    }

    static Split split(String name, boolean category) {
        Split s = read(name, category);
        // Providers write AR (Argentina's code) for their Arabic channels: Arabic, unless the name says Argentina.
        if ("AR".equals(s.code) && (name == null || !name.toLowerCase(Locale.ROOT).contains("argentin"))) return new Split(ARABIC, s.name);
        return s;
    }

    /** Not a country: the tag of the channels in Arabic (written AR, ARB, ARAB or ARABIC by providers). */
    static final String ARABIC = "ARABIC";
    private static final java.util.Set<String> ARABIC_WORDS = new HashSet<>(java.util.Arrays.asList("ARB", "ARAB", "ARABIC"));

    /** `raw` as a tag in a name: a country's code, or ARABIC; null when it's neither. */
    private static String tag(String raw, boolean plain) {
        if (raw != null && !plain && ARABIC_WORDS.contains(raw.toUpperCase(Locale.ROOT))) return ARABIC;
        return code(raw, plain);
    }

    private static Split read(String name, boolean category) {
        if (name == null) return new Split("", "");
        String s = name.trim();
        Matcher low = LOW_BW.matcher(s);
        if (low.matches()) {
            Split rest = read(low.group(1), category);
            return new Split(rest.code, rest.name + " (Low BW)");
        }
        String flag = flag(s);
        if (flag != null) return new Split(flag, stripFlag(s));
        Matcher m;
        if ((m = BRACKET_PREFIX.matcher(s)).matches() && tag(m.group(1), false) != null) return new Split(tag(m.group(1), false), tidy(m.group(2)));
        if ((m = PUNCT_PREFIX.matcher(s)).matches() && tag(m.group(1), false) != null) return new Split(tag(m.group(1), false), tidy(m.group(2)));
        if ((m = BRACKET_SUFFIX.matcher(s)).matches() && tag(m.group(2), false) != null) return new Split(tag(m.group(2), false), tidy(m.group(1)));
        if ((m = PUNCT_SUFFIX.matcher(s)).matches() && tag(m.group(2), false) != null) return new Split(tag(m.group(2), false), tidy(m.group(1)));
        if ((m = CODE_BRACKET.matcher(s)).matches() && tag(m.group(1), false) != null) return new Split(tag(m.group(1), false), tidy(m.group(2)));
        if ((m = FEED.matcher(s)).matches() && code(m.group(2), true) != null) return new Split(code(m.group(2), true), tidy(m.group(1)) + " " + m.group(3).trim());
        // "5 USA" is the channel 5USA: a word has to be left for the name, or the country word is part of it.
        if ((m = PLAIN_SUFFIX.matcher(s)).matches() && m.group(1).matches(".*\\p{L}.*") && code(m.group(2), true) != null) return new Split(code(m.group(2), true), tidy(m.group(1)));
        if (category && (m = PLAIN_PREFIX.matcher(s)).matches() && code(m.group(1), true) != null) return new Split(code(m.group(1), true), tidy(m.group(2)));
        return new Split("", s);
    }

    /** split(), kept for the names drawn every frame (a few hundred at most). */
    static Split splitCached(String name, boolean category) {
        if (name == null) return new Split("", "");
        Map<String, Split> cache = category ? CATEGORY_CACHE : NAME_CACHE;
        synchronized (cache) {
            Split s = cache.get(name);
            if (s == null) {
                if (cache.size() > 2000) cache.clear();
                s = split(name, category);
                cache.put(name, s);
            }
            return s;
        }
    }

    private static final Map<String, Split> NAME_CACHE = new HashMap<>(), CATEGORY_CACHE = new HashMap<>();

    /** A channel's country: its name's, else its category's ("" when neither says). */
    static String of(Xtream.Channel ch, Map<String, String> categoryCountry) {
        if (ch == null) return "";
        if (startsWithUsState(ch, categoryCountry)) return "US";
        String c = splitCached(ch.name, false).code;
        if (!c.isEmpty() || categoryCountry == null || ch.category == null) return c;
        String cat = categoryCountry.get(ch.category);
        return cat == null ? "" : cat;
    }

    /**
     * A name with its country in words, for plain text where no tag can be drawn ("Next on Sky Sports News UK"); the
     * name as it is for no code.
     */
    static String withCode(String name, String code) {
        if (code == null || code.isEmpty()) return name;
        return name + " " + code;
    }

    /**
     * `ch`'s name with its country, for the home screen's process (and anything without Live TV's categories): the
     * provider's name as it is with the tags off (Live TV › Settings › Country tags).
     */
    static String named(Context c, Xtream.Channel ch) {
        if (ch == null || ch.name == null) return "";
        if (!LiveOptions.countryTags(c)) return ch.name;
        Map<String, String> cats = savedCategories(c);
        return withCode(shownName(ch, cats), of(ch, cats));
    }

    /** `ch`'s name as shown beside its tag: without its country, but a US state stays ("AL | Birmingham | WIAT"). */
    static String shownName(Xtream.Channel ch, Map<String, String> categoryCountry) {
        if (ch == null || ch.name == null) return "";
        if (startsWithUsState(ch, categoryCountry)) return ch.name;
        String n = splitCached(ch.name, false).name;
        return n.isEmpty() ? ch.name : n;
    }

    /** US local channels start with their state, and many states' codes are countries' too: AL Albania, CA Canada, AR Arabic. */
    private static final Set<String> US_STATES = new HashSet<>(java.util.Arrays.asList("AL", "AK", "AZ", "AR", "CA", "CO", "CT",
            "DE", "DC", "FL", "GA", "HI", "ID", "IL", "IN", "IA", "KS", "KY", "LA", "ME", "MD", "MA", "MI", "MN", "MS", "MO", "MT",
            "NE", "NV", "NH", "NJ", "NM", "NY", "NC", "ND", "OH", "OK", "OR", "PA", "RI", "SC", "SD", "TN", "TX", "UT", "VT", "VA",
            "WA", "WV", "WI", "WY"));

    /** In a US category, "AL | Birmingham" is Alabama. Elsewhere the code keeps its usual meaning. */
    private static boolean startsWithUsState(Xtream.Channel ch, Map<String, String> categoryCountry) {
        if (ch.name == null || ch.category == null || categoryCountry == null || !"US".equals(categoryCountry.get(ch.category))) return false;
        Matcher m = PUNCT_PREFIX.matcher(ch.name.trim());
        return m.matches() && US_STATES.contains(m.group(1));
    }

    /** Categories A to Z by the name they show, then the same name by its country tag: Sports, Sports UK, Sports US. Pure. */
    static int compareCategories(String a, String b) {
        Split sa = split(a, true), sb = split(b, true);
        int c = Xtream.fold(sa.name).compareTo(Xtream.fold(sb.name));
        return c != 0 ? c : sa.code.compareTo(sb.code);
    }

    /** Streaming services sold in one country only, as their categories name them (spaces gone, lower case). */
    private static final Map<String, String> SERVICE = new HashMap<>();

    static {
        String[][] service = {{"kayo", "AU"}, {"kayoevents", "AU"}, {"stan", "AU"}, {"tsn+", "CA"}, {"sportsnet+", "CA"},
                {"nowhk", "HK"}, {"starhub", "SG"}, {"astrosports", "MY"}, {"coupangplay", "KR"}, {"monomax", "TH"}};
        for (String[] a : service) SERVICE.put(a[0], a[1]);
    }

    /**
     * A category's country: from its name ("US | Entertainment"), else from any later part of it ("Sports | DAZN UK (2)",
     * "Sports | DE Sports"), else the one country its service is sold in ("Sports | Kayo"). "" for none. Pure.
     */
    static String categoryCode(String name) {
        if (name == null) return "";
        String c = split(name, true).code;
        if (!c.isEmpty()) return c;
        String[] parts = name.split("\\|");
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i].trim().replaceAll("\\s*\\(\\d+\\)$", "");
            c = split(part, true).code;
            if (!c.isEmpty()) return c;
            String s = SERVICE.get(part.replace(" ", "").toLowerCase(Locale.ROOT));
            if (s != null) return s;
        }
        return "";
    }

    /** Each category's country (the ones whose names have one), by category id. */
    static Map<String, String> categories(Xtream.Lineup l) {
        Map<String, String> out = new HashMap<>();
        if (l == null) return out;
        for (Xtream.Category cg : l.categories) {
            if (cg.id == null) continue;
            String c = categoryCode(cg.name);
            if (!c.isEmpty()) out.put(cg.id, c);
        }
        return out;
    }

    /** A flag (two regional indicator letters) at the start or the end, as its code. */
    private static String flag(String s) {
        String f = flagAt(s, 0);
        if (f == null && s.length() >= 4) f = flagAt(s, s.length() - 4);
        return f;
    }

    private static String flagAt(String s, int i) {
        if (i < 0 || s.length() < i + 4) return null;
        int a = s.codePointAt(i), b = s.codePointAt(i + 2);
        if (a < 0x1F1E6 || a > 0x1F1FF || b < 0x1F1E6 || b > 0x1F1FF) return null;
        String code = "" + (char) ('A' + a - 0x1F1E6) + (char) ('A' + b - 0x1F1E6);
        return code(code, false);
    }

    private static String stripFlag(String s) {
        if (flagAt(s, 0) != null) return tidy(s.substring(4));
        return tidy(s.substring(0, s.length() - 4));
    }

    /** Separators left at either end go ("TLC -" → "TLC"). */
    private static String tidy(String s) {
        return s.replaceAll("^[\\s:|\\-–—•»/]+|[\\s:|\\-–—•»/]+$", "");
    }

    // MARK: - For the home screen's process

    private static final String CATEGORIES_FILE = "live-category-countries.json";
    private static Map<String, String> savedCategories;
    private static long savedAt;

    /** Live TV's side, as the list loads: each category's country, for the home screen's cards. Background thread. */
    static void saveCategories(Context c, Map<String, String> categories) {
        try {
            Xtream.write(new java.io.File(c.getFilesDir(), CATEGORIES_FILE), new org.json.JSONObject(categories).toString());
        } catch (java.io.IOException e) {
            AppLog.w("live: couldn't keep the categories' countries");
        }
    }

    /** The home screen's side: what Live TV kept (read again only when it changes). */
    static synchronized Map<String, String> savedCategories(Context c) {
        java.io.File f = new java.io.File(c.getFilesDir(), CATEGORIES_FILE);
        long at = f.lastModified();
        if (savedCategories == null || at != savedAt) {
            Map<String, String> out = new HashMap<>();
            if (f.exists()) {
                try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                    org.json.JSONObject o = new org.json.JSONObject(Xtream.read(in));
                    java.util.Iterator<String> keys = o.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        out.put(key, o.optString(key));
                    }
                } catch (java.io.IOException | org.json.JSONException e) {
                    AppLog.w("live: the categories' countries are unreadable");
                }
            }
            savedCategories = out;
            savedAt = at;
        }
        return savedCategories;
    }

    // MARK: - Showing it

    /** What drawing a tag needs, made the first time one's drawn (and never where there's nothing to draw on). */
    private static final class Pen {
        static final RectF R = new RectF();
        static final Paint FILL = new Paint(Paint.ANTI_ALIAS_FLAG), RIM = new Paint(Paint.ANTI_ALIAS_FLAG);

        static {
            RIM.setStyle(Paint.Style.STROKE);
        }
    }

    /**
     * The tag: the code in small capitals on a rounded glass pill (the info bar's chips' family), its middle on the
     * text's (`baseline`, `text` the name's paint). Returns where it ends; nothing drawn (and `x`) for no code.
     */
    static float drawTag(Canvas c, String code, float x, float baseline, Paint text, Paint label, float k) {
        if (code == null || code.isEmpty()) return x;
        float ph = height(text, k), tw = label.measureText(code), pw = tw + ph * 0.7f;
        float mid = baseline - Ui.halfCap(text);
        Pen.R.set(x, mid - ph / 2, x + pw, mid + ph / 2);
        // On a light plate (dark lettering: the focus) the glass goes dark, as the lettering does.
        boolean onLight = luma(text.getColor()) < 128;
        Pen.FILL.setColor(onLight ? 0x1A000000 : LiveTheme.chip());
        c.drawRoundRect(Pen.R, ph / 2, ph / 2, Pen.FILL);
        Pen.RIM.setStrokeWidth(Math.max(1, 1.2f * k));
        Pen.RIM.setColor(onLight ? 0x33000000 : 0x33FFFFFF);
        c.drawRoundRect(Pen.R, ph / 2, ph / 2, Pen.RIM);
        c.drawText(code, x + (pw - tw) / 2, mid + Ui.halfCap(label), label);
        return Pen.R.right;
    }

    /** The tag's lettering for a name drawn in `text`: smaller, semibold, spaced, the name's color a little softer. */
    static Paint label(Paint text, Paint out) {
        if (out == null) out = new Paint(Paint.ANTI_ALIAS_FLAG);
        out.setTypeface(Ui.semibold);
        out.setTextSize(text.getTextSize() * 0.64f);
        out.setLetterSpacing(0.08f);
        out.setColor((text.getColor() & 0x00FFFFFF) | 0xE6000000);
        return out;
    }

    /**
     * The tag inside a line of text (a menu's message: "12:00 AM – 12:30 AM · Cartoon Network West US"), drawn as
     * [drawTag] beside the name before it; `k` the screen's scale.
     */
    static final class Span extends android.text.style.ReplacementSpan {
        private final String code;
        private final float k;
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);

        Span(String code, float k) {
            this.code = code;
            this.k = k;
        }

        @Override public int getSize(Paint p, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            return Math.round(tagWidth(code, p, label(p, label), k));
        }

        @Override public void draw(Canvas c, CharSequence text, int start, int end, float x, int top, int y, int bottom, Paint p) {
            drawTag(c, code, x + 12 * k, y, p, label(p, label), k);
        }
    }

    /** Where a tag on the line at `baseline` starts at the top. */
    static float tagTop(Paint text, float baseline, float k) {
        return baseline - Ui.halfCap(text) - height(text, k) / 2;
    }

    private static float height(Paint text, float k) {
        return Math.max(text.getTextSize() * 0.9f, 20 * k);
    }

    private static int luma(int color) {
        return (((color >> 16) & 255) * 3 + ((color >> 8) & 255) * 6 + (color & 255)) / 10;
    }

    /** How wide the tag is for `code` (0: none), with the gap before it. */
    static float tagWidth(String code, Paint text, Paint label, float k) {
        if (code == null || code.isEmpty()) return 0;
        return label.measureText(code) + height(text, k) * 0.7f + 12 * k;
    }

    /** Whether tags show (Live TV's setting), read once per process and kept in step as it changes. */
    static boolean on(Context c) {
        if (shown == null) shown = LiveOptions.countryTags(c);
        return shown;
    }

    static void changed(boolean on) {
        shown = on;
    }

    private static Boolean shown;
}
