package com.glasslauncher.tv.live;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The streams the account may play at once, and what uses them: one more than that and the provider cuts one off, often
 * the recording. Only Glassio's own are known. Asked about in Live TV (Live.recordNow, Live.streamsFull); a recording
 * starting by itself asks whatever is playing first (wanted).
 */
final class LiveStreams {
    private LiveStreams() {}

    /** A recording about to start by itself needs the stream that's playing. */
    static final String WANTED = "com.glasslauncher.tv.live.action.STREAM_WANTED", EXTRA_ITEM = "item", EXTRA_ONLY = "only";
    /** The answer to how many streams play (EXTRA_ONLY): this and as many. */
    static final int PLAYING = 1000;
    /** How long the question stands before the recording starts; the stream is let go a little sooner. */
    static final long ASK_MS = 20_000, LET_GO_MS = 17_000;

    /** Whether that many recordings and others at once are too many. `max` 0: the limit isn't known. Pure. */
    static boolean over(int recordings, int others, int max) {
        return max > 0 && recordings + others > max;
    }

    /** Where `at` ms in is in a recording of `elapsed` ms and `size` bytes so far: whole MPEG-TS packets. Pure. */
    static long offset(long size, long at, long elapsed) {
        if (elapsed <= 0 || size <= 0 || at <= 0) return 0;
        long b = (long) (size * Math.min(1.0, at / (double) elapsed));
        return Math.max(0, Math.min(b, size - 188L * 4096)) / 188 * 188;
    }

    /** What's being recorded now. */
    static List<LiveRecordings.Rec> recording(Context c) {
        List<LiveRecordings.Rec> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        String list = null;
        for (LiveRecordings.Rec r : LiveRecordings.all(c)) {
            if (LiveRecordings.RECORDING.equals(r.state) && !r.playlist.isEmpty()) {       // another account's streams
                if (list == null) list = String.valueOf(LivePlaylists.active(c));
                if (!list.equals(r.playlist)) continue;
            }
            if (LiveRecordings.RECORDING.equals(r.state) && !r.waiting && !LiveRecordings.interrupted(r, now)) out.add(r);
        }
        return out;
    }

    static String name(LiveRecordings.Rec r) {
        return r.title.isEmpty() ? r.ch.name : r.title;
    }

    /** "Your account plays 2 streams at once". */
    static String allows(int max) {
        return max == 1 ? Ui.tr("A sua conta toca um canal por vez", "Your account plays one stream at a time")
                : Ui.tr("A sua conta toca ", "Your account plays ") + max + Ui.tr(" canais ao mesmo tempo", " streams at once");
    }

    /** "2 of 2 streams in use". Pure. */
    static String inUse(int used, int max) {
        return used + Ui.tr(" de ", " of ") + max + Ui.tr(" canais em uso", " streams in use");
    }

    /** What uses them, in a line. */
    static String uses(List<LiveRecordings.Rec> recs, String watching) {
        StringBuilder s = new StringBuilder();
        for (LiveRecordings.Rec r : recs) {
            if (s.length() > 0) s.append("  ·  ");
            if (r.fetch) s.append(Ui.tr("Da reprise: ", "From catch-up: ")).append(name(r));
            else s.append(Ui.tr("Gravando ", "Recording ")).append(name(r)).append(Ui.tr(" até ", " until ")).append(Ui.time(r.end));
        }
        if (watching != null) {
            if (s.length() > 0) s.append("  ·  ");
            s.append(Ui.tr("Assistindo ", "Watching ")).append(watching);
        }
        return s.toString();
    }

    /** From the recorder: whether something plays that has to let its stream go for `it` (it was asked). Background thread. */
    static boolean wanted(Context c, LiveSchedule.Item it) {
        final boolean[] playing = {false};
        final CountDownLatch answered = new CountDownLatch(1);
        try {
            c.sendOrderedBroadcast(new Intent(WANTED).setPackage(c.getPackageName()).putExtra(EXTRA_ITEM, it.json().toString()), null,
                    new BroadcastReceiver() {
                        @Override public void onReceive(Context x, Intent i) {
                            playing[0] = getResultCode() == Activity.RESULT_FIRST_USER;
                            answered.countDown();
                        }
                    }, new Handler(Looper.getMainLooper()), Activity.RESULT_CANCELED, null, null);
            if (!answered.await(3, TimeUnit.SECONDS)) return false;
        } catch (JSONException | RuntimeException e) {
            AppLog.w("live: streams: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return playing[0];
    }

    /** How many streams play now (Live TV, Multiview); nothing is asked of anyone. Background thread. */
    static int playing(Context c) {
        final int[] n = {0};
        final CountDownLatch answered = new CountDownLatch(1);
        try {
            c.sendOrderedBroadcast(new Intent(WANTED).setPackage(c.getPackageName()).putExtra(EXTRA_ONLY, true), null,
                    new BroadcastReceiver() {
                        @Override public void onReceive(Context x, Intent i) {
                            n[0] = Math.max(0, getResultCode() - PLAYING);
                            answered.countDown();
                        }
                    }, new Handler(Looper.getMainLooper()), PLAYING, null, null);
            answered.await(3, TimeUnit.SECONDS);
        } catch (RuntimeException e) {
            AppLog.w("live: streams: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return n[0];
    }

    /** "No stream to spare" for where nothing can be asked (the phone page, the home screen), or null: there's one. */
    static String refusal(Context c) {
        if (LiveOptions.streamLimit(c) != LiveOptions.LIMIT_ASKS) return null;
        List<LiveRecordings.Rec> recs = recording(c);
        int max = Xtream.knownMax(c);
        if (!over(recs.size() + 1, 0, max)) return null;
        return Ui.tr("Nenhum canal livre na conta: ", "No stream to spare: ") + uses(recs, null);
    }
}
