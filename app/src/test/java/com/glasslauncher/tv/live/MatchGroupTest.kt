package com.glasslauncher.tv.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchGroupTest {
    private val united = "manchester united"
    private fun at(h: Int, m: Int) = (h * 60L + m) * 60_000L
    private fun g(title: String, h: Int, m: Int) = LiveMatchGroup.of(title, at(h, m))!!

    private val match = g("UEFA Champions League Football : Sporting CP v Manchester United", 14, 0)

    @Test fun oneMatchWhateverItsCalled() {
        for (o in listOf(
            g("Match Day Live : UEFA Champions League: Sporting CP v Manchester United", 14, 0),
            g("Sporting - Manchester UTD : Sporting Lisbona - Manchester United", 14, 30),
            g("Futebol - UEFA Champions League : Sporting CP x Manchester United", 14, 50),
            g("UEFA Champions League - Estúdio - Antevisão : Sporting CP x Manchester United", 13, 0),
        )) assertTrue(o.pair, match.same(o, united))
    }

    @Test fun anotherSpellingOfTheOtherSideIsTheSameMatchForATeamKnown() {
        val o = g("Futebol : Desportivo Leonino x Manchester United", 14, 30)
        assertTrue(match.same(o, united))
        assertFalse(match.same(o, null))
    }

    @Test fun theYouthMatchIsItsOwn() {
        val a = g("Fútbol UEFA Youth League : Sporting CP U19 vs. Manchester United U19", 7, 45)
        assertTrue(a.youth)
        assertTrue(a.same(g("UEFA Youth League 2026/27 : Sporting vs Man United - MD5", 7, 55), united))
        assertTrue(a.same(g("UEFA Youth League Football : Sporting CP U19 v Manchester United U19", 8, 55), united))
        assertFalse(g("UEFA Youth League Football : Sporting CP U19 v Manchester United U19", 12, 0).same(match, united))
    }

    @Test fun theWomensIsItsOwnAndAnotherDaysToo() {
        assertFalse(g("WSL : Manchester United Women v Chelsea Women", 14, 30).same(match, united))
        assertFalse(g("Sporting CP v Manchester United", 20, 0).same(match, united))
        assertFalse(g("Premier League : Arsenal v Chelsea", 14, 0).same(match, united))
    }

    @Test fun aRerunTheSameDay() {
        assertTrue(g("UEFA Champions League Football : Sporting CP v Manchester United", 20, 30).rerunOf(match))
        assertFalse(match.rerunOf(g("Futebol : Sporting CP v Manchester United", 8, 0)))
        assertFalse(match.rerunOf(g("UEFA Youth League : Sporting CP U19 v Manchester United U19", 7, 55)))
        assertFalse(g("Sporting CP v Manchester United", 14 + 24, 0).rerunOf(match))
        assertFalse(g("MLB Baseball : Yankees v Red Sox", 19, 0).rerunOf(g("MLB Baseball : Yankees v Red Sox", 13, 0)))
    }

    @Test fun shownAgainUnderAnotherTitle() {
        val day = List(4) { g("Champions League : Sporting CP v Manchester United", 14, it * 10) }
        val mutv = g("UEFA Champions League Football : League Phase: Sporting CP v Manchester United", 19, 0)
        val unmarkedYouth = g("Futebol : Sporting CP x Manchester United", 8, 0)
        val all = day + mutv + unmarkedYouth
        assertTrue(mutv.shownAgain(all))
        assertFalse(day[0].shownAgain(all))
        assertFalse(mutv.shownAgain(listOf(mutv, unmarkedYouth)))
    }

    @Test fun itsReviewInTheEvening() {
        val review = g("Match Day Review : UEFA Champions League: Sporting CP v Manchester United", 18, 0)
        assertTrue(review.same(match, united))
        assertFalse(review.same(g("Sporting CP v Manchester United", 5, 0), united))
    }

    @Test fun shows() {
        assertTrue(g("UEFA Champions League - Estúdio - Antevisão : Sporting CP x Manchester United", 13, 0).show)
        assertFalse(match.show)
        assertNull(LiveMatchGroup.of("The Manchester United Story", 0))
        assertEquals(null, LiveHome.teamsKey("Match Day Review : Arsenal v Spurs"))
    }
}
