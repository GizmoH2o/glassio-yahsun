package com.glasslauncher.tv.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glasslauncher.tv.GlassApp
import kotlinx.coroutines.delay

/** Shown once per start of Glassio (not again on coming back to it). */
object Opening { var done = false }

/**
 * As Glassio starts (mockup 24): its name fades in with a light running under it for about a second, then Home rises in
 * from behind. With more than one profile, then: "Welcome back" with the profile's letter, Continue and Switch profile;
 * it goes by itself after a few seconds. Settings › Look & motion › Opening turns both off.
 */
@Composable
fun OpeningHost(app: GlassApp, s: Float, onSwitch: () -> Unit) {
    if (Opening.done || !app.settings.openingAnim) return
    var phase by remember { mutableStateOf(0) }       // 0 the name, 1 welcome, 2 gone
    val a = remember { Animatable(1f) }
    val name = remember { Animatable(0f) }
    val light = remember { Animatable(0f) }
    val many = remember { app.profiles.all().size > 1 && !app.settings.askProfile }
    LaunchedEffect(Unit) {
        // Only once frames are really drawn (Glassio starting skips its first ones while Home is built), so it's seen.
        repeat(6) { androidx.compose.runtime.withFrameNanos { } }
        delay(120)
        name.animateTo(1f, tween(380, easing = Motion.EaseOut))
        light.animateTo(1f, tween(620))
        if (many) { phase = 1; delay(3_500) }
        a.animateTo(0f, tween(360, easing = Motion.EaseIn))
        phase = 2; Opening.done = true
    }
    if (phase == 2) return
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = a.value }.background(Ds.Bg), contentAlignment = Alignment.Center) {
        if (phase == 0) Column(Modifier.graphicsLayer { alpha = name.value; translationY = (1f - name.value) * 12 * density },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy((4 * s).dp)) {
            Text("Glassio", color = Color.White, fontSize = (34 * s).sp, fontWeight = FontWeight.Light, letterSpacing = (5 * s).sp)
            Text("YAHSUN", color = Color.White.copy(alpha = 0.75f), fontSize = (8 * s).sp, fontWeight = FontWeight.SemiBold, letterSpacing = (4.5f * s).sp)
            Box(Modifier.padding(top = (8 * s).dp).width((70 * s).dp).height((1.2f * s).dp).graphicsLayer { alpha = light.value; scaleX = 0.2f + 0.8f * light.value }
                .background(Brush.horizontalGradient(listOf(Color.Transparent, Color.White, Color.Transparent))))
        } else {
            val p = app.profiles.current()
            val go = remember { FocusRequester() }
            LaunchedEffect(Unit) { delay(60); runCatching { go.requestFocus() } }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy((8 * s).dp)) {
                Box(Modifier.size((46 * s).dp).clip(CircleShape).background(LocalAccent.current), contentAlignment = Alignment.Center) {
                    Text(p.name.take(1).uppercase(), color = Color.White, fontSize = (20 * s).sp, fontWeight = FontWeight.ExtraBold)
                }
                RawText(Lang.tr("Welcome back,") + " " + p.name, color = Color.White, fontSize = (15 * s).sp, fontWeight = FontWeight.Bold)
                Row(Modifier.padding(top = (4 * s).dp), horizontalArrangement = Arrangement.spacedBy((6 * s).dp)) {
                    DsButton("Continue", s, Modifier.focusRequester(go), primary = true) { phase = 2; Opening.done = true }
                    DsButton("Switch profile", s) { phase = 2; Opening.done = true; onSwitch() }
                }
            }
        }
    }
}
