package com.glasslauncher.tv.live

import org.junit.Assert.assertEquals
import org.junit.Test

class CategorySortTest {
    private fun sorted(vararg names: String) = names.sortedWith { a, b -> LiveCountry.compareCategories(a, b) }

    @Test fun `the same name goes by its country, A to Z`() {
        assertEquals(listOf("UK | Sports", "US | Sports"), sorted("US | Sports", "UK | Sports"))
    }

    @Test fun `names come first, wherever the country is written`() {
        assertEquals(
            listOf("UK | Films", "US | News", "UK | Sports", "US | Sports"),
            sorted("US | Sports", "US | News", "UK | Sports", "UK | Films"),
        )
    }

    @Test fun `a name with no country comes before the same name with one`() {
        assertEquals(listOf("Sports", "UK | Sports"), sorted("UK | Sports", "Sports"))
    }

    @Test fun `a name that starts another comes first, whatever their countries`() {
        assertEquals(listOf("US | News", "UK | News Extra"), sorted("UK | News Extra", "US | News"))
    }

    // LiveView sorts by the keys worked out once: the same order as comparing the names.
    @Test fun `sorting by the keys gives the same order as comparing`() {
        val names = listOf("US | Sports", "UK | News Extra", "Sports", "US | News", "UK | Sports", "UK | Films", "CA | News", "News")
        assertEquals(names.sortedWith { a, b -> LiveCountry.compareCategories(a, b) }, names.sortedBy { LiveCountry.categorySortKey(it) })
        assertEquals(listOf("News", "CA | News", "US | News", "UK | News Extra"), sorted("UK | News Extra", "US | News", "CA | News", "News"))
    }
}
