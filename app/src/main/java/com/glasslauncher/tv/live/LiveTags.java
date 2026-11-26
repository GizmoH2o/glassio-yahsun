package com.glasslauncher.tv.live;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The tags the guide doesn't give, or gives wrongly, worked out as it's read: an episode seen before is a REPEAT (and not
 * NEW), a match shown again that day isn't LIVE, a season's first is a PREMIERE. Each is a bit beside the listing, so the
 * settings say what shows without reading the guide again. Only on firm evidence: no tag beats a wrong one.
 */
final class LiveTags {
    private LiveTags() {}

    /** REPEAT by the guide itself; premiere and finale by its description; seen before; its LIVE is a rerun's; the first episode. */
    static final int REPEAT = 1, PREMIERE = 2, FINALE = 4, SEEN = 8, RERUN = 32, FIRST = 64;

    static final long LATER = 12 * 3600_000L;
    /** Marked NEW, on another channel: only this long after the first (stations show a day's new episode at their own hours). */
    private static final long NEW_LATER = 36 * 3600_000L;
    private static final long DAY = 24 * 3600_000L, HALF_HOUR = 30 * 60_000L;
    /** Episodes seen are kept this long; only those about to be shown are (the guide moves the rest). */
    private static final long KEEP = 60 * DAY, AHEAD = DAY;

    // MARK: - Reading a listing. Pure

    /** Season * 10000 + episode at `i` of `s` ("S28 E04", "S1E2"), 0 when there's none there. */
    static int numbersAt(String s, int i) {
        int n = s.length();
        if (i >= n || (s.charAt(i) | 32) != 's') return 0;
        int season = 0, episode = 0, j = i + 1, d = 0;
        for (; j < n && d < 2 && digit(s, j); j++, d++) season = season * 10 + s.charAt(j) - '0';
        if (d == 0) return 0;
        if (j < n && s.charAt(j) == ' ') j++;
        if (j >= n || (s.charAt(j) | 32) != 'e') return 0;
        j++;
        d = 0;
        for (; j < n && d < 4 && digit(s, j); j++, d++) episode = episode * 10 + s.charAt(j) - '0';
        return d == 0 || j < n && digit(s, j) || episode == 0 ? 0 : season * 10_000 + episode;
    }

    private static boolean digit(String s, int i) {
        return s.charAt(i) >= '0' && s.charAt(i) <= '9';
    }

    /** Whether the title itself ends with its numbers ("Angel Face - S07E17"): how a looping channel's guide is made. */
    static boolean numbered(String title) {
        int at = title.lastIndexOf(" - ");
        return at > 0 && numbersAt(title, at + 3) != 0;
    }

    /** The episode a description starts with, its numbers and its own title; null without both. */
    static String episode(String about) {
        if (about == null || about.length() < 6 || numbersAt(about, 0) == 0) return null;
        int nl = about.indexOf('\n');
        if (nl < 5 || nl > 120) return null;
        String line = Xtream.fold(about.substring(0, nl));
        int e = line.indexOf('e');
        // After the numbers there's a title, and not just "Episode 6" over again.
        String rest = line.replaceFirst("^s\\d+ ?e\\d+ ?", "");
        return rest.isEmpty() || e < 0 ? null : line;
    }

    /** The country a guide channel is of, by its id ("Dave.uk"), "" when it doesn't say. */
    static String country(String epg) {
        int dot = epg.lastIndexOf('.');
        return dot > 0 && dot == epg.length() - 3 && Character.isLetter(epg.charAt(dot + 1)) ? epg.substring(dot + 1) : "";
    }

    static long seenKey(String show, String country, String episode) {
        long h = 0xcbf29ce484222325L;
        String s = show + "|" + country + "|" + episode;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }

    private static final Pattern STARTS = Pattern.compile("(?i)(^|[\\n.!] *|^s\\d+ ?e\\d+ )(season|series) premiere\\b");
    private static final Pattern ENDS = Pattern.compile("(?i)(^|[\\n.!] *|^s\\d+ ?e\\d+ )(season|series) finale\\b");
    private static final Pattern OVER = Pattern.compile("(?i)\\b(highlights?|replays?|rerun|rewind|best of|flashback|encore|match review|classic (match|matches|games?))\\b");
    private static final Pattern GOES_ON = Pattern.compile("(?i)\\b(tennis|davis|atp|wta|snooker|darts|golf|mlb|baseball|softball)\\b");

    /** What the description says it is. */
    static int said(String about) {
        if (about == null || about.length() < 14) return 0;
        // A word's search is cheaper than the pattern's: most have neither.
        boolean p = about.contains("remiere"), f = about.contains("inale");
        return (p && STARTS.matcher(about).find() ? PREMIERE : 0) | (f && ENDS.matcher(about).find() ? FINALE : 0);
    }

    /** Whether a title is of something over (its LIVE mark is the match's, not its own). */
    static boolean over(String title) {
        return title != null && OVER.matcher(title).find();
    }

    // MARK: - The guide as it's read

    /** What's kept of the listings that can be told apart by episode, and the live matches. Memory for ~150,000. */
    static final class Reader {
        private long[] row = new long[4096], start = new long[4096], key = new long[4096];
        private int[] channel = new int[4096];
        private byte[] mark = new byte[4096];
        private int n;
        private final Map<String, Integer> channels = new HashMap<>();
        /** By channel: listings, titles with numbers in, the same show as the one before, an episode again within two days, songs. */
        private final List<int[]> counts = new ArrayList<>();
        private final List<String> last = new ArrayList<>(), names = new ArrayList<>();
        /** Live listings of a match by country and title: {start, stop}. */
        private final Map<String, List<long[]>> live = new HashMap<>();
        private final Map<Long, int[]> said = new HashMap<>();
        /** Live listings of a title with a dash in it (a song's, on a music channel): row to channel. */
        private final Map<Long, Integer> songs = new HashMap<>();
        /** Given NEW or LIVE from another channel's listing. */
        private final java.util.Set<Long> gainedNew = new java.util.HashSet<>(), gainedLive = new java.util.HashSet<>();

        void gained(long rowId, boolean isLive) {
            (isLive ? gainedLive : gainedNew).add(rowId);
        }

        /** A listing of `show` (folded, its marks out). */
        void add(long rowId, String epg, long from, long to, String title, String show, String about, boolean fresh, boolean isLive) {
            Integer ch = channels.get(epg);
            if (ch == null) {
                channels.put(epg, ch = counts.size());
                counts.add(new int[5]);
                last.add("");
                names.add(epg);
            }
            int[] c = counts.get(ch);
            c[0]++;
            if (numbered(title)) c[1]++;
            boolean song = title.indexOf('\u2013') > 0;
            if (song) c[4]++;
            if (song && isLive) songs.put(rowId, ch);
            if (show.equals(last.get(ch))) c[2]++;
            last.set(ch, show);
            int s = said(about);
            if (s != 0) said.put(rowId, new int[] {s, ch});
            if (isLive && !song && !country(epg).isEmpty() && !GOES_ON.matcher(show).find() && isMatch(title, from)) {
                String at = country(epg) + "|" + show;
                List<long[]> l = live.get(at);
                if (l == null) live.put(at, l = new ArrayList<>(2));
                l.add(new long[] {from, to});
            }
            String ep = episode(about);
            if (ep == null) return;
            if (n == row.length) grow();
            row[n] = rowId;
            start[n] = from;
            key[n] = seenKey(show, country(epg), ep);
            channel[n] = ch;
            int numbers = numbersAt(about, 0);
            mark[n++] = (byte) ((fresh ? 1 : 0) | (isLive ? 2 : 0) | (numbers % 10_000 == 1 ? 4 : 0));
        }

        private void grow() {
            int to = row.length * 2;
            row = Arrays.copyOf(row, to);
            start = Arrays.copyOf(start, to);
            key = Arrays.copyOf(key, to);
            channel = Arrays.copyOf(channel, to);
            mark = Arrays.copyOf(mark, to);
        }

        /** Whether that channel loops (a 24/7 one): a tag on nearly all it shows says nothing. */
        private boolean loops(int ch) {
            int[] c = counts.get(ch);
            return c[0] < 4 || c[1] * 2 > c[0] || c[2] * 100 > c[0] * 85 || c[3] * 100 > c[0] * 40;
        }

        /** The bits of each listing that has any (row id to bits); `seen` is when each episode was first on before this guide. */
        Map<Long, Integer> bits(Seen seen, long now) {
            Map<Long, Integer> out = new HashMap<>();
            Integer[] order = new Integer[n];
            for (int i = 0; i < n; i++) order[i] = i;
            Arrays.sort(order, (a, b) -> key[a] != key[b] ? Long.compare(key[a], key[b]) : Long.compare(start[a], start[b]));
            // First, which channels loop: the same episode again within two days, over and over.
            for (int a = 0; a < n; ) {
                int z = group(order, a);
                for (int i = a; i < z; i++) {
                    for (int j = i - 1; j >= a; j--) {
                        if (start[order[i]] - start[order[j]] > 2 * DAY) break;
                        if (channel[order[i]] != channel[order[j]]) continue;
                        counts.get(channel[order[i]])[3]++;
                        break;
                    }
                }
                a = z;
            }
            for (int a = 0; a < n; ) {
                int z = group(order, a);
                if (!unsure(order, a, z)) {
                    long first = Long.MAX_VALUE;
                    for (int i = a; i < z; i++) if (!loops(channel[order[i]])) first = Math.min(first, start[order[i]]);
                    long before = seen == null ? 0 : seen.first(key[order[a]]);
                    if (before > 0 && before > now - KEEP) first = Math.min(first, before);
                    Map<Integer, Long> own = new HashMap<>();       // by channel, its first showing of it
                    for (int i = a; i < z; i++) own.putIfAbsent(channel[order[i]], start[order[i]]);
                    for (int i = a; i < z; i++) {
                        int at = order[i];
                        if (loops(channel[at])) continue;
                        int b = 0;
                        boolean isNew = (mark[at] & 1) != 0 || gainedNew.contains(row[at]), isLive = (mark[at] & 2) != 0 || gainedLive.contains(row[at]);
                        boolean later = isNew ? start[at] - own.get(channel[at]) > LATER || start[at] - first > NEW_LATER : start[at] - first > LATER;
                        if (later && !isLive) b = SEEN;
                        else if ((mark[at] & 4) != 0) b = FIRST;
                        if (b != 0) out.put(row[at], b);
                        if (seen != null && start[at] == first && first <= now + AHEAD && first != before) seen.add(key[at], first, name(channel[at]));
                    }
                }
                a = z;
            }
            for (Map.Entry<Long, int[]> e : said.entrySet()) if (!loops(e.getValue()[1])) out.merge(e.getKey(), e.getValue()[0], (x, y) -> x | y);
            for (Map.Entry<Long, Integer> e : songs.entrySet()) {       // "(Live)" in a song's name, on a music channel
                int[] c = counts.get(e.getValue());
                if (c[4] * 10 > c[0] * 7) out.merge(e.getKey(), RERUN, (x, y) -> x | y);
            }
            return out;
        }

        /** A match for sure: its two sides with a "v" between, or one of them a team known. */
        private static boolean isMatch(String title, long from) {
            LiveMatchGroup g = LiveMatchGroup.of(title, from);
            if (g == null) return false;
            String t = " " + title.toLowerCase(java.util.Locale.ROOT) + " ";
            for (String v : new String[] {" v ", " vs ", " vs. ", " x ", " @ ", " at "}) if (t.contains(v)) return true;
            int bar = g.pair.indexOf('|');
            return LiveNames.known(g.pair.substring(0, bar)) != null || LiveNames.known(g.pair.substring(bar + 1)) != null;
        }

        private int group(Integer[] order, int a) {
            int z = a + 1;
            while (z < n && key[order[z]] == key[order[a]]) z++;
            return z;
        }

        /** The numbers don't tell its days apart: the same on one channel at the same hour day after day, or NEW again and again. */
        private boolean unsure(Integer[] order, int a, int z) {
            if (z - a < 3) return false;
            java.util.Set<Long> fresh = new java.util.HashSet<>();
            Map<Long, Integer> hours = new HashMap<>();
            for (int i = a; i < z; i++) {
                int at = order[i];
                if ((mark[at] & 1) != 0) fresh.add((start[at] - start[order[a]] + LATER) / DAY);
                long slot = channel[at] * 100L + (start[at] % DAY) / HALF_HOUR;
                if (hours.merge(slot, 1, Integer::sum) >= 4) return true;
            }
            return fresh.size() >= 3;
        }

        private String name(int ch) {
            return names.get(ch);
        }

        /** The live matches shown again later the same day in a country: {title, from, to, country} of each showing that isn't live. */
        List<Object[]> reruns() {
            List<Object[]> out = new ArrayList<>();
            for (Map.Entry<String, List<long[]>> e : live.entrySet()) {
                List<long[]> l = e.getValue();
                if (l.size() < 2) continue;
                java.util.Collections.sort(l, (x, y) -> Long.compare(x[0], y[0]));
                // The live showing, with those that go on from it: what starts an hour after they're all over is a rerun.
                long first = l.get(0)[0], until = l.get(0)[1];
                for (long[] x : l) {
                    if (x[0] - first > 16 * 3600_000L) {        // another day's
                        first = x[0];
                        until = x[1];
                    } else if (x[0] <= until + 3600_000L) {
                        until = Math.max(until, x[1]);
                    } else if (x[0] - first > 3 * 3600_000L) {
                        out.add(new Object[] {e.getKey().substring(3), x[0], x[0] + 15 * 60_000L, e.getKey().substring(0, 2)});
                    }
                }
            }
            return out;
        }
    }

    /** Into the guide being made (its index on key still there). */
    static void write(Context c, SQLiteDatabase w, Reader r, long now) {
        long began = System.currentTimeMillis();
        Seen seen = null;
        int repeats = 0, firsts = 0, reruns = 0;
        try {
            seen = Seen.open(c, now);
            Map<Long, Integer> bits = r.bits(seen, now);
            w.beginTransaction();
            try (SQLiteStatement up = w.compileStatement("UPDATE p SET rep = rep | ? WHERE rowid = ?");
                 SQLiteStatement off = w.compileStatement("UPDATE p SET rep = rep | " + (RERUN | SEEN) + " WHERE key = ? AND start >= ? AND start <= ? AND epg LIKE ?")) {
                for (Map.Entry<Long, Integer> e : bits.entrySet()) {
                    up.bindLong(1, e.getValue());
                    up.bindLong(2, e.getKey());
                    up.executeUpdateDelete();
                    if ((e.getValue() & SEEN) != 0) repeats++;
                    if ((e.getValue() & (FIRST | PREMIERE | FINALE)) != 0) firsts++;
                }
                for (Object[] x : r.reruns()) {
                    off.bindString(1, (String) x[0]);
                    off.bindLong(2, (Long) x[1]);
                    off.bindLong(3, (Long) x[2]);
                    off.bindString(4, "%." + x[3]);
                    reruns += off.executeUpdateDelete();
                }
                w.setTransactionSuccessful();
            } finally {
                w.endTransaction();
            }
            seen.save();
        } catch (RuntimeException e) {
            AppLog.w("guide: tags not worked out: " + e.getMessage());
        } finally {
            if (seen != null) seen.close();
        }
        AppLog.w("guide: tags: " + repeats + " seen before, " + firsts + " firsts and lasts, " + reruns + " reruns, in "
                + (System.currentTimeMillis() - began) / 1000 + " s");
    }

    // MARK: - The episodes seen

    /** When each episode was first on, the last two months. */
    static final class Seen {
        private SQLiteDatabase db;
        private long[] keys = new long[0], at = new long[0];
        private final List<Object[]> added = new ArrayList<>();

        static Seen open(Context c, long now) {
            Seen s = new Seen();
            s.db = c.openOrCreateDatabase("live-seen.db", Context.MODE_PRIVATE, null);
            s.db.execSQL("CREATE TABLE IF NOT EXISTS s (h INTEGER PRIMARY KEY, at INTEGER, epg TEXT)");
            s.db.delete("s", "at < ?", new String[] {String.valueOf(now - KEEP)});
            try (Cursor q = s.db.rawQuery("SELECT h, at FROM s ORDER BY h", null)) {
                s.keys = new long[q.getCount()];
                s.at = new long[s.keys.length];
                for (int i = 0; q.moveToNext() && i < s.keys.length; i++) {
                    s.keys[i] = q.getLong(0);
                    s.at[i] = q.getLong(1);
                }
            }
            return s;
        }

        /** One made of `keys` (sorted) for the tests. */
        static Seen of(long[] keys, long[] at) {
            Seen s = new Seen();
            s.keys = keys;
            s.at = at;
            return s;
        }

        long first(long key) {
            int i = Arrays.binarySearch(keys, key);
            return i < 0 ? 0 : at[i];
        }

        void add(long key, long when, String epg) {
            added.add(new Object[] {key, when, epg});
        }

        void save() {
            if (db == null) return;
            db.beginTransaction();
            try {
                ContentValues v = new ContentValues();
                for (Object[] a : added) {
                    v.put("h", (Long) a[0]);
                    v.put("at", (Long) a[1]);
                    v.put("epg", (String) a[2]);
                    db.insertWithOnConflict("s", null, v, SQLiteDatabase.CONFLICT_REPLACE);
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }

        void close() {
            if (db != null) db.close();
        }
    }

    /** When `p` of guide channel `epg` was first on, as the guide's had it ({when, guide channel}); null: not known. Background thread. */
    static Object[] firstOn(Context c, String epg, Xtream.Program p) {
        if (epg == null || epg.isEmpty() || p == null) return null;
        String ep = episode(p.about);
        if (ep == null || !c.getDatabasePath("live-seen.db").exists()) return null;
        long key = seenKey(Xtream.fold(p.title), country(epg.toLowerCase(java.util.Locale.ROOT)), ep);
        try (SQLiteDatabase d = SQLiteDatabase.openDatabase(c.getDatabasePath("live-seen.db").getPath(), null, SQLiteDatabase.OPEN_READONLY);
             Cursor q = d.rawQuery("SELECT at, epg FROM s WHERE h = ?", new String[] {String.valueOf(key)})) {
            return q.moveToFirst() ? new Object[] {q.getLong(0) + LiveOptions.guideShift(c), q.getString(1)} : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // MARK: - On a listing

    /** The bits onto a program already marked (Xtream.mark), as the settings say. */
    static void apply(Context c, Xtream.Program p, int bits) {
        apply(p, bits, LiveOptions.workOutRepeats(c), LiveOptions.correctTags(c), LiveOptions.premieres(c));
    }

    /** Pure. */
    static void apply(Xtream.Program p, int bits, boolean repeats, boolean correct, boolean premieres) {
        if (correct) {
            if (p.live && ((bits & RERUN) != 0 || over(p.title))) p.live = false;
            if (p.fresh && (bits & SEEN) != 0 && !p.live) p.fresh = false;
        }
        p.repeat |= !p.live && !p.fresh && ((bits & REPEAT) != 0 || repeats && (bits & SEEN) != 0);
        if (!premieres || p.repeat || (bits & SEEN) != 0) return;
        p.premiere = (bits & PREMIERE) != 0 || (bits & FIRST) != 0 && p.fresh;
        p.finale = !p.premiere && (bits & FINALE) != 0;
    }

    static final int LIVE_TAG = 1, NEW_TAG = 2, REPEAT_TAG = 3, PREMIERE_TAG = 6, FINALE_TAG = 7;

    /** Which tag shows (0: none), with Guide tags at `which` (0: all, 1: live and new only, 2: none). Pure. */
    static int tag(Xtream.Program p, int which) {
        if (which == 2) return 0;
        if (p.live) return LIVE_TAG;
        if (which == 0 && p.premiere) return PREMIERE_TAG;
        if (which == 0 && p.finale) return FINALE_TAG;
        if (p.fresh) return NEW_TAG;
        return which == 0 && p.repeat ? REPEAT_TAG : 0;
    }

    static String text(int tag) {
        switch (tag) {
            case LIVE_TAG: return Ui.tr("AO VIVO", "LIVE");
            case NEW_TAG: return Ui.tr("NOVO", "NEW");
            case REPEAT_TAG: return Ui.tr("REPRISE", "REPEAT");
            case PREMIERE_TAG: return Ui.tr("ESTREIA", "PREMIERE");
            case FINALE_TAG: return Ui.tr("FINAL", "FINALE");
            default: return null;
        }
    }
}
