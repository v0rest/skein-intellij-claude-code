package io.github.v0rest.skein.ui

import io.github.v0rest.skein.claude.Worktrees
import io.github.v0rest.skein.git.ExcludeResult
import io.github.v0rest.skein.git.GitResult
import io.github.v0rest.skein.git.GitWorktree
import io.github.v0rest.skein.threads.SkeinThread
import java.nio.file.Path

/** The pure part of starting a worktree thread: which names are taken, which one is suggested, and what stops a start. */
object WorktreeStart {
    const val NO_COMMIT = "The repository has no commit yet; Claude Code needs one"

    /**
     * The names of the worktrees Claude Code keeps under the repository, which [repositories] names as the project does
     * and as git reports it (links resolved); worktrees elsewhere do not count.
     */
    fun existingNames(repositories: Set<String>, worktrees: List<GitWorktree>): Set<String> {
        val names = repositories.mapTo(HashSet()) { it.trimEnd('/') }
        return worktrees.mapNotNull { Worktrees.split(it.path) }
            .filter { it.repository in names }
            .mapTo(LinkedHashSet()) { it.name }
    }

    /**
     * The worktree names this project's [threads] in the repository use, whichever path to it a thread names
     * ([resolve] gives a folder's real path, and may block). A thread whose `claude --worktree` is still queued or
     * starting has no worktree git could list yet, but its name is taken.
     */
    fun threadNames(repositories: Set<String>, threads: List<SkeinThread>, resolve: (String) -> String? = { null }): Set<String> {
        val names = repositories.mapTo(HashSet()) { it.trimEnd('/') }
        return threads.filter { t -> t.worktree != null && (t.folder.trimEnd('/') in names || resolve(t.folder) in names) }
            .mapNotNullTo(LinkedHashSet()) { it.worktree }
    }

    /**
     * The name the dialog suggests: the first `task-N` that neither git nor a thread uses yet, and whose branch
     * `worktree-<name>` is not among the repository's [branches] (one left behind when git refused to delete it).
     */
    fun defaultName(existing: Set<String>, inUse: Set<String>, branches: Set<String> = emptySet()): String =
        Worktrees.nextName(existing + inUse + branches.mapNotNull(Worktrees::nameOfBranch))

    /** The name typed, or the [suggested] one when the field was left empty. */
    fun chosenName(text: String, suggested: String): String = text.trim().ifEmpty { suggested }

    /** An empty field (the suggested name) or a name Claude Code accepts. */
    fun isAcceptable(text: String): Boolean = text.isBlank() || Worktrees.isValidName(text.trim())

    /**
     * Why no worktree can start, from git's `rev-parse -q --verify HEAD` in the repository, or null when one can. Its exit
     * code decides: 1 means no commit yet, any other failure (a repository git does not trust, say) is said in git's own
     * words, which may be in any language.
     */
    fun headRefusal(head: GitResult): String? = when {
        head.ok -> null
        !head.ran -> "git could not be run: " + head.reason()
        head.exitCode == 1 -> NO_COMMIT
        else -> head.reason()
    }

    /** Why no worktree thread starts in [repository]: Claude Code would put its worktrees inside its own home. */
    fun insideClaudeHome(repository: String): String =
        "Skein does not start worktree threads in a repository that contains the Claude home: $repository"

    /** Why no worktree thread starts: Skein could not keep its worktrees out of git ([io.github.v0rest.skein.git.WorktreeExcludes]). */
    fun excludeFailed(reason: String): String = "Skein could not keep the worktree out of git: $reason"

    /**
     * What Skein wrote to keep the worktrees of [repository] out of git (the guard file, the exclude line or both),
     * paths inside the repository named from its top; null when this call wrote nothing.
     */
    fun excludeAdded(repository: Path, result: ExcludeResult.Ok): String? =
        written(repository, result, "to")?.let { "Skein added $it so git ignores the worktrees in $repository." }

    /**
     * What the check made while worktree threads are open put back in [repository] (git removes or replaces the guard
     * as an ignored file now and then), named as [excludeAdded] names it; null when it wrote nothing.
     */
    fun excludeRestored(repository: Path, result: ExcludeResult.Ok): String? =
        written(repository, result, "in")?.let { "Skein put back $it so git keeps ignoring the worktrees in $repository." }

    private fun written(repository: Path, result: ExcludeResult.Ok, lineGoes: String): String? {
        fun named(path: Path): String = if (path.startsWith(repository)) repository.relativize(path).toString() else path.toString()
        val wrote = listOfNotNull(
            named(result.guardFile).takeIf { result.guardAdded },
            "the line ${Worktrees.EXCLUDE_LINE} $lineGoes ${named(result.excludeFile)}".takeIf { result.lineAdded },
        )
        return if (wrote.isEmpty()) null else wrote.joinToString(" and ")
    }

    /** Resume and restore start the thread anyway (its work is there already), so they warn instead of refusing. */
    fun excludeWarning(repository: String, reason: String): String =
        "Skein could not keep the worktrees of $repository out of git, so git add -A could pick the worktree up: $reason"

    /** The reason of [result] as a person reads it: with a word on the guard when the failed call kept one. */
    fun excludeReason(result: ExcludeResult.Failed): String =
        result.keptGuard?.let { "${result.reason}; Skein kept the guard $it it wrote, since worktrees live in that folder and it changes nothing git tracks" }
            ?: result.reason

    /** The start's refusal for [result], or null when the worktrees are out of git. */
    fun excludeRefusal(result: ExcludeResult): String? = (result as? ExcludeResult.Failed)?.let { excludeFailed(excludeReason(it)) }
}
