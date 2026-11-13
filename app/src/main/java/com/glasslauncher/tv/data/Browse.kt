package com.glasslauncher.tv.data

/**
 * The pages that aren't a title's but open like one (a genre, a person): they go on the same stack as title pages, so
 * Back, and a title opened from them, work the same. Each is a [HeroItem] of its own [HeroItem.type].
 */
object Browse {
    const val GENRE = "glassio-genre"
    const val PERSON = "glassio-person"

    /** A genre: its name here, TMDB's ids for films and series (null: none), the names catalogs give it, and a line. */
    data class Genre(val name: String, val movie: Int?, val tv: Int?, val aliases: Set<String>, val kids: Boolean, val line: String)

    val GENRES = listOf(
        Genre("Action", 28, 10759, setOf("action", "action & adventure", "adventure"), false, "Chases, fights and big set pieces"),
        Genre("Comedy", 35, 35, setOf("comedy"), true, "Feel-good and laugh-out-loud"),
        Genre("Drama", 18, 18, setOf("drama"), false, "Stories that stay with you"),
        Genre("Animation", 16, 16, setOf("animation", "anime"), true, "Animated films and series, anime too"),
        Genre("Sci-Fi", 878, 10765, setOf("science fiction", "sci-fi & fantasy", "sci-fi", "fantasy"), false, "Other worlds, other times"),
        Genre("Crime", 80, 80, setOf("crime"), false, "Heists, detectives and cold cases"),
        Genre("Documentary", 99, 99, setOf("documentary"), true, "Real people, real stories"),
        Genre("Family", 10751, 10751, setOf("family", "kids"), true, "Something everyone can watch"),
        Genre("Horror", 27, null, setOf("horror"), false, "Lights off"),
        Genre("Romance", 10749, null, setOf("romance"), false, "Love stories"),
    )

    /** The genres to offer: the gentle ones only in Kids mode. */
    fun genres(kids: Boolean) = if (kids) GENRES.filter { it.kids } else GENRES

    fun genreNamed(name: String) = GENRES.firstOrNull { it.name == name }

    /** Is [h] in [g] (by the names its catalog gives its genres)? Pure. */
    fun inGenre(h: HeroItem, g: Genre) = h.genres.any { it.lowercase() in g.aliases }

    fun genrePage(name: String) = HeroItem("$GENRE:$name", name, GENRE, name, "", "", "", "", "", "", emptyList(), "")

    fun personPage(p: TitlePeople.Person) = HeroItem("$PERSON:${p.id}", p.id.toString(), PERSON, p.name, p.photo, "", "", "", "", "", listOf(p.role), "")

    fun isPage(h: HeroItem) = h.type == GENRE || h.type == PERSON
}
