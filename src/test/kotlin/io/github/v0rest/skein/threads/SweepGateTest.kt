package io.github.v0rest.skein.threads

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

/** The throttle and the single flight of the worktree looks, on a clock the test moves, and what asks for a look. */
class SweepGateTest {
    private var clock = 0L
    private val waits = mutableListOf<Long>()
    private val starts = mutableListOf<Long>()

    private fun gate(pause: suspend (Long) -> Unit = { waits += it; clock += it }) = SweepGate(15_000, now = { clock }, pause = pause)

    /** Lets the serving loop run as far as it can. */
    private suspend fun settle() = repeat(50) { yield() }

    private fun withGate(gate: SweepGate, sweep: suspend () -> Unit = { starts += clock }, test: suspend CoroutineScope.() -> Unit) = runBlocking {
        val serving = launch { gate.serve(sweep) }
        try {
            test()
        } finally {
            serving.cancel()
        }
    }

    @Test
    fun theFirstRequestRunsAtOnce() {
        val gate = gate()
        withGate(gate) {
            gate.request()
            settle()
            assertEquals(listOf(0L), starts)
            assertEquals(emptyList<Long>(), waits)
        }
    }

    @Test
    fun aRequestWithinTheGapWaitsForItInsteadOfBeingDropped() {
        val gate = gate()
        withGate(gate) {
            gate.request()
            settle()
            clock = 1_000
            gate.request()
            settle()
            assertEquals(listOf(0L, 15_000L), starts)
            assertEquals(listOf(14_000L), waits)
        }
    }

    @Test
    fun manyRequestsWithinTheGapMakeOneLook() {
        val gate = gate()
        withGate(gate) {
            gate.request()
            settle()
            clock = 1_000
            repeat(5) { gate.request() }
            settle()
            assertEquals(listOf(0L, 15_000L), starts)
        }
    }

    @Test
    fun requestsWhileALookRunsMakeExactlyOneMoreAfterIt() {
        val gate = gate()
        val release = CompletableDeferred<Unit>()
        var running = 0
        var most = 0
        val sweep: suspend () -> Unit = {
            running++
            most = maxOf(most, running)
            starts += clock
            if (starts.size == 1) release.await()
            running--
        }
        withGate(gate, sweep) {
            gate.request()
            settle()
            repeat(3) { gate.request() }
            settle()
            assertEquals("one look at a time", listOf(0L), starts)
            release.complete(Unit)
            settle()
            assertEquals(listOf(0L, 15_000L), starts)
            assertEquals(1, most)
        }
    }

    @Test
    fun aRequestWhileTheNextLookWaitsIsServedByIt() {
        val resume = CompletableDeferred<Unit>()
        val gate = gate(pause = {
            waits += it
            resume.await()
            clock += it
        })
        withGate(gate) {
            gate.request()
            settle()
            clock = 1_000
            gate.request()
            settle()
            gate.request() // arrives while the second look waits
            resume.complete(Unit)
            settle()
            assertEquals(listOf(0L, 15_000L), starts)
        }
    }

    @Test
    fun aRequestLongAfterTheLastLookRunsAtOnce() {
        val gate = gate()
        withGate(gate) {
            gate.request()
            settle()
            clock = 100_000
            gate.request()
            settle()
            assertEquals(listOf(0L, 100_000L), starts)
            assertEquals(emptyList<Long>(), waits)
        }
    }

    private fun thread(id: String, state: ThreadState, worktree: String? = "acme") =
        SkeinThread(id, "/w/acme", null, id, state, 0, 0, null, worktree = worktree)

    @Test
    fun aWorktreeThreadFinishingATurnOrComingOrGoingAsksForALook() {
        val updates = flowOf(
            listOf(thread("a", ThreadState.Working)), // what the state flow replays on subscribe
            listOf(thread("a", ThreadState.YourTurn(5))), // a turn finished
            listOf(thread("a", ThreadState.Idle)), // read: still settled, nothing new
            listOf(thread("a", ThreadState.Working)), // a new turn starts
            listOf(thread("a", ThreadState.Idle)), // finished while looked at
            listOf(thread("a", ThreadState.Idle), thread("p", ThreadState.Working, worktree = null)), // a plain thread
            listOf(thread("a", ThreadState.Idle), thread("p", ThreadState.YourTurn(9), worktree = null)), // it finishes
            listOf(thread("a", ThreadState.Idle), thread("p", ThreadState.Idle, worktree = null), thread("b", ThreadState.Starting)), // a new worktree thread
            listOf(thread("b", ThreadState.Starting)), // a closed
        )
        assertEquals(4, runBlocking { worktreeSweepTriggers(updates).toList() }.size)
    }

    /** The gap counts from the end of a look, so a long look is not followed by another right away. */
    @Test
    fun theGapCountsFromTheEndOfALook() {
        val gate = gate()
        withGate(gate, sweep = {
            starts += clock
            clock += 10_000
        }) {
            gate.request()
            settle()
            gate.request()
            settle()
            assertEquals(listOf(0L, 25_000L), starts)
        }
    }

    @Test
    fun requestNowSkipsTheGap() {
        val gate = gate()
        withGate(gate) {
            gate.request()
            settle()
            clock = 1_000
            gate.requestNow()
            settle()
            assertEquals(listOf(0L, 1_000L), starts)
            assertEquals(emptyList<Long>(), waits)
        }
    }

    /** Skein's own Remove while a plain request waits out the gap: the look runs at once, and only once. */
    @Test
    fun requestNowCutsAWaitShort() {
        val never = CompletableDeferred<Unit>()
        val gate = gate(pause = {
            waits += it
            never.await()
        })
        withGate(gate) {
            gate.request()
            settle()
            clock = 1_000
            gate.request()
            settle()
            assertEquals(listOf(14_000L), waits)
            clock = 2_000
            gate.requestNow()
            settle()
            assertEquals(listOf(0L, 2_000L), starts)
        }
    }

    @Test
    fun requestNowWhileALookRunsStillWaitsForItThenRunsWithoutTheGap() {
        val gate = gate()
        val release = CompletableDeferred<Unit>()
        withGate(gate, sweep = {
            starts += clock
            if (starts.size == 1) release.await()
        }) {
            gate.request()
            settle()
            gate.requestNow()
            settle()
            assertEquals(listOf(0L), starts)
            clock = 3_000
            release.complete(Unit)
            settle()
            assertEquals(listOf(0L, 3_000L), starts)
            assertEquals(emptyList<Long>(), waits)
        }
    }
}
