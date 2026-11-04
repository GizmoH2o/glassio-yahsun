package com.glasslauncher.tv.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveNamesTest {
    private fun names(term: String, vararg mine: String) = LiveNames.of(Xtream.fold(term), mine.toList())
    private fun LiveNames.Names.about(title: String) = `in`(Xtream.fold(title))

    @Test fun `no name is in the table twice`() {
        assertEquals(emptyList<String>(), LiveNames.repeated())
    }

    @Test fun `a team is found under its other names, either way round`() {
        val full = names("Manchester United")
        assertTrue(full.about("Man Utd v Arsenal"))
        assertTrue(full.about("Premier League : Man. Utd v Arsenal"))
        assertTrue(full.about("Arsenal v Man United"))
        assertTrue(full.about("Manchester United v AS Roma"))
        assertTrue(names("Man Utd").about("Manchester United v AS Roma"))
        assertTrue(names("Manchester United FC").about("Man Utd v Arsenal"))
    }

    @Test fun `a team's words spread over a title are not the team`() {
        assertFalse(names("Manchester United").about("Manchester City v West Ham United"))
        assertFalse(names("Man City").about("Man Utd v Leicester City"))
        assertFalse(names("Manchester United").about("Man City v Arsenal"))
    }

    @Test fun `a shared name doesn't catch the other team`() {
        val spurs = names("Tottenham Hotspur")
        assertTrue(spurs.about("Arsenal v Spurs"))
        assertFalse(spurs.about("San Antonio Spurs v Boston Celtics"))
        assertFalse(names("Rangers FC Glasgow").about("New York Rangers v Boston Bruins"))
        assertTrue(names("Glasgow Rangers").about("Rangers v Celtic"))
        assertFalse(names("Glasgow Rangers").about("New York Rangers v Boston Bruins"))
        assertFalse(names("AC Milan").about("Inter Milan v Juventus"))
        assertTrue(names("AC Milan").about("AC Milan v Inter Milan"))
        assertFalse(names("AS Monaco").about("Formula 1: Monaco Grand Prix"))
        assertFalse(names("Inter Milan").about("Inter Miami v Orlando City"))
    }

    @Test fun `following a shared name alone stays as typed`() {
        val spurs = names("Spurs")
        assertTrue(spurs.about("San Antonio Spurs v Boston Celtics"))
        assertFalse(spurs.about("Tottenham v Arsenal"))
        assertEquals("spurs", LiveNames.key("Spurs"))
    }

    @Test fun `anything else still goes by its words`() {
        val f1 = names("Formula 1")
        assertTrue(f1.about("Formula 1: Monaco Grand Prix"))
        assertTrue(f1.about("Formula Racing 1"))
        assertFalse(f1.about("Formula E"))
        assertFalse(names("").about("Anything"))
    }

    @Test fun `names added by hand count, as whole phrases`() {
        val team = names("Wrexham", "the dragons", "wafc")
        assertTrue(team.about("Wrexham v Notts County"))
        assertTrue(team.about("WAFC v Notts County"))
        assertTrue(team.about("The Dragons v Notts County"))
        assertFalse(team.about("The Year of Dragons"))
    }

    @Test fun `a typed list becomes names`() {
        assertEquals(listOf("man u", "mufc"), LiveNames.parse(" Man. U , MUFC, man u, ,x"))
        assertEquals(emptyList<String>(), LiveNames.parse(""))
    }

    @Test fun `every name of a team is one key`() {
        assertEquals(LiveNames.key("Manchester United"), LiveNames.key("Man Utd"))
        assertEquals(LiveNames.key("Manchester United"), LiveNames.key("Manchester United FC"))
        assertNotEquals(LiveNames.key("Manchester United"), LiveNames.key("Manchester City"))
        assertEquals("some team", LiveNames.key("Some Team"))
    }

    @Test fun `both listings of a match are one match`() {
        assertEquals(LiveHome.teamsKey("Manchester United v AS Roma"), LiveHome.teamsKey("Man Utd v Roma"))
        assertEquals(LiveHome.teamsKey("Champions League : Man Utd v Roma"), LiveHome.teamsKey("Roma v Manchester United"))
        assertNotEquals(LiveHome.teamsKey("Man Utd v Roma"), LiveHome.teamsKey("Man City v Roma"))
    }

    @Test fun `the scoreboard's name for a team is found`() {
        val team = names("Man Utd")
        assertTrue(team.isTeam("manchester united", "manchester united"))
        assertTrue(team.isTeam("man united", "manchester united"))
        assertFalse(team.isTeam("manchester city", "manchester city"))
        assertFalse(names("Glasgow Rangers").isTeam("rangers", "new york rangers"))
        assertTrue(names("Glasgow Rangers").isTeam("rangers", "rangers"))
        assertTrue(names("Arsenal").isTeam("arsenal", "arsenal"))
    }
}
