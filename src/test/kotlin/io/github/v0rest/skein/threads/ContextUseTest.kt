package io.github.v0rest.skein.threads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class ContextUseTest {
    private fun entry(
        model: String? = "claude-opus-5-5",
        usage: String? = """{"input_tokens":5,"cache_creation_input_tokens":100,"cache_read_input_tokens":900,"output_tokens":7000}""",
        type: String = "assistant",
        sidechain: Boolean? = null,
        timestamp: String? = "2026-11-10T10:00:00.000Z",
    ): String {
        val parts = listOfNotNull(
            """"type":"$type"""",
            sidechain?.let { """"isSidechain":$it""" },
            timestamp?.let { """"timestamp":"$it"""" },
            """"message":{"role":"assistant"${model?.let { ""","model":"$it"""" }.orEmpty()}${usage?.let { ""","usage":$it""" }.orEmpty()}}""",
        )
        return "{" + parts.joinToString(",") + "}"
    }

    @Test
    fun sumsTheInputSideOfTheLastAnswer() {
        val use = ContextUse.fromTail(listOf(entry()))!!
        assertEquals("claude-opus-5-5", use.model)
        assertEquals(1_005L, use.tokens)
        assertEquals(Instant.parse("2026-11-10T10:00:00Z").toEpochMilli(), use.at)
    }

    @Test
    fun theLastQualifyingEntryWins() {
        val lines = listOf(
            entry(model = "claude-sonnet-4-5", usage = """{"input_tokens":10}"""),
            entry(usage = """{"input_tokens":20,"cache_read_input_tokens":30}"""),
            entry(sidechain = true, usage = """{"input_tokens":999}"""),
            entry(model = "<synthetic>", usage = """{"input_tokens":999}"""),
            entry(usage = """{"input_tokens":0,"output_tokens":5}"""),
            entry(usage = null),
            entry(type = "user"),
            "not json {",
        )
        val use = ContextUse.fromTail(lines)!!
        assertEquals("claude-opus-5-5", use.model)
        assertEquals(50L, use.tokens)
    }

    @Test
    fun nullWhenNothingQualifies() {
        assertNull(ContextUse.fromTail(emptyList()))
        assertNull(ContextUse.fromTail(listOf(entry(sidechain = true), entry(model = null), entry(model = "<synthetic>"), entry(usage = """{"output_tokens":4}"""))))
        assertNull(ContextUse.fromTail(listOf("{\"type\":\"assistant\",\"message\":")))
    }

    @Test
    fun aSidechainFlagThatIsFalseCounts() {
        assertEquals(1_005L, ContextUse.fromTail(listOf(entry(sidechain = false)))!!.tokens)
    }

    @Test
    fun aMissingOrOddTimestampLeavesTheTimeUnknown() {
        assertNull(ContextUse.fromTail(listOf(entry(timestamp = null)))!!.at)
        assertNull(ContextUse.fromTail(listOf(entry(timestamp = "yesterday")))!!.at)
    }

    private fun compactBoundary() = """{"type":"system","subtype":"compact_boundary","timestamp":"2026-11-10T10:05:00Z"}"""

    private fun compactSummary() = """{"type":"user","isCompactSummary":true,"message":{"role":"user","content":"summary"}}"""

    @Test
    fun scanTellsFoundResetAndNothingApart() {
        assertEquals(ContextUse.Scan.NothingInTail, ContextUse.scan(listOf(entry(type = "user"), "{")))
        assertEquals(ContextUse.Scan.NothingInTail, ContextUse.scan(emptyList()))
        assertEquals(ContextUse.Scan.Found(ContextUse("claude-opus-5-5", 1_005, Instant.parse("2026-11-10T10:00:00Z").toEpochMilli())), ContextUse.scan(listOf(entry())))
        // a marker newer than the last answer resets, whichever form it takes
        assertEquals(ContextUse.Scan.Reset, ContextUse.scan(listOf(entry(), compactBoundary())))
        assertEquals(ContextUse.Scan.Reset, ContextUse.scan(listOf(entry(), compactSummary())))
        assertEquals(ContextUse.Scan.Reset, ContextUse.scan(listOf(entry(), compactBoundary(), compactSummary(), entry(type = "user"))))
        // an answer after the marker is the new value
        val after = entry(usage = """{"input_tokens":42}""")
        assertEquals(42L, (ContextUse.scan(listOf(entry(), compactBoundary(), compactSummary(), after)) as ContextUse.Scan.Found).use.tokens)
        // a marker alone, with no answer before it in the tail, still resets
        assertEquals(ContextUse.Scan.Reset, ContextUse.scan(listOf(compactBoundary())))
    }

    @Test
    fun equalValuesCompareEqual() {
        assertEquals(ContextUse("claude-opus-5-5", 5, 1L), ContextUse("claude-opus-5-5", 5, 1L))
        assertEquals(ContextUse("claude-opus-5-5", 5, 1L).hashCode(), ContextUse("claude-opus-5-5", 5, 1L).hashCode())
    }

    @Test
    fun theTextsAreMadeOnce() {
        val use = ContextUse("claude-opus-5-5[1m]", 385_279, null)
        assertEquals("claude-opus-5-5[1m] · 385,279 tokens in context", use.tooltipBase)
        assertEquals("Opus, 385k tokens of context", use.spoken)
        assertEquals("claude-x · 1 token in context", ContextUse("claude-x", 1, null).tooltipBase)
    }

    @Test
    fun aProviderPrefixedIdStillReadsAsItsFamily() {
        assertEquals("Sonnet", ContextUse("us.anthropic.claude-sonnet-4-5-20250929-v1:0", 1, null).family)
        assertEquals("Haiku", ContextUse("anthropic.claude-haiku-4-5[1m]", 1, null).family)
    }

    @Test
    fun theFamilyLabel() {
        fun label(model: String) = ContextUse(model, 1, null).family
        assertEquals("Opus", label("claude-opus-5-5"))
        assertEquals("Sonnet", label("claude-sonnet-4-5-20250929"))
        assertEquals("Haiku", label("claude-haiku-4-5"))
        assertEquals("Fable", label("claude-fable-1"))
        assertEquals("a [1m] suffix is dropped", "Opus", label("claude-opus-5-5[1m]"))
        assertEquals("other ids lose the claude- prefix", "gpt-neo", label("gpt-neo"))
        assertEquals("and are cut at 12 characters with an ellipsis", "some-model-n…", label("claude-some-model-name"))
        assertEquals("some-model-n…", label("some-model-name[1m]"))
    }

    @Test
    fun theTokenLabel() {
        fun label(n: Long) = ContextUse.tokenLabel(n)
        assertEquals("999", label(999))
        assertEquals("1.0k", label(1_000))
        assertEquals("8.4k", label(8_449))
        assertEquals("9.9k", label(9_999))
        assertEquals("10k", label(10_000))
        assertEquals("385k", label(385_279))
        assertEquals("rounded down, so a label never claims more than is there", "999k", label(999_999))
        assertEquals("1.0M", label(1_000_000))
        assertEquals("1.9M", label(1_999_999))
        assertEquals("1.2M", label(1_200_000))
    }

    @Test
    fun theRowLabelAndTheExactCount() {
        val use = ContextUse("claude-opus-5-5", 385_279, null)
        assertEquals("Opus · 385k", use.label)
        assertEquals("385,279", ContextUse.exact(385_279))
    }
}
