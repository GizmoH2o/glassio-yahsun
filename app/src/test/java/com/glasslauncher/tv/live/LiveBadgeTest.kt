package com.glasslauncher.tv.live

import org.junit.Assert.assertEquals
import org.junit.Test

/** The resolution badge at a channel logo's corner (Settings › Channels › Resolution badges). */
class LiveBadgeTest {
    private fun ch(id: Int, name: String) = Xtream.Channel().apply { this.id = id; this.name = name }

    @Test fun `a name's tags say its resolution`() {
        assertEquals(5, LiveMerge.nameBadge("UK: Sky Sports Main Event UHD"))
        assertEquals(5, LiveMerge.nameBadge("Sky Cinema 4K"))
        assertEquals(4, LiveMerge.nameBadge("DAZN 1 QHD"))
        assertEquals(4, LiveMerge.nameBadge("Eurosport 2K"))
        assertEquals(4, LiveMerge.nameBadge("ESPN (1440p)"))
        assertEquals(3, LiveMerge.nameBadge("BBC One FHD"))
        assertEquals(3, LiveMerge.nameBadge("BBC One Full HD"))
        assertEquals(3, LiveMerge.nameBadge("CNN 1080p"))
        assertEquals(2, LiveMerge.nameBadge("UK: ITV1 HD"))
        assertEquals(2, LiveMerge.nameBadge("TLC 720p"))
        assertEquals(1, LiveMerge.nameBadge("Dave SD"))
        assertEquals(3, LiveMerge.nameBadge("Sky Atlantic ᶠᴴᴰ"))
    }

    @Test fun `no tag is no badge, not a guess`() {
        assertEquals(0, LiveMerge.nameBadge("UK: Channel 4"))
        assertEquals(0, LiveMerge.nameBadge("HDTV Shop"))
        assertEquals(0, LiveMerge.nameBadge(""))
        assertEquals("", LiveMerge.badge(null, ch(1, "UK: Channel 4")))
    }

    @Test fun `a picture's height says its resolution`() {
        assertEquals(5, LiveMerge.heightBadge(2160))
        assertEquals(4, LiveMerge.heightBadge(1440))
        assertEquals(3, LiveMerge.heightBadge(1080))
        assertEquals(2, LiveMerge.heightBadge(720))
        assertEquals(1, LiveMerge.heightBadge(576))
        assertEquals(0, LiveMerge.heightBadge(0))
    }

    @Test fun `a merged channel shows its best source`() {
        val merged = ch(10, "BBC One").apply { sources = listOf(ch(11, "BBC One SD"), ch(12, "BBC One UHD"), ch(13, "BBC One FHD")) }
        assertEquals("4K", LiveMerge.badge(null, merged))
        val plain = ch(20, "ITV").apply { sources = listOf(ch(21, "ITV"), ch(22, "ITV HD")) }
        assertEquals("HD", LiveMerge.badge(null, plain))
    }

    @Test fun `a rule's row has none`() {
        assertEquals("", LiveMerge.badge(null, ch(30, "Sports 4K").apply { smart = true }))
    }

    @Test fun `2K and 1440 are tags, so those copies still merge`() {
        assertEquals(LiveMerge.nameKey("BBC One"), LiveMerge.nameKey("BBC One QHD"))
        assertEquals(LiveMerge.nameKey("BBC One"), LiveMerge.nameKey("BBC One 1440p"))
    }
}
