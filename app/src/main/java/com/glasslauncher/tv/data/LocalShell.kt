package com.glasslauncher.tv.data

import android.content.Context
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File

/**
 * Runs a shell command through the TV's own adb over localhost (like Nuvio TV Relay does).
 * The first time, the TV asks "Allow USB debugging?" for Glassio's key: choose "Always allow".
 * Used for things only the shell may do, such as starting the TV's screensaver on demand.
 */
object LocalShell {
    private fun keyPair(ctx: Context): AdbKeyPair {
        val dir = File(ctx.filesDir, "adb").apply { mkdirs() }
        val priv = File(dir, "adbkey"); val pub = File(dir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        return AdbKeyPair.read(priv, pub)
    }

    private var conn: Dadb? = null

    /**
     * A connection to the TV's adb. Reads give up after 45 s: while "Allow USB debugging?" waits for an answer the
     * connection hangs, and without a limit it hung for good (Continue Watching, the screensaver, everything after it).
     * The next try puts the question on screen again; "Always allow" works whenever it's answered.
     */
    private fun connect(ctx: Context, port: Int): Dadb = Dadb.create("127.0.0.1", port, keyPair(ctx), 5_000, 45_000)

    /**
     * Copies an APK to the TV and installs it from the shell, detached, so the install survives this app
     * being stopped for the update; Glass is opened again afterwards.
     */
    @Synchronized
    fun install(ctx: Context, apk: File, port: Int = 5555): Result<Unit> = runCatching {
        val d = conn ?: connect(ctx, port).also { conn = it }
        val remote = "/data/local/tmp/glass-update.apk"
        d.push(apk, remote)
        val cmd = "nohup sh -c 'pm install -r -d $remote && rm -f $remote; " +
            "am start -n ${ctx.packageName}/${com.glasslauncher.tv.MainActivity::class.java.name}' >/dev/null 2>&1 &"
        val r = d.shell(cmd)
        check(r.exitCode == 0) { r.allOutput.trim().ifBlank { "exit ${r.exitCode}" } }
    }.onFailure { runCatching { conn?.close() }; conn = null }

    /** Blocking; call off the main thread. Keeps one connection open and reconnects if it drops. */
    @Synchronized
    fun run(ctx: Context, command: String, port: Int = 5555): Result<String> {
        repeat(2) { attempt ->
            val r = runCatching {
                val d = conn ?: connect(ctx, port).also { conn = it }
                val res = d.shell(command)
                check(res.exitCode == 0) { res.allOutput.trim().ifBlank { "exit ${res.exitCode}" } }
                res.allOutput
            }
            if (r.isSuccess) return r
            if (attempt == 1) { Glog.w("TV adb shell", r.exceptionOrNull()); return r }
            runCatching { conn?.close() }; conn = null
        }
        return Result.failure(IllegalStateException())
    }

    /** [run] for Live TV's Java code: true when the command ran. Blocking. */
    @JvmStatic
    fun runCommand(ctx: Context, command: String): Boolean = run(ctx, command).isSuccess
}
