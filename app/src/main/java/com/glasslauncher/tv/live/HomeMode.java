package com.glasslauncher.tv.live;

/** A problem worth telling (a recording that failed): in the log; Live TV shows its own card for it as well. */
final class HomeMode {
    private HomeMode() {}

    static void notice(android.content.Context c, String text) {
        AppLog.w("notice: " + text);
    }
}
