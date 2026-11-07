package com.glasslauncher.tv.data

import android.content.Context
import com.glasslauncher.tv.live.LiveHome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Live TV's cards in the home screen's Continue Watching row: a followed match on now and the channel last watched in
 * front, the newest finished recordings and past shows left partway among the rest (LiveHome.homeCards). Read again every minute while the home screen is
 * in front (what's on changes). Hold OK on one hides it: a recording or a match for good, the channel until 12 hours
 * later (it comes back once you watch again).
 */
object LiveCards {
    const val PLATFORM = "live"
    const val TYPE_MATCH = "live-match"
    const val TYPE_CHANNEL = "live-channel"
    const val TYPE_RECORDING = "live-recording"
    const val TYPE_RESUME = "live-resume"
    private const val CHANNEL_HIDDEN_MS = 12 * 3600_000L

    private val _items = MutableStateFlow<List<HeroItem>>(emptyList())
    val items: StateFlow<List<HeroItem>> = _items

    /** Blocking (it reads Live TV's files): off the main thread. */
    fun refresh(ctx: Context) {
        val cards = runCatching { LiveHome.homeCards(ctx) }.getOrElse { Glog.w("live cards: ${it.message}"); return }
        val hidden = hidden(ctx)
        val now = System.currentTimeMillis()
        val catchup = (ctx.applicationContext as? com.glasslauncher.tv.GlassApp)?.settings?.cwCatchup != false
        val list = cards.filter { k ->
            if (k.kind == LiveHome.HomeCard.RESUME && !catchup) return@filter false
            val at = hidden[k.key] ?: return@filter true
            k.kind == LiveHome.HomeCard.CHANNEL && now - at > CHANNEL_HIDDEN_MS
        }.map { k ->
            HeroItem(
                key = k.key,
                id = when (k.kind) {
                    LiveHome.HomeCard.RECORDING -> "rec:${k.recording}"
                    LiveHome.HomeCard.RESUME -> "res:${k.channel}:${k.start}"
                    else -> "ch:${k.channel}"
                },
                type = when (k.kind) {
                    LiveHome.HomeCard.MATCH -> TYPE_MATCH
                    LiveHome.HomeCard.RECORDING -> TYPE_RECORDING
                    LiveHome.HomeCard.RESUME -> TYPE_RESUME
                    else -> TYPE_CHANNEL
                },
                name = k.title, poster = "", background = k.art, logo = k.logo, overview = "", rating = "", year = "",
                genres = emptyList(), platform = PLATFORM, progress = k.progress, sub = k.sub, country = k.country, watchedAt = k.at,
            )
        }
        if (list != _items.value) _items.value = list
    }

    fun isLive(item: HeroItem) = item.platform == PLATFORM

    /** OK on a card: Live TV on its channel, or playing its recording, or its past show where you left it. */
    fun open(ctx: Context, item: HeroItem) {
        val i = when {
            item.id.startsWith("rec:") -> LiveHome.openRecording(ctx, item.id.removePrefix("rec:"))
            item.id.startsWith("res:") -> {
                val (channel, start) = item.id.removePrefix("res:").split(":").let { (it[0].toIntOrNull() ?: -1) to (it.getOrNull(1)?.toLongOrNull() ?: 0L) }
                LiveHome.openResume(ctx, channel, start)
            }
            else -> LiveHome.open(ctx, item.id.removePrefix("ch:").toIntOrNull() ?: -1, false)
        }
        runCatching { ctx.startActivity(i) }.onFailure { Glog.w("live cards: couldn't open: ${it.message}") }
    }

    /** Hold OK: out of the row. */
    fun hide(ctx: Context, item: HeroItem) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        // Kept small: entries over a month old go.
        val kept = (sp.getStringSet(HIDDEN, emptySet()) ?: emptySet())
            .filter { e -> e.substringAfterLast('|').toLongOrNull()?.let { now - it < 30 * 24 * 3600_000L } == true }
            .filter { !it.startsWith(item.key + "|") }
        sp.edit().putStringSet(HIDDEN, (kept + "${item.key}|$now").toSet()).apply()
        _items.value = _items.value.filter { it.key != item.key }
    }

    private fun hidden(ctx: Context): Map<String, Long> =
        (ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(HIDDEN, emptySet()) ?: emptySet()).mapNotNull { e ->
            val at = e.substringAfterLast('|').toLongOrNull() ?: return@mapNotNull null
            e.substringBeforeLast('|') to at
        }.toMap()

    private const val PREFS = "live_cards"
    private const val HIDDEN = "hidden"
}
