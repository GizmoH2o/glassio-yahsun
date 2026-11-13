package com.glasslauncher.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glasslauncher.tv.GlassApp
import com.glasslauncher.tv.data.HeroItem
import com.glasslauncher.tv.data.Moods
import kotlinx.coroutines.delay

/** A Home row opened as a page (the card sheet's See all): what it's called and every title in it. */
class RowPageAsk(val title: String, val items: List<HeroItem>)

/** The order a row page shows its titles in. */
internal enum class RowSort(val label: String) { Row("As in the row"), Name("A–Z"), Rating("Rating"), Newest("Newest") }

/** A row's titles filtered and sorted. [filter]: "all", "new" (not started), a genre, or "short" (under 2 hours). Pure. */
internal fun rowShown(items: List<HeroItem>, filter: String, sort: RowSort, started: Set<String>): List<HeroItem> {
    val f = when (filter) {
        "all" -> items
        "new" -> items.filter { it.key !in started }
        "short" -> items.filter { (Moods.minutes(it.runtime) ?: Int.MAX_VALUE) < 120 }
        else -> items.filter { it.genres.any { g -> g.equals(filter, true) } }
    }
    return when (sort) {
        RowSort.Row -> f
        RowSort.Name -> f.sortedBy { it.name.lowercase() }
        RowSort.Rating -> f.sortedByDescending { it.rating.toDoubleOrNull() ?: 0.0 }
        RowSort.Newest -> f.sortedByDescending { it.year.take(4).toIntOrNull() ?: 0 }
    }
}

/**
 * A Home row as its own page (mockup 29): its name, filter pills (all, not started, its main genres, under 2 hours), a
 * sort, then every title in it as a grid of posters. OK opens a title; Back closes it. [active]: in front (the
 * selection comes back to it after a title's page).
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun RowPage(app: GlassApp, s: Float, ask: RowPageAsk, active: Boolean, onTitle: (HeroItem) -> Unit, onClose: () -> Unit) {
    BackHandler(enabled = active) { onClose() }
    val items = remember(ask) { ask.items.map { app.shelf.filled(it) } }
    val started = remember(ask) { app.cw.items.value.map { it.key }.toSet() + app.cw.finishedKeys() }
    val genres = remember(items) { items.flatMap { it.genres }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(3).map { it.key } }
    val filters = listOf("all" to "All ${items.size}", "new" to "Not started") + genres.map { it to it } + ("short" to "Under 2 hours")
    var filter by remember(ask) { mutableStateOf("all") }
    var sort by remember(ask) { mutableStateOf(RowSort.Row) }
    val shown = remember(items, filter, sort) { rowShown(items, filter, sort, started) }
    val firstPill = remember { FocusRequester() }
    val back = remember { FocusRequester() }
    var lastKey by remember(ask) { mutableStateOf<String?>(null) }
    LaunchedEffect(ask, active) {
        if (!active) return@LaunchedEffect
        delay(120)
        if (lastKey == null || runCatching { back.requestFocus() }.isFailure) runCatching { firstPill.requestFocus() }
    }
    // Focus stays on this page: Home is still drawn (and focusable) under it.
    Column(Modifier.fillMaxSize().background(Ds.Bg).focusProperties { exit = { FocusRequester.Cancel } }.focusGroup().padding(start = (EDGE * s).dp, end = (EDGE * s).dp, top = (22 * s).dp)) {
        Text("Home  ›", color = Text2, fontSize = (7.5f * s).sp, fontWeight = FontWeight.Bold)
        RawText(ask.title, color = Color.White, fontSize = (19 * s).sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        Row(Modifier.padding(top = (10 * s).dp, bottom = (10 * s).dp), horizontalArrangement = Arrangement.spacedBy((5 * s).dp)) {
            filters.forEachIndexed { i, (key, label) ->
                DsButton(label, s, if (i == 0) Modifier.focusRequester(firstPill) else Modifier, primary = filter == key) { filter = key }
            }
            DsButton(Lang.tr("Sort") + ": " + Lang.tr(sort.label), s) { sort = RowSort.entries[(sort.ordinal + 1) % RowSort.entries.size] }
        }
        if (shown.isEmpty()) Text("Nothing here with this filter.", color = Text2, fontSize = (10 * s).sp)
        LazyVerticalGrid(GridCells.Fixed(7), contentPadding = PaddingValues(top = (4 * s).dp, bottom = (40 * s).dp),
            horizontalArrangement = Arrangement.spacedBy((10 * s).dp), verticalArrangement = Arrangement.spacedBy((12 * s).dp)) {
            itemsIndexed(shown, key = { _, h -> h.key }) { _, h ->
                SmallPoster(h, s, if (h.key == lastKey) Modifier.focusRequester(back) else Modifier, width = 104f, onFocused = { lastKey = h.key }) { onTitle(h) }
            }
        }
    }
}
