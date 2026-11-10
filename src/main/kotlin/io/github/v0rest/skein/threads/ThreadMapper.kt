package io.github.v0rest.skein.threads

import io.github.v0rest.skein.claude.SessionFile
import io.github.v0rest.skein.process.ProcessId
import io.github.v0rest.skein.process.ProcessList
import io.github.v0rest.skein.process.ProcessProbe
import java.io.IOException
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.time.Instant

/**
 * A `claude` Skein has typed into a tab and is waiting to see in the session directory. [preexistingAnchors] are the
 * IDE's child processes that already existed when the tab was opened: other terminals' shells, never this tab's.
 */
data class PendingLaunch(
    val threadId: String,
    val folder: String,
    val typedAt: Long,
    val resumeSessionId: String?,
    val preexistingAnchors: Set<Long>,
    /**
     * A worktree thread's worktree directory, which counts as the thread's folder too: `claude --worktree` starts there,
     * and a resumed worktree session returns there. Null for a thread in an ordinary folder.
     */
    val worktreeCwd: String? = null,
)

/** The status file that answered a [PendingLaunch], and the tab's anchor process it revealed. */
data class LaunchMatch(val anchor: Long, val file: SessionFile)

/**
 * Ties `claude` processes to terminal tabs through the process tree alone. Every process in a tab the IDE opened
 * descends from the IDE, and the ancestor right below the IDE (the shell, or a helper above it) is the tab's anchor.
 */
class ThreadMapper(private val probe: ProcessProbe) {
    /** The IDE's direct child processes right now: every terminal shell (and a few helpers) the IDE started. */
    fun ideChildren(): Set<Long> = probe.children(probe.currentPid()).toSet()

    /**
     * The IDE's direct child processes right now, each with its start instant, so a later process that gets the same pid
     * is not taken for it; a child whose start the system does not report is listed by its pid alone.
     */
    fun ideChildList(): ProcessList {
        val known = HashSet<ProcessId>()
        val startUnknown = HashSet<Long>()
        for (pid in probe.children(probe.currentPid())) {
            val start = probe.startInstant(pid)
            if (start != null) known += ProcessId(pid, start) else startUnknown += pid
        }
        return ProcessList(known, startUnknown)
    }

    /** When [pid] started, or null when it is gone or the system does not say. */
    fun startOf(pid: Long): Instant? = probe.startInstant(pid)

    /** The ancestor of [pid] whose parent is the IDE process, or null when [pid] does not descend from the IDE. */
    fun anchorOf(pid: Long): Long? {
        val ide = probe.currentPid()
        var current = pid
        repeat(MAX_DEPTH) {
            val parent = probe.parent(current) ?: return null
            if (parent == ide) return current
            current = parent
        }
        return null
    }

    /** The thread's process under [anchor]: the outermost interactive session, with no other session between it and the anchor. */
    fun processFor(anchor: Long, sessions: Collection<SessionFile>): SessionFile? =
        sessions
            .filter { it.isInteractive() && anchorOf(it.pid) == anchor && isOutermost(it, anchor, sessions) }
            .minWithOrNull(startOrder)

    /**
     * The session a just-typed `claude` produced: it descends from the IDE under an anchor no thread owns, is
     * outermost there, started after typing, and either carries the resumed session id or runs in the thread's folder
     * (for a worktree thread, the repository or its worktree directory, [PendingLaunch.worktreeCwd], whatever was
     * typed: a resume may come back with another id).
     */
    fun matchLaunch(pending: PendingLaunch, sessions: Collection<SessionFile>, ownedAnchors: Set<Long>): LaunchMatch? {
        val folder = normalize(pending.folder)
        val worktreeFolder = pending.worktreeCwd?.let(::normalize)
        var byFolder: LaunchMatch? = null
        for (file in sessions.filter { it.isInteractive() }.sortedWith(startOrder)) {
            val anchor = anchorOf(file.pid) ?: continue
            if (anchor in ownedAnchors || !isOutermost(file, anchor, sessions)) continue
            // the tab's shell starts with the tab, so a shell that was already there belongs to another terminal
            if (anchor in pending.preexistingAnchors) continue
            // only a claude that started after Skein typed can be the one it typed, whether matched by id or folder
            val startedAt = file.startedAt ?: continue
            if (startedAt < pending.typedAt) continue
            if (pending.resumeSessionId != null && file.sessionId == pending.resumeSessionId) return LaunchMatch(anchor, file)
            if (byFolder == null && file.cwd?.let(::normalize).let { it != null && (it == folder || it == worktreeFolder) }) byFolder = LaunchMatch(anchor, file)
        }
        return byFolder
    }

    private fun isOutermost(file: SessionFile, anchor: Long, sessions: Collection<SessionFile>): Boolean {
        val sessionPids = sessions.mapTo(HashSet()) { it.pid }
        var current = probe.parent(file.pid)
        var depth = 0
        while (current != null && current != anchor && depth++ < MAX_DEPTH) {
            if (current in sessionPids) return false
            current = probe.parent(current)
        }
        return true
    }

    private fun SessionFile.isInteractive(): Boolean = kind == null || kind == "interactive"

    /** The physical path: Claude Code reports getcwd(), which resolves symlinks (on macOS /tmp is /private/tmp). */
    private fun normalize(path: String): String =
        try {
            val p = Path.of(path).toAbsolutePath().normalize()
            try {
                p.toRealPath().toString()
            } catch (e: IOException) {
                p.toString()
            }
        } catch (e: InvalidPathException) {
            path
        }

    private val startOrder = compareBy<SessionFile>({ it.startedAt ?: Long.MAX_VALUE }, { it.pid })

    companion object {
        const val MAX_DEPTH = 64
    }
}
