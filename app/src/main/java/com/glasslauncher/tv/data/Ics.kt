package com.glasslauncher.tv.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * Calendars as iCal files (Google Calendar's "secret address in iCal format", Outlook's and Apple's published ones):
 * the events, and when each happens in a stretch of time, repeating ones included (daily, weekly on given days,
 * monthly on a day or "the second Tuesday", yearly; every few; a count or an end; skipped and moved ones). Pure (no
 * Android), so it's tested on the computer.
 */
object Ics {
    data class Event(
        val uid: String, val title: String, val location: String,
        val start: ZonedDateTime, val allDay: Boolean, val length: java.time.Duration,
        val rule: Map<String, String>?, val skipped: Set<Instant>, val movedFrom: Instant?, val cancelled: Boolean,
    )

    /** One time an event happens. */
    data class Occurrence(val title: String, val location: String, val start: Instant, val end: Instant, val allDay: Boolean)

    fun parse(text: String, zone: ZoneId = ZoneId.systemDefault()): List<Event> {
        // Long lines go on in the next one, after a space or a tab.
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').replace("\n ", "").replace("\n\t", "").split('\n')
        val out = ArrayList<Event>()
        var cur: MutableList<Pair<String, String>>? = null
        var depth = 0
        for (line in lines) {
            when {
                line.equals("BEGIN:VEVENT", true) -> { cur = ArrayList(); depth = 0 }
                cur != null && line.startsWith("BEGIN:", true) -> depth++     // an alarm inside the event
                cur != null && line.startsWith("END:", true) && depth > 0 -> depth--
                line.equals("END:VEVENT", true) -> { cur?.let { event(it, zone)?.let(out::add) }; cur = null }
                cur != null && depth == 0 -> splitLine(line)?.let { cur.add(it) }
            }
        }
        return out
    }

    /** "DTSTART;TZID=Europe/London:20261104T090000" → ("DTSTART;TZID=Europe/London", "20261104T090000"): the first ':' outside quotes. */
    private fun splitLine(line: String): Pair<String, String>? {
        var quoted = false
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') quoted = !quoted
            else if (c == ':' && !quoted) return line.substring(0, i) to line.substring(i + 1)
        }
        return null
    }

    private fun name(key: String) = key.substringBefore(';').uppercase()
    private fun param(key: String, p: String) = key.split(';').drop(1).firstOrNull { it.substringBefore('=').equals(p, true) }
        ?.substringAfter('=')?.trim('"')

    private fun unescape(v: String) = v.replace("\\n", "\n").replace("\\N", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\").trim()

    /** A date or date-time and whether it's a whole day, in its time zone (its TZID, UTC for a "Z", else [zone]). */
    private fun time(key: String, value: String, zone: ZoneId): Pair<ZonedDateTime, Boolean>? = runCatching {
        val v = value.trim()
        if (param(key, "VALUE").equals("DATE", true) || v.length == 8) {
            return LocalDate.parse(v.take(8), DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(zone) to true
        }
        val local = LocalDateTime.parse(v.removeSuffix("Z").take(15), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
        val z = when {
            v.endsWith("Z") -> ZoneOffset.UTC
            else -> param(key, "TZID")?.let { id -> runCatching { ZoneId.of(id) }.getOrNull() } ?: zone
        }
        local.atZone(z) to false
    }.getOrNull()

    private fun event(props: List<Pair<String, String>>, zone: ZoneId): Event? {
        fun first(n: String) = props.firstOrNull { name(it.first) == n }
        val (startKey, startValue) = first("DTSTART") ?: return null
        val (start, allDay) = time(startKey, startValue, zone) ?: return null
        val end = first("DTEND")?.let { time(it.first, it.second, zone)?.first }
        val length = when {
            end != null -> java.time.Duration.between(start, end)
            first("DURATION") != null -> duration(first("DURATION")!!.second)
            else -> null
        } ?: if (allDay) java.time.Duration.ofDays(1) else java.time.Duration.ZERO
        val rule = first("RRULE")?.second?.split(';')?.mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].uppercase() to it[1] } }?.toMap()
        val skipped = props.filter { name(it.first) == "EXDATE" }.flatMap { (k, v) -> v.split(',').mapNotNull { time(k, it, zone)?.first?.toInstant() } }.toSet()
        return Event(
            uid = first("UID")?.second.orEmpty(), title = unescape(first("SUMMARY")?.second.orEmpty()).ifBlank { "(No title)" },
            location = unescape(first("LOCATION")?.second.orEmpty()), start = start, allDay = allDay, length = length.coerceAtLeast(java.time.Duration.ZERO),
            rule = rule, skipped = skipped, movedFrom = first("RECURRENCE-ID")?.let { time(it.first, it.second, zone)?.first?.toInstant() },
            cancelled = first("STATUS")?.second.equals("CANCELLED", true),
        )
    }

    /** "P1W", "P1D", "PT1H30M", "P1DT2H" (weeks, days, hours, minutes, seconds). */
    internal fun duration(v: String): java.time.Duration? {
        val m = Regex("^P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?$").find(v.trim().uppercase().removePrefix("+")) ?: return null
        fun g(i: Int) = m.groupValues[i].toLongOrNull() ?: 0L
        return java.time.Duration.ofDays(g(1) * 7 + g(2)).plusHours(g(3)).plusMinutes(g(4)).plusSeconds(g(5))
    }

    private val DAYS = mapOf("MO" to DayOfWeek.MONDAY, "TU" to DayOfWeek.TUESDAY, "WE" to DayOfWeek.WEDNESDAY, "TH" to DayOfWeek.THURSDAY,
        "FR" to DayOfWeek.FRIDAY, "SA" to DayOfWeek.SATURDAY, "SU" to DayOfWeek.SUNDAY)

    /** "2TU" → (2, Tuesday); "MO" → (0, Monday); "-1FR" → (-1, Friday). */
    private fun byDay(v: String): List<Pair<Int, DayOfWeek>> = v.split(',').mapNotNull { d ->
        val t = d.trim().uppercase()
        val day = DAYS[t.takeLast(2)] ?: return@mapNotNull null
        (t.dropLast(2).toIntOrNull() ?: 0) to day
    }

    /** The [n]th [day] of the month of [m] (negative: from the end), or null when the month hasn't one. */
    private fun nthDay(m: LocalDate, n: Int, day: DayOfWeek): LocalDate? {
        val d = if (n >= 0) m.with(TemporalAdjusters.dayOfWeekInMonth(n.coerceAtLeast(1), day))
        else m.with(TemporalAdjusters.lastInMonth(day)).minusWeeks((-n - 1).toLong())
        return d.takeIf { it.month == m.month }
    }

    /**
     * Every time the events happen from [from] to [to] (overlapping it: one going on at [from] counts), soonest first.
     * Moved occurrences replace their original time; cancelled ones and skipped dates are left out.
     */
    fun between(events: List<Event>, from: Instant, to: Instant): List<Occurrence> {
        val moved = events.filter { it.movedFrom != null }.map { it.uid to it.movedFrom!! }.toSet()
        val out = ArrayList<Occurrence>()
        fun add(e: Event, start: ZonedDateTime) {
            val s = start.toInstant()
            val end = start.plus(e.length).toInstant().let { if (it == s && !e.allDay) s else it }
            if (end.isAfter(from) || (end == s && !s.isBefore(from))) if (s.isBefore(to)) out += Occurrence(e.title, e.location, s, end, e.allDay)
        }
        for (e in events) {
            if (e.cancelled) continue
            if (e.movedFrom != null || e.rule == null) { add(e, e.start); continue }
            for (t in expand(e, from, to)) {
                val i = t.toInstant()
                if (i in e.skipped || (e.uid to i) in moved) continue
                add(e, t)
            }
        }
        return out.sortedBy { it.start }
    }

    /**
     * A repeating event's times up to [to]: from its first (to count them, with COUNT), else from a little before
     * [from] (a daily one since 2010 would take thousands of steps).
     */
    private fun expand(e: Event, from: Instant, to: Instant): Sequence<ZonedDateTime> = sequence {
        val r = e.rule ?: return@sequence
        val freq = r["FREQ"]?.uppercase() ?: return@sequence
        val every = (r["INTERVAL"]?.toIntOrNull() ?: 1).coerceAtLeast(1).toLong()
        val count = r["COUNT"]?.toIntOrNull()
        val until = r["UNTIL"]?.let { u -> time(if (u.length == 8) "UNTIL;VALUE=DATE" else "UNTIL", u, e.start.zone)?.first?.let { t ->
            // A date: the whole of that day.
            if (u.length == 8) t.plusDays(1).minusNanos(1) else t
        } }
        val days = r["BYDAY"]?.let(::byDay).orEmpty()
        val monthDays = r["BYMONTHDAY"]?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
        val months = r["BYMONTH"]?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
        val first = e.start
        val time = first.toLocalTime()
        val zone = first.zone
        var made = 0
        var steps = 0
        fun at(d: LocalDate) = d.atTime(time).atZone(zone)
        // Each period's dates (a day, a week, a month, a year), in order.
        fun period(p: Long): List<LocalDate> {
            val base = first.toLocalDate()
            return when (freq) {
                "DAILY" -> listOf(base.plusDays(p * every))
                "WEEKLY" -> {
                    val week = base.plusWeeks(p * every).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    if (days.isEmpty()) listOf(base.plusWeeks(p * every)) else days.map { week.with(TemporalAdjusters.nextOrSame(it.second)) }.sorted()
                }
                "MONTHLY" -> {
                    val m = base.withDayOfMonth(1).plusMonths(p * every)
                    when {
                        days.isNotEmpty() -> days.mapNotNull { (n, d) -> if (n == 0) null else nthDay(m, n, d) } +
                            days.filter { it.first == 0 }.flatMap { (_, d) -> generateSequence(m.with(TemporalAdjusters.firstInMonth(d))) { it.plusWeeks(1) }.takeWhile { it.month == m.month }.toList() }
                        monthDays.isNotEmpty() -> monthDays.mapNotNull { n -> runCatching { if (n > 0) m.withDayOfMonth(n) else m.withDayOfMonth(m.lengthOfMonth() + n + 1) }.getOrNull() }
                        else -> listOfNotNull(runCatching { m.withDayOfMonth(base.dayOfMonth) }.getOrNull())
                    }.sorted()
                }
                "YEARLY" -> {
                    val y = base.plusYears(p * every).year
                    val ms = months.ifEmpty { listOf(base.monthValue) }
                    ms.flatMap { mo ->
                        val m = LocalDate.of(y, mo, 1)
                        when {
                            days.isNotEmpty() -> days.mapNotNull { (n, d) -> nthDay(m, if (n == 0) 1 else n, d) }
                            monthDays.isNotEmpty() -> monthDays.mapNotNull { n -> runCatching { m.withDayOfMonth(n) }.getOrNull() }
                            else -> listOfNotNull(runCatching { m.withDayOfMonth(base.dayOfMonth) }.getOrNull())  // 29 Feb: leap years only
                        }
                    }.sorted()
                }
                else -> emptyList()
            }
        }
        var p = if (count != null || !from.isAfter(first.toInstant())) 0L else {
            val f = from.atZone(zone).toLocalDate()
            val b = first.toLocalDate()
            val gone = when (freq) {
                "DAILY" -> ChronoUnit.DAYS.between(b, f)
                "WEEKLY" -> ChronoUnit.WEEKS.between(b, f)
                "MONTHLY" -> ChronoUnit.MONTHS.between(b.withDayOfMonth(1), f.withDayOfMonth(1))
                else -> ChronoUnit.YEARS.between(b, f)
            }
            // A period early: one that began before [from] may still be going on (a long event).
            (gone / every - 1).coerceAtLeast(0)
        }
        while (steps < 5000) {
            for (d in period(p)) {
                val t = at(d)
                if (t.isBefore(first)) continue
                if (until != null && t.isAfter(until)) return@sequence
                if (count != null && made >= count) return@sequence
                if (!t.toInstant().isBefore(to)) return@sequence
                made++
                yield(t)
            }
            p++; steps++
        }
    }

    /** What a calendar card says when: "Now", "Today 14:00", "Tomorrow", "Fri 09:30", "Mon 17 Nov". */
    fun whenText(o: Occurrence, now: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
        if (!o.start.isAfter(now) && o.end.isAfter(now) && !o.allDay) return "Now"
        val day = o.start.atZone(zone).toLocalDate()
        val today = now.atZone(zone).toLocalDate()
        val diff = ChronoUnit.DAYS.between(today, day)
        val clock = if (o.allDay) "" else " " + o.start.atZone(zone).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))
        val d = when {
            diff <= 0 -> "Today"
            diff == 1L -> "Tomorrow"
            diff < 7 -> day.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)
            else -> day.format(DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.ENGLISH))
        }
        return d + clock
    }
}
