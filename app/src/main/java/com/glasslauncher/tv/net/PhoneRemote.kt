package com.glasslauncher.tv.net

import com.glasslauncher.tv.GlassApp
import java.security.SecureRandom

/**
 * Phone remote (Settings › System): the phone page (PhoneServer) kept running while Glassio runs, at an address that
 * stays the same (its own long token, kept), so the phone can keep it as a bookmark and use the remote at any time.
 * Off, the page runs only while Settings › Manage from phone is open, with a new address each time.
 */
object PhoneRemote {
    @Volatile private var server: PhoneServer? = null
    @Volatile private var port = 0
    @Volatile private var token = ""

    /** Starts or stops it as the setting says (off the main thread). */
    fun apply(app: GlassApp) {
        Thread({ if (app.settings.phoneRemote) start(app) else stop() }, "glass-phone-remote").apply { isDaemon = true; start() }
    }

    /** The page's address (started first when it isn't running), or null when the TV isn't on a network. Blocking. */
    @Synchronized
    fun start(app: GlassApp): String? {
        // The phone page's own server (Settings › Manage from phone without Phone remote) may still hold the port
        // for a moment as it stops: then this one takes the next port, and the address shown is this one's.
        if (server == null) {
            token = app.settings.phoneToken.ifBlank {
                val b = ByteArray(18); SecureRandom().nextBytes(b)
                android.util.Base64.encodeToString(b, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
                    .also { app.settings.phoneToken = it }
            }
            val s = PhoneServer(app, token)
            // 9090 if it's free (the address on the phone stays the same), else the next free one.
            var p: Int? = null
            for (i in 0 until 10) {
                p = s.start(); if (p == 9090) break
                if (p != null) { s.stop(); p = null }
                Thread.sleep(300)
            }
            port = p ?: s.start() ?: return null
            server = s
        }
        val ip = Net.localIp() ?: return null
        return "http://$ip:$port/?t=$token"
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
    }

    /** Whether it's running now. */
    val running: Boolean get() = server != null
}
