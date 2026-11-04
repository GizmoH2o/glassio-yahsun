package com.glasslauncher.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glasslauncher.tv.GlassApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The first run: what Glassio is, signing in (or not: Home fills with what's trending anyway), making it the TV's home,
 * done. Everything can be skipped and done later in Settings; a step's row opens the Settings page for it, and Back
 * comes back here. [onDone] when it's over.
 */
@Composable
fun Welcome(app: GlassApp, s: Float, stepState: MutableIntState, onSettings: (category: Int) -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var step by stepState
    // Back goes a step back, and never out of the welcome: nothing under it is ready yet.
    BackHandler { if (step > 0) step-- }
    val status by produceState(WelcomeStatus(), step) {
        while (true) {
            value = withContext(Dispatchers.IO) {
                WelcomeStatus(
                    app.library.nuvio.connected, app.library.stremio.connected, isHomeKeyOn(ctx),
                    if (step == 2) com.glasslauncher.tv.data.LocalShell.run(ctx, "echo ok").isSuccess else value.adb,
                )
            }
            delay(3000)
        }
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(step) { delay(150); runCatching { first.requestFocus() } }
    Row(Modifier.fillMaxSize().background(Ds.Bg).focusGroup().padding(horizontal = (54 * s).dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((30 * s).dp)) {
        Column(Modifier.weight(0.42f)) {
            when (step) {
                0 -> Intro("Welcome to Glassio", "Your films, series, live TV and apps, together on one home screen.", s)
                1 -> Intro("Bring in what you watch", "Sign in to fill Home with your library and Continue Watching. No account? Home shows what's trending, and you can still save titles.", s)
                2 -> Intro("Make it your TV's home", "So the Home button comes here, and Glassio can start with the TV. You can do this later.", s)
                else -> Intro("You're all set", "Everything you've seen here is in Settings, whenever you want it.", s)
            }
            Spacer(Modifier.height((12 * s).dp))
            Row(horizontalArrangement = Arrangement.spacedBy((4 * s).dp)) {
                repeat(4) { i -> Box(Modifier.width((24 * s).dp).height((2.5f * s).dp).clip(RoundedCornerShape(50)).background(if (i <= step) Color.White else Color.White.copy(alpha = 0.25f))) }
            }
        }
        Column(Modifier.weight(0.58f), verticalArrangement = Arrangement.spacedBy((6 * s).dp)) {
            when (step) {
                0 -> Row { DsButton("Get started", s, Modifier.focusRequester(first), primary = true) { step = 1 } }
                1 -> {
                    StepRow("Nuvio account", "Scan a code with your phone", status.nuvio, s, Modifier.focusRequester(first)) { onSettings(NUVIO_CATEGORY) }
                    StepRow("Stremio account", "Scan a code with your phone", status.stremio, s) { onSettings(NUVIO_CATEGORY) }
                    StepRow("Restore a backup", "Had Glassio before? Everything back in one go", null, s) { onSettings(SYSTEM_CATEGORY) }
                    Spacer(Modifier.height((4 * s).dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { DsButton(if (status.nuvio || status.stremio) "Continue" else "Skip", s) { step = 2 } }
                }
                2 -> {
                    StepRow("Home button opens Glassio", "Settings › System", status.home, s, Modifier.focusRequester(first)) { onSettings(SYSTEM_CATEGORY) }
                    StepRow("Allow TV access", "For the screensaver, silent updates and more", status.adb, s) { onSettings(SYSTEM_CATEGORY) }
                    Spacer(Modifier.height((4 * s).dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { DsButton("Continue", s) { step = 3 } }
                }
                else -> Row { DsButton("Go to Home", s, Modifier.focusRequester(first), primary = true) { onDone() } }
            }
        }
    }
}

private data class WelcomeStatus(val nuvio: Boolean = false, val stremio: Boolean = false, val home: Boolean = false, val adb: Boolean = false)

@Composable
private fun Intro(title: String, text: String, s: Float) {
    Text(title, color = Color.White, fontSize = (27 * s).sp, lineHeight = (31 * s).sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height((8 * s).dp))
    Text(text, color = Color(0xFFD0D0D4), fontSize = (11 * s).sp, lineHeight = (16 * s).sp)
}

@Composable
private fun StepRow(title: String, about: String, done: Boolean?, s: Float, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val k = liftOf(focused, 1.015f)
    Row(
        modifier.fillMaxWidth().graphicsLayer { scaleX = k; scaleY = k }
            .onFocusChanged { focused = it.isFocused }
            .tvPress(onClick = onClick)
            .clip(RoundedCornerShape((Ds.R_CONTROL * s).dp))
            .background(if (focused) Color.White else Ds.Surface)
            .padding(horizontal = (14 * s).dp, vertical = (10 * s).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (focused) Ds.Ink else Color.White, fontSize = (13 * s).sp, fontWeight = FontWeight.SemiBold)
            Text(about, color = if (focused) Ds.InkSoft else Text2, fontSize = (9.5f * s).sp)
        }
        if (done == true) Text("Done", color = if (focused) Ds.GoodOnWhite else Ds.Good, fontSize = (10 * s).sp, fontWeight = FontWeight.Bold)
    }
}
