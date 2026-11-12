package com.glasslauncher.tv.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The fingerprints that let Live TV open at once (its guide name matches and public logos kept from last time): the
 * same channels give the same one, any change to what a match depends on gives another.
 */
class LiveOpenSpeedTest {
    private fun lineup(vararg names: String): Xtream.Lineup {
        val l = Xtream.Lineup()
        l.categories.add(Xtream.Category())
        val all = Xtream.Category()
        names.forEachIndexed { i, n -> all.channels.add(Xtream.Channel().apply { id = i + 1; name = n; category = "UK" }) }
        l.categories.add(all)
        return l
    }

    @Test fun `the same channels give the same fingerprint`() {
        assertEquals(XmltvStore.lineupPrint(lineup("BBC One", "ITV")), XmltvStore.lineupPrint(lineup("BBC One", "ITV")))
        assertEquals(Live.logoPrint(lineup("BBC One").all(), LiveOptions.LOGOS_PUBLIC, emptyMap()),
            Live.logoPrint(lineup("BBC One").all(), LiveOptions.LOGOS_PUBLIC, emptyMap()))
    }

    @Test fun `a renamed channel is matched again`() {
        assertNotEquals(XmltvStore.lineupPrint(lineup("BBC One", "ITV")), XmltvStore.lineupPrint(lineup("BBC One HD", "ITV")))
    }

    @Test fun `a channel with another guide id has its logo looked up again`() {
        val a = lineup("BBC One")
        val b = lineup("BBC One").also { it.all()[0].epg = "bbc1.uk" }
        assertNotEquals(Live.logoPrint(a.all(), LiveOptions.LOGOS_PUBLIC, emptyMap()), Live.logoPrint(b.all(), LiveOptions.LOGOS_PUBLIC, emptyMap()))
    }
}
