package io.github.v0rest.skein.git

import io.github.v0rest.skein.claude.BaseRef
import java.nio.file.Path

/**
 * How the checked-out branch stands against its remote-tracking branch [upstream] (`origin/main`) - [ahead] commits only
 * the branch has and [behind] commits only the remote-tracking branch has, as of the user's last fetch. In a [shallow]
 * repository the counts cannot be trusted, since the history that would tell is missing.
 */
data class UpstreamCounts(val upstream: String, val ahead: Int, val behind: Int, val shallow: Boolean = false)

/** The pure part of the warning before a new worktree: whether the base it starts from is stale, and the words. */
object BaseWarning {
    /** `<ahead>\t<behind>` as `rev-list --left-right --count HEAD...<upstream>` prints it, or null for anything else. */
    fun parseCounts(stdout: String): Pair<Int, Int>? {
        val parts = stdout.trim().split(Regex("\\s+"))
        if (parts.size != 2) return null
        val ahead = parts[0].toIntOrNull() ?: return null
        val behind = parts[1].toIntOrNull() ?: return null
        return if (ahead >= 0 && behind >= 0) Pair(ahead, behind) else null
    }

    /**
     * Whether the worktree starts from the local branch, the only base that can be stale. `head` does. The default
     * `fresh` starts from the remote's own default branch, which Claude Code fetches itself when it is old, so there is
     * nothing to say, except when the repository has no `origin` at all, where Claude Code falls back to the local HEAD.
     * [hasOrigin] is asked unless the mode is `head`, which needs no remote check.
     */
    fun startsFromLocalBranch(mode: BaseRef, hasOrigin: () -> Boolean): Boolean = mode == BaseRef.HEAD || !hasOrigin()

    /**
     * The whole decision for a new worktree thread in [repository]: the mode comes from the settings of the main
     * checkout ([mainRoot] of the repository, where Claude Code reads the project files), and the branch and the counts
     * are asked only when the worktree starts from the local branch. Every `fresh` start still runs [mainRoot] and
     * [hasOrigin], and `head` skips [hasOrigin].
     */
    fun compute(
        repository: Path,
        mainRoot: (Path) -> Path,
        mode: (Path) -> BaseRef,
        hasOrigin: () -> Boolean,
        branch: () -> String?,
        counts: () -> UpstreamCounts?,
    ): String? {
        val settingsMode = mode(mainRoot(repository))
        return if (startsFromLocalBranch(settingsMode, hasOrigin)) decide(branch(), counts()) else null
    }

    /**
     * The warning for a worktree that starts from [branch], or null when there is nothing to say. No branch, no
     * upstream or no counts (a git error) say nothing, and nothing here ever blocks. A shallow repository gets the same
     * warning without numbers.
     */
    fun decide(branch: String?, counts: UpstreamCounts?): String? {
        if (branch == null || counts == null || counts.behind == 0) return null
        val asOf = "as of your last fetch"
        val tail = "the worktree starts from $branch as it is now"
        return when {
            counts.shallow -> "$branch differs from ${counts.upstream} $asOf, the shallow history cannot tell by how much, and $tail"
            counts.ahead == 0 -> "$branch is ${commits(counts.behind)} behind ${counts.upstream} $asOf, and $tail"
            else -> "$branch has diverged from ${counts.upstream}, ${commits(counts.ahead)} ahead and ${counts.behind} behind $asOf, and $tail"
        }
    }

    private fun commits(n: Int) = if (n == 1) "1 commit" else "$n commits"
}
