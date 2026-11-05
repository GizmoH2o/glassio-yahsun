package com.glasslauncher.tv.live;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings' connection check: 20 seconds of watching what's playing (how fast it arrives, how much is held in reserve,
 * whether it stops), with the week's stops, and what to change. It can't tell the provider from the home internet
 * apart: a live stream only arrives as fast as it plays, so it judges how steady the stream is, not a top speed.
 */
final class LiveCheck {
    static final class Advice {
        boolean good;
        int buffer;
        String format;
    }

    private static final int STOPS_A_WEEK = 5;
    private static final long LOW_RESERVE_MS = 1500, THIN_RESERVE_MS = 3000;
    static final int SECONDS = 20;

    /** What to change, from the stops this week and during the check and the lowest reserve seen. Pure. */
    static Advice advise(int stopsWeek, int stopsNow, long lowestMs, int buffer, String format) {
        Advice a = new Advice();
        a.buffer = buffer;
        a.format = format;
        a.good = stopsWeek < STOPS_A_WEEK && stopsNow == 0 && lowestMs >= THIN_RESERVE_MS;
        if (a.good) return a;
        a.buffer = Math.min(LiveOptions.EXTRA, buffer + 1);
        if (lowestMs < LOW_RESERVE_MS && !LiveOptions.HLS.equals(format)) a.format = LiveOptions.HLS;
        return a;
    }

    /** Whether the check itself saw trouble, not just the week. Pure. */
    static boolean troubleNow(int stopsNow, long lowestMs) {
        return stopsNow > 0 || lowestMs < THIN_RESERVE_MS;
    }

    /** How much of `total` is covered, in percent: 100 only when all of it is. Pure. */
    static int percent(int have, int total) {
        if (total <= 0) return 0;
        return have >= total ? 100 : Math.min(99, Math.round(have * 100f / total));
    }

    // The check under way.

    private final Live live;
    private final LiveView view;
    private int seconds, stopsNow, kbpsSum, kbpsCount;
    private long lowest = Long.MAX_VALUE;
    private boolean running, wasStopped;
    private Advice advice;

    LiveCheck(Live live, LiveView view) {
        this.live = live;
        this.view = view;
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long reserve = live.bufferedMs();
            boolean stopped = reserve < 200;
            if (stopped && !wasStopped) stopsNow++;
            wasStopped = stopped;
            lowest = Math.min(lowest, reserve);
            int kbps = live.kbps();
            if (kbps > 0) {
                kbpsSum += kbps;
                kbpsCount++;
            }
            if (++seconds >= SECONDS) {
                running = false;
                advice = advise(channelStops(), stopsNow, lowest, LiveOptions.buffer(live), LiveOptions.format(live));
            } else {
                view.postDelayed(this, 1000);
            }
            view.invalidate();
        }
    };

    void start() {
        stop();
        seconds = stopsNow = kbpsSum = kbpsCount = 0;
        lowest = Long.MAX_VALUE;
        wasStopped = false;
        advice = null;
        running = live.playing() != null;
        if (running) view.postDelayed(tick, 1000);
    }

    void stop() {
        running = false;
        view.removeCallbacks(tick);
    }

    boolean running() {
        return running;
    }

    Advice advice() {
        return advice;
    }

    int secondsDone() {
        return seconds;
    }

    int stopsNow() {
        return stopsNow;
    }

    long lowest() {
        return lowest == Long.MAX_VALUE ? 0 : lowest;
    }

    /** The stream's speed through the check, Mbps. */
    float mbps() {
        return kbpsCount == 0 ? 0 : kbpsSum / (float) kbpsCount / 1000f;
    }

    /** This week's stops on what's playing: the others' trouble isn't this channel's. */
    int channelStops() {
        Xtream.Channel ch = live.playing();
        return ch == null ? 0 : LiveHealth.recent(live, ch.id, 7 * 24 * 3600_000L);
    }

    int weekStops() {
        int n = 0;
        List<LiveHealth.Entry> week = new ArrayList<>(LiveHealth.week(live));
        for (LiveHealth.Entry e : week) n += e.total();
        return n;
    }
}
