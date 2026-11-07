package com.glasslauncher.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/** Calendar files for the phone (Add to calendar, and the feed): as calendars read them, and as Glassio's own reader does. */
class IcsWriterTest {
    private val start = Instant.parse("2026-11-07T15:00:00Z").toEpochMilli()
    private val end = Instant.parse("2026-11-07T17:00:00Z").toEpochMilli()
    private val stamp = Instant.parse("2026-11-07T09:00:00Z").toEpochMilli()
    private fun one() = IcsWriter.Item("glassio-remind-12-$start@glassio", "Arsenal v Hull City", "637 TNT Sports 1 UK", "Reminder set in Glassio", start, end)

    @Test fun `an event's lines`() {
        val ics = IcsWriter.calendar("Glassio", listOf(one()), stamp, 15)
        assertTrue(ics.startsWith("BEGIN:VCALENDAR\r\n"))
        assertTrue(ics.endsWith("END:VCALENDAR\r\n"))
        for (l in listOf("VERSION:2.0", "BEGIN:VEVENT", "UID:glassio-remind-12-$start@glassio", "DTSTAMP:20261107T090000Z",
                "DTSTART:20261107T150000Z", "DTEND:20261107T170000Z", "SUMMARY:Arsenal v Hull City", "LOCATION:637 TNT Sports 1 UK",
                "BEGIN:VALARM", "TRIGGER:-PT15M", "ACTION:DISPLAY", "END:VALARM", "END:VEVENT", "X-WR-CALNAME:Glassio"))
            assertTrue(l, ics.contains("\r\n$l\r\n") || ics.startsWith("$l\r\n"))
    }

    @Test fun `every line ends with CR LF, none longer than 75`() {
        val long = one().copy(title = "Premier League 26/27: Arsenal versus Hull City, live from the Emirates Stadium in London, with the build-up")
        val ics = IcsWriter.calendar("Glassio", listOf(long), stamp, 15)
        assertFalse(ics.replace("\r\n", "").contains("\n"))
        ics.split("\r\n").forEach { assertTrue(it, it.toByteArray(Charsets.UTF_8).size <= 75) }
    }

    @Test fun `commas, semicolons, backslashes and new lines are escaped`() {
        assertEquals("a\\, b\\; c\\\\ d\\ne", IcsWriter.escape("a, b; c\\ d\ne"))
    }

    @Test fun `no alert when none is asked for`() {
        assertFalse(IcsWriter.calendar("Glassio", listOf(one()), stamp, 0).contains("VALARM"))
    }

    @Test fun `the same reminder, the same id`() {
        assertEquals(IcsWriter.uid("remind", 12, start), IcsWriter.uid("remind", 12, start))
        assertTrue(IcsWriter.uid("remind", 12, start) != IcsWriter.uid("record", 12, start))
    }

    @Test fun `read back as Glassio reads a calendar`() {
        val long = one().copy(title = "Premier League 26/27: Arsenal versus Hull City, live from the Emirates, with a semicolon; and more")
        val back = Ics.parse(IcsWriter.calendar("Glassio", listOf(long, one()), stamp, 15), ZoneOffset.UTC)
        assertEquals(2, back.size)
        val o = Ics.between(back, Instant.ofEpochMilli(start - 1), Instant.ofEpochMilli(end))
        assertEquals(setOf(long.title, one().title), o.map { it.title }.toSet())
        assertEquals(Instant.ofEpochMilli(start), o.first().start)
        assertEquals("637 TNT Sports 1 UK", o.first().location)
    }
}
