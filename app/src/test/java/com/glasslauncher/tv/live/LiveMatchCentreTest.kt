package com.glasslauncher.tv.live

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Match Centre from ESPN's match summary (the shapes copied from a real one). */
class LiveMatchCentreTest {
    private val summary = """{
      "header":{"league":{"name":"English Premier League","slug":"eng.1"},"competitions":[{
        "status":{"type":{"state":"post","shortDetail":"FT"}},
        "competitors":[
          {"homeAway":"away","score":"1","team":{"abbreviation":"COV","displayName":"Coventry City"}},
          {"homeAway":"home","score":"3","team":{"abbreviation":"EVE","displayName":"Everton"}}]}]},
      "boxscore":{"teams":[
        {"homeAway":"home","statistics":[
          {"name":"possessionPct","label":"Possession","displayValue":"49.8"},
          {"name":"totalShots","label":"SHOTS","displayValue":"16"},
          {"name":"shotsOnTarget","label":"ON GOAL","displayValue":"7"},
          {"name":"wonCorners","label":"Corner Kicks","displayValue":"2"},
          {"name":"accuratePasses","label":"Accurate Passes","displayValue":"344"}]},
        {"homeAway":"away","statistics":[
          {"name":"possessionPct","label":"Possession","displayValue":"50.2"},
          {"name":"totalShots","label":"SHOTS","displayValue":"9"},
          {"name":"shotsOnTarget","label":"ON GOAL","displayValue":"3"},
          {"name":"wonCorners","label":"Corner Kicks","displayValue":"5"}]}]},
      "rosters":[
        {"homeAway":"away","formation":"4-4-2","roster":[
          {"starter":true,"jersey":"1","athlete":{"displayName":"Keeper A","shortName":"K. A"},"position":{"abbreviation":"G"}}]},
        {"homeAway":"home","formation":"4-2-3-1","roster":[
          {"starter":true,"jersey":"1","athlete":{"displayName":"Jordan Pickford","shortName":"J. Pickford"},"position":{"abbreviation":"G"}},
          {"starter":false,"jersey":"12","athlete":{"displayName":"Sub B","shortName":"S. B"},"position":{"abbreviation":"SUB"}}]}],
      "keyEvents":[
        {"type":{"text":"Kickoff"},"clock":{"displayValue":""}},
        {"type":{"text":"Goal"},"clock":{"displayValue":"52'"},"team":{"displayName":"Coventry City"},"scoringPlay":true,
         "participants":[{"athlete":{"displayName":"Yann Gboho"}}]},
        {"type":{"text":"Goal - Header"},"clock":{"displayValue":"60'"},"team":{"displayName":"Everton"},"scoringPlay":true,
         "participants":[{"athlete":{"displayName":"Beto"}}]},
        {"type":{"text":"Yellow Card"},"clock":{"displayValue":"63'"},"team":{"displayName":"Everton"},
         "participants":[{"athlete":{"displayName":"James Garner"}}]},
        {"type":{"text":"Red Card"},"clock":{"displayValue":"70'"},"team":{"displayName":"Coventry City"},
         "participants":[{"athlete":{"displayName":"Liam Kelly"}}]},
        {"type":{"text":"Substitution"},"clock":{"displayValue":"73'"},"team":{"displayName":"Everton"},
         "participants":[{"athlete":{"displayName":"Jake O'Brien"}},{"athlete":{"displayName":"Merlin Rohl"}}]},
        {"type":{"text":"Start Delay"},"clock":{"displayValue":"47'"}}],
      "standings":{"groups":[{"standings":{"entries":[
        {"team":"Arsenal","id":"359","stats":[
          {"name":"rank","displayValue":"1"},{"name":"gamesPlayed","displayValue":"9"},{"name":"wins","displayValue":"8"},
          {"name":"ties","displayValue":"0"},{"name":"losses","displayValue":"1"},{"name":"pointDifferential","displayValue":"+9"},
          {"name":"points","displayValue":"24"}]},
        {"team":"Everton","id":"368","stats":[
          {"name":"rank","displayValue":"7"},{"name":"gamesPlayed","displayValue":"9"},{"name":"wins","displayValue":"4"},
          {"name":"ties","displayValue":"2"},{"name":"losses","displayValue":"3"},{"name":"pointDifferential","displayValue":"+2"},
          {"name":"points","displayValue":"14"}]}]}}]}
    }"""

    private val d get() = LiveMatchCentre.parse(JSONObject(summary))

    @Test fun `the two sides, whichever order they come in`() {
        assertEquals("EVE", d.home)
        assertEquals("COV", d.away)
        assertEquals(3, d.homeScore)
        assertEquals(1, d.awayScore)
        assertEquals("FT", d.clock)
        assertEquals("post", d.state)
        assertEquals("English Premier League", d.league)
    }

    @Test fun `the stats worth showing, in order, for both sides`() {
        val s = d.stats
        assertEquals("Possession", s[0].label)
        assertEquals("50%", s[0].home)
        assertEquals("50%", s[0].away)
        assertEquals("Shots", s[1].label)
        assertEquals("16", s[1].home)
        assertEquals("9", s[1].away)
    }

    @Test fun `a stat only one side has isn't shown`() {
        assertFalse(d.stats.any { it.label == "Accurate Passes" })
    }

    @Test fun `a stat's share of the bar`() {
        assertEquals(0.75f, LiveMatchCentre.share("3", "1"), 0.001f)
        assertEquals(0.5f, LiveMatchCentre.share("0", "0"), 0.001f)
        assertEquals(0.6f, LiveMatchCentre.share("60%", "40%"), 0.001f)
    }

    @Test fun `a stat neither side has is told apart from a level one`() {
        val none = LiveMatchCentre.Stat().apply { home = "0"; away = "0" }
        val level = LiveMatchCentre.Stat().apply { home = "2"; away = "2" }
        assertTrue(LiveMatchCentre.none(none))
        assertFalse(LiveMatchCentre.none(level))
    }

    @Test fun `each side's lineup goes to the right side`() {
        assertEquals("4-2-3-1", d.homeLine.formation)
        assertEquals("4-4-2", d.awayLine.formation)
        assertEquals("Jordan Pickford", d.homeLine.starters[0].name)
        assertEquals("1", d.homeLine.starters[0].number)
        assertEquals("G", d.homeLine.starters[0].position)
    }

    @Test fun `only the starters are in the lineup`() {
        assertEquals(1, d.homeLine.starters.size)
    }

    @Test fun `the timeline has goals, cards and changes, not kick-off or delays`() {
        assertEquals(listOf(LiveMatchCentre.GOAL, LiveMatchCentre.GOAL, LiveMatchCentre.YELLOW, LiveMatchCentre.RED, LiveMatchCentre.CHANGE), d.events.map { it.kind })
    }

    @Test fun `a timeline row says who and which side`() {
        assertEquals("52'", d.events[0].minute)
        assertEquals("Yann Gboho", d.events[0].who)
        assertFalse(d.events[0].home)
        assertTrue(d.events[1].home)
    }

    @Test fun `a change names who came on and who went off`() {
        assertEquals("Jake O'Brien ↔ Merlin Rohl", d.events[4].who)
    }

    @Test fun `the table's rows, with the two sides marked`() {
        val t = d.table
        assertEquals("Arsenal", t[0].team)
        assertEquals(listOf("1", "9", "8", "0", "1", "+9", "24"), listOf(t[0].rank, t[0].played, t[0].won, t[0].drawn, t[0].lost, t[0].difference, t[0].points))
        assertFalse(t[0].mine)
        assertTrue(t[1].mine)
    }

    @Test fun `only what there is becomes a section`() {
        val all = LiveMatchCentre.sections(d).toList()
        assertEquals(listOf(LiveMatchCentre.TIMELINE, LiveMatchCentre.STATS, LiveMatchCentre.LINEUPS, LiveMatchCentre.TABLE), all)
    }

    @Test fun `a cup match has no table section`() {
        val j = JSONObject(summary)
        j.remove("standings")
        assertFalse(LiveMatchCentre.sections(LiveMatchCentre.parse(j)).contains(LiveMatchCentre.TABLE))
    }

    @Test fun `before kick-off there may be no sections, and nothing breaks`() {
        val j = JSONObject("""{"header":{"competitions":[{"status":{"type":{"state":"pre","shortDetail":"3:00 PM"}},"competitors":[]}]}}""")
        val p = LiveMatchCentre.parse(j)
        assertEquals("pre", p.state)
        assertEquals(0, LiveMatchCentre.sections(p).size)
    }

    @Test fun `a row list slides to keep the focus in view`() {
        assertEquals(0, LiveMatchCentre.top(0, 5, 20))
        assertEquals(0, LiveMatchCentre.top(1, 5, 3))
        assertEquals(15, LiveMatchCentre.top(99, 5, 20))
    }

    @Test fun `the table opens on the first of your two sides`() {
        assertEquals(1, LiveMatchCentre.firstMine(d.table))
    }
}
