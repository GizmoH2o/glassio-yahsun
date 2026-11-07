package com.glasslauncher.tv.live;

import android.util.Log;

/** The app's log (adb logcat -s ZeroTV). Never logs keys. */
final class AppLog {
    private AppLog() {}

    static void w(String message) {
        try {
            Log.i("GlassioLive", message);
        } catch (RuntimeException ignored) {      // no Android here: the unit tests
        }
    }
}
