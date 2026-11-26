package com.glasslauncher.tv.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf

/**
 * Whether the TV is online (a network that reaches the internet), for the one "No connection" banner the whole app
 * shows instead of each page failing on its own. Each process watches for itself (Glassio's and Live TV's).
 */
object Online {
    val up = mutableStateOf(true)
    private var watching = false
    private val listeners = ArrayList<(Boolean) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    /** Starts watching (once per process); [onChange] hears each change from then on (on the main thread). */
    @JvmStatic
    @Synchronized
    fun watch(c: Context, onChange: ((Boolean) -> Unit)? = null) {
        onChange?.let { listeners += it }
        if (watching) return
        watching = true
        val cm = c.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return
        fun now(): Boolean = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }?.let {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } ?: false
        up.value = now()
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { set(true) }
                override fun onLost(network: Network) { set(now()) }
            })
        }
    }

    /** [onChange] isn't told any more (its screen closed). */
    @JvmStatic
    @Synchronized
    fun unwatch(onChange: ((Boolean) -> Unit)?) { if (onChange != null) listeners.remove(onChange) }

    private fun set(v: Boolean) = main.post {
        if (up.value == v) return@post
        up.value = v
        listeners.toList().forEach { it(v) }
    }
}
