package io.github.v0rest.skein.claude

import io.github.v0rest.skein.paths.PathRules
import io.github.v0rest.skein.paths.PathStyle

/** A Claude Code worktree: the repository it belongs to and its name (the folder under `<repository>/.claude/worktrees/`). */
data class WorktreeRef(val repository: String, val name: String)

/**
 * Where Claude Code puts the worktrees it creates for `claude --worktree <name>`, and what a worktree may be called.
 * Pure string work through [PathRules], so a path may come in any shape the system gives (git's `C:/w/acme` as well
 * as `C:\w\acme` on Windows) and comes out as [PathRules.canonical] text; nothing here touches the file system.
 */
object Worktrees {
    private const val WORKTREES_DIR = "worktrees"
    private const val NAME = "[A-Za-z0-9_][A-Za-z0-9._-]{0,63}"

    // Win32 drops the dots at the end of a name, so on Windows `task-1.` would open the folder `task-1`
    private const val WINDOWS_NAME = "[A-Za-z0-9_](?:[A-Za-z0-9._-]{0,62}[A-Za-z0-9_-])?"
    private val VALID = Regex("^$NAME$")
    private val VALID_ON_WINDOWS = Regex("^$WINDOWS_NAME$")

    /** The pattern of a worktree name by the rules of [style] ([isValidName]). */
    fun namePattern(style: PathStyle = PathStyle.current): String = if (style == PathStyle.WINDOWS) WINDOWS_NAME else NAME

    /** The same rule [io.github.v0rest.skein.terminal.ClaudeCommand] allows after `--worktree`, on the system Skein runs on. */
    val NAME_PATTERN: String = namePattern()

    /** The line that keeps every worktree folder out of git, as `info/exclude` reads it: the folder at the top of the repository. */
    const val EXCLUDE_LINE: String = "/.claude/worktrees/"

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
    fun projectSettingsFiles(repository: String, style: PathStyle = PathStyle.current): List<String> =
        listOf("settings.local.json", "settings.json").map { PathRules.join(repository, PROJECT_SETTINGS_DIR, it, style = style) }

    fun parentOf(repository: String, style: PathStyle = PathStyle.current): String =
        PathRules.join(repository, PROJECT_SETTINGS_DIR, WORKTREES_DIR, style = style)

    fun dirOf(repository: String, name: String, style: PathStyle = PathStyle.current): String =
        PathRules.join(repository, PROJECT_SETTINGS_DIR, WORKTREES_DIR, name, style = style)

    /** The worktree name a branch `worktree-<name>` stands for, or null for any other branch. */
    fun nameOfBranch(branch: String, style: PathStyle = PathStyle.current): String? =
        if (branch.startsWith(BRANCH_PREFIX)) branch.removePrefix(BRANCH_PREFIX).takeIf { isValidName(it, style) } else null

    /**
     * The worktree a working directory is in (the directory itself or anything below it), or null for any other folder
     * (and for a name Claude Code would not accept). In a worktree nested inside another one, it is the inner one. On
     * Windows `.claude` and `worktrees` match in any case, as the disk does, and a repository may be a drive or a share
     * itself (`W:\` on a subst drive, `\\server\share\`); on Unix a worktrees folder right at `/` is none, as before.
     */
    fun split(cwd: String, style: PathStyle = PathStyle.current): WorktreeRef? {
        val names = PathRules.segments(cwd, style)
        val first = if (isRepositoryRoot(PathRules.root(cwd, style), style)) 0 else 1
        val at = (names.size - 3 downTo first).firstOrNull {
            PathRules.sameName(names[it], PROJECT_SETTINGS_DIR, style) && PathRules.sameName(names[it + 1], WORKTREES_DIR, style)
        } ?: return null
        val name = names[at + 2]
        if (!isValidName(name, style)) return null
        return WorktreeRef(PathRules.join(PathRules.root(cwd, style), names.subList(0, at), style), name)
    }

    /** A drive root (`C:\`) or a share (`\\server\share\`) on Windows, which may be a repository of its own; never on Unix. */
    private fun isRepositoryRoot(root: String, style: PathStyle): Boolean =
        style == PathStyle.WINDOWS && PathRules.isAbsolute(root, style) && !root.startsWith("\\\\?\\") && !root.startsWith("\\\\.\\")

    /**
     * Whether [ref] is the worktree [name] of one of [repositories], each named in any shape the system gives: one
     * repository by [PathRules.same], one name by the case rule of [style].
     */
    fun isWorktreeOf(ref: WorktreeRef?, repositories: Collection<String>, name: String, style: PathStyle = PathStyle.current): Boolean =
        ref != null && PathRules.sameName(ref.name, name, style) && repositories.any { PathRules.same(ref.repository, it, style) }

    /**
     * A name Claude Code accepts, and on Windows one that does not end in a dot either: Win32 would open `task-1.` as
     * `task-1`, so Skein never makes such a name there and does not take a folder or a cwd of that name for a worktree.
     */
    fun isValidName(name: String, style: PathStyle = PathStyle.current): Boolean =
        (if (style == PathStyle.WINDOWS) VALID_ON_WINDOWS else VALID).matches(name)

    /** `task-1`, `task-2`, ... : the first one not in [existing], by the case rule of [style] (`Task-1` takes `task-1` on Windows). */
    fun nextName(existing: Set<String>, style: PathStyle = PathStyle.current): String =
        generateSequence(1) { it + 1 }.map { "task-$it" }.first { name -> existing.none { PathRules.sameName(it, name, style) } }
}
