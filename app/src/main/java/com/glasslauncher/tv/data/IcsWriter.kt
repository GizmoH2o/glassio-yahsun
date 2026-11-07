package com.glasslauncher.tv.data

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Calendar files (RFC 5545) for the phone page (Settings › System › Calendar on the phone page): one show's ("Add to
 * calendar"), or the feed of what's set and the matches you follow. Times in UTC, every line ended by CR LF and folded
 * at 75 bytes, text escaped; the same thing always the same UID, so a calendar updates it rather than adding another.
 */
object IcsWriter {
    /** A show for the calendar: its id, title, where (the channel), a note, and its times (ms). */
    data class Item(val uid: String, val title: String, val location: String, val note: String, val start: Long, val end: Long)

    private val UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    /** The id of a reminder, a recording or a followed match: one per kind, channel and start. */
    @JvmStatic
    fun uid(kind: String, channel: Int, start: Long) = "glassio-$kind-$channel-$start@glassio"

    fun escape(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\r\n", "\\n").replace("\n", "\\n")

    private fun time(ms: Long) = UTC.format(Instant.ofEpochMilli(ms))

    /** The calendar called `name` with `items`, made at `now`; an alert `alarmMinutes` before each (0: none). */
    @JvmStatic
    fun calendar(name: String, items: List<Item>, now: Long, alarmMinutes: Int): String {
        val out = StringBuilder()
        fun line(s: String) = fold(s, out)
        line("BEGIN:VCALENDAR")
        line("VERSION:2.0")
        line("PRODID:-//Glassio Yahsun//Live TV//EN")
        line("CALSCALE:GREGORIAN")
        line("METHOD:PUBLISH")
        line("X-WR-CALNAME:" + escape(name))
        line("REFRESH-INTERVAL;VALUE=DURATION:PT1H")
        line("X-PUBLISHED-TTL:PT1H")
        for (it in items) {
            line("BEGIN:VEVENT")
            line("UID:" + it.uid)
            line("DTSTAMP:" + time(now))
            line("DTSTART:" + time(it.start))
            line("DTEND:" + time(maxOf(it.end, it.start + 60_000L)))
            line("SUMMARY:" + escape(it.title))
            if (it.location.isNotBlank()) line("LOCATION:" + escape(it.location))
            if (it.note.isNotBlank()) line("DESCRIPTION:" + escape(it.note))
            if (alarmMinutes > 0) {
                line("BEGIN:VALARM")
                line("ACTION:DISPLAY")
                line("DESCRIPTION:" + escape(it.title))
                line("TRIGGER:-PT${alarmMinutes}M")
                line("END:VALARM")
            }
            line("END:VEVENT")
        }
        line("END:VCALENDAR")
        return out.toString()
    }

    /** `s` as lines of at most 75 bytes (the next ones after a space), never splitting a character. */
    private fun fold(s: String, out: StringBuilder) {
        var used = 0
        var first = true
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val n = Character.charCount(cp)
            val bytes = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            val room = if (first) 75 else 74
            if (used + bytes > room) {
                out.append("\r\n ")
                used = 0
                first = false
            }
            out.appendCodePoint(cp)
            used += bytes
            i += n
        }
        out.append("\r\n")
    }
}
