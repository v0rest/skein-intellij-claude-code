package io.github.v0rest.skein.threads

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectClosingTest {
    /** A close refused after Skein ended the threads leaves the project open, and the flag goes once the attempt is over. */
    @Test
    fun aRefusedCloseLiftsTheFlag() {
        val state = ProjectClosing()
        state.endingThreads()
        assertTrue(state.closing)
        assertTrue(state.closeAttemptOver(projectOpen = true))
        assertFalse(state.closing)
        // the next close attempt sets it again
        state.endingThreads()
        assertTrue(state.closing)
    }

    /** Once the platform said the project closes, nothing lifts the flag, open or not. */
    @Test
    fun aConfirmedCloseKeepsTheFlag() {
        val state = ProjectClosing()
        state.endingThreads()
        state.platformCloses()
        assertFalse(state.closeAttemptOver(projectOpen = true))
        assertTrue(state.closing)
    }

    /** A project that is no longer open keeps the flag. */
    @Test
    fun aProjectNoLongerOpenKeepsTheFlag() {
        val state = ProjectClosing()
        state.endingThreads()
        assertFalse(state.closeAttemptOver(projectOpen = false))
        assertTrue(state.closing)
    }

    /** Without a close under way there is nothing to lift, and the caller leaves the terminal side alone. */
    @Test
    fun noCloseUnderWayLiftsNothing() {
        val state = ProjectClosing()
        assertFalse(state.closeAttemptOver(projectOpen = true))
        assertFalse(state.closing)
    }
}
