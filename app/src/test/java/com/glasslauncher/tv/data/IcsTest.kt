package com.glasslauncher.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class IcsTest {
    private val jm = ZoneId.of("America/Jamaica")

    private fun cal(vararg events: String) = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n" + events.joinToString("") { "BEGIN:VEVENT\r\n$it\r\nEND:VEVENT\r\n" } + "END:VCALENDAR\r\n"
    private fun at(s: String, zone: ZoneId = jm) = ZonedDateTime.of(java.time.LocalDateTime.parse(s), zone).toInstant()
    private fun starts(text: String, from: String, to: String) =
        Ics.between(Ics.parse(text, jm), at(from), at(to)).map { it.start.atZone(jm).toLocalDateTime().toString() }

    @Test fun `a single event, folded and escaped`() {
        val text = cal("UID:a\r\nSUMMARY:Dentist\\, then\r\n  shopping\r\nLOCATION:Main St\\; 2\r\nDTSTART:20261104T140000Z\r\nDTEND:20261104T150000Z\r\nBEGIN:VALARM\r\nSUMMARY:Alarm\r\nEND:VALARM")
        val o = Ics.between(Ics.parse(text, jm), at("2026-11-03T00:00"), at("2026-11-10T00:00")).single()
        assertEquals("Dentist, then shopping", o.title)
        assertEquals("Main St; 2", o.location)
        assertEquals(Instant.parse("2026-11-04T14:00:00Z"), o.start)
        assertEquals(Instant.parse("2026-11-04T15:00:00Z"), o.end)
    }

    @Test fun `time zones, all-day events and durations`() {
        val text = cal(
            "UID:b\r\nSUMMARY:London call\r\nDTSTART;TZID=Europe/London:20261105T090000\r\nDURATION:PT30M",
            "UID:c\r\nSUMMARY:Holiday\r\nDTSTART;VALUE=DATE:20261106\r\nDTEND;VALUE=DATE:20261107",
            "UID:d\r\nSUMMARY:Trip\r\nDTSTART;VALUE=DATE:20261107\r\nDURATION:P1DT2H",
        )
        val o = Ics.between(Ics.parse(text, jm), at("2026-11-03T00:00"), at("2026-11-10T00:00"))
        assertEquals(listOf("London call", "Holiday", "Trip"), o.map { it.title })
        assertEquals(at("2026-11-05T09:00", ZoneId.of("Europe/London")), o[0].start)
        assertEquals(o[0].start.plusSeconds(1800), o[0].end)
        assertTrue(o[1].allDay)
        assertEquals(at("2026-11-06T00:00"), o[1].start)
        assertEquals(java.time.Duration.ofHours(26), java.time.Duration.between(o[2].start, o[2].end))
    }

    @Test fun `weekly on given days, every other week, with a skipped and a moved one`() {
        val text = cal(
            "UID:w\r\nSUMMARY:Gym\r\nDTSTART;TZID=America/Jamaica:20261005T070000\r\nRRULE:FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,TH\r\n" +
                "EXDATE;TZID=America/Jamaica:20261102T070000",
            "UID:w\r\nSUMMARY:Gym (late)\r\nRECURRENCE-ID;TZID=America/Jamaica:20261105T070000\r\nDTSTART;TZID=America/Jamaica:20261105T190000",
        )
        // Weeks of 5 Oct, 19 Oct, 2 Nov, 16 Nov: Monday 2 Nov skipped, Thursday 5 Nov moved to the evening.
        assertEquals(listOf("2026-11-05T19:00", "2026-11-16T07:00", "2026-11-19T07:00"), starts(text, "2026-11-01T00:00", "2026-11-22T00:00"))
    }

    @Test fun `monthly on the second Tuesday and the last Friday, yearly birthdays`() {
        val text = cal(
            "UID:m\r\nSUMMARY:Club\r\nDTSTART:20260113T180000\r\nRRULE:FREQ=MONTHLY;BYDAY=2TU",
            "UID:f\r\nSUMMARY:Payday\r\nDTSTART;VALUE=DATE:20260130\r\nRRULE:FREQ=MONTHLY;BYDAY=-1FR",
            "UID:y\r\nSUMMARY:Mum's birthday\r\nDTSTART;VALUE=DATE:19801120\r\nRRULE:FREQ=YEARLY",
        )
        assertEquals(listOf("2026-11-10T18:00", "2026-11-20T00:00", "2026-11-27T00:00"), starts(text, "2026-11-01T00:00", "2026-12-01T00:00"))
    }

    @Test fun `counts, ends and old daily events`() {
        val text = cal(
            "UID:c\r\nSUMMARY:Course\r\nDTSTART:20261102T100000\r\nRRULE:FREQ=DAILY;COUNT=3",
            "UID:u\r\nSUMMARY:Ends\r\nDTSTART:20261101T120000\r\nRRULE:FREQ=DAILY;UNTIL=20261103",
            "UID:o\r\nSUMMARY:Pills\r\nDTSTART:20100101T080000\r\nRRULE:FREQ=DAILY",
            "UID:x\r\nSUMMARY:Off\r\nSTATUS:CANCELLED\r\nDTSTART:20261104T100000",
        )
        val o = Ics.between(Ics.parse(text, jm), at("2026-11-03T00:00"), at("2026-11-06T00:00"))
        assertEquals(listOf(
            "Pills 2026-11-03T08:00", "Course 2026-11-03T10:00", "Ends 2026-11-03T12:00",
            "Pills 2026-11-04T08:00", "Course 2026-11-04T10:00", "Pills 2026-11-05T08:00",
        ), o.map { it.title + " " + it.start.atZone(jm).toLocalDateTime() })
    }

    @Test fun `an event going on now counts, and its time reads Now`() {
        val text = cal("UID:n\r\nSUMMARY:Meeting\r\nDTSTART:20261103T090000\r\nDTEND:20261103T110000")
        val now = at("2026-11-03T10:00")
        val o = Ics.between(Ics.parse(text, jm), now, at("2026-11-04T00:00")).single()
        assertEquals("Now", Ics.whenText(o, now, jm))
        assertEquals("Today 09:00", Ics.whenText(o, at("2026-11-03T08:00"), jm))
        assertEquals("Tomorrow 09:00", Ics.whenText(o, at("2026-11-02T08:00"), jm))
        assertEquals("Tue 09:00", Ics.whenText(o, at("2026-10-29T08:00"), jm))
        assertEquals("Tue 3 Nov 09:00", Ics.whenText(o, at("2026-10-20T08:00"), jm))
    }
}
