package io.github.v0rest.skein.claude

import io.github.v0rest.skein.paths.PathRules
import io.github.v0rest.skein.paths.PathStyle

/**
 * The oldest Claude Code Skein can read thread status from: 2.1.162, the first release whose changelog lists
 * `waitingFor` for live sessions ("claude agents --json now includes waitingFor"); 2.1.145 had added the live-session
 * listing itself. Checked against Claude Code 2.1.318 on 2026-11-06.
 */
object ClaudeCodeRequirement {
    const val MIN_VERSION = "2.1.162"
}

/**
 * Where a `claude` typed into a terminal would come from. The native installer on Windows puts claude.exe in
 * `%USERPROFILE%\.local\bin` and leaves PATH alone, so a fresh install fails in the terminal with "not recognized" and
 * the one thing to tell the user is to add that folder. Skein itself never changes PATH.
 */
object ClaudeLocator {
    sealed interface Result {
        /** Some PATH folder holds a claude, so the terminal finds it. */
        data object OnPath : Result

        /** No PATH folder holds one, but the installer's own folder [directory] does: it only needs to join PATH. */
        data class OnlyInInstallerFolder(val directory: String) : Result

        /** Neither: not installed, or somewhere Skein does not look. */
        data object NotFound : Result
    }

    /**
     * A pure function of its inputs: [pathEntries] are the PATH folders in order, [home] the user's home folder and
     * [exists] tells whether a file is there. The installer's folder is only a Windows matter, elsewhere it is on PATH
     * already.
     */
    fun locate(style: PathStyle, pathEntries: List<String>, home: String, exists: (String) -> Boolean): Result {
        // PATH comes from the environment and may hold a name the file system refuses (a quote, a `|`), which must not lose the answer
        val exists = { path: String -> runCatching { exists(path) }.getOrDefault(false) }
        val names = executableNames(style)
        // a relative or empty PATH entry means "the current folder", which a terminal tab does not share with Skein
        val folders = pathEntries.map { it.trim().removeSurrounding("\"") }.filter { PathRules.isAbsolute(it, style) }
        if (folders.any { folder -> names.any { exists(PathRules.join(folder, listOf(it), style)) } }) return Result.OnPath
        if (style != PathStyle.WINDOWS || !PathRules.isAbsolute(home, style)) return Result.NotFound
        val installer = PathRules.join(home, listOf(".local", "bin"), style)
        return if (exists(PathRules.join(installer, listOf("claude.exe"), style))) Result.OnlyInInstallerFolder(installer) else Result.NotFound
    }

    /** What a terminal runs for `claude`: the native binary, and on Windows the shim an npm install writes. */
    private fun executableNames(style: PathStyle): List<String> = when (style) {
        PathStyle.UNIX -> listOf("claude")
        PathStyle.WINDOWS -> listOf("claude.exe", "claude.cmd")
    }

    /**
     * As [locate], from an environment map: the PATH variable is found whatever its case (Windows says `Path`), and
     * [home] is the user's home folder.
     */
    fun locateIn(style: PathStyle, env: Map<String, String>, home: String, exists: (String) -> Boolean): Result {
        val path = env.entries.firstOrNull { it.key.equals("PATH", ignoreCase = true) }?.value
        return locate(style, splitPath(path, style), home, exists)
    }

    /** [PATH] split on the style's list separator, empty entries dropped. */
    fun splitPath(path: String?, style: PathStyle): List<String> =
        path?.split(if (style == PathStyle.WINDOWS) ';' else ':').orEmpty().filter { it.isNotBlank() }
}
