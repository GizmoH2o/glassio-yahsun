package com.glasslauncher.tv.data

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf

/**
 * Every launcher option lives here. Reads touch a Compose state counter, so any composable that
 * reads a property recomposes automatically when a setter bumps it.
 */
val MENU_KEYS = listOf("library", "sliders", "gear", "wifi", "theme", "camera", "saver", "background", "recents", "inputs", "trailer")
/** The menu's order on a new TV. */
val MENU_DEFAULT = listOf("inputs", "library", "camera", "sliders", "recents", "gear", "wifi", "saver", "trailer", "theme", "background")
val MENU_NAMES = mapOf("library" to "Library", "sliders" to "Glassio settings", "gear" to "Android settings", "wifi" to "Wi-Fi",
    "theme" to "Themes", "camera" to "Camera", "saver" to "Screensaver", "background" to "Background", "recents" to "Recent apps", "inputs" to "Inputs", "trailer" to "Trailer")

class Settings(private val ctx: Context) {
    private val sp = ctx.getSharedPreferences("glass", Context.MODE_PRIVATE)
    private val rev = mutableIntStateOf(0)

    private fun touch() { rev.intValue = rev.intValue + 1 }
    /** Test only: every stored value (am start --ez dump_prefs true → logcat GlassPrefs). */
    fun dumpAll(): Map<String, *> = sp.all

    /** [keys]' stored values (a profile's own settings, HomeProfiles): each with its type; unset ones left out. */
    fun snapshot(keys: Collection<String>): org.json.JSONObject {
        val out = org.json.JSONObject()
        val all = sp.all
        for (k in keys) when (val v = all[k]) {
            is Boolean -> out.put(k, org.json.JSONObject().put("b", v))
            is Int -> out.put(k, org.json.JSONObject().put("i", v))
            is String -> out.put(k, org.json.JSONObject().put("s", v))
            else -> {}
        }
        return out
    }

    /** [keys] as [j] has them (as snapshot made it); those it hasn't go back to their defaults. */
    fun restore(keys: Collection<String>, j: org.json.JSONObject) {
        val e = sp.edit()
        for (k in keys) {
            val o = j.optJSONObject(k)
            when {
                o == null -> e.remove(k)
                o.has("b") -> e.putBoolean(k, o.getBoolean("b"))
                o.has("i") -> e.putInt(k, o.getInt("i"))
                o.has("s") -> e.putString(k, o.getString("s"))
                else -> e.remove(k)
            }
        }
        e.commit()
        touch()
    }

    private fun b(k: String, d: Boolean): Boolean { rev.intValue; return sp.getBoolean(k, d) }
    private fun i(k: String, d: Int): Int { rev.intValue; return sp.getInt(k, d) }
    private fun f(k: String, d: Float): Float { rev.intValue; return sp.getFloat(k, d) }
    // A value saved as another type (a list of one number set from a computer was stored as a number) is read as
    // text instead of crashing.
    private fun s(k: String, d: String): String { rev.intValue; return runCatching { sp.getString(k, d) ?: d }.getOrElse { sp.all[k]?.toString() ?: d } }
    private fun putB(k: String, v: Boolean) { sp.edit().putBoolean(k, v).apply(); touch() }
    private fun putI(k: String, v: Int) { sp.edit().putInt(k, v).apply(); touch() }
    private fun putF(k: String, v: Float) { sp.edit().putFloat(k, v).apply(); touch() }
    private fun putS(k: String, v: String) { sp.edit().putString(k, v).apply(); touch() }
    private fun list(k: String): List<String> =
        s(k, "").split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    private fun putList(k: String, v: List<String>) = putS(k, v.joinToString("\n"))

    // ---- Hero / carousel
    var heroAutoplay: Boolean get() = b("heroAutoplay", true); set(v) = putB("heroAutoplay", v)
    /** auto | performance | balanced | quality | custom */
    var perfMode: String get() = s("perfMode", "auto"); set(v) = putS("perfMode", v)

    /**
     * What this TV can carry, from its memory and GPU: 2 GB / GLES 2 boxes get "performance",
     * ~4 GB boxes (Google TV Streamer, Shield) "balanced", bigger ones "quality".
     */
    fun deviceTier(): String {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val gb = mi.totalMem / (1024.0 * 1024 * 1024)
        val gles = am.deviceConfigurationInfo.reqGlEsVersion
        val cores = Runtime.getRuntime().availableProcessors()
        return when {
            am.isLowRamDevice || gb < 2.6 || gles < 0x30000 || android.os.Build.VERSION.SDK_INT < 33 -> "performance"
            gb < 5.0 || cores < 6 -> "balanced"
            else -> "quality"
        }
    }
    /** The tier Auto picked, for display. */
    val autoTier: String by lazy { deviceTier() }

    /** The page picture is prepared at this fraction of the screen size (2/3 in Performance: 2.25× less memory and drawing). */
    var pageScale: Float get() = f("pageScale", 1f); set(v) = putF("pageScale", if (v < 0.9f) 0.667f else 1f)
    /** 0 small, 1 medium, 2 large memory caches (pictures kept ready); set by the mode. */
    var memTier: Int get() = i("memTier", 2); set(v) = putI("memTier", v.coerceIn(0, 2))

    /** Glass copies of the backdrop: 480 px (light, default) or 720 px (crisper glass, more memory). */
    var glassRes: Int get() = i("glassRes", 720); set(v) = putI("glassRes", if (v >= 720) 720 else 480)

    /** Which picture a title uses: main | second | best (highest resolution) | addon (the add-on's own). */
    var artChoice: String get() = s("artChoice", "main"); set(v) = putS("artChoice", v)
    /** Per-title picture picked with a long press on the carousel: "key\turl" lines. */
    fun artPick(key: String): String? = list("artPicks").firstOrNull { it.startsWith("$key\t") }?.substringAfter('\t')
    fun setArtPick(key: String, url: String?) {
        val rest = list("artPicks").filterNot { it.startsWith("$key\t") }
        putList("artPicks", if (url == null) rest else (rest + "$key\t$url").takeLast(300))
    }
    fun clearArtPicks() = putList("artPicks", emptyList())
    /** Library, likes and Continue Watching kept the same on every connected account (Connections → Sync). */
    var syncAll: Boolean get() = b("syncAll", false); set(v) = putB("syncAll", v)
    /** Likes already on an account ("trakt", "simkl"), so they're sent once. */
    fun likeSent(acc: String, key: String): Boolean = list("likeSent_$acc").contains(key)
    fun setLikeSent(acc: String, key: String, on: Boolean) {
        val rest = list("likeSent_$acc").filterNot { it == key }
        putList("likeSent_$acc", if (on) (rest + key).takeLast(3000) else rest)
    }
    /** All liked keys. */
    fun likedKeys(): List<String> = list("likes")
    /** Titles you liked (hold OK on Play → Like): their keys. */
    fun liked(key: String): Boolean = list("likes").contains(key)
    fun setLiked(key: String, on: Boolean) {
        val rest = list("likes").filterNot { it == key }
        putList("likes", if (on) (rest + key).takeLast(2000) else rest)
    }
    /** max = original pictures (4K source, sharpest) | high = 1280 px pictures (faster, lighter) */
    var imageQuality: String get() = s("imageQuality", "hd"); set(v) = putS("imageQuality", v)
    /** How many slides ahead are prepared (pictures, logos, glass). */
    var prefetchDepth: Int get() = i("prefetchDepth", 8); set(v) = putI("prefetchDepth", v.coerceIn(1, 8))

    fun applyMode(m: String) {
        // Auto: a weak TV gets the light preset; any other keeps the defaults (made on a 4 GB Google TV Streamer).
        val tier = if (m == "auto") deviceTier().takeIf { it == "performance" } else m
        when (tier) {
            // Performance: pictures at 1280 px and drawn at 2/3 resolution, no rim refraction (no GPU effects at
            // all), a plain crossfade and small caches: for 2 GB TVs and older graphics chips.
            "performance" -> { imageQuality = "high"; prefetchDepth = 2; transitionMs = 300; refraction = 0; heroGallery = false; glassRes = 480
                pageScale = 0.667f; slideAnim = "fade"; memTier = 0; saverQuality = "1080-H264"; cacheMb = 1024 }
            "balanced" -> { imageQuality = "high"; prefetchDepth = 3; transitionMs = 450; refraction = 6; heroGallery = true; galleryShots = 2; glassRes = 480
                pageScale = 1f; memTier = 1 }
            "quality" -> { imageQuality = "max"; prefetchDepth = 6; transitionMs = 700; refraction = 14; heroGallery = true; galleryShots = 3; glassRes = 720
                pageScale = 1f; memTier = 2 }
        }
        perfMode = m
    }

    /** Apple TV style: each title shows a few of its backdrops before the carousel moves on. */
    var heroGallery: Boolean get() = b("heroGallery", true); set(v) = putB("heroGallery", v)
    var galleryShots: Int get() = i("galleryShots", 3); set(v) = putI("galleryShots", v.coerceIn(2, 6))
    var heroIntervalSec: Int get() = i("heroIntervalSec", 9); set(v) = putI("heroIntervalSec", v.coerceIn(3, 60))
    /** logo | text | hidden */
    var titleMode: String get() = s("titleMode", "logo"); set(v) = putS("titleMode", v)
    var titleScale: Float get() = f("titleScale", 0.6f); set(v) = putF("titleScale", v.coerceIn(0.5f, 2.2f))
    var showMeta: Boolean get() = b("showMeta", true); set(v) = putB("showMeta", v)
    var showGenres: Boolean get() = b("showGenres", true); set(v) = putB("showGenres", v)
    var showSynopsis: Boolean get() = b("showSynopsis", true); set(v) = putB("showSynopsis", v)
    /** Synopsis appears only while the carousel is selected. */
    var synopsisOnFocus: Boolean get() = b("synopsisOnFocus", true); set(v) = putB("synopsisOnFocus", v)
    /** Back on the home screen starts the screensaver (like Apple TV). */
    var backSaver: Boolean get() = b("backSaver", true); set(v) = putB("backSaver", v)
    var synopsisScale: Float get() = f("synopsisScale", 1.0f); set(v) = putF("synopsisScale", v.coerceIn(0.6f, 2.0f))
    var synopsisLines: Int get() = i("synopsisLines", 5); set(v) = putI("synopsisLines", v.coerceIn(1, 8))
    /** window (dots on a pill) | liquid (liquid-glass drops, no pill) | progress | hidden */
    var dotsMode: String get() = s("dotsMode", "liquid"); set(v) = putS("dotsMode", v)
    /** apple (Apple TV: the new picture opens up from the side with parallax) | slide (push) | fade */
    var slideAnim: String get() = s("slideAnim", "apple"); set(v) = putS("slideAnim", v)
    // ---- Your own background
    /** carousel (each title's backdrop) | image | video */
    var bgMode: String get() = s("bgMode", "carousel"); set(v) = putS("bgMode", v)
    /** A link or a file path. */
    var bgImage: String get() = s("bgImage", ""); set(v) = putS("bgImage", v)
    var bgVideo: String get() = s("bgVideo", ""); set(v) = putS("bgVideo", v)

    // ---- Menu (from the settings button) and Library
    /** auto (opens when you go up to the settings button) | manual (OK on it) | off (it opens Settings) */
    var menuMode: String get() = s("menuMode", "auto"); set(v) = putS("menuMode", v)
    var menuClock: Boolean get() = b("menuClock", true); set(v) = putB("menuClock", v)
    var menuDate: Boolean get() = b("menuDate", true); set(v) = putB("menuDate", v)
    var menuWeather: Boolean get() = b("menuWeather", true); set(v) = putB("menuWeather", v)
    var menuCity: Boolean get() = b("menuCity", true); set(v) = putB("menuCity", v)
    var menuLibrary: Boolean get() = b("menuLibrary", false); set(v) = putB("menuLibrary", v)
    /** The menu's rows in your order (keys: library, sliders, gear, wifi, theme, camera, saver, background). */
    var menuOrder: List<String>
        get() = (if (sp.contains("menuOrder")) list("menuOrder") else MENU_DEFAULT).filter { it in MENU_KEYS }.let { it + MENU_KEYS.filter { k -> k !in it } }
        set(v) = putList("menuOrder", v)
    var menuAndroid: Boolean get() = b("menuAndroid", true); set(v) = putB("menuAndroid", v)
    var menuWifi: Boolean get() = b("menuWifi", false); set(v) = putB("menuWifi", v)
    /** "Recent apps" in the menu: the app switcher (like holding Home). */
    var menuRecents: Boolean get() = b("menuRecents", true); set(v) = putB("menuRecents", v)
    /** Recent apps through ADB when the TV allows it (exact list); off: the apps Glassio saw on screen, no ADB. */
    var recentsAdb: Boolean get() = b("recentsAdb", true); set(v) = putB("recentsAdb", v)
    // ---- Music (Settings → Music)
    /** Music on: Glassio follows what any music app plays (needs its notification listener, switched on over ADB). */
    var musicOn: Boolean get() = b("musicOn", false); set(v) = putB("musicOn", v)

    // ---- Hue Sync (Philips Hue lights in the colours of what's on screen)
    var hueOn: Boolean get() = b("hueOn", false); set(v) = putB("hueOn", v)
    var hueIp: String get() = s("hueIp", ""); set(v) = putS("hueIp", v)
    var hueUser: String get() = s("hueUser", ""); set(v) = putS("hueUser", v)
    /** Rooms and zones (group ids) whose lights follow the screen. */
    var hueGroups: List<String> get() = list("hueGroups"); set(v) = putList("hueGroups", v)
    /** Single lights (light ids) that follow the screen. */
    var hueLights: List<String> get() = list("hueLights"); set(v) = putList("hueLights", v)
    /** Where the colours come from: "backdrop" (what fills the screen), "poster", "logo". */
    var hueSource: String get() = s("hueSource", "backdrop"); set(v) = putS("hueSource", v)
    /** Videos (your video background, trailers) followed live. */
    var hueVideo: Boolean get() = b("hueVideo", true); set(v) = putB("hueVideo", v)
    var hueVideoSec: Int get() = i("hueVideoSec", 1); set(v) = putI("hueVideoSec", v.coerceIn(0, 10))
    /** A different colour of the picture for each light (true) or the main colour on all. */
    var hueSpread: Boolean get() = b("hueSpread", true); set(v) = putB("hueSpread", v)
    /** 0 Subtle, 1 Natural, 2 Vivid, 3 Maximum. */
    var hueIntensity: Int get() = i("hueIntensity", 1); set(v) = putI("hueIntensity", v.coerceIn(0, 3))
    /** Brightness, % of full. */
    var hueBri: Int get() = i("hueBri", 70); set(v) = putI("hueBri", v.coerceIn(5, 100))
    /** Darker pictures make the lights dimmer. */
    var hueFollowBri: Boolean get() = b("hueFollowBri", false); set(v) = putB("hueFollowBri", v)
    /** How long a change of colour takes. */
    var hueSpeedMs: Int get() = i("hueSpeedMs", 400); set(v) = putI("hueSpeedMs", v.coerceIn(0, 10_000))
    /** Each light's part of the screen ("id:zone,…"; not listed = automatic). */
    var hueZones: String get() = s("hueZones", ""); set(v) = putS("hueZones", v)
    /** How lights of other makes take their colour ("id:hs|xy,…"; not listed = hue and saturation). */
    var hueMethods: String get() = s("hueMethods", ""); set(v) = putS("hueMethods", v)
    /** On the screensaver too (its videos). */
    var hueSaver: Boolean get() = b("hueSaver", true); set(v) = putB("hueSaver", v)
    /** The lights take the glow's colour of the selected app in the dock / poster in the Library. */
    var hueApps: Boolean get() = b("hueApps", false); set(v) = putB("hueApps", v)
    var huePosters: Boolean get() = b("huePosters", false); set(v) = putB("huePosters", v)
    /** How bright the lights are with a selected app's / poster's colour (their own setting, not the screen's). */
    var hueAppsBri: Int get() = i("hueAppsBri", 70); set(v) = putI("hueAppsBri", v.coerceIn(5, 100))
    var huePostersBri: Int get() = i("huePostersBri", 70); set(v) = putI("huePostersBri", v.coerceIn(5, 100))
    /** Opening an app or a film: "restore" (as they were), "dim", "off", "keep". */
    var hueLeave: String get() = s("hueLeave", "restore"); set(v) = putS("hueLeave", v)
    /** The TV turning off: "restore", "off", "keep". */
    var hueOff: String get() = s("hueOff", "restore"); set(v) = putS("hueOff", v)
    /** ▶ in the menu turns it into the player. */
    var musicMenu: Boolean get() = b("musicMenu", true); set(v) = putB("musicMenu", v)
    /** A Music icon in the dock (opens the menu's player). */
    var musicDock: Boolean get() = b("musicDock", true); set(v) = putB("musicDock", v)
    /** Music mode: while a song plays, the home screen's background becomes the player (whatever background you chose). */
    var musicMode: Boolean get() = b("musicMode", false); set(v) = putB("musicMode", v)
    var musicStageScale: Float get() = f("musicStageScale", 0.9f); set(v) = putF("musicStageScale", v.coerceIn(0.7f, 1.4f))
    /** theme | white | black: the player's buttons (the other way round when selected). */
    var musicButtons: String get() = s("musicButtons", "white"); set(v) = putS("musicButtons", v)
    /** The island on the screensaver while music plays. */
    var musicIsland: Boolean get() = b("musicIsland", true); set(v) = putB("musicIsland", v)
    var islandStyle: String get() = s("islandStyle", "theme"); set(v) = putS("islandStyle", v)
    /** Joined to the top of the screen (like a MacBook's notch) instead of floating below it. */
    var islandAttached: Boolean get() = b("islandAttached", false); set(v) = putB("islandAttached", v)
    var islandScale: Float get() = f("islandScale", 0.6f); set(v) = putF("islandScale", v.coerceIn(0.3f, 1.8f))
    var islandOffset: Float get() = f("islandOffset", 0f); set(v) = putF("islandOffset", v.coerceIn(0f, 120f))
    var islandArt: Boolean get() = b("islandArt", true); set(v) = putB("islandArt", v)
    var islandTitle: Boolean get() = b("islandTitle", true); set(v) = putB("islandTitle", v)
    var islandProgress: Boolean get() = b("islandProgress", true); set(v) = putB("islandProgress", v)
    /** art | white: the colour of the island's bars. */
    var islandBars: String get() = s("islandBars", "white"); set(v) = putS("islandBars", v)
    /** "Inputs" in the menu: the TV's HDMI inputs and what is plugged into them. */
    var menuInputs: Boolean get() = b("menuInputs", false); set(v) = putB("menuInputs", v)
    /** The inputs as icons in the dock too (moved, hidden and given icons like apps). */
    var inputsDock: Boolean get() = b("inputsDock", true); set(v) = putB("inputsDock", v)
    /** Apps & icons › Inputs in their own row: Home shows the TV's inputs in a row of their own, apart from the apps. */
    var inputsRow: Boolean get() = b("inputsRow", true); set(v) = putB("inputsRow", v)
    var menuThemes: Boolean get() = b("menuThemes", true); set(v) = putB("menuThemes", v)
    var menuBackground: Boolean get() = b("menuBackground", true); set(v) = putB("menuBackground", v)
    /** Size of the menu (0.8 – 1.3). */
    var menuScale: Float get() = f("menuScale", 1.0f); set(v) = putF("menuScale", v.coerceIn(0.8f, 1.3f))
    /** The TV's numbers under the weather in the menu. */
    var menuSys: Boolean get() = b("menuSys", true); set(v) = putB("menuSys", v)
    var sysIp: Boolean get() = b("sysIp", false); set(v) = putB("sysIp", v)
    var sysCpu: Boolean get() = b("sysCpu", false); set(v) = putB("sysCpu", v)
    var sysGpu: Boolean get() = b("sysGpu", false); set(v) = putB("sysGpu", v)
    var sysRam: Boolean get() = b("sysRam", true); set(v) = putB("sysRam", v)
    var sysStorage: Boolean get() = b("sysStorage", true); set(v) = putB("sysStorage", v)
    /** Cameras, one per line: "Name|rtsp://…" (RTSP, HLS or a plain video link). */
    var cameras: List<String> get() = list("cameras"); set(v) = putList("cameras", v)
    var menuCamera: Boolean get() = b("menuCamera", false); set(v) = putB("menuCamera", v)
    var menuTrailer: Boolean get() = b("menuTrailer", true); set(v) = putB("menuTrailer", v)
    var menuSaver: Boolean get() = b("menuSaver", true); set(v) = putB("menuSaver", v)
    /** Look of the menu: same choices as the dock (set by Themes). */
    var menuStyle: String get() = s("menuStyle", "water_still"); set(v) = putS("menuStyle", v)
    /** The + button next to Play (save to Library). */
    var heroPlus: Boolean get() = b("heroPlus", true); set(v) = putB("heroPlus", v)
    var libraryTrakt: Boolean get() = b("libraryTrakt", true); set(v) = putB("libraryTrakt", v)
    /** The Nuvio account's Library (Nuvio Legacy, official Nuvio and forks that sync with the account). */
    /** Live TV and Following in the menu. */
    var menuLive: Boolean get() = b("menuLive", true); set(v) = putB("menuLive", v)
    var menuFollowing: Boolean get() = b("menuFollowing", true); set(v) = putB("menuFollowing", v)
    var searchEverything: Boolean get() = b("searchEverything", true); set(v) = putB("searchEverything", v)
    /** Search, with nothing typed: mood and time filters across everything the app knows of. */
    var moodFilters: Boolean get() = b("moodFilters", true); set(v) = putB("moodFilters", v)
    var libraryNuvio: Boolean get() = b("libraryNuvio", true); set(v) = putB("libraryNuvio", v)
    /** Catalogs also come from the add-ons installed on the Nuvio account (merged with the TV's own, never twice). */
    var catalogsNuvio: Boolean get() = b("catalogsNuvio", false); set(v) = putB("catalogsNuvio", v)
    /** The + button also saves to the Nuvio account's Library. */
    var libraryToNuvio: Boolean get() = b("libraryToNuvio", true); set(v) = putB("libraryToNuvio", v)
    /** The Stremio account's Library (the Stremio apps' own). */
    var libraryStremio: Boolean get() = b("libraryStremio", true); set(v) = putB("libraryStremio", v)
    /** The + button also saves to the Stremio account's Library. */
    var libraryToStremio: Boolean get() = b("libraryToStremio", true); set(v) = putB("libraryToStremio", v)
    /** Catalogs also come from the add-ons installed on the Stremio account (merged with the others, never twice). */
    var catalogsStremio: Boolean get() = b("catalogsStremio", false); set(v) = putB("catalogsStremio", v)
    var libraryToTrakt: Boolean get() = b("libraryToTrakt", true); set(v) = putB("libraryToTrakt", v)
    var libraryTitles: Boolean get() = b("libraryTitles", false); set(v) = putB("libraryTitles", v)
    /** bow (black on white) | amoled | liquid | clear | water_still | water */
    var theme: String get() = s("theme", "water_still"); set(v) = putS("theme", v)

    /** One choice for the dock, the icons and the menu (Settings keep their own look). Black ones keep the dock clear (matte). */
    fun applyTheme(t: String) {
        when (t) {
            "bow" -> { dockStyle = "clear"; tileStyle = "bow"; menuStyle = "bow" }
            "amoled" -> { dockStyle = "clear"; tileStyle = "amoled"; menuStyle = "amoled" }
            else -> { dockStyle = t; tileStyle = t; menuStyle = t }
        }
        theme = t
    }

    /** Buttons under the title: apple (Play + round Next, like Apple TV) | play (Play only, when selected) | hidden */
    var heroButtons: String get() = s("heroButtons", "play"); set(v) = putS("heroButtons", v)
    var dotsMax: Int get() = i("dotsMax", 11); set(v) = putI("dotsMax", v.coerceIn(3, 20))
    /** Trailers: a Trailer button next to Play (Apple TV buttons). */
    var trailerButton: Boolean get() = b("trailerButton", true); set(v) = putB("trailerButton", v)
    /** cinema (fills the screen) | original (its own shape) | popup (a small glass window by the buttons). */
    var trailerSize: String get() = s("trailerSize", "popup"); set(v) = putS("trailerSize", v)
    var trailerMute: Boolean get() = b("trailerMute", false); set(v) = putB("trailerMute", v)
    var trailerSubs: Boolean get() = b("trailerSubs", true); set(v) = putB("trailerSubs", v)
    /** While a trailer fills the page, the dock and the page dots step aside (never with the trailer in its window). */
    var trailerHideDock: Boolean get() = b("trailerHideDock", true); set(v) = putB("trailerHideDock", v)
    /** "auto": H.264 up to 1080p (720p on small TVs); "full": the best this TV plays, up to its screen (4K: VP9 / AV1). */
    var trailerQuality: String get() = s("trailerQuality", "auto"); set(v) = putS("trailerQuality", v)
    var trailerSubLang: String get() = s("trailerSubLang", "ar"); set(v) = putS("trailerSubLang", v)
    /** Seconds on a title before its trailer starts by itself (0 = only when asked). */
    var trailerAutoSec: Int get() = i("trailerAutoSec", 5); set(v) = putI("trailerAutoSec", v.coerceIn(0, 60))
    var trailerPopupScale: Float get() = f("trailerPopupScale", 1.4f); set(v) = putF("trailerPopupScale", v.coerceIn(0.6f, 2f))
    /** Size of the carousel's dots bar (1 = as designed). */
    var dotsScale: Float get() = f("dotsScale", 0.6f); set(v) = putF("dotsScale", v.coerceIn(0.5f, 1.5f))
    var perCatalog: Int get() = i("perCatalog", 40); set(v) = putI("perCatalog", v.coerceIn(5, 500))
    /** 0 = unlimited */
    var maxItems: Int get() = i("maxItems", 0); set(v) = putI("maxItems", v.coerceAtLeast(0))
    var shuffle: Boolean get() = b("shuffle", false); set(v) = putB("shuffle", v)
    /** Surprise me: a random mix from all the catalogs you chose (different pages each time), fetched again every [surpriseMin]. */
    var surprise: Boolean get() = b("surprise", true); set(v) = putB("surprise", v)
    var surpriseMin: Int get() = i("surpriseMin", 30); set(v) = putI("surpriseMin", v.coerceIn(10, 1440))
    /** Apple style: the title's logo all in white. */
    var logoWhite: Boolean get() = b("logoWhite", false); set(v) = putB("logoWhite", v)
    /** Picture cache on the TV, in MB (applies after Glassio restarts). */
    var cacheMb: Int get() = i("cacheMb", 768); set(v) = putI("cacheMb", v.coerceIn(256, 8192))
    /** Language of synopses and titles from TMDB: en | ar | … */
    var descLang: String get() = s("descLang", "en"); set(v) = putS("descLang", v)
    var tmdbToken: String get() = s("tmdbToken", ""); set(v) = putS("tmdbToken", v.trim())

    var transitionMs: Int get() = i("transitionMs", 700); set(v) = putI("transitionMs", v.coerceIn(100, 2000))
    var dotsAutoHide: Boolean get() = b("dotsAutoHide", true); set(v) = putB("dotsAutoHide", v)

    // ---- Clock / dock look
    var topAutoHide: Boolean get() = b("topAutoHide", true); set(v) = putB("topAutoHide", v)
    var topHideSec: Int get() = i("topHideSec", 2); set(v) = putI("topHideSec", v.coerceIn(2, 30))
    var cornerRadius: Int get() = i("cornerRadius", 18); set(v) = putI("cornerRadius", v.coerceIn(0, 40))
    /** Index of the Apps folder among the dock apps; -1 = after the last app. */
    var folderPos: Int get() = i("folderPos", -1); set(v) = putI("folderPos", v)
    /** The home screen's library rows (Airing this week, Movies, Series): both | nuvio | stremio, with what's saved here. */
    var homeSource: String get() = s("homeSource", "both"); set(v) = putS("homeSource", v)
    /** The home screen's "Airing this week" row: new episodes of the series in your library. */
    var homeAiring: Boolean get() = b("homeAiring", true); set(v) = putB("homeAiring", v)
    /** The home screen's "Movies in your library" and "Series in your library" rows. */
    var homeMovies: Boolean get() = b("homeMovies", true); set(v) = putB("homeMovies", v)
    var homeSeries: Boolean get() = b("homeSeries", true); set(v) = putB("homeSeries", v)
    /** Bumped when Night mode changes (it's in a file): its row reads again. */
    var nightRev: Int get() = i("nightRev", 0); set(v) = putI("nightRev", v)
    /** Updates offer test builds too. */
    var betaUpdates: Boolean get() = b("betaUpdates", false); set(v) = putB("betaUpdates", v)
    /** The code the weekly backup is locked with ("": no weekly backup). */
    var backupAutoCode: String get() = s("backupAutoCode", ""); set(v) = putS("backupAutoCode", v)
    /** Backup to WebDAV (WebDav): its folder's address, the username and the (app) password; when one was last sent. */
    var davUrl: String get() = s("davUrl", ""); set(v) = putS("davUrl", v.trim())
    var davUser: String get() = s("davUser", ""); set(v) = putS("davUser", v.trim())
    var davPass: String get() = s("davPass", ""); set(v) = putS("davPass", v)
    /** Sync with other TVs at home (LanSync), which reads it in its own name. */
    var syncOn: Boolean get() = b("syncOn", false); set(v) = putB("syncOn", v)
    val davLast: Long get() = sp.getLong("davLast", 0L)
    /** The rows of Home in order, by name ("": HomeRows' order). */
    var homeOrder: String get() = s("homeOrder", ""); set(v) = putS("homeOrder", v)
    /** The rows turned off, by name, with commas. */
    var homeHidden: String get() = s("homeHidden", ""); set(v) = putS("homeHidden", v)
    /** The rows' own names: a JSON object, by row. */
    var homeNames: String get() = s("homeNames", "{}"); set(v) = putS("homeNames", v)
    /** Rows of well-known studios' films on Home. */
    var homeCollections: Boolean get() = b("homeCollections", false); set(v) = putB("homeCollections", v)
    /** Your own Home rows (Collections): on or off, and what's in them. */
    var homeMine: Boolean get() = b("homeMine", true); set(v) = putB("homeMine", v)
    var myCollections: String get() = s("myCollections", "[]"); set(v) = putS("myCollections", v)
    /** Home's For You row: picks worked out on the TV alone. */
    var homeForYou: Boolean get() = b("homeForYou", true); set(v) = putB("homeForYou", v)
    /** Home's automatic rows: Because you watched, Quick watches. */
    var homeAuto: Boolean get() = b("homeAuto", true); set(v) = putB("homeAuto", v)
    /** A poster rested on opens up into a wide card (picture, logo, details). */
    var homeExpand: Boolean get() = b("homeExpand", true); set(v) = putB("homeExpand", v)
    /** How many apps a row of the home screen's apps grid holds. */
    var appsPerRow: Int get() = i("appsPerRow", 5); set(v) = putI("appsPerRow", v.coerceIn(3, 8))
    /** The big picture at the top follows the title selected in the rows. */
    var homeFollow: Boolean get() = b("homeFollow", true); set(v) = putB("homeFollow", v)
    /** Continue Watching: row (its own row, Apple TV's) | first (at the start of the carousel) | mixed (among the catalogs) | off */
    var continueMode: String get() = s("continueMode", "row"); set(v) = putS("continueMode", v)
    /** Continue Watching also from the Trakt account (when one is connected). */
    var cwTrakt: Boolean get() = b("cwTrakt", true); set(v) = putB("cwTrakt", v)
    /** Continue Watching keeps only what was watched within this many days (0 = everything). */
    var cwDays: Int get() = i("cwDays", 0); set(v) = putI("cwDays", v)
    /** Home's accent colour (a key of ui.ACCENTS) and the text size of Glassio's screens (a key of ui.TEXT_SIZES). */
    var accent: String get() = s("accent", "red"); set(v) = putS("accent", v)
    /** The big picture: titles said "Not interested" to (keys), Because-you-watched picks, the season's titles first. */
    var heroHidden: List<String> get() = list("heroHidden"); set(v) = putList("heroHidden", v)
    var heroBecause: Boolean get() = b("heroBecause", true); set(v) = putB("heroBecause", v)
    var heroSeasonal: Boolean get() = b("heroSeasonal", true); set(v) = putB("heroSeasonal", v)
    /** Test only: the month the season picks go by (0: this month). */
    var seasonMonth: Int get() = i("seasonMonth", 0); set(v) = putI("seasonMonth", v)
    /** A muted trailer behind the big picture after a few seconds on a title (YouTube, through Cinemeta). */
    /** A followed team's match on now, as a ticker in Home's top bar. */
    var homeTicker: Boolean get() = b("homeTicker", true); set(v) = putB("homeTicker", v)
    /**
     * Home's widgets row, under the apps: the weather's next days, the next calendar events (iCal links: Google
     * Calendar's secret address and the like) and Home Assistant readings (its address, a long-lived token, the entities).
     */
    var widgetsRow: Boolean get() = b("widgetsRow", true); set(v) = putB("widgetsRow", v)
    /** The Today page (the menu's Today, and Your day at the glance row's front in the morning). */
    var todayPage: Boolean get() = b("todayPage", true); set(v) = putB("todayPage", v)
    /** Today opens by itself the first time Home shows in the morning; the day it last did (yyyymmdd). */
    var todayMorning: Boolean get() = b("todayMorning", false); set(v) = putB("todayMorning", v)
    var todayShownDay: Int get() = sp.getInt("todayShownDay", 0); set(v) { sp.edit().putInt("todayShownDay", v).apply() }
    var widgetWeather: Boolean get() = b("widgetWeather", true); set(v) = putB("widgetWeather", v)
    var calLinks: List<String> get() = list("calLinks"); set(v) = putList("calLinks", v.map { it.trim() }.filter { it.isNotBlank() }.distinct())
    var haUrl: String get() = s("haUrl", ""); set(v) = putS("haUrl", v.trim().trimEnd('/'))
    var haToken: String get() = s("haToken", ""); set(v) = putS("haToken", v.trim())
    var haEntities: List<String> get() = list("haEntities"); set(v) = putList("haEntities", v)
    /** Home Assistant's address: the one set, else the webhook's (http://homeassistant.local:8123). */
    val haBase: String get() = haUrl.ifBlank {
        runCatching { java.net.URI(haWebhook).let { u -> if (u.scheme == null || u.host == null) "" else u.scheme + "://" + u.host + (if (u.port > 0) ":" + u.port else "") } }.getOrDefault("")
    }
    /** Look & motion: no lift, slide or zoom; a stronger selection and solid backing behind text. */
    var reduceMotion: Boolean get() = b("reduceMotion", false); set(v) = putB("reduceMotion", v)
    /** Look & motion › Dialog style: "modern" (glass sheets, Glassio's keyboard, glass notices) | "classic". */
    var dialogStyle: String get() = s("dialogStyle", "modern"); set(v) { putS("dialogStyle", v); writeDialogFile() }
    /** Live TV runs in its own process (it doesn't see a change made here): the style in a small file it reads. */
    fun writeDialogFile() { runCatching { java.io.File(ctx.filesDir, "dialog_style").writeText(dialogStyle) } }
    var highContrast: Boolean get() = b("highContrast", false); set(v) = putB("highContrast", v)
    /** Home's dark page picks up a little of the title's colour (off with High contrast). */
    var backdropTint: Boolean get() = b("backdropTint", true); set(v) = putB("backdropTint", v)
    /** OK on a card: "title" opens its title page, "play" plays straight away. */
    var cardOk: String get() = s("cardOk", "title"); set(v) = putS("cardOk", v)
    /** OK on a Continue Watching card: "resume" (in its app: Stremio plays where you left off) | "card" (as OK on a card). */
    var cwOk: String get() = s("cwOk", "resume"); set(v) = putS("cwOk", v)
    /** Live TV's cards for a match: in its teams' colours with their crests and the score, or the show's picture. */
    var matchCards: Boolean get() = b("matchCards", true); set(v) = putB("matchCards", v)
    /** Trailers behind the big picture: auto (off by itself if they keep failing on this TV) | on | off. */
    var trailerMode: String get() = s("trailerMode", if (b("heroTrailers", true)) "auto" else "off"); set(v) = putS("trailerMode", v)
    /** Recommendations (trending and popular rows): auto (with no account or add-on) | always | off. */
    var recsMode: String get() = s("recsMode", "auto"); set(v) = putS("recsMode", v)
    /** What the big picture shows: "all" (the catalogs' titles, and the recommendations when they're on) | "recs" (the recommendations only). */
    var heroSource: String get() = s("heroSource", "all"); set(v) = putS("heroSource", v)
    /** How the big picture changes title: "quick" (the words fade out as the picture crossfades, the new ones fade in as
     *  it ends: under a second, like the streaming apps) | "steps" (words out, the picture changes, then the new words) | "fade" (all at once). */
    var heroChange: String get() = s("heroChange", "quick"); set(v) = putS("heroChange", v)
    /** An open poster card plays its trailer after this many seconds on it; 0: never. */
    var cardTrailerSec: Int get() = i("cardTrailerSec", 0); set(v) = putI("cardTrailerSec", v)
    /** A slow zoom on the still pictures: "off" | "hero" (the big picture) | "cards" (an open poster card) | "both". */
    var slowZoom: String get() = s("slowZoom", "both"); set(v) = putS("slowZoom", v)
    /** Going down the page, the big picture: "away" (scrolls up and off with the page) | "stay" (stays and darkens). */
    var heroScroll: String get() = s("heroScroll", "away"); set(v) = putS("heroScroll", v)
    /** How long on a poster before it opens up, in ms. */
    var cardOpenMs: Int get() = i("cardOpenMs", 300); set(v) = putI("cardOpenMs", v)
    /** How a poster opens: "together" (everything at once, quick) | "staggered" (picture, then the details). */
    var cardOpen: String get() = s("cardOpen", "together"); set(v) = putS("cardOpen", v)
    /** Along a row with a card open, the next one opens straight away (the row stays open). */
    var cardKeepOpen: Boolean get() = b("cardKeepOpen", true); set(v) = putB("cardKeepOpen", v)
    /** Home's motion measured from a streaming app follows the TV's animation speed (HomeMotion); off: its own timings. */
    var homeTvSpeed: Boolean get() = b("homeTvSpeed", false); set(v) = putB("homeTvSpeed", v)
    /** The open card's look: "light" (lighter grey, a thin border) | "dark". */
    var cardStyle: String get() = s("cardStyle", "light"); set(v) = putS("cardStyle", v)
    /** The version whose What's new has been shown, and whether the welcome is done. */
    var seenVersion: String get() = s("seenVersion", ""); set(v) = putS("seenVersion", v)
    var welcomeDone: Boolean get() = b("welcomeDone", false); set(v) = putB("welcomeDone", v)
    /** Kids mode (a kids profile, HomeProfiles): family titles only, these apps only, the PIN to leave. */
    var kidsMode: Boolean get() = b("kidsMode", false); set(v) = putB("kidsMode", v)
    var kidsApps: List<String> get() = list("kidsApps"); set(v) = putList("kidsApps", v)
    /** Home's PIN (4 digits; "" none): leaving Kids mode, and Settings while in it. */
    var homePin: String get() = s("homePin", ""); set(v) = putS("homePin", v)
    /** Who's watching? when Glassio starts, with more than one profile. */
    var askProfile: Boolean get() = b("askProfile", false); set(v) = putB("askProfile", v)
    /** Phone remote: the phone page kept running at a fixed address (net.PhoneRemote), and that address's token. */
    var phoneRemote: Boolean get() = b("phoneRemote", false); set(v) = putB("phoneRemote", v)
    /** The phone page's Live TV section: what's on, the guide, reminders and recordings (LivePhone). */
    var phoneLive: Boolean get() = b("phoneLive", true); set(v) = putB("phoneLive", v)
    /** The phone page's calendar: Add to calendar on a show, and a feed of what's set and the matches you follow (IcsWriter). */
    var phoneCalendar: Boolean get() = b("phoneCalendar", true); set(v) = putB("phoneCalendar", v)
    var phoneToken: String get() = s("phoneToken", ""); set(v) = putS("phoneToken", v)
    var textSize: String get() = s("textSize", "normal"); set(v) = putS("textSize", v)
    /** Continue Watching: series with a new episode out since you watched go first (after Live TV's cards). */
    var cwNewFirst: Boolean get() = b("cwNewFirst", true); set(v) = putB("cwNewFirst", v)
    /** Continue Watching: the next episode takes the place of one you finished. */
    var cwUpNext: Boolean get() = b("cwUpNext", true); set(v) = putB("cwUpNext", v)
    /** Continue Watching: Live TV's past shows you left partway join the row. */
    var cwCatchup: Boolean get() = b("cwCatchup", true); set(v) = putB("cwCatchup", v)
    /** Title pages: where to watch (Live TV and recordings too), and the cast with their other titles. */
    var titleRich: Boolean get() = b("titleRich", true); set(v) = putB("titleRich", v)
    /** After you leave the player, the carousel opens on what you just watched. */
    /** Continue Watching: off | first (at the start of the carousel) | mixed (among the catalogs) */
    /** A remote button that shuffles the carousel on the home screen (its key code; 0 = none). */
    var shuffleKey: Int get() = i("shuffleKey", 0); set(v) = putI("shuffleKey", v)
    /** Titles a catalog has just added open the carousel for two days. */
    var newFirst: Boolean get() = b("newFirst", false); set(v) = putB("newFirst", v)
    /** Continue Watching is fetched while the carousel or the Library shows it (off in the carousel only = still in the Library). */
    val continueWanted: Boolean get() = continueMode != "off" || libraryCw
    var cwSimkl: Boolean get() = b("cwSimkl", true); set(v) = putB("cwSimkl", v)
    /** What you watch on the Nuvio account (Legacy, the official app and forks push it there). */
    var cwNuvioAccount: Boolean get() = b("cwNuvioAccount", true); set(v) = putB("cwNuvioAccount", v)
    /** Continue Watching at the top of the Library: on, card shape (backdrop | poster), size, where it came from. */
    var libraryCw: Boolean get() = b("libraryCw", true); set(v) = putB("libraryCw", v)
    var libraryCwShape: String get() = s("libraryCwShape", "backdrop"); set(v) = putS("libraryCwShape", v)
    var libraryCwSize: Float get() = f("libraryCwSize", 1.0f); set(v) = putF("libraryCwSize", v.coerceIn(0.7f, 1.5f))
    /** Library posters: size (1 = fills the row), how many in a row, and the space between them (1 = normal). */
    var libraryPosterSize: Float get() = f("libraryPosterSize", 1.0f); set(v) = putF("libraryPosterSize", v.coerceIn(0.6f, 1.0f))
    var libraryColumns: Int get() = i("libraryColumns", 5); set(v) = putI("libraryColumns", v.coerceIn(3, 8))
    var librarySpacing: Float get() = f("librarySpacing", 1.2f); set(v) = putF("librarySpacing", v.coerceIn(0.5f, 2.0f))
    var libraryCwSource: Boolean get() = b("libraryCwSource", true); set(v) = putB("libraryCwSource", v)
    /** The Simkl "Plan to watch" list in the Library, and + saves there too. */
    var librarySimkl: Boolean get() = b("librarySimkl", true); set(v) = putB("librarySimkl", v)
    var libraryToSimkl: Boolean get() = b("libraryToSimkl", true); set(v) = putB("libraryToSimkl", v)
    /** How many titles in progress open the carousel (0 = all); the rest mix in with the catalogs. */
    var cwLead: Int get() = i("cwLead", 3); set(v) = putI("cwLead", v)
    /** After you leave the player, the carousel opens on what you just watched. */
    var continueJump: Boolean get() = b("continueJump", false); set(v) = putB("continueJump", v)
    /** Continue Watching is exactly Nuvio's (titles and progress), even with the other accounts syncing too. */
    var cwNuvioOnly: Boolean get() = b("cwNuvioOnly", true); set(v) = putB("cwNuvioOnly", v)
    var cwLegacy: Boolean get() = b("cwLegacy", true); set(v) = putB("cwLegacy", v)
    /** Continue Watching also from the official Nuvio app, through the Nuvio account (when signed in). */
    var cwNuvio: Boolean get() = b("cwNuvio", true); set(v) = putB("cwNuvio", v)
    /** Continue Watching also from Stremio, through the Stremio account (when signed in). */
    var cwStremio: Boolean get() = b("cwStremio", true); set(v) = putB("cwStremio", v)
    var cwProgress: Boolean get() = b("cwProgress", true); set(v) = putB("cwProgress", v)
    /** The next episode after one you finished joins Continue Watching (like Nuvio's "Next up"). */
    var cwNextUp: Boolean get() = b("cwNextUp", true); set(v) = putB("cwNextUp", v)
    /** 0.6 .. 1.0 (1.0 = full width) */
    var dockScale: Float get() = f("dockScale", 1.0f); set(v) = putF("dockScale", v.coerceIn(0.6f, 1.0f))
    /** Background of the dock and the Apps folder: liquid | clear | amoled | bow */
    var dockStyle: String get() = s("dockStyle", "water_still"); set(v) = putS("dockStyle", v)
    /** Background of settings and the icon picker: liquid | clear | amoled */
    var panelStyle: String get() = s("panelStyle", "clear"); set(v) = putS("panelStyle", v)
    /** liquid | clear | amoled | bow (black on white) */
    var tileStyle: String get() = s("tileStyle", "water_still"); set(v) = putS("tileStyle", v)
    /** How far the glass rim bends the background, dp. */
    var refraction: Int get() = i("refraction", 8); set(v) = putI("refraction", v.coerceIn(0, 40))
    var metaScale: Float get() = f("metaScale", 0.9f); set(v) = putF("metaScale", v.coerceIn(0.6f, 2.0f))
    var genresScale: Float get() = f("genresScale", 0.8f); set(v) = putF("genresScale", v.coerceIn(0.6f, 2.0f))
    /** Opaque dark plate behind every icon, so transparent pack icons don't show the poster through. */
    var tileBacking: Boolean get() = b("tileBacking", true); set(v) = putB("tileBacking", v)
    /** package name of the selected icon pack, "" = none */
    var iconPack: String get() = s("iconPack", ""); set(v) = putS("iconPack", v)
    /** Weather in the top bar: off | temp | condition | city | all */
    var weatherMode: String get() = s("weatherMode", "off"); set(v) = putS("weatherMode", v)
    var weatherCity: String get() = s("weatherCity", "Baghdad"); set(v) = putS("weatherCity", v.trim())
    var weatherF: Boolean get() = b("weatherF", false); set(v) = putB("weatherF", v)
    var showDate: Boolean get() = b("showDate", false); set(v) = putB("showDate", v)
    /** tvOS look: icons are white until selected, then show their colours on a light plate. */
    var focusColor: Boolean get() = b("focusColor", true); set(v) = putB("focusColor", v)
    /** The colour of the switches (and of a setting that's set and working) in Settings: burgundy, green, purple. */
    var settingsAccent: String get() = s("settingsAccent", "burgundy"); set(v) = putS("settingsAccent", v)
    /** Plate behind the selected icon in "Colour on focus": light | dark | glass | amoled (pure black) */
    var focusPlate: String get() = s("focusPlate", "glass"); set(v) = putS("focusPlate", v)
    /** Clock: left (Apple TV) | right (next to the settings button, weather under it) */
    var clockPos: String get() = s("clockPos", "right"); set(v) = putS("clockPos", v)
    /** Pressing further at the edge of the dock nudges the icon, like tvOS. */
    var edgeBounce: Boolean get() = b("edgeBounce", true); set(v) = putB("edgeBounce", v)
    /** The selected icon rises a little, like tvOS. */
    var tileLift: Boolean get() = b("tileLift", false); set(v) = putB("tileLift", v)
    var showClock: Boolean get() = b("showClock", false); set(v) = putB("showClock", v)
    var clock24h: Boolean get() = b("clock24h", false); set(v) = putB("clock24h", v)
    var blurRadius: Int get() = i("blurRadius", 32); set(v) = putI("blurRadius", v.coerceIn(0, 80))
    var glassTint: Int get() = i("glassTint", 0); set(v) = putI("glassTint", v.coerceIn(0, 40))
    var showMobileApps: Boolean get() = b("showMobileApps", true); set(v) = putB("showMobileApps", v)
    var dockOrder: List<String> get() = list("dockOrder"); set(v) = putList("dockOrder", v)
    var hiddenApps: List<String> get() = list("hiddenApps"); set(v) = putList("hiddenApps", v)
    /** The Apps folder tile in the dock. */
    var showFolder: Boolean get() = b("showFolder", true); set(v) = putB("showFolder", v)
    /** With fewer apps than a full row, the dock shrinks around them. */
    var dockFit: Boolean get() = b("dockFit", true); set(v) = putB("dockFit", v)
    /** The dock hides below the screen and comes up when you go down to it. */
    var dockHide: Boolean get() = b("dockHide", false); set(v) = putB("dockHide", v)
    /** With your own background (picture, video, screensaver): bottom | center | left (a column on the left). */
    var dockPos: String get() = s("dockPos", "left"); set(v) = putS("dockPos", v)
    /** Down from the dock's last row opens the Library. */
    var libraryDown: Boolean get() = b("libraryDown", true); set(v) = putB("libraryDown", v)

    // ---- Nuvio / Home Assistant
    /** auto (first installed: Legacy, official, Arc, Re-Shaped, Stremio) | legacy | official | arc | reshaped | stremio */
    var nuvioTarget: String get() = s("nuvioTarget", "auto"); set(v) = putS("nuvioTarget", v)
    var haWebhook: String get() = s("haWebhook", ""); set(v) = putS("haWebhook", v.trim())
    var haOnLaunch: Boolean get() = b("haOnLaunch", false); set(v) = putB("haOnLaunch", v)
    /** Events to Home Assistant: All (HaEvents' newer ones too) or Basic. */
    var haEventsAll: Boolean get() = b("haEventsAll", false); set(v) = putB("haEventsAll", v)
    /** Home Assistant may tune, pause, play and show notices (the phone page's /api/ha/…). */
    var haControl: Boolean get() = b("haControl", true); set(v) = putB("haControl", v)

    // ---- Screensaver
    var idleMinutes: Int get() = i("idleMinutes", 0); set(v) = putI("idleMinutes", v.coerceAtLeast(0))
    /** Idle time before the screensaver, in seconds (0 = off). Older versions kept whole minutes in idleMinutes. */
    var idleSeconds: Int get() = if (sp.contains("idleSeconds")) i("idleSeconds", 300) else if (sp.contains("idleMinutes")) i("idleMinutes", 0) * 60 else 300
        set(v) = putI("idleSeconds", v.coerceAtLeast(0))
    /** aerial (Apple videos) | slideshow (posters with logos) | system (the TV's own screensaver, e.g. Aerial Views) */
    var saverMode: String get() = s("saverMode", "aerial"); set(v) = putS("saverMode", v)
    var useAppleAerials: Boolean get() = b("useAppleAerials", false); set(v) = putB("useAppleAerials", v)
    var aerialUrls: List<String> get() = list("aerialUrls"); set(v) = putList("aerialUrls", v)
    var saverClock: Boolean get() = b("saverClock", true); set(v) = putB("saverClock", v)
    /** A followed team's next match (Live TV's Following) under the screensaver's clock. */
    var saverMatch: Boolean get() = b("saverMatch", true); set(v) = putB("saverMatch", v)

    // ---- System
    var interceptHome: Boolean get() = b("interceptHome", false); set(v) = putB("interceptHome", v)
    /** The remote's Guide button opens Live TV's guide (instead of the TV maker's own app); the accessibility service. */
    var interceptGuide: Boolean get() = b("interceptGuide", true); set(v) = putB("interceptGuide", v)
    /** After a restart, Glassio instead of Google TV's own home screen (BootReceiver, HomeKeyService). */
    var bootGlassio: Boolean get() = b("bootGlassio", true); set(v) = putB("bootGlassio", v)
    /** Collections of aerial videos that are on (apple, amazon, jetson, robin); see AerialSource. */
    var saverSources: List<String> get() = if (sp.contains("saverSources")) list("saverSources").filter { it.isNotBlank() } else if (sp.contains("useAppleAerials")) (if (useAppleAerials) listOf("apple") else emptyList()) else listOf("amazon")
        set(v) = putList("saverSources", v)
    /** Your own videos (links and the backgrounds folder) in the screensaver. */
    var saverMine: Boolean get() = b("saverMine", true); set(v) = putB("saverMine", v)
    var saverQuality: String get() = s("saverQuality", "4K-SDR"); set(v) = putS("saverQuality", v)
    var saverShuffle: Boolean get() = b("saverShuffle", true); set(v) = putB("saverShuffle", v)
    /** One video only (its id), or "" for all of them. */
    var saverPick: String get() = s("saverPick", ""); set(v) = putS("saverPick", v)
    /** Videos are kept on the TV as they play (any link), up to [saverCacheGb]. */
    var saverCache: Boolean get() = b("saverCache", true); set(v) = putB("saverCache", v)
    var saverCacheGb: Int get() = i("saverCacheGb", 8); set(v) = putI("saverCacheGb", v.coerceIn(1, 32))
    /** Cross-fade from one video to the next. */
    var saverFade: Boolean get() = b("saverFade", true); set(v) = putB("saverFade", v)
    var saverDate: Boolean get() = b("saverDate", false); set(v) = putB("saverDate", v)
    var saverWeather: Boolean get() = b("saverWeather", true); set(v) = putB("saverWeather", v)
    /** The place the video shows (Apple, Amazon and community videos have one). */
    var saverPlace: Boolean get() = b("saverPlace", true); set(v) = putB("saverPlace", v)
    var saverSys: Boolean get() = b("saverSys", false); set(v) = putB("saverSys", v)
    /** apple (clock in a corner, small text) | netflix (lines appear one by one at the top left, like the notes before a film) */
    var saverStyle: String get() = s("saverStyle", "netflix"); set(v) = putS("saverStyle", v)
    /** bl | br | tl | tr | move (another corner with every video) */
    var saverPos: String get() = s("saverPos", "bl"); set(v) = putS("saverPos", v)
    var saverSize: Float get() = f("saverSize", 1.0f); set(v) = putF("saverSize", v.coerceIn(0.6f, 1.8f))
    /** Seconds the info stays after each new video (0 = always). */
    var saverShowSec: Int get() = i("saverShowSec", 10); set(v) = putI("saverShowSec", v.coerceAtLeast(0))
    /** Netflix style: the lines go away a few seconds after the last one came in. */
    var saverNetflixHide: Boolean get() = b("saverNetflixHide", true); set(v) = putB("saverNetflixHide", v)
    /** Netflix style: the bar before each line — "red", "white", "black" or "off". */
    var saverBar: String get() = s("saverBar", "black"); set(v) = putS("saverBar", v)
    /** How far and how strong the glow is (0.4 … 1.6). */
    var glowSize: Float get() = f("glowSize", 1.1f); set(v) { putF("glowSize", v.coerceIn(0.3f, 1.6f)) }
    /** Dock: on focus the whole tile grows ("tile"), only the icon inside the glass ("icon"), or nothing ("off"). */
    var tileZoom: String get() = s("tileZoom", "tile"); set(v) = putS("tileZoom", v)
    /** A very soft glow around the selected icon / poster: "off", "color" (its own colour) or "white". */
    var glow: String get() = s("glow", "color"); set(v) = putS("glow", v)
    /** Hold Home: "recents", "camera" (live camera over any app), "library", "menu" or "off". */
    /** Hold Home → camera: seconds before the corner camera closes on its own (0 = stays until closed). */
    /** Hold Home → camera: which camera opens (its link; blank = the first one in the list). */
    var cornerCamera: String get() = s("cornerCamera", ""); set(v) = putS("cornerCamera", v)
    /** Hold Home → camera: switch to the next camera every this many seconds (0 = stay on one). */
    var cameraCycleSec: Int get() = i("cameraCycleSec", 5); set(v) = putI("cameraCycleSec", if (v <= 0) 0 else v.coerceIn(5, 600))
    /** Doorbell pop-ups from Home Assistant (port 7979, PiPup's request). */
    var doorbellOn: Boolean get() = b("doorbellOn", true); set(v) = putB("doorbellOn", v)
    var doorbellScale: Float get() = f("doorbellScale", 1.0f); set(v) = putF("doorbellScale", v.coerceIn(0.6f, 1.6f))
    var doorbellText: Boolean get() = b("doorbellText", true); set(v) = putB("doorbellText", v)
    var doorbellTitle: String get() = s("doorbellTitle", "الباب الأمامي"); set(v) = putS("doorbellTitle", v)
    var doorbellMessage: String get() = s("doorbellMessage", "يوجد شخص عند الباب"); set(v) = putS("doorbellMessage", v)
    /** The doorbell's camera: a saved camera's link, or "" = the one Home Assistant sends. */
    var doorbellCamera: String get() = s("doorbellCamera", ""); set(v) = putS("doorbellCamera", v)
    /** Doorbell: go round the cameras every this many seconds (0 = stay on the doorbell camera). */
    var doorbellCycleSec: Int get() = i("doorbellCycleSec", 0); set(v) = putI("doorbellCycleSec", if (v <= 0) 0 else v.coerceIn(5, 600))
    /** Hold Home → camera: its size (1 = a third of the screen wide). */
    var cameraScale: Float get() = f("cameraScale", 1.0f); set(v) = putF("cameraScale", v.coerceIn(0.6f, 1.6f))
    /** auto | software | hardware: how the cameras' video is decoded (see CamPlayer). */
    var cameraDecoder: String get() = s("cameraDecoder", "auto"); set(v) = putS("cameraDecoder", v)
    /** Multi-view: up to four cameras in one row. The cameras (links) in it; empty = the first four. */
    var multiCams: List<String> get() = list("multiCams"); set(v) = putList("multiCams", v.take(4))
    var multiScale: Float get() = f("multiScale", 1.4f); set(v) = putF("multiScale", v.coerceIn(0.6f, 1.4f))
    /** Seconds it stays (0 = until closed). */
    /** Multi-view picture: "auto" (the cameras' full streams where the TV decodes in hardware), "high", "light". */
    var multiQuality: String get() = s("multiQuality", "auto"); set(v) = putS("multiQuality", v)
    var multiSec: Int get() = i("multiSec", 30); set(v) = putI("multiSec", v.coerceIn(0, 600))
    /** On the screensaver: ▲ brings the multi-view up from the bottom. */
    var saverMultiCam: Boolean get() = b("saverMultiCam", true); set(v) = putB("saverMultiCam", v)
    /** Remote buttons (any app): multi-view and the corner camera. 0 = none. */
    var multiKey: Int get() = i("multiKey", 0); set(v) = putI("multiKey", v)
    var cornerKey: Int get() = i("cornerKey", 0); set(v) = putI("cornerKey", v)
    /** Remote buttons that open an app, the Apps folder, the recent apps or an input ([RemoteMap]). */
    var keyMaps: List<String> get() = list("keyMaps"); set(v) = putList("keyMaps", v)
    var doorbellChime: Boolean get() = b("doorbellChime", true); set(v) = putB("doorbellChime", v)
    /** Nuvio Legacy's playback sent to Home Assistant as it happens (event "glassio_nuvio"). */
    var nuvioPush: Boolean get() = b("nuvioPush", false); set(v) = putB("nuvioPush", v)
    /** The key Home Assistant sends with Nuvio's controls (X-Relay-Token), port 9091. */
    var nuvioToken: String get() = s("nuvioToken", ""); set(v) = putS("nuvioToken", v.trim())
    var haDoorbellEntity: String get() = s("haDoorbellEntity", ""); set(v) = putS("haDoorbellEntity", v.trim())
    var doorbellSec: Int get() = i("doorbellSec", 20); set(v) = putI("doorbellSec", v.coerceIn(5, 300))
    var cameraCornerSec: Int get() = i("cameraCornerSec", 30); set(v) = putI("cameraCornerSec", v.coerceIn(0, 600))
    var homeLong: String get() = s("homeLong", if (b("homeRecents", false)) "recents" else "off"); set(v) = putS("homeLong", v)
    /** Ask before removing a title from the Library. */
    var libraryConfirm: Boolean get() = b("libraryConfirm", true); set(v) = putB("libraryConfirm", v)
    /** Background video: a new one when each ends (else the same one loops). */
    var bgShuffle: Boolean get() = b("bgShuffle", true); set(v) = putB("bgShuffle", v)
    /** Screensaver as background: "1080" (light behind the glass) or "saver" (the screensaver's quality and its saved videos). */
    var bgAerialQuality: String get() = s("bgAerialQuality", "1080"); set(v) = putS("bgAerialQuality", v)
    /** Background "Photos": where they come from (mine = the backgrounds folder, bing, wallhaven, unsplash). */
    var photoSource: String get() = s("photoSource", "bing").let { if (it == "wallhaven") "commons" else it }; set(v) = putS("photoSource", v)
    /** What the photos show (Wallhaven and Unsplash search for it). */
    var photoTopic: String get() = s("photoTopic", "architecture"); set(v) = putS("photoTopic", v)
    /** A new photo every this many seconds (0 = keep the one on screen). */
    var photoEvery: Int get() = i("photoEvery", 60); set(v) = putI("photoEvery", if (v <= 0) 0 else v.coerceIn(30, 86_400))
    /** Photos in a random order (else in the source's order). */
    var photoShuffle: Boolean get() = b("photoShuffle", true); set(v) = putB("photoShuffle", v)
    var unsplashKey: String get() = s("unsplashKey", ""); set(v) = putS("unsplashKey", v.trim())
    /** The photo on screen (url, place, credit), so it comes back at once after a restart. */
    var photoLast: String get() = sp.getString("photoLast", "") ?: ""; set(v) { sp.edit().putString("photoLast", v).apply() }
    /** Your own background: the place / title of the video or photo, like the screensaver. */
    var bgInfo: Boolean get() = b("bgInfo", false); set(v) = putB("bgInfo", v)
    var bgInfoPos: String get() = s("bgInfoPos", "tl"); set(v) = putS("bgInfoPos", v)
    /** Shown for this many seconds after each change (0 = always). */
    var bgInfoSec: Int get() = i("bgInfoSec", 10); set(v) = putI("bgInfoSec", v.coerceIn(0, 600))
    /** Your own background: a large clock in the middle, like a phone's lock screen. */
    var bgClock: Boolean get() = b("bgClock", true); set(v) = putB("bgClock", v)
    var bgClockSize: Float get() = f("bgClockSize", 0.8f); set(v) = putF("bgClockSize", v.coerceIn(0.6f, 1.6f))
    /** Depth effect: on a photo, what's in front (a mountain, a person) covers the large clock. */
    var bgDepth: Boolean get() = b("bgDepth", true); set(v) = putB("bgDepth", v)
    /** Large clock: "theme" (glass numerals in the glass themes, black on Black on white) or "white". */
    /** Sharp corners for the menu, the dock and the icons (no rounding). */
    var squareCorners: Boolean get() = b("squareCorners", false); set(v) = putB("squareCorners", v)
    var bgClockStyle: String get() = s("bgClockStyle", "theme"); set(v) = putS("bgClockStyle", v)
    /** The large clock's date in white, whatever the time's look. */
    var bgClockDateWhite: Boolean get() = b("bgClockDateWhite", true); set(v) = putB("bgClockDateWhite", v)
    /** The large clock on the screensaver too (Down shows / hides it). */
    var saverBigClock: Boolean get() = b("saverBigClock", true); set(v) = putB("saverBigClock", v)
    /** Lock & release on the screensaver: hold OK on a video to keep it (and again to let it go). */
    var saverLock: Boolean get() = b("saverLock", true); set(v) = putB("saverLock", v)
    /** The locked videos (their ids). */
    /** The screensaver's large clock was left shown (true) or hidden (▲ on the remote). */
    var saverClockShown: Boolean get() = b("saverClockShown", true); set(v) = putB("saverClockShown", v)
    /** The music island at the top of the home screen too (when Music mode is off). */
    var islandHome: Boolean get() = b("islandHome", false); set(v) = putB("islandHome", v)
    var saverLocked: List<String> get() = list("saverLocked"); set(v) = putList("saverLocked", v)
    /** Only the locked videos play (when there are some). */
    var saverLockedOnly: Boolean get() = b("saverLockedOnly", false); set(v) = putB("saverLockedOnly", v)
    /** Large clock: moved up (negative) or down, as a part of the screen's height. */
    var bgClockOffset: Float get() = f("bgClockOffset", -0.04f); set(v) = putF("bgClockOffset", v.coerceIn(-0.2f, 0.3f))
    /** My video / My picture: go round the others in the folder (else the chosen one stays). */
    var bgVideoShuffle: Boolean get() = b("bgVideoShuffle", false); set(v) = putB("bgVideoShuffle", v)
    var bgImageShuffle: Boolean get() = b("bgImageShuffle", true); set(v) = putB("bgImageShuffle", v)
    /** App lock: your own reminder under the digits (anything, the code itself too), and whether it shows. */
    var lockHintOn: Boolean get() = b("lockHintOn", false); set(v) = putB("lockHintOn", v)
    var lockHint: String get() = s("lockHint", ""); set(v) = putS("lockHint", v)
    /** The info over your background, like the screensaver's: style, which lines, corner, how long. */
    var bgInfoStyle: String get() = s("bgInfoStyle", "netflix"); set(v) = putS("bgInfoStyle", v)
    var bgInfoClock: Boolean get() = b("bgInfoClock", false); set(v) = putB("bgInfoClock", v)
    var bgInfoDate: Boolean get() = b("bgInfoDate", false); set(v) = putB("bgInfoDate", v)
    var bgInfoWeather: Boolean get() = b("bgInfoWeather", true); set(v) = putB("bgInfoWeather", v)
    /** Pictures kept on the TV for the Photos background (oldest go first), in MB. */
    var photoCacheMb: Int get() = i("photoCacheMb", 1024); set(v) = putI("photoCacheMb", v.coerceIn(100, 8192))
    /** Apps that ask for the 4-digit code when opened from Glassio. */
    var lockedApps: List<String> get() = list("lockedApps"); set(v) = putList("lockedApps", v)
    var lockCode: String get() = s("lockCode", "0000"); set(v) = putS("lockCode", v.filter { it.isDigit() }.take(4).padStart(4, '0'))

    /** Existing installs skip the first-run setup (it is for new TVs). */
    fun firstRun() { if (!sp.contains("setupDone")) putB("setupDone", sp.all.size > 8) }

    /** Testing over adb (see DevHooks): key=value — true/false, a number, or text; lists with \n. */
    fun debugSet(kv: String) {
        val k = kv.substringBefore('='); val v = kv.substringAfter('=')
        when {
            v == "true" || v == "false" -> putB(k, v == "true")
            v.toIntOrNull() != null && !k.endsWith("Pick") -> putI(k, v.toInt())
            v.toFloatOrNull() != null && v.contains('.') -> putF(k, v.toFloat())
            else -> putS(k, v.replace("\\n", "\n"))
        }
    }
    /** Opens Glassio when the TV starts (needs the "display over other apps" right, given over ADB). */
    var bootStart: Boolean get() = b("bootStart", false); set(v) = putB("bootStart", v)
    /** Manage from phone: minutes the page stays on after you leave that screen (0 = stops at once). */
    var phoneMinutes: Int get() = i("phoneMinutes", 30); set(v) = putI("phoneMinutes", v.coerceIn(0, 720))
    /** App language, a key of I18n.LANGS. */
    var lang: String get() = s("lang", "en"); set(v) { putS("lang", v); I18n.lang = v }
    /** The first-run setup was finished (or skipped with Later). */
    var setupDone: Boolean get() = b("setupDone", false); set(v) = putB("setupDone", v)
    /** The What's new tour last seen (WhatsNew.ID). */
    var tourSeen: Int get() = i("tourSeen", 0); set(v) = putI("tourSeen", v)
}
