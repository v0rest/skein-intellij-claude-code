package io.github.v0rest.skein.git

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** What a folder's `.git` entry is: a folder (a repository of its own), or a file or link (a worktree or submodule pointing elsewhere). */
enum class GitEntry { FOLDER, FILE }

/** Finds the repository a folder belongs to without running git: the nearest folder up the tree that has a `.git` entry. */
object GitToplevel {
    /** [folder] or its nearest parent with a `.git` folder or file (a linked worktree and a submodule have a file); null if none. */
    fun of(folder: Path, hasGitEntry: (Path) -> Boolean = { Files.exists(it.resolve(".git")) }): Path? =
        generateSequence(folder) { it.parent }.firstOrNull(hasGitEntry)

    /** The `.git` entry right in [folder], not followed if it is a link; null when there is none. Blocking. */
    fun entryOf(folder: Path): GitEntry? {
        val git = folder.resolve(".git")
        return when {
            Files.isDirectory(git, LinkOption.NOFOLLOW_LINKS) -> GitEntry.FOLDER
            Files.exists(git, LinkOption.NOFOLLOW_LINKS) -> GitEntry.FILE
            else -> null
        }
    }

    /** Whether [folder] is still there and still the top of a repository (it has a `.git` folder or file). Blocking. */
    fun isRepository(folder: Path): Boolean = Files.isDirectory(folder) && Files.exists(folder.resolve(".git"))

    /** [folder] as git and Claude Code report it, with its links resolved; null when it cannot be (gone, unreadable). Blocking. */
    fun resolved(folder: String): String? =
        try {
            Path.of(folder).toRealPath().toString()
        } catch (_: Exception) {
            null
        }
}
