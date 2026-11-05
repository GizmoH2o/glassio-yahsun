package com.glasslauncher.tv.live;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/**
 * Keeps the full guide fresh with Live TV closed, in Live TV's process (it holds the settings the guide goes by, and
 * XmltvStore's "already fetching"). The system wakes it every hour with a connection; it fetches only when the guide is
 * older than Update every says. Home showing looks too, for a TV that was asleep through the hours.
 */
public class GuideJob extends JobService {
    private static final int HOURLY = 0x6d1, ONCE = 0x6d2;
    private static final long HOUR = 3600_000L;
    /** The hourly wake-ups aren't exact: this early still counts, or a 6 hour guide would wait for 7. */
    private static final long EARLY = 30 * 60_000L;

    private static long lookedAt;

    /** The hourly wake-up set, once (it survives restarts). */
    static void arm(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null || js.getPendingJob(HOURLY) != null) return;
        js.schedule(new JobInfo.Builder(HOURLY, new ComponentName(c, GuideJob.class))
                .setPeriodic(HOUR)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build());
    }

    /** Home is showing: an old guide is fetched now. The settings are read in the job: this process's may be stale. */
    static void look(Context c) {
        arm(c);
        long now = System.currentTimeMillis();
        if (now - lookedAt < HOUR || now - XmltvStore.updatedAt(c) < HOUR) return;
        lookedAt = now;
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        js.schedule(new JobInfo.Builder(ONCE, new ComponentName(c, GuideJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build());
    }

    /** Whether a guide fetched `age` ago should be fetched again, with Update every at `hours`. Pure. */
    static boolean overdue(long age, int hours) {
        return age < 0 || age > hours * HOUR - EARLY;
    }

    @Override public boolean onStartJob(JobParameters params) {
        final Context app = getApplicationContext();
        Prefs.init(app);
        new Thread(() -> {
            boolean fetching = false;
            try {
                LiveRecordings.cleanup(app);
                long age = System.currentTimeMillis() - XmltvStore.updatedAt(app);
                if (!LiveOptions.fullGuide(app) || !overdue(age, LiveOptions.guideHours(app))) {
                    AppLog.w("guide: left alone, " + age / 60_000 + " min old, every " + LiveOptions.guideHours(app) + " h, on: " + LiveOptions.fullGuide(app));
                    return;
                }
                Xtream.Lineup lineup = Xtream.saved(app);
                if (lineup == null) {
                    AppLog.w("guide: not signed in");
                    return;
                }
                AppLog.w("guide: fetching with Live TV closed or open, " + age / 60_000 + " min old");
                fetching = XmltvStore.fetch(app, lineup, () -> {
                    try {       // new airings of what's followed and the series, for their reminders
                        LiveSeries.sync(app);
                        LiveFollow.sync(app);
                    } catch (RuntimeException e) {
                        AppLog.w("guide: after fetching: " + e.getMessage());
                    }
                    jobFinished(params, false);
                });
            } catch (RuntimeException e) {
                AppLog.w("guide: background: " + e.getMessage());
            } finally {
                if (!fetching) jobFinished(params, false);
            }
        }, "guide-job").start();
        return true;
    }

    /** Out of time, or the connection went: the fetch has its own thread, and the next hour tries again if it fails. */
    @Override public boolean onStopJob(JobParameters params) {
        return false;
    }
}
