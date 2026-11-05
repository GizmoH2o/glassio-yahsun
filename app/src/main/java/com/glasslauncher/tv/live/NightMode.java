package com.glasslauncher.tv.live;

import android.content.Context;

import java.io.File;
import java.nio.file.Files;
import java.util.Calendar;

/**
 * Night mode (Settings › Look &amp; motion): from an hour until 7 in the morning, Glassio and Live TV dim, and Live TV plays
 * quieter. In a file: the home screen's process sets it, Live TV's reads it.
 */
public final class NightMode {
    private NightMode() {}

    public static final int DIM_PERCENT = 35, VOLUME_PERCENT = 60, UNTIL = 7;

    private static File file(Context c) {
        return new File(c.getFilesDir(), "ui_night");
    }

    /** The hour it starts (21 to 23), 0: off. */
    public static int from(Context c) {
        try {
            return Integer.parseInt(new String(Files.readAllBytes(file(c).toPath())).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    public static void setFrom(Context c, int hour) {
        File f = file(c);
        try {
            if (hour <= 0) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            } else {
                Files.write(f.toPath(), String.valueOf(hour).getBytes());
            }
        } catch (Exception e) {
            AppLog.w("night mode: couldn't save");
        }
    }

    /** Whether it's on at this hour, starting at `from` (0: never). Pure. */
    public static boolean on(int from, int hour) {
        return from > 0 && (hour >= from || hour < UNTIL);
    }

    public static boolean now(Context c) {
        return on(from(c), Calendar.getInstance().get(Calendar.HOUR_OF_DAY));
    }
}
