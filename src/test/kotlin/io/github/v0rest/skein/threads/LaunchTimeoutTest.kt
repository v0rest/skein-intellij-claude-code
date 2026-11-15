package io.github.v0rest.skein.threads

import io.github.v0rest.skein.claude.FolderTrust
import io.github.v0rest.skein.claude.FolderTrust.NOT_TRUSTED
import io.github.v0rest.skein.claude.FolderTrust.TRUSTED
import io.github.v0rest.skein.claude.FolderTrust.UNKNOWN
import io.github.v0rest.skein.threads.LaunchTimeout.Outcome
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a launch shows when its status file did not come in time: claude waiting on its folder-trust question, a
 * `claude --worktree` that a folder nobody trusted turned away, or status unknown as before. A trust Skein cannot read
 * keeps everything as it was.
 */
class LaunchTimeoutTest {
    private var reads = 0

    private fun decide(alive: Boolean, worktree: Boolean, trust: FolderTrust): Outcome =
        LaunchTimeout.decide(claudeAlive = alive, worktree = worktree) { reads++; trust }

    @Test
    fun aLiveClaudeInAFolderNobodyTrustedAsksAboutTrust() {
        assertEquals(Outcome.ASKS_TRUST, decide(alive = true, worktree = false, trust = NOT_TRUSTED))
        // a newer claude --worktree might ask rather than refuse
        assertEquals(Outcome.ASKS_TRUST, decide(alive = true, worktree = true, trust = NOT_TRUSTED))
    }

    @Test
    fun aLiveClaudeInATrustedFolderOrOneSkeinCannotTellIsAsBefore() {
        assertEquals(Outcome.AS_BEFORE, decide(alive = true, worktree = false, trust = TRUSTED))
        assertEquals(Outcome.AS_BEFORE, decide(alive = true, worktree = false, trust = UNKNOWN))
        assertEquals(Outcome.AS_BEFORE, decide(alive = true, worktree = true, trust = UNKNOWN))
    }

    @Test
    fun aWorktreeClaudeGoneInAFolderNobodyTrustedWasTurnedAway() {
        assertEquals(Outcome.UNTRUSTED_WORKTREE, decide(alive = false, worktree = true, trust = NOT_TRUSTED))
        assertEquals(Outcome.AS_BEFORE, decide(alive = false, worktree = true, trust = TRUSTED))
        assertEquals(Outcome.AS_BEFORE, decide(alive = false, worktree = true, trust = UNKNOWN))
    }

    /** A plain claude that is gone (never started, or told no) says nothing about trust, so the file is not even read. */
    @Test
    fun aPlainClaudeGoneIsAsBeforeWithoutReadingTrust() {
        assertEquals(Outcome.AS_BEFORE, decide(alive = false, worktree = false, trust = NOT_TRUSTED))
        assertEquals(Outcome.AS_BEFORE, decide(alive = false, worktree = false, trust = UNKNOWN))
        assertEquals(0, reads)
    }

    @Test
    fun trustIsReadOnceWhenItMatters() {
        decide(alive = true, worktree = false, trust = NOT_TRUSTED)
        decide(alive = false, worktree = true, trust = NOT_TRUSTED)
        assertEquals(2, reads)
    }
}
