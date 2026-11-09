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
import androidx.compose.ui.graphics.graphicsLayer
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

/** Settings shown on two pages (Performance, and Home or Clock & glass): one row, so both read and act the same. */
private fun transitionRow(st: com.glasslauncher.tv.data.Settings) = Adjust("Slide transition", "Fade between the big picture's titles (lower = faster)",
    { "${st.transitionMs} ms" }, { st.transitionMs += it * 50; st.perfMode = "custom" })
private fun refractionRow(st: com.glasslauncher.tv.data.Settings) = Adjust("Glass refraction", "How much the glass's rim bends what's behind it (0 = flat, the lightest)",
    { "${st.refraction}" }, { st.refraction += it * 2; st.perfMode = "custom" })
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
    val catFocus = remember { List(24) { FocusRequester() } }

    fun io(block: suspend () -> String) = scope.launch(Dispatchers.IO) {
        toast(ctx, runCatching { block() }.getOrElse { it.message ?: "Failed" })
    }
    /** A video link's picture if it's made already (no waiting). */
    fun thumbOf(id: String): java.io.File? = if (!id.startsWith("http")) null
        else com.glasslauncher.tv.saver.VideoThumbs.file(ctx, id).takeIf { it.isFile && it.length() > 0 }
    /** Makes the pictures still missing, then shows the same list again with them (if it's still open). */
    fun fillThumbs(ids: List<String>, title: String, items: () -> List<ThumbItem>) = scope.launch(Dispatchers.IO) {
        val missing = ids.filter { it.startsWith("http") && thumbOf(it) == null }
        if (missing.isEmpty()) return@launch
        missing.forEach { com.glasslauncher.tv.saver.VideoThumbs.get(ctx, it) }
        if ((dialog as? ThumbDialog)?.title == title) dialog = ThumbDialog(title, items())
    }
    // Simkl (from upstream Glassio): its app id comes from the phone page, then a sign-in code on the TV.
    fun connectSimkl() {
        val sk = app.cw.simkl
        scope.launch(Dispatchers.IO) {
            if (!sk.available) {
                val u = com.glasslauncher.tv.net.PhoneHost.start(app) ?: run { toast(ctx, "The TV is not on a network"); return@launch }
                val code = kotlinx.coroutines.flow.MutableStateFlow<String?>("Simkl Client ID")
                dialog = QrCodeDialog("Simkl: add its app id", u, code, "Scan, then paste the Client ID from simkl.com/settings/developer. Sign-in follows here.")
                var n = 0
                while (!sk.available && dialog != null && n++ < 600) kotlinx.coroutines.delay(1000)
                code.value = null
                if (!sk.available) return@launch
                kotlinx.coroutines.delay(400)
            }
            val pin = runCatching { sk.startPin() }.getOrElse { toast(ctx, "Simkl: ${it.message}"); return@launch }
            val code = kotlinx.coroutines.flow.MutableStateFlow<String?>(pin.code)
            dialog = QrCodeDialog("Connect Simkl", pin.qr, code, "Scan and approve on simkl.com (the code is filled in). This closes by itself.")
            val ok = sk.waitPin(pin) { dialog == null }
            code.value = null
            toast(ctx, if (ok) "Simkl connected" else "Simkl: ${sk.lastError}")
            if (ok) { st.cwSimkl = true; app.cw.pollNow(); tvRev.intValue++ }
        }
    }
    fun simklRows(): List<SRow> = buildList {
        tvRev.intValue
        val sk = app.cw.simkl
        if (!sk.connected) add(Act("Connect Simkl", "Scan a QR code with your phone and approve" + (if (!sk.available) " (first its app id, from the phone)" else ""), "") { connectSimkl() })
        else {
            add(Act("Simkl account", "Signed in" + sk.user.takeIf { it.isNotBlank() }?.let { " as $it" }.orEmpty(), "Sign out", ok = true) {
                dialog = MenuDialog("Sign out of Simkl?", listOf("Sign out" to { sk.signOut(); tvRev.intValue++; toast(ctx, "Signed out of Simkl") }, "Cancel" to { }))
            })
            add(Toggle("Continue Watching from Simkl", "What you paused in any app that uses Simkl", { st.cwSimkl }, { st.cwSimkl = it; app.cw.pollNow() }))
            add(Toggle("Simkl \"Plan to watch\" in the Library", "Its own tab", { st.librarySimkl }, { st.librarySimkl = it; scope.launch(Dispatchers.IO) { app.library.refreshSimkl() } }))
            add(Toggle("Saving to the Library saves to Simkl too", "", { st.libraryToSimkl }, { st.libraryToSimkl = it }))
        }
        add(Toggle("Sync Library and Continue Watching", "Keeps them the same on every connected account: Nuvio, Trakt and Simkl (Stremio is read). Removing a title removes it everywhere",
            { st.syncAll }, { on -> st.syncAll = on; if (on) app.sync.runSoon { m -> toast(ctx, m) } }))
    }
    // A row that opens another section (things that belong together are reachable from both).
    val catTitles = remember { arrayOf<List<String>>(emptyList()) }
    fun link(title: String, desc: String): SRow = Act("→ " + com.glasslauncher.tv.data.I18n.tr(title), desc, "") {
        val i = catTitles[0].indexOf(title)
        if (i >= 0) { cat = i; shownCat = i; scope.launch { delay150(); runCatching { catFocus[i].requestFocus() } } }
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


    val pages = listOf(
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
                Adjust("Picture quality", "The home screen's big picture", { if (st.imageQuality == "high") "High (1280 px)" else "Maximum (original)" },
                    { st.imageQuality = cycle(listOf("high", "max"), st.imageQuality, it); custom() }),
                Adjust("Picture cache", "Posters and backdrops kept on the TV's storage (fewer downloads; less free space). From the next start of Glassio",
                    { if (st.cacheMb >= 1024) "%.1f GB".format(st.cacheMb / 1024f) else "${st.cacheMb} MB" }, { st.cacheMb += it * 256 }),
                // Also on Home and Clock & glass: the same rows (a change there makes the mode Custom too).
                transitionRow(st),
                refractionRow(st),
            )
        },
        Category("Home") {
            listOf(
                Adjust("Accent colour", "LIVE, NEW, the progress bars and the FILM / SERIES line, on Home and the Live TV page", {
                    (ACCENTS.firstOrNull { it.first == st.accent } ?: ACCENTS[0]).second.first
                }, { st.accent = cycle(ACCENTS.map { it.first }, st.accent, it) }),
                Adjust("Text size", "Every screen of Glassio's own (Live TV's player keeps its own)", {
                    (TEXT_SIZES.firstOrNull { it.first == st.textSize } ?: TEXT_SIZES[1]).second.first
                }, { st.textSize = cycle(TEXT_SIZES.map { it.first }, st.textSize, it) }),
                Adjust("Library on Home", "Airing this week, Movies and Series: from your Nuvio library, your Stremio library or both, with what you saved here", {
                    when (st.homeSource) { "nuvio" -> "Nuvio"; "stremio" -> "Stremio"; else -> "Nuvio and Stremio" }
                }, { st.homeSource = cycle(listOf("both", "nuvio", "stremio"), st.homeSource, it); app.shelf.rebuild() }),
                Toggle("Today page", "Your matches, new episodes, recordings and what's set for today, with the weather: in the menu, and at the front of At a glance in the morning", { st.todayPage }, { st.todayPage = it }),
                Toggle("Open Today in the morning", "The first time Home shows after 5 a.m., Today opens by itself", { st.todayMorning }, { st.todayMorning = it }),
                Toggle("Airing this week", "New episodes of the series in your library: out now, or when they come out", { st.homeAiring }, { st.homeAiring = it }),
                Toggle("Movies in your library", "", { st.homeMovies }, { st.homeMovies = it }),
                Toggle("Series in your library", "", { st.homeSeries }, { st.homeSeries = it }),
                Act("Home rows", "Put the rows in your order, turn them off or give them your own name", "") {
                    fun rows(): MenuDialog = MenuDialog("Home rows", com.glasslauncher.tv.data.HomeRows.order(st.homeOrder).filter { n ->
                        // Your collections: only those there are, by their own names.
                        com.glasslauncher.tv.data.Collections.slotOf(n)?.let { slot -> com.glasslauncher.tv.data.Collections.parse(st.myCollections).any { it.slot == slot } } ?: true
                    }.map { n ->
                        val off = n in st.homeHidden.split(',')
                        val slot = com.glasslauncher.tv.data.Collections.slotOf(n)
                        val own = if (slot != null) com.glasslauncher.tv.data.Collections.parse(st.myCollections).firstOrNull { it.slot == slot }?.name.orEmpty()
                        else com.glasslauncher.tv.data.HomeRows.name(st.homeNames, n)
                        (if (off) "○  " else "●  ") + own.ifEmpty { Lang.tr(com.glasslauncher.tv.data.HomeRows.title(n)) } to {
                            fun again() { scope.launch { kotlinx.coroutines.delay(80); dialog = rows() } }
                            scope.launch {
                                kotlinx.coroutines.delay(80)
                                dialog = MenuDialog(own.ifEmpty { Lang.tr(com.glasslauncher.tv.data.HomeRows.title(n)) }, buildList {
                                    add("Move up" to { st.homeOrder = com.glasslauncher.tv.data.HomeRows.move(st.homeOrder, n, -1); again() })
                                    add("Move down" to { st.homeOrder = com.glasslauncher.tv.data.HomeRows.move(st.homeOrder, n, 1); again() })
                                    if (n != "Apps") add((if (off) "Show" else "Hide") to {
                                        val l = st.homeHidden.split(',').filter { it.isNotEmpty() }
                                        st.homeHidden = (if (off) l - n else l + n).joinToString(",")
                                        again()
                                    })
                                    add("Rename" to {
                                        scope.launch {
                                            kotlinx.coroutines.delay(80)
                                            dialog = TextEntry("Row name", own, if (slot != null) "" else "Leave it empty for the usual name") { typed ->
                                                if (slot != null) st.myCollections = com.glasslauncher.tv.data.Collections.write(com.glasslauncher.tv.data.Collections.rename(com.glasslauncher.tv.data.Collections.parse(st.myCollections), slot, typed))
                                                else st.homeNames = com.glasslauncher.tv.data.HomeRows.rename(st.homeNames, n, typed)
                                                again()
                                            }
                                        }
                                        Unit
                                    })
                                    if (slot != null) add("Delete this collection" to {
                                        st.myCollections = com.glasslauncher.tv.data.Collections.write(com.glasslauncher.tv.data.Collections.delete(com.glasslauncher.tv.data.Collections.parse(st.myCollections), slot))
                                        again()
                                    })
                                })
                            }
                            Unit
                        }
                    })
                    dialog = rows()
                },
                Toggle("Collections", "Rows of Pixar, Studio Ghibli and Marvel Studios films", { st.homeCollections }, { st.homeCollections = it }),
                Toggle("Your collections", "Your own rows: hold OK on a title, or use its page, to add it to one", { st.homeMine }, { st.homeMine = it }),
                Toggle("For You", "A row picked on this TV from what you watch and save. It sends nothing anywhere", { st.homeForYou }, { st.homeForYou = it }),
                Toggle("Automatic rows", "Because you watched, and Quick watches from your library", { st.homeAuto }, { st.homeAuto = it }),
                Toggle("Posters open up when selected", "Rest on a poster: it opens into a wide card with its picture, rating, year and genres", { st.homeExpand }, { st.homeExpand = it }),
                Toggle("Big picture follows the selection", "The title selected in a row fills the top of the page", { st.homeFollow }, { st.homeFollow = it }),
                Adjust("Trailer in the open card", "A poster opened up plays its trailer (muted) in its picture after this long on it",
                    { if (st.cardTrailerSec <= 0) "Off" else "After ${st.cardTrailerSec} s" },
                    { st.cardTrailerSec = cycle(listOf(0, 2, 3, 5, 8, 10), st.cardTrailerSec, it) }),
                Adjust("Card opens after", "How long on a poster before it opens up", { "${st.cardOpenMs / 1000f} s" },
                    { st.cardOpenMs = cycle(listOf(150, 300, 650), st.cardOpenMs, it) }),
                Adjust("Card opening", "Together: it widens, its picture and details come in at once, quickly. Staggered: the picture first, then the details",
                    { if (st.cardOpen == "staggered") "Staggered" else "Together" },
                    { st.cardOpen = cycle(listOf("together", "staggered"), st.cardOpen, it) }),
                Toggle("Keep the row open", "With a card open, the next one along the row opens straight away while the last one closes", { st.cardKeepOpen }, { st.cardKeepOpen = it }),
                Adjust("Card style", "An opened poster: Lighter, the lighter grey of the streaming apps. Dark: as dark as the page",
                    { if (st.cardStyle == "dark") "Dark" else "Lighter" },
                    { st.cardStyle = cycle(listOf("light", "dark"), st.cardStyle, it) }),
                Toggle("Follow the TV's animation speed", "Off: the big picture's title change and the cards open at their own speed, as on the streaming apps. On: at the TV's speed (Developer options), faster on some TVs",
                    { st.homeTvSpeed }, { st.homeTvSpeed = it }),
                Adjust("Slow zoom", "The pictures slowly zoom in from the moment they come in: the big picture, an open card, or both",
                    { when (st.slowZoom) { "off" -> "Off"; "hero" -> "Big picture"; "cards" -> "Cards"; else -> "Both" } },
                    { st.slowZoom = cycle(listOf("off", "hero", "cards", "both"), st.slowZoom, it) }),
                Note("The big picture: titles from your catalogs (Settings › Catalogs). Play opens one; My Library saves it; ◀ on Play and ▶ on My Library go to the previous and next title. Hold OK on Play: Not interested, or another picture."),
                Adjust("Big picture shows", "Everything: your catalogs' titles (and the recommendations when they're on). Recommendations only: trending and popular titles from TMDB",
                    { if (st.heroSource == "recs") "Recommendations only" else "Everything" },
                    { st.heroSource = cycle(listOf("all", "recs"), st.heroSource, it) }),
                Adjust("Changing title", "Quick: the words fade out as the picture crossfades, and the new ones fade in as it ends (under a second). In steps: one after the other, slower. All at once: words and picture together",
                    { when (st.heroChange) { "fade" -> "All at once"; "steps" -> "In steps"; else -> "Quick" } },
                    { st.heroChange = cycle(listOf("quick", "steps", "fade"), st.heroChange, it) }),
                Adjust("Going down the page", "Scrolls away: the big picture goes up and off with the page, and the rows come to the top. Stays: it stays behind the rows, darkened",
                    { if (st.heroScroll == "stay") "Stays" else "Scrolls away" },
                    { st.heroScroll = cycle(listOf("away", "stay"), st.heroScroll, it) }),
                Toggle("Because you watched", "Every third title is one like what's in Continue watching (it says why)", { st.heroBecause }, { st.heroBecause = it }),
                Toggle("Seasonal picks first", "Horror in October, Christmas titles in December, romance in February", { st.heroSeasonal }, { st.heroSeasonal = it }),
                Adjust("Trailers", if (st.trailerMode == "auto" && TrailerGuard.tripped(ctx)) "Off for now: they kept failing on this TV. Choose On to try again"
                    else "After a few seconds on a title, its trailer plays (muted) behind it. Auto turns them off if they keep failing",
                    { when (st.trailerMode) { "on" -> "On"; "off" -> "Off"; else -> "Auto" } },
                    { st.trailerMode = cycle(listOf("auto", "on", "off"), st.trailerMode, it); if (st.trailerMode == "on") TrailerGuard.playedWell(ctx) }),
                Adjust("OK on a card", "Open its title page, or play straight away", { if (st.cardOk == "play") "Play straight away" else "Title page" },
                    { st.cardOk = cycle(listOf("title", "play"), st.cardOk, it) }),
                Adjust("OK on Continue watching", "Resume in its app: Stremio plays it where you left off; Nuvio opens on it (its links can't start a stream). As other cards: as OK on a card",
                    { if (st.cwOk == "card") "As other cards" else "Resume in its app" },
                    { st.cwOk = cycle(listOf("resume", "card"), st.cwOk, it) }),
                Toggle("Rich title pages", "Title pages show where to watch, Live TV and your recordings included, and the cast with their other titles", { st.titleRich }, { st.titleRich = it }),
                Adjust("Recommendations", "Trending and popular titles from TMDB. Auto: with no Nuvio or Stremio account signed in",
                    { when (st.recsMode) { "always" -> "Always"; "off" -> "Off"; else -> "Auto" } },
                    { st.recsMode = cycle(listOf("auto", "always", "off"), st.recsMode, it); scope.launch(Dispatchers.IO) { runCatching { app.recs.refresh() } } }),
                Adjust("Match cards", "Live TV's cards for a match: in its teams' colours with their crests and the score, or the show's picture",
                    { if (st.matchCards) "Team colours" else "Show picture" },
                    { st.matchCards = !st.matchCards }),
                Toggle("Live match ticker", "A team you follow playing now: its score under the tabs", { st.homeTicker }, { st.homeTicker = it }),
                Act("Titles you're not interested in", if (st.heroHidden.isEmpty()) "None" else "${st.heroHidden.size} kept off the big picture", if (st.heroHidden.isEmpty()) "" else "Show them again") {
                    if (st.heroHidden.isNotEmpty()) { st.heroHidden = emptyList(); toast(ctx, "They're back on the big picture") }
                },
                Toggle("Auto-play", "Titles change on their own while the big picture is selected", { st.heroAutoplay }, { st.heroAutoplay = it }),
                Adjust("Title time", "Seconds before the next title", { "${st.heroIntervalSec} s" }, { st.heroIntervalSec += it }),
                transitionRow(st),
                Adjust("Title style", "Logo image, plain text, or nothing", {
                    when (st.titleMode) { "logo" -> "Logo"; "text" -> "Text"; else -> "Hidden" }
                }, { st.titleMode = cycle(listOf("logo", "text", "hidden"), st.titleMode, it) }),
                Adjust("Logo / title size", "Logos are evened out so wide and tall ones look the same size", { pct(st.titleScale) }, { st.titleScale = tenth(st.titleScale, it) }),
                Adjust("Synopsis lines", "", { "${st.synopsisLines}" }, { st.synopsisLines += it }),
                Adjust("Titles per catalog", "Reload to apply", { "${st.perCatalog}" }, { st.perCatalog += it * 5 }),
                Adjust("Total titles", "0 = every title, endless loop", { if (st.maxItems == 0) "Unlimited" else "${st.maxItems}" }, { st.maxItems = (st.maxItems + it * 10).coerceAtLeast(0) }),
                Toggle("Shuffle all catalogs", "Random order across every catalog, a new mix each time the list loads", { st.shuffle }, { st.shuffle = it }),
                Adjust("Synopsis language", "Needs a TMDB token. English is used when a title has no text in this language", { com.glasslauncher.tv.data.LANG_NAMES[st.descLang] ?: "English" }, {
                    st.descLang = cycle(com.glasslauncher.tv.data.TMDB_LANGS.keys.toList(), st.descLang, it); app.catalogs.clearEnrichment()
                }),
                Adjust("Artwork", "Which picture each title uses", {
                    when (st.artChoice) { "second" -> "Second picture"; "best" -> "Highest resolution"; "addon" -> "Add-on picture"; else -> "Main (TMDB)" }
                }, { st.artChoice = cycle(listOf("main", "second", "best", "addon"), st.artChoice, it) }),
                Act("Reset pictures picked per title", "", "") { st.clearArtPicks(); toast(ctx, "Every title is back to the Artwork setting") },
                Act("Reload titles now", "") { io { app.catalogs.refresh(); app.catalogs.status.value } },
            ) + buildList {
                // The widgets row, under the apps.
                add(Note("At a glance: a row under your apps with the weather's next days, your calendars' next events and Home Assistant readings. Home Assistant is set up under Settings › Accounts & Home Assistant. Long links and tokens are easiest pasted from your phone (Settings › Manage from phone)."))
                add(Toggle("At a glance row", "Under the apps on Home", { st.widgetsRow }, { st.widgetsRow = it }))
                add(Toggle("Weather forecast", "Today and the next four days, for the weather's city (Look & motion)", { st.widgetWeather }, { st.widgetWeather = it }))
                add(Act("Calendars", if (st.calLinks.isEmpty()) "iCal links, like Google Calendar's secret address" else "${st.calLinks.size} linked: the next 3 events show",
                    if (st.calLinks.isEmpty()) "Add" else "Add or remove") {
                    dialog = MenuDialog("Calendars", st.calLinks.map { l ->
                        "Remove " + (runCatching { java.net.URI(l.replaceFirst(Regex("^webcals?://"), "https://")).host }.getOrNull() ?: l).take(40) to {
                            st.calLinks = st.calLinks - l; toast(ctx, "Calendar removed")
                        }
                    } + ("Add an iCal link" to {
                        scope.launch {
                            kotlinx.coroutines.delay(80)
                            dialog = TextEntry("Calendar link", "", "Its iCal address (https://… or webcal://…, ending .ics)") { l ->
                                if (Regex("^(https?|webcals?)://.+", RegexOption.IGNORE_CASE).matches(l.trim())) { st.calLinks = st.calLinks + l.trim(); toast(ctx, "Calendar added") }
                                else toast(ctx, "That isn't a link (it starts with https:// or webcal://)")
                            }
                        }
                    }))
                })
                add(Act("Home Assistant readings", if (st.haBase.isBlank() || st.haToken.isBlank()) "Set its address and token first: Settings › Accounts & Home Assistant"
                    else "${st.haEntities.size} on Home: sensors, doors, locks, climate, people…", "Choose") {
                    scope.launch {
                        val all = kotlinx.coroutines.withContext(Dispatchers.IO) { runCatching { com.glasslauncher.tv.data.HomeWidgets.all(st.haBase, st.haToken) } }
                        all.onFailure { toast(ctx, it.message ?: "Couldn't reach Home Assistant") }
                        val list = all.getOrNull() ?: return@launch
                        if (list.isEmpty()) { toast(ctx, "Home Assistant has nothing to show"); return@launch }
                        fun menu(): MenuDialog = MenuDialog("Home Assistant readings", list.map { r ->
                            val on = r.id in st.haEntities
                            (if (on) "✓  " else "     ") + r.name + "  ·  " + r.value + (if (r.unit == "°") r.unit else if (r.unit.isNotBlank()) " " + r.unit else "") to {
                                st.haEntities = if (on) st.haEntities - r.id else st.haEntities + r.id
                                scope.launch { kotlinx.coroutines.delay(80); dialog = menu() }
                            }
                        })
                        dialog = menu()
                    }
                })
                // Profiles: each person's own Home (these settings, Continue watching's sources, hidden titles).
                val profs = app.profiles
                val now = profs.current()
                add(Note("Profiles: each person's own Home settings. Switch with the top bar's profile button (the round letter). The Nuvio, Stremio and Trakt accounts are the TV's: each profile picks which of them its Continue watching reads (Settings › Continue Watching)."))
                for (p in profs.all()) {
                    add(Act(p.name + if (p.kids) " (Kids)" else "", if (p.id == now.id) "On now" else "", "Rename or remove") {
                        dialog = MenuDialog(p.name, listOfNotNull(
                            "Rename" to {
                                scope.launch { kotlinx.coroutines.delay(80); dialog = TextEntry("Rename", p.name, "A name") { profs.rename(p.id, it) } }
                            },
                            // Its row gone, the focus to this page's category (not the first one's page).
                            if (p.id != now.id) "Remove" to {
                                val here = cat
                                if (profs.delete(p.id)) {
                                    toast(ctx, "${p.name} removed")
                                    scope.launch { kotlinx.coroutines.delay(80); cat = here; runCatching { catFocus[here].requestFocus() } }
                                }
                            } else null,
                        ))
                    })
                }
                add(Toggle("Ask who's watching", "When Glassio starts, with more than one profile", { st.askProfile }, { st.askProfile = it }))
                add(Act("Home PIN", if (st.homePin.isBlank()) "None: Kids mode can be left freely" else "Asked to leave Kids mode", if (st.homePin.isBlank()) "Set" else "Change") {
                    dialog = TextEntry("Home PIN (4 digits)", "", if (st.homePin.isBlank()) "Asked to leave Kids mode" else "Asked to leave Kids mode. Left empty: no PIN", secret = true) { pin ->
                        if (pin.trim().matches(Regex("\\d{4}"))) { st.homePin = pin.trim(); toast(ctx, "PIN saved") }
                        else if (pin.isBlank() && st.homePin.isNotBlank()) { st.homePin = ""; toast(ctx, "PIN removed: Kids mode can be left freely") }
                        else toast(ctx, "The PIN must be 4 digits")
                    }
                })
                // In Kids mode (here after the PIN): which apps it shows.
                if (st.kidsMode) add(Act("Apps in Kids mode", "${st.kidsApps.size} chosen", "Choose") {
                    fun menu(): MenuDialog = MenuDialog("Apps in Kids mode", app.apps.ordered(includeHidden = true).map { e ->
                        (if (e.pkg in st.kidsApps) "✓  " else "     ") + e.label to {
                            st.kidsApps = if (e.pkg in st.kidsApps) st.kidsApps - e.pkg else st.kidsApps + e.pkg
                            scope.launch { kotlinx.coroutines.delay(80); dialog = menu() }
                        }
                    })
                    dialog = menu()
                })
            }
        },
        Category("Clock & glass") {
            // Collected, not read once: the city it found shows as soon as the lookup is back.
            val weatherFound by app.weather.found.collectAsState()
            listOf(
                Toggle("Show clock", "", { st.showClock }, { st.showClock = it }),
                Toggle("24-hour clock", "", { st.clock24h }, { st.clock24h = it }),
                Toggle("Show date", "Under the clock, like Apple TV", { st.showDate }, { st.showDate = it }),
                Adjust("Clock position", "", { if (st.clockPos == "right") "Right, by Settings" else "Left, like Apple TV" },
                    { st.clockPos = cycle(listOf("left", "right"), st.clockPos, it) }),
                Adjust("Weather", "Top bar, next to the settings button", {
                    when (st.weatherMode) { "off" -> "Off"; "temp" -> "Temperature"; "condition" -> "Temperature + sky"; "city" -> "Temperature + city"; else -> "Everything" }
                }, { st.weatherMode = cycle(listOf("all", "condition", "city", "temp", "off"), st.weatherMode, it); app.weather.reload() }),
                Act("Weather city", st.weatherCity + weatherFound.takeIf { it.isNotBlank() }?.let { "  →  $it" }.orEmpty(), "Change") {
                    dialog = TextEntry("Weather city", st.weatherCity, "Baghdad") { st.weatherCity = it; app.weather.reload() }
                },
                Toggle("Fahrenheit", "", { st.weatherF }, { st.weatherF = it }),
                Toggle("Auto-hide clock and settings button", "Hidden while you browse; they appear when you go up to the settings button", { st.topAutoHide }, { st.topAutoHide = it }),
                Adjust("Shown at start for", "Then hidden until you go up to them", { "${st.topHideSec} s" }, { st.topHideSec += it }),
                Adjust("Corner radius", "The Apps folder and its tiles (0 = square)", { "${st.cornerRadius}" }, { st.cornerRadius += it * 2 }),
                Adjust("Glass blur", "0 turns blur off", { "${st.blurRadius}" }, { st.blurRadius += it * 4 }),
                refractionRow(st),
                Adjust("Glass brightness", "", { "${st.glassTint}" }, { st.glassTint += it * 2 }),
                Toggle("Colour on focus", "In the Apps folder, like tvOS: icons stay white, the selected one shows its colours on a light plate", { st.focusColor }, { st.focusColor = it }),
                Adjust("Selected icon plate", "In the Apps folder, with Colour on focus", { PLATE_NAMES[st.focusPlate] ?: "Dark" },
                    { st.focusPlate = cycle(PLATES, st.focusPlate, it) }),
                Toggle("Bounce at the edges", "In the Apps folder: pressing past the first or last icon nudges it, like tvOS", { st.edgeBounce }, { st.edgeBounce = it }),
                Toggle("Lift selected icon", "In the Apps folder: the selected icon rises a little, glass and all", { st.tileLift }, { st.tileLift = it }),
                Toggle("Show phone apps too", "Apps without a TV launcher entry", { st.showMobileApps }, { st.showMobileApps = it; app.apps.reload() }),
            )
        },
        Category("Apps & icons") {
            val imported = st.dockOrder.size // read for recomposition on reorder
            @Suppress("UNUSED_VARIABLE") val unused = imported + allApps.size
            buildList {
                add(Adjust("Icon pack", "Projectivy / Blueprint / Nova packs installed on this TV",
                    { if (st.iconPack.isEmpty()) "None (original icons)" else (packLabels[st.iconPack] ?: st.iconPack) },
                    { st.iconPack = cycle(packList, st.iconPack, it) }))
                add(Adjust("Apps per row", "The apps on the home screen, in a grid", { "${st.appsPerRow}" }, { st.appsPerRow += it }))
                add(Toggle("Show Apps folder", "All apps (hidden ones too), as a tile among your apps. Hidden apps stay listed below", { st.showFolder }, { st.showFolder = it }))
                // App lock (from upstream Glassio): hold OK on an app, then Lock; it asks for this code to open it.
                add(Act("App lock code", "Locked apps ask for it before they open. Lock one: hold OK on the app, then Lock" +
                    (if (st.lockedApps.isNotEmpty()) " · ${st.lockedApps.size} locked" else ""),
                    if (st.lockCode == "0000") "0000 · change it" else "Set", ok = st.lockCode != "0000") { AppLock.change(app, ctx) })
                add(Adjust("Apps folder background", "Behind the apps in the Apps folder", { styleName(st.dockStyle) },
                    { st.dockStyle = cycle(listOf("liquid", "water", "water_still", "clear", "amoled", "bow"), st.dockStyle, it); st.theme = "" }))
                add(Adjust("Settings background", "Behind the icon picker", { styleName(st.panelStyle) },
                    { st.panelStyle = cycle(listOf("liquid", "clear", "amoled"), st.panelStyle, it) }))
                add(Adjust("Icon style in the Apps folder", "How the plate behind every icon looks there", {
                    styleName(st.tileStyle)
                }, { st.tileStyle = cycle(listOf("liquid", "water", "water_still", "clear", "amoled", "bow"), st.tileStyle, it); st.theme = "" }))
                add(Note("Change one icon: on the home screen hold OK on the app, then Change icon. Pick from an icon pack, the Apple TV icons, a photo on the TV, a link to a PNG, or send one from your phone (Manage from phone)."))
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
        Category("Menu & themes") {
            val themes = listOf("bow", "amoled", "liquid", "clear", "water_still", "water")
            listOf(
                Adjust("Theme", "One look for the Apps folder, its icons and the menu",
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
                Toggle("Live TV tab and Following", "Your IPTV channels with a full guide, and the teams and shows you follow (in the menu)", { st.menuLive }, { st.menuLive = it }),
                Toggle("Following tab", "The teams and shows you follow, in the top bar. Off, it's in the menu.", { st.menuFollowing }, { st.menuFollowing = it }),
                Toggle("Library tab", "", { st.menuLibrary }, { st.menuLibrary = it }),
                Toggle("Search everything", "Search also looks through the TV guide, your recordings and your library", { st.searchEverything }, { st.searchEverything = it }),
                Toggle("Mood and time filters", "Search shows Funny, Scary, Under 30 min and more, across your library, catalogs and charts", { st.moodFilters }, { st.moodFilters = it }),
                Toggle("Android settings in the menu", "", { st.menuAndroid }, { st.menuAndroid = it }),
                Toggle("Wi-Fi in the menu", "", { st.menuWifi }, { st.menuWifi = it }),
                Toggle("Themes in the menu", "", { st.menuThemes }, { st.menuThemes = it }),
            )
        },
        Category("Library") {
            listOf(
                Toggle("Trakt watchlist in the Library", "Needs Trakt (Continue Watching)", { st.libraryTrakt }, { st.libraryTrakt = it; scope.launch(Dispatchers.IO) { app.library.refreshWatchlist() } }),
                Toggle("Nuvio library", if (app.library.nuvio.connected) "From your Nuvio account" + (app.library.nuvio.email.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") + ": Nuvio Legacy, the official Nuvio app and forks that sync with it"
                    else "Connect your Nuvio account above", { st.libraryNuvio }, { st.libraryNuvio = it; scope.launch(Dispatchers.IO) { app.library.refreshNuvio() } }),
                nuvioAccountRow(),
                Toggle("Save to the Nuvio library too", "The + button also saves the title to your Nuvio account (Legacy, official Nuvio and forks see it)", { st.libraryToNuvio }, { st.libraryToNuvio = it }),
                Toggle("Stremio library", if (app.library.stremio.connected) "From your Stremio account" + (app.library.stremio.email.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") + ": the library the Stremio apps show"
                    else "Connect your Stremio account above", { st.libraryStremio }, { st.libraryStremio = it; scope.launch(Dispatchers.IO) { app.library.refreshStremio() } }),
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
                    else "Connect your Nuvio account first: Settings › Accounts & Home Assistant", { st.catalogsNuvio }, { on -> io { app.catalogs.useAccount(on) } }))
                add(Toggle("Add-ons from my Stremio account", if (app.library.stremio.connected) "The add-ons on your Stremio account (${app.library.stremio.email.ifBlank { "signed in" }}) too. One that's already here isn't added twice"
                    else "Connect your Stremio account first: Settings › Accounts & Home Assistant", { st.catalogsStremio }, { on -> io { app.catalogs.useStremio(on) } }))
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
            // The list's titles by name (its entries hold only IMDb ids): the row's cards first, then Cinemeta (kept a week).
            val cwNames by androidx.compose.runtime.produceState(emptyMap<String, String>(), cwList) {
                val known = app.cw.items.value.associate { it.key to it.name }.filterValues { it.isNotBlank() }
                value = known
                value = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    cwList.associate { e ->
                        e.key to (known[e.key] ?: runCatching {
                            app.http.json("https://v3-cinemeta.strem.io/meta/${e.type}/${e.imdb}.json", 7L * 24 * 3600 * 1000)
                                .optJSONObject("meta")?.optString("name")?.takeIf { it.isNotBlank() && it != "null" }
                        }.getOrNull() ?: "")
                    }.filterValues { it.isNotBlank() }
                }
            }
            buildList {
                add(Toggle("Continue watching on Home", "Its row under the big picture: OK opens a title in the app it came from; hold OK to open it in your other app, mark it watched or take it out. Live TV's cards stay either way",
                    { st.continueMode != "off" }, { st.continueMode = if (it) "row" else "off" }))
                add(Adjust("Keep", "Only what you watched within this time; older titles leave the row (they stay in the list below)", {
                    st.cwDays.let { if (it == 0) "Everything" else if (it % 7 == 0 && it < 28) "${it / 7} week" + (if (it > 7) "s" else "") else "$it days" }
                }, { st.cwDays = cycle(listOf(0, 7, 14, 30, 60, 90), st.cwDays, it); app.cw.rebuild() }))
                add(Toggle("New episodes first", "A series with an episode out since you watched goes to the front (NEW EPISODE)", { st.cwNewFirst }, { st.cwNewFirst = it }))
                add(Toggle("Up next", "When you finish an episode, the next one takes its place, whichever app you watched it in", { st.cwUpNext }, { st.cwUpNext = it; app.cw.rebuild() }))
                add(Toggle("Catch-up on Home", "Past shows you left partway in Live TV join the row, and OK carries on where you stopped", { st.cwCatchup }, { st.cwCatchup = it }))
                add(Note("Apps"))
                add(Toggle("Nuvio Legacy", if (app.cw.hasLogAccess()) "Reading what you watch in Nuvio Legacy" else "Allow USB debugging for Glassio when the TV asks", { st.cwLegacy }, { st.cwLegacy = it }))
                val nuvioAcct = app.library.nuvio
                val nStatus by app.cw.nuvioStatus.collectAsState()
                if (nuvioAcct.available) add(Toggle("Nuvio (official app)",
                    if (nuvioAcct.connected) "From your Nuvio account" + (nuvioAcct.email.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") +
                        ": what you're watching in the official Nuvio app" + (if (nStatus.isNotBlank()) " · $nStatus" else "")
                    else "Sign in to your Nuvio account (Settings › Accounts & Home Assistant) to see what you're watching in the official Nuvio app",
                    { st.cwNuvio }, { st.cwNuvio = it; app.cw.syncNuvioNow() }))
                val stremioAcct = app.library.stremio
                val sStatus by app.cw.stremioStatus.collectAsState()
                add(Toggle("Stremio",
                    if (stremioAcct.connected) "From your Stremio account" + (stremioAcct.email.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") +
                        ": what you're watching in Stremio" + (if (sStatus.isNotBlank()) " · $sStatus" else "")
                    else "Sign in to your Stremio account (Settings › Accounts & Home Assistant) to see what you're watching in Stremio",
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
                add(Note("How it works: Nuvio Legacy writes what you play to the TV's log (title, episode, how far). Glassio reads that log through the TV's own debugging connection (ADB), so this needs ADB once: Settings › System › TV access (ADB). Nothing leaves the TV."))
                add(Note("The official Nuvio app saves what you watch to your Nuvio account, and Stremio to your Stremio account: signed in to the same account here, it shows up in Continue Watching (taking a title out here takes it out there too). Nuvio Arc and Re-Shaped: connect Trakt (and turn on Trakt in that app) and what you pause there shows up here through Trakt."))
                add(Note("Other apps (YouTube, Netflix, Apple TV…) keep their Continue Watching private on Google TV; only the system launcher may read it."))
                add(Act("Clear the list", "${cwList.size} titles", "") { app.cw.clear() })
                cwList.forEach { e ->
                    val name = cwNames[e.key] ?: e.imdb
                    add(Act(name + if (e.season > 0) "  ·  S${e.season} · E${e.episode}" else "", "${e.app} · ${e.percent}% watched", "Remove") {
                        app.cw.remove(e.key); toast(ctx, "$name removed from Continue Watching")
                    })
                }
            }
        },
        Category("Nuvio, Stremio & Home Assistant") {
            listOf(
                nuvioAccountRow(),
                stremioAccountRow(),
                *simklRows().toTypedArray(),
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
                Toggle("Tell Home Assistant what's on", "Events to the webhook: something playing, back on Home, the screensaver", { st.haOnLaunch }, { st.haOnLaunch = it }),
                Adjust("Events to Home Assistant", "Basic: playing, back on Home, the screensaver. All: also paused and playing again, goals, kick-off and full time of the matches you follow, recordings, and Kids bedtime",
                    { if (st.haEventsAll) "All" else "Basic" }, { st.haEventsAll = !st.haEventsAll }),
                Act("Send a test event", "") { io { NuvioLaunch.testWebhook(st, app.http) } },
                Toggle("Control from Home Assistant", "Its automations can tune a channel, pause, play and show a notice: the commands to paste are on the phone page (Manage from phone), with Phone remote on",
                    { st.haControl }, { st.haControl = it }),
                // Reading Home Assistant (the At a glance row's readings, chosen in Settings › Home).
                Act("Home Assistant address", st.haBase.ifBlank { "Not set: like http://homeassistant.local:8123" }, if (st.haUrl.isBlank()) "Set" else "Change") {
                    dialog = TextEntry("Home Assistant address", st.haBase, "Like http://homeassistant.local:8123 (empty: the webhook's)") { st.haUrl = it }
                },
                Act("Home Assistant token", if (st.haToken.isBlank()) "A long-lived access token (your HA profile › Security)" else "Saved",
                    if (st.haToken.isBlank()) "Set" else "Change", ok = if (st.haToken.isNotBlank()) true else null) {
                    dialog = TextEntry("Home Assistant token", "", "Long-lived access token (empty: remove it)", secret = true) { st.haToken = it; toast(ctx, if (it.isBlank()) "Token removed" else "Token saved") }
                },
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
                // Off: the TV starts no screensaver of its own, so Glassio's ("Start after" below) is the one that comes on.
                val tvSaverOn = runCatching { AndroidSettings.Secure.getInt(cr, "screensaver_enabled", 1) == 1 }.getOrDefault(true)
                add(Adjust("TV screensaver starts after (ADB)",
                    if (tvSaverOn) "The TV's own screensaver and its idle time. Off lets Glassio's screensaver come on instead"
                    else "Off: the TV starts no screensaver of its own; Glassio's comes on (Start after)", {
                    when { !tvSaverOn -> "Off"; tvAfter <= 0 -> "Unknown"; tvAfter < 60_000 -> "${tvAfter / 1000} s"; tvAfter >= 3_600_000 -> "${tvAfter / 3_600_000} h"; else -> "${tvAfter / 60_000} min" + (if (tvAfter % 60_000 != 0) " ${tvAfter % 60_000 / 1000} s" else "") }
                }, { d ->
                    val opts = listOf(0, 20_000, 30_000, 60_000, 120_000, 300_000, 600_000, 900_000, 1_800_000)
                    val cur = if (!tvSaverOn) 0 else opts.drop(1).minByOrNull { kotlin.math.abs(it - tvAfter) } ?: 60_000
                    val next = cycle(opts, cur, if (!tvSaverOn || tvAfter in opts) d else 0)
                    if (next == 0) {
                        // As Aerial Views' switch off: the screen then stays on until the TV's own sleep time (Energy settings).
                        tv("settings put secure screensaver_enabled 0 && s=\$(settings get secure sleep_timeout) && case \$s in ''|null|-1|0) s=1800000;; esac && settings put system screen_off_timeout \$s",
                            "TV screensaver off: Glassio's screensaver comes on instead")
                    } else {
                        tv("settings put secure screensaver_enabled 1 && settings put system screen_off_timeout $next && settings put secure screensaver_activate_on_sleep 1",
                            "The TV screensaver now starts after " + (if (next < 60_000) "${next / 1000} s" else "${next / 60_000} min"))
                    }
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
                Act("Save a problem report", "Glassio's version, settings and recent log for looking into a problem, with accounts, links and addresses taken out. Saved in Downloads") {
                    toast(ctx, "Making the report…")
                    scope.launch {
                        val done = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { com.glasslauncher.tv.data.ProblemReport.saveToDownloads(ctx, app.updater.current) } }
                        toast(ctx, if (done.isSuccess) "Saved in Downloads: Glassio-problem-report.txt" else "Couldn't save the report")
                    }
                },
                Toggle("Phone remote", "The phone page stays on, at an address that doesn't change: keep it on your phone's home screen and use it as a remote any time (Manage from phone shows its code)",
                    { st.phoneRemote }, { st.phoneRemote = it; com.glasslauncher.tv.net.PhoneRemote.apply(app) }),
                Toggle("Live TV on the phone page", "What's on your channels, the guide, reminders, recordings and your schedule, from the phone; Watch on TV tunes it",
                    { st.phoneLive }, { st.phoneLive = it }),
                Toggle("Calendar on the phone page", "Add to calendar on a show, and a calendar your phone subscribes to: what you set and the matches you follow",
                    { st.phoneCalendar }, { st.phoneCalendar = it }),
                Toggle("Beta updates", "Also offer test builds: newer, and more likely to have problems", { st.betaUpdates }, { st.betaUpdates = it; scope.launch(Dispatchers.IO) { app.updater.check() } }),
                Act("Weekly backup", "Saves a backup in Downloads once a week, locked with your code", if (st.backupAutoCode.isEmpty()) Lang.tr("Off") else Lang.tr("On")) {
                    if (st.backupAutoCode.isNotEmpty()) {
                        st.backupAutoCode = ""
                        com.glasslauncher.tv.data.BackupJob.cancel(ctx)
                        toast(ctx, "Weekly backup off")
                    } else dialog = TextEntry("Backup code", "", "At least ${Backup.MIN_CODE} characters. You need it to restore: keep it somewhere safe", secret = true) { code ->
                        if (code.length < Backup.MIN_CODE) { toast(ctx, "The code needs at least ${Backup.MIN_CODE} characters"); return@TextEntry }
                        st.backupAutoCode = code
                        com.glasslauncher.tv.data.BackupJob.arm(ctx)
                        toast(ctx, "Weekly backup on")
                    }
                },
                Act("Backup to WebDAV", "Nextcloud, Synology, Koofr, pCloud…: the weekly backup goes there too, locked with your code; the last ${com.glasslauncher.tv.data.WebDav.KEEP} are kept",
                    if (st.davUrl.isBlank()) Lang.tr("Off") else runCatching { java.net.URI(st.davUrl).host }.getOrNull() ?: Lang.tr("On")) {
                    fun dav(): MenuDialog = MenuDialog("Backup to WebDAV", listOf(
                        Lang.tr("Folder address") + "  ·  " + (if (st.davUrl.isBlank()) Lang.tr("Not set") else runCatching { java.net.URI(st.davUrl).host }.getOrNull().orEmpty()) to {
                            dialog = TextEntry("Folder address", st.davUrl, "Like https://cloud.example.com/remote.php/dav/files/you/Glassio (the folder is made if it isn't there)") { st.davUrl = it }
                        },
                        Lang.tr("Username") + "  ·  " + st.davUser.ifBlank { Lang.tr("Not set") } to {
                            dialog = TextEntry("Username", st.davUser, "Your WebDAV username") { st.davUser = it }
                        },
                        Lang.tr("Password") + "  ·  " + (if (st.davPass.isEmpty()) Lang.tr("Not set") else Lang.tr("Saved")) to {
                            dialog = TextEntry("Password", "", "An app password (your service's security settings). Empty: remove it. Easier from the phone page", secret = true) { st.davPass = it }
                        },
                        Lang.tr("Back up now") to {
                            fun send(code: String) {
                                toast(ctx, "Backing up…")
                                scope.launch(Dispatchers.IO) {
                                    runCatching { com.glasslauncher.tv.data.WebDav.backUp(ctx, code) }.fold({ toast(ctx, "Backed up to WebDAV") }, { toast(ctx, "Couldn't back up: ${it.message}") })
                                }
                            }
                            if (st.backupAutoCode.isNotEmpty()) send(st.backupAutoCode)
                            else dialog = TextEntry("Backup code", "", "At least ${Backup.MIN_CODE} characters. You need it to restore: keep it somewhere safe", secret = true) { code ->
                                if (code.length < Backup.MIN_CODE) toast(ctx, "The code needs at least ${Backup.MIN_CODE} characters") else send(code)
                            }
                        },
                        Lang.tr("Restore from WebDAV") to {
                            scope.launch(Dispatchers.IO) {
                                val c = com.glasslauncher.tv.data.WebDav.conf(ctx)
                                val names = runCatching { com.glasslauncher.tv.data.WebDav.list(c) }
                                names.onFailure { toast(ctx, "Couldn't read the folder: ${it.message}") }
                                val list = names.getOrNull() ?: return@launch
                                if (list.isEmpty()) { toast(ctx, "No backups in that folder yet"); return@launch }
                                kotlinx.coroutines.withContext(Dispatchers.Main) {
                                    dialog = MenuDialog("Restore from WebDAV", list.map { name ->
                                        name.removePrefix("Glassio Yahsun backup ").removeSuffix(".json") to {
                                            dialog = TextEntry("Restore the backup from " + name.removePrefix("Glassio Yahsun backup ").removeSuffix(".json"), "",
                                                "Its code. Everything here now is replaced, then Glassio restarts", secret = true) { code ->
                                                toast(ctx, "Restoring…")
                                                scope.launch(Dispatchers.IO) {
                                                    runCatching { Backup.restore(ctx, com.glasslauncher.tv.data.WebDav.download(c, name), code) }.fold({
                                                        toast(ctx, "Restored ${it.settings} settings and ${it.files} files. Glassio restarts")
                                                        kotlinx.coroutines.delay(1500)
                                                        kotlinx.coroutines.withContext(Dispatchers.Main) { Backup.restart(ctx) }
                                                    }, { toast(ctx, "Couldn't restore: ${it.message}") })
                                                }
                                            }
                                        }
                                    })
                                }
                            }
                        },
                        Lang.tr("Turn off") to { st.davUrl = ""; st.davUser = ""; st.davPass = ""; toast(ctx, "Backup to WebDAV off") },
                    ))
                    dialog = dav()
                },
                Toggle("Sync with other TVs", "Live TV's favorites and profiles, followed teams and shows, channel names and numbers, reminders and where you left catch-up shows, the same on your TVs at home (same Wi-Fi). Recordings stay on their TV",
                    { st.syncOn }, { st.syncOn = it; scope.launch(Dispatchers.IO) { com.glasslauncher.tv.data.LanSync.apply(app) } }),
                Act("Pair a TV", if (st.syncOn) "One TV shows a code, the other types it" else "Turn on Sync with other TVs first", "") {
                    if (!st.syncOn) { toast(ctx, "Turn on Sync with other TVs first"); return@Act }
                    dialog = MenuDialog("Pair a TV", listOf(
                        Lang.tr("Show a code on this TV") to {
                            val code = com.glasslauncher.tv.data.LanSync.showCode()
                            dialog = MenuDialog(Lang.tr("Type this code on the other TV") + ":  " + code.substring(0, 3) + " " + code.substring(3) +
                                "  ·  " + Lang.tr("for 2 minutes"), listOf(Lang.tr("Done") to { }))
                        },
                        Lang.tr("Type another TV's code") to {
                            val near = com.glasslauncher.tv.data.LanSync.found.entries.filter { e -> com.glasslauncher.tv.data.LanSync.peers(ctx).none { it.id == e.key } }
                            if (near.isEmpty()) toast(ctx, "No other TV with Sync on found yet: turn it on there, wait a moment and try again")
                            else dialog = MenuDialog("Which TV?", near.map { e ->
                                e.value.first to {
                                    dialog = TextEntry("Code shown on " + e.value.first, "", "6 digits") { code ->
                                        scope.launch(Dispatchers.IO) {
                                            runCatching { com.glasslauncher.tv.data.LanSync.pairWith(ctx, e.value.second, e.value.third, code) }
                                                .fold({ toast(ctx, "Paired with $it"); com.glasslauncher.tv.data.LanSync.syncNow(ctx) }, { toast(ctx, "Couldn't pair: ${it.message}") })
                                        }
                                    }
                                }
                            })
                        },
                    ))
                },
                Act("Paired TVs", "", com.glasslauncher.tv.data.LanSync.peers(ctx).size.let { if (it == 0) Lang.tr("None") else it.toString() }) {
                    val list = com.glasslauncher.tv.data.LanSync.peers(ctx)
                    if (list.isEmpty()) { toast(ctx, "No TVs paired yet"); return@Act }
                    dialog = MenuDialog("Paired TVs", list.map { p ->
                        (p.name.ifBlank { "TV" } + "  ·  " + Lang.tr("Remove")) to { com.glasslauncher.tv.data.LanSync.forget(ctx, p.id); toast(ctx, "Removed") }
                    } + listOf(Lang.tr("Sync now") to {
                        scope.launch(Dispatchers.IO) { val n = com.glasslauncher.tv.data.LanSync.syncNow(ctx); toast(ctx, if (n > 0) "Synced" else "No paired TV answered: is it on?") }
                        Unit
                    }))
                },
                Act("Storage", "What Glassio keeps on this TV", "") {
                    scope.launch {
                        val sizes = kotlinx.coroutines.withContext(Dispatchers.IO) {
                            val live = com.glasslauncher.tv.live.LiveHome.storage(app)
                            Triple(live[0], live[1], ctx.cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() })
                        }
                        fun size(b: Long) = android.text.format.Formatter.formatShortFileSize(ctx, b)
                        dialog = MenuDialog("Storage", listOf(
                            Lang.tr("Recordings") + "  ·  " + size(sizes.first) to { toast(ctx, "Delete them in Live TV: Settings, Recordings, Recorded shows") },
                            Lang.tr("TV guide") + "  ·  " + size(sizes.second) to { toast(ctx, "The guide is replaced each time it updates") },
                            Lang.tr("Pictures and other temporary files") + "  ·  " + size(sizes.third) + "  ·  " + Lang.tr("Clear") to {
                                scope.launch(Dispatchers.IO) {
                                    runCatching { coil3.SingletonImageLoader.get(ctx).diskCache?.clear() }
                                    ctx.cacheDir.listFiles()?.forEach { runCatching { it.deleteRecursively() } }
                                    toast(ctx, "Cleared")
                                }
                                Unit
                            },
                        ))
                    }
                },
                Act("Test the connection", "Whether the problem is your internet or your provider", "") {
                    toast(ctx, "Testing…")
                    scope.launch {
                        val r = kotlinx.coroutines.withContext(Dispatchers.IO) { com.glasslauncher.tv.data.ConnectionTest.run(ctx) }
                        val internet = if (r.internetMs < 0) Lang.tr("Internet") + ": " + Lang.tr("no connection") else Lang.tr("Internet") + ": " + r.internetMs + " ms"
                        val speed = if (r.mbps <= 0f) null else Lang.tr("Speed") + ": " + String.format(java.util.Locale.ROOT, "%.0f", r.mbps) + " Mb/s"
                        val provider = when {
                            r.providerMs == 0L -> null
                            r.providerMs < 0 -> Lang.tr("Provider") + ": " + Lang.tr("no answer")
                            else -> Lang.tr("Provider") + ": " + r.providerMs + " ms"
                        }
                        dialog = MenuDialog(Lang.tr(r.verdict), listOfNotNull(internet, speed, provider).map { it to {} })
                    }
                },
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
                Toggle("Back from standby: Glassio", "After a long standby Google TV shows its own home screen: Glassio replaces it. Needs \"Home button opens Glassio\"",
                    { st.bootStart }, { st.bootStart = it }),
                Toggle("Guide button opens Live TV's guide", "Instead of the TV maker's own TV app, on TVs that pass the remote's buttons to apps (some TCL TVs don't). Needs the accessibility service below", { st.interceptGuide }, { st.interceptGuide = it }),
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
        Category("Cameras") {
            buildList {
                add(Note("Your cameras"))
                add(Act("Find cameras", "Searches your network (ONVIF: Hikvision, Dahua, Reolink, Tapo, Amcrest, Axis…)", "") {
                    io {
                        val found = com.glasslauncher.tv.data.Onvif.discover()
                        if (found.isEmpty()) return@io "No cameras answered. Add one with its RTSP link below"
                        dialog = MenuDialog("Cameras found", found.map { f -> "${f.name} · ${f.ip}" to {
                            dialog = TextEntry("Login for ${f.name}", "admin:", "user:password (the camera's own login)") { login ->
                                io {
                                    val chans = com.glasslauncher.tv.data.Onvif.streamUris(f, login.substringBefore(':'), login.substringAfter(':', ""))
                                    st.cameras = (st.cameras + chans.map { (n, uri) -> Cam(n, uri).line() }).distinct(); tvRev.intValue++
                                    if (chans.size > 1) "Added ${chans.size} cameras from ${f.name}" else "Added ${f.name}"
                                }
                            }
                        } })
                        "${found.size} found"
                    }
                })
                add(Act("Cameras from Home Assistant", "Aqara (through HomeKit), Frigate, Ring, Tapo… any camera Home Assistant shows. Uses the Home Assistant link and token (Doorbell, below)", "") {
                    io {
                        check(st.haUrl.startsWith("http") && st.haToken.isNotBlank()) { "Add the Home Assistant link and token first (Doorbell from Home Assistant, below)" }
                        val list = com.glasslauncher.tv.net.HaWatch.cameras(st.haUrl, st.haToken)
                        if (list.isEmpty()) return@io "Home Assistant has no cameras"
                        dialog = MenuDialog("Home Assistant cameras", list.map { (id, n) ->
                            (if (st.cameras.any { Cam.parse(it)?.url == "ha://$id" }) "✓ $n" else n) to {
                                st.cameras = (st.cameras + Cam(n, "ha://$id").line()).distinctBy { Cam.parse(it)?.url ?: it }; tvRev.intValue++
                                toast(ctx, "Added $n")
                            }
                        })
                        "${list.size} cameras"
                    }
                })
                add(Act("Add camera link", "rtsp://user:password@ip:554/… , an HLS (.m3u8) or video link", "") {
                    dialog = TextEntry("Camera link", "rtsp://", "rtsp://user:password@camera-ip:554/stream1") { u ->
                        if (!u.contains("://")) { toast(ctx, "That isn't a link"); return@TextEntry }
                        dialog = TextEntry("Camera name", "", "Door, Garden…") { n -> st.cameras = (st.cameras + Cam(n.ifBlank { "Camera" }, u.trim()).line()).distinct(); tvRev.intValue++ }
                    }
                })
                st.cameras.mapNotNull { Cam.parse(it) }.forEach { c ->
                    add(Act(c.name, runCatching { java.net.URI(c.url).host }.getOrNull() ?: "", "Remove") { st.cameras = st.cameras.filterNot { it == c.line() || Cam.parse(it)?.url == c.url } })
                }
                if (st.cameras.isEmpty()) add(Note("No cameras yet: find them on your network or add a link above. From the phone page you can also add, rename and remove them."))
                add(Note("Where they show"))
                add(Toggle("Camera in the menu", "Live view in the menu, like Apple TV (◀ ▶ switches camera)", { st.menuCamera }, { st.menuCamera = it }))
                add(Toggle("Hold Home shows a camera", "In the corner, over any app (needs the Home button service: System)", { st.homeLong == "camera" },
                    { st.homeLong = if (it) "camera" else "off" }))
                val camList = st.cameras.mapNotNull { Cam.parse(it) }
                if (camList.size > 1) add(Adjust("  · Camera", "Which camera opens when you hold Home",
                    { (camList.firstOrNull { it.url == st.cornerCamera } ?: camList.first()).name },
                    { d ->
                        val i = camList.indexOfFirst { it.url == st.cornerCamera }.coerceAtLeast(0)
                        st.cornerCamera = camList[(i + d).mod(camList.size)].url
                    }))
                add(Adjust("  · Size", "", { pct(st.cameraScale) }, { st.cameraScale = ((st.cameraScale * 10).roundToInt() + it) / 10f }))
                if (camList.size > 1) add(Adjust("  · Switch cameras every", "Goes round your cameras on its own",
                    { if (st.cameraCycleSec == 0) "Off" else if (st.cameraCycleSec < 60) "${st.cameraCycleSec} s" else "${st.cameraCycleSec / 60} min" },
                    { st.cameraCycleSec = cycle(listOf(0, 5, 10, 15, 20, 30, 60), st.cameraCycleSec, it) }))
                add(Adjust("  · Corner camera closes after", "It goes away on its own after this (Back or holding Home again closes it sooner)",
                    { if (st.cameraCornerSec == 0) "Never" else if (st.cameraCornerSec < 60) "${st.cameraCornerSec} s" else "${st.cameraCornerSec / 60} min" },
                    { st.cameraCornerSec = cycle(listOf(10, 20, 30, 60, 120, 300, 0), st.cameraCornerSec, it) }))
                add(Note("Multi-view: up to four cameras in a row"))
                add(Toggle("On the screensaver", "▼ on the screensaver brings them up from the bottom (Back or ▼ again closes them)", { st.saverMultiCam }, { st.saverMultiCam = it }))
                if (camList.isNotEmpty()) add(Act("  · Cameras", "Which ones, in this order (up to four). None chosen = the first four",
                    com.glasslauncher.tv.MultiCamOverlay.cams(app).joinToString(" · ") { it.name }) {
                    fun chooser() {
                        val cur = st.multiCams
                        dialog = MenuDialog("Multi-view cameras", camList.map { c ->
                            (if (c.url in cur) "✓ ${c.name}" else c.name) to {
                                st.multiCams = if (c.url in cur) cur - c.url else (cur + c.url).take(4)
                                scope.launch { kotlinx.coroutines.delay(150); chooser() }
                                Unit
                            }
                        } + listOf("Done" to {}))
                    }
                    chooser()
                })
                add(Adjust("  · Size", "", { pct(st.multiScale) }, { st.multiScale = ((st.multiScale * 10).roundToInt() + it) / 10f }))
                    add(Adjust("  · Picture", "Sharp: each camera's full stream. Light: its small stream (for small or older TVs). Automatic picks for this TV",
                        { when (st.multiQuality) { "high" -> "Sharp"; "light" -> "Light"; else -> "Automatic" } }, { st.multiQuality = cycle(listOf("auto", "high", "light"), st.multiQuality, it) }))
                add(Adjust("  · Shows for", "Then it goes away by itself", { if (st.multiSec == 0) "Until closed" else if (st.multiSec < 60) "${st.multiSec} s" else "${st.multiSec / 60} min" },
                    { st.multiSec = cycle(listOf(10, 20, 30, 60, 120, 300, 0), st.multiSec, it) }))
                add(Act("  · Show it now", "In the middle of the screen (also: menu → Camera, then OK)", "") {
                    val svc = com.glasslauncher.tv.HomeKeyService.instance
                    if (svc == null) toast(ctx, "Needs the Home button service: System → Turn it on") else svc.toggleMulti(bottom = false)
                })
                add(Note("Remote buttons (in any app)"))
                add(link("Remote mapping", "The multi-view and corner camera buttons"))
                add(Adjust("Camera video", "Software keeps the TV's own video playing while a camera shows (some TVs stopped it)", {
                    when (st.cameraDecoder) { "software" -> "Software"; "hardware" -> "Hardware"; else -> "Auto (" + (if (com.glasslauncher.tv.CamPlayer.software(ctx)) "software" else "hardware") + ")" }
                }, { st.cameraDecoder = cycle(listOf("auto", "software", "hardware"), st.cameraDecoder, it) }))
                add(Note("Doorbell from Home Assistant"))
                add(Toggle("Doorbell pop-up", "Home Assistant sends to http://<this TV>:7979/notify (the same as PiPup): the camera shows in the corner over any app",
                    { st.doorbellOn }, { st.doorbellOn = it }))
                if (st.doorbellOn) {
                    add(Adjust("  · Size", "", { pct(st.doorbellScale) }, { st.doorbellScale = ((st.doorbellScale * 10).roundToInt() + it) / 10f }))
                    add(Adjust("  · Camera", "From Home Assistant: the one its automation sends; or one of your cameras here",
                        { camList.firstOrNull { it.url == st.doorbellCamera }?.name ?: "From Home Assistant" },
                        { d ->
                            val opts = listOf("") + camList.map { it.url }
                            val i = opts.indexOf(st.doorbellCamera).coerceAtLeast(0)
                            st.doorbellCamera = opts[(i + d).mod(opts.size)]
                        }))
                    if (camList.isNotEmpty()) add(Adjust("  · Switch cameras every", "Goes round your cameras on its own, starting with the doorbell's",
                        { if (st.doorbellCycleSec == 0) "Off" else if (st.doorbellCycleSec < 60) "${st.doorbellCycleSec} s" else "${st.doorbellCycleSec / 60} min" },
                        { st.doorbellCycleSec = cycle(listOf(0, 5, 10, 15, 20, 30), st.doorbellCycleSec, it) }))
                    add(Adjust("  · Shows for", "Then it goes away on its own (Back closes it sooner)",
                        { if (st.doorbellSec < 60) "${st.doorbellSec} s" else "${st.doorbellSec / 60} min" + (if (st.doorbellSec % 60 != 0) " ${st.doorbellSec % 60} s" else "") },
                        { st.doorbellSec = cycle(listOf(5, 10, 15, 20, 30, 45, 60, 90, 120), st.doorbellSec, it) }))
                    add(Toggle("  · Words", "Where and what, at the bottom of the camera", { st.doorbellText }, { st.doorbellText = it }))
                    if (st.doorbellText) {
                        // Ready-made words, or your own.
                        val presets = listOf(
                            "العربية" to ("الباب الأمامي" to "يوجد شخص عند الباب"),
                            "English" to ("Front Door" to "Someone is at the door"),
                        )
                        val cur = presets.firstOrNull { it.second.first == st.doorbellTitle && it.second.second == st.doorbellMessage }?.first ?: "Custom"
                        add(Adjust("  · Language", "Ready-made words, or write your own below", { cur }, { d ->
                            val names = presets.map { it.first }
                            val i = names.indexOf(cur)
                            val next = presets[((if (i < 0) -1 else i) + d).mod(presets.size)].second
                            st.doorbellTitle = next.first; st.doorbellMessage = next.second
                        }))
                        add(Act("  · Title", "", st.doorbellTitle) {
                            dialog = TextEntry("Doorbell title", st.doorbellTitle, "Front Door") { t -> st.doorbellTitle = t.trim() }
                        })
                        add(Act("  · Message", "", st.doorbellMessage) {
                            dialog = TextEntry("Doorbell message", st.doorbellMessage, "Someone is at the door") { t -> st.doorbellMessage = t.trim() }
                        })
                    }
                    add(Toggle("  · Chime", "A soft ding-dong when it shows", { st.doorbellChime }, { st.doorbellChime = it }))
                    add(Act("  · Try it", "Shows the pop-up now with your camera", "") {
                        io { runCatching { com.glasslauncher.tv.net.NotifyServer.show(app, org.json.JSONObject()); "Shown" }.getOrElse { it.message ?: "Couldn't show it" } }
                    })
                    // Glassio watches the doorbell sensor itself: works with no automation in Home Assistant.
                    val ha = com.glasslauncher.tv.net.HaWatch
                    add(Note("Straight from Home Assistant (no automation needed)"))
                    add(Act("  · Home Assistant link", "Now: ${ha.status}", st.haUrl.ifBlank { "Not set" }) {
                        dialog = TextEntry("Home Assistant link", st.haUrl.ifBlank { "http://" }, "http://homeassistant.local:8123") { u ->
                            st.haUrl = u; ha.restart(); tvRev.intValue++
                        }
                    })
                    add(Act("  · Token", "Home Assistant → your profile → Security → Long-lived access tokens. Easier to paste from your phone (Manage from phone)",
                        if (st.haToken.isBlank()) "Not set" else "Saved") {
                        dialog = TextEntry("Home Assistant token", "", "Long-lived access token") { t ->
                            if (t.isNotBlank()) { st.haToken = t; ha.restart(); tvRev.intValue++ }
                        }
                    })
                    add(Act("  · Doorbell sensor", "The one that turns on when the doorbell rings", st.haDoorbellEntity.ifBlank { "Pick" }) {
                        io {
                            check(st.haUrl.startsWith("http") && st.haToken.isNotBlank()) { "Add the Home Assistant link and token first" }
                            val list = ha.sensors(st.haUrl, st.haToken)
                            if (list.isEmpty()) return@io "No sensors found"
                            dialog = MenuDialog("Doorbell sensor", list.map { (id, n) -> "$n · $id" to { st.haDoorbellEntity = id; ha.restart(); tvRev.intValue++ } })
                            "${list.size} sensors"
                        }
                    })
                }
                add(link("Manage from phone", "Add, rename and remove cameras from your phone"))
            }
        },
        Category("Music") {
            buildList {
                tvRev.intValue
                val access = com.glasslauncher.tv.data.NowPlaying.hasAccess(ctx)
                add(Toggle("Music", if (!st.musicOn) "Glassio follows what any music app plays (Spotify, YouTube Music, Apple Music, Anghami…)"
                    else if (access) "On: following your music apps" else "Needs TV access (ADB, System) to read the music apps: press to try again",
                    { st.musicOn }, { on ->
                        st.musicOn = on
                        if (on) io {
                            val ok = com.glasslauncher.tv.data.NowPlaying.grant(ctx)
                            com.glasslauncher.tv.data.NowPlaying.start(ctx); app.apps.reload(); tvRev.intValue++
                            if (ok) "Music on" else "Allow TV access (ADB) first: Glassio settings → System"
                        } else app.apps.reload()
                    }))
                if (st.musicOn) {
                    add(Note("Where it shows"))
                    add(Toggle("Player in the menu", "▶ in the menu turns it into a small player", { st.musicMenu }, { st.musicMenu = it }))
                    add(Toggle("Music in the dock", "A Music icon (move, hide and change it like an app): opens the player", { st.musicDock }, { st.musicDock = it; app.apps.reload() }))
                    add(Toggle("Music mode", "While a song plays, the carousel's place becomes the player (artwork, song, buttons). ■ stops the music. With your own picture, photos, video or the screensaver's videos as the background, the island shows instead",
                        { st.musicMode }, { st.musicMode = it }))
                    add(Adjust("  · Player size", "", { pct(st.musicStageScale) }, { st.musicStageScale = ((st.musicStageScale * 10).roundToInt() + it) / 10f }))
                    add(Adjust("  · Buttons", "White or black turn the other way round when selected", {
                        when (st.musicButtons) { "white" -> "White"; "black" -> "Black"; else -> "With the theme" }
                    }, { st.musicButtons = cycle(listOf("theme", "white", "black"), st.musicButtons, it) }))
                    add(Note("Island on the screensaver"))
                    add(Toggle("Island", "While music plays, a small island at the top of the screensaver (like the iPhone's). ▲ opens it with its buttons, ▼ closes it", { st.musicIsland }, { st.musicIsland = it }))
                    add(Toggle("  · Island on the home screen too", "When Music mode is off", { st.islandHome }, { st.islandHome = it }))
                    if (st.musicIsland) {
                        add(Adjust("  · Look", "", { when (st.islandStyle) { "glass" -> "Glass"; "black" -> "Black"; "none" -> "No background"; else -> "With the theme" } }, { st.islandStyle = cycle(listOf("none", "theme", "glass", "black"), st.islandStyle, it) }))
                        add(Toggle("  · Joined to the top", "Like a MacBook's notch, instead of floating below the top", { st.islandAttached }, { st.islandAttached = it }))
                        add(Adjust("  · Size", "", { pct(st.islandScale) }, { st.islandScale = ((st.islandScale * 10).roundToInt() + it) / 10f }))
                        add(Adjust("  · Lower", "", { "${st.islandOffset.roundToInt()}" }, { st.islandOffset = st.islandOffset + it * 4f }))
                        add(Toggle("  · Artwork", "", { st.islandArt }, { st.islandArt = it }))
                        add(Toggle("  · Song and artist", "", { st.islandTitle }, { st.islandTitle = it }))
                        add(Toggle("  · Progress bar", "", { st.islandProgress }, { st.islandProgress = it }))
                        add(Adjust("  · Bars colour", "", { if (st.islandBars == "white") "White" else "From the artwork" }, { st.islandBars = cycle(listOf("art", "white"), st.islandBars, it) }))
                    }
                    add(Note("Music apps on this TV: " + installedMusicApps(ctx).joinToString { it.second }.ifBlank { "none yet" } +
                        ". The player follows and controls whatever those apps play. Signing in to Spotify, YouTube Music or Apple Music for their playlists isn't here yet."))
                }
            }
        },
        Category("Remote mapping") {
            tvRev.intValue
            val svcOn = com.glasslauncher.tv.HomeKeyService.instance != null
            // One button does one thing: taking it for something new frees it from what it did before.
            fun free(code: Int) {
                if (st.multiKey == code) st.multiKey = 0
                if (st.cornerKey == code) st.cornerKey = 0
                if (st.shuffleKey == code) st.shuffleKey = 0
                com.glasslauncher.tv.data.RemoteMap.remove(st, code)
            }
            fun mapTo(code: Int, action: String, label: String) {
                free(code); com.glasslauncher.tv.data.RemoteMap.set(st, code, action, label); tvRev.intValue++
                toast(ctx, "${keyName(code)}: " + com.glasslauncher.tv.data.RemoteMap.describe(com.glasslauncher.tv.data.RemoteMap.Map(code, action, label)).lowercase())
            }
            // What a button should open: an app (with its picture), the Apps folder, the recent apps or a TV input.
            fun choose(code: Int) {
                val opts = mutableListOf<ThumbItem>()
                opts += ThumbItem("The Apps folder", "Every app · press again to close it", HueArt.folder()) { mapTo(code, "folder", "Apps folder") }
                opts += ThumbItem("Recent apps", "The open apps, like holding Home", HueArt.recents()) { mapTo(code, "recents", "Recent apps") }
                com.glasslauncher.tv.data.Inputs.list.value.forEach { inp ->
                    val pic = app.apps.ordered(includeHidden = true).firstOrNull { it.pkg == com.glasslauncher.tv.data.Inputs.PREFIX + inp.id }?.let { app.apps.cachedArtwork(it)?.bmp }
                        ?: HueArt.input(inp.label)
                    opts += ThumbItem(inp.label, "TV input", pic) { mapTo(code, com.glasslauncher.tv.data.Inputs.PREFIX + inp.id, inp.label) }
                }
                app.apps.ordered(includeHidden = true).filter { !com.glasslauncher.tv.data.Inputs.isInput(it.pkg) && it.pkg != MusicUi.DOCK_PKG }.forEach { e ->
                    opts += ThumbItem(e.label, "App", app.apps.cachedArtwork(e)?.bmp) { mapTo(code, "app:" + e.pkg, e.label) }
                }
                dialog = ThumbDialog("${keyName(code)} opens…", opts)
            }
            // A set button asks first (a "Clear" row of its own was one OK away from losing it).
            fun keyRow(title: String, desc: String, get: () -> Int, set: (Int) -> Unit): List<SRow> = listOf(
                Act(title, desc, if (get() == 0) "Set" else keyName(get()), ok = if (get() != 0) true else null) {
                    val capture = { dialog = KeyCaptureDialog(title) { code -> free(code); set(code); tvRev.intValue++; toast(ctx, "$title: ${keyName(code)}") } }
                    if (get() == 0) capture()
                    else dialog = MenuDialog("$title · ${keyName(get())}", listOf("Choose another button" to { capture() }, "Clear this button" to { set(0); tvRev.intValue++ }))
                },
            )
            buildList {
                if (!svcOn) add(Note("Buttons in other apps need the Home button service: System → Home button service."))
                add(Note("Buttons that open something"))
                com.glasslauncher.tv.data.RemoteMap.all(st).forEach { m ->
                    add(Act(keyName(m.code), com.glasslauncher.tv.data.RemoteMap.describe(m), "Change") {
                        dialog = MenuDialog(keyName(m.code), listOf(
                            "Open something else" to { choose(m.code) },
                            "Remove this button" to { com.glasslauncher.tv.data.RemoteMap.remove(st, m.code); tvRev.intValue++ },
                        ))
                    })
                }
                add(Act("Add a button", "Press a remote button (Netflix, YouTube, a colour button…), then choose an app, the Apps folder, the recent apps or an input", "Add") {
                    dialog = KeyCaptureDialog("Press the button you want") { code -> choose(code) }
                })
                add(Note("Glassio's buttons"))
                addAll(keyRow("Shuffle button", "On the home screen: the catalog titles in a new random order", { st.shuffleKey }, { st.shuffleKey = it }))
                addAll(keyRow("Multi-view button", "Anywhere: the cameras in the middle (again closes them)", { st.multiKey }, { st.multiKey = it }))
                addAll(keyRow("Corner camera button", "Anywhere: the camera in the corner (again closes it)", { st.cornerKey }, { st.cornerKey = it }))
                add(Note("Home and Back"))
                add(Act("Hold Home", "What holding the Home button does, in any app", when (st.homeLong) {
                    "recents" -> "Recent apps"; "camera" -> "Corner camera"; "library" -> "The Library"; "menu" -> "The menu"; else -> "Nothing" }) {
                    dialog = ThumbDialog("Hold Home", listOf("recents" to ("Recent apps" to "The open apps, like the Apple TV app switcher"),
                        "camera" to ("Corner camera" to "A live camera in the corner, over any app"), "library" to ("The Library" to ""),
                        "menu" to ("The menu" to ""), "off" to ("Nothing" to "")).map { (k, v) -> ThumbItem(v.first, v.second, null, checked = st.homeLong == k) { st.homeLong = k; tvRev.intValue++ } })
                })
                add(Toggle("Recent apps use TV access (ADB)", "On: the exact open apps, while USB debugging is on. Off, or with USB debugging off: the apps used lately, no ADB needed",
                    { st.recentsAdb }, { st.recentsAdb = it }))
                add(Act("Usage access for Recent apps", "Optional: also lists apps opened before Glassio started",
                    if (com.glasslauncher.tv.data.AppHistory.usageAccess(ctx)) "On" else "Off", ok = if (com.glasslauncher.tv.data.AppHistory.usageAccess(ctx)) true else null) {
                    val i = android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (runCatching { ctx.startActivity(i) }.isFailure)
                        android.widget.Toast.makeText(ctx, "This TV has no Usage access screen: Recent apps still works without it", android.widget.Toast.LENGTH_LONG).show()
                })
                add(Toggle("Back on the home screen starts the screensaver", "Like Apple TV", { st.backSaver }, { st.backSaver = it }))
            }
        },
        Category("Hue lights") {
            val hue = com.glasslauncher.tv.data.Hue
            val sync = com.glasslauncher.tv.data.HueSync
            val hueRev = remember { androidx.compose.runtime.mutableIntStateOf(0) }
            // The bridge's rooms, zones and lights (read when this page opens, and after connecting).
            val found by androidx.compose.runtime.produceState<Pair<List<com.glasslauncher.tv.data.Hue.Group>, List<com.glasslauncher.tv.data.Hue.Light>>?>(null, st.hueUser, st.hueIp, hueRev.intValue) {
                value = if (st.hueUser.isBlank() || st.hueIp.isBlank()) null
                    else kotlinx.coroutines.withContext(Dispatchers.IO) { hue.groups(st.hueIp, st.hueUser) to hue.lights(st.hueIp, st.hueUser) }
            }
            buildList {
                add(Toggle("Hue Sync", "Philips Hue lights in the colours of what's on screen: the backdrop, the poster or the logo, videos and trailers, the music's artwork",
                    { st.hueOn }, { on -> st.hueOn = on; if (!on) sync.leave(st, "restore") }))
                if (st.hueOn) {
                    val paired = st.hueUser.isNotBlank()
                    add(Note("Bridge"))
                    if (!paired) {
                        add(Act("Find the bridge", if (st.hueIp.isBlank()) "Looks for the Hue bridge on this network" else "Found: ${st.hueIp}", "Find") {
                            io { val f = hue.discover(ctx); if (f.isEmpty()) "No bridge found: enter its address below" else { st.hueIp = f[0]; "Found ${f[0]}: press the round button on the bridge, then Connect" } }
                        })
                        add(Act("Bridge address", st.hueIp.ifBlank { "Not set (the Hue app shows it: Settings → Bridges)" }, "Change") {
                            dialog = TextEntry("Hue bridge address", st.hueIp, "The address the Hue app shows") { v -> st.hueIp = v.trim() }
                        })
                        if (st.hueIp.isNotBlank()) add(Act("Connect", "Press the round button on top of the bridge, then this (within 30 seconds)", "Connect") {
                            io {
                                repeat(30) {
                                    val r = hue.pair(st.hueIp)
                                    if (r.user != null) { st.hueUser = r.user; sync.refreshGroups(); hueRev.intValue++; return@io "Connected to the Hue bridge: now choose the lights" }
                                    if (!r.waiting) return@io "Hue: ${r.error}"
                                    kotlinx.coroutines.delay(1000)
                                }
                                "The bridge's button wasn't pressed: press it, then Connect again"
                            }
                        })
                    } else {
                        add(Act("Bridge", "Connected · ${st.hueIp}", "", ok = true) { hueRev.intValue++ })
                        val f = found
                        add(Note("Lights that follow the screen"))
                        if (f == null) add(Note("Reading the bridge…"))
                        else {
                            fun plural(n: Int) = "$n light${if (n == 1) "" else "s"}"
                            val areas = f.first.filter { it.type == "Entertainment" }
                            // Entertainment areas first: the Hue app knows where each of their lights stands, and
                            // each light then takes the colour of the part of the screen in front of it.
                            if (areas.isEmpty()) add(Note("No entertainment area on the bridge. Make one in the Hue app (Settings → Entertainment areas) and place the lights around the TV: each then takes the colour of its side of the screen by itself"))
                            areas.forEach { g ->
                                add(Toggle("Entertainment area · ${g.name}", plural(g.lights.size) + if (g.places.isNotEmpty()) " · placed around the TV" else "",
                                    { g.id in st.hueGroups }, { on -> st.hueGroups = if (on) st.hueGroups + g.id else st.hueGroups - g.id; sync.refreshGroups() }))
                            }
                            f.first.filter { it.type != "Entertainment" }.forEach { g ->
                                add(Toggle("${if (g.type == "Zone") "Zone" else "Room"} · ${g.name}", plural(g.lights.size),
                                    { g.id in st.hueGroups }, { on -> st.hueGroups = if (on) st.hueGroups + g.id else st.hueGroups - g.id; sync.refreshGroups() }))
                            }
                            f.second.forEach { l ->
                                add(Toggle("  · ${l.name}", if (l.color) "" else "White only (it follows the brightness)",
                                    { l.id in st.hueLights }, { on -> st.hueLights = if (on) st.hueLights + l.id else st.hueLights - l.id }))
                            }
                            // Each chosen light: which part of the screen it follows (a list to choose from), and
                            // (other makes) how it takes its colour.
                            val chosen = sync.targets(st, f.first)
                            val details = remember(st.hueIp, st.hueUser, hueRev.intValue, f) { f.second.associateBy { it.id } }
                            val placed = f.first.filter { it.id in st.hueGroups }.flatMap { it.places.keys }.toSet()
                            if (chosen.isNotEmpty()) add(Note("Each light · the part of the screen it follows"))
                            chosen.forEach { id ->
                                val l = details[id] ?: return@forEach
                                val zone = sync.zones(st)[id] ?: "auto"
                                val zones = if (l.gradient) hue.ZONES else hue.ZONES - "gradient"
                                val shown = if (zone == "auto" && id in placed) "Its place in the area" else hue.zoneName(zone)
                                add(Act(l.name, (if (l.kind == "ct") "White shades only: the nearest warm or cold white · " else if (l.kind == "dim") "Brightness only · " else "") +
                                    (l.maker.takeIf { !l.philips && it.isNotBlank() }?.let { "$it · " } ?: "") + "OK: choose", shown) {
                                    dialog = ThumbDialog(l.name, listOf(ThumbItem("Blink it", "To see which light this is", HueArt.blink()) { io { hue.identify(st.hueIp, st.hueUser, id); "${l.name} blinks" } }) +
                                        zones.map { z -> ThumbItem(hue.zoneName(z), hue.zoneHint(z), HueArt.zone(z), checked = z == zone) { sync.setZone(st, id, z) } })
                                })
                                if (l.kind == "color" && !l.philips) {
                                    val m = sync.methods(st)[id] ?: "auto"
                                    add(Act("  · Colour method", "Hue and saturation usually match other makes better than xy", if (m == "xy") "xy" else "Hue and saturation") {
                                        dialog = ThumbDialog("${l.name}: colour method", listOf(
                                            ThumbItem("Hue and saturation", "Recommended for this make", HueArt.method("hs"), checked = m != "xy") { sync.setMethod(st, id, "auto") },
                                            ThumbItem("xy", "Like Philips lights", HueArt.method("xy"), checked = m == "xy") { sync.setMethod(st, id, "xy") }))
                                    })
                                }
                            }
                        }
                        add(Note("Colours"))
                        // A list to choose from (OK), with what each choice means.
                        fun pick(title: String, desc: String, cur: String, opts: List<Triple<String, String, String>>, art: ((String) -> Any?)? = null, set: (String) -> Unit) =
                            add(Act(title, desc, opts.firstOrNull { it.first == cur }?.second ?: opts[0].second) {
                                dialog = ThumbDialog(title, opts.map { (k, name, hint) -> ThumbItem(name, hint, art?.invoke(k), checked = k == cur) { set(k) } })
                            })
                        pick("Colours from", "What the lights take their colours from", st.hueSource, listOf(
                            Triple("backdrop", "Backdrop", "What fills the screen: the title's picture, your photo, the music page"),
                            Triple("poster", "Poster", "The title's poster"), Triple("logo", "Logo", "The title's logo")), { HueArt.source(it) }) { st.hueSource = it }
                        add(Toggle("Follow videos", "Your video background and trailers, live", { st.hueVideo }, { st.hueVideo = it }))
                        if (st.hueVideo || st.hueSaver) add(Adjust("  · Every", "How often the lights follow a video", { if (st.hueVideoSec <= 0) "0.5 s" else "${st.hueVideoSec} s" }, { st.hueVideoSec = st.hueVideoSec + it }))
                        add(Adjust("Lights", "Each light a different colour of the picture, or all of them its main colour", {
                            if (st.hueSpread) "A colour each" else "One colour"
                        }, { st.hueSpread = !st.hueSpread }))
                        add(Adjust("Intensity", "How colourful the lights are", {
                            listOf("Subtle", "Natural", "Vivid", "Maximum")[st.hueIntensity]
                        }, { st.hueIntensity = (st.hueIntensity + it).coerceIn(0, 3) }))
                        add(Adjust("Brightness", "", { "${st.hueBri} %" }, { st.hueBri = st.hueBri + it * 5 }))
                        add(Toggle("Follow the picture's brightness", "Darker scenes, dimmer lights", { st.hueFollowBri }, { st.hueFollowBri = it }))
                        pick("Change speed", "How long the lights take to follow a video (a new title's colours come at once)", st.hueSpeedMs.toString(), listOf(
                            Triple("0", "Instant", ""), Triple("400", "Quick", "0.4 s"), Triple("1000", "Smooth", "1 s"),
                            Triple("2000", "Slow", "2 s"), Triple("4000", "Very slow", "4 s"))) { st.hueSpeedMs = it.toInt() }
                        add(Toggle("On the screensaver", "The lights follow its videos", { st.hueSaver }, { st.hueSaver = it }))
                        add(Note("Selection"))
                        add(Toggle("The selected app", "All the lights take the colour of the app selected in the dock (its glow's colour)", { st.hueApps }, { st.hueApps = it }))
                        if (st.hueApps) add(Adjust("  · Brightness", "How bright the lights are with the app's colour", { "${st.hueAppsBri} %" }, { st.hueAppsBri = st.hueAppsBri + it * 5 }))
                        add(Toggle("The selected poster in the Library", "All the lights take the colour of the poster you're on", { st.huePosters }, { st.huePosters = it }))
                        if (st.huePosters) add(Adjust("  · Brightness", "How bright the lights are with the poster's colour", { "${st.huePostersBri} %" }, { st.huePostersBri = st.huePostersBri + it * 5 }))
                        add(Note("Afterwards"))
                        pick("When you open an app or a film", "", st.hueLeave, listOf(
                            Triple("restore", "Back as they were", "As before Hue Sync"), Triple("dim", "Dim them", "Their own colour, at 20 %"),
                            Triple("off", "Turn them off", ""), Triple("keep", "Leave them", "In the last colours"))) { st.hueLeave = it }
                        pick("When the TV turns off", "", st.hueOff, listOf(
                            Triple("restore", "Back as they were", "As before Hue Sync"), Triple("off", "Turn them off", ""),
                            Triple("keep", "Leave them", "In the last colours"))) { st.hueOff = it }
                        add(Act("Put the lights back now", "As they were before Hue Sync (they follow the screen again on the home screen)", "Restore") {
                            sync.leave(st, "restore"); sync.resume(); toast(ctx, "Lights back as they were")
                        })
                        add(Act("Forget the bridge", "", "Forget") { sync.leave(st, "restore"); st.hueUser = ""; st.hueGroups = emptyList(); st.hueLights = emptyList() })
                    }
                }
            }
        },
        Category("About", Kind.About),
    )
    fun page(title: String) = pages.first { it.title == title }
    // The pages as shown: what belongs together, once each.
    val categories = listOf(
        page("Home"),
        Category("Look & motion") {
            buildList {
                add(Adjust("Language", "Glassio and Live TV. Auto follows the TV's language",
                    { Lang.CHOICES.first { it.first == Lang.choice.value }.second },
                    { Lang.choose(ctx, cycle(Lang.CHOICES.map { c -> c.first }, Lang.choice.value, it)) }))
                add(Adjust("Night mode", "Until 7 in the morning: dims Glassio and Live TV, and lowers Live TV's volume", {
                    st.nightRev; Lang.code.value     // read again when it or the language changes
                    val from = com.glasslauncher.tv.live.NightMode.from(ctx)
                    if (from <= 0) Lang.tr("Off") else java.text.SimpleDateFormat("h a", java.util.Locale.getDefault()).format(java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, from); set(java.util.Calendar.MINUTE, 0) }.time)
                }, { dir ->
                    val hours = listOf(0, 21, 22, 23)
                    val now = hours.indexOf(com.glasslauncher.tv.live.NightMode.from(ctx)).coerceAtLeast(0)
                    com.glasslauncher.tv.live.NightMode.setFrom(ctx, hours[(now + dir + hours.size) % hours.size])
                    st.nightRev++
                }))
                add(Act("Remote guide", "What the remote's buttons do", "") {
                    dialog = MenuDialog("Remote guide", listOf(
                        "OK" to "opens what's selected",
                        "Hold OK" to "its menu",
                        "Back" to "one step back; on Home, the first row, then the big picture",
                        "Home" to "Glassio's Home, then the first title",
                        "Live TV: OK" to "the player's controls",
                        "Live TV: Left" to "the channel list",
                        "Live TV: Right" to "the last channel",
                        "Live TV: Up / Down" to "other channels' shows",
                        "Live TV: hold OK" to "the menu",
                    ).map { (k, v) -> (Lang.tr(k) + "  ·  " + Lang.tr(v)) to {} })
                })
                add(Toggle("Reduce motion", "No lift, slide or zoom; simple fades only", { st.reduceMotion }, { st.reduceMotion = it; com.glasslauncher.tv.live.LiveHome.setReduceMotion(ctx, it) }))
                add(Toggle("High contrast", "A stronger selection and solid backing behind text", { st.highContrast }, { st.highContrast = it }))
                add(Toggle("Colour from the picture", "Home's dark background picks up a little colour from the selected title", { st.backdropTint }, { st.backdropTint = it }))
                addAll(page("Clock & glass").rows().filterNot { it is Adjust && it.title == "Glass refraction" })
                addAll(page("Performance").rows())
                addAll(page("Menu & themes").rows())
            }
        },
        page("Apps & icons"),
        page("Catalogs"),
        page("Continue Watching"),
        Category("Accounts & Home Assistant") {
            page("Nuvio, Stremio & Home Assistant").rows() + page("Library").rows().filterNot { it is Act && (it.title == "Nuvio account" || it.title == "Stremio account") }
        },
        page("Screensaver"),
        page("Manage from phone"),
        page("System"),
        // From upstream Glassio (after System: Home opens Manage from phone and System by their place).
        page("Cameras"),
        page("Music"),
        page("Hue lights"),
        page("Remote mapping"),
        page("About"),
    )
    catTitles[0] = categories.map { it.title }
    // Search: what you typed (Settings' first row); the rows of every page that match, while there's something typed.
    var query by remember { mutableStateOf("") }
    val results = remember { FocusRequester() }
    LaunchedEffect(query) { if (query.isNotBlank()) { kotlinx.coroutines.delay(200); runCatching { results.requestFocus() } } }

    // Catalog choices made here are applied when the screen closes.
    DisposableEffect(Unit) { onDispose { app.scope.launch(Dispatchers.IO) { runCatching { app.catalogs.refresh() } } } }
    BackHandler { if (rightFocused) runCatching { catFocus[cat].requestFocus() } else onClose() }
    LaunchedEffect(Unit) { delay150(); runCatching { firstCat.requestFocus() } }

    Box(Modifier.fillMaxSize().background(Ds.Bg).padding(start = (34 * s).dp, end = (34 * s).dp, top = (22 * s).dp, bottom = (14 * s).dp)) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy((22 * s).dp)) {
            Box(Modifier.width((200 * s).dp).fillMaxHeight()) {
                // Scrolls when the list is taller than the screen (the last entry, About, was cut off).
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Settings", color = Color.White, fontSize = (22 * s).sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = (6 * s).dp, bottom = (8 * s).dp))
                    var searchFocused by remember { mutableStateOf(false) }
                    Text(
                        query.ifBlank { "Search settings" }, color = if (searchFocused) Ds.Ink else Text2, fontSize = (12 * s).sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(bottom = (6 * s).dp).clip(RoundedCornerShape((Ds.R_CONTROL * s).dp))
                            .background(if (searchFocused) Color.White else Ds.Surface)
                            .onFocusChanged { searchFocused = it.isFocused }
                            .tvPress(onClick = { dialog = TextEntry("Search settings", query, "A setting's name, or what it does (empty: all pages)", action = "Search") { query = it.trim() } })
                            .padding(horizontal = (11 * s).dp, vertical = (6.5f * s).dp),
                    )
                    categories.forEachIndexed { i, c ->
                        var f by remember { mutableStateOf(false) }
                        Text(
                            c.title, color = if (f) Ds.Ink else if (cat == i) Color.White else Color(0xFFD0D0D4), fontSize = (12.5f * s).sp,
                            fontWeight = if (f || cat == i) FontWeight.SemiBold else FontWeight.Medium,
                            modifier = Modifier.fillMaxWidth().padding(vertical = (1 * s).dp)
                                .clip(RoundedCornerShape((Ds.R_CONTROL * s).dp))
                                .background(if (f) Color.White else if (cat == i && query.isBlank()) Color.White.copy(alpha = 0.14f) else Color.Transparent)
                                .focusRequester(catFocus[i])
                                .then(if (i == initialCat) Modifier.focusRequester(firstCat) else Modifier)
                                .onFocusChanged { f = it.isFocused; if (it.isFocused) { cat = i; query = "" } }
                                .clickable { cat = i }
                                .padding(horizontal = (11 * s).dp, vertical = (6.5f * s).dp),
                        )
                    }
                }
            }
            Box(
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
            ) {
                // A page that isn't there (asked for from outside by number, past About): the first one, not a crash.
                val c = categories.getOrNull(shownCat) ?: categories.first()
                Column(Modifier.fillMaxSize()) {
                    Text(if (query.isNotBlank()) "Results for “$query”" else c.title, color = Color.White, fontSize = (17 * s).sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = (2 * s).dp, top = (4 * s).dp, bottom = (10 * s).dp))
                    if (query.isNotBlank()) {
                        val found = categories.filter { it.kind == Kind.Rows }.flatMap { it.rows() }.filter { r ->
                            val text = when (r) {
                                is Toggle -> r.title + " " + r.desc + " " + Lang.tr(r.title) + " " + Lang.tr(r.desc)
                                is Adjust -> r.title + " " + r.desc + " " + Lang.tr(r.title) + " " + Lang.tr(r.desc)
                                is Act -> r.title + " " + r.desc + " " + Lang.tr(r.title) + " " + Lang.tr(r.desc)
                                is AppItem -> r.entry.label
                                is Note -> ""
                            }
                            text.contains(query, ignoreCase = true)
                        }.distinctBy { r -> when (r) { is Toggle -> r.title; is Adjust -> r.title; is Act -> r.title; else -> r.hashCode().toString() } }
                        if (found.isEmpty()) Text("No setting matches.", color = Text2, fontSize = (12 * s).sp, modifier = Modifier.padding(start = (2 * s).dp))
                        else LazyColumn(Modifier.fillMaxSize().focusRequester(results).focusGroup(), verticalArrangement = Arrangement.spacedBy((4 * s).dp)) {
                            items(found.size) { i -> RowView(found[i], s, app, onApp = { e -> appMenu(app, e, ctx, scope) { dialog = it } }) }
                        }
                    } else if (c.kind == Kind.Phone) PhonePane(app, s)
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
                    .background(Ds.Surface).padding((18 * s).dp)
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
    opts += "Open app" to { com.glasslauncher.tv.ui.AppLock.open(app, ctx, e) }
    show(MenuDialog(e.label, opts))
}

@Composable
private fun RowView(row: SRow, s: Float, app: GlassApp, onApp: (AppEntry) -> Unit) {
    when (row) {
        is Note -> Text(row.text, color = Text2, fontSize = (11 * s).sp, modifier = Modifier.padding(horizontal = (8 * s).dp, vertical = (6 * s).dp))
        is Toggle -> Frame(s, onClick = { row.set(!row.get()) }) { f ->
            Label(row.title, row.desc, s, Modifier.weight(1f), f)
            DsSwitch(row.get(), s)
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
                Label(row.title, if (editing) "◀ ▶ to change · OK when done" else row.desc, s, Modifier.weight(1f), f)
                if (editing) Text("‹  ${row.value()}  ›", color = Ds.Ink, fontSize = (13 * s).sp, fontWeight = FontWeight.Bold)
                else Text(row.value(), color = if (f) Ds.Ink else Text2, fontSize = (12.5f * s).sp, fontWeight = FontWeight.SemiBold)
            }
        }
        is Act -> Frame(s, onClick = row.onClick) { f ->
            Label(row.title, row.desc, s, Modifier.weight(1f), f)
            if (row.value.isNotEmpty()) Text(if (row.ok == true) "✓  " + row.value else row.value,
                color = when (row.ok) { true -> if (f) Ds.GoodOnWhite else Ds.Good; false -> if (f) Ds.BadOnWhite else Ds.Bad; else -> if (f) Ds.Ink else Text2 },
                fontSize = (12.5f * s).sp, fontWeight = FontWeight.SemiBold)
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
                Label(e.label, e.pkg, s, Modifier.weight(1f), f)
                val flags = listOfNotNull(if (hidden) "Hidden" else null, if (app.icons.has(e.pkg)) "Custom icon" else null).joinToString(" · ")
                if (flags.isNotEmpty()) Text(flags, color = if (f) Ds.InkSoft else Text2, fontSize = (11 * s).sp)
                Text(if (moving) "  ◀ ▶ · OK" else "", color = Ds.Ink, fontSize = (12 * s).sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun Label(title: String, desc: String, s: Float, modifier: Modifier, focused: Boolean) {
    Column(modifier) {
        Text(title, color = if (focused) Ds.Ink else Color.White, fontSize = (13.5f * s).sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        // The selected row's whole description, wrapped: some are long, and this is when they're read.
        if (desc.isNotBlank()) Text(desc, color = if (focused) Ds.InkSoft else Text2, fontSize = (10.5f * s).sp, maxLines = if (focused) 3 else 1, overflow = TextOverflow.Ellipsis)
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
    val k = liftOf(focused, 1.012f)
    Row(
        Modifier.fillMaxWidth()
            .then(Modifier.graphicsLayer { scaleX = k; scaleY = k })
            .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
            .clip(RoundedCornerShape((Ds.R_CONTROL * s).dp))
            .background(if (focused || editing) Color.White else Ds.Surface)
            .tvPress(onLong = onLong, onKey = onKey, onClick = onClick)
            .padding(horizontal = (13 * s).dp, vertical = (8 * s).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content(focused) }
}

@Composable
private fun PhonePane(app: GlassApp, s: Float) {
    var url by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var qr by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    val remote = app.settings.phoneRemote
    // Keyed on the switch: turned on or off with this page open, the one page server gives way to the other.
    DisposableEffect(remote) {
        // Phone remote on: the page that's always running, at its fixed address.
        if (remote) {
            val job = app.scope.launch(Dispatchers.IO) {
                val u = com.glasslauncher.tv.net.PhoneRemote.start(app)
                val bmp = u?.let { Net.qr(it, 480).asImageBitmap() }
                kotlinx.coroutines.withContext(Dispatchers.Main) { if (u != null) { url = u; qr = bmp } else failed = true }
            }
            return@DisposableEffect onDispose { job.cancel() }
        }
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
        onDispose { job.cancel(); runCatching { server.stop() } }     // at once: the port is free for Phone remote
    }
    Column(Modifier.fillMaxSize().padding(start = (6 * s).dp)) {
        Text(
            "Scan the code with your phone (same Wi-Fi): a remote, typing and Live TV search from your phone, open and reorder apps, add-ons, app icons, tokens with a real keyboard, and backups.",
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
        Text(if (remote) "Phone remote is on: this page keeps running at this address (Settings › System)." else "The page stops when you leave this screen. For a remote that always works, turn on Phone remote (Settings › System).",
            color = Color.White.copy(alpha = 0.45f), fontSize = (11 * s).sp)
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
    var crash by remember { mutableStateOf(java.io.File(ctx.filesDir, "last-crash.txt").takeIf { it.exists() }?.readText()?.lineSequence()?.take(2)?.joinToString(" · ").orEmpty()) }
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
            add(Note("Glassio Yahsun by GizmoH2o, based on Glassio by Mohammad (mrh12) · Fonts: Inter (SIL OFL) · Weather: Open-Meteo · This product uses the TMDB API but is not endorsed or certified by TMDB."))
            if (crash.isNotBlank()) add(Act("Last crash", crash.take(120), "Clear") { java.io.File(ctx.filesDir, "last-crash.txt").delete(); crash = ""; toast(ctx, "Cleared") })
        }
        LazyColumn(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy((4 * s).dp)) {
            items(rows.size) { i -> RowView(rows[i], s, app, onApp = onApp) }
        }
    }
}

private fun videoName(u: String): String = runCatching {
    java.net.URLDecoder.decode(u.substringBefore('?').trimEnd('/').substringAfterLast('/'), "UTF-8").substringBeforeLast('.').replace('_', ' ').replace('-', ' ').trim()
}.getOrNull().orEmpty().ifBlank { "Video link" }
