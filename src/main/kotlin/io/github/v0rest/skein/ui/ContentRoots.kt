package io.github.v0rest.skein.ui

/** The project's folders as plain paths: every one Skein works in ([choices]) and the ones a new thread starts in ([startFolders]). */
object ContentRoots {
    /**
     * Every folder of the project: the base folder first, then the other content roots, sorted, without duplicates. It
     * feeds [ProjectFolders.all], the folders whose sessions the History lists and whose worktrees folders
     * [WorktreeExcludePolicy] keeps out of the index; the "+" popup offers [startFolders] instead.
     */
    fun choices(baseDir: String?, roots: List<String>, lastUsed: String?): List<String> {
        val base = baseDir?.let(::normalize)
        val others = roots.map(::normalize).filter { it != base }.distinct().sorted()
        return listOfNotNull(base) + others
    }

    /**
     * The folders a new thread can start in: the project base folder first, then the other roots, sorted. A root
     * inside the base stays only when it is the top of a repository of its own ([isRepositoryTop], asked about those
     * roots alone), as in a folder that holds several repositories as modules; Gradle makes content roots of source
     * sets, which never are, and are no places to start. A root above the base stays.
     */
    fun startFolders(baseDir: String?, roots: List<String>, isRepositoryTop: (String) -> Boolean): List<String> {
        val base = baseDir?.let(::normalize)
        val inside = base?.let { if (it == "/") it else "$it/" }
        val others = roots.map(::normalize).filter { it != base }.distinct()
            .filter { inside == null || !it.startsWith(inside) || isRepositoryTop(it) }
            .sorted()
        return listOfNotNull(base) + others
    }

    /** The folder to preselect: the last used one while it is still offered, else the first. */
    fun preselected(choices: List<String>, lastUsed: String?): String? =
        lastUsed?.let(::normalize)?.takeIf { it in choices } ?: choices.firstOrNull()

    private fun normalize(path: String): String = if (path.length > 1) path.trimEnd('/') else path
}
