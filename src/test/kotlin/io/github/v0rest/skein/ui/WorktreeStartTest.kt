package io.github.v0rest.skein.ui

import io.github.v0rest.skein.claude.FolderTrust
import io.github.v0rest.skein.git.GitResult
import io.github.v0rest.skein.git.GitWorktree
import io.github.v0rest.skein.paths.PathStyle.UNIX
import io.github.v0rest.skein.paths.PathStyle.WINDOWS
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
        assertEquals(setOf("feat-x"), WorktreeStart.existingNames(setOf("/w/acme"), worktrees, UNIX))
    }

    /**
     * On Windows the project names its repository as the IDE writes it (`C:/w/acme`), while git's paths come in as
     * the system writes them ([io.github.v0rest.skein.git.WorktreeGit.parseList]): one folder all the same.
     */
    @Test
    fun existingNamesMatchTheRepositoryInAnyShapeOnWindows() {
        val worktrees = listOf(
            GitWorktree("C:\\w\\acme", "main"),
            GitWorktree("C:\\w\\acme\\.claude\\worktrees\\feat-x", "worktree-feat-x"),
            GitWorktree("C:\\w\\acme2\\.claude\\worktrees\\feat-y", "worktree-feat-y"),
            GitWorktree("D:\\w\\acme\\.claude\\worktrees\\feat-z", "worktree-feat-z"),
        )
        assertEquals(setOf("feat-x"), WorktreeStart.existingNames(setOf("C:/w/acme"), worktrees, WINDOWS))
        assertEquals(setOf("feat-x"), WorktreeStart.existingNames(setOf("c:\\W\\Acme\\"), worktrees, WINDOWS))
        // the same text on Unix is one name with backslashes in it
        assertEquals(emptySet<String>(), WorktreeStart.existingNames(setOf("C:/w/acme"), worktrees, UNIX))
    }

    /** git lists real paths, while a project opened through a link names its repository by the link. */
    @Test
    fun aWorktreeListedUnderTheRealPathCounts() {
        val worktrees = listOf(
            GitWorktree("/real/acme", "main"),
            GitWorktree("/real/acme/.claude/worktrees/task-1", "worktree-task-1"),
        )
        val existing = WorktreeStart.existingNames(setOf("/w/acme", "/real/acme"), worktrees, UNIX)
        assertEquals(setOf("task-1"), existing)
        assertEquals("task-2", WorktreeStart.defaultName(existing, emptySet(), style = UNIX))
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
        val inUse = WorktreeStart.threadNames(setOf("/w/acme", "/real/acme"), threads, resolve, UNIX)
        assertEquals(setOf("task-1", "task-2", "task-3"), inUse)
        assertEquals("task-4", WorktreeStart.defaultName(existing = emptySet(), inUse = inUse, style = UNIX))
        assertEquals("task-5", WorktreeStart.defaultName(existing = setOf("task-4"), inUse = inUse, style = UNIX))
    }

    @Test
    fun aThreadNamesItsRepositoryInAnyShapeOnWindows() {
        val threads = listOf(
            thread("t1", "C:/w/acme", "task-1"),
            thread("t2", "c:\\w\\ACME\\", "task-2"),
            thread("t3", "C:/w/acme2", "task-3"),
        )
        assertEquals(setOf("task-1", "task-2"), WorktreeStart.threadNames(setOf("C:\\w\\acme"), threads, style = WINDOWS))
        // on Unix the case counts and a backslash is part of a name
        assertEquals(setOf("task-1"), WorktreeStart.threadNames(setOf("C:/w/acme"), threads, style = UNIX))
    }

    /** NTFS keeps `Task-1` and `task-1` in one folder, so on Windows a worktree `Task-1` takes the name. */
    @Test
    fun aNameTakenInAnotherCaseIsNotOfferedOnWindows() {
        assertEquals("task-2", WorktreeStart.defaultName(existing = setOf("Task-1"), inUse = emptySet(), style = WINDOWS))
        assertEquals("task-2", WorktreeStart.defaultName(existing = emptySet(), inUse = emptySet(), branches = setOf("worktree-TASK-1"), style = WINDOWS))
        assertEquals("task-1", WorktreeStart.defaultName(existing = setOf("Task-1"), inUse = emptySet(), style = UNIX))
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

    /** Win32 would open `task-1.` as `task-1`, so the dialog refuses such a name on Windows and says why. */
    @Test
    fun aNameEndingInADotIsRefusedOnWindows() {
        assertFalse(WorktreeStart.isAcceptable("task-1.", WINDOWS))
        assertFalse(WorktreeStart.isAcceptable(" a.. ", WINDOWS))
        assertTrue(WorktreeStart.isAcceptable("task-1.", UNIX))
        assertTrue(WorktreeStart.isAcceptable("a.b", WINDOWS))
        assertEquals("Letters, digits, '.', '_' and '-', up to 64, not starting with '-' or '.' and not ending with '.'", WorktreeStart.nameRule(WINDOWS))
        assertEquals("Letters, digits, '.', '_' and '-', up to 64, not starting with '-' or '.'", WorktreeStart.nameRule(UNIX))
    }

    /** A `worktree-<name>` branch left behind (git refused to delete it) would clash with a new worktree of that name. */
    @Test
    fun aLeftoverBranchNameIsNotOffered() {
        val branches = setOf("main", "worktree-task-1", "worktree-task-3", "task-2", "worktree-")
        for (style in listOf(UNIX, WINDOWS)) {
            assertEquals("task-2", WorktreeStart.defaultName(existing = emptySet(), inUse = emptySet(), branches = branches, style = style))
            assertEquals("task-4", WorktreeStart.defaultName(existing = setOf("task-2"), inUse = emptySet(), branches = branches, style = style))
            assertEquals("task-1", WorktreeStart.defaultName(existing = emptySet(), inUse = emptySet(), style = style))
        }
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

    @Test
    fun theBaseWarningShowsForANewNameAndNotForAReopenedWorktree() {
        val existing = setOf("task-1")
        assertEquals("behind", WorktreeStart.baseWarningFor("task-2", "task-2", existing, "behind"))
        assertEquals("behind", WorktreeStart.baseWarningFor("", "task-2", existing, "behind"))
        assertNull(WorktreeStart.baseWarningFor("task-1", "task-2", existing, "behind"))
        assertNull(WorktreeStart.baseWarningFor("task-2", "task-2", existing, null))
        // NTFS opens `Task-1` as the existing `task-1`, a reopen; on Unix it is a new worktree
        assertNull(WorktreeStart.baseWarningFor("Task-1", "task-2", existing, "behind", WINDOWS))
        assertEquals("behind", WorktreeStart.baseWarningFor("Task-1", "task-2", existing, "behind", UNIX))
    }

    /** claude --worktree turns away a folder nobody trusted yet: the dialog says so, and only when the file says so. */
    @Test
    fun theTrustWarningShowsOnlyForAFolderNobodyTrusted() {
        assertEquals(
            "claude --worktree refuses a folder Claude Code has not been told to trust yet. Start a plain thread in /w/acme once and answer its question, then come back.",
            WorktreeStart.trustWarning("/w/acme", FolderTrust.NOT_TRUSTED, UNIX),
        )
        assertNull(WorktreeStart.trustWarning("/w/acme", FolderTrust.TRUSTED, UNIX))
        assertNull(WorktreeStart.trustWarning("/w/acme", FolderTrust.UNKNOWN, UNIX))
    }

    /** The repository is named as the other messages name it, whichever shape the IDE or git gave. */
    @Test
    fun theTrustWarningNamesTheRepositoryInItsCanonicalForm() {
        val text = WorktreeStart.trustWarning("C:/Users/skein/demo-shop/", FolderTrust.NOT_TRUSTED, WINDOWS)
        assertTrue(text, text!!.contains("Start a plain thread in C:\\Users\\skein\\demo-shop once"))
    }
}
