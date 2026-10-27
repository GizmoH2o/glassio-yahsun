package com.glasslauncher.tv.ui

import com.glasslauncher.tv.BootReceiver

import android.content.Context
import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glasslauncher.tv.GlassApp
import com.glasslauncher.tv.data.AppEntry
import com.glasslauncher.tv.data.NuvioLaunch
import com.glasslauncher.tv.net.Net
import com.glasslauncher.tv.net.PhoneServer
import com.glasslauncher.tv.saver.ScreensaverActivity
import com.glasslauncher.tv.data.Backup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private sealed interface SRow
private class Toggle(val title: String, val desc: String, val get: () -> Boolean, val set: (Boolean) -> Unit) : SRow
private class Adjust(val title: String, val desc: String, val value: () -> String, val step: (Int) -> Unit) : SRow
/** [ok]: true shows the value in green with a check mark (set and working), false in red. */
private class Act(val title: String, val desc: String, val value: String = "", val ok: Boolean? = null, val onClick: () -> Unit) : SRow
private class Note(val text: String) : SRow
private class AppItem(val entry: AppEntry) : SRow

private enum class Kind { Rows, Phone, About }
private class Category(val title: String, val kind: Kind = Kind.Rows, val rows: @Composable () -> List<SRow> = { emptyList() })

private fun <T> cycle(list: List<T>, cur: T, d: Int): T {
    val i = list.indexOf(cur).let { if (it < 0) 0 else it }
    return list[(i + d).mod(list.size)]
}
private fun styleName(v: String) = when (v) { "water" -> "Glass like glass"; "water_still" -> "Glass like glass (still)"; "clear" -> "Clear (matte)"; "amoled" -> "AMOLED black"; "bow" -> "Black on white"; else -> "Liquid glass" }
private fun themeName(v: String) = when (v) { "bow" -> "Black on white"; "amoled" -> "Black AMOLED"; "liquid" -> "Liquid"; "clear" -> "Clear"; "water_still" -> "Glass still"; "water" -> "Glass is glass"; else -> "Custom" }
private fun hostOf(u: String) = runCatching { java.net.URI(u).host }.getOrNull() ?: "add-on"
private fun pct(f: Float) = "${(f * 100).roundToInt()}%"
private fun tenth(f: Float, d: Int) = ((f * 10).roundToInt() + d) / 10f

/** Google Play page of [pkg], or a Play search for [query]; falls back to the web page. */
private fun openPlay(ctx: Context, pkg: String?, query: String?) {
    val q = android.net.Uri.encode(query.orEmpty())
    val market = if (pkg != null) "market://details?id=$pkg" else "market://search?q=$q&c=apps"
    val web = if (pkg != null) "https://play.google.com/store/apps/details?id=$pkg" else "https://play.google.com/store/search?q=$q&c=apps"
    for (u in listOf(market, web)) {
        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return }
        catch (_: Exception) {}
    }
    toast(ctx, "Google Play is not available on this device")
}

/** Is Glassio's Home-button accessibility service switched on? */
internal fun isHomeKeyOn(ctx: Context): Boolean = runCatching {
    AndroidSettings.Secure.getString(ctx.contentResolver, AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        .contains("${ctx.packageName}/")
}.getOrDefault(false)

private fun openSystem(ctx: Context, action: String) {
    try { ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    catch (_: Exception) { toast(ctx, "This screen is not available on this device") }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun SettingsScreen(app: GlassApp, initialCat: Int, onClose: () -> Unit) {
    val s = LocalScale.current
    val ctx = LocalContext.current
    val st = app.settings
    val scope = rememberCoroutineScope()
    val traktCode by app.cw.traktCode.collectAsState()
    val nuvioUrl by app.library.nuvioUrl.collectAsState()
    // The QR dialog closes when this turns null (sign-in finished); the Nuvio code is in the QR itself.
    val nuvioShort = remember { kotlinx.coroutines.flow.MutableStateFlow<String?>(null) }
    LaunchedEffect(nuvioUrl) { nuvioShort.value = nuvioUrl?.let { "" } }
    val stremioUrl by app.library.stremioUrl.collectAsState()
    // The same for Stremio: its link carries the code.
    val stremioShort = remember { kotlinx.coroutines.flow.MutableStateFlow<String?>(null) }
    LaunchedEffect(stremioUrl) { stremioShort.value = stremioUrl?.let { "" } }
    val tvRev = remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var tmdbOk by remember { mutableStateOf<Boolean?>(null) }
    var tmdbChecking by remember { mutableStateOf(false) }
    LaunchedEffect(st.tmdbToken) {
        tmdbOk = null
        val t = st.tmdbToken
        if (t.isBlank() || com.glasslauncher.tv.data.Tmdb.looksLikeApiKey(t)) return@LaunchedEffect
        tmdbChecking = true
        tmdbOk = kotlinx.coroutines.withContext(Dispatchers.IO) { com.glasslauncher.tv.data.Tmdb.verify(t) }
        tmdbChecking = false
    }
    var homeKeyOn by remember { mutableStateOf(isHomeKeyOn(ctx)) }
    var adbAccess by remember { mutableStateOf<Boolean?>(if (app.cw.hasLogAccess()) true else null) }
    val catalogs by app.catalogs.catalogs.collectAsState()
    val selected by app.catalogs.selectedKeys.collectAsState()
    val addons by app.catalogs.addonList.collectAsState()
    val accountAddons by app.catalogs.accountAddons.collectAsState()
    val stremioAddons by app.catalogs.stremioAddons.collectAsState()
    val status by app.catalogs.status.collectAsState()
    val allApps by app.apps.apps.collectAsState()
    var dialog by remember { mutableStateOf<DialogState?>(null) }
    var phoneDialog by remember { mutableStateOf(false) }
    var cat by remember { mutableIntStateOf(initialCat) }
    var shownCat by remember { mutableIntStateOf(initialCat) }
    LaunchedEffect(cat) { if (cat != shownCat) { kotlinx.coroutines.delay(120); shownCat = cat } }
    val packList = remember { listOf("") + app.packs.installed().map { it.pkg } }
    val packLabels = remember { app.packs.installed().associate { it.pkg to it.label } }
    var rightFocused by remember { mutableStateOf(false) }
    val firstCat = remember { FocusRequester() }
    // One requester per category: leaving the right pane goes back to the category it belongs to,
    // instead of whichever category happens to sit next to the row (that switched the page).
    val catFocus = remember { List(16) { FocusRequester() } }

    fun io(block: suspend () -> String) = scope.launch(Dispatchers.IO) {
        toast(ctx, runCatching { block() }.getOrElse { it.message ?: "Failed" })
    }
    // TMDB: the token is checked with TMDB itself whenever it changes, so "Set" means it really works.
    fun tmdbRow(): SRow {
        val t = st.tmdbToken
        val (value, ok) = when {
            t.isBlank() -> "Not set" to null
            com.glasslauncher.tv.data.Tmdb.looksLikeApiKey(t) -> "API Key, not the token" to false
            tmdbOk == true -> "Set" to true
            tmdbOk == false -> "Not working" to false
            tmdbChecking -> "Checking…" to null
            else -> "Set (couldn't check, offline?)" to null
        }
        return Act("TMDB token", "Use the API Read Access Token (long, starts eyJ…), not the short API Key",
            value, ok) {
            dialog = TextEntry("TMDB API Read Access Token", "", "The long token starting eyJ… (not the 32-character API Key). Easier from the phone page") {
                st.tmdbToken = it; app.catalogs.clearEnrichment()
            }
        }
    }
    // Connect / sign out of the Nuvio account: the same row in Menu & Library, Catalogs and Nuvio, Stremio & Home Assistant.
    fun nuvioAccountRow(): SRow =
        if (!app.library.nuvio.connected) Act("Connect Nuvio account", "Scan a QR code with your phone and approve, like signing in to Nuvio on a TV", if (nuvioUrl != null) "Waiting…" else "") {
                        val appCtx = ctx.applicationContext
                        app.library.connectNuvio { msg -> toast(appCtx, msg) }
                        scope.launch {
                            var n = 0
                            while (app.library.nuvioUrl.value == null && n++ < 60) kotlinx.coroutines.delay(100)
                            app.library.nuvioUrl.value?.let { u -> dialog = QrCodeDialog("Connect Nuvio", u, nuvioShort,
                                "Scan with your phone and approve on nuvio.tv. This closes by itself once you approve.") }
                        }
                    } else Act("Nuvio account", "Signed in" + app.library.nuvio.email.takeIf { it.isNotBlank() }?.let { " as $it" }.orEmpty() + ": Library, add-ons and saving use it", "Sign out") { app.library.nuvio.signOut(); app.library.nuvioList.value = emptyList(); st.libraryNuvio = st.libraryNuvio; toast(ctx, "Signed out of Nuvio") }
    // Connect / sign out of the Stremio account: the same row in Menu & Library, Catalogs and Nuvio, Stremio & Home Assistant.
    fun stremioAccountRow(): SRow =
        if (!app.library.stremio.connected) Act("Connect Stremio account", "Scan a QR code with your phone, sign in to Stremio there and confirm, like the Stremio TV app", if (stremioUrl != null) "Waiting…" else "") {
                        val appCtx = ctx.applicationContext
                        app.library.connectStremio { msg -> toast(appCtx, msg) }
                        scope.launch {
                            var n = 0
                            while (app.library.stremioUrl.value == null && n++ < 60) kotlinx.coroutines.delay(100)
                            app.library.stremioUrl.value?.let { u -> dialog = QrCodeDialog("Connect Stremio", u, stremioShort,
                                "Scan with your phone, sign in to Stremio and confirm. This closes by itself once you do.") }
                        }
                    } else Act("Stremio account", "Signed in" + app.library.stremio.email.takeIf { it.isNotBlank() }?.let { " as $it" }.orEmpty() + ": Library, add-ons and saving use it", "Sign out") {
                        app.library.stremio.signOut(); app.library.stremioList.value = emptyList(); st.libraryStremio = st.libraryStremio; toast(ctx, "Signed out of Stremio")
                    }


    val categories = listOf(
        Category("Performance") {
            fun custom() { st.perfMode = "custom" }
            listOf(
                Adjust("Mode", when (st.perfMode) {
                    "auto" -> "Picked for this TV from its memory and graphics chip"
                    "performance" -> "Smoothest: lighter pictures, quick fades, flat glass"
                    "quality" -> "Sharpest: original pictures, slow fades, full glass"
                    "balanced" -> "Sharp pictures, smooth navigation"
                    else -> "Your own mix of the settings below"
                }, {
                    fun n(m: String) = when (m) { "performance" -> "Performance"; "quality" -> "Quality"; "balanced" -> "Balanced"; else -> "Custom" }
                    if (st.perfMode == "auto") "Auto (${n(st.autoTier)})" else n(st.perfMode)
                }, { st.applyMode(cycle(listOf("auto", "performance", "balanced", "quality"), st.perfMode.takeIf { m -> m != "custom" } ?: "auto", it)) }),
                Adjust("Glass detail", "Resolution of the picture the glass bends (higher = crisper glass, more memory)", {
                    when (st.glassRes) { 720 -> "Sharp (720 px)"; else -> "Standard (480 px)" }
                }, { st.glassRes = cycle(listOf(480, 720), st.glassRes, it); custom() }),
                Adjust("Picture quality", "Carousel backdrops", { if (st.imageQuality == "high") "High (1280 px)" else "Maximum (original)" },
                    { st.imageQuality = cycle(listOf("high", "max"), st.imageQuality, it); custom() }),
                Adjust("Prepare ahead", "Slides loaded before you get to them", { "${st.prefetchDepth}" }, { st.prefetchDepth += it; custom() }),
                Adjust("Slide transition", "", { "${st.transitionMs} ms" }, { st.transitionMs += it * 50; custom() }),
                Adjust("Glass refraction", "0 = flat glass (lightest)", { "${st.refraction}" }, { st.refraction += it * 2; custom() }),
                Toggle("Backdrop gallery", "Several pictures per title", { st.heroGallery }, { st.heroGallery = it; custom() }),
            )
        },
        Category("Carousel") {
            listOf(
                Toggle("Auto-play", "Slides change on their own", { st.heroAutoplay }, { st.heroAutoplay = it }),
                Adjust("Slide time", "Seconds before the next title", { "${st.heroIntervalSec} s" }, { st.heroIntervalSec += it }),
                Adjust("Transition speed", "Fade between slides (lower = faster)", { "${st.transitionMs} ms" }, { st.transitionMs += it * 100 }),
                Adjust("Slide animation", "Apple TV: the next picture opens up from the right (from the left going back) · Slide: pushed in · Fade",
                    { when (st.slideAnim) { "apple" -> "Apple TV"; "slide" -> "Slide"; else -> "Fade" } },
                    { st.slideAnim = cycle(listOf("apple", "slide", "fade"), st.slideAnim, it) }),
                Adjust("Buttons", "Play + Next: like Apple TV, Right or OK on the round button shows the next title, Left on Play the previous one",
                    { when (st.heroButtons) { "apple" -> "Play + Next"; "play" -> "Play only"; else -> "Hidden" } },
                    { st.heroButtons = cycle(listOf("apple", "play", "hidden"), st.heroButtons, it) }),
                Toggle("Dots auto-hide", "Show them only when the carousel is selected", { st.dotsAutoHide }, { st.dotsAutoHide = it }),
                Adjust("Title style", "Logo image, plain text, or nothing", {
                    when (st.titleMode) { "logo" -> "Logo"; "text" -> "Text"; else -> "Hidden" }
                }, { st.titleMode = cycle(listOf("logo", "text", "hidden"), st.titleMode, it) }),
                Adjust("Logo / title size", "Logos are evened out so wide and tall ones look the same size", { pct(st.titleScale) }, { st.titleScale = tenth(st.titleScale, it) }),
                Adjust("Rating, year, platform size", "", { pct(st.metaScale) }, { st.metaScale = tenth(st.metaScale, it) }),
                Adjust("Genres size", "", { pct(st.genresScale) }, { st.genresScale = tenth(st.genresScale, it) }),
                Toggle("Rating, year, platform", "The chips under the title", { st.showMeta }, { st.showMeta = it }),
                Toggle("Genres line", "", { st.showGenres }, { st.showGenres = it }),
                Toggle("Synopsis", "", { st.showSynopsis }, { st.showSynopsis = it }),
                Toggle("Synopsis only when the carousel is selected", "Hidden while you browse the apps", { st.synopsisOnFocus }, { st.synopsisOnFocus = it }),
                Adjust("Synopsis size", "", { pct(st.synopsisScale) }, { st.synopsisScale = tenth(st.synopsisScale, it) }),
                Adjust("Synopsis lines", "", { "${st.synopsisLines}" }, { st.synopsisLines += it }),
                Adjust("Dots style", "", {
                    when (st.dotsMode) { "window" -> "Dots"; "liquid" -> "Liquid glass dots"; "progress" -> "Progress bar"; else -> "Hidden" }
                }, { st.dotsMode = cycle(listOf("window", "liquid", "progress", "hidden"), st.dotsMode, it) }),
                Adjust("Dots per group", "", { "${st.dotsMax}" }, { st.dotsMax += it }),
                Adjust("Titles per catalog", "Reload to apply", { "${st.perCatalog}" }, { st.perCatalog += it * 5 }),
                Adjust("Total titles", "0 = every title, endless loop", { if (st.maxItems == 0) "Unlimited" else "${st.maxItems}" }, { st.maxItems = (st.maxItems + it * 10).coerceAtLeast(0) }),
                Toggle("Backdrop gallery", "Like Apple TV: each title shows several of its pictures (needs the TMDB token)", { st.heroGallery }, { st.heroGallery = it }),
                Adjust("Pictures per title", "", { "${st.galleryShots}" }, { st.galleryShots = (st.galleryShots + it).coerceIn(2, 6) }),
                Toggle("Shuffle all catalogs", "Random order across every catalog, a new mix each time the list loads", { st.shuffle }, { st.shuffle = it }),
                Adjust("Synopsis language", "Needs a TMDB token. English is used when a title has no text in this language", { com.glasslauncher.tv.data.LANG_NAMES[st.descLang] ?: "English" }, {
                    st.descLang = cycle(com.glasslauncher.tv.data.TMDB_LANGS.keys.toList(), st.descLang, it); app.catalogs.clearEnrichment()
                }),
                Adjust("Artwork", "Which picture each title uses", {
                    when (st.artChoice) { "second" -> "Second picture"; "best" -> "Highest resolution"; "addon" -> "Add-on picture"; else -> "Main (TMDB)" }
                }, { st.artChoice = cycle(listOf("main", "second", "best", "addon"), st.artChoice, it) }),
                Note("One title: on the carousel hold OK (or press Menu) to switch to its next picture. Glassio remembers it."),
                Act("Reset pictures picked per title", "", "") { st.clearArtPicks(); toast(ctx, "Every title is back to the Artwork setting") },
                Act("Reload carousel now", "") { io { app.catalogs.refresh(); app.catalogs.status.value } },
            )
        },
        Category("Clock & glass") {
            val bgFolder = CustomBackground.folder(ctx).absolutePath
            fun pick(video: Boolean) {
                val files = CustomBackground.files(ctx, video)
                if (files.isEmpty()) { toast(ctx, "No ${if (video) "videos" else "pictures"} yet. Copy them to: $bgFolder"); return }
                dialog = MenuDialog(if (video) "Videos in the folder" else "Pictures in the folder", files.map { f ->
                    f.name to { if (video) { st.bgVideo = f.absolutePath; st.bgMode = "video" } else { st.bgImage = f.absolutePath; st.bgMode = "image" } }
                })
            }
            listOf(
                Adjust("Background", "Each title's own picture, or yours: a picture or a video, from a link or the folder below",
                    { when (st.bgMode) { "image" -> "My picture"; "video" -> "My video"; "aerial" -> "Screensaver (aerials)"; else -> "Title backdrops" } },
                    { st.bgMode = cycle(listOf("carousel", "image", "video", "aerial"), st.bgMode, it) }),
                Act("Picture from a link", st.bgImage.ifBlank { "https://… (jpg, png, webp)" }, "") {
                    dialog = TextEntry("Picture link", st.bgImage.takeIf { it.startsWith("http") }.orEmpty(), "https://…") { v -> if (v.isNotBlank()) { st.bgImage = v.trim(); st.bgMode = "image" } }
                },
                Act("Picture from the folder", "", "") { pick(false) },
                Act("Video from a link", st.bgVideo.ifBlank { "https://… (mp4, webm, mkv); plays muted, on a loop" }, "") {
                    dialog = TextEntry("Video link", st.bgVideo.takeIf { it.startsWith("http") }.orEmpty(), "https://…") { v -> if (v.isNotBlank()) { st.bgVideo = v.trim(); st.bgMode = "video" } }
                },
                Act("Video from the folder", "", "") { pick(true) },
                Note("Folder for your pictures and videos: $bgFolder (copy with adb push, a file manager or a USB stick). The glass shows the picture; with a video it shows one frame of it."),
                Toggle("Show clock", "", { st.showClock }, { st.showClock = it }),
                Toggle("24-hour clock", "", { st.clock24h }, { st.clock24h = it }),
                Toggle("Show date", "Under the clock, like Apple TV", { st.showDate }, { st.showDate = it }),
                Adjust("Clock position", "", { if (st.clockPos == "right") "Right, by Settings" else "Left, like Apple TV" },
                    { st.clockPos = cycle(listOf("left", "right"), st.clockPos, it) }),
                Adjust("Weather", "Top bar, next to the settings button", {
                    when (st.weatherMode) { "off" -> "Off"; "temp" -> "Temperature"; "condition" -> "Temperature + sky"; "city" -> "Temperature + city"; else -> "Everything" }
                }, { st.weatherMode = cycle(listOf("all", "condition", "city", "temp", "off"), st.weatherMode, it); app.weather.reload() }),
                Act("Weather city", st.weatherCity + app.weather.found.value.takeIf { it.isNotBlank() }?.let { "  →  $it" }.orEmpty(), "Change") {
                    dialog = TextEntry("Weather city", st.weatherCity, "Baghdad") { st.weatherCity = it; app.weather.reload() }
                },
                Toggle("Fahrenheit", "", { st.weatherF }, { st.weatherF = it }),
                Toggle("Auto-hide clock and settings button", "Hidden while you browse; they appear when you go up to the settings button", { st.topAutoHide }, { st.topAutoHide = it }),
                Adjust("Shown at start for", "Then hidden until you go up to them", { "${st.topHideSec} s" }, { st.topHideSec += it }),
                Adjust("Corner radius", "Dock and app tiles (0 = square)", { "${st.cornerRadius}" }, { st.cornerRadius += it * 2 }),
                Adjust("Glass blur", "0 turns blur off", { "${st.blurRadius}" }, { st.blurRadius += it * 4 }),
                Adjust("Glass refraction", "How much the rim bends what is behind it (0 = flat)", { "${st.refraction}" }, { st.refraction += it * 2 }),
                Adjust("Glass brightness", "", { "${st.glassTint}" }, { st.glassTint += it * 2 }),
                Toggle("Colour on focus", "Like tvOS: icons stay white, the selected one shows its colours on a light plate", { st.focusColor }, { st.focusColor = it }),
                Adjust("Selected icon plate", "With Colour on focus", { PLATE_NAMES[st.focusPlate] ?: "Dark" },
                    { st.focusPlate = cycle(PLATES, st.focusPlate, it) }),
                Toggle("Bounce at the edges", "Pressing past the first or last icon nudges it, like tvOS", { st.edgeBounce }, { st.edgeBounce = it }),
                Toggle("Lift selected icon", "The selected icon rises a little, glass and all", { st.tileLift }, { st.tileLift = it }),
                Toggle("Show phone apps too", "Apps without a TV launcher entry", { st.showMobileApps }, { st.showMobileApps = it; app.apps.reload() }),
            )
        },
        Category("Dock & icons") {
            val imported = st.dockOrder.size // read for recomposition on reorder
            @Suppress("UNUSED_VARIABLE") val unused = imported + allApps.size
            buildList {
                add(Adjust("Icon pack", "Projectivy / Blueprint / Nova packs installed on this TV",
                    { if (st.iconPack.isEmpty()) "None (original icons)" else (packLabels[st.iconPack] ?: st.iconPack) },
                    { st.iconPack = cycle(packList, st.iconPack, it) }))
                add(Toggle("Show Apps folder", "The folder with all your apps, in the dock. Hidden apps stay listed below", { st.showFolder }, { st.showFolder = it }))
                add(Adjust("Dock size", "Smaller makes the dock and its icons narrower", { pct(st.dockScale) }, { st.dockScale = ((st.dockScale * 20).roundToInt() + it) / 20f }))
                add(Adjust("Dock background", "Behind the apps row and the Apps folder", { styleName(st.dockStyle) },
                    { st.dockStyle = cycle(listOf("liquid", "water", "water_still", "clear", "amoled", "bow"), st.dockStyle, it); st.theme = "" }))
                add(Adjust("Settings background", "Behind settings and the icon picker", { styleName(st.panelStyle) },
                    { st.panelStyle = cycle(listOf("liquid", "clear", "amoled"), st.panelStyle, it) }))
                add(Adjust("Icon style", "How the plate behind every icon looks", {
                    styleName(st.tileStyle)
                }, { st.tileStyle = cycle(listOf("liquid", "water", "water_still", "clear", "amoled", "bow"), st.tileStyle, it); st.theme = "" }))
                add(Note("Change one icon: on the home screen hold OK on the app → Change icon. Pick from an icon pack, the Apple TV icons, a photo on the TV, a link to a PNG, or send one from your phone (Manage from phone)."))
                add(Note("Icon packs: install any Android TV / Projectivy icon pack from Google Play, then choose it above. Every app takes the pack's icon; you can still change single apps."))
                add(Act("Find icon packs on Google Play", "Projectivy / Android TV icon packs", "") { openPlay(ctx, null, "projectivy icon pack") })
                add(Note("Many icons at once: copy <package>.png files (e.g. com.netflix.ninja.png) into the icon folder below, then Import."))
                add(Note("On the home screen, hold OK on an app for App info, Move, Change icon and Hide."))
                add(Note("Here: OK picks an app up, ◀ ▶ moves it, OK puts it down. Hold OK (or Menu) for its options."))
                add(Act("Import icons from the icon folder", "", "") {
                    val n = app.icons.importDropFolder(app.apps.apps.value.map { it.pkg })
                    toast(ctx, if (n > 0) "Imported $n icons" else "No new icons. Folder: ${app.icons.dropFolder.absolutePath}")
                })
                add(Note("Folder for <package>.png files: ${app.icons.dropFolder.absolutePath}"))
                app.apps.ordered(includeHidden = true).forEach { add(AppItem(it)) }
            }
        },
        Category("Menu, Library & themes") {
            val themes = listOf("bow", "amoled", "liquid", "clear", "water_still", "water")
            listOf(
                Adjust("Theme", "One look for the dock, the icons and the menu. Black ones keep the dock clear (matte)",
                    { if (st.theme.isBlank()) "Custom" else themeName(st.theme) }, { st.applyTheme(cycle(themes, st.theme.ifBlank { "liquid" }, it)) }),
                Adjust("Menu", "The Apple TV-style menu at the settings button",
                    { when (st.menuMode) { "auto" -> "Opens when you go up to it"; "manual" -> "Opens with OK"; else -> "Off (OK opens Settings)" } },
                    { st.menuMode = cycle(listOf("auto", "manual", "off"), st.menuMode, it) }),
                Adjust("Menu look", "", { styleName(st.menuStyle) },
                    { st.menuStyle = cycle(listOf("liquid", "water", "water_still", "clear", "amoled", "bow"), st.menuStyle, it); st.theme = "" }),
                Toggle("Clock in the menu", "", { st.menuClock }, { st.menuClock = it }),
                Toggle("Date in the menu", "", { st.menuDate }, { st.menuDate = it }),
                Toggle("Weather in the menu", "Temperature, conditions, high / low, humidity and wind (city in Clock & glass)", { st.menuWeather }, { st.menuWeather = it; app.weather.reload() }),
                Toggle("City in the menu", "", { st.menuCity }, { st.menuCity = it }),
                Toggle("Live TV and Following in the menu", "Your IPTV channels with a full guide, and the teams and shows you follow", { st.menuLive }, { st.menuLive = it }),
                Toggle("Library in the menu", "", { st.menuLibrary }, { st.menuLibrary = it }),
                Toggle("Android settings in the menu", "", { st.menuAndroid }, { st.menuAndroid = it }),
                Toggle("Wi-Fi in the menu", "", { st.menuWifi }, { st.menuWifi = it }),
                Toggle("Themes in the menu", "", { st.menuThemes }, { st.menuThemes = it }),
                Toggle("Background in the menu", "Switch between title backdrops, your picture, your video and the screensaver", { st.menuBackground }, { st.menuBackground = it }),
                Toggle("+ button on the carousel", "Between Play and Next: saves the title to your Library", { st.heroPlus }, { st.heroPlus = it }),
                Toggle("Trakt watchlist in the Library", "Needs Trakt (Continue Watching)", { st.libraryTrakt }, { st.libraryTrakt = it; scope.launch(Dispatchers.IO) { app.library.refreshWatchlist() } }),
                Toggle("Nuvio library", if (app.library.nuvio.connected) "From your Nuvio account" + (app.library.nuvio.email.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") + ": Nuvio Legacy, the official Nuvio app and forks that sync with it"
                    else "Connect your Nuvio account below", { st.libraryNuvio }, { st.libraryNuvio = it; scope.launch(Dispatchers.IO) { app.library.refreshNuvio() } }),
                nuvioAccountRow(),
                Toggle("Save to the Nuvio library too", "The + button also saves the title to your Nuvio account (Legacy, official Nuvio and forks see it)", { st.libraryToNuvio }, { st.libraryToNuvio = it }),
                Toggle("Stremio library", if (app.library.stremio.connected) "From your Stremio account" + (app.library.stremio.email.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") + ": the library the Stremio apps show"
                    else "Connect your Stremio account below", { st.libraryStremio }, { st.libraryStremio = it; scope.launch(Dispatchers.IO) { app.library.refreshStremio() } }),
                stremioAccountRow(),
                Toggle("Save to the Stremio library too", "The + button also saves the title to your Stremio account", { st.libraryToStremio }, { st.libraryToStremio = it }),
                Toggle("Save to the Trakt watchlist too", "The + button also adds the title on Trakt", { st.libraryToTrakt }, { st.libraryToTrakt = it }),
                Toggle("Titles under posters", "In the Library", { st.libraryTitles }, { st.libraryTitles = it }),
                Note("In the Library: OK opens the title like the carousel. Hold OK (or Menu) to remove it."),
            )
        },
        Category("Catalogs") {
            buildList {
                add(Act("Add add-ons from my phone (QR)", "Scan the code, paste add-on manifest links (Nuvio or Stremio) and your TMDB token", "") { phoneDialog = true })
                add(tmdbRow())
                add(Act("Connect to Nuvio", "Open Nuvio > Add-ons > Manage from phone first", "") { io { app.catalogs.connectLocal() } })
                add(Toggle("Add-ons from my Nuvio account", if (app.library.nuvio.connected) "The add-ons on your Nuvio account (${app.library.nuvio.email.ifBlank { "signed in" }}) too. One that's already here isn't added twice"
                    else "Connect your Nuvio account first (the row below)", { st.catalogsNuvio }, { on -> io { app.catalogs.useAccount(on) } }))
                add(nuvioAccountRow())
                add(Toggle("Add-ons from my Stremio account", if (app.library.stremio.connected) "The add-ons on your Stremio account (${app.library.stremio.email.ifBlank { "signed in" }}) too. One that's already here isn't added twice"
                    else "Connect your Stremio account first (the row below)", { st.catalogsStremio }, { on -> io { app.catalogs.useStremio(on) } }))
                add(stremioAccountRow())
                add(Act("Add add-on link", "Paste a manifest link", "") {
                    dialog = TextEntry("Add-on link", "", "https://…/manifest.json or stremio://…") { u -> io { app.catalogs.addAddon(u) } }
                })
                add(Act("Refresh catalogs", status, "") { io { app.catalogs.refresh(); app.catalogs.status.value } })
                add(Act("Select all catalogs", "", "") { app.catalogs.selectAll(true); io { app.catalogs.refresh(); "Done" } })
                add(Act("Select none", "", "") { app.catalogs.selectAll(false); io { app.catalogs.refresh(); "Done" } })
                addons.sorted().forEach { u ->
                    add(Act("Add-on · ${app.catalogs.addonName(u)}", hostOf(u), "Remove") {
                        dialog = MenuDialog("Remove ${app.catalogs.addonName(u)}?", listOf("Remove add-on" to { io { app.catalogs.removeAddon(u) } }))
                    })
                }
                (accountAddons.size + stremioAddons.size).let { _ -> app.catalogs.accountOnly() }.sorted().forEach { u ->
                    val from = app.catalogs.accountOf(u)
                    add(Act("Add-on · ${app.catalogs.addonName(u)}", "From your $from account · " + hostOf(u), "") { toast(ctx, "Add or remove it in $from; it updates here on the next refresh") })
                }
                if (catalogs.isEmpty()) add(Note("No catalogs yet. Connect to Nuvio or Stremio, or add an add-on link."))
                catalogs.forEach { c ->
                    add(Toggle(c.name, "${c.addonName} · ${c.type}", { c.key in selected }, {
                        app.catalogs.toggleCatalog(c.key)
                    }))
                }
            }
        },
        Category("Continue Watching") {
            val cwList by app.cw.entries.collectAsState()
            buildList {
                add(Adjust("Show Continue Watching", "Where titles you are watching appear", {
                    when (st.continueMode) { "off" -> "Off"; "mixed" -> "Mixed with catalogs"; "first" -> "At the start of the carousel"; else -> "Own row" }
                }, { st.continueMode = cycle(listOf("row", "first", "mixed", "off"), st.continueMode, it) }))
                if (st.continueMode == "first") add(Adjust("Slides at the start", "How many titles in progress open the carousel", {
                    if (st.cwLead <= 0) "All" else "${st.cwLead}"
                }, { st.cwLead = cycle(listOf(1, 2, 3, 4, 5, 0), st.cwLead, it) }))
                if (st.continueMode == "row") add(Note("Own row: a Continue Watching row above the dock, like the Apple TV app. Down from the carousel goes into it; hold OK on a title to take it out."))
                if (st.continueMode != "row") add(Toggle("Open on what I just watched", "Back from Nuvio, the carousel shows that title first", { st.continueJump }, { st.continueJump = it }))
                if (st.continueMode != "row") add(Toggle("Progress bar", "Shows Continue · S1 · E3 · 42% under the title", { st.cwProgress }, { st.cwProgress = it }))
                add(Note("Apps"))
                add(Toggle("Nuvio Legacy", if (app.cw.hasLogAccess()) "Reading what you watch in Nuvio Legacy" else "Allow USB debugging for Glassio when the TV asks", { st.cwLegacy }, { st.cwLegacy = it }))
                val nuvioAcct = app.library.nuvio
                val nStatus by app.cw.nuvioStatus.collectAsState()
                if (nuvioAcct.available) add(Toggle("Nuvio (official app)",
                    if (nuvioAcct.connected) "From your Nuvio account" + (nuvioAcct.email.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") +
                        ": what you're watching in the official Nuvio app" + (if (nStatus.isNotBlank()) " · $nStatus" else "")
                    else "Sign in to your Nuvio account (Settings → Nuvio, Stremio & Home Assistant) to see what you're watching in the official Nuvio app",
                    { st.cwNuvio }, { st.cwNuvio = it; app.cw.syncNuvioNow() }))
                val stremioAcct = app.library.stremio
                val sStatus by app.cw.stremioStatus.collectAsState()
                add(Toggle("Stremio",
                    if (stremioAcct.connected) "From your Stremio account" + (stremioAcct.email.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") +
                        ": what you're watching in Stremio" + (if (sStatus.isNotBlank()) " · $sStatus" else "")
                    else "Sign in to your Stremio account (Settings → Nuvio, Stremio & Home Assistant) to see what you're watching in Stremio",
                    { st.cwStremio }, { st.cwStremio = it; app.cw.syncStremioNow() }))
                val trakt = app.cw.trakt
                val tStatus by app.cw.traktStatus.collectAsState()
                if (trakt.available) {
                    add(Toggle("Trakt", if (trakt.connected) "Signed in as ${trakt.user.ifBlank { "your account" }}. What you pause in any app that uses Trakt (official Nuvio, Nuvio Arc, Re-Shaped, Stremio…)" + (if (tStatus.isNotBlank()) " · $tStatus" else "")
                        else "Connect your account below to see what you paused in any app that uses Trakt", { st.cwTrakt }, { st.cwTrakt = it; app.cw.syncTraktNow() }))
                    if (!trakt.connected) add(Act("Connect Trakt account", traktCode?.let { "Enter $it at ${app.cw.traktUrl.removePrefix("https://")}" } ?: "Scan a QR code with your phone and enter a short code, no password on the TV", if (traktCode != null) "Waiting…" else "") {
                        val appCtx = ctx.applicationContext
                        app.cw.connectTrakt { msg -> toast(appCtx, msg) }
                        scope.launch {
                            // Wait for the code, then show it with a QR code of the sign-in page.
                            var n = 0
                            while (app.cw.traktCode.value == null && n++ < 50) kotlinx.coroutines.delay(100)
                            if (app.cw.traktCode.value != null) dialog = QrCodeDialog("Connect Trakt", app.cw.traktUrl, app.cw.traktCode,
                                "This closes by itself once you approve. The code works for about 10 minutes.")
                        }
                    })
                    else add(Act("Sign out of Trakt", trakt.user, "") { trakt.signOut(); st.cwTrakt = st.cwTrakt; toast(ctx, "Signed out of Trakt") })
                }
                add(Note("How it works: Nuvio Legacy writes what you play to the TV's log (title, episode, how far). Glassio reads that log through the TV's own debugging connection (ADB), so this needs ADB once: Settings → System → TV access (ADB). Nothing leaves the TV."))
                add(Note("The official Nuvio app saves what you watch to your Nuvio account, and Stremio to your Stremio account: signed in to the same account here, it shows up in Continue Watching (taking a title out here takes it out there too). Nuvio Arc and Re-Shaped: connect Trakt (and turn on Trakt in that app) and what you pause there shows up here through Trakt."))
                add(Note("Other apps (YouTube, Netflix, Apple TV…) keep their Continue Watching private on Google TV; only the system launcher may read it."))
                add(Act("Clear the list", "${cwList.size} titles", "") { app.cw.clear() })
                cwList.forEach { e ->
                    add(Act(e.imdb + if (e.season > 0) "  S${e.season}E${e.episode}" else "", "${e.app} · ${e.percent}%", "Remove") { app.cw.remove(e.key) })
                }
            }
        },
        Category("Nuvio, Stremio & Home Assistant") {
            listOf(
                nuvioAccountRow(),
                stremioAccountRow(),
                Adjust("Open titles in", "Auto uses the first installed: Nuvio Legacy, Nuvio (official), Nuvio Arc, Nuvio Re-Shaped, Stremio", {
                    val n = NuvioLaunch.NAMES[st.nuvioTarget] ?: "Auto"
                    val r = NuvioLaunch.resolve(ctx, st)
                    when {
                        st.nuvioTarget == "auto" -> "Auto · " + (if (NuvioLaunch.TARGETS.values.any { NuvioLaunch.installed(ctx, it) }) NuvioLaunch.NAMES[r] else "none installed")
                        !NuvioLaunch.installed(ctx, NuvioLaunch.TARGETS.getValue(r)) -> "$n (not installed)"
                        else -> n
                    }
                }, {
                    st.nuvioTarget = cycle(listOf("auto") + NuvioLaunch.TARGETS.keys, st.nuvioTarget, it)
                }),
                Act("Home Assistant webhook", "Full webhook URL from your Home Assistant", if (st.haWebhook.isBlank()) "Not set" else "Set",
                    ok = if (st.haWebhook.startsWith("http")) true else null) {
                    dialog = TextEntry("Home Assistant webhook", st.haWebhook, "http://homeassistant.local:8123/api/webhook/…") { st.haWebhook = it }
                },
                Toggle("Tell Home Assistant when a title opens", "Sends title, type and id as JSON", { st.haOnLaunch }, { st.haOnLaunch = it }),
                Act("Send a test event", "") { io { NuvioLaunch.testWebhook(st, app.http) } },
                tmdbRow(),
            )
        },
        Category("Screensaver") {
            buildList {
                val system = runCatching { AndroidSettings.Secure.getString(ctx.contentResolver, "screensaver_components") }.getOrNull().orEmpty()
                if (system.isNotBlank() && !system.contains(ctx.packageName)) {
                    add(Note("Your system screensaver is already ${system.substringBefore('/')}. Leave \"Start after\" on Off and it will keep working on this launcher."))
                }
                val aerialViews = "com.neilturner.aerialviews"
                val avInstalled = remember { runCatching { ctx.packageManager.getPackageInfo(aerialViews, 0) }.isSuccess }
                val avComponent = "$aerialViews/.ui.screensaver.DreamActivity"
                add(Note("Recommended: Aerial Views. The best screensaver with Glassio: Apple, Amazon and your own 4K videos, clock and info, free on Google Play."))
                if (!avInstalled) add(Act("Install Aerial Views", "Opens it on Google Play", "Google Play") { openPlay(ctx, aerialViews, null) })
                else {
                    add(Act("Open Aerial Views settings", "Choose videos, clock, overlays", "") {
                        ctx.packageManager.getLaunchIntentForPackage(aerialViews)?.let { runCatching { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                    })
                }
                // The TV's own screensaver, switched through the TV's debugging connection (ADB): on / off and when it starts.
                tvRev.intValue
                val cr = ctx.contentResolver
                val tvOn = runCatching { AndroidSettings.Secure.getInt(cr, "screensaver_enabled", 1) == 1 }.getOrDefault(true) &&
                    runCatching { AndroidSettings.Secure.getString(cr, "screensaver_components") }.getOrNull().orEmpty().startsWith(aerialViews)
                val tvAfter = runCatching { AndroidSettings.System.getInt(cr, "screen_off_timeout") }.getOrDefault(0)
                fun tv(cmd: String, done: String) = io {
                    val r = com.glasslauncher.tv.data.LocalShell.run(ctx, cmd)
                    tvRev.intValue++
                    if (r.isSuccess) done else "Allow USB debugging for Glassio when the TV asks (Always allow), then try again"
                }
                if (avInstalled) add(Toggle("Aerial Views as the TV screensaver (ADB)", if (tvOn) "On: the TV starts Aerial Views by itself when idle" else "Off: the TV doesn't start a screensaver",
                    { tvOn }, { on ->
                        if (on) tv("settings put secure screensaver_components $avComponent && settings put secure screensaver_enabled 1 && settings put secure screensaver_activate_on_sleep 1", "Aerial Views screensaver on").also { st.saverMode = "system" }
                        // Off: with no screensaver the TV goes to sleep at that idle time, so the screen then stays on
                        // until the TV's own sleep time (Energy settings, sleep_timeout) instead.
                        else tv("settings put secure screensaver_enabled 0 && s=\$(settings get secure sleep_timeout) && case \$s in ''|null|-1|0) s=1800000;; esac && settings put system screen_off_timeout \$s",
                            "TV screensaver off (the TV now sleeps at its own sleep time)")
                    }))
                add(Adjust("TV screensaver starts after (ADB)", "The TV's own idle time (Android: screen_off_timeout)", {
                    when { tvAfter <= 0 -> "Unknown"; tvAfter < 60_000 -> "${tvAfter / 1000} s"; tvAfter >= 3_600_000 -> "${tvAfter / 3_600_000} h"; else -> "${tvAfter / 60_000} min" + (if (tvAfter % 60_000 != 0) " ${tvAfter % 60_000 / 1000} s" else "") }
                }, { d ->
                    val opts = listOf(20_000, 30_000, 60_000, 120_000)
                    val next = cycle(opts, opts.minByOrNull { kotlin.math.abs(it - tvAfter) } ?: 60_000, if (tvAfter in opts) d else 0)
                    tv("settings put system screen_off_timeout $next && settings put secure screensaver_activate_on_sleep 1", "The TV screensaver now starts after " + (if (next < 60_000) "${next / 1000} s" else "${next / 60_000} min"))
                }))
                add(Note("Steps: 1. Install Aerial Views. 2. Turn on Aerial Views as the TV screensaver (the TV asks once to allow USB debugging for Glassio: Always allow). 3. Type: TV screensaver. 4. Start after / Back starts it."))
                add(Adjust("Start after", "Idle time on the launcher", { st.idleSeconds.let { v -> if (v == 0) "Off" else if (v < 60) "$v s" else "${v / 60} min" } }, {
                    st.idleSeconds = cycle(listOf(0, 10, 30, 60, 120, 180, 300, 600, 900, 1800), st.idleSeconds, it)
                }))
                add(Adjust("Type", "What plays when the screensaver starts", {
                    when (st.saverMode) {
                        "aerial" -> "Apple aerial videos"
                        "system" -> "TV screensaver (" + (system.substringBefore('/').substringAfterLast('.').ifBlank { "system" }) + ")"
                        else -> "Posters with logos"
                    }
                }, { st.saverMode = cycle(listOf("aerial", "slideshow", "system"), st.saverMode, it) }))
                if (st.saverMode == "system") add(Note("Starts the screensaver chosen in Google TV settings (Aerial Views). The first time, the TV asks to allow USB debugging for Glassio: choose Always allow."))
                add(Toggle("Apple aerial videos", "Streams Apple's public aerial feed", { st.useAppleAerials }, { st.useAppleAerials = it }))
                add(Toggle("Show clock", "", { st.saverClock }, { st.saverClock = it }))
                add(Toggle("Followed matches", "Your team's next match from Following under the clock, with LIVE and the score", { st.saverMatch }, { st.saverMatch = it }))
                add(Toggle("Back on the home screen starts it", "Like Apple TV: press Back on the home screen", { st.backSaver }, { st.backSaver = it }))
                add(Act("Add video link", "A direct .mp4 / .mov link", "") {
                    dialog = TextEntry("Video link", "", "https://…/video.mp4") { u ->
                        if (u.startsWith("http")) st.aerialUrls = (st.aerialUrls + u.trim()).distinct() else toast(ctx, "The link must start with http")
                    }
                })
                st.aerialUrls.forEach { u ->
                    add(Act("Video link", hostOf(u), "Remove") { st.aerialUrls = st.aerialUrls - u })
                }
                add(Act("Preview now", "") { ctx.startActivity(Intent(ctx, ScreensaverActivity::class.java)) })
                add(Act("Use as system screensaver", "Pick Glassio Yahsun in the list", "") { openSystem(ctx, AndroidSettings.ACTION_DREAM_SETTINGS) })
            }
        },
        Category("Manage from phone", Kind.Phone),
        Category("System") {
            val devOn = runCatching { AndroidSettings.Global.getInt(ctx.contentResolver, AndroidSettings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1 }.getOrDefault(false)
            val adbOn = runCatching { AndroidSettings.Global.getInt(ctx.contentResolver, AndroidSettings.Global.ADB_ENABLED, 0) == 1 }.getOrDefault(false)
            listOf(
                // A backup: everything in one file locked with a code (Backup), in the TV's Downloads and via the phone page.
                Act("Back up settings", Backup.reminder(ctx), if (Backup.lastMade(ctx) <= 0L || System.currentTimeMillis() - Backup.lastMade(ctx) >= Backup.REMIND_AFTER_DAYS * 86_400_000L) "Due" else "") {
                    dialog = TextEntry("Backup code", "", "At least ${Backup.MIN_CODE} characters. You need it to restore: keep it somewhere safe", secret = true) { code ->
                        if (code.length < Backup.MIN_CODE) { toast(ctx, "The code needs at least ${Backup.MIN_CODE} characters"); return@TextEntry }
                        // (after this dialog has closed: it clears the dialog as it goes)
                        scope.launch {
                            kotlinx.coroutines.delay(60)
                            dialog = TextEntry("Type the code again", "", "The same code, to be sure", secret = true) { again ->
                                if (again != code) { toast(ctx, "The codes don't match: nothing was saved"); return@TextEntry }
                                toast(ctx, "Making the backup…")
                                scope.launch(Dispatchers.IO) {
                                    val r = runCatching { Backup.saveToDownloads(ctx, Backup.make(ctx, code)) }
                                    toast(ctx, r.fold({ "Backup saved: Downloads › ${Backup.NAME}" }, { "Backup failed: ${it.message}" }))
                                }
                            }
                        }
                    }
                },
                Act("Restore a backup", "From the phone page or Downloads (after a reinstall, Downloads needs TV access). Glassio restarts after", "") {
                    scope.launch(Dispatchers.IO) {
                        val file = runCatching { Backup.findOnTv(ctx) }.getOrNull()
                        if (file == null) { toast(ctx, "No backup on this TV: send one from Manage from phone, or allow TV access (below) to read Downloads"); return@launch }
                        val made = java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(Backup.madeAt(file)))
                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            dialog = TextEntry("Restore the backup from $made", "", "Its code. Everything here now is replaced, then Glassio restarts", secret = true) { code ->
                                toast(ctx, "Restoring…")
                                scope.launch(Dispatchers.IO) {
                                    runCatching { Backup.restore(ctx, file, code) }.fold({
                                        toast(ctx, "Restored ${it.settings} settings and ${it.files} files. Glassio restarts")
                                        kotlinx.coroutines.delay(1500)
                                        kotlinx.coroutines.withContext(Dispatchers.Main) { Backup.restart(ctx) }
                                    }, { toast(ctx, "Couldn't restore: ${it.message}") })
                                }
                            }
                        }
                    }
                },
                Toggle("Open Glassio when the TV starts", "After a restart, Glassio Yahsun instead of Google TV's home screen. Needs the accessibility service or TV access (ADB), below" +
                    (if (BootReceiver.TCL) ". TCL TVs: TV access lets Glassio start by itself" else ""), { st.bootGlassio }, {
                    st.bootGlassio = it
                    if (it && BootReceiver.TCL) scope.launch(Dispatchers.IO) {
                        toast(ctx, if (BootReceiver.allowAutoStart(ctx)) "Glassio may start by itself on this TCL TV" else "Allow TV access (ADB) first: steps below")
                    }
                }),
                Toggle("Home button opens Glassio", "Needs the accessibility service below", { st.interceptHome }, { st.interceptHome = it }),
                Act("Turn on the accessibility service", "Find Glassio Yahsun and switch it on", "") { openSystem(ctx, AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS) },
                Act("Turn it on with ADB", if (homeKeyOn) "The accessibility service is on" else "When the TV doesn't let you (restricted setting / greyed out). Needs TV access (ADB), below", "") {
                    scope.launch(Dispatchers.IO) {
                        val svc = "${ctx.packageName}/com.glasslauncher.tv.HomeKeyService"
                        val cmd = "cur=\$(settings get secure enabled_accessibility_services); " +
                            "case \"\$cur\" in *$svc*) ;; null|'') settings put secure enabled_accessibility_services $svc ;; " +
                            "*) settings put secure enabled_accessibility_services \"\$cur:$svc\" ;; esac; " +
                            "settings put secure accessibility_enabled 1; appops set ${ctx.packageName} ACCESS_RESTRICTED_SETTINGS allow 2>/dev/null; " +
                            // Lets Glassio switch the service back on by itself after every update.
                            "pm grant ${ctx.packageName} android.permission.WRITE_SECURE_SETTINGS 2>/dev/null; true"
                        val r = com.glasslauncher.tv.data.LocalShell.run(ctx, cmd)
                        homeKeyOn = isHomeKeyOn(ctx)
                        toast(ctx, if (r.isSuccess && homeKeyOn) "Accessibility service turned on" else if (r.isSuccess) "Done, but the TV didn't turn it on. Try the button above" else "Allow TV access (ADB) first: steps below")
                    }
                },
                Act("Choose default home app", "", "") { openSystem(ctx, AndroidSettings.ACTION_HOME_SETTINGS) },
                Note("TV access (ADB): Continue Watching from Nuvio Legacy, setting Aerial Views as the screensaver, starting the screensaver and silent updates use the TV's own debugging connection. Turn it on once:"),
                Note("1. Developer options: open About below and press OK on \"Android TV OS build\" (or \"Build\") 7 times, until it says you are a developer."),
                Note("2. Open Developer options below and turn on USB debugging (on some TVs also \"Network debugging\" / \"ADB over network\")."),
                Note("3. Press \"Allow Glassio\" below. The TV asks \"Allow USB debugging?\": tick Always allow from this computer, then OK. It is asked only once."),
                Act("1 · Open About (enable Developer options)", if (devOn) "Developer options are on" else "Press OK on the build number 7 times", "") { openSystem(ctx, AndroidSettings.ACTION_DEVICE_INFO_SETTINGS) },
                Act("2 · Open Developer options", if (adbOn) "USB debugging is on" else "Turn on USB debugging", "") { openSystem(ctx, AndroidSettings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) },
                Act("3 · Allow Glassio", when (adbAccess) { true -> "Allowed: Glassio can use ADB"; false -> "Not allowed yet: press OK, then Always allow on the TV"; else -> "Press OK to check" }, "") {
                    scope.launch(Dispatchers.IO) {
                        val ok = com.glasslauncher.tv.data.LocalShell.run(ctx, "echo ok").isSuccess
                        adbAccess = ok
                        toast(ctx, if (ok) "Glassio can use ADB" else if (!adbOn) "Turn on USB debugging first (step 2)" else "Choose Always allow on the TV, then press OK here again")
                    }
                },
            )
        },
        Category("About", Kind.About),
    )

    // Catalog choices made here are applied when the screen closes.
    DisposableEffect(Unit) { onDispose { app.scope.launch(Dispatchers.IO) { runCatching { app.catalogs.refresh() } } } }
    BackHandler { if (rightFocused) runCatching { catFocus[cat].requestFocus() } else onClose() }
    LaunchedEffect(Unit) { delay150(); runCatching { firstCat.requestFocus() } }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).padding((28 * s).dp)) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy((16 * s).dp)) {
            GlassPanel(Modifier.width((230 * s).dp).fillMaxHeight(), (30 * s).dp, style = st.panelStyle, darken = 0.42f) {
                // Scrolls when the list is taller than the screen (the last entry, About, was cut off).
                Column(Modifier.padding(horizontal = (14 * s).dp, vertical = (12 * s).dp).verticalScroll(rememberScrollState())) {
                    Text("Settings", color = Color.White, fontSize = (22 * s).sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(start = (8 * s).dp, bottom = (8 * s).dp))
                    categories.forEachIndexed { i, c ->
                        var f by remember { mutableStateOf(false) }
                        Text(
                            c.title, color = Color.White, fontSize = (13.5f * s).sp,
                            fontWeight = if (cat == i) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.fillMaxWidth().padding(vertical = (1.5f * s).dp)
                                .clip(RoundedCornerShape((14 * s).dp))
                                .background(if (f) Color.White.copy(alpha = 0.30f) else if (cat == i) Color.White.copy(alpha = 0.12f) else Color.Transparent)
                                .focusRequester(catFocus[i])
                                .then(if (i == initialCat) Modifier.focusRequester(firstCat) else Modifier)
                                .onFocusChanged { f = it.isFocused; if (it.isFocused) cat = i }
                                .clickable { cat = i }
                                .padding(horizontal = (12 * s).dp, vertical = (7.5f * s).dp),
                        )
                    }
                }
            }
            GlassPanel(
                Modifier.weight(1f).fillMaxHeight().onFocusChanged { rightFocused = it.hasFocus }
                    // Left goes back to this page's own category; Up/Down at the ends stay in the page.
                    .focusProperties {
                        exit = { dir ->
                            when (dir) {
                                androidx.compose.ui.focus.FocusDirection.Left -> catFocus[cat]
                                androidx.compose.ui.focus.FocusDirection.Up, androidx.compose.ui.focus.FocusDirection.Down -> FocusRequester.Cancel
                                else -> FocusRequester.Default
                            }
                        }
                    }
                    .focusGroup(),
                (30 * s).dp, style = st.panelStyle, darken = 0.42f,
            ) {
                val c = categories[shownCat]
                Column(Modifier.padding((18 * s).dp)) {
                    Text(c.title, color = Color.White, fontSize = (20 * s).sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(start = (6 * s).dp, bottom = (8 * s).dp))
                    if (c.kind == Kind.Phone) PhonePane(app, s)
                    else if (c.kind == Kind.About) AboutPane(app, s, onApp = { e -> appMenu(app, e, ctx, scope) { dialog = it } })
                    else {
                        val rows = c.rows()
                        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy((4 * s).dp)) {
                            items(rows.size) { i -> RowView(rows[i], s, app, onApp = { e -> appMenu(app, e, ctx, scope) { dialog = it } }) }
                        }
                    }
                }
            }
        }
    }
    DialogHost(dialog) { dialog = null }
    if (phoneDialog) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { phoneDialog = false }) {
            Box(
                Modifier.width((520 * s).dp).height((380 * s).dp).clip(RoundedCornerShape((18 * s).dp))
                    .background(Color(0xF0181820)).padding((18 * s).dp)
                    .focusable(),
            ) { PhonePane(app, s) }
        }
    }
}

private suspend fun delay150() = kotlinx.coroutines.delay(150)

private fun appMenu(app: GlassApp, e: AppEntry, ctx: Context, scope: kotlinx.coroutines.CoroutineScope, show: (DialogState) -> Unit) {
    val hidden = e.pkg in app.settings.hiddenApps
    val opts = mutableListOf<Pair<String, () -> Unit>>()
    opts += (if (hidden) "Show in dock" else "Hide from dock") to { app.apps.toggleHidden(e.pkg) }
    opts += "Icon from a link" to {
        show(TextEntry("Icon link for ${e.label}", "", "https://…/icon.png") { u ->
            scope.launch(Dispatchers.IO) { toast(ctx, runCatching { app.icons.setFromUrl(e.pkg, u.trim()); "Icon updated" }.getOrElse { it.message ?: "Failed" }) }
        })
    }
    opts += "Icon from my phone (Manage from phone)" to { toast(ctx, "Open Settings > Manage from phone, scan the QR code, then pick ${e.label}") }
    if (app.icons.has(e.pkg)) opts += "Reset to the original icon" to { app.icons.reset(e.pkg) }
    opts += "Open app" to { app.apps.launchIntent(e)?.let { runCatching { ctx.startActivity(it) } } }
    show(MenuDialog(e.label, opts))
}

@Composable
private fun RowView(row: SRow, s: Float, app: GlassApp, onApp: (AppEntry) -> Unit) {
    when (row) {
        is Note -> Text(row.text, color = Color.White.copy(alpha = 0.55f), fontSize = (11.5f * s).sp, modifier = Modifier.padding(horizontal = (8 * s).dp, vertical = (6 * s).dp))
        is Toggle -> Frame(s, onClick = { row.set(!row.get()) }) { f ->
            Label(row.title, row.desc, s, Modifier.weight(1f))
            val on = row.get()
            Box(Modifier.width((38 * s).dp).height((22 * s).dp).clip(RoundedCornerShape(50)).background(if (on) Color(0xFF7B61FF) else Color.White.copy(alpha = 0.22f)).padding((3 * s).dp)) {
                Box(Modifier.size((16 * s).dp).clip(RoundedCornerShape(50)).background(Color.White).align(if (on) Alignment.CenterEnd else Alignment.CenterStart))
            }
        }
        is Adjust -> {
            var editing by remember { mutableStateOf(false) }
            Frame(s, editing = editing, onKey = { ev ->
                when {
                    !editing -> false
                    ev.key == Key.DirectionLeft || ev.key == Key.DirectionRight -> {
                        if (ev.type == KeyEventType.KeyDown) row.step(if (ev.key == Key.DirectionLeft) -1 else 1); true
                    }
                    ev.key == Key.Back -> { if (ev.type == KeyEventType.KeyUp) editing = false; true }
                    // Up/Down while editing: stay on the row
                    ev.key == Key.DirectionUp || ev.key == Key.DirectionDown -> true
                    else -> false
                }
            }, onFocus = { if (!it) editing = false }, onClick = { editing = !editing }) { f ->
                Label(row.title, if (editing) "◀ ▶ to change · OK when done" else row.desc, s, Modifier.weight(1f))
                if (editing) Text("‹  ${row.value()}  ›", color = Color(0xFFB9ABFF), fontSize = (13.5f * s).sp, fontWeight = FontWeight.SemiBold)
                else Text(row.value(), color = Color.White.copy(alpha = if (f) 1f else 0.75f), fontSize = (13 * s).sp)
            }
        }
        is Act -> Frame(s, onClick = row.onClick) { f ->
            Label(row.title, row.desc, s, Modifier.weight(1f))
            if (row.value.isNotEmpty()) Text(if (row.ok == true) "✓  " + row.value else row.value,
                color = when (row.ok) { true -> Color(0xFF34C759); false -> Color(0xFFFF6961); else -> Color.White.copy(alpha = 0.7f) },
                fontSize = (12.5f * s).sp, fontWeight = if (row.ok != null) FontWeight.SemiBold else null)
        }
        is AppItem -> {
            val e = row.entry
            val hidden = e.pkg in app.settings.hiddenApps
            val art by produceState<ImageBitmap?>(null, e.pkg) { value = app.apps.artwork(e)?.bmp }
            var moving by remember { mutableStateOf(false) }
            Frame(s, editing = moving, onKey = { ev ->
                when {
                    ev.key == Key.Menu -> { if (ev.type == KeyEventType.KeyDown) onApp(e); true }
                    !moving -> false
                    ev.key == Key.DirectionLeft || ev.key == Key.DirectionRight -> {
                        if (ev.type == KeyEventType.KeyDown) app.apps.move(e.pkg, if (ev.key == Key.DirectionLeft) -1 else 1); true
                    }
                    ev.key == Key.Back -> { if (ev.type == KeyEventType.KeyUp) moving = false; true }
                    ev.key == Key.DirectionUp || ev.key == Key.DirectionDown -> true
                    else -> false
                }
            }, onFocus = { if (!it) moving = false }, onClick = { moving = !moving }, onLong = { onApp(e) }) { f ->
                Box(Modifier.size((34 * s).dp).clip(RoundedCornerShape((8 * s).dp)).background(Color.White.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
                    art?.let { Image(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding((3 * s).dp)) }
                }
                Spacer(Modifier.width((10 * s).dp))
                Label(e.label, e.pkg, s, Modifier.weight(1f))
                val flags = listOfNotNull(if (hidden) "Hidden" else null, if (app.icons.has(e.pkg)) "Custom icon" else null).joinToString(" · ")
                if (flags.isNotEmpty()) Text(flags, color = Color(0xFFB9ABFF), fontSize = (11.5f * s).sp)
                Text(if (moving) "  ◀ ▶ · OK" else "", color = Color(0xFFB9ABFF), fontSize = (12 * s).sp)
            }
        }
    }
}

@Composable
private fun Label(title: String, desc: String, s: Float, modifier: Modifier) {
    Column(modifier) {
        Text(title, color = Color.White, fontSize = (14 * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (desc.isNotBlank()) Text(desc, color = Color.White.copy(alpha = 0.5f), fontSize = (10.5f * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Frame(
    s: Float,
    onKey: ((androidx.compose.ui.input.key.KeyEvent) -> Boolean)? = null,
    onClick: () -> Unit,
    onLong: (() -> Unit)? = null,
    onFocus: (Boolean) -> Unit = {},
    editing: Boolean = false,
    content: @Composable androidx.compose.foundation.layout.RowScope.(Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
            .clip(RoundedCornerShape((14 * s).dp))
            .background(when {
                editing -> Color(0xFF7B61FF).copy(alpha = 0.34f)
                focused -> Color.White.copy(alpha = 0.26f)
                else -> Color.White.copy(alpha = 0.06f)
            })
            .tvPress(onLong = onLong, onKey = onKey, onClick = onClick)
            .padding(horizontal = (14 * s).dp, vertical = (9 * s).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content(focused) }
}

@Composable
private fun PhonePane(app: GlassApp, s: Float) {
    var url by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var qr by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    DisposableEffect(Unit) {
        val token = Net.token()
        val server = PhoneServer(app, token)
        val job = app.scope.launch(Dispatchers.IO) {
            val port = server.start()
            val ip = Net.localIp()
            if (port != null && ip != null) {
                val u = "http://$ip:$port/?t=$token"
                val bmp = Net.qr(u, 480).asImageBitmap()
                kotlinx.coroutines.withContext(Dispatchers.Main) { url = u; qr = bmp }
            } else kotlinx.coroutines.withContext(Dispatchers.Main) { failed = true }
        }
        onDispose { job.cancel(); app.scope.launch(Dispatchers.IO) { job.join(); server.stop() } }
    }
    Column(Modifier.fillMaxSize().padding(start = (6 * s).dp)) {
        Text(
            "Scan the code with your phone (same Wi-Fi) to add add-ons, set app icons, set or upload a background picture or video, paste tokens with a real keyboard, and back up or restore Glassio.",
            color = Color.White.copy(alpha = 0.7f), fontSize = (12.5f * s).sp,
        )
        Spacer(Modifier.height((14 * s).dp))
        val code = qr
        if (code != null) {
            Image(code, "QR code", Modifier.size((190 * s).dp).clip(RoundedCornerShape((14 * s).dp)))
            Spacer(Modifier.height((8 * s).dp))
            Text(url ?: "", color = Color.White.copy(alpha = 0.6f), fontSize = (11 * s).sp)
        } else if (failed) {
            Text("The TV is not on a network, so the phone page can't start.", color = Color(0xFFFF8F9B), fontSize = (13 * s).sp)
        }
        Spacer(Modifier.height((10 * s).dp))
        Text("The page stops when you leave this screen.", color = Color.White.copy(alpha = 0.45f), fontSize = (11 * s).sp)
    }
}


/** Version, updates from GitHub, the project page as a QR code, credits and the last crash (if any). */
@Composable
private fun AboutPane(app: GlassApp, s: Float, onApp: (com.glasslauncher.tv.data.AppEntry) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val rel by app.updater.available.collectAsState()
    val status by app.updater.status.collectAsState()
    val qr = remember { com.glasslauncher.tv.net.Net.qr(com.glasslauncher.tv.data.Updater.REPO_URL, 420).asImageBitmap() }
    val crash = remember { java.io.File(ctx.filesDir, "last-crash.txt").takeIf { it.exists() }?.readText()?.lineSequence()?.take(2)?.joinToString(" · ").orEmpty() }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy((18 * s).dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(qr, "GitHub", Modifier.size((150 * s).dp).clip(RoundedCornerShape((12 * s).dp)))
            Spacer(Modifier.height((6 * s).dp))
            Text("github.com/" + com.glasslauncher.tv.data.Updater.REPO, color = Color.White.copy(alpha = 0.75f), fontSize = (9.5f * s).sp)
            Text("Scan with your phone", color = Color.White.copy(alpha = 0.5f), fontSize = (9 * s).sp)
        }
        val rows = buildList<SRow> {
            add(Note("Glassio Yahsun ${app.updater.current}"))
            if (rel != null) {
                add(Act("Install update ${rel!!.version}", if (status.isNotBlank()) status else "Downloads from GitHub and restarts Glass", "Install") { app.updater.install() })
                rel!!.notes.lineSequence().map { it.trim().removePrefix("-").removePrefix("*").trim() }.filter { it.isNotBlank() && !it.startsWith("#") }
                    .take(6).forEach { add(Note("• $it")) }
            } else add(Act("Check for updates", if (status.isNotBlank()) status else "Looks at the latest release on GitHub", "Check") {
                scope.launch(Dispatchers.IO) { toast(ctx, app.updater.check()) }
            })
            add(Note("A pill appears next to the clock when a new version is out."))
            add(Note("Made by Mohammad (mrh12) · Fonts: Inter (SIL OFL) · Weather: Open-Meteo · This product uses the TMDB API but is not endorsed or certified by TMDB."))
            if (crash.isNotBlank()) add(Act("Last crash", crash.take(120), "Clear") { java.io.File(ctx.filesDir, "last-crash.txt").delete(); toast(ctx, "Cleared") })
        }
        LazyColumn(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy((4 * s).dp)) {
            items(rows.size) { i -> RowView(rows[i], s, app, onApp = onApp) }
        }
    }
}
