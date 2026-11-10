package io.github.v0rest.skein.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusPorcelainTest {
    private fun z(vararg fields: String) = fields.joinToString("") { it + "\u0000" }

    @Test
    fun stagedUnstagedAndUntrackedCountOncePerPath() {
        val lines = StatusPorcelain.parse(z("## worktree-acme", "M  staged.kt", " M changed.kt", "MM both.kt", "?? new.kt", "A  added.kt", " D gone.kt"))
        assertEquals(listOf("staged.kt", "changed.kt", "both.kt", "new.kt", "added.kt", "gone.kt"), lines.paths)
        assertFalse(lines.detached)
    }

    @Test
    fun aRenameOrCopyIsOneChangeAndItsOldPathIsSkipped() {
        val lines = StatusPorcelain.parse(z("## main", "R  new.kt", "old.kt", "RM moved.kt", "was.kt", " C copy.kt", "orig.kt", "?? x.kt"))
        assertEquals(listOf("new.kt", "moved.kt", "copy.kt", "x.kt"), lines.paths)
    }

    @Test
    fun anUntrackedFolderIsOnePath() {
        assertEquals(listOf("build/"), StatusPorcelain.parse(z("## main", "?? build/")).paths)
    }

    @Test
    fun pathsKeepSpacesAndNonAsciiAsGitWroteThem() {
        assertEquals(listOf("a b.kt", "é.txt"), StatusPorcelain.parse(z("## main", "?? a b.kt", " M é.txt")).paths)
    }

    @Test
    fun aDetachedHeadIsSaidInTheBranchLine() {
        assertTrue(StatusPorcelain.parse(z("## HEAD (no branch)", " M a.kt")).detached)
        assertFalse(StatusPorcelain.parse(z("## main...origin/main [ahead 2]")).detached)
        assertFalse(StatusPorcelain.parse(z("## No commits yet on main")).detached)
    }

    @Test
    fun aCleanWorktreeHasNoPaths() {
        assertEquals(StatusLines(emptyList(), false), StatusPorcelain.parse(z("## worktree-acme")))
        assertEquals(StatusLines(emptyList(), false), StatusPorcelain.parse(""))
    }

    @Test
    fun aConflictIsAChangedPath() {
        assertEquals(listOf("a.kt", "b.kt"), StatusPorcelain.parse(z("## HEAD (no branch)", "UU a.kt", "AA b.kt")).paths)
    }
}
