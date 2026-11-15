package io.github.v0rest.skein.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import io.github.v0rest.skein.paths.PathRules
import io.github.v0rest.skein.paths.PathStyle

/** The project's base folder and its local content roots, as plain paths. Call with read access. */
data class ProjectFolders(val base: String?, val contentRoots: List<String>, private val style: PathStyle = PathStyle.current) {
    /** Every folder Skein works in: the base folder first, then the content roots. */
    val all: List<String> get() = ContentRoots.choices(base, contentRoots, null, style)

    /**
     * The folders a new thread can start in ([ContentRoots.startFolders]): the base folder, the roots outside it and the
     * roots inside it that [isRepositoryTop] says are repositories of their own, not the source sets.
     */
    fun startFolders(isRepositoryTop: (String) -> Boolean): List<String> = ContentRoots.startFolders(base, contentRoots, isRepositoryTop, style)

    /** A short label for [folder]: its path relative to the base folder, or the full path outside it. */
    fun label(folder: String): String {
        val b = base ?: return folder
        return when {
            PathRules.same(folder, b, style) -> PathRules.name(folder, style).ifEmpty { folder }
            else -> PathRules.relative(folder, b, style) ?: folder
        }
    }

    companion object {
        fun of(project: Project): ProjectFolders = ProjectFolders(
            project.basePath,
            ProjectRootManager.getInstance(project).contentRoots.filter { it.isInLocalFileSystem }.map { it.path },
        )
    }
}
