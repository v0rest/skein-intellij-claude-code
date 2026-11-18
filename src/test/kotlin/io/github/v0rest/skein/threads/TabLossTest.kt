package io.github.v0rest.skein.threads

import io.github.v0rest.skein.claude.ClaudeActivity
import io.github.v0rest.skein.claude.SessionFile
import io.github.v0rest.skein.claude.SessionSnapshot
import io.github.v0rest.skein.claude.WaitReason
import io.github.v0rest.skein.claude.Worktrees
import io.github.v0rest.skein.process.ProcessId
import io.github.v0rest.skein.testutil.FakeProcessProbe
import io.github.v0rest.skein.testutil.FakeThreadTerminal
import io.github.v0rest.skein.testutil.Fixture
import io.github.v0rest.skein.testutil.sessionFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

private const val A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

/**
 * A thread whose tab the IDE closed past Skein's close check ([ThreadCoordinator.onTabLost]) stays, gets a new tab,
 * and is resumed there when its claude ran in the old one, or comes back unstarted as an IDE restart brings it back.
 */
class TabLossTest {
    private val t0: Instant = Instant.ofEpochMilli(0)
    private val probe = FakeProcessProbe(current = 1)
    private val terminal = FakeThreadTerminal()
    private val slot = LaunchSlot()
    private val closed = mutableListOf<String>()
    private val events = object : CoordinatorEvents {
        override fun notify(thread: SkeinThread, kind: NotificationKind) = Unit

        override fun viewed(threadId: String) = Unit

        override fun closed(thread: SkeinThread) {
            closed += thread.id
        }

        override fun leftNeedsYou(threadId: String) = Unit

        override fun missingStatusFiles() = Unit
    }
    private var nextId = 0

    /** The sessions with a conversation on disk - [A] has one unless a test says otherwise. */
    private val conversations = mutableSetOf(A)
    private val coordinator = ThreadCoordinator(terminal, ThreadMapper(probe), slot, events, conversation = { _, _, id -> id in conversations }) { "t${++nextId}" }
    private var nextShell = 10L

    @Before
    fun tree() {
        probe.add(1, parent = null, start = t0) // the IDE
        terminal.onOpen = { probe.add(nextShell++, parent = 1, start = t0, command = listOf(Fixture.root("bin/zsh"))) }
        terminal.shellsNow = { ThreadMapper(probe).ideChildList() }
    }

    private fun claude(pid: Long, under: Long, startedAt: Long = 1_500, activity: ClaudeActivity = ClaudeActivity.IDLE, waitingFor: String? = null, cwd: String = "/p"): SessionFile {
        probe.add(pid, parent = under, start = Instant.ofEpochMilli(startedAt - 100))
        return sessionFile(pid = pid, activity = activity, waitingFor = waitingFor, sessionId = A, cwd = cwd, name = "acme-api", startedAt = startedAt)
    }

    private fun snap(vararg files: SessionFile) = SessionSnapshot(files.associateBy { it.pid }, emptySet())

    private fun thread(id: String = "t1") = coordinator.threads.single { it.id == id }

    /** t1 in shell 10 with an idle claude 100 in session [A]. */
    private fun idleThread(): SessionFile {
        coordinator.newThread("/p", now = 1_000)
        val s100 = claude(100, under = 10)
        coordinator.onSnapshot(snap(s100), viewed = { false }, now = 2_000)
        assertEquals(ThreadState.Idle, thread().state)
        return s100
    }

    /** What a close sequence leaves of claude and its shell - nothing. */
    private fun ended(vararg pids: Long) = pids.forEach(probe::kill)

    @Test
    fun aThreadWhoseClaudeRanComesBackResumedInANewTab() {
        val s100 = idleThread()
        coordinator.pin(listOf("t1"), true)
        coordinator.color(listOf("t1"), ThreadColor.entries.first())
        coordinator.endingsBeforeLoss(listOf("t1"), snap(s100))
        ended(100, 10)

        coordinator.onTabLost("t1", select = true, snap(), now = 3_000)

        assertEquals(listOf("t1", "t1"), terminal.opened.map { it.id })
        val reopened = terminal.opened.last()
        assertTrue("its shell starts at once", reopened.start)
        assertTrue("it was in front, so it is selected again", reopened.focus)
        assertEquals("t1" to "claude --resume $A", terminal.typed.last())
        assertEquals(ThreadState.Starting, thread().state)
        // the same row - pin, colour and session kept, no close and so no worktree question
        assertTrue(thread().pinned)
        assertEquals(ThreadColor.entries.first(), thread().color)
        assertEquals(A, thread().sessionId)
        assertEquals(emptyList<String>(), closed)
        assertFalse("the old shell is no thread's anchor any more", 10L in slot.ownedAnchors())
    }

    @Test
    fun theResumedThreadTiesItselfToItsNewShell() {
        val s100 = idleThread()
        coordinator.endingsBeforeLoss(listOf("t1"), snap(s100))
        ended(100, 10)
        coordinator.onTabLost("t1", select = false, snap(), now = 3_000)

        val s200 = claude(200, under = 11, startedAt = 3_500) // the new tab's shell is 11
        coordinator.onSnapshot(snap(s200), viewed = { false }, now = 4_000)
        assertEquals(11L, thread().anchor)
        assertEquals(ThreadState.Idle, thread().state)
        assertFalse("not in front before, so not selected now", terminal.opened.last().focus)
    }

    /** The hang-up turns the thread exited before its tab is gone, and how it comes back was decided before that. */
    @Test
    fun howItComesBackIsDecidedBeforeTheHangUp() {
        val s100 = idleThread()
        val ending = coordinator.endingsBeforeLoss(listOf("t1"), snap(s100)).single()
        assertEquals(ProcessId(10, t0), ending.shell)
        assertEquals(100L, ending.claude?.pid)
        ended(100)
        coordinator.onSnapshot(snap(), viewed = { false }, now = 2_500)
        assertEquals(ThreadState.Exited, thread().state)
        ended(10)

        coordinator.onTabLost("t1", select = false, snap(), now = 3_000)
        assertEquals("t1" to "claude --resume $A", terminal.typed.last())
    }

    /** A restored thread nobody opened - its new tab is not shown, its shell does not start, nothing is typed. */
    @Test
    fun aRestoredThreadNobodyOpenedComesBackUnstartedAndUnselected() {
        terminal.lazyShells = true
        coordinator.restore(listOf(saved("r1")), snap(), now = 1_000)
        coordinator.endingsBeforeLoss(listOf("r1"), snap())

        coordinator.onTabLost("r1", select = true, snap(), now = 2_000)

        val reopened = terminal.opened.last()
        assertEquals("r1", reopened.id)
        assertFalse(reopened.start)
        assertFalse(reopened.focus)
        assertTrue(terminal.typed.isEmpty())
        assertEquals(ThreadState.NotStarted, thread("r1").state)
        assertTrue(probe.children(1).isEmpty())
        // and it starts as any restored thread does, once the user opens it
        coordinator.onTabSelected("r1", snap(), now = 3_000)
        assertEquals("r1" to "claude --resume $A", terminal.typed.single())
    }

    /** claude had exited in the tab, so the thread comes back as a restart brings it back, its claude not started yet. */
    @Test
    fun anExitedThreadComesBackLikeARestart() {
        idleThread()
        ended(100)
        coordinator.onSnapshot(snap(), viewed = { false }, now = 2_500)
        assertEquals(ThreadState.Exited, thread().state)
        val typed = terminal.typed.toList()

        coordinator.onTabLost("t1", select = true, snap(), now = 3_000)

        assertFalse(terminal.opened.last().start)
        assertEquals(typed, terminal.typed)
        assertEquals(ThreadState.NotStarted, thread().state)
    }

    /**
     * The tab's claude has not ended yet (on Windows the IDE ends it with the tab, a moment later) - resuming now would run
     * the session twice, so the thread waits until that claude is gone and resumes then.
     */
    @Test
    fun aClaudeOfTheSessionStillRunningIsWaitedFor() {
        val s100 = idleThread()
        ended(10) // the shell went with the tab, claude 100 has not yet

        coordinator.onTabLost("t1", select = false, snap(s100), now = 3_000)
        assertEquals(ThreadState.RunningElsewhere(100), thread().state)
        assertEquals(1, terminal.typed.size)

        ended(100)
        coordinator.onSnapshot(snap(), viewed = { false }, now = 4_000)
        assertEquals("t1" to "claude --resume $A", terminal.typed.last())
        assertEquals(ThreadState.Starting, thread().state)
    }

    /** A status file left in the last snapshot by a claude that is gone already does not hold the resume back. */
    @Test
    fun aStatusFileOfAClaudeAlreadyGoneDoesNotHoldTheResumeBack() {
        val s100 = idleThread()
        ended(100, 10)
        coordinator.onTabLost("t1", select = false, snap(s100), now = 3_000)
        assertEquals("t1" to "claude --resume $A", terminal.typed.last())
    }

    @Test
    fun aThreadWaitingForTheUserIsResumedToo() {
        coordinator.newThread("/p", now = 1_000)
        val s100 = claude(100, under = 10, activity = ClaudeActivity.WAITING, waitingFor = "permission prompt")
        coordinator.onSnapshot(snap(s100), viewed = { false }, now = 2_000)
        assertTrue(thread().state is ThreadState.NeedsYou)
        ended(100, 10)
        coordinator.onTabLost("t1", select = false, snap(), now = 3_000)
        assertEquals("t1" to "claude --resume $A", terminal.typed.last())
    }

    /**
     * A thread whose claude never had a turn has no conversation on disk, and `claude --resume` would only say it found
     * none, so it starts afresh, the session id kept until the new claude brings its own.
     */
    @Test
    fun aSessionWithoutAConversationStartsAPlainClaude() {
        val s100 = idleThread()
        conversations.clear()
        coordinator.endingsBeforeLoss(listOf("t1"), snap(s100))
        ended(100, 10)
        coordinator.onTabLost("t1", select = false, snap(), now = 3_000)
        assertEquals("t1" to "claude", terminal.typed.last())
        assertEquals(ThreadState.Starting, thread().state)
        assertEquals(A, thread().sessionId)
    }

    /** A worktree thread with a session but no conversation starts its worktree again, as one without a session does. */
    @Test
    fun aWorktreeSessionWithoutAConversationStartsItsWorktreeAgain() {
        coordinator.newWorktreeThread("/p", "feat-x", now = 1_000)
        val s100 = claude(100, under = 10, cwd = Worktrees.dirOf("/p", "feat-x"))
        coordinator.onSnapshot(snap(s100), viewed = { false }, now = 2_000)
        assertEquals(A, thread().sessionId)
        conversations.clear()
        ended(100, 10)
        coordinator.onTabLost("t1", select = false, snap(), now = 3_000)
        assertEquals("t1" to "claude --worktree feat-x", terminal.typed.last())
    }

    /** A worktree thread whose claude had no session yet starts its worktree again, as a restored one does. */
    @Test
    fun aWorktreeThreadWithoutASessionYetStartsItsWorktreeAgain() {
        coordinator.newWorktreeThread("/p", "feat-x", now = 1_000)
        assertEquals(ThreadState.Starting, thread().state)
        ended(10)
        coordinator.onTabLost("t1", select = false, snap(), now = 1_500)
        assertEquals("t1" to "claude --worktree feat-x", terminal.typed.last())
        assertEquals("feat-x", thread().worktree)
    }

    @Test
    fun anUnknownThreadChangesNothing() {
        coordinator.onTabLost("nope", select = true, snap(), now = 1_000)
        assertTrue(terminal.opened.isEmpty())
    }

    /**
     * No loss - the user closed a busy thread's tab on its own past the close check, a middle click. Skein ends what Close
     * would end, every thread of it confirmed as for the tab's ×, since the close is the user's own, so the busy one is not
     * left open, and once the tab is gone the thread goes as after Close - its row and the worktree question - and nothing
     * opens it again.
     */
    @Test
    fun aBusyThreadTheUserClosedPastTheCheckIsEndedAndGoes() {
        val s100 = busyThread()

        val ending = coordinator.endingsBeforeTheIdeCloses(listOf("t1"), snap(s100), comesBack = false).single()
        assertEquals(ProcessId(10, t0), ending.shell)
        assertEquals(100L, ending.claude?.pid)
        ended(100, 10)
        coordinator.onTabClosed("t1")

        assertEquals(listOf("t1"), closed)
        assertTrue(coordinator.threads.isEmpty())
        assertEquals(listOf("t1"), terminal.opened.map { it.id })
    }

    /**
     * The user's own close leaves no note on how the thread comes back - the hang-up turns it exited, and should its tab be
     * taken for a lost one after all, it would come back as an exited thread does, not resumed. A clear's ending notes it,
     * decided before the hang-up, and the thread is resumed.
     */
    @Test
    fun theUsersSingleCloseNotesNothingAboutComingBack() {
        val s100 = busyThread()
        coordinator.endingsBeforeTheIdeCloses(listOf("t1"), snap(s100), comesBack = false)
        ended(100)
        coordinator.onSnapshot(snap(), viewed = { false }, now = 2_500)
        assertEquals(ThreadState.Exited, thread().state)
        ended(10)
        val typed = terminal.typed.toList()

        coordinator.onTabLost("t1", select = false, snap(), now = 3_000)

        assertEquals(typed, terminal.typed)
        assertEquals(ThreadState.NotStarted, thread().state)
    }

    @Test
    fun aClearsEndingNotesTheResumeBeforeTheHangUp() {
        val s100 = busyThread()
        coordinator.endingsBeforeTheIdeCloses(listOf("t1"), snap(s100), comesBack = true)
        ended(100)
        coordinator.onSnapshot(snap(), viewed = { false }, now = 2_500)
        ended(10)

        coordinator.onTabLost("t1", select = false, snap(), now = 3_000)

        assertEquals("t1" to "claude --resume $A", terminal.typed.last())
    }

    /** t1 in shell 10 with claude 100 at work in session [A] - busy, so Close would ask, which the user's own close does not. */
    private fun busyThread(): SessionFile {
        coordinator.newThread("/p", now = 1_000)
        val s100 = claude(100, under = 10, activity = ClaudeActivity.BUSY)
        coordinator.onSnapshot(snap(s100), viewed = { false }, now = 2_000)
        assertTrue(thread().state.toString(), CloseRule.isBusy(thread().state))
        return s100
    }

    @Test
    fun theRule() {
        for (state in listOf(ThreadState.NotStarted, ThreadState.RunningElsewhere(7), ThreadState.Exited)) assertFalse(state.toString(), TabLoss.resumesNow(state))
        for (state in listOf(
            ThreadState.Starting, ThreadState.Working, ThreadState.WorkingUnknown, ThreadState.Background, ThreadState.Idle,
            ThreadState.YourTurn(1), ThreadState.NeedsYou(WaitReason.PERMISSION, 1), ThreadState.Unknown,
        )) {
            assertTrue(state.toString(), TabLoss.resumesNow(state))
        }
    }

    private fun saved(id: String) =
        SkeinThread(id = id, folder = "/p", sessionId = A, name = "old", state = ThreadState.Idle, stateSince = 0, createdAt = 5, anchor = null)
}
