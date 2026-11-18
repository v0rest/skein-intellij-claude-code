package io.github.v0rest.skein.threads

import io.github.v0rest.skein.claude.ClaudeActivity
import io.github.v0rest.skein.claude.FolderTrust
import io.github.v0rest.skein.claude.HistoryEntry
import io.github.v0rest.skein.claude.SessionFile
import io.github.v0rest.skein.claude.SessionSnapshot
import io.github.v0rest.skein.claude.WaitReason
import io.github.v0rest.skein.paths.PathRules
import io.github.v0rest.skein.process.HangUp
import io.github.v0rest.skein.process.ProcessId
import io.github.v0rest.skein.process.ProcessProbe
import io.github.v0rest.skein.terminal.ClaudeCommand
import io.github.v0rest.skein.terminal.ThreadTab
import io.github.v0rest.skein.terminal.ThreadTerminal
import io.github.v0rest.skein.terminal.ThreadTerminalListener
import io.github.v0rest.skein.testutil.FakeProcessProbe
import io.github.v0rest.skein.testutil.FakeThreadTerminal
import io.github.v0rest.skein.testutil.Fixture
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
    private val untrustedWorktrees = mutableListOf<SkeinThread>()

    /** What `.claude.json` says about every folder, and the folders the coordinator asked about. */
    private var folderTrust = FolderTrust.UNKNOWN
    private val trustAsked = mutableListOf<String>()
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

        override fun untrustedWorktree(thread: SkeinThread) {
            untrustedWorktrees += thread
        }
    }
    private var nextId = 0
    private val coordinator = ThreadCoordinator(terminal, ThreadMapper(probe), slot, events, trust = { trustAsked += it; folderTrust }) { "t${++nextId}" }

    @Before
    fun tree() {
        probe.add(1, parent = null, start = t0) // the IDE
        probe.add(2, parent = null, start = t0) // iTerm
        terminal.onOpen = { spawnShell() } // the first tab opened gets shell 10, the next 11, ...
        terminal.shellsNow = { ThreadMapper(probe).ideChildList() } // what the terminal lists right before typing
    }

    private var nextShell = 10L

    /** What a tab's shell runs, so the trust look counts it as a shell ([ThreadMapper.kindOf]). */
    private val shellCommand = listOf(Fixture.root("bin/zsh"))

    private fun spawnShell() {
        probe.add(nextShell++, parent = 1, start = t0, command = shellCommand)
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

    /**
     * A tab closed right after Skein typed, before claude is up and long before Skein tied the thread to the tab's shell -
     * the bracket alone proves that shell, which is hung up and passes the hangup on to whatever it started.
     */
    @Test
    fun aCloseBeforeClaudeIsUpHangsUpTheTabsShellAlone() {
        coordinator.newThread("/w/acme", now = 1_000)
        val ending = coordinator.close(listOf("t1"), confirmed = setOf("t1"), snap()).closing.single()
        assertEquals(ProcessId(10, t0), ending.shell)
        assertNull(ending.claude)
        assertNull(ending.doubt)
        assertNull(thread().anchor)
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

                override fun tabsClosingPastCheck(tabIds: List<String>, withinMillis: Long, comesBack: Boolean) = Unit

                override fun tabsLostPastCheck(tabIds: List<String>): Boolean = false

                override fun tabLost(tabId: String, select: Boolean) = Unit
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
        // the repository comes from Worktrees.split, which writes it in the system's own form (`\p` on Windows)
        assertTrue(PathRules.same("/p", terminal.opened.single().folder))
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

    // claude's folder-trust question: it writes no status file while it asks

    /** Claude Code's CLI by its executable, so Skein can tell it from another program under the tab's shell. */
    private val cli = Fixture.root("w/bin/claude")

    /** A claude Skein tells as the CLI, running under [under] without a status file, as it does while it asks about trust. */
    private fun askingClaude(pid: Long, under: Long, startedAt: Long = 1_500) =
        probe.add(pid, parent = under, start = Instant.ofEpochMilli(startedAt - 100), command = listOf(cli))

    /** t1 in /p, its claude 100 under shell 10 asking whether to trust the folder, past the launch timeout. */
    private fun startAskingThread(viewed: Boolean = false) {
        folderTrust = FolderTrust.NOT_TRUSTED
        coordinator.newThread("/p", now = 1_000)
        askingClaude(100, under = 10)
        coordinator.onSnapshot(snap(), viewed = { viewed }, now = 31_000)
        coordinator.onSnapshot(snap(), viewed = { viewed }, now = 31_001)
    }

    private val asking = ThreadState.NeedsYou(WaitReason.TRUST, 31_001)

    @Test
    fun aClaudeAskingWhetherToTrustItsFolderWaitsForTheUser() {
        folderTrust = FolderTrust.NOT_TRUSTED
        coordinator.newThread("/p", now = 1_000)
        askingClaude(100, under = 10)
        coordinator.onSnapshot(snap(), viewed = { false }, now = 31_000)
        assertEquals(ThreadState.Starting, thread().state)
        assertTrue("nothing is read before the launch timeout", trustAsked.isEmpty())
        coordinator.onSnapshot(snap(), viewed = { false }, now = 31_001)
        assertEquals(asking, thread().state)
        assertEquals(listOf("/p"), trustAsked)
        assertEquals("no could-not-read balloon", 0, missingStatusFilesCalls)
        assertEquals(listOf("t1" to NotificationKind.NEEDS_YOU), notifications)
        // the tab's shell is the thread's from now on, and the launch slot is free for the next thread
        assertEquals(10L, thread().anchor)
        assertNull(slot.holder(now = 0))
        // it keeps waiting, and the file is not read again
        coordinator.onSnapshot(snap(), viewed = { false }, now = 300_000)
        assertEquals(asking, thread().state)
        assertEquals(1, trustAsked.size)
        assertEquals(1, notifications.size)
    }

    @Test
    fun aThreadInViewWaitsWithoutABalloon() {
        startAskingThread(viewed = true)
        assertEquals(asking, thread().state)
        assertTrue(notifications.isEmpty())
    }

    /** However long the person takes - here well past the late window - the status file that comes then is the thread's. */
    @Test
    fun theStatusFileAfterTheAnswerGoesOnWithoutAnnouncingATurn() {
        startAskingThread()
        val answered = sessionFile(pid = 100, activity = ClaudeActivity.IDLE, sessionId = A, cwd = "/p", name = "acme-api", startedAt = 1_500)
        coordinator.onSnapshot(snap(answered), viewed = { false }, now = 200_000)
        assertEquals(ThreadState.Idle, thread().state)
        assertEquals(A, thread().sessionId)
        assertEquals(10L, thread().anchor)
        assertEquals(listOf("t1"), leftNeedsYou)
        assertEquals("only the trust question's own balloon", listOf("t1" to NotificationKind.NEEDS_YOU), notifications)
        // from here on its turns are ordinary ones
        coordinator.onSnapshot(snap(sessionFile(pid = 100, activity = ClaudeActivity.BUSY, sessionId = A, cwd = "/p", startedAt = 1_500)), viewed = { false }, now = 201_000)
        coordinator.onSnapshot(snap(answered), viewed = { false }, now = 202_000)
        assertEquals(ThreadState.YourTurn(202_000), thread().state)
    }

    /** "No, exit" ends claude without a status file: the thread is no longer waiting, and its tab is followed as before. */
    @Test
    fun aClaudeThatEndsWhileAskingLeavesTheThreadExited() {
        startAskingThread()
        probe.kill(100)
        coordinator.onSnapshot(snap(), viewed = { false }, now = 40_000)
        assertEquals(ThreadState.Exited, thread().state)
        assertEquals(listOf("t1"), leftNeedsYou)
        assertEquals(0, missingStatusFilesCalls)
        // claude typed again by hand in that tab shows up under its shell
        coordinator.onSnapshot(snap(claude(101, under = 10, startedAt = 50_000)), viewed = { false }, now = 51_000)
        assertEquals(ThreadState.Idle, thread().state)
    }

    /** A pid that another process took over since is not the claude that asked. */
    @Test
    fun aPidTakenOverByAnotherProcessIsNotTheClaudeThatAsked() {
        startAskingThread()
        probe.kill(100)
        probe.add(100, parent = 10, start = Instant.ofEpochMilli(39_000), command = listOf(cli))
        coordinator.onSnapshot(snap(), viewed = { false }, now = 40_000)
        assertEquals(ThreadState.Exited, thread().state)
    }

    @Test
    fun aLiveClaudeInATrustedFolderTimesOutAsBefore() {
        folderTrust = FolderTrust.TRUSTED
        coordinator.newThread("/p", now = 1_000)
        askingClaude(100, under = 10)
        coordinator.onSnapshot(snap(), viewed = { false }, now = 31_001)
        assertEquals(ThreadState.Unknown, thread().state)
        assertEquals(1, missingStatusFilesCalls)
        assertEquals(listOf("/p"), trustAsked)
        assertNull(thread().anchor)
    }

    @Test
    fun aLiveClaudeWhoseTrustSkeinCannotReadTimesOutAsBefore() {
        folderTrust = FolderTrust.UNKNOWN
        coordinator.newThread("/p", now = 1_000)
        askingClaude(100, under = 10)
        coordinator.onSnapshot(snap(), viewed = { false }, now = 31_001)
        assertEquals(ThreadState.Unknown, thread().state)
        assertEquals(1, missingStatusFilesCalls)
        assertTrue(notifications.isEmpty())
    }

    /** A process under the shell that the system names nothing Skein can tell (node on Windows, say) is no proof of claude. */
    @Test
    fun aProcessSkeinCannotTellAsClaudeIsNoAnswer() {
        folderTrust = FolderTrust.NOT_TRUSTED
        coordinator.newThread("/p", now = 1_000)
        probe.add(100, parent = 10, start = Instant.ofEpochMilli(1_400))
        coordinator.onSnapshot(snap(), viewed = { false }, now = 31_001)
        assertEquals(ThreadState.Unknown, thread().state)
        assertEquals(1, missingStatusFilesCalls)
        assertTrue("trust is read only for a claude Skein saw", trustAsked.isEmpty())
    }

    @Test
    fun aPlainLaunchWithoutClaudeNeverReadsTrust() {
        folderTrust = FolderTrust.NOT_TRUSTED
        coordinator.newThread("/p", now = 1_000)
        coordinator.onSnapshot(snap(), viewed = { false }, now = 31_001)
        assertEquals(ThreadState.Unknown, thread().state)
        assertEquals(1, missingStatusFilesCalls)
        assertTrue(trustAsked.isEmpty())
    }

    /** Only the tab's own shell counts: a terminal open before the tab or opened after the typing is another one's. */
    @Test
    fun aClaudeInAnotherTerminalIsNeverTakenForTheThreads() {
        folderTrust = FolderTrust.NOT_TRUSTED
        probe.add(9, parent = 1, start = t0, command = shellCommand) // a terminal the user had open before
        askingClaude(90, under = 9)
        coordinator.newThread("/p", now = 1_000)
        probe.add(30, parent = 1, start = Instant.ofEpochMilli(2_000), command = shellCommand) // one opened after Skein typed
        askingClaude(300, under = 30, startedAt = 2_500)
        coordinator.onSnapshot(snap(), viewed = { false }, now = 31_001)
        assertEquals(ThreadState.Unknown, thread().state)
        assertNull(thread().anchor)
        assertEquals(1, missingStatusFilesCalls)
    }

    @Test
    fun twoClaudesUnderTheTabAreADoubt() {
        folderTrust = FolderTrust.NOT_TRUSTED
        coordinator.newThread("/p", now = 1_000)
        askingClaude(100, under = 10)
        askingClaude(101, under = 10, startedAt = 1_600)
        coordinator.onSnapshot(snap(), viewed = { false }, now = 31_001)
        assertEquals(ThreadState.Unknown, thread().state)
    }

    /** The gate of the could-not-read balloon is no gate here: a thread asks about trust whatever others did before. */
    @Test
    fun aThreadAsksAboutTrustEvenAfterAnotherGotItsStatusFile() {
        val s100 = startIdleThread()
        folderTrust = FolderTrust.NOT_TRUSTED
        coordinator.newThread("/q", now = 10_000)
        askingClaude(101, under = 11, startedAt = 10_500)
        coordinator.onSnapshot(snap(s100), viewed = { false }, now = 40_001)
        assertEquals(ThreadState.NeedsYou(WaitReason.TRUST, 40_001), thread("t2").state)
        assertEquals(listOf("/q"), trustAsked)
        assertEquals(ThreadState.Idle, thread("t1").state)
    }

    /** A resume typed into a tab that has its shell already waits the same way. */
    @Test
    fun aRelaunchInTheSameTabCanAskAboutTrustToo() {
        startIdleThread()
        probe.kill(100)
        coordinator.onSnapshot(snap(), viewed = { false }, now = 3_000)
        coordinator.resume(entry(A), snap(), now = 4_000)
        folderTrust = FolderTrust.NOT_TRUSTED
        askingClaude(101, under = 10, startedAt = 4_500)
        coordinator.onSnapshot(snap(), viewed = { false }, now = 34_001)
        assertEquals(ThreadState.NeedsYou(WaitReason.TRUST, 34_001), thread().state)
        coordinator.onSnapshot(snap(sessionFile(pid = 101, activity = ClaudeActivity.IDLE, sessionId = A, cwd = "/p", startedAt = 4_500)), viewed = { false }, now = 200_000)
        assertEquals(ThreadState.Idle, thread().state)
        assertEquals(listOf("t1" to NotificationKind.NEEDS_YOU), notifications)
    }

    /** Close ends a waiting thread like any other: its claude, then its shell. */
    @Test
    fun closingAThreadThatAsksAboutTrustEndsItsClaudeAndShell() {
        startAskingThread()
        val ending = coordinator.close(listOf("t1"), confirmed = setOf("t1"), snap()).closing.single()
        assertEquals(ProcessId(10, t0), ending.shell)
        assertEquals(ProcessId(100, Instant.ofEpochMilli(1_400)), ending.claude)
        assertNull(ending.doubt)
    }

    /** `claude --worktree` ends at once in a folder nobody trusted: the balloon says that instead of "could not read". */
    @Test
    fun aWorktreeThreadTurnedAwayForTrustSaysSo() {
        folderTrust = FolderTrust.NOT_TRUSTED
        coordinator.newWorktreeThread("/p", "feat-x", now = 1_000)
        coordinator.onSnapshot(snap(), viewed = { false }, now = 31_001)
        assertEquals(ThreadState.Unknown, thread().state)
        assertEquals(listOf("t1"), untrustedWorktrees.map { it.id })
        assertEquals("/p", untrustedWorktrees.single().folder)
        assertEquals(listOf("/p"), trustAsked)
        assertEquals(0, missingStatusFilesCalls)
        // that answer does not use up the could-not-read balloon of a launch with no such reason
        folderTrust = FolderTrust.UNKNOWN
        coordinator.newThread("/q", now = 40_000)
        coordinator.onSnapshot(snap(), viewed = { false }, now = 70_001)
        assertEquals(1, missingStatusFilesCalls)
        assertEquals(1, untrustedWorktrees.size)
    }

    @Test
    fun aWorktreeThreadGoneWithoutAnAnswerOnTrustTimesOutAsBefore() {
        folderTrust = FolderTrust.UNKNOWN
        coordinator.newWorktreeThread("/p", "feat-x", now = 1_000)
        coordinator.onSnapshot(snap(), viewed = { false }, now = 31_001)
        folderTrust = FolderTrust.TRUSTED
        coordinator.newWorktreeThread("/p", "feat-y", now = 40_000)
        coordinator.onSnapshot(snap(), viewed = { false }, now = 70_001)
        assertTrue(untrustedWorktrees.isEmpty())
        assertEquals(listOf("/p", "/p"), trustAsked)
        assertEquals(1, missingStatusFilesCalls)
        assertEquals(listOf(ThreadState.Unknown, ThreadState.Unknown), coordinator.threads.map { it.state })
    }

    // review fixes, round 1

    /** Two terminals whose shells started in the tick the tab opened cannot be told apart, so neither one's claude is taken (T1). */
    @Test
    fun twoShellsThatStartedWithTheTabAreADoubt() {
        folderTrust = FolderTrust.NOT_TRUSTED
        terminal.onOpen = { spawnShell(); spawnShell() } // the tab's shell 10, and 11 of a terminal opened in the same tick
        coordinator.newThread("/p", now = 1_000)
        askingClaude(110, under = 11) // the other terminal's claude, asking about its own folder
        coordinator.onSnapshot(snap(), viewed = { false }, now = 31_001)
        assertEquals(ThreadState.Unknown, thread().state)
        assertNull(thread().anchor)
        assertEquals(1, missingStatusFilesCalls)
        assertTrue(notifications.isEmpty())
    }

    /** A shell another window holds is that window's thread's, so it is no doubt for this one (T3, the owned guard). */
    @Test
    fun aShellAnotherWindowHoldsLeavesTheTabsOwnShell() {
        folderTrust = FolderTrust.NOT_TRUSTED
        terminal.onOpen = { spawnShell(); spawnShell() }
        coordinator.newThread("/p", now = 1_000)
        slot.claimAnchor(Any(), 11) // another window's thread runs in shell 11
        askingClaude(100, under = 10)
        coordinator.onSnapshot(snap(), viewed = { false }, now = 31_001)
        assertEquals(asking, thread().state)
        assertEquals(10L, thread().anchor)
    }

    /** The tab's shell ended after its session started and a new process got its pid: not the tab's shell any more (T3). */
    @Test
    fun aShellPidTakenOverAfterTheStartIsNotTheTabs() {
        folderTrust = FolderTrust.NOT_TRUSTED
        coordinator.newThread("/p", now = 1_000)
        coordinator.onSnapshot(snap(), viewed = { false }, now = 2_000) // the session is seen started with shell 10
        probe.kill(10)
        probe.add(10, parent = 1, start = Instant.ofEpochMilli(5_000), command = shellCommand)
        askingClaude(100, under = 10, startedAt = 6_000)
        coordinator.onSnapshot(snap(), viewed = { false }, now = 31_001)
        assertEquals(ThreadState.Unknown, thread().state)
        assertNull(thread().anchor)
    }

    /**
     * Two windows run on dispatchers of their own, so another one may claim the shell between Skein's look and its own
     * claim: then no trust wait, and status unknown as before (T3, the false path of the claim).
     */
    @Test
    fun aShellAnotherWindowClaimsMeanwhileIsNotTaken() {
        folderTrust = FolderTrust.NOT_TRUSTED
        val otherWindow = Any()
        var race = false
        val racing = object : ProcessProbe by probe {
            override fun children(pid: Long): List<Long> {
                // the walk below the shell is the moment between the look and the claim
                if (race && pid == 10L) slot.claimAnchor(otherWindow, 10)
                return probe.children(pid)
            }
        }
        val window = ThreadCoordinator(terminal, ThreadMapper(racing), slot, events, trust = { folderTrust }) { "w1" }
        window.newThread("/p", now = 1_000)
        askingClaude(100, under = 10)
        window.onSnapshot(snap(), viewed = { false }, now = 2_000)
        race = true
        window.onSnapshot(snap(), viewed = { false }, now = 31_001)
        assertEquals(ThreadState.Unknown, window.threads.single().state)
        assertNull(window.threads.single().anchor)
        assertEquals(1, missingStatusFilesCalls)
    }

    /**
     * The answer came, but claude wrote a status file Skein cannot read: status unknown, as for any such file, and the
     * could-not-read balloon its timeout did not give, under the usual once-only rule (T3, `unrecognized` in the answer).
     */
    @Test
    fun anUnreadableStatusFileAfterTheAnswerIsStatusUnknownWithTheBalloon() {
        startAskingThread()
        coordinator.onSnapshot(snap(unrecognized = setOf(100)), viewed = { false }, now = 60_000)
        assertEquals(ThreadState.Unknown, thread().state)
        assertEquals(listOf("t1"), leftNeedsYou)
        assertEquals(1, missingStatusFilesCalls)
        coordinator.onSnapshot(snap(unrecognized = setOf(100)), viewed = { false }, now = 61_000)
        assertEquals(1, missingStatusFilesCalls)
        // once it is readable, the thread goes on
        coordinator.onSnapshot(snap(sessionFile(pid = 100, activity = ClaudeActivity.IDLE, sessionId = A, cwd = "/p", startedAt = 1_500)), viewed = { false }, now = 62_000)
        assertEquals(ThreadState.Idle, thread().state)
    }

    /** The asking claude ended and its pid went to another process before the close came: only the shell is hung up (T3). */
    @Test
    fun aClaudePidTakenOverBeforeTheCloseIsNotHungUp() {
        startAskingThread()
        probe.kill(100)
        probe.add(100, parent = 10, start = Instant.ofEpochMilli(40_000), command = listOf(cli))
        val ending = coordinator.close(listOf("t1"), confirmed = setOf("t1"), snap()).closing.single()
        assertEquals(ProcessId(10, t0), ending.shell)
        assertNull(ending.claude)
    }

    /**
     * A worktree session resumed from History or restored types `claude --resume`, which a folder nobody trusted does
     * not turn away the way it does `--worktree`, so a claude Skein did not see is no reason for the trust balloon (T2).
     */
    @Test
    fun aResumedWorktreeSessionIsNotTakenForATurnedAwayWorktree() {
        folderTrust = FolderTrust.NOT_TRUSTED
        coordinator.resume(HistoryEntry(A, "/p/.claude/worktrees/feat-x", "title", 0, null, worktree = "feat-x"), snap(), now = 1_000)
        assertEquals("feat-x", thread().worktree)
        coordinator.onSnapshot(snap(), viewed = { false }, now = 31_001)
        assertEquals(ThreadState.Unknown, thread().state)
        assertTrue(untrustedWorktrees.isEmpty())
        assertTrue("trust is not read for a resume Skein saw no claude of", trustAsked.isEmpty())
        assertEquals(1, missingStatusFilesCalls)
        // a restored worktree thread with its session resumes it the same way
        coordinator.restore(listOf(saved("r1", D, worktree = "feat-y")), snap(), now = 40_000)
        coordinator.onTabSelected("r1", snap(), now = 41_000)
        assertEquals("r1" to "claude --resume $D", terminal.typed.last())
        coordinator.onSnapshot(snap(), viewed = { false }, now = 72_000)
        assertEquals(ThreadState.Unknown, thread("r1").state)
        assertTrue(untrustedWorktrees.isEmpty())
    }

    /** A terminal open before the tab fails the bracket, so the tab's own shell is the one left and its claude is the thread's. */
    @Test
    fun aTerminalOpenBeforeTheTabLeavesTheTabsOwnClaude() {
        folderTrust = FolderTrust.NOT_TRUSTED
        probe.add(9, parent = 1, start = t0, command = shellCommand)
        askingClaude(90, under = 9)
        coordinator.newThread("/p", now = 1_000)
        askingClaude(100, under = 10)
        coordinator.onSnapshot(snap(), viewed = { false }, now = 31_001)
        assertEquals(asking, thread().state)
        assertEquals(10L, thread().anchor)
        assertEquals(ProcessId(100, Instant.ofEpochMilli(1_400)), coordinator.close(listOf("t1"), setOf("t1"), snap()).closing.single().claude)
    }

    // review fixes, round 2 - only shells count (T1')

    /**
     * On Windows the IDE starts helpers of its own in the tick the tab opens - conhost, fsnotifier, and JCEF's
     * cef_server when README.md opens in its preview. They are no tab's shell, so the trust wait still engages.
     */
    @Test
    fun theIdesOwnHelpersBesideTheShellAreNoDoubt() {
        folderTrust = FolderTrust.NOT_TRUSTED
        // the helpers as the running system names them, `.exe` on Windows only
        fun exe(name: String) = if (io.github.v0rest.skein.paths.PathStyle.current == io.github.v0rest.skein.paths.PathStyle.WINDOWS) "$name.exe" else name
        terminal.onOpen = {
            spawnShell()
            probe.add(20, parent = 1, start = t0, command = listOf(Fixture.root("w/ide/bin/" + exe("conhost"))))
            probe.add(21, parent = 1, start = t0, command = listOf(Fixture.root("w/ide/bin/" + exe("fsnotifier"))))
            probe.add(22, parent = 1, start = t0, command = listOf(Fixture.root("w/ide/jbr/lib/" + exe("cef_server"))))
            probe.add(23, parent = 1, start = t0, command = listOf(Fixture.root("w/ide/Frameworks/jcef Helper (Renderer)")))
        }
        coordinator.newThread("/p", now = 1_000)
        askingClaude(100, under = 10)
        coordinator.onSnapshot(snap(), viewed = { false }, now = 31_001)
        assertEquals(asking, thread().state)
        assertEquals(10L, thread().anchor)
        assertEquals(0, missingStatusFilesCalls)
    }

    /** A shell Skein does not know by name is no candidate, so nothing is taken and the timeout is as before. */
    @Test
    fun aShellOfAnUnknownNameGivesNoAnswer() {
        folderTrust = FolderTrust.NOT_TRUSTED
        terminal.onOpen = { probe.add(10, parent = 1, start = t0, command = listOf(Fixture.root("opt/bin/oddshell"))) }
        coordinator.newThread("/p", now = 1_000)
        askingClaude(100, under = 10)
        coordinator.onSnapshot(snap(), viewed = { false }, now = 31_001)
        assertEquals(ThreadState.Unknown, thread().state)
        assertNull(thread().anchor)
        assertEquals(1, missingStatusFilesCalls)
    }

    /** A process whose command the system does not give is no shell either. */
    @Test
    fun aShellWhoseCommandIsUnknownGivesNoAnswer() {
        folderTrust = FolderTrust.NOT_TRUSTED
        terminal.onOpen = { probe.add(10, parent = 1, start = t0) }
        coordinator.newThread("/p", now = 1_000)
        askingClaude(100, under = 10)
        coordinator.onSnapshot(snap(), viewed = { false }, now = 31_001)
        assertEquals(ThreadState.Unknown, thread().state)
        assertNull(thread().anchor)
    }

    // review fixes, round 3 - an unrecognised child beside the shell is a doubt (T1'')

    /**
     * The tab's own shell is one Skein does not list (wsl.exe here), and a terminal opened in the same tick runs zsh with
     * a claude asking: that zsh is no proof of being the tab's, so nothing is taken.
     */
    @Test
    fun anUnlistedThreadShellBesideAListedOneIsADoubt() {
        folderTrust = FolderTrust.NOT_TRUSTED
        terminal.onOpen = {
            probe.add(10, parent = 1, start = t0, command = listOf(Fixture.root("w/bin/wsl.exe")))
            probe.add(11, parent = 1, start = t0, command = shellCommand)
        }
        coordinator.newThread("/p", now = 1_000)
        askingClaude(110, under = 11)
        coordinator.onSnapshot(snap(), viewed = { false }, now = 31_001)
        assertEquals(ThreadState.Unknown, thread().state)
        assertNull(thread().anchor)
        assertEquals(1, missingStatusFilesCalls)
    }

    /** A process whose command the system does not give, beside the shell, could be the tab's own shell: a doubt. */
    @Test
    fun aCommandlessProcessBesideTheShellIsADoubt() {
        folderTrust = FolderTrust.NOT_TRUSTED
        terminal.onOpen = {
            spawnShell()
            probe.add(20, parent = 1, start = t0)
        }
        coordinator.newThread("/p", now = 1_000)
        askingClaude(100, under = 10)
        coordinator.onSnapshot(snap(), viewed = { false }, now = 31_001)
        assertEquals(ThreadState.Unknown, thread().state)
        assertNull(thread().anchor)
    }

    // a rename waiting for Claude Code goes only into the claude Skein follows the thread by, for that very session

    @Test
    fun aRenameTargetIsTheThreadWhoseClaudeRunsTheSession() {
        val s100 = startIdleThread()
        val target = coordinator.renameTarget(A, snap(s100))!!
        assertEquals("t1", target.thread.id)
        assertEquals(ThreadState.Idle, target.thread.state)
        assertTrue(target.runsSession)
        assertEquals(ProcessId(100, Instant.ofEpochMilli(1_400)), target.claude)
        assertEquals("t1", target.tab.id)
        assertNull("no thread holds another session", coordinator.renameTarget(D, snap(s100)))
    }

    /** A thread resumed from History holds the session before its claude runs it: nothing to type into yet. */
    @Test
    fun aThreadWhoseClaudeIsNotThereYetIsNoTargetToTypeInto() {
        coordinator.resume(HistoryEntry(A, "/p", "acme", 0, null), snap(), now = 1_000)
        val target = coordinator.renameTarget(A, snap())!!
        assertEquals(false, target.runsSession)
        assertNull(target.claude)
    }

    /** After `/clear` the thread's claude runs another session; the old one is not there to rename. */
    @Test
    fun aThreadWhoseClaudeMovedToAnotherSessionDoesNotRunTheOldOne() {
        startIdleThread()
        val cleared = sessionFile(pid = 100, sessionId = D, cwd = "/p", startedAt = 1_500)
        coordinator.onSnapshot(snap(cleared), viewed = { false }, now = 3_000)
        assertNull(coordinator.renameTarget(A, snap(cleared)))
        assertEquals(true, coordinator.renameTarget(D, snap(cleared))!!.runsSession)
    }

    /** A claude that is gone, or whose start can no longer be read, is no claude to type into. */
    @Test
    fun aClaudeThatIsGoneIsNoTarget() {
        val s100 = startIdleThread()
        probe.kill(100)
        val target = coordinator.renameTarget(A, snap(s100))!!
        assertNull(target.claude)
    }

    // a close before Skein tied the thread to the tab's shell (its status file not matched yet)

    private fun hangUpAndKill() = HangUp { pid ->
        terminal.calls += "hup $pid"
        probe.kill(pid)
        true
    }

    /**
     * claude is up and its status file is there, but the monitor's next look, which ties the thread to the tab's shell,
     * has not come yet. The bracket alone proves the shell and the status file under it gives claude, so the close ends
     * both as for any thread. The thread's anchor and the launch slot stay as they were, the tab is closing anyway.
     */
    @Test
    fun aCloseBeforeTheStatusFileIsMatchedEndsTheTabsClaudeAndShell() {
        coordinator.newThread("/p", now = 1_000)
        coordinator.onSnapshot(snap(), viewed = { false }, now = 1_500) // the tick that sees the tab's session started
        val s100 = claude(100, under = 10, startedAt = 1_800)
        val ending = coordinator.close(listOf("t1"), confirmed = setOf("t1"), snap(s100)).closing.single()
        assertEquals(ProcessId(10, t0), ending.shell)
        assertEquals(ProcessId(100, Instant.ofEpochMilli(1_700)), ending.claude)
        assertNull(ending.doubt)
        assertNull(thread().anchor)
        assertEquals("t1", slot.holder(now = 1_800))
        assertTrue(slot.ownedAnchors().isEmpty())
        runBlocking { CloseSequence(hangUpAndKill(), terminal, ThreadMapper(probe), timeoutMillis = 2_000) {}.run(listOf(ending)) }
        assertEquals(listOf("ending t1", "hup 100", "hup 10", "await t1", "close t1"), terminal.calls)
    }

    /**
     * In a fresh folder the person answered claude's trust question well before the launch timeout, so Skein never saw
     * it asking. Closed right then, the claude comes from its status file, or while the monitor has not read that yet,
     * as the one CLI under the proved shell. A close reads no trust.
     */
    @Test
    fun aCloseRightAfterTheTrustAnswerEndsTheClaudeThatAsked() {
        folderTrust = FolderTrust.NOT_TRUSTED
        coordinator.newThread("/p", now = 1_000)
        askingClaude(100, under = 10)
        coordinator.onSnapshot(snap(), viewed = { false }, now = 2_000)
        assertEquals(ThreadState.Starting, thread().state)
        val answered = sessionFile(pid = 100, activity = ClaudeActivity.IDLE, sessionId = A, cwd = "/p", name = "acme-api", startedAt = 1_500)
        for (seen in listOf(snap(answered), snap())) {
            val ending = coordinator.close(listOf("t1"), confirmed = setOf("t1"), seen).closing.single()
            assertEquals(ProcessId(10, t0), ending.shell)
            assertEquals(ProcessId(100, Instant.ofEpochMilli(1_400)), ending.claude)
            assertNull(ending.doubt)
        }
        assertTrue("a close reads no trust", trustAsked.isEmpty())
        assertNull(thread().anchor)
        // the claude found without a status file passes the sequence's own checks too
        val ending = coordinator.close(listOf("t1"), confirmed = setOf("t1"), snap()).closing.single()
        runBlocking { CloseSequence(hangUpAndKill(), terminal, ThreadMapper(probe), timeoutMillis = 2_000) {}.run(listOf(ending)) }
        assertEquals(listOf("ending t1", "hup 100", "hup 10", "await t1", "close t1"), terminal.calls)
    }

    /** Two shells started with the tab, and either may be its own, so nothing is hung up and the log says why. */
    @Test
    fun twoShellsThatStartedWithTheTabHangUpNothingAtTheClose() {
        terminal.onOpen = { spawnShell(); spawnShell() } // the tab's shell 10, and 11 of a terminal opened in the same tick
        coordinator.newThread("/p", now = 1_000)
        coordinator.onSnapshot(snap(), viewed = { false }, now = 1_500)
        val s100 = claude(100, under = 10, startedAt = 1_800)
        val ending = coordinator.close(listOf("t1"), confirmed = setOf("t1"), snap(s100)).closing.single()
        assertNull(ending.shell)
        assertNull(ending.claude)
        assertTrue(ending.doubt.orEmpty(), ending.doubt.orEmpty().contains("not tied the tab to its shell yet"))
        assertTrue(ending.doubt.orEmpty(), ending.doubt.orEmpty().contains("pids 10, 11"))
        val logged = mutableListOf<String>()
        runBlocking { CloseSequence(signalNothing(), terminal, ThreadMapper(probe), timeoutMillis = 2_000) { logged += it }.run(listOf(ending)) }
        assertEquals(listOf("close t1"), terminal.calls)
        assertEquals(1, logged.size)
        assertTrue(logged.single(), logged.single().contains("thread t1") && logged.single().contains("pids 10, 11"))
    }

    /** Something the IDE started with the tab that Skein does not know as a shell may be the tab's own shell - a doubt. */
    @Test
    fun anUnrecognisedProcessBesideTheShellHangsUpNothingAtTheClose() {
        terminal.onOpen = {
            spawnShell()
            probe.add(20, parent = 1, start = t0) // its command not given, as for another user's process
        }
        coordinator.newThread("/p", now = 1_000)
        val s100 = claude(100, under = 10, startedAt = 1_800)
        val ending = coordinator.close(listOf("t1"), confirmed = setOf("t1"), snap(s100)).closing.single()
        assertNull(ending.shell)
        assertNull(ending.claude)
        assertTrue(ending.doubt.orEmpty(), ending.doubt.orEmpty().contains("(pid 20)"))
    }

    /** The one shell that started with the tab is another thread's anchor (another window holds it), not this tab's to end. */
    @Test
    fun aShellAnotherThreadHoldsIsNotHungUpAtTheClose() {
        coordinator.newThread("/p", now = 1_000)
        slot.claimAnchor(Any(), 10)
        val s100 = claude(100, under = 10, startedAt = 1_800)
        val ending = coordinator.close(listOf("t1"), confirmed = setOf("t1"), snap(s100)).closing.single()
        assertNull(ending.shell)
        assertNull(ending.claude)
        assertTrue(ending.doubt.orEmpty(), ending.doubt.orEmpty().contains("another thread's (pid 10)"))
    }

    /**
     * A tab whose session started but that Skein never typed into (a restored thread on screen, the person typed claude
     * there by hand) gives no bracket, so nothing is hung up, and the log says so.
     */
    @Test
    fun aTabSkeinNeverTypedIntoHangsUpNothingAndSaysSo() {
        coordinator.restore(listOf(saved("r1", A)), snap(), now = 1_000) // its shell 10 starts, nothing is typed
        val byHand = claude(100, under = 10, startedAt = 1_800, sessionId = D)
        val ending = coordinator.close(listOf("r1"), confirmed = setOf("r1"), snap(byHand)).closing.single()
        assertNull(ending.shell)
        assertNull(ending.claude)
        assertTrue(ending.doubt.orEmpty(), ending.doubt.orEmpty().contains("has not typed into the tab"))
    }

    /** A restored tab never shown has no shell yet, so there is nothing to hang up and nothing worth a log line. */
    @Test
    fun aTabWhoseSessionNeverStartedClosesWithoutADoubt() {
        restoreLazily(saved("r1", A))
        assertEquals(Ending(terminal.opened.single(), shell = null, claude = null), coordinator.close(listOf("r1"), confirmed = setOf("r1"), snap()).closing.single())
    }

    /** A terminal that cannot say whether the tab's session started counts as started, so the log still says why nothing is hung up. */
    @Test
    fun aSessionStateThatCannotBeReadKeepsTheDoubt() {
        val unsure = object : ThreadTerminal by terminal {
            override fun sessionStarted(tab: ThreadTab): Boolean = error("no view for the tab")
        }
        val window = ThreadCoordinator(unsure, ThreadMapper(probe), slot, events) { "w1" }
        terminal.lazyShells = true
        window.restore(listOf(saved("w1", A)), snap(), now = 1_000)
        val ending = window.close(listOf("w1"), confirmed = setOf("w1"), snap()).closing.single()
        assertNull(ending.shell)
        assertTrue(ending.doubt.orEmpty(), ending.doubt.orEmpty().contains("has not typed into the tab"))
    }

    /**
     * The tab's own shell had no readable start when Skein typed, and a terminal opened in the same tick did. That one
     * must not pass for the lone shell, so nothing is hung up, and the log names the pid it could not place.
     */
    @Test
    fun aShellWithAnUnreadableStartBesideTheTabsLeavesNothingToHangUp() {
        terminal.onOpen = {
            spawnShell() // the tab's own shell 10, its start unreadable as Skein types
            probe.unknownStart += 10L
            spawnShell() // 11, a terminal opened in the same tick
        }
        coordinator.newThread("/p", now = 1_000)
        probe.unknownStart -= 10L
        val s100 = claude(100, under = 10, startedAt = 1_800)
        val ending = coordinator.close(listOf("t1"), confirmed = setOf("t1"), snap(s100)).closing.single()
        assertNull(ending.shell)
        assertNull(ending.claude)
        assertTrue(ending.doubt.orEmpty(), ending.doubt.orEmpty().contains("no readable start (pid 10)"))
    }

    /**
     * A child of the IDE whose start could not be read as Skein typed (one going away right then) and that is gone by the
     * close cannot be the tab's running shell, so it leaves the lone shell proved.
     */
    @Test
    fun anUnreadablePidGoneByTheCloseLeavesTheShellProved() {
        terminal.onOpen = {
            spawnShell() // the tab's own shell 10
            probe.add(20, parent = 1, start = t0) // something of the IDE's, exiting as Skein types
            probe.unknownStart += 20L
        }
        coordinator.newThread("/p", now = 1_000)
        probe.kill(20)
        val s100 = claude(100, under = 10, startedAt = 1_800)
        val ending = coordinator.close(listOf("t1"), confirmed = setOf("t1"), snap(s100)).closing.single()
        assertEquals(ProcessId(10, t0), ending.shell)
        assertEquals(ProcessId(100, Instant.ofEpochMilli(1_700)), ending.claude)
        assertNull(ending.doubt)
    }

    /** The trust wait takes its shell the same way, so there it gives no answer either, and the launch times out as before. */
    @Test
    fun aShellWithAnUnreadableStartBesideTheTabsGivesTheTrustWaitNoAnswer() {
        folderTrust = FolderTrust.NOT_TRUSTED
        terminal.onOpen = {
            spawnShell()
            probe.unknownStart += 10L
            spawnShell()
        }
        coordinator.newThread("/p", now = 1_000)
        probe.unknownStart -= 10L
        askingClaude(110, under = 11) // the other terminal's claude, asking about its own folder
        coordinator.onSnapshot(snap(), viewed = { false }, now = 31_001)
        assertEquals(ThreadState.Unknown, thread().state)
        assertNull(thread().anchor)
        assertTrue(trustAsked.isEmpty())
    }

    /** A probe that throws on one thread's anchored path costs that thread its hangup, with a doubt, and the others still end. */
    @Test
    fun aProbeThatFailsOnOneAnchoredThreadLosesNotTheWholeClose() {
        var failOn: Long? = null
        val failing = object : ProcessProbe by probe {
            override fun startInstant(pid: Long): Instant? = if (pid == failOn) error("start unreadable") else probe.startInstant(pid)
        }
        var n = 0
        val window = ThreadCoordinator(terminal, ThreadMapper(failing), slot, events) { "w${++n}" }
        window.newThread("/p", now = 1_000) // w1, shell 10
        val s100 = claude(100, under = 10)
        window.onSnapshot(snap(s100), viewed = { false }, now = 2_000)
        window.newThread("/q", now = 3_000) // w2, shell 11
        val s101 = claude(101, under = 11, startedAt = 3_500, cwd = "/q", sessionId = D)
        window.onSnapshot(snap(s100, s101), viewed = { false }, now = 4_000)
        assertEquals(listOf(10L, 11L), window.threads.sortedBy { it.id }.map { it.anchor })
        failOn = 10
        val closing = window.close(listOf("w1", "w2"), confirmed = setOf("w1", "w2"), snap(s100, s101)).closing
        assertEquals(listOf("w1", "w2"), closing.map { it.tab.id })
        assertNull(closing[0].shell)
        assertTrue(closing[0].doubt.orEmpty(), closing[0].doubt.orEmpty().contains("could not tell what runs in its tab") && closing[0].doubt.orEmpty().contains("start unreadable"))
        assertEquals(ProcessId(11, t0), closing[1].shell)
        assertEquals(ProcessId(101, Instant.ofEpochMilli(3_400)), closing[1].claude)
    }

    /** A process listing that fails while Skein looks for claude under the proved shell still hangs up that shell. */
    @Test
    fun aListingThatFailsWhileLookingForClaudeStillHangsUpTheShell() {
        coordinator.newThread("/p", now = 1_000)
        askingClaude(100, under = 10)
        probe.childrenFail = true
        val ending = coordinator.close(listOf("t1"), confirmed = setOf("t1"), snap()).closing.single()
        assertEquals(ProcessId(10, t0), ending.shell)
        assertNull(ending.claude)
    }

    /** A probe that fails while the bracket is checked costs nothing but the hangup - the close goes on, with a doubt. */
    @Test
    fun aProbeThatFailsDuringTheBracketHangsUpNothingAndSaysSo() {
        var fail = false
        val failing = object : ProcessProbe by probe {
            override fun command(pid: Long): List<String> = if (fail) error("process table unreadable") else probe.command(pid)
        }
        val window = ThreadCoordinator(terminal, ThreadMapper(failing), slot, events) { "w1" }
        window.newThread("/p", now = 1_000)
        fail = true
        val ending = window.close(listOf("w1"), confirmed = setOf("w1"), snap()).closing.single()
        assertNull(ending.shell)
        assertTrue(ending.doubt.orEmpty(), ending.doubt.orEmpty().contains("process table unreadable"))
    }

    // the project closes - every thread ends while its terminal is still read, and the IDE then closes the tabs

    /**
     * At a project's close every thread is ended, asking nothing - a working one too, since the close is decided by
     * then - and one Skein has not tied to its shell yet gets the bracket's proof. A restored tab never shown has nothing
     * to end. The threads, their anchors and the launch slot stay as they were, and no tab is closed by Skein.
     */
    @Test
    fun aProjectCloseEndsEveryRunningThreadAskingNothing() {
        startIdleThread() // t1, shell 10, claude 100
        val working = claude(100, under = 10, activity = ClaudeActivity.BUSY)
        coordinator.onSnapshot(snap(working), viewed = { false }, now = 3_000)
        assertEquals(ThreadState.Working, thread("t1").state)
        coordinator.newThread("/q", now = 4_000) // t2, shell 11
        val s101 = claude(101, under = 11, startedAt = 4_500, cwd = "/q", sessionId = D) // its status file not matched yet
        terminal.lazyShells = true
        coordinator.restore(listOf(saved("r1", R)), snap(working), now = 4_600) // its shell starts only when shown
        val endings = coordinator.endingsAtProjectClose(snap(working, s101))
        assertEquals(listOf("t1", "t2", "r1"), endings.map { it.tab.id })
        assertEquals(Ending(endings[0].tab, ProcessId(10, t0), ProcessId(100, Instant.ofEpochMilli(1_400))), endings[0])
        assertEquals(Ending(endings[1].tab, ProcessId(11, t0), ProcessId(101, Instant.ofEpochMilli(4_400))), endings[1])
        assertEquals(Ending(endings[2].tab, shell = null, claude = null), endings[2])
        assertEquals(setOf("t1", "t2", "r1"), coordinator.threads.mapTo(HashSet()) { it.id })
        assertEquals(10L, thread("t1").anchor)
        assertNull(thread("t2").anchor)
        assertEquals("t2", slot.holder(now = 4_600))
        assertTrue(closed.isEmpty())
        runBlocking { CloseSequence(hangUpAndKill(), terminal, ThreadMapper(probe), timeoutMillis = 2_000) {}.endOnly(endings) }
        assertEquals(listOf("hup 100", "hup 10", "await t1", "hup 101", "hup 11", "await t2"), terminal.calls)
        assertTrue(terminal.closed.isEmpty())
    }

    /** A probe that fails on one thread at the project's close costs that thread its hangup, with a doubt, and never the rest. */
    @Test
    fun aProbeThatFailsAtTheProjectsCloseLosesNotTheOtherThreads() {
        var failOn: Long? = null
        val failing = object : ProcessProbe by probe {
            override fun startInstant(pid: Long): Instant? = if (pid == failOn) error("start unreadable") else probe.startInstant(pid)
        }
        var n = 0
        val window = ThreadCoordinator(terminal, ThreadMapper(failing), slot, events) { "w${++n}" }
        window.newThread("/p", now = 1_000)
        val s100 = claude(100, under = 10)
        window.onSnapshot(snap(s100), viewed = { false }, now = 2_000)
        window.newThread("/q", now = 3_000)
        val s101 = claude(101, under = 11, startedAt = 3_500, cwd = "/q", sessionId = D)
        window.onSnapshot(snap(s100, s101), viewed = { false }, now = 4_000)
        failOn = 10
        val endings = window.endingsAtProjectClose(snap(s100, s101))
        assertNull(endings[0].shell)
        assertTrue(endings[0].doubt.orEmpty(), endings[0].doubt.orEmpty().contains("start unreadable"))
        assertEquals(ProcessId(11, t0), endings[1].shell)
    }
}
