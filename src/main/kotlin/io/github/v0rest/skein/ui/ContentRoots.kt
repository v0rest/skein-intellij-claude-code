package io.github.v0rest.skein.ui

import io.github.v0rest.skein.paths.PathRules
import io.github.v0rest.skein.paths.PathStyle

/**
 * The project's folders as plain paths: every one Skein works in ([choices]) and the ones a new thread starts in ([startFolders]).
 * The paths are the IDE's, `/`-separated even on Windows, and are compared by [PathRules] with the OS's own rules.
 */
object ContentRoots {
    /**
     * Every folder of the project: the base folder first, then the other content roots, sorted, without duplicates. It
     * feeds [ProjectFolders.all], the folders whose sessions the History lists and whose worktrees folders
     * [WorktreeExcludePolicy] keeps out of the index; the "+" popup offers [startFolders] instead.
     */
    fun choices(baseDir: String?, roots: List<String>, lastUsed: String?, style: PathStyle = PathStyle.current): List<String> {
        val base = baseDir?.let { FolderPaths.trimmed(it, style) }
        return listOfNotNull(base) + others(base, roots, style)
    }

    /**
     * The folders a new thread can start in: the project base folder first, then the other roots, sorted. A root
     * inside the base stays only when it is the top of a repository of its own ([isRepositoryTop], asked about those
     * roots alone), as in a folder that holds several repositories as modules; Gradle makes content roots of source
     * sets, which never are, and are no places to start. A root above the base stays.
     */
    fun startFolders(baseDir: String?, roots: List<String>, isRepositoryTop: (String) -> Boolean): List<String> =
        startFolders(baseDir, roots, isRepositoryTop, PathStyle.current)

    /** As above with the path rules of [style], for a test that runs Windows paths on another OS. */
    fun startFolders(baseDir: String?, roots: List<String>, isRepositoryTop: (String) -> Boolean, style: PathStyle): List<String> {
        val base = baseDir?.let { FolderPaths.trimmed(it, style) }
        // a root counts as inside the base only when that is sure - a doubtful one stays on offer, nothing is hidden on a guess
        val others = others(base, roots, style)
            .filter { base == null || !PathRules.inside(it, base, style) || isRepositoryTop(it) }
        return listOfNotNull(base) + others
    }

    /** The folder to preselect: the last used one while it is still offered, else the first. */
    fun preselected(choices: List<String>, lastUsed: String?, style: PathStyle = PathStyle.current): String? =
        lastUsed?.let { used -> choices.firstOrNull { PathRules.same(it, used, style) } } ?: choices.firstOrNull()

    private fun others(base: String?, roots: List<String>, style: PathStyle): List<String> =
        roots.map { FolderPaths.trimmed(it, style) }
            .filter { base == null || !PathRules.same(it, base, style) }
            .distinctBy { PathRules.key(it, style) }
            .sorted()
}
