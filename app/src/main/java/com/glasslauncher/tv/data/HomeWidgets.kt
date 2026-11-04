package com.glasslauncher.tv.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Home's widgets row: what its calendar and Home Assistant cards read. (The weather card reads Weather.days.)
 * Blocking: called off the main thread.
 */
object HomeWidgets {
    /** The next events of the calendars (Settings › Home › Calendars): within [days] days, soonest first. */
    data class Agenda(val next: List<Ics.Occurrence>, val failed: Int)

    fun agenda(http: Http, links: List<String>, days: Long = 30, now: Instant = Instant.now()): Agenda {
        var failed = 0
        val all = links.flatMap { link ->
            runCatching {
                // webcal:// is the same file over https.
                val url = link.trim().replaceFirst(Regex("^webcals?://", RegexOption.IGNORE_CASE), "https://")
                Ics.between(Ics.parse(http.text(url, 10 * 60_000L), ZoneId.systemDefault()), now, now.plusSeconds(days * 86_400))
            }.getOrElse { Glog.w("Calendar", it); failed++; emptyList() }
        }
        // The same event in two calendars (shared ones): once.
        return Agenda(all.distinctBy { it.title to it.start }.sortedBy { it.start }, failed)
    }

    /** One Home Assistant entity as its card shows it: its name, its state in words or a number, the unit. */
    data class Reading(val id: String, val name: String, val value: String, val unit: String, val kind: String)

    /** Home Assistant's REST API, with its long-lived token (never cached: readings change). */
    private fun ha(base: String, token: String, path: String): String {
        require(base.startsWith("http")) { "Set Home Assistant's address first (Settings › Accounts & Home Assistant)" }
        require(token.isNotBlank()) { "Set Home Assistant's token first (Settings › Accounts & Home Assistant)" }
        val c = URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 8000
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Content-Type", "application/json")
        try {
            val code = try { c.responseCode } catch (e: java.io.IOException) { error("Couldn't reach Home Assistant at $base") }
            when (code) {
                in 200..299 -> return c.inputStream.bufferedReader().use { it.readText() }
                401, 403 -> error("Home Assistant refused the token")
                404 -> error("Not found on Home Assistant")
                else -> error("Home Assistant answered HTTP $code")
            }
        } finally { c.disconnect() }
    }

    /** The chosen entities' readings, in the order chosen (one that's gone from Home Assistant: left out). */
    fun readings(base: String, token: String, ids: List<String>): List<Reading> = ids.mapNotNull { id ->
        runCatching { reading(JSONObject(ha(base, token, "/api/states/$id"))) }.getOrElse { Glog.w("Home Assistant $id", it); null }
    }

    /** Every entity worth a card (sensors, doors, locks, climate, people...), by name, for choosing in Settings. */
    fun all(base: String, token: String): List<Reading> {
        val a = JSONArray(ha(base, token, "/api/states"))
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::reading) }
            .filter { it.id.substringBefore('.') in SHOWN }.sortedBy { it.name.lowercase() }
    }

    private val SHOWN = setOf("sensor", "binary_sensor", "lock", "climate", "cover", "person", "device_tracker", "weather",
        "switch", "light", "fan", "input_boolean", "input_number", "alarm_control_panel", "water_heater", "vacuum", "media_player")

    private fun reading(j: JSONObject): Reading {
        val id = j.optString("entity_id")
        val at = j.optJSONObject("attributes") ?: JSONObject()
        val unit = at.optString("unit_of_measurement").takeIf { it.isNotBlank() && it != "null" }.orEmpty()
        val cls = at.optString("device_class").takeIf { it != "null" }.orEmpty()
        val state = when (id.substringBefore('.')) {
            // Climate: the room's temperature (its state is the mode).
            "climate", "water_heater" -> at.optString("current_temperature").takeIf { it.isNotBlank() && it != "null" } ?: j.optString("state")
            else -> j.optString("state")
        }
        val u = if (id.startsWith("climate.") || id.startsWith("water_heater.")) "°" else unit
        return Reading(id, at.optString("friendly_name").ifBlank { id }, text(id.substringBefore('.'), state, cls), if (number(state) != null) u else "", cls)
    }

    private fun number(v: String) = v.toDoubleOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() }

    /** A state as words: doors Open or Closed, motion Detected or Clear, numbers to one decimal, on/off as On/Off. */
    internal fun text(domain: String, state: String, deviceClass: String): String {
        number(state)?.let { n ->
            val r = if (abs(n) >= 100) n.roundToLong().toDouble() else Math.round(n * 10) / 10.0
            return if (r == Math.floor(r)) r.toLong().toString() else r.toString()
        }
        val on = state == "on"
        return when {
            state == "unavailable" || state == "unknown" -> "—"
            domain == "binary_sensor" && state in setOf("on", "off") -> when (deviceClass) {
                "door", "window", "opening", "garage_door" -> if (on) "Open" else "Closed"
                "motion", "occupancy", "presence" -> if (on) "Detected" else "Clear"
                "moisture" -> if (on) "Wet" else "Dry"
                "smoke", "gas", "carbon_monoxide", "problem", "safety", "tamper" -> if (on) "Alert" else "OK"
                "connectivity" -> if (on) "Connected" else "Offline"
                "lock" -> if (on) "Unlocked" else "Locked"
                "battery" -> if (on) "Low" else "OK"
                else -> if (on) "On" else "Off"
            }
            domain == "person" || domain == "device_tracker" -> when (state) { "home" -> "Home"; "not_home" -> "Away"; else -> state }
            state == "on" -> "On"
            state == "off" -> "Off"
            else -> state.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
    }
}
