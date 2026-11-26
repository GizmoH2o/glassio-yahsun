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
    private static final String EXTRA_ITEM = "com.glasslauncher.tv.live.extra.RECORD_ITEM", EXTRA_ID = "com.glasslauncher.tv.live.extra.RECORD_ID",
            EXTRA_FETCH = "com.glasslauncher.tv.live.extra.RECORD_FETCH", EXTRA_ASKS = "com.glasslauncher.tv.live.extra.RECORD_ASKS";
    private static final String CHANNEL = "recordings";
    private static final long MIN_FREE = 500L * 1024 * 1024;

    private final Map<String, Thread> running = new HashMap<>();
    /** Those waiting for a stream (from the catch-up): nothing is recording in them. */
    private final java.util.Set<String> waiting = new java.util.HashSet<>();
    /** The playlist each of those running is from: each account has its own streams. */
    private final Map<String, String> account = new HashMap<>();

    /** How many streams of playlist `list` record now (with `running` held). */
    private int streamsOf(String list) {
        int n = 0;
        for (String id : running.keySet()) if (!waiting.contains(id) && list.equals(account.get(id))) n++;
        return n;
    }

    /** How many streams playlist `list`'s account plays (0: not known, or the limit isn't minded). */
    private int maxOf(String list) {
        if (LiveOptions.streamLimit(this) == LiveOptions.LIMIT_OFF) return 0;
        try {
            return Xtream.as(LivePlaylists.loginOf(this, list), () -> Xtream.knownMax(this));
        } catch (IOException e) {
            return 0;
        }
    }
    /** Stopped by hand (Stop recording): not a problem to tell. */
    private final java.util.Set<String> stopping = new java.util.HashSet<>();

    /** Records `it` (its show) from now; false when Android didn't let it. */
    static boolean start(Context c, LiveSchedule.Item it) {
        return start(c, it, false);
    }

    /** `asks`: not started from what's playing (an alarm, the phone): that gets to let its stream go first (LiveStreams). */
    static boolean start(Context c, LiveSchedule.Item it, boolean asks) {
        try {
            c.startForegroundService(new Intent(c, RecordService.class).setAction(START).putExtra(EXTRA_ITEM, it.json().toString())
                    .putExtra(EXTRA_ASKS, asks));
            return true;
        } catch (JSONException | RuntimeException e) {
            AppLog.w("live: couldn't start a recording: " + e.getMessage());
            return false;
        }
    }

    /** The show `it`, over, recorded from its channel's catch-up (when there's a stream for it). */
    static boolean fetch(Context c, LiveSchedule.Item it) {
        try {
            c.startForegroundService(new Intent(c, RecordService.class).setAction(START).putExtra(EXTRA_ITEM, it.json().toString())
                    .putExtra(EXTRA_FETCH, true));
            return true;
        } catch (JSONException | RuntimeException e) {
            AppLog.w("live: couldn't start a fetch: " + e.getMessage());
            return false;
        }
    }

    /** The TV kept awake for `ms` (an alarm's moment in standby). */
    static void awake(Context c, long ms) {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) c.getSystemService(Context.POWER_SERVICE);
            pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "glassio:recording-start").acquire(ms);
        } catch (RuntimeException e) {
            AppLog.w("live: awake: " + e.getMessage());
        }
    }

    /**
     * Recordings whose recorder was stopped from outside (a restart, an update, the TV short of memory): the show still
     * on, the rest of it into the same file; over, what there is of it is kept, and said.
     */
    static void recover(Context c) {
        recover(c, null, false);
    }

    /** Just after a restart or an update: whatever was recording was stopped, however recent (one on again by now goes on). */
    static void recoverAll(Context c) {
        recover(c, null, true);
    }

    /** `here`: the recorder itself, just started again, so what isn't in its hands is nobody's, however recent. */
    private static void recover(Context c, RecordService here, boolean restarted) {
        long now = System.currentTimeMillis();
        for (LiveRecordings.Rec r : LiveRecordings.all(c)) {
            if (!(here != null ? here.orphan(r) : restarted ? LiveRecordings.RECORDING.equals(r.state) : LiveRecordings.interrupted(r, now))) continue;
            if (r.fetch) {      // from its start again, not for ever
                if (r.tries < 3) {
                    if (here != null) here.fetchNow(LiveRecordings.itemOf(r));
                    if (here != null || fetch(c, LiveRecordings.itemOf(r))) continue;
                }
                r.state = LiveRecordings.FAILED;
                r.waiting = false;
                r.why = Ui.tr("a reprise foi interrompida", "the catch-up was interrupted");
                LiveRecordings.update(c, r);
                continue;
            }
            if (now < r.end - 30_000) {
                LiveSchedule.Item it = new LiveSchedule.Item();
                it.kind = LiveSchedule.RECORD;
                it.ch = r.ch;
                it.start = r.showStart();
                it.end = r.end;
                it.title = r.title;
                it.about = r.about;
                AppLog.w("live: a recording was stopped, on with it");
                if (here != null) {     // directly: another start of itself could find it already stopping
                    here.record(it, false);
                    continue;
                }
                if (start(c, it)) continue;
            }
            r.size = r.file == null || r.file.isEmpty() ? 0 : new File(r.file).length();
            r.state = r.size > 1_000_000 ? LiveRecordings.DONE : LiveRecordings.FAILED;
            r.why = Ui.tr("a gravação foi interrompida", "the recording was interrupted");
            if (r.beat > r.start && r.beat < r.end) r.end = r.beat;
            if (!LiveRecordings.update(c, r)) continue;
            LiveRecordings.tell(c, r, r.state.equals(LiveRecordings.FAILED) ? Ui.tr("Gravação falhou", "Recording failed")
                    : Ui.tr("Gravação parou antes", "Recording stopped early"));
        }
    }

    private android.os.PowerManager.WakeLock awake;
    private android.net.wifi.WifiManager.WifiLock wifi;

    /** While anything records: the TV's chip and its Wi-Fi stay on, also with the screen off. */
    private synchronized void hold(boolean on) {
        try {
            if (on && awake == null) {
                awake = getSystemService(android.os.PowerManager.class).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "glassio:recording");
                awake.setReferenceCounted(false);
                awake.acquire(12 * 3600_000L);      // an end to it, whatever happens
                wifi = ((android.net.wifi.WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE))
                        .createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "glassio:recording");
                wifi.setReferenceCounted(false);
                wifi.acquire();
            } else if (!on && awake != null) {
                if (awake.isHeld()) awake.release();
                if (wifi != null && wifi.isHeld()) wifi.release();
                awake = null;
                wifi = null;
            }
        } catch (RuntimeException e) {
            AppLog.w("live: recording awake: " + e.getMessage());
        }
    }

    private android.content.BroadcastReceiver screen;

    @Override public void onCreate() {
        super.onCreate();
        RecordOff.letGo = false;
        screen = new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) RecordOff.letGo = false;
                else screenOff();
            }
        };
        android.content.IntentFilter f = new android.content.IntentFilter(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screen, f);
    }

    /** The TV turned off while something records: RecordOff's say. */
    private void screenOff() {
        boolean recording;
        synchronized (running) {
            recording = running.size() > waiting.size();
        }
        RecordOffActivity up = RecordOff.shown;
        int what = RecordOff.screenOff(LiveOptions.recordOff(this), recording, up != null, RecordOff.letGo);
        if (what == RecordOff.LET_GO) {
            RecordOff.letGo = true;
            up.gone();
        } else if (what == RecordOff.WAKE) {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                synchronized (running) {
                    if (running.isEmpty()) return;
                }
                if (!getSystemService(android.os.PowerManager.class).isInteractive()) RecordOff.wake(getApplicationContext());
            }, 2500);
        }
    }

    @Override public void onDestroy() {
        unregisterReceiver(screen);
        hold(false);
        super.onDestroy();
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

    private boolean orphan(LiveRecordings.Rec r) {
        synchronized (running) {
            return LiveRecordings.RECORDING.equals(r.state) && !running.containsKey(r.id);
        }
    }

    /** The last start it was given: one that came after keeps it going. */
    private volatile int last;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        last = startId;
        try {
            startForeground(1, notification());     // first, always (the system expects it right away)
        } catch (RuntimeException e) {      // started again by the system at a time it doesn't allow it
            AppLog.w("live: recorder: " + e.getMessage());
            if (intent == null) {
                stopSelf();
                return START_NOT_STICKY;
            }
        }
        String action = intent == null ? null : intent.getAction();
        if (intent == null) {       // the system had to stop it: on with what was recording
            recover(this, this, false);
            finishIfIdle();
            return START_STICKY;
        }
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
            } else {        // its recorder was stopped from outside: what there is of it is kept
                LiveRecordings.Rec r = id == null ? null : LiveRecordings.find(this, id);
                if (r != null && LiveRecordings.RECORDING.equals(r.state)) {
                    r.size = r.file == null || r.file.isEmpty() ? 0 : new File(r.file).length();
                    r.state = r.size > 1_000_000 ? LiveRecordings.DONE : LiveRecordings.FAILED;
                    if (r.state.equals(LiveRecordings.FAILED)) r.why = Ui.tr("a gravação foi interrompida", "the recording was interrupted");
                    if (r.beat > r.start && r.beat < r.end) r.end = r.beat;
                    LiveRecordings.update(this, r);
                }
            }
        } else if (START.equals(action)) {
            try {
                LiveSchedule.Item it = LiveSchedule.Item.of(new JSONObject(intent.getStringExtra(EXTRA_ITEM)));
                if (it != null && intent.getBooleanExtra(EXTRA_FETCH, false)) fetchNow(it);
                else if (it != null) record(it, intent.getBooleanExtra(EXTRA_ASKS, false));
            } catch (JSONException | NullPointerException e) {
                AppLog.w("live: a recording without its show");
            }
        }
        finishIfIdle();
        return START_STICKY;
    }

    private void record(LiveSchedule.Item it, boolean asks) {
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
        r.showEnd = it.end;
        r.file = new File(LiveRecordings.folder(this), it.ch.id + "-" + it.start + ".ts").getPath();
        LiveRecordings.Rec before = LiveRecordings.find(this, id);
        r.playlist = before != null && LiveRecordings.RECORDING.equals(before.state) && !before.playlist.isEmpty() ? before.playlist
                : String.valueOf(LivePlaylists.active(this));
        if (before != null && before.file != null && !before.file.isEmpty() && new File(before.file).length() > 0) {
            r.file = before.file;       // on with one that was stopped (or recorded before, by hand): the same file, from its start
            final long had = before.end;
            if (!LiveRecordings.RECORDING.equals(before.state)) r.hadUntil = had;
            r.start = before.start;
            r.size = new File(r.file).length();
            r.gaps = before.gaps;
            r.from = before.from;
            r.gapFrom = LiveRecordings.RECORDING.equals(before.state) ? before.beat : before.end;
        }
        r.beat = System.currentTimeMillis();
        hold(true);
        LiveRecordings.put(this, r);
        com.glasslauncher.tv.data.HaEvents.recording(this, true, r.title, LiveCountry.named(this, r.ch));
        Thread t = new Thread(() -> {
            try {
                if (asks) askForStream(it, r.playlist);
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
            account.put(id, r.playlist);
        }
        updateNotification();
        t.start();
        AppLog.w("live: recording " + it.ch.name + " until " + Ui.time(it.end));
    }

    /** `address` opened, sent on as often as the provider says (from http to https too, which Android doesn't do by itself). */
    private static HttpURLConnection open(String address, int readMs) throws IOException {
        URL url = new URL(address);
        for (int hops = 0; ; hops++) {
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(readMs);
            conn.setRequestProperty("User-Agent", Live.USER_AGENT);
            int code = conn.getResponseCode();
            String to = code >= 301 && code <= 308 && code != 304 ? conn.getHeaderField("Location") : null;
            if (to == null || hops >= 5) return conn;
            conn.disconnect();
            url = new URL(url, to);
        }
    }

    private void fetchNow(LiveSchedule.Item it) {
        final String id = LiveRecordings.fetchId(it.ch.id, it.start);
        synchronized (running) {
            if (running.containsKey(id)) return;
        }
        LiveRecordings.Rec before = LiveRecordings.find(this, id), r = new LiveRecordings.Rec();
        r.id = id;
        r.ch = it.ch;
        r.title = it.title;
        r.about = it.about;
        r.start = it.start;
        r.end = r.showEnd = it.end;
        r.fetch = r.waiting = true;
        LiveRecordings.Rec first = LiveRecordings.find(this, LiveRecordings.idOf(it.ch.id, it.start));       // the one it makes up for
        r.playlist = before != null && !before.playlist.isEmpty() ? before.playlist : first != null && !first.playlist.isEmpty() ? first.playlist
                : String.valueOf(LivePlaylists.active(this));
        r.tries = before != null && LiveRecordings.RECORDING.equals(before.state) ? before.tries + 1 : 1;
        r.file = new File(LiveRecordings.folder(this), it.ch.id + "-" + it.start + "-catchup.ts").getPath();
        r.beat = System.currentTimeMillis();
        hold(true);
        LiveRecordings.put(this, r);
        Thread t = new Thread(() -> {
            try {
                fetch(r);
            } finally {
                synchronized (running) {
                    running.remove(id);
                    waiting.remove(id);
                }
                finishIfIdle();
            }
        }, "fetch " + it.ch.id);
        synchronized (running) {
            running.put(id, t);
            account.put(id, r.playlist);
            waiting.add(id);
        }
        updateNotification();
        t.start();
    }

    /** The show from the catch-up into the file, by the stream's own clock (the provider's doesn't end with the show). */
    private void fetch(LiveRecordings.Rec r) {
        // A stream of its own: what plays and records goes first.
        int max = maxOf(r.playlist);
        while (!Thread.currentThread().isInterrupted()) {
            int n;
            synchronized (running) {
                n = streamsOf(r.playlist);
            }
            boolean inUse = LivePlaylists.loginOf(this, r.playlist) == null;       // what plays is from the one in use
            if (!LiveStreams.over(n + 1, inUse ? LiveStreams.playing(this) : 0, max)) break;
            r.beat = System.currentTimeMillis();
            if (!LiveRecordings.update(this, r)) return;
            try {
                Thread.sleep(20_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (running) {
            waiting.remove(r.id);
        }
        r.waiting = false;
        LiveRecordings.update(this, r);
        Xtream.Channel source = r.ch;
        for (Xtream.Channel v : versions(r)) if (v.archive > 0) {
            source = v;
            break;
        }
        Xtream.Program p = new Xtream.Program();
        p.title = r.title;
        p.start = r.start;
        p.end = r.end;
        long want = r.end - r.start + 60_000L, lastSaved = 0;
        TsClock clock = new TsClock();
        byte[] buffer = new byte[1 << 16];
        int tries = 0;
        boolean full = false, deleted = false, unwritable = false;
        File f = new File(r.file);
        AppLog.w("live: from the catch-up of " + source.name + ": " + r.title);
        try (FileOutputStream out = new FileOutputStream(f, false)) {
            while (clock.ms() < want && tries < 8 && !Thread.currentThread().isInterrupted()) {
                HttpURLConnection conn = null;
                long before = clock.ms();
                try {
                    long base = LiveCatchup.minute(r.start, clock.ms());
                    clock.at(base);
                    final Xtream.Channel src = source;
                    conn = open(Xtream.as(LivePlaylists.loginOf(this, r.playlist), () -> Xtream.catchupUrl(this, src, p, r.start + base, "ts")), 30_000);
                    int code = conn.getResponseCode();
                    if (code != 200) throw new IOException("HTTP " + code);
                    try (InputStream in = conn.getInputStream()) {
                        int n;
                        while (clock.ms() < want && (n = in.read(buffer)) > 0) {
                            try {
                                out.write(buffer, 0, n);
                            } catch (IOException e) {
                                throw new Unwritable(e);
                            }
                            r.size += n;
                            clock.feed(buffer, 0, n);
                            if (Thread.currentThread().isInterrupted()) break;
                            long now = SystemClock.uptimeMillis();
                            if (now - lastSaved > 10_000) {
                                lastSaved = now;
                                r.got = clock.ms();
                                r.beat = System.currentTimeMillis();
                                if (!LiveRecordings.update(this, r)) {
                                    deleted = true;
                                    break;
                                }
                                if (f.getParentFile().getUsableSpace() < MIN_FREE) {
                                    full = true;
                                    break;
                                }
                            }
                        }
                    }
                    if (deleted || full) break;
                    if (clock.ms() >= want - 90_000) break;       // all but the last of it: the provider ends there
                    if (!Thread.currentThread().isInterrupted()) throw new IOException("the stream ended");
                } catch (Unwritable e) {
                    throw e;
                } catch (IOException e) {
                    tries = clock.ms() - before > 60_000 ? 1 : tries + 1;
                    AppLog.w("live: catch-up interrupted at " + clock.ms() / 1000 + " s (" + e.getMessage() + "), again");
                    r.beat = System.currentTimeMillis();
                    if (!LiveRecordings.update(this, r)) {
                        deleted = true;
                        break;
                    }
                    try {
                        Thread.sleep(Math.min(20_000, 4000L * tries));
                    } catch (InterruptedException stop) {
                        Thread.currentThread().interrupt();
                    }
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        } catch (IOException e) {
            AppLog.w("live: couldn't write from the catch-up: " + e.getMessage());
            unwritable = true;
        }
        boolean byHand;
        synchronized (stopping) {
            byHand = stopping.remove(r.id);
        }
        if (deleted || r.size < 1_000_000 && byHand) {
            Thread.interrupted();
            LiveRecordings.delete(this, r.id);
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return;
        }
        r.got = clock.ms();
        boolean whole = r.got >= r.end - r.start - 60_000L && r.got > (r.end - r.start) / 2;
        r.state = r.size > 1_000_000 ? LiveRecordings.DONE : LiveRecordings.FAILED;
        if (!whole && !byHand) {
            r.why = full ? Ui.tr("o armazenamento da TV está cheio", "the TV's storage is full")
                    : unwritable ? Ui.tr("não deu para salvar na TV", "it couldn't be saved on the TV")
                    : r.size == 0 ? Ui.tr("a reprise não abriu", "the catch-up didn't play") : Ui.tr("a reprise parou antes", "the catch-up stopped early");
        }
        if (!whole && r.got > 0) r.end = Math.min(r.end, r.start + r.got);
        LiveRecordings.Rec first = LiveRecordings.find(this, LiveRecordings.idOf(r.ch.id, r.start));
        if (r.state.equals(LiveRecordings.FAILED) && first != null) {      // nothing came: said on the show it was for
            LiveRecordings.delete(this, r.id);
            first.why = r.why;
            first.tries++;
            LiveRecordings.update(this, first);
            AppLog.w("live: nothing from the catch-up: " + r.why);
            return;
        }
        LiveRecordings.update(this, r);
        AppLog.w("live: from the catch-up " + r.state + ", " + r.got / 1000 + " s of " + want / 1000 + ", " + r.size / 1_000_000 + " MB");
        if (whole) LiveRecordings.dismiss(this, LiveRecordings.idOf(r.ch.id, r.start));       // the one it makes up for
    }

    /** Too many streams with this one: what's playing is asked, and has a moment to let its own go. */
    private void askForStream(LiveSchedule.Item it, String list) {
        int how = LiveOptions.streamLimit(this), n;
        synchronized (running) {
            n = streamsOf(list);
        }
        if (how == LiveOptions.LIMIT_OFF || LivePlaylists.loginOf(this, list) != null || !LiveStreams.over(n, 1, maxOf(list))) return;
        if (!LiveStreams.wanted(this, it) || how != LiveOptions.LIMIT_ASKS) return;
        AppLog.w("live: recording " + it.ch.name + " waits for a stream");
        try {
            Thread.sleep(LiveStreams.ASK_MS);
        } catch (InterruptedException e) {      // Skip it
            Thread.currentThread().interrupt();
        }
    }

    /** The stream into the file until the end (or stopped, or no room); then done or failed, and why. */
    private void save(LiveRecordings.Rec r) {
        long until = r.end + LiveOptions.recordLate(this) * 60_000L, lastSaved = 0;     // Keep recording
        // Tries in a row that got nowhere (or not for long): one that then held starts the count again.
        int tries = 0;
        boolean full = false, unwritable = false, deleted = false;
        File f = new File(r.file);
        final long had = r.size;
        byte[] buffer = new byte[1 << 16];
        Xtream.Channel source = r.ch;
        java.util.List<Xtream.Channel> others = null;
        int moves = 0;
        java.util.Set<Integer> tried = new java.util.HashSet<>();        // sources left since one last came in well
        long judgeFrom = 0;
        // Its own one when that's been failing (or another is what it came from): the best of the channel's versions.
        if (r.from != 0 ? r.from != r.ch.id : LiveOptions.avoidBadSources(this) && LiveReliability.badNow(this, r.ch.id)) {
            others = versions(r);
            source = others.get(0);
            if (source.id != r.ch.id) AppLog.w("live: recording " + r.ch.name + " from another of its sources");
        }
        try (FileOutputStream out = new FileOutputStream(f, true)) {
            while (System.currentTimeMillis() < until && !Thread.currentThread().isInterrupted() && tries < 30) {
                HttpURLConnection conn = null;
                long began = SystemClock.uptimeMillis(), got = 0;
                boolean slow = false;
                TsClock clock = new TsClock();
                Trickle trickle = new Trickle();
                try {
                    final Xtream.Channel src = source;
                    conn = open(Xtream.as(LivePlaylists.loginOf(this, r.playlist), () -> Xtream.resolve(this, Xtream.streamUrl(this, src, "ts"))), 20_000);
                    int code = conn.getResponseCode();
                    if (code != 200) throw new IOException("HTTP " + code);
                    try (InputStream in = conn.getInputStream()) {
                        int n;
                        while ((n = in.read(buffer)) > 0) {
                            try {
                                out.write(buffer, 0, n);
                            } catch (IOException e) {
                                throw new Unwritable(e);
                            }
                            r.size += n;
                            got += n;
                            clock.feed(buffer, 0, n);
                            int how = trickle.add(SystemClock.uptimeMillis(), clock.ms(), n);
                            if (how == Trickle.FINE) {
                                moves = 0;
                                tried.clear();
                                r.from = source.id;
                            } else if (how == Trickle.SLOW && SystemClock.uptimeMillis() >= judgeFrom) {
                                // Far slower than it plays: another source (the same again when there's one), but not
                                // round and round when they're all like that.
                                if (others == null) others = versions(r);
                                if (moves++ < Math.max(2, others.size())) {
                                    slow = true;
                                    r.gapFrom = System.currentTimeMillis() - Trickle.WINDOW_MS;     // most of it is missing
                                    throw new IOException("too slow");
                                }
                                moves = 0;
                                judgeFrom = SystemClock.uptimeMillis() + 5 * 60_000L;
                            }
                            long wall = System.currentTimeMillis();
                            LiveRecordings.gap(r, r.gapFrom, wall);     // since it last came in
                            r.gapFrom = wall;
                            if (System.currentTimeMillis() >= until || Thread.currentThread().isInterrupted()) break;
                            long now = SystemClock.uptimeMillis();
                            if (now - lastSaved > 30_000) {      // the list shows how far it is
                                lastSaved = now;
                                r.beat = System.currentTimeMillis();
                                if (!LiveRecordings.update(this, r)) {
                                    deleted = true;
                                    Thread.currentThread().interrupt();
                                } else if (f.getParentFile().getUsableSpace() < MIN_FREE) {
                                    AppLog.w("live: recording stopped: the TV's storage is almost full");
                                    full = true;
                                    Thread.currentThread().interrupt();
                                }
                            }
                        }
                    }
                    if (System.currentTimeMillis() < until && !Thread.currentThread().isInterrupted()) throw new IOException("the stream ended");
                } catch (Unwritable e) {
                    throw e;
                } catch (IOException e) {
                    tries = got > 0 && SystemClock.uptimeMillis() - began > 30_000 ? 1 : tries + 1;
                    AppLog.w("live: recording interrupted (" + e.getMessage() + "), again");
                    // A merged channel's other versions, when this one doesn't open (or keeps dropping).
                    if (slow || tries >= (r.size == 0 ? 2 : 4)) {
                        if (others == null) others = versions(r);
                        if (others.size() > 1) {
                            java.util.List<Integer> ids = new java.util.ArrayList<>();
                            for (Xtream.Channel o : others) ids.add(o.id);
                            tried.add(source.id);
                            source = others.get(nextSource(ids, source.id, tried));
                        }
                    }
                    r.beat = System.currentTimeMillis();
                    if (!LiveRecordings.update(this, r)) {
                        deleted = true;
                        break;
                    }
                    lastSaved = SystemClock.uptimeMillis();
                    try {
                        Thread.sleep(Math.min(20_000, 3000L * tries));
                    } catch (InterruptedException stop) {       // Stop recording: at once
                        Thread.currentThread().interrupt();
                    }
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        } catch (IOException e) {
            AppLog.w("live: couldn't write the recording: " + e.getMessage());
            if (f.getParentFile() != null && f.getParentFile().getUsableSpace() < MIN_FREE) full = true;
            else unwritable = true;
        }
        if (deleted) {      // taken out while it recorded: nothing to keep
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            synchronized (stopping) {
                stopping.remove(r.id);
            }
            return;
        }
        long now = System.currentTimeMillis();
        boolean byHand;
        synchronized (stopping) {
            byHand = stopping.remove(r.id);
        }
        if (byHand && r.size == had && (had == 0 || r.hadUntil > 0)) {        // stopped before anything (new) came
            Thread.interrupted();
            if (had == 0) {
                LiveRecordings.delete(this, r.id);
            } else {        // what there was of it from before, as it was
                r.state = LiveRecordings.DONE;
                r.end = r.hadUntil;
                LiveRecordings.update(this, r);
            }
            return;
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
        LiveRecordings.update(this, r);
        LiveRecordings.fetchMissed(this);
        if (r.state.equals(LiveRecordings.DONE)) com.glasslauncher.tv.data.HaEvents.recording(this, false, r.title, LiveCountry.named(this, r.ch));
        AppLog.w("live: recording " + r.state + ", " + r.size / 1_000_000 + " MB" + (r.why.isEmpty() ? "" : " (" + r.why + ")"));
        if (r.state.equals(LiveRecordings.DONE) && LiveOptions.adBreaks(this)) {     // its ad breaks, while it's fresh
            final String file = r.file;
            Thread t = new Thread(() -> {
                try {
                    LiveBreaks.analyze(file);
                } catch (Exception e) {
                    AppLog.w("breaks: " + e.getMessage());
                }
            }, "live-breaks");
            t.setPriority(Thread.MIN_PRIORITY);
            t.start();
        }
        if (!r.why.isEmpty()) {
            LiveRecordings.tell(this, r, r.state.equals(LiveRecordings.FAILED) ? Ui.tr("Gravação falhou", "Recording failed")
                    : Ui.tr("Gravação parou antes", "Recording stopped early"));
        }
    }

    /** The TV's storage (or the drive) didn't take it: not the channel's doing. */
    private static final class Unwritable extends IOException {
        Unwritable(IOException e) {
            super(e.getMessage());
        }
    }

    /** Which of `ids` (the best first) after `now`: the best not tried yet; all tried, the one after it. Pure. */
    static int nextSource(java.util.List<Integer> ids, int now, java.util.Set<Integer> tried) {
        for (int i = 0; i < ids.size(); i++) if (!tried.contains(ids.get(i))) return i;
        tried.clear();
        tried.add(now);
        return (ids.indexOf(now) + 1) % ids.size();
    }

    /** Versions `ids` as a recording tries them: the one it came from well, its own, the rest; the failing ones last. Pure. */
    static java.util.List<Integer> order(java.util.List<Integer> ids, int own, int from, java.util.Set<Integer> bad) {
        java.util.List<Integer> out = new java.util.ArrayList<>(ids);
        java.util.Collections.sort(out, (a, b) -> Integer.compare(rank(a, own, from, bad), rank(b, own, from, bad)));
        return out;
    }

    private static int rank(int id, int own, int from, java.util.Set<Integer> bad) {
        return id == from && from != 0 ? 0 : (bad.contains(id) ? 10 : 0) + (id == own ? 1 : 2);
    }

    /** The channel's versions as Live TV merges them (its own first), else just itself. */
    private java.util.List<Xtream.Channel> versions(LiveRecordings.Rec rec) {
        Xtream.Channel ch = rec.ch;
        java.util.List<Xtream.Channel> out = new java.util.ArrayList<>();
        try {
            // Its own playlist's list, though another may be in use.
            Xtream.Lineup l = Xtream.as(LivePlaylists.loginOf(this, rec.playlist), () -> Xtream.saved(this));
            Xtream.Channel merged = l == null ? null : l.find(ch.id);
            java.util.List<Xtream.Channel> all = merged == null ? null : LiveMerge.inOrder(merged);
            if (all != null) {
                java.util.List<Integer> ids = new java.util.ArrayList<>();
                java.util.Set<Integer> bad = new java.util.HashSet<>();
                for (Xtream.Channel s : all) {
                    ids.add(s.id);
                    if (LiveOptions.avoidBadSources(this) && LiveReliability.badNow(this, s.id)) bad.add(s.id);
                }
                for (int id : order(ids, ch.id, rec.from, bad)) {
                    for (Xtream.Channel s : all) {
                        if (s.id != id) continue;
                        out.add(s);
                        break;
                    }
                }
            } else if (merged != null) {
                out.add(merged);
            }
        } catch (IOException | RuntimeException e) {
            AppLog.w("live: recording: " + e.getMessage());
        }
        if (out.isEmpty()) out.add(ch);
        return out;
    }

    private void finishIfIdle() {
        synchronized (running) {
            if (!running.isEmpty()) {
                updateNotification();
                return;
            }
        }
        hold(false);
        stopForeground(true);
        stopSelf(last);
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
