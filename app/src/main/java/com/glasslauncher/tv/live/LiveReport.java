package com.glasslauncher.tv.live;

import android.content.Context;

import java.util.List;

/** Live TV's part of a problem report: its guide, its connection and the channels with the most trouble. */
public final class LiveReport {
    private LiveReport() {}

    private static final int TROUBLE_SHOWN = 10;

    public static String text(Context c) {
        StringBuilder b = new StringBuilder();
        long at = XmltvStore.updatedAt(c);
        b.append("Guide: ").append(XmltvStore.count(c)).append(" shows, ")
                .append(c.getDatabasePath("live-guide.db").length() / 1_000_000).append(" MB, ")
                .append(at <= 0 ? "never updated" : (System.currentTimeMillis() - at) / 60_000 + " min since it updated").append('\n');
        b.append("Connection: ").append(LiveMerge.speed(c)).append(" kbit/s as last measured\n");
        b.append("Passing over failing sources: ").append(LiveOptions.avoidBadSources(c)).append('\n');
        List<LiveHealth.Entry> week = LiveHealth.week(c);
        b.append("Channels with trouble this week: ").append(week.size()).append('\n');
        for (int i = 0; i < Math.min(TROUBLE_SHOWN, week.size()); i++) {
            b.append("  ").append(week.get(i).name).append(": ").append(LiveHealth.describe(week.get(i))).append('\n');
        }
        return b.toString();
    }
}
