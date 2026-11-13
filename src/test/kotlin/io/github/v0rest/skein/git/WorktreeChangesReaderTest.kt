package io.github.v0rest.skein.git

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

/** [WorktreeChangesReader]'s commands and its refusals, and its blob reads, with a git that answers from a script. */
class WorktreeChangesReaderTest {
    private val repo: Path = Path.of("/w/acme")
    private val wt: Path = Path.of("/w/acme/.claude/worktrees/task-1")
    private val mergeBase = "7ee31195368ab795369f35d97eaff4ac19a71233"
    private val blob = "78981922613b2afb6025042ff6bd878ac1994e85"
    private val zero = "0".repeat(40)

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
    private val markers = "/w/acme/.git/worktrees/task-1/rebase-merge\n/w/acme/.git/worktrees/task-1/rebase-apply\n" +
        "/w/acme/.git/worktrees/task-1/MERGE_HEAD\n/w/acme/.git/worktrees/task-1/CHERRY_PICK_HEAD\n"
    private val listed = "worktree /w/acme\nHEAD $blob\nbranch refs/heads/main\n\nworktree $wt\nHEAD $blob\nbranch refs/heads/worktree-task-1\n\n"

    private fun answers(
        list: GitResult = ok(listed),
        verify: (String) -> GitResult = { ok("abc") },
        symbolicRef: GitResult = absent,
        mergeBaseAnswer: GitResult = ok("$mergeBase\n"),
        diff: GitResult = ok(":100644 100644 $blob $zero M\u0000a.c\u0000:100644 000000 $blob $zero D\u0000gone.c\u0000"),
        others: GitResult = ok("new file.c\u0000"),
        size: GitResult = ok("5\n"),
        content: GitResult = ok("int a"),
    ): (List<String>) -> GitResult = { args ->
        // the git command after any `-c name=value`
        when (if (args.first() == "-c") args[2] else args.first()) {
            "worktree" -> list
            "rev-parse" -> if ("--verify" in args) verify(args.last()) else ok(markers)
            "symbolic-ref" -> symbolicRef
            "merge-base" -> mergeBaseAnswer
            "diff" -> diff
            "ls-files" -> others
            "cat-file" -> if ("-s" in args) size else content
            else -> GitResult(1, "", "unexpected", false)
        }
    }

    private fun reader(git: GitRunner, exists: (Path) -> Boolean = { it == wt }) = WorktreeChangesReader(git, exists)

    @Test
    fun theCommandsAreTheOnesShowChangesNeeds() {
        val git = FakeGit(answers())
        val found = reader(git).read(repo, wt, recorded = "main")
        assertEquals(
            ChangesLookup.Found(
                Base("main", "refs/heads/main"),
                mergeBase,
                listOf(
                    ChangedFile(ChangeKind.MODIFIED, "a.c", base = BaseObject(blob, "100644")),
                    ChangedFile(ChangeKind.DELETED, "gone.c", base = BaseObject(blob, "100644")),
                    ChangedFile(ChangeKind.UNTRACKED, "new file.c"),
                ),
            ),
            found,
        )
        assertEquals(
            listOf(
                "worktree list --porcelain @$repo",
                "rev-parse --git-path rebase-merge --git-path rebase-apply --git-path MERGE_HEAD --git-path CHERRY_PICK_HEAD @$wt",
                "rev-parse -q --verify refs/heads/main^{commit} @$wt",
                "merge-base refs/heads/main HEAD @$wt",
                "-c core.fsmonitor=false diff --no-color --no-ext-diff --raw -z -M --no-abbrev $mergeBase -- @$wt",
                "-c core.fsmonitor=false ls-files --others --exclude-standard -z @$wt",
            ),
            git.calls,
        )
    }

    @Test
    fun withoutARecordedBaseTheRepositorysBaseCounts() {
        val git = FakeGit(answers(verify = { if (it == "refs/remotes/origin/main^{commit}") ok("abc") else absent }))
        val found = reader(git).read(repo, wt, recorded = null) as ChangesLookup.Found
        assertEquals(Base("origin/main", "refs/remotes/origin/main"), found.base)
        assertTrue(git.calls.toString(), git.calls.contains("symbolic-ref -q --short refs/remotes/origin/HEAD @$repo"))
        assertTrue(git.calls.toString(), git.calls.contains("merge-base refs/remotes/origin/main HEAD @$wt"))
    }

    @Test
    fun aFolderThatIsGoneIsRefusedWithoutAskingGit() {
        val git = FakeGit(answers())
        assertEquals(ChangesLookup.Refused(WorktreeChangesReader.GONE), reader(git, exists = { false }).read(repo, wt, "main"))
        assertEquals(emptyList<String>(), git.calls)
    }

    /** In a folder git does not list, git would answer for the repository around it: nothing runs there. */
    @Test
    fun aFolderGitDoesNotListIsRefusedBeforeGitRunsInIt() {
        val git = FakeGit(answers(list = ok("worktree /w/acme\nHEAD $blob\nbranch refs/heads/main\n\n")))
        assertEquals(ChangesLookup.Refused(WorktreeChangesReader.notListed(wt)), reader(git).read(repo, wt, "main"))
        assertEquals(listOf("worktree list --porcelain @$repo"), git.calls)
    }

    @Test
    fun aPrunableOrBareEntryIsNoWorktreeToRead() {
        for (mark in listOf("prunable gitdir file points to non-existent location", "bare")) {
            val git = FakeGit(answers(list = ok("worktree $wt\nHEAD $blob\n$mark\n\n")))
            assertEquals(mark, ChangesLookup.Refused(WorktreeChangesReader.notListed(wt)), reader(git).read(repo, wt, "main"))
        }
    }

    @Test
    fun aListingGitCannotGiveIsRefusedWithGitsReason() {
        val git = FakeGit(answers(list = GitResult(128, "", "fatal: not a git repository\n", false)))
        assertEquals(ChangesLookup.Refused("not a git repository"), reader(git).read(repo, wt, "main"))
    }

    @Test
    fun aStoppedRebaseIsRefused() {
        val git = FakeGit(answers())
        val rebasing = { path: Path -> path == wt || path.endsWith("rebase-merge") }
        assertEquals(
            ChangesLookup.Refused("a rebase is in progress there - finish or abort it first"),
            reader(git, rebasing).read(repo, wt, "main"),
        )
        assertTrue(git.calls.none { it.startsWith("merge-base") || it.contains(" diff ") })
    }

    @Test
    fun noBaseAnywhereIsRefused() {
        val git = FakeGit(answers(verify = { absent }))
        assertEquals(ChangesLookup.Refused(WorktreeChangesReader.NO_BASE), reader(git).read(repo, wt, "main"))
    }

    @Test
    fun noCommitInCommonWithTheBaseIsRefused() {
        val git = FakeGit(answers(mergeBaseAnswer = absent))
        assertEquals(ChangesLookup.Refused("it has no commit in common with main"), reader(git).read(repo, wt, "main"))
    }

    @Test
    fun gitFailingOrTimingOutAnywhereIsRefusedWithItsReason() {
        val fatal = GitResult(128, "", "fatal: bad object\n", false)
        val cases = listOf(
            answers(mergeBaseAnswer = timedOut) to GitResult.TIMED_OUT,
            answers(mergeBaseAnswer = fatal) to "bad object",
            answers(mergeBaseAnswer = ok("\n")) to "git named no commit in common",
            answers(diff = fatal) to "bad object",
            answers(others = timedOut) to GitResult.TIMED_OUT,
            answers(verify = { timedOut }) to GitResult.TIMED_OUT,
            answers(mergeBaseAnswer = GitResult(-1, "", "no git", false)) to "git could not be run: no git",
        )
        for ((script, reason) in cases) assertEquals(reason, ChangesLookup.Refused(reason), reader(FakeGit(script)).read(repo, wt, "main"))
    }

    @Test
    fun nothingChangedIsAnEmptyList() {
        val found = reader(FakeGit(answers(diff = ok(""), others = ok("")))).read(repo, wt, "main") as ChangesLookup.Found
        assertEquals(emptyList<ChangedFile>(), found.files)
    }

    @Test
    fun aBlobIsReadAfterItsSizeIsKnown() {
        val git = FakeGit(answers())
        val read = reader(git).blob(wt, blob, limit = 10) as BlobRead.Loaded
        assertArrayEquals("int a".toByteArray(), read.bytes)
        assertEquals(listOf("cat-file -s $blob @$wt", "cat-file blob $blob @$wt"), git.calls)
    }

    @Test
    fun aBlobOverTheLimitIsNeverRead() {
        val git = FakeGit(answers(size = ok("11\n")))
        assertEquals(BlobRead.TooLarge(11), reader(git).blob(wt, blob, limit = 10))
        assertEquals(listOf("cat-file -s $blob @$wt"), git.calls)
    }

    @Test
    fun aBlobGitCannotGiveWhole() {
        assertEquals(BlobRead.Failed("git gave 5 of 6 bytes"), reader(FakeGit(answers(size = ok("6\n")))).blob(wt, blob, 10))
        assertEquals(BlobRead.Failed("git gave no size"), reader(FakeGit(answers(size = ok("x\n")))).blob(wt, blob, 10))
        assertEquals(BlobRead.Failed(GitResult.TIMED_OUT), reader(FakeGit(answers(size = timedOut))).blob(wt, blob, 10))
        assertEquals(BlobRead.Failed(GitResult.TIMED_OUT), reader(FakeGit(answers(content = timedOut))).blob(wt, blob, 10))
    }

    /** Skein's git runs every call, the bytes ones included, without optional locks. */
    @Test
    fun noOptionalLocksCoversTheBytesToo() {
        val git = FakeGit(answers())
        NoOptionalLocks(git).bytes(wt, "cat-file", "blob", blob)
        assertEquals(listOf("--no-optional-locks cat-file blob $blob @$wt"), git.calls)
    }
}
