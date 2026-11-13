package com.glasslauncher.tv.ui

import java.text.SimpleDateFormat
import java.util.Date

/**
 * Dates and times written one way everywhere, in Glassio's language, the time as the clock has it (12 or 24 hours,
 * Settings › Look & motion › 24-hour clock): "9:30 PM" beside a clock saying "7:01 PM", not "21:30".
 */
object Dates {
    /** The clock's choice (Settings.clock24h keeps it current). */
    @Volatile var h24 = false

    fun time(at: Long): String = SimpleDateFormat(if (h24) "HH:mm" else "h:mm a", Lang.locale()).format(Date(at))
    /** "Fri 21 Nov". */
    fun shortDay(at: Long): String = SimpleDateFormat("EEE d MMM", Lang.locale()).format(Date(at))
    /** "Friday". */
    fun weekday(at: Long): String = SimpleDateFormat("EEEE", Lang.locale()).format(Date(at))
    /** "Friday 21 November". */
    fun longDay(at: Long): String = SimpleDateFormat("EEEE d MMMM", Lang.locale()).format(Date(at))
}
