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
 *   adb shell am broadcast -a com.glasslauncher.tv.live.TEST -p com.glassioyahsun.tv --ez fetchguide true    Live TV (open): the full guide again, now
 *   adb shell am broadcast -a com.glasslauncher.tv.live.TEST -p com.glassioyahsun.tv --ez streaminfo true    Live TV (open): Stream info
 *   adb shell am broadcast -a com.glasslauncher.tv.live.TEST -p com.glassioyahsun.tv --ei catchup 267 --el at MS [--el from MS]
 *                                                                    Live TV (open): the show on that channel number at
 *                                                                    that moment, from `from` ms into it
 *   adb shell am broadcast -a com.glasslauncher.tv.live.TEST -p com.glassioyahsun.tv --es scores URL          Live TV (open): live scores from a
 *                                                                    stand-in (URL/soccer/all/scoreboard, …: ESPN's
 *                                                                    layout; off: ESPN's)
 *   adb shell am broadcast -a com.glasslauncher.tv.live.TEST -p com.glassioyahsun.tv --ez standin true       Live TV (open):
 *                                                                    a plain picture behind everything, for captures
 *   adb shell am broadcast -a com.glasslauncher.tv.live.TEST -p com.glassioyahsun.tv --ez badsource true     Live TV (open):
 *                                                                    the source playing as if it kept failing (false: all forgotten)
 *   adb shell am broadcast -a com.glasslauncher.tv.live.TEST -p com.glassioyahsun.tv --ez countries true     Live TV (open):
 *                                                                    which channels show a country, into live-countries.txt
 *                                                                    (Android/data/com.glassioyahsun.tv/files; names only)
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

    /** Writes (live-countries.txt in the app's external files) how many channels show a country, from where, and the ones without: names only. */
    static void countries(Context ctx, Xtream.Lineup l) {
        StringBuilder out = new StringBuilder();
        java.util.Map<String, String> cats = LiveCountry.categories(l);
        java.util.Map<String, String> catName = new java.util.HashMap<>();
        for (Xtream.Category cg : l.categories) if (cg.id != null) catName.put(cg.id, cg.name);
        int all = 0, byName = 0, byCat = 0, none = 0;
        java.util.Map<String, int[]> perCat = new java.util.TreeMap<>();
        for (Xtream.Channel ch : l.all()) {
            all++;
            String n = LiveCountry.splitCached(ch.name, false).code, c = LiveCountry.of(ch, cats);
            String cg = String.valueOf(catName.get(ch.category)) + " => " + (cats.containsKey(ch.category) ? cats.get(ch.category) : "-");
            int[] t = perCat.get(cg);
            if (t == null) perCat.put(cg, t = new int[2]);
            t[0]++;
            if (!n.isEmpty()) byName++;
            else if (!c.isEmpty()) byCat++;
            else {
                none++;
                t[1]++;
                out.append("none: " + ch.num + " " + ch.name + "  [" + catName.get(ch.category) + "]\n");
            }
        }
        for (java.util.Map.Entry<String, int[]> e : perCat.entrySet())
            out.append("cat: " + e.getKey() + "  " + e.getValue()[0] + " channels, " + e.getValue()[1] + " without\n");
        out.append("total " + all + ": name " + byName + ", category " + byCat + ", none " + none + "\n");
        try {
            Xtream.write(new java.io.File(ctx.getExternalFilesDir(null), "live-countries.txt"), out.toString());
        } catch (java.io.IOException e) {
            AppLog.w("countries: " + e);
        }
    }

    /** A style's index for its name, -1 for off (or an unknown name). */
    static int style(String name) {
        String[] names = {"zero", "apple", "youtube", "now", "sky", "sling", "hulu", "custom", "netflix", "prime", "disney", "max",
                "plex", "pluto", "cinema"};
        for (int i = 0; i < names.length; i++) if (names[i].equals(name)) return i;
        return -1;
    }
}
