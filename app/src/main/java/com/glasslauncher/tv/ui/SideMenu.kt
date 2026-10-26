package com.glasslauncher.tv.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glasslauncher.tv.GlassApp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val EASE = CubicBezierEasing(0.22f, 0f, 0f, 1f)

private val THEMES = listOf(
    "bow" to "Black on white", "amoled" to "Black AMOLED", "liquid" to "Liquid",
    "clear" to "Clear", "water_still" to "Glass still", "water" to "Glass is glass",
)

/**
 * The menu at the settings button, like the Apple TV Control Center: time, date and weather on top,
 * then Live TV, Following, Library, Glassio settings, Android settings, Wi-Fi and Themes. ◀ or Back closes it.
 */
@Composable
fun SideMenu(app: GlassApp, s: Float, onClose: () -> Unit, onLibrary: () -> Unit, onLiveTv: () -> Unit, onFollowing: () -> Unit,
             onSettings: () -> Unit, modifier: Modifier = Modifier) {
    val st = app.settings
    val ctx = LocalContext.current
    val style = st.menuStyle
    val light = style == "bow"
    val ink = if (light) Color(0xFF111114) else Color.White
    val soft = ink.copy(alpha = if (light) 0.55f else 0.62f)
    var themes by remember { mutableStateOf(false) }
    var bgPage by remember { mutableStateOf(false) }
    // One requester per row: after a choice (the menu redraws in the new theme) focus comes back to the same row.
    val reqs = remember { List(16) { FocusRequester() } }
    var target by remember { mutableIntStateOf(0) }
    var current by remember { mutableIntStateOf(0) }
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(260, easing = EASE)) }
    var inside by remember { mutableStateOf(false) }
    // Take focus from the settings button (or wherever it is): retry until a row has it.
    LaunchedEffect(themes, bgPage, style) { inside = false; repeat(16) { delay(25); if (inside) return@LaunchedEffect; runCatching { reqs[target.coerceIn(0, reqs.lastIndex)].requestFocus() } } }
    var themesRow by remember { mutableIntStateOf(0) }
    var bgRow by remember { mutableIntStateOf(0) }
    BackHandler { if (themes) { target = themesRow; themes = false } else if (bgPage) { target = bgRow; bgPage = false } else onClose() }

    val time by produceState(clockText(st.clock24h), st.clock24h) { while (true) { value = clockText(st.clock24h); delay(5_000) } }
    val date by produceState(longDate(), Unit) { while (true) { value = longDate(); delay(60_000) } }
    val wx by app.weather.now.collectAsState()

    Box(
        modifier
            .onFocusChanged { inside = it.hasFocus }
            .padding(top = (14 * s).dp, end = (22 * s).dp)
            .width((272 * s).dp)
            .graphicsLayer {
                alpha = enter.value
                translationX = (1f - enter.value) * 40 * s * density
                val k = 0.96f + 0.04f * enter.value; scaleX = k; scaleY = k
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0f)
            },
    ) {
        GlassPanel(Modifier.fillMaxWidth(), radius = (24 * s).dp, style = style, darken = 0.25f) {
            Column(Modifier.fillMaxWidth().padding((14 * s).dp)) {
                // Header: time and date, weather on the right.
                if (st.menuClock || st.menuDate || st.menuWeather) Row(Modifier.fillMaxWidth().padding(start = (6 * s).dp, end = (6 * s).dp, top = (2 * s).dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        if (st.menuClock) Text(time, color = ink, fontSize = (30 * s).sp, fontWeight = FontWeight.Light, letterSpacing = (-0.5f * s).sp)
                        if (st.menuDate) Text(date, color = soft, fontSize = (10 * s).sp, fontWeight = FontWeight.Medium)
                    }
                    val w = wx
                    if (st.menuWeather && w != null) Column(horizontalAlignment = Alignment.End) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            WeatherIcon(w.icon, Modifier.size((18 * s).dp), ink)
                            Spacer(Modifier.width((5 * s).dp))
                            Text(w.temp(st.weatherF), color = ink, fontSize = (22 * s).sp, fontWeight = FontWeight.Light)
                        }
                        if (w.condition.isNotBlank()) Text(w.condition, color = soft, fontSize = (9.5f * s).sp, fontWeight = FontWeight.Medium)
                    }
                }
                val w = wx
                if (st.menuWeather && w != null) {
                    fun t(c: Double) = if (c.isNaN()) "–" else ((if (st.weatherF) c * 9 / 5 + 32 else c).roundToInt().toString() + "°")
                    val bits = buildList {
                        if (st.menuCity) add(w.city)
                        if (!w.hiC.isNaN()) add("H ${t(w.hiC)}  L ${t(w.loC)}")
                        if (!w.feelsC.isNaN()) add("Feels ${t(w.feelsC)}")
                        if (w.humidity >= 0) add("${w.humidity}%")
                        if (!w.windKmh.isNaN()) add("${w.windKmh.roundToInt()} km/h")
                    }
                    if (bits.isNotEmpty()) Text(bits.joinToString("  ·  "), color = soft, fontSize = (8.5f * s).sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = (6 * s).dp, end = (6 * s).dp, top = (6 * s).dp), maxLines = 1)
                } else if (st.menuCity && st.menuWeather) Text(st.weatherCity, color = soft, fontSize = (8.5f * s).sp, modifier = Modifier.padding(start = (6 * s).dp, top = (6 * s).dp))
                if (st.menuClock || st.menuDate || st.menuWeather) {
                    Spacer(Modifier.height((12 * s).dp))
                    Box(Modifier.fillMaxWidth().padding(horizontal = (6 * s).dp).height(0.6.dp).background(ink.copy(alpha = 0.14f)))
                    Spacer(Modifier.height((8 * s).dp))
                }

                if (bgPage) {
                    Text("Background", color = soft, fontSize = (9 * s).sp, fontWeight = FontWeight.SemiBold, letterSpacing = (0.8f * s).sp,
                        modifier = Modifier.padding(start = (10 * s).dp, bottom = (4 * s).dp))
                    val back = { target = bgRow; bgPage = false }
                    BG_MODES.forEachIndexed { i, (key, label) ->
                        MenuRow(s, light, label, if (st.bgMode == key) "✓" else "",
                            modifier = Modifier.focusRequester(reqs[i]), onFocused = { current = i },
                            first = i == 0, last = i == BG_MODES.lastIndex, onLeft = back,
                            glyph = { c -> glyph(if (st.bgMode == key) "dotOn" else "dotOff", c) },
                            onClick = {
                                target = i
                                when {
                                    key == "image" && st.bgImage.isBlank() -> toast(ctx, "Choose a picture first: Glassio settings → Clock & glass")
                                    key == "video" && st.bgVideo.isBlank() -> toast(ctx, "Choose a video first: Glassio settings → Clock & glass")
                                    else -> st.bgMode = key
                                }
                            })
                    }
                } else if (!themes) {
                    val rows = buildList<Triple<String, String, () -> Unit>> {
                        if (st.menuLive) add(Triple("live", "Live TV") { onLiveTv() })
                        if (st.menuLive) add(Triple("following", "Following") { onFollowing() })
                        if (st.menuLibrary) add(Triple("library", "Library") { onLibrary() })
                        add(Triple("sliders", "Glassio settings") { onSettings() })
                        if (st.menuAndroid) add(Triple("gear", "Android settings") { open(ctx, android.provider.Settings.ACTION_SETTINGS) })
                        if (st.menuWifi) add(Triple("wifi", "Wi-Fi") { open(ctx, android.provider.Settings.ACTION_WIFI_SETTINGS) })
                        if (st.menuThemes) add(Triple("theme", "Themes") { target = 0; themes = true })
                        if (st.menuBackground) add(Triple("background", "Background") { target = BG_MODES.indexOfFirst { it.first == st.bgMode }.coerceAtLeast(0); bgPage = true })
                    }
                    bgRow = rows.indexOfFirst { it.first == "background" }.coerceAtLeast(0)
                    themesRow = rows.indexOfFirst { it.first == "theme" }.coerceAtLeast(0)
                    rows.forEachIndexed { i, (icon, label, act) ->
                        MenuRow(s, light, label, when (icon) { "theme" -> themeLabel(st.theme); "background" -> BG_MODES.firstOrNull { it.first == st.bgMode }?.second.orEmpty(); else -> "" },
                            chevron = icon == "theme" || icon == "background",
                            modifier = Modifier.focusRequester(reqs[i]), onFocused = { current = i },
                            first = i == 0, last = i == rows.lastIndex, onLeft = onClose, glyph = { c -> glyph(icon, c) }, onClick = act)
                    }
                } else {
                    Text("Themes", color = soft, fontSize = (9 * s).sp, fontWeight = FontWeight.SemiBold, letterSpacing = (0.8f * s).sp,
                        modifier = Modifier.padding(start = (10 * s).dp, bottom = (4 * s).dp))
                    val back = { target = themesRow; themes = false }
                    THEMES.forEachIndexed { i, (key, label) ->
                        MenuRow(s, light, label, if (st.theme == key) "✓" else "",
                            modifier = Modifier.focusRequester(reqs[i]), onFocused = { current = i },
                            first = i == 0, last = false, onLeft = back,
                            glyph = { _ -> swatch(key, light) }, onClick = { target = i; st.applyTheme(key) })
                    }
                    Spacer(Modifier.height((6 * s).dp))
                    Box(Modifier.fillMaxWidth().padding(horizontal = (6 * s).dp).height(0.6.dp).background(ink.copy(alpha = 0.14f)))
                    Spacer(Modifier.height((6 * s).dp))
                    val n = THEMES.size
                    MenuRow(s, light, "Colour on focus", if (st.focusColor) "On" else "Off",
                        modifier = Modifier.focusRequester(reqs[n]), onFocused = { current = n },
                        first = false, last = !st.focusColor, onLeft = back,
                        glyph = { c -> glyph(if (st.focusColor) "dotOn" else "dotOff", c) }, onClick = { target = n; st.focusColor = !st.focusColor })
                    if (st.focusColor) MenuRow(s, light, "Focus plate", PLATE_NAMES[st.focusPlate] ?: "Dark",
                        modifier = Modifier.focusRequester(reqs[n + 1]), onFocused = { current = n + 1 },
                        first = false, last = true, onLeft = back,
                        glyph = { c -> plateGlyph(st.focusPlate, c) },
                        onClick = { target = n + 1; st.focusPlate = PLATES[(PLATES.indexOf(st.focusPlate) + 1).mod(PLATES.size)] })
                }
            }
        }
    }
}

@Composable
private fun MenuRow(
    s: Float, light: Boolean, label: String, value: String, chevron: Boolean = false, modifier: Modifier = Modifier,
    first: Boolean, last: Boolean, onLeft: () -> Unit, glyph: DrawScope.(Color) -> Unit, onClick: () -> Unit,
    onFocused: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val lit by animateFloatAsState(if (focused) 1f else 0f, tween(120), label = "rowLit")
    val ink = if (light) Color(0xFF111114) else Color.White
    val hi = if (light) Color(0xFF111114) else Color.White
    val fg = androidx.compose.ui.graphics.lerp(ink, if (light) Color.White else Color(0xFF111114), lit)
    Row(
        modifier
            .fillMaxWidth()
            .height((34 * s).dp)
            .clip(RoundedCornerShape((12 * s).dp))
            .background(hi.copy(alpha = 0.95f * lit))
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .tvPress(onKey = { ev ->
                when {
                    ev.type != KeyEventType.KeyDown -> false
                    ev.key == Key.DirectionLeft -> { onLeft(); true }
                    ev.key == Key.DirectionRight -> true
                    ev.key == Key.DirectionUp && first -> true
                    ev.key == Key.DirectionDown && last -> true
                    else -> false
                }
            }, onClick = onClick)
            .padding(horizontal = (10 * s).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size((15 * s).dp)) { glyph(fg) }
        Spacer(Modifier.width((11 * s).dp))
        Text(label, color = fg, fontSize = (12 * s).sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        if (value.isNotBlank()) Text(value, color = fg.copy(alpha = 0.6f), fontSize = (10 * s).sp, fontWeight = FontWeight.Medium)
        if (chevron) {
            Spacer(Modifier.width((6 * s).dp))
            Canvas(Modifier.size((8 * s).dp)) {
                val p = Path().apply { moveTo(size.width * 0.32f, size.height * 0.15f); lineTo(size.width * 0.70f, size.height * 0.5f); lineTo(size.width * 0.32f, size.height * 0.85f) }
                drawPath(p, fg.copy(alpha = 0.6f), style = Stroke(size.width * 0.16f, cap = StrokeCap.Round))
            }
        }
    }
}

/** Plates behind the selected icon with "Colour on focus". */
val PLATES = listOf("light", "dark", "glass", "amoled")
val PLATE_NAMES = mapOf("light" to "White", "dark" to "Dark", "glass" to "Glass", "amoled" to "Black AMOLED")

private fun DrawScope.plateGlyph(p: String, c: Color) {
    val r = androidx.compose.ui.geometry.CornerRadius(size.width * 0.26f)
    val fill = when (p) { "light" -> Color(0xFFE4E4EA); "dark" -> Color(0xFF2A2A2E); "amoled" -> Color.Black; else -> Color.White.copy(alpha = 0.28f) }
    drawRoundRect(fill, cornerRadius = r)
    drawRoundRect(c, cornerRadius = r, style = Stroke(size.width * 0.1f))
}

private val BG_MODES = listOf("carousel" to "Title backdrops", "image" to "My picture", "video" to "My video", "aerial" to "Screensaver")

private fun themeLabel(t: String) = THEMES.firstOrNull { it.first == t }?.second ?: "Custom"

private fun open(ctx: android.content.Context, action: String) {
    for (a in listOf(action, android.provider.Settings.ACTION_SETTINGS)) {
        try { ctx.startActivity(Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) {}
    }
    toast(ctx, "This screen is not available on this device")
}

private fun longDate(): String =
    java.text.SimpleDateFormat("EEEE, d MMMM", java.util.Locale.ENGLISH).format(java.util.Date())

/** Thin line glyphs, drawn in the row's colour. */
private fun DrawScope.glyph(kind: String, c: Color) {
    val w = size.width
    val st = Stroke(w * 0.09f, cap = StrokeCap.Round)
    when (kind) {
        "library" -> {
            drawRoundRect(c, Offset(w * 0.10f, w * 0.18f), Size(w * 0.16f, w * 0.68f), CornerRadius(w * 0.04f), style = st)
            drawRoundRect(c, Offset(w * 0.36f, w * 0.10f), Size(w * 0.16f, w * 0.76f), CornerRadius(w * 0.04f), style = st)
            val p = Path().apply { moveTo(w * 0.62f, w * 0.24f); lineTo(w * 0.76f, w * 0.19f); lineTo(w * 0.94f, w * 0.80f); lineTo(w * 0.80f, w * 0.85f); close() }
            drawPath(p, c, style = st)
        }
        "live" -> {
            drawRoundRect(c, Offset(w * 0.06f, w * 0.24f), Size(w * 0.88f, w * 0.62f), CornerRadius(w * 0.12f), style = st)
            drawLine(c, Offset(w * 0.34f, w * 0.06f), Offset(w * 0.5f, w * 0.22f), w * 0.08f, StrokeCap.Round)
            drawLine(c, Offset(w * 0.66f, w * 0.06f), Offset(w * 0.5f, w * 0.22f), w * 0.08f, StrokeCap.Round)
            drawCircle(c, w * 0.08f, Offset(w * 0.5f, w * 0.55f))
        }
        "following" -> {
            val p = Path()
            for (i in 0 until 10) {
                val r = if (i % 2 == 0) w * 0.46f else w * 0.2f
                val a = Math.toRadians(-90.0 + i * 36.0)
                val x = (w / 2 + r * Math.cos(a)).toFloat(); val y = (w * 0.54f + r * Math.sin(a)).toFloat()
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close()
            drawPath(p, c, style = Stroke(w * 0.08f, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
        "sliders" -> for ((i, x) in listOf(0.3f, 0.7f, 0.45f).withIndex()) {
            val y = w * (0.22f + i * 0.28f)
            drawLine(c, Offset(w * 0.08f, y), Offset(w * 0.92f, y), w * 0.08f, StrokeCap.Round)
            drawCircle(c, w * 0.10f, Offset(w * x, y))
        }
        "gear" -> {
            val o = Offset(w / 2, w / 2)
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                drawLine(c, Offset(o.x + (w * 0.30f * Math.cos(a)).toFloat(), o.y + (w * 0.30f * Math.sin(a)).toFloat()),
                    Offset(o.x + (w * 0.46f * Math.cos(a)).toFloat(), o.y + (w * 0.46f * Math.sin(a)).toFloat()), w * 0.13f, StrokeCap.Round)
            }
            drawCircle(c, w * 0.30f, o, style = st)
            drawCircle(c, w * 0.10f, o, style = st)
        }
        "wifi" -> {
            val o = Offset(w / 2, w * 0.84f)
            for (r in listOf(0.30f, 0.56f, 0.82f)) drawArc(c, -135f, 90f, false, Offset(o.x - w * r, o.y - w * r), Size(w * r * 2, w * r * 2), style = st)
            drawCircle(c, w * 0.07f, o)
        }
        "background" -> {
            drawRoundRect(c, Offset(w * 0.06f, w * 0.16f), Size(w * 0.88f, w * 0.68f), CornerRadius(w * 0.12f), style = st)
            val p = Path().apply { moveTo(w * 0.18f, w * 0.72f); lineTo(w * 0.42f, w * 0.44f); lineTo(w * 0.58f, w * 0.60f); lineTo(w * 0.68f, w * 0.50f); lineTo(w * 0.84f, w * 0.72f) }
            drawPath(p, c, style = st)
            drawCircle(c, w * 0.07f, Offset(w * 0.70f, w * 0.32f))
        }
        "dotOn" -> { drawCircle(c, w * 0.42f, Offset(w / 2, w / 2), style = st); drawCircle(c, w * 0.24f, Offset(w / 2, w / 2)) }
        "dotOff" -> drawCircle(c, w * 0.42f, Offset(w / 2, w / 2), style = st)
        "theme" -> {
            drawCircle(c, w * 0.42f, Offset(w / 2, w / 2), style = st)
            drawArc(c, 90f, 180f, true, Offset(w * 0.08f, w * 0.08f), Size(w * 0.84f, w * 0.84f))
        }
    }
}

/** Small preview of a theme: the plate colour it gives the icons. */
private fun DrawScope.swatch(key: String, light: Boolean) {
    val w = size.width
    val (fill, edge) = when (key) {
        "bow" -> Color(0xFFF4F4F6) to Color(0xFF111114)
        "amoled" -> Color.Black to Color.White.copy(alpha = 0.5f)
        "clear" -> Color.White.copy(alpha = 0.14f) to Color.White.copy(alpha = 0.35f)
        "liquid" -> Color.White.copy(alpha = 0.32f) to Color.White.copy(alpha = 0.7f)
        else -> Color.White.copy(alpha = 0.06f) to Color.White.copy(alpha = 0.9f)
    }.let { (f, e) -> if (light && key != "bow" && key != "amoled") Color(0x22000000) to Color(0x66000000) else f to e }
    drawRoundRect(fill, cornerRadius = CornerRadius(w * 0.28f))
    drawRoundRect(edge, cornerRadius = CornerRadius(w * 0.28f), style = Stroke(w * 0.07f))
    if (key == "bow") drawCircle(Color(0xFF111114), w * 0.16f, Offset(w / 2, w / 2))
}
