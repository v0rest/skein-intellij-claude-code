package io.github.v0rest.skein.git

import io.github.v0rest.skein.testutil.RealGit
import io.github.v0rest.skein.ui.WorktreeStart
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

/** Smoke test against the real git; skipped when git is not installed. */
class ProcessGitRunnerTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val runner = ProcessGitRunner()

    @Test
    fun runsGitInTheGivenFolder() {
        val dir = folder.newFolder("empty").toPath()
        assumeTrue(runner.run(dir, "--version").ok)
        assertTrue(runner.run(dir, "init", "-q").ok)
        val git = WorktreeGit(runner)
        val head = git.head(dir)
        assertEquals("no commit yet: git answers 1 and says nothing", 1, head.exitCode)
        assertEquals("", head.stderr)
        assertEquals(WorktreeStart.NO_COMMIT, WorktreeStart.headRefusal(head))
        assertTrue(git.list(dir).size == 1)
        assertNull("no commit yet, so git cannot count", git.state(dir))
    }

    @Test
    fun aRepositoryWithACommitHasAHead() {
        val git = RealGit(folder)
        assumeTrue(git.available)
        val head = WorktreeGit(git).head(git.repository())
        assertTrue(head.ok)
        assertNull(WorktreeStart.headRefusal(head))
    }

    @Test
    fun aMissingFolderIsAFailureNotAThrow() {
        val result = runner.run(folder.root.toPath().resolve("nope"), "status")
        assertFalse(result.ok)
        assertFalse("git could not even start", result.ran)
    }

    /** git removes a detached worktree whatever its commits; only those no ref holds are lost, and only those count. */
    @Test
    fun detachedCommitsCountOnlyWhatNoRefHolds() {
        val git = RealGit(folder)
        assumeTrue(git.available)
        val repo = git.repository()
        assertTrue(git.run(repo, "worktree", "add", "-q", "--detach", "--", ".claude/worktrees/task-1").ok)
        val worktree = repo.resolve(".claude/worktrees/task-1")
        val worktrees = WorktreeGit(git)
        assertEquals(GitWorktree(worktree.toRealPath().toString(), null, detached = true), worktrees.list(repo).last())
        assertEquals("the branch it started from holds its HEAD", 0, worktrees.detachedCommits(worktree))

        assertTrue(git.run(worktree, "commit", "-q", "--allow-empty", "-m", "two").ok)
        assertEquals(1, worktrees.detachedCommits(worktree))
        assertEquals("--all would count HEAD itself as holding it", "0", git.run(worktree, "rev-list", "--count", "HEAD", "--not", "--all").stdout.trim())

        assertTrue(git.run(worktree, "tag", "kept").ok)
        assertEquals("a tag holds it now", 0, worktrees.detachedCommits(worktree))
    }

    /** `lstrip=2` names a branch `worktree-task-1` so even when a tag of the same name exists. */
    @Test
    fun branchesAreNamedWithoutRefsHeads() {
        val git = RealGit(folder)
        assumeTrue(git.available)
        val repo = git.repository()
        assertTrue(git.run(repo, "branch", "worktree-task-1").ok)
        assertTrue(git.run(repo, "tag", "worktree-task-1").ok)
        assertTrue(git.run(repo, "branch", "feature/x").ok)
        assertTrue(WorktreeGit(git).branches(repo).containsAll(setOf("worktree-task-1", "feature/x")))
    }

    /**
     * [ProcessGitRunner.bytes] hands back a blob as git stored it: a CRLF and a lone CR as they are, a NUL, bytes that are
     * not UTF-8, and a backspace that the platform's own capture would let delete the byte before it.
     */
    @Test
    fun bytesComeBackAsGitWroteThem() {
        val git = RealGit(folder)
        assumeTrue(git.available)
        val repo = git.repository()
        val bytes = byteArrayOf(97, 13, 10, 98, 13, 99, 0, 10, 0x80.toByte(), 0xC3.toByte(), 0x28, 7, 8, 0xFF.toByte(), 13) + ByteArray(256) { it.toByte() }
        Files.write(repo.resolve("raw.bin"), bytes)
        val id = git.run(repo, "-c", "core.autocrlf=false", "hash-object", "-w", "--no-filters", "--", "raw.bin").stdout.trim()
        val read = runner.bytes(repo, "cat-file", "blob", id)
        assertTrue(read.ok)
        assertArrayEquals(bytes, read.bytes)
        assertEquals("", read.result.stdout)
    }

    @Test
    fun bytesOfAFailedCallSayWhy() {
        val dir = folder.newFolder("not-a-repository").toPath()
        assumeTrue(runner.run(dir, "--version").ok)
        val read = runner.bytes(dir, "cat-file", "blob", "0".repeat(40))
        assertFalse(read.ok)
        assertTrue(read.result.reason(), read.result.ran)
        assertEquals(0, read.bytes.size)
    }
}
