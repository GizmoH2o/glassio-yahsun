package com.glasslauncher.tv

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import com.glasslauncher.tv.data.Glog
import com.glasslauncher.tv.data.HomeReplace
import com.glasslauncher.tv.data.LocalShell
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * "Replace Google TV's home" on this TV ([HomeReplace] holds the steps): its state file, outside of what a backup
 * takes (it's about this TV), and the safety net for when Glassio's own home screen keeps crashing.
 */
object HomeRescue {
    private val lock = Any()

    private fun file(ctx: Context) = File(ctx.noBackupFilesDir, "home_replace.json")

    fun state(ctx: Context): HomeReplace.State = runCatching { HomeReplace.State.of(file(ctx).readText()) }.getOrDefault(HomeReplace.State())

    private fun save(ctx: Context, s: HomeReplace.State) {
        val tmp = File(ctx.noBackupFilesDir, "home_replace.json.new")
        tmp.outputStream().use { it.write(s.json().toByteArray()); it.fd.sync() }
        check(tmp.renameTo(file(ctx))) { "home_replace.json not saved" }
    }

    private fun steps(ctx: Context) = HomeReplace(ctx.packageName, MainActivity::class.java.name, object : HomeReplace.Store {
        override fun read() = state(ctx)
        override fun write(s: HomeReplace.State) = save(ctx, s)
    }) { LocalShell.run(ctx, it) }

    /** Blocking. Null when done, else why not. */
    fun turnOn(ctx: Context): String? = synchronized(lock) { guarded { steps(ctx).turnOn(System.currentTimeMillis()) { rescueReady(ctx) } } }
    fun turnOff(ctx: Context): String? = synchronized(lock) { guarded { steps(ctx).turnOff() } }
    fun verify(ctx: Context): List<String> = synchronized(lock) { runCatching { steps(ctx).verify() }.getOrDefault(emptyList()) }

    private fun guarded(f: () -> String?): String? = runCatching(f).getOrElse { Glog.w("Replace Google TV's home", it); it.message ?: "Error" }

    fun clearNote(ctx: Context) { synchronized(lock) { runCatching { state(ctx).let { if (it.note.isNotEmpty()) save(ctx, it.copy(note = "")) } } } }

    /** The shell reached, the TV's debugging put back on first when it went off (with WRITE_SECURE_SETTINGS). */
    internal fun shellUp(ctx: Context, tries: Int): Boolean {
        repeat(tries) { i ->
            if (LocalShell.run(ctx, "true").isSuccess) return true
            if (i == 0) runCatching { android.provider.Settings.Global.putInt(ctx.contentResolver, android.provider.Settings.Global.ADB_ENABLED, 1) }
            if (i < tries - 1) Thread.sleep(3_000)
        }
        return false
    }

    /** As Glassio starts: what a TV update turned back on, or a switch cut short (its own thread; adb comes up after the TV). */
    fun started(app: GlassApp) {
        if (state(app).phase == HomeReplace.Phase.OFF) return
        thread(name = "home-replace", isDaemon = true) {
            Thread.sleep(20_000)
            shellUp(app, 10)
            if (verify(app).isNotEmpty()) com.glasslauncher.tv.ui.toast(app, "The TV turned Google's home back on: turned off again")
        }
    }

    // ---- The safety net

    private fun crashTimes(ctx: Context): List<Long> = runCatching {
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        ctx.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(ctx.packageName, 0, 20)
            .filter { it.processName == ctx.packageName && (it.reason == ApplicationExitInfo.REASON_CRASH
                || it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || it.reason == ApplicationExitInfo.REASON_ANR) }
            .map { it.timestamp }
    }.getOrDefault(emptyList())

    /** First thing as the home screen's process starts, and as it crashes ([crashing], not counted by Android yet). */
    fun look(ctx: Context, crashing: Boolean) {
        runCatching {
            val s = state(ctx)
            if (s.phase != HomeReplace.Phase.ON) return
            val now = System.currentTimeMillis()
            if (HomeReplace.crashedTooOften(crashTimes(ctx) + if (crashing) listOf(now) else emptyList(), s.since, now))
                ctx.sendBroadcast(rescue(ctx, ACTION_RESCUE))
        }
    }

    private fun rescue(ctx: Context, action: String) = Intent(action).setComponent(ComponentName(ctx, HomeRescueReceiver::class.java))
        .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)

    /** Whether the rescue's own process is let start here, and reaches the shell. Blocking. */
    private fun rescueReady(ctx: Context): Boolean {
        val done = CountDownLatch(1)
        var ok = false
        ctx.sendOrderedBroadcast(rescue(ctx, ACTION_CHECK), null, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { ok = resultCode == OK; done.countDown() }
        }, null, 0, null, null)
        done.await(50, TimeUnit.SECONDS)
        return ok
    }

    const val ACTION_RESCUE = "com.glasslauncher.tv.HOME_RESCUE"
    const val ACTION_CHECK = "com.glasslauncher.tv.HOME_RESCUE_CHECK"
    const val OK = 7
    const val CRASHED = "Glassio's home screen kept crashing, so Google TV's home is back"

    /** In the rescue's process: Google's home back on, and opened. */
    internal fun rescueNow(ctx: Context) {
        if (state(ctx).phase == HomeReplace.Phase.OFF) return
        Glog.w("Home screen crashed too often: Google TV's home back on")
        shellUp(ctx, 4)
        val left = guarded { steps(ctx).turnOff(CRASHED) }
        if (left != null) Glog.w("Rescue: $left")
        LocalShell.run(ctx, "am start -a android.intent.action.MAIN -c android.intent.category.HOME")
    }
}

/** The rescue, in a process of its own (:rescue): the home screen's crash doesn't take it along. */
class HomeRescueReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        thread(name = "home-rescue") {
            try {
                if (intent.action == HomeRescue.ACTION_RESCUE) HomeRescue.rescueNow(app)
                else if (intent.action == HomeRescue.ACTION_CHECK && HomeRescue.shellUp(app, 1)) pending.resultCode = HomeRescue.OK
            } finally {
                pending.finish()
            }
        }
    }
}
