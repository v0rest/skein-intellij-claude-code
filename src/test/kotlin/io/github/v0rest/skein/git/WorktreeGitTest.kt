package io.github.v0rest.skein.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class WorktreeGitTest {
    private class FakeGit(private val answer: (List<String>) -> GitResult) : GitRunner {
        val calls = mutableListOf<Pair<Path, List<String>>>()
        override fun run(dir: Path, vararg args: String): GitResult {
            calls += dir to args.toList()
            return answer(args.toList())
        }
    }

    private fun ok(out: String = "") = GitResult(0, out, "", false)
    private val repo: Path = Path.of("/w/acme")

    @Test
    fun parsesWorktreeListPorcelain() {
        val out = """
            worktree /w/acme
            HEAD 1111111111111111111111111111111111111111
            branch refs/heads/main

            worktree /w/acme/.claude/worktrees/feat-x
            HEAD 2222222222222222222222222222222222222222
            branch refs/heads/worktree-feat-x
            locked reason here

            worktree /w/acme/.claude/worktrees/loose
            HEAD 3333333333333333333333333333333333333333
            detached
            prunable gitdir file points to non-existent location

        """.trimIndent()
        val list = WorktreeGit(FakeGit { ok(out) }).list(repo)
        assertEquals(
            listOf(
                GitWorktree("/w/acme", "main"),
                GitWorktree("/w/acme/.claude/worktrees/feat-x", "worktree-feat-x", locked = true, lockReason = "reason here"),
                GitWorktree("/w/acme/.claude/worktrees/loose", null, detached = true, prunable = true),
            ),
            list,
        )
    }

    @Test
    fun parsesBareLockedWithoutReasonAndUnknownLines() {
        val out = """
            worktree /w/acme.git
            bare

            worktree /w/acme/.claude/worktrees/acme
            HEAD 2222222222222222222222222222222222222222
            detached
            locked
            some-future-line value

            worktree /w/acme/.claude/worktrees/gone
            HEAD 3333333333333333333333333333333333333333
            branch refs/heads/worktree-gone
            prunable
        """.trimIndent()
        assertEquals(
            listOf(
                GitWorktree("/w/acme.git", null, bare = true),
                GitWorktree("/w/acme/.claude/worktrees/acme", null, detached = true, locked = true),
                GitWorktree("/w/acme/.claude/worktrees/gone", "worktree-gone", prunable = true),
            ),
            WorktreeGit.parseList(out),
        )
    }

    /** The lock's reason as git wrote it, Claude Code's own included, and none for a lock without one. */
    @Test
    fun keepsTheLockReasonAsGitWroteIt() {
        val out = """
            worktree /w/acme/.claude/worktrees/demo
            HEAD 2222222222222222222222222222222222222222
            branch refs/heads/worktree-demo
            locked claude session demo (pid 9384 start Tue Nov  3 08:30:41 2026)

            worktree /w/acme/.claude/worktrees/quoted
            HEAD 3333333333333333333333333333333333333333
            branch refs/heads/worktree-quoted
            locked "two\nlines"

            worktree /w/acme/.claude/worktrees/bare-lock
            HEAD 4444444444444444444444444444444444444444
            branch refs/heads/worktree-bare-lock
            locked
        """.trimIndent()
        assertEquals(
            listOf("claude session demo (pid 9384 start Tue Nov  3 08:30:41 2026)", "\"two\\nlines\"", null),
            WorktreeGit.parseList(out).map { it.lockReason },
        )
        assertTrue(WorktreeGit.parseList(out).all { it.locked })
    }

    /**
     * The one lock Skein lifts, with `--` before the path as everywhere, and the same conventions as its other git
     * calls in a working tree.
     */
    @Test
    fun unlockLiftsTheLockOfOneWorktree() {
        val git = FakeGit { ok() }
        val wt = Path.of("/w/acme/.claude/worktrees/feat-x")
        assertTrue(WorktreeGit(git).liftLock(repo, wt).ok)
        assertEquals(listOf(repo to listOf("-c", "core.fsmonitor=false", "--no-optional-locks", "worktree", "unlock", "--", wt.toString())), git.calls)
    }

    /** A failed list is not an empty one: every folder would then look like one git does not list. */
    @Test
    fun aFailedListingSaysWhy() {
        assertEquals(WorktreeListing.Failed("not a git repository"), WorktreeGit(FakeGit { GitResult(128, "", "fatal: not a git repository\n", false) }).listing(repo))
        assertEquals(WorktreeListing.Failed("git did not finish in time"), WorktreeGit(FakeGit { GitResult(-1, "", "", true) }).listing(repo))
        assertEquals(WorktreeListing.Failed("git could not be run: No such file"), WorktreeGit(FakeGit { GitResult(-1, "", "No such file", false) }).listing(repo))
        assertEquals(WorktreeListing.Listed(listOf(GitWorktree("/w/acme", "main"))), WorktreeGit(FakeGit { ok("worktree /w/acme\nbranch refs/heads/main\n") }).listing(repo))
    }

    @Test
    fun theCurrentBranchIsNullWhenDetachedOrUnknown() {
        val git = FakeGit { ok("main\n") }
        assertEquals("main", WorktreeGit(git).currentBranch(repo))
        assertEquals(listOf(repo to listOf("branch", "--show-current")), git.calls)
        assertNull(WorktreeGit(FakeGit { ok("\n") }).currentBranch(repo))
        assertNull(WorktreeGit(FakeGit { GitResult(128, "", "fatal", false) }).currentBranch(repo))
    }

    /** The one forced git call Skein has, with `--` before the path as everywhere. */
    @Test
    fun forceRemoveIsGitsSingleForce() {
        val git = FakeGit { ok() }
        val wt = Path.of("/w/acme/.claude/worktrees/feat-x")
        WorktreeGit(git).forceRemove(repo, wt)
        assertEquals(listOf(repo to listOf("-c", "core.fsmonitor=false", "worktree", "remove", "--force", "--", wt.toString())), git.calls)
    }

    @Test
    fun noOptionalLocksGoesBeforeEveryCommand() {
        val git = FakeGit { ok() }
        NoOptionalLocks(git).run(repo, "status", "--porcelain")
        assertEquals(listOf(repo to listOf("--no-optional-locks", "status", "--porcelain")), git.calls)
    }

    @Test
    fun aRefusalIsShownInGitsOwnWordsUpToALimit() {
        val refusal = "fatal: '/w/acme/.claude/worktrees/acme' contains modified or untracked files, use --force to delete it\n"
        assertEquals(refusal.trim(), GitResult(128, "", refusal, false).message())
        assertEquals("x".repeat(400) + "…", GitResult(128, "", "x".repeat(900), false).message())
        assertEquals(GitResult.TIMED_OUT, GitResult(-1, "", "partial", true).message())
        assertEquals("git gave no reason", GitResult(1, "", "  \n", false).message())
    }

    @Test
    fun listCallsGitInTheRepository() {
        val git = FakeGit { ok() }
        WorktreeGit(git).list(repo)
        assertEquals(listOf(repo to listOf("worktree", "list", "--porcelain")), git.calls)
    }

    @Test
    fun aFailingListGivesNothing() {
        assertEquals(emptyList<GitWorktree>(), WorktreeGit(FakeGit { GitResult(128, "", "fatal", false) }).list(repo))
    }

    @Test
    fun headAsksGitForHead() {
        val git = FakeGit { GitResult(128, "", "fatal", false) }
        assertFalse(WorktreeGit(git).head(repo).ok)
        assertEquals(listOf(repo to listOf("rev-parse", "-q", "--verify", "HEAD")), git.calls)
        assertTrue(WorktreeGit(FakeGit { ok("abc") }).head(repo).ok)
    }

    @Test
    fun stateCountsChangedFilesAndUnpushedCommits() {
        val wt = Path.of("/w/acme/.claude/worktrees/feat-x")
        val git = FakeGit { args ->
            if (args.contains("status")) ok(" M a.kt\n?? b.kt\n\n") else ok("3\n")
        }
        assertEquals(WorktreeState(2, 3), WorktreeGit(git).state(wt))
        assertEquals(
            listOf(
                wt to listOf("-c", "core.fsmonitor=false", "--no-optional-locks", "status", "--porcelain"),
                wt to listOf("rev-list", "--count", "HEAD", "--not", "--remotes"),
            ),
            git.calls,
        )
    }

    @Test
    fun aFailingGitGivesNoState() {
        val wt = Path.of("/w/acme/.claude/worktrees/feat-x")
        assertNull(WorktreeGit(FakeGit { GitResult(128, "", "fatal", false) }).state(wt))
        assertNull(WorktreeGit(FakeGit { args -> if (args.contains("status")) ok("") else GitResult(-1, "", "", true) }).state(wt))
        assertNull(WorktreeGit(FakeGit { args -> if (args.contains("status")) ok("") else ok("many") }).state(wt))
    }

    @Test
    fun detachedCommitsAreThoseNoBranchTagOrRemoteHolds() {
        val wt = Path.of("/w/acme/.claude/worktrees/feat-x")
        val git = FakeGit { ok("2\n") }
        assertEquals(2, WorktreeGit(git).detachedCommits(wt))
        assertEquals(listOf(wt to listOf("rev-list", "--count", "HEAD", "--not", "--branches", "--tags", "--remotes")), git.calls)
        assertNull(WorktreeGit(FakeGit { GitResult(128, "", "fatal", false) }).detachedCommits(wt))
        assertNull(WorktreeGit(FakeGit { GitResult(-1, "", "", true) }).detachedCommits(wt))
        assertNull(WorktreeGit(FakeGit { ok("many") }).detachedCommits(wt))
    }

    @Test
    fun branchesAreTheLocalOnes() {
        val git = FakeGit { ok("main\nworktree-task-1\nfeature/x\n\n") }
        assertEquals(setOf("main", "worktree-task-1", "feature/x"), WorktreeGit(git).branches(repo))
        assertEquals(listOf(repo to listOf("for-each-ref", "--format=%(refname:lstrip=2)", "refs/heads/")), git.calls)
        assertEquals(emptySet<String>(), WorktreeGit(FakeGit { GitResult(128, "", "fatal", false) }).branches(repo))
    }

    /** `--` ends git's options, so neither the path nor the branch can ever be read as one. */
    @Test
    fun removeAndDeleteBranchNeverForce() {
        val git = FakeGit { ok() }
        val wt = Path.of("/w/acme/.claude/worktrees/feat-x")
        WorktreeGit(git).remove(repo, wt)
        WorktreeGit(git).deleteBranch(repo, "worktree-feat-x")
        assertEquals(
            listOf(
                repo to listOf("-c", "core.fsmonitor=false", "worktree", "remove", "--", wt.toString()),
                repo to listOf("branch", "-d", "--", "worktree-feat-x"),
            ),
            git.calls,
        )
    }
}
