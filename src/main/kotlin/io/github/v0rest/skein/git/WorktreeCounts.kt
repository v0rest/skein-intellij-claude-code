package io.github.v0rest.skein.git

import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** What `git status --porcelain -z --branch` lists: the changed paths, one per path, and whether HEAD is detached. */
data class StatusLines(val paths: List<String>, val detached: Boolean)

object StatusPorcelain {
    private const val DETACHED = "HEAD (no branch)"

    /**
     * [text] split at its NUL bytes. The `## ` entry names the branch (`HEAD (no branch)` when detached); every other
     * entry is `XY <path>`, staged, unstaged and untracked alike, and an untracked folder git did not open is one path.
     * A rename or a copy (`R` or `C` on either side) is followed by the path it came from, which is the same change.
     */
    fun parse(text: String): StatusLines {
        val fields = text.split('\u0000')
        val paths = ArrayList<String>()
        var detached = false
        var i = 0
        while (i < fields.size) {
            val field = fields[i++]
            when {
                field.startsWith("## ") -> detached = field.removePrefix("## ").substringBefore("...") == DETACHED
                field.length > 3 -> {
                    paths += field.substring(3)
                    // the path it came from: the same change, not another one
                    if (field[0] in "RC" || field[1] in "RC") i++
                }
            }
        }
        return StatusLines(paths, detached)
    }
}

/** A git operation stopped half way in a worktree, by the word its row shows and the sentence its tooltip says. */
enum class Operation(val word: String, val described: String) {
    REBASE("rebase", "A rebase is in progress"),
    MERGE("merge", "A merge is in progress"),
    CHERRY_PICK("pick", "A cherry-pick is in progress"),
    ;

    companion object {
        /** What `git rev-parse --git-path` is asked about, in this order, and the operation each path stands for while it exists. */
        val MARKERS: List<Pair<String, Operation>> = listOf(
            "rebase-merge" to REBASE,
            "rebase-apply" to REBASE,
            "MERGE_HEAD" to MERGE,
            "CHERRY_PICK_HEAD" to CHERRY_PICK,
        )
    }
}

/** The branch a worktree's commits are counted against: [name] as the row says it, [ref] as git is asked about it. */
data class Base(val name: String, val ref: String)

/** Whether a ref is there, as `git rev-parse -q --verify` answers: exit 0 yes, exit 1 no, anything else git failed. */
sealed interface RefState {
    data object Found : RefState

    data object Missing : RefState

    data class Failed(val reason: String) : RefState
}

/** The base found for a worktree, none at all, or why git could not tell. */
sealed interface BaseLookup {
    data class Found(val base: Base) : BaseLookup

    data object NoBase : BaseLookup

    data class Failed(val reason: String) : BaseLookup
}

/**
 * Which branch a worktree is compared with. First the branch the repository had checked out when Skein started the
 * thread ([recorded]); when there is none, or it is gone, the remote default branch (`origin/HEAD`, [originDefault]),
 * then `origin/main`, `origin/master`, and the local `main` and `master` ([FALLBACKS]).
 */
object BaseOrder {
    val FALLBACKS: List<Base> = listOf(remote("origin/main"), remote("origin/master"), local("main"), local("master"))

    /**
     * Whether [name] can be a branch name Skein hands git: it comes from the workspace file, which a person may edit, and
     * goes after `refs/heads/`. A subset of git's own rules: nothing a name git accepts is lost, and no option, range
     * or revision syntax gets through.
     */
    fun isBranchName(name: String): Boolean =
        name.isNotEmpty() && !name.startsWith("-") && !name.startsWith("/") && !name.endsWith("/") && !name.contains("..") &&
            !name.contains("@{") && name.none { it.isWhitespace() || it.isISOControl() || it in "~^:?*[\\" }

    /** The branch recorded for the thread, as a local branch; null without one, or for a name that is not a branch name. */
    fun recorded(name: String?): Base? = name?.takeIf(::isBranchName)?.let(::local)

    /** The branch `git symbolic-ref --short refs/remotes/origin/HEAD` printed, `origin/main` say, as a remote branch. */
    fun originDefault(short: String): Base? = short.trim().takeIf(::isBranchName)?.let(::remote)

    /** Every base in the order they are tried, each once. */
    fun candidates(recorded: String?, originHead: String?): List<Base> =
        (listOfNotNull(recorded(recorded), originHead?.let(::originDefault)) + FALLBACKS).distinctBy { it.ref }

    /**
     * The first of [candidates] that [probe] finds, in order. A probe git could not answer ends the search as a failure
     * instead of skipping to a base further down, which would count against the wrong branch without a word.
     */
    fun first(candidates: List<Base>, probe: (Base) -> RefState): BaseLookup {
        for (base in candidates) {
            when (val state = probe(base)) {
                RefState.Found -> return BaseLookup.Found(base)
                RefState.Missing -> Unit
                is RefState.Failed -> return BaseLookup.Failed(state.reason)
            }
        }
        return BaseLookup.NoBase
    }

    private fun local(name: String) = Base(name, "refs/heads/$name")

    private fun remote(name: String) = Base(name, "refs/remotes/$name")
}

/** The changed paths of a worktree: how many, and the first [SHOWN] of them for the tooltip. */
data class Changes(val count: Int, val first: List<String>) {
    companion object {
        const val SHOWN = 10
        val NONE = Changes(0, emptyList())

        fun of(paths: List<String>): Changes = Changes(paths.size, paths.take(SHOWN))
    }
}

/** What a worktree row shows of the worktree itself, as the last look found it. */
sealed interface WorktreeCounts {
    /** Nothing to show: the folder is gone, or git does not list it as a worktree (git would answer for another one). */
    data object NotShown : WorktreeCounts

    /** git failed or did not finish: a `?`, never zeros. */
    data class Failed(val reason: String) : WorktreeCounts

    /** A rebase, merge or cherry-pick stopped half way: its word instead of the numbers. */
    data class InProgress(val operation: Operation, val changes: Changes) : WorktreeCounts

    /**
     * The uncommitted [changes] and the commits on the worktree's branch that [base] does not have ([ahead]); [ahead]
     * and [base] are null when HEAD is [detached] or no base branch exists.
     */
    data class Counted(val changes: Changes, val detached: Boolean, val base: String?, val ahead: Int?) : WorktreeCounts
}

/**
 * Reads what a worktree's row shows: the uncommitted paths (`git status`), a rebase, merge or cherry-pick in progress
 * (`git rev-parse --git-path`), and the commits on its branch that its base does not have (`git rev-list --count`).
 * Every call goes through [git], one after the other. Ask only about a folder git lists as a worktree: in any other
 * folder git answers for the repository around it. Never throws for git's sake. Blocking: off the EDT.
 */
class WorktreeReader(private val git: GitRunner, private val exists: (Path) -> Boolean = { Files.exists(it) }) {
    /**
     * The counts of [worktree], compared with the branch [recorded] for its thread or else with the repository's
     * [fallback] base ([fallbackBase], which the caller keeps for every worktree of the repository).
     */
    fun read(worktree: Path, recorded: String?, fallback: () -> BaseLookup): WorktreeCounts {
        val status = git.run(worktree, *WorktreeGit.NO_FSMONITOR, "status", "--porcelain", "-z", "--branch", "--untracked-files=normal")
        if (!status.ok) return WorktreeCounts.Failed(failure(status))
        val lines = StatusPorcelain.parse(status.stdout)
        val changes = Changes.of(lines.paths)
        when (val operation = operation(worktree)) {
            is Probe.Failed -> return WorktreeCounts.Failed(operation.reason)
            is Probe.Running -> return WorktreeCounts.InProgress(operation.operation, changes)
            Probe.None -> Unit
        }
        // a detached HEAD is on no branch, so there is nothing to count against a base
        if (lines.detached) return WorktreeCounts.Counted(changes, detached = true, base = null, ahead = null)
        val base = when (val found = base(worktree, recorded, fallback)) {
            is BaseLookup.Failed -> return WorktreeCounts.Failed(found.reason)
            BaseLookup.NoBase -> return WorktreeCounts.Counted(changes, detached = false, base = null, ahead = null)
            is BaseLookup.Found -> found.base
        }
        val ahead = git.run(worktree, "rev-list", "--count", "${base.ref}..HEAD")
        if (!ahead.ok) return WorktreeCounts.Failed(failure(ahead))
        val count = ahead.stdout.trim().toIntOrNull() ?: return WorktreeCounts.Failed("git gave no count")
        return WorktreeCounts.Counted(changes, detached = false, base = base.name, ahead = count)
    }

    /**
     * The repository's base when a thread recorded none, or its recorded branch is gone: the remote default branch, then
     * the usual names ([BaseOrder]). The same for every worktree of [repository], so it is read once per look.
     */
    fun fallbackBase(repository: Path): BaseLookup {
        val head = git.run(repository, "symbolic-ref", "-q", "--short", "refs/remotes/origin/HEAD")
        val origin = when {
            head.ok -> head.stdout.trim()
            // not there, or not a symbolic ref: no remote default branch
            head.ran && head.exitCode == 1 -> null
            else -> return BaseLookup.Failed(failure(head))
        }
        return BaseOrder.first(BaseOrder.candidates(recorded = null, originHead = origin)) { probe(repository, it) }
    }

    private fun base(worktree: Path, recorded: String?, fallback: () -> BaseLookup): BaseLookup {
        val own = BaseOrder.recorded(recorded) ?: return fallback()
        return when (val state = probe(worktree, own)) {
            RefState.Found -> BaseLookup.Found(own)
            // the recorded branch was deleted or renamed since: the repository's base stands in
            RefState.Missing -> fallback()
            is RefState.Failed -> BaseLookup.Failed(state.reason)
        }
    }

    private fun probe(dir: Path, base: Base): RefState {
        val result = git.run(dir, "rev-parse", "-q", "--verify", "${base.ref}^{commit}")
        return when {
            result.ok -> RefState.Found
            result.ran && result.exitCode == 1 -> RefState.Missing
            else -> RefState.Failed(failure(result))
        }
    }

    private sealed interface Probe {
        data object None : Probe

        data class Running(val operation: Operation) : Probe

        data class Failed(val reason: String) : Probe
    }

    /**
     * The operation in progress in [worktree]: git names where each of its marker files would be (relative to the
     * worktree in the main one, absolute in a linked one), and the first that exists says which.
     */
    private fun operation(worktree: Path): Probe {
        val args = Operation.MARKERS.flatMap { (marker, _) -> listOf("--git-path", marker) }
        val result = git.run(worktree, "rev-parse", *args.toTypedArray())
        if (!result.ok) return Probe.Failed(failure(result))
        val paths = result.stdout.lines().filter { it.isNotEmpty() }
        if (paths.size != Operation.MARKERS.size) return Probe.Failed("git named ${paths.size} of ${Operation.MARKERS.size} paths")
        for ((index, path) in paths.withIndex()) {
            val marker = try {
                worktree.resolve(path)
            } catch (_: InvalidPathException) {
                return Probe.Failed("git named a path Skein cannot read: $path")
            }
            if (exists(marker)) return Probe.Running(Operation.MARKERS[index].second)
        }
        return Probe.None
    }

    /** Why [result] failed: git's reason, that it did not finish in time, or that it could not be started. */
    private fun failure(result: GitResult): String = if (result.ran || result.timedOut) result.reason() else "git could not be run: " + result.reason()
}
