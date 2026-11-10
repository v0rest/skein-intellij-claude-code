package io.github.v0rest.skein.threads

import io.github.v0rest.skein.claude.FileStamp
import io.github.v0rest.skein.claude.WaitReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThreadPreviewsTest {
    private fun assistant(vararg blocks: String) =
        """{"type":"assistant","uuid":"55555555-5555-4555-8555-555555555555","message":{"role":"assistant","content":[${blocks.joinToString(",")}]}}"""

    private fun user(text: String) = """{"type":"user","message":{"role":"user","content":"$text"}}"""

    private fun text(t: String) = """{"type":"text","text":"$t"}"""

    private fun toolUse(name: String, input: String, id: String) = """{"type":"tool_use","id":"$id","name":"$name","input":$input}"""

    /** One turn: an answer so far, then two calls without a result, the older one a file read. */
    private val lines = listOf(
        user("fix the chart"),
        assistant(text("Looking at the chart. It needs a test.")),
        assistant(toolUse("Read", """{"file_path":"/w/acme/src/Chart.kt"}""", "r1")),
        assistant(toolUse("Bash", """{"command":"./gradlew test"}""", "b1")),
    )

    private val answer = "Looking at the chart."

    @Test
    fun previewFollowsTheState() {
        // a permission prompt is about the oldest call still waiting, a question shows its answer when it has no question tool
        assertEquals("Read: Chart.kt", ThreadPreviews.previewOf(ThreadState.NeedsYou(WaitReason.PERMISSION, 0), lines))
        assertEquals(answer, ThreadPreviews.previewOf(ThreadState.NeedsYou(WaitReason.QUESTION, 0), lines))
        // a turn that finished, read or not: its last answer
        assertEquals(answer, ThreadPreviews.previewOf(ThreadState.YourTurn(0), lines))
        assertEquals(answer, ThreadPreviews.previewOf(ThreadState.Idle, lines))
        // still at it: the newest call
        for (state in listOf(ThreadState.Working, ThreadState.WorkingUnknown, ThreadState.Background, ThreadState.Starting)) {
            assertEquals("$state", "Bash: ./gradlew test", ThreadPreviews.previewOf(state, lines))
        }
        // nothing to say about a thread that is not running here
        for (state in listOf(ThreadState.NotStarted, ThreadState.Exited, ThreadState.Unknown, ThreadState.RunningElsewhere(1))) {
            assertNull("$state", ThreadPreviews.previewOf(state, lines))
        }
    }

    private var clock = 0L
    private var stamp: FileStamp? = FileStamp(1_000, 0)
    private var reads = 0

    private fun cache() = PreviewCache(
        read = { stamp },
        tail = {
            reads++
            lines
        },
        now = { clock },
    )

    private fun thread(state: ThreadState, stateSince: Long = 0, id: String = "t1") =
        SkeinThread(id, "/w/acme", "55555555-5555-4555-8555-555555555555", "acme", state, stateSince, 0, null)

    @Test
    fun anUnchangedTranscriptIsNotReadAgain() {
        for (state in listOf(ThreadState.Idle, ThreadState.Working)) {
            reads = 0
            val cache = cache()
            val first = cache.update(listOf(thread(state)))
            repeat(10) {
                clock += 1_000
                assertEquals("$state", first, cache.update(listOf(thread(state))))
            }
            assertEquals("$state", 1, reads)
        }
        // a transcript that changed under a thread that is not working is read at the next update
        reads = 0
        val cache = cache()
        cache.update(listOf(thread(ThreadState.Idle)))
        clock += 1_000
        stamp = FileStamp(2_000, clock)
        cache.update(listOf(thread(ThreadState.Idle)))
        assertEquals(2, reads)
    }

    @Test
    fun aWorkingThreadIsReadAtMostEveryFiveSeconds() {
        val cache = cache()
        // the transcript grows every second; six updates one second apart, at 0 s to 5 s
        repeat(6) { second ->
            clock = second * 1_000L
            stamp = FileStamp(1_000L + second, clock)
            assertEquals(mapOf("t1" to "Bash: ./gradlew test"), cache.update(listOf(thread(ThreadState.Working))))
        }
        assertEquals("read at 0 s and at 5 s", 2, reads)
    }

    @Test
    fun aStateChangeReadsAtOnce() {
        val cache = cache()
        assertEquals(mapOf("t1" to "Bash: ./gradlew test"), cache.update(listOf(thread(ThreadState.Working))))
        // the same transcript, but the turn is over: read now, not five seconds later
        clock = 1_000
        assertEquals(mapOf("t1" to answer), cache.update(listOf(thread(ThreadState.YourTurn(1_000), stateSince = 1_000))))
        assertEquals(2, reads)
        // working again
        clock = 2_000
        assertEquals(mapOf("t1" to "Bash: ./gradlew test"), cache.update(listOf(thread(ThreadState.Working, stateSince = 2_000))))
        assertEquals(3, reads)
        // the state was left and entered again between two updates: only the time it was entered tells
        clock = 3_000
        cache.update(listOf(thread(ThreadState.Working, stateSince = 2_500)))
        assertEquals(4, reads)
    }

    @Test
    fun aThreadWithNothingToShowReadsNothingAndHasNoEntry() {
        val cache = cache()
        assertEquals(emptyMap<String, String>(), cache.update(listOf(thread(ThreadState.Exited), thread(ThreadState.NotStarted, id = "t2"))))
        assertEquals("a state without a preview reads no transcript", 0, reads)
        // no transcript yet
        stamp = null
        assertEquals(emptyMap<String, String>(), cache.update(listOf(thread(ThreadState.Idle))))
        assertEquals(0, reads)
        // a thread that is gone leaves no entry behind
        stamp = FileStamp(1_000, 0)
        assertEquals(setOf("t1", "t2"), cache.update(listOf(thread(ThreadState.Idle), thread(ThreadState.Idle, id = "t2"))).keys)
        assertEquals(setOf("t2"), cache.update(listOf(thread(ThreadState.Idle, id = "t2"))).keys)
    }

    private val withUsage = lines + """{"type":"assistant","timestamp":"2026-11-10T10:00:00Z","message":{"model":"claude-opus-5-5","usage":{"input_tokens":4,"cache_read_input_tokens":385000}}}"""

    @Test
    fun theModelAndContextTravelWithThePreviewFromTheSameRead() {
        reads = 0
        val cache = PreviewCache(read = { stamp }, tail = { reads++; withUsage }, now = { clock })
        val threads = listOf(thread(ThreadState.Idle), thread(ThreadState.Exited, id = "t2"), thread(ThreadState.Idle, id = "t3"))
        val result = cache.refresh(threads)
        assertEquals(setOf("t1", "t3"), result.contexts.keys)
        assertEquals(385_004L, result.contexts.getValue("t1").tokens)
        assertEquals("claude-opus-5-5", result.contexts.getValue("t1").model)
        assertEquals("one read per thread, for both", 2, reads)
        // unchanged: the same values, no new read
        clock += 1_000
        assertEquals(result.contexts, cache.refresh(threads).contexts)
        assertEquals(2, reads)
        // update() keeps saying what it said
        assertEquals(result.lines, cache.update(threads))
    }

    private val compact = """{"type":"system","subtype":"compact_boundary"}"""

    private fun cacheOf(vararg tails: List<String>): PreviewCache {
        var i = 0
        return PreviewCache(read = { stamp }, tail = { tails[minOf(i++, tails.size - 1)] }, now = { clock })
    }

    private fun sessionThread(session: String, since: Long) =
        SkeinThread("t1", "/w/acme", session, "acme", ThreadState.Idle, since, 0, null)

    @Test
    fun aReReadWithoutAnAnswerKeepsTheValueForTheSameSessionOnly() {
        val s1 = "55555555-5555-4555-8555-555555555555"
        val s2 = "66666666-6666-4666-8666-666666666666"
        val cache = cacheOf(withUsage, listOf(user("next")), listOf(user("next")), listOf(user("next")))
        assertEquals(385_004L, cache.refresh(listOf(sessionThread(s1, 0))).contexts.getValue("t1").tokens)
        stamp = FileStamp(2_000, 1)
        clock += 1_000
        assertEquals("kept", 385_004L, cache.refresh(listOf(sessionThread(s1, 1))).contexts.getValue("t1").tokens)
        stamp = FileStamp(3_000, 2)
        assertEquals("another session does not inherit it", emptyMap<String, ContextUse>(), cache.refresh(listOf(sessionThread(s2, 2))).contexts)
        // and a thread without a session id has nothing to carry
        stamp = FileStamp(4_000, 3)
        assertEquals(emptyMap<String, ContextUse>(), cache.refresh(listOf(sessionThread(s2, 3).copy(sessionId = null))).contexts)
    }

    @Test
    fun aCompactionAfterTheLastAnswerResetsAndANewAnswerShowsAgain() {
        val s1 = "55555555-5555-4555-8555-555555555555"
        val fresh = """{"type":"assistant","message":{"model":"claude-opus-5-5","usage":{"input_tokens":7}}}"""
        val cache = cacheOf(withUsage, withUsage + compact, withUsage + compact + fresh)
        assertEquals(385_004L, cache.refresh(listOf(sessionThread(s1, 0))).contexts.getValue("t1").tokens)
        stamp = FileStamp(2_000, 1)
        assertEquals("reset, not kept", emptyMap<String, ContextUse>(), cache.refresh(listOf(sessionThread(s1, 1))).contexts)
        stamp = FileStamp(3_000, 2)
        assertEquals(7L, cache.refresh(listOf(sessionThread(s1, 2))).contexts.getValue("t1").tokens)
    }

    @Test
    fun aTailWithoutUsageGivesNoContext() {
        val cache = cache()
        assertEquals(emptyMap<String, ContextUse>(), cache.refresh(listOf(thread(ThreadState.Idle))).contexts)
        stamp = null
        assertEquals(emptyMap<String, ContextUse>(), cache.refresh(listOf(thread(ThreadState.Idle))).contexts)
    }
}
