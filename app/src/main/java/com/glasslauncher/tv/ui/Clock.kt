package com.glasslauncher.tv.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun clockText(is24h: Boolean): String =
    SimpleDateFormat(if (is24h) "HH:mm" else "h:mm a", Locale.US).format(Date())
