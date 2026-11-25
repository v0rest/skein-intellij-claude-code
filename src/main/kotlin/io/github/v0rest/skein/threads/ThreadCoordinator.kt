package io.github.v0rest.skein.threads

import io.github.v0rest.skein.claude.FolderTrust
import io.github.v0rest.skein.claude.HistoryEntry
import io.github.v0rest.skein.claude.SessionFile
import io.github.v0rest.skein.claude.SessionSnapshot
import io.github.v0rest.skein.claude.WaitReason
import io.github.v0rest.skein.claude.Worktrees
import io.github.v0rest.skein.claude.liveIn
import io.github.v0rest.skein.paths.PathRules
import io.github.v0rest.skein.paths.PathStyle
import io.github.v0rest.skein.process.ProcessId
import io.github.v0rest.skein.process.ProcessList
import io.github.v0rest.skein.terminal.ClaudeCommand
import io.github.v0rest.skein.terminal.ShellsAtCommand
import io.github.v0rest.skein.terminal.ThreadTab
import io.github.v0rest.skein.terminal.ThreadTerminal
import kotlinx.coroutines.CancellationException
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

    /**
     * A worktree thread's `claude --worktree` ended without a status file in a repository Claude Code was not told to
     * trust, which it refuses. Called for each such launch, in place of [missingStatusFiles].
     */
    fun untrustedWorktree(thread: SkeinThread) = Unit
}

sealed interface ResumeOutcome {
    data class Opened(val threadId: String) : ResumeOutcome

    data class Selected(val threadId: String) : ResumeOutcome

    data class RunningElsewhere(val pid: Long) : ResumeOutcome

    /** The entry's session id is not a Claude Code session id, so Skein will not type it into a shell. */
    data object Refused : ResumeOutcome
}

/**
 * The thread that holds a session with a rename waiting for Claude Code ([ThreadCoordinator.renameTarget]), with what
 * [RenameGate] needs to know of it. [runsSession]: the live status file of the claude under the tab's shell (its anchor)
 * carries that session. [claude]: that claude with its start, read now; null when it is not there or its start cannot be
 * read, so a later look can tell whether it is still the same process.
 */
data class RenameTarget(val thread: SkeinThread, val tab: ThreadTab, val runsSession: Boolean, val claude: ProcessId?)

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
    /**
     * Whether Claude Code was told to trust a folder ([io.github.v0rest.skein.claude.FolderTrustReader]). Asked only for
     * a launch past its timeout, and blocking, since it reads Claude Code's config.
     */
    private val trust: (folder: String) -> FolderTrust = { FolderTrust.UNKNOWN },
    /**
     * Whether a session has a conversation on disk that `claude --resume` can go on with
     * ([io.github.v0rest.skein.claude.Transcripts.resumable]), asked with the thread's folder, the cwd its claude reported
     * and the session id. Asked only when a thread comes back after a lost tab ([onTabLost]), and blocking, since it reads
     * the transcript's head.
     */
    private val conversation: (folder: String, sessionCwd: String?, sessionId: String) -> Boolean = { _, _, _ -> true },
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

        /**
         * Since when the ticks have seen the tab in view without a break ([InViewStart]), null while out of view. Only the
         * ticks set it, a selection does not, so the moment is what the user had before them. It starts over when the
         * thread waits again after its session ran elsewhere.
         */
        var inViewSince: Long? = null

        /**
         * Skein decided to close the tab ([close], [endingsBeforeLoss]) and now ends what runs in the batch first, so the
         * thread does not start in view meanwhile - a claude typed then would stay in a terminal closed a moment later. Gone
         * with the record once the tab closes or comes back, and lifted for a close that does not happen ([closeDropped]).
         */
        var closing = false

        /** Typed into the tab and not yet seen in the session directory. */
        var pending: PendingLaunch? = null
        var pendingTimedOut = false

        /**
         * The thread's claude, seen under the tab's shell past the launch timeout while it asks whether to trust the
         * folder - it writes its status file only once the person answers yes. Null otherwise.
         */
        var asking: ProcessId? = null

        /** Waiting for the launch slot before it can be typed. */
        var queued: String? = null
        var queuedResumeId: String? = null

        /** What [queued] asks again right before it is typed ([Recheck]). */
        var queuedRecheck = Recheck.NONE

        /**
         * How the thread comes back once the IDE has closed its tab past Skein's close check ([TabLoss.resumesNow]),
         * decided before Skein hung its claude up ([endingsBeforeLoss]), and null while no such close is under way.
         */
        var resumeAfterLoss: Boolean? = null

        /** The thread came back after such a close while its session still ran in the old tab's claude, and resumes once that is gone. */
        var resumeWhenFree = false

        fun toThread() = SkeinThread(id, folder, sessionId, name ?: worktree ?: folderName(folder), state, stateSince, createdAt, anchor, pinned, color, sessionCwd, worktree, base)
    }

    /**
     * What a launch that waits for the slot asks again right before it is typed - what the request that queued it asked
     * then. Its session may have come up elsewhere meanwhile - another window restored a thread on the same session and
     * got the slot first - and a second claude on one session is what that request would have refused.
     */
    private enum class Recheck {
        /** Nothing - a new session, a resume the user asked for although its session ran elsewhere, a relaunch in an exited thread's tab. */
        NONE,

        /** Whether the session runs elsewhere now, as opening a thread asks ([onTabSelected]) and a resume from the History. */
        ELSEWHERE,

        /** The same by a claude still alive, as a thread back after a lost tab asks ([onTabLost]), which resumes once that claude is gone. */
        ELSEWHERE_THEN_RESUME,
    }

    private val records = LinkedHashMap<String, Record>()
    private var anyFileEverMatched = false
    private var missingReported = false

    val threads: List<SkeinThread>
        get() = ThreadOrdering.sort(records.values.map { it.toThread() }, { it.state }, { it.createdAt }, { it.pinned })

    /** Whether thread [threadId] has not started yet - opening its tab would start it ([onTabSelected]). */
    fun notStarted(threadId: String): Boolean = records[threadId]?.state == ThreadState.NotStarted

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

    /**
     * Reopens the saved threads lazily - a tab each, and `claude --resume` in one only when the user opens it or its tab
     * stays in view a moment ([InViewStart]) - after a restart that is the one tab left in front. A saved thread whose
     * session a thread here holds already, one the user resumed from the History while the saved threads waited for the
     * project's trust, is that thread now and does not come back a second time ([SavedThreads.heldByLive]); that thread
     * takes on its pin, colour and base ([SavedThreads.merged]).
     */
    fun restore(saved: List<SkeinThread>, snapshot: SessionSnapshot, now: Long) {
        val preexisting = mapper.ideChildren()
        val baseline = mapper.ideChildList()
        val holders = HashMap<String, Record>()
        for (record in records.values) record.sessionId?.let { holders.putIfAbsent(it, record) }
        for (thread in saved) {
            if (thread.id in records) continue
            if (SavedThreads.heldByLive(thread.sessionId, holders.keys)) {
                val holder = holders.getValue(thread.sessionId!!)
                val merged = SavedThreads.merged(holder.toThread(), thread)
                holder.pinned = merged.pinned
                holder.color = merged.color
                holder.base = merged.base
                continue
            }
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

    /**
     * Resumes the History session [entry] in a new thread, or selects the thread that already holds it. [color] is the
     * session's colour ([SessionMarks.colors]), given to a new thread so its row and tab show it from the start, while
     * a thread that already holds the session keeps its own.
     */
    fun resume(entry: HistoryEntry, snapshot: SessionSnapshot, now: Long, force: Boolean = false, color: ThreadColor? = null): ResumeOutcome {
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
        record.color = color
        records[id] = record
        // the user's yes to a second claude on a session running elsewhere holds while the launch waits for the slot
        requestLaunch(record, ClaudeCommand.resume(entry.sessionId), resumeId = entry.sessionId, now = now, recheck = if (force) Recheck.NONE else Recheck.ELSEWHERE)
        return ResumeOutcome.Opened(id)
    }

    /**
     * One look at the threads - the launches in flight, the statuses, which tabs are in view ([viewed]) and the queued
     * launches, each asked again whether its session runs elsewhere by now ([Recheck]) before the slot lets it go. A
     * thread waiting to be opened whose tab stayed in view a moment, its shell running, starts as if the user
     * had opened it ([InViewStart]), unless Skein closes its tab ([Record.closing]) or [startsInView] is false - the
     * project closes, and a claude typed into a terminal about to close would hang on with nobody reading it, or a modal
     * dialog stands in front of the tab.
     */
    fun onSnapshot(snapshot: SessionSnapshot, viewed: (String) -> Boolean, now: Long, startsInView: Boolean = true) {
        followShells()
        val sessions = snapshot.sessions.values
        // the launch in flight first; a timed-out one may still find its claude late, but never while another
        // launch is in flight, and only within the grace window, so it cannot take a newer thread's process
        for (record in records.values.sortedBy { it.pendingTimedOut }) {
            val pending = record.pending ?: continue
            record.asking?.let {
                followAsking(record, it, snapshot, now)
                continue
            }
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
                    else -> checkTimeout(record, pending, snapshot, now)
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
                checkTimeout(record, pending, snapshot, now)
            }
        }
        for (record in records.values) {
            val inView = viewed(record.id)
            markInView(record, inView)
            record.inViewSince = InViewStart.inViewSince(record.inViewSince, inView, now)
        }
        if (startsInView) {
            // after followShells, so the shell bracket is taken before the launch, and through the door a click takes, so
            // the session running elsewhere, the launch slot and the worktree command are minded as for a click
            for (record in records.values) {
                if (!record.closing && InViewStart.starts(record.state, record.inViewSince, now, { typedIntoQuietly(record) }, { shellRunningQuietly(record) })) {
                    onTabSelected(record.id, snapshot, now)
                }
            }
        }
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
                if (record.resumeWhenFree) {
                    // the old tab's claude is gone now, so the thread goes on in its new tab ([onTabLost])
                    record.resumeWhenFree = false
                    resumeAfterLoss(record, now)
                } else {
                    setState(record, ThreadState.NotStarted, now)
                    // the moment in view starts over - the other claude's status file may go a little before it is done
                    record.inViewSince = record.inViewSince?.let { now }
                }
            }
        }
        for (record in records.values) {
            val command = record.queued ?: continue
            val elsewhere = queuedElsewhere(record, snapshot)
            if (elsewhere != null) {
                // what the request would have said had it come now - no second claude on the session, and no slot taken
                val thenResume = record.queuedRecheck == Recheck.ELSEWHERE_THEN_RESUME
                record.queued = null
                record.queuedResumeId = null
                record.queuedRecheck = Recheck.NONE
                setState(record, ThreadState.RunningElsewhere(elsewhere.pid), now)
                record.resumeWhenFree = thenResume
            } else if (slot.tryAcquire(record.id, now, owner = this)) {
                type(record, command, record.queuedResumeId, now)
            }
        }
    }

    /**
     * The live status file of the session [record]'s queued launch resumes, when the request that queued it asked
     * whether that session runs elsewhere ([Recheck]) and it does now, else null.
     */
    private fun queuedElsewhere(record: Record, snapshot: SessionSnapshot): SessionFile? {
        val sessionId = record.queuedResumeId ?: return null
        if (record.queuedRecheck == Recheck.NONE) return null
        val file = snapshot.sessions.values.firstOrNull { it.sessionId == sessionId } ?: return null
        // a lost tab's claude may leave its status file in the snapshot a moment after it is gone, as [onTabLost] knows
        return file.takeIf { record.queuedRecheck != Recheck.ELSEWHERE_THEN_RESUME || mapper.isAlive(it.pid) }
    }

    /**
     * [ThreadTerminal.sessionRunning] for [record]'s tab, with a terminal that cannot say read as not running - a start
     * on a doubt would keep the typed command, and the launch slot with it, waiting for a shell that may never take it.
     * Not [ThreadTerminal.sessionStarted], which a shell that exited still passes.
     */
    private fun shellRunningQuietly(record: Record): Boolean =
        try {
            terminal.sessionRunning(record.tab)
        } catch (e: Exception) {
            false
        }

    /**
     * [ThreadTerminal.typedInto] for [record]'s tab, with a terminal that cannot say read as typed into - a command typed
     * on a doubt could land on whatever the user left at the prompt.
     */
    private fun typedIntoQuietly(record: Record): Boolean =
        try {
            terminal.typedInto(record.tab)
        } catch (e: Exception) {
            true
        }

    private fun clearPending(record: Record) {
        record.pending = null
        record.pendingTimedOut = false
        slot.release(record.id)
    }

    /**
     * The launch's status file did not come in time. Before Skein calls the status unknown it looks whether the thread's
     * claude runs under the tab's shell without one, and reads trust only when that can change the outcome
     * ([LaunchTimeout]) - a claude in a folder nobody trusted asks about that and waits for the person, a worktree
     * thread's claude gone from such a repository was turned away. Every other launch shows status unknown, and the
     * could-not-read balloon once, as before.
     */
    private fun checkTimeout(record: Record, pending: PendingLaunch, snapshot: SessionSnapshot, now: Long) {
        if (record.pendingTimedOut || now - pending.typedAt <= LAUNCH_TIMEOUT_MILLIS) return
        record.pendingTimedOut = true
        slot.release(record.id)
        val asking = claudeWithoutStatus(record, snapshot)
        // only `claude --worktree` is turned away by a folder nobody trusted, a resume in a worktree thread is not
        val worktreeLaunch = record.worktree != null && pending.resumeSessionId == null
        when (LaunchTimeout.decide(claudeAlive = asking != null, worktree = worktreeLaunch) { trustQuietly(record.folder) }) {
            LaunchTimeout.Outcome.ASKS_TRUST -> if (asking != null && waitForTrust(record, asking, now)) return
            LaunchTimeout.Outcome.UNTRUSTED_WORKTREE -> {
                setState(record, ThreadState.Unknown, now)
                events.untrustedWorktree(record.toThread())
                return
            }
            LaunchTimeout.Outcome.AS_BEFORE -> Unit
        }
        setState(record, ThreadState.Unknown, now)
        reportMissingStatusFiles()
    }

    /** The could-not-read balloon, once, and only while no thread Skein started ever got a status file it could read. */
    private fun reportMissingStatusFiles() {
        if (!anyFileEverMatched && !missingReported) {
            missingReported = true
            events.missingStatusFiles()
        }
    }

    /**
     * [conversation], with a failure read as a conversation there - a transcript Skein could not read may well hold one,
     * and starting afresh would leave it behind, while a wrong Resume only has claude say it found none.
     */
    private fun conversationQuietly(record: Record, sessionId: String): Boolean =
        try {
            conversation(record.folder, record.sessionCwd, sessionId)
        } catch (e: RuntimeException) {
            true
        }

    /** [trust], with a failure read as a trust Skein cannot tell. */
    private fun trustQuietly(folder: String): FolderTrust =
        try {
            trust(folder)
        } catch (e: RuntimeException) {
            FolderTrust.UNKNOWN
        }

    /**
     * The tab's shell and the one claude under it that has no status file, or null on any doubt. A tab with its anchor
     * has its shell already, while [doubtAbout] still proves it the tab's own, and a tab without one takes the shell its
     * bracket proves ([bracketedShell]). Two claudes under the shell, or none, are no answer either. Never throws - a
     * process listing that fails is no answer.
     */
    private fun claudeWithoutStatus(record: Record, snapshot: SessionSnapshot): Pair<Long, ProcessId>? =
        try {
            val withStatus = snapshot.sessions.keys + snapshot.unrecognized
            val shell = when (val anchor = record.anchor) {
                null -> (bracketedShell(record) as? TabShell.Proved)?.shell
                else -> mapper.startOf(anchor)?.let { ProcessId(anchor, it) }?.takeIf { doubtAbout(record, it) == null }
            }
            shell?.let { mapper.clisUnder(it.pid).filter { claude -> claude.pid !in withStatus }.singleOrNull()?.let { claude -> it.pid to claude } }
        } catch (e: Exception) {
            null
        }

    /** What the bracket says about the shell of a tab Skein has not tied to one yet ([bracketedShell]). */
    private sealed interface TabShell {
        data class Proved(val shell: ProcessId) : TabShell

        /** [why] in words for the log, as they follow "Skein had not tied the tab to its shell yet, and". */
        data class Doubt(val why: String) : TabShell
    }

    /**
     * The shell of [record]'s tab before Skein tied the thread to one, proved by the bracket alone, or why it is not.
     * An IDE child listed when Skein typed into the tab counts only as it is still that very process, passes
     * [doubtAbout] (not there before the tab's own shell started, and once a tick saw the tab's session started, there
     * then too) and is no thread's anchor, in this window or another. The bracket is only as fine as a tick, so whatever
     * else the IDE started in that tick passes it too. Each child left gets its kind ([ThreadMapper.kindOf]) - the IDE's
     * own helpers (conhost, fsnotifier, JCEF) are ignored, and the tab's shell is taken only when it is the one shell
     * left and nothing unrecognised stands beside it. An unrecognised child may be the tab's own shell (wsl.exe, tmux, a
     * wrapper, a command the system does not give), with a listed shell beside it being another terminal's, and Close
     * would hang that one up. A child whose start could not be read as Skein typed counts as unrecognised too, unless it
     * is a thread's anchor, was there before the tab's own shell or is gone by now, since it cannot be bracketed and may
     * be the tab's own shell - a pid nobody holds any more is no running shell. The trust wait ([claudeWithoutStatus]) and a Close ([untiedEnding]) both take the tab's shell this way.
     */
    private fun bracketedShell(record: Record): TabShell {
        val listing = when (val shells = record.tab.shellsAtCommand) {
            ShellsAtCommand.NoCommandYet -> return TabShell.Doubt("it has not typed into the tab, so it cannot tell its shell")
            ShellsAtCommand.NotListed -> return TabShell.Doubt("it could not list the IDE's processes when it typed into the tab")
            is ShellsAtCommand.Listed -> shells.processes
        }
        val owned = slot.ownedAnchors()
        val unreadable = listing.startUnknown.filter { it !in owned && !record.baseline.hasPid(it) && mapper.isAlive(it) }
        val bracketed = listing.known.filter { mapper.startOf(it.pid) == it.start && doubtAbout(record, it) == null }
        val (held, free) = bracketed.partition { it.pid in owned }
        val kinds = free.groupBy { mapper.kindOf(it.pid) }
        val unrecognised = kinds[IdeChild.UNRECOGNISED].orEmpty()
        val shells = kinds[IdeChild.SHELL].orEmpty()
        return when {
            unrecognised.isNotEmpty() -> TabShell.Doubt("something it does not know as a shell started with the tab too (${pids(unrecognised.map { it.pid })}) and may be the tab's own shell")
            unreadable.isNotEmpty() -> TabShell.Doubt("a process that started with the tab has no readable start (${pids(unreadable)}) and may be the tab's own shell")
            shells.size == 1 -> TabShell.Proved(shells.single())
            shells.isNotEmpty() -> TabShell.Doubt("several shells started with the tab (${pids(shells.map { it.pid })}), so it cannot tell which one is the tab's")
            held.isNotEmpty() -> TabShell.Doubt("the shell that started with the tab is another thread's (${pids(held.map { it.pid })})")
            else -> TabShell.Doubt("no shell that started with the tab is still there")
        }
    }

    /** "pid 10" or "pids 10, 11", for the log. */
    private fun pids(pids: Collection<Long>): String =
        pids.sorted().joinToString(", ", prefix = if (pids.size == 1) "pid " else "pids ")

    /**
     * Shows the thread as waiting on claude's trust question. The tab's shell becomes its anchor (so Close ends it as
     * any other thread), and a balloon goes out unless the person looks at the tab. False, and nothing changed, when
     * another window holds that shell.
     */
    private fun waitForTrust(record: Record, found: Pair<Long, ProcessId>, now: Long): Boolean {
        val (shell, claude) = found
        if (record.anchor == null) {
            if (!slot.claimAnchor(this, shell)) return false
            record.anchor = shell
        }
        record.asking = claude
        setState(record, ThreadState.NeedsYou(WaitReason.TRUST, now), now)
        if (!record.inView) events.notify(record.toThread(), NotificationKind.NEEDS_YOU)
        return true
    }

    /**
     * A thread whose claude asks about trust ([Record.asking]). Its status file comes once the person answers yes,
     * however long that takes, and the thread goes on from there as a starting one, so its first idle is not a finished
     * turn. A status file Skein cannot read ends the wait too, with status unknown and the could-not-read balloon the
     * launch's timeout held back, as a launch with such a file gets it. A claude that ends first (the answer was no)
     * leaves the thread exited, and the tab's shell stays its anchor, so a claude typed there by hand is followed.
     */
    private fun followAsking(record: Record, claude: ProcessId, snapshot: SessionSnapshot, now: Long) {
        val anchor = record.anchor
        val answered = claude.pid in snapshot.sessions || anchor != null && mapper.processFor(anchor, snapshot.sessions.values) != null
        when {
            answered -> {
                record.asking = null
                setState(record, ThreadState.Starting, now)
                clearPending(record)
                anyFileEverMatched = true
            }
            claude.pid in snapshot.unrecognized -> {
                record.asking = null
                clearPending(record)
                setState(record, ThreadState.Unknown, now)
                reportMissingStatusFiles()
            }
            mapper.startOf(claude.pid) != claude.start -> {
                record.asking = null
                record.pending = null
                record.pendingTimedOut = false
                setState(record, ThreadState.Exited, now)
            }
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
                    requestLaunch(record, command, resumeId = sessionId, now = now, recheck = Recheck.ELSEWHERE)
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
     * question included, and meanwhile none of them starts in view ([Record.closing]). A thread whose processes cannot be
     * read gets nothing hung up and a doubt for the log, and the others still end ([endingQuietly]).
     */
    fun close(threadIds: Collection<String>, confirmed: Set<String>, snapshot: SessionSnapshot): CloseOutcome {
        val asked = threadIds.mapNotNull { records[it] }
        val (leftOpen, closing) = asked.partition { CloseRule.isBusy(it.state) && it.id !in confirmed }
        val sessions = snapshot.sessions.values
        for (record in closing) record.closing = true
        return CloseOutcome(leftOpen.map { it.toThread() }, closing.map { endingQuietly(it, sessions) })
    }

    /**
     * A close of [threadIds] that [close] or [endingsBeforeLoss] decided does not happen after all (the ending failed
     * before it got to the tabs), so their threads may start in view again. A tab Skein's own close skipped (not set up in
     * time, or kept open by something else) is not reported here, and its thread then starts only when the user opens it.
     */
    fun closeDropped(threadIds: Collection<String>) {
        for (id in threadIds) records[id]?.closing = false
    }

    /**
     * What to end in each thread as the project closes ([CloseSequence.endOnly]), every thread in the order they came,
     * asking nothing - a busy one too, since the close is decided by then and its terminal goes either way. Like [close],
     * it closes and changes nothing, so the threads are still all there when the project saves them.
     */
    fun endingsAtProjectClose(snapshot: SessionSnapshot): List<Ending> {
        val sessions = snapshot.sessions.values
        return records.values.map { endingQuietly(it, sessions) }
    }

    /**
     * What to end in each of [threadIds] before the IDE closes their tabs past Skein's close check
     * ([io.github.v0rest.skein.terminal.ThreadTerminalListener.tabsClosingPastCheck]), asking nothing, by how the threads
     * fare once the tabs are gone - back in new tabs when [comesBack] (a clear of the editor area, [endingsBeforeLoss]),
     * else gone as after Close (one tab the user closed on its own), which is Close's own pick with every thread of it
     * confirmed, since the close is the user's own, and leaves no note on how a thread comes back. Changes nothing else
     * but that none of them starts in view any more ([Record.closing]).
     */
    fun endingsBeforeTheIdeCloses(threadIds: Collection<String>, snapshot: SessionSnapshot, comesBack: Boolean): List<Ending> =
        if (comesBack) endingsBeforeLoss(threadIds, snapshot) else close(threadIds, confirmed = threadIds.toSet(), snapshot).closing

    /**
     * What to end in each of [threadIds] before the IDE closes their tabs past Skein's close check
     * ([io.github.v0rest.skein.terminal.ThreadTerminalListener.tabsClosingPastCheck]), asking nothing, as for a project
     * close, and how each comes back once its tab is gone ([onTabLost]) - decided now, while claude still runs, since the
     * hang-up turns the thread exited before the tab goes. Closes and changes nothing else, but that none of them starts in
     * view before its tab is gone ([Record.closing]).
     */
    fun endingsBeforeLoss(threadIds: Collection<String>, snapshot: SessionSnapshot): List<Ending> {
        val sessions = snapshot.sessions.values
        return threadIds.mapNotNull { records[it] }.map { record ->
            record.resumeAfterLoss = TabLoss.resumesNow(record.state)
            record.closing = true
            endingQuietly(record, sessions)
        }
    }

    /**
     * The IDE closed thread [threadId]'s tab past Skein's close check and its terminal is gone (a Rollback, Load Context,
     * a restore whose reopen mark the IDE did not honour). The thread stays, with its id, name, pin, colour, worktree and
     * session, in a new tab. A thread whose claude ran in the old one ([TabLoss.resumesNow], decided before the hang-up
     * where Skein ended it) has its shell started at once and its session resumed when it has a conversation to go on
     * with ([resumeAfterLoss]), so the conversation goes on, and its
     * tab is selected when [select] says the old one was the selected editor. Any other comes back as an IDE restart
     * brings it back, not shown, its claude resumed once the user opens it or it stays in view a moment ([InViewStart]).
     * A claude of that session still running (the old tab's, which the IDE ends a moment later on Windows) is waited
     * for, as running elsewhere, and the resume follows once it is gone. No close is reported, so nothing asks about the
     * worktree.
     */
    fun onTabLost(threadId: String, select: Boolean, snapshot: SessionSnapshot, now: Long) {
        val old = records[threadId] ?: return
        val resume = old.resumeAfterLoss ?: TabLoss.resumesNow(old.state)
        // the old tab's launch and shell are no one's any more
        slot.release(old.id)
        old.anchor?.let { slot.releaseAnchor(this, it) }
        val preexisting = mapper.ideChildren() // before the new tab exists, so its own shell is not among them
        val baseline = mapper.ideChildList()
        val tab = terminal.open(threadId, old.folder, old.toThread().name, focus = resume && select, start = resume)
        val record = Record(threadId, old.folder, tab, createdAt = old.createdAt, preexisting = preexisting, baseline = baseline, baselineFinal = resume)
        record.sessionId = old.sessionId
        record.sessionCwd = old.sessionCwd
        record.name = old.name
        record.pinned = old.pinned
        record.color = old.color
        record.worktree = old.worktree
        record.base = old.base
        record.state = old.state
        record.stateSince = old.stateSince
        records[threadId] = record // the same key keeps its place in the map
        // a status file of the old tab's claude may still be in the snapshot after that claude is gone
        val elsewhere = liveElsewhere(record, snapshot)?.takeIf { mapper.isAlive(it.pid) }
        when {
            elsewhere != null -> {
                setState(record, ThreadState.RunningElsewhere(elsewhere.pid), now)
                record.resumeWhenFree = resume
            }
            resume -> resumeAfterLoss(record, now)
            else -> setState(record, ThreadState.NotStarted, now)
        }
    }

    /**
     * The launch of a thread that came back in a new tab ([onTabLost]) - a Resume of its session when that has a
     * conversation on disk ([conversation]), else what a thread without a session starts, a plain `claude` or, for a
     * worktree thread, `claude --worktree`. A claude that never had a turn leaves no conversation, and `claude --resume`
     * would only say it found none. The thread keeps its session id until the new claude's status file brings the new
     * one, so its name follows it there.
     */
    private fun resumeAfterLoss(record: Record, now: Long) {
        val sessionId = record.sessionId?.takeIf(ClaudeCommand::isSessionId)?.takeIf { conversationQuietly(record, it) }
        val worktree = record.worktree
        val command = if (sessionId == null && worktree != null) ClaudeCommand.worktree(worktree) else ClaudeCommand.forSession(sessionId)
        requestLaunch(record, command, resumeId = sessionId, now = now, recheck = Recheck.ELSEWHERE_THEN_RESUME)
    }

    /** [ending], where a failure costs only this thread its hangup, with a doubt for the log, and never the whole close. */
    private fun endingQuietly(record: Record, sessions: Collection<SessionFile>): Ending =
        try {
            ending(record, sessions)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Ending(record.tab, shell = null, claude = null, doubt = "Skein could not tell what runs in its tab: $e")
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
     * a live session file under that anchor ([ThreadMapper.processFor]) or, while it asks about trust and has no such
     * file yet, the claude Skein saw under that very shell ([Record.asking]) - so never a claude in another terminal. The
     * anchor serves the thread's status as it always did. For a signal it must also be, as the very same process (pid
     * and start instant), among the IDE's processes right before Skein typed into the tab ([ThreadTab.shellsAtCommand])
     * and when the tab's session was first seen started ([Record.shellsAtStart]), and not among them before the tab's
     * own shell started ([Record.baseline]). That proves no more than this: the anchor was not opened after the tab's
     * shell (to within a tick) nor before it. It leaves out a terminal the user opened in the same folder at any other
     * time, whose claude may have been taken for the thread's. Only start instants are compared, never with the wall
     * clock, which the JDK's start instants drift from on Linux. An anchor that fails gets nothing hung up, and
     * [Ending.doubt] says why. A tab without an anchor yet goes through [untiedEnding].
     *
     * A claude whose start cannot be read is left out ([Ending.claude] null), so the shell is hung up first; the tab is
     * still open and read then, and the shell passes the hangup on to its jobs, claude among them. Skein then does not
     * wait for that claude itself, only for the session; a live process of the user's own always has a readable start on
     * macOS and Linux, so this is a corner case.
     */
    private fun ending(record: Record, sessions: Collection<SessionFile>): Ending {
        val anchor = record.anchor ?: return untiedEnding(record, sessions)
        val start = mapper.startOf(anchor)
            ?: return Ending(record.tab, shell = null, claude = null, doubt = "the start of its shell (pid $anchor) cannot be read now")
        val shell = ProcessId(anchor, start)
        val doubt = doubtAbout(record, shell)
        if (doubt != null) return Ending(record.tab, shell = null, claude = null, doubt = doubt)
        // a claude asking about trust has no session file yet, but Skein saw it under this very shell
        val claude = mapper.processFor(anchor, sessions)?.pid?.let { pid -> mapper.startOf(pid)?.let { ProcessId(pid, it) } }
            ?: record.asking?.takeIf { mapper.startOf(it.pid) == it.start }
        return Ending(record.tab, shell, claude)
    }

    /**
     * [ending] for a tab Skein has not tied to its shell yet. claude may be up already with its status file not matched
     * (the monitor looks once a second, and a claude asking about trust gets its anchor only at the launch timeout), and
     * closed then, a claude that writes while it exits hangs on for good with its tab gone. So the tab's shell is taken
     * the way the trust wait takes it ([bracketedShell]), and its claude is the one with a live session file under that
     * shell, else the one CLI under it with a readable start. Without either the shell alone is hung up - the tab is still
     * open and read then, and the shell passes the hangup on to its jobs. The thread's anchor and the launch slot stay as
     * they are, the tab is closing. A tab whose session never started (a restored tab never shown) has no shell to hang
     * up and closes without a word, as it always did, and one that cannot say counts as started, so the log still says
     * why nothing was hung up. Never throws - a probe that fails before the shell is proved hangs up nothing and says so,
     * one that fails while looking for claude leaves the shell to hang up alone.
     */
    private fun untiedEnding(record: Record, sessions: Collection<SessionFile>): Ending {
        val started = try {
            terminal.sessionStarted(record.tab)
        } catch (e: Exception) {
            true
        }
        if (!started) return Ending(record.tab, shell = null, claude = null)
        val proof = try {
            bracketedShell(record)
        } catch (e: Exception) {
            TabShell.Doubt("it could not look at the IDE's processes: $e")
        }
        val shell = when (proof) {
            is TabShell.Proved -> proof.shell
            is TabShell.Doubt -> return Ending(record.tab, shell = null, claude = null, doubt = "Skein had not tied the tab to its shell yet, and ${proof.why}")
        }
        val claude = try {
            mapper.processFor(shell.pid, sessions)?.pid?.let { pid -> mapper.startOf(pid)?.let { ProcessId(pid, it) } }
                ?: mapper.clisUnder(shell.pid).singleOrNull()
        } catch (e: Exception) {
            null
        }
        return Ending(record.tab, shell, claude)
    }

    /**
     * Why [shell], [record]'s anchor as it is now or a candidate for it ([bracketedShell]), is not proved the tab's own
     * ([ending]), in words for the log, null when it is.
     */
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

    /**
     * The thread that holds [sessionId] and its claude as [snapshot] and the process table show it now, for a rename
     * waiting to be typed into it ([RenameGate]); null when no thread holds the session. The claude counts only when it
     * is the one under the tab's shell that Skein follows the thread by, with a live status file for that very session.
     */
    fun renameTarget(sessionId: String, snapshot: SessionSnapshot): RenameTarget? {
        val record = records.values.firstOrNull { it.sessionId == sessionId } ?: return null
        val file = record.anchor?.let { mapper.processFor(it, snapshot.sessions.values) }?.takeIf { it.sessionId == sessionId }
        val claude = file?.let { f -> mapper.startOf(f.pid)?.let { ProcessId(f.pid, it) } }
        return RenameTarget(record.toThread(), record.tab, runsSession = file != null, claude = claude)
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

    /** Types [command] into [record]'s tab, or queues it until the slot is free, and then asks [recheck] first. */
    private fun requestLaunch(record: Record, command: String, resumeId: String?, now: Long, recheck: Recheck = Recheck.NONE) {
        setState(record, ThreadState.Starting, now)
        if (slot.tryAcquire(record.id, now, owner = this)) {
            type(record, command, resumeId, now)
        } else {
            record.queued = command
            record.queuedResumeId = resumeId
            record.queuedRecheck = recheck
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
        record.queuedRecheck = Recheck.NONE
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

        /** The last name of [folder] for a tab title, the folder itself for a root. Plain text work, so `C:/w/acme` reads the same on any OS. */
        internal fun folderName(folder: String, style: PathStyle = PathStyle.current): String =
            PathRules.name(folder, style).takeIf { it.isNotBlank() } ?: folder
    }
}
