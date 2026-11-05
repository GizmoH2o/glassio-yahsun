package com.glasslauncher.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which catalogs stay selected as the add-on list changes: what's gone goes, what's only late stays. */
class CatalogChoiceTest {
    private val a = "https://a.example/manifest.json"
    private val b = "https://b.example/manifest.json"
    private val gone = "https://gone.example/token1/manifest.json"
    private fun cat(addon: String, type: String, id: String) = CatalogRef(addon, addon, type, id, id)

    @Test fun `selected catalogs that answered stay as they are`() {
        val all = listOf(cat(a, "movie", "top"), cat(a, "series", "top"))
        val got = CatalogChoice.of(setOf("$a|movie|top"), all, listOf(a))
        assertEquals(setOf("$a|movie|top"), got.selected)
        assertTrue(got.waiting.isEmpty())
    }

    @Test fun `an add-on gone from the list loses its selections and Home gets the usual first picks`() {
        // What happened on the TV: every selection on an add-on the account no longer has.
        val all = listOf(cat(a, "movie", "top"), cat(a, "series", "top"), cat(a, "channel", "tv"))
        val got = CatalogChoice.of(setOf("$gone|movie|one", "$gone|series|two"), all, listOf(a))
        assertEquals(setOf("$a|movie|top", "$a|series|top"), got.selected)
        assertTrue(got.waiting.isEmpty())
    }

    @Test fun `the first picks stop at eight`() {
        val all = (1..12).map { cat(a, "movie", "m$it") }
        assertEquals(8, CatalogChoice.of(setOf("$gone|movie|one"), all, listOf(a)).selected.size)
    }

    @Test fun `one gone selection leaves the others alone, with no new picks`() {
        val all = listOf(cat(a, "movie", "top"), cat(a, "series", "top"))
        assertEquals(setOf("$a|series|top"), CatalogChoice.of(setOf("$a|series|top", "$gone|movie|one"), all, listOf(a)).selected)
    }

    @Test fun `an add-on that moved to a new address keeps its catalogs`() {
        val moved = "https://gone.example/token2/manifest.json"
        val got = CatalogChoice.of(setOf("$gone|movie|one"), listOf(cat(moved, "movie", "one"), cat(a, "movie", "top")), listOf(moved, a))
        assertEquals(setOf("$moved|movie|one"), got.selected)
    }

    @Test fun `a listed add-on that didn't answer keeps its selections and is waited for`() {
        val got = CatalogChoice.of(setOf("$b|movie|top"), listOf(cat(a, "movie", "top")), listOf(a, b))
        assertEquals(setOf("$b|movie|top"), got.selected)
        assertEquals(setOf(b), got.waiting)
    }

    @Test fun `no add-on answering changes nothing, whatever the list says`() {
        val got = CatalogChoice.of(setOf("$gone|movie|one", "$a|movie|top"), emptyList(), listOf(a))
        assertEquals(setOf("$gone|movie|one", "$a|movie|top"), got.selected)
        assertEquals(setOf(a), got.waiting)
    }

    @Test fun `a catalog its add-on no longer offers goes`() {
        val got = CatalogChoice.of(setOf("$a|movie|old", "$a|movie|top"), listOf(cat(a, "movie", "top")), listOf(a))
        assertEquals(setOf("$a|movie|top"), got.selected)
    }

    @Test fun `nothing selected on a first run gets the first picks`() {
        assertEquals(setOf("$a|movie|top"), CatalogChoice.of(emptySet(), listOf(cat(a, "movie", "top")), listOf(a)).selected)
    }
}
