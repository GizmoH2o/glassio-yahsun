package com.glasslauncher.tv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeRowsTest {
    @Test fun `the usual order with nothing saved`() {
        assertEquals("Airing", HomeRows.order("").first())
        assertEquals("Widgets", HomeRows.order("").last())
    }

    @Test fun `a row moves one place and stays at the ends`() {
        val moved = HomeRows.move("", "Watch", -1)
        assertEquals(listOf("Watch", "Airing"), HomeRows.order(moved).take(2))
        assertEquals("Watch", HomeRows.order(HomeRows.move(moved, "Watch", -1)).first())
    }

    @Test fun `new rows join and unknown ones go`() {
        val o = HomeRows.order("Apps,Gone")
        assertEquals("Apps", o.first())
        assertEquals(false, "Gone" in o)
        assertEquals(true, "Watch" in o)
    }

    @Test fun `a new row comes after the one it usually follows, not after your apps`() {
        val o = HomeRows.order("Airing,Watch,Col3,Apps,Widgets")
        assertEquals(true, o.indexOf("Mine1") in (o.indexOf("Col3") + 1) until o.indexOf("Apps"))
        assertEquals(o.size, o.distinct().size)
    }

    @Test fun `a row's own name, and back to the usual`() {
        val n = HomeRows.rename("{}", "Movies", " Films ")
        assertEquals("Films", HomeRows.name(n, "Movies"))
        assertEquals("", HomeRows.name(HomeRows.rename(n, "Movies", " "), "Movies"))
    }

    @Test fun inputsComeRightAfterAppsInAnOrderSavedBeforeThem() {
        val order = HomeRows.order("Apps,Watch,Airing")
        assertEquals(order.indexOf("Apps") + 1, order.indexOf("Inputs"))
        assertEquals("Inputs", HomeRows.title("Inputs"))
    }
}
