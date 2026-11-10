package io.github.v0rest.skein.ui

import io.github.v0rest.skein.git.GitResult
import io.github.v0rest.skein.git.GitWorktree
import io.github.v0rest.skein.threads.SkeinThread
import io.github.v0rest.skein.threads.ThreadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorktreeStartTest {
    private fun thread(id: String, folder: String, worktree: String?) =
        SkeinThread(id, folder, null, id, ThreadState.Starting, 0, 0, null, worktree = worktree)

    @Test
    fun existingNamesAreTheRepositorysClaudeWorktrees() {
        val worktrees = listOf(
            GitWorktree("/w/acme", "main"),
            GitWorktree("/w/acme/.claude/worktrees/feat-x", "worktree-feat-x"),
            GitWorktree("/w/other-wt", "other"),
            GitWorktree("/w/beta/.claude/worktrees/feat-y", "worktree-feat-y"),
        )
        assertEquals(setOf("feat-x"), WorktreeStart.existingNames(setOf("/w/acme"), worktrees))
    }

    /** git lists real paths, while a project opened through a link names its repository by the link. */
    @Test
    fun aWorktreeListedUnderTheRealPathCounts() {
        val worktrees = listOf(
            GitWorktree("/real/acme", "main"),
            GitWorktree("/real/acme/.claude/worktrees/task-1", "worktree-task-1"),
        )
        val existing = WorktreeStart.existingNames(setOf("/w/acme", "/real/acme"), worktrees)
        assertEquals(setOf("task-1"), existing)
        assertEquals("task-2", WorktreeStart.defaultName(existing, emptySet()))
    }

    /** A thread whose `claude --worktree` is still queued or starting has no worktree yet, but its name is taken. */
    @Test
    fun aPendingThreadsNameIsNotOfferedAgain() {
        val resolve: (String) -> String? = { if (it == "/w/link") "/real/acme" else null }
        val threads = listOf(
            thread("t1", "/w/acme", "task-1"),
            thread("t2", "/w/acme/", "task-3"),
            // the same repository through another path
            thread("t3", "/w/link", "task-2"),
            // another repository: its worktrees have names of their own
            thread("t4", "/w/beta", "task-4"),
            thread("t5", "/w/acme", null),
        )
        val inUse = WorktreeStart.threadNames(setOf("/w/acme", "/real/acme"), threads, resolve)
        assertEquals(setOf("task-1", "task-2", "task-3"), inUse)
        assertEquals("task-4", WorktreeStart.defaultName(existing = emptySet(), inUse = inUse))
        assertEquals("task-5", WorktreeStart.defaultName(existing = setOf("task-4"), inUse = inUse))
    }

    /** The field starts with the suggested name; cleared, it still stands for that name. */
    @Test
    fun aBlankNameStandsForTheSuggestedOne() {
        assertEquals("task-2", WorktreeStart.chosenName("  ", "task-2"))
        assertEquals("feat-x", WorktreeStart.chosenName(" feat-x ", "task-2"))
        assertTrue(WorktreeStart.isAcceptable(""))
        assertTrue(WorktreeStart.isAcceptable(" feat-x "))
        assertFalse(WorktreeStart.isAcceptable("-x"))
        assertFalse(WorktreeStart.isAcceptable("a b"))
    }

    /** A `worktree-<name>` branch left behind (git refused to delete it) would clash with a new worktree of that name. */
    @Test
    fun aLeftoverBranchNameIsNotOffered() {
        val branches = setOf("main", "worktree-task-1", "worktree-task-3", "task-2", "worktree-")
        assertEquals("task-2", WorktreeStart.defaultName(existing = emptySet(), inUse = emptySet(), branches = branches))
        assertEquals("task-4", WorktreeStart.defaultName(existing = setOf("task-2"), inUse = emptySet(), branches = branches))
        assertEquals("task-1", WorktreeStart.defaultName(existing = emptySet(), inUse = emptySet()))
    }

    /** `rev-parse -q --verify` answers 1 for a HEAD with no commit; any other failure is said in git's own words. */
    @Test
    fun anotherRevParseFailureShowsGitsMessage() {
        val dubious = "fatal: detected dubious ownership in repository at '/w/acme'\nTo add an exception for this directory, call:\n"
        assertEquals("detected dubious ownership in repository at '/w/acme'", WorktreeStart.headRefusal(GitResult(128, "", dubious, false)))
        assertEquals(WorktreeStart.NO_COMMIT, WorktreeStart.headRefusal(GitResult(1, "", "", false)))
        // the exit code decides, not git's words (which a translated git would not use)
        assertEquals("Needed a single revision", WorktreeStart.headRefusal(GitResult(128, "", "fatal: Needed a single revision\n", false)))
        assertEquals("git gave no reason", WorktreeStart.headRefusal(GitResult(128, "", "", false)))
    }

    /** A git that could not start or did not finish says nothing about the repository's commits. */
    @Test
    fun aGitThatCouldNotRunIsNotReadAsNoCommit() {
        assertNull(WorktreeStart.headRefusal(GitResult(0, "1111111111111111111111111111111111111111\n", "", false)))
        assertEquals("The repository has no commit yet; Claude Code needs one", WorktreeStart.headRefusal(GitResult(1, "", "", false)))
        assertEquals("git could not be run: git did not finish in time", WorktreeStart.headRefusal(GitResult(-1, "", "", true)))
        assertEquals(
            "git could not be run: Cannot run program \"git\": error=2, No such file or directory",
            WorktreeStart.headRefusal(GitResult(-1, "", "Cannot run program \"git\": error=2, No such file or directory", false)),
        )
    }
}
