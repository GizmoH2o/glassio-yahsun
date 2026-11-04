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
                Toggle("Airing this week", "New episodes of the series in your library: out now, or when they come out", { st.homeAiring }, { st.homeAiring = it }),
                Toggle("Movies in your library", "", { st.homeMovies }, { st.homeMovies = it }),
                Toggle("Series in your library", "", { st.homeSeries }, { st.homeSeries = it }),
                Toggle("Posters open up when selected", "Rest on a poster: it opens into a wide card with its picture, rating, year and genres", { st.homeExpand }, { st.homeExpand = it }),
                Toggle("Big picture follows the selection", "The title selected in a row fills the top of the page", { st.homeFollow }, { st.homeFollow = it }),
                Note("The big picture: titles from your catalogs (Settings › Catalogs). Play opens one; My Library saves it; ◀ on Play and ▶ on My Library go to the previous and next title. Hold OK on Play: Not interested, or another picture."),
                Toggle("Because you watched", "Every third title is one like what's in Continue watching (it says why)", { st.heroBecause }, { st.heroBecause = it }),
                Toggle("Seasonal picks first", "Horror in October, Christmas titles in December, romance in February", { st.heroSeasonal }, { st.heroSeasonal = it }),
                Adjust("Trailers", if (st.trailerMode == "auto" && TrailerGuard.tripped(ctx)) "Off for now: they kept failing on this TV. Choose On to try again"
                    else "After a few seconds on a title, its trailer plays (muted) behind it. Auto turns them off if they keep failing",
                    { when (st.trailerMode) { "on" -> "On"; "off" -> "Off"; else -> "Auto" } },
                    { st.trailerMode = cycle(listOf("auto", "on", "off"), st.trailerMode, it); if (st.trailerMode == "on") TrailerGuard.playedWell(ctx) }),
                Adjust("OK on a card", "Open its title page, or play straight away", { if (st.cardOk == "play") "Play straight away" else "Title page" },
                    { st.cardOk = cycle(listOf("title", "play"), st.cardOk, it) }),
                Adjust("Recommendations", "Trending and popular titles from TMDB. Auto: with no Nuvio or Stremio account signed in",
                    { when (st.recsMode) { "always" -> "Always"; "off" -> "Off"; else -> "Auto" } },
                    { st.recsMode = cycle(listOf("auto", "always", "off"), st.recsMode, it); scope.launch(Dispatchers.IO) { runCatching { app.recs.refresh() } } }),
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
            listOf(
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
                Toggle("Library tab", "", { st.menuLibrary }, { st.menuLibrary = it }),
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
                Act("Send a test event", "") { io { NuvioLaunch.testWebhook(st, app.http) } },
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
                Toggle("Phone remote", "The phone page stays on, at an address that doesn't change: keep it on your phone's home screen and use it as a remote any time (Manage from phone shows its code)",
                    { st.phoneRemote }, { st.phoneRemote = it; com.glasslauncher.tv.net.PhoneRemote.apply(app) }),
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
        Category("About", Kind.About),
    )
    fun page(title: String) = pages.first { it.title == title }
    // The pages as shown: what belongs together, once each.
    val categories = listOf(
        page("Home"),
        Category("Look & motion") {
            buildList {
                add(Toggle("Reduce motion", "No lift, slide or zoom; simple fades only", { st.reduceMotion }, { st.reduceMotion = it }))
                add(Toggle("High contrast", "A stronger selection and solid backing behind text", { st.highContrast }, { st.highContrast = it }))
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
        page("About"),
    )
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
                            .tvPress(onClick = { dialog = TextEntry("Search settings", query, "A setting's name, or what it does (empty: all pages)") { query = it.trim() } })
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
                                is Toggle -> r.title + " " + r.desc
                                is Adjust -> r.title + " " + r.desc
                                is Act -> r.title + " " + r.desc
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
    opts += "Open app" to { app.apps.launchIntent(e)?.let { runCatching { ctx.startActivity(it) } } }
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
