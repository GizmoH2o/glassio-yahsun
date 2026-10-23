package com.glasslauncher.tv.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.layout
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.layout.offset
import kotlinx.coroutines.async
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.SuccessResult
import coil3.toBitmap
import com.glasslauncher.tv.GlassApp
import com.glasslauncher.tv.R
import com.glasslauncher.tv.data.AppEntry
import com.glasslauncher.tv.data.Art
import com.glasslauncher.tv.data.HeroItem
import com.glasslauncher.tv.data.NuvioLaunch
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sqrt

enum class Screen { Home, Settings }

const val DOCK_COLUMNS = 6
/** Index of the "Manage from phone" category in the settings list. */
const val PHONE_CATEGORY = 8
/** Index of the "Catalogs" category in the settings list. */
const val CATALOGS_CATEGORY = 4
/** Pseudo package used by move mode for the Apps folder tile. */
private const val FOLDER = "__apps_folder__"

private fun imgReq(ctx: android.content.Context, data: Any?, w: Int = 1920, h: Int = 1080) =
    ImageRequest.Builder(ctx).data(data).size(w, h).build()

private fun Modifier.clipR(r: Dp): Modifier = this.clip(RoundedCornerShape(r))

/** Every dock measurement in dp, from the screen width and the "Dock size" setting (1.0 = full width). */
private class DockMetrics(val s: Float, screenW: Float, dockScale: Float) {
    val padH = 18 * s * dockScale
    val gap = 14 * s * dockScale
    private val fullCellW = (screenW - 2 * 24 * s - 2 * 18 * s - (DOCK_COLUMNS - 1) * 12 * s) / DOCK_COLUMNS
    val cellW = fullCellW * dockScale
    val dockW = DOCK_COLUMNS * cellW + (DOCK_COLUMNS - 1) * gap + 2 * padH
    val sideMargin = (screenW - dockW) / 2f
    val tileH = cellW / 1.6f
    val cellH = tileH
    // The bottom padding is smaller than the row gap so the next row never peeks out of a collapsed dock.
    val padTop = 12 * s * dockScale
    val padBottom = 10 * s * dockScale
    val bottomMargin = 22 * s
    val collapsedH = padTop + cellH + padBottom
}

@Composable
fun HomeScreen(app: GlassApp, screen: Screen, onScreen: (Screen) -> Unit) {
    val settings = app.settings
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var backdrop by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var movingPkg by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<DialogState?>(null) }
    var settingsCat by remember { mutableIntStateOf(0) }
    val firstTile = remember { FocusRequester() }
    val heroFocus = remember { FocusRequester() }
    val folderFocus = remember { FocusRequester() }
    var pickerFor by remember { mutableStateOf<AppEntry?>(null) }
    var folderOpen by remember { mutableStateOf(false) }
    var folderMoving by remember { mutableStateOf<String?>(null) }
    val tileFocus = remember { HashMap<String, FocusRequester>() }

    // The current backdrop as a small bitmap: every glass surface refracts it. The page backdrop only
    // switches once the glass copy is ready, and both crossfade together, so the glass never lags behind.
    var backImgs by remember { mutableStateOf<Triple<ImageBitmap, ImageBitmap, ImageBitmap>?>(null) }
    var prevImgs by remember { mutableStateOf<Triple<ImageBitmap, ImageBitmap, ImageBitmap>?>(null) }
    var shownBackdrop by remember { mutableStateOf<ImageBitmap?>(null) }
    var fadeMs by remember { mutableIntStateOf(settings.transitionMs) }
    val mix = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(backdrop, settings.blurRadius) {
        val u = backdrop ?: return@LaunchedEffect
        // Wait for both the full picture and its glass copy, then switch them in the same frame.
        // Originals are big downloads: if the original is not cached yet, show the 1280 px copy first
        // (fast) and swap in the original silently once it arrives.
        val loader = SingletonImageLoader.get(ctx)
        fun bmpOf(r: coil3.request.ImageResult?) = (r as? SuccessResult)?.image?.toBitmap()?.asImageBitmap()
        var fullBmp: ImageBitmap? = null
        var upgrade: kotlinx.coroutines.Deferred<coil3.request.ImageResult?>? = null
        val high = settings.imageQuality == "high"
        val pinnedBmp = GlassCache.pinnedFull[u]
        val orig = async { if (pinnedBmp != null) null else loader.execute(imgReq(ctx, if (high) sized(u, "w1280") else u)) }
        val next = kotlinx.coroutines.coroutineScope {
            val g = async { GlassCache.get(ctx, u, settings.blurRadius) }
            fullBmp = pinnedBmp ?: kotlinx.coroutines.withTimeoutOrNull(120) { bmpOf(orig.await()) }
            if (fullBmp == null && !high && sized(u, "w1280") != u) {
                fullBmp = bmpOf(loader.execute(imgReq(ctx, sized(u, "w1280"))))
                upgrade = orig
            } else if (fullBmp == null) fullBmp = bmpOf(orig.await())
            g.await()?.let { Triple(it.blurred, it.sharp, it.heavy) }
        }
        if (fullBmp == null) return@LaunchedEffect
        val first = backImgs == null
        prevImgs = backImgs
        if (next != null) backImgs = next
        fadeMs = if (app.browsing()) 220 else settings.transitionMs
        shownBackdrop = fullBmp
        if (first || next == null) mix.snapTo(1f)
        else { mix.snapTo(0f); mix.animateTo(1f, tween(fadeMs, easing = androidx.compose.animation.core.LinearEasing)) }
        prevImgs = null
        upgrade?.let { d -> bmpOf(d.await())?.let { hi -> fadeMs = 0; shownBackdrop = hi } }
    }
    // Glass like glass: a short ripple after every move in the dock.
    val wobble = remember { androidx.compose.animation.core.Animatable(0f) }
    val kick: () -> Unit = {
        if (settings.dockStyle == "water" || settings.tileStyle == "water") scope.launch {
            wobble.snapTo(1f)
            wobble.animateTo(0f, tween(700, easing = androidx.compose.animation.core.LinearOutSlowInEasing))
        }
    }
    LaunchedEffect(app.homeRev.intValue) {
        folderOpen = false; folderMoving = null; pickerFor = null; dialog = null; movingPkg = null; expanded = false
    }
    LaunchedEffect(app.requestSettingsRev.intValue) {
        val c = app.requestSettings.intValue
        if (c >= 0) { settingsCat = c; onScreen(Screen.Settings); app.requestSettings.intValue = -1 }
    }
    LaunchedEffect(app.requestFolder.value) { if (app.requestFolder.value) { folderOpen = true; app.requestFolder.value = false } }

    LaunchedEffect(screen) {
        if (screen == Screen.Home) { delay(400); runCatching { firstTile.requestFocus() } }
    }
    BackHandler(enabled = screen == Screen.Home && movingPkg != null) { movingPkg = null }
    BackHandler(enabled = screen == Screen.Home && movingPkg == null && expanded && !folderOpen) { runCatching { firstTile.requestFocus() } }
    BackHandler(enabled = settings.backSaver && screen == Screen.Home && movingPkg == null && !expanded && !folderOpen && pickerFor == null) {
        runCatching { ctx.startActivity(android.content.Intent(ctx, com.glasslauncher.tv.saver.ScreensaverActivity::class.java)) }
    }

    val onMoveKey: (KeyEvent) -> Boolean = { ev ->
        val p = movingPkg
        if (p == null) false
        else when {
            ev.isSelect -> { if (ev.type == KeyEventType.KeyUp) movingPkg = null; true }
            ev.key == Key.DirectionLeft || ev.key == Key.DirectionRight || ev.key == Key.DirectionUp || ev.key == Key.DirectionDown -> {
                if (ev.type == KeyEventType.KeyDown) {
                    val delta = when (ev.key) {
                        Key.DirectionLeft -> -1; Key.DirectionRight -> 1
                        Key.DirectionUp -> -DOCK_COLUMNS; else -> DOCK_COLUMNS
                    }
                    if (p == FOLDER) {
                        val n = app.apps.ordered().size
                        val cur = settings.folderPos.let { if (it < 0 || it > n) n else it }
                        settings.folderPos = (cur + delta).coerceIn(0, n)
                    } else app.apps.moveVisible(p, delta)
                }
                true
            }
            else -> false
        }
    }

    val showMenu: (AppEntry, Boolean) -> Unit = { e, inFolder ->
        dialog = appMenu(
            app, e, ctx,
            startMove = if (inFolder) ({ folderMoving = e.pkg }) else ({ movingPkg = e.pkg }),
            pickIcon = { pickerFor = e },
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val density = LocalDensity.current
        val rootW = with(density) { maxWidth.roundToPx() }
        val rootH = with(density) { maxHeight.roundToPx() }
        val s = maxWidth.value / 960f
        val screenHdp = maxHeight.value
        val veil = animateFloatAsState(if (expanded && !folderOpen) 1f else 0f, tween(320), label = "veil")
        val env = GlassEnv(backImgs?.first, backImgs?.second, backImgs?.third, rootW, rootH, settings.glassTint, settings.refraction, 1f, wobble.asState(),
            prevImgs?.first, prevImgs?.second, mix.asState(), veil)
        val radius = settings.cornerRadius
        val metrics = remember(s, maxWidth, settings.dockScale) { DockMetrics(s, maxWidth.value, settings.dockScale) }

        CompositionLocalProvider(LocalGlassEnv provides env, LocalScale provides s) {
            // Nothing under the folder is drawn: it covers the whole screen (the TV GPU is the bottleneck).
            // Backdrop and scrims are flattened into one cached GPU layer: moving focus in the dock then
            // composites a single texture instead of redrawing the photo and both gradients every frame.
            if (!folderOpen) Box(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }) {
                Backdrop(shownBackdrop, fadeMs)
                Box(Modifier.fillMaxHeight().fillMaxWidth(0.62f).background(Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = 0.88f), 1f to Color.Transparent)))
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().fillMaxHeight(0.42f).background(Brush.verticalGradient(
                    0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.80f))))
            }
            // Dock open: the whole page blurs and darkens (the glass draws the same veil, see LiquidSurface).
            val veilOn by remember { androidx.compose.runtime.derivedStateOf { veil.value > 0.01f } }
            if (veilOn && !folderOpen) {
                backImgs?.third?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = veil.value }) }
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = veil.value }.background(Color.Black.copy(alpha = VEIL_DIM)))
            }

            // Fully covered by settings or the folder: stop drawing it at all.
            val homeAlphaState = animateFloatAsState(if (screen == Screen.Home && !folderOpen) 1f else 0f, tween(180), label = "homeAlpha")
            val homeVisible by remember { androidx.compose.runtime.derivedStateOf { homeAlphaState.value > 0.001f } }
            if (homeVisible || (screen == Screen.Home && !folderOpen)) Box(
                Modifier.fillMaxSize().graphicsLayer { alpha = homeAlphaState.value }
                    .focusProperties { canFocus = screen == Screen.Home && pickerFor == null && !folderOpen }
            ) {
                HeroSection(app, s, hidden = expanded || folderOpen, paused = screen != Screen.Home, metrics = metrics,
                    heroFocus = heroFocus, firstTile = firstTile, onBackdrop = { backdrop = it })
                TopBar(app, s, onSettings = { onScreen(Screen.Settings) }, modifier = Modifier.fillMaxWidth().align(Alignment.TopStart))
                Dock(
                    app = app, s = s, metrics = metrics, screenH = screenHdp, radius = radius,
                    expanded = expanded, onExpanded = { expanded = it },
                    movingPkg = movingPkg, onMoveKey = onMoveKey, firstTile = firstTile, heroFocus = heroFocus,
                    folderFocus = folderFocus, tileFocus = tileFocus, onMove = kick,
                    onMenu = { showMenu(it, false) },
                    onFolder = { folderOpen = true },
                    onFolderMenu = { dialog = MenuDialog("Apps", listOf("Move" to { movingPkg = FOLDER })) },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
            if (folderOpen) {
                FolderOverlay(
                    app, s, metrics, radius, screenHdp,
                    moving = folderMoving, onMoveDone = { folderMoving = null }, blocked = pickerFor != null,
                    onMenu = { showMenu(it, true) },
                    onClose = {
                        folderMoving = null
                        folderOpen = false
                        scope.launch { delay(150); runCatching { folderFocus.requestFocus() } }
                    },
                )
            }
            if (screen == Screen.Home && !folderOpen) UpdateBanner(app, s, Modifier.align(Alignment.TopCenter))
            if (screen == Screen.Settings) SettingsScreen(app, settingsCat) { settingsCat = 0; onScreen(Screen.Home) }
            pickerFor?.let { e ->
                IconPicker(
                    app, e,
                    onPhone = { settingsCat = PHONE_CATEGORY; onScreen(Screen.Settings) },
                    onClose = {
                        val back = tileFocus[e.pkg] ?: firstTile
                        pickerFor = null
                        scope.launch { delay(200); runCatching { back.requestFocus() } }
                    },
                )
            }
            DialogHost(dialog) { dialog = null }
        }
    }
}

private fun appMenu(
    app: GlassApp, e: AppEntry, ctx: android.content.Context,
    startMove: (() -> Unit)?, pickIcon: () -> Unit,
): DialogState {
    val hidden = e.pkg in app.settings.hiddenApps
    val opts = mutableListOf<Pair<String, () -> Unit>>()
    if (hidden) opts += "Open" to { app.apps.launchIntent(e)?.let { runCatching { ctx.startActivity(it) } } }
    opts += "App info" to {
        runCatching {
            ctx.startActivity(
                Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${e.pkg}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { toast(ctx, "App info is not available on this device") }
    }
    if (startMove != null) opts += "Move" to startMove
    opts += "Change icon" to pickIcon
    opts += (if (hidden) "Show in dock" else "Hide") to { app.apps.toggleHidden(e.pkg) }
    return MenuDialog(e.label, opts)
}

@Composable
private fun Backdrop(bmp: ImageBitmap?, ms: Int) {
    // Same bitmap, same frame, same fade as the glass: the two can never drift apart.
    Crossfade(targetState = bmp, animationSpec = tween(ms, easing = androidx.compose.animation.core.LinearEasing), label = "backdrop") { b ->
        if (b != null) {
            Image(b, null, contentScale = ContentScale.Crop, filterQuality = androidx.compose.ui.graphics.FilterQuality.High, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF110E26), Color(0xFF3A1B52), Color(0xFF0D1A3A)))))
        }
    }
}

// ───────────────────────────── hero / carousel ─────────────────────────────

@Composable
private fun HeroSection(
    app: GlassApp, s: Float, hidden: Boolean, paused: Boolean, metrics: DockMetrics,
    heroFocus: FocusRequester, firstTile: FocusRequester, onBackdrop: (String?) -> Unit,
) {
    val settings = app.settings
    val ctx = LocalContext.current
    val catalogItems by app.catalogs.items.collectAsState()
    val cwItems by app.cw.items.collectAsState()
    val enrichedRaw by app.catalogs.enrichedItems.collectAsState()
    val status by app.catalogs.status.collectAsState()
    var index by remember { mutableIntStateOf(0) }
    var focused by remember { mutableStateOf(false) }
    val mode = settings.continueMode
    val lead = settings.cwLead
    val items = remember(catalogItems, cwItems, mode, lead) {
        val cw = if (mode == "off") emptyList() else cwItems
        val keys = cw.map { it.key }.toSet()
        val rest = catalogItems.filterNot { it.key in keys }
        when {
            cw.isEmpty() -> catalogItems
            else -> buildList {
                // "At the start": the first [lead] titles open the carousel, the others follow one after
                // every four catalog titles. "Mixed": all of them spread that way.
                val head = if (mode == "mixed") 0 else if (lead <= 0) cw.size else lead.coerceAtMost(cw.size)
                addAll(cw.take(head))
                var c = head
                rest.forEachIndexed { i, it -> if (i % 4 == 3 && c < cw.size) add(cw[c++]); add(it) }
                while (c < cw.size) add(cw[c++])
            }
        }
    }
    // TMDB enrichment is cached per title; keep the progress of Continue Watching items on top of it.
    val enriched = enrichedRaw
    // Back from Nuvio: open the carousel on what was just watched.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val lifeState by lifecycle.currentStateFlow.collectAsState()
    val resumed = lifeState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
    val jumpRev by app.cw.jumpRev.collectAsState()
    var jumpedRev by remember { mutableIntStateOf(jumpRev) }
    LaunchedEffect(resumed, jumpRev, items) {
        if (resumed && jumpRev != jumpedRev && settings.continueJump && mode != "off") {
            val k = app.cw.lastWatched.value
            val i = items.indexOfFirst { it.key == k }
            if (i >= 0) index = i
            jumpedRev = jumpRev
        }
    }

    val raw = if (items.isEmpty()) null else items[index.mod(items.size)]
    val target = raw?.let { r -> enriched[r.key]?.copy(progress = r.progress, sub = r.sub) ?: r }

    // The slide changes once its backdrop and logo are decoded (usually instant: prefetched). While you are
    // browsing with the remote it never waits long, so the text keeps up with every press.
    var item by remember { mutableStateOf<HeroItem?>(null) }
    LaunchedEffect(target?.key, target?.background, target?.logo) {
        val t = target
        if (t == null) { item = null; return@LaunchedEffect }
        if (item != null) {
            val loader = SingletonImageLoader.get(ctx)
            // Browsing: the text follows every press at once; the picture follows as soon as it is ready.
            if (!app.browsing()) kotlinx.coroutines.withTimeoutOrNull(2500L) {
                kotlinx.coroutines.coroutineScope {
                    if (t.background.isNotBlank()) launch { loader.execute(imgReq(ctx, t.background)) }
                    if (t.background.isNotBlank()) launch { GlassCache.get(ctx, t.background, settings.blurRadius) }
                    if (t.logo.isNotBlank()) launch { loader.execute(imgReq(ctx, t.logo, 1000, 400)) }
                }
            }
        }
        item = t
    }
    // Apple TV style gallery: the title stays, its pictures change.
    val gallery = item?.let { i -> if (settings.heroGallery) i.gallery.take(settings.galleryShots) else emptyList() }.orEmpty()
    var shot by remember { mutableIntStateOf(0) }
    LaunchedEffect(item?.key) { shot = 0 }
    val shotMs = (settings.heroIntervalSec * 1000L).coerceAtLeast(4000L)
    LaunchedEffect(item?.key, gallery.size, hidden, paused, resumed) {
        if (gallery.size < 2 || hidden || paused || !resumed) return@LaunchedEffect
        val loader = SingletonImageLoader.get(ctx)
        while (true) {
            val next = gallery[(shot + 1).mod(gallery.size)]
            loader.enqueue(imgReq(ctx, next)); launch { GlassCache.get(ctx, next, settings.blurRadius) }
            delay(shotMs)
            if (shot + 1 >= gallery.size) break
            shot++
        }
    }
    val shownBg = gallery.getOrNull(shot) ?: item?.background
    LaunchedEffect(shownBg, hidden) { onBackdrop(shownBg?.takeIf { it.isNotBlank() }) }
    LaunchedEffect(index, items.size, settings.tmdbToken) {
        if (items.isEmpty()) return@LaunchedEffect
        val d = settings.prefetchDepth
        for (k in listOf(0) + (1..d + 1).flatMap { listOf(it, -it) }.take(d * 2)) app.catalogs.enrich(items[(index + k).mod(items.size)])
    }
    LaunchedEffect(index, items.size, enriched.size) {
        if (items.size < 2) return@LaunchedEffect
        val loader = SingletonImageLoader.get(ctx)
        // Both directions, a few slides deep, glass copies included.
        val depth = settings.prefetchDepth
        val ahead = (1..depth).flatMap { if (it <= (depth + 1) / 2) listOf(it, -it) else listOf(it) }
        for (k in ahead) {
            val n = items[(index + k).mod(items.size)].let { enriched[it.key] ?: it }
            if (n.background.isNotBlank()) {
                loader.enqueue(imgReq(ctx, sized(n.background, "w1280")))
                if (kotlin.math.abs(k) <= 1 && settings.imageQuality != "high") loader.enqueue(imgReq(ctx, n.background))
                launch { GlassCache.get(ctx, n.background, settings.blurRadius) }
            }
            if (n.logo.isNotBlank()) loader.enqueue(imgReq(ctx, n.logo, 1000, 400))
        }
    }
    // Continue Watching stays in memory (picture, logo, glass) for as long as the title is in the list:
    // going back to it — even after browsing far, or with Home — never reloads anything.
    val cwKeys = remember(cwItems) { cwItems.map { it.key } }
    LaunchedEffect(cwKeys) { cwItems.forEach { app.catalogs.enrich(it) } }
    val cwShown = cwItems.map { c -> enriched[c.key] ?: c }
    LaunchedEffect(cwShown.map { it.background + it.logo }, settings.imageQuality, settings.blurRadius) {
        val loader = SingletonImageLoader.get(ctx)
        val high = settings.imageQuality == "high"
        val keep = mutableSetOf<String>()
        for (c in cwShown) {
            val bg = c.background
            if (bg.isNotBlank()) {
                keep += bg
                if (GlassCache.pinnedFull[bg] == null) (loader.execute(imgReq(ctx, if (high) sized(bg, "w1280") else bg)) as? SuccessResult)
                    ?.image?.toBitmap()?.asImageBitmap()?.let { GlassCache.pinnedFull[bg] = it }
                GlassCache.pin(ctx, bg, settings.blurRadius)
            }
            if (c.logo.isNotBlank()) {
                keep += c.logo
                if (GlassCache.pinnedLogo[c.logo] == null) (loader.execute(imgReq(ctx, c.logo, 1000, 400)) as? SuccessResult)
                    ?.image?.toBitmap()?.asImageBitmap()?.let { GlassCache.pinnedLogo[c.logo] = it }
            }
        }
        GlassCache.keepOnly(keep)
    }
    // Home pressed while on the home screen: back to the first slide (Continue Watching).
    val homeRev = app.homeRev.intValue
    var seenHome by remember { mutableIntStateOf(homeRev) }
    LaunchedEffect(homeRev) {
        if (homeRev != seenHome) {
            seenHome = homeRev
            index = 0
            delay(120); runCatching { heroFocus.requestFocus() }
        }
    }
    // Keeps playing while the carousel is selected; any Left/Right restarts the timer (index changes).
    LaunchedEffect(index, items.size, settings.heroAutoplay, settings.heroIntervalSec, hidden, paused, resumed) {
        if (settings.heroAutoplay && !hidden && !paused && resumed && items.size > 1) {
            val shots = item?.let { i -> if (settings.heroGallery) i.gallery.take(settings.galleryShots).size else 0 } ?: 0
            delay(settings.heroIntervalSec * 1000L * shots.coerceAtLeast(1))
            index++
        }
    }

    val alpha by animateFloatAsState(if (hidden) 0f else 1f, tween(250), label = "heroAlpha")
    val dotsOn = items.size > 1 && settings.dotsMode != "hidden" && (!settings.dotsAutoHide || focused)
    val dotsAlpha by animateFloatAsState(if (dotsOn) 1f else 0f, tween(300), label = "dotsAlpha")

    Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }) {
        val shown = item
        if (shown != null) {
            HeroInfo(
                item = shown, app = app, s = s, focused = focused, heroFocus = heroFocus, firstTile = firstTile,
                onFocus = { focused = it },
                onPrev = { app.navAt = System.currentTimeMillis(); index-- }, onNext = { app.navAt = System.currentTimeMillis(); index++ },
                onOpen = { NuvioLaunch.open(ctx, settings, app.http, shown) },
                modifier = Modifier.align(Alignment.TopStart),
            )
        } else {
            EmptyHint(status, s, heroFocus, firstTile, Modifier.align(Alignment.TopStart)) {
                app.requestSettings.intValue = CATALOGS_CATEGORY; app.requestSettingsRev.intValue++
            }
        }
        if (items.size > 1 && settings.dotsMode != "hidden") {
            Dots(
                total = items.size, current = index.mod(items.size), mode = settings.dotsMode, max = settings.dotsMax, s = s,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = (metrics.collapsedH + metrics.bottomMargin + 10 * s).dp)
                    .graphicsLayer { this.alpha = dotsAlpha },
            )
        }
    }
}

/**
 * Shown while the carousel has nothing. It must hold [heroFocus]: the dock's first row points Up at it,
 * and without a focus target there Up did nothing, so Settings could not be reached from the dock.
 */
@Composable
private fun EmptyHint(
    status: String, s: Float, heroFocus: FocusRequester, firstTile: FocusRequester,
    modifier: Modifier, onCatalogs: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, spring(0.6f, 450f), label = "hintBtn")
    Column(modifier.padding(start = (56 * s).dp, top = (130 * s).dp).widthIn(max = (480 * s).dp)) {
        Text("Glassio", color = Color.White, fontSize = (30 * s).sp, fontWeight = FontWeight.Light, letterSpacing = 4.sp)
        Spacer(Modifier.height((10 * s).dp))
        Text(if (status.isBlank()) "Loading…" else status, color = Color.White.copy(alpha = 0.7f), fontSize = (14 * s).sp)
        Spacer(Modifier.height((6 * s).dp))
        Text("Open Settings → Catalogs to connect Nuvio and fill the carousel.", color = Color.White.copy(alpha = 0.55f), fontSize = (12 * s).sp)
        Spacer(Modifier.height((14 * s).dp))
        Text(
            "Open Catalogs",
            color = if (focused) Color.Black else Color.White,
            fontSize = (12 * s).sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .focusRequester(heroFocus)
                .focusProperties { down = firstTile }
                .onFocusChanged { focused = it.isFocused }
                .tvPress(onClick = onCatalogs)
                .clip(RoundedCornerShape(50))
                .background(if (focused) Color.White.copy(alpha = 0.95f) else Color.White.copy(alpha = 0.14f))
                .padding(horizontal = (14 * s).dp, vertical = (6 * s).dp),
        )
    }
}

@Composable
private fun HeroInfo(
    item: HeroItem, app: GlassApp, s: Float, focused: Boolean, heroFocus: FocusRequester, firstTile: FocusRequester,
    onFocus: (Boolean) -> Unit, onPrev: () -> Unit, onNext: () -> Unit, onOpen: () -> Unit,
    modifier: Modifier,
) {
    val settings = app.settings
    val ms = settings.transitionMs
    var heroDown by remember { mutableStateOf(false) }
    // The block itself is the focus target (invisible): Left/Right change slide, OK opens the title.
    Box(
        modifier
            .padding(start = (48 * s).dp, top = (96 * s).dp)
            .width((440 * s).dp)
            .focusRequester(heroFocus)
            .focusProperties { down = firstTile }
            .onKeyEvent { ev ->
                when {
                    ev.key == Key.DirectionRight && ev.type == KeyEventType.KeyDown -> { onNext(); true }
                    ev.key == Key.DirectionLeft && ev.type == KeyEventType.KeyDown -> { onPrev(); true }
                    ev.isSelect -> {
                        if (ev.type == KeyEventType.KeyDown && ev.nativeKeyEvent.repeatCount == 0) heroDown = true
                        if (ev.type == KeyEventType.KeyUp && heroDown) { heroDown = false; onOpen() }
                        true
                    }
                    else -> false
                }
            }
            .onFocusChanged { onFocus(it.isFocused) }
            .focusable()
            .padding((8 * s).dp),
    ) {
        AnimatedContent(
            targetState = item,
            transitionSpec = { fadeIn(tween(ms)) togetherWith fadeOut(tween((ms * 0.4f).toInt())) },
            contentKey = { it.key },
            label = "hero",
        ) { it ->
            val m = settings.metaScale
            var headW by remember { mutableIntStateOf(0) }
            val density = LocalDensity.current
            Column {
              Column(Modifier.onSizeChanged { sz -> headW = sz.width }) {
                Title(it, settings.titleMode, settings.titleScale, s)
                if (settings.showMeta) {
                    Spacer(Modifier.height((16 * s).dp))
                    Row(horizontalArrangement = Arrangement.spacedBy((6 * s * m).dp), verticalAlignment = Alignment.CenterVertically) {
                        if (it.rating.isNotBlank()) Chip("★ ${it.rating}", s * m)
                        if (it.year.isNotBlank()) Chip(it.year, s * m)
                        if (it.platform.isNotBlank()) Chip(it.platform, s * m)
                    }
                }
                if (it.progress >= 0f && settings.cwProgress) {
                    Spacer(Modifier.height((10 * s).dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            listOfNotNull("Continue", it.sub.takeIf { x -> x.isNotBlank() }, "${(it.progress * 100).toInt()}%").joinToString("  ·  "),
                            color = Color.White, fontSize = (10.5f * s * m).sp, fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.width((8 * s).dp))
                        Box(Modifier.width((90 * s).dp).height((3 * s).dp).clipR((2 * s).dp).background(Color.White.copy(alpha = 0.25f))) {
                            Box(Modifier.fillMaxWidth(it.progress.coerceIn(0f, 1f)).height((3 * s).dp).background(Color.White))
                        }
                    }
                }
                if (settings.showGenres) {
                    val kind = if (it.type.equals("movie", true)) "Movie" else "Series"
                    Spacer(Modifier.height((8 * s).dp))
                    Text(
                        (listOf(kind) + it.genres).joinToString("  ·  "),
                        color = AppleSecondary, fontWeight = FontWeight.Medium, fontSize = (11 * s * settings.genresScale).sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
              }
                if (settings.showSynopsis && it.overview.isNotBlank()) {
                    AnimatedVisibility(visible = !settings.synopsisOnFocus || focused, enter = fadeIn(tween(200)), exit = fadeOut(tween(150))) {
                        Column {
                            Spacer(Modifier.height((10 * s).dp))
                            Text(
                                it.overview, color = AppleSecondary, fontWeight = FontWeight.Medium,
                                fontSize = (11.5f * s * settings.synopsisScale).sp, lineHeight = (16 * s * settings.synopsisScale).sp,
                                maxLines = settings.synopsisLines, overflow = TextOverflow.Ellipsis,
                                modifier = if (headW > 0) Modifier.width(with(density) { headW.toDp() }) else Modifier,
                            )
                        }
                    }
                }
                // Simple Play button: only shows while the carousel is selected.
                AnimatedVisibility(visible = focused, enter = fadeIn(tween(180)), exit = fadeOut(tween(140))) {
                    Column {
                        Spacer(Modifier.height((14 * s).dp))
                        Row(
                            Modifier.clipR((settings.cornerRadius * 0.7f * s).dp).background(Color.White.copy(alpha = 0.94f))
                                .padding(horizontal = (16 * s).dp, vertical = (7 * s).dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("▶", color = Color.Black, fontSize = (11 * s).sp)
                            Spacer(Modifier.width((7 * s).dp))
                            Text("Play", color = Color.Black, fontSize = (12.5f * s).sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Logos come in every shape: very wide word marks and almost square stacked ones. Sizing them by a
 * fixed height made wide logos tiny and tall ones huge, so every logo gets the same visual *area*,
 * then is capped in width and height.
 */
@Composable
private fun Title(item: HeroItem, mode: String, scale: Float, s: Float) {
    if (mode == "hidden") return
    @Composable fun asText() = Text(
        item.name, color = Color.White, fontSize = (30 * s * scale).sp, fontWeight = FontWeight.Light,
        letterSpacing = (4 * scale).sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
    )
    if (mode == "text" || item.logo.isBlank()) { asText(); return }
    val ctx = LocalContext.current
    val pinnedLogo = GlassCache.pinnedLogo[item.logo]
    val req = remember(item.logo) { imgReq(ctx, item.logo, 1000, 400) }
    var ratio by remember(item.logo) { mutableStateOf<Float?>(null) }
    var failed by remember(item.logo) { mutableStateOf(false) }
    if (failed) { asText(); return }
    val r = (ratio ?: 3f).coerceIn(0.6f, 8f)
    val baseH = 70f * s * scale
    val area = baseH * baseH * 3.2f
    var h = sqrt(area / r)
    var w = h * r
    val maxW = 420f * s * scale
    val maxH = 118f * s * scale
    if (w > maxW) { w = maxW; h = w / r }
    if (h > maxH) { h = maxH; w = h * r }
    if (pinnedLogo != null) {
        val pr = (pinnedLogo.width.toFloat() / pinnedLogo.height).coerceIn(0.6f, 8f)
        var ph = sqrt(area / pr); var pw = ph * pr
        if (pw > maxW) { pw = maxW; ph = pw / pr }
        if (ph > maxH) { ph = maxH; pw = ph * pr }
        Image(pinnedLogo, item.name, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart, modifier = Modifier.size(pw.dp, ph.dp))
        return
    }
    AsyncImage(
        model = req, contentDescription = item.name, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
        onSuccess = { st ->
            val sz = st.painter.intrinsicSize
            if (sz.width > 0f && sz.height > 0f) ratio = sz.width / sz.height
        },
        onError = { failed = true },
        modifier = Modifier.size(w.dp, h.dp).alpha(if (ratio == null) 0f else 1f),
    )
}

@Composable
private fun Chip(text: String, s: Float) {
    Text(
        text, color = Color.White.copy(alpha = 0.92f), fontSize = (10.5f * s).sp,
        modifier = Modifier.border(1.dp, Color.White.copy(alpha = 0.55f), RoundedCornerShape((3 * s).dp))
            .padding(horizontal = (6 * s).dp, vertical = (1.5f * s).dp),
    )
}

@Composable
private fun Dots(total: Int, current: Int, mode: String, max: Int, s: Float, modifier: Modifier) {
    Box(
        modifier.clipR((8 * s).dp).background(Color.Black.copy(alpha = 0.34f))
            .padding(horizontal = (8 * s).dp, vertical = (5 * s).dp),
    ) {
        if (mode == "progress") {
            Box(Modifier.width((120 * s).dp).height((3 * s).dp).clipR((2 * s).dp).background(Color.White.copy(alpha = 0.25f))) {
                Box(Modifier.fillMaxWidth(((current + 1f) / total).coerceIn(0f, 1f)).height((3 * s).dp).clipR((2 * s).dp).background(Color.White))
            }
        } else {
            val page = current / max
            val start = page * max
            val count = minOf(max, total - start)
            Row(horizontalArrangement = Arrangement.spacedBy((4.5f * s).dp), verticalAlignment = Alignment.CenterVertically) {
                for (i in 0 until count) {
                    val active = start + i == current
                    val w by animateDpAsState(if (active) (17 * s).dp else (4.5f * s).dp, spring(Spring.DampingRatioMediumBouncy, 500f), label = "dot")
                    Box(Modifier.width(w).height((4.5f * s).dp).clipR((3 * s).dp).background(Color.White.copy(alpha = if (active) 1f else 0.45f)))
                }
            }
        }
    }
}

// ───────────────────────────── top bar ─────────────────────────────

@Composable
private fun TopBar(app: GlassApp, s: Float, onSettings: () -> Unit, modifier: Modifier) {
    val settings = app.settings
    val time by produceState(clockText(settings.clock24h), settings.clock24h) {
        while (true) { value = clockText(settings.clock24h); delay(10_000) }
    }
    var barFocused by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(true) }
    val tick = app.interaction.longValue
    LaunchedEffect(tick, barFocused, settings.topAutoHide, settings.topHideSec) {
        visible = true
        if (settings.topAutoHide && !barFocused) { delay(settings.topHideSec * 1000L); visible = false }
    }
    val a by animateFloatAsState(if (visible || barFocused) 1f else 0f, tween(350), label = "topAlpha")
    val date by produceState(dateText(), Unit) { while (true) { value = dateText(); delay(60_000) } }
    val wx by app.weather.now.collectAsState()
    val shade = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.5f), blurRadius = 8f))
    Row(
        modifier.padding(top = (18 * s).dp, start = (56 * s).dp, end = (34 * s).dp).graphicsLayer { alpha = a },
        verticalAlignment = Alignment.Top,
    ) {
        val right = settings.clockPos == "right"
        // Apple TV: time and date, top left.
        if (settings.showClock && !right) Column {
            Text(time, color = Color.White, fontSize = (19 * s).sp, fontWeight = FontWeight.SemiBold, style = shade)
            if (settings.showDate) Text(date, color = Color.White.copy(alpha = 0.75f), fontSize = (8.5f * s).sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = (0.6f * s).sp, style = shade)
        }
        Spacer(Modifier.weight(1f))
        @Composable fun weatherRow() {
            val w = wx
            if (w != null && settings.weatherMode != "off") Row(verticalAlignment = Alignment.CenterVertically) {
                WeatherIcon(w.icon, Modifier.size((15 * s).dp))
                Spacer(Modifier.width((5 * s).dp))
                val parts = buildList {
                    add(w.temp(settings.weatherF))
                    if (settings.weatherMode == "condition" || settings.weatherMode == "all") w.condition.takeIf { it.isNotBlank() }?.let { add(it) }
                    if (settings.weatherMode == "city" || settings.weatherMode == "all") add(w.city)
                }
                Text(parts.joinToString("  ·  "), color = Color.White.copy(alpha = 0.9f), fontSize = (10 * s).sp, fontWeight = FontWeight.Medium, style = shade)
            }
        }
        if (right) Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy((4 * s).dp)) {
            // Clock next to Settings, weather under it.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((10 * s).dp)) {
                if (settings.showClock) Column(horizontalAlignment = Alignment.End) {
                    Text(time, color = Color.White, fontSize = (17 * s).sp, fontWeight = FontWeight.SemiBold, style = shade)
                    if (settings.showDate) Text(date, color = Color.White.copy(alpha = 0.75f), fontSize = (8 * s).sp,
                        fontWeight = FontWeight.SemiBold, letterSpacing = (0.6f * s).sp, style = shade)
                }
                UpdateDot(app, s)
                GearButton(s, onSettings) { barFocused = it }
            }
            weatherRow()
        } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((10 * s).dp)) {
            weatherRow()
            UpdateDot(app, s)
            GearButton(s, onSettings) { barFocused = it }
        }
    }
}

/** A small pill when a new version is on GitHub (details and install in Settings → About). */
@Composable
private fun UpdateDot(app: GlassApp, s: Float) {
    val r by app.updater.available.collectAsState()
    val rel = r ?: return
    Text(
        "Update ${rel.version}", color = Color.Black, fontSize = (8.5f * s).sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.92f))
            .padding(horizontal = (7 * s).dp, vertical = (2 * s).dp),
    )
}

/** One-time card when an update is found: tvOS-style banner, top centre, for a few seconds. */
@Composable
fun UpdateBanner(app: GlassApp, s: Float, modifier: Modifier) {
    val r by app.updater.available.collectAsState()
    var shownFor by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(r?.version) {
        val v = r?.version ?: return@LaunchedEffect
        if (v == shownFor) return@LaunchedEffect
        shownFor = v; visible = true; delay(9000); visible = false
    }
    AnimatedVisibility(visible, modifier, enter = fadeIn(tween(300)), exit = fadeOut(tween(400))) {
        GlassPanel(Modifier.padding(top = (16 * s).dp), (16 * s).dp, style = app.settings.panelStyle, darken = 0.55f) {
            Column(Modifier.padding(horizontal = (16 * s).dp, vertical = (10 * s).dp)) {
                Text("Glassio ${r?.version} is available", color = Color.White, fontSize = (12 * s).sp, fontWeight = FontWeight.SemiBold)
                Text("Settings → About → Install update", color = AppleSecondary, fontSize = (10 * s).sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

private fun dateText(): String =
    java.text.SimpleDateFormat("d MMM ''yy", java.util.Locale.US).format(java.util.Date()).uppercase()

@Composable
private fun GearButton(s: Float, onClick: () -> Unit, onFocus: (Boolean) -> Unit) {
    // tvOS 26 top-right button: a small glass circle with the gearshape glyph; selected it turns white.
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.18f else 1f, spring(0.6f, 450f), label = "gear")
    val lit by animateFloatAsState(if (focused) 1f else 0f, tween(160), label = "gearLit")
    Box(
        Modifier.size((20 * s).dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
            .tvPress(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        SlidersGlyph(Modifier.fillMaxSize(), Color.White, lit)
    }
}

/** Settings glyph: three sliders in a rounded square. */
@Composable
private fun SlidersGlyph(modifier: Modifier, outline: Color, lit: Float = 0f) {
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.minDimension
        val sw = w * 0.085f
        val st = androidx.compose.ui.graphics.drawscope.Stroke(width = sw, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        val tl = androidx.compose.ui.geometry.Offset(sw / 2, sw / 2)
        val sz = androidx.compose.ui.geometry.Size(w - sw, w - sw)
        val cr = androidx.compose.ui.geometry.CornerRadius(w * 0.26f)
        // Selected: the square fills white and the sliders turn dark.
        if (lit > 0.01f) drawRoundRect(Color.White.copy(alpha = lit), topLeft = tl, size = sz, cornerRadius = cr)
        val color = androidx.compose.ui.graphics.lerp(outline, Color(0xFF1C1C1E), lit)
        drawRoundRect(androidx.compose.ui.graphics.lerp(outline.copy(alpha = 0.9f), Color.White, lit), topLeft = tl, size = sz, cornerRadius = cr, style = st)
        val knobs = floatArrayOf(0.40f, 0.62f, 0.40f)
        for (i in 0..2) {
            val y = w * (0.30f + i * 0.20f)
            val kx = w * knobs[i]; val kr = w * 0.075f
            // The track stops at the knob, so the knob reads as a ring.
            drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.24f, y), androidx.compose.ui.geometry.Offset(kx - kr, y), sw * 0.9f, androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(color, androidx.compose.ui.geometry.Offset(kx + kr, y), androidx.compose.ui.geometry.Offset(w * 0.76f, y), sw * 0.9f, androidx.compose.ui.graphics.StrokeCap.Round)
            drawCircle(color, w * 0.075f, androidx.compose.ui.geometry.Offset(kx, y), style = androidx.compose.ui.graphics.drawscope.Stroke(width = sw * 0.9f))
        }
    }
}

/** SF Symbols "gearshape.fill" style: a toothed wheel with a hole. */
@Composable
private fun GearGlyph(modifier: Modifier, color: Color) {
    androidx.compose.foundation.Canvas(modifier) {
        val c = androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2)
        val ro = size.minDimension / 2; val ri = ro * 0.74f; val teeth = 8
        val p = androidx.compose.ui.graphics.Path()
        for (i in 0 until teeth * 4) {
            val a = (i / (teeth * 4f)) * 2 * Math.PI - Math.PI / 2
            val r = if ((i % 4) == 1 || (i % 4) == 2) ro else ri
            val x = c.x + (kotlin.math.cos(a) * r).toFloat(); val y = c.y + (kotlin.math.sin(a) * r).toFloat()
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
        val hole = androidx.compose.ui.graphics.Path().apply {
            addOval(androidx.compose.ui.geometry.Rect(c, ro * 0.30f))
        }
        drawPath(androidx.compose.ui.graphics.Path.combine(androidx.compose.ui.graphics.PathOperation.Difference, p, hole), color)
    }
}

// ───────────────────────────── dock ─────────────────────────────

@Composable
private fun Dock(
    app: GlassApp, s: Float, metrics: DockMetrics, screenH: Float, radius: Int,
    expanded: Boolean, onExpanded: (Boolean) -> Unit,
    movingPkg: String?, onMoveKey: (KeyEvent) -> Boolean, firstTile: FocusRequester, heroFocus: FocusRequester,
    folderFocus: FocusRequester, tileFocus: HashMap<String, FocusRequester>, onMove: () -> Unit,
    onMenu: (AppEntry) -> Unit, onFolder: () -> Unit, onFolderMenu: () -> Unit, modifier: Modifier,
) {
    val ctx = LocalContext.current
    val settings = app.settings
    val allApps by app.apps.apps.collectAsState()
    val apps = remember(allApps, settings.dockOrder, settings.hiddenApps, settings.showMobileApps) { app.apps.ordered() }
    // null marks where the Apps folder sits.
    val items: List<AppEntry?> = remember(apps, settings.folderPos) {
        val p = settings.folderPos.let { if (it < 0 || it > apps.size) apps.size else it }
        apps.toMutableList<AppEntry?>().apply { add(p, null) }
    }
    val rows = remember(items) { items.chunked(DOCK_COLUMNS) }
    // Move mode: a moved tile is laid out again in its new slot and loses focus, which then fell to the
    // carousel. Keep focus on the app being moved after every step.
    LaunchedEffect(items, movingPkg) {
        val p = movingPkg ?: return@LaunchedEffect
        delay(30)
        runCatching { (if (p == FOLDER) folderFocus else tileFocus[p])?.requestFocus() }
    }
    val fullH = metrics.padTop + rows.size * metrics.cellH + (rows.size - 1).coerceAtLeast(0) * metrics.gap + metrics.padBottom + 8 * s
    val expandedH = minOf(fullH, screenH * 0.72f).coerceAtLeast(metrics.collapsedH)
    val targetH = if (expanded) expandedH else metrics.collapsedH
    val hState = animateDpAsState(targetH.dp, tween(280, easing = FastOutSlowInEasing), label = "dockH")
    // While the dock grows or shrinks its glass is flat (no off-screen layer resized every frame).
    // The height is applied in the layout phase, so the dock is not recomposed on every animation frame.
    val still by remember(targetH) { androidx.compose.runtime.derivedStateOf { kotlin.math.abs(hState.value.value - targetH) < 0.5f } }
    val scroll = rememberScrollState()
    LaunchedEffect(expanded) { if (!expanded) scroll.animateScrollTo(0) }

    GlassPanel(
        modifier = modifier.padding(horizontal = metrics.sideMargin.dp).padding(bottom = metrics.bottomMargin.dp).fillMaxWidth().layout { m, c ->
            val px = hState.value.roundToPx()
            val p = m.measure(c.copy(minHeight = px, maxHeight = px))
            layout(p.width, px) { p.place(0, 0) }
        },
        radius = (radius * s).dp,
        style = settings.dockStyle,
        refract = still,
    ) {
        // Plain rows instead of a lazy grid: about twenty apps, and focus moves in and out predictably.
        Column(
            Modifier.fillMaxWidth().verticalScroll(scroll)
                .padding(start = metrics.padH.dp, end = metrics.padH.dp, top = metrics.padTop.dp, bottom = metrics.padBottom.dp),
            verticalArrangement = Arrangement.spacedBy(metrics.gap.dp),
        ) {
            rows.forEachIndexed { r, row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(metrics.gap.dp)) {
                    row.forEachIndexed { c, e ->
                        val idx = r * DOCK_COLUMNS + c
                        val edges = gridEdges(c, row.size, r, rows.size, allowUp = true)
                        val base = Modifier.weight(1f)
                            .then(if (idx == 0) Modifier.focusRequester(firstTile) else Modifier)
                            .then(if (r == 0) Modifier.focusProperties { up = heroFocus } else Modifier)
                        if (e == null) {
                            key(FOLDER) {
                                FolderTile(
                                    app, s, radius, moving = movingPkg == FOLDER,
                                    modifier = base.focusRequester(folderFocus),
                                    onFocus = { f -> if (f) { onExpanded(r > 0); onMove() } },
                                    onMoveKey = onMoveKey, onClick = onFolder, onLong = onFolderMenu, edges = edges,
                                )
                            }
                        } else key(e.pkg) {
                            DockTile(
                                entry = e, app = app, s = s, radius = radius, moving = movingPkg == e.pkg,
                                modifier = base.focusRequester(tileFocus.getOrPut(e.pkg) { FocusRequester() }),
                                onFocus = { f -> if (f) { onExpanded(r > 0); onMove() } },
                                onMoveKey = onMoveKey,
                                onClick = { app.apps.launchIntent(e)?.let { runCatching { ctx.startActivity(it) } } },
                                onLong = { onMenu(e) }, edges = edges,
                            )
                        }
                    }
                    repeat(DOCK_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** Which arrows lead out of a grid cell (left of the first column, right of the last, below the last row …). */
private fun gridEdges(col: Int, rowSize: Int, row: Int, rows: Int, allowUp: Boolean): Set<androidx.compose.ui.focus.FocusDirection> = buildSet {
    if (col == 0) add(androidx.compose.ui.focus.FocusDirection.Left)
    if (col == rowSize - 1) add(androidx.compose.ui.focus.FocusDirection.Right)
    if (row == rows - 1) add(androidx.compose.ui.focus.FocusDirection.Down)
    if (row == 0 && !allowUp) add(androidx.compose.ui.focus.FocusDirection.Up)
}

/** The plate behind an icon, in the chosen style. */
@Composable
private fun TilePlate(style: String, focused: Boolean, s: Float, radius: Dp, flat: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    val env = LocalGlassEnv.current
    // Inside the folder the page behind is already one blurred image: plain translucent glass looks the
    // same and costs the GPU nothing extra.
    if (flat && (style == "liquid" || style == "clear" || style.startsWith("water"))) {
        // Folder: frosted plate solid enough that transparent icons read cleanly on it.
        Box(
            Modifier.fillMaxSize()
                .background(Color(0xFF2A2C33).copy(alpha = if (focused) 0.92f else 0.78f))
                .border(if (focused) 1.2.dp else 0.6.dp, Color.White.copy(alpha = if (focused) 0.55f else 0.12f), RoundedCornerShape(radius)),
            contentAlignment = Alignment.Center, content = content,
        )
        return
    }
    when (style) {
        "water", "water_still" -> LiquidSurface(
            Modifier.fillMaxSize(), radius = radius, refraction = if (focused) (env.refractionDp * 1.2f).dp else 0.dp, bezel = (16 * s).dp,
            tint = Color.White.copy(alpha = if (focused) 0.05f else 0.02f), sharp = true, wobble = if (focused && style == "water") env.wobble else 0f,
        ) {
            Box(Modifier.matchParentSize().border(0.6.dp, Color.White.copy(alpha = if (focused) 0.30f else 0.16f), RoundedCornerShape(radius)))
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center, content = content)
        }
        "amoled" -> Box(Modifier.fillMaxSize().background(Color.Black).border(0.6.dp, Color.White.copy(alpha = if (focused) 0.5f else 0.10f), RoundedCornerShape(radius)), contentAlignment = Alignment.Center, content = content)
        "bow" -> Box(Modifier.fillMaxSize().background(Color(0xFFF4F4F6)), contentAlignment = Alignment.Center, content = content)
        "clear" -> LiquidSurface(
            Modifier.fillMaxSize(), radius = radius, refraction = 0.dp, bezel = (8 * s).dp,
            tint = Color.White.copy(alpha = if (focused) 0.16f else 0.09f),
        ) {
            Box(Modifier.matchParentSize().border(0.6.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(radius)))
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center, content = content)
        }
        else -> LiquidSurface(
            // Only the selected icon bends light: no extra GPU layer per icon, fast dock and folder.
            Modifier.fillMaxSize(), radius = radius, refraction = if (focused) (env.refractionDp * 0.75f).dp else 0.dp, bezel = (14 * s).dp,
            tint = Color.White.copy(alpha = if (focused) 0.10f else 0.04f),
        ) {
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center, content = content)
        }
    }
}

private val grayscale = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
private val whiteGlyph = ColorFilter.tint(Color.White, BlendMode.SrcIn)
private val blackGlyph = ColorFilter.tint(Color(0xFF111114), BlendMode.SrcIn)

/**
 * tvOS focus colouring: at rest the icon is a white glyph (or a grey banner); selected, it shows its own
 * colours on a light plate. [lit] animates 0..1, both looks are drawn and cross-faded.
 */
@Composable
private fun FocusColorArt(art: Art?, label: String, s: Float, litState: androidx.compose.runtime.State<Float>, dark: Boolean, restOnLight: Boolean = false) {
    if (art == null) {
        Text(label, color = androidx.compose.ui.graphics.lerp(Color.White, Color.Black, litState.value), fontSize = (11 * s).sp, maxLines = 2, textAlign = TextAlign.Center)
        return
    }
    val bm = art.bmp
    val wide = bm.width > bm.height * 1.3f
    val crop = wide && !art.transparent
    val mod = Modifier.fillMaxSize().padding(if (crop) 0.dp else (8 * s).dp)
    val scale = if (crop) ContentScale.Crop else ContentScale.Fit
    // All three layers stay composed; only their alpha changes, read while drawing (no recomposition).
    Box(Modifier.fillMaxSize()) {
        // Light: soft frosted white (not paper white). Dark: near-black glass.
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = litState.value }
            .background(if (dark) Color(0xF0141416) else Color(0xE6E4E4EA)))
        Image(bm, label, colorFilter = if (art.transparent) (if (restOnLight) blackGlyph else whiteGlyph) else grayscale, contentScale = scale,
            modifier = mod.graphicsLayer { alpha = (1f - litState.value) * if (art.transparent) 0.92f else 0.75f })
        Image(if (dark) bm else art.onLight, label, contentScale = scale,
            modifier = mod.graphicsLayer { alpha = litState.value })
    }
}

/** The icon itself; in "black on white" glyph icons turn black and full banners turn grey. */
@Composable
private fun IconArt(art: Art?, label: String, style: String, s: Float) {
    if (art == null) {
        Text(label, color = if (style == "bow") Color.Black else Color.White, fontSize = (11 * s).sp, maxLines = 2, textAlign = TextAlign.Center)
        return
    }
    val bm = art.bmp
    val wide = bm.width > bm.height * 1.3f
    val filter = when {
        style == "bow" && art.transparent -> ColorFilter.tint(Color(0xFF0E0E10), BlendMode.SrcIn)
        style == "bow" -> grayscale
        else -> null
    }
    Image(
        bm, label, colorFilter = filter,
        contentScale = if (wide && !art.transparent) ContentScale.Crop else ContentScale.Fit,
        modifier = Modifier.fillMaxSize().padding(if (wide && !art.transparent) 0.dp else (8 * s).dp),
    )
}

@Composable
private fun TileFrame(
    s: Float, radius: Int, moving: Boolean, label: String, alwaysLabel: Boolean, modifier: Modifier,
    onFocus: (Boolean) -> Unit, onMoveKey: (KeyEvent) -> Boolean, onClick: () -> Unit, onLong: () -> Unit,
    solid: Boolean = false,
    lift: Boolean = true,
    bounce: Boolean = true,
    /** Directions with no neighbour inside the grid: pressing them bounces instead of moving focus. */
    edges: Set<androidx.compose.ui.focus.FocusDirection> = emptySet(),
    plate: @Composable (focused: Boolean, radius: Dp) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    // tvOS rubber band: at the edge, pressing further nudges the icon that way and it springs back.
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val nudge = remember { androidx.compose.animation.core.Animatable(androidx.compose.ui.geometry.Offset.Zero, androidx.compose.ui.geometry.Offset.VectorConverter) }
    val tileScope = rememberCoroutineScope()
    val backOwner = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current
    val keys: (KeyEvent) -> Boolean = keys@{ ev ->
        if (onMoveKey(ev)) return@keys true
        if (ev.key == Key.Back) {
            if (ev.type == KeyEventType.KeyUp) backOwner?.onBackPressedDispatcher?.onBackPressed()
            return@keys true
        }
        if (!bounce) return@keys false
        val dir = when (ev.key) {
            Key.DirectionLeft -> androidx.compose.ui.focus.FocusDirection.Left
            Key.DirectionRight -> androidx.compose.ui.focus.FocusDirection.Right
            Key.DirectionDown -> androidx.compose.ui.focus.FocusDirection.Down
            Key.DirectionUp -> androidx.compose.ui.focus.FocusDirection.Up
            else -> return@keys false
        }
        if (ev.type != KeyEventType.KeyDown) return@keys true
        if (dir in edges || !focusManager.moveFocus(dir)) {
            val d = 9f * s
            val push = when (dir) {
                androidx.compose.ui.focus.FocusDirection.Left -> androidx.compose.ui.geometry.Offset(-d, 0f)
                androidx.compose.ui.focus.FocusDirection.Right -> androidx.compose.ui.geometry.Offset(d, 0f)
                androidx.compose.ui.focus.FocusDirection.Down -> androidx.compose.ui.geometry.Offset(0f, d * 0.7f)
                else -> androidx.compose.ui.geometry.Offset(0f, -d * 0.7f)
            }
            tileScope.launch {
                nudge.animateTo(push, tween(90, easing = androidx.compose.animation.core.LinearOutSlowInEasing))
                nudge.animateTo(androidx.compose.ui.geometry.Offset.Zero, spring(0.45f, 380f))
            }
        }
        true
    }
    // tvOS: the selected icon rises. Done as a layout offset so the glass behind it follows the icon.
    val rise by animateDpAsState(if (focused && lift) (-7 * s).dp else 0.dp, spring(0.7f, 420f), label = "tileRise")
    // Apple TV style: no frame. The icon lifts, grows a little and casts a soft shadow.
    val scale by animateFloatAsState(if (focused) (if (moving) 1.14f else 1.09f) else 1f, spring(0.72f, 520f), label = "tileScale")
    val shadow by animateDpAsState(if (focused && solid) (16 * s).dp else 0.dp, tween(180), label = "tileShadow")
    val r = (radius * 0.75f * s).dp
    val shape = RoundedCornerShape(r)
    Column(
        modifier
            .fillMaxWidth()
            .zIndex(if (focused) 1f else 0f)
            .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
            .tvPress(onLong = onLong, onKey = keys, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1.6f)
                .offset { androidx.compose.ui.unit.IntOffset((nudge.value.x * density).toInt(), rise.roundToPx() + (nudge.value.y * density).toInt()) }
                .graphicsLayer {
                    scaleX = scale; scaleY = scale
                    shadowElevation = shadow.toPx()
                    this.shape = shape; clip = true
                    ambientShadowColor = Color.Black; spotShadowColor = Color.Black
                },
        ) {
            plate(focused, r)
            if (moving) {
                Text(
                    "◀ ▶ ▲ ▼  ·  OK", color = Color.White, fontSize = (9 * s).sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = (4 * s).dp)
                        .clipR((6 * s).dp).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = (6 * s).dp, vertical = (2 * s).dp),
                )
            }
        }
        if (alwaysLabel) {
            Box(Modifier.fillMaxWidth().height((18 * s).dp), contentAlignment = Alignment.Center) {
                Text(
                    label, color = Color.White.copy(alpha = if (focused) 1f else 0.7f), fontSize = (10.5f * s).sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun DockTile(
    entry: AppEntry, app: GlassApp, s: Float, radius: Int, moving: Boolean, modifier: Modifier,
    onFocus: (Boolean) -> Unit, onMoveKey: (KeyEvent) -> Boolean, onClick: () -> Unit, onLong: () -> Unit,
    alwaysLabel: Boolean = false, label: String = entry.label, flat: Boolean = false,
    edges: Set<androidx.compose.ui.focus.FocusDirection> = emptySet(),
) {
    val rev = app.icons.rev.intValue
    val pack = app.settings.iconPack
    val style = app.settings.tileStyle
    val art by produceState(app.apps.cachedArtwork(entry), entry.pkg, pack, rev) { value = app.apps.artwork(entry) }
    val solid = style == "amoled" || style == "bow"
    val colorOnFocus = app.settings.focusColor
    TileFrame(s, radius, moving, label, alwaysLabel, modifier, onFocus, onMoveKey, onClick, onLong, solid, app.settings.tileLift, app.settings.edgeBounce, edges) { focused, r ->
        if (colorOnFocus) {
            val lit = animateFloatAsState(if (focused) 1f else 0f, tween(220), label = "lit")
            TilePlate(style, focused, s, r, flat) { FocusColorArt(art, entry.label, s, lit, app.settings.focusPlate == "dark", restOnLight = style == "bow") }
        } else TilePlate(style, focused, s, r, flat) { IconArt(art, entry.label, style, s) }
    }
}

/** Apple-style folder: a 3x2 preview of the apps inside. */
@Composable
private fun FolderTile(
    app: GlassApp, s: Float, radius: Int, moving: Boolean, modifier: Modifier,
    onFocus: (Boolean) -> Unit, onMoveKey: (KeyEvent) -> Boolean, onClick: () -> Unit, onLong: () -> Unit,
    edges: Set<androidx.compose.ui.focus.FocusDirection> = emptySet(),
) {
    val style = app.settings.tileStyle
    val all by app.apps.apps.collectAsState()
    val preview = remember(all, app.settings.dockOrder) { app.apps.ordered(includeHidden = true).take(6) }
    TileFrame(s, radius, moving, "Apps", false, modifier, onFocus, onMoveKey, onClick, onLong, style == "amoled" || style == "bow", app.settings.tileLift, app.settings.edgeBounce, edges) { focused, r ->
        TilePlate(style, focused, s, r) {
            Column(Modifier.fillMaxSize().padding((7 * s).dp), verticalArrangement = Arrangement.spacedBy((4 * s).dp)) {
                preview.chunked(3).forEach { row ->
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy((4 * s).dp)) {
                        row.forEach { e ->
                            val art by produceState(app.apps.cachedArtwork(e), e.pkg, app.settings.iconPack, app.icons.rev.intValue) { value = app.apps.artwork(e) }
                            Box(Modifier.weight(1f).fillMaxHeight().clipR((4 * s).dp)
                                .background(if (style == "bow") Color(0xFFE2E2E6) else Color.White.copy(alpha = 0.06f))) {
                                IconArt(art, "", style, s * 0.35f)
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/** Every app, hidden ones included, in a glass folder like tvOS. */
@Composable
private fun FolderOverlay(
    app: GlassApp, s: Float, metrics: DockMetrics, radius: Int, screenH: Float,
    moving: String?, onMoveDone: () -> Unit, blocked: Boolean,
    onMenu: (AppEntry) -> Unit, onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val all by app.apps.apps.collectAsState()
    val list = remember(all, app.settings.dockOrder, app.settings.hiddenApps, app.settings.showMobileApps) { app.apps.ordered(includeHidden = true) }
    val hidden = app.settings.hiddenApps.toSet()
    val first = remember { FocusRequester() }
    val focusOf = remember { HashMap<String, FocusRequester>() }
    // While moving, the arrows carry the app through the grid and focus stays on it.
    LaunchedEffect(list, moving) { moving?.let { p -> delay(30); runCatching { focusOf[p]?.requestFocus() } } }
    val onMoveKey: (KeyEvent) -> Boolean = { ev ->
        val p = moving
        if (p == null) false
        else when {
            ev.isSelect -> { if (ev.type == KeyEventType.KeyUp) onMoveDone(); true }
            ev.key == Key.Back -> { if (ev.type == KeyEventType.KeyUp) onMoveDone(); true }
            ev.key == Key.DirectionLeft || ev.key == Key.DirectionRight || ev.key == Key.DirectionUp || ev.key == Key.DirectionDown -> {
                if (ev.type == KeyEventType.KeyDown) app.apps.move(p, when (ev.key) {
                    Key.DirectionLeft -> -1; Key.DirectionRight -> 1
                    Key.DirectionUp -> -DOCK_COLUMNS; else -> DOCK_COLUMNS
                })
                true
            }
            else -> false
        }
    }
    BackHandler(enabled = moving != null, onBack = onMoveDone)
    BackHandler(enabled = moving == null && !blocked, onBack = onClose)
    LaunchedEffect(Unit) { delay(180); runCatching { first.requestFocus() } }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }

    val outer = LocalGlassEnv.current
    val env = outer.copy(bmp = outer.heavy ?: outer.bmp, sharp = outer.heavy ?: outer.sharp, wobble = 0f, prevBmp = null, prevSharp = null, mix = null, veil = null)
    CompositionLocalProvider(LocalGlassEnv provides env) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // Blurred page and its dimming in one pass.
        val bg = env.bmp
        if (bg != null) Image(bg, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            colorFilter = ColorFilter.tint(Color.Black.copy(alpha = 0.45f), BlendMode.SrcAtop))
        else Box(Modifier.fillMaxSize().background(Color(0xFF101014)))
        AnimatedVisibility(shown, enter = fadeIn(tween(160))) {
            Column(Modifier.fillMaxWidth(0.9f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Apps", color = Color.White, fontSize = (26 * s).sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(bottom = (12 * s).dp))
                GlassPanel(Modifier.fillMaxWidth().heightIn(max = (screenH * 0.74f).dp), radius = (radius * 1.6f * s).dp, style = app.settings.dockStyle, refract = false) {
                    Column(
                        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding((20 * s).dp),
                        verticalArrangement = Arrangement.spacedBy((14 * s).dp),
                    ) {
                        val folderRows = list.chunked(DOCK_COLUMNS)
                        val rowsCount = folderRows.size
                        folderRows.forEachIndexed { r, row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(metrics.gap.dp)) {
                                row.forEachIndexed { c, e ->
                                    key(e.pkg) {
                                        val fr = focusOf.getOrPut(e.pkg) { FocusRequester() }
                                        val edges = gridEdges(c, row.size, r, rowsCount, allowUp = false)
                                        DockTile(
                                            entry = e, app = app, s = s, radius = radius, moving = moving == e.pkg,
                                            modifier = Modifier.weight(1f).focusProperties { canFocus = !blocked }.focusRequester(fr).then(if (r == 0 && c == 0) Modifier.focusRequester(first) else Modifier),
                                            onFocus = {}, onMoveKey = onMoveKey,
                                            onClick = { app.apps.launchIntent(e)?.let { runCatching { ctx.startActivity(it) } } },
                                            onLong = { onMenu(e) },
                                            alwaysLabel = true, flat = true,
                                            label = if (e.pkg in hidden) "${e.label} · hidden" else e.label, edges = edges,
                                        )
                                    }
                                }
                                repeat(DOCK_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }
    }
}
}
