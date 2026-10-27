package com.glasslauncher.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.glasslauncher.tv.data.Glog
import com.glasslauncher.tv.data.LocalShell
import kotlin.concurrent.thread

/**
 * The TV started: Glassio comes up instead of Google TV's own home screen ("Open Glassio when the TV starts").
 *
 * Google TV opens its own launcher after a restart whatever the default home app is, and Android doesn't let an app
 * open a screen from the background. Two ways round it: the Home-button service (HomeKeyService) does it as soon as
 * it's connected; without it, the TV's own adb shell (TV access) may open any screen. The shell comes up a little
 * after the TV does, so it's tried for a minute.
 */
class BootReceiver : BroadcastReceiver() {
    companion object {
        /** TCL's firmware (TclAppBoot) drops an app's start-up broadcasts and alarms unless it may auto-start. */
        val TCL: Boolean = (android.os.Build.BRAND + " " + android.os.Build.MANUFACTURER).contains("tcl", ignoreCase = true)

        /**
         * On TCL TVs: lets Glassio start by itself (after a restart, and for Live TV's reminders and recordings),
         * through the TV's adb shell (TV access). Some firmware names the operation APP_AUTO_START. Blocking.
         */
        fun allowAutoStart(ctx: Context): Boolean {
            if (!TCL) return true
            val p = ctx.packageName
            return LocalShell.run(ctx, "appops set $p AUTO_START allow 2>/dev/null || appops set $p APP_AUTO_START allow").isSuccess
                .also { Glog.i("TCL auto-start for Glassio: ${if (it) "allowed" else "not yet (TV access needed)"}") }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as? GlassApp ?: return
        if (!app.settings.bootGlassio) return
        val done = goAsync()
        thread(name = "glass-boot", isDaemon = true) {
            try {
                Thread.sleep(3_000)     // let the TV's launcher come up first, or it comes back over Glassio
                repeat(12) { attempt ->
                    if (HomeKeyService.connected) {
                        Glog.i("Boot: the Home-button service opens Glassio")
                        return@thread
                    }
                    val r = LocalShell.run(app, "am start -a android.intent.action.MAIN -c android.intent.category.HOME -n ${app.packageName}/${MainActivity::class.java.name}")
                    if (r.isSuccess) {
                        Glog.i("Boot: Glassio opened through the TV's adb (attempt ${attempt + 1})")
                        return@thread
                    }
                    Thread.sleep(5_000)
                }
                Glog.w("Boot: couldn't open Glassio (turn on the Home-button service, or allow TV access)")
            } finally {
                done.finish()
            }
        }
    }
}
