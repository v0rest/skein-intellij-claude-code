package io.github.v0rest.skein.claude

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionFileParserTest {
    private fun ok(json: String): SessionFile = (SessionFileParser.parse(json) as SessionFileParse.Ok).file

    @Test
    fun idle() = assertEquals(ClaudeActivity.IDLE, ok("""{"pid":7,"status":"idle"}""").activity)

    @Test
    fun shellIsBackgroundWork() = assertEquals(ClaudeActivity.BACKGROUND, ok("""{"pid":7,"status":"shell"}""").activity)

    @Test
    fun busy() = assertEquals(ClaudeActivity.BUSY, ok("""{"pid":7,"status":"busy"}""").activity)

    @Test
    fun waitingQuestion() {
        val f = ok("""{"pid":7,"status":"waiting","waitingFor":"input needed"}""")
        assertEquals(ClaudeActivity.WAITING, f.activity)
        assertEquals("input needed", f.waitingFor)
        assertEquals(WaitReason.QUESTION, waitReasonOf(f.waitingFor))
    }

    @Test
    fun waitingPermission() = assertEquals(WaitReason.PERMISSION, waitReasonOf("permission prompt"))

    @Test
    fun waitingOther() {
        assertEquals(WaitReason.OTHER, waitReasonOf("sandbox request"))
        assertEquals(WaitReason.OTHER, waitReasonOf(null))
    }

    @Test
    fun unknownStatusKeepsRaw() {
        val f = ok("""{"pid":7,"status":"napping"}""")
        assertEquals(ClaudeActivity.UNKNOWN, f.activity)
        assertEquals("napping", f.rawStatus)
    }

    @Test
    fun emptyIsIncomplete() = assertEquals(SessionFileParse.Incomplete, SessionFileParser.parse(""))

    /** Claude Code makes up a name like "acme-c8" itself when nobody named the session, and says so in nameSource. */
    @Test
    fun aNameClaudeCodeMadeUpIsMarkedDerived() {
        val f = ok("""{"pid":7,"status":"idle","name":"acme-c8","nameSource":"derived"}""")
        assertEquals("acme-c8", f.name)
        assertTrue(f.nameDerived)
    }

    @Test
    fun aNameSomeoneGaveIsNotDerived() {
        assertEquals(false, ok("""{"pid":7,"status":"idle","name":"Fix login","nameSource":"user"}""").nameDerived)
        assertEquals(false, ok("""{"pid":7,"status":"idle","name":"Fix login"}""").nameDerived)
    }

    @Test
    fun blankIsIncomplete() = assertEquals(SessionFileParse.Incomplete, SessionFileParser.parse(" \n"))

    @Test
    fun truncatedIsIncomplete() = assertEquals(SessionFileParse.Incomplete, SessionFileParser.parse("""{"pid":7,"sta"""))

    @Test
    fun arrayIsUnrecognized() = assertTrue(SessionFileParser.parse("[]") is SessionFileParse.Unrecognized)

    @Test
    fun missingPidIsUnrecognized() = assertTrue(SessionFileParser.parse("""{"status":"idle"}""") is SessionFileParse.Unrecognized)

    @Test
    fun missingStatusIsUnrecognized() = assertTrue(SessionFileParser.parse("""{"pid":7}""") is SessionFileParse.Unrecognized)

    @Test
    fun wrongTypesAreUnrecognized() {
        assertTrue(SessionFileParser.parse("""{"pid":"seven","status":"idle"}""") is SessionFileParse.Unrecognized)
        assertTrue(SessionFileParser.parse("""{"pid":7,"status":5}""") is SessionFileParse.Unrecognized)
    }

    @Test
    fun absurdNumbersReadAsAbsentInsteadOfThrowing() {
        val f = ok("""{"pid":7,"status":"idle","startedAt":1e100000,"statusUpdatedAt":1.5}""")
        assertEquals(null, f.startedAt)
        assertEquals(1L, f.statusUpdatedAt)
        assertTrue(SessionFileParser.parse("""{"pid":1e100000,"status":"idle"}""") is SessionFileParse.Unrecognized)
    }

    @Test
    fun readsAllKnownFields() {
        val f = ok(
            """{"pid":69953,"sessionId":"55555555-5555-4555-8555-555555555555","cwd":"/Users/u/p","name":"skein",""" +
                """"status":"busy","statusUpdatedAt":1793917101498,"startedAt":1793914552362,""" +
                """"procStart":"Thu Nov  5 21:35:49 2026","version":"2.1.318","kind":"interactive","peerFeatures":["x"]}""",
        )
        assertEquals(
            SessionFile(
                pid = 69953,
                sessionId = "55555555-5555-4555-8555-555555555555",
                cwd = "/Users/u/p",
                name = "skein",
                activity = ClaudeActivity.BUSY,
                rawStatus = "busy",
                waitingFor = null,
                statusUpdatedAt = 1793917101498,
                startedAt = 1793914552362,
                procStart = "Thu Nov  5 21:35:49 2026",
                version = "2.1.318",
                kind = "interactive",
            ),
            f,
        )
    }

    @Test
    fun optionalFieldsMayBeNullOrAbsent() {
        val f = ok("""{"pid":7,"status":"idle","name":null,"cwd":null}""")
        assertEquals(null, f.name)
        assertEquals(null, f.cwd)
        assertEquals(null, f.sessionId)
        assertEquals(null, f.statusUpdatedAt)
    }
}
