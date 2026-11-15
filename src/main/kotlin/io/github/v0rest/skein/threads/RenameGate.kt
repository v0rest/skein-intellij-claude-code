package io.github.v0rest.skein.threads

import io.github.v0rest.skein.claude.ClaudeActivity
import io.github.v0rest.skein.claude.SessionFile
import io.github.v0rest.skein.process.ProcessId
import io.github.v0rest.skein.terminal.ClaudeRename
import io.github.v0rest.skein.terminal.DraftGuard
import java.time.Instant

/**
 * When Skein types a waiting rename ([SessionMarks.claudeRenames]) into a thread's claude as `/rename <name>`
 * ([ClaudeRename]). Typed at the wrong moment the line does harm: while claude asks something the keys go to that dialog
 * and Enter may pick its default answer, while it works the line queues as the next message, after a draft it goes to the
 * model with the draft, and with claude gone the shell underneath runs it. So it is typed only when all of it holds, and
 * otherwise waits for the next look, which comes with every status change and every second.
 */
object RenameGate {
    enum class Decision {
        /** Type it now. */
        SEND,

        /** Not now; look again later. */
        WAIT,

        /** Never: the name is not one Skein types into claude, so it stops waiting. */
        DROP,
    }

    /**
     * [name]: the name waiting. [runsSession]: the thread's claude, under the tab's shell, runs that session by its
     * status file. [state]: the thread's state from that status, null without a thread. [claudeAlive]: that claude is the
     * same live process now. [inputClean]: nothing was typed into the tab since the last Enter that sent it, and no key
     * came for a while ([DraftGuard.allowsSend]). [vimMode]: Claude Code's input is in vim mode, where typed text may run
     * as commands, so nothing is typed while it is.
     */
    fun decide(name: String, runsSession: Boolean, state: ThreadState?, claudeAlive: Boolean, inputClean: Boolean, vimMode: Boolean): Decision = when {
        !ClaudeRename.isAllowed(name) -> Decision.DROP
        !vimMode && runsSession && claudeAlive && inputClean && (state == ThreadState.Idle || state is ThreadState.YourTurn) -> Decision.SEND
        else -> Decision.WAIT
    }

    /**
     * Whether an Enter typed into the tab now may send what is in claude's input ([DraftGuard.next]): only while claude is
     * idle, and even then only a turn that starts afterwards shows it did ([atWork]). While it works an Enter may queue the
     * draft or take a suggestion into it, while it asks something the Enter answers that, and in any state Skein is not
     * sure of either may happen.
     */
    fun enterMaySend(state: ThreadState): Boolean = when (state) {
        ThreadState.Idle, is ThreadState.YourTurn -> true
        ThreadState.Working, ThreadState.Background, is ThreadState.NeedsYou, ThreadState.WorkingUnknown, ThreadState.Starting,
        ThreadState.NotStarted, is ThreadState.RunningElsewhere, ThreadState.Exited, ThreadState.Unknown,
        -> false
    }

    /**
     * Whether claude is at work in [state] - a turn or a background task - which, seen starting soon after an Enter on a
     * draft, shows that Enter sent the draft ([DraftGuard.proven]). A dialog does not: claude may have started a turn of
     * its own that asked something before Skein saw it at work, and the Enter answered that dialog. A status Skein does
     * not know is no sign of a turn either.
     */
    fun atWork(state: ThreadState): Boolean = when (state) {
        ThreadState.Working, ThreadState.Background -> true
        is ThreadState.NeedsYou, ThreadState.WorkingUnknown, ThreadState.Idle, is ThreadState.YourTurn, ThreadState.Starting,
        ThreadState.NotStarted, is ThreadState.RunningElsewhere, ThreadState.Exited, ThreadState.Unknown,
        -> false
    }

    /**
     * What the key watch learns of [threads] ([io.github.v0rest.skein.terminal.ThreadTerminal.claudeSeen]): the ones
     * whose claude is idle ([enterMaySend]), and since when each one at work ([atWork]) has been so, the state's start.
     */
    data class Seen(val enterMaySend: Set<String>, val atWorkSince: Map<String, Long>)

    fun seen(threads: List<SkeinThread>): Seen = Seen(
        enterMaySend = threads.filter { enterMaySend(it.state) }.mapTo(HashSet()) { it.id },
        atWorkSince = threads.filter { atWork(it.state) }.associate { it.id to it.stateSince },
    )

    /** The folders whose project settings count for [thread]'s claude: the tab's folder and where claude runs. */
    fun folders(thread: SkeinThread): List<String> = listOfNotNull(thread.folder, thread.sessionCwd?.takeIf { it.isNotBlank() }).distinct()

    /**
     * The status file part of the last look, on a fresh read of claude [claudePid]'s status file ([file], null when it
     * could not be read): it is that claude's, for [sessionId], and says idle.
     */
    fun stillIdle(file: SessionFile?, claudePid: Long, sessionId: String): Boolean =
        file != null && file.pid == claudePid && file.sessionId == sessionId && file.activity == ClaudeActivity.IDLE

    /**
     * The last look right before the line goes, off the EDT, on what was read just now: [claude] is still the same process
     * ([startNow] its start now, null when gone) under the tab's shell ([anchorNow] the shell it hangs under now, which
     * must be [anchor]), its status file ([file]) says it is idle in [sessionId] ([stillIdle]), and Claude Code is not in
     * vim mode ([vimMode]). False on any doubt.
     */
    fun lastLook(claude: ProcessId, startNow: Instant?, anchor: Long, anchorNow: Long?, file: SessionFile?, sessionId: String, vimMode: Boolean): Boolean =
        !vimMode && startNow != null && startNow == claude.start && anchorNow == anchor && stillIdle(file, claude.pid, sessionId)
}
