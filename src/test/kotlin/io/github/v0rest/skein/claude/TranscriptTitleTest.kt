package io.github.v0rest.skein.claude

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranscriptTitleTest {
    private fun custom(t: String) = """{"type":"custom-title","customTitle":"$t","sessionId":"s"}"""
    private fun ai(t: String) = """{"type":"ai-title","aiTitle":"$t","sessionId":"s"}"""
    private fun agent(t: String) = """{"type":"agent-name","agentName":"$t","sessionId":"s"}"""
    private fun prompt(t: String) = """{"type":"last-prompt","lastPrompt":${'"'}$t${'"'},"sessionId":"s"}"""

    @Test
    fun customTitleWinsOverLaterAiTitle() = assertEquals("acme", TranscriptTitle.fromLines(listOf(custom("acme"), ai("Fix chart"))))

    @Test
    fun latestCustomTitleWins() = assertEquals("b", TranscriptTitle.fromLines(listOf(custom("a"), custom("b"))))

    @Test
    fun fallsBackToAiTitleThenAgentName() {
        assertEquals("Fix chart", TranscriptTitle.fromLines(listOf(agent("worker"), ai("Fix chart"))))
        assertEquals("worker", TranscriptTitle.fromLines(listOf(prompt("hello"), agent("worker"))))
    }

    @Test
    fun fallsBackToLastPromptFirstLine() =
        assertEquals("deploy the chart", TranscriptTitle.fromLines(listOf(prompt("deploy the chart\\nplease"))))

    @Test
    fun lastPromptIsTrimmedTo120Chars() {
        val long = "x".repeat(200)
        assertEquals("x".repeat(120), TranscriptTitle.fromLines(listOf(prompt(long))))
    }

    @Test
    fun lastPromptSkipsLeadingBlankLines() =
        assertEquals("deploy it", TranscriptTitle.fromLines(listOf(prompt("\\n\\n  deploy it\\nnow"))))

    @Test
    fun lastPromptIsNotCutInsideASurrogatePair() {
        val emoji = "🚀" // one code point, two chars
        val title = TranscriptTitle.fromLines(listOf("""{"type":"last-prompt","lastPrompt":"${"x".repeat(119)}$emoji tail"}"""))!!
        assertEquals("x".repeat(119) + emoji, title)
    }

    @Test
    fun rankedTitlesMergeAcrossChunks() {
        val tail = TranscriptTitle.ranked(listOf(prompt("latest prompt")))
        val head = TranscriptTitle.ranked(listOf(custom("my name")))
        assertEquals("my name", TranscriptTitle.best(tail, head))
        assertEquals("my name", TranscriptTitle.best(head, tail))
        assertEquals("newer", TranscriptTitle.best(TranscriptTitle.ranked(listOf(custom("newer"))), TranscriptTitle.ranked(listOf(custom("older")))))
    }

    @Test
    fun cwdIsFoundInATruncatedFirstLine() =
        assertEquals("/w/p", TranscriptCwd.fromChunk("""{"parentUuid":null,"cwd":"/w/p","message":{"content":"${"x".repeat(100)}"""))

    @Test
    fun cwdFromChunkHandlesEscapes() =
        assertEquals("/w/a \"b\"", TranscriptCwd.fromChunk("""{"cwd":"/w/a \"b\"","type":"user"}"""))

    @Test
    fun blankTitlesAreIgnored() = assertEquals("b", TranscriptTitle.fromLines(listOf(custom("b"), custom("  "))))

    @Test
    fun nullCustomTitleIsIgnored() =
        assertEquals("Fix chart", TranscriptTitle.fromLines(listOf("""{"type":"custom-title","customTitle":null}""", ai("Fix chart"))))

    @Test
    fun noTitleIsNull() = assertNull(TranscriptTitle.fromLines(listOf("""{"type":"user","cwd":"/p"}""")))

    @Test
    fun malformedLinesAreSkipped() = assertEquals("b", TranscriptTitle.fromLines(listOf("{garbage", "", custom("b"), "[1,2]")))

    @Test
    fun cwdIsTheFirstNonBlankOne() =
        assertEquals("/w/p", TranscriptCwd.fromLines(listOf("""{"type":"permission-mode"}""", """{"type":"user","cwd":""}""", """{"type":"user","cwd":"/w/p"}""", """{"type":"user","cwd":"/w/q"}""")))

    @Test
    fun missingCwdIsNull() = assertNull(TranscriptCwd.fromLines(listOf("""{"type":"user"}""", "{bad")))

    @Test
    fun continuationIsRead() {
        assertEquals("b2", TranscriptContinuation.fromLines(listOf(custom("x"), """{"type":"continued-in","continuedInSessionId":"b2","sessionId":"a1"}""")))
        assertNull(TranscriptContinuation.fromLines(listOf(custom("x"))))
    }
}
