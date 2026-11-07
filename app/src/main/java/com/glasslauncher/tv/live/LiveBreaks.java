package com.glasslauncher.tv.live;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A recording's ad breaks, found from its sound: ad spots are separated by short silences, so a run of them close
 * together is a break. Black frames didn't help (the shows cut through black all the time). Tried on a real 46-minute
 * recording of Blaze: all four breaks found, a few seconds out at most, and nothing in the show taken for one.
 * Pure but for {@link #analyze}, which reads a recording's sound.
 */
final class LiveBreaks {
    private LiveBreaks() {}

    /** Quieter than this (-45 dB of full scale) for this long is a silence. */
    static final double QUIET = Math.pow(10, -45 / 20.0) * 32768;
    static final long SILENCE_MS = 250;
    /** Silences no further apart than this, at least this many, over at least this long, are a break. */
    static final long GAP_MS = 65_000, SPAN_MS = 60_000;
    static final int COUNT = 4;
    /** In a break's last seconds, skipping it saves nothing. */
    static final long TAIL_MS = 5_000;

    /** The breaks {start, end} (ms) from silences {start, end} in time order. Pure. */
    static List<long[]> group(List<long[]> silences) {
        List<long[]> out = new ArrayList<>(), run = new ArrayList<>();
        for (long[] s : silences) {
            if (!run.isEmpty() && s[0] - run.get(run.size() - 1)[1] > GAP_MS) {
                close(run, out);
                run.clear();
            }
            run.add(s);
        }
        close(run, out);
        return out;
    }

    private static void close(List<long[]> run, List<long[]> out) {
        if (run.size() < COUNT) return;
        long start = run.get(0)[0], end = run.get(run.size() - 1)[1];
        if (end - start >= SPAN_MS) out.add(new long[] {start, end});
    }

    /** The break playback at `position` (ms) is in, worth skipping; null for none. Pure. */
    static long[] at(List<long[]> breaks, long position) {
        if (breaks != null) for (long[] b : breaks) if (position >= b[0] && position < b[1] - TAIL_MS) return b;
        return null;
    }

    /** Hears silences in 16-bit sound as it comes, timed from its first sample. */
    static final class Meter {
        final List<long[]> silences = new ArrayList<>();
        private int rate, channels;
        private long frames, quietFrom = -1;

        Meter(int rate, int channels) {
            this.rate = rate;
            this.channels = Math.max(1, channels);
        }

        /** The sound's shape changed (a new stream in the recording). */
        void format(int rate, int channels) {
            if (rate > 0) this.rate = rate;
            if (channels > 0) this.channels = channels;
        }

        /** `count` samples of `pcm`, its channels side by side. */
        void feed(short[] pcm, int count) {
            for (int i = 0; i + channels <= count; i += channels) {
                boolean quiet = true;
                for (int c = 0; c < channels; c++) if (Math.abs(pcm[i + c]) >= QUIET) quiet = false;
                if (quiet) {
                    if (quietFrom < 0) quietFrom = frames;
                } else {
                    stop();
                }
                frames++;
            }
        }

        private void stop() {
            if (quietFrom < 0) return;
            long from = quietFrom * 1000 / rate, to = frames * 1000 / rate;
            if (to - from >= SILENCE_MS) silences.add(new long[] {from, to});
            quietFrom = -1;
        }

        /** The sound's over. */
        void end() {
            stop();
        }
    }

    // MARK: - Kept beside the recording

    static File fileFor(String recording) {
        return new File(recording + ".breaks.json");
    }

    static String write(List<long[]> breaks) {
        JSONArray a = new JSONArray();
        for (long[] b : breaks) a.put(new JSONArray().put(b[0]).put(b[1]));
        return a.toString();
    }

    static List<long[]> parse(String json) {
        List<long[]> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) out.add(new long[] {a.getJSONArray(i).getLong(0), a.getJSONArray(i).getLong(1)});
        } catch (JSONException ignored) {
        }
        return out;
    }

    /** The breaks found before for this recording, or null when it hasn't been looked at. */
    static List<long[]> saved(String recording) {
        File f = fileFor(recording);
        if (!f.exists()) return null;
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[(int) Math.min(f.length(), 1 << 16)];
            int n = in.read(b);
            return parse(new String(b, 0, Math.max(0, n), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        }
    }

    /** Listens through a recording and keeps what it found beside it. Blocking: a minute or so for an hour. */
    static List<long[]> analyze(String recording) throws IOException {
        long began = System.currentTimeMillis();
        MediaExtractor ex = new MediaExtractor();
        MediaCodec dec = null;
        try {
            ex.setDataSource(recording);
            MediaFormat in = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    ex.selectTrack(i);
                    in = ex.getTrackFormat(i);
                    break;
                }
            }
            if (in == null) throw new IOException("no sound in it");
            dec = MediaCodec.createDecoderByType(in.getString(MediaFormat.KEY_MIME));
            dec.configure(in, null, null, 0);
            dec.start();
            Meter m = new Meter(in.getInteger(MediaFormat.KEY_SAMPLE_RATE), in.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            short[] pcm = new short[0];
            boolean inDone = false, outDone = false;
            while (!outDone) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("stopped");
                if (!inDone) {
                    int i = dec.dequeueInputBuffer(10_000);
                    if (i >= 0) {
                        ByteBuffer b = dec.getInputBuffer(i);
                        int n = b == null ? -1 : ex.readSampleData(b, 0);
                        if (n < 0) {
                            dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            dec.queueInputBuffer(i, 0, n, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int o = dec.dequeueOutputBuffer(info, 10_000);
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat f = dec.getOutputFormat();
                    m.format(f.getInteger(MediaFormat.KEY_SAMPLE_RATE), f.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                } else if (o >= 0) {
                    ByteBuffer out = dec.getOutputBuffer(o);
                    if (out != null && info.size > 0) {
                        out.position(info.offset);
                        out.limit(info.offset + info.size);
                        ShortBuffer s = out.order(ByteOrder.nativeOrder()).asShortBuffer();
                        int n = s.remaining();
                        if (pcm.length < n) pcm = new short[n];
                        s.get(pcm, 0, n);
                        m.feed(pcm, n);
                    }
                    dec.releaseOutputBuffer(o, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                }
            }
            m.end();
            List<long[]> breaks = group(m.silences);
            try (FileOutputStream f = new FileOutputStream(fileFor(recording))) {
                f.write(write(breaks).getBytes(StandardCharsets.UTF_8));
            }
            AppLog.w("breaks: " + breaks.size() + " found from " + m.silences.size() + " silences in " + (System.currentTimeMillis() - began) / 1000 + " s");
            return breaks;
        } finally {
            if (dec != null) {
                try {
                    dec.stop();
                } catch (IllegalStateException ignored) {
                }
                dec.release();
            }
            ex.release();
        }
    }
}
