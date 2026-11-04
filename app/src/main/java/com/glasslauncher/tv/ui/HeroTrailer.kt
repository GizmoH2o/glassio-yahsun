package com.glasslauncher.tv.ui

import android.annotation.SuppressLint
import android.graphics.Color as AColor
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.glasslauncher.tv.GlassApp
import com.glasslauncher.tv.data.HeroItem

/**
 * A title's trailers on YouTube (Cinemeta's trailerStreams and trailers), comma-separated in order, or null for none.
 * Blocking; kept a week.
 */
fun trailerOf(app: GlassApp, item: HeroItem): String? = runCatching {
    if (!item.id.matches(Regex("tt\\d{6,10}"))) return null
    val type = if (item.type.equals("movie", true)) "movie" else "series"
    val meta = app.http.json("https://v3-cinemeta.strem.io/meta/$type/${item.id}.json", 7L * 24 * 3600 * 1000).optJSONObject("meta") ?: return null
    val ids = LinkedHashSet<String>()
    meta.optJSONArray("trailerStreams")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.optString("ytId")?.let { ids += it } }
    meta.optJSONArray("trailers")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.optString("source")?.let { ids += it } }
    ids.filter { it.matches(Regex("[\\w-]{11}")) }.take(6).joinToString(",").ifBlank { null }
}.getOrNull()

/** Called from YouTube's page (the trailer playing, over or stopped, or one that won't play here), on the main thread. */
private class TrailerBridge(val onPlaying: () -> Unit, val onEnded: () -> Unit, val onFailed: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    @JavascriptInterface fun playing() { main.post(onPlaying) }
    @JavascriptInterface fun ended() { main.post(onEnded) }
    @JavascriptInterface fun failed() { main.post(onFailed) }
}

/** Everything of YouTube's over its video: hidden from the page's start, and again once it has loaded (it builds its head afresh). */
private const val HIDE_CSS = "#movie_player>:not(.html5-video-container),.ytp-chrome-top,.ytp-chrome-bottom,.ytp-gradient-top,.ytp-gradient-bottom," +
    ".ytp-pause-overlay,.ytp-large-play-button,.ytp-cued-thumbnail-overlay,.ytp-spinner,.ytp-watermark,.ytp-title,.ytp-youtube-button," +
    ".ytp-impression-link,.ytp-ce-element,.ytp-endscreen-content,.ytp-show-cards-title,.ytp-paid-content-overlay,.ytp-error" +
    "{display:none!important}html,body{background:#000!important}"

/**
 * Watches YouTube's own player page once it's loaded: its video playing or over, an error screen (a trailer its owner
 * won't let play outside YouTube, or nothing playing in 20 s). Paused by anything but its end (the TV taking the video
 * away a moment, say) it's over too: never left on screen still, with YouTube's pause screen.
 */
private const val WATCH = """(function(){
var s=document.createElement('style');s.textContent='$HIDE_CSS';document.head.appendChild(s);
var seen=false,done=false,n=0;
function over(){if(!done){done=true;clearInterval(t);Glassio.ended()}}
var t=setInterval(function(){n++;
 if(document.querySelector('.ytp-error')){clearInterval(t);Glassio.failed();return}
 var v=document.querySelector('video');
 if(v&&!seen){seen=true;v.muted=true;
  v.addEventListener('playing',function(){Glassio.playing()});
  v.addEventListener('ended',over);
  v.addEventListener('pause',function(){setTimeout(function(){if(v.paused&&!v.ended)over()},1500)});
  if(!v.paused&&v.currentTime>0)Glassio.playing();
  v.play().catch(function(){});}
 if(n>40&&!(v&&v.currentTime>0)){clearInterval(t);Glassio.failed()}
},500)})()"""

/**
 * Before YouTube's own scripts: no VP9 or AV1 (H.264 instead), as Realtek's TVs hand those to the web view as compressed
 * frames that crash its graphics driver (eglCreateImageKHR) within seconds; and everything of YouTube's over the video
 * hidden from the first moment (title, logo, play and pause symbols, More videos, the end screen), loading included.
 */
private const val H264 = """(function(){
var st=document.createElement('style');st.textContent='$HIDE_CSS';
(document.head||document.documentElement).appendChild(st);
var no=function(t){return /vp0?9|vp8|av01|av1/i.test(String(t))};
if(window.MediaSource){var s=MediaSource.isTypeSupported.bind(MediaSource);MediaSource.isTypeSupported=function(t){return no(t)?false:s(t)}}
var c=HTMLMediaElement.prototype.canPlayType;HTMLMediaElement.prototype.canPlayType=function(t){return no(t)?'':c.call(this,t)};
if(navigator.mediaCapabilities){var d=navigator.mediaCapabilities.decodingInfo.bind(navigator.mediaCapabilities);
 navigator.mediaCapabilities.decodingInfo=function(o){return o&&o.video&&no(o.video.contentType)?Promise.resolve({supported:false,smooth:false,powerEfficient:false}):d(o)}}
})()"""

/** Trailers can play here: without a script before YouTube's own (an older web view) they'd come as VP9 (above). */
val trailersPlay by lazy { runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) }.getOrDefault(false) }

/**
 * Keeps a TV whose graphics driver or web view can't take trailers working: armed while one is on screen. Glassio coming
 * back up still armed means it went down during one (a driver crash leaves nothing else to see); a web view renderer
 * that dies counts too. Two in a row, with Trailers on Auto: no more trailers on this TV. One that plays well clears it.
 */
object TrailerGuard {
    private fun sp(ctx: android.content.Context) = ctx.getSharedPreferences("glass_trailer", android.content.Context.MODE_PRIVATE)
    /** Once, as Glassio starts. */
    fun started(ctx: android.content.Context) {
        val p = sp(ctx)
        if (p.getBoolean("armed", false)) p.edit().putBoolean("armed", false).putInt("fails", p.getInt("fails", 0) + 1).apply()
    }
    fun arm(ctx: android.content.Context) { sp(ctx).edit().putBoolean("armed", true).apply() }
    fun disarm(ctx: android.content.Context) { sp(ctx).edit().putBoolean("armed", false).apply() }
    fun failed(ctx: android.content.Context) { sp(ctx).let { it.edit().putBoolean("armed", false).putInt("fails", it.getInt("fails", 0) + 1).apply() } }
    fun playedWell(ctx: android.content.Context) { sp(ctx).edit().putInt("fails", 0).apply() }
    fun tripped(ctx: android.content.Context) = sp(ctx).getInt("fails", 0) >= 2
    /** Should trailers play? Settings › Home › Trailers: off, on (even after failures), or auto. */
    fun allowed(ctx: android.content.Context, mode: String): Boolean {
        if (!trailersPlay || mode == "off") return false
        if (mode == "on") return true
        val am = ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        return !tripped(ctx) && !am.isLowRamDevice
    }
}

/** The web view with YouTube's page in it, [muted] or not, in the shape both players need. */
@SuppressLint("SetJavaScriptEnabled")
private fun trailerView(ctx: android.content.Context, ids: String, muted: Boolean, onPlaying: () -> Unit, onWaiting: () -> Unit, onEnded: () -> Unit): WebView = WebView(ctx).apply {
    val list = ids.split(',')
    var at = 0
    val mute = if (muted) 1 else 0
    fun load() = loadUrl(
        "https://www.youtube.com/embed/${list[at]}?autoplay=1&mute=$mute&controls=0&disablekb=1&fs=0&iv_load_policy=3&modestbranding=1&rel=0&playsinline=1",
        mapOf("Referer" to "https://glassio.tv/"),
    )
    // The whole screen (wrapping its content, YouTube's page sizes its player to a height of 0).
    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    setBackgroundColor(AColor.BLACK)
    isFocusable = false; isFocusableInTouchMode = false
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.mediaPlaybackRequiresUserGesture = false
    webChromeClient = WebChromeClient()
    webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String) {
            if (url.contains("youtube.com/embed/")) view.evaluateJavascript(if (muted) WATCH else WATCH.replace("v.muted=true;", "v.muted=false;"), null)
        }
        // Its renderer gone (out of memory, a driver): this trailer is over, Glassio stays up. Unhandled, it takes the app down.
        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
            TrailerGuard.failed(view.context)
            onEnded()
            return true
        }
    }
    WebViewCompat.addDocumentStartJavaScript(this, H264, setOf("https://www.youtube.com"))
    val played = Handler(Looper.getMainLooper())
    addJavascriptInterface(TrailerBridge({
        onPlaying()
        // Half a minute on screen without trouble: this TV takes trailers.
        played.removeCallbacksAndMessages(null)
        played.postDelayed({ TrailerGuard.playedWell(context) }, 30_000)
    }, onEnded) {
        at++
        // The next one: the picture back over it while it loads (YouTube's loading screen never shows).
        if (at < list.size) { onWaiting(); load() } else onEnded()
    }, "Glassio")
    TrailerGuard.arm(context)
    load()
}

private fun releaseTrailer(v: WebView) {
    TrailerGuard.disarm(v.context)
    v.stopLoading(); v.loadUrl("about:blank"); v.destroy()
}

/** A trailer full screen with its sound (a title page's Trailer). Back, or its end, closes it. */
@Composable
fun TrailerPlayer(ids: String, onClose: () -> Unit) {
    androidx.activity.compose.BackHandler { onClose() }
    AndroidView(
        factory = { ctx -> trailerView(ctx, ids, muted = false, onPlaying = {}, onWaiting = {}, onEnded = onClose) },
        onRelease = { releaseTrailer(it) },
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * The trailer behind the big picture: YouTube's player page loaded as it is, muted and without its overlays, under the
 * same shades as the backdrops so the text stays readable. Drawn under the page's picture, which fades away once it
 * plays ([shown], 0 to 1): the video is the TV's own video plane, under the app, seen through the web view's hole (made
 * see-through any other way, the web view draws it itself, through the graphics driver that fails). [ids]: the
 * title's trailers in turn (one that won't play here, the next); [onPlaying] once it really plays, [onWaiting] while
 * the next loads; [onEnded] when it's over, stopped, or none plays.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HeroTrailer(ids: String, shown: () -> Float, onPlaying: () -> Unit, onWaiting: () -> Unit, onEnded: () -> Unit) {
    key(ids) {
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx -> trailerView(ctx, ids, muted = true, onPlaying, onWaiting, onEnded) },
                onRelease = { releaseTrailer(it) },
                modifier = Modifier.fillMaxSize(),
            )
            // The backdrops' own shades (baked into them, so gone as they fade): dark at the left (the text) and the bottom.
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = shown() }
                .background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.78f), 0.45f to Color.Black.copy(alpha = 0.25f), 0.7f to Color.Transparent))
                .background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f))))
        }
    }
}
