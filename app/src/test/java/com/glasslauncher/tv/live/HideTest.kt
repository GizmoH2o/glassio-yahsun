package com.glasslauncher.tv.live

import org.junit.Assert.assertEquals
import org.junit.Test

class HideTest {
    private fun ch(n: Int) = Xtream.Channel().apply { id = n }
    private fun group(name: String, vararg ids: Int) = Xtream.Category().apply { id = name; ids.forEach { channels.add(ch(it)) } }

    private val list = listOf(group("fav", 9), group("all", 1, 2, 3, 4, 9), group("sports", 1, 2), group("foreign", 2, 3, 4, 9))

    @Test fun onlyInAHiddenGroupThoughAllChannelsHasEverything() {
        assertEquals(setOf(3, 4, 9), LiveHide.away(list, { it == "foreign" }, setOf()))
    }

    @Test fun favoritesAndYourOwnGroupsAreShown() {
        assertEquals(setOf(3), LiveHide.away(list, { it == "foreign" }, setOf(9, 4)))
    }

    @Test fun outOfAllChannelsButStillFound() {
        val l = Xtream.Lineup().apply { categories.addAll(list); categories[1].channels.forEach { byId[it.id] = it } }
        LiveHide.apply(l, setOf(3, 4))
        assertEquals(listOf(1, 2, 9), l.all().map { it.id })
        assertEquals(listOf(1, 2, 3, 4, 9), l.every().map { it.id })
        assertEquals(3, l.find(3).id)
        assertEquals(listOf(2, 3, 4, 9), l.categories[3].channels.map { it.id })       // its category, for when it's shown again
    }

    @Test fun nothingHidden() {
        assertEquals(setOf<Int>(), LiveHide.away(list, { false }, setOf()))
    }
}
