package com.glasslauncher.tv.data

/**
 * Which catalogs stay selected once the add-ons have answered. An add-on can leave the list (an account's list
 * changes without Glassio being asked): its selections go, or follow it to a new address on the same host. One still
 * on the list that didn't answer is only late, so its selections stay and [Result.waiting] names it. With nothing left
 * selected, the first few film and series catalogs, as on a first run. Pure.
 */
object CatalogChoice {
    private const val FIRST_PICKS = 8

    class Result(val selected: Set<String>, val waiting: Set<String>)

    fun of(selected: Set<String>, all: List<CatalogRef>, urls: List<String>): Result {
        val answered = all.map { it.addon }.toSet()
        fun addonOf(key: String) = urls.firstOrNull { key.startsWith("$it|") } ?: key.substringBefore('|')
        val waiting = selected.map(::addonOf).filter { it in urls && it !in answered }.toSet()
        // Nobody answered (no connection): nothing can be told about anything.
        if (all.isEmpty()) return Result(selected, waiting)
        val offered = all.map { it.key }.toSet()
        val kept = LinkedHashSet<String>()
        for (key in selected) {
            val addon = addonOf(key)
            when {
                key in offered || addon in waiting -> kept += key
                addon !in urls -> movedTo(key.removePrefix("$addon|"), addon, all)?.let { kept += it }
            }
        }
        if (kept.isEmpty()) kept += all.filter { it.type == "movie" || it.type == "series" }.take(FIRST_PICKS).map { it.key }
        return Result(kept, waiting)
    }

    /** The same catalog ("type|id") from the same host as [addon], at its new address; null if there's none. */
    private fun movedTo(catalog: String, addon: String, all: List<CatalogRef>): String? {
        val host = hostOf(addon) ?: return null
        return all.firstOrNull { hostOf(it.addon) == host && "${it.type}|${it.id}" == catalog }?.key
    }

    private fun hostOf(url: String) = runCatching { java.net.URI(url).host }.getOrNull()
}
