package com.glasslauncher.tv.live;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Series: every airing of a show on a channel reminded or recorded (or only its new episodes). The rules are in a
 * small file; their airings in the next day and a half go into LiveSchedule as they show up in the guide: when a
 * rule is set, when Live TV loads that channel's guide, and every few hours by an alarm (ScheduleReceiver).
 * An airing cancelled by hand is skipped for good (the rule doesn't put it back).
 */
final class LiveSeries {
    private LiveSeries() {}

    static final String ACTION = "com.glasslauncher.tv.live.action.SERIES";
    private static final long AHEAD = 36 * 3600_000L, EVERY = 3 * 3600_000L;

    static final class Rule {
        String kind;        // LiveSchedule.REMIND or RECORD
        Xtream.Channel ch;
        String title = "";
        boolean newOnly;
        final Set<Long> skip = new HashSet<>();

        String key() {
            return kind + ":" + ch.id + ":" + Xtream.fold(title);
        }

        boolean matches(Xtream.Program p) {
            return (!newOnly || p.fresh) && !skip.contains(p.start) && Xtream.fold(p.title).equals(Xtream.fold(title));
        }

        JSONObject json() throws JSONException {
            JSONArray s = new JSONArray();
            for (long t : skip) s.put(t);
            return new JSONObject().put("kind", kind).put("ch", Xtream.json(ch)).put("title", title).put("new", newOnly).put("skip", s);
        }

        static Rule of(JSONObject o) {
            JSONObject ch = o.optJSONObject("ch");
            if (ch == null) return null;
            Rule r = new Rule();
            r.kind = o.optString("kind");
            r.ch = Xtream.channel(ch);
            r.title = o.optString("title");
            r.newOnly = o.optBoolean("new");
            JSONArray s = o.optJSONArray("skip");
            long old = System.currentTimeMillis() - 24 * 3600_000L;
            for (int i = 0; s != null && i < s.length(); i++) if (s.optLong(i) > old) r.skip.add(s.optLong(i));
            return r;
        }
    }

    private static List<Rule> cache;
    private static long cacheStamp = -1;

    /** The rules (read again only when the file changed: Live TV asks on every channel's guide). */
    static synchronized List<Rule> all(Context c) {
        File f = file(c);
        long stamp = f.exists() ? f.lastModified() * 31 + f.length() : 0;
        if (cache != null && stamp == cacheStamp) return new ArrayList<>(cache);
        List<Rule> out = new ArrayList<>();
        if (f.exists()) {
            try (FileInputStream in = new FileInputStream(f)) {
                JSONArray a = new JSONArray(Xtream.read(in));
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    Rule r = o == null ? null : Rule.of(o);
                    if (r != null) out.add(r);
                }
            } catch (IOException | JSONException e) {
                AppLog.w("live: series unreadable");
            }
        }
        cache = out;
        cacheStamp = stamp;
        return new ArrayList<>(out);
    }

    static synchronized Rule find(Context c, String kind, int channel, String title) {
        String key = kind + ":" + channel + ":" + Xtream.fold(title);
        for (Rule r : all(c)) if (r.key().equals(key)) return r;
        return null;
    }

    /** Sets the rule, and its airings already in the guide (a background thread). */
    static void add(Context c, Rule rule) {
        Context app = c.getApplicationContext();
        synchronized (LiveSeries.class) {
            List<Rule> list = all(app);
            for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).key().equals(rule.key())) list.remove(i);
            list.add(rule);
            save(app, list);
        }
        arm(app);
        new Thread(() -> sync(app), "series").start();
    }

    /** Takes the rule out, with its airings still to come. */
    static void remove(Context c, Rule rule) {
        synchronized (LiveSeries.class) {
            List<Rule> list = all(c);
            for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).key().equals(rule.key())) list.remove(i);
            save(c, list);
        }
        for (LiveSchedule.Item it : LiveSchedule.all(c)) {
            if (rule.key().equals(it.series)) LiveSchedule.remove(c, it.key());
        }
    }

    /** An airing the rule put in, cancelled by hand: the rule leaves it out from now on. */
    static synchronized void skip(Context c, LiveSchedule.Item it) {
        if (it.series == null) return;
        List<Rule> list = all(c);
        for (Rule r : list) if (r.key().equals(it.series)) r.skip.add(it.start);
        save(c, list);
    }

    /** A channel's programs just loaded (Live TV's guide): its rules' airings into the schedule. */
    static void match(Context c, Xtream.Channel ch, List<Xtream.Program> programs) {
        if (programs == null || programs.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Rule r : all(c)) {
            if (r.ch.id != ch.id) continue;
            for (Xtream.Program p : programs) {
                if (p.start <= now || p.start > now + AHEAD || !r.matches(p)) continue;
                if (LiveSchedule.has(c, r.kind, ch.id, p.start)) continue;
                LiveSchedule.Item it = new LiveSchedule.Item();
                it.kind = r.kind;
                it.ch = r.ch;
                it.start = p.start;
                it.end = p.end;
                it.title = p.title;
                it.about = p.about;
                it.series = r.key();
                LiveSchedule.add(c, it);
                AppLog.w("series: " + r.kind + " " + ch.id + " at " + p.start);
            }
        }
    }

    /** Every rule's channel's guide (the full one, else the provider's), matched. Background thread. */
    static void sync(Context c) {
        List<Rule> rules = all(c);
        if (rules.isEmpty()) return;
        long now = System.currentTimeMillis();
        Set<Integer> done = new HashSet<>();
        for (Rule r : rules) {
            if (!done.add(r.ch.id)) continue;
            List<Xtream.Program> list = null;
            if (LiveOptions.fullGuide(c) && r.ch.epg != null && !r.ch.epg.isEmpty()) {
                list = XmltvStore.programs(c, r.ch.epg, now, now + AHEAD);
            }
            if (list == null || list.isEmpty()) {
                try {
                    list = Xtream.programs(c, r.ch.id, 40);
                } catch (IOException | RuntimeException e) {
                    AppLog.w("series: no guide for " + r.ch.id + ": " + e.getMessage());
                    list = Collections.emptyList();
                }
            }
            match(c, r.ch, list);
        }
    }

    /** The alarm every few hours while there are rules (set again after a restart or an update). */
    static void arm(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(c, ScheduleReceiver.class).setAction(ACTION);
        PendingIntent pi = PendingIntent.getBroadcast(c, 0x5e41e5, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        if (all(c).isEmpty() && LiveFollow.all(c).isEmpty()) am.cancel(pi);       // nothing to look for
        else am.setInexactRepeating(AlarmManager.RTC, System.currentTimeMillis() + EVERY, EVERY, pi);
    }

    private static void save(Context c, List<Rule> list) {
        JSONArray a = new JSONArray();
        try {
            for (Rule r : list) a.put(r.json());
            File f = file(c);
            Xtream.write(f, a.toString());
            cache = new ArrayList<>(list);      // the file's time is to the second: two saves in one would look alike
            cacheStamp = f.lastModified() * 31 + f.length();
        } catch (IOException | JSONException e) {
            AppLog.w("live: couldn't save the series");
        }
    }

    private static File file(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), "live-series.json");
    }
}
