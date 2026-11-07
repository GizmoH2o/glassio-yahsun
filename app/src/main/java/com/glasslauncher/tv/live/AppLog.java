package com.glasslauncher.tv.live;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The app's log (adb logcat -s ZeroTV). Never logs keys. Live TV keeps the last of it in a small file too, for a problem
 * report: the TV's own log can be gone within a minute.
 */
final class AppLog {
    private AppLog() {}

    private static final int KEEP = 96 * 1024;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "app-log");
        t.setDaemon(true);
        return t;
    });
    private static volatile File file;

    /** From now on, this process's lines go to `f` as well. */
    static void keepIn(File f) {
        file = f;
    }

    static void w(String message) {
        try {
            Log.i("GlassioLive", message);
        } catch (RuntimeException ignored) {      // no Android here: the unit tests
        }
        final File f = file;
        if (f == null) return;
        final String line = new SimpleDateFormat("MMM d HH:mm:ss", Locale.US).format(new Date()) + "  " + message + "\n";
        IO.execute(() -> {
            try {
                try (Writer out = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8)) {
                    out.write(line);
                }
                if (f.length() > KEEP * 2L) {
                    String all = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                    Files.write(f.toPath(), tail(all, KEEP).getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {     // only a log
            }
        });
    }

    /** The last of `text`, at most `most` characters, whole lines only. Pure. */
    static String tail(String text, int most) {
        if (text.length() <= most) return text;
        int cut = text.indexOf('\n', text.length() - most - 1);
        return cut < 0 ? "" : text.substring(cut + 1);
    }
}
