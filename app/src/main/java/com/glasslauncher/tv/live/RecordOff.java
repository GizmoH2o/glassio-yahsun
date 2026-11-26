package com.glasslauncher.tv.live;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import java.io.File;
import java.io.IOException;

/**
 * The TV turned off while something records. Some TVs (TCL's) take every wake lock away a few minutes into standby and
 * the recording stops with them, so the TV is turned on again with a black screen (RecordOffActivity: first asked, or at
 * once, or not at all, LiveOptions.recordOff) until the recordings are over, and goes to sleep then. Turned off again
 * from there, it stays off. The recorder's process watches the screen and shows the black screen.
 */
public final class RecordOff {
    private RecordOff() {}

    static final String BLACK = "com.glasslauncher.tv.live.extra.BLACK";
    /** A recording starting this soon after the last one: the TV stays on for it. */
    static final long NEXT_MS = 10 * 60_000L;

    /** What to do as the screen goes off. Pure. */
    static final int NOTHING = 0, LET_GO = 1, WAKE = 2;

    static int screenOff(int option, boolean recording, boolean shown, boolean letGo) {
        if (shown) return LET_GO;       // off again, from the black screen or its question
        return !recording || letGo || option == LiveOptions.OFF_TURNS_OFF ? NOTHING : WAKE;
    }

    /** Whether the black screen goes on with nothing recording now: a recording at `next` (0: none) is close. Pure. */
    static boolean waits(long next, long now) {
        return next > 0 && next - now <= NEXT_MS;
    }

    /** Turned off for real: left alone until the TV comes on again (the recorder's process). */
    static volatile boolean letGo;
    /** The black screen, while it's up (the recorder's process). */
    static volatile RecordOffActivity shown;

    private static File mark(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), "record-off");
    }

    /** The TV was woken for a recording, or is black for one, right now: nothing else opens over it. Any process. */
    public static boolean on(Context c) {
        long age = System.currentTimeMillis() - mark(c).lastModified();
        return age >= 0 && age < 15_000;
    }

    static void touch(Context c) {
        File f = mark(c);
        try {
            if (!f.createNewFile() && !f.setLastModified(System.currentTimeMillis())) AppLog.w("live: record-off mark");
        } catch (IOException e) {
            AppLog.w("live: record-off mark: " + e.getMessage());
        }
    }

    static void untouch(Context c) {
        //noinspection ResultOfMethodCallIgnored
        mark(c).delete();
    }

    /** The show being recorded that ends last, or null. Any process. */
    static LiveRecordings.Rec recording(Context c) {
        long now = System.currentTimeMillis();
        LiveRecordings.Rec last = null;
        for (LiveRecordings.Rec r : LiveRecordings.all(c)) {
            if (!LiveRecordings.RECORDING.equals(r.state) || r.waiting || LiveRecordings.interrupted(r, now)) continue;
            if (last == null || r.end > last.end) last = r;
        }
        return last;
    }

    /** When the next recording set for later starts, or 0. */
    static long next(Context c) {
        long now = System.currentTimeMillis();
        for (LiveSchedule.Item it : LiveSchedule.all(c)) if (LiveSchedule.RECORD.equals(it.kind) && it.start > now) return it.start;
        return 0;
    }

    /** A sleep timer's time, with something recording: the black screen instead (true), nothing asked. Any process. */
    public static boolean instead(Context c) {
        if (LiveOptions.recordOff(c) == LiveOptions.OFF_TURNS_OFF || recording(c) == null) return false;
        AppLog.w("live: recording, a black screen instead of sleep");
        return open(c, true);
    }

    private static boolean open(Context c, boolean black) {
        try {
            c.startActivity(new Intent(c, RecordOffActivity.class).putExtra(BLACK, black)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
            return true;
        } catch (RuntimeException e) {
            AppLog.w("live: the black screen: " + e.getMessage());
            return false;
        }
    }

    /** The screen on again, with the question (or black at once). */
    static void wake(Context c) {
        boolean ask = LiveOptions.recordOff(c) == LiveOptions.OFF_ASKS;
        AppLog.w("live: turned off while recording: on again, " + (ask ? "asking" : "black"));
        light(c, ask, 0);
    }

    /**
     * The black screen's window turns the screen on (a wake lock didn't, on a TCL). That TV takes no wake for two
     * seconds after the screen went off: again, a few times, until it's on.
     */
    private static void light(Context c, boolean ask, int tries) {
        if (letGo) return;
        touch(c);
        boolean opened = open(c, !ask);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (letGo) return;
            boolean lit = ((PowerManager) c.getSystemService(Context.POWER_SERVICE)).isInteractive();
            if (lit && shown != null || lit && !on(c)) return;      // on (or left already)
            if (opened && tries < 4) {
                light(c, ask, tries + 1);
                return;
            }
            AppLog.w("live: the TV didn't come on again for the recording");
            RecordOffActivity a = shown;
            if (a != null) a.gone();
            untouch(c);
            if (lit) {      // on, without the black screen: never left on by itself
                letGo = true;
                sleep(c, null);
            }
        }, 2_000);
    }

    /** The TV to standby: its sleep key through its debugging connection, else the key service's; `failed`: neither could. */
    static void sleep(Context c, Runnable failed) {
        Context app = c.getApplicationContext();
        PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        new Thread(() -> {
            boolean asked = com.glasslauncher.tv.data.LocalShell.runCommand(app, "input keyevent KEYCODE_SLEEP");
            if (asked) android.os.SystemClock.sleep(4000);
            if (!pm.isInteractive()) return;
            app.sendBroadcast(new Intent(app, com.glasslauncher.tv.data.StandbyReceiver.class));
            android.os.SystemClock.sleep(4000);
            if (!pm.isInteractive()) return;
            AppLog.w("live: couldn't put the TV to sleep");
            if (failed != null) new Handler(Looper.getMainLooper()).post(failed);
        }, "record-off-sleep").start();
    }
}
