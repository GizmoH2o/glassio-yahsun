package com.glasslauncher.tv.live;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * Records shows to the TV's storage: the channel's stream (MPEG-TS, which plays back as it is) saved from now (or
 * the show's start) to its end and two minutes more, in its own process (:rec), a foreground service so it goes on
 * with Live TV closed. A dropped stream is opened again (the file goes on); it stops early when storage runs low. Each
 * recording is a thread; the service stops when none is left.
 */
public class RecordService extends Service {
    private static final String START = "com.glasslauncher.tv.live.action.RECORD", STOP = "com.glasslauncher.tv.live.action.STOP_RECORDING";
    private static final String EXTRA_ITEM = "com.glasslauncher.tv.live.extra.RECORD_ITEM", EXTRA_ID = "com.glasslauncher.tv.live.extra.RECORD_ID";
    private static final String CHANNEL = "recordings";
    private static final long MIN_FREE = 500L * 1024 * 1024;

    private final Map<String, Thread> running = new HashMap<>();
    /** Stopped by hand (Stop recording): not a problem to tell. */
    private final java.util.Set<String> stopping = new java.util.HashSet<>();

    /** Records `it` (its show) from now. */
    static void start(Context c, LiveSchedule.Item it) {
        try {
            c.startForegroundService(new Intent(c, RecordService.class).setAction(START).putExtra(EXTRA_ITEM, it.json().toString()));
        } catch (JSONException | RuntimeException e) {
            AppLog.w("live: couldn't start a recording: " + e.getMessage());
        }
    }

    /** Stops recording `id` (what's recorded so far stays). */
    static void stop(Context c, String id) {
        try {
            c.startService(new Intent(c, RecordService.class).setAction(STOP).putExtra(EXTRA_ID, id));
        } catch (RuntimeException e) {
            AppLog.w("live: couldn't stop a recording: " + e.getMessage());
        }
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(1, notification());     // first, always (the system expects it right away)
        String action = intent == null ? null : intent.getAction();
        if (STOP.equals(action)) {
            Thread t;
            String id = intent.getStringExtra(EXTRA_ID);
            synchronized (running) {
                t = running.get(id);
            }
            if (t != null) {
                synchronized (stopping) {
                    stopping.add(id);
                }
                t.interrupt();
            }
        } else if (START.equals(action)) {
            try {
                LiveSchedule.Item it = LiveSchedule.Item.of(new JSONObject(intent.getStringExtra(EXTRA_ITEM)));
                if (it != null) record(it);
            } catch (JSONException | NullPointerException e) {
                AppLog.w("live: a recording without its show");
            }
        }
        finishIfIdle();
        return START_NOT_STICKY;
    }

    private void record(LiveSchedule.Item it) {
        final String id = LiveRecordings.idOf(it.ch.id, it.start);
        synchronized (running) {
            if (running.containsKey(id)) return;    // already on
        }
        LiveRecordings.Rec r = new LiveRecordings.Rec();
        r.id = id;
        r.ch = it.ch;
        r.title = it.title;
        r.about = it.about;
        r.start = System.currentTimeMillis();    // from now: a show on now, or one started early (Start recordings)
        r.end = it.end;
        r.file = new File(LiveRecordings.folder(this), it.ch.id + "-" + it.start + ".ts").getPath();
        LiveRecordings.put(this, r);
        Thread t = new Thread(() -> {
            try {
                save(r);
            } finally {
                synchronized (running) {
                    running.remove(id);
                }
                finishIfIdle();
            }
        }, "record " + it.ch.id);
        synchronized (running) {
            running.put(id, t);
        }
        updateNotification();
        t.start();
        AppLog.w("live: recording " + it.ch.name + " until " + Ui.time(it.end));
    }

    /** The stream into the file until the end (or stopped, or no room); then done or failed, and why. */
    private void save(LiveRecordings.Rec r) {
        long until = r.end + LiveOptions.recordLate(this) * 60_000L, lastSaved = 0;     // Keep recording
        int tries = 0;
        boolean full = false, unwritable = false;
        File f = new File(r.file);
        byte[] buffer = new byte[1 << 16];
        try (FileOutputStream out = new FileOutputStream(f, true)) {
            while (System.currentTimeMillis() < until && !Thread.currentThread().isInterrupted() && tries < 30) {
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL(Xtream.resolve(this, Xtream.streamUrl(this, r.ch, "ts"))).openConnection();
                    conn.setConnectTimeout(10_000);
                    conn.setReadTimeout(20_000);
                    conn.setRequestProperty("User-Agent", Live.USER_AGENT);
                    int code = conn.getResponseCode();
                    if (code != 200) throw new IOException("HTTP " + code);
                    try (InputStream in = conn.getInputStream()) {
                        int n;
                        while ((n = in.read(buffer)) > 0) {
                            out.write(buffer, 0, n);
                            r.size += n;
                            if (System.currentTimeMillis() >= until || Thread.currentThread().isInterrupted()) break;
                            long now = SystemClock.uptimeMillis();
                            if (now - lastSaved > 30_000) {      // the list shows how far it is
                                lastSaved = now;
                                LiveRecordings.put(this, r);
                                if (f.getParentFile().getUsableSpace() < MIN_FREE) {
                                    AppLog.w("live: recording stopped: the TV's storage is almost full");
                                    full = true;
                                    Thread.currentThread().interrupt();
                                }
                            }
                        }
                    }
                } catch (IOException e) {
                    tries++;
                    AppLog.w("live: recording interrupted (" + e.getMessage() + "), again");
                    SystemClock.sleep(3000);
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        } catch (IOException e) {
            AppLog.w("live: couldn't write the recording: " + e.getMessage());
            unwritable = true;
        }
        long now = System.currentTimeMillis();
        boolean byHand;
        synchronized (stopping) {
            byHand = stopping.remove(r.id);
        }
        r.state = r.size > 1_000_000 ? LiveRecordings.DONE : LiveRecordings.FAILED;
        boolean early = !byHand && now < r.end - 60_000;     // stopped well before the show's end, not by hand
        if (r.state.equals(LiveRecordings.FAILED) || early) {
            r.why = full ? Ui.tr("o armazenamento da TV está cheio", "the TV's storage is full")
                    : unwritable ? Ui.tr("não deu para salvar na TV", "it couldn't be saved on the TV")
                    : r.size == 0 ? Ui.tr("o canal não abriu", "the channel didn't play")
                    : Ui.tr("o canal caiu várias vezes", "the channel kept dropping");
        }
        r.end = Math.min(r.end, now);    // stopped early: as long as it really is
        LiveRecordings.put(this, r);
        AppLog.w("live: recording " + r.state + ", " + r.size / 1_000_000 + " MB" + (r.why.isEmpty() ? "" : " (" + r.why + ")"));
        if (!r.why.isEmpty()) {
            LiveRecordings.tell(this, r, r.state.equals(LiveRecordings.FAILED) ? Ui.tr("Gravação falhou", "Recording failed")
                    : Ui.tr("Gravação parou antes", "Recording stopped early"));
        }
    }

    private void finishIfIdle() {
        synchronized (running) {
            if (!running.isEmpty()) {
                updateNotification();
                return;
            }
        }
        stopForeground(true);
        stopSelf();
    }

    private void updateNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(1, notification());
    }

    private Notification notification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, Ui.tr("Gravações", "Recordings"), NotificationManager.IMPORTANCE_LOW));
        }
        int n;
        synchronized (running) {
            n = running.size();
        }
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(com.glasslauncher.tv.R.drawable.ic_launcher_glass)
                .setContentTitle(Ui.tr("Gravando", "Recording"))
                .setContentText(n <= 1 ? Ui.tr("Um programa", "One show") : n + Ui.tr(" programas", " shows"))
                .setOngoing(true)
                .build();
    }
}
