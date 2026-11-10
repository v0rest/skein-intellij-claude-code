package io.github.v0rest.skein.threads

import io.github.v0rest.skein.process.HangUp
import io.github.v0rest.skein.process.ProcessId
import io.github.v0rest.skein.testutil.FakeProcessProbe
import io.github.v0rest.skein.testutil.FakeThreadTerminal
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * A Close hangs up the thread's claude, and once claude has left the tab its shell, while the tab is still open and
 * read; it waits for the tab's session to end, and only then closes the tab: closed first, the IDE stops reading the
 * tab and what still runs in it hangs forever.
 */
class CloseSequenceTest {
    private val terminal = FakeThreadTerminal()
    private val logged = mutableListOf<String>()
    private val probe = FakeProcessProbe(current = 1)
    private val mapper = ThreadMapper(probe)
    private val t0: Instant = Instant.ofEpochMilli(1_000)

    // IDE 1 → shell 10 → claude 100, IDE 1 → shell 11 → claude 101
    private val shell10 = ProcessId(10, t0)
    private val claude100 = ProcessId(100, t0.plusSeconds(2))
    private val shell11 = ProcessId(11, t0.plusSeconds(1))
    private val claude101 = ProcessId(101, t0.plusSeconds(3))

    @Before
    fun tree() {
        probe.add(1, parent = null, start = Instant.EPOCH)
        probe.add(10, parent = 1, start = shell10.start)
        probe.add(100, parent = 10, start = claude100.start)
        probe.add(11, parent = 1, start = shell11.start)
        probe.add(101, parent = 11, start = claude101.start)
    }

    /** A hang-up the process obeys: it leaves the tree at once. */
    private val hangUp = HangUp { pid ->
        terminal.calls += "hup $pid"
        probe.kill(pid)
        true
    }

    /** A hang-up the process ignores. */
    private val ignored = HangUp { pid ->
        terminal.calls += "hup $pid"
        true
    }

    private fun sequence(hangUp: HangUp = this.hangUp, timeoutMillis: Long = 2_000) =
        CloseSequence(hangUp, terminal, mapper, timeoutMillis) { logged += it }

    private fun tab(id: String) = FakeThreadTerminal.Tab(id, "/w/acme")

    private val t1 = Ending(tab("t1"), shell10, claude100)
    private val t2 = Ending(tab("t2"), shell11, claude101)

    @Test
    fun hangsUpClaudeThenTheShellThenWaitsThenCloses() {
        runBlocking { sequence().run(listOf(t1)) }
        assertEquals(listOf("ending t1", "hup 100", "hup 10", "await t1", "close t1"), terminal.calls)
        assertTrue(logged.isEmpty())
    }

    /**
     * A shell exits on SIGHUP at once and does not wait for its jobs, so its session ending says nothing about claude:
     * the shell gets its signal only once claude has left the tab.
     */
    @Test
    fun theShellWaitsUntilClaudeIsGone() {
        val slowClaude = HangUp { pid ->
            terminal.calls += "hup $pid"
            if (pid != 100L) probe.kill(pid)
            true
        }
        runBlocking {
            launch {
                delay(200)
                terminal.calls += "claude gone"
                probe.kill(100)
            }
            sequence(hangUp = slowClaude).run(listOf(t1))
        }
        assertEquals(listOf("ending t1", "hup 100", "claude gone", "hup 10", "await t1", "close t1"), terminal.calls)
    }

    /** claude already exited: only the shell is left in the tab, and it is hung up alone. */
    @Test
    fun aThreadWithoutClaudeHangsUpItsShellAlone() {
        runBlocking { sequence().run(listOf(t1.copy(claude = null))) }
        assertEquals(listOf("ending t1", "hup 10", "await t1", "close t1"), terminal.calls)
    }

    /** Each thread is ended on its own, and the tabs close in one batch at the end, so the one in front can go last. */
    @Test
    fun eachThreadEndsOnItsOwnThenTheTabsCloseInOneGo() {
        runBlocking { sequence().run(listOf(t1, t2)) }
        assertEquals(
            listOf("ending t1,t2", "hup 100", "hup 10", "await t1", "hup 101", "hup 11", "await t2", "close t1,t2"),
            terminal.calls,
        )
        assertEquals(listOf(listOf("t1", "t2")), terminal.closed)
    }

    /** A claude that ignores the hang-up holds up nobody else: the other thread is ended and waited for meanwhile. */
    @Test
    fun aSlowClaudeDoesNotHoldUpAnotherThread() {
        val onlyT2Obeys = HangUp { pid ->
            terminal.calls += "hup $pid"
            if (pid == 101L || pid == 11L) probe.kill(pid)
            true
        }
        runBlocking { sequence(hangUp = onlyT2Obeys, timeoutMillis = 300).run(listOf(t1, t2)) }
        assertEquals(listOf("ending t1,t2", "hup 100", "hup 101", "hup 11", "await t2", "hup 10", "close t1,t2"), terminal.calls)
        assertEquals(1, logged.size)
        assertTrue(logged.single(), logged.single().contains("thread t1"))
    }

    /** A session that does not end in time: the tab closes anyway, as before, and the log says so (no balloon). */
    @Test
    fun aSessionThatDoesNotEndInTimeStillCloses() {
        terminal.ends = { awaitCancellation() }
        runBlocking { sequence(timeoutMillis = 100).run(listOf(t1)) }
        assertEquals(listOf("ending t1", "hup 100", "hup 10", "await t1", "close t1"), terminal.calls)
        assertEquals(1, logged.size)
        assertTrue(logged.single(), logged.single().contains("t1"))
    }

    /** A claude that ignores the hang-up: at the deadline its shell still gets one, and the tab closes, logged. */
    @Test
    fun aClaudeThatDoesNotEndInTimeStillLetsTheTabClose() {
        runBlocking { sequence(hangUp = ignored, timeoutMillis = 100).run(listOf(t1)) }
        assertEquals(listOf("ending t1", "hup 100", "hup 10", "close t1"), terminal.calls)
        assertEquals(1, logged.size)
        assertTrue(logged.single(), logged.single().contains("t1"))
    }

    /** Nothing to hang up (Skein never learned the tab's shell): nothing to wait for, the tab closes at once. */
    @Test
    fun aTabWithNothingToHangUpClosesWithoutWaiting() {
        terminal.ends = { awaitCancellation() }
        runBlocking { sequence(timeoutMillis = 60_000).run(listOf(Ending(tab("t1"), shell = null, claude = null))) }
        assertEquals(listOf("close t1"), terminal.calls)
    }

    /** A thread whose shell Skein could not prove the tab's own: nothing is signalled, the tab closes, and one log line says why. */
    @Test
    fun aDoubtedShellIsLeftAloneAndTheLogSaysWhy() {
        val doubted = Ending(tab("t1"), shell = null, claude = null, doubt = "its shell (pid 20) was not among the IDE's processes when Skein typed into the tab")
        runBlocking { sequence().run(listOf(doubted, t2)) }
        assertEquals(listOf("ending t2", "hup 101", "hup 11", "await t2", "close t1,t2"), terminal.calls)
        assertEquals(1, logged.size)
        assertTrue(logged.single(), logged.single().contains("thread t1") && logged.single().contains("pid 20"))
    }

    /** A tab whose session does not run has no shell of its own left: nothing is signalled, the tab just closes. */
    @Test
    fun aTabWhoseSessionDoesNotRunClosesWithoutSignals() {
        terminal.notRunning += "t1"
        runBlocking { sequence().run(listOf(t1)) }
        assertEquals(listOf("ending t1", "close t1"), terminal.calls)
    }

    /**
     * A pid is signalled only while it is still the process Skein recorded: one that ended may have left its pid to an
     * unrelated process, which has another start instant. Such a thread closes as before, without a signal.
     */
    @Test
    fun aReusedPidIsNotSignalled() {
        probe.kill(100)
        probe.kill(10)
        probe.add(10, parent = 1, start = t0.plusSeconds(60)) // someone else's shell now
        probe.add(100, parent = 10, start = t0.plusSeconds(61))
        runBlocking { sequence().run(listOf(t1)) }
        assertEquals(listOf("ending t1", "close t1"), terminal.calls)
    }

    /** A process no longer in the tab's tree (gone, or under another shell) gets nothing. */
    @Test
    fun aProcessThatLeftTheTabGetsNothing() {
        probe.kill(100)
        probe.add(100, parent = 11, start = claude100.start) // the same process, under another tab's shell
        runBlocking { sequence().run(listOf(t1)) }
        assertEquals(listOf("ending t1", "hup 10", "await t1", "close t1"), terminal.calls)
    }

    /** A failed signal does not stop the next one, and a tab whose shell could not be signalled is not waited for. */
    @Test
    fun aFailedSignalStopsNothing() {
        val failing = HangUp { pid ->
            terminal.calls += "hup $pid"
            val sent = pid == 10L
            if (sent) probe.kill(pid)
            sent
        }
        runBlocking { sequence(hangUp = failing).run(listOf(t1, t2)) }
        assertEquals(listOf("ending t1,t2", "hup 100", "hup 10", "await t1", "hup 101", "hup 11", "close t1,t2"), terminal.calls)
    }

    @Test
    fun nothingToCloseDoesNothing() {
        runBlocking { sequence().run(emptyList()) }
        assertTrue(terminal.calls.isEmpty())
    }
}
