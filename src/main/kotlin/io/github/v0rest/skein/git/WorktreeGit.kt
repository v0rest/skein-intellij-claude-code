package io.github.v0rest.skein.git

import java.nio.file.Path

/**
 * A worktree as `git worktree list --porcelain` reports it; [branch] has no `refs/heads/` and is null when [detached]
 * or [bare]. [locked] and [prunable] say that git marked it so (a locked one stays until it is unlocked, a prunable one
 * has lost its folder or its link to the repository), and [lockReason] is the lock's reason as git wrote it (quoted by
 * git when it holds a byte git does not show as it is), null for a lock without one.
 */
data class GitWorktree(
    val path: String,
    val branch: String?,
    val detached: Boolean = false,
    val bare: Boolean = false,
    val locked: Boolean = false,
    val prunable: Boolean = false,
    val lockReason: String? = null,
)

/** The repositories of their own git found inside a worktree ([WorktreeGit.repositoriesInside]), or why it could not look. */
sealed interface InnerRepositories {
    data class Listed(val paths: List<String>) : InnerRepositories

    data class Failed(val reason: String) : InnerRepositories
}

/** What `git worktree list` gave: the worktrees, or why git could not list them (then nothing about them is known). */
sealed interface WorktreeListing {
    data class Listed(val worktrees: List<GitWorktree>) : WorktreeListing

    data class Failed(val reason: String) : WorktreeListing
}

data class WorktreeState(val changedFiles: Int, val unpushedCommits: Int)

/**
 * Reads and removes worktrees through git. Nothing here forces: git refuses to remove a worktree with changes and to
 * delete a branch that is not merged. It does remove a detached worktree whose commits nothing else holds, though, and
 * those commits are lost then; [detachedCommits] counts them, so the caller can keep such a worktree. The one force
 * ([forceRemove]) and the one unlock ([liftLock]) are for the remove flow alone.
 */
class WorktreeGit(private val git: GitRunner) {
    /**
     * `git rev-parse -q --verify HEAD`: exit 0 when the repository has a commit to start a worktree from, 1 (and
     * nothing said) when HEAD names no commit yet, anything else when git could not tell.
     */
    fun head(repository: Path): GitResult = git.run(repository, "rev-parse", "-q", "--verify", "HEAD")

    /** The worktrees git lists for [repository]; empty when git could not list them, which [listing] tells apart. */
    fun list(repository: Path): List<GitWorktree> = (listing(repository) as? WorktreeListing.Listed)?.worktrees.orEmpty()

    /** The worktrees git lists for [repository], or why it could not list them. */
    fun listing(repository: Path): WorktreeListing {
        val result = git.run(repository, "worktree", "list", "--porcelain")
        return if (result.ok) WorktreeListing.Listed(parseList(result.stdout)) else WorktreeListing.Failed(failure(result))
    }

    /** The branch [repository] has checked out (`git branch --show-current`); null when HEAD is detached or git cannot say. */
    fun currentBranch(repository: Path): String? {
        val result = git.run(repository, "branch", "--show-current")
        return if (result.ok) result.stdout.trim().ifEmpty { null } else null
    }

    /**
     * How the checked-out branch of [repository] stands against its remote-tracking branch, from what the last fetch
     * left in the repository (Skein never fetches). Null when HEAD is detached, the branch has no upstream, its upstream
     * is gone or is a local branch (`--track main`), there is no remote, or git cannot tell. Three git calls at most.
     */
    fun upstreamCounts(repository: Path): UpstreamCounts? {
        val name = git.run(repository, *NO_FSMONITOR, "rev-parse", "--symbolic-full-name", "@{upstream}")
        val full = (if (name.ok) name.stdout.trim() else "").takeIf { it.startsWith(REMOTES) } ?: return null
        val counts = git.run(repository, *NO_FSMONITOR, "rev-list", "--left-right", "--count", "HEAD...@{upstream}")
        if (!counts.ok) return null
        val (ahead, behind) = BaseWarning.parseCounts(counts.stdout) ?: return null
        val shallow = git.run(repository, *NO_FSMONITOR, "rev-parse", "--is-shallow-repository")
        return UpstreamCounts(full.removePrefix(REMOTES), ahead, behind, shallow.ok && shallow.stdout.trim() == "true")
    }

    /** Whether [repository] has a remote named `origin` (`git remote`, nothing over the network), and true when git cannot tell. */
    fun hasOrigin(repository: Path): Boolean {
        val result = git.run(repository, *NO_FSMONITOR, "remote")
        return !result.ok || result.stdout.lines().any { it.trim() == "origin" }
    }

    /**
     * The root of the main checkout of [repository], which is the folder itself unless it is a linked worktree. Claude
     * Code reads the project settings from there. It is the folder holding git's common directory when that is a `.git`, else
     * [repository] as given.
     */
    fun mainCheckoutRoot(repository: Path): Path {
        val result = git.run(repository, *NO_FSMONITOR, "rev-parse", "--path-format=absolute", "--git-common-dir")
        val common = if (result.ok) result.stdout.trim().takeIf { it.isNotEmpty() }?.let { Path.of(it) } else null
        return if (common != null && common.fileName?.toString() == ".git") common.parent ?: repository else repository
    }

    /** Null when git cannot tell. `core.fsmonitor` is off for it ([NO_FSMONITOR]). */
    fun state(worktree: Path): WorktreeState? {
        val status = git.run(worktree, *NO_FSMONITOR, "--no-optional-locks", "status", "--porcelain")
        if (!status.ok) return null
        val unpushed = git.run(worktree, "rev-list", "--count", "HEAD", "--not", "--remotes")
        if (!unpushed.ok) return null
        val count = unpushed.stdout.trim().toIntOrNull() ?: return null
        return WorktreeState(status.stdout.lines().count { it.isNotBlank() }, count)
    }

    /**
     * The commits of [worktree]'s HEAD that no branch, tag or remote-tracking branch holds (`--all` would count HEAD
     * itself as holding them); null when git cannot tell. On a detached HEAD, these are lost when the worktree goes.
     */
    fun detachedCommits(worktree: Path): Int? {
        val result = git.run(worktree, "rev-list", "--count", "HEAD", "--not", "--branches", "--tags", "--remotes")
        return if (result.ok) result.stdout.trim().toIntOrNull() else null
    }

    /** The repository's local branches, without `refs/heads/`; empty when git cannot list them. */
    fun branches(repository: Path): Set<String> {
        // `lstrip=2` drops `refs/heads/` alone, where `short` would say `heads/<name>` next to a tag of the same name
        val result = git.run(repository, "for-each-ref", "--format=%(refname:lstrip=2)", "refs/heads/")
        if (!result.ok) return emptySet()
        return result.stdout.lines().map { it.trim() }.filterTo(LinkedHashSet()) { it.isNotEmpty() }
    }

    /** `--` ends git's options here and in [deleteBranch], so a path or a branch name is never read as one. */
    fun remove(repository: Path, worktree: Path): GitResult = git.run(repository, *NO_FSMONITOR, "worktree", "remove", "--", worktree.toString())

    /**
     * `git worktree remove` with git's force: git then removes a worktree with changed or untracked files too, and that
     * work is lost. Only [io.github.v0rest.skein.ui.WorktreeCloseFlow] calls it, for the Worktrees section's Remove,
     * after git refused the plain [remove] and the owner agreed in a second question to lose what is not committed. A
     * locked worktree still stays: that would take git's force twice, which Skein never asks for.
     */
    fun forceRemove(repository: Path, worktree: Path): GitResult = git.run(repository, *NO_FSMONITOR, "worktree", "remove", "--force", "--", worktree.toString())

    /**
     * `git worktree unlock`: lifts the lock of [worktree], whatever its reason. Only
     * [io.github.v0rest.skein.ui.WorktreeCloseFlow] calls it, for a lock Claude Code left behind whose claude has
     * ended ([io.github.v0rest.skein.claude.WorktreeLock.Stale]), after the owner's yes and right before the plain
     * [remove]. Skein never locks a worktree, and never asks for git's force twice, which a locked worktree would take.
     */
    fun liftLock(repository: Path, worktree: Path): GitResult = git.run(repository, *NO_FSMONITOR, "--no-optional-locks", "worktree", "unlock", "--", worktree.toString())

    fun deleteBranch(repository: Path, branch: String): GitResult = git.run(repository, "branch", "-d", "--", branch)

    /**
     * The repositories of their own that lie in [worktree] without git tracking them, ignored ones included, as git
     * names their folders (`vendor/lib/`), or why git could not say. With every untracked and every ignored file listed
     * one by one (`--untracked-files=all`, `--ignored=traditional`), git still stops at a folder with a `.git` of its
     * own and lists that folder, so a path ending in `/` is such a repository. Removing the worktree, with or without
     * force, would delete them with their commits. A huge ignored tree can make this run into git's timeout; the caller
     * then removes nothing.
     */
    fun repositoriesInside(worktree: Path): InnerRepositories {
        val status = git.run(worktree, *NO_FSMONITOR, "--no-optional-locks", "status", "--porcelain", "-z", "--ignored=traditional", "--untracked-files=all")
        if (!status.ok) return InnerRepositories.Failed(failure(status))
        return InnerRepositories.Listed(StatusPorcelain.parse(status.stdout).paths.filter { it.endsWith("/") })
    }

    /** `git ls-files -- <path>` in [repository]: the files git tracks at or under [path], relative to the repository. */
    fun tracked(repository: Path, path: String): GitResult = git.run(repository, *NO_FSMONITOR, "ls-files", "--", path)

    /** Why [result] failed: git's reason, that it did not finish in time, or that it could not be started. */
    private fun failure(result: GitResult): String = if (result.ran || result.timedOut) result.reason() else "git could not be run: " + result.reason()

    companion object {
        private const val REMOTES = "refs/remotes/"

        /**
         * Turns off `core.fsmonitor` for one git call: a repository's configuration may name a program there, which a
         * git call that reads the work tree (`status`, `ls-files`, `ls-tree`, `worktree remove`) would start. Every such
         * call of Skein's carries it.
         */
        val NO_FSMONITOR: Array<String> = arrayOf("-c", "core.fsmonitor=false")

        /**
         * git's words when it refuses to remove a worktree because of changed or untracked files, the one refusal its
         * force is for. A worktree with a submodule, a locked one and a broken one get other words, and so does every
         * refusal from a git that speaks another language: those never match, so the force is never offered for them.
         */
        private const val CHANGES_REFUSAL = "contains modified or untracked files"

        /**
         * Whether git refused `worktree remove` for changed or untracked files only ([CHANGES_REFUSAL]): it ran, said no,
         * and said that, in English.
         */
        fun refusedForChanges(result: GitResult): Boolean = result.ran && !result.ok && result.stderr.contains(CHANGES_REFUSAL)

        /**
         * The blocks of `git worktree list --porcelain`, one per worktree: `worktree <path>`, then `HEAD`, `branch
         * refs/heads/<name>` or `detached`, or `bare`, and `locked` or `prunable` with an optional reason. Lines it does
         * not know are skipped, so a newer git's additions change nothing.
         */
        fun parseList(text: String): List<GitWorktree> {
            val result = mutableListOf<GitWorktree>()
            var current: GitWorktree? = null
            fun flush() {
                current?.let { result += it }
                current = null
            }
            for (line in text.lines()) {
                val entry = current
                when {
                    line.startsWith("worktree ") -> {
                        flush()
                        current = GitWorktree(line.removePrefix("worktree "), null)
                    }
                    line.isBlank() -> flush()
                    entry == null -> Unit
                    line.startsWith("branch ") -> current = entry.copy(branch = line.removePrefix("branch ").removePrefix("refs/heads/"))
                    line == "detached" -> current = entry.copy(detached = true)
                    line == "bare" -> current = entry.copy(bare = true)
                    line == "locked" -> current = entry.copy(locked = true)
                    line.startsWith("locked ") -> current = entry.copy(locked = true, lockReason = line.removePrefix("locked "))
                    line == "prunable" || line.startsWith("prunable ") -> current = entry.copy(prunable = true)
                }
            }
            flush()
            return result
        }
    }
}
