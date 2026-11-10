package io.github.v0rest.skein.claude

/**
 * The oldest Claude Code Skein can read thread status from: 2.1.162, the first release whose changelog lists
 * `waitingFor` for live sessions ("claude agents --json now includes waitingFor"); 2.1.145 had added the live-session
 * listing itself. Checked against Claude Code 2.1.318 on 2026-11-06.
 */
object ClaudeCodeRequirement {
    const val MIN_VERSION = "2.1.162"
}
