package com.glasslauncher.tv.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Home's sleep timer (the quick panel): the TV goes to sleep at the time set, as Live TV's own timer does it (the sleep
 * key, through the TV's debugging connection). An alarm, so it goes off whatever app is on by then.
 */
object HomeSleep {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("glass_sleep", Context.MODE_PRIVATE)

    private fun intent(ctx: Context) = PendingIntent.getBroadcast(ctx, 7, Intent(ctx, SleepReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** When the TV goes to sleep (ms since 1970), or 0: no timer. */
    fun at(ctx: Context): Long = sp(ctx).getLong("at", 0L).takeIf { it > System.currentTimeMillis() } ?: 0L

    /** In [minutes] (0: the timer off). */
    fun set(ctx: Context, minutes: Int) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(intent(ctx))
        if (minutes <= 0) { sp(ctx).edit().remove("at").apply(); return }
        val at = System.currentTimeMillis() + minutes * 60_000L
        sp(ctx).edit().putLong("at", at).apply()
        // On the minute: an exact alarm (a window is stretched to 10 minutes since Android 12). Without the permission
        // for it (Android 14 asks for it), within the next 10.
        if (android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(ctx))
        else am.setWindow(AlarmManager.RTC_WAKEUP, at, 60_000L, intent(ctx))
    }
}

/** The sleep timer's time: the TV to sleep (off the main thread: the shell may take a moment). */
class SleepReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        app.getSharedPreferences("glass_sleep", Context.MODE_PRIVATE).edit().remove("at").apply()
        val done = goAsync()
        Thread({
            try {
                LocalShell.run(app, "input keyevent KEYCODE_SLEEP").onFailure { Glog.w("Sleep timer", it) }
            } finally { done.finish() }
        }, "home-sleep").start()
    }
}
