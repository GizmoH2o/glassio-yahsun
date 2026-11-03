package com.glasslauncher.tv.data

/**
 * What the big picture shows, in order, from the catalogs' titles:
 *  - titles you said "Not interested" to are left out;
 *  - Kids mode: only family titles (a Family, Kids or Children genre; animation alone isn't enough);
 *  - seasonal titles first (Settings › Home › Seasonal picks): horror in October, Christmas in December, romance in
 *    February;
 *  - "Because you watched": every third slide a title sharing genres with what's in Continue Watching (not one of
 *    those), and why.
 * Pure (no Android), so it's tested on the computer.
 */
object HeroPicks {
    data class Result(val slides: List<HeroItem>, val reasons: Map<String, String>)

    private val KIDS = setOf("family", "kids", "children", "kid", "children's")

    fun isKids(h: HeroItem) = h.genres.any { it.lowercase().trim() in KIDS }

    /** The season's titles for [month] (1–12), or null when the month has none. */
    fun seasonal(month: Int): ((HeroItem) -> Boolean)? = when (month) {
        10 -> { h -> h.genres.any { it.equals("horror", true) } || h.name.contains("halloween", true) }
        12 -> { h ->
            val t = (h.name + " " + h.overview).lowercase()
            listOf("christmas", "xmas", "santa", "holiday", "noel").any { it in t } || h.genres.any { it.equals("holiday", true) }
        }
        2 -> { h -> h.genres.any { it.equals("romance", true) } }
        else -> null
    }

    /**
     * @param catalog the catalogs' titles, as they come; [details] fuller ones by key (genres, overview) when known
     * @param watching Continue Watching's titles (Live TV's cards left out by the caller)
     */
    fun pick(
        catalog: List<HeroItem>, details: Map<String, HeroItem>, hidden: Set<String>, kids: Boolean,
        watching: List<HeroItem>, because: Boolean, seasonalOn: Boolean, month: Int,
    ): Result {
        fun full(h: HeroItem) = details[h.key]?.let { d -> if (d.genres.isNotEmpty() || h.genres.isEmpty()) h.copy(genres = d.genres.ifEmpty { h.genres }, overview = d.overview.ifBlank { h.overview }) else h } ?: h
        var list = catalog.filter { it.key !in hidden }
        if (kids) list = list.filter { isKids(full(it)) }
        val season = if (seasonalOn) seasonal(month) else null
        if (season != null) list = list.sortedBy { if (season(full(it))) 0 else 1 }
        val reasons = HashMap<String, String>()
        if (!because || kids || watching.isEmpty()) return Result(list, reasons)

        // Each watched title's genres count towards what you like; a catalog title scores by the genres it shares.
        val seen = watching.map { it.key }.toSet()
        val weight = HashMap<String, Int>()
        for (w in watching) for (g in full(w).genres) weight[g.lowercase()] = (weight[g.lowercase()] ?: 0) + 1
        if (weight.isEmpty()) return Result(list, reasons)
        val scored = list.filter { it.key !in seen }.mapNotNull { h ->
            val gs = full(h).genres.map { it.lowercase() }
            val score = gs.sumOf { weight[it] ?: 0 }
            if (score <= 0) return@mapNotNull null
            // Why: the watched title sharing the most genres with it.
            val from = watching.maxByOrNull { w -> full(w).genres.count { it.lowercase() in gs } } ?: return@mapNotNull null
            Triple(h, score, from.name)
        }.sortedByDescending { it.second }.take(12)
        if (scored.isEmpty()) return Result(list, reasons)

        // Every third slide one of them (taken out of where it was), the rest in their order.
        val picks = scored.map { it.first }
        val rest = list.filter { p -> picks.none { it.key == p.key } }.toMutableList()
        val out = ArrayList<HeroItem>()
        var pi = 0
        while (rest.isNotEmpty() || pi < picks.size) {
            if (out.size % 3 == 2 && pi < picks.size) {
                out += picks[pi]; reasons[picks[pi].key] = "Because you watched ${scored[pi].third}"; pi++
            } else if (rest.isNotEmpty()) out += rest.removeAt(0)
            else { out += picks[pi]; reasons[picks[pi].key] = "Because you watched ${scored[pi].third}"; pi++ }
        }
        return Result(out, reasons)
    }
}
