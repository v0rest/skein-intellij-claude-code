package io.github.v0rest.skein.threads

import io.github.v0rest.skein.claude.ClaudeActivity
import io.github.v0rest.skein.claude.HistoryEntry
import io.github.v0rest.skein.claude.SessionFile
import io.github.v0rest.skein.claude.SessionSnapshot
import io.github.v0rest.skein.claude.WaitReason
import io.github.v0rest.skein.process.HangUp
import io.github.v0rest.skein.process.ProcessId
import io.github.v0rest.skein.terminal.ClaudeCommand
import io.github.v0rest.skein.terminal.ThreadTerminalListener
import io.github.v0rest.skein.testutil.FakeProcessProbe
import io.github.v0rest.skein.testutil.FakeThreadTerminal
import io.github.v0rest.skein.testutil.sessionFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

private const val A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
private const val D = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
private const val Z = "ffffffff-ffff-4fff-8fff-ffffffffffff"
private const val R = "0123abcd-ef01-4345-8789-abcdef012345"

class ThreadCoordinatorTest {
    private val t0: Instant = Instant.ofEpochMilli(0)
    private val probe = FakeProcessProbe(current = 1)
    private val terminal = FakeThreadTerminal()
    private val slot = LaunchSlot()
    private val notifications = mutableListOf<Pair<String, NotificationKind>>()
    private val viewed = mutableListOf<String>()
    private val closed = mutableListOf<String>()
    private val closedThreads = mutableListOf<SkeinThread>()
    private val leftNeedsYou = mutableListOf<String>()
    private var missingStatusFilesCalls = 0
    private val events = object : CoordinatorEvents {
        override fun notify(thread: SkeinThread, kind: NotificationKind) {
            notifications += thread.id to kind
        }

        override fun viewed(threadId: String) {
            viewed += threadId
        }

        override fun closed(thread: SkeinThread) {
            closed += thread.id
            closedThreads += thread
        }

        override fun leftNeedsYou(threadId: String) {
            leftNeedsYou += threadId
        }

        override fun missingStatusFiles() {
            missingStatusFilesCalls++
        }
    }
    private var nextId = 0
    private val coordinator = ThreadCoordinator(terminal, ThreadMapper(probe), slot, events) { "t${++nextId}" }

    @Before
    fun tree() {
        probe.add(1, parent = null, start = t0) // the IDE
        probe.add(2, parent = null, start = t0) // iTerm
        terminal.onOpen = { spawnShell() } // the first tab opened gets shell 10, the next 11, ...
        terminal.shellsNow = { ThreadMapper(probe).ideChildList() } // what the terminal lists right before typing
    }

    private var nextShell = 10L

    private fun spawnShell() {
        probe.add(nextShell++, parent = 1, start = t0)
    }

    private fun claude(pid: Long, under: Long, startedAt: Long = 1_500, cwd: String = "/p", sessionId: String = A, activity: ClaudeActivity = ClaudeActivity.IDLE, waitingFor: String? = null, name: String? = "acme-api", nameDerived: Boolean = false): SessionFile {
        probe.add(pid, parent = under, start = Instant.ofEpochMilli(startedAt - 100))
        return sessionFile(pid = pid, activity = activity, waitingFor = waitingFor, sessionId = sessionId, cwd = cwd, name = name, startedAt = startedAt, nameDerived = nameDerived)
    }

    private fun snap(vararg files: SessionFile, unrecognized: Set<Long> = emptySet()) =
        SessionSnapshot(files.associateBy { it.pid }, unrecognized)

    private fun thread(id: String = "t1") = coordinator.threads.single { it.id == id }

    private fun startIdleThread(): SessionFile {
        coordinator.newThread("/p", now = 1_000)
        val s100 = claude(100, under = 10)
        coordinator.onSnapshot(snap(s100), viewed = { false }, now = 2_000)
        return s100
    }

    @Test
    fun newThreadTypesPlainClaude() {
        coordinator.newThread("/p", now = 1_000)
        assertEquals(listOf("t1" to "claude"), terminal.typed)
        assertEquals(ThreadState.Starting, thread().state)
        assertEquals("/p", terminal.opened.single().folder)
    }

    @Test
    fun statusFileMapsThreadAndGoesIdle() {
        startIdleThread()
        val t = thread()
        assertEquals(ThreadState.Idle, t.state)
        assertEquals(10L, t.anchor)
        assertEquals(A, t.sessionId)
        assertEquals("acme-api", t.name)
        assertTrue(notifications.isEmpty())
        assertNull(slot.holder(now = 0))
    }

    @Test
    fun nameFallsBackToTheFolderName() {
        coordinator.newThread("/w/acme", now = 1_000)
        assertEquals("acme", thread().name)
        coordinator.onSnapshot(snap(claude(100, under = 10, cwd = "/w/acme", name = null)), viewed = { false }, now = 2_000)
        assertEquals("acme", thread().name)
    }

    /** A name Claude Code made up itself ("acme-c8") is not shown: the thread keeps the folder's name. */
    @Test
    fun aNameClaudeCodeMadeUpShowsTheFolderName() {
        coordinator.newThread("/w/acme", now = 1_000)
        coordinator.onSnapshot(snap(claude(100, under = 10, cwd = "/w/acme", name = "acme-c8", nameDerived = true)), viewed = { false }, now = 2_000)
        assertEquals("acme", thread().name)
        coordinator.onSnapshot(snap(claude(100, under = 10, cwd = "/w/acme", name = "Fix login")), viewed = { false }, now = 3_000)
        assertEquals("Fix login", thread().name)
    }

    /** A thread saved with a made-up name before Skein ignored them drops it once the session says it is made up. */
    @Test
    fun aRestoredMadeUpNameGivesWayToTheFolderName() {
        coordinator.restore(
            listOf(SkeinThread(id = "t1", folder = "/w/acme", sessionId = A, name = "acme-c8", state = ThreadState.Idle, stateSince = 0, createdAt = 5, anchor = null)),
            snap(),
            now = 1_000,
        )
        assertEquals("acme-c8", thread().name)
        coordinator.onTabSelected("t1", snap(), now = 1_500)
        coordinator.onSnapshot(snap(claude(100, under = 10, cwd = "/w/acme", name = "acme-c8", nameDerived = true)), viewed = { false }, now = 2_000)
        assertEquals("acme", thread().name)
    }

    /** A session resumed from the History keeps its title when Claude Code makes a name up for it. */
    @Test
    fun aResumedSessionKeepsItsTitleOverAMadeUpName() {
        val e = HistoryEntry(A, "/w/acme", "Fix login bug", 0, null)
        assertEquals(ResumeOutcome.Opened("t1"), coordinator.resume(e, snap(), now = 1_000))
        coordinator.onSnapshot(snap(claude(100, under = 10, cwd = "/w/acme", name = "acme-c8", nameDerived = true)), viewed = { false }, now = 2_000)
        assertEquals("Fix login bug", thread().name)
    }

    @Test
    fun turnFinishedNotifiesWhenNotViewed() {
        startIdleThread()
        coordinator.onSnapshot(snap(claude(100, under = 10, activity = ClaudeActivity.BUSY)), viewed = { false }, now = 3_000)
        assertEquals(ThreadState.Working, thread().state)
        coordinator.onSnapshot(snap(claude(100, under = 10)), viewed = { false }, now = 4_000)
        assertEquals(ThreadState.YourTurn(4_000), thread().state)
        assertEquals(listOf("t1" to NotificationKind.TURN_FINISHED), notifications)
    }

    @Test
    fun turnFinishedIsQuietWhenViewed() {
        startIdleThread()
        coordinator.onSnapshot(snap(claude(100, under = 10, activity = ClaudeActivity.BUSY)), viewed = { true }, now = 3_000)
        coordinator.onSnapshot(snap(claude(100, under = 10)), viewed = { true }, now = 4_000)
        assertEquals(ThreadState.Idle, thread().state)
        assertTrue(notifications.isEmpty())
    }

    @Test
    fun permissionNotifiesOncePerStop() {
        startIdleThread()
        val waiting = claude(100, under = 10, activity = ClaudeActivity.WAITING, waitingFor = "permission prompt")
        coordinator.onSnapshot(snap(waiting), viewed = { false }, now = 3_000)
        coordinator.onSnapshot(snap(waiting), viewed = { false }, now = 4_000)
        assertEquals(ThreadState.NeedsYou(WaitReason.PERMISSION, 3_000), thread().state)
        assertEquals(listOf("t1" to NotificationKind.NEEDS_YOU), notifications)
    }

    @Test
    fun clearFollowsNewSessionId() {
        startIdleThread()
        coordinator.onSnapshot(snap(claude(100, under = 10, sessionId = D)), viewed = { false }, now = 3_000)
        assertEquals(D, thread().sessionId)
    }

    @Test
    fun claudeExitMarksExited() {
        startIdleThread()
        probe.kill(100)
        coordinator.onSnapshot(snap(), viewed = { false }, now = 3_000)
        assertEquals(ThreadState.Exited, thread().state)
        assertEquals(A, thread().sessionId)
        assertTrue(notifications.isEmpty())
    }

    @Test
    fun relaunchInTabIsFollowed() {
        startIdleThread()
        probe.kill(100)
        coordinator.onSnapshot(snap(), viewed = { false }, now = 3_000)
        coordinator.onSnapshot(snap(claude(101, under = 10, startedAt = 9_000, sessionId = D)), viewed = { false }, now = 10_000)
        assertEquals(ThreadState.Idle, thread().state)
        assertEquals(D, thread().sessionId)
        assertTrue(notifications.isEmpty())
    }

    @Test
    fun selectBringsTheTabForward() {
        startIdleThread()
        coordinator.select("t1", snap(), now = 3_000)
        coordinator.select("nope", snap(), now = 3_000)
        assertEquals(listOf("t1"), terminal.selected)
    }

    @Test
    fun tabCloseRemovesThread() {
        startIdleThread()
        coordinator.onTabClosed("t1")
        assertTrue(coordinator.threads.isEmpty())
    }

    @Test
    fun viewingAThreadReportsItOnce() {
        val s100 = startIdleThread()
        assertEquals(emptyList<String>(), viewed)
        coordinator.onSnapshot(snap(s100), viewed = { true }, now = 3_000)
        coordinator.onSnapshot(snap(s100), viewed = { true }, now = 4_000)
        assertEquals(listOf("t1"), viewed)
        // selecting the tab the user already looks at says nothing new; a thread that comes back into view does
        coordinator.onTabSelected("t1", snap(s100), now = 5_000)
        assertEquals(listOf("t1"), viewed)
        coordinator.onSnapshot(snap(s100), viewed = { false }, now = 6_000)
        coordinator.onSnapshot(snap(s100), viewed = { true }, now = 7_000)
        assertEquals(listOf("t1", "t1"), viewed)
        // opening a tab from the panel or a notification counts at once, not at the next tick
        coordinator.newThread("/p", now = 8_000)
        coordinator.onTabSelected("t2", snap(s100), now = 9_000)
        assertEquals(listOf("t1", "t1", "t2"), viewed)
    }

    @Test
    fun leavingNeedsYouReportsIt() {
        startIdleThread()
        val waiting = claude(100, under = 10, activity = ClaudeActivity.WAITING, waitingFor = "permission prompt")
        coordinator.onSnapshot(snap(waiting), viewed = { false }, now = 3_000)
        coordinator.onSnapshot(snap(waiting), viewed = { false }, now = 4_000)
        assertEquals(emptyList<String>(), leftNeedsYou)
        // the prompt was answered elsewhere: claude works on, the thread no longer needs anyone
        coordinator.onSnapshot(snap(claude(100, under = 10, activity = ClaudeActivity.BUSY)), viewed = { false }, now = 5_000)
        coordinator.onSnapshot(snap(claude(100, under = 10, activity = ClaudeActivity.BUSY)), viewed = { false }, now = 6_000)
        assertEquals(listOf("t1"), leftNeedsYou)
        // a prompt of another kind is a new needs-you, not a leave
        coordinator.onSnapshot(snap(waiting), viewed = { false }, now = 7_000)
        coordinator.onSnapshot(snap(claude(100, under = 10, activity = ClaudeActivity.WAITING, waitingFor = "input needed")), viewed = { false }, now = 8_000)
        assertEquals(listOf("t1"), leftNeedsYou)
        assertEquals(listOf("t1" to NotificationKind.NEEDS_YOU, "t1" to NotificationKind.NEEDS_YOU, "t1" to NotificationKind.NEEDS_YOU), notifications)
    }

    @Test
    fun theSessionsCwdIsKeptOnTheThread() {
        // the folder as the user gave it and the cwd as claude reports it differ in form; the thread keeps both
        coordinator.newThread("/w/acme/", now = 1_000)
        assertNull(thread().sessionCwd)
        coordinator.onSnapshot(snap(claude(100, under = 10, cwd = "/w/acme")), viewed = { false }, now = 2_000)
        assertEquals("/w/acme", thread().sessionCwd)
        assertEquals("/w/acme/", thread().folder)
    }

    @Test
    fun closingAThreadReportsIt() {
        startIdleThread()
        coordinator.onTabClosed("t1")
        coordinator.onTabClosed("nope")
        assertEquals(listOf("t1"), closed)
    }

    /**
     * Close in the thread menu hands back the threads' tabs to close, in one batch so the terminal can close the tab in
     * front last; the threads stay until the tabs report that they closed.
     */
    @Test
    fun closeHandsBackTheThreadsTabsToClose() {
        coordinator.newThread("/w/acme", now = 1_000)
        coordinator.newThread("/w/acme", now = 1_100)
        coordinator.newThread("/w/acme", now = 1_200)
        // all three are starting, so busy: the menu's question named the two it closes
        val outcome = coordinator.close(listOf("t1", "t3", "nope"), confirmed = setOf("t1", "t3"), snap())
        assertEquals(listOf("t1", "t3"), outcome.closing.map { it.tab.id })
        assertTrue(outcome.leftOpen.isEmpty())
        assertEquals(setOf("t1", "t2", "t3"), coordinator.threads.mapTo(HashSet()) { it.id })
        assertTrue(closed.isEmpty())
        // only unknown ids: nothing to close
        assertEquals(CloseOutcome(emptyList(), emptyList()), coordinator.close(listOf("nope"), confirmed = setOf("nope"), snap()))
        // the coordinator never closes a tab itself: the close sequence does, after ending the thread
        assertTrue(terminal.closed.isEmpty())
        // a tab that did close goes through the usual close path; one that has not closed (yet) stays
        coordinator.onTabClosed("t1")
        assertEquals(listOf("t1"), closed)
        assertEquals(setOf("t2", "t3"), coordinator.threads.mapTo(HashSet()) { it.id })
    }

    /**
     * The menu asks before the close and names the threads busy then; one that turned busy since (idle at the menu,
     * working at the close) is not closed unasked, and the others still close.
     */
    @Test
    fun closeLeavesOpenAThreadThatTurnedBusyAfterTheQuestion() {
        startIdleThread() // t1, idle when the menu asked nothing about it
        coordinator.restore(listOf(saved("r1", null)), snap(), now = 2_100) // not started: closes unasked
        coordinator.newThread("/w/acme", now = 2_200) // t2, starting: busy, and named in the question
        val working = claude(100, under = 10, activity = ClaudeActivity.BUSY)
        coordinator.onSnapshot(snap(working), viewed = { false }, now = 3_000)
        assertEquals(ThreadState.Working, thread("t1").state)
        val outcome = coordinator.close(listOf("t1", "r1", "t2"), confirmed = setOf("t2"), snap(working))
        assertEquals(listOf("r1", "t2"), outcome.closing.map { it.tab.id })
        assertEquals(listOf("t1"), outcome.leftOpen.map { it.id })
        // nothing to close at all
        val none = coordinator.close(listOf("t1"), confirmed = emptySet(), snap(working))
        assertEquals(listOf("t1"), none.leftOpen.map { it.id })
        assertTrue(none.closing.isEmpty())
    }

    /** Before its tab closes, a thread's claude (from its live session file) and the tab's shell are hung up. */
    @Test
    fun closeHangsUpTheThreadsClaudeAndShell() {
        val s100 = startIdleThread()
        val ending = coordinator.close(listOf("t1"), confirmed = emptySet(), snap(s100)).closing.single()
        assertEquals("t1", ending.tab.id)
        // each with its start instant, so a later process that gets the same pid is not taken for it
        assertEquals(ProcessId(10, t0), ending.shell)
        assertEquals(ProcessId(100, Instant.ofEpochMilli(1_400)), ending.claude)
    }

    /** claude exited and only the shell is left: the shell alone is hung up. */
    @Test
    fun closeAfterClaudeExitedHangsUpTheShellAlone() {
        startIdleThread()
        probe.kill(100)
        coordinator.onSnapshot(snap(), viewed = { false }, now = 3_000)
        assertEquals(ThreadState.Exited, thread().state)
        val ending = coordinator.close(listOf("t1"), confirmed = emptySet(), snap()).closing.single()
        assertEquals(ProcessId(10, t0), ending.shell)
        assertNull(ending.claude)
    }

    /** A tab whose shell Skein never learned (its claude not matched yet) has nothing to hang up and closes as before. */
    @Test
    fun closeWithoutAnAnchorHangsUpNothing() {
        coordinator.newThread("/w/acme", now = 1_000)
        val ending = coordinator.close(listOf("t1"), confirmed = setOf("t1"), snap()).closing.single()
        assertNull(ending.shell)
        assertNull(ending.claude)
    }

    /** A session running in another terminal is never this thread's to hang up, even with the same session id. */
    @Test
    fun closeNeverHangsUpAClaudeInAnotherTerminal() {
        val elsewhere = claude(300, under = 2, sessionId = A) // iTerm's
        coordinator.restore(listOf(saved("r1", A)), snap(elsewhere), now = 1_000)
        assertEquals(ThreadState.RunningElsewhere(300), thread("r1").state)
        val ending = coordinator.close(listOf("r1"), confirmed = emptySet(), snap(elsewhere)).closing.single()
        assertNull(ending.shell)
        assertNull(ending.claude)
    }

    /** The whole menu Close in order through the sequence: hang up claude, then the shell, wait for the tab, then close it. */
    @Test
    fun closeEndsTheThreadBeforeItsTabCloses() {
        val s100 = startIdleThread()
        val outcome = coordinator.close(listOf("t1"), confirmed = emptySet(), snap(s100))
        // claude and the shell obey the hang-up and leave the process tree
        val hangUp = HangUp { pid ->
            terminal.calls += "hup $pid"
            probe.kill(pid)
            true
        }
        runBlocking { CloseSequence(hangUp, terminal, ThreadMapper(probe), timeoutMillis = 2_000) {}.run(outcome.closing) }
        assertEquals(listOf("ending t1", "hup 100", "hup 10", "await t1", "close t1"), terminal.calls)
        // the thread stays until its tab reports the close, which then runs the usual close path
        assertEquals(listOf("t1"), coordinator.threads.map { it.id })
    }

    /**
     * A Close All over two running threads and a restored one never opened: both running threads are ended, all three
     * tabs close in one batch, the one in front last, and nothing is launched. When the tab in front goes, the IDE
     * selects its neighbour, another restored thread outside the batch; that is Skein's own doing, not the user opening
     * it, so it does not start `claude --resume` either.
     */
    @Test
    fun aBatchEndsItsRunningThreadsAndClosesAllItsTabsAtOnceLaunchingNothing() {
        val s100 = startIdleThread() // t1: shell 10, claude 100
        coordinator.newThread("/q", now = 2_100) // t2: shell 11
        val s101 = claude(101, under = 11, startedAt = 2_500, cwd = "/q", sessionId = D)
        coordinator.onSnapshot(snap(s100, s101), viewed = { false }, now = 3_000)
        coordinator.restore(listOf(saved("r1", R), saved("r2", Z)), snap(s100, s101), now = 3_100)
        terminal.notRunning += setOf("r1", "r2") // their shells start only when the user first opens them
        assertEquals(10L, thread("t1").anchor)
        assertEquals(11L, thread("t2").anchor)
        // the editor shows t1, t2, r1, r2 with t2 in front; the service hands the IDE's selections to the coordinator
        assertEquals(listOf("t1", "t2", "r1", "r2"), terminal.tabRow)
        terminal.front = "t2"
        terminal.setListener(
            object : ThreadTerminalListener {
                override fun tabClosed(tabId: String) = coordinator.onTabClosed(tabId)

                override fun tabSelected(tabId: String) = coordinator.onTabSelected(tabId, snap(), now = 4_000)

                override fun closeRequested(tabIds: List<String>): Boolean = false
            },
        )
        val typed = terminal.typed.toList()
        val ids = listOf("t1", "r1", "t2")
        val outcome = coordinator.close(ids, confirmed = ids.toSet(), snap(s100, s101))
        val hangUp = HangUp { pid ->
            terminal.calls += "hup $pid"
            probe.kill(pid)
            true
        }
        runBlocking { CloseSequence(hangUp, terminal, ThreadMapper(probe), timeoutMillis = 2_000) {}.run(outcome.closing) }
        assertEquals(
            listOf("ending t1,t2", "hup 100", "hup 10", "await t1", "hup 101", "hup 11", "await t2", "close t1,r1,t2"),
            terminal.calls,
        )
        // the IDE went on to r2, and nothing was typed anywhere
        assertEquals("r2", terminal.front)
        assertEquals(typed, terminal.typed)
        assertEquals(ThreadState.NotStarted, thread("r1").state)
        assertEquals(ThreadState.NotStarted, thread("r2").state)
    }

    /**
     * The user opens a plain IDE terminal in the thread's folder right after Skein typed `claude` and starts claude
     * there before the thread's own one shows up. For the thread's status that claude is taken as the thread's, as it
     * always was; but its shell was not among the IDE's processes when Skein typed, so a Close hangs up nothing there,
     * and the log says why.
     */
    @Test
    fun aTerminalTheUserOpenedAfterTheTypingIsNeverHungUp() {
        coordinator.newThread("/p", now = 1_000) // tab t1, its shell 10; the processes are listed as Skein types
        probe.add(20, parent = 1, start = Instant.ofEpochMilli(1_200)) // the user's terminal, opened just after
        val foreign = claude(200, under = 20, startedAt = 1_500)
        coordinator.onSnapshot(snap(foreign), viewed = { false }, now = 2_000)
        assertEquals(20L, thread().anchor)
        assertEquals(ThreadState.Idle, thread().state)
        val ending = coordinator.close(listOf("t1"), confirmed = setOf("t1"), snap(foreign)).closing.single()
        assertNull(ending.shell)
        assertNull(ending.claude)
        val logged = mutableListOf<String>()
        runBlocking { CloseSequence(signalNothing(), terminal, ThreadMapper(probe), timeoutMillis = 2_000) { logged += it }.run(listOf(ending)) }
        assertEquals(listOf("close t1"), terminal.calls)
        assertEquals(1, logged.size)
        assertTrue(logged.single(), logged.single().contains("pid 20"))
    }

    /**
     * The JDK reports no start for the tab's shell (or one far off the wall clock, as on Linux after a suspend): the
     * thread is matched and follows its claude all the same; only a Close, unable to prove the shell, hangs up nothing.
     */
    @Test
    fun aShellWithoutAStartStillGetsItsStatusButNoSignal() {
        terminal.onOpen = {
            spawnShell()
            probe.unknownStart += 10L
        }
        coordinator.newThread("/p", now = 1_000)
        val s100 = claude(100, under = 10)
        coordinator.onSnapshot(snap(s100), viewed = { false }, now = 2_000)
        assertEquals(10L, thread().anchor)
        assertEquals(ThreadState.Idle, thread().state)
        coordinator.onSnapshot(snap(claude(100, under = 10, activity = ClaudeActivity.BUSY)), viewed = { false }, now = 3_000)
        assertEquals(ThreadState.Working, thread().state)
        val ending = coordinator.close(listOf("t1"), confirmed = setOf("t1"), snap(s100)).closing.single()
        assertNull(ending.shell)
        assertTrue(ending.doubt.orEmpty(), ending.doubt.orEmpty().contains("cannot be read now"))
        runBlocking { CloseSequence(signalNothing(), terminal, ThreadMapper(probe), timeoutMillis = 2_000) {}.run(listOf(ending)) }
        assertEquals(listOf("close t1"), terminal.calls)
    }

    /**
     * Listing the IDE's processes only serves the trust a Close needs: when it fails, the tick still matches the thread
     * and follows its claude, and the Close that cannot prove the shell hangs up nothing.
     */
    @Test
    fun aFailingProcessListingNeverCostsTheTicksMatching() {
        terminal.onOpen = { spawnShell() }
        coordinator.newThread("/p", now = 1_000)
        probe.childrenFail = true
        coordinator.onSnapshot(snap(claude(100, under = 10)), viewed = { false }, now = 2_000)
        assertEquals(10L, thread().anchor)
        assertEquals(ThreadState.Idle, thread().state)
        coordinator.onSnapshot(snap(claude(100, under = 10, activity = ClaudeActivity.BUSY)), viewed = { false }, now = 3_000)
        assertEquals(ThreadState.Working, thread().state)
    }

    /**
     * A shell whose start instant is far off the wall clock (on Linux the JDK's start instants drift after a suspend or
     * an NTP step) is matched as always and, being the very process that was there when Skein typed, gets the whole
     * close: only start instants are compared, never with the wall clock.
     */
    @Test
    fun aShellWithADriftedStartIsMatchedAndHungUpAsAlways() {
        val drifted = Instant.parse("2031-01-01T00:00:00Z")
        terminal.onOpen = { probe.add(10, parent = 1, start = drifted) }
        coordinator.newThread("/p", now = 1_000)
        val s100 = claude(100, under = 10)
        coordinator.onSnapshot(snap(s100), viewed = { false }, now = 2_000)
        assertEquals(10L, thread().anchor)
        assertEquals(ThreadState.Idle, thread().state)
        val ending = coordinator.close(listOf("t1"), confirmed = setOf("t1"), snap(s100)).closing.single()
        assertEquals(ProcessId(10, drifted), ending.shell)
        val hangUp = HangUp { pid ->
            terminal.calls += "hup $pid"
            probe.kill(pid)
            true
        }
        runBlocking { CloseSequence(hangUp, terminal, ThreadMapper(probe), timeoutMillis = 2_000) {}.run(listOf(ending)) }
        assertEquals(listOf("ending t1", "hup 100", "hup 10", "await t1", "close t1"), terminal.calls)
    }

    /** A pid that left the shell's place to another process after Skein typed is not that shell. */
    @Test
    fun aShellPidTakenOverByAnotherProcessIsNotHungUp() {
        val s100 = startIdleThread()
        probe.kill(100)
        probe.kill(10)
        probe.add(10, parent = 1, start = Instant.ofEpochMilli(50_000)) // someone else's, same pid
        val ending = coordinator.close(listOf("t1"), confirmed = setOf("t1"), snap(s100)).closing.single()
        assertNull(ending.shell)
        assertTrue(ending.doubt.orEmpty(), ending.doubt.orEmpty().contains("no longer"))
    }

    /** A shell whose start could not be read when Skein typed, though it can be now, is not proved; the log says which. */
    @Test
    fun aShellWhoseStartWasUnreadableWhenSkeinTypedIsNotHungUp() {
        terminal.onOpen = {
            spawnShell()
            probe.unknownStart += 10L
        }
        coordinator.newThread("/p", now = 1_000)
        probe.unknownStart -= 10L
        val s100 = claude(100, under = 10)
        coordinator.onSnapshot(snap(s100), viewed = { false }, now = 2_000)
        assertEquals(ThreadState.Idle, thread().state)
        val ending = coordinator.close(listOf("t1"), confirmed = setOf("t1"), snap(s100)).closing.single()
        assertNull(ending.shell)
        assertTrue(ending.doubt.orEmpty(), ending.doubt.orEmpty().contains("could not be read when Skein typed"))
    }

    /** Restored threads in these tests start their shells only when first shown, as the real terminal's do. */
    private fun restoreLazily(vararg saved: SkeinThread) {
        terminal.lazyShells = true
        coordinator.restore(saved.toList(), snap(), now = 1_000)
        assertTrue(probe.children(1).isEmpty())
    }

    /**
     * After the IDE restored a thread, the user opens a plain terminal in its folder; later the user opens the thread,
     * whose shell starts only now, and the claude in the plain terminal shows up before the thread's own (a slow
     * resume). The thread may take that claude for its own, as it always could; but the plain terminal's shell was there
     * before the thread's shell started, so a Close hangs up nothing in it.
     */
    @Test
    fun aRestoredThreadNeverHangsUpATerminalOpenedBeforeItsShell() {
        restoreLazily(saved("r1", A))
        probe.add(20, parent = 1, start = Instant.ofEpochMilli(1_100)) // the user's terminal, after the restore
        coordinator.onSnapshot(snap(), viewed = { false }, now = 2_000) // a tick: r1's shell has not started
        terminal.show("r1") // the user opens r1: its shell 10 starts
        coordinator.onTabSelected("r1", snap(), now = 3_000)
        assertEquals(listOf("r1" to "claude --resume $A"), terminal.typed)
        val foreign = claude(200, under = 20, startedAt = 3_500, sessionId = D)
        coordinator.onSnapshot(snap(foreign), viewed = { false }, now = 4_000)
        assertEquals(20L, thread("r1").anchor)
        val ending = coordinator.close(listOf("r1"), confirmed = setOf("r1"), snap(foreign)).closing.single()
        assertNull(ending.shell)
        assertTrue(ending.doubt.orEmpty(), ending.doubt.orEmpty().contains("was open before the tab's own shell started"))
        runBlocking { CloseSequence(signalNothing(), terminal, ThreadMapper(probe), timeoutMillis = 2_000) {}.run(listOf(ending)) }
        assertEquals(listOf("close r1"), terminal.calls)
    }

    /**
     * A restored thread shown at startup starts its shell at once but is launched only when the user clicks into it; a
     * plain terminal opened in between, with a claude that shows up first, is not the thread's either: it was not there
     * when the thread's session was first seen started.
     */
    @Test
    fun aRestoredThreadNeverHangsUpATerminalOpenedAfterItsShell() {
        restoreLazily(saved("r1", A))
        terminal.show("r1") // in front at startup: its shell 10 starts
        coordinator.onSnapshot(snap(), viewed = { false }, now = 2_000) // the tick that sees it started
        probe.add(20, parent = 1, start = Instant.ofEpochMilli(2_100)) // the user's terminal, minutes before the click
        coordinator.onSnapshot(snap(), viewed = { false }, now = 3_000)
        coordinator.onTabSelected("r1", snap(), now = 4_000)
        val foreign = claude(200, under = 20, startedAt = 4_500, sessionId = D)
        coordinator.onSnapshot(snap(foreign), viewed = { false }, now = 5_000)
        assertEquals(20L, thread("r1").anchor)
        val ending = coordinator.close(listOf("r1"), confirmed = setOf("r1"), snap(foreign)).closing.single()
        assertNull(ending.shell)
        assertTrue(ending.doubt.orEmpty(), ending.doubt.orEmpty().contains("not there yet when the tab's session started"))
    }

    /** The usual restored thread: its own shell starts when it is first shown, and a Close hangs up claude and shell. */
    @Test
    fun aRestoredThreadHangsUpItsOwnShellStartedAtFirstShow() {
        restoreLazily(saved("r1", A))
        probe.add(20, parent = 1, start = Instant.ofEpochMilli(1_100)) // another terminal, not the thread's
        coordinator.onSnapshot(snap(), viewed = { false }, now = 2_000)
        terminal.show("r1")
        coordinator.onTabSelected("r1", snap(), now = 3_000)
        val own = claude(100, under = 10, startedAt = 3_500, sessionId = A)
        coordinator.onSnapshot(snap(own), viewed = { false }, now = 4_000)
        assertEquals(10L, thread("r1").anchor)
        val ending = coordinator.close(listOf("r1"), confirmed = setOf("r1"), snap(own)).closing.single()
        assertEquals(ProcessId(10, t0), ending.shell)
        assertNull(ending.doubt)
        val hangUp = HangUp { pid ->
            terminal.calls += "hup $pid"
            probe.kill(pid)
            true
        }
        runBlocking { CloseSequence(hangUp, terminal, ThreadMapper(probe), timeoutMillis = 2_000) {}.run(listOf(ending)) }
        assertEquals(listOf("ending r1", "hup 100", "hup 10", "await r1", "close r1"), terminal.calls)
    }

    private fun signalNothing() = HangUp { pid -> throw AssertionError("signalled $pid") }

    @Test
    fun tabCloseWhileLaunchingFreesTheSlot() {
        coordinator.newThread("/p", now = 1_000)
        assertEquals("t1", slot.holder(now = 0))
        coordinator.onTabClosed("t1")
        assertNull(slot.holder(now = 0))
    }

    @Test
    fun viewingTheTabClearsYourTurn() {
        startIdleThread()
        coordinator.onSnapshot(snap(claude(100, under = 10, activity = ClaudeActivity.BUSY)), viewed = { false }, now = 3_000)
        coordinator.onSnapshot(snap(claude(100, under = 10)), viewed = { false }, now = 4_000)
        coordinator.onTabSelected("t1", snap(claude(100, under = 10)), now = 5_000)
        assertEquals(ThreadState.Idle, thread().state)
    }

    private fun saved(id: String, sessionId: String?, folder: String = "/p", worktree: String? = null) =
        SkeinThread(id = id, folder = folder, sessionId = sessionId, name = "old", state = ThreadState.Idle, stateSince = 0, createdAt = 5, anchor = null, worktree = worktree)

    @Test
    fun restoreIsLazy() {
        coordinator.restore(listOf(saved("r1", A)), snap(), now = 1_000)
        assertEquals(ThreadState.NotStarted, thread("r1").state)
        assertTrue(terminal.typed.isEmpty())
        assertEquals("r1", terminal.opened.single().id)
        coordinator.onTabSelected("r1", snap(), now = 2_000)
        assertEquals(listOf("r1" to "claude --resume $A"), terminal.typed)
        assertEquals(ThreadState.Starting, thread("r1").state)
        coordinator.onSnapshot(snap(claude(100, under = 10, startedAt = 2_500)), viewed = { true }, now = 3_000)
        assertEquals(ThreadState.Idle, thread("r1").state)
        assertEquals(10L, thread("r1").anchor)
    }

    @Test
    fun restoredThreadWithoutSessionStartsPlainClaude() {
        coordinator.restore(listOf(saved("r1", null)), snap(), now = 1_000)
        coordinator.onTabSelected("r1", snap(), now = 2_000)
        assertEquals(listOf("r1" to "claude"), terminal.typed)
    }

    @Test
    fun restoredThreadWithAnInvalidSessionIdStartsPlainClaude() {
        coordinator.restore(listOf(saved("r1", "x; touch pwned")), snap(), now = 1_000)
        assertNull(thread("r1").sessionId)
        coordinator.onTabSelected("r1", snap(), now = 2_000)
        assertEquals(listOf("r1" to "claude"), terminal.typed)
    }

    @Test
    fun statusFileWithAnInvalidSessionIdKeepsTheLastValidOne() {
        startIdleThread()
        coordinator.onSnapshot(snap(claude(100, under = 10, sessionId = "\$(id)")), viewed = { false }, now = 3_000)
        assertEquals(A, thread().sessionId)
    }

    @Test
    fun resumeOfAnInvalidSessionIdIsRefused() {
        assertEquals(ResumeOutcome.Refused, coordinator.resume(entry("x; touch pwned"), snap(), now = 1_000))
        assertTrue(terminal.opened.isEmpty())
        assertTrue(terminal.typed.isEmpty())
    }

    @Test
    fun restoreSkipsSessionRunningElsewhere() {
        val foreign = claude(200, under = 2, sessionId = A)
        coordinator.restore(listOf(saved("r1", A)), snap(foreign), now = 1_000)
        assertEquals(ThreadState.RunningElsewhere(200), thread("r1").state)
        coordinator.onTabSelected("r1", snap(foreign), now = 2_000)
        assertTrue(terminal.typed.isEmpty())
        probe.kill(200)
        coordinator.onSnapshot(snap(), viewed = { false }, now = 3_000)
        assertEquals(ThreadState.NotStarted, thread("r1").state)
    }

    private fun entry(sessionId: String = A, cwd: String = "/p") = HistoryEntry(sessionId, cwd, "title", 0, null)

    @Test
    fun resumeOwnThreadSelectsIt() {
        startIdleThread()
        assertEquals(ResumeOutcome.Selected("t1"), coordinator.resume(entry(A), snap(claude(100, under = 10)), now = 3_000))
        assertEquals(listOf("t1"), terminal.selected)
        assertEquals(1, terminal.opened.size)
    }

    @Test
    fun resumeForeignLiveSessionAsksFirst() {
        val foreign = claude(200, under = 2, sessionId = A)
        assertEquals(ResumeOutcome.RunningElsewhere(200), coordinator.resume(entry(A), snap(foreign), now = 1_000))
        assertTrue(terminal.opened.isEmpty())
    }

    @Test
    fun resumeForeignLiveSessionWithForceOpens() {
        val foreign = claude(200, under = 2, sessionId = A)
        assertEquals(ResumeOutcome.Opened("t1"), coordinator.resume(entry(A), snap(foreign), now = 1_000, force = true))
        assertEquals(listOf("t1" to "claude --resume $A"), terminal.typed)
    }

    @Test
    fun resumeOpensNewThreadInTheEntryFolder() {
        assertEquals(ResumeOutcome.Opened("t1"), coordinator.resume(entry(A, cwd = "/p/sub"), snap(), now = 1_000))
        assertEquals("/p/sub", terminal.opened.single().folder)
        assertEquals(listOf("t1" to "claude --resume $A"), terminal.typed)
        assertEquals(ThreadState.Starting, thread().state)
        coordinator.onSnapshot(snap(claude(100, under = 10, cwd = "/p/sub", sessionId = A)), viewed = { true }, now = 2_000)
        assertEquals(ThreadState.Idle, thread().state)
    }

    @Test
    fun unrecognizedStatusFileShowsUnknown() {
        startIdleThread()
        coordinator.onSnapshot(snap(unrecognized = setOf(100)), viewed = { false }, now = 3_000)
        assertEquals(ThreadState.Unknown, thread().state)
        assertTrue(notifications.isEmpty())
    }

    @Test
    fun launchWithoutStatusFileBecomesUnknownAfterTimeout() {
        coordinator.newThread("/p", now = 1_000)
        coordinator.onSnapshot(snap(), viewed = { false }, now = 31_000)
        assertEquals(ThreadState.Starting, thread().state)
        coordinator.onSnapshot(snap(), viewed = { false }, now = 31_001)
        assertEquals(ThreadState.Unknown, thread().state)
        assertEquals(1, missingStatusFilesCalls)
        assertNull(slot.holder(now = 0))
        coordinator.newThread("/p", now = 40_000)
        coordinator.onSnapshot(snap(), viewed = { false }, now = 80_000)
        assertEquals(1, missingStatusFilesCalls)
    }

    @Test
    fun lateStatusFileStillMapsAnUnknownLaunch() {
        coordinator.newThread("/p", now = 1_000)
        coordinator.onSnapshot(snap(), viewed = { false }, now = 31_001)
        assertEquals(ThreadState.Unknown, thread().state)
        coordinator.onSnapshot(snap(claude(100, under = 10, startedAt = 35_000)), viewed = { false }, now = 36_000)
        assertEquals(ThreadState.Idle, thread().state)
    }

    @Test
    fun foreignClaudeInTheSameFolderIsNotAdopted() {
        coordinator.newThread("/p", now = 1_000)
        coordinator.onSnapshot(snap(claude(200, under = 2, cwd = "/p", startedAt = 1_500)), viewed = { false }, now = 2_000)
        assertEquals(ThreadState.Starting, thread().state)
        assertNull(thread().anchor)
    }

    @Test
    fun launchSlotSerializesAcrossCoordinators() {
        val otherTerminal = FakeThreadTerminal().apply { onOpen = { spawnShell() } }
        val other = ThreadCoordinator(otherTerminal, ThreadMapper(probe), slot, events) { "o1" }
        coordinator.newThread("/p", now = 1_000)
        other.newThread("/p", now = 1_001)
        assertEquals(listOf("t1" to "claude"), terminal.typed)
        assertTrue("the second window must wait for the slot", otherTerminal.typed.isEmpty())
        assertEquals(ThreadState.Starting, other.threads.single().state)
        val s100 = claude(100, under = 10)
        coordinator.onSnapshot(snap(s100), viewed = { false }, now = 2_000)
        assertTrue("still waiting until it sees the slot free", otherTerminal.typed.isEmpty())
        other.onSnapshot(snap(s100), viewed = { false }, now = 2_001)
        assertEquals(listOf("o1" to "claude"), otherTerminal.typed)
        assertEquals("o1", slot.holder(now = 2_001))
        // the second coordinator must not adopt the first one's process even though it is in the same folder
        assertNull(other.threads.single().anchor)
        other.onSnapshot(snap(s100, claude(101, under = 11, startedAt = 2_500, sessionId = Z)), viewed = { false }, now = 3_000)
        assertEquals(11L, other.threads.single().anchor)
        assertNull(slot.holder(now = 3_000))
    }

    @Test
    fun aTimedOutLaunchDoesNotStealTheNextThreadsProcess() {
        coordinator.newThread("/p", now = 1_000)
        coordinator.onSnapshot(snap(), viewed = { false }, now = 31_001)
        assertEquals(ThreadState.Unknown, thread("t1").state)
        coordinator.newThread("/p", now = 40_000)
        coordinator.onSnapshot(snap(claude(101, under = 11, startedAt = 41_000, sessionId = Z)), viewed = { false }, now = 42_000)
        assertEquals(11L, thread("t2").anchor)
        assertEquals(ThreadState.Idle, thread("t2").state)
        assertNull(thread("t1").anchor)
        assertEquals(ThreadState.Unknown, thread("t1").state)
    }

    @Test
    fun lateMatchStopsAfterTheGraceWindow() {
        coordinator.newThread("/p", now = 1_000)
        coordinator.onSnapshot(snap(), viewed = { false }, now = 31_001)
        coordinator.onSnapshot(snap(), viewed = { false }, now = 121_001)
        coordinator.onSnapshot(snap(claude(100, under = 10, startedAt = 125_000)), viewed = { false }, now = 126_000)
        assertNull(thread().anchor)
        assertEquals(ThreadState.Unknown, thread().state)
    }

    @Test
    fun aLateBusyResumeStillCountsAsStartingUp() {
        coordinator.restore(listOf(saved("r1", A)), snap(), now = 1_000)
        coordinator.onTabSelected("r1", snap(), now = 2_000)
        coordinator.onSnapshot(snap(), viewed = { false }, now = 32_001)
        assertEquals(ThreadState.Unknown, thread("r1").state)
        coordinator.onSnapshot(snap(claude(100, under = 10, startedAt = 40_000, activity = ClaudeActivity.BUSY)), viewed = { false }, now = 41_000)
        coordinator.onSnapshot(snap(claude(100, under = 10, startedAt = 40_000)), viewed = { false }, now = 42_000)
        assertEquals(ThreadState.Idle, thread("r1").state)
        assertTrue("a resume that was slow to start announced a turn", notifications.isEmpty())
    }

    @Test
    fun missingStatusFilesIsNotReportedOnceAnyThreadGotAFile() {
        startIdleThread()
        coordinator.newThread("/p", now = 10_000)
        coordinator.onSnapshot(snap(claude(100, under = 10)), viewed = { false }, now = 40_001)
        assertEquals(ThreadState.Unknown, thread("t2").state)
        assertEquals(0, missingStatusFilesCalls)
    }

    @Test
    fun selectStartsANotStartedThread() {
        coordinator.restore(listOf(saved("r1", A)), snap(), now = 1_000)
        coordinator.select("r1", snap(), now = 2_000)
        assertEquals(listOf("r1"), terminal.selected)
        assertEquals(listOf("r1" to "claude --resume $A"), terminal.typed)
    }

    @Test
    fun resumingTheSessionOfAnExitedThreadRelaunchesItInItsTab() {
        startIdleThread()
        probe.kill(100)
        coordinator.onSnapshot(snap(), viewed = { false }, now = 3_000)
        assertEquals(ThreadState.Exited, thread().state)
        assertEquals(ResumeOutcome.Selected("t1"), coordinator.resume(entry(A), snap(), now = 4_000))
        assertEquals(listOf("t1" to "claude", "t1" to "claude --resume $A"), terminal.typed)
        assertEquals(ThreadState.Starting, thread().state)
        coordinator.onSnapshot(snap(), viewed = { false }, now = 5_000)
        assertEquals("a relaunch in flight is starting, not exited", ThreadState.Starting, thread().state)
        // after 30 s it is unknown, but a claude may still be starting: a history click must not type again
        coordinator.onSnapshot(snap(), viewed = { false }, now = 34_001)
        assertEquals(ThreadState.Unknown, thread().state)
        coordinator.resume(entry(A), snap(), now = 35_000)
        assertEquals("no second relaunch while one may still be starting", 2, terminal.typed.size)
        // after the two-minute window the tab reads exited again, and history can relaunch it
        coordinator.onSnapshot(snap(), viewed = { false }, now = 124_001)
        assertEquals(ThreadState.Exited, thread().state)
        assertEquals(ResumeOutcome.Selected("t1"), coordinator.resume(entry(A), snap(), now = 125_000))
        assertEquals("a history click relaunches again", 3, terminal.typed.size)
        // a relaunch that shows up after the 30 s mark and busy first is still starting up, not a finished turn
        coordinator.onSnapshot(snap(), viewed = { false }, now = 155_001)
        assertEquals(ThreadState.Unknown, thread().state)
        coordinator.onSnapshot(snap(claude(101, under = 10, startedAt = 155_500, activity = ClaudeActivity.BUSY)), viewed = { false }, now = 156_000)
        coordinator.onSnapshot(snap(claude(101, under = 10, startedAt = 155_500)), viewed = { false }, now = 157_000)
        assertEquals(ThreadState.Idle, thread().state)
        assertTrue("no false finished-turn", notifications.isEmpty())
    }

    @Test
    fun aQueuedRelaunchShowsStartingAndIsNotTypedTwice() {
        startIdleThread()
        probe.kill(100)
        coordinator.onSnapshot(snap(), viewed = { false }, now = 3_000)
        coordinator.newThread("/p", now = 3_500) // holds the launch slot
        assertEquals(ResumeOutcome.Selected("t1"), coordinator.resume(entry(A), snap(), now = 4_000))
        coordinator.onSnapshot(snap(), viewed = { false }, now = 4_500)
        assertEquals("queued behind the slot, still starting", ThreadState.Starting, thread("t1").state)
        // t2's claude shows up, the slot frees, and t1's queued relaunch is typed
        coordinator.onSnapshot(snap(claude(101, under = 11, startedAt = 4_000, sessionId = Z)), viewed = { false }, now = 5_000)
        assertEquals(listOf("t1" to "claude", "t2" to "claude", "t1" to "claude --resume $A"), terminal.typed)
        // a second history click while that claude is starting must not type it again
        coordinator.onSnapshot(snap(claude(101, under = 11, startedAt = 4_000, sessionId = Z)), viewed = { false }, now = 5_500)
        coordinator.resume(entry(A), snap(claude(101, under = 11, startedAt = 4_000, sessionId = Z)), now = 6_000)
        assertEquals(listOf("t1" to "claude", "t2" to "claude", "t1" to "claude --resume $A"), terminal.typed)
        assertEquals(ThreadState.Starting, thread("t1").state)
    }

    @Test
    fun selectingAYourTurnThreadClearsIt() {
        startIdleThread()
        coordinator.onSnapshot(snap(claude(100, under = 10, activity = ClaudeActivity.BUSY)), viewed = { false }, now = 3_000)
        coordinator.onSnapshot(snap(claude(100, under = 10)), viewed = { false }, now = 4_000)
        assertEquals(ThreadState.YourTurn(4_000), thread().state)
        coordinator.select("t1", snap(claude(100, under = 10)), now = 5_000)
        assertEquals(ThreadState.Idle, thread().state)
    }

    @Test
    fun aSlowFirstClaudeIsNotAdoptedByALaterTabInTheSameFolder() {
        // t1's claude registers only after t1 timed out and the user opened t2 in the same folder
        coordinator.newThread("/p", now = 1_000)
        coordinator.onSnapshot(snap(), viewed = { false }, now = 31_001)
        coordinator.newThread("/p", now = 40_000)
        val slowFirst = claude(100, under = 10, startedAt = 41_000, sessionId = A)
        coordinator.onSnapshot(snap(slowFirst), viewed = { false }, now = 41_500)
        assertEquals("t2 must not take t1's claude", null, thread("t2").anchor)
        val second = claude(101, under = 11, startedAt = 42_000, sessionId = Z)
        coordinator.onSnapshot(snap(slowFirst, second), viewed = { false }, now = 42_500)
        assertEquals(11L, thread("t2").anchor)
        assertEquals(10L, thread("t1").anchor)
    }

    @Test
    fun forgettingTheCoordinatorReleasesItsSlotAndAnchors() {
        startIdleThread()
        coordinator.newThread("/p", now = 3_000)
        assertEquals("t2", slot.holder(now = 3_000))
        coordinator.dispose()
        assertNull(slot.holder(now = 3_001))
        assertTrue(slot.ownedAnchors().isEmpty())
    }

    @Test
    fun secondThreadWaitsForTheSlotAndThenTypes() {
        coordinator.newThread("/p", now = 1_000)
        coordinator.newThread("/p", now = 1_001)
        assertEquals(listOf("t1" to "claude"), terminal.typed)
        coordinator.onSnapshot(snap(claude(100, under = 10)), viewed = { false }, now = 2_000)
        assertEquals(listOf("t1" to "claude", "t2" to "claude"), terminal.typed)
        coordinator.onSnapshot(snap(claude(100, under = 10), claude(101, under = 11, startedAt = 2_500, sessionId = Z)), viewed = { false }, now = 3_000)
        assertEquals(11L, thread("t2").anchor)
    }

    @Test
    fun threadsAreOrderedWaitingFirst() {
        coordinator.newThread("/p", now = 1_000)
        coordinator.onSnapshot(snap(claude(100, under = 10)), viewed = { false }, now = 2_000)
        coordinator.newThread("/p", now = 3_000)
        coordinator.onSnapshot(snap(claude(100, under = 10), claude(101, under = 11, startedAt = 3_500, sessionId = Z, activity = ClaudeActivity.WAITING, waitingFor = "input needed")), viewed = { false }, now = 4_000)
        assertEquals(listOf("t2", "t1"), coordinator.threads.map { it.id })
    }

    @Test
    fun onlyClaudeCommandsAreTyped() {
        coordinator.newThread("/p", now = 1_000)
        coordinator.resume(entry(R, cwd = "/q"), snap(), now = 2_000)
        coordinator.restore(listOf(saved("r1", A)), snap(), now = 3_000)
        coordinator.onSnapshot(snap(), viewed = { false }, now = 40_000)
        coordinator.onTabSelected("r1", snap(), now = 41_000)
        coordinator.onSnapshot(snap(), viewed = { false }, now = 80_000)
        assertTrue(terminal.typed.all { ClaudeCommand.isAllowed(it.second) })
        assertEquals(3, terminal.typed.size)
    }

    @Test
    fun pinAndColourShowOnTheThreadAndSurviveARestore() {
        coordinator.newThread("/w/acme", now = 1_000)
        coordinator.newThread("/w/acme", now = 1_100)
        coordinator.pin(listOf("t2"), pinned = true)
        coordinator.color(listOf("t1", "t2"), ThreadColor.PURPLE)
        assertEquals(listOf("t2", "t1"), coordinator.threads.map { it.id })
        assertTrue(thread("t2").pinned)
        assertEquals(ThreadColor.PURPLE, thread("t1").color)
        coordinator.color(listOf("t1"), null)
        assertNull(thread("t1").color)
        coordinator.pin(listOf("t2"), pinned = false)
        assertEquals(listOf("t1", "t2"), coordinator.threads.map { it.id })

        val fresh = ThreadCoordinator(FakeThreadTerminal(), ThreadMapper(probe), LaunchSlot(), events) { "x" }
        fresh.restore(listOf(saved("r1", A).copy(pinned = true, color = ThreadColor.GREEN)), snap(), now = 1_000)
        val restored = fresh.threads.single()
        assertTrue(restored.pinned)
        assertEquals(ThreadColor.GREEN, restored.color)
    }

    @Test
    fun pinAndColourGoAwayWithTheThreadWhenItsTabCloses() {
        coordinator.newThread("/w/acme", now = 1_000)
        coordinator.pin(listOf("t1"), pinned = true)
        coordinator.color(listOf("t1"), ThreadColor.RED)
        coordinator.onTabClosed("t1")
        assertTrue(coordinator.threads.isEmpty())
        // the same id coming back from the workspace file carries only what was saved with it, not what the closed tab had
        coordinator.restore(listOf(saved("t1", A)), snap(), now = 2_000)
        assertTrue(!thread("t1").pinned)
        assertNull(thread("t1").color)
    }

    @Test
    fun pinningAnUnknownThreadChangesNothing() {
        coordinator.newThread("/w/acme", now = 1_000)
        coordinator.pin(listOf("nope"), pinned = true)
        coordinator.color(listOf("nope"), ThreadColor.BLUE)
        assertTrue(!thread("t1").pinned)
        assertNull(thread("t1").color)
        assertEquals(1, coordinator.threads.size)
    }

    @Test
    fun worktreeThreadTypesTheWorktreeCommand() {
        coordinator.newWorktreeThread("/p", "feat-x", now = 1_000)
        assertEquals(listOf("t1" to "claude --worktree feat-x"), terminal.typed)
        assertEquals("feat-x", terminal.opened.single().name)
        assertEquals("/p", terminal.opened.single().folder)
        assertEquals("feat-x", thread().worktree)
        assertEquals(ThreadState.Starting, thread().state)
    }

    @Test
    fun aWorktreeSessionMatchesItsThread() {
        coordinator.newWorktreeThread("/p", "feat-x", now = 1_000)
        coordinator.onSnapshot(snap(claude(100, under = 10, cwd = "/p/.claude/worktrees/feat-x")), viewed = { false }, now = 2_000)
        assertEquals(10L, thread().anchor)
        assertEquals(ThreadState.Idle, thread().state)
    }

    @Test
    fun aQueuedWorktreeLaunchStillMatchesTheWorktree() {
        coordinator.newThread("/p", now = 1_000)
        coordinator.newWorktreeThread("/p", "feat-x", now = 1_001) // queued behind t1
        coordinator.onSnapshot(snap(claude(100, under = 10)), viewed = { false }, now = 2_000)
        assertEquals("t2" to "claude --worktree feat-x", terminal.typed.last())
        coordinator.onSnapshot(snap(claude(100, under = 10), claude(101, under = 11, startedAt = 2_500, sessionId = Z, cwd = "/p/.claude/worktrees/feat-x")), viewed = { false }, now = 3_000)
        assertEquals(11L, thread("t2").anchor)
    }

    @Test
    fun aSessionInAnotherWorktreeIsNotAdopted() {
        coordinator.newWorktreeThread("/p", "feat-x", now = 1_000)
        val other = claude(100, under = 10, cwd = "/p/.claude/worktrees/other")
        coordinator.onSnapshot(snap(other), viewed = { false }, now = 2_000)
        assertNull(thread().anchor)
        assertEquals(ThreadState.Starting, thread().state)
        coordinator.onSnapshot(snap(other), viewed = { false }, now = 31_001)
        assertEquals(ThreadState.Unknown, thread().state)
    }

    @Test
    fun worktreeNameFillsOnlyTheFolderFallback() {
        coordinator.newWorktreeThread("/p", "feat-x", now = 1_000)
        assertEquals("feat-x", thread().name)
        coordinator.onSnapshot(snap(claude(100, under = 10, cwd = "/p/.claude/worktrees/feat-x", name = null)), viewed = { false }, now = 2_000)
        assertEquals("feat-x", thread().name)
        coordinator.onSnapshot(snap(claude(100, under = 10, cwd = "/p/.claude/worktrees/feat-x", name = "Fix login")), viewed = { false }, now = 3_000)
        assertEquals("Fix login", thread().name)
        assertEquals("feat-x", thread().worktree)
    }

    @Test
    fun aWorktreeThreadKeepsTheBaseItStartedWith() {
        coordinator.newWorktreeThread("/p", "feat-x", now = 1_000, base = "develop")
        assertEquals("develop", thread().base)
        coordinator.onSnapshot(snap(claude(100, under = 10, cwd = "/p/.claude/worktrees/feat-x")), viewed = { false }, now = 2_000)
        assertEquals("develop", thread().base)
        coordinator.newWorktreeThread("/p", "feat-y", now = 3_000)
        assertNull(thread("t2").base)
    }

    @Test
    fun theBaseSurvivesARestoreOfAWorktreeThreadOnly() {
        coordinator.restore(listOf(saved("r1", A, worktree = "feat-x").copy(base = "develop"), saved("r2", null).copy(base = "develop")), snap(), now = 1_000)
        assertEquals("develop", thread("r1").base)
        assertNull("a thread in an ordinary folder has no base", thread("r2").base)
    }

    @Test
    fun worktreeSurvivesARestore() {
        coordinator.restore(listOf(saved("r1", A, worktree = "feat-x")), snap(), now = 1_000)
        assertEquals("feat-x", thread("r1").worktree)
        assertEquals("/p", terminal.opened.single().folder)
        coordinator.onTabSelected("r1", snap(), now = 2_000)
        assertEquals(listOf("r1" to "claude --resume $A"), terminal.typed)
    }

    @Test
    fun aRestoredWorktreeThreadWithoutSessionStartsTheWorktreeCommand() {
        coordinator.restore(listOf(saved("r1", null, worktree = "feat-x")), snap(), now = 1_000)
        coordinator.onTabSelected("r1", snap(), now = 2_000)
        assertEquals(listOf("r1" to "claude --worktree feat-x"), terminal.typed)
        coordinator.onSnapshot(snap(claude(100, under = 10, startedAt = 2_500, cwd = "/p/.claude/worktrees/feat-x")), viewed = { false }, now = 3_000)
        assertEquals(10L, thread("r1").anchor)
    }

    @Test
    fun resumingAWorktreeEntryOpensTheTabInTheRepository() {
        val e = HistoryEntry(A, "/p/.claude/worktrees/feat-x", "title", 0, null, worktree = "feat-x")
        assertEquals(ResumeOutcome.Opened("t1"), coordinator.resume(e, snap(), now = 1_000))
        assertEquals("/p", terminal.opened.single().folder)
        assertEquals("title", terminal.opened.single().name)
        assertEquals(listOf("t1" to "claude --resume $A"), terminal.typed)
        assertEquals("feat-x", thread().worktree)
    }

    /** A worktree thread resumed by id may come back under another id; its status file then names the worktree. */
    @Test
    fun aWorktreeResumeWithAnotherIdMatchesTheWorktreeCwd() {
        coordinator.restore(listOf(saved("r1", A, worktree = "feat-x")), snap(), now = 1_000)
        coordinator.onTabSelected("r1", snap(), now = 2_000)
        assertEquals(listOf("r1" to "claude --resume $A"), terminal.typed)
        val restored = claude(100, under = 10, startedAt = 2_500, cwd = "/p/.claude/worktrees/feat-x", sessionId = D)
        coordinator.onSnapshot(snap(restored), viewed = { false }, now = 3_000)
        assertEquals(10L, thread("r1").anchor)
        assertEquals(D, thread("r1").sessionId)
        // the same for a worktree session resumed from the History
        val entry = HistoryEntry(Z, "/p/.claude/worktrees/feat-y", "title", 0, null, worktree = "feat-y")
        assertEquals(ResumeOutcome.Opened("t1"), coordinator.resume(entry, snap(restored), now = 4_000))
        coordinator.onSnapshot(snap(restored, claude(101, under = 11, startedAt = 4_500, cwd = "/p/.claude/worktrees/feat-y", sessionId = R)), viewed = { false }, now = 5_000)
        assertEquals(11L, thread("t1").anchor)
    }

    /** Which cwd a fresh worktree session reports first has not been observed: the repository's counts too. */
    @Test
    fun aWorktreeLaunchAlsoAcceptsTheRepositoryCwd() {
        coordinator.newWorktreeThread("/p", "feat-x", now = 1_000)
        coordinator.onSnapshot(snap(claude(100, under = 10, cwd = "/p")), viewed = { false }, now = 2_000)
        assertEquals(10L, thread().anchor)
        coordinator.restore(listOf(saved("r1", A, worktree = "feat-y")), snap(), now = 3_000)
        coordinator.onTabSelected("r1", snap(), now = 4_000)
        coordinator.onSnapshot(snap(claude(100, under = 10, cwd = "/p"), claude(101, under = 11, startedAt = 4_500, cwd = "/p", sessionId = D)), viewed = { false }, now = 5_000)
        assertEquals(11L, thread("r1").anchor)
    }

    @Test
    fun anInvalidWorktreeNameOpensNoTab() {
        assertThrows(IllegalArgumentException::class.java) { coordinator.newWorktreeThread("/p", "-x", now = 1_000) }
        assertTrue(terminal.opened.isEmpty())
        assertTrue(coordinator.threads.isEmpty())
        assertNull(slot.holder(now = 1_000))
    }

    /** A hand-edited workspace file must not make every selection of the tab throw. */
    @Test
    fun aSavedInvalidWorktreeIsDropped() {
        coordinator.restore(listOf(saved("r1", null, worktree = "../x"), saved("r2", A, worktree = "-x")), snap(), now = 1_000)
        assertNull(thread("r1").worktree)
        assertNull(thread("r2").worktree)
        coordinator.onTabSelected("r1", snap(), now = 2_000)
        assertEquals(listOf("r1" to "claude"), terminal.typed)
    }

    @Test
    fun closingAWorktreeThreadReportsItsWorktree() {
        coordinator.newWorktreeThread("/p", "feat-x", now = 1_000)
        coordinator.onTabClosed("t1")
        assertEquals("feat-x", closedThreads.single().worktree)
        assertEquals("t1", closedThreads.single().id)
    }
}
