package com.glasslauncher.tv.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Finding a channel's guide by name, what counts as missing one, and the guide's room to grow. */
class LiveGuideMatchTest {
    private val file = mapOf("bbc1.uk" to "BBC One", "itv.uk" to "ITV", "fox.us" to "Fox", "fox.au" to "Fox", "euro.eu" to "Eurosport HD")
    private val guided = setOf("bbc1.uk", "itv.uk", "fox.us", "fox.au", "euro.eu")
    private val names = LiveGuideMatch.unique(file, guided)

    @Test fun `a channel finds the one guide of its name`() {
        assertEquals("bbc1.uk", LiveGuideMatch.find("UK: BBC One HD", names))
    }

    @Test fun `quality tags don't matter`() {
        assertEquals("euro.eu", LiveGuideMatch.find("Eurosport FHD", names))
    }

    @Test fun `two guides of one name are no match`() {
        assertNull(LiveGuideMatch.find("Foxy", LiveGuideMatch.unique(mapOf("a" to "Foxy", "b" to "Foxy"), setOf("a", "b"))))
    }

    @Test fun `a very short name is too easy to get wrong`() {
        assertNull(LiveGuideMatch.find("ITV", names))
    }

    @Test fun `a guide without shows isn't offered`() {
        assertNull(LiveGuideMatch.find("BBC One", LiveGuideMatch.unique(file, setOf("itv.uk"))))
    }

    @Test fun `an unknown name matches nothing`() {
        assertNull(LiveGuideMatch.find("Some Other Channel", names))
    }

    @Test fun `radio and adult channels never expect a guide`() {
        assertFalse(LiveGuideMatch.expectsGuide("Radio | Sirius XM", "Radio: She's So Funny"))
        assertFalse(LiveGuideMatch.expectsGuide("24/7 Adult", "Pix And Video"))
    }

    @Test fun `event slots never expect a guide`() {
        assertFalse(LiveGuideMatch.expectsGuide("Sports | Paramount+", "Paramount+ 97: NO EVENT"))
        assertFalse(LiveGuideMatch.expectsGuide("Sports | DAZN US", "DAZN US 70: NO EVENT"))
        assertFalse(LiveGuideMatch.expectsGuide("Sports | FloSports", "(FLSP 171) | hockey:  Gatineau vs Rouyn"))
        assertFalse(LiveGuideMatch.expectsGuide("Sports | F1 TV", "F1 TV 01 | Offline"))
    }

    @Test fun `ordinary channels expect a guide`() {
        assertTrue(LiveGuideMatch.expectsGuide("Entertainment | UK", "BBC One"))
        assertTrue(LiveGuideMatch.expectsGuide("Sports | UK", "UK: Sky Sports 1"))
        assertTrue(LiveGuideMatch.expectsGuide("Sports", "Sky Sports News"))
        assertTrue(LiveGuideMatch.expectsGuide(null, "BBC Two"))
    }

    @Test fun `past shows are kept while there's plenty of room`() {
        val mb = 1_000_000L
        assertTrue(XmltvStore.roomToKeep(14_000 * mb, 230 * mb))
        assertFalse(XmltvStore.roomToKeep(600 * mb, 230 * mb))
    }

    private fun ch(id: Int, name: String, category: String, epg: String, quality: Int) = Xtream.Channel().apply {
        this.id = id
        this.name = name
        this.category = category
        this.epg = epg
        this.quality = quality
    }

    @Test fun `an SD channel gets the HD copy from another category, same guide only`() {
        val sd = ch(1, "Blaze SD", "ent", "blaze.uk", LiveMerge.SD)
        val hd = ch(2, "Blaze HD", "ent-hd", "blaze.uk", LiveMerge.HD)
        val other = ch(3, "Blaze HD", "movies", "blaze.us", LiveMerge.HD)
        assertEquals(listOf(hd), LiveMerge.hdTwins(sd, listOf(sd, hd, other)))
    }

    @Test fun `no guide id means no twin`() {
        val sd = ch(1, "Blaze SD", "ent", "", LiveMerge.SD)
        val hd = ch(2, "Blaze HD", "ent-hd", "", LiveMerge.HD)
        assertTrue(LiveMerge.hdTwins(sd, listOf(sd, hd)).isEmpty())
    }

    @Test fun `an HD channel isn't given twins`() {
        val a = ch(1, "Blaze HD", "ent", "blaze.uk", LiveMerge.HD)
        val b = ch(2, "Blaze FHD", "ent-hd", "blaze.uk", LiveMerge.FHD)
        assertTrue(LiveMerge.hdTwins(a, listOf(a, b)).isEmpty())
    }

    @Test fun `a copy in the same category is merged already`() {
        val sd = ch(1, "Blaze SD", "ent", "blaze.uk", LiveMerge.SD)
        val hd = ch(2, "Blaze HD", "ent", "blaze.uk", LiveMerge.HD)
        assertTrue(LiveMerge.hdTwins(sd, listOf(sd, hd)).isEmpty())
    }

    private fun lineup(vararg cats: Pair<String, List<Xtream.Channel>>): Xtream.Lineup {
        val l = Xtream.Lineup()
        l.categories.add(Xtream.Category().apply { id = "fav"; name = "Favorites" })
        val all = Xtream.Category().apply { id = "all"; name = "All" }
        l.categories.add(all)
        for ((id, list) in cats) {
            all.channels.addAll(list)
            l.categories.add(Xtream.Category().apply { this.id = id; name = id; channels.addAll(list) })
        }
        return l
    }

    @Test fun `a visiting HD copy still merges in its own category`() {
        val sd = ch(1, "Blaze SD", "ent", "blaze.uk", LiveMerge.SD)
        val hd = ch(2, "Blaze HD", "ent-hd", "blaze.uk", LiveMerge.HD)
        val fhd = ch(3, "Blaze FHD", "ent-hd", "blaze.uk", LiveMerge.FHD)
        val l = lineup("ent" to listOf(sd), "ent-hd" to listOf(hd, fhd))
        LiveMerge.apply(l, setOf(LiveMerge.TWINS))
        assertEquals(2, l.all().size)
        assertEquals(1, l.categories[3].channels.size)
        assertEquals(2, l.categories[3].channels[0].sources.size)
    }

    @Test fun `the SD channel gets the visitor as a source and keeps its own id`() {
        val sd = ch(1, "Blaze SD", "ent", "blaze.uk", LiveMerge.SD)
        val hd = ch(2, "Blaze HD", "ent-hd", "blaze.uk", LiveMerge.HD)
        val l = lineup("ent" to listOf(sd), "ent-hd" to listOf(hd))
        LiveMerge.apply(l, setOf(LiveMerge.TWINS))
        val shown = l.categories[2].channels[0]
        assertEquals(1, shown.id)
        assertEquals(2, shown.sources.size)
        assertEquals(2, l.categories[3].channels[0].id)
        assertEquals(null, l.categories[3].channels[0].sources)
    }

    @Test fun `off, the two stay apart`() {
        val sd = ch(1, "Blaze SD", "ent", "blaze.uk", LiveMerge.SD)
        val hd = ch(2, "Blaze HD", "ent-hd", "blaze.uk", LiveMerge.HD)
        val l = lineup("ent" to listOf(sd), "ent-hd" to listOf(hd))
        LiveMerge.apply(l, emptySet())
        assertEquals(null, l.categories[2].channels[0].sources)
    }
}
