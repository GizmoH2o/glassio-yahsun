package com.glasslauncher.tv.live;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * What went wrong, kept for afterwards (the TV's own log is tiny and lost on a restart): a crash in any process, and
 * Live TV's screen not answering for a while (a freeze), with the threads' stacks. In the app's external files
 * folder, which adb can read (adb shell cat /sdcard/Android/data/com.glassioyahsun.tv/files/problems.txt). Web addresses are
 * taken out (a stream's carries the login); the newest problems are kept, the file stays small.
 */
final class CrashLog {
    private CrashLog() {}

    private static final long MAX_BYTES = 64 * 1024;
    private static final long FREEZE_MS = 8_000;

    /** Records crashes in this process, then lets them go on as before. */
    static void install(Context c) {
        final Context app = c.getApplicationContext();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                StringWriter s = new StringWriter();
                error.printStackTrace(new PrintWriter(s));
                write(app, "crash in thread " + thread.getName(), s.toString());
            } catch (Throwable ignored) {
            }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    /** Watches the screen's thread (Live TV's): stuck for FREEZE_MS, every thread's stack is recorded, once a freeze. */
    static void watchFreezes(Context c) {
        final Context app = c.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        final long[] answered = {SystemClock.uptimeMillis()};
        Thread t = new Thread(() -> {
            boolean reported = false;
            while (true) {
                main.post(() -> answered[0] = SystemClock.uptimeMillis());
                try {
                    Thread.sleep(2_000);
                } catch (InterruptedException e) {
                    return;
                }
                long late = SystemClock.uptimeMillis() - answered[0];
                if (late < FREEZE_MS) {
                    reported = false;
                    continue;
                }
                if (reported) continue;
                reported = true;
                StringBuilder s = new StringBuilder();
                for (java.util.Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
                    Thread th = e.getKey();
                    if (th != Looper.getMainLooper().getThread() && e.getValue().length == 0) continue;
                    s.append(th == Looper.getMainLooper().getThread() ? "MAIN " : "").append(th.getName())
                            .append(" (").append(th.getState()).append(")\n");
                    for (StackTraceElement el : e.getValue()) s.append("    at ").append(el).append('\n');
                }
                write(app, "screen not answering for " + late / 1000 + " s", s.toString());
            }
        }, "freeze-watch");
        t.setDaemon(true);
        t.start();
    }

    private static synchronized void write(Context c, String what, String detail) {
        File dir = c.getExternalFilesDir(null);
        if (dir == null) return;
        File f = new File(dir, "problems.txt");
        String process = android.app.Application.getProcessName();
        String text = "=== " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date()) + "  " + process
                + "  " + what + "\n" + detail.replaceAll("(?i)\\b(?:https?|rtmp|rtsp)://\\S+", "<address>") + "\n";
        try {
            if (f.length() > MAX_BYTES) {       // the older half goes
                byte[] all = java.nio.file.Files.readAllBytes(f.toPath());
                java.nio.file.Files.write(f.toPath(), java.util.Arrays.copyOfRange(all, all.length / 2, all.length));
            }
            try (FileOutputStream out = new FileOutputStream(f, true)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
        }
        AppLog.w("problem recorded: " + what);
    }
}
