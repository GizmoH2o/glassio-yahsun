package com.glasslauncher.tv.ui

/** Swallows a key for a moment after a page change, so presses meant for the old page don't run on into the next. */
object NavGate {
    @Volatile private var key = 0
    @Volatile private var until = 0L
    fun hold(keyCode: Int, ms: Long) { key = keyCode; until = android.os.SystemClock.uptimeMillis() + ms }
    fun blocks(keyCode: Int): Boolean = keyCode == key && android.os.SystemClock.uptimeMillis() < until
}

/** The colours offered for Settings' switches and for a row while you change it (Settings › Menu & themes). */
val SETTINGS_ACCENTS = linkedMapOf("burgundy" to ("Burgundy" to 0xFFA3243F.toInt()), "green" to ("Green" to 0xFF34C759.toInt()), "purple" to ("Purple" to 0xFF7B61FF.toInt()))

/** A row being changed (moved, edited), in the chosen accent. */
@androidx.compose.runtime.Composable
fun editAccent(): androidx.compose.ui.graphics.Color = accentOf((androidx.compose.ui.platform.LocalContext.current.applicationContext as com.glasslauncher.tv.GlassApp).settings.settingsAccent).copy(alpha = 0.55f)

fun accentOf(key: String): androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color(SETTINGS_ACCENTS[key]?.second ?: SETTINGS_ACCENTS.getValue("burgundy").second)
