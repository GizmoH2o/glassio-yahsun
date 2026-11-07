package com.glasslauncher.tv.live;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.LoadControl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.io.File;
import java.util.List;
import java.util.Set;

/**
 * Live TV's own settings (Live TV > Settings): the stream format, the buffer, the categories hidden and the ones locked
 * behind the parental PIN. Only Live TV's process (:live) reads them, so SharedPreferences are fine; they belong to
 * the provider signed in and go with it (clear). The PIN is kept only as a salted hash.
 */
final class LiveOptions {
    private LiveOptions() {}

    static final String AUTO = "auto", TS = "ts", HLS = "m3u8";
    static final int NORMAL = 0, LARGE = 1, EXTRA = 2;

    /** Settings back to their first values (Reset this section). */
    static void forget(Context c, String... keys) {
        SharedPreferences.Editor e = sp(c).edit();
        for (String k : keys) e.remove(k);
        e.apply();
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("live-options", Context.MODE_PRIVATE);
    }

    /** "auto" (what the provider prefers), "ts" (MPEG-TS) or "m3u8" (HLS). */
    static String format(Context c) {
        return sp(c).getString("format", AUTO);
    }

    static void setFormat(Context c, String f) {
        sp(c).edit().putString("format", f).apply();
    }

    /** NORMAL, LARGE or EXTRA: how much video is loaded before it plays and after it stalls. */
    static int buffer(Context c) {
        return sp(c).getInt("buffer", NORMAL);
    }

    static void setBuffer(Context c, int b) {
        sp(c).edit().putInt("buffer", b).apply();
    }

    /**
     * The player's buffer for `buffer`: Media3's defaults (start after 2.5 s loaded, 5 s after a stall), or more
     * before starting and after a stall, for connections that stall: slower to start, steadier after.
     */
    static LoadControl loadControl(int buffer) {
        DefaultLoadControl.Builder b = new DefaultLoadControl.Builder();
        if (buffer == LARGE) b.setBufferDurationsMs(30_000, 60_000, 5_000, 10_000);
        else if (buffer == EXTRA) b.setBufferDurationsMs(50_000, 120_000, 10_000, 20_000);
        return b.build();
    }

    // MARK: - Playback

    /** Auto frame rate: the screen switches to a refresh rate matching the video (when the stream tells its own). */
    /** Live TV through the TV's own buffer (its last 15 minutes): ◀ goes back on any channel, catch-up or not. */
    static boolean rewindAny(Context c) {
        return sp(c).getBoolean("rewindAny", false);
    }

    static void setRewindAny(Context c, boolean on) {
        sp(c).edit().putBoolean("rewindAny", on).apply();
    }

    static boolean frameRate(Context c) {
        return sp(c).getBoolean("frameRate", false);
    }

    static void setFrameRate(Context c, boolean on) {
        sp(c).edit().putBoolean("frameRate", on).apply();
    }

    /** Dolby and DTS sound sent as it is to the soundbar or receiver (when it takes it); off: decoded on the TV. */
    // The captions' look (Live TV's settings, Captions): size (1920 units), text color, background.
    static final float[] CC_SIZES = {32, 40, 50, 62};
    static final int[] CC_COLORS = {0xFFF5F5F7, 0xFFFFE14D, 0xFF7FE7FF};
    static final int CC_BAND = 0, CC_OUTLINE = 1, CC_BOX = 2, CC_STYLES = 3;

    static int ccSize(Context c) {
        return Math.max(0, Math.min(CC_SIZES.length - 1, sp(c).getInt("ccSize", 1)));
    }

    static int ccColor(Context c) {
        return Math.max(0, Math.min(CC_COLORS.length - 1, sp(c).getInt("ccColor", 0)));
    }

    static int ccStyle(Context c) {
        return Math.max(0, Math.min(CC_STYLES - 1, sp(c).getInt("ccStyle", CC_BAND)));
    }

    static void setCaptions(Context c, int size, int color, int style) {
        sp(c).edit().putInt("ccSize", size).putInt("ccColor", color).putInt("ccStyle", style).apply();
    }

    /** The audio language picked last in the player's Audio (a language code; "": automatic, the stream's own). */
    static String audioLanguage(Context c) {
        return sp(c).getString("audioLanguage", "");
    }

    static void setAudioLanguage(Context c, String language) {
        sp(c).edit().putString("audioLanguage", language == null ? "" : language).apply();
    }

    /** Whether subtitles are on (the last choice in the player's Subtitles: it carries on to every channel). */
    static boolean subtitles(Context c) {
        return sp(c).getBoolean("subtitles", false);
    }

    static void setSubtitles(Context c, boolean on) {
        sp(c).edit().putBoolean("subtitles", on).apply();
    }

    static boolean passthrough(Context c) {
        return sp(c).getBoolean("passthrough", true);
    }

    static void setPassthrough(Context c, boolean on) {
        sp(c).edit().putBoolean("passthrough", on).apply();
    }

    /** Tunneled playback: the video goes from the decoder to the screen in step with the sound (where the TV can). */
    static boolean tunneling(Context c) {
        return sp(c).getBoolean("tunneling", false);
    }

    static void setTunneling(Context c, boolean on) {
        sp(c).edit().putBoolean("tunneling", on).apply();
    }

    /** The picture's fit: Live.FIT, ZOOM or STRETCH. */
    static int aspect(Context c) {
        return sp(c).getInt("aspect", 0);
    }

    static void setAspect(Context c, int mode) {
        sp(c).edit().putInt("aspect", mode).apply();
    }

    // MARK: - The guide

    static final int[] GUIDE_HOURS = {1, 2, 4, 6, 12, 24}, PAST_DAYS = {0, 1, 2, 3, 5, 7};

    /** How old a channel's saved guide may get before it's fetched again, in hours. */
    static int guideHours(Context c) {
        return sp(c).getInt("guideHours", 6);
    }

    static void setGuideHours(Context c, int h) {
        sp(c).edit().putInt("guideHours", h).apply();
    }

    /** Whether a guide update keeps the shows that are over, up to the past days kept. */
    static boolean keepPast(Context c) {
        return sp(c).getBoolean("keepPast", true);
    }

    static void setKeepPast(Context c, boolean on) {
        sp(c).edit().putBoolean("keepPast", on).apply();
    }

    /** Whether a catch-up channel's guide reaches further back with the provider's table. */
    static boolean catchupTable(Context c) {
        return sp(c).getBoolean("catchupTable", true);
    }

    static void setCatchupTable(Context c, boolean on) {
        sp(c).edit().putBoolean("catchupTable", on).apply();
    }

    /** Whether a channel with no guide takes the one with exactly its name. */
    static boolean matchNames(Context c) {
        return sp(c).getBoolean("matchNames", true);
    }

    static void setMatchNames(Context c, boolean on) {
        sp(c).edit().putBoolean("matchNames", on).apply();
    }

    /** Whether the Recordings page marks recordings that won't all fit the account's streams. */
    /** A line when a followed team's match is close and late (LiveAlerts). */
    static boolean closeGames(Context c) {
        return sp(c).getBoolean("closeGames", true);
    }

    static void setCloseGames(Context c, boolean on) {
        sp(c).edit().putBoolean("closeGames", on).apply();
    }

    /** Goals and close games shown on the home screen too. */
    static boolean alertsOnHome(Context c) {
        return sp(c).getBoolean("alertsOnHome", true);
    }

    static void setAlertsOnHome(Context c, boolean on) {
        sp(c).edit().putBoolean("alertsOnHome", on).apply();
    }

    /** No alerts for a match being recorded: they'd spoil it. */
    static boolean alertsQuietRecording(Context c) {
        return sp(c).getBoolean("alertsQuietRecording", true);
    }

    static void setAlertsQuietRecording(Context c, boolean on) {
        sp(c).edit().putBoolean("alertsQuietRecording", on).apply();
    }

    /** Every Multiview box on the video chip; off, only the focused one (the rest in software). */
    static boolean multiviewHardware(Context c) {
        return sp(c).getBoolean("multiviewHardware", true);
    }

    static void setMultiviewHardware(Context c, boolean on) {
        sp(c).edit().putBoolean("multiviewHardware", on).apply();
    }

    /** Multiview's saved views (LiveMultiRules). */
    static String multiViews(Context c) {
        return sp(c).getString("multiViews", "[]");
    }

    static void setMultiViews(Context c, String json) {
        sp(c).edit().putString("multiViews", json).apply();
    }

    /** The player's Match tab (LivePlayerMatch). */
    static boolean matchCentre(Context c) {
        return sp(c).getBoolean("matchCentre", true);
    }

    static void setMatchCentre(Context c, boolean on) {
        sp(c).edit().putBoolean("matchCentre", on).apply();
    }

    /** Smart channels: the group in the guide, its two ready-made rows, and your own rules (LiveSmart). */
    static boolean smartChannels(Context c) {
        return sp(c).getBoolean("smartChannels", true);
    }

    static void setSmartChannels(Context c, boolean on) {
        sp(c).edit().putBoolean("smartChannels", on).apply();
    }

    static boolean smartFollowing(Context c) {
        return sp(c).getBoolean("smartFollowing", true);
    }

    static void setSmartFollowing(Context c, boolean on) {
        sp(c).edit().putBoolean("smartFollowing", on).apply();
    }

    static boolean smartNew(Context c) {
        return sp(c).getBoolean("smartNew", true);
    }

    static void setSmartNew(Context c, boolean on) {
        sp(c).edit().putBoolean("smartNew", on).apply();
    }

    static String smartRules(Context c) {
        return sp(c).getString("smartRules", "[]");
    }

    static void setSmartRules(Context c, String json) {
        sp(c).edit().putString("smartRules", json).apply();
    }

    static boolean recordClashes(Context c) {
        return sp(c).getBoolean("recordClashes", true);
    }

    static void setRecordClashes(Context c, boolean on) {
        sp(c).edit().putBoolean("recordClashes", on).apply();
    }

    /** Whether recordings go to a USB drive when one is plugged in. RecordService's process reads it too. */
    static boolean recordToDrive(Context c) {
        return c.getApplicationContext().getSharedPreferences("live-options", Context.MODE_MULTI_PROCESS).getBoolean("recordToDrive", false);
    }

    static void setRecordToDrive(Context c, boolean on) {
        sp(c).edit().putBoolean("recordToDrive", on).commit();     // now: the recorder reads it from another process
    }

    /** Whether a merged channel passes over a source that kept failing in the last hours. */
    static boolean avoidBadSources(Context c) {
        return sp(c).getBoolean("avoidBadSources", true);
    }

    static void setAvoidBadSources(Context c, boolean on) {
        sp(c).edit().putBoolean("avoidBadSources", on).apply();
    }

    /** Whether an SD channel takes the HD copy of itself from another category as a source. */
    static boolean hdTwins(Context c) {
        return sp(c).getBoolean("hdTwins", true);
    }

    static void setHdTwins(Context c, boolean on) {
        sp(c).edit().putBoolean("hdTwins", on).apply();
    }

    /** How many days of programs that are over the guide keeps (on catch-up channels, at least their catch-up). */
    static int pastDays(Context c) {
        return sp(c).getInt("pastDays", 1);
    }

    static void setPastDays(Context c, int d) {
        sp(c).edit().putInt("pastDays", d).apply();
    }

    static final int[] DAYS_AHEAD = {1, 2, 3, 5, 7};

    /** Compact guide rows (smaller, more of them on screen) instead of the comfortable ones. */
    /** The guide's channel column: the logo and the name at the left (true) or in the middle. */
    static boolean guideLogosLeft(Context c) {
        return sp(c).getBoolean("guideLogosLeft", true);
    }

    static void setGuideLogosLeft(Context c, boolean left) {
        sp(c).edit().putBoolean("guideLogosLeft", left).apply();
    }

    /** The player's info bar: the logo in one place, centered (true), or on the text beside it (it moved with it). */
    static boolean playerLogoSteady(Context c) {
        return sp(c).getBoolean("playerLogoSteady", true);
    }

    static void setPlayerLogoSteady(Context c, boolean steady) {
        sp(c).edit().putBoolean("playerLogoSteady", steady).apply();
    }

    /** Where channel logos come from first (the others fill in what it doesn't have): the provider, the guide, iptv-org. */
    static final int LOGOS_PROVIDER = 0, LOGOS_GUIDE = 1, LOGOS_PUBLIC = 2;

    static int logoSource(Context c) {
        int v = sp(c).getInt("logoSource", LOGOS_PROVIDER);
        return v < LOGOS_PROVIDER || v > LOGOS_PUBLIC ? LOGOS_PROVIDER : v;
    }

    static void setLogoSource(Context c, int source) {
        sp(c).edit().putInt("logoSource", source).apply();
    }

    /** What stands beside a channel's name in Glassio 3's guide and list. */
    static final int MARK_LOGO = 0, MARK_NUMBER = 1, MARK_BOTH = 2;

    static int channelMark(Context c) {
        int v = sp(c).getInt("channelMark", MARK_LOGO);
        return v < MARK_LOGO || v > MARK_BOTH ? MARK_LOGO : v;
    }

    static void setChannelMark(Context c, int mark) {
        sp(c).edit().putInt("channelMark", mark).apply();
    }

    static boolean guideCompact(Context c) {
        return sp(c).getBoolean("guideCompact", false);
    }

    static void setGuideCompact(Context c, boolean on) {
        sp(c).edit().putBoolean("guideCompact", on).apply();
    }

    /** The guide offsets on offer, in minutes: three hours either way, by half hours. */
    static final int[] GUIDE_SHIFTS = {-180, -150, -120, -90, -60, -30, 0, 30, 60, 90, 120, 150, 180};
    private static volatile long shiftMs, shiftReadAt;

    /**
     * How far the guide's times are moved, in ms, for a provider whose guide runs early or late: every program is
     * shown, reminded of and recorded that much later (less: earlier). Each playlist has its own (a file LivePlaylists
     * moves), read from every process; kept a couple of seconds, it's read for every guide lookup.
     */
    static long guideShift(Context c) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - shiftReadAt > 2000 || shiftReadAt == 0) {
            long ms = 0;
            java.io.File f = shiftFile(c);
            if (f.exists()) {
                try {
                    byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
                    ms = Long.parseLong(new String(b, StandardCharsets.UTF_8).trim()) * 60_000L;
                } catch (java.io.IOException | NumberFormatException ignored) {
                }
            }
            shiftMs = ms;
            shiftReadAt = now;
        }
        return shiftMs;
    }

    static int guideShiftMinutes(Context c) {
        return (int) (guideShift(c) / 60_000L);
    }

    static void setGuideShift(Context c, int minutes) {
        java.io.File f = shiftFile(c);
        try {
            if (minutes == 0) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            } else {
                java.nio.file.Files.write(f.toPath(), String.valueOf(minutes).getBytes(StandardCharsets.UTF_8));
            }
        } catch (java.io.IOException e) {
            AppLog.w("live: couldn't save the guide offset");
        }
        shiftReadAt = 0;
    }

    private static java.io.File shiftFile(Context c) {
        return new java.io.File(c.getApplicationContext().getFilesDir(), "live-guide-shift.txt");
    }

    /** An offset in words: "+1 h 30 min", "−30 min", or none. */
    static String shiftName(int minutes) {
        if (minutes == 0) return Ui.tr("Nenhum", "None");
        int m = Math.abs(minutes);
        String s = (m >= 60 ? m / 60 + " h" : "") + (m >= 60 && m % 60 != 0 ? " " : "") + (m % 60 != 0 ? m % 60 + " min" : "");
        return (minutes > 0 ? "+" : "−") + s;
    }

    /** The full guide (XMLTV): the provider's whole guide fetched at once, instead of channel by channel. */
    static boolean fullGuide(Context c) {
        return sp(c).getBoolean("fullGuide", true);
    }

    static void setFullGuide(Context c, boolean on) {
        sp(c).edit().putBoolean("fullGuide", on).apply();
    }

    /** How many days ahead the full guide keeps. */
    static int daysAhead(Context c) {
        return sp(c).getInt("daysAhead", 2);
    }

    static void setDaysAhead(Context c, int d) {
        sp(c).edit().putInt("daysAhead", d).apply();
    }

    /** A guide link typed in the settings (an XMLTV .xml or .xml.gz); "": the provider's. */
    static String guideUrl(Context c) {
        return sp(c).getString("guideUrl", "");
    }

    static void setGuideUrl(Context c, String url) {
        sp(c).edit().putString("guideUrl", url == null ? "" : url.trim()).apply();
    }

    /** Whether each channel's guide is fetched again the first time Live TV shows it (else the saved one, while fresh). */
    static boolean guideOnStart(Context c) {
        return sp(c).getBoolean("guideOnStart", true);
    }

    static void setGuideOnStart(Context c, boolean on) {
        sp(c).edit().putBoolean("guideOnStart", on).apply();
    }

    /** The next value after `now` in `values` (round), or before it (dir < 0). */
    static final int[] REMIND_LEAD = {0, 1, 2, 5, 10};
    /** Recordings: minutes started before the show, and kept going after it (a match's stoppage time). */
    static final int[] RECORD_EARLY = {0, 1, 2, 5, 10}, RECORD_LATE = {0, 2, 5, 10, 15, 30};
    static final int[] KEEP_DAYS = {0, 7, 14, 30, 60, 90}, KEEP_SHOW = {0, 1, 3, 5, 10};

    /** Recordings older than this many days go by themselves (0: none). */
    static int keepDays(Context c) {
        return sp(c).getInt("keepDays", 0);
    }

    static void setKeepDays(Context c, int days) {
        sp(c).edit().putInt("keepDays", days).apply();
    }

    /** Only this many of each show's recordings stay, the newest (0: all). */
    static int keepPerShow(Context c) {
        return sp(c).getInt("keepPerShow", 0);
    }

    static void setKeepPerShow(Context c, int n) {
        sp(c).edit().putInt("keepPerShow", n).apply();
    }

    /** A line at half-time and full-time of a followed team's match, while Live TV is on. */
    static boolean matchNews(Context c) {
        return sp(c).getBoolean("matchNews", true);
    }

    static void setMatchNews(Context c, boolean on) {
        sp(c).edit().putBoolean("matchNews", on).apply();
    }

    /** A line at a red card in a followed team's match, while Live TV is on. */
    static boolean redCardAlerts(Context c) {
        return sp(c).getBoolean("redCardAlerts", true);
    }

    static void setRedCardAlerts(Context c, boolean on) {
        sp(c).edit().putBoolean("redCardAlerts", on).apply();
    }

    /** Channels the full guide knows nothing about stay out of the categories. */
    static boolean hideNoGuide(Context c) {
        return sp(c).getBoolean("hideNoGuide", false);
    }

    static void setHideNoGuide(Context c, boolean on) {
        sp(c).edit().putBoolean("hideNoGuide", on).apply();
    }

    private static final java.util.Set<String> ADULT = new java.util.HashSet<>(java.util.Arrays.asList(
            "adult", "adults", "xxx", "18", "porn", "erotic", "erotica", "erotik", "adulto", "adultos"));

    /** The ids of the categories whose names say they're for adults. Pure. */
    static Set<String> adultIds(java.util.List<Xtream.Category> categories) {
        Set<String> out = new HashSet<>();
        for (Xtream.Category c : categories) if (c.name != null && looksAdult(c.name)) out.add(c.id);
        return out;
    }

    /** Whether a category's name says it's for adults ("XXX", "For Adults", "18+"; U18 isn't). Pure. */
    static boolean looksAdult(String name) {
        for (String w : Xtream.fold(name).split(" ")) if (ADULT.contains(w)) return true;
        return false;
    }

    /** The player's Night mode switch: quieter now, whatever the hour. */
    static boolean nightSound(Context c) {
        return sp(c).getBoolean("nightSound", false);
    }

    static void setNightSound(Context c, boolean on) {
        sp(c).edit().putBoolean("nightSound", on).apply();
    }

    /** Whether a channel keeps the audio and subtitles last picked on it. */
    static boolean keepChannelTracks(Context c) {
        return sp(c).getBoolean("keepChannelTracks", true);
    }

    static void setKeepChannelTracks(Context c, boolean on) {
        sp(c).edit().putBoolean("keepChannelTracks", on).apply();
    }

    /** The language a channel starts in: its own when it keeps one, else `general` (may be null). Pure. */
    static String channelLanguage(boolean keep, String own, String general) {
        return keep && !own.isEmpty() ? own : general;
    }

    /** The audio language picked on this channel, whatever the others do ("": none picked). */
    static String channelAudio(Context c, int channel) {
        return sp(c).getString("audio:" + channel, "");
    }

    static void setChannelAudio(Context c, int channel, String language) {
        sp(c).edit().putString("audio:" + channel, language).apply();
    }

    /** The subtitle language picked on this channel ("": none picked). */
    static String channelText(Context c, int channel) {
        return sp(c).getString("text:" + channel, "");
    }

    static void setChannelText(Context c, int channel, String language) {
        sp(c).edit().putString("text:" + channel, language).apply();
    }

    /**
     * How many minutes early a recording starts (0: on time, still half a minute ahead for the stream to start), and
     * how long it keeps going after the end. A file, as the reminders' are: the alarm and the recorder read it from the
     * main process.
     */
    static int recordEarly(Context c) {
        return recordPad(c, 0, 0);
    }

    static int recordLate(Context c) {
        return recordPad(c, 1, 2);
    }

    static void setRecordPadding(Context c, int early, int late) {
        try {
            Xtream.write(new java.io.File(c.getApplicationContext().getFilesDir(), "live-record-pad.txt"), early + " " + late);
        } catch (java.io.IOException e) {
            AppLog.w("live: couldn't save the recording times");
        }
    }

    private static int recordPad(Context c, int i, int fallback) {
        java.io.File f = new java.io.File(c.getApplicationContext().getFilesDir(), "live-record-pad.txt");
        if (!f.exists()) return fallback;
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            String[] s = Xtream.read(in).trim().split(" ");
            return Integer.parseInt(s[i]);
        } catch (java.io.IOException | RuntimeException e) {
            return fallback;
        }
    }
    static final int[] HISTORY_MAX = {5, 10, 20, 30, 50, 100};
    static final int[] PANEL_OPACITY = {25, 50, 75, 90, 100};

    /** How solid the settings' background is, in percent (100: the video doesn't show through). */
    static int panelOpacity(Context c) {
        return sp(c).getInt("panelOpacity", 50);
    }

    static void setPanelOpacity(Context c, int pct) {
        sp(c).edit().putInt("panelOpacity", pct).apply();
    }

    /** How many channels History keeps (the latest watched). */
    static int historyMax(Context c) {
        return sp(c).getInt("historyMax", 20);
    }

    static void setHistoryMax(Context c, int n) {
        sp(c).edit().putInt("historyMax", n).apply();
    }

    /**
     * The reminders' settings: how many minutes before a show, and whether the TV switches by itself. In a file, not
     * the preferences: Live TV sets them, the main process (the alarm, the card) reads them.
     */
    static int remindLead(Context c) {
        String[] s = remindFile(c);
        try {
            return Integer.parseInt(s[0]);
        } catch (RuntimeException e) {
            return 1;
        }
    }

    static boolean remindAuto(Context c) {
        String[] s = remindFile(c);
        return s.length > 1 && s[1].equals("auto");
    }

    static void setRemind(Context c, int lead, boolean auto) {
        try {
            Xtream.write(new java.io.File(c.getApplicationContext().getFilesDir(), "live-remind.txt"), lead + " " + (auto ? "auto" : "ask"));
        } catch (java.io.IOException e) {
            AppLog.w("live: couldn't save the reminder settings");
        }
    }

    private static String[] remindFile(Context c) {
        java.io.File f = new java.io.File(c.getApplicationContext().getFilesDir(), "live-remind.txt");
        if (!f.exists()) return new String[0];
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            return Xtream.read(in).trim().split(" ");
        } catch (java.io.IOException e) {
            return new String[0];
        }
    }

    static int step(int[] values, int now, int dir) {
        int at = 0;
        for (int i = 0; i < values.length; i++) if (values[i] == now) at = i;
        return values[(at + dir + values.length) % values.length];
    }

    // MARK: - Starting by itself

    // Read by the home screen's process (it starts Live TV), so these are files rather than the preferences above.

    /** Live previews on the home screen's On now row (off: the file live-no-previews). */
    /** Country tags beside channel names (LiveCountry): on unless turned off. Any process. */
    static boolean countryTags(Context c) {
        return !flag(c, "live-no-country-tags").exists();
    }

    static void setCountryTags(Context c, boolean on) {
        setFlag(c, "live-no-country-tags", !on);
        LiveCountry.changed(on);
    }

    /** "Play TLC on Glassio Yahsun" and Google TV's search open Live TV on a channel (LiveVoice): on unless off. */
    static boolean voiceChannels(Context c) {
        return !flag(c, "live-no-voice").exists();
    }

    static void setVoiceChannels(Context c, boolean on) {
        setFlag(c, "live-no-voice", !on);
        LiveVoice.apply(c);
    }

    static boolean homePreviews(Context c) {
        return !flag(c, "live-no-previews").exists();
    }

    static void setHomePreviews(Context c, boolean on) {
        setFlag(c, "live-no-previews", !on);
    }

    /**
     * What's followed (LiveFollow) alerts for: live airings only (the guide marks them live), or every airing; all: the
     * file live-follow-all (read by the alarms' process too).
     */
    static boolean followAll(Context c) {
        return flag(c, "live-follow-all").exists();
    }

    static void setFollowAll(Context c, boolean all) {
        setFlag(c, "live-follow-all", all);
    }

    /** Match alerts as the small card (else the panel of every channel showing the match). Read by ReminderActivity. */
    static boolean matchCard(Context c) {
        return flag(c, "live-match-card").exists();
    }

    static void setMatchCard(Context c, boolean card) {
        setFlag(c, "live-match-card", card);
    }

    /** A reminder for the channel already on in Live TV still shows (by default it doesn't). Read by ReminderActivity. */
    static boolean remindWatching(Context c) {
        return flag(c, "live-remind-watching").exists();
    }

    static void setRemindWatching(Context c, boolean on) {
        setFlag(c, "live-remind-watching", on);
    }

    /** isLocked, read fresh from another process (the home screen's previews: Live TV may have just changed it). */
    @SuppressWarnings("deprecation")
    static boolean isLockedAnyProcess(Context c, String category) {
        SharedPreferences p = c.getApplicationContext().getSharedPreferences("live-options", Context.MODE_MULTI_PROCESS);
        return !p.getString("pin", "").isEmpty() && category != null
                && p.getStringSet("locked", new java.util.HashSet<>()).contains(category);
    }

    /** Live TV opens when the TV starts. */
    static boolean startOnBoot(Context c) {
        return flag(c, "live-start-boot").exists();
    }

    static void setStartOnBoot(Context c, boolean on) {
        setFlag(c, "live-start-boot", on);
    }

    /** Live TV opens when the TV wakes up from standby. */
    /** Whether Live TV asks who's watching when it opens (with more than one profile). */
    static boolean askProfile(Context c) {
        return sp(c).getBoolean("askProfile", false);
    }

    static void setAskProfile(Context c, boolean on) {
        sp(c).edit().putBoolean("askProfile", on).apply();
    }

    /** Whether Live TV opens on its Home (rows of what's on) rather than straight on the last channel. */
    static boolean homeOnStart(Context c) {
        return sp(c).getBoolean("homeOnStart", false);
    }

    static void setHomeOnStart(Context c, boolean on) {
        sp(c).edit().putBoolean("homeOnStart", on).apply();
    }

    static boolean startOnWake(Context c) {
        return flag(c, "live-start-wake").exists();
    }

    static void setStartOnWake(Context c, boolean on) {
        setFlag(c, "live-start-wake", on);
    }

    /** Live scores for the teams followed (on unless turned off). Read by the match panel's process too. */
    static boolean scores(Context c) {
        return !flag(c, "live-scores-off").exists();
    }

    static void setScores(Context c, boolean on) {
        setFlag(c, "live-scores-off", !on);
    }

    /** On a past show, the score and its moments stay hidden until asked for (on unless turned off). */
    static boolean spoilerGuard(Context c) {
        return !flag(c, "live-spoiler-guard-off").exists();
    }

    static void setSpoilerGuard(Context c, boolean on) {
        setFlag(c, "live-spoiler-guard-off", !on);
    }

    /** A card in a show's last two minutes with what's next (on unless turned off). */
    static boolean upNextCard(Context c) {
        return !flag(c, "live-up-next-off").exists();
    }

    static void setUpNextCard(Context c, boolean on) {
        setFlag(c, "live-up-next-off", !on);
    }

    /** A card when a followed team's match has a goal, while Live TV is on (on unless turned off). */
    static boolean goalAlerts(Context c) {
        return !flag(c, "live-goals-off").exists();
    }

    static void setGoalAlerts(Context c, boolean on) {
        setFlag(c, "live-goals-off", !on);
    }

    private static java.io.File flag(Context c, String name) {
        return new java.io.File(c.getApplicationContext().getFilesDir(), name);
    }

    private static void setFlag(Context c, String name, boolean on) {
        java.io.File f = flag(c, name);
        try {
            if (on) {
                //noinspection ResultOfMethodCallIgnored
                f.createNewFile();
            } else {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        } catch (java.io.IOException ignored) {
        }
    }

    // MARK: - Sorting

    static final int PROVIDER = 0, BY_NUMBER = 1, BY_NAME = 2;

    /** How the channels in each category are sorted: PROVIDER, BY_NUMBER or BY_NAME. */
    static int channelSort(Context c) {
        return sp(c).getInt("channelSort", PROVIDER);
    }

    static void setChannelSort(Context c, int s) {
        sp(c).edit().putInt("channelSort", s).apply();
    }

    private static Set<String> spoilerFree;
    private static long spoilerReadAt;

    /**
     * The followed teams (folded) whose scores stay hidden, for watching later. In a file, not the preferences: the home
     * screen's process sets it, Live TV's reads it too, so it's looked at again every few seconds.
     */
    static synchronized Set<String> spoilerFree(Context c) {
        long up = android.os.SystemClock.uptimeMillis();
        if (spoilerFree == null || up - spoilerReadAt > 3000) {
            spoilerReadAt = up;
            Set<String> read = new java.util.HashSet<>();
            File f = new File(c.getFilesDir(), "live-spoiler-free");
            if (f.exists()) {
                try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                    read.addAll(activeSpoilerFree(Xtream.read(in), System.currentTimeMillis()));
                } catch (java.io.IOException | RuntimeException ignored) {
                }
            }
            spoilerFree = read;
        }
        return spoilerFree;
    }

    /** The teams of the file's lines ("team" or "team|when it ends") that haven't ended. Pure. */
    static Set<String> activeSpoilerFree(String file, long now) {
        Set<String> out = new java.util.HashSet<>();
        for (String line : file.split("\n")) {
            String l = line.trim();
            int bar = l.lastIndexOf('|');
            long until = 0;
            if (bar > 0) {
                try {
                    until = Long.parseLong(l.substring(bar + 1));
                } catch (NumberFormatException ignored) {
                }
                l = l.substring(0, bar);
            }
            if (!l.isEmpty() && (until == 0 || until > now)) out.add(l);
        }
        return out;
    }

    static synchronized void setSpoilerFree(Context c, String folded, boolean on, long until) {
        Set<String> now = new java.util.HashSet<>(spoilerFree(c));
        if (on) now.add(folded);
        else now.remove(folded);
        File f = new File(c.getFilesDir(), "live-spoiler-free");
        try {
            StringBuilder b = new StringBuilder();
            String old = "";
            if (f.exists()) {
                try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                    old = Xtream.read(in);
                }
            }
            // The others keep when they end.
            for (String line : old.split("\n")) {
                int bar = line.lastIndexOf('|');
                String team = (bar > 0 ? line.substring(0, bar) : line).trim();
                if (!team.isEmpty() && !team.equals(folded) && now.contains(team)) b.append(line.trim()).append('\n');
            }
            if (on) b.append(folded).append(until > 0 ? "|" + until : "").append('\n');
            Xtream.write(f, b.toString());
        } catch (java.io.IOException e) {
            AppLog.w("live: couldn't save the spoiler-free teams");
        }
        spoilerFree = now;
        spoilerReadAt = android.os.SystemClock.uptimeMillis();
    }

    /** How the provider's categories are sorted: PROVIDER or BY_NAME. */
    static int categorySort(Context c) {
        return sp(c).getInt("categorySort", PROVIDER);
    }

    static void setCategorySort(Context c, int s) {
        sp(c).edit().putInt("categorySort", s).apply();
    }

    // MARK: - Merging similar channels

    /** Channels of the same name in other qualities (HD, FHD, 4K, HEVC…) as one, playing the best the connection carries. */
    static boolean merge(Context c) {
        return sp(c).getBoolean("merge", true);
    }

    static void setMerge(Context c, boolean on) {
        sp(c).edit().putBoolean("merge", on).apply();
    }

    /** The merged channels shown as their sources again: their groups (LiveMerge). */
    static Set<String> separateGroups(Context c) {
        Set<String> out = new HashSet<>();
        for (String e : sp(c).getStringSet("separate", new HashSet<>())) out.add(e.substring(0, Math.max(0, e.indexOf('\t'))));
        return out;
    }

    /** The same, each as {group, its name} (the settings' list), by name. */
    static List<String[]> separate(Context c) {
        List<String[]> out = new ArrayList<>();
        for (String e : sp(c).getStringSet("separate", new HashSet<>())) {
            int tab = e.indexOf('\t');
            if (tab > 0) out.add(new String[] {e.substring(0, tab), e.substring(tab + 1)});
        }
        java.util.Collections.sort(out, (a, b) -> a[1].compareToIgnoreCase(b[1]));
        return out;
    }

    static void setSeparate(Context c, String group, String name, boolean on) {
        Set<String> s = new HashSet<>();
        for (String e : sp(c).getStringSet("separate", new HashSet<>())) if (!e.startsWith(group + "\t")) s.add(e);
        if (on) s.add(group + "\t" + name);
        sp(c).edit().putStringSet("separate", s).commit();     // now: the list is read again right after
    }

    // MARK: - Categories

    static Set<String> hidden(Context c) {
        return new HashSet<>(sp(c).getStringSet("hidden", new HashSet<>()));
    }

    static void setHidden(Context c, String category, boolean on) {
        Set<String> s = hidden(c);
        if (on) s.add(category); else s.remove(category);
        sp(c).edit().putStringSet("hidden", s).apply();
    }

    static Set<String> locked(Context c) {
        return new HashSet<>(sp(c).getStringSet("locked", new HashSet<>()));
    }

    /** Whether a channel of category `id` asks for the PIN (only while there is one). */
    static boolean isLocked(Context c, String category) {
        return hasPin(c) && category != null && locked(c).contains(category);
    }

    static void setLocked(Context c, String category, boolean on) {
        Set<String> s = locked(c);
        if (on) s.add(category); else s.remove(category);
        sp(c).edit().putStringSet("locked", s).apply();
    }

    // MARK: - The parental PIN

    /** Shows rated at this age or older ask for the PIN (0: off). */
    static final int[] AGE_LOCKS = {0, 12, 14, 16, 18};

    static int ageLock(Context c) {
        return sp(c).getInt("ageLock", 0);
    }

    static void setAgeLock(Context c, int age) {
        sp(c).edit().putInt("ageLock", age).apply();
    }

    static boolean hasPin(Context c) {
        return !sp(c).getString("pin", "").isEmpty();
    }

    /** A new PIN, or null to turn the lock off. */
    static void setPin(Context c, String pin) {
        if (pin == null) {
            sp(c).edit().remove("pin").remove("salt").apply();
            return;
        }
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        String s = Base64.encodeToString(salt, Base64.NO_WRAP);
        sp(c).edit().putString("salt", s).putString("pin", hash(s, pin)).apply();
    }

    static boolean checkPin(Context c, String pin) {
        String want = sp(c).getString("pin", "");
        return !want.isEmpty() && MessageDigest.isEqual(want.getBytes(StandardCharsets.UTF_8),
                hash(sp(c).getString("salt", ""), pin).getBytes(StandardCharsets.UTF_8));
    }

    private static String hash(String salt, String pin) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            d.update(salt.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(d.digest(pin.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Signed out: these were for that provider. */
    static void clear(Context c) {
        sp(c).edit().clear().apply();
    }

    /** The account's own settings (its categories, its guide's link), signing out of it; the rest stays. */
    static void clearAccount(Context c) {
        sp(c).edit().remove("hidden").remove("locked").remove("separate").remove("guideUrl").remove("fullGuide").apply();
    }
}
