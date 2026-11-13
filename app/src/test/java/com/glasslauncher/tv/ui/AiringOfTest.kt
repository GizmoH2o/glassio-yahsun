package com.glasslauncher.tv.ui

import com.glasslauncher.tv.data.TitleDetails
import com.glasslauncher.tv.live.LiveHome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** An episode found on your channels by the guide's words. */
class AiringOfTest {
    private fun airing(title: String, sub: String) = LiveHome.Airing().apply { this.title = title; this.sub = sub; channelName = "251" }
    private fun ep(season: Int, number: Int, name: String) = TitleDetails.Episode(season, number, name, "", "", 0)

    @Test fun byNumber() {
        val a = airing("Breaking Bad", "S5 E9 Blood Money")
        assertEquals(a, airingOf(ep(5, 9, "Blood Money"), listOf(a)))
        assertNull(airingOf(ep(5, 10, "Buried"), listOf(a)))
    }

    @Test fun byTitle() {
        val a = airing("Breaking Bad", "Ozymandias")
        assertEquals(a, airingOf(ep(5, 14, "Ozymandias"), listOf(a)))
        assertNull(airingOf(ep(5, 1, "Pie"), listOf(airing("X", "Pie and mash"))))
    }
}
