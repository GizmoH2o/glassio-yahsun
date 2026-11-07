package com.glasslauncher.tv.live

import org.junit.Assert.assertEquals
import org.junit.Test

/** Countries in the ways the real lineup writes them that weren't read before (names from it). */
class LiveCountryMoreTest {
    private fun split(name: String) = LiveCountry.split(name).let { it.code to it.name }

    @Test fun `a code before the feed's own bracket`() {
        assertEquals("AU" to "(STAN 52) | Hong Kong China v USA", split("AU (STAN 52) | Hong Kong China v USA"))
        assertEquals("CA" to "(TSN+ 001) | Formula 1 Driver Tracker", split("CA (TSN+ 001) | Formula 1 Driver Tracker"))
    }

    @Test fun `a code between the service and its feed number`() {
        assertEquals("UK" to "DAZN 01: La Passe FC vs. Cote D'or FC", split("DAZN UK 01: La Passe FC vs. Cote D'or FC"))
        assertEquals("AU" to "Kayo 02: NO EVENT", split("Kayo AU 02: NO EVENT"))
    }

    @Test fun `US Latino is the US`() {
        assertEquals("US" to "CINE LATINO", split("USL: CINE LATINO"))
    }

    @Test fun `low bandwidth is said after the name, not before it`() {
        assertEquals("" to "Cartoon Network (Low BW)", split("LBW: Cartoon Network"))
    }

    @Test fun `regions and teams aren't countries`() {
        assertEquals("" to "LAT: A&E", split("LAT: A&E"))
        assertEquals("" to "CAR: 3ABN", split("CAR: 3ABN"))
        assertEquals("" to "4K: USA Network", split("4K: USA Network"))
        assertEquals("", split("(FLSP 929) | live:  USA Seniors Cricket NPL Semifinal #1").first)
        assertEquals("", split("Soccer04: Barcelona vs Paris FC ( ESPN Deportes USA Feed ) @ 3:00 pm").first)
        assertEquals("", split("MLS 07 | New England Revolution vs. Inter Miami CF").first)
        assertEquals("", split("Hub Premier 10").first)
        assertEquals("", split("Coupang | 01").first)
    }

    @Test fun `a category's country from any part of its name`() {
        assertEquals("UK", LiveCountry.categoryCode("Sports | DAZN UK (2)"))
        assertEquals("DE", LiveCountry.categoryCode("Sports | DE Sports"))
        assertEquals("US", LiveCountry.categoryCode("US | Entertainment"))
        assertEquals("ES", LiveCountry.categoryCode("OnePlay | Cine (ES)"))
    }

    @Test fun `a service sold in one country is that country`() {
        assertEquals("AU", LiveCountry.categoryCode("Sports | Kayo"))
        assertEquals("AU", LiveCountry.categoryCode("Sports | Kayo (2)"))
        assertEquals("AU", LiveCountry.categoryCode("Sports | Stan"))
        assertEquals("CA", LiveCountry.categoryCode("Sports | TSN+ (2)"))
        assertEquals("CA", LiveCountry.categoryCode("Sports | Sportsnet+"))
        assertEquals("HK", LiveCountry.categoryCode("Sports | NowHK"))
        assertEquals("SG", LiveCountry.categoryCode("Sports | StarHub"))
        assertEquals("MY", LiveCountry.categoryCode("Sports | AstroSports"))
        assertEquals("KR", LiveCountry.categoryCode("Sports | Coupang Play"))
        assertEquals("TH", LiveCountry.categoryCode("Sports | MonoMAX"))
    }

    @Test fun `categories without a country stay without`() {
        for (c in listOf("Sports | FloSports", "Sports | ESPN+", "Sports | Paramount+", "Sports | NCAA Basketball (W)", "24/7 Kids",
                "Sports | EPL Teams", "Sports | MLS", "Latino | Entertainment", "CAR | Entertainment", "Sports | NBA League Pass (TEST)",
                "PPV Events | 1", "Sports | F1 TV (2)", "Sports | Football / Soccer", "Radio | Sirius XM", "Sports | TV2 Play",
                "4K / UHD", "OnePlay | Kids International", "Sports | Snooker / Pool", "Sports | OHL/WHL/QMJHL"))
            assertEquals(c, "", LiveCountry.categoryCode(c))
    }
}
