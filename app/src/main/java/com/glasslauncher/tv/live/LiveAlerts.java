package com.glasslauncher.tv.live;

import android.content.Context;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/** The rules of the sports alerts beyond a kick-off: close games, what's offered, and what would spoil a recording. */
final class LiveAlerts {
    private LiveAlerts() {}

    /** A close game is told from this minute, with no more than one goal in it. */
    private static final int CLOSE_FROM = 80, CLOSE_MARGIN = 1;
    private static final long AHEAD = 60_000;

    /** Whether a match on now is close and late. Pure. */
    static boolean closeGame(String state, String clock, int home, int away) {
        return "in".equals(state) && LiveMoments.minuteOf(clock) >= CLOSE_FROM && Math.abs(home - away) <= CLOSE_MARGIN;
    }

    /** Whether any of the titles recording now (folded) is the match. Pure. */
    static boolean recording(List<String> titles, Predicate<String> isMatch) {
        for (String t : titles) if (isMatch.test(t)) return true;
        return false;
    }

    /** Whether to offer going to `channel` while `playing` is on (-1: none); never what's already on. Pure. */
    static boolean offer(int channel, int playing) {
        return channel >= 0 && channel != playing;
    }

    /** Whether a match of `term` (folded) is being recorded right now, so an alert would spoil it. */
    static boolean recording(Context c, String term) {
        if (!LiveOptions.alertsQuietRecording(c)) return false;
        List<String> titles = new ArrayList<>();
        for (LiveRecordings.Rec r : LiveRecordings.all(c)) if (LiveRecordings.RECORDING.equals(r.state) && r.title != null) titles.add(Xtream.fold(r.title));
        LiveNames.Names names = LiveNames.of(c, term);
        return recording(titles, names::in);
    }

    /**
     * Whether a title (folded) is this match and not just one with a similar name: it has to name the other side too,
     * by a word that side doesn't share with `own`. "UC San Diego vs CA Baptist" isn't San Diego Wave v Utah. Pure.
     */
    static boolean sameMatch(String title, String own, String other) {
        List<String> theirs = Arrays.asList(own.split("\\s+"));
        for (String word : other.split("\\s+")) if (word.length() >= 3 && !theirs.contains(word) && hasWord(title, word)) return true;
        return false;
    }

    private static boolean hasWord(String title, String word) {
        return (" " + title + " ").contains(" " + word + " ");
    }

    /**
     * The channel showing the match right now, or null: better no offer than the wrong channel. Blocking: it reads the
     * guide.
     */
    static Xtream.Channel channelFor(Context c, LiveScores.Score s) {
        long now = System.currentTimeMillis();
        String home = Xtream.fold(s.homeName), away = Xtream.fold(s.awayName);
        boolean homeIsMine = LiveNames.of(c, s.term).in(home);
        for (Object[] o : LiveHome.airings(c, Collections.singletonList(s.term), now, AHEAD, 8)) {
            Xtream.Program p = (Xtream.Program) o[1];
            if (p.start > now) continue;
            if (sameMatch(Xtream.fold(p.title), homeIsMine ? home : away, homeIsMine ? away : home)) return (Xtream.Channel) o[0];
        }
        return null;
    }
}
