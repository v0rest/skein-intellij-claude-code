package io.github.v0rest.skein.git

import io.github.v0rest.skein.testutil.RealGit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

/**
 * [WorktreeReader] against the real git, in a temporary repository with a worktree under `.claude/worktrees`, through
 * [NoOptionalLocks] as Skein runs it. Skipped when git is not installed.
 */
class WorktreeReaderRealGitTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var git: RealGit
    private lateinit var reader: WorktreeReader
    private lateinit var repo: Path
    private lateinit var wt: Path

    @Before
    fun aRepositoryWithAWorktree() {
        git = RealGit(folder)
        assumeTrue(git.available)
        reader = WorktreeReader(NoOptionalLocks(git))
        repo = git.repository("acme").toRealPath()
        ok(repo, "branch", "-M", "main")
        ok(repo, "worktree", "add", "-q", "-b", "worktree-acme", "--", ".claude/worktrees/acme")
        wt = repo.resolve(".claude/worktrees/acme")
    }

    private fun ok(dir: Path, vararg args: String): GitResult = git.run(dir, *args).also { assertTrue(args.joinToString(" ") + ": " + it.stderr, it.ok) }

    private fun commit(dir: Path, file: String, text: String, message: String = "change $file") {
        Files.writeString(dir.resolve(file), text)
        ok(dir, "add", "--", file)
        ok(dir, "commit", "-q", "-m", message)
    }

    private fun read(recorded: String? = "main"): WorktreeCounts = reader.read(wt, recorded) { reader.fallbackBase(repo) }

    @Test
    fun aCleanWorktreeIsLevelWithItsRecordedBase() {
        assertEquals(WorktreeCounts.Counted(Changes.NONE, detached = false, base = "main", ahead = 0), read())
    }

    @Test
    fun stagedUnstagedAndUntrackedPathsAndCommitsAheadAreCounted() {
        commit(wt, "one.c", "1\n")
        commit(wt, "two.c", "2\n")
        Files.writeString(wt.resolve("a.c"), "int b;\n")
        Files.writeString(wt.resolve("staged.c"), "s\n")
        ok(wt, "add", "--", "staged.c")
        Files.writeString(wt.resolve("new file.c"), "n\n")
        val counts = read() as WorktreeCounts.Counted
        assertEquals(setOf("a.c", "staged.c", "new file.c"), counts.changes.first.toSet())
        assertEquals(3, counts.changes.count)
        assertEquals("main", counts.base)
        assertEquals(2, counts.ahead)
    }

    @Test
    fun theTooltipKeepsTenPathsAndTheCountKeepsAll() {
        for (i in 1..12) Files.writeString(wt.resolve("f$i.c"), "$i\n")
        val counts = read() as WorktreeCounts.Counted
        assertEquals(12, counts.changes.count)
        assertEquals(10, counts.changes.first.size)
    }

    @Test
    fun withoutARecordedBaseTheRemoteDefaultBranchCounts() {
        ok(repo, "update-ref", "refs/remotes/origin/trunk", "HEAD")
        ok(repo, "symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/origin/trunk")
        commit(wt, "one.c", "1\n")
        assertEquals(WorktreeCounts.Counted(Changes.NONE, detached = false, base = "origin/trunk", ahead = 1), read(recorded = null))
    }

    @Test
    fun aRecordedBranchThatIsGoneFallsBackToTheUsualNames() {
        commit(wt, "one.c", "1\n")
        assertEquals(WorktreeCounts.Counted(Changes.NONE, detached = false, base = "main", ahead = 1), read(recorded = "develop"))
    }

    @Test
    fun theRecordedBranchWinsOverTheUsualNames() {
        ok(repo, "branch", "develop")
        commit(repo, "a.c", "int c;\n") // main moves on, develop stays where the worktree started
        commit(wt, "one.c", "1\n")
        assertEquals(WorktreeCounts.Counted(Changes.NONE, detached = false, base = "develop", ahead = 1), read(recorded = "develop"))
    }

    @Test
    fun noBaseAnywhereCountsTheChangesOnly() {
        ok(repo, "branch", "-M", "main", "trunk")
        Files.writeString(wt.resolve("x.c"), "x\n")
        assertEquals(WorktreeCounts.Counted(Changes(1, listOf("x.c")), detached = false, base = null, ahead = null), read(recorded = null))
    }

    @Test
    fun aDetachedHeadCountsTheChangesOnly() {
        commit(wt, "one.c", "1\n")
        ok(wt, "checkout", "-q", "--detach")
        Files.writeString(wt.resolve("x.c"), "x\n")
        assertEquals(WorktreeCounts.Counted(Changes(1, listOf("x.c")), detached = true, base = null, ahead = null), read())
    }

    /** main and the worktree's branch both change a.c, so replaying one on the other stops on a conflict. */
    private fun conflicting(): String {
        commit(repo, "a.c", "int main_side;\n")
        commit(wt, "a.c", "int worktree_side;\n")
        return git.run(repo, "rev-parse", "main").stdout.trim()
    }

    @Test
    fun aRebaseStoppedHalfWayShowsItsWord() {
        conflicting()
        assertTrue(!git.run(wt, "rebase", "main").ok)
        val counts = read()
        assertTrue(counts.toString(), counts is WorktreeCounts.InProgress && counts.operation == Operation.REBASE && "a.c" in counts.changes.first)
    }

    @Test
    fun aMergeStoppedHalfWayShowsItsWord() {
        conflicting()
        assertTrue(!git.run(wt, "merge", "main").ok)
        val counts = read()
        assertTrue(counts.toString(), counts is WorktreeCounts.InProgress && counts.operation == Operation.MERGE)
    }

    @Test
    fun aCherryPickStoppedHalfWayShowsItsWord() {
        val pick = conflicting()
        assertTrue(!git.run(wt, "cherry-pick", pick).ok)
        val counts = read()
        assertTrue(counts.toString(), counts is WorktreeCounts.InProgress && counts.operation == Operation.CHERRY_PICK)
    }

    /** The main working tree answers `--git-path` with a relative path; it counts from the worktree asked about. */
    @Test
    fun theMainWorkingTreeIsReadToo() {
        conflicting()
        assertTrue(!git.run(repo, "merge", "worktree-acme").ok)
        val counts = reader.read(repo, "main") { BaseLookup.NoBase }
        assertTrue(counts.toString(), counts is WorktreeCounts.InProgress && counts.operation == Operation.MERGE)
    }
}
