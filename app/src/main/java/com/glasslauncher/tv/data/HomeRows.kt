package com.glasslauncher.tv.data

import org.json.JSONObject

/** Home's rows as you arrange them (Settings › Home › Home rows): their order and own names, by each row's name. Pure. */
object HomeRows {
    /** Every row, in the usual order, with its usual title. */
    private val ALL = listOf(
        "Airing" to "Airing this week", "Watch" to "Continue watching", "ForYou" to "For You", "Movies" to "Movies in your library",
        "Series" to "Series in your library", "Trending" to "Trending now", "RecFilms" to "Popular films",
        "RecSeries" to "Popular series", "Col1" to "Pixar", "Col2" to "Studio Ghibli", "Col3" to "Marvel Studios",
        "Mine1" to "Your collection", "Mine2" to "Your collection", "Mine3" to "Your collection", "Mine4" to "Your collection",
        "Mine5" to "Your collection", "Mine6" to "Your collection", "Because" to "Because you watched", "Quick" to "Quick watches",
        "Apps" to "Apps", "Inputs" to "Inputs", "Widgets" to "At a glance",
    )

    fun title(row: String): String = ALL.firstOrNull { it.first == row }?.second ?: row

    /** The rows in order: those saved, with any new one after the row it usually follows. A name no longer known is dropped. */
    fun order(saved: String): List<String> {
        val known = ALL.map { it.first }
        val out = saved.split(',').filter { it in known }.distinct().toMutableList()
        for ((i, row) in known.withIndex()) {
            if (row in out) continue
            out.add(if (i == 0) out.size else out.indexOf(known[i - 1]) + 1, row)
        }
        return out
    }

    /** The order with [row] moved one place ([by]: -1 up, 1 down); at the end it stays. */
    fun move(saved: String, row: String, by: Int): String {
        val list = order(saved).toMutableList()
        val at = list.indexOf(row)
        val to = at + by
        if (at < 0 || to !in list.indices) return list.joinToString(",")
        list.removeAt(at)
        list.add(to, row)
        return list.joinToString(",")
    }

    fun name(names: String, row: String): String = runCatching { JSONObject(names).optString(row) }.getOrDefault("")

    /** The names with [row]'s set; blank takes it off. */
    fun rename(names: String, row: String, name: String): String {
        val o = runCatching { JSONObject(names) }.getOrDefault(JSONObject())
        if (name.isBlank()) o.remove(row) else o.put(row, name.trim())
        return o.toString()
    }
}
