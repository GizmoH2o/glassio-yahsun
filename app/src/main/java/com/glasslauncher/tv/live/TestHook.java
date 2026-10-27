package com.glasslauncher.tv.live;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;

/**
 * For checking looks on the TV from a computer, without touching anyone's settings: a broadcast only adb can send
 * (it needs DUMP, which the shell has and apps don't). What it changes stays in memory and goes when the app does.
 *
 *   adb shell am broadcast -a com.glasslauncher.tv.live.TEST -p com.glassioyahsun.tv --es style sky   Live TV (open) in a style (zero: Glassio's,
 *                                                                    apple, youtube, now, sky, sling, hulu, custom,
 *                                                                    netflix, prime, disney, max, plex, pluto;
 *                                                                    off: the one chosen)
 *   adb shell am broadcast -a com.glasslauncher.tv.live.TEST -p com.glassioyahsun.tv --ei remind 603  Live TV (open): a reminder card for that
 *            [--ei in 5] [--es match "Chelsea"]                      channel number, its show starting in that many
 *                                                                    seconds (60); with match, a match alert for that team
 *   adb shell am broadcast -a com.glasslauncher.tv.live.TEST -p com.glassioyahsun.tv --es follow "Arsenal"    Live TV (open): follows it, as
 *                                                                    Follow… does
 *   adb shell am broadcast -a com.glasslauncher.tv.live.TEST -p com.glassioyahsun.tv --es unfollow "Arsenal"  and stops following it
 *   adb shell am broadcast -a com.glasslauncher.tv.live.TEST -p com.glassioyahsun.tv --es recproblem missed   Live TV (open): a missed recording
 *                                                                    of the channel on, told as a real one is (off: deleted)
 *   adb shell am broadcast -a com.glasslauncher.tv.live.TEST -p com.glassioyahsun.tv --es scores URL          Live TV (open): live scores from a
 *                                                                    stand-in (URL/soccer/all/scoreboard, …: ESPN's
 *                                                                    layout; off: ESPN's)
 */
final class TestHook {
    private TestHook() {}

    static final String ACTION = "com.glasslauncher.tv.live.TEST";
    private static final String DUMP = "android.permission.DUMP";
    private static final int RECEIVER_EXPORTED = 2;       // Context.RECEIVER_EXPORTED (Android 13), the shell sends it

    static void register(Context c, BroadcastReceiver r) {
        c.registerReceiver(r, new IntentFilter(ACTION), DUMP, null, RECEIVER_EXPORTED);
    }

    static void unregister(Context c, BroadcastReceiver r) {
        try {
            c.unregisterReceiver(r);
        } catch (IllegalArgumentException ignored) {
        }
    }

    /** A style's index for its name, -1 for off (or an unknown name). */
    static int style(String name) {
        String[] names = {"zero", "apple", "youtube", "now", "sky", "sling", "hulu", "custom", "netflix", "prime", "disney", "max",
                "plex", "pluto"};
        for (int i = 0; i < names.length; i++) if (names[i].equals(name)) return i;
        return -1;
    }
}
