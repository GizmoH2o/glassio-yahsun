package com.glasslauncher.tv.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Home Assistant's tune: a channel by its number, or by its name as voice finds it (your usual country first). */
class LiveHaTuneTest {
    private fun ch(id: Int, num: Int, name: String) = Xtream.Channel().also { it.id = id; it.num = num; it.name = name; it.category = "1" }
    private val all = listOf(ch(1, 622, "UK: Sky Sports News"), ch(2, 8325, "UK: Sky Sports News HD"), ch(3, 16035, "IE: Sky Sports News"),
        ch(4, 101, "UK: BBC One"), ch(5, 2353, "US: USA Network"))

    @Test fun `by number`() {
        assertEquals(1, LivePhone.tuneTarget(all, "622", "")!!.id)
        assertEquals(5, LivePhone.tuneTarget(all, " 2353 ", "")!!.id)
        assertNull(LivePhone.tuneTarget(all, "999", ""))
    }

    @Test fun `by name, the usual country first`() {
        assertEquals(4, LivePhone.tuneTarget(all, "BBC One", "UK")!!.id)
        assertEquals(3, LivePhone.tuneTarget(all, "Sky Sports News", "IE")!!.id)
        assertEquals("UK", LiveCountry.split(LivePhone.tuneTarget(all, "Sky Sports News", "UK")!!.name).code)
    }

    @Test fun `a country said wins`() {
        assertEquals(3, LivePhone.tuneTarget(all, "Sky Sports News Ireland", "UK")!!.id)
    }

    @Test fun `nothing like it`() {
        assertNull(LivePhone.tuneTarget(all, "Discovery", "UK"))
        assertNull(LivePhone.tuneTarget(all, "", "UK"))
    }
}
