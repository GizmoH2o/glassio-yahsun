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
}
