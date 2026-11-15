package io.github.v0rest.skein.claude

import com.intellij.util.EnvironmentUtil
import java.io.BufferedReader
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/** Size and modification time of a file, enough to notice that it changed. */
data class FileStamp(val size: Long, val modifiedMillis: Long)

/**
 * The only code in Skein that touches `~/.claude`. Every method opens files read-only, returns null (or an empty
 * list) for anything missing or unreadable, and rejects paths outside [root]. Nothing here creates, changes or
 * removes a file. [configFile] is Claude Code's global config, `.claude.json`, which lives outside [root] unless
 * CLAUDE_CONFIG_DIR is set ([resolveConfigFile]), or null when this home does not know it.
 */
open class ClaudeHome(val root: Path, val configFile: Path? = null) {
    private val normalizedRoot: Path = root.toAbsolutePath().normalize()

    val sessionsDir: Path get() = root.resolve("sessions")
    val projectsDir: Path get() = root.resolve("projects")

    /** The whole file as UTF-8, or null when it is missing or larger than [maxBytes]. */
    open fun readText(path: Path, maxBytes: Int = 1 shl 20): String? =
        read(path, maxBytes, fromEnd = false, wholeOnly = true)?.let { String(it, Charsets.UTF_8) }

    /** The first [maxBytes] bytes (fewer for a shorter file), or null when the file is missing. */
    open fun readHead(path: Path, maxBytes: Int): ByteArray? = read(path, maxBytes, fromEnd = false, wholeOnly = false)

    /** The last [maxBytes] bytes (fewer for a shorter file), or null when the file is missing. */
    open fun readTail(path: Path, maxBytes: Int): ByteArray? = read(path, maxBytes, fromEnd = true, wholeOnly = false)

    /** Direct entries of [dir] matching [glob], sorted; empty when the directory is missing or cannot be listed. */
    open fun list(dir: Path, glob: String = "*"): List<Path> = listOrNull(dir, glob).orEmpty()

    /**
     * As [list], but null when [dir] is not a directory or its listing did not complete, so a caller can tell "no
     * entries" from "could not look".
     */
    open fun listOrNull(dir: Path, glob: String = "*"): List<Path>? {
        val checked = inside(dir)
        if (!Files.isDirectory(checked)) return null
        return try {
            Files.newDirectoryStream(checked, glob).use { entries -> entries.toList().sorted() }
        } catch (e: IOException) {
            null
        } catch (e: DirectoryIteratorException) {
            null
        }
    }

    /** Size and modification time of a regular file, or null when it is missing. */
    open fun stamp(path: Path): FileStamp? {
        val checked = inside(path)
        return try {
            val attributes = Files.readAttributes(checked, BasicFileAttributes::class.java)
            if (attributes.isRegularFile) FileStamp(attributes.size(), attributes.lastModifiedTime().toMillis()) else null
        } catch (e: IOException) {
            null
        }
    }

    /**
     * [configFile] handed to [parse] as UTF-8 text, or null when there is none, it is not a regular file, it is larger
     * than [maxBytes] (by its size, or by the bytes read, [readAtMost]) or it cannot be opened. Only that one file is opened, read-only. It holds the user's account as
     * well, so a caller keeps only what it needs from it and never logs it. Blocking.
     */
    open fun <T : Any> readConfig(maxBytes: Long, parse: (Reader) -> T?): T? {
        val file = configFile ?: return null
        return try {
            val attributes = Files.readAttributes(file, BasicFileAttributes::class.java)
            if (!attributes.isRegularFile || attributes.size() > maxBytes) return null
            // the size may grow between the look and the read, so the read counts its bytes as well
            Files.newInputStream(file).use { readAtMost(it, maxBytes, parse) }
        } catch (e: IOException) {
            null
        }
    }

    /**
     * Whether [path] is this home or lies inside it, compared as given and with links resolved as far as the path exists
     * (a folder Claude Code would create later is judged by the folders above it). Opens nothing; blocking.
     */
    fun holds(path: Path): Boolean {
        val homes = forms(root)
        return forms(path).any { p -> homes.any { p.startsWith(it) } }
    }

    /**
     * Whether the worktrees Claude Code makes for [repository] would lie inside this home: a repository around the home,
     * such as a dotfiles repository in the user's home folder. Skein starts, excludes and removes no worktree there.
     */
    fun holdsWorktreesOf(repository: Path): Boolean = holds(Path.of(Worktrees.parentOf(repository.toString())))

    /** [path] made absolute and normal, and the same with its links resolved up to the deepest folder that exists. */
    private fun forms(path: Path): Set<Path> {
        val given = path.toAbsolutePath().normalize()
        var existing: Path? = given
        while (existing != null) {
            try {
                return setOf(given, existing.toRealPath().resolve(existing.relativize(given)))
            } catch (e: IOException) {
                existing = existing.parent
            }
        }
        return setOf(given)
    }

    private fun read(path: Path, maxBytes: Int, fromEnd: Boolean, wholeOnly: Boolean): ByteArray? {
        require(maxBytes >= 0) { "maxBytes must not be negative" }
        val channel = openForReading(inside(path)) ?: return null
        return try {
            channel.use { ch ->
                val size = ch.size()
                if (wholeOnly && size > maxBytes) return null
                val count = minOf(size, maxBytes.toLong()).toInt()
                if (fromEnd) ch.position(size - count)
                val buffer = ByteBuffer.allocate(count)
                while (buffer.hasRemaining() && ch.read(buffer) >= 0) {
                    // keep filling until the requested bytes are in or the file ends
                }
                buffer.flip()
                ByteArray(buffer.remaining()).also { buffer.get(it) }
            }
        } catch (e: IOException) {
            null
        }
    }

    private fun openForReading(checked: Path): SeekableByteChannel? =
        try {
            if (Files.isRegularFile(checked)) Files.newByteChannel(checked, StandardOpenOption.READ) else null
        } catch (e: IOException) {
            null
        }

    private fun inside(path: Path): Path {
        val normalized = path.toAbsolutePath().normalize()
        require(normalized.startsWith(normalizedRoot)) { "Refusing to touch a path outside $root: $path" }
        return normalized
    }

    companion object {
        /** The folder Claude Code uses: `CLAUDE_CONFIG_DIR` when it names an absolute folder, else `~/.claude`. */
        fun resolveRoot(env: Map<String, String>, userHome: String): Path = resolveRoot(env, emptyMap(), userHome)

        /**
         * As above, preferring the shell environment (what the terminal and so `claude` see, including variables
         * exported in rc files) over the IDE process's own, which lacks them when the IDE starts from the Dock.
         */
        fun resolveRoot(shellEnv: Map<String, String>, processEnv: Map<String, String>, userHome: String): Path =
            configDir(shellEnv, processEnv) ?: Path.of(userHome, ".claude")

        /**
         * Where Claude Code keeps its global config, `.claude.json` - in CLAUDE_CONFIG_DIR by the same rule as the home
         * ([resolveRoot]), else in the user's home folder, next to `~/.claude` rather than in it.
         */
        fun resolveConfigFile(shellEnv: Map<String, String>, processEnv: Map<String, String>, userHome: String): Path =
            (configDir(shellEnv, processEnv) ?: Path.of(userHome)).resolve(".claude.json")

        /**
         * [input] handed to [parse] as strict UTF-8 (malformed bytes are an error, not a replacement character), with the
         * bytes counted as they are read: past [maxBytes] the read fails and the answer is null, whatever size was
         * looked at before. Closes nothing - the caller owns [input].
         */
        internal fun <T : Any> readAtMost(input: InputStream, maxBytes: Long, parse: (Reader) -> T?): T? =
            try {
                parse(BufferedReader(InputStreamReader(AtMost(input, maxBytes), Charsets.UTF_8.newDecoder())))
            } catch (e: IOException) {
                null
            }

        /** [input] that fails once more than [maxBytes] have come through it. */
        private class AtMost(input: InputStream, private val maxBytes: Long) : FilterInputStream(input) {
            private var count = 0L

            override fun read(): Int = super.read().also { if (it >= 0) counted(1) }

            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) counted(it.toLong()) }

            override fun skip(n: Long): Long = super.skip(n).also { counted(it) }

            private fun counted(n: Long) {
                count += n
                if (count > maxBytes) throw IOException("more than $maxBytes bytes")
            }
        }

        private fun configDir(shellEnv: Map<String, String>, processEnv: Map<String, String>): Path? =
            sequenceOf(shellEnv, processEnv)
                .mapNotNull { it["CLAUDE_CONFIG_DIR"]?.trim()?.takeIf(String::isNotEmpty) }
                .map { Path.of(it) }
                .firstOrNull { it.isAbsolute }

        private val defaultHome by lazy {
            val shellEnv = EnvironmentUtil.getEnvironmentMap()
            val userHome = System.getProperty("user.home")
            ClaudeHome(resolveRoot(shellEnv, System.getenv(), userHome), resolveConfigFile(shellEnv, System.getenv(), userHome))
        }

        /** The user's Claude home. Resolving it may wait for the shell environment, so call it off the EDT. */
        fun default(): ClaudeHome = defaultHome
    }
}
