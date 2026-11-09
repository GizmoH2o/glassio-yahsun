package com.glasslauncher.tv.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.foundation.focusable
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.background
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

fun toast(ctx: Context, msg: String) {
    val words = Lang.tr(msg)
    // Glassio in front and Modern dialogs: its own glass notice at the bottom; else Android's.
    if (Notice.front && dialogsModern(ctx)) { Notice.show(words); return }
    Handler(Looper.getMainLooper()).post { Toast.makeText(ctx, words, Toast.LENGTH_LONG).show() }
}

sealed interface DialogState
/** [secret]: what's typed shows as dots (a backup's code). */
class TextEntry(val title: String, val initial: String, val hint: String, val secret: Boolean = false, val action: String = "Save",
    /** 4: a PIN, typed on the round code pad (Modern dialogs). */
    val digits: Int = 0, val onDone: (String) -> Unit) : DialogState
class MenuDialog(val title: String, val options: List<Pair<String, () -> Unit>>) : DialogState
/** A list with a small picture next to each choice (pictures, videos, screensaver videos). */
class ThumbDialog(val title: String, val items: List<ThumbItem>) : DialogState
/** "Press the button you want": the next key (not the arrows, OK or Back) goes to [onKey]. */
class KeyCaptureDialog(val title: String, val onKey: (Int) -> Unit) : DialogState
class ThumbItem(val label: String, val sub: String, val thumb: Any?, val checked: Boolean = false, val onClick: () -> Unit)
/** A sign-in code with a QR code of the page to enter it on; closes by itself when [code] turns null. */
/** Put a list in order: OK picks a row up, ▲ ▼ carry it, OK puts it down. [onDone] gets the keys in the new order. */
class ReorderDialog(val title: String, val items: List<Pair<String, String>>, val onDone: (List<String>) -> Unit) : DialogState
class QrCodeDialog(val title: String, val url: String, val code: kotlinx.coroutines.flow.StateFlow<String?>, val note: String) : DialogState

@Composable
fun DialogHost(state: DialogState?, onDismiss: () -> Unit) {
    if (state == null) return
    androidx.compose.runtime.key(state) {
        when (state) {
            is TextEntry -> TextEntryDialog(state, onDismiss)
            is MenuDialog -> MenuDialogView(state, onDismiss)
            is QrCodeDialog -> QrCodeDialogView(state, onDismiss)
            is ThumbDialog -> ThumbDialogView(state, onDismiss)
            is ReorderDialog -> ReorderDialogView(state, onDismiss)
            is KeyCaptureDialog -> KeyCaptureView(state, onDismiss)
            QuickPanelState -> QuickPanelView(onDismiss)
            is WhatsNew -> WhatsNewView(state, onDismiss)
        }
    }
}

/** What's new after an update: the changelog's section for this version. */
class WhatsNew(val version: String, val lines: List<com.glasslauncher.tv.data.Changelog.Line>) : DialogState

@Composable
private fun WhatsNewView(d: WhatsNew, onDismiss: () -> Unit) {
    val fr = remember { FocusRequester() }
    val scroll = androidx.compose.foundation.rememberScrollState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        DialogSurface(width = 560) {
            Text("What's new in ${d.version}", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Column(Modifier.height(300.dp).verticalScroll(scroll)) {
                d.lines.forEachIndexed { i, l ->
                    Text(l.text, color = if (l.heading) Color.White else Color(0xFFD0D0D4), fontSize = if (l.heading) 13.sp else 12.sp,
                        fontWeight = if (l.heading) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.padding(top = if (l.heading && i > 0) 10.dp else 3.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            // Close keeps the focus; Up and Down scroll the text.
            DialogButton("Close", Modifier.focusRequester(fr).onPreviewKeyEvent { ev ->
                val by = when (ev.nativeKeyEvent.keyCode) { android.view.KeyEvent.KEYCODE_DPAD_DOWN -> 220f; android.view.KeyEvent.KEYCODE_DPAD_UP -> -220f; else -> 0f }
                if (by != 0f && ev.type == KeyEventType.KeyDown) scope.launch { scroll.animateScrollBy(by) }
                by != 0f
            }, onClick = onDismiss)
            LaunchedEffect(Unit) { delay(60); runCatching { fr.requestFocus() } }
        }
    }
}

@Composable
private fun DialogSurface(width: Int = 420, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    // Modern (Settings › Look & motion › Dialog style): the glass sheet over the dimmed, blurred page.
    if (rememberDialogsModern()) { ModernSheet(width + 40, modifier, center = false) { content() }; return }
    Column(
        modifier.width(width.dp).clip(RoundedCornerShape(14.dp)).background(Ds.Surface).padding(14.dp),
    ) { content() }
}

@Composable
private fun DialogButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (rememberDialogsModern()) { SheetButton(text, modifier, destructive = destructiveChoice(text), onClick = onClick); return }
    var focused by remember { mutableStateOf(false) }
    Text(
        text, color = if (focused) Ds.Ink else Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = modifier.clip(RoundedCornerShape(9.dp))
            .background(if (focused) Color.White else Ds.SurfaceHi)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

@Composable
private fun TextEntryDialog(d: TextEntry, onDismiss: () -> Unit) {
    if (rememberDialogsModern()) {
        Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            if (d.digits == 4) androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CodePad(d.title, d.hint, onCode = { d.onDone(it); onDismiss(); true }, onCancel = onDismiss)
            }
            else GlassKeyboardEntry(d.title, d.hint, d.initial, d.secret, d.action, onDone = { d.onDone(it); onDismiss() }, onCancel = onDismiss)
        }
        return
    }
    var text by remember { mutableStateOf(d.initial) }
    val fr = remember { FocusRequester() }
    val saveFocus = remember { FocusRequester() }
    var boxFocused by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        DialogSurface {
            Text(d.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(d.hint, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
            Spacer(Modifier.height(10.dp))
            BasicTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 14.sp), cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done,
                    keyboardType = if (d.secret) androidx.compose.ui.text.input.KeyboardType.Password else androidx.compose.ui.text.input.KeyboardType.Text),
                visualTransformation = if (d.secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                keyboardActions = KeyboardActions(onDone = { d.onDone(text); onDismiss() }),
                // Selected: an outline, so you can see where you are with the keyboard closed. Down: straight to Save (a text
                // box keeps the arrows otherwise, and Save couldn't be reached with the remote).
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp))
                    .background(Ds.SurfaceHi).border(if (boxFocused) 2.dp else 0.dp, if (boxFocused) Color.White else Color.Transparent, RoundedCornerShape(9.dp))
                    .padding(10.dp).focusRequester(fr).onFocusChanged { boxFocused = it.isFocused }
                    .onPreviewKeyEvent { ev ->
                        if (ev.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) { if (ev.type == KeyEventType.KeyDown) runCatching { saveFocus.requestFocus() }; true } else false
                    },
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton(d.action, Modifier.focusRequester(saveFocus)) { d.onDone(text); onDismiss() }
                DialogButton("Cancel", onClick = onDismiss)
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
}

@Composable
private fun MenuDialogView(d: MenuDialog, onDismiss: () -> Unit) {
    val fr = remember { FocusRequester() }
    // The menu opens while OK is still held (long press). Ignore that OK until it is released
    // and pressed again, otherwise the release would trigger the first option.
    var armed by remember { mutableStateOf(false) }
    if (rememberDialogsModern()) {
        val guard = Modifier.onPreviewKeyEvent { ev ->
            if (!ev.isSelect || armed) false
            else if (ev.type == KeyEventType.KeyDown && ev.nativeKeyEvent.repeatCount == 0) { armed = true; false }
            else true
        }
        val confirm = isConfirmation(d.options.map { it.first })
        Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            ModernSheet(if (confirm) 380 else if (d.options.any { it.first.length > 30 }) 460 else 340, guard) {
                SheetTitle(d.title)
                if (confirm) {
                    // A question with a way out: the action, then Cancel, which is selected first (nothing happens by accident).
                    val (label, action) = d.options[0]
                    SheetButton(label, Modifier.padding(bottom = 8.dp), destructive = destructiveChoice(label), center = true) { onDismiss(); action() }
                    SheetButton(d.options[1].first, Modifier.focusRequester(fr), center = true) { onDismiss(); d.options[1].second() }
                } else Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    d.options.forEachIndexed { i, (label, action) ->
                        SheetButton(label, if (i == 0) Modifier.focusRequester(fr) else Modifier, destructive = destructiveChoice(label)) { onDismiss(); action() }
                    }
                }
            }
        }
        LaunchedEffect(Unit) { delay(60); runCatching { fr.requestFocus() } }
        return
    }
    Dialog(onDismissRequest = onDismiss) {
        DialogSurface(
            width = if (d.options.any { it.first.length > 30 }) 400 else 230,
            modifier = Modifier.onPreviewKeyEvent { ev ->
                if (!ev.isSelect || armed) false
                else if (ev.type == KeyEventType.KeyDown && ev.nativeKeyEvent.repeatCount == 0) { armed = true; false }
                else true
            },
        ) {
            Text(d.title, color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
            // Long lists (Home Assistant sensors, cameras…) scroll with the focus instead of running off the screen.
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                d.options.forEachIndexed { i, (label, action) ->
                    DialogButton(label, Modifier.fillMaxWidth().padding(bottom = 3.dp).let { if (i == 0) it.focusRequester(fr) else it }) { onDismiss(); action() }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
}

@Composable
private fun KeyCaptureView(d: KeyCaptureDialog, onDismiss: () -> Unit) {
    val fr = remember { FocusRequester() }
    var hint by remember { mutableStateOf("Press the button on the remote you want to use (Back cancels)") }
    // The OK that opened this is still being released: ignored.
    var armed by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        DialogSurface(width = 330, modifier = Modifier.focusRequester(fr).onPreviewKeyEvent { ev ->
            val code = ev.nativeKeyEvent.keyCode
            if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
            when (code) {
                android.view.KeyEvent.KEYCODE_BACK -> onDismiss()
                android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_DPAD_UP,
                android.view.KeyEvent.KEYCODE_DPAD_DOWN, android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT ->
                    if (armed) hint = "The arrows and OK move around Glassio: pick another button" else armed = true
                else -> { onDismiss(); d.onKey(code) }
            }
            true
        }.focusable()) {
            Text(d.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 4.dp))
            Text(hint, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 4.dp))
        }
    }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(400); armed = true; runCatching { fr.requestFocus() } }
    // Buttons the system keeps from apps (YouTube, Netflix, the star…) are caught by the Home button service.
    androidx.compose.runtime.DisposableEffect(Unit) {
        com.glasslauncher.tv.KeyHub.capture = { code -> onDismiss(); d.onKey(code) }
        onDispose { com.glasslauncher.tv.KeyHub.capture = null }
    }
}

/** A remote button's name for Settings ("Red", "Play/Pause"…). */
fun keyName(code: Int): String = when (code) {
    0 -> "Not set"
    else -> android.view.KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").split('_')
        .joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }
}

@Composable
private fun ReorderDialogView(d: ReorderDialog, onDismiss: () -> Unit) {
    val order = remember { androidx.compose.runtime.mutableStateListOf<String>().apply { addAll(d.items.map { it.first }) } }
    val names = remember { d.items.toMap() }
    var held by remember { mutableStateOf<String?>(null) }
    val frs = remember { HashMap<String, FocusRequester>() }
    var armed by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = { d.onDone(order.toList()); onDismiss() }) {
        DialogSurface(width = 300) {
            Text(d.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 4.dp))
            Text(if (held == null) "OK picks a row up · Back saves" else "▲ ▼ move it · OK puts it down", color = Color.White.copy(alpha = 0.55f), fontSize = 10.5f.sp,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 10.dp))
            order.forEachIndexed { i, k ->
                val fr = frs.getOrPut(k) { FocusRequester() }
                var f by remember { mutableStateOf(false) }
                val lifted = held == k
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 3.dp).focusRequester(fr).onFocusChanged { f = it.isFocused }
                        .clip(RoundedCornerShape(9.dp))
                        .background(when { lifted -> editAccent(); f -> Color.White.copy(alpha = 0.92f); else -> Color.White.copy(alpha = 0.06f) })
                        .onPreviewKeyEvent { ev ->
                            if (ev.isSelect) {
                                if (ev.type == KeyEventType.KeyDown) armed = true
                                else if (armed) { held = if (held == k) null else k }
                                return@onPreviewKeyEvent true
                            }
                            if (held == k && ev.type == KeyEventType.KeyDown && (ev.key == Key.DirectionUp || ev.key == Key.DirectionDown)) {
                                val j = (i + if (ev.key == Key.DirectionUp) -1 else 1).coerceIn(0, order.lastIndex)
                                if (j != i) { order.removeAt(i); order.add(j, k) }
                                true
                            } else false
                        }
                        .focusable()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${i + 1}", color = (if (f && !lifted) Color.Black else Color.White).copy(alpha = 0.5f), fontSize = 12.sp, modifier = Modifier.width(22.dp))
                    Text(names[k] ?: k, color = if (f && !lifted) Color.Black else Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    if (lifted) Text("↕", color = Color.White, fontSize = 13.sp)
                }
            }
        }
    }
    // The row that's carried keeps the focus as it moves.
    LaunchedEffect(order.toList(), held) { held?.let { runCatching { frs[it]?.requestFocus() } } }
    LaunchedEffect(Unit) { runCatching { frs[order.first()]?.requestFocus() } }
}

@Composable
private fun QrCodeDialogView(d: QrCodeDialog, onDismiss: () -> Unit) {
    val code by d.code.collectAsState()
    var seen by remember { mutableStateOf(false) }
    LaunchedEffect(code) { if (code != null) seen = true else if (seen) onDismiss() }
    val qr = remember(d.url) { com.glasslauncher.tv.net.Net.qr(d.url, 360).asImageBitmap() }
    val fr = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        DialogSurface(width = 460) {
            Text(d.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                androidx.compose.foundation.Image(qr, null, Modifier.width(150.dp).height(150.dp).clip(RoundedCornerShape(10.dp)))
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("Scan with your phone, or open", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                    // (long links wrap)
                    Text(d.url.removePrefix("https://"), color = Color.White, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    // An empty code: the link itself signs in (Nuvio), nothing to type.
                    if (code != "") {
                        Text("and enter", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                        Text(code ?: "…", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(d.note, color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            DialogButton("Close", Modifier.focusRequester(fr), onClick = onDismiss)
        }
    }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
}


@Composable
private fun ThumbDialogView(d: ThumbDialog, onDismiss: () -> Unit) {
    val fr = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        val modern = rememberDialogsModern()
        // Pictures only when some choice has one (a list of words gets no empty boxes).
        val pictures = d.items.any { it.thumb != null }
        DialogSurface(width = 520) {
            if (modern) SheetTitle(d.title)
            else Text(d.title, color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
            androidx.compose.foundation.lazy.LazyColumn(if (modern) Modifier.heightIn(max = 420.dp) else Modifier.height(380.dp)) {
                items(d.items.size) { i ->
                    val it = d.items[i]
                    var focused by remember { mutableStateOf(false) }
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 4.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (focused) Color.White.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.05f))
                            .let { m -> if (i == 0) m.focusRequester(fr) else m }
                            .onFocusChanged { f -> focused = f.isFocused }
                            .clickable { onDismiss(); it.onClick() }
                            .padding(6.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        if (pictures || !modern) androidx.compose.foundation.layout.Box(Modifier.width(96.dp).height(54.dp).clip(RoundedCornerShape(7.dp)).background(Color.White.copy(alpha = 0.08f))) {
                            if (it.thumb is androidx.compose.ui.graphics.ImageBitmap) androidx.compose.foundation.Image(it.thumb, null,
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(54.dp))
                            else if (it.thumb != null) coil3.compose.AsyncImage(model = it.thumb, contentDescription = null,
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(54.dp))
                        }
                        Spacer(Modifier.width(if (pictures || !modern) 12.dp else 8.dp))
                        Column(Modifier.weight(1f).padding(vertical = if (pictures || !modern) 0.dp else 6.dp)) {
                            Text(it.label, color = if (focused) Color.Black else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                            if (it.sub.isNotBlank()) Text(it.sub, color = (if (focused) Color.Black else Color.White).copy(alpha = 0.55f), fontSize = 10.sp, maxLines = 1)
                        }
                        if (it.checked) Text("✓", color = if (focused) Color.Black else Color.White, fontSize = 15.sp, modifier = Modifier.padding(end = 8.dp))
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { delay100(); runCatching { fr.requestFocus() } }
}

private suspend fun delay100() = kotlinx.coroutines.delay(100)
