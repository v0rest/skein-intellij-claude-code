package io.github.v0rest.skein.ui

import io.github.v0rest.skein.paths.PathRules
import io.github.v0rest.skein.paths.PathStyle

/**
 * The folders the project view hands over are the IDE's system-independent paths - `/w/acme`, and on Windows
 * `C:/w/acme` - so they stay in that spelling and only their comparison goes through [PathRules].
 */
internal object FolderPaths {
    /** [path] without a trailing separator, a root as it is. Keeps the IDE's spelling, so what comes out still is what went in. */
    fun trimmed(path: String, style: PathStyle): String {
        if (PathRules.parent(path, style) == null) return path
        return when (style) {
            PathStyle.UNIX -> path.trimEnd('/')
            PathStyle.WINDOWS -> path.trimEnd('/', '\\')
        }
    }

    /** [path] in the IDE's spelling: `/` between the names on every OS. */
    fun ide(path: String, style: PathStyle): String = when (style) {
        PathStyle.UNIX -> path
        PathStyle.WINDOWS -> path.replace('\\', '/')
    }
}
