package com.glasslauncher.tv.ui

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.clearAndSetSemantics

val KeyEvent.isSelect: Boolean
    get() = key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter

/**
 * Remote-control press handling without any default focus/ripple effect:
 * short press on OK = [onClick]; holding OK (or pressing Menu) = [onLong].
 * [onKey] sees every key first and can swallow it (used by the "move app" mode).
 * Put onFocusChanged / focusRequester BEFORE this modifier in the chain.
 */
@Composable
fun Modifier.tvPress(
    onLong: (() -> Unit)? = null,
    onKey: ((KeyEvent) -> Boolean)? = null,
    onClick: () -> Unit,
): Modifier {
    var longFired by remember { mutableStateOf(false) }
    var downSeen by remember { mutableStateOf(false) }
    return this.onFocusChanged { if (!it.isFocused) { downSeen = false; longFired = false } }.onKeyEvent { ev ->
        if (onKey != null && onKey(ev)) return@onKeyEvent true
        when {
            ev.isSelect -> when (ev.type) {
                KeyEventType.KeyDown -> {
                    if (ev.nativeKeyEvent.repeatCount == 0) { longFired = false; downSeen = true }
                    if (downSeen && onLong != null && ev.nativeKeyEvent.repeatCount >= 1 && !longFired) {
                        longFired = true; onLong()
                    }
                    true
                }
                KeyEventType.KeyUp -> {
                    if (downSeen && !longFired) onClick()
                    longFired = false; downSeen = false
                    true
                }
                else -> false
            }
            ev.key == Key.Menu && onLong != null -> {
                if (ev.type == KeyEventType.KeyDown) onLong()
                true
            }
            else -> false
        }
    }.focusable()
}

/**
 * Compose keeps a description of the screen up to date (redone on every slide) whenever any accessibility service is
 * on, though Glassio's own Home-button service never reads the screen. Without a service that speaks the screen
 * (TalkBack and the like), that work is skipped; with one, everything is described as usual.
 */
@Composable
fun QuietSemantics(content: @Composable () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val am = remember { ctx.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager }
    fun speaks() = am.isEnabled && am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_SPOKEN).isNotEmpty()
    var spoken by remember { mutableStateOf(speaks()) }
    androidx.compose.runtime.DisposableEffect(am) {
        val l = android.view.accessibility.AccessibilityManager.AccessibilityStateChangeListener { spoken = speaks() }
        am.addAccessibilityStateChangeListener(l)
        onDispose { am.removeAccessibilityStateChangeListener(l) }
    }
    androidx.compose.foundation.layout.Box(
        if (spoken) Modifier else Modifier.clearAndSetSemantics { },
    ) { content() }
}
