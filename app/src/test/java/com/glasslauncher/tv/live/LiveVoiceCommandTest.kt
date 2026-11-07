package com.glasslauncher.tv.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** Voice commands beyond a channel: a game by its team, what's on tonight, recording and reminders. */
class LiveVoiceCommandTest {
    private fun read(said: String) = LiveVoiceCommand.parse(said)?.let { Triple(it.kind, it.team, it.other) }
    private fun kind(said: String) = LiveVoiceCommand.parse(said)?.kind
    private val G = LiveVoiceCommand.GAME

    // ── a game by its team

    @Test fun `put a team's game on`() {
        assertEquals(Triple(G, "arsenal", ""), read("Put the Arsenal game on"))
        assertEquals(Triple(G, "arsenal", ""), read("put on the Arsenal match"))
        assertEquals(Triple(G, "arsenal", ""), read("watch the Arsenal game"))
        assertEquals(Triple(G, "man utd", ""), read("Play the Man Utd game"))
        assertEquals(Triple(G, "arsenal", ""), read("the Arsenal game"))          // Google's "play … on Glassio" leaves this
        assertEquals(Triple(G, "lakers", ""), read("put the Lakers game on now"))
    }

    @Test fun `both teams`() {
        assertEquals(Triple(G, "leeds", "tottenham"), read("put on Leeds v Tottenham"))
        assertEquals(Triple(G, "leeds united", "tottenham"), read("watch Leeds United vs. Tottenham"))
        assertEquals(Triple(G, "flamengo", "palmeiras"), read("assistir Flamengo x Palmeiras"))
        assertEquals(Triple(G, "celtic", "rangers"), read("Celtic against Rangers game"))
    }

    @Test fun `in the other languages`() {
        assertEquals(Triple(G, "flamengo", ""), read("coloca o jogo do Flamengo"))
        assertEquals(Triple(G, "real madrid", ""), read("pon el partido del Real Madrid"))
        assertEquals(Triple(G, "bayern", ""), read("zeig das Spiel von Bayern"))
        assertEquals(Triple(G, "psg", ""), read("mets le match du PSG"))
        assertEquals(Triple(G, "juventus", ""), read("metti la partita della Juventus"))
        assertEquals(Triple(G, "спартак", ""), read("включи матч Спартак"))
    }

    @Test fun `a channel isn't a game`() {
        assertNull(read("watch TLC"))
        assertNull(read("put on BBC One"))
        assertNull(read("Game of Thrones"))
        assertNull(read("watch the game show network"))
        assertNull(read("match of the day"))
        assertNull(read("watch the game"))
    }

    // ── what's on

    @Test fun `what's on tonight, tomorrow night, now`() {
        assertEquals(LiveVoiceCommand.TONIGHT, kind("What's on tonight?"))
        assertEquals(LiveVoiceCommand.TONIGHT, kind("anything good on tonight"))
        assertEquals(LiveVoiceCommand.TOMORROW, kind("what's on tomorrow night"))
        assertEquals(LiveVoiceCommand.NOW, kind("what's on now"))
        assertEquals(LiveVoiceCommand.NOW, kind("what is on right now"))
        assertEquals(LiveVoiceCommand.TONIGHT, kind("o que passa hoje à noite"))
        assertEquals(LiveVoiceCommand.TONIGHT, kind("qué hay esta noche"))
        assertEquals(LiveVoiceCommand.TONIGHT, kind("was kommt heute Abend"))
        assertEquals(LiveVoiceCommand.TONIGHT, kind("qu'est-ce qu'il y a ce soir"))
        assertEquals(LiveVoiceCommand.TONIGHT, kind("cosa c'è stasera"))
        assertEquals(LiveVoiceCommand.TONIGHT, kind("что сегодня вечером"))
    }

    @Test fun `a show called tonight isn't the question`() {
        assertNull(kind("The Tonight Show"))
        assertNull(kind("watch Tonight with Trevor McDonald"))
    }

    // ── recording and reminders

    @Test fun `record this, record this series`() {
        assertEquals(LiveVoiceCommand.RECORD_THIS, kind("Record this"))
        assertEquals(LiveVoiceCommand.RECORD_THIS, kind("record this show"))
        assertEquals(LiveVoiceCommand.RECORD_THIS, kind("start recording"))
        assertEquals(LiveVoiceCommand.RECORD_SERIES, kind("Record this series"))
        assertEquals(LiveVoiceCommand.RECORD_SERIES, kind("record every episode"))
        assertEquals(LiveVoiceCommand.RECORD_SERIES, kind("gravar esta série"))
        assertEquals(LiveVoiceCommand.RECORD_THIS, kind("grava isso"))
        assertEquals(LiveVoiceCommand.RECORD_SERIES, kind("grabar esta serie"))
        assertEquals(LiveVoiceCommand.RECORD_THIS, kind("nimm das auf"))
        assertEquals(LiveVoiceCommand.RECORD_SERIES, kind("enregistrer cette série"))
        assertEquals(LiveVoiceCommand.RECORD_THIS, kind("registra questo"))
        assertEquals(LiveVoiceCommand.RECORD_THIS, kind("запиши это"))
    }

    @Test fun `remind or record a team's game`() {
        assertEquals(Triple(LiveVoiceCommand.REMIND_GAME, "arsenal", ""), read("Remind me about the Arsenal game"))
        assertEquals(Triple(LiveVoiceCommand.REMIND_GAME, "leeds", "spurs"), read("remind me of Leeds v Spurs"))
        assertEquals(Triple(LiveVoiceCommand.RECORD_GAME, "arsenal", ""), read("Record the Arsenal game"))
        assertEquals(Triple(LiveVoiceCommand.RECORD_GAME, "flamengo", ""), read("gravar o jogo do Flamengo"))
    }

    // ── when "tonight" is

    private val tz = TimeZone.getTimeZone("Europe/London")
    private fun at(day: Int, hour: Int, minute: Int = 0): Long = Calendar.getInstance(tz).apply {
        clear(); set(2026, Calendar.NOVEMBER, day, hour, minute)
    }.timeInMillis

    @Test fun `tonight is seven to eleven, from now once it's begun`() {
        assertEquals(listOf(at(7, 19), at(7, 23)), LiveVoiceCommand.window(LiveVoiceCommand.TONIGHT, at(7, 9), tz).toList())
        assertEquals(listOf(at(7, 20, 30), at(7, 23)), LiveVoiceCommand.window(LiveVoiceCommand.TONIGHT, at(7, 20, 30), tz).toList())
    }

    @Test fun `late at night, tonight is what's left of it`() {
        assertEquals(listOf(at(7, 23, 30), at(8, 2)), LiveVoiceCommand.window(LiveVoiceCommand.TONIGHT, at(7, 23, 30), tz).toList())
    }

    @Test fun `tomorrow night and now`() {
        assertEquals(listOf(at(8, 19), at(8, 23)), LiveVoiceCommand.window(LiveVoiceCommand.TOMORROW, at(7, 9), tz).toList())
        assertEquals(listOf(at(7, 9), at(7, 9) + 1), LiveVoiceCommand.window(LiveVoiceCommand.NOW, at(7, 9), tz).toList())
    }

    // ── which airing of a game

    private fun cand(title: String, start: Long, end: Long, score: Int = 0) = LiveVoiceCommand.Candidate(title, start, end, score)

    @Test fun `the match on now beats a show about the team`() {
        val now = at(7, 15)
        val list = listOf(cand("Arsenal: Inside Story", at(7, 14), at(7, 16)), cand("Premier League: Arsenal v Chelsea", at(7, 14, 30), at(7, 16, 30)))
        assertEquals(1, LiveVoiceCommand.pick(list, now))
    }

    @Test fun `on now beats later, the better channel wins a tie`() {
        val now = at(7, 15)
        val list = listOf(cand("Arsenal v Chelsea", at(8, 15), at(8, 17), 50),
                cand("Arsenal v Chelsea", at(7, 14, 30), at(7, 16, 30), 0),
                cand("Arsenal v Chelsea", at(7, 14, 30), at(7, 16, 30), 40))
        assertEquals(2, LiveVoiceCommand.pick(list, now))
    }

    @Test fun `none on, the next one`() {
        val now = at(7, 9)
        val list = listOf(cand("Arsenal v Chelsea", at(8, 15), at(8, 17)), cand("Arsenal v Chelsea", at(7, 15), at(7, 17)))
        assertEquals(1, LiveVoiceCommand.pick(list, now))
    }

    @Test fun `a day-long block of several matches loses to the team's own airing, even a later one`() {
        // Seen on the TV: 1305 Premier League TV's "Leeds United v Tottenham Hotspur / Arsenal v Hull City / …", 6 a.m. to 3 p.m.
        val now = at(7, 9)
        val list = listOf(cand("Leeds United v Tottenham Hotspur / Arsenal v Hull City", at(7, 6), at(7, 15), 40),
                cand("Premier League: Arsenal v Hull City", at(7, 12, 30), at(7, 14, 30)))
        assertEquals(1, LiveVoiceCommand.pick(list, now))
    }

    @Test fun `a team channel's next-game notice isn't the game`() {
        // Seen on the TV: the channel "Arsenal" (Sports | EPL Teams) with "Next Game: Hull City @ Arsenal on 2026-11-07".
        val now = at(7, 9)
        val list = listOf(cand("Next Game: Hull City @ Arsenal on 2026-11-07", at(7, 9), at(7, 10), 30),
                cand("Leeds United v Tottenham Hotspur / Arsenal v Hull City", at(7, 6), at(7, 15)))
        assertEquals(1, LiveVoiceCommand.pick(list, now))
        assertEquals(0, LiveVoiceCommand.pick(listOf(list[0]), now))       // alone, still something
    }

    @Test fun `with nothing better, the block is still something`() {
        val now = at(7, 9)
        assertEquals(0, LiveVoiceCommand.pick(listOf(cand("Leeds v Spurs / Arsenal v Hull", at(7, 6), at(7, 15))), now))
    }

    @Test fun `a game over isn't picked`() {
        assertEquals(-1, LiveVoiceCommand.pick(listOf(cand("Arsenal v Chelsea", at(7, 6), at(7, 8))), at(7, 9)))
    }
}
