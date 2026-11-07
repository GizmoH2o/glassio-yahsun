package com.glasslauncher.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sync with other TVs at home: changes noticed, merged (the newest wins, deletions too), signed, and pairing by a code. */
class LanSyncTest {
    private fun it(v: String, at: Long, by: String = "A", del: Boolean = false) = LanSync.Item(v, at, by, del)

    // ── what changed here

    @Test fun `a new thing, a changed one and one gone, noticed at once`() {
        val before = mapOf("fav:main" to it("[1,2]", 100), "follow" to it("{}", 100), "remind:x" to it("r", 100))
        val now = mapOf("fav:main" to "[1,2,3]", "follow" to "{}", "edits" to "{\"5\":1}")
        val seen = LanSync.observe(before, now, 500, "A")
        assertEquals(it("[1,2,3]", 500), seen["fav:main"])
        assertEquals(it("{}", 100), seen["follow"])                 // the same: its time stays
        assertEquals(it("{\"5\":1}", 500), seen["edits"])
        assertEquals(it("", 500, "A", true), seen["remind:x"])     // gone: remembered as gone
    }

    @Test fun `gone and still gone keeps its time`() {
        val before = mapOf("remind:x" to it("", 300, "B", true))
        assertEquals(it("", 300, "B", true), LanSync.observe(before, emptyMap(), 900, "A")["remind:x"])
    }

    // ── two TVs' changes together

    @Test fun `the newest wins, thing by thing`() {
        val a = mapOf("fav:main" to it("[1]", 100, "A"), "follow" to it("{a}", 300, "A"))
        val b = mapOf("fav:main" to it("[1,2]", 200, "B"), "follow" to it("{b}", 250, "B"), "edits" to it("{e}", 50, "B"))
        val m = LanSync.merge(a, b)
        assertEquals("[1,2]", m["fav:main"]!!.v)
        assertEquals("{a}", m["follow"]!!.v)
        assertEquals("{e}", m["edits"]!!.v)
        assertEquals(m, LanSync.merge(b, a))       // whichever TV merges, the same
    }

    @Test fun `a deletion newer than the change wins, an older one doesn't`() {
        val a = mapOf("remind:x" to it("", 300, "A", true))
        assertTrue(LanSync.merge(a, mapOf("remind:x" to it("r", 200, "B")))["remind:x"]!!.del)
        assertFalse(LanSync.merge(a, mapOf("remind:x" to it("r2", 400, "B")))["remind:x"]!!.del)
    }

    @Test fun `the same moment on both, one answer everywhere`() {
        val a = mapOf("follow" to it("{a}", 100, "A"))
        val b = mapOf("follow" to it("{b}", 100, "B"))
        assertEquals(LanSync.merge(a, b), LanSync.merge(b, a))
    }

    @Test fun `long-gone deletions are forgotten`() {
        val m = mapOf("remind:x" to it("", 0, "A", true), "remind:y" to it("", LanSync.FORGET_MS, "A", true))
        val kept = LanSync.prune(m, LanSync.FORGET_MS + 1000)
        assertNull(kept["remind:x"])
        assertTrue(kept.containsKey("remind:y"))
    }

    @Test fun `what to write here after a merge`() {
        val here = mapOf("fav:main" to "[1]", "remind:x" to "r", "follow" to "{}")
        val merged = mapOf("fav:main" to it("[1,2]", 200, "B"), "remind:x" to it("", 300, "B", true), "follow" to it("{}", 100),
            "edits" to it("{e}", 50, "B"))
        val w = LanSync.writes(here, merged)
        assertEquals(mapOf("fav:main" to "[1,2]", "remind:x" to null, "edits" to "{e}"), w)
    }

    // ── signing and pairing

    @Test fun `a message signed with the pair's secret, and nothing else, passes`() {
        val s = "c2VjcmV0LXNlY3JldA"
        val sig = LanSync.sign(s, "body")
        assertTrue(LanSync.verify(s, "body", sig))
        assertFalse(LanSync.verify(s, "body!", sig))
        assertFalse(LanSync.verify("other", "body", sig))
    }

    @Test fun `pairing by the code shown on the other TV`() {
        val salt = "c2FsdA"
        val keyA = LanSync.pairKey("482913", salt)
        val keyB = LanSync.pairKey("482913", salt)
        val wrong = LanSync.pairKey("482914", salt)
        val proofA = LanSync.proof(keyA, "A", "nonceB")
        assertEquals(proofA, LanSync.proof(keyB, "A", "nonceB"))         // B checks the TV showing the code
        assertTrue(proofA != LanSync.proof(wrong, "A", "nonceB"))
        assertTrue(LanSync.proof(keyA, "A", "n") != LanSync.proof(keyA, "B", "n"))    // A's proof isn't B's
        assertEquals(LanSync.secret(keyA, "nA", "nB"), LanSync.secret(keyB, "nA", "nB"))
        assertTrue(LanSync.secret(keyA, "nA", "nB") != LanSync.secret(keyA, "nA", "nC"))
    }

    @Test fun `a code is six digits`() {
        repeat(50) { assertTrue(LanSync.newCode().matches(Regex("\\d{6}"))) }
    }
}
