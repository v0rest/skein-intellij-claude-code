package io.github.v0rest.skein.ui

import io.github.v0rest.skein.claude.WaitReason
import io.github.v0rest.skein.threads.SkeinThread
import io.github.v0rest.skein.threads.ThreadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WaitingCounterTest {
    private fun thread(id: String, state: ThreadState, pinned: Boolean = false, createdAt: Long = 0, sessionId: String? = null) =
        SkeinThread(id = id, folder = "/w/acme", sessionId = sessionId, name = id, state = state, stateSince = 0, createdAt = createdAt, anchor = null, pinned = pinned)

    private fun waiting(since: Long) = ThreadState.NeedsYou(WaitReason.PERMISSION, since)

    @Test
    fun countsOnlyThreadsThatNeedYou() {
        val result = WaitingCounter.of(
            listOf(
                thread("a", waiting(10)),
                thread("b", ThreadState.YourTurn(5)),
                thread("c", ThreadState.Working),
                thread("d", waiting(20), sessionId = "55555555-5555-4555-8555-555555555555"),
            ),
            mapOf("55555555-5555-4555-8555-555555555555" to "Fix login"),
        )
        assertEquals(2, result.count)
        assertEquals(listOf("a", "Fix login"), result.names)
    }

    @Test
    fun theLongestWaitingComesFirst() {
        val result = WaitingCounter.of(
            listOf(
                thread("pinned-recent", waiting(900), pinned = true),
                thread("older", waiting(100)),
                thread("tie-late", waiting(500), createdAt = 9),
                thread("tie-early", waiting(500), createdAt = 1),
            ),
            emptyMap(),
        )
        assertEquals("older", result.longestWaitingId)
        assertEquals(listOf("older", "tie-early", "tie-late", "pinned-recent"), result.names)
    }

    @Test
    fun zeroWhenNobodyWaits() {
        val result = WaitingCounter.of(listOf(thread("a", ThreadState.YourTurn(1)), thread("b", ThreadState.Idle)), emptyMap())
        assertEquals(0, result.count)
        assertEquals(emptyList<String>(), result.names)
        assertNull(result.longestWaitingId)
    }

    @Test
    fun jumpTargetIsNullWhenNothingWaits() {
        assertNull(WaitingCounter.jumpTarget(listOf(thread("a", ThreadState.Working), thread("b", ThreadState.Idle))))
    }

    @Test
    fun jumpTargetPrefersNeedsYouOverAnOlderYourTurn() {
        assertEquals("needs", WaitingCounter.jumpTarget(listOf(thread("turn", ThreadState.YourTurn(1)), thread("needs", waiting(900)))))
    }

    @Test
    fun jumpTargetFallsBackToTheOldestYourTurn() {
        val threads = listOf(thread("pinned-recent", ThreadState.YourTurn(500), pinned = true), thread("older", ThreadState.YourTurn(100)))
        assertEquals("older", WaitingCounter.jumpTarget(threads))
    }
}
