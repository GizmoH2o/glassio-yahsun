package com.glasslauncher.tv

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Home button → Glass Launcher.
 *
 * Google TV sends Home to its own launcher no matter which app is the default home. This service
 * (switched on by the user under Settings > Accessibility) receives the Home key *before* the system
 * does and consumes it, so the stock launcher never appears and there is no flash.
 * The old window-change reaction is kept only as a fallback for devices that don't deliver key events.
 */
class HomeKeyService : AccessibilityService() {
    private var lastLaunch = 0L
    private var homeDownAt = 0L

    private fun enabled(): Boolean = (application as? GlassApp)?.settings?.interceptHome == true

    private fun showGlass() {
        val now = System.currentTimeMillis()
        if (now - lastLaunch < 1000) return
        lastLaunch = now
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME || !enabled()) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) homeDownAt = System.currentTimeMillis()
            KeyEvent.ACTION_UP -> showGlass()
        }
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || !enabled()) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != "com.google.android.apps.tv.launcherx" && pkg != "com.google.android.tvlauncher") return
        // Only reached when the Home key was not filtered above (e.g. Home from the Google TV remote app).
        if (System.currentTimeMillis() - homeDownAt < 1500) return
        showGlass()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Ask for key events explicitly; some TV builds ignore the flag from the XML alone.
        serviceInfo = serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
    }

    override fun onInterrupt() {}
}
