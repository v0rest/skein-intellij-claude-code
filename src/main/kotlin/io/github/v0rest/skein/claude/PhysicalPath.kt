package io.github.v0rest.skein.claude

import io.github.v0rest.skein.paths.PathRules
import io.github.v0rest.skein.paths.PathStyle
import java.io.IOException
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * A folder as Claude Code reports it. Its cwd comes from getcwd(), which resolves links (on macOS /tmp is
 * /private/tmp) and on Windows writes the long names with an upper-case drive, so a path the IDE hands over is
 * resolved the same way before it is compared with one from a status file or a transcript.
 */
object PhysicalPath {
    /**
     * [path] in canonical form, with links resolved when it is an absolute path that exists on this machine. Anything
     * else - a path in the other OS's shape, an odd name - comes back canonical but as written, a missing folder of this machine with `..` folded away, so
     * this never throws and never needs the disk to answer. Blocking when it does touch the disk.
     *
     * [foldMissing] false leaves a `..` in a folder that is not there alone, so a caller that must not act on a doubt (the
     * History) still sees it as one. A folder that exists has its `..` resolved by the disk either way.
     */
    fun of(path: String, style: PathStyle = PathStyle.current, foldMissing: Boolean = true): String {
        val canonical = PathRules.canonical(path, style)
        // only this machine's file system can be asked, a Windows path on the Mac is judged as text
        if (style != PathStyle.current || !PathRules.isAbsolute(canonical, style)) return canonical
        // links are resolved before `..` is folded, as chdir and getcwd() do - the paths the IDE and Claude Code write have no `..` anyway
        return try {
            val given = Path.of(canonical)
            val resolved = try {
                given.toRealPath()
            } catch (e: IOException) {
                // a folder that is not there (yet) is judged by its name, with `.` and `..` folded away as before
                if (foldMissing) given.normalize() else given
            }
            PathRules.canonical(resolved.toString(), style)
        } catch (e: InvalidPathException) {
            canonical
        }
    }
}
