package com.glasslauncher.tv.live;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The phone page's Live TV tab (PhoneServer, Settings › System › Live TV on the phone page): what's on your channels,
 * the guide searched, what's set and the recordings, and what can be done with them (watch on the TV, remind, record,
 * cancel, play or delete a recording). What it sends never has a stream's address, a logo's (it may carry the login) or
 * the guide's channel ids: names, numbers, countries and shows. A Kids profile's: its own channels, no recordings.
 */
public final class LivePhone {
    private LivePhone() {}

    private static final long HOUR = 3600_000L;
    private static final int MAX = 40;

    // MARK: - Pure

    /** {on now, next} in `list` (either null). */
    static Xtream.Program[] nowNext(List<Xtream.Program> list, long now) {
        Xtream.Program on = null, next = null;
        if (list != null) {
            for (Xtream.Program p : list) {
                if (p.start <= now && p.end > now) on = p;
                else if (p.start > now && (next == null || p.start < next.start)) next = p;
            }
        }
        return new Xtream.Program[] {on, next};
    }

    /** Whether channel `name` (number `num`) is what was typed: its number, or all its words. */
    static boolean matches(String name, int num, String q) {
        String t = q == null ? "" : q.trim();
        if (t.isEmpty()) return true;
        if (t.matches("\\d{1,5}")) return String.valueOf(num).equals(t);
        String n = " " + Xtream.fold(name) + " ";
        for (String w : Xtream.fold(t).split(" ")) if (!w.isEmpty() && !n.contains(w)) return false;
        return true;
    }

    private static JSONObject show(Xtream.Program p) throws JSONException {
        if (p == null) return null;
        return new JSONObject().put("title", p.title == null ? "" : p.title).put("start", p.start).put("end", p.end);
    }

    /** A channel as sent: id, number, name as shown, country, what's on now and next. */
    static JSONObject channel(Xtream.Channel ch, String name, String country, Xtream.Program on, Xtream.Program next) throws JSONException {
        JSONObject o = new JSONObject().put("id", ch.id).put("num", ch.num).put("name", name).put("country", country);
        if (on != null) o.put("now", show(on));
        if (next != null) o.put("next", show(next));
        return o;
    }

    /** An airing as sent: its channel's, the show's, and whether a reminder or a recording is set for it. */
    static JSONObject airing(Xtream.Channel ch, String name, String country, Xtream.Program p, boolean remind, boolean record) throws JSONException {
        return new JSONObject().put("id", ch.id).put("num", ch.num).put("name", name).put("country", country)
                .put("title", p.title == null ? "" : p.title).put("start", p.start).put("end", p.end)
                .put("remind", remind).put("record", record);
    }

    // MARK: - Read

    private static Xtream.Lineup lineup(Context c) {
        Xtream.Lineup l = Xtream.ready(c) ? Xtream.saved(c) : null;
        if (l != null && LiveProfiles.kids(c)) l = LiveProfiles.kidsOnly(c, l);
        return l;
    }

    private static String nameOf(Context c, Xtream.Channel ch, Map<String, String> cats) {
        if (ch.name == null) return "";
        return LiveOptions.countryTags(c) ? LiveCountry.shownName(ch, cats) : ch.name;
    }

    private static String countryOf(Context c, Xtream.Channel ch, Map<String, String> cats) {
        return LiveOptions.countryTags(c) ? LiveCountry.of(ch, cats) : "";
    }

    private static List<Xtream.Program> guide(Context c, Xtream.Channel ch, long now) {
        if (LiveOptions.fullGuide(c) && ch.epg != null && !ch.epg.isEmpty()) {
            List<Xtream.Program> l = XmltvStore.programs(c, ch.epg, now - 6 * HOUR, now + 12 * HOUR);
            if (l != null && !l.isEmpty()) return l;
        }
        Xtream.SavedGuide g = Xtream.savedGuide(c, ch.id);
        return g == null ? null : g.programs;
    }

    /** Your channels (favorites, then the ones watched lately) with what's on; with `q`, any channel it names. Blocking. */
    public static JSONObject channels(Context c, String q) throws JSONException {
        JSONObject out = new JSONObject();
        JSONArray list = new JSONArray();
        Xtream.Lineup l = lineup(c);
        out.put("ready", l != null);
        if (l == null) return out.put("channels", list);
        Map<String, String> cats = LiveCountry.savedCategories(c);
        long now = System.currentTimeMillis();
        List<Xtream.Channel> pick = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        if (q == null || q.trim().isEmpty()) {
            if (!l.categories.isEmpty()) for (Xtream.Channel ch : l.categories.get(0).channels) if (seen.add(ch.id)) pick.add(ch);
            for (Xtream.Channel r : Xtream.recent(c)) {
                Xtream.Channel ch = l.find(r.id);
                if (ch != null && seen.add(ch.id)) pick.add(ch);
            }
        } else {
            for (Xtream.Channel ch : l.all()) {
                if (pick.size() >= MAX) break;
                // The provider's name as it is: the shown one costs too much for every channel (thousands).
                if (ch.name != null && matches(ch.name, ch.num, q) && seen.add(ch.id)) pick.add(ch);
            }
        }
        for (int i = 0; i < pick.size() && i < MAX; i++) {
            Xtream.Channel ch = pick.get(i);
            Xtream.Program[] nn = nowNext(guide(c, ch, now), now);
            list.put(channel(ch, nameOf(c, ch, cats), countryOf(c, ch, cats), nn[0], nn[1]));
        }
        return out.put("channels", list);
    }

    /** The guide's airings of what was typed (a team under its other names too), the next 7 days. Blocking. */
    public static JSONArray find(Context c, String words) throws JSONException {
        JSONArray out = new JSONArray();
        Xtream.Lineup l = lineup(c);
        String q = Xtream.fold(words == null ? "" : words);
        if (l == null || q.length() < 2) return out;
        Map<String, String> cats = LiveCountry.savedCategories(c);
        long now = System.currentTimeMillis(), to = now + 7 * 24 * HOUR;
        LiveNames.Names names = LiveNames.of(c, q);
        List<Object[]> all = new ArrayList<>(LiveSearch.savedShows(c, l, q.split(" "), now));
        if (LiveOptions.fullGuide(c)) {
            Map<String, List<Xtream.Channel>> byEpg = new HashMap<>();
            for (Xtream.Channel ch : l.all()) {
                if (ch.epg == null || ch.epg.isEmpty()) continue;
                String id = ch.epg.toLowerCase(Locale.ROOT);
                List<Xtream.Channel> chs = byEpg.get(id);
                if (chs == null) byEpg.put(id, chs = new ArrayList<>());
                chs.add(ch);
            }
            List<XmltvStore.Found> found = names.isKnown() ? LiveNames.find(c, names, now, to, 200) : XmltvStore.findAll(c, q.split(" "), now, to, 200);
            for (XmltvStore.Found f : found) {
                List<Xtream.Channel> chs = f.epg == null ? null : byEpg.get(f.epg.toLowerCase(Locale.ROOT));
                if (chs == null) continue;
                Xtream.Program p = new Xtream.Program();
                p.title = f.title;
                p.start = f.start;
                p.end = f.end;
                for (Xtream.Channel ch : chs) all.add(new Object[] {ch, p});
            }
        }
        java.util.Collections.sort(all, (a, b) -> Long.compare(((Xtream.Program) a[1]).start, ((Xtream.Program) b[1]).start));
        Set<String> seen = new HashSet<>();
        for (Object[] o : all) {
            if (out.length() >= 60) break;
            Xtream.Channel ch = (Xtream.Channel) o[0];
            Xtream.Program p = (Xtream.Program) o[1];
            if (p.end <= now || p.title == null || !names.in(Xtream.fold(p.title))) continue;
            if (LiveVoiceCommand.notice(new LiveVoiceCommand.Candidate(p.title, p.start, p.end, 0))) continue;     // "Next Game: …", not a broadcast
            // Copies of one channel (HD, backup) show once: the same name at the same time.
            if (!seen.add(ch.id + ":" + p.start) || !seen.add(nameOf(c, ch, cats).toLowerCase(Locale.ROOT) + "|" + p.title + "|" + p.start)) continue;
            out.put(airing(ch, nameOf(c, ch, cats), countryOf(c, ch, cats), p,
                    LiveSchedule.has(c, LiveSchedule.REMIND, ch.id, p.start), LiveSchedule.has(c, LiveSchedule.RECORD, ch.id, p.start)));
        }
        return out;
    }

    /** The reminders and recordings set, soonest first. */
    public static JSONArray schedule(Context c) throws JSONException {
        JSONArray out = new JSONArray();
        Map<String, String> cats = LiveCountry.savedCategories(c);
        long now = System.currentTimeMillis();
        List<LiveSchedule.Item> items = new ArrayList<>(LiveSchedule.all(c));
        java.util.Collections.sort(items, (a, b) -> Long.compare(a.start, b.start));
        for (LiveSchedule.Item it : items) {
            if (it.ch == null || it.end <= now) continue;
            out.put(new JSONObject().put("key", it.key()).put("kind", LiveSchedule.reminder(it) ? "remind" : "record")
                    .put("title", it.title).put("name", nameOf(c, it.ch, cats)).put("num", it.ch.num).put("country", countryOf(c, it.ch, cats))
                    .put("start", it.start).put("end", it.end));
        }
        return out;
    }

    /** The finished recordings, newest first (none for a Kids profile). */
    public static JSONArray recordings(Context c) throws JSONException {
        JSONArray out = new JSONArray();
        if (LiveProfiles.kids(c)) return out;
        Map<String, String> cats = LiveCountry.savedCategories(c);
        List<LiveRecordings.Rec> recs = new ArrayList<>();
        for (LiveRecordings.Rec r : LiveRecordings.all(c)) if (LiveRecordings.DONE.equals(r.state) && r.ch != null) recs.add(r);
        java.util.Collections.sort(recs, (a, b) -> Long.compare(b.start, a.start));
        for (LiveRecordings.Rec r : recs) {
            out.put(new JSONObject().put("id", r.id).put("title", r.title.isEmpty() ? nameOf(c, r.ch, cats) : r.title)
                    .put("name", nameOf(c, r.ch, cats)).put("num", r.ch.num).put("country", countryOf(c, r.ch, cats))
                    .put("start", r.start).put("end", r.end).put("size", r.size));
        }
        return out;
    }

    /** The channel Home Assistant asks for: its number, or its name as voice finds it (`usual` country first). Pure. */
    static Xtream.Channel tuneTarget(List<Xtream.Channel> all, String said, String usual) {
        String t = said == null ? "" : said.trim();
        if (t.isEmpty()) return null;
        if (t.matches("\\d{1,5}")) {
            int n = Integer.parseInt(t);
            for (Xtream.Channel ch : all) if (ch.num == n) return ch;
            return null;
        }
        final String u = usual == null ? "" : usual;
        return LiveVoice.best(all, LiveVoice.parse(t), ch -> LiveCountry.of(ch, null),
                ch -> !u.isEmpty() && u.equals(LiveCountry.of(ch, null)) ? 40 : 0);
    }

    /** Home Assistant's tune: Live TV on the channel it names (Settings › Nuvio, Stremio & Home Assistant). */
    public static String tune(Context c, String said) {
        Xtream.Lineup l = lineup(c);
        if (l == null) throw new IllegalStateException("Live TV isn't signed in");
        List<Xtream.Channel> recent = Xtream.recent(c);
        String usual = recent.isEmpty() ? "" : LiveCountry.of(recent.get(0), LiveCountry.savedCategories(c));
        Xtream.Channel ch = tuneTarget(l.all(), said, usual);
        if (ch == null) throw new IllegalArgumentException("No channel like " + (said == null ? "" : said.trim()));
        return watch(c, ch.id);
    }

    // MARK: - The calendar (Settings › System › Calendar on the phone page)

    /** "637  TNT Sports 1 UK": where a show is, for the calendar. */
    private static String where(Context c, Xtream.Channel ch, Map<String, String> cats) {
        String cc = countryOf(c, ch, cats);
        return (ch.num > 0 ? ch.num + "  " : "") + nameOf(c, ch, cats) + (cc.isEmpty() ? "" : " " + cc);
    }

    /** The feed's shows: what's set, then the matches of what you follow, the next 7 days. Blocking (the guide). */
    public static List<com.glasslauncher.tv.data.IcsWriter.Item> calendar(Context c) {
        List<com.glasslauncher.tv.data.IcsWriter.Item> out = new ArrayList<>();
        Map<String, String> cats = LiveCountry.savedCategories(c);
        long now = System.currentTimeMillis(), to = now + 7 * 24 * HOUR;
        Set<String> seen = new HashSet<>();
        for (LiveSchedule.Item it : LiveSchedule.all(c)) {
            if (it.ch == null || it.end <= now || it.start > to) continue;
            boolean remind = LiveSchedule.reminder(it);
            seen.add(it.ch.id + ":" + it.start);
            out.add(new com.glasslauncher.tv.data.IcsWriter.Item(com.glasslauncher.tv.data.IcsWriter.uid(remind ? "remind" : "record", it.ch.id, it.start),
                    it.title, where(c, it.ch, cats), remind ? "Reminder in Glassio" : "Recording in Glassio", it.start, it.end));
        }
        if (Xtream.ready(c)) {
            for (String term : LiveFollow.all(c)) {
                for (LiveHome.Airing a : LiveHome.team(c, term).matches) {
                    if (a.ch == null || a.end <= now || a.start > to || !seen.add(a.ch.id + ":" + a.start)) continue;
                    out.add(new com.glasslauncher.tv.data.IcsWriter.Item(com.glasslauncher.tv.data.IcsWriter.uid("match", a.ch.id, a.start),
                            a.title, where(c, a.ch, cats), "You follow " + term, a.start, a.end));
                }
            }
        }
        java.util.Collections.sort(out, (x, y) -> Long.compare(x.getStart(), y.getStart()));
        return out;
    }

    /** One show for Add to calendar: `title` on channel `id`, its times. */
    public static com.glasslauncher.tv.data.IcsWriter.Item calendarItem(Context c, int id, long start, long end, String title) {
        Xtream.Channel ch = channelOf(c, id);
        return new com.glasslauncher.tv.data.IcsWriter.Item(com.glasslauncher.tv.data.IcsWriter.uid("show", ch.id, start),
                title == null ? "" : title.trim(), where(c, ch, LiveCountry.savedCategories(c)), "From Glassio", start, end);
    }

    // MARK: - Do

    private static Xtream.Channel channelOf(Context c, int id) {
        Xtream.Lineup l = lineup(c);
        Xtream.Channel ch = l == null ? null : l.find(id);
        if (ch == null) throw new IllegalArgumentException("No such channel");
        return ch;
    }

    /** Live TV on the TV, on channel `id`. */
    public static String watch(Context c, int id) {
        Xtream.Channel ch = channelOf(c, id);
        c.startActivity(LiveHome.open(c, ch.id, false).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        return "Watching " + (ch.num > 0 ? ch.num + " " : "") + nameOf(c, ch, LiveCountry.savedCategories(c)) + " on the TV";
    }

    /** A reminder (`record` false) or a recording for the show from `start` to `end` on `id`; a show on now records at once. */
    public static String set(Context c, boolean record, int id, long start, long end, String title) {
        Xtream.Channel ch = channelOf(c, id);
        long now = System.currentTimeMillis();
        if (end <= now) throw new IllegalArgumentException("It's over");
        String kind = record ? LiveSchedule.RECORD : LiveSchedule.REMIND;
        LiveSchedule.Item it = new LiveSchedule.Item();
        it.kind = kind;
        it.ch = ch;
        it.start = start;
        it.end = end;
        it.title = title == null ? "" : title.trim();
        it.about = LiveNames.about(c, it.title, "");
        if (record && start <= now) {
            if (LiveRecordings.recording(c, ch.id, start)) return "Already recording";
            RecordService.start(c, it);
            return "Recording until " + Ui.time(end);
        }
        if (LiveSchedule.has(c, kind, ch.id, start)) return record ? "Already recording it" : "Already reminding you";
        LiveSchedule.add(c, it);
        return (record ? "Records at " : "Reminder at ") + Ui.time(start);
    }

    /** A reminder or a recording set (its key, from schedule) taken out. */
    public static String cancel(Context c, String key) {
        if (LiveSchedule.find(c, key) == null) throw new IllegalArgumentException("Not set");
        LiveSchedule.cancel(c, key);
        return "Cancelled";
    }

    /** A recording played on the TV. */
    public static String play(Context c, String id) {
        if (LiveProfiles.kids(c) || LiveRecordings.find(c, id) == null) throw new IllegalArgumentException("No such recording");
        c.startActivity(LiveHome.openRecording(c, id).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        return "Playing on the TV";
    }

    /** A recording deleted (the phone asked first). */
    public static String delete(Context c, String id) {
        if (LiveProfiles.kids(c) || LiveRecordings.find(c, id) == null) throw new IllegalArgumentException("No such recording");
        LiveRecordings.delete(c, id);
        return "Deleted";
    }
}
