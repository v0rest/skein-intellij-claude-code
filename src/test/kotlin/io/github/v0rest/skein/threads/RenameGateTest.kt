package io.github.v0rest.skein.threads

import io.github.v0rest.skein.claude.ClaudeActivity
import io.github.v0rest.skein.claude.WaitReason
import io.github.v0rest.skein.process.ProcessId
import io.github.v0rest.skein.terminal.DraftGuard
import io.github.v0rest.skein.testutil.sessionFile
import io.github.v0rest.skein.threads.RenameGate.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * When Skein types `/rename` into a thread's claude: only into the claude that runs that very session, alive under the
 * tab's shell, idle (never in a dialog, at work or starting), with nothing typed into the tab since the last Enter that
 * sent it, and only a name the shell underneath would read as plain text. Everything else waits for the next look.
 */
class RenameGateTest {
    private val s = "55555555-5555-4555-8555-555555555555"

    private fun decide(
        name: String = "task-1",
        runsSession: Boolean = true,
        state: ThreadState? = ThreadState.Idle,
        claudeAlive: Boolean = true,
        inputClean: Boolean = true,
        vimMode: Boolean = false,
    ) = RenameGate.decide(name, runsSession, state, claudeAlive, inputClean, vimMode)

    @Test
    fun anIdleThreadWithACleanInputGetsItNow() {
        assertEquals(Decision.SEND, decide())
        assertEquals(Decision.SEND, decide(state = ThreadState.YourTurn(since = 5)))
    }

    @Test
    fun everyOtherStateWaits() {
        val others = listOf(
            ThreadState.NeedsYou(WaitReason.PERMISSION, 1), ThreadState.NeedsYou(WaitReason.QUESTION, 1),
            ThreadState.NeedsYou(WaitReason.OTHER, 1), ThreadState.NeedsYou(WaitReason.TRUST, 1),
            ThreadState.Working, ThreadState.WorkingUnknown, ThreadState.Background, ThreadState.Starting,
            ThreadState.NotStarted, ThreadState.RunningElsewhere(7), ThreadState.Exited, ThreadState.Unknown, null,
        )
        for (state in others) assertEquals(state.toString(), Decision.WAIT, decide(state = state))
    }

    @Test
    fun aThreadThatDoesNotRunThatSessionWaits() {
        assertEquals(Decision.WAIT, decide(runsSession = false))
    }

    @Test
    fun aClaudeThatIsGoneWaits() {
        assertEquals(Decision.WAIT, decide(claudeAlive = false))
    }

    @Test
    fun aDraftOrKeysThatHaveNotSettledWait() {
        assertEquals(Decision.WAIT, decide(inputClean = false))
    }

    /** In vim mode typed text may run as commands: the rename waits, and goes once the setting is gone. */
    @Test
    fun vimModeKeepsItWaiting() {
        assertEquals(Decision.WAIT, decide(vimMode = true))
        assertEquals(Decision.DROP, decide(name = "demo; rm", vimMode = true))
    }

    /** A name the rule refuses can never be sent, so it is dropped rather than kept waiting forever. */
    @Test
    fun aNameTheShellWouldReadIsDropped() {
        assertEquals(Decision.DROP, decide(name = "demo; rm -rf ~"))
        assertEquals(Decision.DROP, decide(name = "demo; rm -rf ~", state = ThreadState.Working, inputClean = false))
        assertEquals(Decision.DROP, decide(name = ""))
    }

    /**
     * An Enter on a draft may send it only while claude is idle: at work it may queue it or take a suggestion into it, in
     * a dialog it answers that, and in a state Skein is unsure of either may happen.
     */
    @Test
    fun anEnterMaySendTheInputOnlyWhileClaudeIsIdle() {
        for (state in listOf(ThreadState.Idle, ThreadState.YourTurn(1))) assertTrue(state.toString(), RenameGate.enterMaySend(state))
        for (state in listOf(ThreadState.Working, ThreadState.Background, ThreadState.NeedsYou(WaitReason.PERMISSION, 1), ThreadState.NeedsYou(WaitReason.TRUST, 1), ThreadState.WorkingUnknown, ThreadState.Starting, ThreadState.NotStarted, ThreadState.RunningElsewhere(7), ThreadState.Exited, ThreadState.Unknown)) {
            assertFalse(state.toString(), RenameGate.enterMaySend(state))
        }
    }

    /**
     * The states that, seen starting right after such an Enter, show it sent the draft: a turn or a background task. A
     * dialog does not, nor a status Skein does not know, nor the trust question that comes before any turn.
     */
    @Test
    fun aTurnOrABackgroundTaskShowClaudeAtWork() {
        for (state in listOf(ThreadState.Working, ThreadState.Background)) assertTrue(state.toString(), RenameGate.atWork(state))
        for (state in listOf(
            ThreadState.NeedsYou(WaitReason.PERMISSION, 1), ThreadState.NeedsYou(WaitReason.QUESTION, 1), ThreadState.NeedsYou(WaitReason.OTHER, 1),
            ThreadState.NeedsYou(WaitReason.TRUST, 1), ThreadState.WorkingUnknown, ThreadState.Idle, ThreadState.YourTurn(1), ThreadState.Starting,
            ThreadState.NotStarted, ThreadState.RunningElsewhere(7), ThreadState.Exited, ThreadState.Unknown,
        )) {
            assertFalse(state.toString(), RenameGate.atWork(state))
        }
    }

    private fun thread(id: String, state: ThreadState, since: Long, folder: String = "/w/demo-shop", sessionCwd: String? = null) =
        SkeinThread(id, folder, s, "demo-shop", state, since, 0, 10, sessionCwd = sessionCwd)

    /**
     * Claude idle with a draft in its input starts a turn of its own that asks for permission before Skein sees it at
     * work; the Enter that answers that dialog looks like an Enter on the draft. The dialog seen right after it must not
     * count as the turn that sent the draft.
     */
    @Test
    fun aDialogSeenRightAfterAnEnterDoesNotProveIt() {
        var draft = DraftGuard.next(DraftGuard.Draft(), DraftGuard.Key.OTHER, enterMaySend = true, at = 1_000, wallAt = 49_000)
        draft = DraftGuard.next(draft, DraftGuard.Key.ENTER, enterMaySend = RenameGate.enterMaySend(ThreadState.Idle), at = 2_000, wallAt = 50_000)
        val asking = RenameGate.seen(listOf(thread("t1", ThreadState.NeedsYou(WaitReason.PERMISSION, 51_000), since = 51_000)))
        val proven = DraftGuard.proven(draft, asking.atWorkSince["t1"], null)
        assertEquals(null, proven)
        assertFalse(DraftGuard.settled(draft, proven).clean)
        // a turn seen at work right after the Enter is what proves it
        val working = RenameGate.seen(listOf(thread("t1", ThreadState.Working, since = 51_000)))
        assertTrue(DraftGuard.settled(draft, DraftGuard.proven(draft, working.atWorkSince["t1"], null)).clean)
    }

    /** What the key watch learns: the idle threads, where an Enter may send, and since when the ones at work are so. */
    @Test
    fun theKeyWatchLearnsWhoIsIdleAndSinceWhenTheOthersWork() {
        val seen = RenameGate.seen(
            listOf(
                thread("idle", ThreadState.Idle, since = 1), thread("turn", ThreadState.YourTurn(2), since = 2),
                thread("work", ThreadState.Working, since = 3), thread("bg", ThreadState.Background, since = 4),
                thread("ask", ThreadState.NeedsYou(WaitReason.PERMISSION, 5), since = 5), thread("odd", ThreadState.WorkingUnknown, since = 6),
                thread("new", ThreadState.Starting, since = 7),
            ),
        )
        assertEquals(setOf("idle", "turn"), seen.enterMaySend)
        assertEquals(mapOf("work" to 3L, "bg" to 4L), seen.atWorkSince)
    }

    /** The project settings that count for a thread's claude: those of its tab's folder and of where claude runs. */
    @Test
    fun aThreadsFoldersAreItsTabsAndItsClaudes() {
        assertEquals(listOf("/w/demo-shop"), RenameGate.folders(thread("t1", ThreadState.Idle, 1)))
        assertEquals(listOf("/w/demo-shop"), RenameGate.folders(thread("t1", ThreadState.Idle, 1, sessionCwd = " ")))
        assertEquals(listOf("/w/demo-shop"), RenameGate.folders(thread("t1", ThreadState.Idle, 1, sessionCwd = "/w/demo-shop")))
        assertEquals(
            listOf("/w/demo-shop", "/w/demo-shop/.claude/worktrees/task-1"),
            RenameGate.folders(thread("t1", ThreadState.Idle, 1, sessionCwd = "/w/demo-shop/.claude/worktrees/task-1")),
        )
    }

    /**
     * The last look right before the line goes, on what was read just then: the same claude (its start), under the same
     * shell, idle in that session by its status file, and no vim mode. Each one failing alone stops the line.
     */
    @Test
    fun theLastLookNeedsEveryPart() {
        val start = Instant.ofEpochMilli(1_400)
        val claude = ProcessId(100, start)
        val idle = sessionFile(pid = 100, sessionId = s, activity = ClaudeActivity.IDLE)
        fun look(startNow: Instant? = start, anchorNow: Long? = 10, file: io.github.v0rest.skein.claude.SessionFile? = idle, vim: Boolean = false) =
            RenameGate.lastLook(claude, startNow, anchor = 10, anchorNow = anchorNow, file = file, sessionId = s, vimMode = vim)
        assertTrue(look())
        assertFalse("another process got the pid", look(startNow = Instant.ofEpochMilli(9_000)))
        assertFalse("claude is gone", look(startNow = null))
        assertFalse("under another shell", look(anchorNow = 11))
        assertFalse("not under the IDE any more", look(anchorNow = null))
        assertFalse("at work", look(file = sessionFile(pid = 100, sessionId = s, activity = ClaudeActivity.BUSY)))
        assertFalse("another session", look(file = sessionFile(pid = 100, sessionId = "66666666-6666-4666-8666-666666666666")))
        assertFalse("no status file", look(file = null))
        assertFalse("vim mode", look(vim = true))
    }

    /** The status file read right before the send must be that claude's, for that session, and say idle. */
    @Test
    fun theLastReadMustSayIdleForThatClaudeAndSession() {
        assertTrue(RenameGate.stillIdle(sessionFile(pid = 100, sessionId = s, activity = ClaudeActivity.IDLE), claudePid = 100, sessionId = s))
        assertFalse(RenameGate.stillIdle(sessionFile(pid = 100, sessionId = s, activity = ClaudeActivity.BUSY), claudePid = 100, sessionId = s))
        assertFalse(RenameGate.stillIdle(sessionFile(pid = 100, sessionId = s, activity = ClaudeActivity.WAITING), claudePid = 100, sessionId = s))
        assertFalse(RenameGate.stillIdle(sessionFile(pid = 100, sessionId = s, activity = ClaudeActivity.BACKGROUND), claudePid = 100, sessionId = s))
        assertFalse(RenameGate.stillIdle(sessionFile(pid = 100, sessionId = s, activity = ClaudeActivity.UNKNOWN), claudePid = 100, sessionId = s))
        assertFalse(RenameGate.stillIdle(sessionFile(pid = 101, sessionId = s), claudePid = 100, sessionId = s))
        assertFalse(RenameGate.stillIdle(sessionFile(pid = 100, sessionId = "66666666-6666-4666-8666-666666666666"), claudePid = 100, sessionId = s))
        assertFalse(RenameGate.stillIdle(null, claudePid = 100, sessionId = s))
    }
}
