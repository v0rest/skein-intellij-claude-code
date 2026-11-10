package io.github.v0rest.skein.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager

/** The project's base folder and its local content roots, as plain paths. Call with read access. */
data class ProjectFolders(val base: String?, val contentRoots: List<String>) {
    /** Every folder Skein works in: the base folder first, then the content roots. */
    val all: List<String> get() = ContentRoots.choices(base, contentRoots, null)

    /**
     * The folders a new thread can start in ([ContentRoots.startFolders]): the base folder, the roots outside it and the
     * roots inside it that [isRepositoryTop] says are repositories of their own, not the source sets.
     */
    fun startFolders(isRepositoryTop: (String) -> Boolean): List<String> = ContentRoots.startFolders(base, contentRoots, isRepositoryTop)

    /** A short label for [folder]: its path relative to the base folder, or the full path outside it. */
    fun label(folder: String): String {
        val b = base ?: return folder
        return when {
            folder == b -> folder.substringAfterLast('/').ifEmpty { folder }
            folder.startsWith("$b/") -> folder.removePrefix("$b/")
            else -> folder
        }
    }

    companion object {
        fun of(project: Project): ProjectFolders = ProjectFolders(
            project.basePath,
            ProjectRootManager.getInstance(project).contentRoots.filter { it.isInLocalFileSystem }.map { it.path },
        )
    }
}
