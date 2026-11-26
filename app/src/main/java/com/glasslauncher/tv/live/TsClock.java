package com.glasslauncher.tv.live;

/** How much of a show has come in, by an MPEG-TS stream's own clock (its PCR): a jump in it counts as nothing. Pure. */
final class TsClock {
    private final byte[] packet = new byte[188];
    private int have, pid = -1;
    private long last = -1, total;

    /** Ms of the stream so far. */
    long ms() {
        return total;
    }

    /** Another stream takes over, at `ms` of the show. */
    void at(long ms) {
        total = ms;
        last = -1;
        pid = -1;
        have = 0;
    }

    void feed(byte[] b, int off, int len) {
        int i = 0;
        while (i < len) {
            if (have == 0) {
                if (b[off + i] != 0x47) {       // till a packet's start
                    i++;
                    continue;
                }
                if (len - i >= 188) {
                    read(b, off + i);
                    i += 188;
                    continue;
                }
            }
            int take = Math.min(188 - have, len - i);
            System.arraycopy(b, off + i, packet, have, take);
            have += take;
            i += take;
            if (have == 188) {
                have = 0;
                read(packet, 0);
            }
        }
    }

    private void read(byte[] p, int o) {
        int afc = (p[o + 3] >> 4) & 3, len = p[o + 4] & 0xFF;
        if ((afc & 2) == 0 || len < 7 || (p[o + 5] & 0x10) == 0) return;
        int id = ((p[o + 1] & 0x1F) << 8) | (p[o + 2] & 0xFF);
        if (pid < 0) pid = id;
        if (id != pid) return;
        long v = ((p[o + 6] & 0xFFL) << 25) | ((p[o + 7] & 0xFFL) << 17) | ((p[o + 8] & 0xFFL) << 9)
                | ((p[o + 9] & 0xFFL) << 1) | ((p[o + 10] & 0xFFL) >> 7);
        long ms = v / 90;
        if (last >= 0 && ms > last && ms - last < 10_000) total += ms - last;
        last = ms;
    }
}
