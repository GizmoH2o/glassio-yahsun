package com.glasslauncher.tv.data

/** The furthest episode finished of a series, in any app. [hidden]: its next episode's card was taken out. */
data class Finished(val imdb: String, val season: Int, val episode: Int, val at: Long, val app: String, val hidden: Boolean = false) {
    val key: String get() = "series:$imdb"
}

/**
 * Continue Watching's rules: what takes a finished episode's place, and the order of the one row that holds what's in
 * progress in every app with Live TV's cards. Pure.
 */
object UpNext {
    /** How many series are remembered. */
    const val KEEP = 40
    private const val DAY = 24 * 3600_000L
    /** How long a series you finished an episode of stays worth a card. */
    private const val LATELY = 60 * DAY
    /** How long an episode counts as new, and how far back a new one brings a series back. */
    private const val NEW = 14 * DAY
    private const val YEAR = 365 * DAY

    private fun Finished.isPast(season: Int, episode: Int) = this.season > season || this.season == season && this.episode >= episode

    /** [list] knowing [f]: the furthest episode stands for the series, dated by its latest watch; newest first. */
    fun note(list: List<Finished>, f: Finished): List<Finished> {
        val old = list.firstOrNull { it.imdb == f.imdb }
        val kept = when {
            old == null -> f
            f.season == old.season && f.episode == old.episode -> old.copy(at = maxOf(old.at, f.at))
            old.isPast(f.season, f.episode) -> old.copy(at = maxOf(old.at, f.at))
            else -> f.copy(at = maxOf(old.at, f.at))
        }
        return (list.filterNot { it.imdb == f.imdb } + kept).sortedByDescending { it.at }.take(KEEP)
    }

    /** The series' card out, until a further episode is finished. */
    fun hide(list: List<Finished>, imdb: String): List<Finished> = list.map { if (it.imdb == imdb) it.copy(hidden = true) else it }

    /** The first episode out after S[season] E[episode]; specials never. Null: none yet. */
    fun next(eps: List<Episode>, season: Int, episode: Int, now: Long): Episode? =
        eps.filter { it.season > 0 && it.released <= now && (it.season > season || it.season == season && it.episode > episode) }
            .minWithOrNull(compareBy<Episode> { it.season }.thenBy { it.episode })

    /** Worth a card: finished lately, or a new episode out for something not left for years. */
    fun fresh(f: Finished, next: Episode, now: Long): Boolean =
        now - f.at <= LATELY || (next.released >= now - NEW && now - f.at <= YEAR)

    private fun CwEntry.overtakes(f: Finished) = at >= f.at || !f.isPast(season, episode)

    /** The series whose next episode gets a card: not taken out, and nothing started in them since. */
    fun waiting(finished: List<Finished>, entries: List<CwEntry>): List<Finished> =
        finished.filter { f -> !f.hidden && entries.none { it.imdb == f.imdb && it.overtakes(f) } }

    /** What keeps its card as in progress: not what another app still has half watched after you finished it. */
    fun inProgress(entries: List<CwEntry>, finished: List<Finished>): List<CwEntry> {
        val next = waiting(finished, entries).map { it.imdb }.toHashSet()
        return entries.filterNot { it.imdb in next }
    }

    /**
     * The one row: what's on now first (a card with no [HeroItem.watchedAt]: as they came), then everything else by when
     * you watched it; with [newFirst], the series in [newer] ahead of those.
     */
    fun order(live: List<HeroItem>, watching: List<HeroItem>, newer: Set<String>, newFirst: Boolean): List<HeroItem> {
        val rest = (live + watching).filter { it.watchedAt > 0 }.sortedByDescending { it.watchedAt }
        val now = (live + watching).filter { it.watchedAt <= 0 }
        return now + if (newFirst) rest.sortedBy { if (it.key in newer) 0 else 1 } else rest
    }
}
