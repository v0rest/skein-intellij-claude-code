package io.github.v0rest.skein.git

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Path

/** [WorktreeReader]'s commands and its answers when git fails, with a git that answers from a script. */
class WorktreeReaderTest {
    private val wt: Path = Path.of("/w/acme/.claude/worktrees/acme")
    private val repo: Path = Path.of("/w/acme")

    private class FakeGit(private val answer: (List<String>) -> GitResult) : GitRunner {
        val calls = mutableListOf<String>()

        override fun run(dir: Path, vararg args: String): GitResult {
            calls += args.joinToString(" ") + " @" + dir
            return answer(args.toList())
        }
    }

    private fun ok(out: String = "") = GitResult(0, out, "", false)
    private val absent = GitResult(1, "", "", false)
    private val timedOut = GitResult(-1, "", "", true)
    private val markers = "/w/acme/.git/worktrees/acme/rebase-merge\n/w/acme/.git/worktrees/acme/rebase-apply\n" +
        "/w/acme/.git/worktrees/acme/MERGE_HEAD\n/w/acme/.git/worktrees/acme/CHERRY_PICK_HEAD\n"

    private fun answers(
        status: GitResult = ok("## worktree-acme\u0000 M a.kt\u0000"),
        gitPath: GitResult = ok(markers),
        verify: (String) -> GitResult = { ok("abc") },
        revList: GitResult = ok("2\n"),
        symbolicRef: GitResult = absent,
    ): (List<String>) -> GitResult = { args ->
        // the git command after any `-c name=value`
        when (if (args.first() == "-c") args[2] else args.first()) {
            "status" -> status
            "rev-parse" -> if ("--verify" in args) verify(args.last()) else gitPath
            "rev-list" -> revList
            "symbolic-ref" -> symbolicRef
            else -> GitResult(1, "", "unexpected", false)
        }
    }

    @Test
    fun theCommandsAreTheOnesTheRowNeeds() {
        val git = FakeGit(answers())
        val counts = WorktreeReader(git) { false }.read(wt, "main") { BaseLookup.NoBase }
        assertEquals(WorktreeCounts.Counted(Changes(1, listOf("a.kt")), detached = false, base = "main", ahead = 2), counts)
        assertEquals(
            listOf(
                "-c core.fsmonitor=false status --porcelain -z --branch --untracked-files=normal @$wt",
                "rev-parse --git-path rebase-merge --git-path rebase-apply --git-path MERGE_HEAD --git-path CHERRY_PICK_HEAD @$wt",
                "rev-parse -q --verify refs/heads/main^{commit} @$wt",
                "rev-list --count refs/heads/main..HEAD @$wt",
            ),
            git.calls,
        )
    }

    @Test
    fun theFallbackAsksForTheRemoteDefaultThenTheUsualNames() {
        val git = FakeGit(answers(symbolicRef = ok("origin/trunk\n"), verify = { if (it == "refs/heads/main^{commit}") ok("abc") else absent }))
        assertEquals(BaseLookup.Found(Base("main", "refs/heads/main")), WorktreeReader(git) { false }.fallbackBase(repo))
        assertEquals(
            listOf(
                "symbolic-ref -q --short refs/remotes/origin/HEAD @$repo",
                "rev-parse -q --verify refs/remotes/origin/trunk^{commit} @$repo",
                "rev-parse -q --verify refs/remotes/origin/main^{commit} @$repo",
                "rev-parse -q --verify refs/remotes/origin/master^{commit} @$repo",
                "rev-parse -q --verify refs/heads/main^{commit} @$repo",
            ),
            git.calls,
        )
    }

    private fun reader(answer: (List<String>) -> GitResult) = WorktreeReader(FakeGit(answer)) { false }

    @Test
    fun aGitThatFailsOrTimesOutIsAFailureNeverZeros() {
        assertEquals(WorktreeCounts.Failed("git did not finish in time"), reader(answers(status = timedOut)).read(wt, "main") { BaseLookup.NoBase })
        assertEquals(WorktreeCounts.Failed("not a git repository"), reader(answers(status = GitResult(128, "", "fatal: not a git repository\n", false))).read(wt, "main") { BaseLookup.NoBase })
        assertEquals(WorktreeCounts.Failed("git did not finish in time"), reader(answers(gitPath = timedOut)).read(wt, "main") { BaseLookup.NoBase })
        assertEquals(WorktreeCounts.Failed("git did not finish in time"), reader(answers(revList = timedOut)).read(wt, "main") { BaseLookup.NoBase })
        assertEquals(WorktreeCounts.Failed("git did not finish in time"), reader(answers(verify = { timedOut })).read(wt, "main") { BaseLookup.NoBase })
        assertEquals(WorktreeCounts.Failed("bad"), reader(answers()).read(wt, null) { BaseLookup.Failed("bad") })
        assertEquals(WorktreeCounts.Failed("git gave no count"), reader(answers(revList = ok("many"))).read(wt, "main") { BaseLookup.NoBase })
        assertEquals(BaseLookup.Failed("git did not finish in time"), reader(answers(symbolicRef = timedOut)).fallbackBase(repo))
    }

    @Test
    fun anOperationInProgressComesBeforeTheBranch() {
        val inMerge = WorktreeReader(FakeGit(answers(status = ok("## HEAD (no branch)\u0000UU a.kt\u0000")))) { it.toString().endsWith("MERGE_HEAD") }
        assertEquals(WorktreeCounts.InProgress(Operation.MERGE, Changes(1, listOf("a.kt"))), inMerge.read(wt, "main") { BaseLookup.NoBase })
        val inRebase = WorktreeReader(FakeGit(answers())) { it.toString().endsWith("rebase-apply") }
        assertEquals(Operation.REBASE, (inRebase.read(wt, "main") { BaseLookup.NoBase } as WorktreeCounts.InProgress).operation)
    }

    @Test
    fun aDetachedHeadIsNotCountedAgainstABase() {
        val git = FakeGit(answers(status = ok("## HEAD (no branch)\u0000")))
        assertEquals(WorktreeCounts.Counted(Changes.NONE, detached = true, base = null, ahead = null), WorktreeReader(git) { false }.read(wt, "main") { error("not asked") })
        assertEquals(2, git.calls.size)
    }

    @Test
    fun theFallbackIsAskedOnlyWhenTheRecordedBranchIsMissing() {
        var asked = 0
        val fallback = { asked++; BaseLookup.Found(Base("origin/main", "refs/remotes/origin/main")) }
        WorktreeReader(FakeGit(answers())) { false }.read(wt, "main", fallback)
        assertEquals(0, asked)
        val counts = WorktreeReader(FakeGit(answers(verify = { absent }))) { false }.read(wt, "main", fallback)
        assertEquals(1, asked)
        assertEquals("origin/main", (counts as WorktreeCounts.Counted).base)
    }
}
