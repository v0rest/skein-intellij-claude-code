package io.github.v0rest.skein.ui

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.impl.DirectoryIndexExcludePolicy
import com.intellij.openapi.vfs.VfsUtilCore
import io.github.v0rest.skein.claude.Worktrees

/**
 * Keeps Claude Code's worktree folders out of the index under the project base folder and every content root, so a
 * worktree is not indexed as a second copy of the project. The platform's own default covers the folder the project
 * was opened on and the content roots of non-Java modules, but not a Java/Kotlin module's content root elsewhere (a
 * window with several repositories, for one). Paths only: no git, no file system.
 */
class WorktreeExcludePolicy(private val project: Project) : DirectoryIndexExcludePolicy {
    override fun getExcludeUrlsForProject(): Array<String> {
        if (project.isDisposed) return emptyArray()
        val folders = ReadAction.computeBlocking<ProjectFolders, RuntimeException> { ProjectFolders.of(project) }
        return folders.all.map { VfsUtilCore.pathToUrl(Worktrees.parentOf(it)) }.distinct().toTypedArray()
    }
}
