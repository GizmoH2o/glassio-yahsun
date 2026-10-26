package com.glasslauncher.tv.live;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;

/**
 * For checking looks on the TV from a computer, without touching anyone's settings: a broadcast only adb can send
 * (it needs DUMP, which the shell has and apps don't). What it changes stays in memory and goes when the app does.
 *
 *   adb shell am broadcast -a com.glasslauncher.tv.live.TEST --es style sky          Live TV in a style (zero, apple, youtube, now, sky,
 *                                                                    sling, hulu, custom; off: the one chosen)
 *   adb shell am broadcast -a com.glasslauncher.tv.live.TEST --es weather rain       the home screen's weather (clear, partly, cloudy,
 *            [--ez day false] [--ei hour 21]                         fog, rain, snow, storm; off: the real one) and hour
 *   adb shell am broadcast -a com.glasslauncher.tv.live.TEST --es wallpaper weather  the home screen's wallpaper (Wallpapers.IDS; off)
 *   adb shell am broadcast -a com.glasslauncher.tv.live.TEST --es search "sky news"  the home screen's search, as if those words were said
 *   adb shell am broadcast -a com.glasslauncher.tv.live.TEST --es updates URL        the release feed Glassio → Updates checks (GitHub's
 *                                                                    releases/latest JSON; off: GitHub's), forgetting
 *                                                                    what the last check found
 *   adb shell am broadcast -a com.glasslauncher.tv.live.TEST --ei remind 603         Live TV (open): a reminder card for that channel number,
 *            [--ei in 5] [--es match "Chelsea"]                      its show starting in that many seconds (60); with
 *                                                                    match, a match alert for that team (its panel)
 *   adb shell am broadcast -a com.glasslauncher.tv.live.TEST --es follow "Arsenal"   Live TV (open): follows it, as Follow… does
 *   adb shell am broadcast -a com.glasslauncher.tv.live.TEST --es unfollow "Arsenal" and stops following it
 *   adb shell am broadcast -a com.glasslauncher.tv.live.TEST --es recproblem missed   Live TV (open): a missed recording of the channel on,
 *                                                                    told as a real one is (off: deleted)
 *   adb shell am broadcast -a com.glasslauncher.tv.live.TEST --es scores URL         Live TV (open): live scores from a stand-in (URL/soccer/
 *                                                                    all/scoreboard, …: ESPN's layout; off: ESPN's)
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
        String[] names = {"zero", "apple", "youtube", "now", "sky", "sling", "hulu", "custom"};
        for (int i = 0; i < names.length; i++) if (names[i].equals(name)) return i;
        return -1;
    }

    /** A WMO weather code for a kind of sky, -1 for off. */
    static int weather(String name) {
        switch (name) {
            case "clear": return 0;
            case "partly": return 2;
            case "cloudy": return 3;
            case "fog": return 45;
            case "rain": return 61;
            case "snow": return 71;
            case "storm": return 95;
            default: return -1;
        }
    }
}
