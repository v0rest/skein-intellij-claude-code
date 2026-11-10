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
 * A thread tab to close and what to hang up in it first: [shell], the tab's anchor (the claude ancestor right below the
 * IDE, [ThreadMapper.anchorOf]) as Skein proved it the tab's own, and [claude], the thread's claude from its live session
 * file. Either may be null: claude exited, or Skein never learned the tab's shell, or could not prove it, and then
 * nothing is hung up and the tab closes as it always did; [doubt] says why a known anchor was not proved.
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
 * Then the tabs close in one batch ([ThreadTerminal.close]), so the tab in front goes last.
 *
 * Nothing is signalled unless the tab's session runs, and each signal goes out only while its target is still the very
 * process Skein recorded (same pid, same start instant, still in the tab's tree): a pid freed by an exited process may
 * belong to anyone by now. IDE-free; runs off the EDT and off the coordinator's confined dispatcher.
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
        val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLI
        for (ending in closing) {
            if (ending.shell == null && ending.doubt != null) {
                log("Skein hangs up nothing in thread ${ending.tab.id}, ${ending.doubt}; its tab closes as before")
            }
        }
        val known = closing.filter { it.shell != null }
        if (known.isNotEmpty()) {
            terminal.markEnding(known.map { it.tab })
            try {
                coroutineScope { known.map { async { end(it, deadline) } }.awaitAll() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Skein could not end the threads it closes: $e")
            }
        }
        terminal.close(closing.map { it.tab })
    }

    /** One thread: SIGHUP to claude, wait for it to leave, SIGHUP to the shell, wait for the session to end. */
    private suspend fun end(ending: Ending, deadline: Long) {
        val shell = ending.shell ?: return
        // a session that does not run has no shell of its own left, and the recorded pids may be anyone's
        if (!running(ending)) return
        val claude = ending.claude
        if (claude != null && hangUpIfOurs(ending, claude) && !awaitGone(ending, claude, deadline)) {
            // past the deadline: the shell still gets its signal and passes it on, as a closing terminal's would
            hangUpIfOurs(ending, shell)
            log("claude in thread ${ending.tab.id} did not end within $timeoutMillis ms of SIGHUP; Skein hung up its shell and closes the tab anyway")
            return
        }
        if (!running(ending) || !hangUpIfOurs(ending, shell)) return
        if (!awaitSessionEnded(ending, deadline)) {
            log("The terminal session of thread ${ending.tab.id} did not end within $timeoutMillis ms of SIGHUP; Skein closes the tab anyway")
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
