package io.github.v0rest.skein.claude

import io.github.v0rest.skein.terminal.ClaudeCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorktreesTest {
    @Test
    fun dirOfPutsTheWorktreeUnderTheRepository() =
        assertEquals("/w/acme/.claude/worktrees/feat-x", Worktrees.dirOf("/w/acme", "feat-x"))

    @Test
    fun dirOfTrimsATrailingSlash() =
        assertEquals("/w/acme/.claude/worktrees/feat-x", Worktrees.dirOf("/w/acme/", "feat-x"))

    @Test
    fun parentOfIsTheWorktreesFolder() = assertEquals("/w/acme/.claude/worktrees", Worktrees.parentOf("/w/acme"))

    /** Anchored at the top of the repository, and a folder: a file of that name elsewhere stays visible to git. */
    @Test
    fun theExcludeLineIsTheWorktreesFolder() = assertEquals("/.claude/worktrees/", Worktrees.EXCLUDE_LINE)

    @Test
    fun theGuardIsAGitignoreThatIgnoresEverything() {
        assertEquals(".gitignore", Worktrees.GUARD_NAME)
        assertEquals("*\n", Worktrees.GUARD_TEXT)
    }

    @Test
    fun aWorktreeBranchNamesItsWorktree() {
        assertEquals("task-1", Worktrees.nameOfBranch("worktree-task-1"))
        assertEquals("feat.x", Worktrees.nameOfBranch("worktree-feat.x"))
        assertNull(Worktrees.nameOfBranch("main"))
        assertNull(Worktrees.nameOfBranch("worktree-"))
        assertNull(Worktrees.nameOfBranch("feature/worktree-task-1"))
        assertNull(Worktrees.nameOfBranch("worktree-a b"))
    }

    @Test
    fun splitFindsRepositoryAndName() =
        assertEquals(WorktreeRef("/w/acme", "feat-x"), Worktrees.split("/w/acme/.claude/worktrees/feat-x"))

    @Test
    fun splitReadsADeeperCwdAsTheSameWorktree() =
        assertEquals(WorktreeRef("/w/acme", "feat-x"), Worktrees.split("/w/acme/.claude/worktrees/feat-x/src"))

    @Test
    fun splitIgnoresATrailingSlash() =
        assertEquals(WorktreeRef("/w/acme", "feat-x"), Worktrees.split("/w/acme/.claude/worktrees/feat-x/"))

    @Test
    fun splitIgnoresOtherFolders() {
        assertNull(Worktrees.split("/w/acme"))
        assertNull(Worktrees.split("/w/acme/.claude/worktrees"))
        assertNull(Worktrees.split("/w/acme/.claude/worktrees/"))
        assertNull(Worktrees.split("/w/acme/worktrees/feat-x"))
    }

    @Test
    fun splitRejectsAnInvalidName() {
        assertNull(Worktrees.split("/w/acme/.claude/worktrees/.."))
        assertNull(Worktrees.split("/w/acme/.claude/worktrees/-x"))
    }

    /** A worktree project with worktrees of its own: a cwd in one of those is in the inner worktree. */
    @Test
    fun aNestedWorktreeIsTheInnerOne() {
        val outer = "/w/acme/.claude/worktrees/feat-x"
        assertEquals(WorktreeRef(outer, "inner"), Worktrees.split("$outer/.claude/worktrees/inner"))
        assertEquals(WorktreeRef(outer, "inner"), Worktrees.split("$outer/.claude/worktrees/inner/src"))
        assertEquals(WorktreeRef("/w/acme", "feat-x"), Worktrees.split("$outer/.claude/worktrees"))
    }

    @Test
    fun splitRejectsAnEmptyRepository() = assertNull(Worktrees.split("/.claude/worktrees/x"))

    @Test
    fun namesFollowClaudeCodesRules() {
        assertTrue(Worktrees.isValidName("feat_x.2"))
        listOf("", "-x", ".x", "..", "a b", "a/b", "x".repeat(65)).forEach { assertFalse(it, Worktrees.isValidName(it)) }
    }

    @Test
    fun nextNameIsTheFirstFreeTask() {
        assertEquals("task-1", Worktrees.nextName(emptySet()))
        assertEquals("task-3", Worktrees.nextName(setOf("task-1", "task-2", "feat-x")))
    }

    @Test
    fun theNameRuleAndTheAllowedCommandAgree() {
        val names = listOf(
            "feat-x", "feat_x.2", "x", "X9", "_a", "a.b-c_d", "x".repeat(64),
            "", "-x", ".x", "..", "a b", "a/b", "a;rm", "x".repeat(65), "a\nb", "x\n", "a\$b", "é",
        )
        for (name in names) {
            assertEquals("disagree on '$name'", Worktrees.isValidName(name), ClaudeCommand.isAllowed("claude --worktree $name"))
        }
    }
}
