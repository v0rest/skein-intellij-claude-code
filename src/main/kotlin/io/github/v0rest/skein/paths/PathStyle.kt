package io.github.v0rest.skein.paths

import com.intellij.openapi.util.SystemInfo

/**
 * Which system's rules a path text follows. Product code passes [current] (the default everywhere in [PathRules]),
 * tests pass the other one too, so a run on the Mac checks the Windows rules and the other way round.
 */
enum class PathStyle(val separator: Char) {
    /** `\` and `/` both separate, names compare without regard to case, a root is a drive (`C:\`) or a UNC share. */
    WINDOWS('\\'),

    /** Only `/` separates and `\` is an ordinary character in a name, names compare exactly, the root is `/`. */
    UNIX('/'),
    ;

    companion object {
        /** The rules of the system Skein runs on - macOS and Linux both follow [UNIX] here, as the code did before. */
        val current: PathStyle = if (SystemInfo.isWindows) WINDOWS else UNIX
    }
}
