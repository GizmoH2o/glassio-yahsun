package com.glasslauncher.tv.live;

/** Whether a live stream being recorded comes in much slower than it plays, by its own clock. Pure. */
final class Trickle {
    static final int UNKNOWN = 0, FINE = 1, SLOW = 2;
    static final long WINDOW_MS = 30_000;
    /** This much a second is no trickle, whatever its clock says (it may have changed programs). */
    private static final long PLENTY = 300_000;
    /** No clock in it (radio): slow under this a second. */
    private static final long LEAST = 2_000;

    private long from = -1, streamFrom, bytes;

    /** `n` bytes came at `now`, the stream then `streamMs` on; SLOW or FINE as each half minute ends, else UNKNOWN. */
    int add(long now, long streamMs, int n) {
        if (from < 0) {
            from = now;
            streamFrom = streamMs;
        }
        bytes += n;
        long took = now - from;
        if (took < WINDOW_MS) return UNKNOWN;
        long rate = bytes * 1000 / took, came = streamMs - streamFrom;
        boolean slow = streamMs == 0 ? rate < LEAST : came * 2 < took && rate < PLENTY;
        from = now;
        streamFrom = streamMs;
        bytes = 0;
        return slow ? SLOW : FINE;
    }
}
