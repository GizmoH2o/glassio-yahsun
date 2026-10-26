package com.glasslauncher.tv.live;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The schedule's alarms (a reminder pops up, a recording starts), and setting them again after the TV restarts. */
public class ScheduleReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || LiveSeries.ACTION.equals(action)) {
            if (!LiveSeries.ACTION.equals(action)) LiveSchedule.armAll(c);
            LiveSeries.arm(c);
            // The series' airings from the guide (the network: off the main thread, the broadcast kept open).
            final PendingResult done = goAsync();
            final Context app = c.getApplicationContext();
            new Thread(() -> {
                try {
                    LiveSeries.sync(app);
                    LiveFollow.sync(app);       // what's followed, through the full guide
                } finally {
                    done.finish();
                }
            }, "series").start();
            return;
        }
        String key = LiveSchedule.keyOf(intent);
        LiveSchedule.Item it = key == null ? null : LiveSchedule.find(c, key);
        if (it == null) return;
        LiveSchedule.remove(c, key);     // it's happening now
        if (LiveSchedule.RECORD.equals(it.kind)) {
            RecordService.start(c, it);
            return;
        }
        try {
            // Over whatever is on: Glassio, the TV's home app, may open an activity from the background.
            c.startActivity(ReminderActivity.intent(c, it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            AppLog.w("live: couldn't show a reminder: " + e.getMessage());
        }
    }
}
