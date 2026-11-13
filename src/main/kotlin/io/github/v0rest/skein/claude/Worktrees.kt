package io.github.v0rest.skein.claude

/** A Claude Code worktree: the repository it belongs to and its name (the folder under `<repository>/.claude/worktrees/`). */
data class WorktreeRef(val repository: String, val name: String)

/**
 * Where Claude Code puts the worktrees it creates for `claude --worktree <name>`, and what a worktree may be called.
 * Pure string work on `/`-separated paths; nothing here touches the file system.
 */
object Worktrees {
    private const val PARENT = "/.claude/worktrees"
    private const val NAME = "[A-Za-z0-9_][A-Za-z0-9._-]{0,63}"
    private val VALID = Regex("^$NAME$")

    /** The same rule [io.github.v0rest.skein.terminal.ClaudeCommand] allows after `--worktree`. */
    const val NAME_PATTERN: String = NAME

    /** The line that keeps every worktree folder out of git, as `info/exclude` reads it: the folder at the top of the repository. */
    const val EXCLUDE_LINE: String = "$PARENT/"

    /** Claude Code puts the worktree `<name>` on the branch `worktree-<name>`. */
    const val BRANCH_PREFIX: String = "worktree-"

    /**
     * The file in the worktrees folder that keeps it out of git: [GUARD_TEXT] ignores everything there, itself
     * included, and the deepest `.gitignore` wins over every other rule.
     */
    const val GUARD_NAME: String = ".gitignore"

    const val GUARD_TEXT: String = "*\n"

    /** The folder of Claude Code's project settings, inside the repository Claude Code starts in. */
    const val PROJECT_SETTINGS_DIR: String = ".claude"

    /** Claude Code's project settings files of [repository], the winning one first - `settings.local.json`, then `settings.json`. */
    fun projectSettingsFiles(repository: String): List<String> =
        listOf("settings.local.json", "settings.json").map { repository.trimEnd('/') + "/" + PROJECT_SETTINGS_DIR + "/" + it }

    fun parentOf(repository: String): String = repository.trimEnd('/') + PARENT

    fun dirOf(repository: String, name: String): String = parentOf(repository) + "/" + name

    /** The worktree name a branch `worktree-<name>` stands for, or null for any other branch. */
    fun nameOfBranch(branch: String): String? =
        if (branch.startsWith(BRANCH_PREFIX)) branch.removePrefix(BRANCH_PREFIX).takeIf(::isValidName) else null

    /**
     * The worktree a working directory is in (the directory itself or anything below it), or null for any other folder
     * (and for a name Claude Code would not accept). In a worktree nested inside another one, it is the inner one.
     */
    fun split(cwd: String): WorktreeRef? {
        val path = cwd.trimEnd('/')
        val at = path.lastIndexOf("$PARENT/")
        if (at < 0) return null
        val name = path.substring(at + PARENT.length + 1).substringBefore('/')
        val repository = path.substring(0, at)
        if (repository.isEmpty() || !isValidName(name)) return null
        return WorktreeRef(repository, name)
    }

    fun isValidName(name: String): Boolean = VALID.matches(name)

    /** `task-1`, `task-2`, ... : the first one not in [existing]. */
    fun nextName(existing: Set<String>): String = generateSequence(1) { it + 1 }.map { "task-$it" }.first { it !in existing }
}
