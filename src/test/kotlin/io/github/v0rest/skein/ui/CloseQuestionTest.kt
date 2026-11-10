package io.github.v0rest.skein.ui

import io.github.v0rest.skein.claude.WaitReason
import io.github.v0rest.skein.threads.SkeinThread
import io.github.v0rest.skein.threads.ThreadState
import io.github.v0rest.skein.ui.actions.CloseQuestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The words of the question Close asks while claude may be mid-turn ([io.github.v0rest.skein.threads.CloseRule]). */
class CloseQuestionTest {
    private val session = "55555555-5555-4555-8555-555555555555"
    private val after = "Closing ends what it is doing now; you can resume the session from the History later."

    private fun thread(id: String, name: String, state: ThreadState, sessionId: String? = null) =
        SkeinThread(id, "/w/$name", sessionId, name, state, stateSince = 0, createdAt = 0, anchor = null)

    @Test
    fun noBusyThreadNoQuestion() {
        assertNull(CloseQuestion.of(listOf(thread("t1", "acme", ThreadState.Idle), thread("t2", "beta", ThreadState.Exited)), emptyMap()))
        assertNull(CloseQuestion.of(emptyList(), emptyMap()))
    }

    @Test
    fun oneBusyThreadIsNamed() {
        val question = CloseQuestion.of(listOf(thread("t1", "acme", ThreadState.Working)), emptyMap())
        assertEquals(CloseQuestion.Question("Close Thread", "claude is still working in acme. $after", setOf("t1")), question)
    }

    /** Only the busy ones are named, by the name the list shows (a Skein name over claude's own), and only they are confirmed. */
    @Test
    fun severalThreadsNameOnlyTheBusyOnes() {
        val threads = listOf(
            thread("t1", "acme", ThreadState.Background, sessionId = session),
            thread("t2", "idle-one", ThreadState.Idle),
            thread("t3", "beta", ThreadState.NeedsYou(WaitReason.QUESTION, since = 1)),
        )
        val question = CloseQuestion.of(threads, mapOf(session to "Fix login"))
        assertEquals(CloseQuestion.Question("Close Threads", "claude is still working in Fix login, beta. $after", setOf("t1", "t3")), question)
    }

    @Test
    fun anUnknownThreadMayStillBeWorking() {
        val question = CloseQuestion.of(listOf(thread("t1", "acme", ThreadState.Unknown)), emptyMap())
        assertEquals(CloseQuestion.Question("Close Thread", "claude may still be working in acme. $after", setOf("t1")), question)
    }

    /** One question for all: the working ones first, then the ones whose state is not known. */
    @Test
    fun workingAndUnknownThreadsShareOneQuestion() {
        val threads = listOf(
            thread("t1", "acme", ThreadState.Unknown),
            thread("t2", "beta", ThreadState.Working),
            thread("t3", "gamma", ThreadState.Idle),
            thread("t4", "delta", ThreadState.Starting),
        )
        val question = CloseQuestion.of(threads, emptyMap())
        assertEquals(
            CloseQuestion.Question("Close Threads", "claude is still working in beta, delta. claude may still be working in acme. $after", setOf("t1", "t2", "t4")),
            question,
        )
    }
}
