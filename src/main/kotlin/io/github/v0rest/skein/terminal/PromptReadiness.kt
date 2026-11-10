package io.github.v0rest.skein.terminal

/**
 * When to type into a fresh shell: after its first output has gone quiet for [quietMillis], or after [maxWaitMillis]
 * regardless, so a silent or endlessly chatty prompt still gets the command exactly once.
 */
class PromptReadiness(val quietMillis: Long = 300, val maxWaitMillis: Long = 5_000) {
    @Volatile
    private var lastOutputAt: Long? = null

    fun onOutput(now: Long) {
        lastOutputAt = now
    }

    fun shouldSend(now: Long, startedAt: Long): Boolean {
        if (now - startedAt > maxWaitMillis) return true
        val last = lastOutputAt ?: return false
        return now - last > quietMillis
    }
}
