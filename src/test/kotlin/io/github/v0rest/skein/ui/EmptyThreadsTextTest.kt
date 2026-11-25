package io.github.v0rest.skein.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class EmptyThreadsTextTest {
    @Test
    fun anEmptyListWithNothingWaitingSaysSo() {
        assertEquals("No threads yet", EmptyThreadsText.of(0))
    }

    /** Saved threads held back until the project is trusted must not look lost. */
    @Test
    fun threadsThatWaitForTrustAreCounted() {
        assertEquals("1 thread comes back once you trust the project", EmptyThreadsText.of(1))
        assertEquals("3 threads come back once you trust the project", EmptyThreadsText.of(3))
    }
}
