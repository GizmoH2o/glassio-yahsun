package com.glasslauncher.tv.data

/** Search's mood and time filters: what fits, across everything the app knows of. Pure. */
object Moods {
    /** A mood: any of [genres], none of [avoid]. Lower case. */
    data class Mood(val label: String, val genres: Set<String>, val avoid: Set<String> = emptySet())

    const val KIDS = "With the kids"
    /** How many titles the row shows, and how many are looked up at a time. */
    const val MAX = 40
    const val LOOKUP = 150

    val ALL = listOf(
        Mood("Funny", setOf("comedy")),
        Mood("Feel-good", setOf("comedy", "family", "romance", "music", "musical"), setOf("horror", "thriller", "crime", "war")),
        Mood("Exciting", setOf("action", "adventure", "thriller")),
        Mood("Scary", setOf("horror")),
        Mood("Romantic", setOf("romance"), setOf("horror")),
        Mood("True stories", setOf("documentary", "biography", "history")),
        Mood(KIDS, emptySet()),
    )

    /** The lengths offered, in minutes, and how each is called. */
    val TIMES = listOf(30 to "Under 30 min", 60 to "Under 1 hour", 120 to "Under 2 hours")

    private val HOURS = Regex("""(\d+)\s*h""")
    private val MINUTES = Regex("""(\d+)\s*m""")

    /** "126 min", "2h 6m", "2h" as minutes; null when it doesn't say. */
    fun minutes(runtime: String): Int? {
        val h = HOURS.find(runtime)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val m = MINUTES.find(runtime)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return (h * 60 + m).takeIf { it > 0 }
    }

    private fun words(h: HeroItem) = h.genres.flatMap { it.lowercase().split("&") }.map { it.trim() }.toSet()

    /** Whether [h] fits [mood] (null: any) and lasts at most [maxMinutes] (0: any; an unknown length doesn't pass). */
    fun fits(h: HeroItem, mood: Mood?, maxMinutes: Int): Boolean {
        if (maxMinutes > 0 && (minutes(h.runtime) ?: Int.MAX_VALUE) > maxMinutes) return false
        if (mood == null) return true
        if (mood.label == KIDS) return HeroPicks.isKids(h)
        val g = words(h)
        return g.any { it in mood.genres } && g.none { it in mood.avoid }
    }

    /** What fits from [all]: each title once, yours ([mine], by key) first, then the best rated. */
    fun pick(all: List<HeroItem>, mood: Mood?, maxMinutes: Int, mine: Set<String>, kids: Boolean): List<HeroItem> =
        all.distinctBy { it.id.ifBlank { it.key } }
            .filter { fits(it, mood, maxMinutes) && (!kids || HeroPicks.isKids(it)) }
            .sortedWith(compareBy<HeroItem> { if (it.key in mine) 0 else 1 }.thenByDescending { it.rating.toDoubleOrNull() ?: 0.0 })
            .take(MAX)

    /** Titles Cinemeta can tell more about: no length or no genres yet. */
    fun lacking(all: List<HeroItem>): List<HeroItem> =
        all.filter { it.id.matches(Regex("tt\\d{6,10}")) && (it.runtime.isBlank() || it.genres.isEmpty()) }
}
