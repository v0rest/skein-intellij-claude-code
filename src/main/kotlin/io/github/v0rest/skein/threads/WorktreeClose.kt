package io.github.v0rest.skein.threads

import io.github.v0rest.skein.git.WorktreeState

sealed interface CloseOffer {
    /** The worktree is already gone: close without asking. */
    data object Nothing : CloseOffer

    /** Something stops a removal right now, so only keeping it is possible; [reason] says what. */
    data class KeepOnly(val reason: String) : CloseOffer

    /** Offer to keep or remove, with what git says is left in the worktree. */
    data class KeepOrRemove(val changedFiles: Int, val unpushedCommits: Int) : CloseOffer
}

object WorktreeClose {
    const val UNSAVED_EDITS = "unsaved changes in the editor"
    const val SESSION_INSIDE = "another session is running in this worktree"
    const val OTHER_THREAD = "another Skein thread uses this worktree"
    const val GIT_FAILED = "git could not read the worktree"
    const val NESTED_WORKTREE = "another worktree lies inside this one"
    const val DETACHED_COMMITS = "commits that no branch holds"
    const val CLAUDE_HOME = "Skein leaves worktrees alone in a repository that contains the Claude home"

    /** How many of the folders [innerRepositories] names before it says how many more there are. */
    const val INNER_SHOWN = 5

    /**
     * Repositories of their own lie inside the [worktree], ignored or not: removing it would delete them with their
     * commits. Names up to [INNER_SHOWN] of [paths], and what the owner can do instead.
     */
    fun innerRepositories(paths: List<String>, worktree: String): String {
        val more = paths.size - INNER_SHOWN
        val named = paths.take(INNER_SHOWN).joinToString(", ") + if (more > 0) " and $more more" else ""
        return "repositories of their own lie inside it, and removing it would delete them with their commits: $named. " +
            "Move or delete those folders first, or remove the worktree with git by hand: ${byHand(worktree)}"
    }

    /** git could not say whether a repository of its own lies inside the [worktree], so nothing is removed; what to do instead. */
    fun innerUnknown(reason: String, worktree: String): String =
        "git could not say whether a repository of its own lies inside it: $reason. " +
            "Check it for one yourself, then remove the worktree with git by hand: ${byHand(worktree)}"

    /**
     * Claude Code's lock on the worktree names process [pid], which still runs and cannot be told apart from the claude
     * that locked it, so the lock stays.
     */
    fun claudeStillRuns(pid: Long): String = "Claude Code's lock on it names pid $pid, which still runs and may be the claude that locked it"

    private fun byHand(worktree: String): String = "git worktree remove $worktree"

    /**
     * Remove is offered only for a worktree that is still there, that no session and no other Skein thread uses, with no
     * edit waiting in an editor (git cannot see one, so a removal would lose it), with no other worktree inside it (git
     * status here cannot see one, and the removal would take it along), whose state git could read, with no repository
     * of its own inside it ([inner] says which, or that git could not tell; ignored ones count, and the removal would
     * delete them), on a detached HEAD, with no [detachedCommits] that no branch, tag or remote holds (git removes
     * such a worktree without a word), and not locked by a claude that still runs there ([claudeRuns], its pid; a lock
     * Claude Code left behind with its claude gone does not count, the flow lifts that one after the yes).
     */
    fun offer(
        exists: Boolean,
        liveInside: Boolean,
        state: WorktreeState?,
        otherThread: Boolean = false,
        unsavedEdits: Boolean = false,
        nestedWorktree: Boolean = false,
        detachedCommits: Int = 0,
        inner: String? = null,
        claudeRuns: Long? = null,
    ): CloseOffer = when {
        !exists -> CloseOffer.Nothing
        unsavedEdits -> CloseOffer.KeepOnly(UNSAVED_EDITS)
        liveInside -> CloseOffer.KeepOnly(SESSION_INSIDE)
        otherThread -> CloseOffer.KeepOnly(OTHER_THREAD)
        claudeRuns != null -> CloseOffer.KeepOnly(claudeStillRuns(claudeRuns))
        nestedWorktree -> CloseOffer.KeepOnly(NESTED_WORKTREE)
        state == null -> CloseOffer.KeepOnly(GIT_FAILED)
        inner != null -> CloseOffer.KeepOnly(inner)
        detachedCommits > 0 -> CloseOffer.KeepOnly(DETACHED_COMMITS)
        else -> CloseOffer.KeepOrRemove(state.changedFiles, state.unpushedCommits)
    }
}
