package com.glasslauncher.tv

import com.glasslauncher.tv.data.HomeReplace
import com.glasslauncher.tv.data.HomeReplace.Phase
import com.glasslauncher.tv.data.HomeReplace.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A pretend TV: the launcher outranks the recovery screen, which outranks Glassio. */
class HomeReplaceTest {
    private val me = "com.glassioyahsun.tv"
    private val launcher = "com.google.android.apps.tv.launcherx"
    private val setup = "com.google.android.tungsten.setupwraith"
    private val activity = "com.glasslauncher.tv.MainActivity"
    private val all = linkedMapOf(launcher to "$launcher/.home.HomeActivity", setup to "$setup/.RecoveryActivity", me to "$me/$activity")
    private val off = mutableSetOf<String>()
    private val homesSeen = mutableListOf<String>()
    private val homesSet = mutableListOf<String>()
    private var state = State()
    private var wifi = "cname=android.uid.system:1000"
    private var stuckOff = false
    private var stuckOn = false
    private var adb = true
    private var role = me
    private var dieAt = ""
    private fun home() = all.filterKeys { it !in off }.values.first()
    private val h = HomeReplace(me, activity, object : HomeReplace.Store {
        override fun read() = state
        override fun write(s: State) { state = State.of(s.json()) }
    }) { c ->
        if (dieAt.isNotEmpty() && dieAt in c) throw IllegalStateException("cut off")
        if (!adb) Result.failure(Exception("no adb")) else Result.success(when {
            "query-activities" in c -> all.filterKeys { it !in off }.values.joinToString("\n", "3 activities found:\n") { "  Activity #0:\n    priority=2 match=0x1\n    $it" }
            "resolve-activity" in c -> "priority=2 isDefault=true\n" + home()
            "set-home" in c -> { homesSet += c.substringAfter("'").dropLast(1); "Success" }
            "dumpsys wifi" in c -> wifi
            "dumpsys role" in c -> "name=android.app.role.HOME\n          holders=$role\n"
            "disable-user" in c -> { if (!stuckOn) off += c.substringAfterLast(' '); homesSeen += home(); "" }
            "pm enable" in c -> { if (!stuckOff) off -= c.substringAfterLast(' '); homesSeen += home(); "" }
            "packages -d" in c -> off.joinToString("\n") { "package:$it" }
            else -> error(c)
        })
    }
    private fun turnOn(ready: Boolean = true) = h.turnOn(1L) { ready }

    @Test fun onMakesGlassioTheHome() {
        assertNull(turnOn())
        assertEquals(Phase.ON, state.phase)
        assertEquals(setOf(launcher, setup), off)
        assertTrue(home().startsWith(me))
    }
    @Test fun theRecoveryScreenIsNeverTheHomeInBetween() {
        turnOn(); h.turnOff()
        assertEquals(4, homesSeen.size)
        assertFalse(homesSeen.any { it.startsWith(setup) })
    }
    @Test fun offPutsEverythingBack() {
        turnOn()
        assertNull(h.turnOff())
        assertTrue(off.isEmpty())
        assertEquals(State(), state)
    }
    @Test fun theHomeThatWasThereIsGivenBack() {
        role = launcher
        turnOn(); h.turnOff()
        assertEquals("$launcher/.home.HomeActivity", homesSet.last())
    }
    @Test fun anAppAlreadyOffStaysOffAfterTheUndo() {
        off += setup
        turnOn(); h.turnOff()
        assertEquals(setOf(setup), off)
    }
    @Test fun wifiOwnerStopsIt() {
        wifi = "cname=$setup"
        assertNotNull(turnOn()); assertTrue(off.isEmpty()); assertEquals(Phase.OFF, state.phase)
    }
    @Test fun noRescueNoSwitch() { assertNotNull(turnOn(false)); assertTrue(off.isEmpty()) }
    @Test fun noAdbNoSwitch() {
        adb = false
        assertEquals(HomeReplace.NO_ADB, turnOn()); assertEquals(Phase.OFF, state.phase)
    }
    @Test fun noGoogleHomeNoSwitch() { off += launcher; assertNotNull(turnOn()); assertEquals(setOf(launcher), off) }
    @Test fun refusedByTheTvIsUndone() {
        stuckOn = true
        assertNotNull(turnOn()); assertTrue(off.isEmpty()); assertEquals(Phase.OFF, state.phase)
    }
    @Test fun cutShortIsUndoneAtTheNextStart() {
        dieAt = "disable-user --user 0 $launcher"
        runCatching { turnOn() }
        assertEquals(Phase.TURNING_ON, state.phase); assertTrue(setup in off)
        dieAt = ""
        h.verify()
        assertTrue(off.isEmpty()); assertEquals(Phase.OFF, state.phase)
    }
    @Test fun whatDoesntComeBackIsKeptAndNamed() {
        turnOn(); stuckOff = true
        assertTrue(h.turnOff()!!.contains("Google TV's home"))
        assertEquals(Phase.UNDOING, state.phase)
        stuckOff = false
        h.verify()
        assertTrue(off.isEmpty()); assertEquals(Phase.OFF, state.phase)
    }
    @Test fun aTvUpdateTurningItBackOnIsFixed() {
        turnOn(); off -= launcher
        assertEquals(listOf(launcher), h.verify()); assertTrue(launcher in off)
    }
    @Test fun noAdbAtStartIsSaidAndStaysOn() {
        turnOn(); adb = false; h.verify()
        assertEquals(Phase.ON, state.phase); assertEquals(HomeReplace.NO_ADB, state.attention)
    }
    @Test fun theRescueLeavesItsNoteAndStaysOff() {
        turnOn(); h.turnOff("crashed"); h.verify()
        assertEquals("crashed", state.note); assertEquals(Phase.OFF, state.phase); assertTrue(off.isEmpty())
    }
    @Test fun stateCantNameOtherApps() {
        assertEquals(listOf(setup), State.of(State(Phase.ON, listOf(setup, "com.tcl.tv")).json()).disabled)
        assertEquals(State(), State.of("broken"))
    }
    @Test fun crashesCounted() {
        assertTrue(HomeReplace.crashedTooOften(listOf(1_000, 2_000, 3_000), 500, 4_000))
        assertFalse(HomeReplace.crashedTooOften(listOf(1_000, 2_000), 500, 4_000))
        assertFalse(HomeReplace.crashedTooOften(listOf(1_000, 2_000, 3_000), 1_500, 4_000))
        assertFalse(HomeReplace.crashedTooOften(listOf(1_000, 2_000, 400_000), 500, 400_500))
    }
}
