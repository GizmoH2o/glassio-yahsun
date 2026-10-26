package com.glasslauncher.tv.live;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.zip.GZIPInputStream;

/**
 * The full guide (XMLTV): the provider's whole guide file (xmltv.php, or a link typed in the settings), read as it
 * downloads (it can be hundreds of MB, never held in memory), keeping only the programs of this lineup's channels in
 * the days the settings keep, in a small SQLite database. A new one is built beside the one in use and replaces it
 * when done, so the guide works meanwhile. Live TV's guide reads it first, and Search looks for shows in it.
 */
final class XmltvStore {
    private XmltvStore() {}

    private static final String DB = "live-guide.db", BUILDING = "live-guide-new.db";
    private static final long HOUR = 3600_000L, DAY = 24 * HOUR;
    private static final int ABOUT_MAX = 320;
    /** The database's layout: 2 added the shows' pictures (an older one is fetched again). */
    /** The guide's layout: 3 added each programme's rating, 4 the channels' logos (an older guide is fetched again). */
    private static final int VERSION = 5;      // 5: sub-title and repeat kept apart

    private static SQLiteDatabase db;
    /** The guide in use has the sub and rep columns (one from before VERSION 5 doesn't, until it's fetched again). */
    private static boolean dbSub;
    private static long dbStamp;
    private static volatile boolean running;
    private static volatile String progress = "";

    // MARK: - Reading

    /** The database in use, opened read-only (again when a new one replaced it), or null (none yet). */
    private static synchronized SQLiteDatabase read(Context c) {
        File f = c.getDatabasePath(DB);
        if (!f.exists()) {
            close();
            return null;
        }
        long stamp = f.lastModified() * 31 + f.length();
        if (db == null || stamp != dbStamp) {
            close();
            try {
                db = SQLiteDatabase.openDatabase(f.getPath(), null, SQLiteDatabase.OPEN_READONLY);
                dbStamp = stamp;
                try (Cursor q = db.rawQuery("SELECT * FROM p LIMIT 0", null)) {
                    dbSub = q.getColumnIndex("sub") >= 0;
                }
            } catch (RuntimeException e) {
                AppLog.w("guide: unreadable: " + e.getMessage());
                db = null;
            }
        }
        return db;
    }

    /** The database let go of (it's about to move: another playlist). */
    static void release() {
        close();
    }

    private static synchronized void close() {
        if (db != null) db.close();
        db = null;
    }

    /**
     * The programs of guide channel `epg` (a channel's epg_channel_id) between `from` and `to`, in order; null when
     * there's no full guide, empty when it has nothing for that channel.
     */
    static List<Xtream.Program> programs(Context c, String epg, long from, long to) {
        if (epg == null || epg.isEmpty()) return null;
        synchronized (XmltvStore.class) {
            SQLiteDatabase d = read(c);
            if (d == null) return null;
            List<Xtream.Program> out = new ArrayList<>();
            long shift = LiveOptions.guideShift(c);
            try (Cursor q = d.rawQuery("SELECT start, stop, title, about, icon, age" + (dbSub ? ", sub, rep" : "")
                    + " FROM p WHERE epg = ? AND stop > ? AND start < ? ORDER BY start",
                    new String[] {epg.toLowerCase(Locale.ROOT), String.valueOf(from - shift), String.valueOf(to - shift)})) {
                while (q.moveToNext()) {
                    Xtream.Program p = new Xtream.Program();
                    p.start = q.getLong(0) + shift;
                    p.end = q.getLong(1) + shift;
                    p.title = q.getString(2);
                    p.about = q.getString(3);
                    p.icon = q.isNull(4) ? "" : q.getString(4);
                    p.age = q.isNull(5) ? -1 : q.getInt(5);
                    if (dbSub) {
                        p.sub = q.isNull(6) ? "" : q.getString(6);
                        p.repeat = q.getInt(7) != 0;
                    }
                    Xtream.mark(p);
                    if (!out.isEmpty() && p.start < out.get(out.size() - 1).end) continue;   // two sources at the same time
                    out.add(p);
                }
            } catch (RuntimeException e) {
                return null;
            }
            return out;
        }
    }

    /**
     * The age a rating means (US TV and films, UK, Germany's FSK, and a plain number: "16", "16+", "FSK 16"), -1 when
     * it says none.
     */
    static int ageOf(String rating) {
        if (rating == null) return -1;
        String r = rating.trim().toUpperCase(Locale.ROOT).replace("_", "-");
        switch (r) {
            case "TV-Y": case "TV-G": case "G": case "U": case "UC": case "E": case "ALL": return 0;
            case "TV-Y7": case "TV-Y7-FV": return 7;
            case "TV-PG": case "PG": return 10;
            case "PG-13": return 13;
            case "TV-14": return 14;
            case "TV-MA": case "R": return 17;
            case "NC-17": case "R18": case "X": case "XXX": case "ADULT": case "ADULTS": return 18;
            case "12A": return 12;
            default: break;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,2})").matcher(r);
        if (m.find()) {
            int n = Integer.parseInt(m.group(1));
            return n <= 21 ? n : -1;
        }
        return -1;
    }

    private static Map<String, String> logos;
    private static long logosStamp = -1;

    /** The guide's logos by channel (epg, lowercase), read once per guide. */
    static Map<String, String> logos(Context c) {
        synchronized (XmltvStore.class) {
            SQLiteDatabase d = read(c);
            if (d == null) return new HashMap<>();
            if (logos != null && logosStamp == dbStamp) return logos;
            Map<String, String> out = new HashMap<>();
            try (Cursor q = d.rawQuery("SELECT epg, icon FROM ch", null)) {
                while (q.moveToNext()) out.put(q.getString(0), q.getString(1));
            } catch (RuntimeException ignored) {        // a guide from before logos
            }
            logos = out;
            logosStamp = dbStamp;
            return out;
        }
    }

    /** Channels the provider gave no logo: the full guide's, when it has one. */
    static void fillLogos(Context c, Xtream.Lineup l) {
        if (!LiveOptions.fullGuide(c)) return;
        Map<String, String> found = logos(c);
        if (found.isEmpty()) return;
        for (Xtream.Category cg : l.categories) {
            for (Xtream.Channel ch : cg.channels) {
                if ((ch.logo == null || ch.logo.isEmpty()) && ch.epg != null && !ch.epg.isEmpty()) {
                    String icon = found.get(ch.epg.toLowerCase(Locale.ROOT));
                    if (icon != null) ch.logo = icon;
                }
            }
        }
    }

    /** A show found by search: its title and when it starts. */
    static final class Found {
        String title, icon, epg;
        /** The title folded (what the search compares); only findAny fills it. */
        String key = "";
        long start, end;
    }

    /**
     * Every airing between `from` and `to` whose title has all the words of any one of `phrases` (already folded), in
     * one pass over the guide instead of one per phrase; each with its folded title (`key`) so the caller can tell which
     * phrase it matched. At most `limit`, by start. Background thread.
     */
    static List<Found> findAny(Context c, List<String[]> phrases, long from, long to, int limit) {
        List<Found> out = new ArrayList<>();
        if (phrases.isEmpty()) return out;
        long shift = LiveOptions.guideShift(c);
        StringBuilder where = new StringBuilder("stop > ? AND start < ? AND (");
        List<String> args = new ArrayList<>();
        args.add(String.valueOf(from - shift));
        args.add(String.valueOf(to - shift));
        for (int i = 0; i < phrases.size(); i++) {
            where.append(i == 0 ? "(" : " OR (");
            String[] words = phrases.get(i);
            for (int j = 0; j < words.length; j++) {
                where.append(j == 0 ? "key LIKE ?" : " AND key LIKE ?");
                args.add("%" + words[j].replace("%", "").replace("_", "") + "%");
            }
            where.append(')');
        }
        where.append(')');
        synchronized (XmltvStore.class) {
            SQLiteDatabase d = read(c);
            if (d == null) return out;
            try (Cursor q = d.rawQuery("SELECT epg, title, start, stop, icon, key FROM p WHERE " + where + " ORDER BY start LIMIT " + limit,
                    args.toArray(new String[0]))) {
                while (q.moveToNext()) {
                    Found f = new Found();
                    f.epg = q.getString(0);
                    f.title = q.getString(1);
                    f.start = q.getLong(2) + shift;
                    f.end = q.getLong(3) + shift;
                    f.icon = q.isNull(4) ? "" : q.getString(4);
                    f.key = q.isNull(5) ? "" : q.getString(5);
                    out.add(f);
                }
            } catch (RuntimeException ignored) {
            }
        }
        return out;
    }

    /**
     * Every airing of a show whose title has every word (already folded) between `from` and `to`, on any guide channel
     * (each with its `epg`, lowercase): at most `limit`, by start. Background thread.
     */
    static List<Found> findAll(Context c, String[] words, long from, long to, int limit) {
        List<Found> out = new ArrayList<>();
        if (words.length == 0) return out;
        long shift = LiveOptions.guideShift(c);
        StringBuilder where = new StringBuilder("stop > ? AND start < ?");
        List<String> args = new ArrayList<>();
        args.add(String.valueOf(from - shift));
        args.add(String.valueOf(to - shift));
        for (String w : words) {
            where.append(" AND key LIKE ?");
            args.add("%" + w.replace("%", "").replace("_", "") + "%");
        }
        synchronized (XmltvStore.class) {
            SQLiteDatabase d = read(c);
            if (d == null) return out;
            try (Cursor q = d.rawQuery("SELECT epg, title, start, stop, icon FROM p WHERE " + where + " ORDER BY start LIMIT " + limit,
                    args.toArray(new String[0]))) {
                while (q.moveToNext()) {
                    Found f = new Found();
                    f.epg = q.getString(0);
                    f.title = q.getString(1);
                    f.start = q.getLong(2) + shift;
                    f.end = q.getLong(3) + shift;
                    f.icon = q.isNull(4) ? "" : q.getString(4);
                    out.add(f);
                }
            } catch (RuntimeException ignored) {
            }
        }
        return out;
    }

    /**
     * The guide channels airing a show whose title has every word (already folded) between `from` and `to`: for
     * each, the first such show; at most `limit`, soonest first. Background thread.
     */
    static Map<String, Found> find(Context c, String[] words, long from, long to, int limit) {
        Map<String, Found> out = new LinkedHashMap<>();
        if (words.length == 0) return out;
        long shift = LiveOptions.guideShift(c);
        StringBuilder where = new StringBuilder("stop > ? AND start < ?");
        List<String> args = new ArrayList<>();
        args.add(String.valueOf(from - shift));
        args.add(String.valueOf(to - shift));
        for (String w : words) {
            where.append(" AND key LIKE ?");
            args.add("%" + w.replace("%", "").replace("_", "") + "%");
        }
        synchronized (XmltvStore.class) {
            SQLiteDatabase d = read(c);
            if (d == null) return out;
            try (Cursor q = d.rawQuery("SELECT epg, title, start, stop, icon FROM p WHERE " + where + " ORDER BY start LIMIT " + (limit * 4),
                    args.toArray(new String[0]))) {
                while (q.moveToNext() && out.size() < limit) {
                    String epg = q.getString(0);
                    if (out.containsKey(epg)) continue;
                    Found f = new Found();
                    f.title = q.getString(1);
                    f.start = q.getLong(2) + shift;
                    f.end = q.getLong(3) + shift;
                    f.icon = q.isNull(4) ? "" : q.getString(4);
                    out.put(epg, f);
                }
            } catch (RuntimeException ignored) {
            }
        }
        return out;
    }

    /** When the guide in use was fetched (0: none, or one in an older layout) and how many programs it has. */
    static long updatedAt(Context c) {
        return meta(c, "v") >= VERSION ? meta(c, "at") : 0;
    }

    static long count(Context c) {
        return meta(c, "count");
    }

    private static long meta(Context c, String key) {
        synchronized (XmltvStore.class) {
            SQLiteDatabase d = read(c);
            if (d == null) return 0;
            try (Cursor q = d.rawQuery("SELECT v FROM meta WHERE k = ?", new String[] {key})) {
                return q.moveToNext() ? Long.parseLong(q.getString(0)) : 0;
            } catch (RuntimeException e) {
                return 0;
            }
        }
    }

    /** Whether one is being fetched now, and how far ("34%"; the settings show it). */
    static boolean running() {
        return running;
    }

    static String progress() {
        return progress;
    }

    /** Signed out: it was that provider's guide. */
    static void clear(Context c) {
        close();
        //noinspection ResultOfMethodCallIgnored
        c.getDatabasePath(DB).delete();
        //noinspection ResultOfMethodCallIgnored
        new File(c.getDatabasePath(DB).getPath() + "-journal").delete();
    }

    // MARK: - Fetching

    /**
     * Fetches the full guide when it's on and old: older than the hours picked, or (updating on start) than an hour.
     * `now`: fetch anyway (Update now). `done` runs on the background thread when a new one is in use.
     */
    static void updateIfStale(Context context, Xtream.Lineup lineup, boolean now, Runnable done) {
        Context c = context.getApplicationContext();
        if (!LiveOptions.fullGuide(c) || running || lineup == null) return;
        long age = System.currentTimeMillis() - updatedAt(c);
        boolean stale = now || age > LiveOptions.guideHours(c) * HOUR || (LiveOptions.guideOnStart(c) && age > HOUR);
        if (!stale) return;
        running = true;
        progress = "";
        // A copy of what it needs: the lineup changes on the screen's thread.
        Set<String> wanted = new HashSet<>();
        int archive = 0;
        for (Xtream.Channel ch : lineup.all()) {
            if (ch.epg != null && !ch.epg.isEmpty()) wanted.add(ch.epg.toLowerCase(Locale.ROOT));
            archive = Math.max(archive, ch.archive);
        }
        final int pastDays = Math.max(archive, LiveOptions.pastDays(c));
        new Thread(() -> {
            try {
                long t0 = System.currentTimeMillis();
                long n = build(c, wanted, pastDays);
                AppLog.w("guide: " + n + " programs for " + wanted.size() + " guide channels in "
                        + (System.currentTimeMillis() - t0) / 1000 + " s, " + c.getDatabasePath(DB).length() / 1_000_000 + " MB");
                if (done != null) done.run();
            } catch (Exception e) {
                AppLog.w("guide: couldn't fetch: " + e.getMessage());   // never the address: it carries the login
                progress = Ui.tr("Falhou: ", "Failed: ") + e.getMessage();
            } finally {
                running = false;
            }
        }, "full-guide").start();
    }

    private static long build(Context c, Set<String> wanted, int pastDays) throws IOException, XmlPullParserException {
        long now = System.currentTimeMillis();
        long from = now - pastDays * DAY, to = now + LiveOptions.daysAhead(c) * DAY;
        String custom = LiveOptions.guideUrl(c);
        String url = custom.isEmpty() ? Xtream.xmltvUrl(c) : custom;
        if (url == null || url.isEmpty()) throw new IOException(Ui.tr("Esta lista não tem guia completo; ponha um em Fonte do guia.",
                "This playlist has no full guide; set one in Guide source."));
        File building = c.getDatabasePath(BUILDING);
        //noinspection ResultOfMethodCallIgnored
        building.getParentFile().mkdirs();
        SQLiteDatabase.deleteDatabase(building);
        SQLiteDatabase w = SQLiteDatabase.openOrCreateDatabase(building, null);
        long count = 0, pictures = 0;
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        InputStream opened = null;      // closed at the end: a GZIPInputStream left open leaks its Inflater
        try {
            w.execSQL("CREATE TABLE p (epg TEXT, start INTEGER, stop INTEGER, title TEXT, key TEXT, about TEXT, icon TEXT, age INTEGER, "
                    + "sub TEXT, rep INTEGER)");
            w.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT)");
            w.execSQL("CREATE TABLE ch (epg TEXT PRIMARY KEY, icon TEXT)");
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(60_000);
            conn.setRequestProperty("User-Agent", Live.USER_AGENT);
            conn.setRequestProperty("Accept-Encoding", "gzip");
            int code = conn.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            final long length = conn.getContentLengthLong();
            final long[] read = {0};
            InputStream raw = new FilterInputStream(conn.getInputStream()) {     // counts, for the progress
                @Override public int read() throws IOException {
                    int b = super.read();
                    if (b >= 0) read[0]++;
                    return b;
                }

                @Override public int read(byte[] b, int off, int len) throws IOException {
                    int n = super.read(b, off, len);
                    if (n > 0) read[0] += n;
                    return n;
                }
            };
            BufferedInputStream in = new BufferedInputStream(raw, 1 << 16);
            in.mark(2);
            boolean gzip = in.read() == 0x1f && in.read() == 0x8b;     // .xml.gz, or gzip on the way
            in.reset();
            InputStream xml = gzip ? new GZIPInputStream(in, 1 << 16) : in;
            opened = xml;
            XmlPullParser p = Xml.newPullParser();
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            p.setInput(xml, null);
            SQLiteStatement insert = w.compileStatement("INSERT INTO p VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
            SQLiteStatement logo = w.compileStatement("INSERT OR IGNORE INTO ch VALUES (?, ?)");
            Set<String> seen = new HashSet<>();
            Map<String, Long> starts = new HashMap<>();
            Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT);
            w.beginTransaction();
            for (int e = p.getEventType(); e != XmlPullParser.END_DOCUMENT; e = p.next()) {
                if (e == XmlPullParser.START_TAG && "channel".equals(p.getName())) {     // its logo (<icon src=…>)
                    String id = p.getAttributeValue(null, "id");
                    String key = id == null ? "" : id.toLowerCase(Locale.ROOT);
                    String src = "";
                    for (int depth = 1; depth > 0; ) {
                        int t = p.next();
                        if (t == XmlPullParser.START_TAG) {
                            if (depth == 1 && "icon".equals(p.getName()) && src.isEmpty()) {
                                String s = p.getAttributeValue(null, "src");
                                if (s != null && s.startsWith("http")) src = s;
                            }
                            depth++;
                        } else if (t == XmlPullParser.END_TAG) {
                            depth--;
                        } else if (t == XmlPullParser.END_DOCUMENT) {
                            break;
                        }
                    }
                    if (!src.isEmpty() && wanted.contains(key)) {
                        logo.bindString(1, key);
                        logo.bindString(2, src);
                        logo.executeInsert();
                    }
                    continue;
                }
                if (e != XmlPullParser.START_TAG || !"programme".equals(p.getName())) continue;
                String channel = p.getAttributeValue(null, "channel");
                String key = channel == null ? "" : channel.toLowerCase(Locale.ROOT);
                long start = time(p.getAttributeValue(null, "start"), utc), stop = time(p.getAttributeValue(null, "stop"), utc);
                if (!wanted.contains(key) || start <= 0 || stop <= start || stop <= from || start >= to) {
                    skip(p);
                    continue;
                }
                String title = "", sub = "", about = "", icon = "";
                boolean fresh = false, live = false, inRating = false, repeat = false;
                int age = -1;
                for (int depth = 1; depth > 0; ) {
                    int t = p.next();
                    if (t == XmlPullParser.START_TAG) {
                        String n = p.getName();
                        if (inRating && depth == 2 && "value".equals(n)) {     // <rating><value>TV-14</value></rating>
                            age = Math.max(age, ageOf(p.nextText()));
                            continue;
                        }
                        if (depth == 1 && "rating".equals(n)) inRating = true;
                        if (depth == 1 && ("title".equals(n) || "sub-title".equals(n) || "desc".equals(n))) {
                            String text = p.nextText().trim();     // ends on its end tag: the depth stays
                            if ("title".equals(n) && title.isEmpty()) title = text;
                            else if ("sub-title".equals(n) && sub.isEmpty()) sub = text;
                            else if ("desc".equals(n) && about.isEmpty()) about = text;
                            continue;
                        }
                        if (depth == 1 && ("new".equals(n) || "premiere".equals(n))) fresh = true;
                        if (depth == 1 && "live".equals(n)) live = true;
                        if (depth == 1 && "previously-shown".equals(n)) repeat = true;
                        if (depth == 1 && "icon".equals(n) && icon.isEmpty()) {
                            String src = p.getAttributeValue(null, "src");
                            if (src != null && src.startsWith("http")) icon = src;
                        }
                        depth++;
                    } else if (t == XmlPullParser.END_TAG) {
                        depth--;
                        if (depth == 1) inRating = false;
                    } else if (t == XmlPullParser.END_DOCUMENT) {
                        break;
                    }
                }
                // The same programme twice (two guide sources for one channel): the first one stays.
                Long had = starts.get(key + start);
                if (had != null) continue;
                if (starts.size() > 200_000) starts.clear();
                starts.put(key + start, stop);
                String episode = sub.length() > 120 ? sub.substring(0, 119).trim() + "…" : sub;
                if (!sub.isEmpty()) about = about.isEmpty() ? sub : sub + "\n" + about;
                if (about.length() > ABOUT_MAX) about = about.substring(0, ABOUT_MAX - 1).trim() + "…";
                insert.bindString(1, key);
                insert.bindLong(2, start);
                insert.bindLong(3, stop);
                insert.bindString(4, title + (fresh ? " [New]" : "") + (live ? " [Live]" : ""));
                insert.bindString(5, Xtream.fold(title));
                insert.bindString(6, about);
                insert.bindString(7, icon);
                insert.bindLong(8, age);
                insert.bindString(9, episode);
                insert.bindLong(10, repeat && !fresh && !live ? 1 : 0);
                if (!icon.isEmpty()) pictures++;
                insert.executeInsert();
                seen.add(key);
                if (++count % 5000 == 0) {     // a transaction every few thousand: fast, the journal stays small
                    w.setTransactionSuccessful();
                    w.endTransaction();
                    w.beginTransaction();
                    progress = length > 0 ? Math.min(99, read[0] * 100 / length) + "%"
                            : String.format(Locale.ROOT, "%,d", count);
                }
            }
            w.setTransactionSuccessful();
            w.endTransaction();
            progress = Ui.tr("Indexando…", "Indexing…");
            w.execSQL("CREATE INDEX by_channel ON p (epg, start)");
            w.execSQL("INSERT INTO meta VALUES ('at', '" + now + "'), ('count', '" + count + "'), ('channels', '" + seen.size()
                    + "'), ('v', '" + VERSION + "')");
        } finally {
            if (opened != null) {
                try {
                    opened.close();
                } catch (IOException ignored) {
                }
            }
            conn.disconnect();
            if (w.inTransaction()) w.endTransaction();
            w.close();
        }
        if (count == 0) {
            SQLiteDatabase.deleteDatabase(building);
            throw new IOException(Ui.tr("o guia não tem programas destes canais", "the guide has no programs for these channels"));
        }
        // In place of the one in use.
        synchronized (XmltvStore.class) {
            close();
            File in = c.getDatabasePath(DB);
            SQLiteDatabase.deleteDatabase(in);
            if (!building.renameTo(in)) throw new IOException("couldn't keep the guide");
        }
        progress = "";
        AppLog.w("guide: " + pictures + " with a picture");
        return count;
    }

    /** Past the element the parser is on (its start tag), children and all. */
    private static void skip(XmlPullParser p) throws IOException, XmlPullParserException {
        for (int depth = 1; depth > 0; ) {
            int t = p.next();
            if (t == XmlPullParser.START_TAG) depth++;
            else if (t == XmlPullParser.END_TAG) depth--;
            else if (t == XmlPullParser.END_DOCUMENT) return;
        }
    }

    /** An XMLTV time ("20261021140000 +0100", the zone optional: then UTC) in ms; 0 when it isn't one. */
    private static long time(String s, Calendar utc) {
        if (s == null || s.length() < 12) return 0;
        try {
            int y = Integer.parseInt(s.substring(0, 4)), mo = Integer.parseInt(s.substring(4, 6)), d = Integer.parseInt(s.substring(6, 8));
            int h = Integer.parseInt(s.substring(8, 10)), mi = Integer.parseInt(s.substring(10, 12));
            int sec = s.length() >= 14 && Character.isDigit(s.charAt(12)) ? Integer.parseInt(s.substring(12, 14)) : 0;
            utc.clear();
            utc.set(y, mo - 1, d, h, mi, sec);
            long t = utc.getTimeInMillis();
            int plus = s.indexOf('+', 12), minus = s.indexOf('-', 12), at = plus >= 0 ? plus : minus;
            if (at >= 0 && s.length() >= at + 5) {
                int off = Integer.parseInt(s.substring(at + 1, at + 3)) * 60 + Integer.parseInt(s.substring(at + 3, at + 5));
                t -= (plus >= 0 ? 1 : -1) * off * 60_000L;
            }
            return t;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
