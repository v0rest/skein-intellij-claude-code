package io.github.v0rest.skein.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.text.StringUtil
import io.github.v0rest.skein.claude.ClaudeHome
import io.github.v0rest.skein.claude.SessionSnapshot
import io.github.v0rest.skein.claude.WorktreeLock
import io.github.v0rest.skein.claude.Worktrees
import io.github.v0rest.skein.git.GitEntry
import io.github.v0rest.skein.git.GitResult
import io.github.v0rest.skein.git.GitToplevel
import io.github.v0rest.skein.git.GitWorktree
import io.github.v0rest.skein.git.InnerRepositories
import io.github.v0rest.skein.git.LeftoverWorktreeFolder
import io.github.v0rest.skein.git.ProcessGitRunner
import io.github.v0rest.skein.git.WorktreeGit
import io.github.v0rest.skein.git.WorktreeListing
import io.github.v0rest.skein.git.WorktreeState
import io.github.v0rest.skein.notify.ThreadNotifier
import io.github.v0rest.skein.process.JdkProcessProbe
import io.github.v0rest.skein.process.ProcessProbe
import io.github.v0rest.skein.threads.CloseOffer
import io.github.v0rest.skein.threads.KeptWorktrees
import io.github.v0rest.skein.threads.SkeinThread
import io.github.v0rest.skein.threads.ThreadService
import io.github.v0rest.skein.threads.WorktreeClose
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/** The words of the close flow. */
object WorktreeCloseText {
    fun title(name: String): String = "Worktree $name"

    /** What every remove question adds: git deletes the ignored files with the worktree, and the counts leave them out. */
    const val IGNORED_GO_TOO = "Removing it also deletes its ignored files, such as build output and .env."

    /** What a remove question adds when Remove first lifts the lock Claude Code left on the worktree ([WorktreeLock.Stale]). */
    const val LIFTS_LOCK = "Claude Code left it locked and the claude that locked it has ended, so Skein lifts that lock first."

    /**
     * The question: what is left in the worktree, the branch Remove would delete (none when git named none), that Remove
     * lifts the lock Claude Code left behind ([liftsLock]), and that the ignored files go too.
     */
    fun message(name: String, offer: CloseOffer.KeepOrRemove, branch: String?, liftsLock: Boolean = false): String {
        val left = left(offer)
        val what = if (branch == null) "the worktree" else "the worktree and its branch $branch"
        return "Worktree $name is still there: $left. Keep it, or remove $what? ${lifts(liftsLock)}$IGNORED_GO_TOO"
    }

    fun kept(name: String, reason: String): String = "Worktree $name is kept: $reason"

    fun notRemoved(name: String, reason: String): String = "Worktree $name was not removed: $reason"

    /** The worktree stays, but the lock Claude Code had left on it is gone: Skein lifted it, its claude had ended. */
    fun notRemovedLockLifted(name: String, reason: String): String =
        "Worktree $name was not removed, though Skein lifted the lock Claude Code had left on it: $reason"

    /** The lock git listed right before the lift was not the one the question named: nothing was lifted or removed. */
    const val LOCK_CHANGED = "its lock changed while Skein checked it, so Skein lifted nothing"

    /** git could not lift the lock Claude Code left on the worktree, or list it right before: nothing was removed. */
    fun lockNotLifted(name: String, reason: String): String = notRemoved(name, "Skein could not lift the lock Claude Code left on it: $reason")

    fun removed(name: String, branch: String): String = "Worktree $name and its branch $branch were removed"

    /** git refused `branch -d`, which it does for a branch with commits not merged anywhere: they stay on the branch. */
    fun branchKept(name: String, branch: String, reason: String): String =
        "Worktree $name was removed, but Skein kept the branch $branch, it has unmerged commits: $reason"

    /** git named no branch for the worktree (detached, or not listed under the expected path): nothing was deleted. */
    fun branchUnknown(name: String): String = "Worktree $name was removed; its branch is unknown, so none was deleted"

    /**
     * The Worktrees section's Remove…: the worktree named, what is left in it, the branch it would delete, that it first
     * lifts the lock Claude Code left behind ([liftsLock]), and that the ignored files go too.
     */
    fun removeQuestion(name: String, offer: CloseOffer.KeepOrRemove, branch: String?, liftsLock: Boolean = false): String {
        val what = if (branch == null) "the worktree $name" else "the worktree $name and its branch $branch"
        return "Remove $what? Left in it: ${left(offer)}. ${lifts(liftsLock)}$IGNORED_GO_TOO"
    }

    fun removeTitle(name: String): String = "Remove Worktree $name"

    /**
     * The second question, after git refused the removal: git's own words ([gitMessage]), and what git's force would
     * lose for good.
     */
    fun forceQuestion(name: String, gitMessage: String): String =
        "git did not remove the worktree $name:\n\n$gitMessage\n\nRemove it anyway? Everything in it that is not committed " +
            "is lost for good, changed and untracked files and ignored ones such as build output alike."

    fun forceTitle(name: String): String = "Force Remove Worktree $name"

    /** The question before deleting a folder git does not list as a worktree. */
    fun deleteQuestion(folder: String): String =
        "git does not list $folder as a worktree. Delete the folder and everything in it? This cannot be undone."

    fun deleteTitle(name: String): String = "Delete Folder $name"

    fun deleted(name: String): String = "Folder $name was deleted"

    fun notDeleted(name: String, reason: String): String = "Folder $name was not deleted: $reason"

    fun gone(name: String): String = "Worktree $name is already gone"

    /** The section's Remove… of a worktree a close question or another Remove… is busy with. */
    fun busy(name: String): String = "Worktree $name is already being closed or removed"

    /**
     * Why a folder with a `.git` file that git does not list here stays: it is a worktree git links to another path,
     * which happens when the repository moved or was copied, and `git worktree repair` links it again.
     */
    fun linkedElsewhere(folder: String): String =
        "it is a worktree git links to another path, as after the repository moved or was copied. " +
            "To link it here again, run git worktree repair $folder in the repository"

    private fun lifts(liftsLock: Boolean): String = if (liftsLock) "$LIFTS_LOCK " else ""

    private fun left(offer: CloseOffer.KeepOrRemove): String =
        count(offer.changedFiles, "changed file", "changed files") + ", " + count(offer.unpushedCommits, "commit", "commits") + " not pushed"

    private fun count(n: Int, one: String, many: String): String = "$n ${if (n == 1) one else many}"
}

/**
 * What happens when a worktree thread's tab closes. Once the thread's own `claude` has had time to exit, a worktree
 * that is still there gets a question when no other session or Skein thread uses it, no editor holds an unsaved change
 * to a file in it, no other worktree lies inside it, git could read its state and, on a detached HEAD, every commit is
 * held by a branch, a tag or a remote: what is left in it, Keep (the default) or Remove; otherwise it is kept and Skein
 * says why. After Remove all of that is checked again, the unsaved edits last, and then Skein runs `git worktree remove`
 * and `git branch -d` for the branch the worktree had; git refuses a worktree with changes and a branch not merged, and
 * then Skein says why and leaves it there. Nothing is forced or retried, and a worktree in a repository that holds the
 * Claude home is never touched (Skein says so). git runs off the EDT, only the questions may touch Swing, and one
 * close at a time per worktree asks anything (whichever path to the repository its thread names).
 *
 * Claude Code locks the worktree it makes and can leave the lock behind when its claude ends ([WorktreeLock]). While
 * that claude still runs the worktree is kept, and Skein says so. Once it has ended the question says Skein lifts the
 * lock, and after a yes, with the checks run again and the same lock still there, git lists the worktree once more and,
 * while it is still that lock, Skein runs `git worktree unlock` and the plain remove right after it; from there on all
 * goes as for a worktree that was never locked, and the lock stays lifted whatever git answers. A lock anyone else made
 * is never lifted.
 *
 * The Worktrees section's Remove… goes the same way ([removeKept]), with one step more for a worktree git lists: when
 * git refuses the plain removal of one that is not locked only because of changed or untracked files
 * ([WorktreeGit.refusedForChanges]), git's words go into a second question ([confirmForce]) that says what is lost;
 * after a yes there the checks run once more, git is asked once more without force, and only its same refusal leads to
 * the force. Any other refusal (a submodule, a lock Skein did not lift, a broken worktree) is shown as git words it,
 * with no force. In both flows a repository of its own inside the worktree, ignored or not, keeps it, since any
 * removal would delete it ([WorktreeGit.repositoriesInside]); when git cannot say, nothing is removed. A folder git
 * does not list and that has no `.git` of its own is deleted as a plain folder after a question of its own
 * ([confirmDelete]).
 * Closing a thread never forces.
 */
class WorktreeCloseFlow(
    /** Reads: the worktree list and its state. */
    private val git: WorktreeGit,
    /** `worktree remove` and `branch -d`, which may take longer than a read on a big worktree. */
    private val removals: WorktreeGit = git,
    /** The live sessions; a state flow never completes, so a wait on it ends by its timeout at the latest. */
    private val snapshots: StateFlow<SessionSnapshot>,
    /** The project's open threads; the closed one may still be among them. */
    private val threads: () -> List<SkeinThread> = { emptyList() },
    /** The worktree folders a close flow is busy with, shared by every flow of the project. */
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet(),
    /** Keep or Remove; true for Remove. The section's Remove… asks Remove or Cancel. */
    private val ask: suspend (message: String, title: String) -> Boolean,
    /** The section's second question, after git refused: force the removal or not; true to force. Closing never asks it. */
    private val confirmForce: suspend (message: String, title: String) -> Boolean = { _, _ -> false },
    /** The section's question before deleting a folder git does not list; true to delete. */
    private val confirmDelete: suspend (message: String, title: String) -> Boolean = { _, _ -> false },
    private val report: (text: String, warning: Boolean) -> Unit,
    /** Whether an editor holds a change, not saved yet, to a file in one of these worktree folders: git cannot see it. */
    private val unsavedEdits: suspend (dirs: Set<String>) -> Boolean,
    /** Whether Claude Code would put this repository's worktrees inside the Claude home; blocking. */
    private val insideClaudeHome: (repository: Path) -> Boolean,
    private val exists: (Path) -> Boolean = { Files.exists(it) },
    /** A folder with its links resolved, as git and the status files report it, or null; blocking. */
    private val resolve: (String) -> String? = GitToplevel::resolved,
    /** False once the project is closing: the flow then stops where it is. */
    private val active: () -> Boolean = { true },
    private val settleMillis: Long = SETTLE_MILLIS,
    /** Whether a path is a link; blocking. */
    private val isLink: (Path) -> Boolean = { Files.isSymbolicLink(it) },
    /** Why a leftover folder holds a `.git` somewhere inside ([LeftoverWorktreeFolder.gitInside]); null when none does. Blocking. */
    private val gitInside: (Path) -> String? = { LeftoverWorktreeFolder.gitInside(it) },
    /** Deletes a folder git does not list ([LeftoverWorktreeFolder.delete]); null once it is gone, else why not. Blocking. */
    private val deleteLeftover: (repository: String, name: String) -> String? = LeftoverWorktreeFolder::delete,
    /** Whether the claude that locked a worktree still runs ([WorktreeLock.of]); blocking. */
    private val probe: ProcessProbe = JdkProcessProbe(),
    /** The system's zone: the start in Claude Code's lock reason is read in it and as UTC ([WorktreeLock.of]). */
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    suspend fun run(thread: SkeinThread) {
        val name = thread.worktree ?: return
        try {
            // a name from a hand-edited workspace file must never become a path git is asked to remove
            if (!Worktrees.isValidName(name)) return
            // a repository around the Claude home (a dotfiles repository in the home folder) is not Skein's to clean
            if (withContext(Dispatchers.IO) { insideClaudeHome(Path.of(thread.folder)) }) {
                if (withContext(Dispatchers.IO) { exists(Path.of(Worktrees.dirOf(thread.folder, name))) }) {
                    report(WorktreeCloseText.kept(name, WorktreeClose.CLAUDE_HOME), false)
                }
                return
            }
            once(thread, name)
        } catch (_: ProcessCanceledException) {
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("Skein could not finish closing the worktree $name", e)
        }
    }

    /**
     * A second close of a worktree already being asked about (two threads in it, both closed) asks nothing, also when
     * the two threads name the repository by different paths (a link to it and its real path).
     */
    private suspend fun once(thread: SkeinThread, name: String) {
        val repository = thread.folder.trimEnd('/')
        val resolved = withContext(Dispatchers.IO) { resolve(repository) }
        val key = Worktrees.dirOf(resolved ?: repository, name)
        if (!inFlight.add(key)) return
        try {
            // the repository as the thread names it and as git and the status files report it
            val repositories = setOfNotNull(repository, resolved, thread.sessionCwd?.let(Worktrees::split)?.repository)
            offer(thread, name, repository, repositories)
        } finally {
            inFlight.remove(key)
        }
    }

    private suspend fun offer(thread: SkeinThread, name: String, repository: String, repositories: Set<String>) {
        val worktree = Path.of(Worktrees.dirOf(repository, name))
        val dirs = repositories.mapTo(LinkedHashSet()) { Worktrees.dirOf(it, name) }
        // the closed thread's own claude exits a moment after its tab (and may clean up after itself): wait for that,
        // so a session still inside afterwards really is another one, and nothing is read while it is going
        val settled = settled(repositories, name, thread.sessionId)
        if (!active()) return
        val first = check(thread.id, name, repository, repositories, worktree, dirs, settled)
        when (first.offer) {
            CloseOffer.Nothing -> Unit
            is CloseOffer.KeepOnly -> report(WorktreeCloseText.kept(name, first.offer.reason), false)
            is CloseOffer.KeepOrRemove -> {
                // the branch named in the question is the one Remove deletes
                val branch = first.view.entry?.branch
                val question = WorktreeCloseText.message(name, first.offer, branch, liftsLock = first.view.lock is WorktreeLock.Stale)
                if (!ask(question, WorktreeCloseText.title(name)) || !active()) return
                // asked again, right before the removal: while the question was open the worktree may have gone, a
                // session or thread may have started in it, git may see it differently, or a file in it may have been edited
                val now = check(thread.id, name, repository, repositories, worktree, dirs, settled = true)
                when (now.offer) {
                    CloseOffer.Nothing -> Unit
                    is CloseOffer.KeepOnly -> report(WorktreeCloseText.kept(name, now.offer.reason), false)
                    is CloseOffer.KeepOrRemove -> remove(Path.of(repository), worktree, repositories, name, branch, lifts(first, now))
                }
            }
        }
    }

    /**
     * The Worktrees section's Remove… of the worktree [name] of [repository] (as the project names it), which no thread
     * of this window holds. A worktree git lists gets the close flow's checks, with every thread of every open window
     * ([threads]) counting, then the question, the checks again and the removal, and git's force only after a second
     * yes ([remove]). A folder git does not list goes as a plain folder ([leftover]). Nothing is asked when git cannot
     * list the worktrees, or for a repository that contains the Claude home. Off the EDT but for the questions.
     */
    suspend fun removeKept(repository: String, name: String) {
        if (!Worktrees.isValidName(name)) return
        try {
            val named = repository.trimEnd('/')
            if (withContext(Dispatchers.IO) { insideClaudeHome(Path.of(named)) }) return report(WorktreeCloseText.notRemoved(name, WorktreeClose.CLAUDE_HOME), false)
            val resolved = withContext(Dispatchers.IO) { resolve(named) }
            val key = Worktrees.dirOf(resolved ?: named, name)
            if (!inFlight.add(key)) return report(WorktreeCloseText.busy(name), false)
            try {
                kept(named, setOfNotNull(named, resolved), name)
            } finally {
                inFlight.remove(key)
            }
        } catch (_: ProcessCanceledException) {
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("Skein could not remove the worktree $name", e)
        }
    }

    private suspend fun kept(repository: String, repositories: Set<String>, name: String) {
        val worktree = Path.of(Worktrees.dirOf(repository, name))
        val dirs = repositories.mapTo(LinkedHashSet()) { Worktrees.dirOf(it, name) }
        val worktrees = when (val listing = withContext(Dispatchers.IO) { git.listing(Path.of(repository)) }) {
            is WorktreeListing.Failed -> return report(WorktreeCloseText.notRemoved(name, listing.reason), true)
            is WorktreeListing.Listed -> listing.worktrees
        }
        if (entryOf(worktrees, repositories, name) == null) return leftover(repository, repositories, name, worktree, dirs)
        val recheck: suspend () -> Check = { check(null, name, repository, repositories, worktree, dirs, settled = true) }
        val first = recheck()
        when (first.offer) {
            CloseOffer.Nothing -> report(WorktreeCloseText.gone(name), false)
            is CloseOffer.KeepOnly -> report(WorktreeCloseText.notRemoved(name, first.offer.reason), false)
            is CloseOffer.KeepOrRemove -> {
                val branch = first.view.entry?.branch
                val question = WorktreeCloseText.removeQuestion(name, first.offer, branch, liftsLock = first.view.lock is WorktreeLock.Stale)
                if (!ask(question, WorktreeCloseText.removeTitle(name)) || !active()) return
                val now = recheck()
                when (now.offer) {
                    CloseOffer.Nothing -> report(WorktreeCloseText.gone(name), false)
                    is CloseOffer.KeepOnly -> report(WorktreeCloseText.notRemoved(name, now.offer.reason), false)
                    is CloseOffer.KeepOrRemove -> {
                        val lift = lifts(first, now)
                        // a lock Skein does not lift stays, and git's force would not do for it
                        val force = ForceStep(now.view.entry?.locked == true && lift == null) { recheck().offer }
                        remove(Path.of(repository), worktree, repositories, name, branch, lift, force)
                    }
                }
            }
        }
    }

    /**
     * A folder under the worktrees folder that git does not list: asked about, checked again, and deleted as a plain
     * folder ([deleteLeftover]).
     */
    private suspend fun leftover(repository: String, repositories: Set<String>, name: String, folder: Path, dirs: Set<String>) {
        val recheck: suspend () -> CloseOffer = { leftoverOffer(repository, repositories, name, folder, dirs) }
        when (val first = recheck()) {
            CloseOffer.Nothing -> report(WorktreeCloseText.gone(name), false)
            is CloseOffer.KeepOnly -> report(WorktreeCloseText.notDeleted(name, first.reason), false)
            is CloseOffer.KeepOrRemove -> {
                if (!confirmDelete(WorktreeCloseText.deleteQuestion(folder.toString()), WorktreeCloseText.deleteTitle(name)) || !active()) return
                when (val now = recheck()) {
                    CloseOffer.Nothing -> report(WorktreeCloseText.gone(name), false)
                    is CloseOffer.KeepOnly -> report(WorktreeCloseText.notDeleted(name, now.reason), false)
                    is CloseOffer.KeepOrRemove -> {
                        val failure = withContext(NonCancellable) { withContext(Dispatchers.IO) { deleteLeftover(repository, name) } }
                        if (failure == null) report(WorktreeCloseText.deleted(name), false) else report(WorktreeCloseText.notDeleted(name, failure), true)
                    }
                }
            }
        }
    }

    /**
     * Whether the leftover [folder] may go: it is still there, not a link, git can list the worktrees and lists neither
     * it (it may have become a worktree meanwhile) nor one inside it, it has no `.git` of its own (a `.git` file makes it
     * a worktree git links to another path, a `.git` folder a repository of its own, and either holds work) and none
     * anywhere deeper ([LeftoverWorktreeFolder.gitInside]), no session
     * and no thread of any window is in it, the repository tracks nothing in it (a delete would be a change to commit),
     * and, last, no editor holds an unsaved change to a file in it.
     */
    private suspend fun leftoverOffer(repository: String, repositories: Set<String>, name: String, folder: Path, dirs: Set<String>): CloseOffer {
        if (!withContext(Dispatchers.IO) { exists(folder) }) return CloseOffer.Nothing
        val reason = withContext(Dispatchers.IO) { leftoverRefusal(repository, repositories, name, folder) }
        if (reason != null) return CloseOffer.KeepOnly(reason)
        if (unsavedEdits(dirs)) return CloseOffer.KeepOnly(WorktreeClose.UNSAVED_EDITS)
        return CloseOffer.KeepOrRemove(0, 0)
    }

    /** Why the leftover [folder] must stay ([leftoverOffer]), the editors aside; null when nothing holds it. Blocking. */
    private fun leftoverRefusal(repository: String, repositories: Set<String>, name: String, folder: Path): String? {
        if (isLink(folder)) return LINK
        val worktrees = when (val listing = git.listing(Path.of(repository))) {
            is WorktreeListing.Failed -> return listing.reason
            is WorktreeListing.Listed -> listing.worktrees
        }
        return when {
            entryOf(worktrees, repositories, name) != null -> NOW_LISTED
            holdsAnother(worktrees, repositories, name) -> WorktreeClose.NESTED_WORKTREE
            else -> when (GitToplevel.entryOf(folder)) {
                GitEntry.FILE -> WorktreeCloseText.linkedElsewhere(folder.toString())
                GitEntry.FOLDER -> OWN_REPOSITORY
                // a clone or a worktree link deeper inside holds work too, and a look that cannot finish counts as one
                null -> gitInside(folder) ?: when {
                    liveInside(snapshots.value, repositories, name) -> WorktreeClose.SESSION_INSIDE
                    repositories.any { KeptWorktrees.held(Worktrees.dirOf(it, name), threads(), resolve) } -> WorktreeClose.OTHER_THREAD
                    else -> trackedThere(Path.of(repository), folder)
                }
            }
        }
    }

    /** Why [folder] must stay because [repository] tracks files in it, or git could not say; null when it tracks none. */
    private fun trackedThere(repository: Path, folder: Path): String? {
        val result = git.tracked(repository, repository.relativize(folder).toString())
        return when {
            !result.ok -> if (result.ran || result.timedOut) result.reason() else "git could not be run: " + result.reason()
            result.stdout.isNotBlank() -> TRACKED
            else -> null
        }
    }

    private class Check(val offer: CloseOffer, val view: GitView)

    /**
     * The lock Remove lifts, if any: a lock Claude Code left behind, both when the question was asked, which then said
     * so, and right now, after it, and the same lock both times. A lock that came while the question was open, or that
     * changed, is not lifted, and git refuses as for any lock.
     */
    private fun lifts(asked: Check, now: Check): WorktreeLock.Stale? = (now.view.lock as? WorktreeLock.Stale)?.takeIf { it == asked.view.lock }

    /** What the worktree's lock means now, as git lists [entry]; none for a worktree git does not list. Blocking. */
    private fun lockOf(entry: GitWorktree?): WorktreeLock = entry?.let { WorktreeLock.of(it.locked, it.lockReason, probe, zone) } ?: WorktreeLock.None

    /**
     * Everything that decides the offer, in this order: the worktree is there, no session ([settled]) and no other thread
     * uses it, what git says, and last the editors: a change still only in an editor is invisible to git, and Remove
     * would lose it, so nothing may come between that look and the question or the removal. [closedId] is the closed
     * thread, null for the section's Remove…, where any thread at or inside the worktree counts.
     */
    private suspend fun check(
        closedId: String?,
        name: String,
        repository: String,
        repositories: Set<String>,
        worktree: Path,
        dirs: Set<String>,
        settled: Boolean,
    ): Check {
        val present = withContext(Dispatchers.IO) { exists(worktree) }
        val live = !settled || liveInside(snapshots.value, repositories, name)
        val otherThread = withContext(Dispatchers.IO) {
            if (closedId != null) {
                sharedWithThread(threads(), closedId, repositories, name, resolve)
            } else {
                repositories.any { KeptWorktrees.held(Worktrees.dirOf(it, name), threads(), resolve) }
            }
        }
        val view = if (present && !live && !otherThread) withContext(Dispatchers.IO) { read(repository, worktree, repositories, name) } else GitView.NONE
        val unsaved = present && unsavedEdits(dirs)
        val claudeRuns = (view.lock as? WorktreeLock.Live)?.pid
        val offer = WorktreeClose.offer(present, live, view.state, otherThread, unsaved, view.nested, view.detachedCommits, view.inner, claudeRuns)
        return Check(offer, view)
    }

    /**
     * What git says about the worktree: its entry in the list (and so its branch), whether it holds another, its state,
     * and what its lock means ([WorktreeLock.of]).
     */
    private class GitView(
        val entry: GitWorktree?,
        val state: WorktreeState?,
        val nested: Boolean,
        val detachedCommits: Int,
        val inner: String? = null,
        val lock: WorktreeLock = WorktreeLock.None,
    ) {
        companion object {
            val NONE = GitView(null, null, false, 0)
        }
    }

    private fun read(repository: String, worktree: Path, repositories: Set<String>, name: String): GitView {
        val worktrees = git.list(Path.of(repository))
        // a folder git does not list as a worktree has no state of its own (git would answer for the working tree around
        // it), and neither has one git marks prunable, whose link to the repository is broken
        val entry = entryOf(worktrees, repositories, name)?.takeUnless { it.prunable }
        // Claude Code's own lock: whether the claude that made it still runs
        val lock = lockOf(entry)
        // the worktrees folder is ignored, so git status here cannot see a worktree inside this one, and the removal would delete it
        if (holdsAnother(worktrees, repositories, name)) return GitView(entry, null, true, 0, lock = lock)
        if (entry == null) return GitView.NONE
        val state = git.state(worktree) ?: return GitView(entry, null, false, 0, lock = lock)
        // a repository of its own inside, untracked or ignored: git status above cannot see an ignored one, and git
        // worktree remove deletes it without a word, with its commits
        val inner = when (val found = git.repositoriesInside(worktree)) {
            is InnerRepositories.Failed -> WorktreeClose.innerUnknown(found.reason, worktree.toString())
            is InnerRepositories.Listed -> found.paths.takeIf { it.isNotEmpty() }?.let { WorktreeClose.innerRepositories(it, worktree.toString()) }
        }
        if (inner != null) return GitView(entry, state, false, 0, inner, lock)
        if (entry.branch != null) return GitView(entry, state, false, 0, lock = lock)
        // a detached HEAD: git removes the worktree without a word, even with commits nothing else holds
        val detached = git.detachedCommits(worktree) ?: return GitView(entry, null, false, 0, lock = lock)
        return GitView(entry, state, false, detached, lock = lock)
    }

    /**
     * What the section's Remove… adds after git refused: its force behind [confirmForce], unless the worktree is
     * [locked] by a lock Skein did not lift. [recheck] runs the checks again after that question.
     */
    private class ForceStep(val locked: Boolean, val recheck: suspend () -> CloseOffer)

    /** What [liftThenRemove] did. */
    private sealed interface Removal {
        /** git's answer to the plain remove, after the lift if there was one. */
        data class Ran(val result: GitResult) : Removal

        /** The lock right before the lift was not the one the question named: nothing was lifted or removed. */
        data object LockChanged : Removal

        /** git could not lift the lock, or list the worktree right before: nothing was removed. */
        data class NotLifted(val reason: String) : Removal
    }

    /**
     * git's plain remove of [worktree], with the stale lock [lift] lifted right before it: git lists the worktree once
     * more and only while its lock is still [lift] does `git worktree unlock` run, the plain remove right after it.
     * Blocking.
     */
    private fun liftThenRemove(repository: Path, worktree: Path, repositories: Set<String>, name: String, lift: WorktreeLock.Stale?): Removal {
        if (lift != null) {
            val worktrees = when (val listing = git.listing(repository)) {
                is WorktreeListing.Failed -> return Removal.NotLifted(listing.reason)
                is WorktreeListing.Listed -> listing.worktrees
            }
            if (lockOf(entryOf(worktrees, repositories, name)?.takeUnless { it.prunable }) != lift) return Removal.LockChanged
            val lifted = removals.liftLock(repository, worktree)
            if (!lifted.ok) return Removal.NotLifted(reason(lifted))
        }
        return Removal.Ran(removals.remove(repository, worktree))
    }

    /**
     * git's plain remove of [worktree], after lifting the lock Claude Code left on it ([lift], [liftThenRemove]), then
     * the force step for the section ([force]) and `branch -d`. A lifted lock stays lifted whatever comes after, a
     * refusal or a no to the force: its claude has ended, and the reports say so.
     */
    private suspend fun remove(
        repository: Path,
        worktree: Path,
        repositories: Set<String>,
        name: String,
        branch: String?,
        lift: WorktreeLock.Stale?,
        force: ForceStep? = null,
    ) {
        // once started, git finishes and its answer is reported even if the project closes meanwhile; the lock goes only
        // here, after the yes and the checks again, in one step with the plain remove
        val removal = withContext(NonCancellable) { withContext(Dispatchers.IO) { liftThenRemove(repository, worktree, repositories, name, lift) } }
        val removed = when (removal) {
            is Removal.Ran -> removal.result
            Removal.LockChanged -> return report(WorktreeCloseText.notRemoved(name, WorktreeCloseText.LOCK_CHANGED), true)
            is Removal.NotLifted -> return report(WorktreeCloseText.lockNotLifted(name, removal.reason), true)
        }
        val notRemoved = { reason: String ->
            if (lift != null) WorktreeCloseText.notRemovedLockLifted(name, reason) else WorktreeCloseText.notRemoved(name, reason)
        }
        if (!removed.ok) {
            // closing never forces; a locked worktree would take git's force twice; any refusal but the one for changed
            // or untracked files (a submodule, whose commits live under the worktree's git folder, say) is git's last word
            if (force == null) return report(notRemoved(reason(removed)), true)
            if (force.locked || !WorktreeGit.refusedForChanges(removed)) return report(notRemoved(removed.message()), true)
            if (!confirmForce(WorktreeCloseText.forceQuestion(name, removed.message()), WorktreeCloseText.forceTitle(name)) || !active()) return
            // asked again before the force: while the second question was open anything may have changed
            when (val now = force.recheck()) {
                CloseOffer.Nothing -> return
                is CloseOffer.KeepOnly -> return report(notRemoved(now.reason), false)
                is CloseOffer.KeepOrRemove -> Unit
            }
            // and git without force once more: a submodule set up meanwhile gets git's own refusal, not the force
            val again = withContext(NonCancellable) { withContext(Dispatchers.IO) { removals.remove(repository, worktree) } }
            if (!again.ok) {
                if (!WorktreeGit.refusedForChanges(again)) return report(notRemoved(again.message()), true)
                val forced = withContext(NonCancellable) { withContext(Dispatchers.IO) { removals.forceRemove(repository, worktree) } }
                if (!forced.ok) return report(notRemoved(forced.message()), true)
            }
        }
        if (branch == null) return report(WorktreeCloseText.branchUnknown(name), false)
        val deleted = withContext(Dispatchers.IO) { removals.deleteBranch(repository, branch) }
        if (deleted.ok) {
            report(WorktreeCloseText.removed(name, branch), false)
        } else {
            report(WorktreeCloseText.branchKept(name, branch, reason(deleted)), true)
        }
    }

    /**
     * True once no session runs inside the worktree and the closed thread's own session ([ownSessionId]) is gone,
     * whatever folder its status file names; false when either is still there after [settleMillis].
     */
    private suspend fun settled(repositories: Set<String>, name: String, ownSessionId: String?): Boolean =
        withTimeoutOrNull(settleMillis) {
            snapshots.first { snapshot ->
                !liveInside(snapshot, repositories, name) && (ownSessionId == null || snapshot.sessions.values.none { it.sessionId == ownSessionId })
            }
        } != null

    private fun reason(result: GitResult): String = result.reason()

    companion object {
        private val LOG = logger<WorktreeCloseFlow>()

        /** How long the closed thread's own claude has to exit before a session still inside counts as another one. */
        const val SETTLE_MILLIS = 10_000L

        /** Removing a big worktree takes a while, and a remove cut off half way leaves half a folder behind. */
        private const val REMOVAL_TIMEOUT_MILLIS = 5 * 60_000

        private const val KEEP = "Keep"
        private const val REMOVE = "Remove"
        private const val FORCE_REMOVE = "Force Remove"
        private const val DELETE = "Delete"
        private const val CANCEL = "Cancel"

        const val LINK = "it is a link, and Skein deletes nothing through a link"
        const val NOW_LISTED = "git lists it as a worktree now"
        const val OWN_REPOSITORY = "it holds a git repository of its own, whose commits may be nowhere else"
        const val TRACKED = "git tracks files in it, so deleting it would be a change to commit"

        /**
         * Whether a session in [snapshot] runs in the worktree [name] of one of [repositories], or anywhere below it (a
         * worktree nested inside it included).
         */
        fun liveInside(snapshot: SessionSnapshot, repositories: Set<String>, name: String): Boolean {
            val dirs = repositories.map { Worktrees.dirOf(it, name) }
            return snapshot.sessions.values.any { session -> session.cwd?.let { cwd -> dirs.any { isAtOrUnder(cwd, it) } } == true }
        }

        /**
         * Whether an open thread other than [closedId] runs in the worktree [name] of one of [repositories] (or in a
         * worktree nested inside it), whatever its claude's cwd and whichever path to the repository it names;
         * [resolve] gives a folder's real path, and may block.
         */
        fun sharedWithThread(
            threads: List<SkeinThread>,
            closedId: String,
            repositories: Set<String>,
            name: String,
            resolve: (String) -> String? = { null },
        ): Boolean {
            val dirs = repositories.map { Worktrees.dirOf(it, name) }
            return threads.any { other ->
                val worktree = other.worktree ?: return@any false
                if (other.id == closedId) return@any false
                val usesIt = { folder: String? -> folder != null && dirs.any { isAtOrUnder(Worktrees.dirOf(folder, worktree), it) } }
                usesIt(other.folder.trimEnd('/')) || usesIt(resolve(other.folder))
            }
        }

        /** Whether git lists a worktree below the worktree [name] of one of [repositories] (not that worktree itself). */
        fun holdsAnother(worktrees: List<GitWorktree>, repositories: Set<String>, name: String): Boolean {
            val dirs = repositories.map { Worktrees.dirOf(it, name) }
            return worktrees.any { w -> dirs.any { w.path.trimEnd('/').startsWith("$it/") } }
        }

        /** The worktree [name] of one of [repositories] as `git worktree list` reports it, or null when git does not list it. */
        fun entryOf(worktrees: List<GitWorktree>, repositories: Set<String>, name: String): GitWorktree? =
            worktrees.firstOrNull { w -> repositories.any { w.path.trimEnd('/') == Worktrees.dirOf(it, name) } }

        /** Whether [path] is the folder [dir] or lies below it. */
        private fun isAtOrUnder(path: String, dir: String): Boolean {
            val trimmed = path.trimEnd('/')
            return trimmed == dir || trimmed.startsWith("$dir/")
        }

        /**
         * Whether one of [files], each a path and its canonical path (links resolved, or null), lies in one of the
         * worktree folders [dirs]: a file opened through a link to the repository has a path outside the worktree.
         */
        fun anyInWorktree(files: List<Pair<String, String?>>, dirs: Set<String>): Boolean =
            files.any { (path, canonical) -> listOfNotNull(path, canonical).any { p -> dirs.any { isAtOrUnder(p, it) } } }

        /** Whether an editor holds a change, not saved yet, to a file in one of [dirs]; reads only, in a read action. */
        private suspend fun unsavedEditsIn(dirs: Set<String>): Boolean = readAction {
            val documents = FileDocumentManager.getInstance()
            val files = documents.unsavedDocuments.mapNotNull { documents.getFile(it) }.map { it.path to it.canonicalPath }
            anyInWorktree(files, dirs)
        }

        /** The flow as the IDE runs it: git in a process, the question as a dialog, the outcome as a balloon. */
        suspend fun run(
            project: Project,
            thread: SkeinThread,
            snapshots: StateFlow<SessionSnapshot>,
            threads: () -> List<SkeinThread>,
            inFlight: MutableSet<String>,
            active: () -> Boolean,
        ) {
            WorktreeCloseFlow(
                git = WorktreeGit(ProcessGitRunner()),
                removals = WorktreeGit(ProcessGitRunner(REMOVAL_TIMEOUT_MILLIS)),
                snapshots = snapshots,
                threads = threads,
                inFlight = inFlight,
                ask = { message, title ->
                    withContext(Dispatchers.EDT) {
                        active() && Messages.showDialog(project, message, title, arrayOf(KEEP, REMOVE), 0, Messages.getQuestionIcon()) == 1
                    }
                },
                report = { text, warning -> balloon(project, text, warning) },
                unsavedEdits = ::unsavedEditsIn,
                insideClaudeHome = { ClaudeHome.default().holdsWorktreesOf(it) },
                active = active,
            ).run(thread)
        }

        /**
         * The Worktrees section's Remove… as the IDE runs it ([removeKept]): the questions as dialogs, each with Cancel as
         * its default, the outcome as a balloon, and the threads of every open window counting.
         */
        suspend fun removeKept(
            project: Project,
            repository: String,
            name: String,
            snapshots: StateFlow<SessionSnapshot>,
            inFlight: MutableSet<String>,
            active: () -> Boolean,
        ) {
            WorktreeCloseFlow(
                git = WorktreeGit(ProcessGitRunner()),
                removals = WorktreeGit(ProcessGitRunner(REMOVAL_TIMEOUT_MILLIS)),
                snapshots = snapshots,
                threads = ::threadsOfOpenWindows,
                inFlight = inFlight,
                ask = { message, title -> choose(project, message, title, REMOVE, active) },
                confirmForce = { message, title -> choose(project, message, title, FORCE_REMOVE, active) },
                confirmDelete = { message, title -> choose(project, message, title, DELETE, active) },
                report = { text, warning -> balloon(project, text, warning) },
                unsavedEdits = ::unsavedEditsIn,
                insideClaudeHome = { ClaudeHome.default().holdsWorktreesOf(it) },
                active = active,
            ).removeKept(repository, name)
        }

        /** The threads of every open project window. */
        internal fun threadsOfOpenWindows(): List<SkeinThread> =
            ProjectManager.getInstance().openProjects.filterNot { it.isDisposed }.flatMap { ThreadService.getInstance(it).threads.value }

        /** [yes] or Cancel, on the EDT, with Cancel the default, since each of these questions may lose work; true for [yes]. */
        private suspend fun choose(project: Project, message: String, title: String, yes: String, active: () -> Boolean): Boolean =
            withContext(Dispatchers.EDT) {
                active() && Messages.showDialog(project, message, title, arrayOf(yes, CANCEL), 1, Messages.getWarningIcon()) == 0
            }

        private fun balloon(project: Project, text: String, warning: Boolean) {
            if (project.isDisposed) return
            // the text is HTML to the platform, and git's reason may hold a `<`
            NotificationGroupManager.getInstance().getNotificationGroup(ThreadNotifier.GROUP_ID)
                .createNotification(StringUtil.escapeXmlEntities(text), if (warning) NotificationType.WARNING else NotificationType.INFORMATION)
                .notify(project)
        }
    }
}
