package io.github.v0rest.skein.claude

import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** What Claude Code's `worktree.baseRef` setting says a new worktree starts from. */
enum class BaseRef {
    /** The repository's default branch on the remote (`origin/HEAD`), Claude Code's default. */
    FRESH,

    /** The local `HEAD` of the repository, with its unpushed commits. */
    HEAD,
}

/**
 * Reads `worktree.baseRef` from the settings files Claude Code's public docs name (project local, project, user), only
 * ever for reading. Managed settings and `--settings` are not read, so a base they set is not seen and the default
 * counts. Never throws. A file that is missing, too big, unreadable or not strict JSON (a comment, a trailing comma,
 * single quotes and unquoted names all count) is skipped, since Claude Code skips such a file too.
 */
object WorktreeBaseRef {
    private const val MAX_BYTES = 1 shl 20

    /**
     * The setting in force for a worktree thread whose project settings live in [repository], the main checkout's root
     * when the thread starts from a linked worktree. It is the first of its settings files that names a known value.
     */
    fun read(home: ClaudeHome, repository: Path): BaseRef {
        val project = Worktrees.projectSettingsFiles(repository.toString()).map { readFile(Path.of(it)) }
        val user = home.readText(home.root.resolve("settings.json"))
        return of(project + user)
    }

    /** The first value among [texts] (settings files, the winning one first) that is `head` or `fresh`, else [BaseRef.FRESH]. */
    fun of(texts: List<String?>): BaseRef {
        for (text in texts) {
            val value = text?.let(::parseStrict)?.let { it.get("worktree") as? JsonObject }?.stringOrNull("baseRef")
            when (value) {
                "head" -> return BaseRef.HEAD
                "fresh" -> return BaseRef.FRESH
            }
        }
        return BaseRef.FRESH
    }

    private fun parseStrict(text: String): JsonObject? =
        try {
            val reader = JsonReader(StringReader(text)).apply { strictness = Strictness.STRICT }
            val value = JsonParser.parseReader(reader)
            if (reader.peek() == JsonToken.END_DOCUMENT) value as? JsonObject else null
        } catch (e: JsonParseException) {
            null
        } catch (e: java.io.IOException) {
            null
        }

    private fun readFile(path: Path): String? =
        try {
            if (Files.isRegularFile(path) && Files.size(path) <= MAX_BYTES) Files.readString(path) else null
        } catch (e: IOException) {
            null
        } catch (e: RuntimeException) { // malformed UTF-8
            null
        }
}
