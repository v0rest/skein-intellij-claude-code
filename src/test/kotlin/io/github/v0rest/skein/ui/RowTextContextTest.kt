package io.github.v0rest.skein.ui

import io.github.v0rest.skein.threads.ContextUse
import org.junit.Assert.assertEquals
import org.junit.Test

class RowTextContextTest {
    private val now = 1_000_000_000_000L

    @Test
    fun theTooltipNamesTheModelTheExactCountAndWhenClaudeAnswered() {
        val use = ContextUse("claude-opus-5-5[1m]", 385_279, now - 2 * 60_000)
        assertEquals("claude-opus-5-5[1m] · 385,279 tokens in context", use.tooltipBase)
        assertEquals("2 min ago", RowText.answered(use, now))
        assertEquals("just now", RowText.answered(ContextUse("claude-opus-5-5", 1, now - 5_000), now))
        assertEquals("claude-opus-5-5 · 1 token in context", ContextUse("claude-opus-5-5", 1, now).tooltipBase)
        assertEquals("no time known, none shown", null, RowText.answered(ContextUse("claude-opus-5-5", 12, null), now))
    }
}
