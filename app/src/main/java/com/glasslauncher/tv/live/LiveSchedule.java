package com.glasslauncher.tv.live;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reminders and recordings set for later (the guide: hold OK on a show), each with an alarm: a minute before a
 * reminded show starts, ReminderActivity pops up over whatever is on; as a recorded one starts, RecordService records
 * it. Kept in a small file (Live TV sets them, the main process fires them), the alarms set again after a restart.
 */
final class LiveSchedule {
    private LiveSchedule() {}

    /** SWITCH: a reminder's second alarm, as the show starts, when the TV switches by itself (LiveOptions.remindAuto). */
    static final String REMIND = "remind", RECORD = "record", SWITCH = "switch";
    private static final String ACTION = "com.glasslauncher.tv.live.action.SCHEDULE";

    static final class Item {
        String kind;
        Xtream.Channel ch;
        long start, end;
        String title = "", about = "";
        /** The series rule that put it in (LiveSeries.Rule.key), or null. */
        String series;

        String key() {
            return kind + ":" + ch.id + ":" + start;
        }

        Item as(String kind) {
            Item it = new Item();
            it.kind = kind;
            it.ch = ch;
            it.start = start;
            it.end = end;
            it.title = title;
            it.about = about;
            it.series = series;
            return it;
        }

        JSONObject json() throws JSONException {
            return new JSONObject().put("kind", kind).put("ch", Xtream.json(ch)).put("start", start).put("end", end)
                    .put("title", title).put("about", about).put("series", series == null ? "" : series);
        }

        static Item of(JSONObject o) {
            Item it = new Item();
            it.kind = o.optString("kind");
            JSONObject ch = o.optJSONObject("ch");
            if (ch == null) return null;
            it.ch = Xtream.channel(ch);
            it.start = o.optLong("start");
            it.end = o.optLong("end");
            it.title = o.optString("title");
            it.about = o.optString("about");
            String s = o.optString("series");
            it.series = s.isEmpty() ? null : s;
            return it;
        }
    }

    /** What's set, soonest first, without what's over (a reminder once its show started, a recording once due). */
    static synchronized List<Item> all(Context c) {
        List<Item> out = new ArrayList<>();
        File f = file(c);
        if (!f.exists()) return out;
        long now = System.currentTimeMillis();
        try (FileInputStream in = new FileInputStream(f)) {
            JSONArray a = new JSONArray(Xtream.read(in));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                Item it = o == null ? null : Item.of(o);
                if (it != null && it.start > now - 5 * 60_000L) out.add(it);
            }
        } catch (IOException | JSONException e) {
            AppLog.w("live: schedule unreadable");
        }
        java.util.Collections.sort(out, (x, y) -> Long.compare(x.start, y.start));
        return out;
    }

    static synchronized boolean has(Context c, String kind, int channel, long start) {
        for (Item it : all(c)) {
            boolean same = it.kind.equals(kind) || kind.equals(REMIND) && it.kind.equals(SWITCH);
            if (same && it.ch.id == channel && it.start == start) return true;
        }
        return false;
    }

    /** A reminder (its card), or its switch as the show starts. */
    static boolean reminder(Item it) {
        return REMIND.equals(it.kind) || SWITCH.equals(it.kind);
    }

    static synchronized Item find(Context c, String key) {
        for (Item it : all(c)) if (it.key().equals(key)) return it;
        return null;
    }

    /** Sets `it` (and its alarm). */
    static synchronized void add(Context c, Item it) {
        List<Item> list = all(c);
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).key().equals(it.key())) list.remove(i);
        list.add(it);
        save(c, list);
        arm(c, it);
    }

    /** Takes it out (and its alarm); a reminder, with its switch. */
    static synchronized void remove(Context c, String key) {
        List<Item> list = all(c);
        String alsoSwitch = key.startsWith(REMIND + ":") ? SWITCH + key.substring(REMIND.length()) : null;
        for (int i = list.size() - 1; i >= 0; i--) {
            String k = list.get(i).key();
            if (!k.equals(key) && !k.equals(alsoSwitch)) continue;
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(pending(c, list.get(i)));
            list.remove(i);
        }
        save(c, list);
    }

    /** Cancelled by hand: out, and a series' rule leaves that airing out from now on. */
    static synchronized void cancel(Context c, String key) {
        String alsoSwitch = key.startsWith(REMIND + ":") ? SWITCH + key.substring(REMIND.length()) : null;
        for (Item it : all(c)) {
            if (it.key().equals(key) || it.key().equals(alsoSwitch)) {
                LiveSeries.skip(c, it);
                LiveFollow.skip(c, it);
            }
        }
        remove(c, key);
    }

    /** When the schedule last changed (the guide's marks follow). */
    static long stamp(Context c) {
        File f = file(c);
        return f.exists() ? f.lastModified() * 31 + f.length() : 0;
    }

    /** The alarms off (a playlist put aside: its reminders and recordings wait with it). */
    static synchronized void disarmAll(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        for (Item it : all(c)) am.cancel(pending(c, it));
    }

    /** After a restart the alarms are gone: set again (and what was missed while the TV was off, told). */
    static synchronized void armAll(Context c) {
        sweepMissed(c);
        for (Item it : all(c)) arm(c, it);
    }

    /**
     * Recordings that never started (their alarm never came: the TV was unplugged): each a missed recording on the
     * Recordings page and a notice; then everything over leaves the file.
     */
    private static void sweepMissed(Context c) {
        File f = file(c);
        if (!f.exists()) return;
        long now = System.currentTimeMillis();
        List<Item> keep = new ArrayList<>();
        boolean dropped = false;
        try (FileInputStream in = new FileInputStream(f)) {
            JSONArray a = new JSONArray(Xtream.read(in));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                Item it = o == null ? null : Item.of(o);
                if (it == null) continue;
                if (it.start > now - 5 * 60_000L) {
                    keep.add(it);
                    continue;
                }
                dropped = true;
                if (!it.kind.equals(RECORD) || it.end < now - 24 * 3600_000L) continue;     // a day old: not worth a word
                if (LiveRecordings.find(c, LiveRecordings.idOf(it.ch.id, it.start)) != null) continue;
                LiveRecordings.Rec r = new LiveRecordings.Rec();
                r.id = LiveRecordings.idOf(it.ch.id, it.start);
                r.ch = it.ch;
                r.title = it.title;
                r.about = it.about;
                r.start = it.start;
                r.end = it.end;
                r.file = "";
                r.state = LiveRecordings.FAILED;
                r.why = Ui.tr("a TV estava desligada", "the TV was off");
                LiveRecordings.put(c, r);
                LiveRecordings.tell(c, r, Ui.tr("Gravação perdida", "Recording missed"));
            }
        } catch (IOException | JSONException e) {
            return;
        }
        if (dropped) save(c, keep);
    }

    private static void arm(Context c, Item it) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long now = System.currentTimeMillis();
        // A reminder the minutes picked before (at least half a minute: Watch then catches the start); a recording
        // half a minute early (the stream takes a moment to start), plus the minutes picked (Start recordings).
        long early = it.kind.equals(REMIND) ? Math.max(30_000L, LiveOptions.remindLead(c) * 60_000L)
                : it.kind.equals(SWITCH) ? ReminderActivity.COUNTDOWN_MS : 30_000 + LiveOptions.recordEarly(c) * 60_000L;
        long at = Math.max(now + 1000, it.start - early);
        PendingIntent pi = pending(c, it);
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setWindow(AlarmManager.RTC_WAKEUP, at, 30_000, pi);
            else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (SecurityException e) {
            am.setWindow(AlarmManager.RTC_WAKEUP, at, 30_000, pi);
        }
    }

    private static PendingIntent pending(Context c, Item it) {
        Intent i = new Intent(c, ScheduleReceiver.class).setAction(ACTION).setData(Uri.parse("zero-schedule:" + it.key()));
        return PendingIntent.getBroadcast(c, it.key().hashCode(), i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** The item an alarm is for. */
    static String keyOf(Intent alarm) {
        Uri u = alarm == null ? null : alarm.getData();
        return u == null ? null : u.getSchemeSpecificPart();
    }

    private static void save(Context c, List<Item> list) {
        JSONArray a = new JSONArray();
        try {
            for (Item it : list) a.put(it.json());
            Xtream.write(file(c), a.toString());
        } catch (IOException | JSONException e) {
            AppLog.w("live: couldn't save the schedule");
        }
    }

    private static File file(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), "live-schedule.json");
    }
}
