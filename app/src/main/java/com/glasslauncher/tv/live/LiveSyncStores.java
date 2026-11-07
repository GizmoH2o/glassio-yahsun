package com.glasslauncher.tv.live;

import android.content.Context;
import android.content.Intent;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Live TV's side of Sync with other TVs (LanSync): its reminders as the sync sees them ("remind:" + their key → their
 * JSON; recordings stay on the TV that made them), a reminder put in or taken out with its alarm, and Live TV told to read
 * what changed again.
 */
public final class LiveSyncStores {
    private LiveSyncStores() {}

    /** Sent (to Glassio only) when the sync wrote something: Live TV reads its lists again. */
    public static final String SYNCED = "com.glasslauncher.tv.live.action.SYNCED";

    public static String activeProfile(Context c) {
        return LiveProfiles.active(c).id;
    }

    public static Map<String, String> reminders(Context c) {
        Map<String, String> out = new HashMap<>();
        for (LiveSchedule.Item it : LiveSchedule.all(c)) {
            if (it.ch == null || !LiveSchedule.reminder(it)) continue;
            try {
                out.put("remind:" + it.key(), it.json().toString());
            } catch (JSONException ignored) {
            }
        }
        return out;
    }

    public static void putReminder(Context c, String json) {
        try {
            LiveSchedule.Item it = LiveSchedule.Item.of(new JSONObject(json));
            if (it == null || it.ch == null || !LiveSchedule.reminder(it)) return;
            if (it.end <= System.currentTimeMillis() || LiveSchedule.find(c, it.key()) != null) return;
            LiveSchedule.add(c, it);
        } catch (JSONException e) {
            AppLog.w("live: a synced reminder was unreadable");
        }
    }

    public static void cancelReminder(Context c, String key) {
        if (LiveSchedule.find(c, key) != null) LiveSchedule.cancel(c, key);
    }

    public static void refreshLive(Context c) {
        c.sendBroadcast(new Intent(SYNCED).setPackage(c.getPackageName()));
    }
}
