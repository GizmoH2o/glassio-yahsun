package com.glasslauncher.tv.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Countries read from how providers write them, and channels found from what was said. */
class LiveCountryVoiceTest {
    private fun split(name: String, category: Boolean = false) = LiveCountry.split(name, category).let { it.code to it.name }

    @Test fun prefixes() {
        assertEquals("US" to "TLC", split("US: TLC"))
        assertEquals("US" to "TLC HD", split("US | TLC HD"))
        assertEquals("UK" to "Sky News", split("[UK] Sky News"))
        assertEquals("UK" to "Sky News", split("|UK| Sky News"))
        assertEquals("CA" to "CTV", split("CA - CTV"))
        assertEquals("JM" to "TVJ", split("JM: TVJ"))
    }

    @Test fun suffixes() {
        assertEquals("US" to "TLC", split("TLC (US)"))
        assertEquals("US" to "TLC", split("TLC USA"))
        assertEquals("US" to "TLC", split("TLC US"))
        assertEquals("UK" to "BBC One", split("BBC One - UK"))
        assertEquals("DE" to "ZDF", split("ZDF [DE]"))
    }

    @Test fun arabicIsNotArgentina() {
        assertEquals(LiveCountry.ARABIC to "beIN Sports 1", split("AR: beIN Sports 1"))
        assertEquals(LiveCountry.ARABIC to "MBC 2", split("[AR] MBC 2"))
        assertEquals(LiveCountry.ARABIC to "Al Jazeera", split("ARABIC | Al Jazeera"))
        assertEquals(LiveCountry.ARABIC to "Rotana Cinema", split("[ARB] Rotana Cinema"))
        assertEquals("AR" to "Argentina TV Publica", split("AR | Argentina TV Publica"))
        assertEquals("" to "Arte", split("Arte"))
    }

    @Test fun favoritesByVoice() {
        fun fav(said: String) = LiveVoice.parseFavorite(said)?.let { it.channel to it.add }
        assertEquals("tlc" to true, fav("Add TLC to favourites"))
        assertEquals("tlc usa" to true, fav("add TLC USA to my favorites"))
        assertEquals("sky news" to true, fav("favourite Sky News"))
        assertEquals("tlc" to false, fav("Remove TLC from favourites"))
        assertEquals("302" to false, fav("take channel 302 out of my favorites"))
        assertEquals("tlc" to false, fav("unfavorite TLC"))
        assertEquals("globo" to true, fav("adicionar Globo aos favoritos"))
        assertNull(fav("watch TLC"))
        assertNull(fav("TLC"))
    }

    @Test fun flags() {
        assertEquals("US" to "TLC", split("🇺🇸 TLC"))
    }

    @Test fun picturesAndWordsAreNotCountries() {
        assertEquals("" to "TLC HD", split("TLC HD"))
        assertEquals("" to "TLC (SD)", split("TLC (SD)"))
        assertEquals("" to "TLC West", split("TLC West"))
        assertEquals("" to "USA Network", split("USA Network"))       // a channel, not a country
        assertEquals("" to "TFC", split("TFC"))
        assertEquals("" to "E! (EN)", split("E! (EN)"))
    }

    @Test fun categories() {
        assertEquals("US" to "Entertainment", split("US | Entertainment", true))
        assertEquals("US" to "ENTERTAINMENT", split("USA ENTERTAINMENT", true))
        assertEquals("UK" to "Sports", split("UK: Sports", true))
        assertEquals("" to "Sports", split("Sports", true))
    }

    private fun parse(said: String) = LiveVoice.parse(said).let { Triple(it.name, it.country, it.number) }

    @Test fun whatWasSaid() {
        assertEquals(Triple("tlc", "", -1), parse("Watch TLC"))
        assertEquals(Triple("tlc", "US", -1), parse("watch TLC USA"))
        assertEquals(Triple("tlc", "US", -1), parse("TLC US"))
        assertEquals(Triple("tlc", "US", -1), parse("play TLC from the United States on Glassio Yahsun"))
        assertEquals(Triple("sky news", "UK", -1), parse("put on Sky News UK"))
        assertEquals(Triple("tlc", "US", -1), parse("US TLC"))
        assertEquals(Triple("", "", 2340), parse("channel 2340"))
        assertEquals("usa network", LiveVoice.parse("watch USA Network").whole)
        assertEquals(Triple("us", "", -1), parse("watch US"))        // nothing else: a name, not a country
    }

    @Test fun fits() {
        assertEquals(100, LiveVoice.fit("TLC", "tlc"))
        assertEquals(95, LiveVoice.fit("TLC HD", "tlc"))
        assertEquals(60, LiveVoice.fit("TLC West", "tlc"))
        assertEquals(0, LiveVoice.fit("TNT", "tlc"))
    }

    private fun ch(id: Int, num: Int, name: String, cat: String = "") = Xtream.Channel().apply {
        this.id = id; this.num = num; this.name = name; this.category = cat
    }

    private val lineup = listOf(
        ch(1, 2340, "TLC", "us"), ch(2, 2341, "TLC West", "us"), ch(3, 900, "UK: TLC"), ch(4, 901, "TLC HD", "ca"),
        ch(5, 100, "USA Network", "us"),
    )
    private val countries = mapOf("us" to "US", "ca" to "CA")

    private fun best(said: String, liked: (Xtream.Channel) -> Int = { 0 }) =
        LiveVoice.best(lineup, LiveVoice.parse(said), { LiveCountry.of(it, countries) }, { liked(it) })?.id

    @Test fun findsTheChannel() {
        assertEquals(1, best("watch TLC USA"))
        assertEquals(3, best("watch TLC UK"))
        assertEquals(4, best("TLC Canada"))
        assertEquals(1, best("channel 2340"))
        assertEquals(5, best("watch USA Network"))
        assertEquals(2, best("TLC West"))
    }

    @Test fun withoutACountryTheOnesWatchedWin() {
        assertEquals(3, best("watch TLC") { if (it.id == 3) 60 else 0 })
        assertEquals(1, best("watch TLC"))      // the plain name over "TLC HD", the shorter
    }

    @Test fun nothingFits() {
        assertNull(best("watch CNN"))
        assertNull(best("TLC France"))
        assertNull(best("channel 7"))
    }
}
