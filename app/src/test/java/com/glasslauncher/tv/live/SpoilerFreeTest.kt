package com.glasslauncher.tv.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpoilerFreeTest {
    @Test fun `a result in a title goes`() {
        assertEquals("Man Utd v Roma", LiveNames.withoutResult("Man Utd 2-1 Roma"))
        assertEquals("Man Utd v Roma", LiveNames.withoutResult("Man Utd 2 : 1 Roma"))
    }

    @Test fun `a time or a year stays`() {
        assertEquals("Man Utd v Roma 20:30", LiveNames.withoutResult("Man Utd v Roma 20:30"))
        assertEquals("Champions League 2026-27", LiveNames.withoutResult("Champions League 2026-27"))
    }

    @Test fun `a team hidden for a while comes back by itself`() {
        val file = "man utd|1000\narsenal\nroma|3000\n"
        assertEquals(setOf("man utd", "arsenal", "roma"), LiveOptions.activeSpoilerFree(file, 500))
        assertEquals(setOf("arsenal", "roma"), LiveOptions.activeSpoilerFree(file, 2000))
        assertEquals(setOf("arsenal"), LiveOptions.activeSpoilerFree(file, 4000))
    }

    @Test fun `marks after a team's name don't split a match`() {
        assertEquals(LiveHome.teamsKey("RB Leipzig v Manchester City"), LiveHome.teamsKey("Leipzig vs Man City - MD4"))
        assertEquals(LiveHome.teamsKey("RB Leipzig v Manchester City"), LiveHome.teamsKey("RB Lipsia - Manchester City"))
        assertEquals(LiveHome.teamsKey("RB Leipzig v Manchester City"), LiveHome.teamsKey("LEIPZIG - MANCHESTER CITY (Z)"))
    }

    @Test fun `American teams are found by their short names`() {
        val lakers = LiveNames.of("los angeles lakers", emptyList())
        assertTrue(lakers.`in`("nba lakers at celtics"))
        assertFalse(lakers.`in`("los angeles clippers at celtics"))
        val giants = LiveNames.of("new york giants", emptyList())
        assertTrue(giants.`in`("giants at eagles"))
        assertFalse(giants.`in`("san francisco giants at dodgers"))
    }
}
