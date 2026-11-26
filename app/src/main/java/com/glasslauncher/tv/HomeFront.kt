package com.glasslauncher.tv

/**
 * What is in front, as far as Google TV's home screen goes: from the window changes HomeKeyService sees. A takeover
 * that got lost (a busy TV, a window change missed) is tried again only while Google TV's home is still there.
 */
class HomeFront {
    enum class What { OTHER, GOOGLE_HOME, GOOGLE_WAKING }

    var what = What.OTHER
        private set

    /** A window came up; `screen`: a screen of its own (an app's activity), not a pop-up or the volume bar. */
    fun window(pkg: String, cls: String, screen: Boolean) {
        val google = pkg in LAUNCHERS
        when {
            google && isHome(cls) -> what = What.GOOGLE_HOME
            google && cls.endsWith(".DispatchActivity") -> what = What.GOOGLE_WAKING
            screen -> what = What.OTHER
        }
    }

    /** Glassio was seen in front, or someone chose something: nothing to bring back. */
    fun clear() { what = What.OTHER }

    /** Glassio should be tried again now. Google TV's start-up screen counts only just after the TV woke. */
    fun retry(glassioFront: Boolean, waking: Boolean): Boolean =
        !glassioFront && (what == What.GOOGLE_HOME || waking && what == What.GOOGLE_WAKING)

    companion object {
        val LAUNCHERS = setOf("com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher")
        fun isHome(cls: String) = cls.contains("HomeActivity") || cls.contains("MainActivity") || cls.contains("LauncherActivity")
        /** When to look again after a takeover, and after the TV woke (its launcher comes up some seconds in). */
        val AFTER_SHOW = longArrayOf(2_000, 4_000, 7_000, 11_000)
        val AFTER_WAKE = longArrayOf(4_000, 7_000, 11_000, 16_000, 22_000)
    }
}
