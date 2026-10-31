package com.glasslauncher.tv.live;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.audio.AudioCapabilities;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Live TV (IPTV): your provider's channels (Xtream Codes, Xtream) played with Media3's ExoPlayer, the UI drawn on top
 * (LiveView). Its own process (:live), so video never weighs on the home screen. The player follows Media3's advice:
 * created when the screen shows and released when it goes (onStart / onStop), audio focus, the network kept awake
 * while playing, a decoder fallback, and a live stream that fell behind its window tunes back in.
 */
public class Live extends Activity {
    /** Some providers turn away requests without a client name. */
    /** How long a server starting a channel gets before the next try. */
    private static final long RETRY_MS = 2500;
    static final String USER_AGENT = "Glassio/1.0 (Android TV)";
    /**
     * From the home screen: the channel to play (its Live TV row), the guide to open (the Guide button), or the first
     * digit of a channel number (the remote's number keys).
     */
    static final String EXTRA_CHANNEL = "com.glasslauncher.tv.live.extra.CHANNEL", EXTRA_GUIDE = "com.glasslauncher.tv.live.extra.GUIDE",
            EXTRA_DIGIT = "com.glasslauncher.tv.live.extra.DIGIT", EXTRA_SEARCH = "com.glasslauncher.tv.live.extra.SEARCH";
    /** From Continue Watching: a past program to go on with (the channel, its start, end and title, where it was). */
    static final String EXTRA_RESUME_CHANNEL = "com.glasslauncher.tv.live.extra.RESUME_CHANNEL", EXTRA_RESUME_START = "com.glasslauncher.tv.live.extra.RESUME_START",
            EXTRA_RESUME_END = "com.glasslauncher.tv.live.extra.RESUME_END", EXTRA_RESUME_TITLE = "com.glasslauncher.tv.live.extra.RESUME_TITLE",
            EXTRA_RESUME_AT = "com.glasslauncher.tv.live.extra.RESUME_AT";
    /** From the home screen's Continue Watching: a finished recording to play (its id). */
    static final String EXTRA_RECORDING = "com.glasslauncher.tv.live.extra.RECORDING";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private FrameLayout root;
    private SurfaceView surface;
    private LiveView view;
    private ExoPlayer player;
    private Xtream.Lineup lineup;
    private Xtream.Channel playing, previous;
    /** The past program playing from the catch-up (null: the channel live), and where it was when the screen went. */
    private Xtream.Program catchup;
    /** A recording playing (its file; catchup holds its show), or null. */
    private String recordingFile;
    /** Live TV paused (LiveTimeshift): the channel going into the pieces; null when it plays live. */
    private LiveTimeshift shift;
    /** Where the player started in the pieces (the stream's clock), and whether it plays from them yet (else, paused). */
    private long shiftFrom;
    private boolean shiftPlaying;
    private androidx.media3.extractor.DefaultExtractorsFactory extractors;
    /**
     * A past program's address starts here, ms into it (the provider takes a start to the minute): the player's
     * position is from there. And how far past that start to go once it plays, when the stream can seek.
     */
    private long catchupBase, catchupInside;
    /** ◀ ▶ pressed in a row: where they go (position()'s ms), done once the presses stop; -1: none. */
    private long skipTo = -1, skipLast;
    private int skipRun;
    /** What the skips were pressed on (the past program or the pause): anything else since, they're dropped. */
    private Object skipOn;
    private long resumeAt;
    private String format = "ts";   // this channel's stream format (HLS after MPEG-TS failed, for this channel only)
    private boolean triedOther, waited, preview, askGuide;
    /** The parental PIN was typed since Live TV came on screen: locked categories play. */
    private boolean unlocked;
    private int askChannel = -1, askDigit = -1, askResume = -1;
    /** Words searched on the home screen: Live TV's search opens with them. */
    private String askSearch;
    private Xtream.Program resumeProgram;
    private long resumeFrom;
    private float aspect = 16 / 9f;   // the video's shape (from the stream once it plays)
    /** How the picture fills the screen: FIT (all of it, bars if need be), ZOOM (the screen, cropped), STRETCH. */
    static final int FIT = 0, ZOOM = 1, STRETCH = 2;
    private volatile boolean loading;
    /** What plays of `playing`: itself, or the source of a merged channel picked for the connection (or by the person). */
    private Xtream.Channel source;
    /** Glassio's player let go for another one (Open in another player). */
    private boolean handedOff;
    /** The sources that failed on this channel (the next one plays); the one the person picked (-1: the best one). */
    private final java.util.Set<Integer> failed = new java.util.HashSet<>();
    private int chosenSource = -1;
    /** It played, then waited for data: two such stalls within a minute step down to a lighter source. */
    private boolean wasReady;
    private long stallAt;
    /**
     * The backup when a stream stops without an error (frozen picture, endless buffering, the provider closing it):
     * every few seconds `streamWatch` looks; it moves to the next source of a merged channel, else connects again
     * (a few times, waiting longer each time). Played well for half a minute, the count starts over.
     */
    private static final long STUCK_MS = 10_000, STUCK_FIRST_MS = 15_000, FROZEN_MS = 10_000, WATCH_MS = 2000, GOOD_MS = 30_000;
    private static final int MAX_RECONNECTS = 4;
    private int reconnects;
    private long themeTick;
    private long bufferingSince, readySince, lastPosition = -1, positionSince;

    private final Player.Listener listener = new Player.Listener() {
        @Override public void onPlaybackStateChanged(int state) {
            long up = android.os.SystemClock.uptimeMillis();
            bufferingSince = state == Player.STATE_BUFFERING ? up : 0;
            readySince = state == Player.STATE_READY ? up : 0;
            positionSince = up;
            lastPosition = -1;
            if (state == Player.STATE_ENDED && catchup == null && shift == null && recordingFile == null && playing != null) {
                recover(Ui.tr("O canal parou", "The channel stopped"));      // live has no end: the provider closed it
                return;
            }
            view.setBuffering(state == Player.STATE_BUFFERING);
            if (state == Player.STATE_BUFFERING && wasReady) stalled();
            wasReady = state == Player.STATE_READY || wasReady && state == Player.STATE_BUFFERING;
            if (state == Player.STATE_READY) view.setStatus("");
            if (state == Player.STATE_READY && catchup != null && catchupInside > 0 && player != null) {
                // The rest of the way into the minute it started at; a stream that can't seek plays from there.
                long inside = catchupInside;
                catchupInside = 0;
                if (player.isCurrentMediaItemSeekable() && inside > 1000) player.seekTo(inside);
            }
            if (state == Player.STATE_ENDED && catchup != null && !endSeen) {       // the program is over: what's next, or live
                endSeen = true;
                upNext();
            }
            // (leftCatchup, on the way, takes it out of Continue Watching: it got to its end)
        }

        @Override public void onIsPlayingChanged(boolean isPlaying) {
            if (catchup != null) view.invalidate();    // the banner's pause mark
        }

        @Override public void onPlayerError(PlaybackException e) {
            // Never the address: it carries the login. The server's answer, when it turned the stream down.
            int status = httpStatus(e);
            AppLog.w("live: " + e.getErrorCodeName() + (status > 0 ? " (HTTP " + status + ")" : "") + " on " + format
                    + (playing == null ? "" : ", " + playing.name));
            if (shift != null && playing != null) {     // the paused stream couldn't play: live again
                view.flash(Ui.tr("Não deu para continuar daí: ao vivo", "Couldn't go on from there: live"));
                play(playing);
                return;
            }
            if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && player != null) {
                player.seekToDefaultPosition();
                player.prepare();
                return;
            }
            // A server still starting the channel (on-demand channels answer 502 to 504 for a few seconds): once more,
            // after a moment.
            if (playing != null && status >= 502 && status <= 504 && !waited) {
                waited = true;
                final Xtream.Channel ch = playing;
                final Xtream.Program p = catchup;
                view.setStatus(Ui.tr("Iniciando o canal…", "Starting the channel…"));
                main.postDelayed(() -> { if (playing == ch && catchup == p) retry(); }, RETRY_MS);
                return;
            }
            // Some servers serve a channel only in the other format (HEVC ones often only as MPEG-TS, others only as
            // HLS): one more try that way (and its wait).
            if (playing != null && !triedOther) {
                triedOther = true;
                waited = false;
                format = format.equals("ts") ? "m3u8" : "ts";
                retry();
                return;
            }
            if (playing != null && recordingFile == null && nextSource()) return;     // a merged channel: its next source
            if (playing != null && recordingFile == null && (status < 400 || status >= 500) && reconnect()) return;
            if (playing != null && recordingFile == null) health(LiveHealth.FAILED);
            view.setBuffering(false);
            view.setStatus(catchup != null
                    ? Ui.tr("Não deu para tocar este programa", "Couldn't play this program") + " (" + (status > 0 ? "HTTP " + status : e.getErrorCodeName()) + ")"
                    : Ui.tr("Não deu para tocar este canal", "Couldn't play this channel") + " (" + (status > 0 ? "HTTP " + status : e.getErrorCodeName()) + ")");
        }

        @Override public void onRenderedFirstFrame() {
            view.tuned();   // the picture is there: it fades in from black
            matchFrameRate();
        }

        @Override public void onVideoSizeChanged(VideoSize size) {
            fit(size);
            // What this source really is (a merged channel's order and the Source list follow).
            androidx.media3.common.Format f = player == null ? null : player.getVideoFormat();
            if (source != null && recordingFile == null && size.height > 0) {
                LiveMerge.played(Live.this, source.id, size.height,
                        f != null && MimeTypes.VIDEO_H265.equals(f.sampleMimeType));
            }
        }

        @Override public void onTracksChanged(androidx.media3.common.Tracks tracks) {
            view.tracksChanged();     // audio and subtitle choices, the stream's tags
        }

        @Override public void onCues(androidx.media3.common.text.CueGroup cues) {
            StringBuilder sb = new StringBuilder();
            for (androidx.media3.common.text.Cue cue : cues.cues) {
                if (cue.text == null) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(cue.text);
            }
            view.setSubtitle(sb.toString());
        }
    };

    /**
     * Watches the streams coming in: their fastest second in the first seconds (a live stream's first burst, a past
     * program or an HLS piece downloading as fast as it can) tells the connection's speed (LiveMerge). Loading thread.
     */
    private final TransferListener speedWatch = new TransferListener() {
        private long openedAt, windowAt, windowBytes;

        @Override public void onTransferInitializing(DataSource s, DataSpec spec, boolean network) {}

        @Override public synchronized void onTransferStart(DataSource s, DataSpec spec, boolean network) {
            if (!network) return;
            openedAt = windowAt = android.os.SystemClock.elapsedRealtime();
            windowBytes = 0;
        }

        @Override public synchronized void onBytesTransferred(DataSource s, DataSpec spec, boolean network, int bytes) {
            if (!network) return;
            windowBytes += bytes;
            long now = android.os.SystemClock.elapsedRealtime(), ms = now - windowAt;
            if (ms < 1000) return;
            if (now - openedAt < 15_000 && windowBytes >= 128 * 1024) LiveMerge.measured(Live.this, (int) (windowBytes * 8 / ms));
            windowAt = now;
            windowBytes = 0;
        }

        @Override public void onTransferEnd(DataSource s, DataSpec spec, boolean network) {}
    };

    /** The HTTP status the server answered with, when that's what failed (else 0). */
    private static int httpStatus(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                return ((androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) t).responseCode;
            }
        }
        return 0;
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!freezesWatched) {
            freezesWatched = true;
            CrashLog.watchFreezes(this);
        }
        LiveTheme.load(this);
        Ui.init(this);
        LiveTheme.applyFonts(this);     // the style's lettering and layout (Live TV's process only)
        LiveView.compact = LiveOptions.guideCompact(this);
        LiveView.applyLayout();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        surface = new SurfaceView(this);   // a SurfaceView: the video goes straight to the display (the efficient way)
        root.addView(surface, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        view = new LiveView(this);
        root.addView(view, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        TestHook.register(this, testHook);
        readIntent(getIntent());
        if (Xtream.signedIn(this)) load(); else view.showSignIn();
    }

    @Override protected void onStart() {
        super.onStart();
        handedOff = false;
        LiveScores.get(this).watch(scores);
        registerReceiver(recordingProblem, new android.content.IntentFilter(LiveRecordings.PROBLEM), null, null,
                android.os.Build.VERSION.SDK_INT >= 33 ? Context.RECEIVER_NOT_EXPORTED : 0);
        main.removeCallbacks(ratingWatch);
        main.postDelayed(ratingWatch, 15_000);
        main.removeCallbacks(streamWatch);
        main.postDelayed(streamWatch, WATCH_MS);
        openedAt = android.os.SystemClock.uptimeMillis();
        createPlayer();
        if (playing == null) return;
        if (locked(playing)) view.askPin(() -> { unlocked = true; resume(); }, view::openGuide);
        else resume();
    }

    /** A recording that failed or was cut short (the recorder's process tells): a card over what's on. */
    private final android.content.BroadcastReceiver recordingProblem = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String head = i.getStringExtra(LiveRecordings.EXTRA_HEAD), line = i.getStringExtra(LiveRecordings.EXTRA_LINE);
            if (head != null && line != null) view.card(head, line);
        }
    };

    /** Live scores: the info bar redrawn as they change; a goal, a card (Goal alerts). */
    private final LiveScores.Listener scores = new LiveScores.Listener() {
        @Override public void changed() {
            view.scoresChanged();
        }

        @Override public void goal(LiveScores.Score s, boolean forHome) {
            view.goal(forHome ? s.home : s.away, s.line());
        }
    };

    /** Back from the choice of players without picking one (Glassio stayed underneath): the channel again. */
    @Override protected void onResume() {
        super.onResume();
        if (!handedOff) return;
        handedOff = false;
        createPlayer();
        if (playing != null) resume();
    }

    /** Back on screen: the program where it was, or the channel live. */
    private void resume() {
        if (catchup != null) startCatchup(resumeAt); else play(playing);
    }

    /** The freeze watch runs once per process (CrashLog). */
    private static boolean freezesWatched;

    /** The channel on screen now, for a reminder from another process (ReminderActivity): none while away. */
    private void sayWatching(Xtream.Channel ch) {
        File f = new File(getFilesDir(), WATCHING_FILE);
        if (ch == null) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return;
        }
        StringBuilder s = new StringBuilder().append(ch.id);
        if (ch.sources != null) for (Xtream.Channel src : ch.sources) s.append(' ').append(src.id);
        try {
            Xtream.write(f, s.toString());
        } catch (IOException ignored) {
        }
    }

    static final String WATCHING_FILE = "live-watching.txt";

    /** Whether Live TV is on screen on the channel `id` (or one of its sources). */
    static boolean watching(Context c, int id) {
        File f = new File(c.getFilesDir(), WATCHING_FILE);
        if (!f.exists()) return false;
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            for (String part : Xtream.read(in).trim().split(" ")) if (part.equals(String.valueOf(id))) return true;
        } catch (IOException ignored) {
        }
        return false;
    }

    @Override protected void onStop() {
        LiveScores.get(this).unwatch(scores);
        try {
            unregisterReceiver(recordingProblem);
        } catch (IllegalArgumentException ignored) {
        }
        sayWatching(null);
        main.removeCallbacks(ratingWatch);
        main.removeCallbacks(streamWatch);
        if (shift != null && playing != null) {      // the pause ends with the screen: live again next time
            endShift();
        }
        if (player != null && catchup != null) resumeAt = position();
        leftCatchup();
        releasePlayer();
        setDisplayMode(0);  // the TV's own refresh rate again
        unlocked = false;   // the parental PIN is asked again next time
        view.cancelSleep();     // the sleep timer is Live TV's: it doesn't follow into other apps
        super.onStop();
    }

    /** A style shown for a check from the computer (TestHook): in memory only. */
    private final android.content.BroadcastReceiver testHook = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String follow = i.getStringExtra("follow"), unfollow = i.getStringExtra("unfollow");
            if (i.hasExtra("recproblem")) {     // a missed recording of the channel on, as told (off: it goes)
                String id = "test-recording";
                if ("off".equals(i.getStringExtra("recproblem"))) {
                    LiveRecordings.delete(Live.this, id);
                    return;
                }
                if (playing == null) return;
                LiveRecordings.Rec r = new LiveRecordings.Rec();
                r.id = id;
                r.ch = playing;
                r.title = "Test";
                r.start = System.currentTimeMillis() - 3600_000L;
                r.end = System.currentTimeMillis() - 600_000L;
                r.file = "";
                r.state = LiveRecordings.FAILED;
                r.why = Ui.tr("a TV estava desligada", "the TV was off");
                LiveRecordings.put(Live.this, r);
                LiveRecordings.tell(Live.this, r, Ui.tr("Gravação perdida", "Recording missed"));
                return;
            }
            if (i.hasExtra("scores")) {     // the live scores from a stand-in (off: ESPN's)
                LiveScores.get(Live.this).test(i.getStringExtra("scores"));
                return;
            }
            if (follow != null) {       // as Follow… does (and unfollow, as taking it out in Settings)
                LiveFollow.add(Live.this, follow);
                return;
            }
            if (unfollow != null) {
                LiveFollow.remove(Live.this, unfollow);
                return;
            }
            int remind = i.getIntExtra("remind", -1);
            if (remind >= 0 && lineup != null) {        // a reminder card now, for channel number `remind`'s next show
                for (Xtream.Channel ch : lineup.all()) {
                    if (ch.num != remind) continue;
                    LiveSchedule.Item it = new LiveSchedule.Item();
                    it.kind = LiveSchedule.REMIND;
                    it.ch = ch;
                    it.start = System.currentTimeMillis() + i.getIntExtra("in", 60) * 1000L;
                    it.end = it.start + 3600_000L;
                    it.title = Ui.tr("Teste", "Test");
                    String match = i.getStringExtra("match");      // a match alert, as following `match` makes
                    if (match != null) {
                        it.series = LiveFollow.SERIES + Xtream.fold(match);
                        Xtream.Program on = view.playingShow();
                        if (on != null) it.title = on.title;
                    }
                    startActivity(ReminderActivity.intent(Live.this, it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    break;
                }
                return;
            }
            int logoOf = i.getIntExtra("logoinfo", -1);
            if (logoOf >= 0 && lineup != null) {     // channel number `logoOf`'s logos, to the log
                for (Xtream.Channel ch : lineup.all()) {
                    if (ch.num == logoOf) AppLog.w("live: logos of " + ch.num + " " + ch.name + " (id " + ch.id + "): shown "
                            + ch.logo + ", provider's " + ch.providerLogo + ", epg " + ch.epg + ", for home "
                            + LiveLogoChoice.shown(Live.this, ch.id));
                }
                return;
            }
            String said = i.getStringExtra("voice");
            if (said != null) {     // as said into the remote in Live TV: tunes, or "add … to favourites", else a search
                if (!view.voiceWatch(said)) view.flash("voice: no channel for \"" + said + "\"");
                return;
            }
            String s = i.getStringExtra("style");
            if (s == null) return;
            LiveTheme.test(TestHook.style(s));
            LiveTheme.applyFonts(Live.this);
            view.restyle();
        }
    };

    @Override protected void onDestroy() {
        TestHook.unregister(this, testHook);
        io.shutdownNow();
        view.release();
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (view.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    private void createPlayer() {
        if (player != null) return;
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(USER_AGENT)
                .setAllowCrossProtocolRedirects(true)   // providers often send http on to https (or back)
                .setConnectTimeoutMs(10_000)
                .setReadTimeoutMs(20_000)
                .setTransferListener(speedWatch);
        // Audio passthrough off: Dolby and DTS are decoded here (as if the TV took only PCM) rather than sent on.
        final boolean passthrough = LiveOptions.passthrough(this);
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this) {
            @Override protected AudioSink buildAudioSink(Context c, boolean floatOutput, boolean playbackParams) {
                if (passthrough) return super.buildAudioSink(c, floatOutput, playbackParams);
                return new DefaultAudioSink.Builder(c).setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                        .setEnableFloatOutput(floatOutput).setEnableAudioTrackPlaybackParams(playbackParams).build();
            }
        }.setEnableDecoderFallback(true);
        DefaultTrackSelector tracks = new DefaultTrackSelector(this);
        tracks.setParameters(subtitlePrefs(tracks.buildUponParameters().setTunnelingEnabled(LiveOptions.tunneling(this)),
                LiveOptions.subtitles(this)).setPreferredAudioLanguage(audioPref()));
        // A TS stream's closed captions (CEA-608, in the video, as US channels send them) show as a subtitle track
        // even when the stream doesn't announce them, as HLS does already.
        extractors = new androidx.media3.extractor.DefaultExtractorsFactory()
                .setTsSubtitleFormats(java.util.Collections.singletonList(new androidx.media3.common.Format.Builder()
                        .setSampleMimeType(MimeTypes.APPLICATION_CEA608).setAccessibilityChannel(1)
                        .setLabel(Ui.tr("Legendas (CC)", "Closed captions")).build()));
        player = new ExoPlayer.Builder(this, renderers)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(Xtream.resolving(this, new DefaultDataSource.Factory(this, http)), extractors))
                .setTrackSelector(tracks)
                .setLoadControl(LiveOptions.loadControl(LiveOptions.buffer(this)))
                .build();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        player.setWakeMode(C.WAKE_MODE_NETWORK);
        player.setVideoSurfaceView(surface);
        player.addListener(listener);
    }

    private void releasePlayer() {
        if (player == null) return;
        player.removeListener(listener);
        player.release();
        player = null;
    }

    // MARK: - Signing in and the channel list

    /** From the sign-in screen: checks the login with the provider, then loads the channels. */
    void signIn(String kind, String server, String user, String pass) {
        view.setStatus(Ui.tr("Entrando…", "Signing in…"));
        Context app = getApplicationContext();
        io.execute(() -> {
            try {
                if (kind.equals(Xtream.M3U)) Xtream.signInM3u(app, server, user);
                else if (kind.equals(Xtream.MAG)) Xtream.signInMag(app, server, user);
                else Xtream.signIn(app, server, user, pass);
                LivePlaylists.signedIn(app);
                main.post(() -> {
                    view.setStatus("");
                    load();
                });
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.toString() : e.getMessage();
                main.post(() -> view.setStatus(message));
            }
        });
    }

    void signOut() {
        releasePlayer();
        LiveSchedule.disarmAll(this);
        Xtream.signOut(this);
        Xtream.clearGuides(this);
        XmltvStore.clear(this);
        LiveOptions.clearAccount(this);
        for (String name : new String[] {"live-sources.json", "live-preferred.json", "live-speed.txt", "live-schedule.json", "live-series.json", "live-watch-next.json",
                "live-guide-shift.txt"}) {
            //noinspection ResultOfMethodCallIgnored
            new java.io.File(getFilesDir(), name).delete();
        }
        prefs().edit().clear().commit();
        // Another playlist left: that one in use; none: the sign-in screen.
        String next = LivePlaylists.removeActive(this);
        playlistChanged(next != null);
    }

    /** `id` in use instead (its channels, favorites, guide). */
    void switchPlaylist(String id) {
        if (XmltvStore.running()) {
            view.flash(Ui.tr("Espere o guia completo terminar de atualizar", "Wait for the full guide to finish updating"));
            return;
        }
        releasePlayer();
        LivePlaylists.switchTo(this, id);
        playlistChanged(true);
        view.flash(Ui.tr("Usando ", "Using ") + Xtream.user(this));
    }

    /** The sign-in screen for another account (Back there goes back to this one). */
    void addPlaylist() {
        if (XmltvStore.running()) {
            view.flash(Ui.tr("Espere o guia completo terminar de atualizar", "Wait for the full guide to finish updating"));
            return;
        }
        releasePlayer();
        LivePlaylists.startAdding(this);
        playlistChanged(false);
    }

    /** Back on the sign-in screen while adding one: the one before. False when there's none. */
    boolean cancelAdding() {
        if (!LivePlaylists.cancelAdding(this)) return false;
        playlistChanged(true);
        return true;
    }

    /** Another account's files are in place: everything about the last one forgotten, its channels loaded. */
    private void playlistChanged(boolean load) {
        releasePlayer();
        unlocked = false;
        lineup = null;
        playing = previous = null;
        catchup = null;
        loading = false;
        createPlayer();
        view.guideReplaced();
        if (load && Xtream.signedIn(this)) load(); else view.showSignIn();
    }

    /** The saved channels right away (starting the last channel), then a fresh list when it's old. */
    private void load() {
        if (loading) return;
        loading = true;
        view.setStatus(Ui.tr("Carregando os canais…", "Loading channels…"));
        Context app = getApplicationContext();
        io.execute(() -> {
            Xtream.Lineup saved = Xtream.saved(app);
            if (saved != null) main.post(() -> show(saved));
            if (saved != null && System.currentTimeMillis() - saved.at < Xtream.LIST_EVERY && saved.version >= Xtream.LIST_VERSION) {
                loading = false;
                return;
            }
            try {
                Xtream.Lineup fresh = Xtream.fetch(app);
                main.post(() -> show(fresh));
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.toString() : e.getMessage();
                AppLog.w("live: list: " + message);
                if (saved == null) main.post(() -> view.setStatus(message));
            } finally {
                loading = false;
            }
        });
    }

    private void show(Xtream.Lineup l) {
        if (!followSynced) {        // what's followed, once as Live TV opens (then the alarm's every few hours)
            followSynced = true;
            final Context app = getApplicationContext();
            io.execute(() -> LiveFollow.sync(app));
            io.execute(() -> {      // the subscription: asked again, and a word when it ends soon (once a day)
                Xtream.Account a = Xtream.account(app);
                if (a != null) main.post(() -> view.subscriptionEnding(a));
            });
        }
        LiveChannelEdits.apply(this, l);        // names and numbers changed by hand
        applyLogos(l);      // the logos from the source chosen (the provider's, the guide's, the public database's)
        if (LiveProfiles.kids(this)) l = LiveProfiles.kidsOnly(l);     // a Kids profile: the children's channels only
        lineup = l;
        SharedPreferences p = prefs();
        int last = p.getInt("channel", -1);
        // The small window on the home screen changed channel since: that one (and from now on).
        int fromMini = LiveHome.takeNextChannel(this);
        if (fromMini >= 0 && l.find(fromMini) != null) {
            last = fromMini;
            p.edit().putInt("channel", fromMini).apply();
        }
        String category = p.getString("category", "");
        view.setLineup(l, category);
        updateGuide(false);
        if (l.all().isEmpty()) {
            view.setStatus(Ui.tr("O provedor não tem canais ao vivo nesta conta.", "The provider has no live channels on this account."));
            return;
        }
        view.setStatus("");
        int recorded = 0;
        StringBuilder some = new StringBuilder();
        for (Xtream.Channel ch : l.all()) {
            if (ch.archive <= 0) continue;
            if (recorded++ < 5) some.append(some.length() == 0 ? "" : ", ").append(ch.num).append(' ').append(ch.name);
        }
        AppLog.w("live: " + l.all().size() + " channels, " + recorded + " with catch-up" + (recorded > 0 ? " (" + some + ")" : ""));
        if (playing != null) {      // a refresh while watching: keep going (as the channel in the new list)
            Xtream.Channel now = l.find(playing.id);
            if (now != null) {
                playing = now;
                asked();
                return;
            }
            // Not in this list any more (a Kids profile on): it stops, and the start goes as on opening.
            endShift();
            if (player != null) player.stop();
            playing = null;
            catchup = null;
        }
        if (askChannel >= 0 || askResume >= 0) {
            asked();
            return;
        }
        Xtream.Channel start = find(last);
        if (start == null) {
            view.openGuide();   // first time: pick a channel
        } else {
            boolean plain = !askGuide && askDigit < 0 && askSearch == null;
            play(start);
            asked();
            if (plain && !profileAsked && LiveOptions.askProfile(this) && LiveProfiles.all(this).size() > 1) view.openProfiles();
            profileAsked = true;
        }
    }

    /**
     * Each channel's logo from the source Live TV › Settings › Channel logos names, the others filling in what it
     * doesn't have. The public database's come a moment later (it may be fetched first), then the screen redraws.
     */
    void applyLogos(Xtream.Lineup l) {
        if (l == null) return;
        final int setting = LiveOptions.logoSource(this);
        final java.util.Map<Integer, Integer> picks = new java.util.HashMap<>(LiveLogoChoice.picks(this));
        java.util.Set<Xtream.Channel> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        final List<Xtream.Channel> all = new java.util.ArrayList<>();
        for (Xtream.Category cg : l.categories) for (Xtream.Channel ch : cg.channels) if (seen.add(ch)) all.add(ch);
        java.util.Map<String, String> guide = LiveOptions.fullGuide(this) ? XmltvStore.logos(this) : new java.util.HashMap<>();
        boolean anyPublic = setting == LiveOptions.LOGOS_PUBLIC;
        for (Xtream.Channel ch : all) {
            if (ch.providerLogo == null) ch.providerLogo = ch.logo == null ? "" : ch.logo;
            int source = sourceOf(ch, setting, picks);
            if (source == LiveOptions.LOGOS_PUBLIC) anyPublic = true;
            String fromGuide = ch.epg == null || ch.epg.isEmpty() ? null : guide.get(ch.epg.toLowerCase(java.util.Locale.ROOT));
            String own = ch.providerLogo.isEmpty() ? null : ch.providerLogo;
            String chosen = source == LiveOptions.LOGOS_GUIDE ? first(fromGuide, own) : first(own, fromGuide);
            ch.logo = chosen == null ? "" : chosen;
        }
        final Context app = getApplicationContext();
        if (!anyPublic) {
            saveShown(app, all);
            return;
        }
        final java.util.Map<String, String> categoryCountry = LiveCountry.categories(l);
        // What the last look-up matched, at once: the public logos are there as Live TV opens, and the database (14 MB)
        // is read again only for a channel not matched yet (new, renamed) or when the database itself is new.
        LivePublicLogos.Matches saved = LivePublicLogos.matches(app);
        boolean complete = saved.indexAt != 0 && saved.indexAt == LivePublicLogos.indexAt(app) && !LivePublicLogos.due(app);
        for (Xtream.Channel ch : all) {
            if (sourceOf(ch, setting, picks) != LiveOptions.LOGOS_PUBLIC) continue;
            String[] m = saved.byId.get(ch.id);
            if (m == null || !m[0].equals(LivePublicLogos.signature(ch.name, LiveCountry.of(ch, categoryCountry), ch.epg))) {
                complete = false;
            } else if (!m[1].isEmpty()) {
                ch.logo = m[1];
            }
        }
        if (complete) {
            AppLog.w("live: public logos from the last look-up");
            saveShown(app, all);
            return;
        }
        // Its own thread: the first time fetches the database, which mustn't hold up the channels and the guide.
        new Thread(() -> {
            LivePublicLogos.Index idx = LivePublicLogos.index(app);
            final java.util.Map<Xtream.Channel, String> found = new java.util.IdentityHashMap<>();
            if (idx != null) {
                java.util.Map<Integer, String[]> matches = new java.util.HashMap<>();
                for (Xtream.Channel ch : all) {
                    if (sourceOf(ch, setting, picks) != LiveOptions.LOGOS_PUBLIC) continue;
                    String name = LiveCountry.splitCached(ch.name, false).name, country = LiveCountry.of(ch, categoryCountry);
                    String url = LivePublicLogos.find(idx.byName, name.isEmpty() ? ch.name : name, country, ch.epg);
                    if (url != null) found.put(ch, url);
                    matches.put(ch.id, new String[] {LivePublicLogos.signature(ch.name, country, ch.epg), url == null ? "" : url});
                }
                LivePublicLogos.saveMatches(app, LivePublicLogos.indexAt(app), matches);
                AppLog.w("live: public logos for " + found.size() + " of " + all.size() + " channels");
            }
            main.post(() -> {
                // The setting or a pick changed meanwhile: that applyLogos does it.
                if (LiveOptions.logoSource(this) != setting || !picks.equals(LiveLogoChoice.picks(this))) return;
                for (java.util.Map.Entry<Xtream.Channel, String> e : found.entrySet()) e.getKey().logo = e.getValue();
                view.invalidate();
                saveShown(app, all);
            });
        }, "live-public-logos").start();
    }

    /** Channel `ch`'s logo source: its own pick, else the setting. */
    private static int sourceOf(Xtream.Channel ch, int setting, java.util.Map<Integer, Integer> picks) {
        Integer p = picks.get(ch.id);
        return p == null ? setting : p;
    }

    /** The logos that aren't the provider's, for the home screen's cards (in the background). */
    private void saveShown(Context app, List<Xtream.Channel> all) {
        final java.util.Map<Integer, String> shown = new java.util.HashMap<>();
        for (Xtream.Channel ch : all) {
            if (ch.logo != null && !ch.logo.isEmpty() && !ch.logo.equals(ch.providerLogo)) shown.put(ch.id, ch.logo);
        }
        io.execute(() -> LiveLogoChoice.saveShown(app, shown));
    }

    private static String first(String a, String b) {
        return a != null && !a.isEmpty() ? a : b != null && !b.isEmpty() ? b : null;
    }

    /** Channel logos' source changed (Settings): the list's logos again from it. */
    void logoSourceChanged() {
        applyLogos(lineup);
        view.invalidate();
    }

    private Xtream.Channel find(int id) {
        return lineup == null ? null : lineup.find(id);
    }

    /** Merging changed (on, off, a channel kept apart or merged again): the saved list again, merged anew. */
    void relist() {
        Context app = getApplicationContext();
        io.execute(() -> {
            Xtream.Lineup l = Xtream.saved(app);
            if (l != null) main.post(() -> show(l));
        });
    }

    /** What the home screen asked for: a favorite channel (its row), the guide (the Guide button), a channel number. */
    private void asked() {
        if (lineup == null) return;
        if (askChannel >= 0) {
            Xtream.Channel ch = find(askChannel);
            askChannel = -1;
            if (ch != null) {
                // From the home screen's favorites row: in Favorites; from the TV's channel list, as it is.
                if (Xtream.isFavorite(lineup, ch.id)) view.useCategory(Xtream.FAVORITES);
                if (playing == null || playing.id != ch.id) play(ch);
            }
        }
        if (askGuide) {
            askGuide = false;
            view.openGuide();
        }
        if (askDigit >= 0) {
            view.typeDigit(askDigit);
            askDigit = -1;
        }
        if (askSearch != null) {
            view.searchFor(askSearch);
            askSearch = null;
        }
        if (askRecording != null) {      // the home screen's Continue Watching: a recording
            LiveRecordings.Rec r = LiveRecordings.find(this, askRecording);
            askRecording = null;
            if (r != null && !r.file.isEmpty() && new java.io.File(r.file).exists()) playRecording(r);
        }
        if (askResume >= 0) {      // Continue Watching: the program, where it was
            Xtream.Channel ch = find(askResume);
            askResume = -1;
            if (ch != null && resumeProgram != null && resumeProgram.end > resumeProgram.start) playCatchup(ch, resumeProgram, resumeFrom);
        }
    }

    private void readIntent(Intent intent) {
        askChannel = intent == null ? -1 : intent.getIntExtra(EXTRA_CHANNEL, -1);
        askGuide = intent != null && intent.getBooleanExtra(EXTRA_GUIDE, false);
        askDigit = intent == null ? -1 : intent.getIntExtra(EXTRA_DIGIT, -1);
        askSearch = intent == null ? null : intent.getStringExtra(EXTRA_SEARCH);
        askResume = intent == null ? -1 : intent.getIntExtra(EXTRA_RESUME_CHANNEL, -1);
        askRecording = intent == null ? null : intent.getStringExtra(EXTRA_RECORDING);
        if (askResume >= 0) {
            resumeProgram = new Xtream.Program();
            resumeProgram.start = intent.getLongExtra(EXTRA_RESUME_START, 0);
            resumeProgram.end = intent.getLongExtra(EXTRA_RESUME_END, 0);
            String t = intent.getStringExtra(EXTRA_RESUME_TITLE);
            resumeProgram.title = t == null ? "" : t;
            resumeFrom = intent.getLongExtra(EXTRA_RESUME_AT, 0);
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readIntent(intent);
        asked();
    }

    // MARK: - Playing

    /** Tunes to `ch` live (remembered for next time). */
    void play(Xtream.Channel ch) {
        if (locked(ch) || ratedLocked(onNowOf(ch))) {
            view.askPin(() -> { unlocked = true; play(ch); }, () -> { if (playing == null) view.openGuide(); });
            return;
        }
        endShift();
        leftCatchup();
        if (playing != null && playing.id != ch.id) previous = playing;
        if (playing == null || playing.id != ch.id) chosenSource = LiveMerge.preferred(this, ch);     // the version picked last time
        playing = ch;
        sayWatching(ch);
        catchup = null;
        recordingFile = null;
        source = null;
        failed.clear();
        reconnects = 0;
        triedOther = waited = false;
        format = chosenFormat();
        view.followGroup(ch);       // its own group from now on (the guide, the list, channel up / down)
        prefs().edit().putInt("channel", ch.id).putString("category", view.categoryId()).apply();
        final Context app = getApplicationContext();
        io.execute(() -> Xtream.watched(app, ch));     // the home screen's On now row
        start(ch);
        view.showBanner(ch);
    }

    private void start(Xtream.Channel ch) {
        if (player == null) return;
        if (source == null) source = pickSource(false);
        if (source == null) source = ch;
        wasReady = false;
        stallAt = 0;
        MediaItem item = new MediaItem.Builder()
                .setUri(Xtream.streamUrl(this, source, format))
                .setMimeType(Xtream.mime(this, Xtream.streamUrl(this, source, format), format))
                .build();
        view.setStatus("");
        view.setBuffering(true);
        view.tuning();      // black until its first frame: nothing half-decoded shows
        player.setMediaItem(item);
        player.prepare();
        player.play();
    }

    // MARK: - Auto frame rate

    /**
     * With auto frame rate on, the screen switches to the refresh rate that's a whole multiple of the video's frame
     * rate (the lowest: 24 Hz for a film, 50 for 25 fps, 60 for 30), at the same resolution; when the stream doesn't
     * tell its frame rate, or the screen has no such mode, nothing changes.
     */
    private void matchFrameRate() {
        if (player == null) return;
        if (!LiveOptions.frameRate(this)) {
            setDisplayMode(0);
            return;
        }
        androidx.media3.common.Format f = player.getVideoFormat();
        float fps = f == null ? -1 : f.frameRate;
        if (fps <= 0) return;
        android.view.Display d = getWindowManager().getDefaultDisplay();
        android.view.Display.Mode now = d.getMode(), best = null;
        for (android.view.Display.Mode m : d.getSupportedModes()) {
            if (m.getPhysicalWidth() != now.getPhysicalWidth() || m.getPhysicalHeight() != now.getPhysicalHeight()) continue;
            double times = m.getRefreshRate() / fps, whole = Math.round(times);
            if (whole < 1 || Math.abs(times - whole) > 0.01 * whole) continue;
            if (best == null || m.getRefreshRate() < best.getRefreshRate()) best = m;
        }
        if (best != null) setDisplayMode(best.getModeId());
        AppLog.w("live: video " + fps + " fps, screen " + (best == null ? "kept" : best.getRefreshRate() + " Hz"));
    }

    /** The screen mode this window asks for (0: the TV's own). */
    private void setDisplayMode(int id) {
        android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
        if (lp.preferredDisplayModeId == id) return;
        lp.preferredDisplayModeId = id;
        getWindow().setAttributes(lp);
    }

    // MARK: - The picture, the tracks, what the stream is

    int aspectMode() {
        return LiveOptions.aspect(this);
    }

    /** The next picture fit (fit → zoom → stretch), applied now. */
    int nextAspect() {
        int mode = (aspectMode() + 1) % 3;
        LiveOptions.setAspect(this, mode);
        layoutVideo(true);
        return mode;
    }

    /**
     * What the stream is, as tags: its resolution, frame rate (when it tells it), codec, HDR, and the sound's
     * channels and Dolby.
     */
    java.util.List<String> badges() {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (player == null) return out;
        androidx.media3.common.Format v = player.getVideoFormat(), a = player.getAudioFormat();
        if (v != null && v.height > 0) {
            int lines = Math.min(v.height, v.width > 0 ? v.width : v.height);
            out.add(lines >= 2000 ? "4K" : lines >= 1400 ? "1440p" : lines >= 1000 ? "1080p" : lines >= 700 ? "720p" : "SD");
            if (v.frameRate > 0) out.add(Math.round(v.frameRate) + " FPS");
            if (MimeTypes.VIDEO_H265.equals(v.sampleMimeType)) out.add("HEVC");
            else if (MimeTypes.VIDEO_H264.equals(v.sampleMimeType)) out.add("H.264");
            if (v.colorInfo != null && v.colorInfo.colorTransfer == C.COLOR_TRANSFER_ST2084) out.add("HDR10");
            else if (v.colorInfo != null && v.colorInfo.colorTransfer == C.COLOR_TRANSFER_HLG) out.add("HLG");
        }
        if (a != null) {
            if (MimeTypes.AUDIO_E_AC3_JOC.equals(a.sampleMimeType)) out.add("Atmos");
            else if (MimeTypes.AUDIO_AC3.equals(a.sampleMimeType) || MimeTypes.AUDIO_E_AC3.equals(a.sampleMimeType)) out.add("Dolby");
            if (a.channelCount >= 8) out.add("7.1");
            else if (a.channelCount >= 6) out.add("5.1");
            else if (a.channelCount == 2) out.add("Stereo");
        }
        return out;
    }

    /** The tracks of `type` (C.TRACK_TYPE_AUDIO or TEXT) the TV can play, by name (language, or a number). */
    java.util.List<String> trackNames(int type) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (player == null) return out;
        for (androidx.media3.common.Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() != type) continue;
            for (int i = 0; i < g.length; i++) if (g.isTrackSupported(i)) out.add(trackName(g.getTrackFormat(i), out.size() + 1));
        }
        return out;
    }

    private static String trackName(androidx.media3.common.Format f, int n) {
        boolean cc = MimeTypes.APPLICATION_CEA608.equals(f.sampleMimeType) || MimeTypes.APPLICATION_CEA708.equals(f.sampleMimeType);
        String name = f.label != null ? f.label : cc ? Ui.tr("Legendas (CC)", "Closed captions")
                : f.language != null && !f.language.equals("und") ? new java.util.Locale(f.language).getDisplayLanguage(Ui.locale()) : "";
        if (name.isEmpty()) name = Ui.tr("Faixa ", "Track ") + n;
        if (f.channelCount >= 6) name += " · 5.1";
        return name;
    }

    /** Which of trackNames(type) plays, or -1 (none: subtitles off). */
    int selectedTrack(int type) {
        if (player == null) return -1;
        int n = 0;
        for (androidx.media3.common.Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() != type) continue;
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i)) continue;
                if (g.isTrackSelected(i)) return n;
                n++;
            }
        }
        return -1;
    }

    /**
     * Subtitles on (the TV's language, else English, else one with no language: closed captions) or off, for every
     * stream from now on.
     */
    private <B extends androidx.media3.common.TrackSelectionParameters.Builder> B subtitlePrefs(B p, boolean on) {
        java.util.Locale l = java.util.Locale.getDefault();
        p.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !on);
        if (on) p.setPreferredTextLanguages(l.getLanguage(), "en").setSelectUndeterminedTextLanguage(true);
        return p;
    }

    /** The audio language every stream starts in when it has it (null: the stream's own choice). */
    private String audioPref() {
        String l = LiveOptions.audioLanguage(this);
        return l.isEmpty() ? null : l;
    }

    /** The settings' Audio language back to automatic: the stream playing follows at once. */
    void clearAudioLanguage() {
        LiveOptions.setAudioLanguage(this, "");
        if (player == null) return;
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO).setPreferredAudioLanguage(null).build());
    }

    /** Subtitles on or off from Live TV's settings: remembered, and the stream playing follows at once. */
    void setSubtitlesOn(boolean on) {
        LiveOptions.setSubtitles(this, on);
        if (player == null) return;
        player.setTrackSelectionParameters(subtitlePrefs(player.getTrackSelectionParameters().buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT), on).build());
        if (!on) view.setSubtitle("");
    }

    /** Plays track `index` of trackNames(type); -1 turns the type off (subtitles off). Subtitles: remembered. */
    void selectTrack(int type, int index) {
        if (player == null) return;
        androidx.media3.common.TrackSelectionParameters.Builder p = player.getTrackSelectionParameters().buildUpon();
        if (type == C.TRACK_TYPE_TEXT) {
            LiveOptions.setSubtitles(this, index >= 0);
            subtitlePrefs(p, index >= 0);
        }
        if (index < 0) {
            player.setTrackSelectionParameters(p.setTrackTypeDisabled(type, true).build());
            view.setSubtitle("");
            return;
        }
        int n = 0;
        for (androidx.media3.common.Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() != type) continue;
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i)) continue;
                if (n++ == index) {
                    // An audio track with a language: that language from now on, on every channel that has it.
                    String lang = g.getTrackFormat(i).language;
                    if (type == C.TRACK_TYPE_AUDIO && lang != null && !lang.isEmpty() && !lang.equals("und")) {
                        LiveOptions.setAudioLanguage(this, lang);
                        p.setPreferredAudioLanguage(lang);
                    }
                    player.setTrackSelectionParameters(p.setTrackTypeDisabled(type, false)
                            .setOverrideForType(new androidx.media3.common.TrackSelectionOverride(g.getMediaTrackGroup(), i)).build());
                    return;
                }
            }
        }
    }

    /** The full guide, when it's on and old (or now: the settings' Update now); the guide reads the new one after. */
    void updateGuide(boolean now) {
        XmltvStore.updateIfStale(this, lineup, now, () -> main.post(() -> {
            view.guideReplaced();
            relist();       // the list again, with the new guide's logos
        }));
    }

    /**
     * The channel in another player on the TV (VLC and the like), for one Glassio's can't play: its stream address (a
     * merged channel's source playing, else its first; MAG's asked for now). Glassio's player lets go first, since most
     * providers allow one connection at a time.
     */
    void openElsewhere(Xtream.Channel ch) {
        Xtream.Channel src = ch == playing && source != null ? source
                : ch.sources != null && !ch.sources.isEmpty() ? ch.sources.get(0) : ch;
        String format = chosenFormat();
        final Context app = getApplicationContext();
        io.execute(() -> {
            String url;
            try {
                url = Xtream.resolve(app, Xtream.streamUrl(app, src, format));
            } catch (IOException e) {
                main.post(() -> view.flash(Ui.tr("O portal não deu o endereço do canal", "The portal didn't give the channel's address")));
                return;
            }
            main.post(() -> {
                String mime = Xtream.mime(app, url, format);
                Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(android.net.Uri.parse(url), mime != null ? mime : "video/*")
                        .putExtra("title", ch.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                List<android.content.pm.ResolveInfo> players = getPackageManager().queryIntentActivities(intent, 0);
                players.removeIf(r -> r.activityInfo.packageName.equals(getPackageName()));
                if (players.isEmpty()) {
                    view.flash(Ui.tr("Nenhum outro player nesta TV: instale o VLC, por exemplo", "No other player on this TV: install VLC, for one"));
                    return;
                }
                if (shift != null && playing != null) endShift();
                releasePlayer();
                handedOff = true;
                if (players.size() == 1) {
                    android.content.pm.ActivityInfo a = players.get(0).activityInfo;
                    intent.setClassName(a.packageName, a.name);
                    startActivity(intent);
                } else {
                    startActivity(Intent.createChooser(intent, Ui.tr("Abrir em", "Open in")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                }
            });
        });
    }

    /** The stream format: Live TV's setting, or (Auto) what the provider prefers. */
    private String chosenFormat() {
        String f = LiveOptions.format(this);
        return !f.equals(LiveOptions.AUTO) ? f : lineup == null ? "ts" : lineup.format;
    }

    /** Whether `ch` is in a category locked behind the parental PIN, not typed yet. */
    boolean locked(Xtream.Channel ch) {
        if (unlocked || ch == null) return false;
        if (LiveOptions.isLocked(this, ch.category)) return true;
        if (ch.sources != null) for (Xtream.Channel s : ch.sources) if (LiveOptions.isLocked(this, s.category)) return true;
        return false;
    }

    /** The show on now on `ch`, from the guide Live TV has (null while it loads). */
    private Xtream.Program onNowOf(Xtream.Channel ch) {
        List<Xtream.Program> list = ch == null ? null : view.programsOf(ch);
        long now = System.currentTimeMillis();
        if (list != null) for (Xtream.Program p : list) if (p.start <= now && p.end > now) return p;
        return null;
    }

    /**
     * Whether `p` is rated at or over the age set (Parental controls) and the PIN wasn't typed since Live TV came on.
     * Its rating is the guide's, else TMDB's.
     */
    boolean ratedLocked(Xtream.Program p) {
        if (unlocked || p == null) return false;
        int age = LiveOptions.ageLock(this);
        if (age <= 0 || !LiveOptions.hasPin(this)) return false;
        int rated = LiveTmdb.age(this, p);
        return rated >= age;
    }

    /**
     * Every few seconds while Live TV shows: the channel live moving on to a show rated over the age set (or its guide
     * just arriving with one) stops for the PIN.
     */
    private final Runnable ratingWatch = new Runnable() {
        @Override public void run() {
            if (playing != null && catchup == null && shift == null && !askingRating && ratedLocked(onNowOf(playing))) {
                final Xtream.Channel ch = playing;
                askingRating = true;
                if (player != null) player.stop();
                view.flash(Ui.tr("Este programa pede o PIN", "This show needs the PIN"));
                view.askPin(() -> {
                    askingRating = false;
                    unlocked = true;
                    play(ch);
                }, () -> {
                    askingRating = false;
                    view.openGuide();
                });
            }
            main.postDelayed(this, 15_000);
        }
    };
    private boolean askingRating;

    /** The PIN was typed (in the settings): locked categories play until Live TV leaves the screen. */
    void unlock() {
        unlocked = true;
    }

    boolean isUnlocked() {
        return unlocked;
    }

    /** The buffer or the format changed in the settings: a new player, the same channel (or program) on it. */
    void playbackChanged() {
        if (player != null && catchup != null) resumeAt = position();
        releasePlayer();
        createPlayer();
        if (playing != null) resume();
    }

    /** The same channel (or past program) again, in the format now chosen. */
    private void retry() {
        if (catchup != null) startCatchup(0); else if (playing != null) start(playing);
    }

    // MARK: - A merged channel's sources

    /** The source to play now: the one the person picked, else the best for the connection (LiveMerge.pick). */
    private Xtream.Channel pickSource(boolean past) {
        if (playing == null) return null;
        if (chosenSource >= 0 && playing.sources != null) {
            for (Xtream.Channel s : playing.sources) {
                if (s.id == chosenSource && !failed.contains(s.id) && (!past || s.archive > 0)) return s;
            }
        }
        return LiveMerge.pick(this, playing, past, failed);
    }

    /** The source playing failed (in both formats): the next one, if the channel has another. */
    private boolean nextSource() {
        if (playing.sources == null || source == null) return false;
        failed.add(source.id);
        Xtream.Channel next = LiveMerge.pick(this, playing, catchup != null, failed);
        if (next == null) return false;
        AppLog.w("live: " + playing.name + ": " + LiveMerge.label(source) + " didn't play, now " + LiveMerge.label(next));
        health(LiveHealth.SWITCHED);
        source = next;
        chosenSource = -1;
        triedOther = waited = false;
        format = chosenFormat();
        view.setStatus(Ui.tr("Tentando outra fonte…", "Trying another source…"));
        retry();
        return true;
    }

    /** When Live TV came on screen: trouble in its first seconds (the old connection, cut by an update or a restart, still held by the provider) isn't the channel's. */
    private long openedAt;

    /** Notes trouble on the channel playing for Stream health (not in the first 20 seconds after opening). */
    private void health(int kind) {
        if (android.os.SystemClock.uptimeMillis() - openedAt < 20_000) return;
        LiveHealth.note(this, playing, kind);
    }

    /** Every few seconds while on screen: a stream stuck buffering, or its picture still while it "plays", is recovered. */
    private final Runnable streamWatch = new Runnable() {
        @Override public void run() {
            main.postDelayed(this, WATCH_MS);
            long tickUp = android.os.SystemClock.uptimeMillis();
            if (tickUp - themeTick > 60_000) {      // sunset or sunrise: the night style on or off
                themeTick = tickUp;
                if (LiveTheme.tick(Live.this)) {
                    LiveTheme.applyFonts(Live.this);
                    view.restyle();
                }
            }
            if (player == null || playing == null || shift != null || recordingFile != null || askingRating || !player.getPlayWhenReady()) return;
            long up = android.os.SystemClock.uptimeMillis();
            int state = player.getPlaybackState();
            if (state == Player.STATE_BUFFERING && bufferingSince > 0
                    && up - bufferingSince > (wasReady ? STUCK_MS : STUCK_FIRST_MS)) {
                recover(Ui.tr("O sinal travou", "The stream froze"));
            } else if (state == Player.STATE_READY && player.isPlaying()) {
                long at = player.getCurrentPosition();
                if (at != lastPosition) {
                    lastPosition = at;
                    positionSince = up;
                } else if (up - positionSince > FROZEN_MS) {
                    recover(Ui.tr("A imagem congelou", "The picture froze"));
                    return;
                }
                if (readySince > 0 && up - readySince > GOOD_MS) reconnects = 0;     // it plays well again
            }
        }
    };

    /** The stream stopped (`why`, for the message): the merged channel's next source, else the same one again. */
    private void recover(String why) {
        if (playing == null) return;
        AppLog.w("live: " + playing.name + ": " + why + (source == null ? "" : " on " + LiveMerge.label(source)));
        health(LiveHealth.FROZE);
        bufferingSince = 0;
        positionSince = android.os.SystemClock.uptimeMillis();
        if (playing.sources != null && source != null && sources() != null) {
            failed.add(source.id);
            Xtream.Channel next = LiveMerge.pick(this, playing, catchup != null, failed);
            if (next != null) {
                view.flash(why + Ui.tr(": outra fonte", ": another source") + " (" + LiveMerge.label(next) + ")");
                health(LiveHealth.SWITCHED);
                chosenSource = -1;
                switchSource(next);
                return;
            }
        }
        if (!reconnect()) {
            health(LiveHealth.FAILED);
            view.setBuffering(false);
            view.setStatus(why + Ui.tr(". OK para tentar de novo.", ". OK to try again."));
        } else {
            view.flash(why + Ui.tr(": conectando de novo", ": connecting again"));
        }
    }

    /**
     * Connects again (the first time at once, then waiting longer), from where it was; with every source of a merged
     * channel failed, from the best one again. False after MAX_RECONNECTS.
     */
    private boolean reconnect() {
        if (playing == null || reconnects >= MAX_RECONNECTS) return false;
        health(LiveHealth.RECONNECT);
        long wait = reconnects * RETRY_MS;
        reconnects++;
        if (playing.sources != null) {
            failed.clear();
            source = null;
        }
        triedOther = waited = false;
        format = chosenFormat();
        view.setStatus(Ui.tr("Conectando de novo…", "Connecting again…"));
        final Xtream.Channel ch = playing;
        final Xtream.Program p = catchup;
        final long at = player != null && catchup != null ? position() : 0;
        main.postDelayed(() -> {
            if (playing != ch || catchup != p) return;
            if (p != null) startCatchup(at); else start(ch);
        }, wait);
        return true;
    }

    /** OK on "couldn't play": the channel again, from the start of the tries. */
    void retryNow() {
        reconnects = 0;
        failed.clear();
        if (playing != null && playing.sources != null) source = null;
        reconnect();
    }

    /** It waited for data while playing: the second time within a minute, a lighter source (when picked for the connection). */
    private void stalled() {
        long now = android.os.SystemClock.uptimeMillis();
        boolean again = stallAt > 0 && now - stallAt < 60_000;
        stallAt = now;
        if (!again || playing == null || source == null || chosenSource >= 0 || recordingFile != null || shift != null) return;
        Xtream.Channel next = LiveMerge.lighter(playing, source, catchup != null, failed);
        if (next == null) return;
        LiveMerge.slower(this, LiveMerge.need(source) * 9 / 10);
        AppLog.w("live: " + playing.name + " stalls on " + LiveMerge.label(source) + ", now " + LiveMerge.label(next));
        health(LiveHealth.SWITCHED);
        view.flash(Ui.tr("Conexão lenta: ", "Slow connection: ") + LiveMerge.label(next));
        switchSource(next);
    }

    /** Plays `s` of the channel now: live from now, a past program from where it is. */
    private void switchSource(Xtream.Channel s) {
        source = s;
        triedOther = waited = false;
        format = chosenFormat();
        if (catchup != null) {
            long at = position();
            startCatchup(at);
        } else if (playing != null) {
            start(playing);
        }
    }

    /** A merged channel's sources that can play now (on a past program, those with catch-up), or null. */
    List<Xtream.Channel> sources() {
        if (playing == null || playing.sources == null || recordingFile != null) return null;
        List<Xtream.Channel> out = new java.util.ArrayList<>();
        for (Xtream.Channel s : LiveMerge.inOrder(playing)) if (catchup == null || s.archive > 0) out.add(s);
        return out.size() > 1 ? out : null;
    }

    /** A source in the Source list: what it is ("1080p"), its name and its category (two of the same name differ there). */
    String sourceLabel(Xtream.Channel s) {
        String cat = "";
        if (lineup != null) for (Xtream.Category c : view.providerCategories()) if (c.id.equals(s.category)) cat = c.name;
        return LiveMerge.label(s) + "   " + s.name + (cat.isEmpty() ? "" : "  ·  " + cat);
    }

    /** The source playing (the channel itself when it has none). */
    Xtream.Channel source() {
        return source;
    }

    /** Whether the source is picked for the connection (not by the person). */
    boolean autoSource() {
        return chosenSource < 0;
    }

    /** The person picked source `id` (-1: back to the best for the connection). */
    void chooseSource(int id) {
        chosenSource = id;
        LiveMerge.prefer(this, playing, id);       // the same version next time on this channel
        failed.clear();
        reconnects = 0;
        Xtream.Channel s = pickSource(catchup != null);
        if (s != null && (source == null || s.id != source.id)) switchSource(s);
    }

    Xtream.Channel playing() {
        return playing;
    }

    private static final int MULTIVIEW = 7;

    /** Multiview, the channel playing in its first box (the main player stops while it's open). */
    void openMultiview() {
        // The hardware decoder goes first: Multiview's first box needs it (the TV runs one at a time).
        if (player != null && catchup != null) resumeAt = position();
        leftCatchup();
        releasePlayer();
        Intent i = new Intent(this, Multiview.class).putExtra(Multiview.EXTRA_UNLOCKED, unlocked);
        if (playing != null) i.putExtra(Multiview.EXTRA_CHANNEL, playing.id);
        startActivityForResult(i, MULTIVIEW);
    }

    /** What voice search heard goes here (the search page). */
    private java.util.function.Consumer<String> heard;
    private static final int VOICE = 41;

    /** Google's voice input (the TV's); what it heard to `done`, or a message when the TV has none. */
    void voiceSearch(java.util.function.Consumer<String> done) {
        Intent i = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, Ui.tr("Diga um canal ou um programa", "Say a channel or a show"))
                .putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        try {
            heard = done;
            startActivityForResult(i, VOICE);
        } catch (android.content.ActivityNotFoundException | SecurityException e) {
            heard = null;
            view.flash(Ui.tr("A busca por voz não está disponível nesta TV", "Voice search isn't available on this TV"));
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == VOICE) {
            java.util.function.Consumer<String> done = heard;
            heard = null;
            java.util.ArrayList<String> said = result == RESULT_OK && data != null
                    ? data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS) : null;
            if (done != null && said != null && !said.isEmpty()) done.accept(said.get(0));
            return;
        }
        if (request != MULTIVIEW || result != RESULT_OK || data == null) return;
        Xtream.Channel ch = find(data.getIntExtra(Multiview.RESULT_CHANNEL, -1));    // the focused box, full screen
        if (ch != null && (playing == null || ch.id != playing.id || catchup != null)) play(ch);
    }

    /** The channel watched before this one (the remote's last-channel key), or null. */
    Xtream.Channel previous() {
        return previous;
    }

    // MARK: - Catch-up

    /** Plays past program `p` of `ch` from its start (the provider's catch-up). */
    void playCatchup(Xtream.Channel ch, Xtream.Program p) {
        playCatchup(ch, p, 0);
    }

    /** Plays past program `p` of `ch` from `from` (ms into it). */
    void playCatchup(Xtream.Channel ch, Xtream.Program p, long from) {
        if (locked(ch) || ratedLocked(p)) {
            view.askPin(() -> { unlocked = true; playCatchup(ch, p, from); }, null);
            return;
        }
        endShift();
        leftCatchup();
        if (playing != null && playing.id != ch.id) previous = playing;
        if (playing == null || playing.id != ch.id) chosenSource = -1;
        playing = ch;
        sayWatching(ch);
        catchup = p;
        recordingFile = null;
        source = null;
        failed.clear();
        reconnects = 0;
        triedOther = waited = false;
        format = chosenFormat();
        view.followGroup(ch);       // its own group from now on (the guide, the list, channel up / down)
        prefs().edit().putInt("channel", ch.id).putString("category", view.categoryId()).apply();
        startCatchup(from);
        view.showBanner(ch);
    }

    /** The address is made on a background thread (it may ask the server for its time zone), then played. */
    private void startCatchup(long from) {
        if (player == null || playing == null || catchup == null) return;
        final Xtream.Channel ch = playing;
        final Xtream.Program p = catchup;
        final String f = format;
        final String file = recordingFile;
        // A merged channel's past from a source with catch-up (the one picked for the connection when there are several).
        if (file == null && (source == null || source.archive <= 0)) source = pickSource(true);
        final Xtream.Channel src = source == null ? ch : source;
        // The provider's address starts at the minute before `from`: the rest is a seek once it plays, when it can.
        // (A recording's file seeks from the start.)
        from = Math.max(0, from);
        final long base = file != null ? 0 : (p.start + from) / 60_000 * 60_000 - p.start;
        catchupBase = base;
        catchupInside = from - base;
        skipTo = -1;
        Context app = getApplicationContext();
        wasReady = false;
        stallAt = 0;
        view.setStatus("");
        view.setBuffering(true);
        view.tuning();
        player.stop();
        io.execute(() -> {
            String url;
            try {
                url = file != null ? android.net.Uri.fromFile(new java.io.File(file)).toString() : Xtream.catchupUrl(app, src, p, p.start + base, f);
            } catch (Exception e) {
                url = null;
            }
            final String address = url;
            main.post(() -> {
                if (player == null || playing != ch || catchup != p) return;    // something else since
                if (address == null) {
                    view.setBuffering(false);
                    view.setStatus(Ui.tr("Não deu para falar com o servidor.", "Couldn't reach the server."));
                    return;
                }
                // From the start of the address: a stream that can't seek would say it's further on than it is.
                player.setMediaItem(new MediaItem.Builder().setUri(address)
                        .setMimeType(file == null && f.equals("m3u8") ? MimeTypes.APPLICATION_M3U8 : MimeTypes.VIDEO_MP2T).build(), 0);
                player.prepare();
                player.play();
                endSeen = false;
                main.removeCallbacks(endWatch);
                main.postDelayed(endWatch, 1000);
            });
        });
    }

    /** A past program stops playing (another channel, live, the screen goes): Continue Watching gets where it got to. */
    private void leftCatchup() {
        if (player == null || catchup == null || playing == null || recordingFile != null) return;
        final Xtream.Channel ch = playing;
        final Xtream.Program p = catchup;
        final long at = player.getPlaybackState() == Player.STATE_ENDED ? Long.MAX_VALUE / 2 : position();
        final long length = length();
        final Context app = getApplicationContext();
        io.execute(() -> LiveResume.left(app, ch, p, at, length));
    }

    private String askRecording;

    /**
     * Watch on Home: the channel goes on in a small window on Glassio's home screen while you browse. Its address is
     * asked for here (a MAG portal's now), handed over in a file of the app's own (never in the intent: it carries the
     * login), then this player lets go and Live TV closes (most providers allow one connection at a time).
     */
    void watchOnHome() {
        Xtream.Channel ch = playing;
        if (ch == null) return;
        if (catchup != null || recordingFile != null) {
            view.flash(Ui.tr("Só canais ao vivo", "Live channels only"));
            return;
        }
        Xtream.Channel src = source != null ? source : ch;
        String format = chosenFormat();
        final Context app = getApplicationContext();
        io.execute(() -> {
            String url;
            try {
                url = Xtream.resolve(app, Xtream.streamUrl(app, src, format));
            } catch (IOException e) {
                main.post(() -> view.flash(Ui.tr("O portal não deu o endereço do canal", "The portal didn't give the channel's address")));
                return;
            }
            String mime = Xtream.mime(app, url, format);
            boolean put = LiveHome.putMini(app, url, mime, ch);
            main.post(() -> {
                if (!put) {
                    view.flash(Ui.tr("Não deu para abrir no início", "Couldn't open it on Home"));
                    return;
                }
                if (shift != null) endShift();
                releasePlayer();
                try {
                    startActivity(new Intent(this, com.glasslauncher.tv.MainActivity.class).putExtra("mini_live", true)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (RuntimeException e) {
                    AppLog.w("live: couldn't open the home screen: " + e.getMessage());
                }
                finish();
            });
        });
    }

    /** Plays a recording from its file, like a past program (pause, skip, LIVE back to its channel). */
    void playRecording(LiveRecordings.Rec r) {
        Xtream.Channel ch = find(r.ch.id);
        if (ch == null) ch = r.ch;      // a channel the provider no longer has: its recording still plays
        final Xtream.Channel channel = ch;
        if (locked(channel)) {
            view.askPin(() -> { unlocked = true; playRecording(r); }, null);
            return;
        }
        endShift();
        leftCatchup();
        if (playing != null && playing.id != channel.id) previous = playing;
        playing = channel;
        source = null;
        Xtream.Program p = new Xtream.Program();
        p.start = r.start;
        p.end = r.end;
        p.title = r.title;
        p.about = r.about;
        catchup = p;
        recordingFile = r.file;
        triedOther = waited = false;
        format = "ts";
        startCatchup(0);
        view.showBanner(channel);
    }

    /** Whether what plays is a recording. */
    boolean recorded() {
        return recordingFile != null;
    }

    /** The past program playing, or null (the channel live). */
    Xtream.Program catchup() {
        return catchup;
    }

    /**
     * A past program or a recording reached its end (NOW's, YouTube TV's Up next): the next one plays in a few
     * seconds unless the card is dismissed (then the channel live): a recording's next of the same show, a past
     * program's next on the channel from the catch-up, or the channel live when its next is on now.
     */
    /** Whether this past program's end was seen (Up next shows once); endWatch looks every second. */
    private boolean endSeen;
    private final Runnable endWatch = new Runnable() {
        @Override public void run() {
            // Some providers' catch-up runs on past the show (longer than it): its end is the guide's, then.
            if (player == null || catchup == null || endSeen) return;
            long length = length();
            if (player.isPlaying() && length > 60_000 && position() >= length) {
                endSeen = true;
                upNext();
                return;
            }
            main.postDelayed(this, 1000);
        }
    };

    private void upNext() {
        if (playing == null || catchup == null) return;
        final Xtream.Channel ch = playing;
        final Xtream.Program p = catchup;
        long now = System.currentTimeMillis();
        if (recordingFile != null) {
            LiveRecordings.Rec best = null;
            for (LiveRecordings.Rec r : LiveRecordings.all(this)) {
                if (!LiveRecordings.DONE.equals(r.state) || r.file.equals(recordingFile) || !r.title.equalsIgnoreCase(p.title)
                        || r.start <= p.start) continue;
                if (best == null || r.start < best.start) best = r;
            }
            if (best == null) {
                backToLive();
                return;
            }
            final LiveRecordings.Rec next = best;
            view.upNext(next.title, Ui.tr("Gravado", "Recorded") + "  ·  " + ch.name, Ui.tr("Assistir", "Play now"),
                    () -> playRecording(next), this::backToLive);
            return;
        }
        List<Xtream.Program> list = view.programsOf(ch);
        Xtream.Program next = null;
        for (int i = 0; list != null && i + 1 < list.size(); i++) if (list.get(i).start == p.start) next = list.get(i + 1);
        if (next == null) {
            backToLive();
            return;
        }
        final Xtream.Program n = next;
        if (n.start <= now && n.end > now) {        // on now: the channel live
            view.upNext(n.title, Ui.tr("Agora", "Now") + "  ·  " + ch.name, Ui.tr("Assistir ao vivo", "Watch live"),
                    this::backToLive, this::backToLive);
        } else if (n.end <= now && LiveView.replayable(ch, n, now)) {
            view.upNext(n.title, Ui.time(n.start) + "  ·  " + ch.name, Ui.tr("Assistir", "Play now"),
                    () -> playCatchup(ch, n), this::backToLive);
        } else {
            backToLive();
        }
    }

    /** From a past program back to its channel live. */
    void backToLive() {
        if (playing != null) play(playing);
    }

    /** How far into the past program (ms) and how long it is (the stream's length, else the guide's). */
    long position() {
        if (skipTo >= 0) return skipTo;     // skips on their way: where they go
        if (shift != null) return Math.max(0, shiftAt() - shift.oldest());
        if (player == null) return 0;
        long at = Math.max(0, player.getCurrentPosition());
        // A past program: from where its address starts (and, till it plays, where it's going in that minute).
        return catchup == null ? at : Math.max(0, catchupBase + Math.max(at, catchupInside));
    }

    long length() {
        if (shift != null) return Math.max(1000, shift.newest() - shift.oldest());
        long d = player == null ? C.TIME_UNSET : player.getDuration();
        long guide = catchup == null ? 0 : catchup.end - catchup.start;
        long total = d == C.TIME_UNSET || d <= 0 ? guide : (catchup == null ? 0 : catchupBase) + d;
        // Some providers' catch-up says it's far longer than the show (a day and more): then the guide's length.
        if (guide > 0 && recordingFile == null && total > guide * 3 / 2 + 15 * 60_000L) return guide;
        return total;
    }

    boolean paused() {
        if (shift != null && !shiftPlaying) return true;
        return player != null && !player.getPlayWhenReady();
    }

    /** Play/pause: a past program or a recording as it is; live TV pauses into the TV's own buffer (LiveTimeshift). */
    void togglePause() {
        if (player == null) return;
        if (skipTo >= 0) {      // skips on their way: there first
            main.removeCallbacks(applySkip);
            applySkip.run();
        }
        if (catchup == null && shift == null && playing != null) {
            startShift();
            return;
        }
        if (shift != null && !shiftPlaying) {
            playShift(shift.oldest() > shiftFrom ? shift.oldest() : shiftFrom);
            return;
        }
        player.setPlayWhenReady(!player.getPlayWhenReady());
    }

    /** A backup brought back: the reminders armed again, the look read again, and Live TV starts over with it all. */
    void restored() {
        LiveSchedule.armAll(this);
        LiveSeries.arm(this);
        LiveTheme.load(this);
        recreate();
    }

    private boolean followSynced;

    /** Who's watching was asked since Live TV opened (once). */
    private boolean profileAsked;

    /** Another profile watches: its favorites, History and Continue watching (and, Kids, its channels only). */
    void switchProfile(String id) {
        LiveProfiles.switchTo(this, id);
        LiveTheme.load(this);       // each profile its own style
        LiveTheme.applyFonts(this);
        view.restyle();
        relist();       // the list read again (off the screen's thread): its favorites, and Kids' channels
        view.historyChanged();
    }

    /** The sound off while Live TV's Home covers the video (YouTube TV's Home is quiet), and back. */
    void setMuted(boolean muted) {
        if (player != null) player.setVolume(muted ? 0f : 1f);
    }

    /** Whether live TV is paused or playing behind live (from the TV's own buffer). */
    boolean shifted() {
        return shift != null;
    }

    /** How far behind live it plays (ms). */
    long behind() {
        return shift == null ? 0 : Math.max(0, shift.newest() - shiftAt());
    }

    /** The moment playing now (the stream's clock), behind live. */
    private long shiftAt() {
        if (shift == null) return 0;
        if (!shiftPlaying || player == null) return shiftFrom;
        return shiftFrom + Math.max(0, player.getCurrentPosition());
    }

    /** Pause on live TV: the player lets go of the stream and the TV keeps it from now (the picture stays). */
    private void startShift() {
        shift = new LiveTimeshift(this, source != null ? source : playing);     // the stream that was playing (a merged channel's)
        shiftFrom = 0;
        shiftPlaying = false;
        player.setPlayWhenReady(false);
        player.stop();      // its connection goes: the buffer's takes its place (one per account, often)
        view.setBuffering(false);
        view.tracksChanged();
        // The buffer couldn't start (no answer from the provider): live again.
        final LiveTimeshift s = shift;
        main.postDelayed(() -> {
            if (shift == s && !s.started()) {
                view.flash(s.failure() != null ? s.failure() : Ui.tr("Não deu para pausar este canal", "Couldn't pause this channel"));
                play(playing);
            }
        }, 15_000);
    }

    /** Plays the buffer from moment `time` (the stream's clock; the player waits at the newest for what comes next). */
    private void playShift(long time) {
        if (shift == null || player == null) return;
        long offset = shift.offsetAt(time);
        shiftFrom = shift.timeAt(offset);
        shiftPlaying = true;
        androidx.media3.exoplayer.source.ProgressiveMediaSource source =
                new androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(shift.sources(), extractors)
                        .createMediaSource(new MediaItem.Builder().setUri("timeshift:" + offset).setMimeType(MimeTypes.VIDEO_MP2T).build());
        wasReady = false;
        view.setBuffering(true);
        player.setMediaSource(source);
        player.prepare();
        player.play();
        view.tracksChanged();
    }

    /** The pause over: the buffer stops and goes (the caller plays what's next). */
    private void endShift() {
        if (shift == null) return;
        shift.stop();
        shift = null;
        shiftPlaying = false;
    }

    /**
     * Skips `ms` (back when negative) in the past program, or behind live. Presses in a row add up and go once they
     * stop (each would otherwise open the stream again); held, the steps grow. False when nothing can skip.
     */
    boolean skip(long ms) {
        if (player == null || shift == null && catchup == null) return false;
        long up = android.os.SystemClock.uptimeMillis();
        skipRun = up - skipLast < 700 && (ms > 0) == (skipStep > 0) ? skipRun + 1 : 0;
        skipLast = up;
        skipStep = ms;
        long step = ms * (skipRun >= 12 ? 6 : skipRun >= 5 ? 3 : 1);
        long to = Math.max(0, (skipTo >= 0 ? skipTo : position()) + step), end = length();
        long now = System.currentTimeMillis();
        boolean airing = shift == null && recordingFile == null && catchup.end > now;
        // Behind live (the pause, or a show still on): up to live. Over: up to its last second.
        if (shift != null) to = Math.min(to, end);
        else if (airing) to = Math.min(to, now - catchup.start);
        else if (end > 0) to = Math.min(to, end - 1000);
        skipTo = to;
        skipOn = shift != null ? shift : catchup;
        main.removeCallbacks(applySkip);
        main.postDelayed(applySkip, 600);
        return true;
    }

    private long skipStep;

    /** The skips pressed, done: a seek, the stream again from the minute when it can't seek, or live at the end. */
    private final Runnable applySkip = () -> {
        long to = skipTo;
        skipTo = -1;
        if (to < 0 || player == null) return;
        if (shift != null) {
            if (skipOn != shift) return;
            if (shift.oldest() + to >= shift.newest() - 4000) {     // past the newest: live again
                backToLive();
                return;
            }
            boolean wasPaused = paused();
            playShift(shift.oldest() + to);
            if (wasPaused) player.setPlayWhenReady(false);      // still paused, at the new moment
            return;
        }
        if (catchup == null || skipOn != catchup) return;
        long now = System.currentTimeMillis();
        if (recordingFile == null && catchup.end > now && to >= now - catchup.start - 5000) {    // caught up: live
            backToLive();
            return;
        }
        long at = position();
        if (recordingFile != null || player.isCurrentMediaItemSeekable() && to >= catchupBase) {
            player.seekTo(Math.max(0, to - catchupBase));
            return;
        }
        // It can't seek (most providers' catch-up as MPEG-TS): the address again, from the minute `to` is in; ahead,
        // the next minute when that one is no further on than now.
        long minute = (catchup.start + to) / 60_000 * 60_000 - catchup.start;
        startCatchup(to > at && minute <= at ? minute + 60_000 : to);
    };

    /** The video at its own shape, as large as the screen allows (letterboxed or pillarboxed). */
    private void fit(VideoSize size) {
        if (size.width > 0 && size.height > 0) aspect = size.width * size.pixelWidthHeightRatio / size.height;
        layoutVideo(false);
    }

    /**
     * The guide shows the playing channel in a window at the top right (LiveView.P_*), otherwise full screen: the
     * video glides between the two (YouTube TV's way).
     */
    void setPreview(boolean on) {
        if (preview == on) return;
        preview = on;
        layoutVideo(true);
    }

    /** The guide's window moves right by `dx` (1920 space) while its groups are beside it. */
    /** The guide's video window moved (another style's layout): the video goes there. */
    void layoutChanged() {
        layoutVideo(false);
    }

    void shiftPreview(float dx) {
        if (previewDx == dx) return;
        previewDx = dx;
        if (preview) layoutVideo(true);
    }

    private float previewDx;

    /**
     * The video at its own shape, as large as the screen allows (letterboxed or pillarboxed). In the guide's window it
     * keeps that layout and is scaled and moved there: a transform, so the move animates without a relayout.
     */
    private void layoutVideo(boolean animate) {
        int sw = root.getWidth(), sh = root.getHeight();
        if (sw <= 0) {
            root.post(() -> layoutVideo(false));
            return;
        }
        // In the guide's window always all of it; full screen, as picked (Picture: fit, zoom or stretch).
        int mode = preview ? FIT : aspectMode();
        int vw = sw, vh = Math.round(sw / aspect);
        if (mode == STRETCH) {
            vh = sh;
        } else if (mode == FIT ? vh > sh : vh < sh) {
            vh = sh;
            vw = Math.round(sh * aspect);
        }
        int left = (sw - vw) / 2, top = (sh - vh) / 2;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surface.getLayoutParams();
        if (lp.width != vw || lp.height != vh || lp.leftMargin != left || lp.topMargin != top) {
            lp.width = vw;
            lp.height = vh;
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.leftMargin = left;
            lp.topMargin = top;
            surface.setLayoutParams(lp);
        }
        float s = 1, tx = 0, ty = 0;
        if (preview) {
            float k = sw / 1920f, bx = (LiveView.P_X + previewDx) * k, by = LiveView.P_Y * k, bw = LiveView.P_W * k, bh = LiveView.P_H * k;
            s = Math.min(bw / vw, bh / vh);
            tx = bx + (bw - vw * s) / 2 - left;
            ty = by + (bh - vh * s) / 2 - top;
        }
        surface.setPivotX(0);
        surface.setPivotY(0);
        surface.animate().cancel();
        if (animate) {
            surface.animate().scaleX(s).scaleY(s).translationX(tx).translationY(ty).setDuration(420)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(2.2f)).start();
        } else {
            surface.setScaleX(s);
            surface.setScaleY(s);
            surface.setTranslationX(tx);
            surface.setTranslationY(ty);
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("live-ui", MODE_PRIVATE);
    }
}
