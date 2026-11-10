package io.github.v0rest.skein.threads

import io.github.v0rest.skein.claude.HistoryEntry
import io.github.v0rest.skein.claude.SessionFile
import io.github.v0rest.skein.claude.SessionSnapshot
import io.github.v0rest.skein.claude.Worktrees
import io.github.v0rest.skein.claude.liveIn
import io.github.v0rest.skein.process.ProcessId
import io.github.v0rest.skein.process.ProcessList
import io.github.v0rest.skein.terminal.ClaudeCommand
import io.github.v0rest.skein.terminal.ShellsAtCommand
import io.github.v0rest.skein.terminal.ThreadTab
import io.github.v0rest.skein.terminal.ThreadTerminal
import java.nio.file.Path
import java.util.UUID

/** One row of the Skein panel. */
data class SkeinThread(
    val id: String,
    val folder: String,
    val sessionId: String?,
    val name: String,
    val state: ThreadState,
    val stateSince: Long,
    val createdAt: Long,
    val anchor: Long?,
    val pinned: Boolean = false,
    val color: ThreadColor? = null,
    /** The working directory the thread's `claude` reported, resolved as it sees it; null until a status file matched. */
    val sessionCwd: String? = null,
    /** The name of the Claude Code worktree the thread runs in, or null for a thread in an ordinary folder. */
    val worktree: String? = null,
    /**
     * The branch the repository had checked out when Skein started the worktree thread, which the row counts the
     * worktree's commits against; null when there was none (a detached repository, a worktree that already existed, a
     * thread resumed from History).
     */
    val base: String? = null,
)

interface CoordinatorEvents {
    fun notify(thread: SkeinThread, kind: NotificationKind)

    /** The user started looking at the thread's tab; once per time it comes into view, not every tick. */
    fun viewed(threadId: String)

    /** The thread's tab closed and the thread is gone. */
    fun closed(thread: SkeinThread)

    /** The thread stopped needing the user without being viewed (the prompt was answered or cancelled elsewhere, claude exited). */
    fun leftNeedsYou(threadId: String)

    /** No thread Skein ever started produced a status file: the user's Claude Code is probably too old. Called once. */
    fun missingStatusFiles()
}

sealed interface ResumeOutcome {
    data class Opened(val threadId: String) : ResumeOutcome

    data class Selected(val threadId: String) : ResumeOutcome

    data class RunningElsewhere(val pid: Long) : ResumeOutcome

    /** The entry's session id is not a Claude Code session id, so Skein will not type it into a shell. */
    data object Refused : ResumeOutcome
}

/** What a Close does ([ThreadCoordinator.close]): the threads it leaves open, and the tabs to close with what to end first. */
data class CloseOutcome(val leftOpen: List<SkeinThread>, val closing: List<Ending>)

/**
 * Owns the threads of one project. Synchronous and IDE-free: the service drives it from one confined thread with
 * snapshots of the session directory, and it talks to the terminal only through [ThreadTerminal].
 */
class ThreadCoordinator(
    private val terminal: ThreadTerminal,
    private val mapper: ThreadMapper,
    private val slot: LaunchSlot,
    private val events: CoordinatorEvents,
    private val ids: () -> String = { UUID.randomUUID().toString() },
) {
    private class Record(
        val id: String,
        val folder: String,
        val tab: ThreadTab,
        val createdAt: Long,
        val preexisting: Set<Long>,
        /** The IDE's child processes before the tab's own shell started: none of them is the tab's ([ending]). */
        var baseline: ProcessList,
        /**
         * Whether [baseline] is final. A tab opened out of view starts its shell only when first shown, so until its launch
         * is asked for or its session is seen started, the baseline follows the IDE's children every tick.
         */
        var baselineFinal: Boolean,
    ) {
        /** The IDE's child processes at the first tick that saw the tab's session started; null until then ([ending]). */
        var shellsAtStart: ProcessList? = null

        var sessionId: String? = null
        var sessionCwd: String? = null
        var name: String? = null
        var state: ThreadState = ThreadState.Starting
        var stateSince: Long = createdAt
        var anchor: Long? = null
        var hadProcess = false
        var pinned = false
        var color: ThreadColor? = null

        /** The worktree the thread runs in; the tab itself is in [folder], the repository. */
        var worktree: String? = null

        /** The branch the repository had checked out when the worktree thread started ([SkeinThread.base]). */
        var base: String? = null

        /** The user is looking at the tab, as of the last tick or tab selection. */
        var inView = false

        /** Typed into the tab and not yet seen in the session directory. */
        var pending: PendingLaunch? = null
        var pendingTimedOut = false

        /** Waiting for the launch slot before it can be typed. */
        var queued: String? = null
        var queuedResumeId: String? = null

        fun toThread() = SkeinThread(id, folder, sessionId, name ?: worktree ?: folderName(folder), state, stateSince, createdAt, anchor, pinned, color, sessionCwd, worktree, base)
    }

    private val records = LinkedHashMap<String, Record>()
    private var anyFileEverMatched = false
    private var missingReported = false

    val threads: List<SkeinThread>
        get() = ThreadOrdering.sort(records.values.map { it.toThread() }, { it.state }, { it.createdAt }, { it.pinned })

    /** Opens a tab in [folder] and types `claude` into it (or queues that until the launch slot is free). */
    fun newThread(folder: String, now: Long): String {
        val id = ids()
        val preexisting = mapper.ideChildren() // before the tab exists, so its own shell is not among them
        val baseline = mapper.ideChildList()
        val record = Record(id, folder, terminal.open(id, folder, folderName(folder), focus = true), createdAt = now, preexisting = preexisting, baseline = baseline, baselineFinal = true)
        records[id] = record
        requestLaunch(record, ClaudeCommand.NEW, resumeId = null, now = now)
        return id
    }

    /**
     * Opens a tab in the repository [folder] named [name] and types `claude --worktree <name>` into it (or queues that).
     * [base] is the branch the repository had checked out at that moment, kept with the thread ([SkeinThread.base]).
     * A name Claude Code would not accept throws before any tab opens.
     */
    fun newWorktreeThread(folder: String, name: String, now: Long, base: String? = null): String {
        val command = ClaudeCommand.worktree(name)
        val id = ids()
        val preexisting = mapper.ideChildren()
        val baseline = mapper.ideChildList()
        val record = Record(id, folder, terminal.open(id, folder, name, focus = true), createdAt = now, preexisting = preexisting, baseline = baseline, baselineFinal = true)
        record.worktree = name
        record.base = base
        records[id] = record
        requestLaunch(record, command, resumeId = null, now = now)
        return id
    }

    /** Reopens the saved threads lazily: a tab each, `claude --resume` only when the user first opens one. */
    fun restore(saved: List<SkeinThread>, snapshot: SessionSnapshot, now: Long) {
        val preexisting = mapper.ideChildren()
        val baseline = mapper.ideChildList()
        for (thread in saved) {
            if (thread.id in records) continue
            // out of view, the tab starts its shell only when first shown: its baseline keeps following until then
            val tab = terminal.open(thread.id, thread.folder, thread.name, focus = false)
            val record = Record(thread.id, thread.folder, tab, createdAt = thread.createdAt, preexisting = preexisting, baseline = baseline, baselineFinal = false)
            record.sessionId = thread.sessionId?.takeIf(ClaudeCommand::isSessionId)
            record.name = thread.name
            record.pinned = thread.pinned
            record.color = thread.color
            // a name from a hand-edited workspace file would make every later launch in the tab throw
            record.worktree = thread.worktree?.takeIf(Worktrees::isValidName)
            record.base = thread.base.takeIf { record.worktree != null }
            records[thread.id] = record
            val elsewhere = liveElsewhere(record, snapshot)
            setState(record, if (elsewhere != null) ThreadState.RunningElsewhere(elsewhere.pid) else ThreadState.NotStarted, now)
        }
    }

    fun resume(entry: HistoryEntry, snapshot: SessionSnapshot, now: Long, force: Boolean = false): ResumeOutcome {
        if (!ClaudeCommand.isSessionId(entry.sessionId)) return ResumeOutcome.Refused
        records.values.firstOrNull { it.sessionId == entry.sessionId }?.let { own ->
            terminal.select(own.tab)
            when (own.state) {
                // its claude is gone but the shell is still there: resume the session in the same tab, once
                ThreadState.Exited -> if (own.pending == null && own.queued == null) {
                    requestLaunch(own, ClaudeCommand.resume(entry.sessionId), resumeId = entry.sessionId, now = now)
                }
                ThreadState.NotStarted -> onTabSelected(own.id, snapshot, now)
                else -> Unit
            }
            return ResumeOutcome.Selected(own.id)
        }
        val live = entry.liveIn(snapshot)
        if (live != null && !force) return ResumeOutcome.RunningElsewhere(live.pid)
        val id = ids()
        val preexisting = mapper.ideChildren()
        val baseline = mapper.ideChildList()
        // a worktree session is resumed from the repository: Claude Code takes it back to its worktree
        val ref = if (entry.worktree != null) Worktrees.split(entry.cwd) else null
        val folder = ref?.repository ?: entry.cwd
        val record = Record(id, folder, terminal.open(id, folder, entry.title, focus = true), createdAt = now, preexisting = preexisting, baseline = baseline, baselineFinal = true)
        record.worktree = ref?.name
        record.sessionId = entry.sessionId
        record.name = entry.title
        records[id] = record
        requestLaunch(record, ClaudeCommand.resume(entry.sessionId), resumeId = entry.sessionId, now = now)
        return ResumeOutcome.Opened(id)
    }

    fun onSnapshot(snapshot: SessionSnapshot, viewed: (String) -> Boolean, now: Long) {
        followShells()
        val sessions = snapshot.sessions.values
        // the launch in flight first; a timed-out one may still find its claude late, but never while another
        // launch is in flight, and only within the grace window, so it cannot take a newer thread's process
        for (record in records.values.sortedBy { it.pendingTimedOut }) {
            val pending = record.pending ?: continue
            val anchor = record.anchor
            if (anchor != null) {
                // a relaunch in a tab that already has its anchor: the new claude shows up under that anchor
                when {
                    mapper.processFor(anchor, sessions) != null -> {
                        // a late one still counts as starting up, so it cannot announce a turn it did not have
                        if (record.state == ThreadState.Unknown) setState(record, ThreadState.Starting, now)
                        clearPending(record)
                    }
                    // nothing came within the late window: give up, and the tab reads "exited" again
                    now - pending.typedAt > LATE_MATCH_MILLIS -> clearPending(record)
                    // after the launch timeout: status unknown, but still waiting, so nothing is typed twice
                    else -> checkTimeout(record, pending, now)
                }
                continue
            }
            if (record.pendingTimedOut) {
                if (now - pending.typedAt > LATE_MATCH_MILLIS) {
                    record.pending = null
                    continue
                }
                val inFlight = slot.holder(now)
                if (inFlight != null && inFlight != record.id) continue
            }
            val match = mapper.matchLaunch(pending, sessions, slot.ownedAnchors())
            if (match != null && slot.claimAnchor(this, match.anchor)) {
                record.anchor = match.anchor
                // a late match still counts as starting up, so a slow --resume never announces a turn
                if (record.state == ThreadState.Unknown) setState(record, ThreadState.Starting, now)
                clearPending(record)
                anyFileEverMatched = true
            } else {
                checkTimeout(record, pending, now)
            }
        }
        for (record in records.values) markInView(record, viewed(record.id))
        for (record in records.values) {
            val anchor = record.anchor ?: continue
            val file = mapper.processFor(anchor, sessions)
            when {
                file != null -> apply(record, file, record.inView, now)
                snapshot.unrecognized.any { mapper.anchorOf(it) == anchor } -> setState(record, ThreadState.Unknown, now)
                // a relaunch typed (or queued) for this tab is still on its way: starting, not exited
                record.hadProcess && record.pending == null && record.queued == null -> setState(record, ThreadState.Exited, now)
            }
        }
        for (record in records.values) {
            if (record.anchor != null || record.pending != null || record.queued != null) continue
            val elsewhere = liveElsewhere(record, snapshot)
            if (elsewhere != null) {
                setState(record, ThreadState.RunningElsewhere(elsewhere.pid), now)
            } else if (record.state is ThreadState.RunningElsewhere) {
                setState(record, ThreadState.NotStarted, now)
            }
        }
        for (record in records.values) {
            val command = record.queued ?: continue
            if (slot.tryAcquire(record.id, now, owner = this)) type(record, command, record.queuedResumeId, now)
        }
    }

    private fun clearPending(record: Record) {
        record.pending = null
        record.pendingTimedOut = false
        slot.release(record.id)
    }

    private fun checkTimeout(record: Record, pending: PendingLaunch, now: Long) {
        if (record.pendingTimedOut || now - pending.typedAt <= LAUNCH_TIMEOUT_MILLIS) return
        record.pendingTimedOut = true
        slot.release(record.id)
        setState(record, ThreadState.Unknown, now)
        if (!anyFileEverMatched && !missingReported) {
            missingReported = true
            events.missingStatusFiles()
        }
    }

    fun onTabSelected(threadId: String, snapshot: SessionSnapshot, now: Long) {
        val record = records[threadId] ?: return
        markInView(record, true)
        when (val state = record.state) {
            ThreadState.NotStarted -> {
                val elsewhere = liveElsewhere(record, snapshot)
                if (elsewhere != null) {
                    setState(record, ThreadState.RunningElsewhere(elsewhere.pid), now)
                } else {
                    // this selection is what starts the tab's shell: the baseline from before it is the one that counts
                    record.baselineFinal = true
                    val sessionId = record.sessionId?.takeIf(ClaudeCommand::isSessionId)
                    val worktree = record.worktree
                    val command = if (sessionId == null && worktree != null) ClaudeCommand.worktree(worktree) else ClaudeCommand.forSession(sessionId)
                    requestLaunch(record, command, resumeId = sessionId, now = now)
                }
            }
            is ThreadState.RunningElsewhere -> Unit
            else -> setState(record, ThreadStateMachine.onViewed(state), now)
        }
    }

    /** The user picked the thread (panel row, notification): bring its tab forward, and start it if it waits for that. */
    fun select(threadId: String, snapshot: SessionSnapshot, now: Long) {
        val record = records[threadId] ?: return
        terminal.select(record.tab)
        // bringing the tab forward is the user opening it: start a not-started thread, and the turn counts as read
        onTabSelected(threadId, snapshot, now)
    }

    /**
     * Decides which of the threads close and what to end in each first; closes nothing itself, so it stays synchronous:
     * the caller runs the [CloseSequence] on the result, which hangs up each thread's claude and shell, waits for the
     * tabs' sessions to end and then closes the tabs in one batch, the one in front last. [confirmed] are the threads
     * the user agreed to close although busy ([CloseRule]); a thread busy now but not among them turned busy after the
     * question, so it stays open and comes back in [CloseOutcome.leftOpen], for the caller to say so. The closed threads
     * stay until a tab reports that it closed ([onTabClosed]), which then runs the usual close path, the worktree
     * question included.
     */
    fun close(threadIds: Collection<String>, confirmed: Set<String>, snapshot: SessionSnapshot): CloseOutcome {
        val asked = threadIds.mapNotNull { records[it] }
        val (leftOpen, closing) = asked.partition { CloseRule.isBusy(it.state) && it.id !in confirmed }
        val sessions = snapshot.sessions.values
        return CloseOutcome(leftOpen.map { it.toThread() }, closing.map { ending(it, sessions) })
    }

    /**
     * Brackets each tab's own shell between two listings of the IDE's children, one listing per tick for all tabs that
     * need it. While the tab's session has not started, its baseline ([Record.baseline]) follows the IDE's children,
     * unless it is final already (its launch was asked for, which is what starts a hidden tab's shell). The first tick
     * that sees the session started makes the baseline final and keeps the children of that moment
     * ([Record.shellsAtStart]). The platform reports a session started only after it spawned the shell, so a baseline
     * listed before the state was read as not started predates the tab's shell, and the listing taken after the state
     * was read as started contains it; a terminal opened before the one or after the other is not the tab's. Each tick
     * checks the session state, which reads a field, and lists processes only when a tab is still waiting for its
     * start. This is trust bookkeeping only: a listing that fails keeps the last values and never costs the tick's
     * matching (a Close that cannot prove the shell then hangs up nothing).
     */
    private fun followShells() {
        try {
            val waiting = records.values.filter { it.shellsAtStart == null }
            if (waiting.isEmpty()) return
            // listed before any state is read, so a shell spawned in between is never part of a baseline
            val before = if (waiting.any { !it.baselineFinal }) mapper.ideChildList() else null
            var after: ProcessList? = null
            for (record in waiting) {
                if (terminal.sessionStarted(record.tab)) {
                    record.baselineFinal = true
                    record.shellsAtStart = after ?: mapper.ideChildList().also { after = it }
                } else if (!record.baselineFinal && before != null) {
                    record.baseline = before
                }
            }
        } catch (e: Exception) {
            // the process table could not be listed this tick: keep the last baselines and go on matching
        }
    }

    /**
     * What to hang up before [record]'s tab closes: the tab's shell (its anchor) and the thread's claude, taken only from
     * a live session file under that anchor ([ThreadMapper.processFor]), so never a claude in another terminal. The
     * anchor serves the thread's status as it always did. For a signal it must also be, as the very same process (pid
     * and start instant), among the IDE's processes right before Skein typed into the tab ([ThreadTab.shellsAtCommand])
     * and when the tab's session was first seen started ([Record.shellsAtStart]), and not among them before the tab's
     * own shell started ([Record.baseline]). That proves no more than this: the anchor was not opened after the tab's
     * shell (to within a tick) nor before it. It leaves out a terminal the user opened in the same folder at any other
     * time, whose claude may have been taken for the thread's. Only start instants are compared, never with the wall
     * clock, which the JDK's start instants drift from on Linux. An anchor that fails gets nothing hung up, and
     * [Ending.doubt] says why.
     *
     * A claude whose start cannot be read is left out ([Ending.claude] null), so the shell is hung up first; the tab is
     * still open and read then, and the shell passes the hangup on to its jobs, claude among them. Skein then does not
     * wait for that claude itself, only for the session; a live process of the user's own always has a readable start on
     * macOS and Linux, so this is a corner case.
     */
    private fun ending(record: Record, sessions: Collection<SessionFile>): Ending {
        val anchor = record.anchor ?: return Ending(record.tab, shell = null, claude = null)
        val start = mapper.startOf(anchor)
            ?: return Ending(record.tab, shell = null, claude = null, doubt = "the start of its shell (pid $anchor) cannot be read now")
        val shell = ProcessId(anchor, start)
        val doubt = doubtAbout(record, shell)
        if (doubt != null) return Ending(record.tab, shell = null, claude = null, doubt = doubt)
        val claude = mapper.processFor(anchor, sessions)?.pid?.let { pid -> mapper.startOf(pid)?.let { ProcessId(pid, it) } }
        return Ending(record.tab, shell, claude)
    }

    /** Why [shell], [record]'s anchor as it is now, is not proved the tab's own ([ending]), in words for the log; null when it is. */
    private fun doubtAbout(record: Record, shell: ProcessId): String? {
        val anchor = shell.pid
        val atCommand = when (val shells = record.tab.shellsAtCommand) {
            ShellsAtCommand.NoCommandYet -> return "Skein has not typed into its tab, so it cannot tell its shell (pid $anchor)"
            ShellsAtCommand.NotListed -> return "Skein could not list the IDE's processes when it typed into the tab"
            is ShellsAtCommand.Listed -> shells.processes
        }
        return notThere(atCommand, shell, "when Skein typed into the tab")
            ?: record.shellsAtStart?.let { notThere(it, shell, "when the tab's session started") }
            ?: when (record.baseline.had(shell)) {
                // its start could not be read before the tab's shell started: it cannot be told from one open then
                null -> "its shell (pid $anchor) cannot be told apart from a terminal that was open before the tab's own"
                true -> "its shell (pid $anchor) was open before the tab's own shell started, so it is another terminal's"
                false -> null
            }
    }

    /** Why [shell] was not in [listing] taken [moment], in words for the log; null when it was. */
    private fun notThere(listing: ProcessList, shell: ProcessId, moment: String): String? = when (listing.had(shell)) {
        true -> null
        null -> "the start of its shell (pid ${shell.pid}) could not be read $moment"
        false -> if (listing.hasPid(shell.pid)) {
            "pid ${shell.pid} is no longer the shell that was there $moment"
        } else {
            "its shell (pid ${shell.pid}) was not there yet $moment, so it is another terminal's"
        }
    }

    fun pin(threadIds: Collection<String>, pinned: Boolean) {
        for (id in threadIds) records[id]?.pinned = pinned
    }

    fun color(threadIds: Collection<String>, color: ThreadColor?) {
        for (id in threadIds) records[id]?.color = color
    }

    fun onTabClosed(threadId: String) {
        val record = records[threadId] ?: return
        val thread = record.toThread() // before the record goes
        records.remove(threadId)
        slot.release(record.id)
        record.anchor?.let { slot.releaseAnchor(this, it) }
        events.closed(thread)
    }

    /** Reports the thread once each time it comes into view. */
    private fun markInView(record: Record, inView: Boolean) {
        if (inView && !record.inView) events.viewed(record.id)
        record.inView = inView
    }

    /** The project is closing: let go of the launch slot and the anchors this window held. */
    fun dispose() {
        slot.forget(this)
    }

    private fun apply(record: Record, file: SessionFile, viewed: Boolean, now: Long) {
        record.hadProcess = true
        file.sessionId?.takeIf(ClaudeCommand::isSessionId)?.let { record.sessionId = it }
        file.cwd?.takeIf { it.isNotBlank() }?.let { record.sessionCwd = it }
        when {
            // a name Claude Code made up ("acme-c8") is noise: it never replaces a name, and a thread saved under that
            // very name goes back to its worktree or folder; a History title it was resumed under stays
            file.nameDerived -> if (record.name == file.name) record.name = null
            !file.name.isNullOrBlank() -> record.name = file.name
        }
        val transition = ThreadStateMachine.onSession(record.state, file, viewed, now)
        setState(record, transition.state, now)
        transition.notify?.let { events.notify(record.toThread(), it) }
    }

    private fun requestLaunch(record: Record, command: String, resumeId: String?, now: Long) {
        setState(record, ThreadState.Starting, now)
        if (slot.tryAcquire(record.id, now, owner = this)) {
            type(record, command, resumeId, now)
        } else {
            record.queued = command
            record.queuedResumeId = resumeId
        }
    }

    private fun type(record: Record, command: String, resumeId: String?, now: Long) {
        terminal.type(record.tab, command)
        setState(record, ThreadState.Starting, now)
        // a worktree thread's claude runs in the worktree directory, not in the folder the tab is in: `--worktree` starts
        // it there, and a resume returns a session to the worktree it ended in, whatever id it comes back with
        val worktreeCwd = record.worktree?.let { Worktrees.dirOf(record.folder, it) }
        record.pending = PendingLaunch(record.id, record.folder, now, resumeId, preexistingAnchors = record.preexisting, worktreeCwd = worktreeCwd)
        record.pendingTimedOut = false
        record.queued = null
        record.queuedResumeId = null
    }

    private fun liveElsewhere(record: Record, snapshot: SessionSnapshot): SessionFile? {
        val sessionId = record.sessionId ?: return null
        return snapshot.sessions.values.firstOrNull { it.sessionId == sessionId }
    }

    private fun setState(record: Record, state: ThreadState, now: Long) {
        if (record.state == state) return
        // a prompt of another kind is a new needs-you (and its own notification), not a leave
        val leftNeedsYou = record.state is ThreadState.NeedsYou && state !is ThreadState.NeedsYou
        record.state = state
        record.stateSince = when (state) {
            is ThreadState.NeedsYou -> state.since
            is ThreadState.YourTurn -> state.since
            else -> now
        }
        if (leftNeedsYou) events.leftNeedsYou(record.id)
    }

    companion object {
        const val LAUNCH_TIMEOUT_MILLIS = 30_000L

        /** How long after typing a timed-out launch may still be matched to a late status file. */
        const val LATE_MATCH_MILLIS = 120_000L

        private fun folderName(folder: String): String =
            runCatching { Path.of(folder).fileName?.toString() }.getOrNull()?.takeIf { it.isNotBlank() } ?: folder
    }
}
