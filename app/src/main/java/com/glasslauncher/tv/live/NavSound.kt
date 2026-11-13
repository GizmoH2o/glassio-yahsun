package com.glasslauncher.tv.live

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import java.io.File

/**
 * Settings › Look & motion › Navigation sounds: a soft tick as the selection moves and a click on OK, the TV's own
 * sound effects, as Apple TV does, in Glassio and in Live TV alike (Live TV's own process reads the same flag: a file).
 */
object NavSound {
    private const val FLAG = "nav-sounds"
    private var lastAt = 0L

    fun set(c: Context, on: Boolean) {
        val f = File(c.applicationContext.filesDir, FLAG)
        if (on) runCatching { f.createNewFile() } else f.delete()
    }

    private fun on(c: Context) = File(c.applicationContext.filesDir, FLAG).exists()

    /** From an activity's dispatchKeyEvent: the sound for [e], when they're on. */
    @JvmStatic
    fun onKey(c: Context, e: KeyEvent) {
        if (e.action != KeyEvent.ACTION_DOWN) return
        val fx = when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> AudioManager.FX_FOCUS_NAVIGATION_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> AudioManager.FX_FOCUS_NAVIGATION_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> AudioManager.FX_FOCUS_NAVIGATION_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> AudioManager.FX_FOCUS_NAVIGATION_RIGHT
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (e.repeatCount == 0) AudioManager.FX_KEY_CLICK else return
            // Back: its own sound (the TV's), so going back sounds different from going in.
            KeyEvent.KEYCODE_BACK -> if (e.repeatCount == 0) backFx() else return
            else -> return
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastAt < 70) return       // held down: not a buzz
        if (!on(c)) return
        lastAt = now
        runCatching { (c.getSystemService(Context.AUDIO_SERVICE) as AudioManager).playSoundEffect(fx, 0.35f) }
    }

    private fun backFx() = if (android.os.Build.VERSION.SDK_INT >= 30) AudioManager.FX_BACK else AudioManager.FX_KEYPRESS_DELETE

    /** A title starting (Play handing over to Nuvio, Stremio): a fuller sound than OK's click. */
    @JvmStatic
    fun play(c: Context) = once(c, AudioManager.FX_KEYPRESS_RETURN, 0.45f)

    /** Something that couldn't be done: the TV's "not that" sound. */
    @JvmStatic
    fun error(c: Context) = once(c, AudioManager.FX_KEYPRESS_INVALID, 0.4f)

    private fun once(c: Context, fx: Int, volume: Float) {
        if (!on(c)) return
        runCatching { (c.getSystemService(Context.AUDIO_SERVICE) as AudioManager).playSoundEffect(fx, volume) }
    }
}
