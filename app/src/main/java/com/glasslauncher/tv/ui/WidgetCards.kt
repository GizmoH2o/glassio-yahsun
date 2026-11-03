package com.glasslauncher.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glasslauncher.tv.data.HomeWidgets
import com.glasslauncher.tv.data.Ics
import com.glasslauncher.tv.data.WeatherDay
import java.time.Instant
import java.time.LocalDate
import kotlin.math.roundToInt

/** The widgets row's cards: as tall as Airing this week's, the wide ones twice as wide. */
private val WIDE = AIRING_W * 2f
private val SMALL = AIRING_W * 0.82f
private val TALL = AIRING_W * 0.58f

/** A widget card: dark, the selected look of the other cards; OK [onClick] (read again), hold OK [onHold]. */
@Composable
private fun WidgetCard(s: Float, width: Float, modifier: Modifier, onClick: () -> Unit, onHold: (() -> Unit)?, content: @Composable () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier.width((width * s).dp).height((TALL * s).dp)
            .onFocusChanged { focused = it.isFocused }
            .tvPress(onLong = onHold, onKey = backKey(), onClick = onClick)
            .cardFocus(s, focused, 5f)
            .background(if (focused) Color(0xFF2A2A2F) else CARD)
            .padding(horizontal = (7 * s).dp, vertical = (5 * s).dp),
    ) { content() }
}

private fun deg(c: Double, f: Boolean) = if (c.isNaN()) "–" else ((if (f) c * 9 / 5 + 32 else c).roundToInt().toString() + "°")

/** The weather: today large (the place, the icon, the high and low), the next four days beside it. */
@Composable
internal fun WeatherWidget(city: String, nowTemp: String, days: List<WeatherDay>, fahrenheit: Boolean, s: Float, modifier: Modifier, onClick: () -> Unit) {
    WidgetCard(s, WIDE, modifier, onClick, null) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            val today = days.firstOrNull()
            Column(Modifier.weight(1f)) {
                Text(city, color = Color.White.copy(alpha = 0.65f), fontSize = (6.5f * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (today != null) WeatherIcon(today.icon, Modifier.size((15 * s).dp))
                    Spacer(Modifier.width((4 * s).dp))
                    Text(nowTemp, color = Color.White, fontSize = (16 * s).sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
                if (today != null) Text("${today.condition} · ${deg(today.hiC, fahrenheit)} / ${deg(today.loC, fahrenheit)}",
                    color = Color.White.copy(alpha = 0.75f), fontSize = (6 * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            days.drop(1).take(4).forEach { d ->
                Column(Modifier.width((22 * s).dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy((2 * s).dp)) {
                    val name = runCatching { LocalDate.parse(d.date).dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH) }.getOrDefault("")
                    Text(name, color = Color.White.copy(alpha = 0.65f), fontSize = (6 * s).sp, maxLines = 1)
                    WeatherIcon(d.icon, Modifier.size((10 * s).dp))
                    Text(deg(d.hiC, fahrenheit), color = Color.White, fontSize = (6.5f * s).sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(deg(d.loC, fahrenheit), color = Color.White.copy(alpha = 0.55f), fontSize = (6 * s).sp, maxLines = 1)
                }
            }
        }
    }
}

/** The calendars' next three events: when (in the accent colour) and what. */
@Composable
internal fun CalendarWidget(agenda: HomeWidgets.Agenda?, now: Instant, s: Float, modifier: Modifier, onClick: () -> Unit) {
    val accent = LocalAccent.current
    WidgetCard(s, WIDE, modifier, onClick, null) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy((2.5f * s).dp)) {
            Text("CALENDAR", color = Color.White.copy(alpha = 0.55f), fontSize = (5.5f * s).sp, fontWeight = FontWeight.Bold, letterSpacing = (1 * s).sp)
            val next = agenda?.next.orEmpty().take(3)
            when {
                agenda == null -> Text("Reading…", color = Color.White.copy(alpha = 0.6f), fontSize = (7 * s).sp)
                next.isEmpty() && agenda.failed > 0 -> Text("Couldn't read the calendar: check its link in Settings › Home", color = Color.White.copy(alpha = 0.7f),
                    fontSize = (6.5f * s).sp, maxLines = 2)
                next.isEmpty() -> Text("Nothing in the next 30 days", color = Color.White.copy(alpha = 0.7f), fontSize = (7 * s).sp)
                else -> next.forEach { o ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(Ics.whenText(o, now), color = accent, fontSize = (6.5f * s).sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false,
                            modifier = Modifier.width((50 * s).dp))
                        Text(o.title, color = Color.White, fontSize = (7 * s).sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** A Home Assistant reading: its name, its value large with the unit. */
@Composable
internal fun HaWidget(r: HomeWidgets.Reading, s: Float, modifier: Modifier, onClick: () -> Unit, onHold: () -> Unit) {
    WidgetCard(s, SMALL, modifier, onClick, onHold) {
        Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            Text(r.name, color = Color.White.copy(alpha = 0.7f), fontSize = (6.5f * s).sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = (8 * s).sp)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(r.value, color = Color.White, fontSize = (if (r.value.length > 8) 9f else 13f).times(s).sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (r.unit.isNotBlank()) Text((if (r.unit.startsWith("°") && r.unit.length == 1) "" else " ") + r.unit, color = Color.White.copy(alpha = 0.7f), fontSize = (7 * s).sp, maxLines = 1,
                    modifier = Modifier.padding(bottom = (1.5f * s).dp))
            }
        }
    }
}
