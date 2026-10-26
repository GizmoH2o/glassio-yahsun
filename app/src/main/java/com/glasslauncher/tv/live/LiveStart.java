package com.glasslauncher.tv.live;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.SystemClock;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Opens Live TV by itself, when its settings say so: when the TV starts (the home screen's first showing after it
 * booted) and when it wakes up from standby (the screen comes on). It runs in the home screen's process (:home): as
 * the TV's home app, Glassio may open an activity from the background, which other apps can't since Android 10.
 */
final class LiveStart {
    private LiveStart() {}

    /** The home screen is showing: the first time since the TV started, Live TV opens (when on). */
    static void homeShown(Context c) {
        long boot = System.currentTimeMillis() - SystemClock.elapsedRealtime();
        File f = new File(c.getFilesDir(), "live-last-boot");
        long last = 0;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[32];
            int n = in.read(b);
            if (n > 0) last = Long.parseLong(new String(b, 0, n, StandardCharsets.UTF_8).trim());
        } catch (IOException | NumberFormatException ignored) {
        }
        if (Math.abs(boot - last) < 120_000) return;    // this boot was seen (the clock may have moved a little)
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(String.valueOf(boot).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
        // Recorded even when off: turning it on later waits for the next start.
        if (LiveOptions.startOnBoot(c)) open(c, "start");
    }

    /** Listens for the TV waking up (the screen coming on), while this process lives. */
    static void watchWake(Application app) {
        app.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                if (LiveOptions.startOnWake(c)) open(c, "wake");
            }
        }, new IntentFilter(Intent.ACTION_SCREEN_ON));
    }

    private static void open(Context c, String why) {
        if (!Xtream.ready(c)) return;    // not signed in to a provider: nothing to open on
        try {
            c.startActivity(HomeApps.liveIntent(c, -1, false));
            AppLog.w("live: opened on " + why);
        } catch (RuntimeException e) {
            AppLog.w("live: couldn't open on " + why + ": " + e.getMessage());
        }
    }
}
