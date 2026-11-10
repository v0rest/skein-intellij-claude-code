package io.github.v0rest.skein.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptReadinessTest {
    @Test
    fun waitsForQuietAfterFirstOutput() {
        val r = PromptReadiness()
        r.onOutput(100)
        r.onOutput(250)
        assertFalse(r.shouldSend(now = 400, startedAt = 0))
        assertTrue(r.shouldSend(now = 551, startedAt = 0))
    }

    @Test
    fun doesNotSendBeforeAnyOutput() {
        val r = PromptReadiness()
        assertFalse(r.shouldSend(now = 1000, startedAt = 0))
    }

    @Test
    fun sendsAfterMaxWaitWithoutOutput() {
        val r = PromptReadiness()
        assertFalse(r.shouldSend(now = 5000, startedAt = 0))
        assertTrue(r.shouldSend(now = 5001, startedAt = 0))
    }

    @Test
    fun keepsWaitingWhilePromptStreams() {
        val r = PromptReadiness()
        var t = 200L
        while (t <= 3000) {
            r.onOutput(t)
            t += 200
        }
        assertFalse(r.shouldSend(now = 3100, startedAt = 0))
        assertTrue(r.shouldSend(now = 3301, startedAt = 0))
    }

    @Test
    fun maxWaitAlsoCapsAStreamingPrompt() {
        val r = PromptReadiness(quietMillis = 300, maxWaitMillis = 5000)
        var t = 100L
        while (t <= 6000) {
            r.onOutput(t)
            t += 100
        }
        assertTrue(r.shouldSend(now = 6001, startedAt = 0))
    }
}
