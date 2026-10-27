package com.glasslauncher.tv.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which public logo a channel gets, and which of a channel's logos is the best. */
class LivePublicLogosTest {
    private val wiki = "https://upload.wikimedia.org/tlc.png"
    private val india = "https://example.org/tlc-in.png"
    private val skyUk = "https://example.org/sky-main-event-uk.png"
    private val index = mapOf(
        "tlc" to listOf(arrayOf("US", wiki), arrayOf("UK", wiki), arrayOf("DE", wiki), arrayOf("IN", india)),
        "sky sports main event" to listOf(arrayOf("UK", skyUk), arrayOf("IE", skyUk)),
    )

    @Test fun keys() {
        assertEquals("tlc", LivePublicLogos.key("TLC HD"))
        assertEquals("sky sports main event", LivePublicLogos.key("Sky Sports Main Event (Low Latency)"))
        assertEquals("bein sports 1", LivePublicLogos.key("beIN Sports 1 FHD"))
    }

    @Test fun theCountrysOwn() {
        assertEquals(india, LivePublicLogos.find(index, "TLC", "IN"))
        assertEquals(wiki, LivePublicLogos.find(index, "TLC", "US"))
        assertEquals(skyUk, LivePublicLogos.find(index, "Sky Sports Main Event", "UK"))
    }

    @Test fun withoutACountryTheOneMostShare() {
        assertEquals(wiki, LivePublicLogos.find(index, "TLC", ""))
        assertEquals(wiki, LivePublicLogos.find(index, "TLC", "FR"))       // France not listed: the common one
    }

    @Test fun pictureTagsAndRegions() {
        assertEquals(wiki, LivePublicLogos.find(index, "TLC HD", "US"))
        assertEquals(wiki, LivePublicLogos.find(index, "TLC West", "US"))
        assertEquals(skyUk, LivePublicLogos.find(index, "Sky Sports Main Event (Low Latency)", "UK"))
    }

    @Test fun theGuidesIdFirst() {
        val byId = index + mapOf("@tlc.in" to listOf(arrayOf("IN", india)))
        assertEquals("@tlc.in", LivePublicLogos.idKey("TLC.in@HD"))
        assertEquals("", LivePublicLogos.idKey(" "))
        assertEquals(india, LivePublicLogos.find(byId, "TLC", "US", "TLC.in"))      // the id says which TLC
        assertEquals(wiki, LivePublicLogos.find(byId, "TLC", "US", "tlc.unknown"))  // not iptv-org's: by name
        assertEquals(wiki, LivePublicLogos.find(byId, "TLC", "US", null))
    }

    @Test fun unknownChannels() {
        assertNull(LivePublicLogos.find(index, "CNN", "US"))
        assertNull(LivePublicLogos.find(index, "TLC Kids", "US"))
    }

    @Test fun bestLogo() {
        assertTrue(LivePublicLogos.score("PNG", true, 512) > LivePublicLogos.score("PNG", false, 1000))     // the channel's own
        assertTrue(LivePublicLogos.score("PNG", true, 960) > LivePublicLogos.score("PNG", true, 334))       // sharper
        assertTrue(LivePublicLogos.score("PNG", true, 500) > LivePublicLogos.score("JPEG", true, 500))      // see-through
        assertEquals(-1, LivePublicLogos.score("SVG", true, 900))       // Android can't draw it
        assertEquals(-1, LivePublicLogos.score(null, true, 900))
    }
}
