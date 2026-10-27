package com.glasslauncher.tv.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The season, number and title read from how guides write an episode line. */
class LiveEpisodeTest {
    private fun read(line: String) = LiveEpisode.of(line)?.let { Triple(it.season, it.episode, it.title) }

    @Test fun seasonAndEpisode() {
        assertEquals(Triple(5, 8, "Terror Tales of the Park III"), read("S05 E08 Terror Tales of the Park III"))
        assertEquals(Triple(5, 8, "Terror Tales"), read("S5E8 - Terror Tales"))
        assertEquals(Triple(1, 6, "Rick Potion No. 9"), read("S01 E06 Rick Potion No. 9"))
        assertEquals(Triple(3, 9, "Vae Victis"), read("s03e09: Vae Victis"))
        assertEquals(Triple(5, 8, "The Ghouls"), read("Season 5 Episode 8: The Ghouls"))
        assertEquals(Triple(5, 8, "The Ghouls"), read("Season 5, Episode 8 – The Ghouls"))
        assertEquals(Triple(2, 14, "Título"), read("T2 E14 Título"))
        assertEquals(Triple(4, 3, "Die Folge"), read("Staffel 4 Folge 3 · Die Folge"))
        assertEquals(Triple(5, 8, "The Ghouls"), read("5x08 The Ghouls"))
    }

    @Test fun episodeOnly() {
        assertEquals(Triple(-1, 12, "Pilot"), read("Ep. 12 Pilot"))
        assertEquals(Triple(-1, 3, ""), read("Episode 3"))
        assertEquals(Triple(1, 6, ""), read("S01 E06"))
    }

    @Test fun titleOnly() {
        assertEquals(Triple(-1, -1, "Just Plane Beep"), read("Just Plane Beep"))
        assertEquals(Triple(-1, -1, "Super Dave Hall of Fame"), read("Super Dave Hall of Fame"))
        assertEquals(Triple(-1, -1, "Season Finale"), read("Season Finale"))
        assertEquals(Triple(-1, -1, "Everybody Loves Raymond"), read("Everybody Loves Raymond"))
        assertNull(read(""))
        assertNull(read("   "))
    }

    @Test fun labels() {
        assertEquals("S5 · E8", LiveEpisode.of("S05 E08 Terror Tales")!!.label())
        assertEquals("S0 · E1", LiveEpisode.of("S00E01 Special")!!.label())
        assertEquals("E12", LiveEpisode.of("Ep 12 Pilot")!!.label())
        assertEquals("", LiveEpisode.of("Just Plane Beep")!!.label())
    }

    @Test fun synopsisWithoutTheEpisodeLine() {
        val line = "S05 E08 Terror Tales of the Park III"
        assertEquals("Each of the park guys tries…", LiveEpisode.aboutWithout("$line\nEach of the park guys tries…", line))
        assertEquals("", LiveEpisode.aboutWithout(line, line))
        assertEquals("Other words.", LiveEpisode.aboutWithout("Other words.", line))
        assertEquals("S05 E08 Terror Tales of the Park III and more", LiveEpisode.aboutWithout("S05 E08 Terror Tales of the Park III and more", line))
    }

    @Test fun tmdbNamesCompareWithoutPunctuationOrCase() {
        assertEquals("the ghouls", LiveTmdbEpisodes.key("The Ghouls"))
        assertEquals("the ghouls", LiveTmdbEpisodes.key("  the  GHOULS! "))
        assertEquals("rick potion no 9", LiveTmdbEpisodes.key("Rick Potion No. 9"))
        assertEquals("", LiveTmdbEpisodes.key(null))
        assertEquals("", LiveTmdbEpisodes.key("…"))
    }

    /** A show's episodes as TMDB names them: season × 1000 + number. */
    private fun show(vararg eps: Pair<String, Int>) = eps.associate { LiveTmdbEpisodes.key(it.first) to it.second }

    private val bluey = show("Turtleboy" to 3015, "Onesies" to 3016, "Tradies" to 3017, "Housework" to 3013,
        "Sheepdog" to 3014, "Granny Mobile" to 2033, "The Show" to 1040, "Bin Night" to 1019)

    @Test fun tmdbMatchesTheSameName() {
        assertEquals(3013, LiveTmdbEpisodes.match(bluey, "Housework"))
        assertEquals(1019, LiveTmdbEpisodes.match(bluey, "Bin Night!"))
    }

    @Test fun tmdbMatchesAnyPartOfADoubleBill() {
        assertEquals(3013, LiveTmdbEpisodes.match(bluey, "Housework; Sheepdog; Explorers"))
        assertEquals(3016, LiveTmdbEpisodes.match(bluey, "Lost Hat; Onesies; Tradies"))
        assertEquals(2033, LiveTmdbEpisodes.match(bluey, "Granny Mobile / Space / Cubby"))
    }

    @Test fun tmdbMatchesLoosely() {
        assertEquals(3015, LiveTmdbEpisodes.match(bluey, "Turtle Boy; Onesies; Tradies"))
        assertEquals(1040, LiveTmdbEpisodes.match(bluey, "Show"))
        assertEquals(2033, LiveTmdbEpisodes.match(bluey, "Granny-Mobile"))
        val parts = show("The Return, Part 1" to 1009, "The Return, Part 2" to 1010)
        assertEquals(1010, LiveTmdbEpisodes.match(parts, "The Return Pt. 2"))
        assertEquals(LiveTmdbEpisodes.loose("Rock and Roll"), LiveTmdbEpisodes.loose("Rock & Roll"))
    }

    @Test fun tmdbMatchesASpellingSlip() {
        val gumball = show("The Ghouls" to 6025, "The Pact" to 6012, "The Anybody" to 6011)
        assertEquals(6025, LiveTmdbEpisodes.match(gumball, "The Ghoulss"))
        assertEquals(6011, LiveTmdbEpisodes.match(gumball, "The Anybodyy"))
    }

    @Test fun tmdbDoesNotGuess() {
        val parts = show("The Return, Part 1" to 1009, "The Return, Part 2" to 1010)
        assertNull(LiveTmdbEpisodes.match(parts, "The Return Part 3"))      // the numbers differ
        assertNull(LiveTmdbEpisodes.match(bluey, "Explorers"))
        assertNull(LiveTmdbEpisodes.match(bluey, "Sheep"))                 // too short to be near
        val close = show("Night Owls" to 1001, "Night Owl" to 1002, "Night Bowl" to 1003)
        assertNull(LiveTmdbEpisodes.match(close, "Night Owlz"))            // two are as close
        assertNull(LiveTmdbEpisodes.match(emptyMap(), "Housework"))
        assertNull(LiveTmdbEpisodes.match(bluey, ""))
    }
}
