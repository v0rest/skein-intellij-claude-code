package io.github.v0rest.skein.ui

import io.github.v0rest.skein.claude.Worktrees

/** The grey note a worktree thread's row carries after its name. */
object WorktreeLabel {
    /**
     * `worktree`, followed by the worktree's name unless the row already shows that name; null outside a worktree. The
     * worktree is the one the session's [sessionCwd] lies in, else the thread's own [worktree] (no session yet, or one
     * that reports the repository).
     */
    fun of(shownName: String, worktree: String?, sessionCwd: String? = null): String? {
        val shown = sessionCwd?.let(Worktrees::split)?.name ?: worktree ?: return null
        return if (shownName == shown) "worktree" else "worktree $shown"
    }
}
