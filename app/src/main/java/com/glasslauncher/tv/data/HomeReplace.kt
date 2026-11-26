package com.glasslauncher.tv.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * "Replace Google TV's home": Google's launcher (and its recovery screen, next in line) turned off, so the Home button
 * opens Glassio itself instead of Google TV's home with Glassio over it. Off, not removed: everything back in one press.
 *
 * Only the steps, through the TV's own adb shell ([sh]); [store] keeps what was done (written before each step, so a
 * switch cut short is undone by [verify]).
 */
class HomeReplace(private val pkg: String, private val activity: String, private val store: Store, private val sh: (String) -> Result<String>) {
    interface Store { fun read(): State; fun write(s: State) }

    enum class Phase { OFF, TURNING_ON, ON, UNDOING }

    /** [disabled]: the apps Glassio turned off (and only those); [home]: the home screen to give back, when it wasn't Glassio. */
    data class State(val phase: Phase = Phase.OFF, val disabled: List<String> = emptyList(), val home: String = "",
        val since: Long = 0, val note: String = "", val attention: String = "") {
        fun json(): String = JSONObject().put("phase", phase.name).put("disabled", JSONArray(disabled)).put("home", home)
            .put("since", since).put("note", note).put("attention", attention).toString()
        companion object {
            fun of(text: String): State = runCatching {
                val j = JSONObject(text)
                val a = j.optJSONArray("disabled") ?: JSONArray()
                State(Phase.valueOf(j.getString("phase")), (0 until a.length()).map { a.getString(it) }.filter { it in ALLOWED },
                    j.optString("home"), j.optLong("since"), j.optString("note"), j.optString("attention"))
            }.getOrDefault(State())
        }
    }

    private class Stop(why: String) : Exception(why)

    private fun run(cmd: String): String = sh(cmd).getOrElse { throw Stop(NO_ADB) }
    private fun components(out: String) = out.lines().map { it.trim() }.filter { COMPONENT.matches(it) }
    private fun homes() = components(run("cmd package query-activities --brief --user 0 $HOME"))
    private fun current() = components(run("cmd package resolve-activity --brief --user 0 $HOME")).lastOrNull().orEmpty()
    private fun holder() = ROLE.find(run("dumpsys role"))?.groupValues?.get(1).orEmpty()
    private fun ours() = current().startsWith("$pkg/")
    private fun isOff(p: String) = run("pm list packages -d --user 0").lines().any { it.trim() == "package:$p" }
    private fun setHome(c: String) = run("cmd package set-home-activity --user 0 '$c'").contains("Success")
    private fun off(p: String): Boolean { if (p !in ALLOWED) return false; run("pm disable-user --user 0 $p"); return isOff(p) }
    private fun on(p: String): Boolean { if (p !in ALLOWED) return false; run("pm enable --user 0 $p"); return !isOff(p) }

    /** Turns it on; null when done, else why not (with nothing left changed, or what didn't come back named). */
    fun turnOn(now: Long, rescueReady: () -> Boolean): String? {
        try {
            if (store.read().phase != Phase.OFF) verify()
            if (store.read().phase == Phase.ON) return null
            val homes = homes().map { it.substringBefore('/') }
            val targets = ALLOWED.filter { it in homes }
            if (targets.none { it in LAUNCHERS }) return "Google TV's home isn't in use on this TV"
            if (pkg !in homes) return "Glassio isn't one of this TV's home screens"
            // An app that is turned off loses the Wi-Fi networks it saved, for good.
            val owners = WIFI.findAll(run("dumpsys wifi | grep -o 'cname=[^ ]*'; true")).map { it.groupValues[1] }.toSet()
            if (targets.any { it in owners }) return "Google TV saved this TV's Wi-Fi, and turning it off would erase it. Forget the network, connect again, then try once more"
            if (!rescueReady()) return "The safety net can't reach the TV. Allow TV access (ADB) first"
            val was = if (holder() == pkg) "" else current().takeIf { !it.startsWith("$pkg/") }.orEmpty()
            store.write(State(Phase.TURNING_ON, emptyList(), was, now))
            if (!setHome("$pkg/$activity")) return undone("Android didn't accept Glassio as the home screen")
            // The recovery screen first: with the launcher off before it, it would be the home screen in between.
            for (p in targets) {
                store.write(store.read().let { it.copy(disabled = it.disabled + p) })
                if (!off(p)) return undone("The TV didn't let Google TV's home be turned off")
            }
            if (!ours()) return undone("The Home button still didn't lead to Glassio")
            store.write(store.read().copy(phase = Phase.ON, note = "", attention = ""))
            return null
        } catch (e: Stop) {
            return if (store.read().phase == Phase.OFF) e.message else undone(e.message.orEmpty())
        }
    }

    private fun undone(why: String): String = turnOff()?.let { "$why. $it" } ?: why

    /** Everything back; null when done, else what didn't come back (kept on the list, to try again). */
    fun turnOff(note: String = ""): String? {
        val s = store.read()
        if (s.phase == Phase.OFF) return null
        store.write(s.copy(phase = Phase.UNDOING, note = note.ifEmpty { s.note }))
        // The launcher first, so the recovery screen isn't the home screen in between.
        val left = s.disabled.reversed().filter { p -> !runCatching { on(p) }.getOrDefault(false) }
        if (left.isEmpty()) {
            if (COMPONENT.matches(s.home)) runCatching { setHome(s.home) }
            store.write(State(note = note.ifEmpty { s.note }))
            return null
        }
        val why = "Couldn't turn back on: " + left.joinToString { NAMES[it] ?: it }
        store.write(store.read().copy(disabled = left.reversed(), attention = why))
        return why
    }

    /** After a start: a switch cut short is undone; on, what the TV turned back on is turned off again (and named). */
    fun verify(): List<String> {
        val s = store.read()
        when (s.phase) {
            Phase.OFF -> return emptyList()
            Phase.TURNING_ON, Phase.UNDOING -> { turnOff(); return emptyList() }
            Phase.ON -> Unit
        }
        return try {
            val back = s.disabled.filter { !isOff(it) }
            val failed = back.filter { !off(it) }.toSet()
            if (!ours()) setHome("$pkg/$activity")
            store.write(store.read().copy(attention = when {
                failed.isNotEmpty() -> "The TV turned Google's home back on"
                !ours() -> "The Home button doesn't lead to Glassio"
                else -> ""
            }))
            back - failed
        } catch (e: Stop) {
            store.write(store.read().copy(attention = e.message.orEmpty()))
            emptyList()
        }
    }

    companion object {
        const val NO_ADB = "TV access (ADB) is off"
        private const val HOME = "-a android.intent.action.MAIN -c android.intent.category.HOME"
        /** The only apps ever turned off, in the order they are. */
        val ALLOWED = listOf("com.google.android.tungsten.setupwraith", "com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher")
        private val LAUNCHERS = ALLOWED.drop(1)
        private val NAMES = mapOf(ALLOWED[0] to "Google's recovery home", ALLOWED[1] to "Google TV's home", ALLOWED[2] to "Android TV's home")
        private val COMPONENT = Regex("[A-Za-z][\\w.]*/[\\w.$]+")
        private val ROLE = Regex("android\\.app\\.role\\.HOME\\s+holders=([\\w.]+)")
        private val WIFI = Regex("cname=([\\w.]+)")

        /** The home screen crashed or froze too often: its crashes' [times], those from before [since] not counting. */
        fun crashedTooOften(times: List<Long>, since: Long, now: Long): Boolean =
            times.count { it > since && now - it < 5 * 60_000L } >= 3
    }
}
