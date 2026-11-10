package io.github.v0rest.skein.git

import com.intellij.openapi.util.io.NioFiles
import io.github.v0rest.skein.claude.Worktrees
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Deletes a folder under a repository's worktrees folder that git does not list as a worktree, once the owner agreed:
 * the one recursive delete in Skein. It takes only a folder directly under that worktrees folder, named as Claude Code
 * names a worktree, with no link on the way there (the folder itself, the worktrees folder or the one above it), and
 * never one with a `.git` entry in it, at the top or deeper ([gitInside]): a `.git` file makes it a worktree git links
 * to another path (a repository moved or copied), a `.git` folder a repository of its own, and either holds work a
 * delete would lose. The platform's
 * recursive delete removes a link inside the folder as a link, never what it points to. Whether git lists the folder,
 * and whether anything uses it, the caller checks right before. Blocking: off the EDT.
 */
object LeftoverWorktreeFolder {
    /** How many entries [gitInside] looks at before it gives up, and the folder stays. */
    const val MAX_ENTRIES = 200_000

    /**
     * Why [folder] is no plain leftover because of what lies inside it: a `.git` entry at any depth (a clone, a worktree
     * link, a submodule), or a look that could not finish (an unreadable folder, more than [limit] entries), which counts
     * as one. Null when there is none. Links are not followed: one inside is deleted as a link, so what it points to is
     * not this folder's. Blocking.
     */
    fun gitInside(folder: Path, limit: Int = MAX_ENTRIES): String? {
        val pending = ArrayDeque<Path>()
        pending.add(folder)
        var seen = 0
        while (pending.isNotEmpty()) {
            val dir = pending.removeLast()
            try {
                Files.newDirectoryStream(dir).use { entries ->
                    for (entry in entries) {
                        if (++seen > limit) return "$folder holds more than $limit files, too many to make sure no repository lies inside"
                        if (entry.fileName.toString() == ".git") return "${folder.relativize(entry)} lies inside $folder, so it holds a repository or a worktree"
                        if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) pending.add(entry)
                    }
                }
            } catch (e: IOException) {
                return "could not look through $dir: " + (if (e is FileSystemException) e.reason ?: e.javaClass.simpleName else e.message ?: e.javaClass.simpleName)
            } catch (e: DirectoryIteratorException) {
                return "could not look through $dir: " + (e.cause?.message ?: e.javaClass.simpleName)
            }
        }
        return null
    }

    /** Null once the folder [name] of [repository] is gone; otherwise why it was not deleted, or not all of it. */
    fun delete(repository: String, name: String): String? {
        if (!Worktrees.isValidName(name)) return "$name is not a worktree name"
        val folder = Path.of(Worktrees.dirOf(repository, name))
        val worktrees = folder.parent
        for (step in listOf(worktrees.parent, worktrees, folder)) {
            if (Files.isSymbolicLink(step)) return "$step is a link, and Skein deletes nothing through a link"
        }
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) return "$folder is not a folder"
        if (Files.exists(folder.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) return "$folder has a .git of its own, so it is no leftover"
        gitInside(folder)?.let { return it }
        return try {
            NioFiles.deleteRecursively(folder)
            null
        } catch (e: IOException) {
            "could not delete all of $folder: " + (if (e is FileSystemException) e.reason ?: e.javaClass.simpleName else e.message ?: e.javaClass.simpleName)
        }
    }
}
