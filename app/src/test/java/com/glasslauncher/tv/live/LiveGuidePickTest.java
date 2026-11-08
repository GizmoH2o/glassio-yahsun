package com.glasslauncher.tv.live;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/** Choosing a channel's guide by hand: the suggestions and the search. */
public class LiveGuidePickTest {
    private final List<String[]> all = Arrays.asList(
            new String[] {"bbc1.uk", "BBC One", "Provider's guide"},
            new String[] {"bbc1scot.uk", "BBC One Scotland", "epg.example.com"},
            new String[] {"bbc2.uk", "BBC Two", "Provider's guide"},
            new String[] {"itv1.uk", "ITV 1", "Provider's guide"},
            new String[] {"sky.sports.main", "Sky Sports Main Event", "epg.example.com"});

    @Test
    public void theSameNameComesFirst() {
        List<String[]> s = LiveGuideMatch.suggest("UK: BBC One HD", all, 5);
        assertEquals("bbc1.uk", s.get(0)[0]);
        assertEquals("bbc1scot.uk", s.get(1)[0]);
    }

    @Test
    public void unrelatedNamesAreLeftOut() {
        for (String[] g : LiveGuideMatch.suggest("BBC One", all, 5)) assertTrue(g[0].startsWith("bbc"));
        assertEquals(0, LiveGuideMatch.suggest("Discovery", all, 5).size());
        assertEquals(0, LiveGuideMatch.suggest("", all, 5).size());
    }

    @Test
    public void itsOwnCountryFirst() {
        List<String[]> cinemax = Arrays.asList(
                new String[] {"cinemax.ar", "ARG: Cinemax", "Provider's guide"},
                new String[] {"cinemax.br", "BR: CINEMAX", "Provider's guide"},
                new String[] {"cinemax.us", "Cinemax", "Provider's guide"});
        assertEquals("cinemax.us", LiveGuideMatch.suggest("Cinemax Hits US", cinemax, 5).get(0)[0]);
        assertEquals("cinemax.br", LiveGuideMatch.suggest("BR: Cinemax", cinemax, 5).get(0)[0]);
    }

    @Test
    public void atMostMax() {
        assertEquals(1, LiveGuideMatch.suggest("BBC", all, 1).size());
    }

    @Test
    public void likenessIsHundredOnlyWhenTheSame() {
        assertEquals(100, LiveGuideMatch.likeness("bbc one", "bbc one"));
        assertTrue(LiveGuideMatch.likeness("bbc one", "bbc one scotland") < 100);
        assertEquals(0, LiveGuideMatch.likeness("", "bbc"));
    }

    @Test
    public void searchFindsNameOrId() {
        assertEquals(2, LiveView.guideSearch("bbc one", all, 10).size());
        assertEquals("sky.sports.main", LiveView.guideSearch("sky sports", all, 10).get(0)[0]);
        assertEquals("itv1.uk", LiveView.guideSearch("itv1", all, 10).get(0)[0]);
        assertEquals(0, LiveView.guideSearch("  ", all, 10).size());
    }
}
