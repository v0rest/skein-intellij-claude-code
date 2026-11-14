package io.github.v0rest.skein.threads

import com.intellij.openapi.diagnostic.ControlFlowException
import io.github.v0rest.skein.claude.ClaudeHome
import io.github.v0rest.skein.claude.Worktrees
import io.github.v0rest.skein.git.GitEntry
import io.github.v0rest.skein.git.GitToplevel
import io.github.v0rest.skein.git.GitWorktree
import io.github.v0rest.skein.git.WorktreeCounts
import io.github.v0rest.skein.git.WorktreeGit
import io.github.v0rest.skein.git.WorktreeListing
import io.github.v0rest.skein.git.WorktreeReader
import io.github.v0rest.skein.paths.PathRules
import io.github.v0rest.skein.paths.PathStyle
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException

/** What git says a folder under a repository's worktrees folder is. */
sealed interface KeptKind {
    /** A worktree git lists, on [branch] (null when detached), [locked] or not. */
    data class Registered(val branch: String?, val locked: Boolean) : KeptKind

    /** A folder git does not list as a worktree and with no `.git` of its own: a leftover. */
    data object Unregistered : KeptKind

    /**
     * A folder git does not list here but with a `.git` of its own: a worktree git links to another path (a `.git` file,
     * as after the repository moved or was copied; `git worktree repair` links it again), or a repository of its own
     * ([ownRepository], a `.git` folder). Either holds work, so it is never deleted as a leftover, and git is not asked
     * about it.
     */
    data class Unlinked(val ownRepository: Boolean) : KeptKind

    /** git could not list the worktrees, so nothing is known about the folder. */
    data class Unknown(val reason: String) : KeptKind
}

/** A folder under a repository's worktrees folder, as the Worktrees section lists it. */
data class KeptWorktree(
    /** The repository as the project or a thread names it, as [PathRules.canonical] text: a thread opened on the worktree starts there. */
    val repository: String,
    /** The worktree folder under the repository's real path, the links resolved, as [PathRules.canonical] text. */
    val path: String,
    val name: String,
    val kind: KeptKind,
    val counts: WorktreeCounts,
)

/**
 * One look at the worktrees: the counts of this window's worktree threads, by thread id, and every folder under the
 * worktrees folder of each repository Skein knows. Each repository is looked at once, by its real path; git runs only
 * where there is a worktrees folder with something in it or a worktree thread, and only in folders git lists as
 * worktrees (in any other folder it would answer for the repository around it). One git process at a time. A
 * repository that holds the Claude home is left alone, as everywhere else. Paths are compared by the rules of [style]
 * ([PathRules]), so git's `C:/w/acme` and the disk's `C:\w\acme` are one folder on Windows. Blocking: off the EDT.
 */
class WorktreeSweep(
    private val git: WorktreeGit,
    private val reader: WorktreeReader,
    private val disk: Disk,
    private val style: PathStyle = PathStyle.current,
) {
    /** What a look needs from the disk, all of it blocking. */
    interface Disk {
        /** [folder] with its links resolved, or null when it cannot be. */
        fun realPath(folder: String): String?

        /** Whether [folder] is still there and still the top of a repository. */
        fun isRepository(folder: Path): Boolean

        /** Whether Claude Code would put the worktrees of [repository] inside its own home. */
        fun holdsClaudeHome(repository: Path): Boolean

        /** The names of the folders in [dir], links left out; empty when it is not there or cannot be read. */
        fun folders(dir: Path): List<String>

        /** The `.git` entry right in [folder] ([GitToplevel.entryOf]), or null. */
        fun gitEntry(folder: Path): GitEntry?

        fun exists(path: Path): Boolean

        /** Whether the disk says [cwd] lies in the folder [dir] ([SameFolder.holds]); none of the fakes needs to say so. */
        fun sameFolder(dir: String, cwd: String): Boolean = false
    }

    /** The disk as a look reads it. Blocking. */
    object RealDisk : Disk {
        override fun realPath(folder: String): String? = GitToplevel.resolved(folder)

        override fun isRepository(folder: Path): Boolean = GitToplevel.isRepository(folder)

        override fun holdsClaudeHome(repository: Path): Boolean = ClaudeHome.default().holdsWorktreesOf(repository)

        override fun folders(dir: Path): List<String> =
            try {
                Files.newDirectoryStream(dir).use { entries ->
                    entries.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }.map { it.fileName.toString() }
                }
            } catch (_: IOException) {
                emptyList()
            } catch (_: DirectoryIteratorException) {
                emptyList()
            }

        override fun gitEntry(folder: Path): GitEntry? = GitToplevel.entryOf(folder)

        override fun exists(path: Path): Boolean = Files.exists(path)

        override fun sameFolder(dir: String, cwd: String): Boolean = SameFolder.holds(dir, cwd)
    }

    /**
     * What one look found: the counts by thread id, the folders the section may list ([KeptWorktrees.visible] leaves
     * out the ones a thread holds), the real path of each folder it resolved, for that check on the EDT, and whether
     * the section's rows got their counts ([keptCounted]; not while the section is closed).
     */
    data class Result(
        val counts: Map<String, WorktreeCounts>,
        val kept: List<KeptWorktree>,
        val resolved: Map<String, String>,
        val keptCounted: Boolean = true,
    ) {
        companion object {
            val EMPTY = Result(emptyMap(), emptyList(), emptyMap(), keptCounted = false)
        }
    }

    /**
     * Looks at the worktrees of [repositories] (the tops of the project's folders) and of the repositories of [threads],
     * this window's threads. A folder a thread holds gets no counts in the section's list: its thread row has them, and
     * without [keptCounts] (the section is closed, its header needs the folders alone) no row of the section gets any.
     * Whatever one repository's look throws stays with that repository: its threads say `?`, and none of its folders
     * is listed rather than some of them.
     */
    fun run(repositories: List<String>, threads: List<SkeinThread>, keptCounts: Boolean = true): Result {
        val resolved = LinkedHashMap<String, String>()
        fun real(folder: String): String = resolved.getOrPut(folder) { isolated { disk.realPath(folder) } ?: PathRules.canonical(folder, style) }
        val worktreeThreads = threads.filter { thread -> thread.worktree?.let { Worktrees.isValidName(it, style) } == true }
        val counts = HashMap<String, WorktreeCounts>()
        val kept = ArrayList<KeptWorktree>()
        val repos = WorktreeWatch.distinct(repositories + worktreeThreads.map { it.folder }, style) { real(it) }
        for ((repository, named) in repos) {
            val here = worktreeThreads.filter { PathRules.same(real(it.folder), repository, style) }
            val rows = ArrayList<KeptWorktree>()
            try {
                look(repository, named, here, threads, keptCounts, counts, rows, ::real)
                kept += rows
            } catch (e: Exception) {
                if (e is ControlFlowException || e is CancellationException) throw e
                val reason = e.message ?: e.javaClass.simpleName
                for (thread in here) counts[thread.id] = WorktreeCounts.Failed(reason)
            }
        }
        return Result(counts, kept, resolved, keptCounts)
    }

    private fun look(
        repository: String,
        named: String,
        here: List<SkeinThread>,
        threads: List<SkeinThread>,
        keptCounts: Boolean,
        counts: MutableMap<String, WorktreeCounts>,
        kept: MutableList<KeptWorktree>,
        real: (String) -> String,
    ) {
        val top = Path.of(repository)
        if (disk.holdsClaudeHome(top) || !disk.isRepository(top)) {
            for (thread in here) counts[thread.id] = WorktreeCounts.NotShown
            return
        }
        val names = disk.folders(Path.of(Worktrees.parentOf(repository, style))).filter { Worktrees.isValidName(it, style) }.sorted()
        if (names.isEmpty() && here.isEmpty()) return
        val listing = git.listing(top)
        val fallback = lazy { reader.fallbackBase(top) }
        fun countsOf(dir: String, recorded: String?): WorktreeCounts {
            if (!disk.exists(Path.of(dir))) return WorktreeCounts.NotShown
            val worktrees = when (listing) {
                is WorktreeListing.Failed -> return WorktreeCounts.Failed(listing.reason)
                is WorktreeListing.Listed -> listing.worktrees
            }
            val entry = entryAt(worktrees, dir)
            if (entry == null || entry.prunable || entry.bare) return WorktreeCounts.NotShown
            return reader.read(Path.of(dir), recorded, fallback::value)
        }
        for (thread in here) counts[thread.id] = countsOf(Worktrees.dirOf(repository, thread.worktree!!, style), thread.base)
        for (name in names) {
            val dir = Worktrees.dirOf(repository, name, style)
            val kind = when (listing) {
                is WorktreeListing.Failed -> KeptKind.Unknown(listing.reason)
                is WorktreeListing.Listed -> entryAt(listing.worktrees, dir)?.let { KeptKind.Registered(it.branch, it.locked) } ?: unlisted(Path.of(dir))
            }
            val rowCounts = when {
                kind is KeptKind.Unknown -> WorktreeCounts.Failed(kind.reason)
                kind !is KeptKind.Registered || !keptCounts -> WorktreeCounts.NotShown
                // its thread row shows the counts, against the base the thread recorded
                KeptWorktrees.held(dir, threads, real, style, disk::sameFolder) -> WorktreeCounts.NotShown
                else -> countsOf(dir, recorded = null)
            }
            kept += KeptWorktree(PathRules.canonical(named, style), dir, name, kind, rowCounts)
        }
    }

    /**
     * The entry git lists for the folder [dir], by [PathRules.same]: strict, so on Windows a name Win32 would trim
     * (`task-1.`) never matches the folder it opens, and such a folder reads as not listed - which Skein never deletes
     * either, its `.git` file makes it [KeptKind.Unlinked]. Skein does not take such a name for a worktree there anyway.
     */
    private fun entryAt(worktrees: List<GitWorktree>, dir: String): GitWorktree? = worktrees.firstOrNull { PathRules.same(it.path, dir, style) }

    /** A folder git does not list: a leftover, unless a `.git` of its own makes it a worktree linked elsewhere or a repository. */
    private fun unlisted(folder: Path): KeptKind = when (disk.gitEntry(folder)) {
        null -> KeptKind.Unregistered
        GitEntry.FILE -> KeptKind.Unlinked(ownRepository = false)
        GitEntry.FOLDER -> KeptKind.Unlinked(ownRepository = true)
    }

    /** [block], or null when it throws; cancellation and the platform's control flow pass. */
    private fun <T> isolated(block: () -> T): T? =
        try {
            block()
        } catch (e: Exception) {
            if (e is ControlFlowException || e is CancellationException) throw e
            null
        }
}

/** Which of the folders a look found the section lists. */
object KeptWorktrees {
    /**
     * [kept] without the folders an open thread of this window holds, with the threads as they are now: a thread
     * opened on a kept worktree takes its row away at once, not at the next look. [resolved] maps a folder to its real
     * path as the look found it; a folder it did not resolve counts as it is named.
     */
    fun visible(kept: List<KeptWorktree>, threads: List<SkeinThread>, resolved: Map<String, String>, style: PathStyle = PathStyle.current): List<KeptWorktree> {
        val real = { folder: String -> resolved[folder] ?: folder }
        return kept.filter { !held(it.path, threads, real, style) }
    }

    /**
     * Whether one of [threads] holds the worktree folder [dir]: a worktree thread whose worktree it is, whichever path
     * to the repository the thread names ([real] gives a folder's real path), or any thread whose claude runs in it or
     * below it, or whose folder lies in it. A yes keeps the folder from going, so a place the text cannot place for sure
     * ([PathRules.mayBeInside], a `..` say) holds it too, and so does a claude's cwd [sameFolder] finds in it on the disk
     * (blocking; none by default).
     */
    fun held(
        dir: String,
        threads: List<SkeinThread>,
        real: (String) -> String?,
        style: PathStyle = PathStyle.current,
        sameFolder: (dir: String, cwd: String) -> Boolean = { _, _ -> false },
    ): Boolean = threads.any { thread ->
        places(thread, real, style).any { PathRules.mayBeInside(it, dir, style) } ||
            thread.sessionCwd?.takeIf(String::isNotBlank)?.let { sameFolder(dir, it) } == true
    }

    /**
     * The folders [thread] works in: its worktree under the repository as named and as resolved, its claude's cwd as
     * written and as resolved (on Windows a cwd may keep a subst drive or a junction), its folder. An empty one names no
     * place, so it holds nothing, as before.
     */
    private fun places(thread: SkeinThread, real: (String) -> String?, style: PathStyle): List<String> {
        val folder = thread.folder.takeIf(String::isNotBlank)
        val resolved = real(thread.folder)?.takeIf(String::isNotBlank)
        val cwd = thread.sessionCwd?.takeIf(String::isNotBlank)
        val worktrees = thread.worktree?.let { name -> listOfNotNull(folder, resolved).map { Worktrees.dirOf(it, name, style) } }.orEmpty()
        return worktrees + listOfNotNull(cwd, cwd?.let(real)?.takeIf(String::isNotBlank), folder, resolved)
    }
}

/**
 * Whether a session's cwd lies in a folder by the disk's own word. On Windows a subst drive (`W:\acme` for
 * `C:\work\acme`) is no reparse point, so toRealPath keeps it, while [Files.isSameFile] compares the volume and the
 * file id and sees through it, as through a junction or a short name. Only ever adds holds.
 */
object SameFolder {
    /**
     * Whether [dir] is [cwd] or one of its ancestors up to the root, as [isSameFile] says for both while they exist. A
     * look that throws is no answer, and the walk goes on. Blocking.
     */
    fun holds(dir: String, cwd: String, isSameFile: (Path, Path) -> Boolean = Files::isSameFile): Boolean {
        val folder = path(dir) ?: return false
        var at = path(cwd)
        while (at != null) {
            val here: Path = at
            if (answer { isSameFile(folder, here) } == true) return true
            at = here.parent
        }
        return false
    }

    private fun path(text: String): Path? = answer { Path.of(text) }

    private fun <T> answer(block: () -> T): T? =
        try {
            block()
        } catch (e: Exception) {
            if (e is ControlFlowException || e is CancellationException) throw e
            null
        }
}

/**
 * When the worktree rows need a new look: a worktree thread came or went (a closed one may leave its worktree kept),
 * or one finished a turn, its state moving to your turn or idle. Not on the value a state flow replays on subscribe,
 * and not on any other status change.
 */
fun worktreeSweepTriggers(threads: Flow<List<SkeinThread>>): Flow<Unit> = flow {
    var last: Map<String, Boolean>? = null
    threads.collect { list ->
        val now = list.filter { it.worktree != null }.associate { it.id to (it.state is ThreadState.YourTurn || it.state == ThreadState.Idle) }
        val before = last
        last = now
        if (before != null && (before.keys != now.keys || now.any { (id, settled) -> settled && before[id] == false })) emit(Unit)
    }
}

/**
 * Runs the looks one at a time ([serve] is the only loop) and leaves [gapMillis] between the end of one and the start of
 * the next: a request while a look runs, or within [gapMillis] of the last end, is not dropped but waits, and however
 * many came meanwhile, one look serves them all. A request that arrives while the next look is still waiting is served
 * by that look. [requestNow], for Skein's own Open and Remove, skips the wait (a look already running still finishes
 * first).
 */
class SweepGate(
    private val gapMillis: Long,
    private val now: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val urgent = Channel<Unit>(Channel.CONFLATED)

    /** Never blocks, never throws. */
    fun request() {
        requests.trySend(Unit)
    }

    /** A look without waiting out the gap. Never blocks, never throws. */
    fun requestNow() {
        urgent.trySend(Unit)
        requests.trySend(Unit)
    }

    /** Runs [sweep] for the requests, for as long as the calling coroutine lives. */
    suspend fun serve(sweep: suspend () -> Unit) {
        var lastEnd: Long? = null
        for (unused in requests) {
            val wait = lastEnd?.let { it + gapMillis - now() } ?: 0L
            if (wait > 0) waitUnlessUrgent(wait)
            // what came while waiting is served by this look
            requests.tryReceive()
            urgent.tryReceive()
            try {
                sweep()
            } finally {
                lastEnd = now()
            }
        }
    }

    /** [pause]s for [wait], or less when [requestNow] asks meanwhile (or asked while the last look ran). */
    private suspend fun waitUnlessUrgent(wait: Long) {
        if (urgent.tryReceive().isSuccess) return
        coroutineScope {
            val waiting = launch { pause(wait) }
            select {
                waiting.onJoin {}
                urgent.onReceive {}
            }
            waiting.cancel()
        }
    }
}
