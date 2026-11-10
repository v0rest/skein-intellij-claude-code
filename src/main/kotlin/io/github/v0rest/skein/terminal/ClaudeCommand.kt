package io.github.v0rest.skein.terminal

import io.github.v0rest.skein.claude.Worktrees

/**
 * The only three commands Skein ever types into a shell: `claude`, `claude --resume <session-id>` and
 * `claude --worktree <name>`. A session id comes from files on disk (status files, transcript names, the saved thread
 * list), and it is typed into an interactive shell, so it must be a plain UUID; a worktree name must be a plain
 * [Worktrees] name; anything else is refused rather than quoted.
 */
object ClaudeCommand {
    const val NEW = "claude"

    private const val UUID = "[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}"
    private val SESSION_ID = Regex("^$UUID$")
    private val ALLOWED = Regex("^claude( --resume $UUID| --worktree ${Worktrees.NAME_PATTERN})?$")

    fun isSessionId(id: String?): Boolean = id != null && SESSION_ID.matches(id)

    fun resume(sessionId: String): String {
        require(isSessionId(sessionId)) { "not a Claude Code session id" }
        return "claude --resume $sessionId"
    }

    fun worktree(name: String): String {
        require(Worktrees.isValidName(name)) { "not a usable worktree name" }
        return "claude --worktree $name"
    }

    /** `claude --resume <id>` for a valid id, plain `claude` otherwise. */
    fun forSession(sessionId: String?): String = if (sessionId != null && isSessionId(sessionId)) resume(sessionId) else NEW

    fun isAllowed(command: String): Boolean = ALLOWED.matches(command)
}
