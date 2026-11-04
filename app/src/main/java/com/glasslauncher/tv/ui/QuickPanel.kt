package com.glasslauncher.tv.ui

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.glasslauncher.tv.data.Glog
import com.glasslauncher.tv.data.HomeSleep
import kotlinx.coroutines.delay

/** The quick panel (the top bar's switch button): inputs, sleep timer, volume, Bluetooth and picture settings. */
object QuickPanelState : DialogState

/** One of the TV's own inputs (HDMI, the aerial), as the panel offers them. */
private class TvSource(val label: String, val intent: Intent)

/**
 * The TV's inputs: its HDMI ports and the like (passthrough) and its tuner, not apps' preview inputs. Apps' inputs
 * call themselves tuners too (Google Play Movies, VLC, IPTV apps), so a tuner only from the TV's own input service:
 * the package its HDMI inputs come from. Opened through the TV's own app as a channel (TCL's TV app takes them so;
 * content:// links with a channel type).
 */
private fun sources(ctx: Context): List<TvSource> = runCatching {
    val tim = ctx.getSystemService(Context.TV_INPUT_SERVICE) as TvInputManager
    val all = tim.tvInputList.filter { !it.isHidden(ctx) }
    val tvPkgs = all.filter { it.isPassthroughInput }.map { it.serviceInfo.packageName }.toSet()
    all.filter { it.isPassthroughInput || (it.type == TvInputInfo.TYPE_TUNER && it.serviceInfo.packageName in tvPkgs) }
        .sortedWith(compareBy({ if (it.type == TvInputInfo.TYPE_TUNER) 1 else 0 }, { it.loadLabel(ctx).toString() }))
        .map { info ->
            val label = (info.loadCustomLabel(ctx) ?: info.loadLabel(ctx)).toString().ifBlank { if (info.type == TvInputInfo.TYPE_TUNER) "TV" else "Input" }
            val i = if (info.isPassthroughInput)
                Intent(Intent.ACTION_VIEW).setDataAndType(TvContract.buildChannelUriForPassthroughInput(info.id), "vnd.android.cursor.item/channel")
            else Intent(Intent.ACTION_VIEW).setDataAndType(TvContract.buildChannelsUriForInput(info.id), "vnd.android.cursor.dir/channel")
            TvSource(label, i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
}.getOrElse { Glog.w("TV inputs", it); emptyList() }

@Composable
internal fun QuickPanelView(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val audio = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val inputs = remember { sources(ctx) }
    var volume by remember { mutableIntStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    val maxVolume = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var muted by remember { mutableStateOf(audio.isStreamMute(AudioManager.STREAM_MUSIC)) }
    var sleepAt by remember { mutableLongStateOf(HomeSleep.at(ctx)) }
    val now by androidx.compose.runtime.produceState(System.currentTimeMillis()) { while (true) { delay(15_000); value = System.currentTimeMillis() } }
    val first = remember { FocusRequester() }
    fun open(i: Intent, what: String) {
        runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { toast(ctx, "This TV can't open $what from here") }
    }
    fun vol(dir: Int) {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
        volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC); muted = audio.isStreamMute(AudioManager.STREAM_MUSIC)
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(560.dp).clip(RoundedCornerShape(14.dp)).background(Ds.Surface).padding(18.dp),
        ) {
            Text("Quick settings", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (inputs.isNotEmpty()) {
                Label("Input")
                Chips {
                    inputs.forEachIndexed { i, src ->
                        Chip(src.label, false, if (i == 0) Modifier.focusRequester(first) else Modifier) { onDismiss(); open(src.intent, src.label) }
                    }
                }
            }
            // Minutes left counted from now (not from the last tick of [now]: just set to 15, it said 16).
            Label(if (sleepAt > now) "Sleep timer · the TV sleeps in ${kotlin.math.ceil((sleepAt - System.currentTimeMillis()).coerceAtLeast(0) / 60_000.0).toInt()} min" else "Sleep timer")
            Chips {
                listOf(0, 15, 30, 60, 90, 120).forEachIndexed { i, m ->
                    val on = if (m == 0) sleepAt <= now else false
                    Chip(if (m == 0) "Off" else "$m min", on, if (inputs.isEmpty() && i == 0) Modifier.focusRequester(first) else Modifier) {
                        HomeSleep.set(ctx, m)
                        sleepAt = HomeSleep.at(ctx)
                        toast(ctx, if (m == 0) "Sleep timer off" else "The TV goes to sleep in $m minutes")
                    }
                }
            }
            Label("Volume · " + if (muted) "muted" else "$volume of $maxVolume")
            Chips {
                Chip("−", false) { vol(AudioManager.ADJUST_LOWER) }
                Chip(if (muted) "Unmute" else "Mute", muted) { vol(AudioManager.ADJUST_TOGGLE_MUTE) }
                Chip("+", false) { vol(AudioManager.ADJUST_RAISE) }
            }
            Label("The TV's settings")
            Chips {
                // Bluetooth opens the TV's own page: switching it off here would cut a Bluetooth remote off too.
                Chip("Bluetooth & remotes", false) { onDismiss(); open(Intent("com.android.tv.settings.ACCESSORIES_CONNECTED_DEVICES"), "Bluetooth") }
                Chip("Picture", false) { onDismiss(); open(Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS), "picture settings") }
                Chip("Sound", false) { onDismiss(); open(Intent(android.provider.Settings.ACTION_SOUND_SETTINGS), "sound settings") }
            }
        }
    }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
}

@Composable
private fun Label(text: String) {
    Spacer(Modifier.height(14.dp))
    Text(text, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 6.dp))
}

/** A row of chips, onto a second line when they don't fit (never squeezed). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

@Composable
private fun Chip(text: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(
        text, color = if (focused) Ds.Ink else Color.White, fontSize = 13.sp, fontWeight = if (focused || on) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1, softWrap = false,
        modifier = modifier.clip(RoundedCornerShape(50))
            .background(when { focused -> Color.White.copy(alpha = 0.95f); on -> Color.White.copy(alpha = 0.26f); else -> Ds.SurfaceHi })
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}
