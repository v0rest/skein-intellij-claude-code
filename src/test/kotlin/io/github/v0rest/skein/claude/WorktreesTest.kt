package io.github.v0rest.skein.claude

import io.github.v0rest.skein.paths.PathStyle
import io.github.v0rest.skein.paths.PathStyle.UNIX
import io.github.v0rest.skein.paths.PathStyle.WINDOWS
import io.github.v0rest.skein.terminal.ClaudeCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules of both systems run on every system: each test names its style. */
class WorktreesTest {
    @Test
    fun dirOfPutsTheWorktreeUnderTheRepository() {
        assertEquals("/w/acme/.claude/worktrees/feat-x", Worktrees.dirOf("/w/acme", "feat-x", UNIX))
        assertEquals("C:\\w\\acme\\.claude\\worktrees\\feat-x", Worktrees.dirOf("C:\\w\\acme", "feat-x", WINDOWS))
    }

    /** git and the IDE write `C:/w/acme` on Windows: the worktree comes out in the system's own shape all the same. */
    @Test
    fun dirOfTakesEitherSeparatorOnWindows() {
        assertEquals("C:\\w\\acme\\.claude\\worktrees\\feat-x", Worktrees.dirOf("C:/w/acme", "feat-x", WINDOWS))
        assertEquals("C:\\w\\acme\\.claude\\worktrees\\feat-x", Worktrees.dirOf("c:/w/acme/", "feat-x", WINDOWS))
        // on Unix a backslash is part of a name
        assertEquals("/w/a\\b/.claude/worktrees/feat-x", Worktrees.dirOf("/w/a\\b", "feat-x", UNIX))
    }

    @Test
    fun dirOfTrimsATrailingSlash() {
        assertEquals("/w/acme/.claude/worktrees/feat-x", Worktrees.dirOf("/w/acme/", "feat-x", UNIX))
        assertEquals("C:\\w\\acme\\.claude\\worktrees\\feat-x", Worktrees.dirOf("C:\\w\\acme\\", "feat-x", WINDOWS))
    }

    @Test
    fun parentOfIsTheWorktreesFolder() {
        assertEquals("/w/acme/.claude/worktrees", Worktrees.parentOf("/w/acme", UNIX))
        assertEquals("C:\\w\\acme\\.claude\\worktrees", Worktrees.parentOf("C:/w/acme", WINDOWS))
    }

    @Test
    fun theProjectSettingsLieInTheRepositorysClaudeFolder() {
        assertEquals(listOf("/w/acme/.claude/settings.local.json", "/w/acme/.claude/settings.json"), Worktrees.projectSettingsFiles("/w/acme/", UNIX))
        assertEquals(
            listOf("C:\\w\\acme\\.claude\\settings.local.json", "C:\\w\\acme\\.claude\\settings.json"),
            Worktrees.projectSettingsFiles("C:/w/acme", WINDOWS),
        )
    }

    /** Anchored at the top of the repository, and a folder: a file of that name elsewhere stays visible to git. Git's form on every system. */
    @Test
    fun theExcludeLineIsTheWorktreesFolder() = assertEquals("/.claude/worktrees/", Worktrees.EXCLUDE_LINE)

    @Test
    fun theGuardIsAGitignoreThatIgnoresEverything() {
        assertEquals(".gitignore", Worktrees.GUARD_NAME)
        assertEquals("*\n", Worktrees.GUARD_TEXT)
    }

    @Test
    fun aWorktreeBranchNamesItsWorktree() {
        for (style in PathStyle.entries) {
            assertEquals("task-1", Worktrees.nameOfBranch("worktree-task-1", style))
            assertEquals("feat.x", Worktrees.nameOfBranch("worktree-feat.x", style))
            assertNull(Worktrees.nameOfBranch("main", style))
            assertNull(Worktrees.nameOfBranch("worktree-", style))
            assertNull(Worktrees.nameOfBranch("feature/worktree-task-1", style))
            assertNull(Worktrees.nameOfBranch("worktree-a b", style))
        }
        assertEquals("task-1.", Worktrees.nameOfBranch("worktree-task-1.", UNIX))
        assertNull(Worktrees.nameOfBranch("worktree-task-1.", WINDOWS))
    }

    @Test
    fun splitFindsRepositoryAndName() {
        assertEquals(WorktreeRef("/w/acme", "feat-x"), Worktrees.split("/w/acme/.claude/worktrees/feat-x", UNIX))
        assertEquals(WorktreeRef("C:\\w\\acme", "feat-x"), Worktrees.split("C:\\w\\acme\\.claude\\worktrees\\feat-x", WINDOWS))
    }

    /** git's forward slashes, a mixed path and a lower-case drive all split the same on Windows. */
    @Test
    fun splitTakesAnyShapeOnWindows() {
        val ref = WorktreeRef("C:\\w\\acme", "feat-x")
        assertEquals(ref, Worktrees.split("C:/w/acme/.claude/worktrees/feat-x", WINDOWS))
        assertEquals(ref, Worktrees.split("C:\\w\\acme/.claude\\worktrees/feat-x", WINDOWS))
        assertEquals(ref, Worktrees.split("c:\\w\\acme\\.claude\\worktrees\\feat-x", WINDOWS))
        assertEquals(WorktreeRef("\\\\server\\share\\acme", "feat-x"), Worktrees.split("\\\\server\\share\\acme\\.claude\\worktrees\\feat-x", WINDOWS))
        // on Unix a backslash is part of a name, so this is no worktrees folder
        assertNull(Worktrees.split("/w/acme\\.claude\\worktrees\\feat-x", UNIX))
    }

    /** NTFS opens `.Claude\Worktrees` as `.claude\worktrees`; on Unix they are other folders. */
    @Test
    fun theWorktreesFolderMatchesInAnyCaseOnWindowsOnly() {
        assertEquals(WorktreeRef("C:\\w\\acme", "feat-x"), Worktrees.split("C:\\w\\acme\\.Claude\\WORKTREES\\feat-x", WINDOWS))
        assertNull(Worktrees.split("/w/acme/.Claude/worktrees/feat-x", UNIX))
        assertNull(Worktrees.split("/w/acme/.claude/Worktrees/feat-x", UNIX))
    }

    @Test
    fun splitReadsADeeperCwdAsTheSameWorktree() {
        assertEquals(WorktreeRef("/w/acme", "feat-x"), Worktrees.split("/w/acme/.claude/worktrees/feat-x/src", UNIX))
        assertEquals(WorktreeRef("C:\\w\\acme", "feat-x"), Worktrees.split("C:\\w\\acme\\.claude\\worktrees\\feat-x\\src", WINDOWS))
    }

    @Test
    fun splitIgnoresATrailingSlash() {
        assertEquals(WorktreeRef("/w/acme", "feat-x"), Worktrees.split("/w/acme/.claude/worktrees/feat-x/", UNIX))
        assertEquals(WorktreeRef("C:\\w\\acme", "feat-x"), Worktrees.split("C:\\w\\acme\\.claude\\worktrees\\feat-x\\", WINDOWS))
    }

    @Test
    fun splitIgnoresOtherFolders() {
        for ((style, root) in listOf(UNIX to "/w/acme", WINDOWS to "C:\\w\\acme")) {
            val s = style.separator
            assertNull(Worktrees.split(root, style))
            assertNull(Worktrees.split("$root$s.claude${s}worktrees", style))
            assertNull(Worktrees.split("$root$s.claude${s}worktrees$s", style))
            assertNull(Worktrees.split("$root${s}worktrees${s}feat-x", style))
            assertNull(Worktrees.split("$root$s.claude${s}worktrees2${s}feat-x", style))
        }
    }

    @Test
    fun splitRejectsAnInvalidName() {
        for ((style, root) in listOf(UNIX to "/w/acme", WINDOWS to "C:\\w\\acme")) {
            val s = style.separator
            assertNull(Worktrees.split("$root$s.claude${s}worktrees$s..", style))
            assertNull(Worktrees.split("$root$s.claude${s}worktrees$s-x", style))
        }
    }

    /**
     * Win32 opens `task-1.` as `task-1`, so on Windows a cwd under such a name is no worktree of Skein's, while on Unix
     * it is a folder of its own name.
     */
    @Test
    fun aNameEndingInADotIsAWorktreeOnUnixOnly() {
        assertEquals(WorktreeRef("/w/acme", "task-1."), Worktrees.split("/w/acme/.claude/worktrees/task-1./src", UNIX))
        assertNull(Worktrees.split("C:\\w\\acme\\.claude\\worktrees\\task-1.\\src", WINDOWS))
        assertNull(Worktrees.split("C:/w/acme/.claude/worktrees/a..", WINDOWS))
    }

    /** A worktree project with worktrees of its own: a cwd in one of those is in the inner worktree. */
    @Test
    fun aNestedWorktreeIsTheInnerOne() {
        val outer = "/w/acme/.claude/worktrees/feat-x"
        assertEquals(WorktreeRef(outer, "inner"), Worktrees.split("$outer/.claude/worktrees/inner", UNIX))
        assertEquals(WorktreeRef(outer, "inner"), Worktrees.split("$outer/.claude/worktrees/inner/src", UNIX))
        assertEquals(WorktreeRef("/w/acme", "feat-x"), Worktrees.split("$outer/.claude/worktrees", UNIX))
        val windows = "C:\\w\\acme\\.claude\\worktrees\\feat-x"
        assertEquals(WorktreeRef(windows, "inner"), Worktrees.split("C:/w/acme/.claude/worktrees/feat-x/.claude/worktrees/inner/src", WINDOWS))
        assertEquals(WorktreeRef("C:\\w\\acme", "feat-x"), Worktrees.split("$windows\\.claude\\worktrees", WINDOWS))
    }

    /** No repository name before the worktrees folder: `/` is no repository Skein makes worktrees in, nor is no root at all. */
    @Test
    fun splitRejectsAnEmptyRepository() {
        assertNull(Worktrees.split("/.claude/worktrees/x", UNIX))
        assertNull(Worktrees.split(".claude/worktrees/x", UNIX))
        assertNull(Worktrees.split(".claude\\worktrees\\x", WINDOWS))
        // the current drive's root and a drive-relative path name no root of their own
        assertNull(Worktrees.split("\\.claude\\worktrees\\x", WINDOWS))
        assertNull(Worktrees.split("C:.claude\\worktrees\\x", WINDOWS))
    }

    /** On Windows a drive (`W:\` on a subst drive) or a share may be a repository of its own. */
    @Test
    fun aDriveOrAShareIsARepositoryOnWindows() {
        assertEquals(WorktreeRef("C:\\", "x"), Worktrees.split("C:\\.claude\\worktrees\\x", WINDOWS))
        assertEquals(WorktreeRef("W:\\", "task-1"), Worktrees.split("w:/.claude/worktrees/task-1/src", WINDOWS))
        assertEquals(WorktreeRef("\\\\srv\\sh\\", "x"), Worktrees.split("\\\\srv\\sh\\.claude\\worktrees\\x", WINDOWS))
        assertEquals("W:\\.claude\\worktrees\\task-1", Worktrees.dirOf("W:\\", "task-1", WINDOWS))
        assertEquals("\\\\srv\\sh\\.claude\\worktrees\\x", Worktrees.dirOf("\\\\srv\\sh", "x", WINDOWS))
    }

    /** One worktree whichever shape of its repository's path each side has, the name by the system's case rule. */
    @Test
    fun aWorktreeRefIsComparedByPathRules() {
        val ref = WorktreeRef("C:\\w\\acme", "task-1")
        assertTrue(Worktrees.isWorktreeOf(ref, listOf("c:/w/ACME/"), "Task-1", WINDOWS))
        assertFalse(Worktrees.isWorktreeOf(ref, listOf("C:/w/acme2"), "task-1", WINDOWS))
        assertFalse(Worktrees.isWorktreeOf(ref, listOf("C:/w/acme"), "task-2", WINDOWS))
        assertFalse(Worktrees.isWorktreeOf(null, listOf("C:/w/acme"), "task-1", WINDOWS))
        val unix = WorktreeRef("/w/acme", "task-1")
        assertTrue(Worktrees.isWorktreeOf(unix, listOf("/w/other", "/w/acme/"), "task-1", UNIX))
        assertFalse(Worktrees.isWorktreeOf(unix, listOf("/w/ACME"), "task-1", UNIX))
        assertFalse(Worktrees.isWorktreeOf(unix, listOf("/w/acme"), "Task-1", UNIX))
    }

    @Test
    fun namesFollowClaudeCodesRules() {
        for (style in PathStyle.entries) {
            assertTrue(Worktrees.isValidName("feat_x.2", style))
            assertTrue(Worktrees.isValidName("x".repeat(64), style))
            listOf("", "-x", ".x", "..", "a b", "a/b", "a\\b", "x".repeat(65)).forEach { assertFalse("$style '$it'", Worktrees.isValidName(it, style)) }
        }
    }

    /** Win32 would open `task-1.` as `task-1`: Skein never makes such a name there, and does not take one for a worktree. */
    @Test
    fun aNameEndingInADotIsRefusedOnWindowsOnly() {
        for (name in listOf("task-1.", "a..", "x".repeat(63) + ".")) {
            assertTrue(name, Worktrees.isValidName(name, UNIX))
            assertFalse(name, Worktrees.isValidName(name, WINDOWS))
        }
        assertTrue(Worktrees.isValidName("a.b", WINDOWS))
        assertTrue(Worktrees.isValidName("a", WINDOWS))
        assertTrue(Worktrees.isValidName("a-", WINDOWS))
    }

    @Test
    fun nextNameIsTheFirstFreeTask() {
        assertEquals("task-1", Worktrees.nextName(emptySet(), UNIX))
        assertEquals("task-3", Worktrees.nextName(setOf("task-1", "task-2", "feat-x"), UNIX))
    }

    /** NTFS keeps `Task-1` and `task-1` in one folder, so on Windows either takes the name. */
    @Test
    fun nextNameSkipsANameTakenInAnotherCaseOnWindows() {
        assertEquals("task-2", Worktrees.nextName(setOf("Task-1"), WINDOWS))
        assertEquals("task-1", Worktrees.nextName(setOf("Task-1"), UNIX))
    }

    @Test
    fun theNameRuleAndTheAllowedCommandAgree() {
        val names = listOf(
            "feat-x", "feat_x.2", "x", "X9", "_a", "a.b-c_d", "x".repeat(64), "task-1.", "a..",
            "", "-x", ".x", "..", "a b", "a/b", "a;rm", "x".repeat(65), "a\nb", "x\n", "a\$b", "é",
        )
        for (name in names) {
            assertEquals("disagree on '$name'", Worktrees.isValidName(name), ClaudeCommand.isAllowed("claude --worktree $name"))
        }
    }

    /** The pattern each system's command check is built from agrees with that system's name rule. */
    @Test
    fun eachSystemsPatternAgreesWithItsNameRule() {
        val names = listOf("feat-x", "x", "a.b", "task-1.", "a..", "x".repeat(64), "x".repeat(63) + ".", "-x", ".x", "", "a b")
        for (style in PathStyle.entries) {
            val pattern = Regex("^${Worktrees.namePattern(style)}$")
            for (name in names) assertEquals("$style '$name'", Worktrees.isValidName(name, style), pattern.matches(name))
        }
    }
}
