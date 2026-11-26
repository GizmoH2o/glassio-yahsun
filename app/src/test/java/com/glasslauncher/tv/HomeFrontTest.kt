package com.glasslauncher.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFrontTest {
    private val google = "com.google.android.apps.tv.launcherx"
    private val home = "$google.home.HomeActivity"
    private val waking = "$google.coreservices.bootmode.DispatchActivity"
    private fun front() = HomeFront().apply { window(google, home, true) }

    @Test fun googleHomeLeftInFrontIsRetried() = assertTrue(front().retry(glassioFront = false, waking = false))
    @Test fun notOnceGlassioIsThere() = assertFalse(front().retry(glassioFront = true, waking = true))
    @Test fun notOverAnAppOpenedSince() =
        assertFalse(front().apply { window("com.netflix.ninja", "com.netflix.ninja.MainActivity", true) }.retry(false, true))
    @Test fun notOverGoogleTvsOwnOtherScreens() =
        assertFalse(front().apply { window(google, "$google.search.SearchActivity", true) }.retry(false, true))
    @Test fun aPopUpOverGoogleHomeChangesNothing() =
        assertTrue(front().apply { window("com.android.systemui", "android.widget.FrameLayout", false) }.retry(false, false))
    @Test fun startUpScreenOnlyWhileWaking() {
        val f = HomeFront().apply { window(google, waking, true) }
        assertTrue(f.retry(false, waking = true)); assertFalse(f.retry(false, waking = false))
    }
    @Test fun nothingSeenNothingDone() = assertFalse(HomeFront().retry(false, true))
    @Test fun clearedWhenSomethingIsChosen() = assertFalse(front().apply { clear() }.retry(false, true))
}
