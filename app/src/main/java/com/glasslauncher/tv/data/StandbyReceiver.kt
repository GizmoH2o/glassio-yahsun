package com.glasslauncher.tv.data

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.glasslauncher.tv.HomeKeyService

/** The TV to standby through the key service (its own lock action), when there's no debugging connection for the sleep key. */
class StandbyReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (android.os.Build.VERSION.SDK_INT < 28) return
        val done = HomeKeyService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) == true
        Glog.i("Standby by the key service: $done")
    }
}
