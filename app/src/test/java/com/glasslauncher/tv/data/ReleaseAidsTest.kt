package com.glasslauncher.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseAidsTest {
    @Test fun `a version's section of the changelog, without marks`() {
        val text = "# Changelog\n\nAbout.\n\n## 3.0.0 — 2026-11-03\n\n**A new look**\n- **Tabs**: on top\n- One *more*\n\n## 2.5.0 — 2026-10-31\n- Old\n"
        assertEquals(listOf(Changelog.Line("A new look", true), Changelog.Line("Tabs: on top", false), Changelog.Line("One more", false)), Changelog.section(text, "3.0.0"))
        assertEquals(listOf(Changelog.Line("Old", false)), Changelog.section(text, "2.5.0"))
        assertEquals(emptyList<Changelog.Line>(), Changelog.section(text, "9.9.9"))
    }

    @Test fun `a report keeps no tokens, links, addresses or e-mails`() {
        val s = ProblemReport.scrub("GET https://calendar.google.com/calendar/ical/secret/basic.ics?t=abc token=xyz Bearer abc.def eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.sig from dan@example.com at 192.168.1.107")
        assertFalse(s, s.contains("secret"))
        assertFalse(s, s.contains("abc"))
        assertFalse(s, s.contains("xyz"))
        assertFalse(s, s.contains("eyJ"))
        assertFalse(s, s.contains("dan@"))
        assertFalse(s, s.contains("192.168"))
        assertTrue(s, s.contains("calendar.google.com"))
    }

    @Test fun `private settings are left out of a report`() {
        for (k in listOf("tmdbToken", "haToken", "haUrl", "haWebhook", "homePin", "phoneToken", "calLinks", "weatherCity", "bgImage")) assertTrue(k, ProblemReport.privateSetting(k))
        for (k in listOf("textSize", "perfMode", "heroAutoplay", "reduceMotion")) assertFalse(k, ProblemReport.privateSetting(k))
    }
}
