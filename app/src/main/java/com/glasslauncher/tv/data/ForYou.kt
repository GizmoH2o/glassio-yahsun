package com.glasslauncher.tv.data

/**
 * Home's For You row: picks worked out on the TV alone. Your taste is a weight per genre, from what you watch (the latest
 * most), what you saved and what you turned down; the picks come from the titles the app already has, so none of this
 * asks a server anything or tells one what you watch. Pure.
 */
object ForYou {
    /** How many titles the row holds, and how many of them one genre may lead. */
    const val ROW = 20
    private const val PER_GENRE = 10
    private const val DAY = 24 * 3600_000L
    /** A watch counts half as much after this long. */
    private const val HALF_LIFE = 30.0
    private const val SAVED = 0.4
    private const val TURNED_DOWN = 1.0

    private fun words(h: HeroItem) = h.genres.flatMap { it.lowercase().split("&") }.map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** Your taste: each genre's weight. A genre you've turned down more than you watch goes below zero. */
    fun taste(watching: List<HeroItem>, saved: List<HeroItem>, turnedDown: List<HeroItem>, now: Long): Map<String, Double> {
        val out = HashMap<String, Double>()
        fun add(h: HeroItem, by: Double) { for (g in words(h)) out[g] = (out[g] ?: 0.0) + by }
        for (h in watching) add(h, Math.pow(0.5, (now - h.watchedAt).coerceAtLeast(0L).toDouble() / DAY / HALF_LIFE))
        for (h in saved) add(h, SAVED)
        for (h in turnedDown) add(h, -TURNED_DOWN)
        return out
    }

    /** How well [h] fits: the weights of its genres, shared out, so a title that's only what you like beats one that's partly. */
    private fun fit(h: HeroItem, taste: Map<String, Double>): Double {
        val g = words(h)
        return if (g.isEmpty()) 0.0 else g.sumOf { taste[it] ?: 0.0 } / g.size
    }

    /**
     * The row from [pool], without [skip] (keys: what you have, are watching or turned down): the best fits, then the best
     * rated; each title once, and no genre leading more than its share while others have titles.
     */
    fun pick(pool: List<HeroItem>, taste: Map<String, Double>, skip: Set<String>): List<HeroItem> {
        val ranked = pool.distinctBy { it.key }.filter { it.key !in skip }
            .map { it to fit(it, taste) }.filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<HeroItem, Double>> { it.second }.thenByDescending { it.first.rating.toDoubleOrNull() ?: 0.0 }.thenBy { it.first.key })
            .map { it.first }
        val lead = HashMap<String, Int>()
        val first = ArrayList<HeroItem>()
        val later = ArrayList<HeroItem>()
        for (h in ranked) {
            // Led by its genre you like most.
            val g = words(h).maxByOrNull { taste[it] ?: 0.0 }.orEmpty()
            if ((lead[g] ?: 0) < PER_GENRE) { lead[g] = (lead[g] ?: 0) + 1; first += h } else later += h
        }
        return (first + later).take(ROW)
    }
}
