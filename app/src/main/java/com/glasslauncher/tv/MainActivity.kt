package com.glasslauncher.tv

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import com.glasslauncher.tv.saver.ScreensaverActivity
import com.glasslauncher.tv.ui.HomeScreen
import com.glasslauncher.tv.ui.Screen
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private companion object {
        /** The extras that ask for a page or a window. */
        val REQUESTS = listOf("open_settings", "open_menu", "open_library", "open_live", "open_following", "open_folder", "mini_live")
        /** How long a page asked for holds against Home from the launcher fallback. */
        const val REQUEST_HOLD_MS = 3_000L
    }

    private var screen by mutableStateOf(Screen.Home)
    @Volatile private var lastInput = System.currentTimeMillis()
    /** When a page was last asked for by an intent (open_settings, open_live, Watch on Home…), uptime ms; 0: never. */
    private var requestedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideBars()
        val app = application as GlassApp
        handleExtras(intent)
        setContent {
            // A launcher never exits on Back; it just returns to its home view.
            BackHandler(enabled = true) { screen = Screen.Home }
            LaunchedEffect(Unit) {
                while (true) {
                    delay(2_000)
                    val secs = app.settings.idleSeconds
                    val idle = System.currentTimeMillis() - lastInput
                    // Not while a channel plays in the small window (Watch on Home).
                    if (secs > 0 && idle > secs * 1000L && com.glasslauncher.tv.ui.MiniLive.now.value == null &&
                        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        lastInput = System.currentTimeMillis()
                        startActivity(Intent(this@MainActivity, ScreensaverActivity::class.java))
                    }
                }
            }
            com.glasslauncher.tv.ui.GlassType { com.glasslauncher.tv.ui.QuietSemantics { HomeScreen(app, screen) { screen = it } } }
            // Watch on Home and the kick-off alert: over everything, last.
            com.glasslauncher.tv.ui.MiniLiveWindow()
            com.glasslauncher.tv.ui.KickOffCard()
        }
        // Live TV's cards in Continue Watching: read again every minute while the home screen is in front.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    withContext(kotlinx.coroutines.Dispatchers.IO) { com.glasslauncher.tv.data.LiveCards.refresh(this@MainActivity) }
                    // A team you follow just kicked off (and nothing's in the small window): the alert.
                    val kick = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { com.glasslauncher.tv.live.LiveHome.matchDay(this@MainActivity) }.getOrNull()
                            ?.takeIf { com.glasslauncher.tv.ui.MiniLive.now.value == null && com.glasslauncher.tv.ui.KickOff.shouldTell(this@MainActivity, it) }
                    }
                    if (kick != null) com.glasslauncher.tv.ui.KickOff.now.value = kick
                    delay(60_000)
                }
            }
        }
    }

    private fun hideBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onStop() {
        super.onStop()
        (application as GlassApp).cw.inFront = false     // away (another app, Live TV): the accounts less often
    }

    override fun onResume() {
        super.onResume()
        lastInput = System.currentTimeMillis()
        hideBars()
        (application as GlassApp).cw.pollNow()
        // Live TV's "open when the TV starts" (its settings): the first time the home screen shows after a boot.
        Thread { runCatching { com.glasslauncher.tv.live.LiveHome.homeShown(this) } }.start()
    }

    /** Terminal helper: am start -n com.glassioyahsun.tv/com.glasslauncher.tv.MainActivity --es icon_pack <package> ("none" clears it). */
    private fun handleExtras(i: Intent?) {
        val app = application as GlassApp
        i?.getStringExtra("reset_icon")?.let { app.icons.reset(it) }
        if (i?.getBooleanExtra("open_folder", false) == true) app.requestFolder.value = true
        if (i != null && REQUESTS.any { i.hasExtra(it) }) requestedAt = android.os.SystemClock.uptimeMillis()
        if (i?.hasExtra("show_setup") == true) app.showSetup.value = i.getBooleanExtra("show_setup", false)
        // Watch on Home (Live TV's menu): the channel it handed over, in the small window. Read from the app's own file,
        // so nothing outside the app can put a stream here.
        if (i?.getBooleanExtra("mini_live", false) == true) {
            screen = Screen.Home
            Thread {
                val m = runCatching { com.glasslauncher.tv.live.LiveHome.takeMini(this) }.getOrNull()
                if (m != null) runOnUiThread { com.glasslauncher.tv.ui.MiniLive.now.value = m }
            }.start()
        }
        if (i?.hasExtra("hero_autoplay") == true) app.settings.heroAutoplay = i.getBooleanExtra("hero_autoplay", true)
        if (i?.hasExtra("page_glass") == true) com.glasslauncher.tv.ui.PageGlass.on.value = i.getBooleanExtra("page_glass", true)
        i?.getIntExtra("open_settings", -1)?.takeIf { it >= 0 }?.let { app.requestSettings.intValue = it; app.requestSettingsRev.intValue++ }
        if (i?.getBooleanExtra("open_menu", false) == true) { app.requestSettings.intValue = 100; app.requestSettingsRev.intValue++ }
        if (i?.getBooleanExtra("open_library", false) == true) { app.requestSettings.intValue = 101; app.requestSettingsRev.intValue++ }
        // Live TV's menu → Home, or am start ... --ez open_live true / --ez open_following true.
        if (i?.getBooleanExtra("open_live", false) == true) { app.requestSettings.intValue = 102; app.requestSettingsRev.intValue++ }
        if (i?.getBooleanExtra("open_following", false) == true) { app.requestSettings.intValue = 103; app.requestSettingsRev.intValue++ }
        if (i?.hasExtra("timing") == true) com.glasslauncher.tv.data.Glog.timing = i.getBooleanExtra("timing", false)
        if (i?.hasExtra("dbg_hide") == true) com.glasslauncher.tv.ui.DebugHide.intValue = i.getIntExtra("dbg_hide", 0)
        i?.getStringExtra("dock_style")?.let { app.settings.dockStyle = it }
        i?.getStringExtra("panel_style")?.let { app.settings.panelStyle = it }
        i?.getStringExtra("tile_style")?.let { app.settings.tileStyle = it }
        i?.getStringExtra("theme")?.let { app.settings.applyTheme(it) }
        i?.getStringExtra("menu_mode")?.let { app.settings.menuMode = it }
        i?.getStringExtra("bg_mode")?.let { if (it in setOf("carousel", "image", "video", "aerial")) app.settings.bgMode = it }
        if (i?.hasExtra("menu_city") == true) app.settings.menuCity = i.getBooleanExtra("menu_city", true)
        if (i?.hasExtra("focus_color") == true) app.settings.focusColor = i.getBooleanExtra("focus_color", false)
        i?.getStringExtra("ha_webhook")?.let { app.settings.haWebhook = it; app.settings.haOnLaunch = it.startsWith("http") }
        i?.getStringExtra("saver_mode")?.let { if (it in setOf("aerial", "slideshow", "system")) app.settings.saverMode = it }
        i?.getStringExtra("slide_anim")?.let { if (it in setOf("fade", "slide", "apple")) app.settings.slideAnim = it }
        i?.getStringExtra("dots_mode")?.let { if (it in setOf("window", "liquid", "progress", "hidden")) app.settings.dotsMode = it }
        i?.getStringExtra("continue_mode")?.let { if (it in setOf("row", "off", "first", "mixed")) app.settings.continueMode = it }
        if (i?.getBooleanExtra("start_saver", false) == true)
            startActivity(android.content.Intent(this, com.glasslauncher.tv.saver.ScreensaverActivity::class.java))
        if (i?.getBooleanExtra("dump_prefs", false) == true) app.settings.dumpAll().toSortedMap().forEach { (k, v) ->
            android.util.Log.i("GlassPrefs", "$k=${v.toString().take(120)}")
        }
        if (i?.getBooleanExtra("dump_settings", false) == true) app.settings.run {
            android.util.Log.i("GlassSettings", "dockStyle=$dockStyle tileStyle=$tileStyle panelStyle=$panelStyle iconPack=$iconPack blur=$blurRadius refraction=$refraction dockScale=$dockScale saverMode=$saverMode backSaver=$backSaver continueMode=$continueMode cwLead=$cwLead cw=${(application as GlassApp).cw.entries.value.size} cwAccess=${(application as GlassApp).cw.hasLogAccess()}")
        }
        i?.getStringExtra("perf_mode")?.let { if (it in setOf("auto", "performance", "balanced", "quality")) app.settings.applyMode(it) }
        i?.getIntExtra("refraction", -1)?.takeIf { it >= 0 }?.let { app.settings.refraction = it; app.settings.perfMode = "custom" }
        i?.getStringExtra("art_choice")?.let { if (it in setOf("main", "second", "best", "addon")) app.settings.artChoice = it }
        i?.getIntExtra("blur", -1)?.takeIf { it >= 0 }?.let { app.settings.blurRadius = it }
        i?.getStringExtra("dock_style")?.let { if (it in setOf("liquid", "clear", "amoled", "bow", "water", "water_still")) app.settings.dockStyle = it }
        i?.getStringExtra("tile_style")?.let { if (it in setOf("liquid", "clear", "amoled", "bow", "water", "water_still")) app.settings.tileStyle = it }
        i?.getStringExtra("icon_pack")?.let { p ->
            if (p == "none") app.settings.iconPack = ""
            else if (app.packs.installed().any { it.pkg == p }) app.settings.iconPack = p
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleExtras(intent)
        // Home pressed while we are already in front: go back to the plain home view. Not when Google TV's launcher was
        // only seen coming up (HomeKeyService's fallback) just after a page was asked for: Glassio starting (after an
        // update, say) briefly shows that launcher, and its late Home closed the page that had just opened.
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            val byKey = intent.getBooleanExtra(HomeKeyService.EXTRA_BY_KEY, true)
            if (byKey || android.os.SystemClock.uptimeMillis() - requestedAt > REQUEST_HOLD_MS) {
                screen = Screen.Home
                (application as GlassApp).homeRev.intValue++
            }
        }
        lastInput = System.currentTimeMillis()
    }

    override fun onUserInteraction() {
        lastInput = System.currentTimeMillis()
        (application as GlassApp).touch()
    }

    // Watch on Home: Back goes to the small window (selects it), and Back on it closes it, before the home screen sees
    // it (its own keys take Back too).
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && com.glasslauncher.tv.ui.KickOff.now.value != null) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) com.glasslauncher.tv.ui.KickOff.close()
            return true
        }
        val mini = com.glasslauncher.tv.ui.MiniLive
        if (event.keyCode == KeyEvent.KEYCODE_BACK && mini.now.value != null) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                if (mini.selected.value) mini.close() else mini.selectRequest.intValue++
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            screen = if (screen == Screen.Settings) Screen.Home else Screen.Settings
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
