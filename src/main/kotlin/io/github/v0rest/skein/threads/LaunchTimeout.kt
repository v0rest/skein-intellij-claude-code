package io.github.v0rest.skein.threads

import io.github.v0rest.skein.claude.FolderTrust

/**
 * What a launch shows when its status file did not come within [ThreadCoordinator.LAUNCH_TIMEOUT_MILLIS]. In a folder
 * nobody told Claude Code to trust, claude first asks whether to trust it and writes no status file while it asks, and
 * `claude --worktree` there ends at once. Only a NOT_TRUSTED read changes anything - a trust Skein cannot read keeps
 * the status unknown and the could-not-read balloon, as before.
 */
object LaunchTimeout {
    enum class Outcome {
        /** claude runs under the tab's shell and the folder is not trusted, so it waits for the person's answer. */
        ASKS_TRUST,

        /** A worktree thread's claude is gone and the repository is not trusted - `claude --worktree` turned it away. */
        UNTRUSTED_WORKTREE,

        /** Status unknown, and the could-not-read balloon when no thread ever got a status file. */
        AS_BEFORE,
    }

    /**
     * [claudeAlive] - Skein saw the thread's claude under the tab's shell without a status file. [worktree] - the launch
     * typed `claude --worktree`, not a resume in a worktree thread. [trust] reads Claude Code's config and is asked only
     * when its answer can change the outcome, since the file may be large.
     */
    fun decide(claudeAlive: Boolean, worktree: Boolean, trust: () -> FolderTrust): Outcome = when {
        // a plain claude that is gone may never have started at all, and trust says nothing about that
        !claudeAlive && !worktree -> Outcome.AS_BEFORE
        trust() != FolderTrust.NOT_TRUSTED -> Outcome.AS_BEFORE
        claudeAlive -> Outcome.ASKS_TRUST
        else -> Outcome.UNTRUSTED_WORKTREE
    }
}
