package com.glasslauncher.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Continue Watching's Resume in its app: Stremio's link that plays with the stream used last. */
class ResumeLinkTest {
    @Test fun `a movie plays itself`() {
        assertEquals("stremio:///detail/movie/tt0066921/tt0066921?autoPlay=true", NuvioLaunch.resumeLink("movie", "tt0066921", null))
    }

    @Test fun `a series plays its episode`() {
        assertEquals("stremio:///detail/series/tt0944947/tt0944947:1:3?autoPlay=true", NuvioLaunch.resumeLink("series", "tt0944947", 1 to 3))
    }

    @Test fun `a series with no episode known has none`() {
        assertNull(NuvioLaunch.resumeLink("series", "tt0944947", null))
    }

    @Test fun `ids with a colon keep it, and no id has none`() {
        assertEquals("stremio:///detail/series/kitsu:42/kitsu:42:7?autoPlay=true", NuvioLaunch.resumeLink("series", "kitsu:42", 1 to 7))
        assertNull(NuvioLaunch.resumeLink("movie", " ", null))
    }
}
