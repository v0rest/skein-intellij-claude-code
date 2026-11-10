package io.github.v0rest.skein.threads

import org.junit.Assert.assertEquals
import org.junit.Test

class TabTitlesTest {
    private val session = "55555555-5555-4555-8555-555555555555"

    private fun thread(id: String, name: String, sessionId: String? = null) =
        SkeinThread(id, "/w/acme", sessionId, name, ThreadState.Working, 0, 0, null)

    @Test
    fun onlyChangedTitlesArePushed() {
        val pushed = mapOf("t1" to "acme", "t2" to "feat-x")
        val threads = listOf(thread("t1", "acme"), thread("t2", "feat-y"), thread("t3", "acme"))
        assertEquals(mapOf("t2" to "feat-y", "t3" to "acme"), TabTitles.changed(pushed, threads, emptyMap()))
        assertEquals(emptyMap<String, String>(), TabTitles.changed(TabTitles.remembered(threads, emptyMap()), threads, emptyMap()))
    }

    @Test
    fun aSkeinNameWinsOverClaudeCodesName() {
        val threads = listOf(thread("t1", "claude code's name", session))
        val names = mapOf(session to "acme")
        assertEquals(mapOf("t1" to "acme"), TabTitles.changed(emptyMap(), threads, names))
        assertEquals(mapOf("t1" to "acme"), TabTitles.remembered(threads, names))
        // the Skein name taken away: Claude Code's name comes back
        assertEquals(mapOf("t1" to "claude code's name"), TabTitles.changed(mapOf("t1" to "acme"), threads, emptyMap()))
    }

    @Test
    fun aClosedThreadIsForgotten() {
        val pushed = TabTitles.remembered(listOf(thread("t1", "acme"), thread("t2", "feat-x")), emptyMap())
        val remaining = listOf(thread("t2", "feat-x"))
        assertEquals(emptyMap<String, String>(), TabTitles.changed(pushed, remaining, emptyMap()))
        val after = TabTitles.remembered(remaining, emptyMap())
        assertEquals(mapOf("t2" to "feat-x"), after)
        // a tab with the same id again starts without a title, so it is pushed again
        assertEquals(mapOf("t1" to "acme"), TabTitles.changed(after, listOf(thread("t1", "acme")), emptyMap()))
    }
}
