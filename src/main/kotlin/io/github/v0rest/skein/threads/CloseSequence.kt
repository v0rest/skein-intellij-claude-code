package io.github.v0rest.skein.threads

import io.github.v0rest.skein.process.HangUp
import io.github.v0rest.skein.process.ProcessId
import io.github.v0rest.skein.terminal.ThreadTab
import io.github.v0rest.skein.terminal.ThreadTerminal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A thread tab to close and what to hang up in it first. [shell] is the tab's shell right below the IDE as Skein proved
 * it the tab's own, its anchor ([ThreadMapper.anchorOf]) or, before a status file tied the thread to one, the lone shell
 * the tab's bracket proves. [claude] is the thread's claude under it, from its live session file or seen there without
 * one, and null when claude exited or cannot be told - the shell then passes the hangup on. [shell] is null when Skein
 * could not prove the tab's shell, and then nothing is hung up and the tab closes as it always did, while [doubt] says
 * why.
 */
data class Ending(val tab: ThreadTab, val shell: ProcessId?, val claude: ProcessId?, val doubt: String? = null)

/**
 * How Skein closes thread tabs. Closed while something runs in it, a terminal tab leaves that hanging forever with a
 * dead terminal: the IDE keeps the tab's pty open but stops reading it, and a program that writes while it exits blocks
 * (IJPL-259859). So each thread's processes get SIGHUP, as from a closing terminal, while the tab is still open and
 * read: claude first, and the shell only once claude has left the tab (a shell exits on SIGHUP at once and does not wait
 * for its jobs, so its end says nothing about claude's); then the sequence waits until the tab's session has ended.
 * Every thread goes through this on its own, so a slow claude holds up nobody else's, and all of them share one
 * deadline, [timeoutMillis]: past it a thread's shell still gets its signal, and the log says which thread did not end.
 * Then the tabs close in one batch ([ThreadTerminal.close]), so the tab in front goes last. Where [hangUp] sends
 * nothing (Windows, where the IDE ends claude and its shell itself when the tab closes), nothing is waited for and the
 * tabs close at once.
 *
 * Nothing is signalled unless the tab's session runs, and each signal goes out only while its target is still the very
 * process Skein recorded (same pid, same start instant, still in the tab's tree): a pid freed by an exited process may
 * belong to anyone by now. IDE-free; runs off the EDT and off the coordinator's confined dispatcher.
 *
 * A project that closes uses [endOnly], with the short window its close leaves, since the close waits for it, and so do
 * tabs the IDE closes past Skein's close check ([endBeforeTheIdeCloses]).
 */
class CloseSequence(
    private val hangUp: HangUp,
    private val terminal: ThreadTerminal,
    private val mapper: ThreadMapper,
    private val timeoutMillis: Long,
    private val log: (String) -> Unit,
) {
    /** Ends the threads of [closing] and closes their tabs; a thread that does not end in time is closed anyway, and logged. */
    suspend fun run(closing: List<Ending>) {
        if (closing.isEmpty()) return
        endAll(closing, Then.CLOSE_TABS, timeoutMillis)
        terminal.close(closing.map { it.tab })
    }

    /**
     * Ends the threads of [closing] as [run] does, all within one deadline [withinMillis] from now, but marks and closes
     * no tab - for a project that closes, which closes the tabs itself once this returns. Never throws but for
     * cancellation.
     */
    suspend fun endOnly(closing: List<Ending>, withinMillis: Long = timeoutMillis) {
        if (closing.isEmpty()) return
        endAll(closing, Then.PROJECT_CLOSES, withinMillis)
    }

    /**
     * Ends the threads of [closing] as [endOnly] does, within [withinMillis], for tabs the IDE is closing past Skein's
     * close check ([io.github.v0rest.skein.terminal.ThreadTerminalListener.tabClosingPastCheck]), which the IDE closes
     * itself once this returns. Never throws but for cancellation.
     */
    suspend fun endBeforeTheIdeCloses(closing: List<Ending>, withinMillis: Long) {
        if (closing.isEmpty()) return
        endAll(closing, Then.IDE_CLOSES_TABS, withinMillis)
    }

    /** What comes after the ending, in words for the log, and after a doubt that hangs up nothing. */
    private enum class Then(val words: String, val asBefore: String) {
        CLOSE_TABS("closes the tab anyway", "its tab closes as before"),
        PROJECT_CLOSES("lets the project close anyway", "the project closes as before"),
        IDE_CLOSES_TABS("lets the IDE close the tab anyway", "the IDE closes its tab as before"),
    }

    private suspend fun endAll(closing: List<Ending>, then: Then, withinMillis: Long) {
        val deadline = System.nanoTime() + withinMillis * NANOS_PER_MILLI
        for (ending in closing) {
            if (ending.shell == null && ending.doubt != null) {
                log("Skein hangs up nothing in thread ${ending.tab.id}, ${ending.doubt}; ${then.asBefore}")
            }
        }
        val known = closing.filter { it.shell != null }
        if (known.isEmpty()) return
        // the close check holds a tab that is being ended, and a project that closes lets every tab go anyway
        if (then == Then.CLOSE_TABS) terminal.markEnding(known.map { it.tab })
        try {
            coroutineScope { known.map { async { end(it, deadline, then, withinMillis) } }.awaitAll() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Skein could not end the threads it closes: $e")
        }
    }

    /** One thread: SIGHUP to claude, wait for it to leave, SIGHUP to the shell, wait for the session to end. */
    private suspend fun end(ending: Ending, deadline: Long, then: Then, withinMillis: Long) {
        val shell = ending.shell ?: return
        // a session that does not run has no shell of its own left, and the recorded pids may be anyone's
        if (!running(ending)) return
        val claude = ending.claude
        if (claude != null && hangUpIfOurs(ending, claude) && !awaitGone(ending, claude, deadline)) {
            // past the deadline: the shell still gets its signal and passes it on, as a closing terminal's would
            hangUpIfOurs(ending, shell)
            log("claude in thread ${ending.tab.id} did not end within $withinMillis ms of SIGHUP; Skein hung up its shell and ${then.words}")
            return
        }
        if (!running(ending) || !hangUpIfOurs(ending, shell)) return
        if (!awaitSessionEnded(ending, deadline)) {
            log("The terminal session of thread ${ending.tab.id} did not end within $withinMillis ms of SIGHUP; Skein ${then.words}")
        }
    }

    private fun running(ending: Ending): Boolean = try {
        terminal.sessionRunning(ending.tab)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /**
     * Whether [process] is still the one Skein recorded, in [ending]'s tab: the same start instant, and the tab's shell
     * as its anchor (for the shell itself, still a child of the IDE). Never throws but for cancellation.
     */
    private fun isOurs(ending: Ending, process: ProcessId): Boolean = try {
        val shell = ending.shell
        shell != null && mapper.anchorOf(process.pid) == shell.pid && mapper.startOf(process.pid) == process.start
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /** Sends SIGHUP to [process] only while it is still ours ([isOurs]); true when it went out. Never throws but for cancellation. */
    private fun hangUpIfOurs(ending: Ending, process: ProcessId): Boolean {
        if (!isOurs(ending, process)) return false
        return try {
            hangUp.hangUp(process.pid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Skein could not hang up process ${process.pid} of thread ${ending.tab.id}: $e")
            false
        }
    }

    /** True once [claude] has left [ending]'s tab, false at the [deadline]. */
    private suspend fun awaitGone(ending: Ending, claude: ProcessId, deadline: Long): Boolean {
        while (isOurs(ending, claude)) {
            if (System.nanoTime() >= deadline) return false
            delay(POLL_MILLIS)
        }
        return true
    }

    /** True once [ending]'s terminal session has ended, false at the [deadline]. Never throws but for cancellation. */
    private suspend fun awaitSessionEnded(ending: Ending, deadline: Long): Boolean {
        val remainingMillis = (deadline - System.nanoTime()) / NANOS_PER_MILLI
        if (remainingMillis <= 0) return false
        return try {
            withTimeoutOrNull(remainingMillis) { terminal.awaitEnded(ending.tab) } != null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Skein could not wait for thread ${ending.tab.id} to end: $e")
            false
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val POLL_MILLIS = 50L
    }
}
