package io.github.v0rest.skein.threads

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayNamesTest {
    private fun thread(sessionId: String?, name: String = "auto name") =
        SkeinThread("t1", "/p", sessionId, name, ThreadState.Working, 0, 0, null)

    @Test
    fun aSkeinNameWinsOverClaudeCodesOwnName() {
        assertEquals("mine", DisplayNames.thread(thread("s1"), mapOf("s1" to "mine")))
        assertEquals("mine", DisplayNames.session("s1", "fallback", mapOf("s1" to "mine")))
    }

    @Test
    fun withoutASkeinNameTheThreadKeepsItsName() {
        assertEquals("auto name", DisplayNames.thread(thread("s1"), mapOf("s2" to "other")))
        assertEquals("auto name", DisplayNames.thread(thread(null), mapOf("s2" to "other")))
        assertEquals("fallback", DisplayNames.session("s1", "fallback", emptyMap()))
    }

    @Test
    fun clearCarriesTheNameToTheThreadsNewSession() {
        val carried = DisplayNames.carry(mapOf("t1" to "s1"), mapOf("t1" to "s2"), mapOf("s1" to "x"))
        assertEquals(mapOf("s1" to "x", "s2" to "x"), carried)
    }

    @Test
    fun carryKeepsANameTheNewSessionAlreadyHas() {
        val names = mapOf("s1" to "x", "s2" to "own")
        assertEquals(names, DisplayNames.carry(mapOf("t1" to "s1"), mapOf("t1" to "s2"), names))
    }

    @Test
    fun carryIgnoresNewThreadsAndClosedOnes() {
        val names = mapOf("s1" to "x")
        assertEquals(names, DisplayNames.carry(mapOf("t1" to "s1"), mapOf("t2" to "s9"), names))
        assertEquals(names, DisplayNames.carry(mapOf("t1" to null), mapOf("t1" to "s2"), names))
        assertEquals(names, DisplayNames.carry(mapOf("t1" to "s1"), mapOf("t1" to null), names))
    }
}
