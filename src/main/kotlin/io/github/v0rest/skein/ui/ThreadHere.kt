package io.github.v0rest.skein.ui

import io.github.v0rest.skein.claude.Worktrees

/**
 * Where New Thread Here starts a thread for what is selected in the Project view or an editor tab. Pure string work on
 * `/`-separated paths, as the IDE's virtual files give them, and nothing here touches the disk.
 */
object ThreadHere {
    /** One selected item, with its path, whether it is a folder and whether it lies on the local disk (a jar entry does not). */
    data class Item(val path: String, val isFolder: Boolean, val isLocal: Boolean = true)

    /**
     * The folder for [selected], a folder itself or a file's folder. Every item has to be on the local disk and lead to
     * the same folder, so several files of one folder start one thread there, and that folder has to be one of the
     * project's [projectFolders] ([ProjectFolders.all], the folders the History lists) or lie inside one. A folder in a
     * Claude Code worktree has none, since a worktree has its own way in and a plain thread there would not be a
     * worktree thread, unless the project's [base] folder lies in that same worktree, a worktree opened as a project of
     * its own, where it counts like any other folder. Null means the action does not show.
     */
    fun folderFor(selected: List<Item>, projectFolders: List<String>, base: String?): String? {
        if (selected.isEmpty() || selected.any { !it.isLocal }) return null
        val folder = selected.map { if (it.isFolder) normalize(it.path) else parentOf(normalize(it.path)) }.distinct().singleOrNull() ?: return null
        if (projectFolders.map(::normalize).none { isAtOrUnder(folder, it) }) return null
        val worktree = Worktrees.split(folder)?.let { Worktrees.dirOf(it.repository, it.name) }
        if (worktree != null && (base == null || !isAtOrUnder(normalize(base), worktree))) return null
        return folder
    }

    private fun isAtOrUnder(path: String, folder: String): Boolean =
        path == folder || path.startsWith(if (folder.endsWith('/')) folder else "$folder/")

    private fun parentOf(path: String): String {
        val at = path.lastIndexOf('/')
        return if (at <= 0) "/" else path.substring(0, at)
    }

    private fun normalize(path: String): String = if (path.length > 1) path.trimEnd('/') else path
}
