package io.github.v0rest.skein.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeCommandTest {
    private val id = "0123abcd-ef01-4345-8789-abcdef012345"

    @Test
    fun newSessionIsPlainClaude() = assertEquals("claude", ClaudeCommand.NEW)

    @Test
    fun resumeBuildsTheCommandForAUuid() = assertEquals("claude --resume $id", ClaudeCommand.resume(id))

    @Test
    fun sessionIdMustBeAUuid() {
        assertTrue(ClaudeCommand.isSessionId(id))
        assertTrue(ClaudeCommand.isSessionId(id.uppercase()))
        for (bad in listOf(null, "", "abc", "x; touch pwned", "\$(id)", "$id; ls", "$id\nls", "../$id", "$id ", "0123abcdef0143458789abcdef012345")) {
            assertFalse("accepted: $bad", ClaudeCommand.isSessionId(bad))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun resumeRefusesAnythingElse() {
        ClaudeCommand.resume("x; touch pwned")
    }

    @Test
    fun onlyTheThreeCommandsAreAllowed() {
        assertTrue(ClaudeCommand.isAllowed("claude"))
        assertTrue(ClaudeCommand.isAllowed("claude --resume $id"))
        for (bad in listOf("claude ", " claude", "claude --dangerously-skip-permissions", "claude; ls", "claude --resume abc", "claude --resume $id --settings x", "ls", "claude\nls")) {
            assertFalse("allowed: $bad", ClaudeCommand.isAllowed(bad))
        }
    }

    @Test
    fun worktreeBuildsTheCommand() = assertEquals("claude --worktree feat-x", ClaudeCommand.worktree("feat-x"))

    @Test(expected = IllegalArgumentException::class)
    fun worktreeRefusesABadName() {
        ClaudeCommand.worktree("a;rm")
    }

    @Test
    fun worktreeCommandIsAllowedAndBadNamesAreNot() {
        assertTrue(ClaudeCommand.isAllowed("claude --worktree feat-x"))
        assertFalse(ClaudeCommand.isAllowed("claude --worktree -x"))
        assertFalse(ClaudeCommand.isAllowed("claude --worktree a;rm"))
        assertFalse(ClaudeCommand.isAllowed("claude --worktree"))
    }
}
