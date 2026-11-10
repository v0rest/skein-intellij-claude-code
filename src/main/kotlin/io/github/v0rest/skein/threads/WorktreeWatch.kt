package io.github.v0rest.skein.threads

import com.intellij.openapi.diagnostic.ControlFlowException
import io.github.v0rest.skein.git.ExcludeResult
import io.github.v0rest.skein.ui.WorktreeStart
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException

/**
 * The pure part of keeping the worktrees of open worktree threads out of git. git treats the guard
 * `.claude/worktrees/.gitignore` as an expendable ignored file (`git clean -X`, `git stash -a`, a switch to a branch
 * that tracks or drops it), so while a project has a worktree thread [ThreadService] runs
 * [io.github.v0rest.skein.git.WorktreeExcludes] again for each of their repositories every [PERIOD_MILLIS].
 */
object WorktreeWatch {
    const val PERIOD_MILLIS: Long = 60_000L

    /**
     * The repositories of [threads] to check, keyed by real path ([resolve] gives it and may block; null keeps the folder
     * as a thread names it), each with the folder the first thread names it by: one per repository, however many
     * worktree threads it has and whichever path to it they name. Threads in an ordinary folder do not count.
     */
    fun repositories(threads: List<SkeinThread>, resolve: (String) -> String?): Map<String, String> =
        distinct(threads.filter { it.worktree != null }.map { it.folder }, resolve)

    /** [folders] once per real path ([resolve] as in [repositories]; one that throws counts as null), each keyed by it, in their order. */
    fun distinct(folders: Collection<String>, resolve: (String) -> String?): Map<String, String> {
        val found = LinkedHashMap<String, String>()
        for (folder in folders) found.putIfAbsent(isolated { resolve(folder) } ?: folder, folder)
        return found
    }

    /** What a check needs from outside, all of it blocking: off the EDT. */
    interface Checks {
        /** [folder] with its links resolved, or null when it cannot be. */
        fun realPath(folder: String): String?

        /** Whether [folder] is still there and still the top of a repository. */
        fun isRepository(folder: Path): Boolean

        /** Whether Claude Code would put the worktrees of [repository] inside its own home: left alone, as everywhere else. */
        fun holdsClaudeHome(repository: Path): Boolean

        /** Keeps the worktrees of [repository] out of git ([io.github.v0rest.skein.git.WorktreeExcludes.ensureExcluded]). */
        fun ensure(repository: Path): ExcludeResult
    }

    /**
     * One tick of the minute check: each repository of [threads]' worktree threads checked once, by real path, and the
     * balloons to show; [warnings] holds the reason last shown for each repository and is kept up to date.
     */
    fun tick(threads: List<SkeinThread>, checks: Checks, warnings: MutableMap<String, String>): List<Balloon> =
        check(repositories(threads, checks::realPath), periodic = true, checks, warnings)

    /**
     * The check of each of [repositories] (real path to the folder a thread names it by, from [repositories] or
     * [distinct]) and the balloons to show ([outcome]). A folder that is gone or no longer a repository is skipped, and
     * so is one that holds the Claude home. Whatever one repository's check throws is that repository's failure, so the
     * others still get theirs; [warnings] changes only for an outcome that is returned.
     */
    fun check(repositories: Map<String, String>, periodic: Boolean, checks: Checks, warnings: MutableMap<String, String>): List<Balloon> =
        repositories.mapNotNull { (key, folder) ->
            val result = try {
                val repository = Path.of(folder)
                if (checks.holdsClaudeHome(repository) || !checks.isRepository(repository)) return@mapNotNull null
                checks.ensure(repository)
            } catch (e: Exception) {
                if (e is ControlFlowException || e is CancellationException) throw e
                ExcludeResult.Failed(e.message ?: e.javaClass.simpleName)
            }
            val outcome = outcome(folder, result, periodic, warnings[key])
            if (outcome.warned == null) warnings.remove(key) else warnings[key] = outcome.warned
            outcome.balloon
        }

    /** [block], or null when it throws; cancellation and the platform's control flow pass, and so does an [Error]. */
    private fun <T> isolated(block: () -> T): T? =
        try {
            block()
        } catch (e: Exception) {
            if (e is ControlFlowException || e is CancellationException) throw e
            null
        }

    sealed interface Balloon {
        val text: String

        data class Info(override val text: String) : Balloon

        data class Warning(override val text: String) : Balloon
    }

    /** What one check shows ([balloon], null for nothing), and the reason to remember for its repository ([warned], null once it holds). */
    data class Outcome(val balloon: Balloon?, val warned: String?)

    /**
     * What to show for [result], the check of the repository a thread names [folder]. A resume or a restore
     * ([periodic] false) says what it wrote and warns every time, since it was asked for; the minute check says what it
     * put back and warns only when the reason differs from [lastWarned], the one last shown for that repository, so a
     * failure that lasts is not shown again every minute. Only the reason counts, not the word on a kept guard, which
     * the next check no longer has. A check that holds forgets the reason.
     */
    fun outcome(folder: String, result: ExcludeResult, periodic: Boolean, lastWarned: String?): Outcome = when (result) {
        is ExcludeResult.Ok -> {
            val repository = Path.of(folder)
            val text = if (periodic) WorktreeStart.excludeRestored(repository, result) else WorktreeStart.excludeAdded(repository, result)
            Outcome(text?.let { Balloon.Info(it) }, null)
        }
        is ExcludeResult.Failed -> {
            val shown = !periodic || result.reason != lastWarned
            Outcome(if (shown) Balloon.Warning(WorktreeStart.excludeWarning(folder, WorktreeStart.excludeReason(result))) else null, result.reason)
        }
    }
}
