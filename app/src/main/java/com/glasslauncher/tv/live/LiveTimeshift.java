package com.glasslauncher.tv.live;

import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;

import androidx.media3.common.C;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Pausing live TV (Sky's, YouTube TV's): from the moment of the pause, the channel's stream goes into pieces on the TV
 * (the cache, CHUNK bytes each) while the player waits; play reads them from where it stopped while they keep growing,
 * and ◀ ▶ move within them. The oldest pieces go after MAX_MS (or when room runs short). One connection to the
 * provider: the player's own stops while this one fills.
 */
final class LiveTimeshift {
    /** A piece's size: whole MPEG-TS packets (188 bytes), about 24 MB. */
    static final long CHUNK = 188L * 128 * 1024;
    static final long MAX_MS = 90 * 60_000L;
    private static final long MIN_FREE = 800L * 1024 * 1024;

    private final Context app;
    private final Xtream.Channel channel;
    private final File dir;
    private final Thread thread;
    /**
     * {the stream's own clock (ms from the first moment kept, its PCR), where that packet is, when it came in}, about
     * twice a second: where each moment is in the pieces. The stream's clock, not the arrival: a provider sends its
     * last seconds in a burst when a stream starts.
     */
    private final List<long[]> marks = new ArrayList<>();
    private final Pcr pcr = new Pcr();
    private volatile long written, first;
    private volatile boolean done;
    private volatile String failure;

    LiveTimeshift(Context c, Xtream.Channel channel) {
        app = c.getApplicationContext();
        this.channel = channel;
        dir = new File(app.getCacheDir(), "timeshift");
        clear(dir);
        dir.mkdirs();
        thread = new Thread(this::fill, "timeshift");
        thread.start();
    }

    /** The stream into the pieces until stopped; a dropped connection is picked up again (a short gap). */
    private void fill() {
        byte[] buffer = new byte[1 << 16];
        int tries = 0;
        long lastMark = 0;
        RandomAccessFile out = null;
        long outIndex = -1;
        try {
            while (!done && tries < 20) {
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL(Xtream.resolve(app, Xtream.streamUrl(app, channel, "ts"))).openConnection();
                    conn.setConnectTimeout(10_000);
                    conn.setReadTimeout(20_000);
                    conn.setRequestProperty("User-Agent", Live.USER_AGENT);
                    int code = conn.getResponseCode();
                    if (code != 200) throw new IOException("HTTP " + code);
                    try (InputStream in = conn.getInputStream()) {
                        int n;
                        while (!done && (n = in.read(buffer)) > 0) {
                            int off = 0;
                            while (off < n) {       // across a piece's end
                                long index = written / CHUNK, within = written % CHUNK;
                                if (index != outIndex) {
                                    if (out != null) out.close();
                                    out = new RandomAccessFile(piece(index), "rw");
                                    outIndex = index;
                                }
                                int part = (int) Math.min(n - off, CHUNK - within);
                                out.seek(within);
                                out.write(buffer, off, part);
                                pcr.feed(buffer, off, part, written);
                                off += part;
                                written += part;
                            }
                            tries = 0;
                            long now = SystemClock.uptimeMillis();
                            if (now - lastMark >= 2000) {
                                lastMark = now;
                                trim();
                            }
                        }
                    }
                } catch (IOException e) {
                    if (done) break;
                    tries++;
                    pcr.gap();      // what comes next joins on where this stopped
                    AppLog.w("live: pause buffer interrupted (" + e.getMessage() + "), again");
                    SystemClock.sleep(1500);
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
            if (!done) failure = Ui.tr("A TV não conseguiu guardar o canal.", "The TV couldn't keep the channel.");
        } catch (RuntimeException e) {
            failure = e.getMessage();
        } finally {
            if (out != null) try {
                out.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** The oldest pieces go: older than MAX_MS, or the TV's storage running short. */
    private void trim() {
        long now = System.currentTimeMillis(), keepFrom = first;
        synchronized (marks) {
            for (long[] m : marks) {
                if (m[2] < now - MAX_MS) keepFrom = m[1]; else break;
            }
        }
        if (dir.getUsableSpace() < MIN_FREE) keepFrom = Math.max(keepFrom, written - 4 * CHUNK);
        long firstPiece = first / CHUNK, keepPiece = keepFrom / CHUNK;
        if (keepPiece <= firstPiece) return;
        for (long i = firstPiece; i < keepPiece; i++) piece(i).delete();
        first = keepPiece * CHUNK;
        synchronized (marks) {
            while (marks.size() > 1 && marks.get(1)[1] <= first) marks.remove(0);
        }
    }

    private File piece(long index) {
        return new File(dir, index + ".ts");
    }

    /** Stops filling and deletes the pieces (on another thread: the reader may still be closing). */
    void stop() {
        done = true;
        thread.interrupt();
        new Thread(() -> {
            try {
                thread.join(3000);
            } catch (InterruptedException ignored) {
            }
            clear(dir);
        }, "timeshift-clear").start();
    }

    private static void clear(File dir) {
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
    }

    /** Why it stopped filling (null: it's fine). */
    String failure() {
        return failure;
    }

    /** Whether anything has come in yet. */
    boolean started() {
        return written > 0;
    }

    /** The oldest moment still kept, and the newest (the stream's clock, ms). */
    long oldest() {
        synchronized (marks) {
            return marks.isEmpty() ? 0 : marks.get(0)[0];
        }
    }

    long newest() {
        synchronized (marks) {
            return marks.isEmpty() ? 0 : marks.get(marks.size() - 1)[0];
        }
    }

    /** Where moment `time` is in the pieces (the packet with the clock just before it, never before the oldest kept). */
    long offsetAt(long time) {
        long at = first;
        synchronized (marks) {
            for (long[] m : marks) {
                if (m[0] > time) break;
                at = m[1];
            }
        }
        return Math.max(first, at);
    }

    /** The moment (the stream's clock) of the bytes at `offset`. */
    long timeAt(long offset) {
        long time = 0;
        synchronized (marks) {
            for (long[] m : marks) {
                if (m[1] > offset) break;
                time = m[0];
            }
        }
        return time;
    }

    /**
     * Reads the stream's clock (PCR) out of the MPEG-TS packets as they're written: a mark about twice a second.
     * Jumps (a new connection, the provider's own) are joined on, so the clock only moves ahead.
     */
    private final class Pcr {
        private final byte[] packet = new byte[188];
        private int have;
        private long packetAt;
        private int pid = -1;
        private long base = -1, last = -1, shift, lastMarkAt = -1000;
        private boolean gap;

        void gap() {
            gap = true;
            have = 0;
        }

        void feed(byte[] b, int off, int len, long at) {
            for (int i = 0; i < len; i++) {
                if (have == 0) {
                    if (b[off + i] != 0x47) continue;       // till a packet's start
                    packetAt = at + i;
                }
                packet[have++] = b[off + i];
                if (have == 188) {
                    have = 0;
                    read();
                }
            }
        }

        private void read() {
            int afc = (packet[3] >> 4) & 3, len = packet[4] & 0xFF;
            if ((afc & 2) == 0 || len < 7 || (packet[5] & 0x10) == 0) return;
            int p = ((packet[1] & 0x1F) << 8) | (packet[2] & 0xFF);
            if (pid < 0) pid = p;
            if (p != pid) return;
            long v = ((packet[6] & 0xFFL) << 25) | ((packet[7] & 0xFFL) << 17) | ((packet[8] & 0xFFL) << 9)
                    | ((packet[9] & 0xFFL) << 1) | ((packet[10] & 0xFFL) >> 7);
            long ms = v / 90;
            if (base < 0) base = ms;
            long t = ms - base + shift;
            if (last >= 0 && (gap || t < last || t > last + 10_000)) {      // a jump: on from where it was
                shift += last + 40 - t;
                t = last + 40;
            }
            gap = false;
            last = t;
            if (t - lastMarkAt < 500) return;
            lastMarkAt = t;
            synchronized (marks) {
                marks.add(new long[] {t, packetAt, System.currentTimeMillis()});
            }
        }
    }

    /** The player's source: the pieces from an offset (timeshift:<offset>), waiting at the end for more. */
    DataSource.Factory sources() {
        return () -> new Reader();
    }

    private final class Reader extends BaseDataSource {
        private Uri uri;
        private long pos;
        private RandomAccessFile in;
        private long inIndex = -1;
        private boolean opened;

        Reader() {
            super(false);
        }

        @Override public long open(DataSpec spec) throws IOException {
            uri = spec.uri;
            transferInitializing(spec);
            long from;
            try {
                from = Long.parseLong(spec.uri.getSchemeSpecificPart());
            } catch (NumberFormatException e) {
                from = first;
            }
            pos = from + spec.position;
            opened = true;
            transferStarted(spec);
            return C.LENGTH_UNSET;
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) return 0;
            while (true) {
                if (pos < first) pos = first;       // what it was reading went (too old): on from the oldest kept
                long have = written - pos;
                if (have > 0) {
                    long index = pos / CHUNK, within = pos % CHUNK;
                    if (index != inIndex) {
                        if (in != null) in.close();
                        in = new RandomAccessFile(piece(index), "r");
                        inIndex = index;
                    }
                    int n = (int) Math.min(length, Math.min(have, CHUNK - within));
                    in.seek(within);
                    n = in.read(buffer, offset, n);
                    if (n <= 0) {       // not on the disk yet: a moment
                        SystemClock.sleep(20);
                        continue;
                    }
                    pos += n;
                    bytesTransferred(n);
                    return n;
                }
                if (done || failure != null) return C.RESULT_END_OF_INPUT;
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                SystemClock.sleep(40);      // at the newest: the rest as it comes
            }
        }

        @Override public Uri getUri() {
            return uri;
        }

        @Override public void close() throws IOException {
            if (in != null) in.close();
            in = null;
            inIndex = -1;
            if (opened) {
                opened = false;
                transferEnded();
            }
        }
    }
}
