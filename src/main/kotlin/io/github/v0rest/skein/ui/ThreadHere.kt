package io.github.v0rest.skein.ui

import io.github.v0rest.skein.claude.Worktrees
import io.github.v0rest.skein.paths.PathRules
import io.github.v0rest.skein.paths.PathStyle

/**
 * Where New Thread Here starts a thread for what is selected in the Project view or an editor tab. Pure string work on
 * the paths the IDE's virtual files give - `/`-separated, also on Windows - and nothing here touches the disk.
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
    fun folderFor(selected: List<Item>, projectFolders: List<String>, base: String?, style: PathStyle = PathStyle.current): String? {
        if (selected.isEmpty() || selected.any { !it.isLocal }) return null
        val folder = selected.map { if (it.isFolder) FolderPaths.trimmed(it.path, style) else parentOf(it.path, style) }
            .distinctBy { PathRules.key(it, style) }.singleOrNull() ?: return null
        // both checks let the action go ahead, so a doubtful path counts as no
        if (projectFolders.none { PathRules.inside(folder, it, style) }) return null
        val worktree = Worktrees.split(folder)?.let { Worktrees.dirOf(it.repository, it.name) }
        if (worktree != null && (base == null || !PathRules.inside(base, worktree, style))) return null
        return folder
    }

    private fun parentOf(path: String, style: PathStyle): String =
        PathRules.parent(path, style)?.let { FolderPaths.ide(it, style) } ?: path
}
