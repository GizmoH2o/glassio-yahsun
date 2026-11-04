package com.glasslauncher.tv.data

import android.content.Context

/** The changelog, as built into the app: what a version changed, for What's new after an update. */
object Changelog {
    /** A line of What's new: a heading, or a point under one. */
    data class Line(val text: String, val heading: Boolean)

    /** One version's lines, as plain text: bullets without their mark, no bold marks. Pure. */
    internal fun section(text: String, version: String): List<Line> {
        val lines = text.lines()
        val start = lines.indexOfFirst { it.startsWith("## ") && it.removePrefix("## ").trim().substringBefore(' ') == version }
        if (start < 0) return emptyList()
        return lines.drop(start + 1).takeWhile { !it.startsWith("## ") }.map { it.trim() }.filter { it.isNotBlank() }
            .map { Line(it.removePrefix("- ").removePrefix("* ").replace("*", ""), !it.startsWith("- ") && !it.startsWith("* ")) }
    }

    fun forVersion(ctx: Context, version: String): List<Line> = runCatching {
        section(ctx.assets.open("CHANGELOG.md").bufferedReader().use { it.readText() }, version)
    }.getOrDefault(emptyList())
}
