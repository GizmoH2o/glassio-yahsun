package com.glasslauncher.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * Starts Glassio again in a new process (after a backup is restored: the old one still holds the old settings). This
 * runs in its own small process: the home screen's process asks for it and ends; this one ends that process for
 * sure, opens the home screen again, and ends too. Without it, Google TV's own home screen could come up meanwhile.
 */
class RestartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getIntExtra(EXTRA_PID, -1).takeIf { it > 0 }?.let { android.os.Process.killProcess(it) }
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
        Runtime.getRuntime().exit(0)
    }

    companion object {
        private const val EXTRA_PID = "pid"

        /** From the home screen's process, while Glassio is in front. */
        fun restart(ctx: Context) {
            ctx.startActivity(Intent(ctx, RestartActivity::class.java).putExtra(EXTRA_PID, android.os.Process.myPid())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Runtime.getRuntime().exit(0)
        }
    }
}
