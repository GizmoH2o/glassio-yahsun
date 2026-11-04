package com.glasslauncher.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LangTest {
    @Test fun `exact words and filled-in patterns`() {
        Lang.set("es", listOf("Home\tInicio", "Saved to Library · %s\tGuardado · %s", "Season %d\tTemporada %d", "%d of %d\t%d de %d"))
        assertEquals("Inicio", Lang.tr("Home"))
        assertEquals("Guardado · Dune", Lang.tr("Saved to Library · Dune"))
        assertEquals("Temporada 2", Lang.tr("Season 2"))
        assertEquals("3 de 10", Lang.tr("3 of 10"))
        Lang.set("en", emptyList())
    }

    @Test fun `numbers only fill number slots, so a title is left alone`() {
        Lang.set("es", listOf("%d of %d\t%d de %d", "Season %d\tTemporada %d"))
        assertEquals("Game of Thrones", Lang.tr("Game of Thrones"))
        assertEquals("Season Finale", Lang.tr("Season Finale"))
        Lang.set("en", emptyList())
    }

    @Test fun `what isn't in the table stays as it is`() {
        Lang.set("de", listOf("Home\tStart"))
        assertEquals("Unknown words", Lang.tr("Unknown words"))
        Lang.set("en", emptyList())
    }

    @Test fun `a weather word is apart from the same word elsewhere`() {
        Lang.set("es", listOf("weather:Clear\tDespejado", "Clear\tClaro"))
        assertEquals("Despejado", Lang.weather("Clear"))
        assertEquals("Claro", Lang.tr("Clear"))
        assertEquals("Hail", Lang.weather("Hail"))
        Lang.set("en", emptyList())
    }

    @Test fun `every table has the same slots as its English, and the same keys`() {
        val dir = File("src/main/assets/i18n")
        val tables = listOf("pt", "es", "de", "ru").associateWith { File(dir, "$it.txt").readLines().filter { l -> l.isNotBlank() } }
        val slot = Regex("%[sd]")
        for ((lang, lines) in tables) for (l in lines) {
            val (en, tr) = l.split('\t', limit = 2).let { it[0] to it[1] }
            assertEquals("$lang: $en", slot.findAll(en).count(), slot.findAll(tr).count())
            assertTrue("$lang: $en is blank", tr.isNotBlank())
        }
        val required = setOf("Home", "Settings", "Search", "Play", "Library", "Live TV", "Language")
        for ((lang, lines) in tables) {
            val keys = lines.map { it.substringBefore('\t') }.toSet()
            assertTrue("$lang is missing ${required - keys}", keys.containsAll(required))
        }
    }
}
