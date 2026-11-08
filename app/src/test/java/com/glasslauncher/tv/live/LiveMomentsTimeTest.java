package com.glasslauncher.tv.live;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * Moments where they really happened: Manchester United 4–4 Aston Villa (8 Nov 2026), ESPN's scoreboard event and
 * its summary's key events as they were read that evening.
 */
public class LiveMomentsTimeTest {
    private static final long SHOW = LiveScores.when("2026-11-08T16:00:00Z");
    private static final String[] REAL = {"16:32:54", "16:35:35", "16:47:04", "17:15:35", "17:38:46", "17:43:26", "18:22:14", "18:26:55"};

    private static String read(String name) {
        InputStream in = LiveMomentsTimeTest.class.getClassLoader().getResourceAsStream("moments/" + name);
        try (Scanner s = new Scanner(in, StandardCharsets.UTF_8.name()).useDelimiter("\\A")) {
            return s.next();
        }
    }

    private static long at(String hms) {
        return LiveScores.when("2026-11-08T" + hms + "Z") - SHOW;
    }

    @Test
    public void everyGoalSitsWhereItHappened() throws Exception {
        LiveMoments.Match m = LiveMoments.parse(new JSONObject(read("mun-avl-event.json")), SHOW,
                new JSONArray(read("mun-avl-keyevents.json")), 0);
        assertEquals(8, m.moments.size());
        for (int i = 0; i < REAL.length; i++) assertEquals("goal " + (i + 1), at(REAL[i]), m.moments.get(i).at);
    }

    @Test
    public void timingMovesThemAllLater() throws Exception {
        LiveMoments.Match m = LiveMoments.parse(new JSONObject(read("mun-avl-event.json")), SHOW,
                new JSONArray(read("mun-avl-keyevents.json")), 30_000);
        assertEquals(at(REAL[0]) + 30_000, m.moments.get(0).at);
    }

    @Test
    public void withoutTheSummaryTheMinuteIsCloser() throws Exception {
        LiveMoments.Match m = LiveMoments.parse(new JSONObject(read("mun-avl-event.json")), SHOW, null, 0);
        // The 44th minute, from the scheduled 16:30 kick-off: 17:14, a minute and a half off (as before).
        assertEquals(at("17:14:00"), m.moments.get(3).at);
        // 90'+6': 15 minutes after the first half's 45, then 51 more: 18:21, not 18:36 as stoppage counted twice.
        assertEquals(at("18:21:00"), m.moments.get(7).at);
    }

    @Test
    public void theTimelineHasTheKickOffsAndEveryGoal() throws Exception {
        LiveMoments.Timeline t = LiveMoments.timeline(new JSONArray(read("mun-avl-keyevents.json")));
        assertEquals(LiveScores.when("2026-11-08T16:31:55Z"), t.kickOff);
        assertEquals(LiveScores.when("2026-11-08T17:36:10Z"), t.secondHalf);
        assertEquals(8, t.goals.size());
        assertEquals(0, t.reds.size());
    }

    @Test
    public void stoppageTimeStaysInItsHalf() {
        long ko = LiveScores.when("2026-11-08T16:30:00Z"), second = LiveScores.when("2026-11-08T17:36:00Z");
        assertArrayEquals(new int[] {45, 3}, LiveMoments.clock("45'+3'"));
        assertNull(LiveMoments.clock("HT"));
        assertEquals(LiveScores.when("2026-11-08T17:18:00Z"), LiveMoments.estimate(ko, second, "45'+3'"));
        assertEquals(LiveScores.when("2026-11-08T17:39:00Z"), LiveMoments.estimate(ko, second, "48'"));
        assertTrue(LiveMoments.estimate(ko, 0, "48'") < second);       // no second-half start known: 15 minutes' break
    }
}
