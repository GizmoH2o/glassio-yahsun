package com.glasslauncher.tv.live;

import android.view.View;

/**
 * What TalkBack hears in Live TV, which is drawn by hand: every line goes through [say], which tells the screen reader
 * (and keeps it as the view's description, so it's read again when TalkBack lands on it). Channels are said as they show,
 * their country after them ("622, Sky Sports News, UK"); a show with its time, channel and state. The test broadcast's
 * speech mode logs each line (tag GlassioLive, "speech: …"), for checking without turning TalkBack on.
 */
final class LiveSpeech {
    private LiveSpeech() {}

    /** The test hook's: each line said is logged too. */
    static volatile boolean log;

    /** The parts that aren't empty, with commas. Pure. */
    static String join(String... parts) {
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.trim().isEmpty()) continue;
            if (b.length() > 0) b.append(", ");
            b.append(p.trim());
        }
        return b.toString();
    }

    /** "622, Sky Sports News, UK". Pure. */
    static String channel(int num, String name, String country) {
        String cc = LiveCountry.ARABIC.equals(country) ? "Arabic" : country;
        return join(num > 0 ? String.valueOf(num) : "", name, cc);
    }

    /** "8:00 PM, Gogglebox, 622, Channel 4, UK, on now, reminder set". Pure. */
    static String show(String when, String title, String channel, String... states) {
        String[] all = new String[3 + states.length];
        all[0] = when;
        all[1] = title;
        all[2] = channel;
        System.arraycopy(states, 0, all, 3, states.length);
        return join(all);
    }

    /** Says `text` from `v` (nothing for none). */
    static void say(View v, CharSequence text) {
        if (v == null || text == null || text.length() == 0) return;
        String t = text.toString();
        if (log) AppLog.w("speech: " + t);
        v.setContentDescription(t);
        v.announceForAccessibility(t);
    }
}
