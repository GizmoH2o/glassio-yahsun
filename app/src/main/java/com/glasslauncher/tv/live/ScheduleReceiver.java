package com.glasslauncher.tv.live;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The schedule's alarms (a reminder pops up, a recording starts), and setting them again after the TV restarts. */
public class ScheduleReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) LivePlaylists.finish(c);
        if (LivePlaylists.OTHERS.equals(action)) {      // something's set on a playlist put aside
            String id = intent.getData() == null ? "" : intent.getData().getSchemeSpecificPart();
            LiveSchedule.Item it = LivePlaylists.tell(c, id);
            if (it == null) return;
            try {
                c.startActivity(ReminderActivity.intent(c, it).putExtra(ReminderActivity.EXTRA_PLAYLIST, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException e) {
                AppLog.w("live: couldn't tell of another playlist: " + e.getMessage());
            }
            return;
        }
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || LiveSeries.ACTION.equals(action)) {
            if (!LiveSeries.ACTION.equals(action)) {
                LiveSchedule.armAll(c);
                LivePlaylists.armOthers(c);
            }
            if (LiveSeries.ACTION.equals(action)) RecordService.recover(c);
            else RecordService.recoverAll(c);       // a recording the restart (or the update) stopped
            LiveSeries.arm(c);
            GuideJob.arm(c);
            // The series' airings from the guide (the network: off the main thread, the broadcast kept open).
            final PendingResult done = goAsync();
            final Context app = c.getApplicationContext();
            new Thread(() -> {
                try {
                    LiveSeries.sync(app);
                    LiveRecordings.fetchMissed(app);
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
        LiveFollow.told(c, it);
        LiveSchedule.started(c, it);     // a followed match: still "on now" for the home screen and the screensaver
        if (LiveSchedule.RECORD.equals(it.kind)) {
            RecordService.awake(c, 30_000);      // in standby: till the recorder has its own hold on the TV
            boolean on = RecordService.start(c, it, true);
            LiveSchedule.remove(c, key);
            if (!on) LiveRecordings.missed(c, it, Ui.tr("a TV não deixou começar", "the TV didn't let it start"), null);
            return;
        }
        LiveSchedule.remove(c, key);     // it's happening now
        try {
            // Over whatever is on: Glassio, the TV's home app, may open an activity from the background.
            c.startActivity(ReminderActivity.intent(c, it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            AppLog.w("live: couldn't show a reminder: " + e.getMessage());
        }
    }
}
