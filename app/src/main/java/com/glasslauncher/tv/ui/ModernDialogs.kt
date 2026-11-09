package com.glasslauncher.tv.ui

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/*
 * Glassio's modern dialogs (Settings › Look & motion › Dialog style): tvOS's centred glass sheets over the dimmed,
 * blurred page, with Netflix's big clear actions. What's selected is a white plate with dark text; a destructive
 * choice reads red. Text is typed on Glassio's own keyboard; codes on a round number pad.
 */

/** Modern dialogs are on (the default). */
fun dialogsModern(ctx: Context): Boolean =
    (ctx.applicationContext as? com.glasslauncher.tv.GlassApp)?.let { a -> runCatching { a.settings.dialogStyle != "classic" }.getOrDefault(true) } ?: true

@Composable
fun rememberDialogsModern(): Boolean = dialogsModern(LocalContext.current)

private val SheetFill = Color(0xFF232327)
private val SheetRim = Color.White.copy(alpha = 0.10f)
private val Soft = Color(0xFFA1A1A6)
private val Red = Color(0xFFFF6961)
private val RedOnWhite = Color(0xFFD70015)

/** The page behind a dialog: dimmed, and blurred where the TV can blur behind a window. */
@Composable
fun DialogBackdrop(dim: Float = 0.62f) {
    val view = LocalView.current
    LaunchedEffect(view) {
        val w = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window ?: return@LaunchedEffect
        w.setDimAmount(dim)
        if (Build.VERSION.SDK_INT >= 31) runCatching {
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            w.attributes = w.attributes.apply { blurBehindRadius = 48 }
        }
    }
}

/** A centred glass sheet that settles in (a little scale and fade). */
@Composable
fun ModernSheet(width: Int, modifier: Modifier = Modifier, center: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    DialogBackdrop()
    val enter = remember { Animatable(0f) }
    val still = LocalReduceMotion.current
    LaunchedEffect(Unit) { if (still) enter.snapTo(1f) else enter.animateTo(1f, tween(220, easing = FastOutSlowInEasing)) }
    val shape = RoundedCornerShape(26.dp)
    Column(
        modifier.width(width.dp)
            .graphicsLayer { alpha = enter.value; val k = 0.95f + 0.05f * enter.value; scaleX = k; scaleY = k }
            .clip(shape).background(SheetFill).border(0.6.dp, SheetRim, shape)
            .padding(horizontal = 18.dp, vertical = 20.dp),
        horizontalAlignment = if (center) Alignment.CenterHorizontally else Alignment.Start,
    ) { content() }
}

/** The sheet's title, centred, and an optional quieter line under it. */
@Composable
fun SheetTitle(title: String, sub: String = "") {
    if (title.isNotBlank()) Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
        maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
    if (sub.isNotBlank()) {
        Spacer(Modifier.height(4.dp))
        Text(sub, color = Soft, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
    Spacer(Modifier.height(14.dp))
}

/** A big action: a white plate with dark text when selected, a faint glass one otherwise; [destructive] reads red. */
@Composable
fun SheetButton(text: String, modifier: Modifier = Modifier, destructive: Boolean = false, trailing: String = "", center: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val still = LocalReduceMotion.current
    val k by animateFloatAsState(if (focused && !still) 1.035f else 1f, tween(140), label = "sheetButton")
    val ink = when { focused && destructive -> RedOnWhite; focused -> Ds.Ink; destructive -> Red; else -> Color.White }
    Row(
        modifier.fillMaxWidth().graphicsLayer { scaleX = k; scaleY = k }
            .clip(RoundedCornerShape(14.dp))
            .background(if (focused) Color.White else Color.White.copy(alpha = 0.08f))
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (center) Arrangement.Center else Arrangement.Start,
    ) {
        Text(text, color = ink, fontSize = 14.sp, fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = if (center) TextAlign.Center else TextAlign.Start,
            modifier = if (center) Modifier else Modifier.weight(1f))
        if (trailing.isNotEmpty()) Text(trailing, color = if (focused) Ds.Ink else Soft, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp))
    }
}

/** Whether a menu choice undoes or removes something (it reads red). */
fun destructiveChoice(label: String): Boolean {
    val l = label.trim().lowercase()
    return listOf("remove", "delete", "hide", "sign out", "forget", "clear", "uninstall", "reset", "unlink", "disconnect", "erase", "stop following")
        .any { l.startsWith(it) }
}

/** Whether a menu is a question with a way out ("Sign out?" · Sign out · Cancel): shown as a confirmation. */
fun isConfirmation(options: List<String>): Boolean =
    options.size == 2 && options[1].trim().lowercase() in setOf("cancel", "not now", "keep", "no", "keep it")

// MARK: - Keyboard

private const val LETTERS = "abcdefghijklmnopqrstuvwxyz"
private const val DIGITS = "1234567890"
private const val SYMBOLS1 = "!@#\$%&*()-"
private const val SYMBOLS2 = "_=+[]{};:'"
private const val SYMBOLS3 = "\",.<>/?\\|~"

/**
 * Glassio's keyboard: the field at the top (dots for a secret), its blinking caret, then the keys (letters and
 * numbers, a page of symbols, shift, space, delete, ".com"). A remote's or a keyboard's own keys type too.
 */
@Composable
fun GlassKeyboardEntry(
    title: String, hint: String, initial: String, secret: Boolean, action: String,
    onDone: (String) -> Unit, onCancel: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    var symbols by remember { mutableStateOf(false) }
    var caps by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val now = remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(530); now.intValue++ } }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
    fun type(s: String) { if (text.length < 500) text += if (caps && s.length == 1 && s[0].isLetter()) s.uppercase() else s; if (caps && s.length == 1) caps = false }
    fun delete() { if (text.isNotEmpty()) text = text.dropLast(1) }
    ModernSheet(620, Modifier.onPreviewKeyEvent { ev ->
        if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val k = ev.nativeKeyEvent
        when {
            k.keyCode == android.view.KeyEvent.KEYCODE_DEL -> { delete(); true }
            // Letters, numbers and symbols from a keyboard or the remote's number keys (not OK or the arrows).
            k.unicodeChar > 0 && !Character.isISOControl(k.unicodeChar) && k.keyCode != android.view.KeyEvent.KEYCODE_DPAD_CENTER &&
                k.keyCode != android.view.KeyEvent.KEYCODE_ENTER -> { type(k.unicodeChar.toChar().toString()); true }
            else -> false
        }
    }) {
        SheetTitle(title, hint)
        // The field.
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.10f))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val shown = if (secret) "•".repeat(text.length) else text
            Text(shown.takeLast(48), color = Color.White, fontSize = 17.sp, maxLines = 1)
            Box(Modifier.width(2.dp).height(20.dp).background(if (now.intValue % 2 == 0) Color.White else Color.Transparent))
        }
        Spacer(Modifier.height(14.dp))
        val rows = if (symbols) listOf(SYMBOLS1, SYMBOLS2, SYMBOLS3, DIGITS)
            else listOf(LETTERS.substring(0, 10), LETTERS.substring(10, 20), LETTERS.substring(20) + ".-@_", DIGITS)
        rows.forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                row.forEachIndexed { i, ch ->
                    val label = if (caps && ch.isLetter()) ch.uppercaseChar().toString() else ch.toString()
                    Key(label, Modifier.width(48.dp).let { if (r == 0 && i == 0) it.focusRequester(first) else it }) { type(ch.toString()) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Key(if (caps) "⇧ ON" else "⇧", Modifier.width(70.dp), lit = caps) { caps = !caps }
            Key(if (symbols) "abc" else "&?#", Modifier.width(70.dp)) { symbols = !symbols }
            Key("space", Modifier.width(184.dp)) { type(" ") }
            Key(".com", Modifier.width(70.dp)) { type(".com") }
            Key("⌫", Modifier.width(70.dp)) { delete() }
            Key("✕", Modifier.width(46.dp)) { text = "" }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            SheetButton("Cancel", Modifier.weight(1f), center = true, onClick = onCancel)
            SheetButton(action, Modifier.weight(1f), center = true) { onDone(text) }
        }
    }
}

@Composable
private fun Key(label: String, modifier: Modifier, lit: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier.height(42.dp).clip(RoundedCornerShape(10.dp))
            .background(when { focused -> Color.White; lit -> Color.White.copy(alpha = 0.28f); else -> Color.White.copy(alpha = 0.10f) })
            .onFocusChanged { focused = it.isFocused }.clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (focused) Ds.Ink else Color.White, fontSize = if (label.length > 1) 12.sp else 16.sp, fontWeight = FontWeight.Medium) }
}

// MARK: - Code pad

/**
 * Four digits on a round number pad (1–9, clear, 0, delete) under four dots that fill as you type; [onCode] gets
 * each complete code (it answers true when it was right: else a shake and the dots empty). The remote's own number
 * keys type too.
 */
@Composable
fun CodePad(title: String, sub: String, onCode: (String) -> Boolean, onCancel: () -> Unit) {
    var code by remember { mutableStateOf("") }
    val shake = remember { Animatable(0f) }
    var wrong by remember { mutableIntStateOf(0) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
    LaunchedEffect(wrong) {
        if (wrong == 0) return@LaunchedEffect
        repeat(3) { shake.animateTo(1f, tween(40)); shake.animateTo(-1f, tween(40)) }
        shake.animateTo(0f, tween(40)); code = ""
    }
    fun digit(d: Int) {
        if (code.length >= 4) return
        code += d
        if (code.length == 4 && !onCode(code)) wrong++
    }
    ModernSheet(340, Modifier.graphicsLayer { translationX = shake.value * 14 * density }.onPreviewKeyEvent { ev ->
        val k = ev.nativeKeyEvent.keyCode
        if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (k) {
            in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9 -> { digit(k - android.view.KeyEvent.KEYCODE_0); true }
            android.view.KeyEvent.KEYCODE_DEL -> { code = code.dropLast(1); true }
            else -> false
        }
    }) {
        SheetTitle(title, sub)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            for (i in 0 until 4) Box(Modifier.size(14.dp).clip(CircleShape)
                .background(if (i < code.length) Color.White else Color.Transparent).border(1.5.dp, if (i < code.length) Color.White else Soft, CircleShape))
        }
        Spacer(Modifier.height(18.dp))
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "✕", "0", "⌫")
        keys.chunked(3).forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                row.forEachIndexed { c, label ->
                    RoundKey(label, if (r == 0 && c == 0) Modifier.focusRequester(first) else Modifier) {
                        when (label) { "✕" -> if (code.isEmpty()) onCancel() else code = ""; "⌫" -> code = code.dropLast(1); else -> digit(label.toInt()) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundKey(label: String, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val still = LocalReduceMotion.current
    val k by animateFloatAsState(if (focused && !still) 1.08f else 1f, tween(120), label = "roundKey")
    Box(
        modifier.size(62.dp).graphicsLayer { scaleX = k; scaleY = k }.clip(CircleShape)
            .background(if (focused) Color.White else Color.White.copy(alpha = 0.10f))
            .onFocusChanged { focused = it.isFocused }.clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (focused) Ds.Ink else Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium) }
}

// MARK: - Notices

/** Glassio's notices (instead of Android's toasts) while Glassio is in front: a glass pill at the bottom. */
object Notice {
    val now = mutableStateOf<Pair<String, Long>?>(null)
    /** Glassio's home activity is in front (MainActivity sets it). */
    @Volatile var front = false

    fun show(msg: String) {
        Handler(Looper.getMainLooper()).post { now.value = msg to SystemClock.uptimeMillis() }
    }
}

/** The notice pill, over the home screen; it fades in, stays about four seconds, and fades away. */
@Composable
fun NoticeHost() {
    val n = Notice.now.value
    var shown by remember { mutableStateOf<String?>(null) }
    val a = remember { Animatable(0f) }
    LaunchedEffect(n) {
        if (n == null) return@LaunchedEffect
        shown = n.first
        a.animateTo(1f, tween(180))
        delay(3_600)
        a.animateTo(0f, tween(260))
        if (Notice.now.value === n) Notice.now.value = null
    }
    val msg = shown ?: return
    Box(Modifier.fillMaxSize().padding(bottom = 40.dp), contentAlignment = Alignment.BottomCenter) {
        Text(msg, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 3,
            modifier = Modifier.graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 18 * density }
                .clip(RoundedCornerShape(50)).background(SheetFill).border(0.6.dp, SheetRim, RoundedCornerShape(50))
                .padding(horizontal = 22.dp, vertical = 11.dp))
    }
}
