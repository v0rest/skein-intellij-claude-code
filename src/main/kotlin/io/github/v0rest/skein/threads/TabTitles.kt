package io.github.v0rest.skein.threads

/**
 * What each thread's editor tab reads: its display name ([DisplayNames.thread]), the Skein name, else Claude Code's,
 * else the worktree or folder. Only the titles that changed are handed to the terminal.
 */
object TabTitles {
    /** The display names of [threads] that differ from what was [pushed] to their tabs, by thread id. */
    fun changed(pushed: Map<String, String>, threads: List<SkeinThread>, names: Map<String, String>): Map<String, String> =
        remembered(threads, names).filter { (id, title) -> pushed[id] != title }

    /** What the tabs read once [changed] is pushed: the display name of every thread in [threads], closed ones gone. */
    fun remembered(threads: List<SkeinThread>, names: Map<String, String>): Map<String, String> =
        threads.associate { it.id to DisplayNames.thread(it, names) }
}
