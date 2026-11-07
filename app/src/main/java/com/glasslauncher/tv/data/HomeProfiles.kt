package com.glasslauncher.tv.data

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import org.json.JSONArray
import org.json.JSONObject

/**
 * Profiles on Home: each person's own Home settings (the KEYS below: rows, Continue Watching's sources and what's
 * kept, the accent, text size, the big picture's picks and hidden titles), swapped in when they become the profile on.
 * A kids profile turns Kids mode on (family titles only, chosen apps only, the PIN to leave). The accounts themselves
 * (Nuvio, Stremio, Trakt) are the TV's: a profile picks which of them its Continue Watching reads.
 */
class HomeProfiles(ctx: Context, private val settings: Settings) {
    data class Profile(val id: String, val name: String, val kids: Boolean)

    companion object {
        /** The settings each profile has its own. */
        val KEYS = listOf(
            "accent", "textSize", "homeSource", "homeAiring", "homeMovies", "homeSeries", "homeExpand", "homeFollow",
            "continueMode", "cwTrakt", "cwNuvio", "cwStremio", "cwLegacy", "cwDays", "cwNewFirst", "cwUpNext", "cwCatchup", "titleRich",
            "heroHidden", "heroBecause", "heroSeasonal", "homeTicker", "kidsMode", "kidsApps",
            "homeOrder", "homeHidden", "homeNames", "homeCollections",
            "widgetsRow", "widgetWeather", "calLinks", "haEntities", "trailerMode", "recsMode", "cardOk",
        )
    }

    private val sp = ctx.getSharedPreferences("glass_profiles", Context.MODE_PRIVATE)
    /** Bumped on every change: composables reading [all] or [current] follow. */
    val rev = mutableIntStateOf(0)

    fun all(): List<Profile> {
        rev.intValue
        val a = runCatching { JSONArray(sp.getString("list", "[]")) }.getOrDefault(JSONArray())
        val out = (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            .map { Profile(it.optString("id"), it.optString("name"), it.optBoolean("kids")) }
        return out.ifEmpty { listOf(Profile("main", "Me", false)) }
    }

    fun current(): Profile {
        rev.intValue
        val id = sp.getString("current", "main")
        return all().firstOrNull { it.id == id } ?: all().first()
    }

    private fun save(list: List<Profile>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("kids", it.kids)) }
        sp.edit().putString("list", a.toString()).commit()
        rev.intValue++
    }

    private fun data(id: String): JSONObject = runCatching { JSONObject(sp.getString("data:$id", "{}")) }.getOrDefault(JSONObject())

    /** A new profile, with Home as it is now (a kids one in Kids mode). Not switched to. */
    fun add(name: String, kids: Boolean): Profile {
        val p = Profile("p" + System.currentTimeMillis().toString(36), name.trim().ifBlank { if (kids) "Kids" else "Profile" }, kids)
        val d = settings.snapshot(KEYS)
        d.put("kidsMode", JSONObject().put("b", kids))
        if (kids) {
            d.remove("heroHidden")
            d.put("continueMode", JSONObject().put("s", "off"))
        }
        sp.edit().putString("data:${p.id}", d.toString()).commit()
        save(all() + p)
        return p
    }

    fun rename(id: String, name: String) {
        if (name.isBlank()) return
        save(all().map { if (it.id == id) it.copy(name = name.trim()) else it })
    }

    /** Takes a profile out (not the one on, nor the last). */
    fun delete(id: String): Boolean {
        val list = all()
        if (list.size <= 1 || current().id == id) return false
        save(list.filterNot { it.id == id })
        sp.edit().remove("data:$id").commit()
        return true
    }

    /** [id] becomes the profile on: this one's Home settings kept for it, [id]'s put in place. */
    fun switchTo(id: String) {
        val now = current()
        if (now.id == id) return
        val target = all().firstOrNull { it.id == id } ?: return
        // The first time: "Me" is written into the list, with Home as it is.
        if (sp.getString("list", null) == null) save(all())
        sp.edit().putString("data:${now.id}", settings.snapshot(KEYS).toString()).putString("current", target.id).commit()
        settings.restore(KEYS, data(target.id))
        rev.intValue++
    }
}
