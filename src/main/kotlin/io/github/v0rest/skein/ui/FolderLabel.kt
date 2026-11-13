package io.github.v0rest.skein.ui

/** The grey note a plain thread's row carries when the thread runs in a folder inside the project, as New Thread Here starts it. */
object FolderLabel {
    /**
     * The thread's [folder] as a path from the project's [base] folder, as the History names a session's folder, for a
     * folder inside the base other than the base itself. Null for the base, for a folder outside it (a content root of
     * its own is named after its folder already) and while the row's [shownName] already is that path.
     */
    fun of(shownName: String, folder: String, base: String?): String? {
        val b = base?.let(::normalize) ?: return null
        val f = normalize(folder)
        val inside = if (b == "/") "/" else "$b/"
        if (f == b || !f.startsWith(inside)) return null
        return f.removePrefix(inside).takeIf { it != shownName }
    }

    private fun normalize(path: String): String = if (path.length > 1) path.trimEnd('/') else path
}
