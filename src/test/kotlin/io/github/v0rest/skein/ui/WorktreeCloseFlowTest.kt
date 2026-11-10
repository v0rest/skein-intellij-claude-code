package io.github.v0rest.skein.ui

import io.github.v0rest.skein.claude.SessionSnapshot
import io.github.v0rest.skein.git.GitResult
import io.github.v0rest.skein.git.GitRunner
import io.github.v0rest.skein.git.GitWorktree
import io.github.v0rest.skein.git.WorktreeGit
import io.github.v0rest.skein.process.ProcessProbe
import io.github.v0rest.skein.testutil.FakeProcessProbe
import io.github.v0rest.skein.testutil.sessionFile
import io.github.v0rest.skein.threads.CloseOffer
import io.github.v0rest.skein.threads.SkeinThread
import io.github.v0rest.skein.threads.ThreadState
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

class WorktreeCloseFlowTest {
    private val repo = "/w/acme"
    private val dir = "/w/acme/.claude/worktrees/feat-x"
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
        "worktree $repo\nHEAD 1111111111111111111111111111111111111111\nbranch refs/heads/main\n\n" +
            "worktree $dir\nHEAD 2222222222222222222222222222222222222222\n$branchLine\n\n"

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
        inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet(),
        removals: WorktreeGit? = null,
        settleMillis: Long = 50,
        unsavedEdits: suspend (Set<String>) -> Boolean = { false },
        insideClaudeHome: (Path) -> Boolean = { false },
        resolve: (String) -> String? = { null },
        probe: ProcessProbe = FakeProcessProbe(),
        ask: suspend (String, String) -> Boolean = { message, title ->
            questions += message to title
            events += "ask"
            remove
        },
    ): WorktreeCloseFlow {
        val git = WorktreeGit(FakeGit(answer))
        return WorktreeCloseFlow(
            git = git,
            removals = removals ?: git,
            snapshots = snapshots,
            threads = threads,
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
        val inner = "worktree $dir/.claude/worktrees/inner\nHEAD 3333333333333333333333333333333333333333\nbranch refs/heads/worktree-inner\n\n"
        runBlocking { flow(answers(list = listing() + inner)).run(thread()) }
        assertEquals(listOf("git worktree list --porcelain @$repo"), events)
        assertEquals(listOf("Worktree feat-x is kept: another worktree lies inside this one"), reports)
    }

    /** Only a worktree below this one's folder counts, not one whose name merely starts the same. */
    @Test
    fun aWorktreeNextToThisOneDoesNotKeepIt() {
        val sibling = "worktree $dir-2\nHEAD 3333333333333333333333333333333333333333\nbranch refs/heads/worktree-feat-x-2\n\n"
        runBlocking { flow(answers(list = listing() + sibling)).run(thread()) }
        assertEquals(reads + rereads + listOf(removeEvent, deleteEvent), events)
    }

    /** git lists real paths: an inner worktree under the resolved repository counts as well. */
    @Test
    fun anInnerWorktreeListedUnderTheRealPathKeepsTheOuterOne() {
        val real = "/real/acme"
        val resolve: (String) -> String? = { if (it.trimEnd('/') == repo) real else null }
        val list = "worktree $real\nHEAD 1111111111111111111111111111111111111111\nbranch refs/heads/main\n\n" +
            "worktree $real/.claude/worktrees/feat-x\nHEAD 2222222222222222222222222222222222222222\nbranch refs/heads/$branch\n\n" +
            "worktree $real/.claude/worktrees/feat-x/.claude/worktrees/inner\nHEAD 3333333333333333333333333333333333333333\ndetached\n\n"
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
                list = listing() + "worktree $dir/.claude/worktrees/inner\nHEAD 3333333333333333333333333333333333333333\ndetached\n\n"
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

    @Test
    fun aGoneWorktreeAsksNothing() {
        runBlocking { flow(exists = { false }).run(thread()) }
        assertEquals(emptyList<String>(), events)
        assertEquals(emptyList<String>(), reports)
    }

    @Test
    fun aSessionStillInsideOnlyAllowsKeeping() {
        runBlocking { flow(snapshots = MutableStateFlow(sessionInside("$dir/src"))).run(thread()) }
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

    @Test
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
        val repositories = setOf(repo)
        assertTrue(WorktreeCloseFlow.liveInside(sessionInside(), repositories, "feat-x"))
        assertTrue(WorktreeCloseFlow.liveInside(sessionInside("$dir/src/"), repositories, "feat-x"))
        assertTrue(WorktreeCloseFlow.liveInside(sessionInside("$dir/.claude/worktrees/inner"), repositories, "feat-x"))
        assertFalse(WorktreeCloseFlow.liveInside(sessionInside("$dir-2"), repositories, "feat-x"))
        assertFalse(WorktreeCloseFlow.liveInside(sessionInside(), repositories, "feat-y"))
        assertFalse(WorktreeCloseFlow.liveInside(sessionInside(repo), repositories, "feat-x"))
        assertFalse(WorktreeCloseFlow.liveInside(sessionInside("/w/beta/.claude/worktrees/feat-x"), repositories, "feat-x"))
        // the repository as the status file reports it, resolved
        assertTrue(WorktreeCloseFlow.liveInside(sessionInside("/real/acme/.claude/worktrees/feat-x"), setOf(repo, "/real/acme"), "feat-x"))
    }

    @Test
    fun theWorktreeIsFoundUnderTheRepositoryOrItsResolvedPath() {
        val worktrees = listOf(
            GitWorktree("/real/acme", "main"),
            GitWorktree("/real/acme/.claude/worktrees/feat-x", branch),
            GitWorktree("/w/beta/.claude/worktrees/feat-y", "worktree-feat-y"),
        )
        assertEquals(GitWorktree("/real/acme/.claude/worktrees/feat-x", branch), WorktreeCloseFlow.entryOf(worktrees, setOf(repo, "/real/acme"), "feat-x"))
        assertNull(WorktreeCloseFlow.entryOf(worktrees, setOf(repo), "feat-x"))
        assertNull(WorktreeCloseFlow.entryOf(worktrees, setOf(repo, "/real/acme"), "feat-y"))
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
        val dirs = setOf(dir, "/real/acme/.claude/worktrees/feat-x")
        val elsewhere = "/w/acme/a.kt" to "/w/acme/a.kt"
        // each unsaved document as (path, canonical path)
        assertTrue(WorktreeCloseFlow.anyInWorktree(listOf(elsewhere, "$dir/a.kt" to null), dirs))
        assertTrue(WorktreeCloseFlow.anyInWorktree(listOf(elsewhere, "/link/acme/.claude/worktrees/feat-x/a.kt" to "/real/acme/.claude/worktrees/feat-x/a.kt"), dirs))
        assertTrue(WorktreeCloseFlow.anyInWorktree(listOf("/link/to/a.kt" to "$dir/src/a.kt"), dirs))
        assertFalse(WorktreeCloseFlow.anyInWorktree(listOf("/link/to/a.kt" to null), dirs))
        assertFalse(WorktreeCloseFlow.anyInWorktree(listOf("$dir-2/a.kt" to "$dir-2/a.kt", elsewhere), dirs))
        assertFalse(WorktreeCloseFlow.anyInWorktree(emptyList(), dirs))
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
        val real = "/real/acme"
        val resolve: (String) -> String? = { if (it.trimEnd('/') == repo || it.trimEnd('/') == real) real else null }
        val sibling = thread(id = "t2", sessionId = null)
        runBlocking { flow(threads = { listOf(sibling) }, resolve = resolve).run(thread().copy(folder = real)) }
        assertEquals(emptyList<String>(), events)
        assertEquals(listOf("Worktree feat-x is kept: another Skein thread uses this worktree"), reports)
    }

    @Test
    fun aSecondCloseThroughAnotherPathToTheRepositoryDoesNotAskAgain() {
        val real = "/real/acme"
        val resolve: (String) -> String? = { if (it.trimEnd('/') == repo || it.trimEnd('/') == real) real else null }
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
        val nested = sessionFile(pid = 8, sessionId = "s-9", cwd = "$dir/.claude/worktrees/inner")
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
}
