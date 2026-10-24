package com.glasslauncher.tv.data

import android.util.Log

/**
 * One log tag for everything that can go wrong quietly (network, Trakt, the TV's adb, opening a title).
 * To see it: adb logcat -s Glassio
 */
object Glog {
    const val TAG = "Glassio"
    fun w(what: String, e: Throwable? = null) { Log.w(TAG, if (e != null) "$what: ${e.javaClass.simpleName}: ${e.message}" else what) }
    fun i(what: String) { Log.i(TAG, what) }
}
