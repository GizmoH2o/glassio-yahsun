package com.glasslauncher.tv.data

import org.json.JSONArray
import org.json.JSONObject

/** A Home row of your own. [slot] is its row (Mine1…), so it keeps its place however the others change. */
data class MyCollection(val slot: Int, val name: String, val titles: List<HeroItem>)

/** Your own Home rows, kept in Settings as JSON. Pure. */
object Collections {
    /** How many there can be (each is a row of Home's), and how many titles each holds. */
    const val MAX = 6
    const val TITLES = 60

    fun row(slot: Int) = "Mine$slot"
    fun slotOf(row: String): Int? = row.removePrefix("Mine").takeIf { row.startsWith("Mine") }?.toIntOrNull()

    /** [list] with a new, empty collection; null when there's no name or no room. */
    fun create(list: List<MyCollection>, name: String): List<MyCollection>? {
        val slot = (1..MAX).firstOrNull { n -> list.none { it.slot == n } } ?: return null
        if (name.isBlank()) return null
        return (list + MyCollection(slot, name.trim(), emptyList())).sortedBy { it.slot }
    }

    fun delete(list: List<MyCollection>, slot: Int) = list.filterNot { it.slot == slot }

    fun rename(list: List<MyCollection>, slot: Int, name: String) =
        if (name.isBlank()) list else list.map { if (it.slot == slot) it.copy(name = name.trim()) else it }

    fun holds(c: MyCollection, item: HeroItem) = c.titles.any { it.key == item.key }

    /** [item] added to the front of the collection in [slot], or taken out if it's there. */
    fun toggle(list: List<MyCollection>, slot: Int, item: HeroItem) = list.map { c ->
        if (c.slot != slot) c
        else if (holds(c, item)) c.copy(titles = c.titles.filterNot { it.key == item.key })
        else c.copy(titles = (listOf(item) + c.titles).take(TITLES))
    }

    fun write(list: List<MyCollection>): String {
        val a = JSONArray()
        for (c in list) {
            val titles = JSONArray()
            for (h in c.titles) titles.put(JSONObject().put("key", h.key).put("id", h.id).put("type", h.type).put("name", h.name)
                .put("poster", h.poster).put("background", h.background).put("rating", h.rating).put("year", h.year).put("genres", JSONArray(h.genres)))
            a.put(JSONObject().put("slot", c.slot).put("name", c.name).put("titles", titles))
        }
        return a.toString()
    }

    fun parse(json: String): List<MyCollection> = runCatching {
        val a = JSONArray(json)
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val t = o.getJSONArray("titles")
            MyCollection(o.getInt("slot"), o.getString("name"), (0 until t.length()).map { j ->
                val h = t.getJSONObject(j)
                val g = h.optJSONArray("genres")
                HeroItem(
                    h.getString("key"), h.optString("id"), h.optString("type"), h.optString("name"), h.optString("poster"), h.optString("background"), "", "",
                    h.optString("rating"), h.optString("year"), g?.let { x -> (0 until x.length()).map { k -> x.optString(k) } }.orEmpty(), "",
                )
            })
        }
    }.getOrDefault(emptyList())
}
