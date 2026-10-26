package com.glasslauncher.tv.live;

import android.content.Context;

/**
 * A followed team's match today, for the home screen and the screensaver: the follow reminders already know its
 * title, channel and time (nothing asked online); while it's on, LiveScores has the score. Any process.
 */
final class LiveMatchDay {
    private LiveMatchDay() {}

    static final class Match {
        String term = "", title = "";       // the followed words (folded); the show's title
        Xtream.Channel ch;
        long start, end;
    }

    private static final long HOUR = 3600_000L, AHEAD = 18 * HOUR, SOON = 30 * 60_000L;
    private static Match cached;
    private static long cachedAt;

    /** The soonest followed match not over yet, starting within 18 hours; null when none. Read again every minute. */
    static synchronized Match next(Context c) {
        long now = System.currentTimeMillis();
        if (now - cachedAt < 60_000) return cached != null && cached.end > now ? cached : null;
        cachedAt = now;
        cached = null;
        for (LiveSchedule.Item it : LiveSchedule.all(c)) {
            if (it.series == null || !it.series.startsWith(LiveFollow.SERIES) || it.ch == null) continue;
            if (it.end <= now || it.start > now + AHEAD) continue;
            if (cached != null && it.start >= cached.start) continue;
            Match m = new Match();
            m.term = it.series.substring(LiveFollow.SERIES.length());
            m.title = it.title;
            m.ch = it.ch;
            m.start = it.start;
            m.end = it.end;
            cached = m;
        }
        return cached;
    }

    /** On now, or starting within half an hour: the time to show its score and offer it. */
    static boolean soon(Match m, long now) {
        return m != null && now >= m.start - SOON && now < m.end;
    }

    /** Its score while it's on (Live scores), else null. */
    static LiveScores.Score score(Context c, Match m) {
        if (m == null || !LiveOptions.scores(c)) return null;
        LiveScores.Score s = LiveScores.get(c).forTerm(m.term);
        return s == null || s.state.equals("pre") ? null : s;
    }

    /** The first line: the score once it's on (MUN 1–0 BOU · 63'), else the match's title. */
    static String headline(Match m, LiveScores.Score s) {
        return s != null ? s.line() : teams(m.title);
    }

    /** The match without its competition ("Premier League : Man United v Bournemouth" → the teams). */
    static String teams(String title) {
        int at = title.lastIndexOf(':');
        String t = at > 0 ? title.substring(at + 1).trim() : title;
        return t.isEmpty() ? title : t;
    }

    /** The second line: LIVE (or the kick-off time) and the channel. */
    static String where(Match m, long now) {
        String ch = (m.ch.num > 0 ? m.ch.num + "  " : "") + m.ch.name;
        return (now >= m.start ? Ui.tr("AO VIVO", "LIVE") : Ui.time(m.start)) + "  ·  " + ch;
    }
}
