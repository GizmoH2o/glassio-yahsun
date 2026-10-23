package com.glasslauncher.tv.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
    Handler(Looper.getMainLooper()).post { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
}

sealed interface DialogState
class TextEntry(val title: String, val initial: String, val hint: String, val onDone: (String) -> Unit) : DialogState
class MenuDialog(val title: String, val options: List<Pair<String, () -> Unit>>) : DialogState

@Composable
fun DialogHost(state: DialogState?, onDismiss: () -> Unit) {
    if (state == null) return
    androidx.compose.runtime.key(state) {
        when (state) {
            is TextEntry -> TextEntryDialog(state, onDismiss)
            is MenuDialog -> MenuDialogView(state, onDismiss)
        }
    }
}

@Composable
private fun DialogSurface(width: Int = 420, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.width(width.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xF0181820))
            .border(0.8.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(16.dp)).padding(14.dp),
    ) { content() }
}

@Composable
private fun DialogButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(
        text, color = if (focused) Color.Black else Color.White, fontSize = 13.sp,
        fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
        modifier = modifier.clip(RoundedCornerShape(9.dp))
            .background(if (focused) Color.White.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.06f))
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

@Composable
private fun TextEntryDialog(d: TextEntry, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(d.initial) }
    val fr = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        DialogSurface {
            Text(d.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(d.hint, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
            Spacer(Modifier.height(10.dp))
            BasicTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 14.sp), cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { d.onDone(text); onDismiss() }),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.10f)).padding(10.dp).focusRequester(fr),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton("Save") { d.onDone(text); onDismiss() }
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
    Dialog(onDismissRequest = onDismiss) {
        DialogSurface(
            width = 230,
            modifier = Modifier.onPreviewKeyEvent { ev ->
                if (!ev.isSelect || armed) false
                else if (ev.type == KeyEventType.KeyDown && ev.nativeKeyEvent.repeatCount == 0) { armed = true; false }
                else true
            },
        ) {
            Text(d.title, color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
            d.options.forEachIndexed { i, (label, action) ->
                DialogButton(label, Modifier.fillMaxWidth().padding(bottom = 3.dp).let { if (i == 0) it.focusRequester(fr) else it }) { onDismiss(); action() }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
}
