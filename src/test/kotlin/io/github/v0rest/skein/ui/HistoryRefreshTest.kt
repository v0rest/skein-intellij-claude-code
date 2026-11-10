package io.github.v0rest.skein.ui

import io.github.v0rest.skein.claude.ClaudeActivity
import io.github.v0rest.skein.claude.SessionSnapshot
import io.github.v0rest.skein.testutil.sessionFile
import io.github.v0rest.skein.threads.SkeinThread
import io.github.v0rest.skein.threads.ThreadState
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryRefreshTest {
    private fun thread(id: String, session: String?, state: ThreadState = ThreadState.Idle, since: Long = 0) =
        SkeinThread(id = id, folder = "/p", sessionId = session, name = id, state = state, stateSince = since, createdAt = 0, anchor = null)

    @Test
    fun rescansOnlyWhenAThreadComesGoesOrChangesItsSession() {
        val updates = flowOf(
            listOf(thread("a", "s1")), // what the state flow replays on subscribe, right after the panel's own scan
            listOf(thread("a", "s1", ThreadState.Working, since = 5)), // a status flip
            listOf(thread("a", "s1", ThreadState.YourTurn(9), since = 9)), // another one
            listOf(thread("a", "s1"), thread("b", null)), // a new thread
            listOf(thread("a", "s2"), thread("b", null)), // /clear gave a a new session, so s1 lands in history
            listOf(thread("b", null)), // a closed
        )
        assertEquals(3, runBlocking { historyRescans(updates).toList() }.size)
    }

    @Test
    fun runningLabelsFollowSessionsStartingAndStoppingNotTheirStatus() {
        val a = sessionFile(pid = 1, sessionId = "a")
        val b = sessionFile(pid = 2, sessionId = "b")
        val snapshots = flowOf(
            SessionSnapshot(mapOf(1L to a), emptySet()),
            SessionSnapshot(mapOf(1L to sessionFile(pid = 1, activity = ClaudeActivity.BUSY, sessionId = "a")), emptySet()), // a status change
            SessionSnapshot(mapOf(1L to a, 2L to b), emptySet()), // b starts
            SessionSnapshot(mapOf(2L to b), emptySet()), // a stops
        )
        assertEquals(listOf(setOf("a"), setOf("a", "b"), setOf("b")), runBlocking { liveSessionIds(snapshots).toList() })
    }
}
