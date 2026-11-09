package com.glasslauncher.tv

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import com.glasslauncher.tv.ui.backGoesBack
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.clearAndSetSemantics
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
        val REQUESTS = listOf("open_settings", "open_menu", "open_library", "open_live", "open_following", "open_today", "open_folder", "mini_live")
        /** How long a page asked for holds against Home from the launcher fallback. */
        const val REQUEST_HOLD_MS = 3_000L
    }

    private var screen by mutableStateOf(Screen.Home)
    private var resumed by mutableStateOf(false)
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
                        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !systemSaverFirst(secs)) {
                        lastInput = System.currentTimeMillis()
                        com.glasslauncher.tv.ui.SaverGate.opening.value = true
                        startActivity(Intent(this@MainActivity, ScreensaverActivity::class.java))
                    }
                }
            }
            com.glasslauncher.tv.ui.GlassType(app.settings.accent, app.settings.textSize, app.settings.reduceMotion, app.settings.highContrast) { com.glasslauncher.tv.ui.QuietSemantics {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                    val multi = com.glasslauncher.tv.MultiCamOverlay.open.value
                    val mv by androidx.compose.animation.core.animateFloatAsState(if (multi) 1f else 0f,
                        androidx.compose.animation.core.tween(if (multi) 180 else 150), label = "multiBlur")
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().graphicsLayer {
                        renderEffect = if (mv > 0.01f && android.os.Build.VERSION.SDK_INT >= 31)
                            androidx.compose.ui.graphics.BlurEffect(36f * mv, 36f * mv, androidx.compose.ui.graphics.TileMode.Clamp) else null
                    }) { androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.backGoesBack()) { HomeScreen(app, screen) { screen = it } } }
                    if (mv > 0.01f) androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()
                        .graphicsLayer { alpha = mv }.background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.2f)))
                // Back from the screensaver: out of black (it closes faded to black, so nothing snaps).
                if (com.glasslauncher.tv.ui.SaverGate.cover.floatValue > 0f) {
                    // Runs once the home screen draws again (a stopped activity doesn't compose).
                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        val cover = com.glasslauncher.tv.ui.SaverGate.cover
                        kotlinx.coroutines.delay(80)
                        androidx.compose.animation.core.animate(cover.floatValue, 0f, animationSpec = androidx.compose.animation.core.tween(420)) { v, _ -> cover.floatValue = v }
                    }
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()
                        .then(androidx.compose.ui.Modifier.graphicsLayer { alpha = com.glasslauncher.tv.ui.SaverGate.cover.floatValue })
                        .then(androidx.compose.ui.Modifier.background(androidx.compose.ui.graphics.Color.Black)))
                }
                    androidx.compose.runtime.SideEffect { KeyHub.homeInFront = resumed && screen == Screen.Home }
                }
            } }
            // Watch on Home and the kick-off alert: over everything, last.
            com.glasslauncher.tv.ui.NoticeHost()
            com.glasslauncher.tv.ui.NightDim()
            com.glasslauncher.tv.ui.MiniLiveWindow()
            com.glasslauncher.tv.ui.KickOffCard()
        }
        // Goals and close games of followed teams, while the home screen is in front (not over the small window, not in Kids mode).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                val alerts = com.glasslauncher.tv.live.LiveHome.watchAlerts(this@MainActivity) { a ->
                    runOnUiThread {
                        if (com.glasslauncher.tv.ui.MiniLive.now.value == null && !(application as GlassApp).settings.kidsMode) com.glasslauncher.tv.ui.KickOff.now.value = a
                    }
                }
                try { kotlinx.coroutines.awaitCancellation() } finally { com.glasslauncher.tv.live.LiveHome.stopScores(this@MainActivity, alerts) }
            }
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
        // The pages paint their own black: the window's black under them was one more full screen for the TV GPU
        // every frame.
        window.setBackgroundDrawable(null)
    }

    /**
     * The TV's own screensaver (another app, e.g. Aerial Views) is on and starts at about the same time or
     * earlier: leave it to the TV. Starting Glassio's as well put one on top of the other, and Back then had
     * to be pressed twice (issue #9). With Type "TV screensaver" Glassio only asks the TV to start its own.
     */
    private fun systemSaverFirst(secs: Int): Boolean {
        val app = application as GlassApp
        if (app.settings.saverMode == "system") return false
        val cr = contentResolver
        val on = runCatching { android.provider.Settings.Secure.getInt(cr, "screensaver_enabled", 1) == 1 }.getOrDefault(false)
        val comp = runCatching { android.provider.Settings.Secure.getString(cr, "screensaver_components") }.getOrNull().orEmpty()
        // (Glassio itself as the TV screensaver counts too: the TV then starts it, in every app.)
        if (!on || comp.isBlank()) return false
        // Glassio's own dream: on the home screen Glassio starts it itself (a playing background video kept the
        // TV from starting it, so it never came on here).
        if (comp.startsWith(packageName)) return false
        val tv = runCatching { android.provider.Settings.System.getInt(cr, "screen_off_timeout") }.getOrDefault(Int.MAX_VALUE)
        return tv <= secs * 1000L + 5_000
    }

    private fun screenReaderOn(): Boolean =
        getSystemService(android.view.accessibility.AccessibilityManager::class.java)?.isTouchExplorationEnabled == true

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
        com.glasslauncher.tv.data.NuvioWatch.start(this)
        com.glasslauncher.tv.ui.MusicUi.resumedRev.intValue++
        resumed = true
        com.glasslauncher.tv.ui.Notice.front = true
        // Set here too: a pause and resume in the same frame (another start of Glassio while it's in front) left
        // the screen unchanged, nothing was composed again, and Glassio counted as "not in front" until the next
        // change (a remote button meant for the home screen started Glassio again instead).
        KeyHub.homeInFront = screen == Screen.Home
        com.glasslauncher.tv.ui.SaverGate.opening.value = false
        (application as GlassApp).ensureHomeService()     // the Home-button service, if an update switched it off
        com.glasslauncher.tv.data.HaEvents.home(this)     // back from watching: Home Assistant hears it
        openTodayInTheMorning()
        // Live TV's "open when the TV starts" (its settings): the first time the home screen shows after a boot.
        Thread {
            runCatching { com.glasslauncher.tv.live.LiveHome.homeShown(this) }
            runCatching { com.glasslauncher.tv.live.LiveHome.guideLook(this) }
        }.start()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        com.glasslauncher.tv.ui.Notice.front = false
        KeyHub.homeInFront = false
    }

    /** Settings › Home › Open Today in the morning: the first time Home shows after 5 a.m. (not over a page an intent asked for). */
    private fun openTodayInTheMorning() {
        val app = application as GlassApp
        val st = app.settings
        if (!st.todayMorning || !st.todayPage || st.kidsMode) return
        if (android.os.SystemClock.uptimeMillis() - requestedAt < REQUEST_HOLD_MS && requestedAt != 0L) return
        val now = System.currentTimeMillis()
        val tz = java.util.TimeZone.getDefault()
        if (!com.glasslauncher.tv.live.LiveToday.opensItself(now, tz, st.todayShownDay)) return
        st.todayShownDay = com.glasslauncher.tv.live.LiveToday.dayKey(now, tz)
        app.requestSettings.intValue = 104
        app.requestSettingsRev.intValue++
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
        if (i?.hasExtra("widgets_row") == true) app.settings.widgetsRow = i.getBooleanExtra("widgets_row", true)
        // Test only: the widgets' calendars and Home Assistant set back to none.
        if (i?.getStringExtra("widgets_reset") == "all") { app.settings.calLinks = emptyList(); app.settings.haUrl = ""; app.settings.haToken = ""; app.settings.haEntities = emptyList() }
        if (i?.hasExtra("page_glass") == true) com.glasslauncher.tv.ui.PageGlass.on.value = i.getBooleanExtra("page_glass", true)
        i?.getIntExtra("test_goal", -1)?.takeIf { it >= 0 }?.let { num ->      // the alert card, on the TV (am start --ei test_goal 622)
            Thread { com.glasslauncher.tv.live.LiveHome.testAlert(this, num)?.let { a -> runOnUiThread { com.glasslauncher.tv.ui.KickOff.now.value = a } } }.start()
        }
        i?.getIntExtra("open_settings", -1)?.takeIf { it >= 0 }?.let { app.requestSettings.intValue = it; app.requestSettingsRev.intValue++ }
        if (i?.getBooleanExtra("open_menu", false) == true) { app.requestSettings.intValue = 100; app.requestSettingsRev.intValue++ }
        if (i?.getBooleanExtra("open_library", false) == true) { app.requestSettings.intValue = 101; app.requestSettingsRev.intValue++ }
        // Live TV's menu → Home, or am start ... --ez open_live true / --ez open_following true.
        if (i?.getBooleanExtra("open_live", false) == true) { app.requestSettings.intValue = 102; app.requestSettingsRev.intValue++ }
        if (i?.getBooleanExtra("open_following", false) == true) { app.requestSettings.intValue = 103; app.requestSettingsRev.intValue++ }
        if (i?.getBooleanExtra("open_today", false) == true) { app.requestSettings.intValue = 104; app.requestSettingsRev.intValue++ }
        // Test only: Sync with other TVs on or off, a pairing code shown (in the log), a sync now, the pairs forgotten.
        if (i?.hasExtra("sync_on") == true) { app.settings.syncOn = i.getBooleanExtra("sync_on", false); Thread { com.glasslauncher.tv.data.LanSync.apply(this) }.start() }
        if (i?.getBooleanExtra("sync_pair", false) == true) com.glasslauncher.tv.data.Glog.i("Sync test: pairing code " + com.glasslauncher.tv.data.LanSync.showCode())
        if (i?.getBooleanExtra("sync_now", false) == true) Thread { com.glasslauncher.tv.data.Glog.i("Sync test: synced with " + com.glasslauncher.tv.data.LanSync.syncNow(this)) }.start()
        if (i?.getBooleanExtra("sync_forget", false) == true) com.glasslauncher.tv.data.LanSync.peers(this).forEach { com.glasslauncher.tv.data.LanSync.forget(this, it.id) }
        // Test only: Backup to WebDAV's path with a stand-in file (WebDav.test), its outcome in the log.
        if (i?.getBooleanExtra("dav_test", false) == true) Thread {
            runCatching { com.glasslauncher.tv.data.WebDav.test(this) }
                .fold({ com.glasslauncher.tv.data.Glog.i("WebDAV test: ok, ${it.size} in the folder: ${it.joinToString()}") },
                    { com.glasslauncher.tv.data.Glog.w("WebDAV test failed", it) })
        }.start()
        if (i?.hasExtra("timing") == true) com.glasslauncher.tv.data.Glog.timing = i.getBooleanExtra("timing", false)
        if (i?.hasExtra("dbg_hide") == true) com.glasslauncher.tv.ui.DebugHide.intValue = i.getIntExtra("dbg_hide", 0)
        i?.getStringExtra("dock_style")?.let { app.settings.dockStyle = it }
        i?.getStringExtra("panel_style")?.let { app.settings.panelStyle = it }
        i?.getStringExtra("tile_style")?.let { app.settings.tileStyle = it }
        i?.getStringExtra("theme")?.let { app.settings.applyTheme(it) }
        i?.getStringExtra("accent")?.let { if (com.glasslauncher.tv.ui.ACCENTS.any { a -> a.first == it }) app.settings.accent = it }
        i?.getStringExtra("text_size")?.let { if (com.glasslauncher.tv.ui.TEXT_SIZES.any { t -> t.first == it }) app.settings.textSize = it }
        i?.getStringExtra("ticker_test")?.let { com.glasslauncher.tv.ui.TickerTest.value = it }
        i?.getStringExtra("hero_hidden")?.let { if (it == "clear") app.settings.heroHidden = emptyList() }
        if (i?.hasExtra("season_month") == true) app.settings.seasonMonth = i.getIntExtra("season_month", 0)
        i?.getStringExtra("menu_mode")?.let { app.settings.menuMode = it }
        i?.getStringExtra("bg_mode")?.let { if (it in setOf("carousel", "image", "video", "aerial")) app.settings.bgMode = it }
        if (i?.hasExtra("menu_city") == true) app.settings.menuCity = i.getBooleanExtra("menu_city", true)
        if (i?.hasExtra("focus_color") == true) app.settings.focusColor = i.getBooleanExtra("focus_color", false)
        i?.getStringExtra("ha_webhook")?.let { app.settings.haWebhook = it; app.settings.haOnLaunch = it.startsWith("http") }
        i?.getStringExtra("saver_mode")?.let { if (it in setOf("aerial", "slideshow", "system")) app.settings.saverMode = it }
        i?.getStringExtra("slide_anim")?.let { if (it in setOf("fade", "slide", "apple")) app.settings.slideAnim = it }
        i?.getStringExtra("dots_mode")?.let { if (it in setOf("window", "liquid", "progress", "hidden")) app.settings.dotsMode = it }
        i?.getStringExtra("hero_source")?.let { if (it in setOf("all", "recs")) app.settings.heroSource = it }
        i?.getStringExtra("hero_change")?.let { if (it in setOf("quick", "steps", "fade")) app.settings.heroChange = it }
        i?.getStringExtra("slow_zoom")?.let { if (it in setOf("off", "hero", "cards", "both")) app.settings.slowZoom = it }
        i?.getStringExtra("hero_scroll")?.let { if (it in setOf("away", "stay")) app.settings.heroScroll = it }
        if (i?.hasExtra("card_open_ms") == true) app.settings.cardOpenMs = i.getIntExtra("card_open_ms", 300).coerceIn(100, 1000)
        i?.getStringExtra("card_open")?.let { if (it in setOf("together", "staggered")) app.settings.cardOpen = it }
        if (i?.hasExtra("card_keep_open") == true) app.settings.cardKeepOpen = i.getBooleanExtra("card_keep_open", true)
        i?.getStringExtra("cw_ok")?.let { if (it in setOf("resume", "card")) app.settings.cwOk = it }
        if (i?.hasExtra("tv_speed") == true) app.settings.homeTvSpeed = i.getBooleanExtra("tv_speed", false)
        i?.getStringExtra("card_style")?.let { if (it in setOf("light", "dark")) app.settings.cardStyle = it }
        if (i?.hasExtra("card_trailer") == true) app.settings.cardTrailerSec = i.getIntExtra("card_trailer", 0).coerceIn(0, 10)
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
    @android.annotation.SuppressLint("RestrictedApi") // normal Activity override; lint misreads the androidx stub
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (com.glasslauncher.tv.ui.NavGate.blocks(event.keyCode)) return true
        // The shuffle button and "press the button you want" (also caught by the Home button service first).
        if (KeyHub.handle(application as GlassApp, event.keyCode, event.action == KeyEvent.ACTION_DOWN)) return true
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
        if (keyCode == KeyEvent.KEYCODE_MENU && (application as GlassApp).settings.kidsMode) return true     // Kids mode: not to Settings
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            screen = if (screen == Screen.Settings) Screen.Home else Screen.Settings
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
