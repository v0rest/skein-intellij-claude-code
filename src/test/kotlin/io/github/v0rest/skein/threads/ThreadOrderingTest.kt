package io.github.v0rest.skein.threads

import io.github.v0rest.skein.claude.WaitReason
import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadOrderingTest {
    private data class Row(val id: String, val state: ThreadState, val createdAt: Long)

    @Test
    fun needsYouFirstOldestFirst_thenYourTurn_thenCreationOrder() {
        val rows = listOf(
            Row("A", ThreadState.Idle, 1),
            Row("B", ThreadState.YourTurn(5), 2),
            Row("C", ThreadState.NeedsYou(WaitReason.PERMISSION, 9), 3),
            Row("D", ThreadState.NeedsYou(WaitReason.QUESTION, 4), 4),
            Row("E", ThreadState.Working, 5),
            Row("F", ThreadState.Exited, 6),
        )
        val sorted = ThreadOrdering.sort(rows, { it.state }, { it.createdAt })
        assertEquals(listOf("D", "C", "B", "A", "E", "F"), sorted.map { it.id })
    }

    @Test
    fun twoYourTurnsOldestFirst() {
        val rows = listOf(Row("A", ThreadState.YourTurn(9), 1), Row("B", ThreadState.YourTurn(2), 2))
        assertEquals(listOf("B", "A"), ThreadOrdering.sort(rows, { it.state }, { it.createdAt }).map { it.id })
    }

    @Test
    fun restKeepsCreationOrderRegardlessOfState() {
        val rows = listOf(Row("A", ThreadState.Exited, 3), Row("B", ThreadState.Working, 1), Row("C", ThreadState.Starting, 2))
        assertEquals(listOf("B", "C", "A"), ThreadOrdering.sort(rows, { it.state }, { it.createdAt }).map { it.id })
    }

    private data class PRow(val id: String, val state: ThreadState, val createdAt: Long, val pinned: Boolean)

    private fun sortPinned(rows: List<PRow>) = ThreadOrdering.sort(rows, { it.state }, { it.createdAt }, { it.pinned }).map { it.id }

    @Test
    fun pinnedThreadsComeFirstAndKeepTheUsualOrderInside() {
        val rows = listOf(
            PRow("a", ThreadState.Idle, 1, pinned = true),
            PRow("b", ThreadState.NeedsYou(WaitReason.PERMISSION, 3), 2, pinned = false),
            PRow("c", ThreadState.YourTurn(5), 3, pinned = true),
            PRow("d", ThreadState.NeedsYou(WaitReason.QUESTION, 4), 4, pinned = true),
        )
        assertEquals(listOf("d", "c", "a", "b"), sortPinned(rows))
    }

    @Test
    fun anUnpinnedWaitingThreadNeverMovesAboveAPinnedOne() {
        val rows = listOf(
            PRow("a", ThreadState.Working, 1, pinned = true),
            PRow("b", ThreadState.NeedsYou(WaitReason.PERMISSION, 1), 2, pinned = false),
        )
        assertEquals(listOf("a", "b"), sortPinned(rows))
    }
}
