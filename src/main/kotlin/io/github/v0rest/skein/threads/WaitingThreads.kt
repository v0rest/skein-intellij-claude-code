package io.github.v0rest.skein.threads

/**
 * The saved threads of a project nobody has trusted yet, as its workspace file names them, not started
 * ([SavedThreads.waitingThreads]). The worktree checks count them - a worktree, or a worktree name, that a waiting thread
 * needs once it comes back is not free - but by their text alone. The file came with the project, so a folder in it can
 * be any path, a share on another machine among them (`\\server\share` on Windows), where a mere look makes Windows
 * sign in to that server with the user's credentials. So a check compares these folders as written and never resolves
 * one or looks it up on the disk ([KeptWorktrees.held], `WorktreeStart.threadNames`, `WorktreeCloseFlow.sharedWithThread`),
 * and a type of their own keeps them out of the lists a resolver goes over: the list is read only as [textOnly].
 */
@JvmInline
value class WaitingThreads(val textOnly: List<SkeinThread>) {
    val size: Int get() = textOnly.size

    companion object {
        val NONE = WaitingThreads(emptyList())
    }
}
