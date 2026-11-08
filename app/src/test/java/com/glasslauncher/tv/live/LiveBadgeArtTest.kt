package com.glasslauncher.tv.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The player's stream badges as the badge set's pictures (LiveBadgeArt). */
class LiveBadgeArtTest {
    private val set = """
        {"groups": [{"id": "gr", "name": "Resolution"}],
         "filters": [
           {"id": "r-1080", "name": "1080p", "groupId": "gr", "isEnabled": true, "pattern": "x",
            "imageURL": "https://example.com/1080p-black.png", "tagColor": "#FFFFFF", "borderColor": "#EEEEEE",
            "textColor": "#000000", "tagStyle": "filled", "type": "filter"},
           {"id": "s-h265", "name": "HEVC", "groupId": "ge", "isEnabled": true,
            "imageURL": "https://example.com/hevc.png", "tagColor": "#112233", "borderColor": "#FFFFFF",
            "textColor": "#FFFFFF", "tagStyle": "outline"},
           {"id": "a-aac", "name": "AAC", "isEnabled": false, "imageURL": "https://example.com/aac.png"},
           {"id": "ch-20", "name": "2.0", "isEnabled": true, "imageURL": "file:///sdcard/x.png"},
           {"id": "v-hdr", "name": "HDR", "isEnabled": true, "imageURL": "https://example.com/hdr.png", "tagColor": "not a color"}
         ]}
    """.trimIndent()

    @Test fun `the set's badges with a picture, by id`() {
        val s = LiveBadgeArt.parse(set)
        val hd = s["r-1080"]!!
        assertEquals("https://example.com/1080p-black.png", hd.url)
        assertEquals(0xFFFFFFFF.toInt(), hd.tag)
        assertEquals(0xFFEEEEEE.toInt(), hd.border)
        assertEquals(0xFF000000.toInt(), hd.ink)
        assertTrue(hd.filled)
        assertFalse(s["s-h265"]!!.filled)
    }

    @Test fun `off, or without a web picture, it isn't there`() {
        val s = LiveBadgeArt.parse(set)
        assertNull(s["a-aac"])
        assertNull(s["ch-20"])
    }

    @Test fun `a color it can't read is the usual one`() {
        assertEquals(0xFFFFFFFF.toInt(), LiveBadgeArt.parse(set)["v-hdr"]!!.tag)
    }

    @Test fun `not the set reads as none`() {
        assertTrue(LiveBadgeArt.parse("not json").isEmpty())
        assertTrue(LiveBadgeArt.parse("{}").isEmpty())
    }

    @Test fun `the picture's lines pick the resolution`() {
        assertEquals("r-4k", LiveBadgeArt.resolutionId(2160))
        assertEquals("r-1440p", LiveBadgeArt.resolutionId(1440))
        assertEquals("r-1080", LiveBadgeArt.resolutionId(1080))
        assertEquals("r-720", LiveBadgeArt.resolutionId(720))
        assertEquals("r-576p", LiveBadgeArt.resolutionId(576))
        assertEquals("r-480p", LiveBadgeArt.resolutionId(480))
        assertEquals("r-360p", LiveBadgeArt.resolutionId(360))
        assertEquals("r-144p", LiveBadgeArt.resolutionId(144))
    }

    @Test fun `codecs, sound and channels`() {
        assertEquals("s-h265", LiveBadgeArt.videoId("video/hevc"))
        assertEquals("s-h264", LiveBadgeArt.videoId("video/avc"))
        assertEquals("ge-av1", LiveBadgeArt.videoId("video/av01"))
        assertEquals("", LiveBadgeArt.videoId("video/mpeg2"))
        assertEquals("a-atmos-ddplus", LiveBadgeArt.audioId("audio/eac3-joc"))
        assertEquals("a-dp", LiveBadgeArt.audioId("audio/eac3"))
        assertEquals("a-dd", LiveBadgeArt.audioId("audio/ac3"))
        assertEquals("a-aac", LiveBadgeArt.audioId("audio/mp4a-latm"))
        assertEquals("", LiveBadgeArt.audioId("audio/mpeg"))
        assertEquals("ch-71", LiveBadgeArt.channelsId(8))
        assertEquals("ch-51", LiveBadgeArt.channelsId(6))
        assertEquals("ch-20", LiveBadgeArt.channelsId(2))
        assertEquals("", LiveBadgeArt.channelsId(1))
    }
}

class LiveBadgeArtColorTest {
    @Test fun `six and eight digit colors`() {
        assertEquals(0xFF112233.toInt(), LiveBadgeArt.color("#112233", 0))
        assertEquals(0x80112233.toInt(), LiveBadgeArt.color("#80112233", 0))
        assertEquals(7, LiveBadgeArt.color("red", 7))
        assertEquals(7, LiveBadgeArt.color("#12345", 7))
    }
}
