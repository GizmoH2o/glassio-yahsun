package com.glasslauncher.tv.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.glasslauncher.tv.GlassApp
import com.glasslauncher.tv.data.HeroItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * One place for what's happened and what's about to (mockup 21): followed matches and shows starting now or soon (Live
 * TV's Following), new episodes of what you watch (Home's Airing this week), and a Glassio update. The top bar's bell
 * opens it; a dot on the bell while there's one you haven't seen.
 */
object Inbox {
    /** One line: its kind (STARTING NOW…), what, the line under it, a picture, its id (for seen), what OK does. */
    class Entry(val kind: String, val title: String, val sub: String, val picture: String, val id: String, val red: Boolean,
                val title2: HeroItem? = null, val channel: Int = -1, val update: Boolean = false, val whatsNew: Boolean = false)

    /** OK on the What's new line: Home shows the sheet. */
    val openWhatsNew = mutableStateOf(false)

    val entries = mutableStateOf<List<Entry>>(emptyList())
    /** A line not seen yet (the bell's dot). */
    val unseen = mutableStateOf(false)
    private fun sp(c: Context) = c.getSharedPreferences("glass_inbox", Context.MODE_PRIVATE)

    /** Reads it all again (off the main thread). */
    fun load(app: GlassApp): List<Entry> {
        val out = ArrayList<Entry>()
        val now = System.currentTimeMillis()
        runCatching {
            val f = com.glasslauncher.tv.live.LiveHome.following(app)
            val all = listOfNotNull(f.spot) + f.also + f.next + f.shows
            for (a in all.distinctBy { "${it.channel}:${it.start}" }.sortedBy { it.start }.take(6)) {
                val on = a.start <= now && now < a.end
                if (!on && a.start - now > 12 * 3_600_000L) continue
                out += Entry(if (on) "STARTING NOW" else if (a.remind) "REMINDER" else "COMING UP", a.title,
                    listOf(a.channelName, if (on) "OK to watch" else Dates.time(a.start)).filter { it.isNotBlank() }.joinToString(" · "),
                    a.art.ifBlank { a.logo }, "live:${a.channel}:${a.start}", on, channel = a.channel)
            }
        }
        for (a in app.shelf.airing.value) {
            val at = a.episode.released
            if (at > now || now - at > 2 * 24 * 3_600_000L) continue
            out += Entry("NEW EPISODE", a.item.name, "S${a.episode.season} · E${a.episode.episode}" + a.episode.title.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                a.item.background.ifBlank { a.item.poster }, "ep:${a.item.key}:${a.episode.season}:${a.episode.episode}", false, title2 = a.item)
        }
        // This version's news, for a week after the update.
        if (now - app.settings.whatsNewAt < 7 * 24 * 3_600_000L && !app.settings.whatsNewPopup) {
            val v = app.updater.current
            out += Entry("WHAT'S NEW", "Glassio $v", "OK to see what's new", "", "whatsnew:$v", false, whatsNew = true)
        }
        app.updater.available.value?.let { r ->
            out += Entry("GLASSIO", "${r.version} is ready to install", "OK to install", "", "update:${r.version}", false, update = true)
        }
        val seen = sp(app).getStringSet("seen", emptySet()) ?: emptySet()
        unseen.value = out.any { it.id !in seen }
        return out
    }

    /** Opened: everything on it counts as seen. */
    fun seen(c: Context) {
        sp(c).edit().putStringSet("seen", entries.value.map { it.id }.toSet()).apply()
        unseen.value = false
    }
}

/** Keeps the inbox current while Home is up (every few minutes), for the bell's dot. */
@Composable
fun InboxWatch(app: GlassApp) {
    LaunchedEffect(Unit) {
        while (true) {
            Inbox.entries.value = withContext(Dispatchers.IO) { Inbox.load(app) }
            delay(5 * 60_000L)
        }
    }
}

/** The inbox panel at the right (Back closes it); OK on a line does what it says. */
@Composable
fun InboxPanel(app: GlassApp, s: Float, onTitle: (HeroItem) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    androidx.activity.compose.BackHandler { onClose() }
    LaunchedEffect(Unit) {
        Inbox.entries.value = withContext(Dispatchers.IO) { Inbox.load(app) }
        Inbox.seen(ctx)
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(Inbox.entries.value.isNotEmpty()) { delay(60); runCatching { first.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.TopEnd) {
        Column(
            Modifier.padding(top = (40 * s).dp, end = (20 * s).dp, bottom = (20 * s).dp).width((280 * s).dp).fillMaxHeight()
                .clip(RoundedCornerShape((Ds.R_PANEL * s).dp)).background(Ds.Surface.copy(alpha = 0.96f)).padding((11 * s).dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy((4 * s).dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(start = (4 * s).dp, bottom = (4 * s).dp), verticalAlignment = Alignment.Bottom) {
                Text("Inbox", color = Color.White, fontSize = (14 * s).sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            }
            val list = Inbox.entries.value
            if (list.isEmpty()) {
                Text("Nothing new. Matches and shows you follow, new episodes and updates show here.", color = Text2, fontSize = (8 * s).sp,
                    modifier = Modifier.padding((4 * s).dp).focusRequester(first).focusable())
            }
            list.forEachIndexed { i, e ->
                var focused by remember { mutableStateOf(false) }
                Row(
                    Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier)
                        .onFocusChanged { focused = it.isFocused }
                        .tvPress(onClick = {
                            when {
                                e.update -> app.updater.install()
                                e.whatsNew -> { onClose(); Inbox.openWhatsNew.value = true }
                                e.title2 != null -> { onClose(); onTitle(e.title2) }
                                e.channel >= 0 -> runCatching { ctx.startActivity(com.glasslauncher.tv.live.LiveHome.open(ctx, e.channel, false)) }
                            }
                        })
                        .clip(RoundedCornerShape((Ds.R_CONTROL * s).dp)).background(if (focused) Color.White else Color.Transparent)
                        .padding((6 * s).dp),
                    horizontalArrangement = Arrangement.spacedBy((7 * s).dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size((30 * s).dp).clip(RoundedCornerShape((Ds.R_CARD * s).dp)).background(Ds.SurfaceHi)) {
                        if (e.picture.isNotBlank()) AsyncImage(sized(e.picture, "w342"), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    Column(Modifier.weight(1f)) {
                        Text(e.kind, color = if (e.red) (if (focused) Ds.BadOnWhite else Color(0xFFFF6B5E)) else if (focused) Ds.InkSoft else Text2,
                            fontSize = (6 * s).sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (0.5f * s).sp)
                        RawText(e.title, color = if (focused) Ds.Ink else Color.White, fontSize = (9 * s).sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        RawText(e.sub, color = if (focused) Ds.InkSoft else Text2, fontSize = (7 * s).sp, maxLines = 1)
                    }
                }
            }
        }
    }
}
