package com.glasslauncher.tv.live;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.json.JSONObject;
import org.junit.Test;

/** Match cards: a board's event read, a show's sides matched to its teams, and two colours told apart. */
public class LiveMatchCardsTest {
    private static final String EVENT = "{\"id\":\"1\",\"date\":\"2026-11-08T20:00Z\",\"status\":{\"type\":{\"state\":\"in\",\"shortDetail\":\"63'\"}},"
            + "\"competitions\":[{\"competitors\":["
            + "{\"homeAway\":\"home\",\"score\":\"2\",\"team\":{\"displayName\":\"Atalanta\",\"shortDisplayName\":\"Atalanta\",\"abbreviation\":\"ATA\","
            + "\"logo\":\"https://a/ata.png\",\"color\":\"1e71b8\",\"alternateColor\":\"000000\"}},"
            + "{\"homeAway\":\"away\",\"score\":\"1\",\"team\":{\"displayName\":\"Kairat Almaty\",\"shortDisplayName\":\"Kairat\",\"abbreviation\":\"KAI\","
            + "\"logo\":\"https://a/kai.png\",\"color\":\"ffd700\",\"alternateColor\":\"000000\"}}]}]}";

    @Test
    public void readsTheTeamsAndTheScore() throws Exception {
        LiveMatchCards.Match m = LiveMatchCards.read(new JSONObject(EVENT));
        assertNotNull(m);
        assertEquals("Atalanta", m.homeName);
        assertEquals("https://a/kai.png", m.awayLogo);
        assertEquals(0xFF1E71B8, m.homeColor);
        assertEquals(0xFFFFD700, m.awayColor);
        assertEquals(2, m.homeScore);
        assertEquals("in", m.state);
        assertTrue(m.drawable());
    }

    @Test
    public void aShowIsTheMatchEitherWayRound() throws Exception {
        LiveMatchCards.Match m = LiveMatchCards.read(new JSONObject(EVENT));
        assertTrue(LiveMatchCards.is(m, "atalanta", "kairat"));
        assertTrue(LiveMatchCards.is(m, "kairat almaty", "atalanta"));
        assertFalse(LiveMatchCards.is(m, "atalanta", "inter"));
    }

    @Test
    public void namesMatchWholeWordsOnly() {
        assertTrue(LiveMatchCards.named("manchester united", Arrays.asList("manchester united", "man utd")));
        assertTrue(LiveMatchCards.named("kairat", Arrays.asList("kairat almaty")));
        assertFalse(LiveMatchCards.named("man", Arrays.asList("manchester city")));
        assertFalse(LiveMatchCards.named("", Arrays.asList("arsenal")));
    }

    @Test
    public void coloursAreToldApart() {
        assertEquals(0xFF112233, LiveMatchCards.colour("#112233"));
        assertEquals(0, LiveMatchCards.colour("nope"));
        // Two reds: the away team's second colour.
        int[] two = LiveMatchCards.distinct(0xFFC8102E, 0xFFFFFFFF, 0xFFD00020, 0xFF0A2240);
        assertEquals(0xFFC8102E, two[0]);
        assertEquals(0xFF0A2240, two[1]);
        // Juventus: black, so its second colour (white), where its dark crest shows.
        assertEquals(0xFFFFFFFF, LiveMatchCards.distinct(0xFF482E92, 0xFFFFFFFF, 0xFF000000, 0xFFFFFFFF)[1]);
        // None given: greys that differ.
        int[] none = LiveMatchCards.distinct(0, 0, 0, 0);
        assertTrue(none[0] != none[1]);
    }
}
