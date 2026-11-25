package io.github.v0rest.skein.ui

import io.github.v0rest.skein.claude.SessionSnapshot
import io.github.v0rest.skein.git.GitResult
import io.github.v0rest.skein.git.GitRunner
import io.github.v0rest.skein.git.GitWorktree
import io.github.v0rest.skein.git.WorktreeGit
import io.github.v0rest.skein.paths.PathRules
import io.github.v0rest.skein.paths.PathStyle
import io.github.v0rest.skein.paths.PathStyle.UNIX
import io.github.v0rest.skein.paths.PathStyle.WINDOWS
import io.github.v0rest.skein.process.ProcessProbe
import io.github.v0rest.skein.testutil.FakeProcessProbe
import io.github.v0rest.skein.testutil.Fixture
import io.github.v0rest.skein.testutil.sessionFile
import io.github.v0rest.skein.threads.CloseOffer
import io.github.v0rest.skein.threads.SkeinThread
import io.github.v0rest.skein.threads.ThreadState
import io.github.v0rest.skein.threads.WaitingThreads
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Folders are the system's own ([Fixture.root]), as a thread, a status file and java.nio name them; git's listing
 * writes them as git does ([Fixture.forwardRoot], `C:/w/acme` on Windows).
 */
class WorktreeCloseFlowTest {
    private val repo = Fixture.root("w/acme")
    private val dir = Fixture.root("w/acme/.claude/worktrees/feat-x")

    /** [relative] as `git worktree list` writes it. */
    private fun git(relative: String) = Fixture.forwardRoot(relative)
    private val branch = "worktree-feat-x"
    private val removeEvent = "git -c core.fsmonitor=false worktree remove -- $dir @$repo"
    private val deleteEvent = "git branch -d -- $branch @$repo"

    /** git calls and the question, in the order they happened. */
    private val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val reports = mutableListOf<String>()

    /** Per report: true for a warning, false for an information balloon. */
    private val warnings = mutableListOf<Boolean>()
    private val questions = mutableListOf<Pair<String, String>>()

    private inner class FakeGit(private val answer: (List<String>) -> GitResult, private val label: String = "git") : GitRunner {
        override fun run(dir: Path, vararg args: String): GitResult {
            events += "$label " + args.joinToString(" ") + " @" + dir
            // the scripted answer reads the command after a leading `-c name=value`
            return answer(args.toList().let { if (it.firstOrNull() == "-c") it.drop(2) else it })
        }
    }

    private fun ok(out: String = "") = GitResult(0, out, "", false)

    private fun listing(branchLine: String = "branch refs/heads/$branch") =
        "worktree ${git("w/acme")}\nHEAD 1111111111111111111111111111111111111111\nbranch refs/heads/main\n\n" +
            "worktree ${git("w/acme/.claude/worktrees/feat-x")}\nHEAD 2222222222222222222222222222222222222222\n$branchLine\n\n"

    private fun answers(
        list: String = listing(),
        remove: GitResult = ok(),
        deleteBranch: GitResult = ok(),
        detached: GitResult = ok("0\n"),
        unlock: GitResult = ok(),
    ): (List<String>) -> GitResult = { args ->
        when {
            "unlock" in args -> unlock
            args.take(2) == listOf("worktree", "list") -> ok(list)
            "status" in args -> ok(" M a.kt\n?? b.kt\n")
            args.firstOrNull() == "rev-list" && "--branches" in args -> detached
            args.firstOrNull() == "rev-list" -> ok("1\n")
            args.take(2) == listOf("worktree", "remove") -> remove
            args.take(2) == listOf("branch", "-d") -> deleteBranch
            else -> GitResult(1, "", "unexpected", false)
        }
    }

    private fun thread(worktree: String? = "feat-x", sessionId: String? = "s-1", id: String = "t1", name: String = "feat-x") =
        SkeinThread(id, repo, sessionId, name, ThreadState.Exited, 0, 0, null, worktree = worktree)

    private fun flow(
        answer: (List<String>) -> GitResult = answers(),
        remove: Boolean = true,
        snapshots: MutableStateFlow<SessionSnapshot> = MutableStateFlow(SessionSnapshot.EMPTY),
        exists: (Path) -> Boolean = { true },
        threads: () -> List<SkeinThread> = { emptyList() },
        waiting: () -> WaitingThreads = { WaitingThreads.NONE },
        inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet(),
        removals: WorktreeGit? = null,
        settleMillis: Long = 50,
        unsavedEdits: suspend (Set<String>) -> Boolean = { false },
        insideClaudeHome: (Path) -> Boolean = { false },
        resolve: (String) -> String? = { null },
        probe: ProcessProbe = FakeProcessProbe(),
        style: PathStyle = PathStyle.current,
        ask: suspend (String, String) -> Boolean = { message, title ->
            questions += message to title
            events += "ask"
            remove
        },
    ): WorktreeCloseFlow {
        val git = WorktreeGit(FakeGit(answer), style)
        return WorktreeCloseFlow(
            git = git,
            removals = removals ?: git,
            snapshots = snapshots,
            threads = threads,
            waiting = waiting,
            inFlight = inFlight,
            ask = ask,
            report = { text, warning ->
                reports += text
                warnings += warning
            },
            unsavedEdits = unsavedEdits,
            insideClaudeHome = insideClaudeHome,
            exists = exists,
            resolve = resolve,
            settleMillis = settleMillis,
            probe = probe,
            zone = ZoneOffset.UTC,
            style = style,
        )
    }

    private val reads = listOf(
        "git worktree list --porcelain @$repo",
        "git -c core.fsmonitor=false --no-optional-locks status --porcelain @$dir",
        "git rev-list --count HEAD --not --remotes @$dir",
        "git -c core.fsmonitor=false --no-optional-locks status --porcelain -z --ignored=traditional --untracked-files=all @$dir",
        "ask",
    )

    /** The same reads again after the question, right before the removal. */
    private val rereads = reads.dropLast(1)
    private val detachedCount = "git rev-list --count HEAD --not --branches --tags --remotes @$dir"

    private fun sessionInside(cwd: String = dir) = SessionSnapshot(mapOf(7L to sessionFile(pid = 7, sessionId = "s-1", cwd = cwd)), emptySet())

    @Test
    fun removalRemovesTheWorktreeThenTheBranch() {
        runBlocking { flow().run(thread()) }
        assertEquals(
            reads + rereads + listOf(
                removeEvent,
                deleteEvent,
            ),
            events,
        )
        assertEquals(
            listOf("Worktree feat-x is still there: 2 changed files, 1 commit not pushed. Keep it, or remove the worktree and its branch $branch? Removing it also deletes its ignored files, such as build output and .env." to "Worktree feat-x"),
            questions,
        )
        assertEquals(listOf("Worktree feat-x and its branch $branch were removed"), reports)
        assertEquals(listOf(false), warnings)
    }

    @Test
    fun aRefusedWorktreeRemoveKeepsTheBranch() {
        val refused = GitResult(128, "", "fatal: '$dir' contains modified or untracked files, use --force to delete it\n", false)
        runBlocking { flow(answers(remove = refused)).run(thread()) }
        assertEquals(
            reads + rereads + listOf(
                removeEvent,
            ),
            events,
        )
        assertEquals(listOf("Worktree feat-x was not removed: '$dir' contains modified or untracked files, use --force to delete it"), reports)
        assertEquals(listOf(true), warnings)
    }

    @Test
    fun aRefusedBranchDeleteIsReportedAndNothingIsForced() {
        val refused = GitResult(1, "", "error: the branch '$branch' is not fully merged.\nhint: If you are sure you want to delete it, run 'git branch -D $branch'\n", false)
        runBlocking { flow(answers(deleteBranch = refused)).run(thread()) }
        assertEquals(
            reads + rereads + listOf(
                removeEvent,
                deleteEvent,
            ),
            events,
        )
        assertTrue(events.toString(), events.none { it.contains("--force") || it.contains(" -f ") || it.contains(" -D ") })
        assertEquals(listOf("Worktree feat-x was removed, but Skein kept the branch $branch, it has unmerged commits: the branch '$branch' is not fully merged."), reports)
        assertEquals(listOf(true), warnings)
    }

    @Test
    fun keepLeavesTheWorktreeAndItsBranch() {
        runBlocking { flow(remove = false).run(thread()) }
        assertEquals(reads, events)
        assertEquals(emptyList<String>(), reports)
    }

    /** Every commit of the detached HEAD is held by a branch, a tag or a remote: removing the worktree loses none. */
    @Test
    fun aDetachedWorktreeIsRemovedWithoutTouchingABranch() {
        runBlocking { flow(answers(list = listing(branchLine = "detached"))).run(thread()) }
        val detachedReads = rereads + detachedCount
        assertEquals(detachedReads + "ask" + detachedReads + removeEvent, events)
        assertEquals("Worktree feat-x is still there: 2 changed files, 1 commit not pushed. Keep it, or remove the worktree? Removing it also deletes its ignored files, such as build output and .env.", questions.single().first)
        assertEquals(listOf("Worktree feat-x was removed; its branch is unknown, so none was deleted"), reports)
    }

    /** git removes a detached worktree without a word, and commits nothing else holds would be lost with it. */
    @Test
    fun detachedCommitsNoBranchHoldsKeepTheWorktree() {
        runBlocking { flow(answers(list = listing(branchLine = "detached"), detached = ok("2\n"))).run(thread()) }
        assertEquals(rereads + detachedCount, events)
        assertEquals(listOf("Worktree feat-x is kept: commits that no branch holds"), reports)
        assertEquals(emptyList<Pair<String, String>>(), questions)
    }

    @Test
    fun aDetachedCountGitCannotGiveKeepsTheWorktree() {
        runBlocking { flow(answers(list = listing(branchLine = "detached"), detached = GitResult(128, "", "fatal: bad object HEAD\n", false))).run(thread()) }
        assertEquals(rereads + detachedCount, events)
        assertEquals(listOf("Worktree feat-x is kept: git could not read the worktree"), reports)
    }

    /** With `/.claude/worktrees/` ignored, git status in the outer worktree cannot see an inner one, and the remove would take it along. */
    @Test
    fun aWorktreeHoldingAnotherOneIsKeptOnly() {
        val inner = "worktree ${git("w/acme/.claude/worktrees/feat-x/.claude/worktrees/inner")}\nHEAD 3333333333333333333333333333333333333333\nbranch refs/heads/worktree-inner\n\n"
        runBlocking { flow(answers(list = listing() + inner)).run(thread()) }
        assertEquals(listOf("git worktree list --porcelain @$repo"), events)
        assertEquals(listOf("Worktree feat-x is kept: another worktree lies inside this one"), reports)
    }

    /** Only a worktree below this one's folder counts, not one whose name merely starts the same. */
    @Test
    fun aWorktreeNextToThisOneDoesNotKeepIt() {
        val sibling = "worktree ${git("w/acme/.claude/worktrees/feat-x-2")}\nHEAD 3333333333333333333333333333333333333333\nbranch refs/heads/worktree-feat-x-2\n\n"
        runBlocking { flow(answers(list = listing() + sibling)).run(thread()) }
        assertEquals(reads + rereads + listOf(removeEvent, deleteEvent), events)
    }

    /** git lists real paths: an inner worktree under the resolved repository counts as well. */
    @Test
    fun anInnerWorktreeListedUnderTheRealPathKeepsTheOuterOne() {
        val real = Fixture.root("real/acme")
        val resolve: (String) -> String? = { if (PathRules.same(it, repo)) real else null }
        val list = "worktree ${git("real/acme")}\nHEAD 1111111111111111111111111111111111111111\nbranch refs/heads/main\n\n" +
            "worktree ${git("real/acme/.claude/worktrees/feat-x")}\nHEAD 2222222222222222222222222222222222222222\nbranch refs/heads/$branch\n\n" +
            "worktree ${git("real/acme/.claude/worktrees/feat-x/.claude/worktrees/inner")}\nHEAD 3333333333333333333333333333333333333333\ndetached\n\n"
        runBlocking { flow(answers(list = list), resolve = resolve).run(thread()) }
        assertEquals(listOf("Worktree feat-x is kept: another worktree lies inside this one"), reports)
    }

    @Test
    fun aWorktreeMadeInsideWhileAskingStopsTheRemove() {
        var list = listing()
        val normal = answers()
        val answer: (List<String>) -> GitResult = { args -> if (args.take(2) == listOf("worktree", "list")) ok(list) else normal(args) }
        val flow = flow(
            answer,
            ask = { _, _ ->
                events += "ask"
                list = listing() + "worktree ${git("w/acme/.claude/worktrees/feat-x/.claude/worktrees/inner")}\nHEAD 3333333333333333333333333333333333333333\ndetached\n\n"
                true
            },
        )
        runBlocking { flow.run(thread()) }
        assertEquals(reads + "git worktree list --porcelain @$repo", events)
        assertEquals(listOf("Worktree feat-x is kept: another worktree lies inside this one"), reports)
    }

    /** Nothing happens between the last look for unsaved edits and the remove: it comes after git has been asked again. */
    @Test
    fun theUnsavedCheckIsTheLastStepBeforeTheRemove() {
        runBlocking {
            flow(unsavedEdits = {
                events += "unsaved?"
                false
            }).run(thread())
        }
        assertEquals(rereads + "unsaved?" + "ask" + rereads + "unsaved?" + removeEvent + deleteEvent, events)
    }

    /**
     * Windows on any system: the thread names its repository as the IDE does (`C:/w/acme`), git lists the worktree as
     * `C:/w/acme/.claude/worktrees/feat-x`. That is the worktree the question is about, and the one git removes, by
     * the path the system writes.
     */
    @Test
    fun aWindowsCloseFindsTheWorktreeGitListsWithForwardSlashes() {
        val list = "worktree C:/w/acme\nHEAD 1111\nbranch refs/heads/main\n\nworktree C:/w/acme/.claude/worktrees/feat-x\nHEAD 2222\nbranch refs/heads/$branch\n\n"
        val thread = thread().copy(folder = "C:/w/acme")
        runBlocking { flow(answers(list = list), style = WINDOWS).run(thread) }
        val repo = "C:\\w\\acme"
        val dir = "C:\\w\\acme\\.claude\\worktrees\\feat-x"
        assertEquals(
            listOf(
                "git worktree list --porcelain @$repo",
                "git -c core.fsmonitor=false --no-optional-locks status --porcelain @$dir",
                "git rev-list --count HEAD --not --remotes @$dir",
                "git -c core.fsmonitor=false --no-optional-locks status --porcelain -z --ignored=traditional --untracked-files=all @$dir",
                "ask",
            ),
            events.take(5),
        )
        assertEquals("git -c core.fsmonitor=false worktree remove -- $dir @$repo", events.dropLast(1).last())
        assertEquals(listOf("Worktree feat-x and its branch $branch were removed"), reports)
    }

    @Test
    fun aGoneWorktreeAsksNothing() {
        runBlocking { flow(exists = { false }).run(thread()) }
        assertEquals(emptyList<String>(), events)
        assertEquals(emptyList<String>(), reports)
    }

    @Test
    fun aSessionStillInsideOnlyAllowsKeeping() {
        runBlocking { flow(snapshots = MutableStateFlow(sessionInside(Fixture.root("w/acme/.claude/worktrees/feat-x/src")))).run(thread()) }
        assertEquals(emptyList<String>(), events)
        assertEquals(listOf("Worktree feat-x is kept: another session is running in this worktree"), reports)
    }

    @Test
    fun theClosedThreadsOwnClaudeIsGivenTimeToExit() {
        val snapshots = MutableStateFlow(sessionInside())
        runBlocking {
            launch {
                delay(100)
                snapshots.value = SessionSnapshot.EMPTY
            }
            flow(snapshots = snapshots, settleMillis = 10_000).run(thread())
        }
        assertEquals(reads + rereads + listOf(removeEvent, deleteEvent), events)
    }

    /** The status file may name the repository rather than the worktree: the thread's own session is awaited by its id. */
    @Test
    fun theClosedThreadsOwnSessionIsAwaitedWhateverItsCwd() {
        val snapshots = MutableStateFlow(SessionSnapshot(mapOf(7L to sessionFile(pid = 7, sessionId = "s-1", cwd = repo)), emptySet()))
        runBlocking {
            launch {
                delay(200)
                events += "own claude gone"
                snapshots.value = SessionSnapshot.EMPTY
            }
            flow(snapshots = snapshots, settleMillis = 10_000).run(thread())
        }
        assertEquals(
            listOf("own claude gone") + reads + rereads + listOf(removeEvent, deleteEvent),
            events,
        )
    }

    @Test
    fun anotherThreadInTheSameWorktreeKeepsItKeepOnly() {
        val sibling = thread(id = "t2", sessionId = null)
        val elsewhere = thread(id = "t3", sessionId = null, worktree = "feat-y", name = "feat-y")
        // the closed thread itself may still be in the list until the next publish
        runBlocking { flow(threads = { listOf(thread(), sibling, elsewhere) }).run(thread()) }
        assertEquals(emptyList<String>(), events)
        assertEquals(listOf("Worktree feat-x is kept: another Skein thread uses this worktree"), reports)

        events.clear()
        runBlocking { flow(threads = { listOf(thread(), elsewhere) }, remove = false).run(thread()) }
        assertEquals(reads, events)
    }

    @Test
    fun aWorktreeRemovedWhileAskingNeedsNoMoreWork() {
        var present = true
        val flow = flow(
            exists = { present },
            ask = { _, _ ->
                events += "ask"
                present = false
                true
            },
        )
        runBlocking { flow.run(thread()) }
        assertEquals(reads, events)
        assertEquals(emptyList<String>(), reports)
    }

    @Test
    fun aRefusedRemoveOfAGoneWorktreeIsReported() {
        val refused = GitResult(128, "", "fatal: '$dir' is not a working tree\n", false)
        runBlocking { flow(answers(remove = refused)).run(thread()) }
        assertEquals(reads + rereads + listOf(removeEvent), events)
        assertEquals(listOf("Worktree feat-x was not removed: '$dir' is not a working tree"), reports)
    }

    /** Bounded: a regression that never asks would leave the second close waiting for the first one's question forever. */
    @Test(timeout = 30_000)
    fun aSecondCloseOfTheSameWorktreeDoesNotAskAgain() {
        val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val asked = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<Boolean>()
        runBlocking {
            val first = launch {
                val flow = flow(
                    inFlight = inFlight,
                    ask = { _, _ ->
                        events += "ask"
                        asked.complete(Unit)
                        answer.await()
                    },
                )
                flow.run(thread())
            }
            asked.await()
            flow(inFlight = inFlight).run(thread(id = "t2", sessionId = "s-2"))
            events += "second close done"
            answer.complete(false)
            first.join()
        }
        assertEquals(reads + listOf("second close done"), events)
        assertTrue(inFlight.isEmpty())
    }

    @Test
    fun removalsRunOnTheirOwnGit() {
        val slow = WorktreeGit(FakeGit(answers(), label = "slow git"))
        runBlocking { flow(removals = slow).run(thread()) }
        assertEquals(
            reads + rereads + listOf("slow $removeEvent", "slow $deleteEvent"),
            events,
        )
    }

    @Test
    fun aSessionStartedWhileTheQuestionWasOpenKeepsTheWorktree() {
        val snapshots = MutableStateFlow(SessionSnapshot.EMPTY)
        val flow = flow(
            snapshots = snapshots,
            ask = { _, _ ->
                events += "ask"
                snapshots.value = sessionInside()
                true
            },
        )
        runBlocking { flow.run(thread()) }
        assertEquals(reads, events)
        assertEquals(listOf("Worktree feat-x is kept: another session is running in this worktree"), reports)
    }

    @Test
    fun aThreadOutsideAWorktreeOrWithAnInvalidNameIsLeftAlone() {
        runBlocking {
            flow().run(thread(worktree = null))
            flow().run(thread(worktree = ".."))
        }
        // not a worktree thread, or a name that must never become a path: nothing at all
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun liveInsideIsAnySessionInTheWorktreeOrBelowIt() {
        val repo = "/w/acme"
        val dir = "/w/acme/.claude/worktrees/feat-x"
        val repositories = setOf(repo)
        assertTrue(WorktreeCloseFlow.liveInside(sessionInside(dir), repositories, "feat-x", UNIX))
        assertTrue(WorktreeCloseFlow.liveInside(sessionInside("$dir/src/"), repositories, "feat-x", UNIX))
        assertTrue(WorktreeCloseFlow.liveInside(sessionInside("$dir/.claude/worktrees/inner"), repositories, "feat-x", UNIX))
        assertFalse(WorktreeCloseFlow.liveInside(sessionInside("$dir-2"), repositories, "feat-x", UNIX))
        assertFalse(WorktreeCloseFlow.liveInside(sessionInside(dir), repositories, "feat-y", UNIX))
        assertFalse(WorktreeCloseFlow.liveInside(sessionInside(repo), repositories, "feat-x", UNIX))
        assertFalse(WorktreeCloseFlow.liveInside(sessionInside("/w/beta/.claude/worktrees/feat-x"), repositories, "feat-x", UNIX))
        // the repository as the status file reports it, resolved
        assertTrue(WorktreeCloseFlow.liveInside(sessionInside("/real/acme/.claude/worktrees/feat-x"), setOf(repo, "/real/acme"), "feat-x", UNIX))
        // a cwd that may lie inside counts, an empty one does not
        assertTrue(WorktreeCloseFlow.liveInside(sessionInside("/w/acme/.claude/worktrees/x/../feat-x"), repositories, "feat-x", UNIX))
        assertFalse(WorktreeCloseFlow.liveInside(sessionInside(""), repositories, "feat-x", UNIX))
    }

    /** A Windows status file names its cwd with backslashes, the thread may name the repository as the IDE does. */
    @Test
    fun liveInsideTakesTheShapesOfWindows() {
        val repositories = setOf("C:/w/acme")
        assertTrue(WorktreeCloseFlow.liveInside(sessionInside("C:\\w\\acme\\.claude\\worktrees\\feat-x"), repositories, "feat-x", WINDOWS))
        assertTrue(WorktreeCloseFlow.liveInside(sessionInside("c:\\W\\ACME\\.Claude\\Worktrees\\Feat-X\\src"), repositories, "feat-x", WINDOWS))
        assertFalse(WorktreeCloseFlow.liveInside(sessionInside("C:\\w\\acme\\.claude\\worktrees\\feat-x-2"), repositories, "feat-x", WINDOWS))
        assertFalse(WorktreeCloseFlow.liveInside(sessionInside("C:\\w\\acme"), repositories, "feat-x", WINDOWS))
        assertFalse(WorktreeCloseFlow.liveInside(sessionInside("D:\\w\\acme\\.claude\\worktrees\\feat-x"), repositories, "feat-x", WINDOWS))
        // Win32 would open `feat-x.` as `feat-x`: it may be this worktree, so it holds it
        assertTrue(WorktreeCloseFlow.liveInside(sessionInside("C:\\w\\acme\\.claude\\worktrees\\feat-x."), repositories, "feat-x", WINDOWS))
        // on Unix the case counts
        assertFalse(WorktreeCloseFlow.liveInside(sessionInside("/w/ACME/.claude/worktrees/feat-x"), setOf("/w/acme"), "feat-x", UNIX))
    }

    /**
     * A claude started from a subst drive (or through a junction) keeps that spelling as its cwd: by its real path it is
     * in the worktree all the same, and either reading holds the worktree.
     */
    @Test
    fun aSessionIsInsideByItsRealPathToo() {
        val repositories = setOf("C:\\work\\acme")
        val cwd = sessionInside("W:\\acme\\.claude\\worktrees\\feat-x\\src")
        val real: (String) -> String? = { if (it.startsWith("W:")) "C:\\work" + it.removePrefix("W:") else null }
        assertFalse(WorktreeCloseFlow.liveInside(cwd, repositories, "feat-x", WINDOWS))
        assertTrue(WorktreeCloseFlow.liveInside(cwd, repositories, "feat-x", WINDOWS, real))
        assertFalse(WorktreeCloseFlow.liveInside(cwd, repositories, "feat-y", WINDOWS, real))
        // the cwd as written still counts when its real path says otherwise
        assertTrue(WorktreeCloseFlow.liveInside(sessionInside("C:\\work\\acme\\.claude\\worktrees\\feat-x"), repositories, "feat-x", WINDOWS, resolve = { "D:\\elsewhere" }))
        assertTrue(WorktreeCloseFlow.liveInside(sessionInside("/link/acme/.claude/worktrees/feat-x"), setOf("/w/acme"), "feat-x", UNIX, resolve = { "/w/acme/.claude/worktrees/feat-x" }))
        // a subst drive keeps `W:` in its real path too: the disk's own word holds then
        val same = { dir: String, c: String -> dir == "C:\\work\\acme\\.claude\\worktrees\\feat-x" && c.startsWith("W:") }
        assertTrue(WorktreeCloseFlow.liveInside(cwd, repositories, "feat-x", WINDOWS, { null }, same))
        assertFalse(WorktreeCloseFlow.liveInside(cwd, repositories, "feat-y", WINDOWS, { null }, same))
    }

    @Test
    fun theWorktreeIsFoundUnderTheRepositoryOrItsResolvedPath() {
        val repo = "/w/acme"
        val worktrees = listOf(
            GitWorktree("/real/acme", "main"),
            GitWorktree("/real/acme/.claude/worktrees/feat-x", branch),
            GitWorktree("/w/beta/.claude/worktrees/feat-y", "worktree-feat-y"),
        )
        assertEquals(GitWorktree("/real/acme/.claude/worktrees/feat-x", branch), WorktreeCloseFlow.entryOf(worktrees, setOf(repo, "/real/acme"), "feat-x", UNIX))
        assertNull(WorktreeCloseFlow.entryOf(worktrees, setOf(repo), "feat-x", UNIX))
        assertNull(WorktreeCloseFlow.entryOf(worktrees, setOf(repo, "/real/acme"), "feat-y", UNIX))
    }

    /**
     * git's paths come in as the system writes them ([WorktreeGit.parseList]), the thread names the repository as the
     * IDE does: on Windows one folder all the same, in any case. The lookup is strict - a name Win32 would trim
     * (`feat-x.`) is not the folder `feat-x` - and Skein does not take such a name for a worktree there anyway.
     */
    @Test
    fun theWorktreeIsFoundInAnyWindowsShape() {
        val worktrees = WorktreeGit.parseList(
            "worktree C:/w/acme\nbranch refs/heads/main\n\nworktree C:/w/acme/.claude/worktrees/Feat-X\nbranch refs/heads/$branch\n\n" +
                "worktree C:/w/acme/.claude/worktrees/task-1.\nbranch refs/heads/worktree-task-1.\n\n",
            WINDOWS,
        )
        val entry = GitWorktree("C:\\w\\acme\\.claude\\worktrees\\Feat-X", branch)
        assertEquals(entry, WorktreeCloseFlow.entryOf(worktrees, setOf("C:/w/acme"), "feat-x", WINDOWS))
        assertEquals(entry, WorktreeCloseFlow.entryOf(worktrees, setOf("c:\\w\\acme\\"), "feat-x", WINDOWS))
        assertNull(WorktreeCloseFlow.entryOf(worktrees, setOf("C:/w/acme2"), "feat-x", WINDOWS))
        assertNull(WorktreeCloseFlow.entryOf(worktrees, setOf("C:/w/acme"), "task-1", WINDOWS))
        // the same texts on Unix name other folders
        assertNull(WorktreeCloseFlow.entryOf(worktrees, setOf("C:/w/acme"), "feat-x", UNIX))
    }

    /**
     * A worktree git lists below this one keeps it, in any Windows shape; this one's own entry and one next to it do
     * not, and a listed path the text cannot place for sure counts as below it.
     */
    @Test
    fun anotherWorktreeInsideIsSeenInAnyWindowsShape() {
        fun listed(vararg paths: String) = paths.map { GitWorktree(it, null, detached = true) }
        val repositories = setOf("C:/w/acme")
        val own = "C:\\w\\acme\\.claude\\worktrees\\feat-x"
        assertFalse(WorktreeCloseFlow.holdsAnother(listed("C:\\w\\acme", own, "$own-2"), repositories, "feat-x", WINDOWS))
        assertFalse(WorktreeCloseFlow.holdsAnother(listed("c:\\W\\ACME\\.claude\\worktrees\\FEAT-X"), repositories, "feat-x", WINDOWS))
        assertTrue(WorktreeCloseFlow.holdsAnother(listed("$own\\.claude\\worktrees\\inner"), repositories, "feat-x", WINDOWS))
        assertTrue(WorktreeCloseFlow.holdsAnother(listed("C:\\w\\acme\\.Claude\\Worktrees\\Feat-X\\sub\\inner"), repositories, "feat-x", WINDOWS))
        assertTrue(WorktreeCloseFlow.holdsAnother(listed("$own\\inner."), repositories, "feat-x", WINDOWS))
        // git may list `feat-x.` (made through git with its dot), which Win32 opens as this folder: the lookup does not
        // take it for this one, and it keeps this one all the same
        assertTrue(WorktreeCloseFlow.holdsAnother(listed("$own."), repositories, "feat-x", WINDOWS))
        assertNull(WorktreeCloseFlow.entryOf(listed("$own."), repositories, "feat-x", WINDOWS))
        assertFalse(WorktreeCloseFlow.holdsAnother(listed("D:\\w\\acme\\.claude\\worktrees\\feat-x\\inner"), repositories, "feat-x", WINDOWS))
        // Unix: a `..` below may lead anywhere, and so holds it; its own entry does not
        assertTrue(WorktreeCloseFlow.holdsAnother(listed("/w/acme/.claude/worktrees/feat-x/a/.."), setOf("/w/acme"), "feat-x", UNIX))
        assertFalse(WorktreeCloseFlow.holdsAnother(listed("/w/acme/.claude/worktrees/feat-x"), setOf("/w/acme"), "feat-x", UNIX))
    }

    /** Another thread in the same worktree, whichever Windows shape it names the repository by. */
    @Test
    fun anotherThreadInTheWorktreeIsSeenInAnyWindowsShape() {
        fun other(folder: String, worktree: String = "feat-x") = SkeinThread("t2", folder, null, "t2", ThreadState.Idle, 0, 0, null, worktree = worktree)
        val repositories = setOf("C:\\w\\acme")
        assertTrue(WorktreeCloseFlow.sharedWithThread(listOf(other("C:/w/acme")), "t1", repositories, "feat-x", style = WINDOWS))
        assertTrue(WorktreeCloseFlow.sharedWithThread(listOf(other("c:\\W\\Acme\\", "Feat-X")), "t1", repositories, "feat-x", style = WINDOWS))
        assertTrue(WorktreeCloseFlow.sharedWithThread(listOf(other("C:/link/acme")), "t1", repositories, "feat-x", { "C:\\w\\acme" }, WINDOWS))
        assertFalse(WorktreeCloseFlow.sharedWithThread(listOf(other("C:/w/acme", "feat-y")), "t1", repositories, "feat-x", style = WINDOWS))
        assertFalse(WorktreeCloseFlow.sharedWithThread(listOf(other("C:/w/acme2")), "t1", repositories, "feat-x", style = WINDOWS))
        assertFalse(WorktreeCloseFlow.sharedWithThread(listOf(other("C:/w/acme").copy(id = "t1")), "t1", repositories, "feat-x", style = WINDOWS))
        assertFalse(WorktreeCloseFlow.sharedWithThread(listOf(other("")), "t1", repositories, "feat-x", style = WINDOWS))
        // Win32 would open `acme.` as `acme`: that thread may use this worktree, so it keeps it
        assertTrue(WorktreeCloseFlow.sharedWithThread(listOf(other("C:/w/acme.")), "t1", repositories, "feat-x", style = WINDOWS))
        // a thread in a worktree nested inside this one uses this one too, not the other way round
        assertTrue(WorktreeCloseFlow.sharedWithThread(listOf(other("C:\\w\\acme\\.claude\\worktrees\\feat-x", "inner")), "t1", repositories, "feat-x", style = WINDOWS))
        assertFalse(
            WorktreeCloseFlow.sharedWithThread(listOf(other("C:\\w\\acme", "feat-x")), "t1", setOf("C:\\w\\acme\\.claude\\worktrees\\feat-x"), "inner", style = WINDOWS),
        )
        assertTrue(WorktreeCloseFlow.sharedWithThread(listOf(other("/w/acme/.claude/worktrees/feat-x", "inner")), "t1", setOf("/w/acme"), "feat-x", style = UNIX))
        assertFalse(WorktreeCloseFlow.sharedWithThread(listOf(other("/w/acme", "feat-x")), "t1", setOf("/w/acme/.claude/worktrees/feat-x"), "inner", style = UNIX))
    }

    /** One worktree is asked about once, whichever shape of its path each close names. */
    @Test
    fun theCloseFlowsShareOneKeyPerWorktreeFolder() {
        assertEquals(WorktreeCloseFlow.flightKey("C:/w/acme", "feat-x", WINDOWS), WorktreeCloseFlow.flightKey("c:\\W\\ACME\\", "Feat-X", WINDOWS))
        assertFalse(WorktreeCloseFlow.flightKey("C:/w/acme", "feat-x", WINDOWS) == WorktreeCloseFlow.flightKey("C:/w/acme", "feat-y", WINDOWS))
        assertEquals("/w/acme/.claude/worktrees/feat-x", WorktreeCloseFlow.flightKey("/w/acme/", "feat-x", UNIX))
        assertFalse(WorktreeCloseFlow.flightKey("/w/acme", "feat-x", UNIX) == WorktreeCloseFlow.flightKey("/w/ACME", "feat-x", UNIX))
    }

    @Test
    fun theQuestionSaysWhatIsLeft() {
        assertEquals(
            "Worktree feat-x is still there: 3 changed files, 2 commits not pushed. Keep it, or remove the worktree and its branch $branch? Removing it also deletes its ignored files, such as build output and .env.",
            WorktreeCloseText.message("feat-x", CloseOffer.KeepOrRemove(3, 2), branch),
        )
        assertEquals(
            "Worktree feat-x is still there: 1 changed file, 0 commits not pushed. Keep it, or remove the worktree and its branch $branch? Removing it also deletes its ignored files, such as build output and .env.",
            WorktreeCloseText.message("feat-x", CloseOffer.KeepOrRemove(1, 0), branch),
        )
        assertEquals("Worktree feat-x", WorktreeCloseText.title("feat-x"))
    }

    /** The question names the branch Remove would delete; with no branch known, it offers the worktree alone. */
    @Test
    fun theQuestionNamesTheBranchItWouldDelete() {
        assertEquals(
            "Worktree task-2 is still there: 0 changed files, 0 commits not pushed. Keep it, or remove the worktree and its branch worktree-task-2? Removing it also deletes its ignored files, such as build output and .env.",
            WorktreeCloseText.message("task-2", CloseOffer.KeepOrRemove(0, 0), "worktree-task-2"),
        )
        assertEquals(
            "Worktree task-2 is still there: 0 changed files, 0 commits not pushed. Keep it, or remove the worktree? Removing it also deletes its ignored files, such as build output and .env.",
            WorktreeCloseText.message("task-2", CloseOffer.KeepOrRemove(0, 0), null),
        )
    }

    /** An edit still only in an editor is invisible to git: Remove would lose it, so it is not offered. */
    @Test
    fun unsavedEditorChangesKeepTheWorktree() {
        val looked = mutableListOf<Set<String>>()
        runBlocking {
            flow(unsavedEdits = { dirs ->
                looked += dirs
                true
            }).run(thread())
        }
        assertEquals(reads.dropLast(1), events)
        assertEquals(listOf("Worktree feat-x is kept: unsaved changes in the editor"), reports)
        assertTrue(looked.toString(), looked.isNotEmpty() && looked.all { dir in it })
    }

    @Test
    fun anEditMadeWhileAskingStopsTheRemove() {
        var edited = false
        val flow = flow(
            unsavedEdits = { edited },
            ask = { _, _ ->
                events += "ask"
                edited = true
                true
            },
        )
        runBlocking { flow.run(thread()) }
        assertEquals(reads + rereads, events)
        assertEquals(listOf("Worktree feat-x is kept: unsaved changes in the editor"), reports)
    }

    /** A dotfiles repository around the Claude home: git is never asked anything there, and Skein says why it keeps the worktree. */
    @Test
    fun aWorktreeInsideTheClaudeHomeIsLeftAlone() {
        val checked = mutableListOf<Path>()
        runBlocking {
            flow(insideClaudeHome = {
                checked.add(it)
                true
            }).run(thread())
        }
        assertEquals(emptyList<String>(), events)
        assertEquals(listOf("Worktree feat-x is kept: Skein leaves worktrees alone in a repository that contains the Claude home"), reports)
        assertEquals(listOf(false), warnings)
        assertEquals(listOf(Path.of(repo)), checked)

        // a worktree already gone needs no word
        reports.clear()
        runBlocking { flow(insideClaudeHome = { true }, exists = { false }).run(thread()) }
        assertEquals(emptyList<String>(), events)
        assertEquals(emptyList<String>(), reports)
    }

    /** A file opened through a link to the repository has a path outside the worktree; its canonical path is inside. */
    @Test
    fun anUnsavedFileOpenedThroughALinkCounts() {
        val dir = "/w/acme/.claude/worktrees/feat-x"
        val dirs = setOf(dir, "/real/acme/.claude/worktrees/feat-x")
        val elsewhere = "/w/acme/a.kt" to "/w/acme/a.kt"
        // each unsaved document as (path, canonical path)
        assertTrue(WorktreeCloseFlow.anyInWorktree(listOf(elsewhere, "$dir/a.kt" to null), dirs, UNIX))
        assertTrue(WorktreeCloseFlow.anyInWorktree(listOf(elsewhere, "/link/acme/.claude/worktrees/feat-x/a.kt" to "/real/acme/.claude/worktrees/feat-x/a.kt"), dirs, UNIX))
        assertTrue(WorktreeCloseFlow.anyInWorktree(listOf("/link/to/a.kt" to "$dir/src/a.kt"), dirs, UNIX))
        assertFalse(WorktreeCloseFlow.anyInWorktree(listOf("/link/to/a.kt" to null), dirs, UNIX))
        assertFalse(WorktreeCloseFlow.anyInWorktree(listOf("$dir-2/a.kt" to "$dir-2/a.kt", elsewhere), dirs, UNIX))
        assertFalse(WorktreeCloseFlow.anyInWorktree(emptyList(), dirs, UNIX))
        // a path that may lie in the worktree counts: a remove would lose its edit
        assertTrue(WorktreeCloseFlow.anyInWorktree(listOf("/w/acme/.claude/worktrees/x/../feat-x/a.kt" to null), dirs, UNIX))
    }

    /** On Windows an editor's file is `C:/w/...` as the IDE writes it, while the worktree's folders have backslashes. */
    @Test
    fun anUnsavedFileCountsInAnyWindowsShape() {
        val dirs = setOf("C:\\w\\acme\\.claude\\worktrees\\feat-x")
        assertTrue(WorktreeCloseFlow.anyInWorktree(listOf("C:/w/acme/.claude/worktrees/feat-x/a.kt" to null), dirs, WINDOWS))
        assertTrue(WorktreeCloseFlow.anyInWorktree(listOf("c:/w/ACME/.claude/worktrees/Feat-X/src/a.kt" to null), dirs, WINDOWS))
        assertTrue(WorktreeCloseFlow.anyInWorktree(listOf("C:/link/a.kt" to "C:/w/acme/.claude/worktrees/feat-x/a.kt"), dirs, WINDOWS))
        assertFalse(WorktreeCloseFlow.anyInWorktree(listOf("C:/w/acme/a.kt" to "C:/w/acme/a.kt"), dirs, WINDOWS))
        assertFalse(WorktreeCloseFlow.anyInWorktree(listOf("C:/w/acme/.claude/worktrees/feat-x-2/a.kt" to null), dirs, WINDOWS))
    }

    @Test
    fun aGitThatCannotReadTheWorktreeOffersKeepOnly() {
        val normal = answers()
        val failing: (List<String>) -> GitResult = { args -> if ("status" in args) GitResult(-1, "", "", true) else normal(args) }
        runBlocking { flow(failing).run(thread()) }
        assertEquals(listOf("git worktree list --porcelain @$repo", "git -c core.fsmonitor=false --no-optional-locks status --porcelain @$dir"), events)
        assertEquals(listOf("Worktree feat-x is kept: git could not read the worktree"), reports)
    }

    /** A folder git does not list as a worktree has no state of its own: nothing is read there, and nothing is removed. */
    @Test
    fun aFolderGitDoesNotListAsAWorktreeOffersKeepOnly() {
        val mainOnly = "worktree $repo\nHEAD 1111111111111111111111111111111111111111\nbranch refs/heads/main\n\n"
        runBlocking { flow(answers(list = mainOnly)).run(thread()) }
        assertEquals(listOf("git worktree list --porcelain @$repo"), events)
        assertEquals(listOf("Worktree feat-x is kept: git could not read the worktree"), reports)
    }

    /** A thread resumed from the History names the real path; one started from the project may name a link to it. */
    @Test
    fun aSiblingThreadOnAnotherPathToTheRepositoryKeepsTheWorktree() {
        val real = Fixture.root("real/acme")
        val resolve: (String) -> String? = { if (PathRules.same(it, repo) || PathRules.same(it, real)) real else null }
        val sibling = thread(id = "t2", sessionId = null)
        runBlocking { flow(threads = { listOf(sibling) }, resolve = resolve).run(thread().copy(folder = real)) }
        assertEquals(emptyList<String>(), events)
        assertEquals(listOf("Worktree feat-x is kept: another Skein thread uses this worktree"), reports)
    }

    /** Bounded, as [aSecondCloseOfTheSameWorktreeDoesNotAskAgain]. */
    @Test(timeout = 30_000)
    fun aSecondCloseThroughAnotherPathToTheRepositoryDoesNotAskAgain() {
        val real = Fixture.root("real/acme")
        val resolve: (String) -> String? = { if (PathRules.same(it, repo) || PathRules.same(it, real)) real else null }
        val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val asked = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<Boolean>()
        runBlocking {
            val first = launch {
                val flow = flow(
                    inFlight = inFlight,
                    resolve = resolve,
                    ask = { _, _ ->
                        events += "ask"
                        asked.complete(Unit)
                        answer.await()
                    },
                )
                flow.run(thread())
            }
            asked.await()
            flow(inFlight = inFlight, resolve = resolve).run(thread(id = "t2", sessionId = "s-2").copy(folder = real))
            events += "second close done"
            answer.complete(false)
            first.join()
        }
        assertEquals(reads + listOf("second close done"), events)
        assertTrue(inFlight.isEmpty())
    }

    /** Another session, in a worktree nested inside this one, runs inside this one too. */
    @Test
    fun aSessionInANestedWorktreeKeepsTheOuterOne() {
        val nested = sessionFile(pid = 8, sessionId = "s-9", cwd = Fixture.root("w/acme/.claude/worktrees/feat-x/.claude/worktrees/inner"))
        runBlocking { flow(snapshots = MutableStateFlow(SessionSnapshot(mapOf(8L to nested), emptySet()))).run(thread()) }
        assertEquals(emptyList<String>(), events)
        assertEquals(listOf("Worktree feat-x is kept: another session is running in this worktree"), reports)
    }

    @Test
    fun theRefusalIsGitsFirstLine() {
        assertEquals(
            "the branch 'x' is not fully merged.",
            GitResult.firstLine("error: the branch 'x' is not fully merged.\nhint: If you are sure you want to delete it, run 'git branch -D x'\n"),
        )
        assertEquals("'/w/a' is not a working tree", GitResult.firstLine("\n  fatal: '/w/a' is not a working tree\n"))
        assertEquals("git gave no reason", GitResult.firstLine(""))
        assertEquals("git gave no reason", GitResult.firstLine(" \n"))
    }

    private val claudeLock = "locked claude session feat-x (pid 4242 start Tue Nov 10 08:30:41 2026)"
    private val unlockEvent = "git -c core.fsmonitor=false --no-optional-locks worktree unlock -- $dir @$repo"
    private val listEvent = "git worktree list --porcelain @$repo"
    private val otherClaudeLock = "locked claude session feat-x (pid 4343 start Tue Nov 10 09:15:02 2026)"

    /** [answers], with the listing's lock line from [lockLine] at each `worktree list`, counted from 0. */
    private fun withLocks(lockLine: (call: Int) -> String?, remove: GitResult = ok()): (List<String>) -> GitResult {
        val normal = answers(remove = remove)
        var calls = 0
        return { args ->
            if (args.take(2) == listOf("worktree", "list")) {
                val line = lockLine(calls++)
                ok(listing("branch refs/heads/$branch" + (line?.let { "\n$it" } ?: "")))
            } else {
                normal(args)
            }
        }
    }

    /** Claude Code left its lock when the thread's claude ended: Remove lifts it right before git's plain remove. */
    @Test
    fun closingLiftsTheLockTheEndedClaudeLeftAfterRemove() {
        runBlocking { flow(answers(list = listing("branch refs/heads/$branch\n$claudeLock"))).run(thread()) }
        assertEquals(reads + rereads + listOf(listEvent, unlockEvent, removeEvent, deleteEvent), events)
        assertEquals(
            "Worktree feat-x is still there: 2 changed files, 1 commit not pushed. Keep it, or remove the worktree and its branch $branch? " +
                "${WorktreeCloseText.LIFTS_LOCK} Removing it also deletes its ignored files, such as build output and .env.",
            questions.single().first,
        )
        assertEquals(listOf("Worktree feat-x and its branch $branch were removed"), reports)
    }

    @Test
    fun keepLeavesTheLockAsItIs() {
        runBlocking { flow(answers(list = listing("branch refs/heads/$branch\n$claudeLock")), remove = false).run(thread()) }
        assertEquals(reads, events)
    }

    /** Closing never forces: git's refusal for changed files ends it, and the report says the lock is lifted. */
    @Test
    fun aRefusalAfterTheLiftKeepsTheWorktreeAndSaysTheLockIsLifted() {
        val refused = GitResult(128, "", "fatal: '$dir' contains modified or untracked files, use --force to delete it\n", false)
        runBlocking { flow(answers(list = listing("branch refs/heads/$branch\n$claudeLock"), remove = refused)).run(thread()) }
        assertEquals(reads + rereads + listOf(listEvent, unlockEvent, removeEvent), events)
        assertEquals(listOf(WorktreeCloseText.notRemovedLockLifted("feat-x", "'$dir' contains modified or untracked files, use --force to delete it")), reports)
        assertEquals(listOf(true), warnings)
    }

    /** The claude of another session still runs under that lock: the worktree is kept, and the lock too. */
    @Test
    fun aLiveClaudesLockKeepsTheWorktree() {
        val probe = FakeProcessProbe().apply { add(4242, null, Instant.parse("2026-11-10T08:30:41.500Z"), listOf("/w/bin/claude")) }
        runBlocking { flow(answers(list = listing("branch refs/heads/$branch\n$claudeLock")), probe = probe).run(thread()) }
        assertEquals(rereads, events)
        assertEquals(listOf("Worktree feat-x is kept: Claude Code's lock on it names pid 4242, which still runs and may be the claude that locked it"), reports)
        assertEquals(emptyList<Pair<String, String>>(), questions)
    }

    /** Stale lock A when the question was asked, another stale lock B right after it: B is not lifted, git refuses. */
    @Test
    fun closingDoesNotLiftALockThatChangedWhileTheQuestionWasOpen() {
        val locked = GitResult(128, "", "fatal: cannot remove a locked working tree, lock reason: claude session feat-x (pid 4343 start Tue Nov 10 09:15:02 2026)\nuse 'remove -f -f' to override or unlock first\n", false)
        runBlocking { flow(withLocks({ if (it < 1) claudeLock else otherClaudeLock }, remove = locked)).run(thread()) }
        assertEquals(reads + rereads + removeEvent, events)
        assertTrue(events.none { it == unlockEvent })
        assertEquals(listOf("Worktree feat-x was not removed: cannot remove a locked working tree, lock reason: claude session feat-x (pid 4343 start Tue Nov 10 09:15:02 2026)"), reports)
    }

    /** git lists the worktree once more right before the lift: a lock that changed after the checks stays, nothing goes. */
    @Test
    fun closingDoesNotLiftALockThatChangedRightBeforeTheLift() {
        for (atTheLift in listOf(otherClaudeLock, null)) {
            events.clear()
            reports.clear()
            runBlocking { flow(withLocks({ if (it < 2) claudeLock else atTheLift })).run(thread()) }
            assertEquals(reads + rereads + listEvent, events)
            assertEquals(listOf(WorktreeCloseText.notRemoved("feat-x", WorktreeCloseText.LOCK_CHANGED)), reports)
        }
    }

    /**
     * A saved thread that waits for its project's trust keeps the worktree it will need, by its folder as written, which
     * the resolver never sees: a workspace file nobody trusted may name a share on another machine there.
     */
    @Test
    fun aThreadWaitingForTrustKeepsTheWorktreeAndItsFolderIsNeverResolved() {
        val seen = mutableListOf<String>()
        val resolve: (String) -> String? = {
            seen += it
            null
        }
        // the repository's own folder, with a separator at its end, so the resolver's calls tell it apart
        val waiting = thread(id = "w1", sessionId = null).copy(folder = "$repo/", state = ThreadState.NotStarted)
        runBlocking { flow(waiting = { WaitingThreads(listOf(waiting)) }, resolve = resolve).run(thread()) }
        assertEquals(emptyList<String>(), events)
        assertEquals(listOf("Worktree feat-x is kept: another Skein thread uses this worktree"), reports)
        assertFalse(seen.toString(), "$repo/" in seen)

        // one in another worktree keeps nothing
        events.clear()
        reports.clear()
        runBlocking { flow(waiting = { WaitingThreads(listOf(waiting.copy(worktree = "feat-y"))) }, remove = false).run(thread()) }
        assertEquals(reads, events)
    }

    /** A waiting thread shares the worktree by its folder as written alone; the resolver never sees that folder. */
    @Test
    fun aWaitingThreadSharesTheWorktreeByItsFolderAsWrittenAlone() {
        fun other(folder: String, worktree: String = "feat-x") = SkeinThread("w2", folder, null, "w2", ThreadState.NotStarted, 0, 0, null, worktree = worktree)
        val seen = mutableListOf<String>()
        val resolve: (String) -> String? = {
            seen += it
            "C:\\w\\acme"
        }
        fun shared(waiting: SkeinThread) =
            WorktreeCloseFlow.sharedWithThread(emptyList(), "t1", setOf("C:\\w\\acme"), "feat-x", resolve, WINDOWS, WaitingThreads(listOf(waiting)))
        assertTrue(shared(other("c:/W/acme/")))
        assertFalse(shared(other("C:/link/acme")))
        assertFalse(shared(other("\\\\srv\\share\\acme")))
        assertFalse(shared(other("C:/w/acme", "feat-y")))
        assertFalse(shared(other("C:/w/acme").copy(id = "t1")))
        assertEquals(emptyList<String>(), seen)
    }
}
