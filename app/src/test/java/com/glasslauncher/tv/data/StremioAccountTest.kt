package com.glasslauncher.tv.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** How Stremio's library items are read: a series' episode from its video id, and its dates. */
class StremioAccountTest {
    @Test fun episodeFromTheVideoId() {
        assertEquals(1 to 2, StremioAccount.episodeOf("tt0944947:1:2", "tt0944947"))
        assertEquals(10 to 15, StremioAccount.episodeOf("tt0903747:10:15", "tt0903747"))
        assertEquals(1 to 5, StremioAccount.episodeOf("kitsu:123:5", "kitsu:123"))        // one season
    }

    @Test fun aFilmOrNothingKnownHasNoEpisode() {
        assertEquals(0 to 0, StremioAccount.episodeOf("tt0111161", "tt0111161"))
        assertEquals(0 to 0, StremioAccount.episodeOf("", "tt0944947"))
        assertEquals(0 to 0, StremioAccount.episodeOf("tt9999999:1:2", "tt0944947"))       // another title's
        assertEquals(0 to 0, StremioAccount.episodeOf("tt0944947:one:2", "tt0944947"))
        assertEquals(0 to 0, StremioAccount.episodeOf("tt0944947:1:2:3", "tt0944947"))
    }

    @Test fun dates() {
        assertEquals(1709324100000L, StremioAccount.ms("2024-03-01T20:15:00.000Z"))
        assertEquals(1709324100000L, StremioAccount.ms("2024-03-01T20:15:00Z"))
        assertEquals(0L, StremioAccount.ms(""))
        assertEquals(0L, StremioAccount.ms("null"))
        assertEquals("2024-03-01T20:15:00Z", StremioAccount.iso(1709324100000L))
        assertEquals(1709324100123L, StremioAccount.ms(StremioAccount.iso(1709324100123L)))
    }
}
