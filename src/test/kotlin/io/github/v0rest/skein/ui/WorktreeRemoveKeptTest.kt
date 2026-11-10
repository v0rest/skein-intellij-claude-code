package io.github.v0rest.skein.ui

import io.github.v0rest.skein.claude.SessionSnapshot
import io.github.v0rest.skein.git.GitResult
import io.github.v0rest.skein.git.GitRunner
import io.github.v0rest.skein.git.WorktreeGit
import io.github.v0rest.skein.process.ProcessProbe
import io.github.v0rest.skein.testutil.FakeProcessProbe
import io.github.v0rest.skein.testutil.sessionFile
import io.github.v0rest.skein.threads.SkeinThread
import io.github.v0rest.skein.threads.ThreadState
import io.github.v0rest.skein.threads.WorktreeClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.util.Collections

/**
 * The Worktrees section's Remove… ([WorktreeCloseFlow.removeKept]): the close flow's checks and question, git's force
 * only after a second yes and the checks once more, never for a locked worktree, `branch -d` only, and a folder git
 * does not list deleted as a plain folder after its own question.
 */
class WorktreeRemoveKeptTest {
    private val repo = "/w/acme"
    private val dir = "/w/acme/.claude/worktrees/acme"
    private val branch = "worktree-acme"

    /** git calls, questions and deletes, in the order they happened. */
    private val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val reports = mutableListOf<String>()
    private val questions = mutableListOf<String>()

    private inner class FakeGit(private val answer: (List<String>) -> GitResult) : GitRunner {
        override fun run(dir: Path, vararg args: String): GitResult {
            events += "git " + args.joinToString(" ")
            // the scripted answer reads the command after a leading `-c name=value`
            return answer(args.toList().let { if (it.firstOrNull() == "-c") it.drop(2) else it })
        }
    }

    private fun ok(out: String = "") = GitResult(0, out, "", false)

    private fun listing(entry: String? = "branch refs/heads/$branch") =
        "worktree $repo\nHEAD 1111\nbranch refs/heads/main\n\n" + (entry?.let { "worktree $dir\nHEAD 2222\n$it\n\n" } ?: "")

    private val refusal = GitResult(128, "", "fatal: '$dir' contains modified or untracked files, use --force to delete it\n", false)

    /** How many plain removes the scripted git answered. */
    private var removes = 0

    private fun answers(
        list: () -> GitResult = { ok(listing()) },
        remove: GitResult = ok(),
        force: GitResult = ok(),
        deleteBranch: GitResult = ok(),
        tracked: GitResult = ok(),
        secondRemove: GitResult? = null,
        nested: GitResult = ok("## worktree-acme\u0000 M a.kt\u0000?? b.kt\u0000"),
        unlock: () -> GitResult = { ok() },
    ): (List<String>) -> GitResult = { args ->
        when {
            "unlock" in args -> unlock()
            args.take(2) == listOf("worktree", "list") -> list()
            args.firstOrNull() == "ls-files" -> tracked
            "--untracked-files=all" in args -> nested
            "status" in args -> ok(" M a.kt\n?? b.kt\n")
            args.firstOrNull() == "rev-list" -> ok("1\n")
            args.take(3) == listOf("worktree", "remove", "--force") -> force
            args.take(2) == listOf("worktree", "remove") -> if (removes++ == 0) remove else secondRemove ?: remove
            args.take(2) == listOf("branch", "-d") -> deleteBranch
            else -> GitResult(1, "", "unexpected", false)
        }
    }

    private fun flow(
        answer: (List<String>) -> GitResult = answers(),
        threads: () -> List<SkeinThread> = { emptyList() },
        snapshots: MutableStateFlow<SessionSnapshot> = MutableStateFlow(SessionSnapshot.EMPTY),
        remove: Boolean = true,
        force: suspend () -> Boolean = { true },
        delete: Boolean = true,
        exists: (Path) -> Boolean = { true },
        isLink: (Path) -> Boolean = { false },
        unsavedEdits: suspend (Set<String>) -> Boolean = { false },
        probe: ProcessProbe = FakeProcessProbe(),
        ask: suspend () -> Unit = {},
    ) = WorktreeCloseFlow(
        git = WorktreeGit(FakeGit(answer)),
        snapshots = snapshots,
        threads = threads,
        ask = { message, _ ->
            events += "ask"
            questions += message
            ask()
            remove
        },
        confirmForce = { message, _ ->
            events += "force?"
            questions += message
            force()
        },
        confirmDelete = { message, _ ->
            events += "delete?"
            questions += message
            delete
        },
        report = { text, _ -> reports += text },
        unsavedEdits = unsavedEdits,
        insideClaudeHome = { false },
        exists = exists,
        isLink = isLink,
        gitInside = { null },
        deleteLeftover = { _, name ->
            events += "delete $name"
            null
        },
        probe = probe,
        zone = ZoneOffset.UTC,
    )

    private val list = "git worktree list --porcelain"
    private val lsFiles = "git -c core.fsmonitor=false ls-files -- .claude/worktrees/acme"
    private val nestedCheck = "git -c core.fsmonitor=false --no-optional-locks status --porcelain -z --ignored=traditional --untracked-files=all"
    private val reads = listOf(list, "git -c core.fsmonitor=false --no-optional-locks status --porcelain", "git rev-list --count HEAD --not --remotes", nestedCheck)
    private val plainRemove = "git -c core.fsmonitor=false worktree remove -- $dir"
    private val forcedRemove = "git -c core.fsmonitor=false worktree remove --force -- $dir"
    private val deleteBranch = "git branch -d -- $branch"
    private val unlock = "git -c core.fsmonitor=false --no-optional-locks worktree unlock -- $dir"

    /** The lock Claude Code can leave on a worktree it made, with the pid and start of its claude; 08:30:41 UTC. */
    private val claudeLock = "locked claude session acme (pid 4242 start Tue Nov 10 08:30:41 2026)"

    /** Another stale lock of Claude Code's on the same worktree, by another claude that has ended too. */
    private val otherClaudeLock = "locked claude session acme (pid 4343 start Tue Nov 10 09:15:02 2026)"
    private val claudeStart: Instant = Instant.parse("2026-11-10T08:30:41.300Z")
    private val claude = listOf("/w/bin/claude", "--worktree", "acme")
    private val lockedRefusal = GitResult(128, "", "fatal: cannot remove a locked working tree, lock reason: on a stick\nuse 'remove -f -f' to override or unlock first\n", false)

    private fun run(flow: WorktreeCloseFlow) = runBlocking { flow.removeKept(repo, "acme") }

    @Test
    fun aKeptWorktreeIsRemovedThenItsBranchWithDashDOnly() {
        run(flow())
        assertEquals(listOf(list) + reads + "ask" + reads + listOf(plainRemove, deleteBranch), events)
        assertEquals(listOf("Remove the worktree acme and its branch $branch? Left in it: 2 changed files, 1 commit not pushed. Removing it also deletes its ignored files, such as build output and .env."), questions)
        assertEquals(listOf("Worktree acme and its branch $branch were removed"), reports)
    }

    @Test
    fun cancelRemovesNothing() {
        run(flow(remove = false))
        assertEquals(listOf(list) + reads + "ask", events)
        assertEquals(emptyList<String>(), reports)
    }

    @Test
    fun gitsRefusalLeadsToASecondQuestionAndTheForceOnlyAfterItsYesAndTheChecksAgain() {
        run(flow(answers(remove = refusal)))
        assertEquals(listOf(list) + reads + "ask" + reads + listOf(plainRemove, "force?") + reads + listOf(plainRemove, forcedRemove, deleteBranch), events)
        val second = questions[1]
        assertTrue(second, second.contains("contains modified or untracked files"))
        assertTrue(second, second.contains("lost for good"))
        assertEquals(listOf("Worktree acme and its branch $branch were removed"), reports)
    }

    @Test
    fun noToTheSecondQuestionForcesNothing() {
        run(flow(answers(remove = refusal), force = { false }))
        assertEquals(listOf(list) + reads + "ask" + reads + listOf(plainRemove, "force?"), events)
        assertTrue(events.none { it.contains("--force") || it.contains("branch") })
    }

    /** git would need its force twice for a locked worktree; Skein shows git's refusal and asks nothing more. */
    @Test
    fun aLockedWorktreeIsNeverOfferedTheForce() {
        val locked = GitResult(128, "", "fatal: cannot remove a locked working tree, lock reason: on a stick\nuse 'remove -f -f' to override or unlock first\n", false)
        run(flow(answers(list = { ok(listing("branch refs/heads/$branch\nlocked on a stick")) }, remove = locked)))
        assertEquals(listOf(list) + reads + "ask" + reads + plainRemove, events)
        assertEquals(1, reports.size)
        assertTrue(reports.single(), reports.single().startsWith("Worktree acme was not removed: fatal: cannot remove a locked working tree"))
    }

    @Test
    fun aSessionStartedWhileTheSecondQuestionWasOpenStopsTheForce() {
        val snapshots = MutableStateFlow(SessionSnapshot.EMPTY)
        run(
            flow(answers(remove = refusal), snapshots = snapshots, force = {
                snapshots.value = SessionSnapshot(mapOf(7L to sessionFile(pid = 7, sessionId = "s-1", cwd = "$dir/src")), emptySet())
                true
            }),
        )
        assertTrue(events.none { it == forcedRemove })
        assertEquals(listOf("Worktree acme was not removed: ${WorktreeClose.SESSION_INSIDE}"), reports)
    }

    @Test
    fun aBranchGitWillNotDeleteIsKeptAndSaidSo() {
        run(flow(answers(deleteBranch = GitResult(1, "", "error: the branch '$branch' is not fully merged.\n", false))))
        assertEquals(listOf("Worktree acme was removed, but Skein kept the branch $branch, it has unmerged commits: the branch '$branch' is not fully merged."), reports)
        assertTrue(events.none { it.contains("-D") })
    }

    /** Threads of every open window count, and so does any thread whose claude runs inside. */
    @Test
    fun aThreadAnywhereInTheWorktreeKeepsIt() {
        val worktreeThread = SkeinThread("t9", repo, null, "acme", ThreadState.Idle, 0, 0, null, worktree = "acme")
        val plainInside = SkeinThread("t8", repo, null, "x", ThreadState.Idle, 0, 0, null, sessionCwd = "$dir/src")
        for (holder in listOf(worktreeThread, plainInside)) {
            events.clear()
            reports.clear()
            run(flow(threads = { listOf(holder) }))
            assertEquals(listOf(list), events)
            assertEquals(listOf("Worktree acme was not removed: ${WorktreeClose.OTHER_THREAD}"), reports)
        }
    }

    @Test
    fun aListingGitCannotGiveRemovesAndDeletesNothing() {
        run(flow(answers(list = { GitResult(128, "", "fatal: not a git repository\n", false) })))
        assertEquals(listOf(list), events)
        assertEquals(listOf("Worktree acme was not removed: not a git repository"), reports)
    }

    @Test
    fun aFolderGitDoesNotListIsDeletedAfterItsOwnQuestion() {
        run(flow(answers(list = { ok(listing(entry = null)) })))
        assertEquals(listOf(list, list, lsFiles, "delete?", list, lsFiles, "delete acme"), events)
        assertEquals(listOf("git does not list $dir as a worktree. Delete the folder and everything in it? This cannot be undone."), questions)
        assertEquals(listOf("Folder acme was deleted"), reports)
    }

    @Test
    fun noToTheDeleteQuestionDeletesNothing() {
        run(flow(answers(list = { ok(listing(entry = null)) }), delete = false))
        assertEquals(listOf(list, list, lsFiles, "delete?"), events)
        assertEquals(emptyList<String>(), reports)
    }

    @Test
    fun aLeftoverFolderStaysWhileAnythingUsesItOrCouldLoseWork() {
        val inside = SessionSnapshot(mapOf(7L to sessionFile(pid = 7, sessionId = "s-1", cwd = dir)), emptySet())
        val nested = listing(entry = null) + "worktree $dir/.claude/worktrees/inner\nHEAD 3333\nbranch refs/heads/worktree-inner\n\n"
        val holder = SkeinThread("t9", repo, null, "acme", ThreadState.Idle, 0, 0, null, worktree = "acme")
        val cases = listOf(
            WorktreeCloseFlow.LINK to flow(answers(list = { ok(listing(entry = null)) }), isLink = { true }),
            WorktreeClose.SESSION_INSIDE to flow(answers(list = { ok(listing(entry = null)) }), snapshots = MutableStateFlow(inside)),
            WorktreeClose.OTHER_THREAD to flow(answers(list = { ok(listing(entry = null)) }), threads = { listOf(holder) }),
            WorktreeClose.NESTED_WORKTREE to flow(answers(list = { ok(nested) })),
            WorktreeCloseFlow.TRACKED to flow(answers(list = { ok(listing(entry = null)) }, tracked = ok(".claude/worktrees/acme/a.c\n"))),
            "not a git repository" to flow(answers(list = { ok(listing(entry = null)) }, tracked = GitResult(128, "", "fatal: not a git repository\n", false))),
            WorktreeClose.UNSAVED_EDITS to flow(answers(list = { ok(listing(entry = null)) }), unsavedEdits = { true }),
        )
        for ((reason, flow) in cases) {
            events.clear()
            reports.clear()
            run(flow)
            assertEquals(reason, listOf("Folder acme was not deleted: $reason"), reports)
            assertTrue(reason, events.none { it.startsWith("delete") })
        }
    }

    /** git started listing the folder while the question was open: it is a worktree now, and no plain delete is right. */
    @Test
    fun aFolderThatBecameAWorktreeMeanwhileIsNotDeleted() {
        var calls = 0
        run(flow(answers(list = { ok(if (calls++ < 2) listing(entry = null) else listing()) })))
        assertEquals(listOf("Folder acme was not deleted: ${WorktreeCloseFlow.NOW_LISTED}"), reports)
        assertTrue(events.none { it == "delete acme" })
    }

    @Test
    fun aGoneFolderAsksNothing() {
        run(flow(answers(list = { ok(listing(entry = null)) }), exists = { false }))
        assertEquals(listOf("Worktree acme is already gone"), reports)
        assertTrue(events.none { it.endsWith("?") })
    }

    /** Closing a thread's tab never forces, whatever git says: only the section's Remove… asks the second question. */
    @Test
    fun closingAThreadNeverAsksToForce() {
        val thread = SkeinThread("t1", repo, null, "acme", ThreadState.Exited, 0, 0, null, worktree = "acme")
        val flow = WorktreeCloseFlow(
            git = WorktreeGit(FakeGit(answers(remove = refusal))),
            snapshots = MutableStateFlow(SessionSnapshot.EMPTY),
            ask = { _, _ -> true },
            confirmForce = { _, _ ->
                events += "force?"
                true
            },
            report = { text, _ -> reports += text },
            unsavedEdits = { false },
            insideClaudeHome = { false },
            exists = { true },
            settleMillis = 10,
        )
        runBlocking { flow.run(thread) }
        assertTrue(events.toString(), events.none { it == "force?" || it.contains("--force") })
        assertEquals(listOf("Worktree acme was not removed: '$dir' contains modified or untracked files, use --force to delete it"), reports)
    }

    /** A worktree git marks prunable has lost its link: git status there would answer for the repository around it. */
    @Test
    fun aPrunableWorktreeIsNeitherReadNorForced() {
        run(flow(answers(list = { ok(listing("branch refs/heads/$branch\nprunable gitdir file points to non-existent location")) }, remove = refusal)))
        assertEquals(listOf(list, list), events)
        assertEquals(listOf("Worktree acme was not removed: ${WorktreeClose.GIT_FAILED}"), reports)
    }

    @Test
    fun aRemoveWhileTheSameWorktreeIsBeingClosedSaysSo() {
        val inFlight: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        inFlight += dir
        val flow = WorktreeCloseFlow(
            git = WorktreeGit(FakeGit(answers())),
            snapshots = MutableStateFlow(SessionSnapshot.EMPTY),
            inFlight = inFlight,
            ask = { _, _ -> true },
            report = { text, _ -> reports += text },
            unsavedEdits = { false },
            insideClaudeHome = { false },
            exists = { true },
        )
        run(flow)
        assertEquals(emptyList<String>(), events)
        assertEquals(listOf("Worktree acme is already being closed or removed"), reports)
    }

    /** git's force is for changed and untracked files only: any other refusal, or one in another language, is git's last word. */
    @Test
    fun onlyTheRefusalForChangedFilesIsOfferedTheForce() {
        val others = listOf(
            "fatal: working trees containing submodules cannot be moved or removed\n",
            "fatal: validation failed, cannot remove working tree: '$dir' does not point back to '/w/acme/.git/worktrees/acme'\n",
            "fatal: '$dir' enthält geänderte oder unversionierte Dateien, benutzen Sie --force zum Löschen\n",
        )
        for (stderr in others) {
            events.clear()
            reports.clear()
            run(flow(answers(remove = GitResult(128, "", stderr, false))))
            assertTrue(stderr, events.none { it == "force?" || it.contains("--force") })
            assertEquals(stderr, 1, events.count { it == plainRemove })
            assertEquals(listOf("Worktree acme was not removed: ${stderr.trim()}"), reports)
        }
    }

    /** A repository of its own inside, untracked or ignored, keeps the worktree before any question; so does a look that fails. */
    @Test
    fun aRepositoryOfItsOwnInsideTheWorktreeKeepsIt() {
        run(flow(answers(nested = ok("## worktree-acme\u0000?? notes.txt\u0000!! build/a.o\u0000!! vendor/lib/\u0000"))))
        assertEquals(listOf(list) + reads, events)
        assertEquals(listOf("Worktree acme was not removed: " + WorktreeClose.innerRepositories(listOf("vendor/lib/"), dir)), reports)
        events.clear()
        reports.clear()
        run(flow(answers(nested = GitResult(-1, "", "", true))))
        assertEquals(listOf(list) + reads, events)
        assertEquals(listOf("Worktree acme was not removed: " + WorktreeClose.innerUnknown(GitResult.TIMED_OUT, dir)), reports)
    }

    /** After the second yes git is asked again without force, and only its changes refusal leads on to the force. */
    @Test
    fun theForceRunsOnlyIfGitStillRefusesForChangedFiles() {
        val submodule = GitResult(128, "", "fatal: working trees containing submodules cannot be moved or removed\n", false)
        run(flow(answers(remove = refusal, secondRemove = submodule)))
        assertTrue(events.toString(), events.none { it.contains("--force") })
        assertEquals(listOf("Worktree acme was not removed: fatal: working trees containing submodules cannot be moved or removed"), reports)
        events.clear()
        reports.clear()
        removes = 0
        run(flow(answers(remove = refusal, secondRemove = ok())))
        assertTrue(events.toString(), events.none { it.contains("--force") })
        assertEquals(listOf("Worktree acme and its branch $branch were removed"), reports)
    }

    /**
     * Claude Code's own lock, its claude gone: the question says Skein lifts it, and Skein lifts it after the yes and the
     * checks again, right before git's plain remove; the rest goes as for a worktree that was never locked.
     */
    @Test
    fun aStaleClaudeCodeLockIsLiftedRightBeforeThePlainRemove() {
        run(flow(answers(list = { ok(listing("branch refs/heads/$branch\n$claudeLock")) })))
        assertEquals(listOf(list) + reads + "ask" + reads + listOf(list, unlock, plainRemove, deleteBranch), events)
        assertEquals(
            listOf(
                "Remove the worktree acme and its branch $branch? Left in it: 2 changed files, 1 commit not pushed. " +
                    "${WorktreeCloseText.LIFTS_LOCK} Removing it also deletes its ignored files, such as build output and .env.",
            ),
            questions,
        )
        assertEquals(listOf("Worktree acme and its branch $branch were removed"), reports)
    }

    @Test
    fun cancelLeavesAStaleLockInPlace() {
        run(flow(answers(list = { ok(listing("branch refs/heads/$branch\n$claudeLock")) }), remove = false))
        assertEquals(listOf(list) + reads + "ask", events)
        assertTrue(events.none { it == unlock })
    }

    /** A pid now held by another program that started at another time is not the lock's claude. */
    @Test
    fun aLockWhoseClaudeIsGoneIsStaleThoughThePidLives() {
        val holder = FakeProcessProbe().apply { add(4242, null, claudeStart.minusSeconds(3 * 86_400), listOf("/bin/sleep", "60")) }
        run(flow(answers(list = { ok(listing("branch refs/heads/$branch\n$claudeLock")) }), probe = holder))
        assertEquals(listOf(list) + reads + "ask" + reads + listOf(list, unlock, plainRemove, deleteBranch), events)
    }

    /** Either signal vouches: a claude whose start reads another time, or a process that started when the lock says. */
    @Test
    fun aClaudeCommandOrAMatchingStartKeepsTheLock() {
        val holders = listOf(
            FakeProcessProbe().apply { add(4242, null, claudeStart.plusSeconds(3_600), claude) },
            FakeProcessProbe().apply { add(4242, null, claudeStart, listOf("/w/bin/node", "/w/lib/cli.js")) },
        )
        for (holder in holders) {
            events.clear()
            reports.clear()
            run(flow(answers(list = { ok(listing("branch refs/heads/$branch\n$claudeLock")) }), probe = holder))
            assertEquals(listOf(list) + reads, events)
            assertEquals(listOf("Worktree acme was not removed: Claude Code's lock on it names pid 4242, which still runs and may be the claude that locked it"), reports)
        }
    }

    /** The claude that locked the worktree still runs: Skein says so before any question and never lifts its lock. */
    @Test
    fun aLiveClaudesLockIsRefusedBeforeAnyQuestion() {
        val probe = FakeProcessProbe().apply { add(4242, null, claudeStart, claude) }
        run(flow(answers(list = { ok(listing("branch refs/heads/$branch\n$claudeLock")) }), probe = probe))
        assertEquals(listOf(list) + reads, events)
        assertEquals(listOf("Worktree acme was not removed: Claude Code's lock on it names pid 4242, which still runs and may be the claude that locked it"), reports)
    }

    @Test
    fun aClaudeThatStartedWhileTheQuestionWasOpenKeepsItsLock() {
        val probe = FakeProcessProbe()
        run(flow(answers(list = { ok(listing("branch refs/heads/$branch\n$claudeLock")) }), probe = probe, ask = { probe.add(4242, null, claudeStart, claude) }))
        assertEquals(listOf(list) + reads + "ask" + reads, events)
        assertEquals(listOf("Worktree acme was not removed: Claude Code's lock on it names pid 4242, which still runs and may be the claude that locked it"), reports)
    }

    /** The question said nothing about a lock: one that came while it was open stays, and git refuses as for any lock. */
    @Test
    fun aLockThatCameWhileTheQuestionWasOpenIsNotLifted() {
        var calls = 0
        run(flow(answers(list = { ok(if (calls++ < 2) listing() else listing("branch refs/heads/$branch\n$claudeLock")) }, remove = lockedRefusal)))
        assertEquals(listOf(list) + reads + "ask" + reads + plainRemove, events)
        assertEquals(1, reports.size)
        assertTrue(reports.single(), reports.single().startsWith("Worktree acme was not removed: fatal: cannot remove a locked working tree"))
    }

    /** Lifted, then git's refusal for changed files: the second question, the checks again and only then the force. */
    @Test
    fun aStaleLockWithChangedFilesIsForcedOnlyAfterTheSecondYes() {
        var lifted = false
        val answer = answers(
            list = { ok(listing("branch refs/heads/$branch" + if (lifted) "" else "\n$claudeLock")) },
            remove = refusal,
            unlock = {
                lifted = true
                ok()
            },
        )
        run(flow(answer))
        assertEquals(listOf(list) + reads + "ask" + reads + listOf(list, unlock, plainRemove, "force?") + reads + listOf(plainRemove, forcedRemove, deleteBranch), events)
        assertEquals(listOf("Worktree acme and its branch $branch were removed"), reports)
    }

    /** No to the force: the worktree stays, and so does the lift, since no claude held that lock. */
    @Test
    fun noToTheForceAfterTheLiftForcesNothing() {
        run(flow(answers(list = { ok(listing("branch refs/heads/$branch\n$claudeLock")) }, remove = refusal), force = { false }))
        assertEquals(listOf(list) + reads + "ask" + reads + listOf(list, unlock, plainRemove, "force?"), events)
        assertEquals(emptyList<String>(), reports)
    }

    /** Any other refusal after the lift is git's last word, and the report says the lock is gone. */
    @Test
    fun aRefusalAfterTheLiftSaysTheLockIsLifted() {
        val submodule = GitResult(128, "", "fatal: working trees containing submodules cannot be moved or removed\n", false)
        run(flow(answers(list = { ok(listing("branch refs/heads/$branch\n$claudeLock")) }, remove = submodule)))
        assertEquals(listOf(list) + reads + "ask" + reads + listOf(list, unlock, plainRemove), events)
        assertEquals(listOf(WorktreeCloseText.notRemovedLockLifted("acme", "fatal: working trees containing submodules cannot be moved or removed")), reports)
        assertTrue(reports.single(), reports.single().contains("lock") && reports.single().contains("submodules"))
    }

    @Test
    fun aLiftGitRefusesRemovesNothing() {
        val notLocked = GitResult(128, "", "fatal: '$dir' is not locked\n", false)
        run(flow(answers(list = { ok(listing("branch refs/heads/$branch\n$claudeLock")) }, unlock = { notLocked })))
        assertEquals(listOf(list) + reads + "ask" + reads + listOf(list, unlock), events)
        assertEquals(listOf(WorktreeCloseText.lockNotLifted("acme", "'$dir' is not locked")), reports)
        assertTrue(reports.single(), reports.single().contains("could not lift the lock"))
    }

    /** A lock someone else made stays as it is: git refuses, and neither a lift nor the force follows. */
    @Test
    fun aForeignLockIsNeverLifted() {
        for (reason in listOf("on a stick", "claude session acme (pid 4242)", "claude  session acme (pid 4242 start Tue Nov 10 08:30:41 2026)")) {
            events.clear()
            reports.clear()
            questions.clear()
            run(flow(answers(list = { ok(listing("branch refs/heads/$branch\nlocked $reason")) }, remove = lockedRefusal)))
            assertEquals(reason, listOf(list) + reads + "ask" + reads + plainRemove, events)
            assertTrue(reason, questions.none { it.contains(WorktreeCloseText.LIFTS_LOCK) })
        }
    }

    /**
     * Stale lock A when the question was asked, stale lock B, of another ended claude, right after it: the question
     * named A, so B is not lifted, and git refuses as for any lock, with no force offered.
     */
    @Test
    fun aLockThatChangedWhileTheQuestionWasOpenIsNotLifted() {
        var calls = 0
        run(flow(answers(list = { ok(listing("branch refs/heads/$branch\n" + if (calls++ < 2) claudeLock else otherClaudeLock)) }, remove = lockedRefusal)))
        assertEquals(listOf(list) + reads + "ask" + reads + plainRemove, events)
        assertTrue(events.none { it == unlock || it == "force?" })
        assertEquals(1, reports.size)
        assertTrue(reports.single(), reports.single().startsWith("Worktree acme was not removed: fatal: cannot remove a locked working tree"))
    }

    /**
     * Right before the lift git lists the worktree once more: a lock that changed after the checks (another ended
     * claude's, a claude that runs there now, or none at all) is not lifted, and nothing is removed.
     */
    @Test
    fun aLockThatChangedRightBeforeTheLiftIsNotLifted() {
        val probe = FakeProcessProbe()
        val atTheLift = listOf<() -> String>(
            { listing("branch refs/heads/$branch\n$otherClaudeLock") },
            {
                probe.add(4242, null, claudeStart, claude)
                listing("branch refs/heads/$branch\n$claudeLock")
            },
            { listing() },
        )
        for (changed in atTheLift) {
            events.clear()
            reports.clear()
            probe.kill(4242)
            var calls = 0
            run(flow(answers(list = { ok(if (calls++ < 3) listing("branch refs/heads/$branch\n$claudeLock") else changed()) }), probe = probe))
            assertEquals(listOf(list) + reads + "ask" + reads + list, events)
            assertEquals(listOf(WorktreeCloseText.notRemoved("acme", WorktreeCloseText.LOCK_CHANGED)), reports)
        }
    }

    /** git cannot list the worktrees right before the lift: nothing is lifted or removed, and Skein says why. */
    @Test
    fun aListingThatFailsRightBeforeTheLiftLiftsNothing() {
        var calls = 0
        val failed = GitResult(128, "", "fatal: not a git repository\n", false)
        run(flow(answers(list = { if (calls++ < 3) ok(listing("branch refs/heads/$branch\n$claudeLock")) else failed })))
        assertEquals(listOf(list) + reads + "ask" + reads + list, events)
        assertEquals(listOf(WorktreeCloseText.lockNotLifted("acme", "not a git repository")), reports)
    }
}
